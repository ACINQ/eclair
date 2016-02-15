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
class FooSpec extends TestKit(ActorSystem("TestSystem")) with WordSpecLike with ShouldMatchers with ImplicitSender {

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
    "reach open state without anchor" in {
      val alice = system.actorOf(Props(new Channel(self, Alice.channelParams)), "Alice")
      val bob = system.actorOf(Props(new Channel(self, Bob.channelParams)), "Bob")

      alice ! INPUT_NONE
      val opena = expectMsgClass(classOf[open_channel])
      bob ! INPUT_NONE
      val openb = expectMsgClass(classOf[open_channel])

      bob ! opena
      bob ! CMD_GETSTATE
      expectMsg(OPEN_WAIT_FOR_ANCHOR)

      alice ! openb
      val MakeAnchor(_, _, amount) = expectMsgClass(classOf[MakeAnchor])
      val anchorTx = Transaction(version = 1,
        txIn = Seq.empty[TxIn],
        txOut = TxOut(amount, Scripts.anchorPubkeyScript(Alice.channelParams.commitPubKey, Bob.channelParams.commitPubKey)) :: Nil,
        lockTime = 0
      )
      alice ! (anchorTx, 0)
      val openanchora = expectMsgClass(classOf[open_anchor])
      alice ! CMD_GETSTATE
      expectMsg(OPEN_WAIT_FOR_COMMIT_SIG)

      bob ! openanchora
      val opencommitsigb = expectMsgClass(classOf[open_commit_sig])
      expectMsgClass(classOf[WatchConfirmed])
      expectMsgClass(classOf[WatchSpent])
      bob ! CMD_GETSTATE
      expectMsg(OPEN_WAITING_THEIRANCHOR)

      alice ! opencommitsigb
      expectMsgClass(classOf[WatchConfirmed])
      expectMsgClass(classOf[WatchSpent])
      expectMsgClass(classOf[Publish])
      alice ! CMD_GETSTATE
      expectMsg(OPEN_WAITING_OURANCHOR)

      alice ! BITCOIN_ANCHOR_DEPTHOK
      expectMsgClass(classOf[WatchLost])
      val opencompletea = expectMsgClass(classOf[open_complete])

      bob ! BITCOIN_ANCHOR_DEPTHOK
      expectMsgClass(classOf[WatchLost])
      val opencompleteb = expectMsgClass(classOf[open_complete])

      alice ! opencompleteb
      bob ! opencompletea

      alice ! CMD_GETSTATE
      expectMsg(NORMAL_HIGHPRIO)
      bob ! CMD_GETSTATE
      expectMsg(NORMAL_LOWPRIO)

      val R = sha256_hash(1, 2, 1, 2)
      val H = Crypto.sha256(R)
      alice ! CMD_SEND_HTLC_UPDATE(60000000, H, locktime(Blocks(4)))
      val addhtlca = expectMsgClass(classOf[update_add_htlc])
      alice ! CMD_GETSTATE
      expectMsg(WAIT_FOR_HTLC_ACCEPT_HIGHPRIO)

      bob ! addhtlca
      val htlcacceptb = expectMsgClass(classOf[update_accept])
      alice ! htlcacceptb
      val updatesiga = expectMsgClass(classOf[update_signature])
      bob ! updatesiga
      val update_complete(preimage) = expectMsgClass(classOf[update_complete])
      alice ! update_complete(preimage)

      bob ! CMD_GETSTATE
      expectMsg(NORMAL_HIGHPRIO)
      alice ! CMD_GETSTATE
      expectMsg(NORMAL_LOWPRIO)

      bob ! CMD_SEND_HTLC_FULFILL(R)
      val fulfillhtlcb = expectMsgClass(classOf[update_fulfill_htlc])
      alice ! fulfillhtlcb

      alice ! CMD_CLOSE(10000)
      expectMsgClass(classOf[WatchConfirmedBasedOnOutputs])
      val closea = expectMsgClass(classOf[close_channel])

      bob ! closea

    }
  }
}
