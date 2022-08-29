package fr.acinq.eclair.integration.basic

import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.MilliSatoshiLong
import fr.acinq.eclair.integration.basic.fixtures.composite.ThreeNodesFixture
import fr.acinq.eclair.testutils.FixtureSpec
import org.scalatest.TestData
import org.scalatest.concurrent.IntegrationPatience
import scodec.bits.HexStringSyntax


/**
 * This test checks the integration between Channel and Router (events, etc.)
 */
class ThreeNodesIntegrationSpec extends FixtureSpec with IntegrationPatience {

  type FixtureParam = ThreeNodesFixture

  import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture.{connect, getRouterData, knownFundingTxs, nodeParamsFor, openChannel, sendSuccessfulPayment, watcherAutopilot}

  override def createFixture(testData: TestData): FixtureParam = {
    // seeds have been chosen so that node ids start with 02aaaa for alice, 02bbbb for bob, etc.
    val aliceParams = nodeParamsFor("alice", ByteVector32(hex"b4acd47335b25ab7b84b8c020997b12018592bb4631b868762154d77fa8b93a3"))
    val bobParams = nodeParamsFor("bob", ByteVector32(hex"7620226fec887b0b2ebe76492e5a3fd3eb0e47cd3773263f6a81b59a704dc492"))
    val carolParams = nodeParamsFor("carol", ByteVector32(hex"ebd5a5d3abfb3ef73731eb3418d918f247445183180522674666db98a66411cc"))
    ThreeNodesFixture(aliceParams, bobParams, carolParams, testData.name)
  }

  override def cleanupFixture(fixture: FixtureParam): Unit = {
    fixture.cleanup()
  }

  test("connect alice->bob and bob->carol, pay alice->carol") { f =>
    import f._
    connect(alice, bob)
    connect(bob, carol)

    // we put watchers on auto pilot to confirm funding txs
    alice.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol)))
    bob.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol)))
    carol.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol)))

    openChannel(alice, bob, 100_000 sat).channelId
    openChannel(bob, carol, 100_000 sat).channelId

    // alice now knows about bob-carol
    eventually {
      val routerData = getRouterData(alice)
      //prettyPrint(routerData, alice, bob, carol)
      assert(routerData.channels.size == 2) // 2 channels
      assert(routerData.channels.values.flatMap(c => c.update_1_opt.toSeq ++ c.update_2_opt.toSeq).size == 4) // 2 channel_updates per channel
    }

    sendSuccessfulPayment(alice, carol, 100_000 msat)
  }

}


