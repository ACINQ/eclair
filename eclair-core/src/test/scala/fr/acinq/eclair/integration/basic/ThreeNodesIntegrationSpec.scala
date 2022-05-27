package fr.acinq.eclair.integration.basic

import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, SatoshiLong}
import fr.acinq.eclair.ShortChannelId.txIndex
import fr.acinq.eclair.crypto.keymanager.{LocalChannelKeyManager, LocalNodeKeyManager}
import fr.acinq.eclair.integration.basic.fixtures.ThreeNodesFixture
import fr.acinq.eclair.testutils.FixtureSpec
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, TestConstants, TestDatabases}
import org.scalatest.TestData
import org.scalatest.concurrent.IntegrationPatience
import scodec.bits.HexStringSyntax

import scala.concurrent.duration.DurationInt


/**
 * This test checks the integration between Channel and Router (events, etc.)
 */
class ThreeNodesIntegrationSpec extends FixtureSpec with IntegrationPatience {

  type FixtureParam = ThreeNodesFixture

  import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture._

  override def createFixture(testData: TestData): FixtureParam = {
    val aliceParams = TestConstants.Alice.nodeParams
      .modify(_.channelConf.dustLimit).setTo(1000 sat)
      .modify(_.routerConf.routerBroadcastInterval).setTo(1 second)
      .modifyAll(_.relayParams.privateChannelFees.feeBase, _.relayParams.publicChannelFees.feeBase).setTo(1_000 msat)
      .modifyAll(_.relayParams.privateChannelFees.feeProportionalMillionths, _.relayParams.publicChannelFees.feeProportionalMillionths).setTo(10)
    val bobParams = TestConstants.Bob.nodeParams
      .modify(_.routerConf.routerBroadcastInterval).setTo(1 second)
      .modifyAll(_.relayParams.privateChannelFees.feeBase, _.relayParams.publicChannelFees.feeBase).setTo(1_000 msat)
      .modifyAll(_.relayParams.privateChannelFees.feeProportionalMillionths, _.relayParams.publicChannelFees.feeProportionalMillionths).setTo(10)

    val charlieSeed: ByteVector32 = ByteVector32(hex"ebd5a5d3abfb3ef73731eb3418d918f247445183180522674666db98a66411cc") // 02cccc...
    val charlieNodeKeyManager = new LocalNodeKeyManager(charlieSeed, Block.RegtestGenesisBlock.hash)
    val charlieChannelKeyManager = new LocalChannelKeyManager(charlieSeed, Block.RegtestGenesisBlock.hash)
    val charlieParams = TestConstants.Bob.nodeParams
      .modify(_.alias).setTo("charlie")
      .modify(_.nodeKeyManager).setTo(charlieNodeKeyManager)
      .modify(_.db).setTo(TestDatabases.inMemoryDb())
      .modify(_.channelKeyManager).setTo(charlieChannelKeyManager)
    ThreeNodesFixture(aliceParams, bobParams, charlieParams)
  }

  override def cleanupFixture(fixture: FixtureParam): Unit = {
    fixture.cleanup()
  }

  test("connect alice->bob and bob->charlie, pay alice->charlie") { f =>
    import f._
    connect(alice, bob)
    connect(bob, charlie)

    val channelIdAB = openChannel(alice, bob, 100_000 sat).channelId
    val channelIdBC = openChannel(bob, charlie, 100_000 sat).channelId

    val fundingTxAB = fundingTx(alice, channelIdAB)
    val fundingTxBC = fundingTx(bob, channelIdBC)

    val shortIdAB = confirmChannel(alice, bob, channelIdAB, BlockHeight(420_000), 21)
    val shortIdBC = confirmChannel(bob, charlie, channelIdBC, BlockHeight(420_001), 22)

    val fundingTxs = Map(
      shortIdAB -> fundingTxAB,
      shortIdBC -> fundingTxBC
    )

    // auto-validate channel announcements
    alice.watcher.setAutoPilot(autoValidatePublicChannels(fundingTxs))
    bob.watcher.setAutoPilot(autoValidatePublicChannels(fundingTxs))

    confirmChannelDeep(alice, bob, channelIdAB, shortIdAB.blockHeight, txIndex(shortIdAB))
    confirmChannelDeep(bob, charlie, channelIdBC, shortIdBC.blockHeight, txIndex(shortIdBC))

    // alice now knows about bob-charlie
    eventually {
      val routerData = getRouterData(alice)
      //prettyPrint(routerData, alice, bob, charlie)
      assert(routerData.channels.size == 2) // 2 channels
      assert(routerData.channels.values.flatMap(c => c.update_1_opt.toSeq ++ c.update_2_opt.toSeq).size == 3) // only 3 channel_updates because c->b is disabled (all funds on b)
    }

    sendPayment(alice, charlie, 100_000 msat)
  }

}


