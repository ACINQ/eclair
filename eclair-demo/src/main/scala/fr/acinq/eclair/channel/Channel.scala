package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM, LoggingFSM, Props}
import fr.acinq.bitcoin.{OutPoint, _}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.Helpers._
import fr.acinq.eclair.channel.TypeDefs.Change
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.router.IRCRouter
import lightning._
import lightning.open_channel.anchor_offer.{WILL_CREATE_ANCHOR, WONT_CREATE_ANCHOR}
import lightning.route_step.Next

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._


/**
  * Created by PM on 20/08/2015.
  */

object Channel {
  def props(them: ActorRef, blockchain: ActorRef, paymentHandler: ActorRef, params: OurChannelParams, theirNodeId: String) = Props(new Channel(them, blockchain, paymentHandler, params, theirNodeId))
}

class Channel(val them: ActorRef, val blockchain: ActorRef, paymentHandler: ActorRef, val params: OurChannelParams, theirNodeId: String) extends LoggingFSM[State, Data] {

  log.info(s"commit pubkey: ${params.commitPubKey}")
  log.info(s"final pubkey: ${params.finalPubKey}")

  context.system.eventStream.publish(ChannelCreated(self, params, theirNodeId))

  import ExecutionContext.Implicits.global

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

  /*
                              FUNDER                            FUNDEE
                                 | open_channel      open_channel |
                                 |---------------  ---------------|
    OPEN_WAIT_FOR_OPEN_WITHANCHOR|               \/               |OPEN_WAIT_FOR_OPEN_NOANCHOR
                                 |               /\               |
                                 |<--------------  -------------->|
                                 |                                |OPEN_WAIT_FOR_ANCHOR
                                 |  open_anchor                   |
                                 |---------------                 |
         OPEN_WAIT_FOR_COMMIT_SIG|               \                |
                                 |                --------------->|
                                 |                open_commit_sig |
                                 |                ----------------|
                                 |               /                |OPEN_WAITING_THEIRANCHOR
                                 |<--------------                 |
           OPEN_WAITING_OURANCHOR|                                |
                                 |                                |
                                 | open_complete   openu_complete |
                                 |---------------  ---------------|
 OPEN_WAIT_FOR_COMPLETE_OURANCHOR|               \/               |OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR
                                 |               /\               |
                                 |<--------------  -------------->|
                           NORMAL|                                |NORMAL
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
      goto(OPEN_WAIT_FOR_COMMIT_SIG) using DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams, theirParams, anchorTx, anchorOutputIndex, TheirCommit(0, theirSpec, BinaryData(""), theirRevocationHash), theirNextRevocationHash)

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

      // build and sign their commit tx
      val theirTx = makeTheirTx(ourParams, theirParams, TxIn(OutPoint(anchorTxHash, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, theirRevocationHash, theirSpec)
      log.info(s"signing their tx: $theirTx")
      val ourSigForThem = sign(ourParams, theirParams, Satoshi(anchorAmount), theirTx)
      them ! open_commit_sig(ourSigForThem)

      // watch the anchor
      blockchain ! WatchConfirmed(self, anchorTxid, ourParams.minDepth, BITCOIN_ANCHOR_DEPTHOK)
      blockchain ! WatchSpent(self, anchorTxid, anchorOutputIndex, 0, BITCOIN_ANCHOR_SPENT)

      // FIXME: ourTx is not signed by them and cannot be published. We won't lose money since they are funding the chanel
      val ourRevocationHash = Helpers.revocationHash(ourParams.shaSeed, 0)
      val ourTx = makeOurTx(ourParams, theirParams, TxIn(OutPoint(anchorTxHash, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, ourRevocationHash, ourSpec)

      val commitments = Commitments(ourParams, theirParams,
        OurCommit(0, ourSpec, ourTx), TheirCommit(0, theirSpec, theirTx.txid, theirRevocationHash),
        OurChanges(Nil, Nil, Nil), TheirChanges(Nil, Nil), 0L,
        Right(theirNextRevocationHash), anchorOutput, ShaChain.init, new BasicTxDb)
      context.system.eventStream.publish(ChannelIdAssigned(self, commitments.anchorId, anchorAmount))
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
            OurChanges(Nil, Nil, Nil), TheirChanges(Nil, Nil), 0L,
            Right(theirNextRevocationHash), anchorOutput, ShaChain.init, new BasicTxDb)
          context.system.eventStream.publish(ChannelIdAssigned(self, commitments.anchorId, anchorAmount.amount))
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
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
      goto(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) using DATA_NORMAL(commitments, None, Map())

    case Event(BITCOIN_ANCHOR_TIMEOUT, _) =>
      them ! error(Some("Anchor timed out"))
      goto(CLOSED)

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_OPEN_WAITING) if tx.txid == d.commitments.theirCommit.txid =>
      // they are funding the anchor, we have nothing at stake
      log.warning(s"their anchor ${d.commitments.anchorId} was spent, sending error and closing")
      them ! error(Some(s"your anchor ${d.commitments.anchorId} was spent"))
      goto(CLOSED)

    case Event((BITCOIN_ANCHOR_SPENT, _), d: HasCommitments) => handleInformationLeak(d)

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
      goto(OPEN_WAIT_FOR_COMPLETE_OURANCHOR) using DATA_NORMAL(commitments, None, Map())

    case Event(BITCOIN_ANCHOR_TIMEOUT, _) =>
      them ! error(Some("Anchor timed out"))
      goto(CLOSED)

    case Event((BITCOIN_ANCHOR_SPENT, _), d: DATA_OPEN_WAITING) => handleInformationLeak(d)

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
      IRCRouter.register(theirNodeId, d.commitments.anchorId)
      goto(NORMAL)

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_NORMAL) if tx.txid == d.commitments.theirCommit.txid =>
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
      IRCRouter.register(theirNodeId, d.commitments.anchorId)
      goto(NORMAL)

    case Event((BITCOIN_ANCHOR_SPENT, _), d: DATA_NORMAL) => handleInformationLeak(d)

    case Event(cmd: CMD_CLOSE, d: DATA_NORMAL) =>
      blockchain ! Publish(d.commitments.ourCommit.publishableTx)
      blockchain ! WatchConfirmed(self, d.commitments.ourCommit.publishableTx.txid, d.commitments.ourParams.minDepth, BITCOIN_CLOSE_DONE)
      goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.ourCommit.publishableTx))

    case Event(e@error(problem), d: DATA_NORMAL) => handleTheirError(e, d)
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

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) if d.ourClearing.isDefined =>
      handleCommandError(sender, new RuntimeException("cannot send new htlcs, closing in progress"))
      stay

    case Event(c@CMD_ADD_HTLC(amountMsat, rHash, expiry, route, origin, id_opt, do_commit), d@DATA_NORMAL(commitments, _, downstreams)) =>
      Try(Commitments.sendAdd(commitments, c)) match {
        case Success((commitments1, add)) =>
          if (do_commit) self ! CMD_SIGN
          handleCommandSuccess(sender, add, d.copy(commitments = commitments1, downstreams = downstreams + (add.id -> origin)))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(add@update_add_htlc(htlcId, amount, rHash, expiry, nodeIds), d@DATA_NORMAL(commitments, _, _)) =>
      Try(Commitments.receiveAdd(commitments, add)) match {
        case Success(commitments1) =>
          commitments1.ourParams.autoSignInterval.map(interval => context.system.scheduler.scheduleOnce(interval, self, CMD_SIGN))
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event(c@CMD_FULFILL_HTLC(id, r, do_commit), d: DATA_NORMAL) =>
      Try(Commitments.sendFulfill(d.commitments, c)) match {
        case Success((commitments1, fulfill)) =>
          if (do_commit) self ! CMD_SIGN
          handleCommandSuccess(sender, fulfill, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(fulfill@update_fulfill_htlc(id, r), d@DATA_NORMAL(commitments, _, downstreams)) =>
      Try(Commitments.receiveFulfill(d.commitments, fulfill)) match {
        case Success(commitments1) =>
          propagateDownstream(Right(fulfill), downstreams)
          commitments1.ourParams.autoSignInterval.map(interval => context.system.scheduler.scheduleOnce(interval, self, CMD_SIGN))
          stay using d.copy(commitments = commitments1, downstreams = downstreams - id)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event(c@CMD_FAIL_HTLC(id, reason, do_commit), d: DATA_NORMAL) =>
      Try(Commitments.sendFail(d.commitments, c)) match {
        case Success((commitments1, fail)) =>
          if (do_commit) self ! CMD_SIGN
          handleCommandSuccess(sender, fail, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(fail@update_fail_htlc(id, reason), d@DATA_NORMAL(commitments, _, downstreams)) =>
      Try(Commitments.receiveFail(d.commitments, fail)) match {
        case Success(commitments1) =>
          propagateDownstream(Left(fail), downstreams)
          commitments1.ourParams.autoSignInterval.map(interval => context.system.scheduler.scheduleOnce(interval, self, CMD_SIGN))
          stay using d.copy(commitments = commitments1, downstreams = downstreams - id)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event(CMD_SIGN, d: DATA_NORMAL) if d.commitments.theirNextCommitInfo.isLeft =>
      //TODO : this is a temporary fix
      log.info(s"already in the process of signing, delaying CMD_SIGN")
      import scala.concurrent.ExecutionContext.Implicits.global
      context.system.scheduler.scheduleOnce(100 milliseconds, self, CMD_SIGN)
      stay

    case Event(CMD_SIGN, d: DATA_NORMAL) if !Commitments.weHaveChanges(d.commitments) =>
      // nothing to sign, just ignoring
      log.info("ignoring CMD_SIGN (nothing to sign)")
      stay()

    case Event(CMD_SIGN, d: DATA_NORMAL) =>
      Try(Commitments.sendCommit(d.commitments)) match {
        case Success((commitments1, commit)) => handleCommandSuccess(sender, commit, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(msg@update_commit(theirSig), d: DATA_NORMAL) =>
      Try(Commitments.receiveCommit(d.commitments, msg)) match {
        case Success((commitments1, revocation)) =>
          them ! revocation
          // now that we have their sig, we should propagate the htlcs newly received
          (commitments1.ourCommit.spec.htlcs -- d.commitments.ourCommit.spec.htlcs)
            .filter(_.direction == IN)
            .foreach(htlc => propagateUpstream(htlc.add, d.commitments.anchorId))
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments1))
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event(msg@update_revocation(revocationPreimage, nextRevocationHash), d: DATA_NORMAL) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked
      Try(Commitments.receiveRevocation(d.commitments, msg)) match {
        case Success(commitments1) =>
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleOurError(cause, d)
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

    case Event(theirClearing@close_clearing(theirScriptPubKey), d@DATA_NORMAL(commitments, ourClearingOpt, downstreams)) =>
      val ourClearing: close_clearing = ourClearingOpt.getOrElse {
        val ourScriptPubKey: BinaryData = Script.write(Scripts.pay2pkh(commitments.ourParams.finalPubKey))
        log.info(s"our final tx can be redeemed with ${Base58Check.encode(Base58.Prefix.SecretKeyTestnet, d.commitments.ourParams.finalPrivKey)}")
        val c = close_clearing(ourScriptPubKey)
        them ! c
        c
      }
      if (commitments.hasNoPendingHtlcs) {
        val (_, ourCloseSig) = makeFinalTx(commitments, ourClearing.scriptPubkey, theirScriptPubKey)
        them ! ourCloseSig
        goto(NEGOTIATING) using DATA_NEGOTIATING(commitments, ourClearing, theirClearing, ourCloseSig)
      } else {
        goto(CLEARING) using DATA_CLEARING(commitments, ourClearing, theirClearing, downstreams)
      }

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_NORMAL) if tx.txid == d.commitments.theirCommit.txid => handleTheirSpentCurrent(tx, d)

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_NORMAL) => handleTheirSpentOther(tx, d)

    case Event(e@error(problem), d: DATA_NORMAL) => handleTheirError(e, d)

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
    case Event(c@CMD_FULFILL_HTLC(id, r, do_commit), d: DATA_CLEARING) =>
      Try(Commitments.sendFulfill(d.commitments, c)) match {
        case Success((commitments1, fulfill)) => handleCommandSuccess(sender, fulfill, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(fulfill@update_fulfill_htlc(id, r), d: DATA_CLEARING) =>
      Try(Commitments.receiveFulfill(d.commitments, fulfill)) match {
        case Success(commitments1) =>
          propagateDownstream(Right(fulfill), d.downstreams)
          stay using d.copy(commitments = commitments1, downstreams = d.downstreams - id)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event(c@CMD_FAIL_HTLC(id, reason, do_commit), d: DATA_CLEARING) =>
      Try(Commitments.sendFail(d.commitments, c)) match {
        case Success((commitments1, fail)) => handleCommandSuccess(sender, fail, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(fail@update_fail_htlc(id, reason), d: DATA_CLEARING) =>
      Try(Commitments.receiveFail(d.commitments, fail)) match {
        case Success(commitments1) =>
          propagateDownstream(Left(fail), d.downstreams)
          stay using d.copy(commitments = commitments1, downstreams = d.downstreams - id)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event(CMD_SIGN, d: DATA_CLEARING) if d.commitments.theirNextCommitInfo.isLeft =>
      //TODO : this is a temporary fix
      log.info(s"already in the process of signing, delaying CMD_SIGN")
      import scala.concurrent.ExecutionContext.Implicits.global
      context.system.scheduler.scheduleOnce(100 milliseconds, self, CMD_SIGN)
      stay

    case Event(CMD_SIGN, d: DATA_CLEARING) if !Commitments.weHaveChanges(d.commitments) =>
      // nothing to sign, just ignoring
      log.info("ignoring CMD_SIGN (nothing to sign)")
      stay()

    case Event(CMD_SIGN, d: DATA_CLEARING) =>
      Try(Commitments.sendCommit(d.commitments)) match {
        case Success((commitments1, commit)) => handleCommandSuccess(sender, commit, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(msg@update_commit(theirSig), d@DATA_CLEARING(commitments, ourClearing, theirClearing, _)) =>
      // TODO : we might have to propagate htlcs upstream depending on the outcome of https://github.com/ElementsProject/lightning/issues/29
      Try(Commitments.receiveCommit(d.commitments, msg)) match {
        case Success((commitments1, revocation)) if commitments1.hasNoPendingHtlcs =>
          them ! revocation
          val (_, ourCloseSig) = makeFinalTx(commitments1, ourClearing.scriptPubkey, theirClearing.scriptPubkey)
          them ! ourCloseSig
          goto(NEGOTIATING) using DATA_NEGOTIATING(commitments1, ourClearing, theirClearing, ourCloseSig)
        case Success((commitments1, revocation)) =>
          them ! revocation
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event(msg@update_revocation(revocationPreimage, nextRevocationHash), d@DATA_CLEARING(commitments, ourClearing, theirClearing, _)) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked
      Try(Commitments.receiveRevocation(d.commitments, msg)) match {
        case Success(commitments1) if commitments1.hasNoPendingHtlcs =>
          val (finalTx, ourCloseSig) = makeFinalTx(commitments1, ourClearing.scriptPubkey, theirClearing.scriptPubkey)
          them ! ourCloseSig
          goto(NEGOTIATING) using DATA_NEGOTIATING(commitments1, ourClearing, theirClearing, ourCloseSig)
        case Success(commitments1) =>
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_CLEARING) if tx.txid == d.commitments.theirCommit.txid => handleTheirSpentCurrent(tx, d)

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_CLEARING) => handleTheirSpentOther(tx, d)

    case Event(e@error(problem), d: DATA_CLEARING) => handleTheirError(e, d)
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

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_NEGOTIATING) if tx.txid == d.commitments.theirCommit.txid => handleTheirSpentCurrent(tx, d)

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_NEGOTIATING) => handleTheirSpentOther(tx, d)

    case Event(e@error(problem), d: DATA_NEGOTIATING) => handleTheirError(e, d)

  }

  when(CLOSING) {
    case Event(close_signature(theirCloseFee, theirSig), d: DATA_CLOSING) if d.ourSignature.map(_.closeFee) == Some(theirCloseFee) =>
      // expected in case of a mutual close
      stay()

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_CLOSING) if tx.txid == d.commitments.ourCommit.publishableTx.txid =>
      // we just initiated a uniclose moments ago and are now receiving the blockchain notification, there is nothing to do
      stay()

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_CLOSING) if Some(tx.txid) == d.mutualClosePublished.map(_.txid) =>
      // we just published a mutual close tx, we are notified but it's alright
      stay()

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_CLOSING) if tx.txid == d.commitments.theirCommit.txid =>
      // counterparty may attempt to spend its last commit tx at any time
      handleTheirSpentCurrent(tx, d)

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_CLOSING) =>
      // counterparty may attempt to spend a revoked commit tx at any time
      handleTheirSpentOther(tx, d)

    case Event(BITCOIN_CLOSE_DONE, d: DATA_CLOSING) if d.mutualClosePublished.isDefined => goto(CLOSED)

    case Event(BITCOIN_SPEND_OURS_DONE, d: DATA_CLOSING) if d.ourCommitPublished.isDefined => goto(CLOSED)

    case Event(BITCOIN_SPEND_THEIRS_DONE, d: DATA_CLOSING) if d.theirCommitPublished.isDefined => goto(CLOSED)

    case Event(BITCOIN_STEAL_DONE, d: DATA_CLOSING) if d.revokedPublished.size > 0 => goto(CLOSED)

    case Event(e@error(problem), d: DATA_CLOSING) => stay // nothing to do, there is already a spending tx published
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

    // because channels send CMD to each others when relaying payments
    case Event("ok", _) => stay
  }

  onTransition {
    case previousState -> currentState => context.system.eventStream.publish(ChannelChangedState(self, previousState, currentState, stateData))
  }

  /*
          888    888        d8888 888b    888 8888888b.  888      8888888888 8888888b.   .d8888b.
          888    888       d88888 8888b   888 888  "Y88b 888      888        888   Y88b d88P  Y88b
          888    888      d88P888 88888b  888 888    888 888      888        888    888 Y88b.
          8888888888     d88P 888 888Y88b 888 888    888 888      8888888    888   d88P  "Y888b.
          888    888    d88P  888 888 Y88b888 888    888 888      888        8888888P"      "Y88b.
          888    888   d88P   888 888  Y88888 888    888 888      888        888 T88b         "888
          888    888  d8888888888 888   Y8888 888  .d88P 888      888        888  T88b  Y88b  d88P
          888    888 d88P     888 888    Y888 8888888P"  88888888 8888888888 888   T88b  "Y8888P"
   */

  def propagateUpstream(add: update_add_htlc, anchorId: BinaryData) = {
    val r = route.parseFrom(add.route.info.toByteArray)
    r.steps match {
      case route_step(amountMsat, Next.Bitcoin(nextNodeId)) +: rest =>
        log.debug(s"propagating htlc #${add.id} to $nextNodeId")
        import ExecutionContext.Implicits.global
        context.system.actorSelection(Register.actorPathToNodeId(nextNodeId))
          .resolveOne(3 seconds)
          .onComplete {
            case Success(upstream) =>
              log.info(s"forwarding htlc #${add.id} to upstream=$upstream")
              val upstream_route = route(rest)
              // TODO : we should decrement expiry !!
              upstream ! CMD_ADD_HTLC(amountMsat, add.rHash, add.expiry, upstream_route, Some(anchorId))
              upstream ! CMD_SIGN
            case Failure(t: Throwable) =>
              // TODO : send "fail route error"
              log.warning(s"couldn't resolve upstream node, htlc #${add.id} will timeout", t)
          }
      case route_step(amount, Next.End(true)) +: rest =>
        log.info(s"we are the final recipient of htlc #${add.id}")
        paymentHandler ! add
    }
  }

  def propagateDownstream(fulfill_or_fail: Either[update_fail_htlc, update_fulfill_htlc], downstreams: Map[Long, Option[BinaryData]]) = {
    val (id, short, cmd: Command) = fulfill_or_fail match {
      case Left(fail) => (fail.id, "fail", CMD_FAIL_HTLC(fail.id, fail.reason.info.toStringUtf8))
      case Right(fulfill) => (fulfill.id, "fulfill", CMD_FULFILL_HTLC(fulfill.id, fulfill.r))
    }
    downstreams(id) match {
      case Some(previousChannelId) =>
        log.debug(s"propagating $short for htlc #$id to $previousChannelId")
        import ExecutionContext.Implicits.global
        context.system.actorSelection(Register.actorPathToChannelId(previousChannelId))
          .resolveOne(3 seconds)
          .onComplete {
            case Success(downstream) =>
              log.info(s"forwarding $short to downstream=$downstream")
              downstream ! CMD_SIGN
              downstream ! cmd
              downstream ! CMD_SIGN
            case Failure(t: Throwable) =>
              log.warning(s"couldn't resolve downstream node, htlc #$id will timeout", t)
          }
      case None =>
        log.info(s"we were the origin payer for htlc #$id")
    }
  }

  def handleCommandSuccess(sender: ActorRef, change: Change, newData: Data) = {
    them ! change
    sender ! "ok"
    stay using newData
  }

  def handleCommandError(sender: ActorRef, cause: Throwable) = {
    log.error(cause, "")
    sender ! cause.getMessage
    stay
  }

  def handleOurError(cause: Throwable, d: HasCommitments) = {
    log.error(cause, "")
    them ! error(Some(cause.getMessage))
    spendOurCurrent(d)
  }

  def handleTheirError(e: error, d: HasCommitments) = {
    log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
    spendOurCurrent(d)
  }

  def spendOurCurrent(d: HasCommitments) = {
    val tx = d.commitments.ourCommit.publishableTx

    blockchain ! Publish(tx)
    blockchain ! WatchConfirmed(self, tx.txid, d.commitments.ourParams.minDepth, BITCOIN_SPEND_OURS_DONE)

    val txs1 = Helpers.claimReceivedHtlcs(tx, Commitments.makeOurTxTemplate(d.commitments), d.commitments)
    val txs2 = Helpers.claimSentHtlcs(tx, Commitments.makeOurTxTemplate(d.commitments), d.commitments)
    val txs = txs1 ++ txs2
    txs.map(tx => blockchain ! PublishAsap(tx))

    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(ourCommitPublished = Some(tx))
      case _ => DATA_CLOSING(d.commitments, ourCommitPublished = Some(tx))
    }

    goto(CLOSING) using nextData
  }

  def handleTheirSpentCurrent(tx: Transaction, d: HasCommitments) = {
    log.warning(s"they published their current commit in txid=${tx.txid}")
    assert(tx.txid == d.commitments.theirCommit.txid)

    blockchain ! WatchConfirmed(self, tx.txid, d.commitments.ourParams.minDepth, BITCOIN_SPEND_THEIRS_DONE)

    val txs1 = Helpers.claimReceivedHtlcs(tx, Commitments.makeTheirTxTemplate(d.commitments), d.commitments)
    val txs2 = Helpers.claimSentHtlcs(tx, Commitments.makeTheirTxTemplate(d.commitments), d.commitments)
    val txs = txs1 ++ txs2
    txs.map(tx => blockchain ! PublishAsap(tx))

    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(theirCommitPublished = Some(tx))
      case _ => DATA_CLOSING(d.commitments, theirCommitPublished = Some(tx))
    }

    goto(CLOSING) using nextData
  }

  def handleTheirSpentOther(tx: Transaction, d: HasCommitments) = {
    log.warning(s"anchor spent in txid=${tx.txid}")
    d.commitments.txDb.get(tx.txid) match {
      case Some(spendingTx) =>
        log.warning(s"txid=${tx.txid} was a revoked commitment, publishing the punishment tx")
        them ! error(Some("Anchor has been spent"))
        blockchain ! Publish(spendingTx)
        blockchain ! WatchConfirmed(self, spendingTx.txid, d.commitments.ourParams.minDepth, BITCOIN_STEAL_DONE)
        val nextData = d match {
          case closing: DATA_CLOSING => closing.copy(revokedPublished = closing.revokedPublished :+ tx)
          case _ => DATA_CLOSING(d.commitments, revokedPublished = Seq(tx))
        }
        goto(CLOSING) using nextData
      case None =>
        // the published tx was neither their current commitment nor a revoked one
        log.error(s"couldn't identify txid=${tx.txid}!")
        goto(ERR_INFORMATION_LEAK)
    }
  }

  def handleInformationLeak(d: HasCommitments) = {
    // this is never supposed to happen !!
    log.error(s"our anchor ${d.commitments.anchorId} was spent !!")
    them ! error(Some("Anchor has been spent"))
    blockchain ! Publish(d.commitments.ourCommit.publishableTx)
    goto(ERR_INFORMATION_LEAK)
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
          case d: HasCommitments => handleOurError(t, d)
          case _ => goto(CLOSED)
        }
      }
  }

}




