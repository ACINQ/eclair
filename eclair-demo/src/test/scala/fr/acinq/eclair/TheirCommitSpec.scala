package fr.acinq.eclair

import fr.acinq.bitcoin.Crypto
import fr.acinq.lightning._
import lightning.locktime.Locktime.Blocks
import lightning._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

/**
 * Created by PM on 08/09/2015.
 */
@RunWith(classOf[JUnitRunner])
class TheirCommitSpec extends TestHelper {

  "Node" must {

    "handle the case where they spend the current commitment tx when in NORMAL_HIGHPRIO" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(previousCommitment))) = reachState_NOANCHOR(NORMAL_LOWPRIO)
      // we do an htlc so that both parties have a positive balance, which also inverts priority
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 1))
      val r = sha256_hash(1, 2, 1, 2)
      val rHash = Crypto.sha256(r)
      val htlc = update_add_htlc(ourRevocationHash, 40000000, rHash, locktime(Blocks(4)))
      val newState = previousCommitment.state.htlc_send(htlc)
      node ! htlc
      val update_accept(theirSig, theirRevocationHash) = expectMsgClass(classOf[update_accept])
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      node ! update_signature(ourSigForThem, ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
      val update_complete(theirRevocationPreimage) = expectMsgClass(classOf[update_complete])
      node ! CMD_GETSTATE
      expectMsg(NORMAL_HIGHPRIO)
      node ! BITCOIN_ANCHOR_SPENT(ourCommitTx)
      expectMsgClass(classOf[error])
      // TODO : test not finished !
    }

    "handle the case where they spend a revoked commitment tx when in NORMAL_HIGHPRIO" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(previousCommitment))) = reachState_NOANCHOR(NORMAL_LOWPRIO)
      // we do an htlc so that both parties have a positive balance, which also inverts priority
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 1))
      val r = sha256_hash(1, 2, 1, 2)
      val rHash = Crypto.sha256(r)
      val htlc = update_add_htlc(ourRevocationHash, 40000000, rHash, locktime(Blocks(4)))
      val newState = previousCommitment.state.htlc_send(htlc)
      node ! htlc
      val update_accept(theirSig, theirRevocationHash) = expectMsgClass(classOf[update_accept])
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      node ! update_signature(ourSigForThem, ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
      val update_complete(theirRevocationPreimage) = expectMsgClass(classOf[update_complete])
      node ! CMD_GETSTATE
      expectMsg(NORMAL_HIGHPRIO)
      node ! BITCOIN_ANCHOR_SPENT(previousCommitment.tx)
      expectMsgClass(classOf[error])
      // TODO : test not finished !
    }
  }

}
