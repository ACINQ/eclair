package fr.acinq.eclair.channel

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import lightning._
import lightning.locktime.Locktime.Blocks
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner


@RunWith(classOf[JUnitRunner])
class ChannelSpec extends TestKit(ActorSystem("TestSystem")) with WordSpecLike with ShouldMatchers with ImplicitSender {

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

  "channel" should {
    "open, propose, accept, fulfill htlcs and close" in {
      val blockchain = TestProbe("blockchain")
      val alice = system.actorOf(Props(new Channel(blockchain.ref, Alice.channelParams)), "Alice")
      val bob = system.actorOf(Props(new Channel(blockchain.ref, Bob.channelParams)), "Bob")

      val monitora = TestProbe()
      val monitorb = TestProbe()

      alice ! SubscribeTransitionCallBack(monitora.ref)
      val CurrentState(_, INIT_WITHANCHOR) = monitora.expectMsgClass(classOf[CurrentState[_]])

      bob ! SubscribeTransitionCallBack(monitorb.ref)
      val CurrentState(_, INIT_NOANCHOR) = monitorb.expectMsgClass(classOf[CurrentState[_]])

      alice.tell(INPUT_NONE, bob)
      bob.tell(INPUT_NONE, alice)

      def waitForAliceTransition = monitora.expectMsgClass(classOf[Transition[_]])
      def waitForBobTransition = monitorb.expectMsgClass(classOf[Transition[_]])

      val Transition(_, INIT_WITHANCHOR, OPEN_WAIT_FOR_OPEN_WITHANCHOR) = waitForAliceTransition
      val Transition(_, INIT_NOANCHOR, OPEN_WAIT_FOR_OPEN_NOANCHOR) = waitForBobTransition

      val MakeAnchor(_, _, amount) = blockchain.expectMsgClass(classOf[MakeAnchor])
      val anchorTx = Transaction(version = 1,
        txIn = Seq.empty[TxIn],
        txOut = TxOut(amount, Scripts.anchorPubkeyScript(Alice.channelParams.commitPubKey, Bob.channelParams.commitPubKey)) :: Nil,
        lockTime = 0
      )
      blockchain.reply((anchorTx, 0))
      blockchain.expectMsgClass(classOf[WatchConfirmed])
      blockchain.expectMsgClass(classOf[WatchSpent])
      blockchain.expectMsgClass(classOf[WatchConfirmed])
      blockchain.expectMsgClass(classOf[WatchSpent])
      blockchain.expectMsgClass(classOf[Publish])

      val Transition(_, OPEN_WAIT_FOR_OPEN_WITHANCHOR, OPEN_WAIT_FOR_COMMIT_SIG) = waitForAliceTransition
      val Transition(_, OPEN_WAIT_FOR_OPEN_NOANCHOR, OPEN_WAIT_FOR_ANCHOR) = waitForBobTransition

      val Transition(_, OPEN_WAIT_FOR_COMMIT_SIG, OPEN_WAITING_OURANCHOR) = waitForAliceTransition
      val Transition(_, OPEN_WAIT_FOR_ANCHOR, OPEN_WAITING_THEIRANCHOR) = waitForBobTransition

      blockchain.send(alice, BITCOIN_ANCHOR_DEPTHOK)
      blockchain.expectMsgClass(classOf[WatchLost])

      blockchain.send(bob, BITCOIN_ANCHOR_DEPTHOK)
      blockchain.expectMsgClass(classOf[WatchLost])

      val Transition(_, OPEN_WAITING_OURANCHOR, OPEN_WAIT_FOR_COMPLETE_OURANCHOR) = waitForAliceTransition
      val Transition(_, OPEN_WAITING_THEIRANCHOR, OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) = waitForBobTransition

      val Transition(_, OPEN_WAIT_FOR_COMPLETE_OURANCHOR, NORMAL_HIGHPRIO) = waitForAliceTransition
      val Transition(_, OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR, NORMAL_LOWPRIO) = waitForBobTransition

      val R: BinaryData = "0102030405060708010203040506070801020304050607080102030405060708"
      val H = Crypto.sha256(R)
      alice ! CMD_SEND_HTLC_UPDATE(60000000, H, locktime(Blocks(4)))

      val Transition(_, NORMAL_HIGHPRIO, WAIT_FOR_HTLC_ACCEPT_HIGHPRIO) = waitForAliceTransition
      val Transition(_, NORMAL_LOWPRIO, WAIT_FOR_UPDATE_SIG_LOWPRIO) = waitForBobTransition

      val Transition(_, WAIT_FOR_HTLC_ACCEPT_HIGHPRIO, WAIT_FOR_UPDATE_COMPLETE_HIGHPRIO) = waitForAliceTransition
      val Transition(_, WAIT_FOR_UPDATE_COMPLETE_HIGHPRIO, NORMAL_LOWPRIO) = waitForAliceTransition
      val Transition(_, WAIT_FOR_UPDATE_SIG_LOWPRIO, NORMAL_HIGHPRIO) = waitForBobTransition

      bob ! CMD_SEND_HTLC_FULFILL(R)

      val Transition(_, NORMAL_LOWPRIO, WAIT_FOR_UPDATE_SIG_LOWPRIO) = waitForAliceTransition
      val Transition(_, NORMAL_HIGHPRIO, WAIT_FOR_HTLC_ACCEPT_HIGHPRIO) = waitForBobTransition

      val Transition(_, WAIT_FOR_UPDATE_SIG_LOWPRIO, NORMAL_HIGHPRIO) = waitForAliceTransition
      val Transition(_, WAIT_FOR_HTLC_ACCEPT_HIGHPRIO, WAIT_FOR_UPDATE_COMPLETE_HIGHPRIO) = waitForBobTransition
      val Transition(_, WAIT_FOR_UPDATE_COMPLETE_HIGHPRIO, NORMAL_LOWPRIO) = waitForBobTransition

      alice ! CMD_CLOSE(10000)
      val Transition(_, NORMAL_HIGHPRIO, WAIT_FOR_CLOSE_COMPLETE) = waitForAliceTransition
      val Transition(_, NORMAL_LOWPRIO, WAIT_FOR_CLOSE_ACK) = waitForBobTransition

      blockchain.expectMsgClass(classOf[WatchConfirmedBasedOnOutputs])
      blockchain.expectMsgClass(classOf[WatchConfirmed])
      val Publish(closingTx1) = blockchain.expectMsgClass(classOf[Publish])
      blockchain.expectMsgClass(classOf[WatchConfirmed])
      val Publish(closingTx2) = blockchain.expectMsgClass(classOf[Publish])

      val Transition(_, WAIT_FOR_CLOSE_COMPLETE, CLOSING) = waitForAliceTransition
      val Transition(_, WAIT_FOR_CLOSE_ACK, CLOSING) = waitForBobTransition

      blockchain.send(alice, (BITCOIN_ANCHOR_SPENT, closingTx1))
      blockchain.send(bob, (BITCOIN_ANCHOR_SPENT, closingTx1))

      blockchain.send(alice, BITCOIN_CLOSE_DONE)
      blockchain.send(bob, BITCOIN_CLOSE_DONE)

      val Transition(_, CLOSING, CLOSED) = waitForAliceTransition
      val Transition(_, CLOSING, CLOSED) = waitForBobTransition
    }
  }
}
