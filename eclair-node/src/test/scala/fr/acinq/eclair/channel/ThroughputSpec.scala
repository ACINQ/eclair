package fr.acinq.eclair.channel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.payment.Relayer
import fr.acinq.eclair.wire.UpdateAddHtlc
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
    val blockchain = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient(), 300)), "blockchain")
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
    val router: ActorRef = ???
    val relayer = system.actorOf(Relayer.props(Globals.Node.privateKey, paymentHandler))
    val alice = system.actorOf(Channel.props(pipe, blockchain, ???, relayer, Alice.channelParams, Bob.id), "a")
    val bob = system.actorOf(Channel.props(pipe, blockchain, ???, relayer, Bob.channelParams, Alice.id), "b")

    val latch = new CountDownLatch(2)
    val listener = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case ChannelChangedState(_, _, _, _, NORMAL, _) => latch.countDown()
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
