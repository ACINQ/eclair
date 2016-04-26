package fr.acinq.eclair.channel

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorRef, ActorSystem, Props, Stash}
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import lightning.{locktime, update_add_htlc}
import lightning.locktime.Locktime.Blocks
import org.junit.runner.RunWith
import org.scalatest.{FunSuite, FunSuiteLike}
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class ChannelSpec extends TestKit(ActorSystem("MySpec")) with ImplicitSender with FunSuiteLike {

  import ChannelSpec._

  test("open channels with and without anchor") {
    val testData = TestActors()
    testData.open
    TestActors.cleanup(testData)
  }

  test("create and fulfill HTLCs") {
    val testData = TestActors()
    testData.open
    import testData._
    val R: BinaryData = "0102030405060708010203040506070801020304050607080102030405060708"
    val H = Crypto.sha256(R)

    alice ! CMD_ADD_HTLC(60000000, H, locktime(Blocks(4)))

    alice ! CMD_GETSTATEDATA
    val DATA_NORMAL(_, _, _, _, _, _, List(Change(OUT, _, update_add_htlc(_, _, r1, _, _))), _, _) = receiveOne(5 seconds)
    assert(r1 == bin2sha256(H))

    bob ! CMD_GETSTATEDATA
    val DATA_NORMAL(_, _, _, _, _, _, List(Change(IN, _, update_add_htlc(_, _, r2, _, _))), _, _) = receiveOne(5 seconds)
    assert(r2 == bin2sha256(H))

    bob ! CMD_SIGN
//    val Transition(_, NORMAL, NORMAL_WAIT_FOR_REV) = waitForBobTransition
    // FIXME: sigs are not valid
//    val Transition(_, NORMAL, NORMAL_WAIT_FOR_REV_THEIRSIG) = waitForAliceTransition
    //val Transition(_, NORMAL_WAIT_FOR_REV, NORMAL_WAIT_FOR_SIG) = waitForBobTransition
    //val Transition(_, NORMAL_WAIT_FOR_SIG, NORMAL) = waitForBobTransition
    //val Transition(_, NORMAL_WAIT_FOR_REV_THEIRSIG, NORMAL) = waitForAliceTransition

    TestActors.cleanup(testData)
  }
}

object ChannelSpec {
  val anchorAmount = 100100000L

  // Alice is funder, Bob is not

  object Alice {
    val (Base58.Prefix.SecretKeyTestnet, commitPrivKey) = Base58Check.decode("cQPmcNr6pwBQPyGfab3SksE9nTCtx9ism9T4dkS9dETNU2KKtJHk")
    val (Base58.Prefix.SecretKeyTestnet, finalPrivKey) = Base58Check.decode("cUrAtLtV7GGddqdkhUxnbZVDWGJBTducpPoon3eKp9Vnr1zxs6BG")
    val channelParams = OurChannelParams(locktime(Blocks(10)), commitPrivKey, finalPrivKey, 1, 100000, "alice-seed".getBytes(), Some(anchorAmount))
  }

  object Bob {
    val (Base58.Prefix.SecretKeyTestnet, commitPrivKey) = Base58Check.decode("cSUwLtdZ2tht9ZmHhdQue48pfe7tY2GT2TGWJDtjoZgo6FHrubGk")
    val (Base58.Prefix.SecretKeyTestnet, finalPrivKey) = Base58Check.decode("cPR7ZgXpUaDPA3GwGceMDS5pfnSm955yvks3yELf3wMJwegsdGTg")
    val channelParams = OurChannelParams(locktime(Blocks(10)), commitPrivKey, finalPrivKey, 2, 100000, "bob-seed".getBytes(), None)
  }

  case class TestActors(alice: ActorRef, monitorAlice: TestProbe, bob: ActorRef, monitorBob: TestProbe, blockchain: TestProbe, pipe: ActorRef) {
    def waitForAliceTransition = monitorAlice.expectMsgClass(classOf[Transition[_]])

    def waitForBobTransition = monitorBob.expectMsgClass(classOf[Transition[_]])

    def open: Unit = {
      val MakeAnchor(_, _, amount) = blockchain.expectMsgClass(classOf[MakeAnchor])
      val anchorTx = Transaction(version = 1,
        txIn = Seq.empty[TxIn],
        txOut = TxOut(amount, Scripts.anchorPubkeyScript(Alice.channelParams.commitPubKey, Bob.channelParams.commitPubKey)) :: Nil,
        lockTime = 0
      )
      blockchain.reply((anchorTx, 0))
      blockchain.expectMsgClass(classOf[Publish])

      val Transition(_, OPEN_WAIT_FOR_OPEN_WITHANCHOR, OPEN_WAIT_FOR_COMMIT_SIG) = waitForAliceTransition
      val Transition(_, OPEN_WAIT_FOR_OPEN_NOANCHOR, OPEN_WAIT_FOR_ANCHOR) = waitForBobTransition

      val Transition(_, OPEN_WAIT_FOR_COMMIT_SIG, OPEN_WAITING_OURANCHOR) = waitForAliceTransition
      val Transition(_, OPEN_WAIT_FOR_ANCHOR, OPEN_WAITING_THEIRANCHOR) = waitForBobTransition

      blockchain.send(alice, BITCOIN_ANCHOR_DEPTHOK)

      blockchain.send(bob, BITCOIN_ANCHOR_DEPTHOK)

      val Transition(_, OPEN_WAITING_OURANCHOR, OPEN_WAIT_FOR_COMPLETE_OURANCHOR) = waitForAliceTransition
      val Transition(_, OPEN_WAITING_THEIRANCHOR, OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) = waitForBobTransition

      val Transition(_, OPEN_WAIT_FOR_COMPLETE_OURANCHOR, NORMAL) = waitForAliceTransition
      val Transition(_, OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR, NORMAL) = waitForBobTransition
    }
  }

  object TestActors {
    def apply()(implicit system: ActorSystem): TestActors = {
      val blockchain = TestProbe("blockchain")
      blockchain.ignoreMsg {
        case m: WatchConfirmed => true
        case m: WatchSpent => true
        case m: WatchLost => true
      }
      val pipe = system.actorOf(Props[ChannelSpec.Pipe])
      val alice = system.actorOf(Channel.props(pipe, blockchain.ref, Alice.channelParams), "Alice")
      val bob = system.actorOf(Channel.props(pipe, blockchain.ref, Bob.channelParams), "Bob")

      val monitora = TestProbe()
      val monitorb = TestProbe()

      alice ! SubscribeTransitionCallBack(monitora.ref)
      val CurrentState(_, OPEN_WAIT_FOR_OPEN_WITHANCHOR) = monitora.expectMsgClass(classOf[CurrentState[_]])

      bob ! SubscribeTransitionCallBack(monitorb.ref)
      val CurrentState(_, OPEN_WAIT_FOR_OPEN_NOANCHOR) = monitorb.expectMsgClass(classOf[CurrentState[_]])

      pipe ! alice
      pipe ! bob

      new TestActors(alice, monitora, bob, monitorb, blockchain, pipe)
    }

    def cleanup(data: TestActors)(implicit system: ActorSystem): Unit = {
      system.stop(data.alice)
      system.stop(data.bob)
      system.stop(data.pipe)
      Thread.sleep(100)
    }
  }


  // handle a bi-directional path between 2 actors
  // used to avoid the chicken-and-egg problem of:
  // a = new Channel(b)
  // b = new Channel(a)
  class Pipe extends Actor with Stash {

    override def unhandled(message: Any): Unit = stash()

    def receive = {
      case a: ActorRef => context become receive1(a)
    }

    def receive1(a: ActorRef): Receive = {
      case b: ActorRef =>
        unstashAll()
        context become receive2(a, b)
    }

    def receive2(a: ActorRef, b: ActorRef): Receive = {
      case msg if sender() == a => b forward msg
      case msg if sender() == b => a forward msg
    }
  }
}