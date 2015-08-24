package fr.acinq.eclair

import akka.actor.{Props, ActorSystem}
import lightning.sha256_hash
import org.bouncycastle.util.encoders.Hex

/**
 * Created by PM on 20/08/2015.
 */
object Boot extends App {

  val system = ActorSystem()

  val alice = system.actorOf(Props(new Node(Hex.decode("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), Hex.decode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), INIT_WITHANCHOR)), name = "alice")
  val bob = system.actorOf(Props(new Node(Hex.decode("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"), Hex.decode("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"), INIT_NOANCHOR)), name = "bob")

  bob.tell(INPUT_NONE, alice)
  alice.tell(INPUT_NONE, bob)

  Thread.sleep(3000)
  alice ! TxConfirmed(sha256_hash(1, 2, 3, 4), 1)
  bob ! TxConfirmed(sha256_hash(1, 2, 3, 4), 1)

  Thread.sleep(2000)
  alice ! TxConfirmed(sha256_hash(1, 2, 3, 4), 2)
  bob ! TxConfirmed(sha256_hash(1, 2, 3, 4), 2)

}
