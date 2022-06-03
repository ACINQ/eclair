package fr.acinq.eclair.integration.basic

import com.softwaremill.quicklens._
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{ScidAlias, ZeroConf}
import fr.acinq.eclair.channel.DATA_NORMAL
import fr.acinq.eclair.integration.basic.fixtures.ThreeNodesFixture
import fr.acinq.eclair.payment.PaymentSent
import fr.acinq.eclair.testutils.FixtureSpec
import fr.acinq.eclair.wire.protocol.{ChannelReady, ChannelReadyTlv}
import fr.acinq.eclair.{MilliSatoshiLong, RealShortChannelId}
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

  private def createChannels(f: FixtureParam)(deepConfirm: Boolean, stripAliasFromCarol: Boolean = false): (ByteVector32, ByteVector32) = {
    import f._

    alice.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol), deepConfirm = deepConfirm))
    bob.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol), deepConfirm = deepConfirm))
    carol.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol), deepConfirm = deepConfirm))

    connect(alice, bob)
    connect(bob, carol, mutate21 = {
      case channelReady: ChannelReady if stripAliasFromCarol =>
        channelReady.modify(_.tlvStream.records).using(_.filter { case _: ChannelReadyTlv.ShortChannelIdTlv => false; case _ => true })
      case other => other
    })

    val channelId_ab = openChannel(alice, bob, 100_000 sat).channelId
    val channelId_bc = openChannel(bob, carol, 100_000 sat).channelId

    (channelId_ab, channelId_bc)
  }

  private def sendPaymentAliceToCarol(f: FixtureParam, useHint: Boolean = false, overrideHintScid_opt: Option[RealShortChannelId] = None): PaymentSent = {
    import f._
    val hint = if (useHint) {
      val Some(hint) = getRouterData(carol).privateChannels.values.head.toIncomingExtraHop
      Seq(hint.modify(_.shortChannelId).setToIfDefined(overrideHintScid_opt))
    } else Seq.empty
    sendPayment(alice, carol, 100_000 msat, hints = Seq(hint))
  }

  test("a->b->c (b-c private)") { f =>
    import f._

    val (channelId_ab, channelId_bc) = createChannels(f)(deepConfirm = false)

    eventually {
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ZeroConf))
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ScidAlias))
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFlags.announceChannel)
      // a-b and b-c are in NORMAL and have real scids (the funding tx has reached min_depth)
      assert(getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(carol, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
    }

    // alice can't find a route to carol because b-c isn't announced
    intercept[AssertionError] {
      sendPaymentAliceToCarol(f)
    }

    // with a routing hint the payment works
    sendPaymentAliceToCarol(f, useHint = true)
    // NB: the default hints use bob's alias, even id scid alias isn't enabled, because eclair always sends and understands aliases
    assert(getRouterData(carol).privateChannels.values.head.toIncomingExtraHop.get.shortChannelId ==
      getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].localAlias)

    // if alice uses the real scid instead of the b-c alias, it still works
    sendPaymentAliceToCarol(f, useHint = true, overrideHintScid_opt = Some(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.get))
  }

  test("a->b->c (b-c scid-alias private)", Tag(ScidAliasBobCarol)) { f =>
    import f._

    val (channelId_ab, channelId_bc) = createChannels(f)(deepConfirm = false)

    eventually {
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ZeroConf))
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ScidAlias))
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFlags.announceChannel)
      // a-b and b-c are in NORMAL and have real scids (the funding tx has reached min_depth)
      assert(getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(carol, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
    }

    // alice can't find a route to carol because b-c isn't announced
    intercept[AssertionError] {
      sendPaymentAliceToCarol(f)
    }

    // with a routing hint the payment works
    sendPaymentAliceToCarol(f, useHint = true)
    // NB: the default hints use bob's alias, even id scid alias isn't enabled, because eclair always sends and understands aliases
    assert(getRouterData(carol).privateChannels.values.head.toIncomingExtraHop.get.shortChannelId ==
      getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].localAlias)

    // if alice uses the real scid instead of the b-c alias, it doesn't work due to option_scid_alias
    intercept[AssertionError] {
      sendPaymentAliceToCarol(f, useHint = true, overrideHintScid_opt = Some(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.get))
    }
  }

  test("a->b->c (b-c zero-conf unconfirmed private)", Tag(ZeroConfBobCarol)) { f =>
    import f._

    val (channelId_ab, channelId_bc) = createChannels(f)(deepConfirm = false)

    eventually {
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ZeroConf))
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ScidAlias))
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFlags.announceChannel)
      // a-b is in NORMAL, the funding tx has reach min_depth
      assert(getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      // b-c is in NORMAL too, but the funding tx isn't confirmed (zero-conf): it doesn't have a real scid
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isEmpty)
      assert(getChannelData(carol, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isEmpty)
    }

    // alice can't find a route to carol because b-c isn't announced
    intercept[AssertionError] {
      sendPaymentAliceToCarol(f)
    }

    // with a routing hint the payment works
    sendPaymentAliceToCarol(f, useHint = true)
  }

  test("a->b->c (b-c zero-conf unconfirmed private, no alias from carol)", Tag(ZeroConfBobCarol)) { f =>
    import f._

    val (channelId_ab, channelId_bc) = createChannels(f)(deepConfirm = false, stripAliasFromCarol = true)

    eventually {
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ZeroConf))
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ScidAlias))
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFlags.announceChannel)
      // a-b is in NORMAL, the funding tx has reach min_depth
      assert(getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      // b-c is in NORMAL too, but the funding tx isn't confirmed (zero-conf): it doesn't have a real scid
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isEmpty)
      assert(getChannelData(carol, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isEmpty)
    }

    // alice can't find a route to carol because b-c isn't announced
    intercept[AssertionError] {
      sendPaymentAliceToCarol(f)
    }

    // carol don't have hints, because bob sent her a channel_update with scid=0, which carol couldn't attribute to a channel
    assert(getRouterData(carol).privateChannels.values.head.toIncomingExtraHop.isEmpty)
  }

  test("a->b->c (b-c zero-conf scid-alias unconfirmed private, no alias from carol)", Tag(ZeroConfBobCarol), Tag(ScidAliasBobCarol)) { f =>
    import f._

    val (channelId_ab, channelId_bc) = createChannels(f)(deepConfirm = false, stripAliasFromCarol = true)

    eventually {
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ZeroConf))
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ScidAlias))
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFlags.announceChannel)
      // a-b is in NORMAL, the funding tx has reach min_depth
      assert(getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      // b-c is in NORMAL too, but the funding tx isn't confirmed (zero-conf): it doesn't have a real scid
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isEmpty)
      assert(getChannelData(carol, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isEmpty)
    }

    // alice can't find a route to carol because b-c isn't announced
    intercept[AssertionError] {
      sendPaymentAliceToCarol(f)
    }

    // carol don't have hints, because bob sent her a channel_update with scid=0, which carol couldn't attribute to a channel
    assert(getRouterData(carol).privateChannels.values.head.toIncomingExtraHop.isEmpty)
  }

  test("a->b->c (b-c zero-conf deeply confirmed private)", Tag(ZeroConfBobCarol)) { f =>
    import f._

    val (channelId_ab, channelId_bc) = createChannels(f)(deepConfirm = true)

    eventually {
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ZeroConf))
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ScidAlias))
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFlags.announceChannel)
      // both channels have real scids because they are deeply confirmed, even the zeroconf channel
      assert(getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(carol, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
    }

    // alice can't find a route to carol because b-c isn't announced
    intercept[AssertionError] {
      sendPaymentAliceToCarol(f)
    }

    // with a routing hint the payment works
    sendPaymentAliceToCarol(f, useHint = true)

    // if alice uses the real scid instead of the b-c alias, it still works
    // TODO  This actually doesn't work, because the ChannelRelayer relies on the the LocalChannelUpdate event to maintain
    // TODO  its scid resolution map, and the channel doesn't emit a new one when a real scid is assigned, because we use the
    // TODO  remote alias for the channel_update, not the real scid. So the channel_update remains the same. We used to
    // TODO  have the ChannelRelayer also listen to ShortChannelIdAssigned event, but it's doesn't seem worth it here.
    //sendPaymentAliceToCarol(f, useHint = true, overrideHintScid_opt = Some(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.get))
  }

  test("a->b->c (b-c zero-conf scid-alias deeply confirmed private)", Tag(ZeroConfBobCarol), Tag(ScidAliasBobCarol)) { f =>
    import f._

    val (channelId_ab, channelId_bc) = createChannels(f)(deepConfirm = true)

    eventually {
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ZeroConf))
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ScidAlias))
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFlags.announceChannel)
      // both channels have real scids because they are deeply confirmed, even the zeroconf channel
      assert(getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(carol, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
    }

    // alice can't find a route to carol because b-c isn't announced
    intercept[AssertionError] {
      sendPaymentAliceToCarol(f)
    }

    // with a routing hint the payment works
    sendPaymentAliceToCarol(f, useHint = true)

    // if alice uses the real scid instead of the b-c alias, it doesn't work due to option_scid_alias
    intercept[AssertionError] {
      sendPaymentAliceToCarol(f, useHint = true, overrideHintScid_opt = Some(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.get))
    }
  }

  test("a->b->c (b-c zero-conf deeply confirmed private, no alias from carol)", Tag(ZeroConfBobCarol)) { f =>
    import f._

    val (channelId_ab, channelId_bc) = createChannels(f)(deepConfirm = true, stripAliasFromCarol = true)

    eventually {
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ZeroConf))
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ScidAlias))
      // both channels have real scids because they are deeply confirmed, even the zeroconf channel
      assert(getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(carol, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
    }

    // alice can't find a route to carol because b-c isn't announced
    intercept[AssertionError] {
      sendPaymentAliceToCarol(f)
    }

    // carol is able to give routing hints from bob, because bob has sent a new channel_update using the real scid
    val Some(hint) = getRouterData(carol).privateChannels.values.head.toIncomingExtraHop
    // NB: in this convoluted scenario where carol has actually sent her alias but we have stripped it midflight,
    // she will still understand bob's alias and use it in her routing hint
    assert(hint.shortChannelId == getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].localAlias)
    // with a routing hint the payment works
    sendPaymentAliceToCarol(f, useHint = true)

    // if alice uses the real scid instead of the b-c alias, it still works
    sendPaymentAliceToCarol(f, useHint = true, overrideHintScid_opt = Some(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.get))
  }

  test("a->b->c (b-c zero-conf scid-alias deeply confirmed private, no alias from carol)", Tag(ZeroConfBobCarol), Tag(ScidAliasBobCarol)) { f =>
    import f._

    val (channelId_ab, channelId_bc) = createChannels(f)(deepConfirm = true, stripAliasFromCarol = true)

    eventually {
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ZeroConf))
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ScidAlias))
      // both channels have real scids because they are deeply confirmed, even the zeroconf channel
      assert(getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(carol, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
    }

    // alice can't find a route to carol because b-c isn't announced
    intercept[AssertionError] {
      sendPaymentAliceToCarol(f)
    }

    eventually {
      // carol is able to give routing hints from bob, because bob has sent a new channel_update using the real scid
      val Some(hint) = getRouterData(carol).privateChannels.values.head.toIncomingExtraHop
      // NB: in this convoluted scenario where carol has actually sent her alias but we have stripped it midflight,
      // she will still understand bob's alias and use it in her routing hint
      assert(hint.shortChannelId == getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].localAlias)
    }
    // with a routing hint the payment works
    sendPaymentAliceToCarol(f, useHint = true)

    // if alice uses the real scid instead of the b-c alias, it doesn't work due to option_scid_alias
    intercept[AssertionError] {
      sendPaymentAliceToCarol(f, useHint = true, overrideHintScid_opt = Some(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.get))
    }
  }

  test("a->b->c (b-c zero-conf unconfirmed public)", Tag(ZeroConfBobCarol), Tag(PublicBobCarol)) { f =>
    import f._

    val (channelId_ab, channelId_bc) = createChannels(f)(deepConfirm = false)

    eventually {
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ZeroConf))
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ScidAlias))
      // a-b is in NORMAL, the funding tx has reach min_depth
      assert(getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      // b-c is in NORMAL too, but the funding tx isn't confirmed (zero-conf): it doesn't have a real scid
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isEmpty)
      assert(getChannelData(carol, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isEmpty)
    }

    // alice can't find a route to carol because b-c isn't announced
    intercept[AssertionError] {
      sendPaymentAliceToCarol(f)
    }

    // with a routing hint the payment works
    sendPaymentAliceToCarol(f, useHint = true)
  }

  test("a->b->c (b-c zero-conf deeply confirmed public)", Tag(ZeroConfBobCarol), Tag(PublicBobCarol)) { f =>
    import f._

    val (channelId_ab, channelId_bc) = createChannels(f)(deepConfirm = true)

    eventually {
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ZeroConf))
      assert(!getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.channelFeatures.features.contains(ScidAlias))
      // both channels have real scids because they are deeply confirmed, even the zeroconf channel
      assert(getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_ab).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
      assert(getChannelData(carol, channelId_bc).asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
    }

    // alice eventually learns about public channel b-c
    eventually {
      assert(getRouterData(alice).channels.size == 2)
    }

    // alice can make a payment to carol without routing hints
    sendPaymentAliceToCarol(f)
  }

}
