package fr.acinq.eclair.swap

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.WatchExternalChannelSpent
import fr.acinq.eclair.channel.{DATA_NORMAL, RealScidStatus}
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture.{confirmChannel, confirmChannelDeep, connect, getChannelData, getRouterData, openChannel}
import fr.acinq.eclair.payment.PaymentEvent
import fr.acinq.eclair.swap.SwapEvents.SwapEvent
import fr.acinq.eclair.{BlockHeight, NodeParams}
import org.scalatest.concurrent.Eventually.eventually

case class SwapProbes(cli: TestProbe, paymentEvents: TestProbe, swapEvents: TestProbe)

case class SwapIntegrationFixture(system: ActorSystem, alice: MinimalNodeFixture, bob: MinimalNodeFixture, aliceSwap: SwapProbes, bobSwap: SwapProbes, channelId: ByteVector32) {
  implicit val implicitSystem: ActorSystem = system

  def cleanup(): Unit = {
    TestKit.shutdownActorSystem(alice.system)
    TestKit.shutdownActorSystem(bob.system)
    TestKit.shutdownActorSystem(system)
  }
}

object SwapIntegrationFixture {
  def apply(aliceParams: NodeParams, bobParams: NodeParams): SwapIntegrationFixture = {
    val system = ActorSystem("system-test")
    val alice = MinimalNodeFixture(aliceParams)
    val bob = MinimalNodeFixture(bobParams)
    val aliceSwap = SwapProbes(TestProbe()(alice.system), TestProbe()(alice.system), TestProbe()(alice.system))
    val bobSwap = SwapProbes(TestProbe()(bob.system), TestProbe()(bob.system), TestProbe()(bob.system))
    alice.system.eventStream.subscribe(aliceSwap.paymentEvents.ref, classOf[PaymentEvent])
    alice.system.eventStream.subscribe(aliceSwap.swapEvents.ref, classOf[SwapEvent])
    bob.system.eventStream.subscribe(bobSwap.paymentEvents.ref, classOf[PaymentEvent])
    bob.system.eventStream.subscribe(bobSwap.swapEvents.ref, classOf[SwapEvent])

    connect(alice, bob)(system)
    val channelId = openChannel(alice, bob, 100_000 sat)(system).channelId
    confirmChannel(alice, bob, channelId, BlockHeight(420_000), 21)(system)
    confirmChannelDeep(alice, bob, channelId, BlockHeight(420_000), 21)(system)
    assert(getChannelData(alice, channelId)(system).asInstanceOf[DATA_NORMAL].shortIds.real.isInstanceOf[RealScidStatus.Final])
    assert(getChannelData(bob, channelId)(system).asInstanceOf[DATA_NORMAL].shortIds.real.isInstanceOf[RealScidStatus.Final])

    eventually {
      getRouterData(alice)(system).privateChannels.size == 1
    }
    alice.watcher.expectMsgType[WatchExternalChannelSpent]
    bob.watcher.expectMsgType[WatchExternalChannelSpent]

    SwapIntegrationFixture(system, alice, bob, aliceSwap, bobSwap, channelId)
  }
}
