package fr.acinq.eclair.channel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.payment.Relayer
import fr.acinq.eclair.wire.{Init, UpdateAddHtlc}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class ThroughputSpec extends FunSuite {
  ignore("throughput") {
    implicit val system = ActorSystem()
    val pipe = system.actorOf(Props[Pipe], "pipe")
    val blockchain = system.actorOf(ZmqWatcher.props(new TestBitcoinClient()), "blockchain")
    val paymentHandler = system.actorOf(Props(new Actor() {
      val random = new Random()

      def generateR(): BinaryData = {
        val r = Array.fill[Byte](32)(0)
        random.nextBytes(r)
        r
      }

      context.become(run(Map()))

      override def receive: Receive = ???

      // TODO: store this map on file ?
      def run(h2r: Map[BinaryData, BinaryData]): Receive = {
        case ('add, tgt: ActorRef) =>
          val r = generateR()
          val h: BinaryData = Crypto.sha256(r)
          tgt ! CMD_ADD_HTLC(1, h, 1)
          context.become(run(h2r + (h -> r)))

        case ('sig, tgt: ActorRef) => tgt ! CMD_SIGN

        case htlc: UpdateAddHtlc if h2r.contains(htlc.paymentHash) =>
          val r = h2r(htlc.paymentHash)
          sender ! CMD_FULFILL_HTLC(htlc.id, r)
          context.become(run(h2r - htlc.paymentHash))
      }
    }), "payment-handler")
    val registerA = TestProbe()
    val registerB = TestProbe()
    val relayerA = system.actorOf(Relayer.props(Alice.nodeParams, registerA.ref, paymentHandler))
    val relayerB = system.actorOf(Relayer.props(Bob.nodeParams, registerB.ref, paymentHandler))
    val wallet = new TestWallet
    val alice = system.actorOf(Channel.props(Alice.nodeParams, wallet, Bob.nodeParams.nodeId, blockchain, ???, relayerA, None), "a")
    val bob = system.actorOf(Channel.props(Bob.nodeParams, wallet, Alice.nodeParams.nodeId, blockchain, ???, relayerB, None), "b")
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    alice ! INPUT_INIT_FUNDER("00" * 32, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, Alice.channelParams, pipe, bobInit, ChannelFlags.Empty)
    bob ! INPUT_INIT_FUNDEE("00" * 32, Bob.channelParams, pipe, aliceInit)

    val latch = new CountDownLatch(2)
    val listener = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case ChannelStateChanged(_, _, _, _, NORMAL, _) => latch.countDown()
      }
    }), "listener")
    system.eventStream.subscribe(listener, classOf[ChannelEvent])

    pipe ! (alice, bob)
    latch.await()

    var i = new AtomicLong(0)
    val random = new Random()

    def msg = random.nextInt(100) % 5 match {
      case 0 | 1 | 2 | 3 => 'add
      case 4 => 'sig
    }

    import scala.concurrent.ExecutionContext.Implicits.global
    system.scheduler.schedule(0 seconds, 50 milliseconds, new Runnable() {
      override def run(): Unit = paymentHandler ! (msg, alice)
    })
    system.scheduler.schedule(5 seconds, 70 milliseconds, new Runnable() {
      override def run(): Unit = paymentHandler ! (msg, bob)
    })

    Thread.sleep(Long.MaxValue)
  }
}
