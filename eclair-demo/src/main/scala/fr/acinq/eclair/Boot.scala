package fr.acinq.eclair

import akka.actor.{Props, ActorSystem}
import com.google.protobuf.ByteString
import lightning._
import lightning.locktime.Locktime._
import lightning.open_channel.anchor_offer.WILL_CREATE_ANCHOR

/**
 * Created by PM on 20/08/2015.
 */
object Boot extends App {

  val system = ActorSystem()

  val node = system.actorOf(Props(classOf[Node]))

  node ! open_channel(
    delay = locktime(Blocks(1)),
    revocationHash = sha256_hash(1, 1, 1, 1),
    commitKey = bitcoin_pubkey(ByteString.copyFromUtf8("")),
    finalKey = bitcoin_pubkey(ByteString.copyFromUtf8("")),
    anch = WILL_CREATE_ANCHOR,
    minDepth = Some(2),
    commitmentFee = 100)

  node ! open_anchor(
    txid = sha256_hash(1, 1, 1, 1),
    outputIndex = 1,
    amount = 100000000L,
    commitSig = signature(1, 1, 1, 1, 0, 0, 0, 0))

  node ! TxConfirmed(1)
  node ! TxConfirmed(2)

  node ! open_complete(
    blockid = Some(sha256_hash(1, 1, 1, 1))
  )

  node ! update_add_htlc(
    revocationHash = sha256_hash(1, 1, 1, 1),
    amount = 1000,
    rHash = sha256_hash(1, 1, 1, 1),
    expiry = locktime(Blocks(4)))

  node ! update_signature(
    sig = signature(1, 1, 1, 1, 0, 0, 0, 0),
    revocationPreimage = sha256_hash(1, 1, 1, 1))

  // this is sent by the next node
  node ! update_complete_htlc(
    revocationHash = sha256_hash(1, 1, 1, 1),
    r = sha256_hash(1, 1, 1, 1))

  node ! update_signature(
    sig = signature(1, 1, 1, 1, 0, 0, 0, 0),
    revocationPreimage = sha256_hash(1, 1, 1, 1))
}
