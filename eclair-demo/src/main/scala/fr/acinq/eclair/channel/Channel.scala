package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM, LoggingFSM, Props}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin.{OutPoint, _}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.Helpers._
import fr.acinq.eclair.crypto.ShaChain
import lightning._
import lightning.open_channel.anchor_offer.{WILL_CREATE_ANCHOR, WONT_CREATE_ANCHOR}

import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

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
      them ! open_channel(params.delay, Helpers.revocationHash(params.shaSeed, 0), Helpers.revocationHash(params.shaSeed, 1), params.commitPubKey, params.finalPubKey, WONT_CREATE_ANCHOR, Some(params.minDepth), params.initialFeeRate)
      startWith(OPEN_WAIT_FOR_OPEN_NOANCHOR, DATA_OPEN_WAIT_FOR_OPEN(params))
    case _ =>
      them ! open_channel(params.delay, Helpers.revocationHash(params.shaSeed, 0), Helpers.revocationHash(params.shaSeed, 1), params.commitPubKey, params.finalPubKey, WILL_CREATE_ANCHOR, Some(params.minDepth), params.initialFeeRate)
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
  when(OPEN_WAIT_FOR_OPEN_NOANCHOR)(handleExceptions {
    case Event(open_channel(delay, theirRevocationHash, theirNextRevocationHash, commitKey, finalKey, WILL_CREATE_ANCHOR, minDepth, initialFeeRate), DATA_OPEN_WAIT_FOR_OPEN(ourParams)) =>
      val theirParams = TheirChannelParams(delay, commitKey, finalKey, minDepth, initialFeeRate)
      goto(OPEN_WAIT_FOR_ANCHOR) using DATA_OPEN_WAIT_FOR_ANCHOR(ourParams, theirParams, theirRevocationHash, theirNextRevocationHash)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e@error(problem), _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(OPEN_WAIT_FOR_OPEN_WITHANCHOR)(handleExceptions {
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

    case Event(e@error(problem), _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(OPEN_WAIT_FOR_ANCHOR)(handleExceptions {
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
      val ourSigForThem = sign(ourParams, theirParams, Satoshi(anchorAmount), theirTx)
      them ! open_commit_sig(ourSigForThem)
      blockchain ! WatchConfirmed(self, anchorTxid, ourParams.minDepth, BITCOIN_ANCHOR_DEPTHOK)
      blockchain ! WatchSpent(self, anchorTxid, anchorOutputIndex, 0, BITCOIN_ANCHOR_SPENT)
      // FIXME: ourTx is not signed by them and cannot be published. We won't lose money since they are funding the chanel
      val commitments = Commitments(ourParams, theirParams,
        OurCommit(0, ourSpec, ourTx), TheirCommit(0, theirSpec, theirRevocationHash),
        OurChanges(Nil, Nil, Nil), TheirChanges(Nil, Nil),
        Right(theirNextRevocationHash), anchorOutput, ShaChain.init)
      goto(OPEN_WAITING_THEIRANCHOR) using DATA_OPEN_WAITING(commitments, None)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e@error(problem), _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(OPEN_WAIT_FOR_COMMIT_SIG)(handleExceptions {
    case Event(open_commit_sig(theirSig), DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams, theirParams, anchorTx, anchorOutputIndex, theirCommitment, theirNextRevocationHash)) =>
      val anchorAmount = anchorTx.txOut(anchorOutputIndex).amount
      val theirSpec = theirCommitment.spec
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val ourRevocationHash = Helpers.revocationHash(ourParams.shaSeed, 0L)
      val ourSpec = CommitmentSpec(Set.empty[Htlc], feeRate = ourParams.initialFeeRate, initial_amount_us_msat = anchorAmount.toLong * 1000, initial_amount_them_msat = 0, amount_us_msat = anchorAmount.toLong * 1000, amount_them_msat = 0)
      val ourTx = makeOurTx(ourParams, theirParams, TxIn(OutPoint(anchorTx, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, ourRevocationHash, ourSpec)
      log.info(s"checking our tx: $ourTx")
      val ourSig = sign(ourParams, theirParams, anchorAmount, ourTx)
      val signedTx: Transaction = addSigs(ourParams, theirParams, anchorAmount, ourTx, ourSig, theirSig)
      val anchorOutput = anchorTx.txOut(anchorOutputIndex)
      checksig(ourParams, theirParams, anchorOutput, signedTx) match {
        case Failure(cause) =>
          log.error(cause, "their open_commit_sig message contains an invalid signature")
          them ! error(Some(cause.getMessage))
          // we haven't published anything yet, we can just stop
          goto(CLOSED)
        case Success(_) =>
          blockchain ! WatchConfirmed(self, anchorTx.txid, ourParams.minDepth, BITCOIN_ANCHOR_DEPTHOK)
          blockchain ! WatchSpent(self, anchorTx.txid, anchorOutputIndex, 0, BITCOIN_ANCHOR_SPENT)
          blockchain ! Publish(anchorTx)
          val commitments = Commitments(ourParams, theirParams,
            OurCommit(0, ourSpec, signedTx), theirCommitment,
            OurChanges(Nil, Nil, Nil), TheirChanges(Nil, Nil),
            Right(theirNextRevocationHash), anchorOutput, ShaChain.init)
          goto(OPEN_WAITING_OURANCHOR) using DATA_OPEN_WAITING(commitments, None)
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e@error(problem), _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(OPEN_WAITING_THEIRANCHOR)(handleExceptions {
    case Event(msg@open_complete(blockId_opt), d: DATA_OPEN_WAITING) =>
      log.info(s"received their open_complete, deferring message")
      stay using d.copy(deferred = Some(msg))

    case Event(BITCOIN_ANCHOR_DEPTHOK, d@DATA_OPEN_WAITING(commitments, deferred)) =>
      blockchain ! WatchLost(self, commitments.anchorId, commitments.ourParams.minDepth, BITCOIN_ANCHOR_LOST)
      them ! open_complete(None)
      deferred.map(self ! _)
      //TODO htlcIdx should not be 0 when resuming connection
      goto(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) using DATA_NORMAL(commitments, 0, None)

    case Event(BITCOIN_ANCHOR_TIMEOUT, _) =>
      them ! error(Some("Anchor timed out"))
      goto(CLOSED)

    case Event((BITCOIN_ANCHOR_SPENT, _), d: DATA_OPEN_WAITING) =>
      // they are funding the anchor, we have nothing at stake
      log.warning(s"their anchor ${d.commitments.anchorId} was spent, sending error and closing")
      them ! error(Some(s"your anchor ${d.commitments.anchorId} was spent"))
      goto(CLOSED)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e@error(problem), _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(OPEN_WAITING_OURANCHOR)(handleExceptions {
    case Event(msg@open_complete(blockId_opt), d: DATA_OPEN_WAITING) =>
      log.info(s"received their open_complete, deferring message")
      stay using d.copy(deferred = Some(msg))

    case Event(BITCOIN_ANCHOR_DEPTHOK, d@DATA_OPEN_WAITING(commitments, deferred)) =>
      blockchain ! WatchLost(self, commitments.anchorId, commitments.ourParams.minDepth, BITCOIN_ANCHOR_LOST)
      them ! open_complete(None)
      deferred.map(self ! _)
      //TODO htlcIdx should not be 0 when resuming connection
      goto(OPEN_WAIT_FOR_COMPLETE_OURANCHOR) using DATA_NORMAL(commitments, 0, None)

    case Event(BITCOIN_ANCHOR_TIMEOUT, _) =>
      them ! error(Some("Anchor timed out"))
      goto(CLOSED)

    case Event((BITCOIN_ANCHOR_SPENT, _), d: DATA_OPEN_WAITING) =>
      // this is never supposed to happen !!
      log.error(s"our anchor ${d.commitments.anchorId} was spent !!")
      them ! error(Some("Anchor has been spent"))
      blockchain ! Publish(d.commitments.ourCommit.publishableTx)
      goto(ERR_INFORMATION_LEAK)

    case Event(cmd: CMD_CLOSE, d: DATA_OPEN_WAITING) =>
      blockchain ! Publish(d.commitments.ourCommit.publishableTx)
      blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
      goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))

    case Event(e@error(problem), d: DATA_OPEN_WAITING) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      blockchain ! Publish(d.commitments.ourCommit.publishableTx)
      blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
      goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
  })

  when(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR)(handleExceptions {
    case Event(open_complete(blockid_opt), d: DATA_NORMAL) =>
      Register.create_alias(theirNodeId, d.commitments.anchorId)
      goto(NORMAL)

    case Event((BITCOIN_ANCHOR_SPENT, _), d: DATA_NORMAL) =>
      // they are funding the anchor, we have nothing at stake
      log.warning(s"their anchor ${d.commitments.anchorId} was spent, sending error and closing")
      them ! error(Some(s"your anchor ${d.commitments.anchorId} was spent"))
      goto(CLOSED)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e@error(problem), _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(OPEN_WAIT_FOR_COMPLETE_OURANCHOR)(handleExceptions {
    case Event(open_complete(blockid_opt), d: DATA_NORMAL) =>
      Register.create_alias(theirNodeId, d.commitments.anchorId)
      goto(NORMAL)

    case Event((BITCOIN_ANCHOR_SPENT, _), d: DATA_NORMAL) =>
      // this is never supposed to happen !!
      log.error(s"our anchor ${d.commitments.anchorId} was spent while we were waiting for their open_complete message !!")
      them ! error(Some("Anchor has been spent"))
      blockchain ! Publish(d.commitments.ourCommit.publishableTx)
      goto(ERR_INFORMATION_LEAK)

    case Event(cmd: CMD_CLOSE, d: DATA_NORMAL) =>
      blockchain ! Publish(d.commitments.ourCommit.publishableTx)
      blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
      goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))

    case Event(e@error(problem), d: DATA_NORMAL) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      blockchain ! Publish(d.commitments.ourCommit.publishableTx)
      blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
      goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
  })


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
    case Event(CMD_ADD_HTLC(amount, rHash, expiry, nodeIds, origin, id_opt), d@DATA_NORMAL(commitments, htlcIdx, _)) =>
      // TODO : this should probably be done in Commitments.scala
      // our available funds as seen by them, including all pending changes
      val reduced = reduce(commitments.theirCommit.spec, commitments.theirChanges.acked, commitments.ourChanges.acked ++ commitments.ourChanges.signed ++ commitments.ourChanges.proposed)
      // the pending htlcs that we sent to them (seen as IN from their pov) have already been deduced from our balance
      val available = reduced.amount_them_msat + reduced.htlcs.filter(_.direction == OUT).map(-_.amountMsat).sum
      if (amount > available) {
        sender ! s"insufficient funds (available=$available msat)"
        stay
      } else {
        // TODO: nodeIds are ignored
        val id: Long = id_opt.getOrElse(htlcIdx + 1)
        val steps = route(route_step(0, next = route_step.Next.End(true)) :: Nil)
        val htlc = update_add_htlc(id, amount, rHash, expiry, routing(ByteString.copyFrom(steps.toByteArray)))
        them ! htlc
        sender ! "ok"
        stay using d.copy(htlcIdx = htlc.id, commitments = commitments.addOurProposal(htlc))
      }

    case Event(htlc@update_add_htlc(htlcId, amount, rHash, expiry, nodeIds), d@DATA_NORMAL(commitments, _, _)) =>
      // TODO : this should probably be done in Commitments.scala
      // their available funds as seen by us, including all pending changes
      val reduced = reduce(commitments.ourCommit.spec, commitments.ourChanges.acked, commitments.theirChanges.acked ++ commitments.theirChanges.proposed)
      // the pending htlcs that they sent to us (seen as IN from our pov) have already been deduced from their balance
      val available = reduced.amount_them_msat + reduced.htlcs.filter(_.direction == OUT).map(-_.amountMsat).sum
      if (amount > available) {
        log.error("they sent an htlc but had insufficient funds")
        them ! error(Some("Insufficient funds"))
        blockchain ! Publish(d.commitments.ourCommit.publishableTx)
        blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
        goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
      } else {
        // TODO: nodeIds are ignored
        stay using d.copy(commitments = commitments.addTheirProposal(htlc))
      }

    case Event(CMD_FULFILL_HTLC(id, r), d: DATA_NORMAL) =>
      Try(Commitments.sendFulfill(d.commitments, CMD_FULFILL_HTLC(id, r))) match {
        case Success((commitments1, fullfill)) =>
          them ! fullfill
          sender ! "ok"
          stay using d.copy(commitments = commitments1)
        case Failure(cause) =>
          log.error(cause, "")
          sender ! cause.getMessage
          stay
      }

    case Event(fulfill@update_fulfill_htlc(id, r), d: DATA_NORMAL) =>
      Try(Commitments.receiveFulfill(d.commitments, fulfill)) match {
        case Success(commitments1) =>
          stay using d.copy(commitments = commitments1)
        case Failure(cause) =>
          log.error(cause, "")
          them ! error(Some(cause.getMessage))
          blockchain ! Publish(d.commitments.ourCommit.publishableTx)
          blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
          goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
      }

    case Event(CMD_FAIL_HTLC(id, reason), d: DATA_NORMAL) =>
      Try(Commitments.sendFail(d.commitments, CMD_FAIL_HTLC(id, reason))) match {
        case Success((commitments1, fail)) =>
          them ! fail
          sender ! "ok"
          stay using d.copy(commitments = commitments1)
        case Failure(cause) =>
          log.error(cause, "")
          sender ! cause.getMessage
          stay
      }

    case Event(fail@update_fail_htlc(id, reason), d: DATA_NORMAL) =>
      Try(Commitments.receiveFail(d.commitments, fail)) match {
        case Success(commitments1) =>
          stay using d.copy(commitments = commitments1)
        case Failure(cause) =>
          log.error(cause, "")
          them ! error(Some(cause.getMessage))
          blockchain ! Publish(d.commitments.ourCommit.publishableTx)
          blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
          goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
      }

    case Event(CMD_SIGN, d: DATA_NORMAL) =>
      if (d.commitments.theirNextCommitInfo.isLeft) {
        sender ! "cannot sign until next revocation hash is received"
        stay
      } /*else if (d.commitments.ourChanges.proposed.isEmpty) {
        //TODO : check this
        sender ! "cannot sign when there are no changes"
        stay
      }*/
      else {
        Try(Commitments.sendCommit(d.commitments)) match {
          case Success((commitments1, commit)) =>
            them ! commit
            sender ! "ok"
            stay using d.copy(commitments = commitments1)
          case Failure(cause) =>
            log.error(cause, "")
            them ! error(Some(cause.getMessage))
            blockchain ! Publish(d.commitments.ourCommit.publishableTx)
            blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
            goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
        }
      }

    case Event(msg@update_commit(theirSig), d: DATA_NORMAL) =>
      Try(Commitments.receiveCommit(d.commitments, msg)) match {
        case Success((commitments1, revocation)) =>
          them ! revocation
          stay using d.copy(commitments = commitments1)
        case Failure(cause) =>
          log.error(cause, "")
          them ! error(Some(cause.getMessage))
          blockchain ! Publish(d.commitments.ourCommit.publishableTx)
          blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
          goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
      }

    case Event(msg@update_revocation(revocationPreimage, nextRevocationHash), d: DATA_NORMAL) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked
      Try(Commitments.receiveRevocation(d.commitments, msg)) match {
        case Success(commitments1) =>
          stay using d.copy(commitments = commitments1)
        case Failure(cause) =>
          log.error(cause, "")
          them ! error(Some(cause.getMessage))
          blockchain ! Publish(d.commitments.ourCommit.publishableTx)
          blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
          goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
      }

    case Event(CMD_CLOSE(scriptPubKeyOpt), d: DATA_NORMAL) =>
      if (d.ourClearing.isDefined) {
        sender ! "closing already in progress"
        stay
      } else {
        val ourScriptPubKey: BinaryData = scriptPubKeyOpt.getOrElse {
          log.info(s"our final tx can be redeemed with ${Base58Check.encode(Base58.Prefix.SecretKeyTestnet, d.commitments.ourParams.finalPrivKey)}")
          Script.write(Scripts.pay2pkh(d.commitments.ourParams.finalPubKey))
        }
        val ourCloseClearing = close_clearing(ourScriptPubKey)
        them ! ourCloseClearing
        stay using d.copy(ourClearing = Some(ourCloseClearing))
      }

    case Event(theirClearing@close_clearing(theirScriptPubKey), d@DATA_NORMAL(commitments, _, ourClearingOpt)) =>
      val ourClearing: close_clearing = ourClearingOpt.getOrElse {
        val ourScriptPubKey: BinaryData = Script.write(Scripts.pay2pkh(commitments.ourParams.finalPubKey))
        log.info(s"our final tx can be redeemed with ${Base58Check.encode(Base58.Prefix.SecretKeyTestnet, d.commitments.ourParams.finalPrivKey)}")
        val c = close_clearing(ourScriptPubKey)
        them ! c
        c
      }
      if (commitments.hasNoPendingHtlcs) {
        val (finalTx, ourCloseSig) = makeFinalTx(commitments, ourClearing.scriptPubkey, theirScriptPubKey)
        them ! ourCloseSig
        goto(NEGOTIATING) using DATA_NEGOTIATING(commitments, d.htlcIdx, ourClearing, theirClearing, ourCloseSig)
      } else {
        goto(CLEARING) using DATA_CLEARING(commitments, d.htlcIdx, ourClearing, theirClearing)
      }

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_NORMAL) =>
      log.warning(s"anchor spent in ${tx.txid}")
      them ! error(Some("Anchor has been spent"))
      Helpers.claimTheirRevokedCommit(tx, d.commitments) match {
        case Some(spendingTx) =>
          blockchain ! Publish(spendingTx)
          blockchain ! WatchConfirmed(self, spendingTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
          goto(CLOSING) using DATA_CLOSING(d.commitments, revokedPublished = Seq(tx))
        case None =>
          // TODO: this is definitely not right!
          stay()
      }

    case Event(e@error(problem), d: DATA_NORMAL) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      blockchain ! Publish(d.commitments.ourCommit.publishableTx)
      blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
      goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))

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

  when(CLEARING) {
    case Event(CMD_FULFILL_HTLC(id, r), d: DATA_CLEARING) =>
      Try(Commitments.sendFulfill(d.commitments, CMD_FULFILL_HTLC(id, r))) match {
        case Success((commitments1, fullfill)) =>
          them ! fullfill
          sender ! "ok"
          stay using d.copy(commitments = commitments1)
        case Failure(cause) =>
          log.error(cause, "")
          sender ! cause.getMessage
          stay
      }

    case Event(fulfill@update_fulfill_htlc(id, r), d: DATA_CLEARING) =>
      Try(Commitments.receiveFulfill(d.commitments, fulfill)) match {
        case Success(commitments1) =>
          stay using d.copy(commitments = commitments1)
        case Failure(cause) =>
          log.error(cause, "")
          them ! error(Some(cause.getMessage))
          blockchain ! Publish(d.commitments.ourCommit.publishableTx)
          blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
          goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
      }

    case Event(CMD_FAIL_HTLC(id, reason), d: DATA_CLEARING) =>
      Try(Commitments.sendFail(d.commitments, CMD_FAIL_HTLC(id, reason))) match {
        case Success((commitments1, fail)) =>
          them ! fail
          sender ! "ok"
          stay using d.copy(commitments = commitments1)
        case Failure(cause) =>
          log.error(cause, "")
          sender ! cause.getMessage
          stay
      }

    case Event(fail@update_fail_htlc(id, reason), d: DATA_CLEARING) =>
      Try(Commitments.receiveFail(d.commitments, fail)) match {
        case Success(commitments1) =>
          stay using d.copy(commitments = commitments1)
        case Failure(cause) =>
          log.error(cause, "")
          them ! error(Some(cause.getMessage))
          blockchain ! Publish(d.commitments.ourCommit.publishableTx)
          blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
          goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
      }

    case Event(CMD_SIGN, d: DATA_CLEARING) =>
      if (d.commitments.theirNextCommitInfo.isLeft) {
        sender ! "cannot sign until next revocation hash is received"
        stay
      } /*else if (d.commitments.ourChanges.proposed.isEmpty) {
        //TODO : check this
        sender ! "cannot sign when there are no changes"
        stay
      }*/
      else {
        Try(Commitments.sendCommit(d.commitments)) match {
          case Success((commitments1, commit)) =>
            them ! commit
            sender ! "ok"
            stay using d.copy(commitments = commitments1)
          case Failure(cause) =>
            log.error(cause, "")
            them ! error(Some(cause.getMessage))
            blockchain ! Publish(d.commitments.ourCommit.publishableTx)
            blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
            goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
        }
      }

    case Event(msg@update_commit(theirSig), d@DATA_CLEARING(commitments, _, ourClearing, theirClearing)) =>
      Try(Commitments.receiveCommit(d.commitments, msg)) match {
        case Success((commitments1, revocation)) =>
          them ! revocation
          if (commitments1.hasNoPendingHtlcs) {
            val (finalTx, ourCloseSig) = makeFinalTx(commitments1, ourClearing.scriptPubkey, theirClearing.scriptPubkey)
            them ! ourCloseSig
            goto(NEGOTIATING) using DATA_NEGOTIATING(commitments1, d.htlcIdx, ourClearing, theirClearing, ourCloseSig)
          } else {
            stay using d.copy(commitments = commitments1)
          }
        case Failure(cause) =>
          log.error(cause, "")
          them ! error(Some(cause.getMessage))
          blockchain ! Publish(d.commitments.ourCommit.publishableTx)
          blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
          goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
      }

    case Event(msg@update_revocation(revocationPreimage, nextRevocationHash), d@DATA_CLEARING(commitments, _, ourClearing, theirClearing)) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked
      Try(Commitments.receiveRevocation(d.commitments, msg)) match {
        case Success(commitments1) if commitments1.hasNoPendingHtlcs =>
          val (finalTx, ourCloseSig) = makeFinalTx(commitments1, ourClearing.scriptPubkey, theirClearing.scriptPubkey)
          them ! ourCloseSig
          goto(NEGOTIATING) using DATA_NEGOTIATING(commitments1, d.htlcIdx, ourClearing, theirClearing, ourCloseSig)
        case Success(commitments1) =>
          stay using d.copy(commitments = commitments1)
        case Failure(cause) =>
          log.error(cause, "")
          them ! error(Some(cause.getMessage))
          blockchain ! Publish(d.commitments.ourCommit.publishableTx)
          blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
          goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
      }

    case Event(e@error(problem), d: DATA_CLEARING) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      blockchain ! Publish(d.commitments.ourCommit.publishableTx)
      blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
      goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
  }

  when(NEGOTIATING) {
    case Event(close_signature(theirCloseFee, theirSig), d: DATA_NEGOTIATING) if theirCloseFee == d.ourSignature.closeFee =>
      checkCloseSignature(theirSig, Satoshi(theirCloseFee), d) match {
        case Success(signedTx) =>
          blockchain ! Publish(signedTx)
          blockchain ! WatchConfirmed(self, signedTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
          goto(CLOSING) using DATA_CLOSING(d.commitments, ourSignature = Some(d.ourSignature), mutualClosePublished = Some(signedTx))
        case Failure(cause) =>
          log.error(cause, "cannot verify their close signature")
          throw new RuntimeException("cannot verify their close signature", cause)
      }

    case Event(close_signature(theirCloseFee, theirSig), d: DATA_NEGOTIATING) =>
      checkCloseSignature(theirSig, Satoshi(theirCloseFee), d) match {
        case Success(_) =>
          val closeFee = ((theirCloseFee + d.ourSignature.closeFee) / 4) * 2 match {
            case value if value == d.ourSignature.closeFee => value + 2
            case value => value
          }
          val (finalTx, ourCloseSig) = makeFinalTx(d.commitments, d.ourClearing.scriptPubkey, d.theirClearing.scriptPubkey, Satoshi(closeFee))
          them ! ourCloseSig
          if (closeFee == theirCloseFee) {
            val signedTx = addSigs(d.commitments.ourParams, d.commitments.theirParams, d.commitments.anchorOutput.amount, finalTx, ourCloseSig.sig, theirSig)
            blockchain ! Publish(signedTx)
            blockchain ! WatchConfirmed(self, signedTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
            goto(CLOSING) using DATA_CLOSING(d.commitments, ourSignature = Some(ourCloseSig), mutualClosePublished = Some(signedTx))
          } else {
            stay using d.copy(ourSignature = ourCloseSig)
          }
        case Failure(cause) =>
          log.error(cause, "cannot verify their close signature")
          throw new RuntimeException("cannot verify their close signature", cause)
      }

    case Event(e@error(problem), _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      // TODO not implemented
      goto(CLOSED)
  }

  when(CLOSING) {
    case Event(close_signature(theirCloseFee, theirSig), d: DATA_CLOSING) if d.ourSignature.map(_.closeFee) == Some(theirCloseFee) =>
      stay()

    case Event(close_signature(theirCloseFee, theirSig), d: DATA_CLOSING) =>
      throw new RuntimeException(s"unexpected closing fee: $theirCloseFee ours is ${
        d.ourSignature.map(_.closeFee)
      }")

    case Event(BITCOIN_CLOSE_DONE, _) => goto(CLOSED)

    case Event(e@error(problem), _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      // TODO not implemented
      goto(CLOSED)
  }

  when(CLOSED, stateTimeout = 30 seconds) {
    case Event(StateTimeout, _) =>
      log.info("shutting down")
      stop(FSM.Normal)
  }

  when(ERR_INFORMATION_LEAK, stateTimeout = 30 seconds) {
    case Event(StateTimeout, _) =>
      log.info("shutting down")
      stop(FSM.Normal)
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
        case c: DATA_NEGOTIATING => c.commitments.anchorId
        case c: DATA_CLOSING => c.commitments.anchorId
        case _ => Hash.Zeroes
      }, stateName, stateData)
      stay

    // TODO : them ! error(Some("Unexpected message")) ?

  }

  /**
    * This helper function runs the state's default event handlers, and react to exceptions by unilaterally closing the channel
    */
  def handleExceptions(s: StateFunction): StateFunction = {
    case event if s.isDefinedAt(event) =>
      try {
        s(event)
      } catch {
        case t: Throwable => event.stateData match {
          case d: HasCommitments =>
            log.error(t, "error, closing")
            blockchain ! Publish(d.commitments.ourCommit.publishableTx)
            blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
            goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))
          case _ =>
            goto(CLOSED)
        }
      }
  }

}




