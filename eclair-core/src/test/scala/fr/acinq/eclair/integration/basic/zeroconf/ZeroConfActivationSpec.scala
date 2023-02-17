package fr.acinq.eclair.integration.basic.zeroconf

import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.FeatureSupport.Optional
import fr.acinq.eclair.Features.ZeroConf
import fr.acinq.eclair.channel.ChannelTypes.AnchorOutputsZeroFeeHtlcTx
import fr.acinq.eclair.channel.{DATA_NORMAL, NORMAL, PersistentChannelData, SupportedChannelType}
import fr.acinq.eclair.integration.basic.fixtures.composite.TwoNodesFixture
import fr.acinq.eclair.testutils.FixtureSpec
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.{Tag, TestData}
import scodec.bits.HexStringSyntax

import scala.concurrent.duration.DurationInt

/**
 * Test the activation of zero-conf option, via features or channel type.
 */
class ZeroConfActivationSpec extends FixtureSpec with IntegrationPatience {

  implicit val config: PatienceConfig = PatienceConfig(5 second, 50 milliseconds)

  type FixtureParam = TwoNodesFixture

  val ZeroConfAlice = "zero_conf_alice"
  val ZeroConfBob = "zero_conf_bob"

  import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture._

  override def createFixture(testData: TestData): FixtureParam = {
    // seeds have been chosen so that node ids start with 02aaaa for alice, 02bbbb for bob, etc.
    val aliceParams = nodeParamsFor("alice", ByteVector32(hex"b4acd47335b25ab7b84b8c020997b12018592bb4631b868762154d77fa8b93a3"))
      .modify(_.features.activated).using(_ - ZeroConf) // we will enable those features on demand
      .modify(_.features.activated).usingIf(testData.tags.contains(ZeroConfAlice))(_ + (ZeroConf -> Optional))
    val bobParams = nodeParamsFor("bob", ByteVector32(hex"7620226fec887b0b2ebe76492e5a3fd3eb0e47cd3773263f6a81b59a704dc492"))
      .modify(_.features.activated).using(_ - ZeroConf) // we will enable those features on demand
      .modify(_.features.activated).usingIf(testData.tags.contains(ZeroConfBob))(_ + (ZeroConf -> Optional))
    TwoNodesFixture(aliceParams, bobParams, testData.name)
  }

  override def cleanupFixture(fixture: FixtureParam): Unit = {
    fixture.cleanup()
  }

  private def createChannel(f: FixtureParam, channelType_opt: Option[SupportedChannelType] = None): ByteVector32 = {
    import f._

    alice.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob), confirm = false, deepConfirm = false))
    bob.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob), confirm = false, deepConfirm = false))

    connect(alice, bob)
    openChannel(alice, bob, 100_000 sat, channelType_opt).channelId
  }

  test("open a channel alice-bob (zero-conf disabled on both sides)") { f =>
    import f._

    assert(!alice.nodeParams.features.activated.contains(ZeroConf))
    assert(!bob.nodeParams.features.activated.contains(ZeroConf))

    val channelId = createChannel(f)
    assert(!getChannelData(alice, channelId).asInstanceOf[PersistentChannelData].commitments.params.channelFeatures.hasFeature(ZeroConf))
    assert(!getChannelData(bob, channelId).asInstanceOf[PersistentChannelData].commitments.params.channelFeatures.hasFeature(ZeroConf))
  }

  test("open a channel alice-bob (zero-conf disabled on both sides, requested via channel type by alice)") { f =>
    import f._

    assert(!alice.nodeParams.features.activated.contains(ZeroConf))
    assert(!bob.nodeParams.features.activated.contains(ZeroConf))

    connect(alice, bob)
    val channelType = AnchorOutputsZeroFeeHtlcTx(zeroConf = true)
    // bob rejects the channel
    intercept[AssertionError] {
      openChannel(alice, bob, 100_000 sat, channelType_opt = Some(channelType)).channelId
    }
  }

  test("open a channel alice-bob (zero-conf enabled on bob, requested via channel type by alice)", Tag(ZeroConfBob)) { f =>
    import f._

    assert(!alice.nodeParams.features.activated.contains(ZeroConf))
    assert(bob.nodeParams.features.activated.contains(ZeroConf))

    val channelType = AnchorOutputsZeroFeeHtlcTx(zeroConf = true)
    val channelId = createChannel(f, channelType_opt = Some(channelType))

    eventually {
      assert(getChannelData(alice, channelId).asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures.hasFeature(ZeroConf))
      assert(getChannelData(bob, channelId).asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures.hasFeature(ZeroConf))
    }
  }

  test("open a channel alice-bob (zero-conf enabled on bob, not requested via channel type by alice)", Tag(ZeroConfBob)) { f =>
    import f._

    assert(!alice.nodeParams.features.activated.contains(ZeroConf))
    assert(bob.nodeParams.features.activated.contains(ZeroConf))

    val channelType = AnchorOutputsZeroFeeHtlcTx()
    val channelId = createChannel(f, channelType_opt = Some(channelType))

    // Bob has activated support for 0-conf with Alice, so he doesn't wait for the funding tx to confirm regardless of
    // the channel type and activated feature bits. Since Alice has full control over the funding tx, she accepts Bob's
    // early channel_ready and completes the channel opening flow without waiting for confirmations.
    eventually {
      assert(getChannelState(alice, channelId) == NORMAL)
      assert(getChannelState(bob, channelId) == NORMAL)
    }
  }

  test("open a channel alice-bob (zero-conf enabled on alice and bob, but not requested via channel type by alice)", Tag(ZeroConfAlice), Tag(ZeroConfBob)) { f =>
    import f._

    assert(alice.nodeParams.features.activated.contains(ZeroConf))
    assert(bob.nodeParams.features.activated.contains(ZeroConf))

    val channelType = AnchorOutputsZeroFeeHtlcTx()
    val channelId = createChannel(f, channelType_opt = Some(channelType))

    eventually {
      assert(getChannelData(alice, channelId).asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures.hasFeature(ZeroConf))
      assert(getChannelData(bob, channelId).asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures.hasFeature(ZeroConf))
    }
  }

}