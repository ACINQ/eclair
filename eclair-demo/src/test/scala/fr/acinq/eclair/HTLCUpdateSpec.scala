package fr.acinq.eclair

import fr.acinq.bitcoin.{ScriptFlags, Transaction, Crypto}
import fr.acinq.lightning._
import lightning.locktime.Locktime.Blocks
import lightning._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.util.Try

/**
 * Created by PM on 08/09/2015.
 */
@RunWith(classOf[JUnitRunner])
class HTLCUpdateSpec extends TestHelper {

  "Node" must {

    "successfully handle an update loop in NORMAL_LOWPRIO" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(previousCommitment))) = reachState_NOANCHOR(NORMAL_LOWPRIO)
      val ourRevocationHash1 = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 1))
      val r = sha256_hash(1, 2, 1, 2)
      val rHash = Crypto.sha256(r)
      val htlc = update_add_htlc(ourRevocationHash1, 40000000, rHash, locktime(Blocks(4)))
      val state1 = previousCommitment.state.htlc_send(htlc)

      node ! htlc
      val update_accept(theirSig1, theirRevocationHash1) = expectMsgClass(classOf[update_accept])
      val (ourCommitTx1, ourSigForThem1) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, state1, ourRevocationHash1, theirRevocationHash1)
      val signedCommitTx1 = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx1, theirSig1)
      Transaction.correctlySpends(signedCommitTx1, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_UPDATE_SIG_LOWPRIO)

      node ! update_signature(ourSigForThem1, ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
      val update_complete(theirRevocationPreimage0) = expectMsgClass(classOf[update_complete])
      node ! CMD_GETSTATE
      expectMsg(NORMAL_HIGHPRIO)
      val ourRevocationHash2 = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 2))
      val state2 = state1.htlc_fulfill(r)

      node ! update_fulfill_htlc(ourRevocationHash2, r)
      val update_accept(theirSig2, theirRevocationHash2) = expectMsgClass(classOf[update_accept])
      val (ourCommitTx2, ourSigForThem2) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, state2, ourRevocationHash2, theirRevocationHash2)
      val signedCommitTx2 = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx2, theirSig2)
      Transaction.correctlySpends(signedCommitTx2, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_UPDATE_SIG_HIGHPRIO)

      node ! update_signature(ourSigForThem2, ShaChain.shaChainFromSeed(ourParams.shaSeed, 1))
      val update_complete(theirRevocationPreimage1) = expectMsgClass(classOf[update_complete])
      assert(Crypto.sha256(theirRevocationPreimage1).deep === sha2562bin(theirRevocationHash1).data.toArray.deep)
      node ! CMD_GETSTATE
      expectMsg(NORMAL_LOWPRIO)
    }

    "successfully handle an update loop in NORMAL_HIGHPRIO" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(previousCommitment))) = reachState_WITHANCHOR(NORMAL_HIGHPRIO)
      val ourRevocationHash1 = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 1))
      val r = sha256_hash(1, 2, 1, 2)
      val rHash = Crypto.sha256(r)
      val htlc = update_add_htlc(ourRevocationHash1, 1000, rHash, locktime(Blocks(4)))
      val newState = previousCommitment.state.htlc_send(htlc)
      node ! htlc
      val update_accept(theirSig, theirRevocationHash) = expectMsgClass(classOf[update_accept])
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, newState, ourRevocationHash1, theirRevocationHash)
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_UPDATE_SIG_HIGHPRIO)
      node ! update_signature(ourSigForThem, ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
      val update_complete(theirRevocationPreimage) = expectMsgClass(classOf[update_complete])
      node ! CMD_GETSTATE
      expectMsg(NORMAL_LOWPRIO)
    }

  }

}
