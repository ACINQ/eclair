package fr.acinq.eclair.channel

import fr.acinq.bitcoin.{BinaryData, Crypto, ScriptFlags, Transaction}
import fr.acinq.eclair._
import TestConstants.{Alice, Bob}
import lightning.locktime.Locktime.Blocks
import lightning.{locktime, routing, update_add_htlc}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class StealRevokedCommitmentSpec extends FunSuite {

  def signAndReceiveRevocation(sender: Commitments, receiver: Commitments): (Commitments, Commitments) = {
    val (sender1, commit1) = Commitments.sendCommit(sender)
    val (receiver1, rev1) = Commitments.receiveCommit(receiver, commit1)
    val sender2 = Commitments.receiveRevocation(sender1, rev1)
    (sender2, receiver1)
  }

  def addHtlc(sender: Commitments, receiver: Commitments, htlc: update_add_htlc): (Commitments, Commitments) = {
    (Commitments.sendAdd(sender, CMD_ADD_HTLC(id = Some(htlc.id), amountMsat = htlc.amountMsat, rHash = htlc.rHash, expiry = htlc.expiry))._1, Commitments.receiveAdd(receiver, htlc))
  }

  def fulfillHtlc(sender: Commitments, receiver: Commitments, id: Long, paymentPreimage: BinaryData): (Commitments, Commitments) = {
    val (sender1, fulfill) = Commitments.sendFulfill(sender, CMD_FULFILL_HTLC(id, paymentPreimage))
    val receiver1 = Commitments.receiveFulfill(receiver, fulfill)
    (sender1, receiver1)
  }

  test("steal a revoked commit tx") {
    val alice = Alice.commitments
    val bob = Bob.commitments

    val R: BinaryData = "0102030405060708010203040506070801020304050607080102030405060708"
    val H = Crypto.sha256(R)

    val htlc = update_add_htlc(1, 70000000, H, locktime(Blocks(400)), routing.defaultInstance)
    val (alice1, bob1) = addHtlc(alice, bob, htlc)
    val (alice2, bob2) = signAndReceiveRevocation(alice1, bob1)

    val (bob3, alice3) = signAndReceiveRevocation(bob2, alice2)
    val (bob4, alice4) = fulfillHtlc(bob3, alice3, 1, R)
    val (bob5, alice5) = signAndReceiveRevocation(bob4, alice4)


    val theirTxTemplate = Commitments.makeTheirTxTemplate(bob3)
    val theirTx = theirTxTemplate.makeTx
    assert(theirTx.txIn == alice3.ourCommit.publishableTx.txIn && theirTx.txOut == alice3.ourCommit.publishableTx.txOut)
    val preimage = bob5.theirPreimages.getHash(0xFFFFFFFFFFFFFFFFL - bob3.theirCommit.index).get
    val punishTx = Helpers.claimRevokedCommitTx(theirTxTemplate, preimage, bob3.ourParams.finalPrivKey)
    Transaction.correctlySpends(punishTx, Seq(alice3.ourCommit.publishableTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)


    // now what if Alice published a revoked commit tx ?
    val stealTx = bob5.txDb.get(alice4.ourCommit.publishableTx.txid)
    Transaction.correctlySpends(stealTx.get, Seq(alice4.ourCommit.publishableTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    // but we cannot steal Alice's current commit tx
    assert(bob5.txDb.get(alice5.ourCommit.publishableTx.txid) == None)
  }
}
