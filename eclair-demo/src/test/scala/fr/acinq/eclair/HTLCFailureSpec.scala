package fr.acinq.eclair

import fr.acinq.bitcoin.{ScriptFlags, Transaction, Crypto}
import fr.acinq.lightning._
import lightning.locktime.Locktime.Blocks
import lightning.update_decline_htlc.Reason.{InsufficientFunds, CannotRoute}
import lightning._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

/**
 * Created by PM on 11/09/2015.
 */
@RunWith(classOf[JUnitRunner])
class HTLCFailureSpec extends TestHelper {

  "Node" must {

    "handle an update_decline_htlc when in WAIT_FOR_HTLC_ACCEPT_HIGHPRIO" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(previousCommitment))) = reachState_WITHANCHOR(NORMAL_HIGHPRIO)
      val their_r = sha256_hash(1, 2, 1, 2)
      val their_rHash = Crypto.sha256(their_r)
      node ! CMD_SEND_HTLC_UPDATE(60000000, their_rHash, locktime(Blocks(4)))
      expectMsgClass(classOf[update_add_htlc])
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_HTLC_ACCEPT_HIGHPRIO)

      node ! update_decline_htlc(CannotRoute(true))
      node ! CMD_GETSTATE
      expectMsg(NORMAL_HIGHPRIO)
    }

    "handle an update_decline_htlc when in WAIT_FOR_HTLC_ACCEPT_LOWPRIO" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(previousCommitment))) = reachState_NOANCHOR(NORMAL_LOWPRIO)
      val their_r = sha256_hash(1, 2, 1, 2)
      val their_rHash = Crypto.sha256(their_r)
      node ! CMD_SEND_HTLC_UPDATE(60000000, their_rHash, locktime(Blocks(4)))
      expectMsgClass(classOf[update_add_htlc])
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_HTLC_ACCEPT_LOWPRIO)

      node ! update_decline_htlc(InsufficientFunds(funding(None)))
      node ! CMD_GETSTATE
      expectMsg(NORMAL_LOWPRIO)
    }

    "handle an update_routefail_htlc when in NORMAL_HIGHPRIO" in {
      val (node, channelDesc0) = reachState_NOANCHOR(NORMAL_LOWPRIO)

      val (channelDesc1@ChannelDesc(Some(ourParams), Some(theirParams), Some(previousCommitment)), r) = send_htlc(node, channelDesc0, 40000000)
      val state1 = previousCommitment.state
      val their_rHash = Crypto.sha256(r)
      node ! CMD_GETSTATE
      expectMsg(NORMAL_HIGHPRIO)

      val state2 = state1.htlc_remove(their_rHash)
      val ourRevocationHash2 = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 2))
      node ! update_routefail_htlc(ourRevocationHash2, their_rHash)

      val update_accept(theirSig2, theirRevocationHash2) = expectMsgClass(classOf[update_accept])
      val (ourCommitTx2, ourSigForThem2) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, state2, ourRevocationHash2, theirRevocationHash2)
      val signedCommitTx2 = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx2, theirSig2)
      Transaction.correctlySpends(signedCommitTx2, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_UPDATE_SIG_HIGHPRIO)

      node ! update_signature(ourSigForThem2, ShaChain.shaChainFromSeed(ourParams.shaSeed, 1))
      val update_complete(theirRevocationPreimage1) = expectMsgClass(classOf[update_complete])
      node ! CMD_GETSTATE
      expectMsg(NORMAL_LOWPRIO)
    }

    "handle an update_routefail_htlc when in WAIT_FOR_HTLC_ACCEPT_LOWPRIO" in {
      // TODO
    }

    "handle an update_timedout_htlc when in NORMAL_HIGHPRIO" in {
      // TODO
    }

    "handle an update_timedout_htlc when in NORMAL_LOWPRIO" in {
      // TODO
    }

    "handle an update_timedout_htlc when in WAIT_FOR_HTLC_ACCEPT_LOWPRIO" in {
      // TODO
    }
  }

}
