package fr.acinq.eclair.channel

import fr.acinq.bitcoin._
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
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

  def addHtlc(sender: Commitments, receiver: Commitments, htlc: update_add_htlc): (Commitments, Commitments) = {
    (Commitments.sendAdd(sender, CMD_ADD_HTLC(id = Some(htlc.id), amountMsat = htlc.amountMsat, rHash = htlc.rHash, expiry = htlc.expiry))._1, Commitments.receiveAdd(receiver, htlc))
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

    // Alice publishes her current commit tx
    val tx = alice3.ourCommit.publishableTx

    // we're Bob. Check that our view of Alice's commit tx is right
    val theirTxTemplate = Commitments.makeTheirTxTemplate(bob3)
    val theirTx = theirTxTemplate.makeTx
    assert(theirTx.txOut === tx.txOut)

    // suppose we have the payment preimage, what do we do ?
    val paymentPreimage = R
    val htlcTemplate = theirTxTemplate.htlcSent.find(_.htlc.rHash == bin2sha256(H)).get

    val tx1 = Helpers.claimReceivedHtlc(tx, htlcTemplate, paymentPreimage, bob3.ourParams.finalPrivKey)
    Transaction.correctlySpends(tx1, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }
}
