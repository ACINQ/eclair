package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM, LoggingFSM, Props}
import fr.acinq.bitcoin.{OutPoint, _}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.Helpers.Closing._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.CommitmentSpec._
import fr.acinq.eclair.transactions.Signature._
import fr.acinq.eclair.transactions.{CommitmentSpec, Htlc, IN, OldScripts}
import fr.acinq.eclair.wire._

import scala.compat.Platform
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}


/**
  * Created by PM on 20/08/2015.
  */

object Channel {
  def props(them: ActorRef, blockchain: ActorRef, paymentHandler: ActorRef, params: OurChannelParams, theirNodeId: String) = Props(new Channel(them, blockchain, paymentHandler, params, theirNodeId))
}

class Channel(val them: ActorRef, val blockchain: ActorRef, paymentHandler: ActorRef, val params: OurChannelParams, theirNodeId: String)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends LoggingFSM[State, Data] {

  log.info(s"commit pubkey: ${params.commitPubKey}")
  log.info(s"final pubkey: ${params.finalPubKey}")

  context.system.eventStream.publish(ChannelCreated(self, params, theirNodeId))

  val localParams = LocalParams(
    dustLimitSatoshis = 542,
    maxHtlcValueInFlightMsat = Long.MaxValue,
    channelReserveSatoshis = 0,
    htlcMinimumMsat = 0,
    maxNumHtlcs = 100,
    feeratePerKb = 10000,
    toSelfDelay = 144,
    fundingPubkey = params.commitPubKey,
    revocationBasepoint = Array.fill[Byte](33)(0),
    paymentBasepoint = Array.fill[Byte](33)(0),
    delayedPaymentBasepoint = Array.fill[Byte](33)(0),
    finalScriptPubKey = Array.fill[Byte](47)(0)
  )

  params.anchorAmount match {
    case None =>
      startWith(WAIT_FOR_OPEN_CHANNEL, DATA_WAIT_FOR_OPEN_CHANNEL(localParams, shaSeed = params.shaSeed, autoSignInterval = params.autoSignInterval))
    case Some(amount) =>
      val temporaryChannelId = Platform.currentTime
      val fundingSatoshis = amount.amount
      val pushMsat = 0
      val firstPerCommitmentPoint: BinaryData = ???
      them ! OpenChannel(temporaryChannelId = temporaryChannelId,
        fundingSatoshis = fundingSatoshis,
        pushMsat = pushMsat,
        dustLimitSatoshis = localParams.dustLimitSatoshis,
        maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
        channelReserveSatoshis = localParams.channelReserveSatoshis,
        htlcMinimumMsat = localParams.htlcMinimumMsat,
        maxNumHtlcs = localParams.maxNumHtlcs,
        feeratePerKb = localParams.feeratePerKb,
        toSelfDelay = localParams.toSelfDelay,
        fundingPubkey = localParams.fundingPubkey,
        revocationBasepoint = localParams.revocationBasepoint,
        paymentBasepoint = localParams.paymentBasepoint,
        delayedPaymentBasepoint = localParams.delayedPaymentBasepoint,
        firstPerCommitmentPoint = firstPerCommitmentPoint)
      startWith(WAIT_FOR_ACCEPT_CHANNEL, DATA_WAIT_FOR_ACCEPT_CHANNEL(temporaryChannelId, localParams, fundingSatoshis = fundingSatoshis, pushMsat = pushMsat, autoSignInterval = params.autoSignInterval))
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
                                                OLD
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
                                 | open_complete    open_complete |
                                 |---------------  ---------------|
 OPEN_WAIT_FOR_COMPLETE_OURANCHOR|               \/               |OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR
                                 |               /\               |
                                 |<--------------  -------------->|
                           NORMAL|                                |NORMAL

                                                NEW
                              FUNDER                            FUNDEE
                                 |                                |
                                 |          open_channel          |WAIT_FOR_OPEN_CHANNEL
                                 |------------------------------->|
          WAIT_FOR_ACCEPT_CHANNEL|                                |
                                 |         accept_channel         |
                                 |<-------------------------------|
                                 |                                |WAIT_FOR_FUNDING_CREATED
                                 |        funding_created         |
                                 |------------------------------->|
          WAIT_FOR_FUNDING_SIGNED|                                |
                                 |         funding_signed         |
                                 |<-------------------------------|
          WAIT_FOR_FUNDING_LOCKED|                                |WAIT_FOR_FUNDING_LOCKED
                                 | funding_locked  funding_locked |
                                 |---------------  ---------------|
                                 |               \/               |
                                 |               /\               |
                                 |<--------------  -------------->|
                           NORMAL|                                |NORMAL
   */
  when(WAIT_FOR_OPEN_CHANNEL)(handleExceptions {
    case Event(open: OpenChannel, DATA_WAIT_FOR_OPEN_CHANNEL(localParams, shaSeed, autoSignInterval)) =>
      // TODO : here we should check if remote parameters suit us
      // TODO : maybe also check uniqueness of temporary channel id
      val minimumDepth = 6
      them ! AcceptChannel(temporaryChannelId = Platform.currentTime,
        dustLimitSatoshis = localParams.dustLimitSatoshis,
        maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
        channelReserveSatoshis = localParams.channelReserveSatoshis,
        minimumDepth = minimumDepth,
        htlcMinimumMsat = localParams.htlcMinimumMsat,
        maxNumHtlcs = localParams.maxNumHtlcs,
        toSelfDelay = localParams.toSelfDelay,
        fundingPubkey = localParams.fundingPubkey,
        revocationBasepoint = Array.fill[Byte](33)(0),
        paymentBasepoint = Array.fill[Byte](33)(0),
        delayedPaymentBasepoint = Array.fill[Byte](33)(0),
        firstPerCommitmentPoint = Array.fill[Byte](32)(0))
      val remoteParams = RemoteParams(
        dustLimitSatoshis = open.dustLimitSatoshis,
        maxHtlcValueInFlightMsat = open.maxHtlcValueInFlightMsat,
        channelReserveSatoshis = open.channelReserveSatoshis,
        htlcMinimumMsat = open.htlcMinimumMsat,
        maxNumHtlcs = open.maxNumHtlcs,
        feeratePerKb = open.feeratePerKb,
        toSelfDelay = open.toSelfDelay,
        fundingPubkey = open.fundingPubkey,
        revocationBasepoint = open.fundingPubkey,
        paymentBasepoint = open.paymentBasepoint,
        delayedPaymentBasepoint = open.delayedPaymentBasepoint)
      log.debug(s"remote params: $remoteParams")
      val channelParams = ChannelParams(
        localParams = localParams,
        remoteParams = remoteParams,
        fundingSatoshis = open.fundingSatoshis,
        minimumDepth = minimumDepth,
        shaSeed = params.shaSeed,
        autoSignInterval = autoSignInterval)
      goto(WAIT_FOR_FUNDING_CREATED) using DATA_WAIT_FOR_FUNDING_CREATED(open.temporaryChannelId, channelParams, open.pushMsat)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(WAIT_FOR_ACCEPT_CHANNEL)(handleExceptions {
    case Event(accept: AcceptChannel, DATA_WAIT_FOR_ACCEPT_CHANNEL(temporaryChannelId, localParams, fundingSatoshis, pushMsat, autoSignInterval)) =>
      // TODO : here we should check if remote parameters suit us
      // TODO : check equality of temporaryChannelId? or should be done upstream
      val remoteParams = RemoteParams(
        dustLimitSatoshis = accept.dustLimitSatoshis,
        maxHtlcValueInFlightMsat = accept.maxHtlcValueInFlightMsat,
        channelReserveSatoshis = accept.channelReserveSatoshis,
        htlcMinimumMsat = accept.htlcMinimumMsat,
        maxNumHtlcs = accept.maxNumHtlcs,
        feeratePerKb = localParams.feeratePerKb, // TODO : this should probably be in AcceptChannel packet
        toSelfDelay = accept.toSelfDelay,
        fundingPubkey = accept.fundingPubkey,
        revocationBasepoint = accept.fundingPubkey,
        paymentBasepoint = accept.paymentBasepoint,
        delayedPaymentBasepoint = accept.delayedPaymentBasepoint
      )
      log.debug(s"remote params: $remoteParams")
      val channelParams = ChannelParams(
        localParams = localParams,
        remoteParams = remoteParams,
        fundingSatoshis = fundingSatoshis,
        minimumDepth = accept.minimumDepth,
        shaSeed = params.shaSeed,
        autoSignInterval = autoSignInterval)
      blockchain ! MakeAnchor(localParams.fundingPubkey, remoteParams.fundingPubkey, Satoshi(channelParams.fundingSatoshis))
      goto(WAIT_FOR_FUNDING_CREATED_INTERNAL) using DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, channelParams, pushMsat, accept.firstPerCommitmentPoint)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_CREATED_INTERNAL)(handleExceptions {
    case Event((anchorTx: Transaction, anchorOutputIndex: Int), DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, channelParams, pushMsat, remoteFirstPerCommitmentPoint)) =>
      log.info(s"anchor txid=${anchorTx.txid}")
      val theirSpec = CommitmentSpec(Set.empty[Htlc], feeRate = channelParams.remoteParams.feeratePerKb, to_local_msat = pushMsat, to_remote_msat = channelParams.fundingSatoshis * 1000 - pushMsat)
      val theirRevocationPubkey: BinaryData = ???
      // some combination of params.remoteParams.revocationBasepoint and remoteFirstPerCommitmentPoint
      val ourSigForThem: BinaryData = ??? // signature of their initial commitment tx that pays them pushMsat
      them ! FundingCreated(
        temporaryChannelId = temporaryChannelId,
        txid = anchorTx.hash,
        outputIndex = anchorOutputIndex,
        signature = ourSigForThem
      )
      goto(WAIT_FOR_FUNDING_SIGNED) using DATA_WAIT_FOR_FUNDING_SIGNED(temporaryChannelId, channelParams, pushMsat, anchorTx, anchorOutputIndex, RemoteCommit(0, theirSpec, anchorTx.hash, theirRevocationPubkey))

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_CREATED)(handleExceptions {
    case Event(FundingCreated(_, anchorTxHash, anchorOutputIndex, remoteSignature), DATA_WAIT_FOR_FUNDING_CREATED(temporaryChannelId, channelParams, pushMsat)) =>
      val anchorTxid = anchorTxHash.reverse //see https://github.com/ElementsProject/lightning/issues/17

      val anchorOutput = TxOut(Satoshi(channelParams.fundingSatoshis), publicKeyScript = OldScripts.anchorPubkeyScript(channelParams.localParams.fundingPubkey, channelParams.remoteParams.fundingPubkey))

      // they fund the channel with their anchor tx, so the money is theirs (but we are paid pushMsat)
      val ourSpec = CommitmentSpec(Set.empty[Htlc], feeRate = channelParams.localParams.feeratePerKb, to_remote_msat = channelParams.fundingSatoshis * 1000 - pushMsat, to_local_msat = pushMsat)
      val theirSpec = CommitmentSpec(Set.empty[Htlc], feeRate = channelParams.remoteParams.feeratePerKb, to_remote_msat = pushMsat, to_local_msat = channelParams.fundingSatoshis * 1000 - pushMsat)

      // build and sign their commit tx
      val theirTx: Transaction = ??? //makeTheirTx(ourParams, theirParams, TxIn(OutPoint(anchorTxHash, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, theirRevocationHash, theirSpec)
      log.info(s"signing their tx: $theirTx")
      val ourSigForThem: BinaryData = ??? // sign(ourParams, theirParams, Satoshi(anchorAmount), theirTx)
      them ! FundingSigned(
        temporaryChannelId = temporaryChannelId,
        signature = ourSigForThem
      )

      // watch the anchor
      blockchain ! WatchSpent(self, anchorTxid, anchorOutputIndex, 0, BITCOIN_FUNDING_SPENT) // TODO : should we wait for an acknowledgment from watcher?
      blockchain ! WatchConfirmed(self, anchorTxid, channelParams.minimumDepth.toInt, BITCOIN_FUNDING_DEPTHOK)

      val ourRevocationPubkey: BinaryData = ???
      // Helpers.revocationHash(ourParams.shaSeed, 0)
      val ourTx: Transaction = ??? // makeOurTx(ourParams, theirParams, TxIn(OutPoint(anchorTxHash, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, ourRevocationHash, ourSpec)

      val commitments = Commitments(channelParams.localParams, channelParams.remoteParams,
        LocalCommit(0, ourSpec, ourTx), RemoteCommit(0, theirSpec, theirTx.txid, ???),
        LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil), 0L,
        Right(???), anchorOutput, params.shaSeed, ShaChain.init, new BasicTxDb)
      context.system.eventStream.publish(ChannelIdAssigned(self, commitments.anchorId, Satoshi(channelParams.fundingSatoshis)))
      goto(WAIT_FOR_FUNDING_LOCKED) using DATA_WAIT_FOR_FUNDING_LOCKED(temporaryChannelId, channelParams, commitments, None)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_SIGNED)(handleExceptions {
    case Event(FundingSigned(_, theirSig), DATA_WAIT_FOR_FUNDING_SIGNED(temporaryChannelId, channelParams, pushMsat, anchorTx, anchorOutputIndex, remoteCommit)) =>
      val anchorAmount = anchorTx.txOut(anchorOutputIndex).amount
      val remoteSpec = remoteCommit.spec
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val ourRevocationHash = Commitments.revocationHash(channelParams.shaSeed, 0L)
      val ourSpec = CommitmentSpec(Set.empty[Htlc], feeRate = channelParams.localParams.feeratePerKb, to_local_msat = anchorAmount.toLong * 1000 - pushMsat, to_remote_msat = pushMsat)
      val ourTx = makeLocalTx(channelParams.localParams, channelParams.remoteParams, TxIn(OutPoint(anchorTx, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, ourRevocationHash, ourSpec)
      log.info(s"checking our tx: $ourTx")
      val ourSig = sign(channelParams.localParams, channelParams.remoteParams, anchorAmount, ourTx)
      val signedTx: Transaction = ???
      //addSigs(ourParams, theirParams, anchorAmount, ourTx, ourSig, theirSig)
      val anchorOutput: TxOut = ??? //anchorTx.txOut(anchorOutputIndex)
      checksig(channelParams.localParams, channelParams.remoteParams, anchorOutput, signedTx) match {
        case Failure(cause) =>
          log.error(cause, "their FundingSigned message contains an invalid signature")
          them ! Error(temporaryChannelId, cause.getMessage.getBytes)
          // we haven't published anything yet, we can just stop
          goto(CLOSED)
        case Success(_) =>
          blockchain ! WatchConfirmed(self, anchorTx.txid, channelParams.minimumDepth, BITCOIN_FUNDING_DEPTHOK)
          blockchain ! WatchSpent(self, anchorTx.txid, anchorOutputIndex, 0, BITCOIN_FUNDING_SPENT)
          blockchain ! Publish(anchorTx)
          val commitments = Commitments(channelParams.localParams, channelParams.remoteParams,
            LocalCommit(0, ourSpec, signedTx), remoteCommit,
            LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil), 0L,
            Right(???), anchorOutput, channelParams.shaSeed, ShaChain.init, new BasicTxDb)
          context.system.eventStream.publish(ChannelIdAssigned(self, commitments.anchorId, anchorAmount))
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
          goto(WAIT_FOR_FUNDING_LOCKED) using DATA_WAIT_FOR_FUNDING_LOCKED(temporaryChannelId, channelParams, commitments, None)
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_LOCKED_INTERNAL)(handleExceptions {
    case Event(msg: FundingLocked, d: DATA_WAIT_FOR_FUNDING_LOCKED) =>
      log.info(s"received their FundingLocked, deferring message")
      stay using d.copy(deferred = Some(msg))

    case Event(BITCOIN_FUNDING_DEPTHOK, d@DATA_WAIT_FOR_FUNDING_LOCKED(temporaryChannelId, channelParams, commitments, deferred)) =>
      val channelId = 0L
      blockchain ! WatchLost(self, commitments.anchorId, channelParams.minimumDepth, BITCOIN_FUNDING_LOST)
      them ! FundingLocked(channelId, 0L, ???) // TODO
      deferred.map(self ! _)
      //TODO htlcIdx should not be 0 when resuming connection
      goto(WAIT_FOR_FUNDING_LOCKED) using DATA_NORMAL(channelId, channelParams, commitments, None, Map())

    case Event(BITCOIN_FUNDING_TIMEOUT, _) =>
      them ! Error(0, "Anchor timed out".getBytes)
      goto(CLOSED)

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_WAIT_FOR_FUNDING_LOCKED) if tx.txid == d.commitments.remoteCommit.txid =>
      // they are funding the anchor, we have nothing at stake
      log.warning(s"their anchor ${d.commitments.anchorId} was spent, sending error and closing")
      them ! Error(0, s"your anchor ${d.commitments.anchorId} was spent".getBytes())
      goto(CLOSED)

    case Event((BITCOIN_FUNDING_SPENT, _), d: DATA_WAIT_FOR_FUNDING_LOCKED) => handleInformationLeak(d)

    case Event(cmd: CMD_CLOSE, d: DATA_WAIT_FOR_FUNDING_LOCKED) =>
      blockchain ! Publish(d.commitments.localCommit.publishableTx)
      blockchain ! WatchConfirmed(self, d.commitments.localCommit.publishableTx.txid, d.channelParams.minimumDepth, BITCOIN_CLOSE_DONE)
      goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.localCommit.publishableTx))

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_LOCKED) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      blockchain ! Publish(d.commitments.localCommit.publishableTx)
      blockchain ! WatchConfirmed(self, d.commitments.localCommit.publishableTx.txid, d.channelParams.minimumDepth, BITCOIN_CLOSE_DONE)
      goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.localCommit.publishableTx))
  })

  when(WAIT_FOR_FUNDING_LOCKED)(handleExceptions {
    case Event(FundingLocked(temporaryChannelId, channelId, nextPerCommitmentPoint), d: DATA_NORMAL) =>
      Register.create_alias(theirNodeId, d.commitments.anchorId)
      goto(NORMAL)

    case Event((BITCOIN_FUNDING_SPENT, _), d: DATA_NORMAL) => handleInformationLeak(d)

    case Event(cmd: CMD_CLOSE, d: DATA_NORMAL) =>
      blockchain ! Publish(d.commitments.localCommit.publishableTx)
      blockchain ! WatchConfirmed(self, d.commitments.localCommit.publishableTx.txid, d.channelParams.minimumDepth, BITCOIN_CLOSE_DONE)
      goto(CLOSING) using DATA_CLOSING(d.commitments, ourCommitPublished = Some(d.commitments.localCommit.publishableTx))

    case Event(e: Error, d: DATA_NORMAL) => handleTheirError(e, d)
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

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) if d.ourShutdown.isDefined =>
      handleCommandError(sender, new RuntimeException("cannot send new htlcs, closing in progress"))
      stay

    case Event(c@CMD_ADD_HTLC(amountMsat, rHash, expiry, route, origin, id_opt, do_commit), d@DATA_NORMAL(channelId, channelParams, commitments, _, downstreams)) =>
      Try(Commitments.sendAdd(commitments, c)) match {
        case Success((commitments1, add)) =>
          if (do_commit) self ! CMD_SIGN
          handleCommandSuccess(sender, add, d.copy(commitments = commitments1, downstreams = downstreams + (add.id -> origin)))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(add: UpdateAddHtlc, d@DATA_NORMAL(_, channelParams, commitments, _, _)) =>
      Try(Commitments.receiveAdd(commitments, add)) match {
        case Success(commitments1) =>
          import scala.concurrent.ExecutionContext.Implicits.global
          channelParams.autoSignInterval.map(interval => context.system.scheduler.scheduleOnce(interval, self, CMD_SIGN))
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event(c@CMD_FULFILL_HTLC(id, r, do_commit), d: DATA_NORMAL) =>
      Try(Commitments.sendFulfill(d.commitments, c, d.channelId)) match {
        case Success((commitments1, fulfill)) =>
          if (do_commit) self ! CMD_SIGN
          handleCommandSuccess(sender, fulfill, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(fulfill@UpdateFulfillHtlc(_, id, r), d@DATA_NORMAL(channelId, channelParams, commitments, _, downstreams)) =>
      Try(Commitments.receiveFulfill(d.commitments, fulfill)) match {
        case Success((commitments1, htlc)) =>
          propagateDownstream(htlc, Right(fulfill), downstreams(id))
          import scala.concurrent.ExecutionContext.Implicits.global
          channelParams.autoSignInterval.map(interval => context.system.scheduler.scheduleOnce(interval, self, CMD_SIGN))
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

    case Event(fail@UpdateFailHtlc(_, id, reason), d@DATA_NORMAL(channelId, channelParams, commitments, _, downstreams)) =>
      Try(Commitments.receiveFail(d.commitments, fail)) match {
        case Success((commitments1, htlc)) =>
          propagateDownstream(htlc, Left(fail), downstreams(id))
          import scala.concurrent.ExecutionContext.Implicits.global
          channelParams.autoSignInterval.map(interval => context.system.scheduler.scheduleOnce(interval, self, CMD_SIGN))
          stay using d.copy(commitments = commitments1, downstreams = downstreams - id)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event(CMD_SIGN, d: DATA_NORMAL) if d.commitments.remoteNextCommitInfo.isLeft =>
      //TODO : this is a temporary fix
      log.info(s"already in the process of signing, delaying CMD_SIGN")
      context.system.scheduler.scheduleOnce(100 milliseconds, self, CMD_SIGN)
      stay

    case Event(CMD_SIGN, d: DATA_NORMAL) if !Commitments.localHasChanges(d.commitments) =>
      // nothing to sign, just ignoring
      log.info("ignoring CMD_SIGN (nothing to sign)")
      stay()

    case Event(CMD_SIGN, d: DATA_NORMAL) =>
      Try(Commitments.sendCommit(d.commitments)) match {
        case Success((commitments1, commit)) => handleCommandSuccess(sender, commit, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(msg@CommitSig(_, theirSig, theirHtlcSigs), d: DATA_NORMAL) =>
      Try(Commitments.receiveCommit(d.commitments, msg)) match {
        case Success((commitments1, revocation)) =>
          them ! revocation
          // now that we have their sig, we should propagate the htlcs newly received
          (commitments1.localCommit.spec.htlcs -- d.commitments.localCommit.spec.htlcs)
            .filter(_.direction == IN)
            .foreach(htlc => propagateUpstream(htlc.add, d.commitments.anchorId))
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments1))
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event(msg: RevokeAndAck, d: DATA_NORMAL) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked
      Try(Commitments.receiveRevocation(d.commitments, msg)) match {
        case Success(commitments1) =>
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event(CMD_CLOSE(ourScriptPubKey_opt), d: DATA_NORMAL) =>
      if (d.ourShutdown.isDefined) {
        sender ! "closing already in progress"
        stay
      } else {
        val ourScriptPubKey = ourScriptPubKey_opt.getOrElse(d.channelParams.localParams.finalScriptPubKey)
        val ourShutdown = Shutdown(d.channelId, ourScriptPubKey)
        them ! ourShutdown
        stay using d.copy(ourShutdown = Some(ourShutdown))
      }

    case Event(theirShutdown@Shutdown(_, theirScriptPubKey), d@DATA_NORMAL(channelId, channelParams, commitments, ourShutdownOpt, downstreams)) =>
      val ourShutdown: Shutdown = ourShutdownOpt.getOrElse {
        val c = Shutdown(channelId, channelParams.localParams.finalScriptPubKey)
        them ! c
        c
      }
      if (commitments.hasNoPendingHtlcs) {
        val (_, fee, ourCloseSig) = makeFinalTx(commitments, ourShutdown.scriptPubKey, theirScriptPubKey)
        val closingSigned = ClosingSigned(channelId, fee, ourCloseSig)
        them ! ourCloseSig
        goto(NEGOTIATING) using DATA_NEGOTIATING(channelId, channelParams, commitments, ourShutdown, theirShutdown, closingSigned)
      } else {
        goto(SHUTDOWN) using DATA_SHUTDOWN(channelId, channelParams, commitments, ourShutdown, theirShutdown, downstreams)
      }

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NORMAL) if tx.txid == d.commitments.remoteCommit.txid => handleTheirSpentCurrent(tx, d)

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NORMAL) => handleTheirSpentOther(tx, d)

    case Event(e: Error, d: DATA_NORMAL) => handleTheirError(e, d)

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

  when(SHUTDOWN) {
    case Event(c@CMD_FULFILL_HTLC(id, r, do_commit), d: DATA_SHUTDOWN) =>
      Try(Commitments.sendFulfill(d.commitments, c, d.channelId)) match {
        case Success((commitments1, fulfill)) => handleCommandSuccess(sender, fulfill, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(fulfill@UpdateFulfillHtlc(_, id, r), d: DATA_SHUTDOWN) =>
      Try(Commitments.receiveFulfill(d.commitments, fulfill)) match {
        case Success((commitments1, htlc)) =>
          propagateDownstream(htlc, Right(fulfill), d.downstreams(id))
          stay using d.copy(commitments = commitments1, downstreams = d.downstreams - id)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event(c@CMD_FAIL_HTLC(id, reason, do_commit), d: DATA_SHUTDOWN) =>
      Try(Commitments.sendFail(d.commitments, c)) match {
        case Success((commitments1, fail)) => handleCommandSuccess(sender, fail, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(fail@UpdateFailHtlc(_, id, reason), d: DATA_SHUTDOWN) =>
      Try(Commitments.receiveFail(d.commitments, fail)) match {
        case Success((commitments1, htlc)) =>
          propagateDownstream(htlc, Left(fail), d.downstreams(id))
          stay using d.copy(commitments = commitments1, downstreams = d.downstreams - id)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event(CMD_SIGN, d: DATA_SHUTDOWN) if d.commitments.remoteNextCommitInfo.isLeft =>
      //TODO : this is a temporary fix
      log.info(s"already in the process of signing, delaying CMD_SIGN")
      context.system.scheduler.scheduleOnce(100 milliseconds, self, CMD_SIGN)
      stay

    case Event(CMD_SIGN, d: DATA_SHUTDOWN) if !Commitments.localHasChanges(d.commitments) =>
      // nothing to sign, just ignoring
      log.info("ignoring CMD_SIGN (nothing to sign)")
      stay()

    case Event(CMD_SIGN, d: DATA_SHUTDOWN) =>
      Try(Commitments.sendCommit(d.commitments)) match {
        case Success((commitments1, commit)) => handleCommandSuccess(sender, commit, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(msg@CommitSig(_, theirSig, theirHtlcSigs), d@DATA_SHUTDOWN(channelId, channelParams, commitments, ourShutdown, theirShutdown, _)) =>
      // TODO : we might have to propagate htlcs upstream depending on the outcome of https://github.com/ElementsProject/lightning/issues/29
      Try(Commitments.receiveCommit(d.commitments, msg)) match {
        case Success((commitments1, revocation)) if commitments1.hasNoPendingHtlcs =>
          them ! revocation
          val (_, fee, ourCloseSig) = makeFinalTx(commitments1, ourShutdown.scriptPubKey, theirShutdown.scriptPubKey)
          val closingSigned = ClosingSigned(channelId, fee, ourCloseSig)
          them ! closingSigned
          goto(NEGOTIATING) using DATA_NEGOTIATING(channelId, channelParams, commitments1, ourShutdown, theirShutdown, closingSigned)
        case Success((commitments1, revocation)) =>
          them ! revocation
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event(msg: RevokeAndAck, d@DATA_SHUTDOWN(channelId, channelParams, commitments, ourShutdown, theirShutdown, _)) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked
      Try(Commitments.receiveRevocation(d.commitments, msg)) match {
        case Success(commitments1) if commitments1.hasNoPendingHtlcs =>
          val (_, fee, ourCloseSig) = makeFinalTx(commitments1, ourShutdown.scriptPubKey, theirShutdown.scriptPubKey)
          val closingSigned = ClosingSigned(channelId, fee, ourCloseSig)
          them ! closingSigned
          goto(NEGOTIATING) using DATA_NEGOTIATING(channelId, channelParams, commitments1, ourShutdown, theirShutdown, closingSigned)
        case Success(commitments1) =>
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleOurError(cause, d)
      }

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_SHUTDOWN) if tx.txid == d.commitments.remoteCommit.txid => handleTheirSpentCurrent(tx, d)

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_SHUTDOWN) => handleTheirSpentOther(tx, d)

    case Event(e: Error, d: DATA_SHUTDOWN) => handleTheirError(e, d)
  }

  when(NEGOTIATING) {
    case Event(ClosingSigned(_, theirCloseFee, theirSig), d: DATA_NEGOTIATING) if theirCloseFee == d.ourClosingSigned.feeSatoshis =>
      checkCloseSignature(theirSig, Satoshi(theirCloseFee), d) match {
        case Success(signedTx) =>
          log.info(s"finalTxId=${signedTx.txid}")
          blockchain ! Publish(signedTx)
          blockchain ! WatchConfirmed(self, signedTx.txid, 3, BITCOIN_CLOSE_DONE) // hardcoded mindepth
          goto(CLOSING) using DATA_CLOSING(d.commitments, ourSignature = Some(d.ourClosingSigned), mutualClosePublished = Some(signedTx))
        case Failure(cause) =>
          log.error(cause, "cannot verify their close signature")
          throw new RuntimeException("cannot verify their close signature", cause)
      }

    case Event(ClosingSigned(channelId, theirCloseFee, theirSig), d: DATA_NEGOTIATING) =>
      checkCloseSignature(theirSig, Satoshi(theirCloseFee), d) match {
        case Success(_) =>
          val closeFee = ((theirCloseFee + d.ourClosingSigned.feeSatoshis) / 4) * 2 match {
            case value if value == d.ourClosingSigned.feeSatoshis => value + 2
            case value => value
          }
          val (finalTx, fee, ourCloseSig) = makeFinalTx(d.commitments, d.ourShutdown.scriptPubKey, d.theirShutdown.scriptPubKey, Satoshi(closeFee))
          val closingSigned = ClosingSigned(channelId, fee, ourCloseSig)
          log.info(s"finalTxId=${finalTx.txid}")
          them ! closingSigned
          if (closeFee == theirCloseFee) {
            val signedTx = addSigs(d.commitments.localParams, d.commitments.remoteParams, d.commitments.anchorOutput.amount, finalTx, ourCloseSig, theirSig)
            blockchain ! Publish(signedTx)
            blockchain ! WatchConfirmed(self, signedTx.txid, 3, BITCOIN_CLOSE_DONE) // hardcoded mindepth
            goto(CLOSING) using DATA_CLOSING(d.commitments, ourSignature = Some(closingSigned), mutualClosePublished = Some(signedTx))
          } else {
            stay using d.copy(ourClosingSigned = closingSigned)
          }
        case Failure(cause) =>
          log.error(cause, "cannot verify their close signature")
          throw new RuntimeException("cannot verify their close signature", cause)
      }

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NEGOTIATING) if tx.txid == makeFinalTx(d.commitments, d.ourShutdown.scriptPubKey, d.theirShutdown.scriptPubKey, Satoshi(d.ourClosingSigned.feeSatoshis))._1.txid =>
      // happens when we agreed on a closeSig, but we don't know it yet: we receive the watcher notification before their ClosingSigned (which will match ours)
      stay()

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NEGOTIATING) if tx.txid == d.commitments.remoteCommit.txid => handleTheirSpentCurrent(tx, d)

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NEGOTIATING) => handleTheirSpentOther(tx, d)

    case Event(e: Error, d: DATA_NEGOTIATING) => handleTheirError(e, d)

  }

  when(CLOSING) {

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) if tx.txid == d.commitments.localCommit.publishableTx.txid =>
      // we just initiated a uniclose moments ago and are now receiving the blockchain notification, there is nothing to do
      stay()

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) if Some(tx.txid) == d.mutualClosePublished.map(_.txid) =>
      // we just published a mutual close tx, we are notified but it's alright
      stay()

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) if tx.txid == d.commitments.remoteCommit.txid =>
      // counterparty may attempt to spend its last commit tx at any time
      handleTheirSpentCurrent(tx, d)

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) =>
      // counterparty may attempt to spend a revoked commit tx at any time
      handleTheirSpentOther(tx, d)

    case Event(BITCOIN_CLOSE_DONE, d: DATA_CLOSING) if d.mutualClosePublished.isDefined => goto(CLOSED)

    case Event(BITCOIN_SPEND_OURS_DONE, d: DATA_CLOSING) if d.ourCommitPublished.isDefined => goto(CLOSED)

    case Event(BITCOIN_SPEND_THEIRS_DONE, d: DATA_CLOSING) if d.theirCommitPublished.isDefined => goto(CLOSED)

    case Event(BITCOIN_STEAL_DONE, d: DATA_CLOSING) if d.revokedPublished.size > 0 => goto(CLOSED)

    case Event(e: Error, d: DATA_CLOSING) => stay // nothing to do, there is already a spending tx published
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

    case Event(BITCOIN_FUNDING_LOST, _) => goto(ERR_ANCHOR_LOST)

    case Event(CMD_GETSTATE, _) =>
      sender ! stateName
      stay

    case Event(CMD_GETSTATEDATA, _) =>
      sender ! stateData
      stay

    case Event(CMD_GETINFO, _) =>
      sender ! RES_GETINFO(theirNodeId, stateData match {
        // TODO
        case c: DATA_WAIT_FOR_OPEN_CHANNEL => 0L
        case c: DATA_WAIT_FOR_ACCEPT_CHANNEL => c.temporaryChannelId
        case c: DATA_WAIT_FOR_FUNDING_CREATED => c.temporaryChannelId
        case c: DATA_WAIT_FOR_FUNDING_LOCKED => c.temporaryChannelId
        case c: DATA_NORMAL => c.channelId
        case c: DATA_SHUTDOWN => c.channelId
        case c: DATA_NEGOTIATING => c.channelId
        case c: DATA_CLOSING => 0L
        case _ => 0L
      }, stateName, stateData)
      stay

    // because channels send CMD to each others when relaying payments
    case Event("ok", _) => stay
  }

  onTransition {
    case previousState -> currentState => context.system.eventStream.publish(ChannelChangedState(self, theirNodeId, previousState, currentState, stateData))
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

  def propagateUpstream(add: UpdateAddHtlc, anchorId: BinaryData) = {
    /*val r = route.parseFrom(add.route.info.toByteArray)
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
              upstream ! CMD_ADD_HTLC(amountMsat, add.rHash, add.expiry, upstream_route, Some(Origin(anchorId, add.id)))
              upstream ! CMD_SIGN
            case Failure(t: Throwable) =>
              // TODO : send "fail route error"
              log.warning(s"couldn't resolve upstream node, htlc #${add.id} will timeout", t)
          }
      case route_step(amount, Next.End(true)) +: rest =>
        log.info(s"we are the final recipient of htlc #${add.id}")
        context.system.eventStream.publish(PaymentReceived(self, add.rHash))
        paymentHandler ! add
    }*/
  }

  def propagateDownstream(htlc: UpdateAddHtlc, fail_or_fulfill: Either[UpdateFailHtlc, UpdateFulfillHtlc], origin_opt: Option[Origin]) = {
    (origin_opt, fail_or_fulfill) match {
      case (Some(origin), Left(fail)) =>
        val downstream = context.system.actorSelection(Register.actorPathToChannelId(origin.channelId))
        downstream ! CMD_SIGN
        downstream ! CMD_FAIL_HTLC(origin.htlc_id, fail.reason.toStringUtf8)
        downstream ! CMD_SIGN
      case (Some(origin), Right(fulfill)) =>
        val downstream = context.system.actorSelection(Register.actorPathToChannelId(origin.channelId))
        downstream ! CMD_SIGN
        downstream ! CMD_FULFILL_HTLC(origin.htlc_id, fulfill.paymentPreimage)
        downstream ! CMD_SIGN
      case (None, Left(fail)) =>
        log.info(s"we were the origin payer for htlc #${htlc.id}")
        context.system.eventStream.publish(PaymentFailed(self, htlc.paymentHash, fail.reason.toStringUtf8))
      case (None, Right(fulfill)) =>
        log.info(s"we were the origin payer for htlc #${htlc.id}")
        context.system.eventStream.publish(PaymentSent(self, htlc.paymentHash))
    }
  }

  def handleCommandSuccess(sender: ActorRef, msg: LightningMessage, newData: Data) = {
    them ! msg
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
    them ! Error(0, cause.getMessage)
    spendOurCurrent(d)
  }

  def handleTheirError(e: Error, d: HasCommitments) = {
    log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
    spendOurCurrent(d)
  }

  def spendOurCurrent(d: HasCommitments) = {
    val tx = d.commitments.localCommit.publishableTx

    blockchain ! Publish(tx)
    blockchain ! WatchConfirmed(self, tx.txid, 3, BITCOIN_SPEND_OURS_DONE) // TODO hardcoded mindepth

    val txs1 = claimReceivedHtlcs(tx, Commitments.makeLocalTxTemplate(d.commitments), d.commitments)
    val txs2 = claimSentHtlcs(tx, Commitments.makeLocalTxTemplate(d.commitments), d.commitments)
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
    assert(tx.txid == d.commitments.remoteCommit.txid)

    blockchain ! WatchConfirmed(self, tx.txid, 3, BITCOIN_SPEND_THEIRS_DONE) // TODO hardcoded mindepth

    val txs1 = claimReceivedHtlcs(tx, Commitments.makeRemoteTxTemplate(d.commitments), d.commitments)
    val txs2 = claimSentHtlcs(tx, Commitments.makeRemoteTxTemplate(d.commitments), d.commitments)
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
        them ! Error(0, "Anchor has been spent".getBytes)
        blockchain ! Publish(spendingTx)
        blockchain ! WatchConfirmed(self, spendingTx.txid, 3, BITCOIN_STEAL_DONE)
        // TODO hardcoded mindepth
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
    // TODO! channel id
    them ! Error(0, "Anchor has been spent".getBytes)
    blockchain ! Publish(d.commitments.localCommit.publishableTx)
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




