package fr.acinq.eclair.channel

import fr.acinq.bitcoin.{BinaryData, Crypto, ScriptFlags, Transaction}
import fr.acinq.eclair._
import lightning.locktime.Locktime.Blocks
import lightning.{locktime, routing, update_add_htlc}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import TestConstants._

@RunWith(classOf[JUnitRunner])
class StealRevokedCommitmentSpec extends FunSuite {

  def signAndReceiveRevocation(sender: Commitments, receiver: Commitments): (Commitments, Commitments) = {
    val (sender1, commit1) = Commitments.sendCommit(sender)
    val (receiver1, rev1) = Commitments.receiveCommit(receiver, commit1)
    val sender2 = Commitments.receiveRevocation(sender1, rev1)
    (sender2, receiver1)
  }

  def addHtlc(sender: Commitments, receiver: Commitments, htlc: update_add_htlc): (Commitments, Commitments) = {
    (Commitments.addOurProposal(sender, htlc), Commitments.addTheirProposal(receiver, htlc))
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

    // now what if Alice published a revoked commit tx ?
    Seq(alice1, alice2, alice3, alice4).map(alice => {
      val stealTx = Helpers.claimTheirRevokedCommit(alice.ourCommit.publishableTx, bob5)
      Transaction.correctlySpends(stealTx.get, Seq(alice.ourCommit.publishableTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    })

    // but we cannot steal Alice's current commit tx
    assert(Helpers.claimTheirRevokedCommit(alice5.ourCommit.publishableTx, bob5) == None)
  }
}
