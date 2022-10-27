package fr.acinq.eclair.plugins.peerswap

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.{ClassicActorRefOps, ClassicActorSystemOps}
import akka.actor.typed.{ActorRef, SupervisorStrategy}
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.WatchExternalChannelSpent
import fr.acinq.eclair.channel.{DATA_NORMAL, RealScidStatus}
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture.{confirmChannel, confirmChannelDeep, connect, getChannelData, getRouterData, openChannel}
import fr.acinq.eclair.payment.PaymentEvent
import fr.acinq.eclair.plugins.peerswap.SwapEvents.SwapEvent
import fr.acinq.eclair.plugins.peerswap.db.sqlite.SqliteSwapsDb
import fr.acinq.eclair.{BlockHeight, NodeParams, TestConstants}
import org.scalatest.concurrent.Eventually.eventually

import java.sql.DriverManager

case class SwapActors(cli: TestProbe, paymentEvents: TestProbe, swapEvents: TestProbe, swapRegister: ActorRef[SwapRegister.Command])

case class SwapIntegrationFixture(system: ActorSystem, alice: MinimalNodeFixture, bob: MinimalNodeFixture, aliceSwap: SwapActors, bobSwap: SwapActors, channelId: ByteVector32) {
  implicit val implicitSystem: ActorSystem = system

  def cleanup(): Unit = {
    TestKit.shutdownActorSystem(alice.system)
    TestKit.shutdownActorSystem(bob.system)
    TestKit.shutdownActorSystem(system)
  }
}

object SwapIntegrationFixture {
  def swapRegister(node: MinimalNodeFixture): ActorRef[SwapRegister.Command] = {
    val keyManager: SwapKeyManager = new LocalSwapKeyManager(TestConstants.Alice.seed, node.nodeParams.chainHash)
    val db = new SqliteSwapsDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
    node.system.spawn(Behaviors.supervise(SwapRegister(node.nodeParams, node.paymentInitiator, node.watcher.ref.toTyped, node.register, node.wallet, keyManager, db, Set())).onFailure(SupervisorStrategy.stop), s"swap-register-${node.nodeParams.alias}")
  }
  def apply(aliceParams: NodeParams, bobParams: NodeParams): SwapIntegrationFixture = {
    val system = ActorSystem("system-test")
    val alice = MinimalNodeFixture(aliceParams, "alice-swap-integration")
    val bob = MinimalNodeFixture(bobParams, "bob-swap-integration")
    val aliceSwap = SwapActors(TestProbe()(alice.system), TestProbe()(alice.system), TestProbe()(alice.system), swapRegister(alice))
    val bobSwap = SwapActors(TestProbe()(bob.system), TestProbe()(bob.system), TestProbe()(bob.system), swapRegister(bob))
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
