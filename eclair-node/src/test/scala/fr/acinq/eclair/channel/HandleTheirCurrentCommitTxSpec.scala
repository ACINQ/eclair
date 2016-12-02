package fr.acinq.eclair.channel

import fr.acinq.bitcoin._
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.wire.AddHtlc
import lightning.locktime.Locktime.Blocks
import lightning.{locktime, routing, update_add_htlc}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class HandleTheirCurrentCommitTxSpec extends FunSuite {

  def signAndReceiveRevocation(sender: Commitments, receiver: Commitments): (Commitments, Commitments) = {
    val (sender1, commit1) = Commitments.sendCommit(sender)
    val (receiver1, rev1) = Commitments.receiveCommit(receiver, commit1)
    val sender2 = Commitments.receiveRevocation(sender1, rev1)
    (sender2, receiver1)
  }

  def addHtlc(sender: Commitments, receiver: Commitments, htlc: AddHtlc): (Commitments, Commitments) = {
    (Commitments.sendAdd(sender, CMD_ADD_HTLC(id = Some(htlc.id), amountMsat = htlc.amountMsat, rHash = htlc.paymentHash, expiry = htlc.expiry))._1, Commitments.receiveAdd(receiver, htlc))
  }

  test("claim received htlcs in their current commit tx") {
    val alice = Alice.commitments
    val bob = Bob.commitments

    val R: BinaryData = "0102030405060708010203040506070801020304050607080102030405060708"
    val H = Crypto.sha256(R)
    val R1: BinaryData = "0202030405060708010203040506070801020304050607080102030405060708"
    val H1 = Crypto.sha256(R1)

    val (alice0, bob0) = addHtlc(alice, bob, AddHtlc(0, 1, 70000000, 400, H, BinaryData("")))
    val (alice1, bob1) = addHtlc(alice0, bob0, AddHtlc(0, 2, 80000000, 350, H1, BinaryData("")))
    val (alice2, bob2) = signAndReceiveRevocation(alice1, bob1)
    val (bob3, alice3) = signAndReceiveRevocation(bob2, alice2)

    // Alice publishes her current commit tx
    val tx = alice3.ourCommit.publishableTx

    // suppose we have the payment preimage, what do we do ?
    val (bob4, _) = Commitments.sendFulfill(bob3, CMD_FULFILL_HTLC(1, R), 0)
    val (bob5, _) = Commitments.sendFulfill(bob4, CMD_FULFILL_HTLC(2, R1), 0)

    // we're Bob. Check that our view of Alice's commit tx is right
    val theirTxTemplate = Commitments.makeTheirTxTemplate(bob5)
    val theirTx = theirTxTemplate.makeTx
    assert(theirTx.txOut === tx.txOut)

    val Seq(tx1, tx2) = Helpers.claimReceivedHtlcs(tx, theirTxTemplate, bob5)
    assert(tx1.txIn.length == 1 && tx1.txOut.length == 1 && tx2.txIn.length == 1 && tx2.txOut.length == 1)
    assert(Set(tx1.txOut(0).amount, tx2.txOut(0).amount) == Set(Satoshi(70000), Satoshi(80000)))
    Transaction.correctlySpends(tx1, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(tx2, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("claim sent htlcs in their current commit tx") {
    val alice = Alice.commitments
    val bob = Bob.commitments

    val R: BinaryData = "0102030405060708010203040506070801020304050607080102030405060708"
    val H = Crypto.sha256(R)
    val R1: BinaryData = "0202030405060708010203040506070801020304050607080102030405060708"
    val H1 = Crypto.sha256(R1)

    val (alice0, bob0) = addHtlc(alice, bob, AddHtlc(0, 1, 70000000, 400, H, BinaryData("")))
    val (alice1, bob1) = addHtlc(alice0, bob0, AddHtlc(0, 1, 80000000, 350, H1, BinaryData("")))
    val (alice2, bob2) = signAndReceiveRevocation(alice1, bob1)
    val (bob3, alice3) = signAndReceiveRevocation(bob2, alice2)

    // Bob publishes his current commit tx
    val tx = bob3.ourCommit.publishableTx

    // we're Alice. Check that our view of Bob's commit tx is right
    val theirTxTemplate = Commitments.makeTheirTxTemplate(alice3)
    val theirTx = theirTxTemplate.makeTx
    assert(theirTx.txOut === tx.txOut)

    val Seq(tx1, tx2) = Helpers.claimSentHtlcs(tx, theirTxTemplate, alice3)
    assert(tx1.txIn.length == 1 && tx1.txOut.length == 1 && tx2.txIn.length == 1 && tx2.txOut.length == 1)
    assert(Set(tx1.txOut(0).amount, tx2.txOut(0).amount) == Set(Satoshi(70000), Satoshi(80000)))
    Transaction.correctlySpends(tx1, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(tx2, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }
}
