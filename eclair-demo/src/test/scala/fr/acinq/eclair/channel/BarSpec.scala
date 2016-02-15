package fr.acinq.eclair.channel

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import lightning._
import lightning.locktime.Locktime.Blocks
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class BarSpec extends TestKit(ActorSystem("TestSystem")) with WordSpecLike with ShouldMatchers with ImplicitSender {

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
    "open, manage htlcs and close" in {
      val alice = system.actorOf(Props(new Channel(self, Alice.channelParams)), "Alice")
      val bob = system.actorOf(Props(new Channel(self, Bob.channelParams)), "Bob")

      alice.tell(INPUT_NONE, bob)
      bob.tell(INPUT_NONE, alice)

      val MakeAnchor(_, _, amount) = expectMsgClass(classOf[MakeAnchor])
      val anchorTx = Transaction(version = 1,
        txIn = Seq.empty[TxIn],
        txOut = TxOut(amount, Scripts.anchorPubkeyScript(Alice.channelParams.commitPubKey, Bob.channelParams.commitPubKey)) :: Nil,
        lockTime = 0
      )
      alice ! (anchorTx, 0)

      expectMsgClass(classOf[WatchConfirmed])
      expectMsgClass(classOf[WatchSpent])

      expectMsgClass(classOf[WatchConfirmed])
      expectMsgClass(classOf[WatchSpent])
      expectMsgClass(classOf[Publish])

      alice ! CMD_GETSTATE
      expectMsg(OPEN_WAITING_OURANCHOR)

      alice ! BITCOIN_ANCHOR_DEPTHOK
      expectMsgClass(classOf[WatchLost])

      bob ! BITCOIN_ANCHOR_DEPTHOK
      expectMsgClass(classOf[WatchLost])

      Thread.sleep(100)

      alice ! CMD_GETSTATE
      expectMsg(NORMAL_HIGHPRIO)
      bob ! CMD_GETSTATE
      expectMsg(NORMAL_LOWPRIO)

      val R: BinaryData = "0102030405060708010203040506070801020304050607080102030405060708"
      val H = Crypto.sha256(R)
      alice ! CMD_SEND_HTLC_UPDATE(60000000, H, locktime(Blocks(4)))

      Thread.sleep(100)

      bob ! CMD_SEND_HTLC_FULFILL(R)

      Thread.sleep(100)

      alice ! CMD_GETSTATE
      expectMsg(NORMAL_HIGHPRIO)
      bob ! CMD_GETSTATE
      expectMsg(NORMAL_LOWPRIO)

      alice ! CMD_CLOSE(10000)

      expectMsgClass(classOf[WatchConfirmedBasedOnOutputs])
      expectMsgClass(classOf[WatchConfirmed])
      val Publish(closingTx1) = expectMsgClass(classOf[Publish])
      expectMsgClass(classOf[WatchConfirmed])
      val Publish(closingTx2) = expectMsgClass(classOf[Publish])

      Thread.sleep(100)

      alice ! CMD_GETSTATE
      expectMsg(CLOSING)
      bob ! CMD_GETSTATE
      expectMsg(CLOSING)

      alice ! (BITCOIN_ANCHOR_SPENT, closingTx1)
      bob ! (BITCOIN_ANCHOR_SPENT, closingTx1)

      alice ! CMD_GETSTATE
      expectMsg(CLOSING)
      bob ! CMD_GETSTATE
      expectMsg(CLOSING)
    }
  }
}
