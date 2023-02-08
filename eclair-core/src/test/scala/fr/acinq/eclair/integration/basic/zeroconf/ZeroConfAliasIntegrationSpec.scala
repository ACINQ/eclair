package fr.acinq.eclair.integration.basic.zeroconf

import akka.testkit.TestProbe
import com.softwaremill.quicklens._
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{ScidAlias, ZeroConf}
import fr.acinq.eclair.channel.{DATA_NORMAL, RealScidStatus}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.integration.basic.fixtures.composite.ThreeNodesFixture
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.RouteNotFound
import fr.acinq.eclair.router.Router.{FinalizeRoute, PredefinedNodeRoute, RouteResponse}
import fr.acinq.eclair.testutils.FixtureSpec
import fr.acinq.eclair.wire.protocol.{FailureMessage, UnknownNextPeer, Update}
import fr.acinq.eclair.{MilliSatoshiLong, RealShortChannelId, ShortChannelId}
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.{Tag, TestData}
import scodec.bits.HexStringSyntax

class ZeroConfAliasIntegrationSpec extends FixtureSpec with IntegrationPatience {

  type FixtureParam = ThreeNodesFixture

  val ZeroConfBobCarol = "zeroconf_bob_carol"
  val ScidAliasBobCarol = "scid_alias_bob_carol"
  val PublicBobCarol = "public_bob_carol"

  import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture.{connect, getChannelData, getRouterData, knownFundingTxs, nodeParamsFor, openChannel, sendFailingPayment, sendPayment, sendSuccessfulPayment, watcherAutopilot}

  override def createFixture(testData: TestData): FixtureParam = {
    // seeds have been chosen so that node ids start with 02aaaa for alice, 02bbbb for bob, etc.
    val aliceParams = nodeParamsFor("alice", ByteVector32(hex"b4acd47335b25ab7b84b8c020997b12018592bb4631b868762154d77fa8b93a3"))
    val bobParams = nodeParamsFor("bob", ByteVector32(hex"7620226fec887b0b2ebe76492e5a3fd3eb0e47cd3773263f6a81b59a704dc492"))
      .modify(_.features.activated).using(_ - ZeroConf - ScidAlias) // we will enable those features on demand
      .modify(_.features.activated).usingIf(testData.tags.contains(ZeroConfBobCarol))(_ + (ZeroConf -> Optional))
      .modify(_.features.activated).usingIf(testData.tags.contains(ScidAliasBobCarol))(_ + (ScidAlias -> Optional))
      .modify(_.channelConf.channelFlags.announceChannel).setTo(testData.tags.contains(PublicBobCarol))
    val carolParams = nodeParamsFor("carol", ByteVector32(hex"ebd5a5d3abfb3ef73731eb3418d918f247445183180522674666db98a66411cc"))
      .modify(_.features.activated).using(_ - ZeroConf - ScidAlias) // we will enable those features on demand
      .modify(_.features.activated).usingIf(testData.tags.contains(ZeroConfBobCarol))(_ + (ZeroConf -> Mandatory))
      .modify(_.features.activated).usingIf(testData.tags.contains(ScidAliasBobCarol))(_ + (ScidAlias -> Mandatory))
      .modify(_.channelConf.channelFlags.announceChannel).setTo(testData.tags.contains(PublicBobCarol))
    ThreeNodesFixture(aliceParams, bobParams, carolParams, testData.name)
  }

  override def cleanupFixture(fixture: FixtureParam): Unit = {
    fixture.cleanup()
  }

  private def createChannels(f: FixtureParam)(deepConfirm: Boolean): (ByteVector32, ByteVector32) = {
    import f._

    alice.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol), deepConfirm = deepConfirm))
    bob.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol), deepConfirm = deepConfirm))
    carol.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol), deepConfirm = deepConfirm))

    connect(alice, bob)
    connect(bob, carol)

    val channelId_ab = openChannel(alice, bob, 100_000 sat).channelId
    val channelId_bc = openChannel(bob, carol, 100_000 sat).channelId

    (channelId_ab, channelId_bc)
  }

  private def createBobToCarolTestHint(f: FixtureParam, useHint: Boolean, overrideHintScid_opt: Option[RealShortChannelId]): List[ExtraHop] = {
    import f._
    if (useHint) {
      val Some(carolHint) = getRouterData(carol).privateChannels.values.head.toIncomingExtraHop
      // due to how node ids are built, bob < carol so carol is always the node 2
      val bobAlias = getRouterData(bob).privateChannels.values.find(_.nodeId2 == carol.nodeParams.nodeId).value.shortIds.localAlias
      // the hint is always using the alias
      assert(carolHint.shortChannelId == bobAlias)
      List(carolHint.modify(_.shortChannelId).setToIfDefined(overrideHintScid_opt))
    } else {
      Nil
    }
  }

  case object Ok

  private def sendPaymentAliceToCarol(f: FixtureParam, expected: Either[Either[Throwable, FailureMessage], Ok.type], useHint: Boolean = false, overrideHintScid_opt: Option[RealShortChannelId] = None): Unit = {
    import f._
    val result = sendPayment(alice, carol, 100_000 msat, hints = List(createBobToCarolTestHint(f, useHint, overrideHintScid_opt))) match {
      case Left(paymentFailed) =>
        Left(PaymentFailure.transformForUser(paymentFailed.failures).last match {
          case LocalFailure(_, _, t) => Left(t)
          case RemoteFailure(_, _, e) => Right(e.failureMessage)
          case _: UnreadableRemoteFailure => fail("received unreadable remote failure")
        })
      case Right(_) => Right(Ok)
    }

    assert(result == expected)
  }

  private def createSelfRouteCarol(f: FixtureParam, scid_bc: ShortChannelId): Unit = {
    import f._
    val sender = TestProbe("sender")
    sender.send(carol.router, FinalizeRoute(PredefinedNodeRoute(50_000 msat, Seq(bob.nodeId, carol.nodeId))))
    val route = sender.expectMsgType[RouteResponse].routes.head
    assert(route.hops.length == 1)
    assert(route.hops.map(_.nodeId) == Seq(bob.nodeId))
    assert(route.hops.map(_.nextNodeId) == Seq(carol.nodeId))
    assert(route.hops.map(_.shortChannelId) == Seq(scid_bc))
  }

  private def internalTest(f: FixtureParam,
                           deepConfirm: Boolean,
                           bcPublic: Boolean,
                           bcZeroConf: Boolean,
                           bcScidAlias: Boolean,
                           paymentWithoutHint: Either[Either[Throwable, FailureMessage], Ok.type],
                           paymentWithHint_opt: Option[Either[Either[Throwable, FailureMessage], Ok.type]],
                           paymentWithRealScidHint_opt: Option[Either[Either[Throwable, FailureMessage], Ok.type]]): Unit = {
    import f._

    val (_, channelId_bc) = createChannels(f)(deepConfirm = deepConfirm)

    eventually {
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures.features.contains(ZeroConf) == bcZeroConf)
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures.features.contains(ScidAlias) == bcScidAlias)
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.params.channelFlags.announceChannel == bcPublic)
      if (deepConfirm) {
        assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].shortIds.real.isInstanceOf[RealScidStatus.Final])
      } else if (bcZeroConf) {
        assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].shortIds.real == RealScidStatus.Unknown)
      } else {
        assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].shortIds.real.isInstanceOf[RealScidStatus.Temporary])
      }
    }

    eventually {
      if (bcPublic && deepConfirm) {
        // if channel bob-carol is public, we wait for alice to learn about it
        val data = getRouterData(alice)
        assert(data.channels.size == 2)
        assert(data.channels.values.forall(pc => pc.update_1_opt.isDefined && pc.update_2_opt.isDefined))
      }
    }

    eventually {
      sendPaymentAliceToCarol(f, paymentWithoutHint)
    }

    paymentWithHint_opt.foreach { paymentWithHint =>
      eventually {
        sendPaymentAliceToCarol(f, paymentWithHint, useHint = true)
      }
    }

    paymentWithRealScidHint_opt.foreach { paymentWithRealScidHint =>
      eventually {
        sendPaymentAliceToCarol(f, paymentWithRealScidHint, useHint = true, overrideHintScid_opt = Some(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].shortIds.real.toOption.value))
      }
    }

    eventually {
      if (deepConfirm) {
        val scidsBob = getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].shortIds
        val scid_bc = if (bcPublic) scidsBob.real.toOption.get else scidsBob.localAlias
        createSelfRouteCarol(f, scid_bc)
      }
    }
  }

  test("a->b->c (b-c private)") { f =>
    internalTest(f,
      deepConfirm = true,
      bcPublic = false,
      bcZeroConf = false,
      bcScidAlias = false,
      paymentWithoutHint = Left(Left(RouteNotFound)), // alice can't find a route to carol because bob-carol isn't announced
      paymentWithHint_opt = Some(Right(Ok)), // with a routing hint the payment works (and it will use the alias, even if the feature isn't enabled)
      paymentWithRealScidHint_opt = Some(Right(Ok)) // if alice uses the real scid instead of the bob-carol alias, it still works
    )
  }

  test("a->b->c (b-c scid-alias private)", Tag(ScidAliasBobCarol)) { f =>
    internalTest(f,
      deepConfirm = true,
      bcPublic = false,
      bcZeroConf = false,
      bcScidAlias = true,
      paymentWithoutHint = Left(Left(RouteNotFound)), // alice can't find a route to carol because bob-carol isn't announced
      paymentWithHint_opt = Some(Right(Ok)), // with a routing hint the payment works
      paymentWithRealScidHint_opt = Some(Left(Right(UnknownNextPeer()))) // if alice uses the real scid instead of the bob-carol alias, it doesn't work due to option_scid_alias
    )
  }

  test("a->b->c (b-c zero-conf unconfirmed private)", Tag(ZeroConfBobCarol)) { f =>
    internalTest(f,
      deepConfirm = false,
      bcPublic = false,
      bcZeroConf = true,
      bcScidAlias = false,
      paymentWithoutHint = Left(Left(RouteNotFound)), // alice can't find a route to carol because bob-carol isn't announced
      paymentWithHint_opt = Some(Right(Ok)), // with a routing hint the payment works
      paymentWithRealScidHint_opt = None // there is no real scid for bob-carol yet
    )
  }

  test("a->b->c (b-c zero-conf deeply confirmed private)", Tag(ZeroConfBobCarol)) { f =>
    internalTest(f,
      deepConfirm = true,
      bcPublic = false,
      bcZeroConf = true,
      bcScidAlias = false,
      paymentWithoutHint = Left(Left(RouteNotFound)), // alice can't find a route to carol because bob-carol isn't announced
      paymentWithHint_opt = Some(Right(Ok)), // with a routing hint the payment works
      // TODO: we should be able to send payments with the real scid in the routing hint, but this currently doesn't work,
      //  because the ChannelRelayer relies on the the LocalChannelUpdate event to maintain its scid resolution map, and
      //  the channel doesn't emit a new one when a real scid is assigned, because we use the remote alias for the
      //  channel_update, not the real scid. So the channel_update remains the same. We used to have the ChannelRelayer
      //  also listen to ShortChannelIdAssigned event, but it's doesn't seem worth it here.
      paymentWithRealScidHint_opt = None
    )
  }

  test("a->b->c (b-c zero-conf scid-alias deeply confirmed private)", Tag(ZeroConfBobCarol), Tag(ScidAliasBobCarol)) { f =>
    internalTest(f,
      deepConfirm = true,
      bcPublic = false,
      bcZeroConf = true,
      bcScidAlias = true,
      paymentWithoutHint = Left(Left(RouteNotFound)), // alice can't find a route to carol because bob-carol isn't announced
      paymentWithHint_opt = Some(Right(Ok)), // with a routing hint the payment works
      paymentWithRealScidHint_opt = Some(Left(Right(UnknownNextPeer()))) // if alice uses the real scid instead of the b-c alias, it doesn't work due to option_scid_alias
    )
  }

  test("a->b->c (b-c zero-conf unconfirmed public)", Tag(ZeroConfBobCarol), Tag(PublicBobCarol)) { f =>
    internalTest(f,
      deepConfirm = false,
      bcPublic = true,
      bcZeroConf = true,
      bcScidAlias = false,
      paymentWithoutHint = Left(Left(RouteNotFound)), // alice can't find a route to carol because bob-carol isn't announced yet
      paymentWithHint_opt = Some(Right(Ok)), // with a routing hint the payment works
      paymentWithRealScidHint_opt = None // there is no real scid for bob-carol yet
    )
  }

  test("a->b->c (b-c zero-conf deeply confirmed public)", Tag(ZeroConfBobCarol), Tag(PublicBobCarol)) { f =>
    internalTest(f,
      deepConfirm = true,
      bcPublic = true,
      bcZeroConf = true,
      bcScidAlias = false,
      paymentWithoutHint = Right(Ok),
      paymentWithHint_opt = None, // there is no routing hints for public channels
      paymentWithRealScidHint_opt = None // there is no routing hints for public channels
    )
  }

  test("temporary channel failures don't leak the real scid", Tag(ScidAliasBobCarol), Tag(ZeroConfBobCarol)) { f =>
    import f._

    val (_, channelId_bc) = createChannels(f)(deepConfirm = false)

    eventually {
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures.features.contains(ZeroConf))
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures.features.contains(ScidAlias))
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].shortIds.real == RealScidStatus.Unknown)
      assert(getRouterData(bob).privateChannels.values.exists(_.nodeId2 == carol.nodeParams.nodeId))
    }

    val Some(carolHint) = getRouterData(carol).privateChannels.values.head.toIncomingExtraHop
    val bobAlias = getRouterData(bob).privateChannels.values.find(_.nodeId2 == carol.nodeParams.nodeId).value.shortIds.localAlias
    assert(carolHint.shortChannelId == bobAlias)

    // We make sure Bob won't have enough liquidity to relay another payment.
    sendSuccessfulPayment(bob, carol, 35_000_000 msat)
    sendSuccessfulPayment(bob, carol, 25_000_000 msat)

    // The channel update returned in failures doesn't leak the real scid.
    val failure = sendFailingPayment(alice, carol, 40_000_000 msat, hints = List(List(carolHint)))
    val failureWithChannelUpdate = failure.failures.collect { case RemoteFailure(_, _, Sphinx.DecryptedFailurePacket(_, f: Update)) => f }
    assert(failureWithChannelUpdate.length == 1)
    assert(failureWithChannelUpdate.head.update.shortChannelId == bobAlias)
  }

}
