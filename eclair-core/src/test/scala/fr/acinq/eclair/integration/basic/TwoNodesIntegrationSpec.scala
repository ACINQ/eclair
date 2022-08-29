package fr.acinq.eclair.integration.basic

import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.channel.{DATA_NORMAL, NORMAL, RealScidStatus}
import fr.acinq.eclair.integration.basic.fixtures.composite.TwoNodesFixture
import fr.acinq.eclair.testutils.FixtureSpec
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong}
import org.scalatest.TestData
import org.scalatest.concurrent.IntegrationPatience
import scodec.bits.HexStringSyntax

/**
 * This test checks the integration between Channel and Router (events, etc.)
 */
class TwoNodesIntegrationSpec extends FixtureSpec with IntegrationPatience {

  type FixtureParam = TwoNodesFixture

  import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture.{confirmChannel, confirmChannelDeep, connect, getChannelData, getChannelState, getRouterData, knownFundingTxs, nodeParamsFor, openChannel, sendSuccessfulPayment, watcherAutopilot}

  override def createFixture(testData: TestData): FixtureParam = {
    // seeds have been chosen so that node ids start with 02aaaa for alice, 02bbbb for bob, etc.
    val aliceParams = nodeParamsFor("alice", ByteVector32(hex"b4acd47335b25ab7b84b8c020997b12018592bb4631b868762154d77fa8b93a3"))
    val bobParams = nodeParamsFor("bob", ByteVector32(hex"7620226fec887b0b2ebe76492e5a3fd3eb0e47cd3773263f6a81b59a704dc492"))
    TwoNodesFixture(aliceParams, bobParams, testName = testData.name)
  }

  override def cleanupFixture(fixture: FixtureParam): Unit = {
    fixture.cleanup()
  }

  test("connect alice to bob") { f =>
    import f._
    connect(alice, bob)
  }

  test("open a channel alice-bob") { f =>
    import f._
    connect(alice, bob)
    val channelId = openChannel(alice, bob, 100_000 sat).channelId
    confirmChannel(alice, bob, channelId, BlockHeight(420_000), 21)
    assert(getChannelState(alice, channelId) == NORMAL)
    assert(getChannelState(bob, channelId) == NORMAL)
  }

  test("open a channel alice-bob (autoconfirm)") { f =>
    import f._
    alice.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice)))
    bob.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice)))
    connect(alice, bob)
    val channelId = openChannel(alice, bob, 100_000 sat).channelId
    eventually {
      assert(getChannelState(alice, channelId) == NORMAL)
      assert(getChannelState(bob, channelId) == NORMAL)
    }
  }

  test("open a channel alice-bob and confirm deeply") { f =>
    import f._
    connect(alice, bob)
    val channelId = openChannel(alice, bob, 100_000 sat).channelId
    confirmChannel(alice, bob, channelId, BlockHeight(420_000), 21)
    confirmChannelDeep(alice, bob, channelId, BlockHeight(420_000), 21)
    assert(getChannelData(alice, channelId).asInstanceOf[DATA_NORMAL].shortIds.real.isInstanceOf[RealScidStatus.Final])
    assert(getChannelData(bob, channelId).asInstanceOf[DATA_NORMAL].shortIds.real.isInstanceOf[RealScidStatus.Final])
  }

  test("open a channel alice-bob and confirm deeply (autoconfirm)") { f =>
    import f._
    alice.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice)))
    bob.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice)))
    connect(alice, bob)
    val channelId = openChannel(alice, bob, 100_000 sat).channelId
    eventually {
      assert(getChannelData(alice, channelId).asInstanceOf[DATA_NORMAL].shortIds.real.isInstanceOf[RealScidStatus.Final])
      assert(getChannelData(bob, channelId).asInstanceOf[DATA_NORMAL].shortIds.real.isInstanceOf[RealScidStatus.Final])
    }
  }

  test("open a channel alice-bob and make a payment alice->bob") { f =>
    import f._
    connect(alice, bob)
    val channelId = openChannel(alice, bob, 100_000 sat).channelId
    confirmChannel(alice, bob, channelId, BlockHeight(420_000), 21)

    eventually {
      getRouterData(alice).privateChannels.size == 1
    }

    sendSuccessfulPayment(alice, bob, 10_000 msat)
  }

}


