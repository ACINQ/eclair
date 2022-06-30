package fr.acinq.eclair.integration.basic

import com.softwaremill.quicklens._
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{ScidAlias, ZeroConf}
import fr.acinq.eclair.channel.{DATA_NORMAL, RealScidStatus}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.integration.basic.fixtures.ThreeNodesFixture
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.send.PaymentError.RetryExhausted
import fr.acinq.eclair.router.RouteNotFound
import fr.acinq.eclair.testutils.FixtureSpec
import fr.acinq.eclair.wire.protocol.{UnknownNextPeer, Update}
import fr.acinq.eclair.{MilliSatoshiLong, RealShortChannelId}
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.{Tag, TestData}
import scodec.bits.HexStringSyntax

class ZeroConfAliasIntegrationSpec extends FixtureSpec with IntegrationPatience {

  type FixtureParam = ThreeNodesFixture

  val ZeroConfBobCarol = "zeroconf_bob_carol"
  val ScidAliasBobCarol = "scid_alias_bob_carol"
  val PublicBobCarol = "public_bob_carol"

  import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture._

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
    ThreeNodesFixture(aliceParams, bobParams, carolParams)
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

  private def createBobToCarolTestHint(f: FixtureParam, useHint: Boolean, overrideHintScid_opt: Option[RealShortChannelId]): Seq[ExtraHop] = {
    import f._
    if (useHint) {
      val Some(carolHint) = getRouterData(carol).privateChannels.values.head.toIncomingExtraHop
      // due to how node ids are built, bob < carol so carol is always the node 2
      val bobAlias = getRouterData(bob).privateChannels.values.find(_.nodeId2 == carol.nodeParams.nodeId).value.shortIds.localAlias
      // the hint is always using the alias
      assert(carolHint.shortChannelId == bobAlias)
      Seq(carolHint.modify(_.shortChannelId).setToIfDefined(overrideHintScid_opt))
    } else {
      Seq.empty
    }
  }

  private def sendSuccessfulPaymentAliceToCarol(f: FixtureParam, useHint: Boolean = false, overrideHintScid_opt: Option[RealShortChannelId] = None): PaymentSent = {
    import f._
    sendSuccessfulPayment(alice, carol, 100_000 msat, hints = Seq(createBobToCarolTestHint(f, useHint, overrideHintScid_opt)))
  }

  private def sendFailingPaymentAliceToCarol(f: FixtureParam, useHint: Boolean = false, overrideHintScid_opt: Option[RealShortChannelId] = None): PaymentFailed = {
    import f._
    sendFailingPayment(alice, carol, 100_000 msat, hints = Seq(createBobToCarolTestHint(f, useHint, overrideHintScid_opt)))
  }

  private def internalTest(f: FixtureParam,
                           deepConfirm: Boolean,
                           bcPublic: Boolean,
                           bcZeroConf: Boolean,
                           bcScidAlias: Boolean,
                           paymentWorksWithoutHint: Boolean,
                           paymentWorksWithHint_opt: Option[Boolean],
                           paymentWorksWithRealScidHint_opt: Option[Boolean]): Unit = {
    import f._

    val (_, channelId_bc) = createChannels(f)(deepConfirm = deepConfirm)

    eventually {
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ZeroConf) == bcZeroConf)
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ScidAlias) == bcScidAlias)
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFlags.announceChannel == bcPublic)
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

    def validateFailure(failure: PaymentFailed): Unit = {
      failure.failures.foreach {
        case LocalFailure(_, _, t) => assert(t == RouteNotFound || t == RetryExhausted)
        case RemoteFailure(_, _, e) => assert(e.failureMessage == UnknownNextPeer)
        case _: UnreadableRemoteFailure => fail("received unreadable remote failure")
      }
    }

    eventually {
      if (paymentWorksWithoutHint) {
        sendSuccessfulPaymentAliceToCarol(f)
      } else {
        val failure = sendFailingPaymentAliceToCarol(f)
        validateFailure(failure)
      }
    }

    eventually {
      paymentWorksWithHint_opt match {
        case Some(true) => sendSuccessfulPaymentAliceToCarol(f, useHint = true)
        case Some(false) =>
          val failure = sendFailingPaymentAliceToCarol(f, useHint = true)
          validateFailure(failure)
        case None => // skipped
      }
    }

    eventually {
      paymentWorksWithRealScidHint_opt match {
        // if alice uses the real scid instead of the bob-carol alias, it still works
        case Some(true) => sendSuccessfulPaymentAliceToCarol(f, useHint = true, overrideHintScid_opt = Some(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].shortIds.real.toOption.value))
        case Some(false) =>
          val failure = sendFailingPaymentAliceToCarol(f, useHint = true, overrideHintScid_opt = Some(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].shortIds.real.toOption.value))
          validateFailure(failure)
        case None => // skipped
      }
    }
  }

  test("a->b->c (b-c private)") { f =>
    internalTest(f,
      deepConfirm = true,
      bcPublic = false,
      bcZeroConf = false,
      bcScidAlias = false,
      paymentWorksWithoutHint = false, // alice can't find a route to carol because bob-carol isn't announced
      paymentWorksWithHint_opt = Some(true), // with a routing hint the payment works (and it will use the alias, even if the feature isn't enabled)
      paymentWorksWithRealScidHint_opt = Some(true) // if alice uses the real scid instead of the bob-carol alias, it still works
    )
  }

  test("a->b->c (b-c scid-alias private)", Tag(ScidAliasBobCarol)) { f =>
    internalTest(f,
      deepConfirm = true,
      bcPublic = false,
      bcZeroConf = false,
      bcScidAlias = true,
      paymentWorksWithoutHint = false, // alice can't find a route to carol because bob-carol isn't announced
      paymentWorksWithHint_opt = Some(true), // with a routing hint the payment works
      paymentWorksWithRealScidHint_opt = Some(false) // if alice uses the real scid instead of the bob-carol alias, it doesn't work due to option_scid_alias
    )
  }

  test("a->b->c (b-c zero-conf unconfirmed private)", Tag(ZeroConfBobCarol)) { f =>
    internalTest(f,
      deepConfirm = false,
      bcPublic = false,
      bcZeroConf = true,
      bcScidAlias = false,
      paymentWorksWithoutHint = false, // alice can't find a route to carol because bob-carol isn't announced
      paymentWorksWithHint_opt = Some(true), // with a routing hint the payment works
      paymentWorksWithRealScidHint_opt = None // there is no real scid for bob-carol yet
    )
  }

  test("a->b->c (b-c zero-conf deeply confirmed private)", Tag(ZeroConfBobCarol)) { f =>
    internalTest(f,
      deepConfirm = true,
      bcPublic = false,
      bcZeroConf = true,
      bcScidAlias = false,
      paymentWorksWithoutHint = false, // alice can't find a route to carol because bob-carol isn't announced
      paymentWorksWithHint_opt = Some(true), // with a routing hint the payment works
      // TODO: we should be able to send payments with the real scid in the routing hint, but this currently doesn't work,
      //  because the ChannelRelayer relies on the the LocalChannelUpdate event to maintain its scid resolution map, and
      //  the channel doesn't emit a new one when a real scid is assigned, because we use the remote alias for the
      //  channel_update, not the real scid. So the channel_update remains the same. We used to have the ChannelRelayer
      //  also listen to ShortChannelIdAssigned event, but it's doesn't seem worth it here.
      paymentWorksWithRealScidHint_opt = None
    )
  }

  test("a->b->c (b-c zero-conf scid-alias deeply confirmed private)", Tag(ZeroConfBobCarol), Tag(ScidAliasBobCarol)) { f =>
    internalTest(f,
      deepConfirm = true,
      bcPublic = false,
      bcZeroConf = true,
      bcScidAlias = true,
      paymentWorksWithoutHint = false, // alice can't find a route to carol because bob-carol isn't announced
      paymentWorksWithHint_opt = Some(true), // with a routing hint the payment works
      paymentWorksWithRealScidHint_opt = Some(false) // if alice uses the real scid instead of the b-c alias, it doesn't work due to option_scid_alias
    )
  }

  test("a->b->c (b-c zero-conf unconfirmed public)", Tag(ZeroConfBobCarol), Tag(PublicBobCarol)) { f =>
    internalTest(f,
      deepConfirm = false,
      bcPublic = true,
      bcZeroConf = true,
      bcScidAlias = false,
      paymentWorksWithoutHint = false, // alice can't find a route to carol because bob-carol isn't announced yet
      paymentWorksWithHint_opt = Some(true), // with a routing hint the payment works
      paymentWorksWithRealScidHint_opt = None // there is no real scid for bob-carol yet
    )
  }

  test("a->b->c (b-c zero-conf deeply confirmed public)", Tag(ZeroConfBobCarol), Tag(PublicBobCarol)) { f =>
    internalTest(f,
      deepConfirm = true,
      bcPublic = true,
      bcZeroConf = true,
      bcScidAlias = false,
      paymentWorksWithoutHint = true,
      paymentWorksWithHint_opt = None, // there is no routing hints for public channels
      paymentWorksWithRealScidHint_opt = None // there is no routing hints for public channels
    )
  }

  test("temporary channel failures don't leak the real scid", Tag(ScidAliasBobCarol), Tag(ZeroConfBobCarol)) { f =>
    import f._

    val (_, channelId_bc) = createChannels(f)(deepConfirm = false)

    eventually {
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ZeroConf))
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ScidAlias))
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].shortIds.real == RealScidStatus.Unknown)
      assert(getRouterData(bob).privateChannels.values.exists(_.nodeId2 == carol.nodeParams.nodeId))
    }

    val Some(carolHint) = getRouterData(carol).privateChannels.values.head.toIncomingExtraHop
    val bobAlias = getRouterData(bob).privateChannels.values.find(_.nodeId2 == carol.nodeParams.nodeId).value.shortIds.localAlias
    assert(carolHint.shortChannelId == bobAlias)

    // We make sure Bob won't have enough liquidity to relay another payment.
    sendSuccessfulPayment(bob, carol, 60_000_000 msat)

    // The channel update returned in failures doesn't leak the real scid.
    val failure = sendFailingPayment(alice, carol, 50_000_000 msat, hints = Seq(Seq(carolHint)))
    val failureWithChannelUpdate = failure.failures.collect { case RemoteFailure(_, _, Sphinx.DecryptedFailurePacket(_, f: Update)) => f }
    assert(failureWithChannelUpdate.length == 1)
    assert(failureWithChannelUpdate.head.update.shortChannelId == bobAlias)
  }

}
