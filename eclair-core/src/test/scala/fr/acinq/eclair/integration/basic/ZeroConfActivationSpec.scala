package fr.acinq.eclair.integration.basic

import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.FeatureSupport.Optional
import fr.acinq.eclair.Features.ZeroConf
import fr.acinq.eclair.channel.ChannelTypes.AnchorOutputsZeroFeeHtlcTx
import fr.acinq.eclair.channel.PersistentChannelData
import fr.acinq.eclair.integration.basic.fixtures.TwoNodesFixture
import fr.acinq.eclair.testutils.FixtureSpec
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.{Tag, TestData}
import scodec.bits.HexStringSyntax

/**
 * Test the activation of zero-conf option, via features or channel type.
 */
class ZeroConfActivationSpec extends FixtureSpec with IntegrationPatience {

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
    TwoNodesFixture(aliceParams, bobParams)
  }

  override def cleanupFixture(fixture: FixtureParam): Unit = {
    fixture.cleanup()
  }

  test("open a channel alice-bob (zero-conf disabled on both sides)") { f =>
    import f._

    assert(!alice.nodeParams.features.activated.contains(ZeroConf))
    assert(!bob.nodeParams.features.activated.contains(ZeroConf))

    connect(alice, bob)
    val channelId = openChannel(alice, bob, 100_000 sat).channelId

    assert(!getChannelData(alice, channelId).asInstanceOf[PersistentChannelData].commitments.channelFeatures.hasFeature(ZeroConf))
    assert(!getChannelData(bob, channelId).asInstanceOf[PersistentChannelData].commitments.channelFeatures.hasFeature(ZeroConf))
  }

  test("open a channel alice-bob (zero-conf disabled on both sides, requested via channel type by alice)") { f =>
    import f._

    assert(!alice.nodeParams.features.activated.contains(ZeroConf))
    assert(!bob.nodeParams.features.activated.contains(ZeroConf))

    connect(alice, bob)
    val channelType = AnchorOutputsZeroFeeHtlcTx(scidAlias = false, zeroConf = true)
    // bob rejects the channel
    intercept[AssertionError] {
      openChannel(alice, bob, 100_000 sat, channelType_opt = Some(channelType)).channelId
    }
  }

  test("open a channel alice-bob (zero-conf enabled on bob, requested via channel type by alice)", Tag(ZeroConfBob)) { f =>
    import f._

    assert(!alice.nodeParams.features.activated.contains(ZeroConf))
    assert(bob.nodeParams.features.activated.contains(ZeroConf))

    connect(alice, bob)
    val channelType = AnchorOutputsZeroFeeHtlcTx(scidAlias = false, zeroConf = true)
    val channelId = openChannel(alice, bob, 100_000 sat, channelType_opt = Some(channelType)).channelId

    assert(getChannelData(alice, channelId).asInstanceOf[PersistentChannelData].commitments.channelFeatures.hasFeature(ZeroConf))
    assert(getChannelData(bob, channelId).asInstanceOf[PersistentChannelData].commitments.channelFeatures.hasFeature(ZeroConf))
  }

  test("open a channel alice-bob (zero-conf enabled on alice and bob, but not requested via channel type by alice)", Tag(ZeroConfAlice), Tag(ZeroConfBob)) { f =>
    import f._

    assert(alice.nodeParams.features.activated.contains(ZeroConf))
    assert(bob.nodeParams.features.activated.contains(ZeroConf))

    connect(alice, bob)
    val channelType = AnchorOutputsZeroFeeHtlcTx(scidAlias = false, zeroConf = false)
    val channelId = openChannel(alice, bob, 100_000 sat, channelType_opt = Some(channelType)).channelId

    assert(getChannelData(alice, channelId).asInstanceOf[PersistentChannelData].commitments.channelFeatures.hasFeature(ZeroConf))
    assert(getChannelData(bob, channelId).asInstanceOf[PersistentChannelData].commitments.channelFeatures.hasFeature(ZeroConf))
  }

}