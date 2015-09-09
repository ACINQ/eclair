package fr.acinq.eclair

import fr.acinq.bitcoin.{ScriptFlags, Transaction, Crypto}
import fr.acinq.lightning._
import lightning.locktime.Locktime.Blocks
import lightning._

import scala.util.Try

/**
 * Created by PM on 08/09/2015.
 */
class HTLCUpdateSpec extends TestHelper {

  "Node" must {

    "successfully handle an update loop in NORMAL_LOWPRIO" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(previousCommitment))) = reachState_NOANCHOR(NORMAL_LOWPRIO)
      val ourRevocationHashPreimage = ShaChain.shaChainFromSeed(ourParams.shaSeed, 1)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      val r = sha256_hash(1, 2, 1, 2)
      val rHash = Crypto.sha256(r)
      val htlc = update_add_htlc(ourRevocationHash, 1000, rHash, locktime(Blocks(4)))
      val newState = previousCommitment.state.htlc_send(htlc)
      node ! htlc
      val update_accept(theirSig, theirRevocationHash) = expectMsgClass(classOf[update_accept])
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, newState, ourRevocationHashPreimage, theirRevocationHash)
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_UPDATE_SIG_LOWPRIO)
      node ! update_signature(ourSigForThem, ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
      val update_complete(theirRevocationPreimage) = expectMsgClass(classOf[update_complete])
      node ! CMD_GETSTATE
      expectMsg(NORMAL_HIGHPRIO)
    }

    "successfully handle an update loop in NORMAL_HIGHPRIO" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(previousCommitment))) = reachState_WITHANCHOR(NORMAL_HIGHPRIO)
      val ourRevocationHashPreimage = ShaChain.shaChainFromSeed(ourParams.shaSeed, 1)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      val r = sha256_hash(1, 2, 1, 2)
      val rHash = Crypto.sha256(r)
      val htlc = update_add_htlc(ourRevocationHash, 1000, rHash, locktime(Blocks(4)))
      val newState = previousCommitment.state.htlc_send(htlc)
      node ! htlc
      val update_accept(theirSig, theirRevocationHash) = expectMsgClass(classOf[update_accept])
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, newState, ourRevocationHashPreimage, theirRevocationHash)
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
