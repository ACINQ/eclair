package fr.acinq.eclair.channel

import akka.actor.{ActorRef, LoggingFSM, Props}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin.{OutPoint, _}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.Helpers._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.bitcoin.Crypto.sha256
import lightning._
import lightning.open_channel.anchor_offer.{WILL_CREATE_ANCHOR, WONT_CREATE_ANCHOR}

import scala.util.{Failure, Success, Try}

/**
  * Created by PM on 20/08/2015.
  */

object Channel {
  def props(them: ActorRef, blockchain: ActorRef, params: OurChannelParams, theirNodeId: String = Hash.Zeroes.toString()) = Props(new Channel(them, blockchain, params, theirNodeId))
}

class Channel(val them: ActorRef, val blockchain: ActorRef, val params: OurChannelParams, theirNodeId: String) extends LoggingFSM[State, Data] {

  log.info(s"commit pubkey: ${params.commitPubKey}")
  log.info(s"final pubkey: ${params.finalPubKey}")

  params.anchorAmount match {
    case None =>
      them ! open_channel(params.delay, sha256(ShaChain.shaChainFromSeed(params.shaSeed, 0)), sha256(ShaChain.shaChainFromSeed(params.shaSeed, 1)), params.commitPubKey, params.finalPubKey, WONT_CREATE_ANCHOR, Some(params.minDepth), params.initialFeeRate)
      startWith(OPEN_WAIT_FOR_OPEN_NOANCHOR, DATA_OPEN_WAIT_FOR_OPEN(params))
    case _ =>
      them ! open_channel(params.delay, sha256(ShaChain.shaChainFromSeed(params.shaSeed, 0)), sha256(ShaChain.shaChainFromSeed(params.shaSeed, 1)), params.commitPubKey, params.finalPubKey, WILL_CREATE_ANCHOR, Some(params.minDepth), params.initialFeeRate)
      startWith(OPEN_WAIT_FOR_OPEN_WITHANCHOR, DATA_OPEN_WAIT_FOR_OPEN(params))
  }

  /*
          8888888 888b    888 8888888 88888888888
            888   8888b   888   888       888
            888   88888b  888   888       888
            888   888Y88b 888   888       888
            888   888 Y88b888   888       888
            888   888  Y88888   888       888
            888   888   Y8888   888       888
          8888888 888    Y888 8888888     888
   */
  when(OPEN_WAIT_FOR_OPEN_NOANCHOR) {
    case Event(open_channel(delay, theirRevocationHash, theirNextRevocationHash, commitKey, finalKey, WILL_CREATE_ANCHOR, minDepth, initialFeeRate), DATA_OPEN_WAIT_FOR_OPEN(ourParams)) =>
      val theirParams = TheirChannelParams(delay, commitKey, finalKey, minDepth, initialFeeRate)
      goto(OPEN_WAIT_FOR_ANCHOR) using DATA_OPEN_WAIT_FOR_ANCHOR(ourParams, theirParams, theirRevocationHash, theirNextRevocationHash)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAIT_FOR_OPEN_WITHANCHOR) {
    case Event(open_channel(delay, theirRevocationHash, theirNextRevocationHash, commitKey, finalKey, WONT_CREATE_ANCHOR, minDepth, initialFeeRate), DATA_OPEN_WAIT_FOR_OPEN(ourParams)) =>
      val theirParams = TheirChannelParams(delay, commitKey, finalKey, minDepth, initialFeeRate)
      log.debug(s"their params: $theirParams")
      blockchain ! MakeAnchor(params.commitPubKey, theirParams.commitPubKey, ourParams.anchorAmount.get)
      stay using DATA_OPEN_WITH_ANCHOR_WAIT_FOR_ANCHOR(ourParams, theirParams, theirRevocationHash, theirNextRevocationHash)

    case Event((anchorTx: Transaction, anchorOutputIndex: Int), DATA_OPEN_WITH_ANCHOR_WAIT_FOR_ANCHOR(ourParams, theirParams, theirRevocationHash, theirNextRevocationHash)) =>
      log.info(s"anchor txid=${anchorTx.txid}")
      val amount = anchorTx.txOut(anchorOutputIndex).amount.toLong
      val theirSpec = CommitmentSpec(Set.empty[Htlc], feeRate = theirParams.initialFeeRate, initial_amount_us_msat = 0, initial_amount_them_msat = amount * 1000, amount_us_msat = 0, amount_them_msat = amount * 1000)
      them ! open_anchor(anchorTx.hash, anchorOutputIndex, amount)
      goto(OPEN_WAIT_FOR_COMMIT_SIG) using DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams, theirParams, anchorTx, anchorOutputIndex, TheirCommit(0, theirSpec, theirRevocationHash), theirNextRevocationHash)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAIT_FOR_ANCHOR) {
    case Event(open_anchor(anchorTxHash, anchorOutputIndex, anchorAmount), DATA_OPEN_WAIT_FOR_ANCHOR(ourParams, theirParams, theirRevocationHash, theirNextRevocationHash)) =>
      val anchorTxid = anchorTxHash.reverse //see https://github.com/ElementsProject/lightning/issues/17

      val anchorOutput = TxOut(Satoshi(anchorAmount), publicKeyScript = Scripts.anchorPubkeyScript(ourParams.commitPubKey, theirParams.commitPubKey))

      // they fund the channel with their anchor tx, so the money is theirs
      val ourSpec = CommitmentSpec(Set.empty[Htlc], feeRate = ourParams.initialFeeRate, initial_amount_them_msat = anchorAmount * 1000, initial_amount_us_msat = 0, amount_them_msat = anchorAmount * 1000, amount_us_msat = 0)
      val theirSpec = CommitmentSpec(Set.empty[Htlc], feeRate = theirParams.initialFeeRate, initial_amount_them_msat = 0, initial_amount_us_msat = anchorAmount * 1000, amount_them_msat = 0, amount_us_msat = anchorAmount * 1000)

      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
      val ourTx = makeOurTx(ourParams, theirParams, TxIn(OutPoint(anchorTxHash, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, ourRevocationHash, ourSpec)
      val theirTx = makeTheirTx(ourParams, theirParams, TxIn(OutPoint(anchorTxHash, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, theirRevocationHash, theirSpec)
      log.info(s"signing their tx: $theirTx")
      val ourSigForThem = sign(ourParams, theirParams, anchorAmount, theirTx)
      them ! open_commit_sig(ourSigForThem)
      blockchain ! WatchConfirmed(self, anchorTxid, ourParams.minDepth, BITCOIN_ANCHOR_DEPTHOK)
      blockchain ! WatchSpent(self, anchorTxid, anchorOutputIndex, 0, BITCOIN_ANCHOR_SPENT)
      // FIXME: ourTx is not signed by them and cannot be published
      val commitments = Commitments(ourParams, theirParams, OurCommit(0, ourSpec, ourTx), TheirCommit(0, theirSpec, theirRevocationHash), OurChanges(Nil, Nil, Nil), TheirChanges(Nil, Nil), Some(theirNextRevocationHash), anchorOutput)
      goto(OPEN_WAITING_THEIRANCHOR) using DATA_OPEN_WAITING(commitments, ShaChain.init, None)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAIT_FOR_COMMIT_SIG) {
    case Event(open_commit_sig(theirSig), DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams, theirParams, anchorTx, anchorOutputIndex, theirCommitment, theirNextRevocationHash)) =>
      val anchorAmount = anchorTx.txOut(anchorOutputIndex).amount.toLong
      val theirSpec = theirCommitment.spec
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
      val ourSpec = CommitmentSpec(Set.empty[Htlc], feeRate = ourParams.initialFeeRate, initial_amount_us_msat = anchorAmount * 1000, initial_amount_them_msat = 0, amount_us_msat = anchorAmount * 1000, amount_them_msat = 0)
      val ourTx = makeOurTx(ourParams, theirParams, TxIn(OutPoint(anchorTx, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, ourRevocationHash, ourSpec)
      log.info(s"checking our tx: $ourTx")
      val ourSig = sign(ourParams, theirParams, anchorAmount, ourTx)
      val signedTx: Transaction = addSigs(ourParams, theirParams, anchorAmount, ourTx, ourSig, theirSig)
      val anchorOutput = anchorTx.txOut(anchorOutputIndex)
      checksig(ourParams, theirParams, anchorOutput, signedTx) match {
        case Failure(cause) =>
          log.error(cause, "their open_commit_sig message contains an invalid signature")
          them ! error(Some("Bad signature"))
          goto(CLOSED)
        case Success(_) =>
          blockchain ! WatchConfirmed(self, anchorTx.txid, ourParams.minDepth, BITCOIN_ANCHOR_DEPTHOK)
          blockchain ! WatchSpent(self, anchorTx.txid, anchorOutputIndex, 0, BITCOIN_ANCHOR_SPENT)
          blockchain ! Publish(anchorTx)
          val commitments = Commitments(ourParams, theirParams, OurCommit(0, ourSpec, signedTx), theirCommitment, OurChanges(Nil, Nil, Nil), TheirChanges(Nil, Nil), Some(theirNextRevocationHash), anchorOutput)
          goto(OPEN_WAITING_OURANCHOR) using DATA_OPEN_WAITING(commitments, ShaChain.init, None)
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAITING_THEIRANCHOR) {
    case Event(BITCOIN_ANCHOR_DEPTHOK, d@DATA_OPEN_WAITING(commitments, shaChain, deferred)) =>
      blockchain ! WatchLost(self, commitments.anchorId, commitments.ourParams.minDepth, BITCOIN_ANCHOR_LOST)
      them ! open_complete(None)
      deferred.map(self ! _)
      //TODO htlcIdx should not be 0 when resuming connection
      goto(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) using DATA_NORMAL(commitments, shaChain, 0, None)

    case Event(msg@open_complete(blockId_opt), d: DATA_OPEN_WAITING) =>
      log.info(s"received their open_complete, deferring message")
      stay using d.copy(deferred = Some(msg))

    case Event(BITCOIN_ANCHOR_TIMEOUT, _) =>
      them ! error(Some("Anchor timed out"))
      goto(ERR_ANCHOR_TIMEOUT)

    /*case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      blockchain ! Publish(finalTx)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)*/

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_OPEN_WAITING) if (isTheirCommit(tx, d.commitments.ourParams, d.commitments.theirParams, d.commitments.theirCommit)) =>
      them ! handle_theircommit(tx, d.commitments.ourParams, d.commitments.theirParams, d.shaChain, d.commitments.theirCommit)
      goto(CLOSING) using DATA_CLOSING(d.commitments.ourParams, d.commitments.theirParams, d.shaChain, d.commitments.ourCommit, d.commitments.theirCommit, theirCommitPublished = Some(tx))

    case Event(BITCOIN_ANCHOR_SPENT, _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.ourCommit)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.ourCommit, d.theirCommit, ourCommitPublished = Some(d.ourCommit.publishableTx))
  }

  when(OPEN_WAITING_OURANCHOR) {
    case Event(BITCOIN_ANCHOR_DEPTHOK, d@DATA_OPEN_WAITING(commitments, shaChain, deferred)) =>
      blockchain ! WatchLost(self, commitments.anchorId, commitments.ourParams.minDepth, BITCOIN_ANCHOR_LOST)
      them ! open_complete(None)
      deferred.map(self ! _)
      //TODO htlcIdx should not be 0 when resuming connection
      goto(OPEN_WAIT_FOR_COMPLETE_OURANCHOR) using DATA_NORMAL(commitments, shaChain, 0, None)

    case Event(msg@open_complete(blockId_opt), d: DATA_OPEN_WAITING) =>
      log.info(s"received their open_complete, deferring message")
      stay using d.copy(deferred = Some(msg))

    /*case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      blockchain ! Publish(finalTx)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)*/

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_OPEN_WAITING) if (isTheirCommit(tx, d.commitments.ourParams, d.commitments.theirParams, d.commitments.theirCommit)) =>
      them ! handle_theircommit(tx, d.commitments.ourParams, d.commitments.theirParams, d.shaChain, d.commitments.theirCommit)
      goto(CLOSING) using DATA_CLOSING(d.commitments.ourParams, d.commitments.theirParams, d.shaChain, d.commitments.ourCommit, d.commitments.theirCommit, theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, _), _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.ourCommit)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.ourCommit, d.theirCommit, ourCommitPublished = Some(d.ourCommit.publishableTx))
  }

  when(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) {
    case Event(open_complete(blockid_opt), d: DATA_NORMAL) =>
      Register.create_alias(theirNodeId, d.commitments.anchorId)
      goto(NORMAL)

    /*case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      blockchain ! Publish(finalTx)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)*/

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_NORMAL) if (isTheirCommit(tx, d.commitments.ourParams, d.commitments.theirParams, d.commitments.theirCommit)) =>
      them ! handle_theircommit(tx, d.commitments.ourParams, d.commitments.theirParams, d.shaChain, d.commitments.theirCommit)
      goto(CLOSING) using DATA_CLOSING(d.commitments.ourParams, d.commitments.theirParams, d.shaChain, d.commitments.ourCommit, d.commitments.theirCommit, theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, _), _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: DATA_NORMAL) =>
      publish_ourcommit(d.commitments.ourCommit)
      goto(CLOSING) using DATA_CLOSING(d.commitments.ourParams, d.commitments.theirParams, d.shaChain, d.commitments.ourCommit, d.commitments.theirCommit, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
  }

  when(OPEN_WAIT_FOR_COMPLETE_OURANCHOR) {

    case Event(open_complete(blockid_opt), d: DATA_NORMAL) =>
      Register.create_alias(theirNodeId, d.commitments.anchorId)
      goto(NORMAL)

    /*case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      blockchain ! Publish(finalTx)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)*/

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_NORMAL) if (isTheirCommit(tx, d.commitments.ourParams, d.commitments.theirParams, d.commitments.theirCommit)) =>
      them ! handle_theircommit(tx, d.commitments.ourParams, d.commitments.theirParams, d.shaChain, d.commitments.theirCommit)
      goto(CLOSING) using DATA_CLOSING(d.commitments.ourParams, d.commitments.theirParams, d.shaChain, d.commitments.ourCommit, d.commitments.theirCommit, theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, _), _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: DATA_NORMAL) =>
      publish_ourcommit(d.commitments.ourCommit)
      goto(CLOSING) using DATA_CLOSING(d.commitments.ourParams, d.commitments.theirParams, d.shaChain, d.commitments.ourCommit, d.commitments.theirCommit, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
  }


  /*
          888b     d888        d8888 8888888 888b    888      888      .d88888b.   .d88888b.  8888888b.
          8888b   d8888       d88888   888   8888b   888      888     d88P" "Y88b d88P" "Y88b 888   Y88b
          88888b.d88888      d88P888   888   88888b  888      888     888     888 888     888 888    888
          888Y88888P888     d88P 888   888   888Y88b 888      888     888     888 888     888 888   d88P
          888 Y888P 888    d88P  888   888   888 Y88b888      888     888     888 888     888 8888888P"
          888  Y8P  888   d88P   888   888   888  Y88888      888     888     888 888     888 888
          888   "   888  d8888888888   888   888   Y8888      888     Y88b. .d88P Y88b. .d88P 888
          888       888 d88P     888 8888888 888    Y888      88888888 "Y88888P"   "Y88888P"  888
   */

  when(NORMAL) {

    case Event(CMD_ADD_HTLC(amount, rHash, expiry, nodeIds, origin), d@DATA_NORMAL(commitments, _, htlcIdx, _)) =>
      // TODO: should we take pending htlcs into account?
      // TODO: assert(commitment.state.commit_changes(staged).us.pay_msat >= amount, "insufficient funds!")
      // TODO: nodeIds are ignored
      val htlc = update_add_htlc(htlcIdx + 1, amount, rHash, expiry, routing(ByteString.EMPTY))
      them ! htlc
      stay using d.copy(htlcIdx = htlc.id, commitments = commitments.addOurProposal(htlc))

    case Event(htlc@update_add_htlc(htlcId, amount, rHash, expiry, nodeIds), d@DATA_NORMAL(commitments, _, _, _)) =>
      // TODO: should we take pending htlcs into account?
      // assert(commitment.state.commit_changes(staged).them.pay_msat >= amount, "insufficient funds!") // TODO : we should fail the channel
      // TODO: nodeIds are ignored
      stay using d.copy(commitments = commitments.addTheirProposal(htlc))

    case Event(CMD_FULFILL_HTLC(id, r), d: DATA_NORMAL) =>
      val (commitments1, fullfill) = Commitments.sendFulfill(d.commitments, CMD_FULFILL_HTLC(id, r))
      them ! fullfill
      stay using d.copy(commitments = commitments1)

    case Event(fulfill@update_fulfill_htlc(id, r), d: DATA_NORMAL) =>
      stay using d.copy(commitments = Commitments.receiveFulfill(d.commitments, fulfill))

    case Event(CMD_FAIL_HTLC(id, reason), d: DATA_NORMAL) =>
      val (commitments1, fail) = Commitments.sendFail(d.commitments, CMD_FAIL_HTLC(id, reason))
      them ! fail
      stay using d.copy(commitments = commitments1)

    case Event(fail@update_fail_htlc(id, reason), d: DATA_NORMAL) =>
      stay using d.copy(commitments = Commitments.receiveFail(d.commitments, fail))

    case Event(CMD_SIGN, d: DATA_NORMAL) =>
      val (commitments1, commit) = Commitments.sendCommit(d.commitments)
      them ! commit
      stay using d.copy(commitments = commitments1)

    case Event(msg@update_commit(theirSig), d: DATA_NORMAL) =>
      Try(Commitments.receiveCommit(d.commitments, msg)) match {
        case Success((commitments1, revocation)) =>
          them ! revocation
          stay using d.copy(commitments = commitments1)
        case Failure(cause) =>
          log.error(cause, "received a bad signature")
          them ! error(Some("Bad signature"))
          publish_ourcommit(d.commitments.ourCommit)
          goto(CLOSING) using DATA_CLOSING(d.commitments.ourParams, d.commitments.theirParams, d.shaChain, d.commitments.ourCommit, d.commitments.theirCommit, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
      }

    case Event(msg@update_revocation(revocationPreimage, nextRevocationHash), d: DATA_NORMAL) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked
      //TODO : check rev pre image is valid
      stay using d.copy(commitments = Commitments.receiveRevocation(d.commitments, msg))

    case Event(theirClearing@close_clearing(theirScriptPubKey), d@DATA_NORMAL(commitments, _, _, ourClearingOpt)) =>
      val ourClearing: close_clearing = ourClearingOpt.getOrElse {
        val ourScriptPubKey: BinaryData = Script.write(Scripts.pay2pkh(commitments.ourParams.finalPubKey))
        log.info(s"our final tx can be redeemed with ${Base58Check.encode(Base58.Prefix.SecretKeyTestnet, d.commitments.ourParams.finalPrivKey)}")
        them ! close_clearing(ourScriptPubKey)
        close_clearing(ourScriptPubKey)
      }
      if (commitments.hasNoPendingHtlcs) {
        val (finalTx, ourCloseSig) = makeFinalTx(commitments, ourClearing.scriptPubkey, theirScriptPubKey)
        them ! ourCloseSig
        goto(NEGOCIATING) using DATA_NEGOCIATING(commitments, d.shaChain, d.htlcIdx, ourClearing, theirClearing, ourCloseSig)
      } else {
        goto(CLEARING) using DATA_CLEARING(commitments, d.shaChain, d.htlcIdx, ourClearing, theirClearing)
      }

    case Event(CMD_CLOSE(scriptPubKeyOpt), d: DATA_NORMAL) =>
      val ourScriptPubKey: BinaryData = scriptPubKeyOpt.getOrElse {
        log.info(s"our final tx can be redeemed with ${Base58Check.encode(Base58.Prefix.SecretKeyTestnet, d.commitments.ourParams.finalPrivKey)}")
        Script.write(Scripts.pay2pkh(d.commitments.ourParams.finalPubKey))
      }
      val ourCloseClearing = close_clearing(ourScriptPubKey)
      them ! ourCloseClearing
      stay using d.copy(ourClearing = Some(ourCloseClearing))

    /*case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      blockchain ! Publish(finalTx)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_NORMAL) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, revokedPublished = tx :: Nil)

    case Event((BITCOIN_ANCHOR_SPENT, _), _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))*/
  }


  when(CLEARING) {
    case Event(CMD_FULFILL_HTLC(id, r), d: DATA_CLEARING) =>
      val (commitments1, fullfill) = Commitments.sendFulfill(d.commitments, CMD_FULFILL_HTLC(id, r))
      them ! fullfill
      stay using d.copy(commitments = commitments1)

    case Event(fulfill@update_fulfill_htlc(id, r), d: DATA_CLEARING) =>
      stay using d.copy(commitments = Commitments.receiveFulfill(d.commitments, fulfill))

    case Event(CMD_FAIL_HTLC(id, reason), d: DATA_CLEARING) =>
      val (commitments1, fail) = Commitments.sendFail(d.commitments, CMD_FAIL_HTLC(id, reason))
      them ! fail
      stay using d.copy(commitments = commitments1)

    case Event(fail@update_fail_htlc(id, reason), d: DATA_CLEARING) =>
      stay using d.copy(commitments = Commitments.receiveFail(d.commitments, fail))

    case Event(CMD_SIGN, d: DATA_CLEARING) =>
      val (commitments1, commit) = Commitments.sendCommit(d.commitments)
      them ! commit
      stay using d.copy(commitments = commitments1)

    case Event(msg@update_commit(theirSig), d@DATA_CLEARING(commitments, _, _, ourClearing, theirClearing)) =>
      Try(Commitments.receiveCommit(d.commitments, msg)) match {
        case Success((commitments1, revocation)) =>
          them ! revocation
          if (commitments1.hasNoPendingHtlcs) {
            val (finalTx, ourCloseSig) = makeFinalTx(commitments1, ourClearing.scriptPubkey, theirClearing.scriptPubkey)
            them ! ourCloseSig
            goto(NEGOCIATING) using DATA_NEGOCIATING(commitments1, d.shaChain, d.htlcIdx, ourClearing, theirClearing, ourCloseSig)
          } else {
            stay using d.copy(commitments = commitments1)
          }
        case Failure(cause) =>
          log.error(cause, "received a bad signature")
          them ! error(Some("Bad signature"))
          publish_ourcommit(d.commitments.ourCommit)
          goto(CLOSING) using DATA_CLOSING(d.commitments.ourParams, d.commitments.theirParams, d.shaChain, d.commitments.ourCommit, d.commitments.theirCommit, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
      }


    case Event(msg@update_revocation(revocationPreimage, nextRevocationHash), d@DATA_CLEARING(commitments, _, _, ourClearing, theirClearing)) =>
      val commitments1 = Commitments.receiveRevocation(commitments, msg)
      if (commitments1.hasNoPendingHtlcs) {
        val (finalTx, ourCloseSig) = makeFinalTx(commitments1, ourClearing.scriptPubkey, theirClearing.scriptPubkey)
        them ! ourCloseSig
        goto(NEGOCIATING) using DATA_NEGOCIATING(commitments1, d.shaChain, d.htlcIdx, ourClearing, theirClearing, ourCloseSig)
      } else {
        stay using d.copy(commitments = commitments1)
      }
  }

  when(NEGOCIATING) {
    case Event(close_signature(theirCloseFee, theirSig), d: DATA_NEGOCIATING) if theirCloseFee == d.ourSignature.closeFee =>
      checkCloseSignature(theirSig, Satoshi(theirCloseFee), d) match {
        case Success(signedTx) =>
          blockchain ! Publish(signedTx)
          blockchain ! WatchConfirmed(self, signedTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
          goto(CLOSING) using DATA_CLOSING(d.commitments.ourParams, d.commitments.theirParams, d.shaChain, d.commitments.ourCommit, d.commitments.theirCommit, mutualClosePublished = Some(signedTx))
        case Failure(cause) =>
          log.error(cause, "cannot verify their close signature")
          throw new RuntimeException("cannot verify their close signature", cause)
      }

    case Event(close_signature(theirCloseFee, theirSig), d: DATA_NEGOCIATING) =>
      checkCloseSignature(theirSig, Satoshi(theirCloseFee), d) match {
        case Success(_) =>
          val closeFee = ((theirCloseFee + d.ourSignature.closeFee) / 4) * 2 match {
            case value if value == d.ourSignature.closeFee => value + 2
            case value => value
          }
          val (finalTx, ourCloseSig) = makeFinalTx(d.commitments, d.ourClearing.scriptPubkey, d.theirClearing.scriptPubkey, Satoshi(closeFee))
          them ! ourCloseSig
          if (closeFee == theirCloseFee) {
            val signedTx = addSigs(d.commitments.ourParams, d.commitments.theirParams, d.commitments.anchorOutput.amount.toLong, finalTx, ourCloseSig.sig, theirSig)
            blockchain ! Publish(signedTx)
            blockchain ! WatchConfirmed(self, signedTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
            goto(CLOSING) using DATA_CLOSING(d.commitments.ourParams, d.commitments.theirParams, d.shaChain, d.commitments.ourCommit, d.commitments.theirCommit, mutualClosePublished = Some(signedTx))
          } else {
            stay using d.copy(ourSignature = ourCloseSig)
          }
        case Failure(cause) =>
          log.error(cause, "cannot verify their close signature")
          throw new RuntimeException("cannot verify their close signature", cause)
      }
  }
  /*
           .d8888b.  888      .d88888b.   .d8888b. 8888888 888b    888  .d8888b.
          d88P  Y88b 888     d88P" "Y88b d88P  Y88b  888   8888b   888 d88P  Y88b
          888    888 888     888     888 Y88b.       888   88888b  888 888    888
          888        888     888     888  "Y888b.    888   888Y88b 888 888
          888        888     888     888     "Y88b.  888   888 Y88b888 888  88888
          888    888 888     888     888       "888  888   888  Y88888 888    888
          Y88b  d88P 888     Y88b. .d88P Y88b  d88P  888   888   Y8888 Y88b  d88P
           "Y8888P"  88888888 "Y88888P"   "Y8888P" 8888888 888    Y888  "Y8888P88
   */

  when(CLOSING) {
    case Event(close_signature(theirCloseFee, theirSig), d: DATA_NEGOCIATING) if theirCloseFee == d.ourSignature.closeFee =>
      stay()

    case Event(close_signature(theirCloseFee, theirSig), d: DATA_NEGOCIATING) =>
      throw new RuntimeException(s"unexpected closing fee: $theirCloseFee ours is ${d.ourSignature.closeFee}")

    case Event(BITCOIN_CLOSE_DONE, _) => goto(CLOSED)
  }
  /*def clearing_handler: StateFunction = {
    case Event(htlc@update_add_htlc(htlcId, amount, rHash, expiry, nodeIds), d@DATA_CLEARING(ack_in, _, _, _, _, staged, commitment, _, _)) =>
      // TODO : should we take pending htlcs into account?
      assert(commitment.state.commit_changes(staged).them.pay_msat >= amount, "insufficient funds!") // TODO : we should fail the channel
      // TODO nodeIds are ignored
      stay using d.copy(ack_in = ack_in + 1, staged = staged :+ Change(IN, ack_in + 1, htlc))

    case Event(fulfill@update_fulfill_htlc(id, r), d@DATA_CLEARING(ack_in, _, _, _, _, staged, commitment, _, _)) =>
      assert(commitment.state.commit_changes(staged).them.htlcs_received.exists(_.id == id), s"unknown htlc id=$id") // TODO : we should fail the channel
      stay using d.copy(ack_in = ack_in + 1, staged = staged :+ Change(IN, ack_in + 1, fulfill))

    case Event(fail@update_fail_htlc(id, reason), d@DATA_CLEARING(ack_in, _, _, _, _, staged, commitment, _, _)) =>
      assert(commitment.state.commit_changes(staged).them.htlcs_received.exists(_.id == id), s"unknown htlc id=$id") // TODO : we should fail the channel
      stay using d.copy(ack_in = ack_in + 1, staged = staged :+ Change(IN, ack_in + 1, fail))

    case Event(CMD_FULFILL_HTLC(id, r), d@DATA_CLEARING(_, ack_out, _, _, _, staged, commitment, _, _)) =>
      assert(commitment.state.commit_changes(staged).us.htlcs_received.exists(_.id == id), s"unknown htlc id=$id") // TODO : we should fail the channel
    val fulfill = update_fulfill_htlc(id, r)
      them ! fulfill
      stay using d.copy(ack_out = ack_out + 1, staged = staged :+ Change(OUT, ack_out + 1, fulfill))

    case Event(fulfill@update_fulfill_htlc(id, r), d@DATA_CLEARING(ack_in, _, _, _, _, staged, commitment, _, _)) =>
      assert(commitment.state.commit_changes(staged).them.htlcs_received.exists(_.id == id), s"unknown htlc id=$id") // TODO : we should fail the channel
      stay using d.copy(ack_in = ack_in + 1, staged = staged :+ Change(IN, ack_in + 1, fulfill))

    case Event(CMD_FAIL_HTLC(id, reason), d@DATA_CLEARING(_, ack_out, _, _, _, staged, commitment, _, _)) =>
      assert(commitment.state.commit_changes(staged).us.htlcs_received.exists(_.id == id), s"unknown htlc id=$id") // TODO : we should fail the channel
    val fail = update_fail_htlc(id, fail_reason(ByteString.copyFromUtf8(reason)))
      them ! fail
      stay using d.copy(ack_out = ack_out + 1, staged = staged :+ Change(OUT, ack_out + 1, fail))

    case Event(fail@update_fail_htlc(id, reason), d@DATA_CLEARING(ack_in, _, _, _, _, staged, commitment, _, _)) =>
      assert(commitment.state.commit_changes(staged).them.htlcs_received.exists(_.id == id), s"unknown htlc id=$id") // TODO : we should fail the channel
      stay using d.copy(ack_in = ack_in + 1, staged = staged :+ Change(IN, ack_in + 1, fail))

    case Event(clearing@close_clearing(theirScriptPubKey), d@DATA_CLEARING(ack_in, ack_out, ourParams, theirParams, shaChain, staged, commitment, _, ClosingData(_, None))) =>
      val closing = d.closing.copy(theirScriptPubKey = Some(theirScriptPubKey))
      if (commitment.state.them.htlcs_received.size == 0 && commitment.state.us.htlcs_received.size == 0) {
        val finalTx = makeFinalTx(commitment.tx.txIn, ourParams.finalPubKey, theirParams.finalPubKey, commitment.state) //TODO ADJUST FEES
        val ourSig = bin2signature(Transaction.signInput(finalTx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
        them ! close_signature(closeFee, ourSig)
        goto(CLOSE_NEGOTIATING) using DATA_NEGOTIATING(ack_in + 1, ack_out + 1, ourParams, theirParams, shaChain, commitment, closing)
      } else {
        stay using d.copy(ack_in = ack_in + 1, closing = closing)
      }
  }

  when(CLOSE_CLEARING)(clearing_handler orElse {
    case Event(CMD_SIGN, d@DATA_CLEARING(ack_in, ack_out, ourParams, theirParams, shaChain, staged, previousCommitment, ReadyForSig(theirNextRevocationHash), _)) =>
      val proposal = UpdateProposal(previousCommitment.index + 1, previousCommitment.state.commit_changes(staged), theirNextRevocationHash)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, proposal.index))
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, proposal.state, ourRevocationHash, theirNextRevocationHash)
      them ! update_commit(ourSigForThem, ack_in)
      goto(CLOSE_CLEARING_WAIT_FOR_REV) using d.copy(ack_out = ack_out + 1, staged = Nil, next = WaitForRev(proposal))

    case Event(msg@update_commit(theirSig, theirAck), d@DATA_CLEARING(ack_in, ack_out, ourParams, theirParams, shaChain, staged, previousCommitment, ReadyForSig(theirNextRevocationHash), _)) =>
      // counterparty initiated a new commitment
      val committed_changes = staged.filter(c => c.direction == IN || c.ack <= theirAck)
      val uncommitted_changes = staged.filterNot(committed_changes.contains(_))
      // TODO : we should check that this is the correct state (see acknowledge discussion)
      val proposal = UpdateProposal(previousCommitment.index + 1, previousCommitment.state.commit_changes(committed_changes), theirNextRevocationHash)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, proposal.index))
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, proposal.state, ourRevocationHash, proposal.theirRevocationHash)
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          publish_ourcommit(previousCommitment)
          goto(CLOSING) using DATA_CLOSING(ack_in = ack_in + 1, ack_out = ack_out + 1, ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
        case true =>
          val preimage = ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index)
          val ourNextRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, proposal.index + 1))
          them ! update_revocation(preimage, ourNextRevocationHash, ack_in + 1)
          them ! update_commit(ourSigForThem, ack_in + 1)
          goto(CLOSE_CLEARING_WAIT_FOR_REV_THEIRSIG) using d.copy(ack_in = ack_in + 1, ack_out = ack_out + 2, staged = uncommitted_changes, next = WaitForRevTheirSig(Commitment(proposal.index, signedCommitTx, proposal.state, proposal.theirRevocationHash)))
      }
  })

  when(CLOSE_CLEARING_WAIT_FOR_REV)(clearing_handler orElse {
    case Event(update_revocation(theirRevocationPreimage, theirNextRevocationHash, theirAck), d@DATA_CLEARING(ack_in, ack_out, ourParams, theirParams, shaChain, staged, previousCommitment, WaitForRev(proposal), closing)) =>
      // counterparty replied with the signature for its new commitment tx, and revocationPreimage
      val revocationHashCheck = new BinaryData(previousCommitment.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage))
      if (revocationHashCheck) {
        goto(CLOSE_CLEARING_WAIT_FOR_SIG) using d.copy(ack_in = ack_in + 1, next = WaitForSig(proposal, theirNextRevocationHash))
      } else {
        log.warning(s"the revocation preimage they gave us is wrong! hash=${previousCommitment.theirRevocationHash} preimage=$theirRevocationPreimage")
        them ! error(Some("Wrong preimage"))
        publish_ourcommit(previousCommitment)
        goto(CLOSING) using DATA_CLOSING(ack_in = ack_in + 1, ack_out = ack_out + 1, ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
      }

    case Event(msg@update_commit(theirSig, theirAck), DATA_CLEARING(ack_in, ack_out, ourParams, theirParams, shaChain, staged, previousCommitment, WaitForRev(proposal), closing)) =>
      // TODO : IGNORED FOR NOW
      log.warning(s"ignored $msg")
      stay
  })

  when(CLOSE_CLEARING_WAIT_FOR_REV_THEIRSIG)(clearing_handler orElse {
    case Event(update_revocation(theirRevocationPreimage, theirNextRevocationHash, theirAck), d@DATA_CLEARING(ack_in, ack_out, ourParams, theirParams, shaChain, staged, previousCommitment, WaitForRevTheirSig(nextCommitment), _)) =>
      // counterparty replied with the signature for its new commitment tx, and revocationPreimage
      val revocationHashCheck = new BinaryData(previousCommitment.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage))
      if (revocationHashCheck) {
        if (nextCommitment.state.them.htlcs_received.size == 0 && nextCommitment.state.us.htlcs_received.size == 0) {
          val finalTx = makeFinalTx(nextCommitment.tx.txIn, ourParams.finalPubKey, theirParams.finalPubKey, nextCommitment.state) //TODO ADJUST FEES
          val ourSig = bin2signature(Transaction.signInput(finalTx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
          them ! close_signature(closeFee, ourSig)
          goto(CLOSE_NEGOTIATING) using d.copy(ack_in = ack_in + 1, ack_out = ack_out + 1, commitment = nextCommitment, next = ReadyForSig(theirNextRevocationHash))
        } else {
          goto(CLOSE_CLEARING) using d.copy(ack_in = ack_in + 1, commitment = nextCommitment, next = ReadyForSig(theirNextRevocationHash))
        }
      } else {
        log.warning(s"the revocation preimage they gave us is wrong! hash=${previousCommitment.theirRevocationHash} preimage=$theirRevocationPreimage")
        them ! error(Some("Wrong preimage"))
        publish_ourcommit(previousCommitment)
        goto(CLOSING) using DATA_CLOSING(ack_in = ack_in + 1, ack_out = ack_out + 1, ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
      }
  })

  when(CLOSE_CLEARING_WAIT_FOR_SIG)(clearing_handler orElse {
    case Event(update_commit(theirSig, theirAck), d@DATA_CLEARING(ack_in, ack_out, ourParams, theirParams, shaChain, staged, previousCommitment, WaitForSig(proposal, theirNextRevocationHash), _)) =>
      // counterparty replied with the signature for the new commitment tx
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, proposal.index))
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, proposal.state, ourRevocationHash, proposal.theirRevocationHash)
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          publish_ourcommit(previousCommitment)
          goto(CLOSING) using DATA_CLOSING(ack_in = ack_in + 1, ack_out = ack_out + 1, ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
        case true =>
          val preimage = ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index)
          val ourNextRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, proposal.index + 1))
          them ! update_revocation(preimage, ourNextRevocationHash, ack_in + 1)
          val nextCommitment = Commitment(proposal.index, signedCommitTx, proposal.state, proposal.theirRevocationHash)
          if (nextCommitment.state.them.htlcs_received.size == 0 && nextCommitment.state.us.htlcs_received.size == 0) {
            val finalTx = makeFinalTx(nextCommitment.tx.txIn, ourParams.finalPubKey, theirParams.finalPubKey, nextCommitment.state) //TODO ADJUST FEES
            val ourSig = bin2signature(Transaction.signInput(finalTx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
            them ! close_signature(closeFee, ourSig)
            goto(CLOSE_NEGOTIATING) using d.copy(ack_in = ack_in + 1, ack_out = ack_out + 1, commitment = nextCommitment, next = ReadyForSig(theirNextRevocationHash))
          } else {
            goto(CLOSE_CLEARING) using d.copy(ack_in = ack_in + 1, ack_out = ack_out + 1, commitment = nextCommitment, next = ReadyForSig(theirNextRevocationHash))
          }
      }
  })

  when(CLOSE_NEGOTIATING) {
    case Event(close_signature(closeFee, sig), DATA_NEGOTIATING(ack_in, ack_out, ourParams, theirParams, shaChain, commitment, closing)) =>
      // TODO: we should actually negotiate
      // TODO: publish tx
      val mutualTx: Transaction = null // TODO
      goto(CLOSING) using DATA_CLOSING(ack_in, ack_out, ourParams, theirParams, shaChain, commitment, Some(mutualTx), None, None, Nil)
  }

  /*when(WAIT_FOR_CLOSE_COMPLETE) {
    case Event(close_channel_complete(theirSig), d: CurrentCommitment) =>
      //TODO we should use the closing fee in pkts
      val closingState = d.commitment.state.adjust_fees(Globals.closing_fee * 1000, d.ourParams.anchorAmount.isDefined)
      val finalTx = makeFinalTx(d.commitment.tx.txIn, ourFinalPubKey, d.theirParams.finalPubKey, closingState)
      val signedFinalTx = sign_our_commitment_tx(d.ourParams, d.theirParams, finalTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedFinalTx, Map(signedFinalTx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, d.theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          publish_ourcommit(d.commitment)
          goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
        case true =>
          them ! close_channel_ack()
          blockchain ! Publish(signedFinalTx)
          goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, mutualClosePublished = Some(signedFinalTx))
      }

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isMutualClose(tx, d.ourParams, d.theirParams, d.commitment)) =>
      // it is possible that we received this before the close_channel_complete, we may still receive the latter
      log.info(s"mutual close detected: $tx")
      blockchain ! WatchConfirmed(self, tx.txid, d.ourParams.minDepth, BITCOIN_CLOSE_DONE)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, mutualClosePublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, revokedPublished = tx :: Nil)

    case Event((BITCOIN_ANCHOR_SPENT, _), _) =>
      goto(ERR_INFORMATION_LEAK)
  }*/

  /**
    * At this point we have already published the closing tx
    */
  /*when(WAIT_FOR_CLOSE_ACK) {
    case Event(close_channel_ack(), d: DATA_WAIT_FOR_CLOSE_ACK) =>
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, mutualClosePublished = Some(d.mutualCloseTx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isMutualClose(tx, d.ourParams, d.theirParams, d.commitment)) =>
      // it is possible that we received this before the close_channel_ack, we may still receive the latter
      log.info(s"mutual close detected: $tx")
      blockchain ! WatchConfirmed(self, tx.txid, d.ourParams.minDepth, BITCOIN_CLOSE_DONE)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, mutualClosePublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, revokedPublished = tx :: Nil)

    case Event((BITCOIN_ANCHOR_SPENT, _), _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: DATA_WAIT_FOR_CLOSE_ACK) =>
      // no-op, because at this point we have already published the mutual close tx on the blockchain
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, mutualClosePublished = Some(d.mutualCloseTx))
  }*/

  /**
    * We enter this state when the anchor is spent by at least one tx
    * We leave this state when tx (or txes) spending the spending tx is buried deep enough in the blockchain
    */
  when(CLOSING) {

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d@DATA_CLOSING(_, _, ourParams, theirParams, _, commitment, _, _, _, _)) if (isMutualClose(tx, ourParams, theirParams, commitment)) =>
      log.info(s"mutual close detected: $tx")
      blockchain ! WatchConfirmed(self, tx.txid, ourParams.minDepth, BITCOIN_CLOSE_DONE)
      // wait for BITCOIN_CLOSE_DONE
      // should we override the previous tx? (which may be different because of malleability)
      stay using d.copy(mutualClosePublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), DATA_CLOSING(_, _, _, _, _, commitment, _, _, _, _)) if (isOurCommit(tx, commitment)) =>
      log.info(s"our commit detected: $tx")
      handle_ourcommit()
      stay

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d@DATA_CLOSING(_, _, ourParams, theirParams, shaChain, commitment, _, _, _, _)) if (isTheirCommit(tx, ourParams, theirParams, commitment)) =>
      handle_theircommit(tx, ourParams, theirParams, shaChain, commitment)
      stay using d.copy(theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_CLOSING) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      stay using d.copy(revokedPublished = tx +: d.revokedPublished)

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), _) =>
      // somebody managed to spend the anchor...
      // we're fucked
      goto(ERR_INFORMATION_LEAK)

    case Event(INPUT_NO_MORE_HTLCS, _) =>
      // should we do something ???
      // wait for BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED
      stay

    case Event(BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED, DATA_CLOSING(_, _, ourParams, theirParams, _, _, _, Some(ourCommitPublished), _, _)) =>
      handle_ourcommit_delaypassed()
      stay

    case Event(pkt: error, _) =>
      // there is nothing to do here
      stay

    case Event(BITCOIN_CLOSE_DONE, _) => goto(CLOSED)

    case Event(BITCOIN_SPEND_OURS_DONE, _) => goto(CLOSED)

    case Event(BITCOIN_SPEND_THEIRS_DONE, _) => goto(CLOSED)

    case Event(BITCOIN_STEAL_DONE, _) => goto(CLOSED)

    /*case Event(p: close_channel_complete, _) => stay // if bitcoin network is faster than lightning network (very unlikely to happen)

    case Event(p: close_channel_ack, _) => stay // if bitcoin network is faster than lightning network (very unlikely to happen)*/
  }*/

  when(CLOSED) {
    case _ if false => stay // we don't want this to match so that whenUnhandled works
  }

  whenUnhandled {

    case Event(BITCOIN_ANCHOR_LOST, _) => goto(ERR_ANCHOR_LOST)

    case Event(CMD_GETSTATE, _) =>
      sender ! stateName
      stay

    case Event(CMD_GETSTATEDATA, _) =>
      sender ! stateData
      stay

    case Event(CMD_GETINFO, _) =>
      sender ! RES_GETINFO(theirNodeId, stateData match {
        case c: DATA_NORMAL => c.commitments.anchorId
        case c: DATA_OPEN_WAITING => c.commitments.anchorId
        case c: DATA_CLEARING => c.commitments.anchorId
        case c: DATA_NEGOCIATING => c.commitments.anchorId
        case c: DATA_CLOSING => c.ourCommit.publishableTx.txIn(0).outPoint.hash
        case _ => Hash.Zeroes
      }, stateName, stateData)
      stay

    // TODO : them ! error(Some("Unexpected message")) ?

  }

  /*
          888    888 8888888888 888      8888888b.  8888888888 8888888b.   .d8888b.
          888    888 888        888      888   Y88b 888        888   Y88b d88P  Y88b
          888    888 888        888      888    888 888        888    888 Y88b.
          8888888888 8888888    888      888   d88P 8888888    888   d88P  "Y888b.
          888    888 888        888      8888888P"  888        8888888P"      "Y88b.
          888    888 888        888      888        888        888 T88b         "888
          888    888 888        888      888        888        888  T88b  Y88b  d88P
          888    888 8888888888 88888888 888        8888888888 888   T88b  "Y8888P"
  */

  /**
    * Something went wrong, we publish the current commitment transaction
    */
  def publish_ourcommit(commitment: OurCommit) = {
    log.info(s"publishing our commitment tx: ${
      commitment.publishableTx
    }")
    blockchain ! Publish(commitment.publishableTx)
  }

  def handle_ourcommit() = {
    // if (HTLCs)
    //    handle them (how ???)
    //    wait for INPUT_NO_MORE_HTLCS
    // else
    //    wait for BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED
  }

  def handle_ourcommit_delaypassed() = {
    // spend ours
    // wait for BITCOIN_SPEND_OURS_DONE
  }

  /**
    * They published their current commitment transaction
    */
  def handle_theircommit(publishedTx: Transaction, ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: TheirCommit): error = {
    log.info(s"their commit detected: $publishedTx")
    // if (HTLCs)
    //    handle them (how ???)
    //    wait for INPUT_NO_MORE_HTLCS
    // else
    //    spend theirs
    //    wait for BITCOIN_SPEND_THEIRS_DONE
    error(Some("Commit tx noticed"))
  }

  def handle_revoked(publishedTx: Transaction): error = {
    log.info(s"revoked commit detected: $publishedTx")
    // steal immediately
    // wait for BITCOIN_STEAL_DONE
    error(Some("Otherspend noticed"))
  }

  def checkCloseSignature(closeSig: BinaryData, closeFee: Satoshi, d: DATA_NEGOCIATING): Try[Transaction] = {
    val (finalTx, ourCloseSig) = Helpers.makeFinalTx(d.commitments, d.ourClearing.scriptPubkey, d.theirClearing.scriptPubkey, closeFee)
    val signedTx = addSigs(d.commitments.ourParams, d.commitments.theirParams, d.commitments.anchorOutput.amount.toLong, finalTx, ourCloseSig.sig, closeSig)
    checksig(d.commitments.ourParams, d.commitments.theirParams, d.commitments.anchorOutput, signedTx).map(_ => signedTx)
  }
}




