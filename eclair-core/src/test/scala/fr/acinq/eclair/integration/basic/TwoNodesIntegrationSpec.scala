package fr.acinq.eclair.integration.basic

import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture.getPeerChannels
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

  import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture.{confirmChannel, connect, getChannelData, getRouterData, knownFundingTxs, nodeParamsFor, openChannel, sendSuccessfulPayment, watcherAutopilot}

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
    assert(getChannelData(alice, channelId).asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.isInstanceOf[LocalFundingStatus.ConfirmedFundingTx])
    assert(getChannelData(bob, channelId).asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.isInstanceOf[LocalFundingStatus.ConfirmedFundingTx])
  }

  test("open multiple channels alice-bob") { f =>
    import f._
    connect(alice, bob)
    val channelId1 = openChannel(alice, bob, 100_000 sat).channelId
    val channelId2 = openChannel(bob, alice, 110_000 sat).channelId
    val channels = getPeerChannels(alice, bob.nodeId)
    assert(channels.map(_.data.channelId).toSet == Set(channelId1, channelId2))
    channels.foreach(c => assert(c.state == WAIT_FOR_DUAL_FUNDING_CREATED || c.state == WAIT_FOR_DUAL_FUNDING_SIGNED || c.state == WAIT_FOR_DUAL_FUNDING_CONFIRMED))
    confirmChannel(alice, bob, channelId1, BlockHeight(420_000), 21)
    confirmChannel(bob, alice, channelId2, BlockHeight(420_000), 22)
    getPeerChannels(bob, alice.nodeId).foreach(c => assert(c.data.isInstanceOf[DATA_NORMAL]))
  }

  test("open a channel alice-bob (autoconfirm)") { f =>
    import f._
    alice.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice)))
    bob.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice)))
    connect(alice, bob)
    val channelId = openChannel(alice, bob, 100_000 sat).channelId
    eventually {
      assert(getChannelData(alice, channelId).asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.isInstanceOf[LocalFundingStatus.ConfirmedFundingTx])
      assert(getChannelData(bob, channelId).asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.isInstanceOf[LocalFundingStatus.ConfirmedFundingTx])
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


