package fr.acinq.eclair.integration.basic

import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.channel.{DATA_NORMAL, NORMAL}
import fr.acinq.eclair.integration.basic.fixtures.TwoNodesFixture
import fr.acinq.eclair.testutils.FixtureSpec
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, TestConstants}
import org.scalatest.TestData
import org.scalatest.concurrent.IntegrationPatience


/**
 * This test checks the integration between Channel and Router (events, etc.)
 */
class TwoNodesIntegrationSpec extends FixtureSpec with IntegrationPatience {

  type FixtureParam = TwoNodesFixture

  import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture._

  override def createFixture(testData: TestData): FixtureParam = {
    val aliceParams = TestConstants.Alice.nodeParams
      .modify(_.channelConf.dustLimit).setTo(1000 sat)
    val bobParams = TestConstants.Bob.nodeParams
    TwoNodesFixture(aliceParams, bobParams)
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
    alice.watcher.setAutoPilot(autoConfirmLocalChannels(alice.wallet.funded))
    bob.watcher.setAutoPilot(autoConfirmLocalChannels(alice.wallet.funded))
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
    assert(getChannelData(alice, channelId).asInstanceOf[DATA_NORMAL].buried)
    assert(getChannelData(bob, channelId).asInstanceOf[DATA_NORMAL].buried)
  }

  test("open a channel alice-bob and confirm deeply (autoconfirm)") { f =>
    import f._
    alice.watcher.setAutoPilot(autoConfirmLocalChannels(alice.wallet.funded))
    bob.watcher.setAutoPilot(autoConfirmLocalChannels(alice.wallet.funded))
    connect(alice, bob)
    val channelId = openChannel(alice, bob, 100_000 sat).channelId
    eventually {
      assert(getChannelData(alice, channelId).asInstanceOf[DATA_NORMAL].buried)
      assert(getChannelData(bob, channelId).asInstanceOf[DATA_NORMAL].buried)
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

    sendPayment(alice, bob, 10_000 msat)
  }

}


