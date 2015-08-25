package fr.acinq.eclair

import akka.actor.{Props, ActorSystem}
import fr.acinq.bitcoin._
import lightning.sha256_hash
import org.bouncycastle.util.encoders.Hex

/**
 * Created by PM on 20/08/2015.
 */
object Boot extends App {

  val system = ActorSystem()

  val previousTx = Transaction.read("0100000001bb4f5a244b29dc733c56f80c0fed7dd395367d9d3b416c01767c5123ef124f82000000006b4830450221009e6ed264343e43dfee2373b925915f7a4468e0bc68216606e40064561e6c097a022030f2a50546a908579d0fab539d5726a1f83cfd48d29b89ab078d649a8e2131a0012103c80b6c289bf0421d010485cec5f02636d18fb4ed0f33bfa6412e20918ebd7a34ffffffff0200093d00000000001976a9145dbf52b8d7af4fb5f9b75b808f0a8284493531b388acf0b0b805000000001976a914807c74c89592e8a260f04b5a3bc63e7bef8c282588ac00000000")

  val alice = system.actorOf(Props(new Node(Hex.decode("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), Hex.decode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), Some(AnchorInput(100000000L, OutPoint(previousTx, 0), SignData(previousTx.txOut(0).publicKeyScript, Base58Check.decode("cV7LGVeY2VPuCyCSarqEqFCUNig2NzwiAEBTTA89vNRQ4Vqjfurs")._2))))), name = "alice")
  val bob = system.actorOf(Props(new Node(Hex.decode("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"), Hex.decode("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"), None)), name = "bob")

  bob.tell(INPUT_NONE, alice)
  alice.tell(INPUT_NONE, bob)

  Thread.sleep(3000)
  alice ! TxConfirmed(sha256_hash(1, 2, 3, 4), 1)
  bob ! TxConfirmed(sha256_hash(1, 2, 3, 4), 1)

  Thread.sleep(2000)
  alice ! TxConfirmed(sha256_hash(1, 2, 3, 4), 2)
  bob ! TxConfirmed(sha256_hash(1, 2, 3, 4), 2)

}
