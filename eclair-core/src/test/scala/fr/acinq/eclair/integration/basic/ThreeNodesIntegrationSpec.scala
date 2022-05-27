package fr.acinq.eclair.integration.basic

import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.ShortChannelId.txIndex
import fr.acinq.eclair.integration.basic.fixtures.ThreeNodesFixture
import fr.acinq.eclair.testutils.FixtureSpec
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong}
import org.scalatest.TestData
import org.scalatest.concurrent.IntegrationPatience
import scodec.bits.HexStringSyntax


/**
 * This test checks the integration between Channel and Router (events, etc.)
 */
class ThreeNodesIntegrationSpec extends FixtureSpec with IntegrationPatience {

  type FixtureParam = ThreeNodesFixture

  import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture._

  override def createFixture(testData: TestData): FixtureParam = {
    // seeds have been chose so that node ids start with 02aaaa for alice, 02bbbb for bob, etc.
    val aliceParams = nodeParamsFor("alice", ByteVector32(hex"b4acd47335b25ab7b84b8c020997b12018592bb4631b868762154d77fa8b93a3"))
    val bobParams = nodeParamsFor("bob", ByteVector32(hex"7620226fec887b0b2ebe76492e5a3fd3eb0e47cd3773263f6a81b59a704dc492"))
    val charlieParams = nodeParamsFor("charlie", ByteVector32(hex"ebd5a5d3abfb3ef73731eb3418d918f247445183180522674666db98a66411cc"))
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


