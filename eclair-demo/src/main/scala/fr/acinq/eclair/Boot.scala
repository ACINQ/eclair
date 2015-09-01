package fr.acinq.eclair

import akka.actor.{Props, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import fr.acinq.bitcoin._
import fr.acinq.lightning._
import lightning.locktime.Locktime.Blocks
import lightning.{locktime, sha256_hash}
import org.bouncycastle.util.encoders.Hex

import scala.concurrent.Await

/**
 * Created by PM on 20/08/2015.
 */
object Boot extends App {

  val system = ActorSystem()
  implicit val timeout = Timeout(30 seconds)

  val anchorInput = AnchorInput(100100000L, OutPoint(Hex.decode("7727730d21428276a4d6b0e16f3a3e6f3a07a07dc67151e6a88d4a8c3e8edb24").reverse, 1), SignData("76a914e093fbc19866b98e0fbc25d79d0ad0f0375170af88ac", Base58Check.decode("cU1YgK56oUKAtV6XXHZeJQjEx1KGXkZS1pGiKpyW4mUyKYFJwWFg")._2))

  val alice_commit_priv = Base58Check.decode("cQPmcNr6pwBQPyGfab3SksE9nTCtx9ism9T4dkS9dETNU2KKtJHk")._2
  val alice_final_priv = Base58Check.decode("cUrAtLtV7GGddqdkhUxnbZVDWGJBTducpPoon3eKp9Vnr1zxs6BG")._2
  val bob_commit_priv = Base58Check.decode("cSUwLtdZ2tht9ZmHhdQue48pfe7tY2GT2TGWJDtjoZgo6FHrubGk")._2
  val bob_final_priv = Base58Check.decode("cPR7ZgXpUaDPA3GwGceMDS5pfnSm955yvks3yELf3wMJwegsdGTg")._2

  val blockchain = system.actorOf(Props(new BlockchainWatcher), name = "blockchain")
  val alice = system.actorOf(Props(new Node(blockchain, alice_commit_priv, alice_final_priv, 1, Some(anchorInput))), name = "alice")
  val bob = system.actorOf(Props(new Node(blockchain, bob_commit_priv, bob_final_priv, 2, None)), name = "bob")

  bob.tell(INPUT_NONE, alice)
  alice.tell(INPUT_NONE, bob)

  while (Await.result(alice ? CMD_GETSTATE, 5 seconds) != NORMAL_HIGHPRIO) Thread.sleep(200)
  while (Await.result(bob ? CMD_GETSTATE, 5 seconds) != NORMAL_LOWPRIO) Thread.sleep(200)

  val r = sha256_hash(7, 7, 7, 7)
  val rHash = Crypto.sha256(r)

  alice ! CMD_SEND_HTLC_UPDATE(100, rHash, locktime(Blocks(4)))

  while (Await.result(alice ? CMD_GETSTATE, 5 seconds) != NORMAL_LOWPRIO) Thread.sleep(200)
  while (Await.result(bob ? CMD_GETSTATE, 5 seconds) != NORMAL_HIGHPRIO) Thread.sleep(200)

  bob ! CMD_SEND_HTLC_COMPLETE(r)

  while (Await.result(alice ? CMD_GETSTATE, 5 seconds) != NORMAL_HIGHPRIO) Thread.sleep(200)
  while (Await.result(bob ? CMD_GETSTATE, 5 seconds) != NORMAL_LOWPRIO) Thread.sleep(200)

  alice ! CMD_CLOSE(0)

  while (Await.result(alice ? CMD_GETSTATE, 5 seconds) != CLOSED) Thread.sleep(200)
  while (Await.result(bob ? CMD_GETSTATE, 5 seconds) != CLOSED) Thread.sleep(200)

  system.shutdown()

}
