package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM, LoggingFSM, Props}
import fr.acinq.bitcoin.Crypto.Point
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.Helpers.{Closing, Funding}
import fr.acinq.eclair.crypto.{Generators, ShaChain}
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire._

import scala.compat.Platform
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}


/**
  * Created by PM on 20/08/2015.
  */

object Channel {
  def props(them: ActorRef, blockchain: ActorRef, paymentHandler: ActorRef, localParams: LocalParams, theirNodeId: String, autoSignInterval: Option[FiniteDuration] = None) = Props(new Channel(them, blockchain, paymentHandler, localParams, theirNodeId, autoSignInterval))
}

class Channel(val them: ActorRef, val blockchain: ActorRef, paymentHandler: ActorRef, val localParams: LocalParams, theirNodeId: String, autoSignInterval: Option[FiniteDuration] = None)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends LoggingFSM[State, Data] {

  context.system.eventStream.publish(ChannelCreated(self, localParams, theirNodeId))

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

  startWith(WAIT_FOR_INIT_INTERNAL, Nothing)

  when(WAIT_FOR_INIT_INTERNAL)(handleExceptions {
    case Event(INPUT_INIT_FUNDER(fundingSatoshis, pushMsat), Nothing) if localParams.isFunder =>
      val temporaryChannelId = Platform.currentTime
      val firstPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, 0)
      them ! OpenChannel(temporaryChannelId = temporaryChannelId,
        fundingSatoshis = fundingSatoshis,
        pushMsat = pushMsat,
        dustLimitSatoshis = localParams.dustLimitSatoshis,
        maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
        channelReserveSatoshis = localParams.channelReserveSatoshis,
        htlcMinimumMsat = localParams.htlcMinimumMsat,
        feeratePerKw = localParams.feeratePerKw,
        toSelfDelay = localParams.toSelfDelay,
        maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
        fundingPubkey = localParams.fundingPrivKey.publicKey,
        revocationBasepoint = localParams.revocationSecret.toPoint,
        paymentBasepoint = localParams.paymentKey.toPoint,
        delayedPaymentBasepoint = localParams.delayedPaymentKey.toPoint,
        firstPerCommitmentPoint = firstPerCommitmentPoint)
      goto(WAIT_FOR_ACCEPT_CHANNEL) using DATA_WAIT_FOR_ACCEPT_CHANNEL(temporaryChannelId, localParams, fundingSatoshis = fundingSatoshis, pushMsat = pushMsat, autoSignInterval = autoSignInterval)

    case Event(INPUT_INIT_FUNDEE(), Nothing) if !localParams.isFunder =>
      goto(WAIT_FOR_OPEN_CHANNEL) using DATA_WAIT_FOR_OPEN_CHANNEL(localParams, autoSignInterval = autoSignInterval)
  })


  when(WAIT_FOR_OPEN_CHANNEL)(handleExceptions {
    case Event(open: OpenChannel, DATA_WAIT_FOR_OPEN_CHANNEL(localParams, autoSignInterval)) =>
      // TODO: here we should check if remote parameters suit us
      // TODO: maybe also check uniqueness of temporary channel id
      val minimumDepth = Globals.default_mindepth_blocks
      val firstPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, 0)
      them ! AcceptChannel(temporaryChannelId = Platform.currentTime,
        dustLimitSatoshis = localParams.dustLimitSatoshis,
        maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
        channelReserveSatoshis = localParams.channelReserveSatoshis,
        minimumDepth = minimumDepth,
        htlcMinimumMsat = localParams.htlcMinimumMsat,
        toSelfDelay = localParams.toSelfDelay,
        maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
        fundingPubkey = localParams.fundingPrivKey.publicKey,
        revocationBasepoint = localParams.revocationSecret.toPoint,
        paymentBasepoint = localParams.paymentKey.toPoint,
        delayedPaymentBasepoint = localParams.delayedPaymentKey.toPoint,
        firstPerCommitmentPoint = firstPerCommitmentPoint)
      val remoteParams = RemoteParams(
        dustLimitSatoshis = open.dustLimitSatoshis,
        maxHtlcValueInFlightMsat = open.maxHtlcValueInFlightMsat,
        channelReserveSatoshis = open.channelReserveSatoshis,
        htlcMinimumMsat = open.htlcMinimumMsat,
        feeratePerKw = open.feeratePerKw,
        toSelfDelay = open.toSelfDelay,
        maxAcceptedHtlcs = open.maxAcceptedHtlcs,
        fundingPubKey = open.fundingPubkey,
        revocationBasepoint = open.revocationBasepoint,
        paymentBasepoint = open.paymentBasepoint,
        delayedPaymentBasepoint = open.delayedPaymentBasepoint)
      log.debug(s"remote params: $remoteParams")
      val params = ChannelParams(
        localParams = localParams.copy(feeratePerKw = open.feeratePerKw), // funder gets to choose the first feerate
        remoteParams = remoteParams,
        fundingSatoshis = open.fundingSatoshis,
        minimumDepth = minimumDepth,
        autoSignInterval = autoSignInterval)
      goto(WAIT_FOR_FUNDING_CREATED) using DATA_WAIT_FOR_FUNDING_CREATED(open.temporaryChannelId, params, open.pushMsat, open.firstPerCommitmentPoint)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(WAIT_FOR_ACCEPT_CHANNEL)(handleExceptions {
    case Event(accept: AcceptChannel, DATA_WAIT_FOR_ACCEPT_CHANNEL(temporaryChannelId, localParams, fundingSatoshis, pushMsat, autoSignInterval)) =>
      // TODO: here we should check if remote parameters suit us
      // TODO: check equality of temporaryChannelId? or should be done upstream
      val remoteParams = RemoteParams(
        dustLimitSatoshis = accept.dustLimitSatoshis,
        maxHtlcValueInFlightMsat = accept.maxHtlcValueInFlightMsat,
        channelReserveSatoshis = accept.channelReserveSatoshis,
        htlcMinimumMsat = accept.htlcMinimumMsat,
        feeratePerKw = localParams.feeratePerKw, // funder gets to choose the first feerate
        toSelfDelay = accept.toSelfDelay,
        maxAcceptedHtlcs = accept.maxAcceptedHtlcs,
        fundingPubKey = accept.fundingPubkey,
        revocationBasepoint = accept.revocationBasepoint,
        paymentBasepoint = accept.paymentBasepoint,
        delayedPaymentBasepoint = accept.delayedPaymentBasepoint
      )
      log.debug(s"remote params: $remoteParams")
      val params = ChannelParams(
        localParams = localParams,
        remoteParams = remoteParams,
        fundingSatoshis = fundingSatoshis,
        minimumDepth = accept.minimumDepth,
        autoSignInterval = autoSignInterval)
      val localFundingPubkey = params.localParams.fundingPrivKey.publicKey
      blockchain ! MakeFundingTx(localFundingPubkey, remoteParams.fundingPubKey, Satoshi(params.fundingSatoshis))
      goto(WAIT_FOR_FUNDING_CREATED_INTERNAL) using DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, params, pushMsat, accept.firstPerCommitmentPoint)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_CREATED_INTERNAL)(handleExceptions {
    case Event((fundingTx: Transaction, fundingTxOutputIndex: Int), DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, params, pushMsat, remoteFirstPerCommitmentPoint)) =>
      // our wallet provided us with a funding tx
      log.info(s"funding tx txid=${fundingTx.txid}")

      // let's create the first commitment tx that spends the yet uncommitted funding tx
      val (localSpec, localCommitTx, remoteSpec, remoteCommitTx) = Funding.makeFirstCommitTxs(params, pushMsat, fundingTx.hash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint)

      val localSigOfRemoteTx = Transactions.sign(remoteCommitTx, localParams.fundingPrivKey) // signature of their initial commitment tx that pays them pushMsat
      them ! FundingCreated(
        temporaryChannelId = temporaryChannelId,
        txid = fundingTx.hash,
        outputIndex = fundingTxOutputIndex,
        signature = localSigOfRemoteTx
      )
      goto(WAIT_FOR_FUNDING_SIGNED) using DATA_WAIT_FOR_FUNDING_SIGNED(temporaryChannelId, params, fundingTx, localSpec, localCommitTx, RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint))

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_CREATED)(handleExceptions {
    case Event(FundingCreated(_, fundingTxHash, fundingTxOutputIndex, remoteSig), DATA_WAIT_FOR_FUNDING_CREATED(temporaryChannelId, params, pushMsat, remoteFirstPerCommitmentPoint)) =>
      // they fund the channel with their funding tx, so the money is theirs (but we are paid pushMsat)
      val (localSpec, localCommitTx, remoteSpec, remoteCommitTx) = Funding.makeFirstCommitTxs(params, pushMsat, fundingTxHash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint)

      // check remote signature validity
      val localSigOfLocalTx = Transactions.sign(localCommitTx, localParams.fundingPrivKey)
      val signedLocalCommitTx = Transactions.addSigs(localCommitTx, params.localParams.fundingPrivKey.publicKey, params.remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
      Transactions.checkSpendable(signedLocalCommitTx) match {
        case Failure(cause) =>
          log.error(cause, "their FundingCreated message contains an invalid signature")
          them ! Error(temporaryChannelId, cause.getMessage.getBytes)
          // we haven't anything at stake yet, we can just stop
          goto(CLOSED)
        case Success(_) =>
          log.info(s"signing remote tx: $remoteCommitTx")
          val localSigOfRemoteTx = Transactions.sign(remoteCommitTx, localParams.fundingPrivKey)
          them ! FundingSigned(
            temporaryChannelId = temporaryChannelId,
            signature = localSigOfRemoteTx
          )

          // watch the funding tx transaction
          val commitInput = localCommitTx.input
          blockchain ! WatchSpent(self, commitInput.outPoint.txid, commitInput.outPoint.index.toInt, minDepth = 0, BITCOIN_FUNDING_SPENT) // TODO: should we wait for an acknowledgment from the watcher?
          blockchain ! WatchConfirmed(self, commitInput.outPoint.txid, params.minimumDepth.toInt, BITCOIN_FUNDING_DEPTHOK)

          val commitments = Commitments(params.localParams, params.remoteParams,
            LocalCommit(0, localSpec, PublishableTxs(signedLocalCommitTx, Nil)), RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint),
            LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil),
            localCurrentHtlcId = 0L,
            remoteNextCommitInfo = Right(null), // TODO: we will receive their next per-commitment point in the next message, so we temporarily put an empty byte array
            commitInput, ShaChain.init, channelId = 0)
          context.system.eventStream.publish(ChannelIdAssigned(self, commitments.anchorId, Satoshi(params.fundingSatoshis)))
          goto(WAIT_FOR_FUNDING_LOCKED_INTERNAL) using DATA_WAIT_FOR_FUNDING_LOCKED_INTERNAL(temporaryChannelId, params, commitments, None)
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_SIGNED)(handleExceptions {
    case Event(FundingSigned(_, remoteSig), DATA_WAIT_FOR_FUNDING_SIGNED(temporaryChannelId, params, fundingTx, localSpec, localCommitTx, remoteCommit)) =>
      // we make sure that their sig checks out and that our first commit tx is spendable
      val localSigOfLocalTx = Transactions.sign(localCommitTx, localParams.fundingPrivKey)
      val signedLocalCommitTx = Transactions.addSigs(localCommitTx, params.localParams.fundingPrivKey.publicKey, params.remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
      Transactions.checkSpendable(signedLocalCommitTx) match {
        case Failure(cause) =>
          log.error(cause, "their FundingSigned message contains an invalid signature")
          them ! Error(temporaryChannelId, cause.getMessage.getBytes)
          // we haven't published anything yet, we can just stop
          goto(CLOSED)
        case Success(_) =>
          val commitInput = localCommitTx.input
          blockchain ! WatchSpent(self, commitInput.outPoint.txid, commitInput.outPoint.index.toInt, minDepth = 0, BITCOIN_FUNDING_SPENT) // TODO: should we wait for an acknowledgment from the watcher?
          blockchain ! WatchConfirmed(self, commitInput.outPoint.txid, params.minimumDepth, BITCOIN_FUNDING_DEPTHOK)
          blockchain ! PublishAsap(fundingTx)
          val commitments = Commitments(params.localParams, params.remoteParams,
            LocalCommit(0, localSpec, PublishableTxs(signedLocalCommitTx, Nil)), remoteCommit,
            LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil),
            localCurrentHtlcId = 0L,
            remoteNextCommitInfo = Right(null), // TODO: we will receive their next per-commitment point in the next message, so we temporarily put an empty byte array
            commitInput, ShaChain.init, channelId = 0)
          context.system.eventStream.publish(ChannelIdAssigned(self, commitments.anchorId, Satoshi(params.fundingSatoshis)))
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
          goto(WAIT_FOR_FUNDING_LOCKED_INTERNAL) using DATA_WAIT_FOR_FUNDING_LOCKED_INTERNAL(temporaryChannelId, params, commitments, None)
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_LOCKED_INTERNAL)(handleExceptions {
    case Event(msg: FundingLocked, d: DATA_WAIT_FOR_FUNDING_LOCKED_INTERNAL) =>
      log.info(s"received their FundingLocked, deferring message")
      stay using d.copy(deferred = Some(msg))

    case Event(BITCOIN_FUNDING_DEPTHOK, d@DATA_WAIT_FOR_FUNDING_LOCKED_INTERNAL(temporaryChannelId, params, commitments, deferred)) =>
      // TODO: set channelId
      val channelId = 0L
      blockchain ! WatchLost(self, commitments.anchorId, params.minimumDepth, BITCOIN_FUNDING_LOST)
      val nextPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, 1)
      them ! FundingLocked(channelId, 0L, None, None, nextPerCommitmentPoint) // TODO: routing announcements disabled
      deferred.map(self ! _)
      // TODO: htlcIdx should not be 0 when resuming connection
      goto(WAIT_FOR_FUNDING_LOCKED) using DATA_NORMAL(channelId, params, commitments.copy(channelId = channelId), None, Map())

    case Event(BITCOIN_FUNDING_TIMEOUT, _) =>
      them ! Error(0, "Funding tx timed out".getBytes)
      goto(CLOSED)

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_WAIT_FOR_FUNDING_LOCKED_INTERNAL) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event((BITCOIN_FUNDING_SPENT, _), d: DATA_WAIT_FOR_FUNDING_LOCKED_INTERNAL) => handleInformationLeak(d)

    case Event(cmd: CMD_CLOSE, d: DATA_WAIT_FOR_FUNDING_LOCKED_INTERNAL) =>
      blockchain ! PublishAsap(d.commitments.localCommit.publishableTxs.commitTx.tx)
      blockchain ! WatchConfirmed(self, d.commitments.localCommit.publishableTxs.commitTx.tx.txid, d.params.minimumDepth, BITCOIN_CLOSE_DONE)
      // there can't be htlcs at this stage
      val localCommitPublished = LocalCommitPublished(d.commitments.localCommit.publishableTxs.commitTx.tx, None, Nil, Nil, Nil)
      goto(CLOSING) using DATA_CLOSING(d.commitments, localCommitPublished = Some(localCommitPublished))

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_LOCKED_INTERNAL) =>
      log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
      blockchain ! PublishAsap(d.commitments.localCommit.publishableTxs.commitTx.tx)
      blockchain ! WatchConfirmed(self, d.commitments.localCommit.publishableTxs.commitTx.tx.txid, d.params.minimumDepth, BITCOIN_CLOSE_DONE)
      // there can't be htlcs at this stage
      // TODO: LocalCommitPublished.claimDelayedOutputTx should be defined
      val localCommitPublished = LocalCommitPublished(d.commitments.localCommit.publishableTxs.commitTx.tx, None, Nil, Nil, Nil)
      goto(CLOSING) using DATA_CLOSING(d.commitments, localCommitPublished = Some(localCommitPublished))
  })

  when(WAIT_FOR_FUNDING_LOCKED)(handleExceptions {
    case Event(FundingLocked(temporaryChannelId, channelId, _, _, nextPerCommitmentPoint), d: DATA_NORMAL) =>
      // TODO: check channelId matches ours
      Register.create_alias(theirNodeId, d.commitments.anchorId)
      goto(NORMAL) using d.copy(commitments = d.commitments.copy(remoteNextCommitInfo = Right(nextPerCommitmentPoint)))

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NORMAL) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event((BITCOIN_FUNDING_SPENT, _), d: DATA_NORMAL) => handleInformationLeak(d)

    case Event(cmd: CMD_CLOSE, d: DATA_NORMAL) =>
      blockchain ! PublishAsap(d.commitments.localCommit.publishableTxs.commitTx.tx)
      blockchain ! WatchConfirmed(self, d.commitments.localCommit.publishableTxs.commitTx.tx.txid, d.params.minimumDepth, BITCOIN_CLOSE_DONE)
      // there can't be htlcs at this stage
      // TODO: LocalCommitPublished.claimDelayedOutputTx should be defined
      val localCommitPublished = LocalCommitPublished(d.commitments.localCommit.publishableTxs.commitTx.tx, None, Nil, Nil, Nil)
      goto(CLOSING) using DATA_CLOSING(d.commitments, localCommitPublished = Some(localCommitPublished))

    case Event(e: Error, d: DATA_NORMAL) => handleRemoteError(e, d)
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

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) if d.localShutdown.isDefined =>
      handleCommandError(sender, new RuntimeException("cannot send new htlcs, closing in progress"))

    case Event(c@CMD_ADD_HTLC(amountMsat, rHash, expiry, route, origin, id_opt, do_commit), d@DATA_NORMAL(channelId, params, commitments, _, downstreams)) =>
      Try(Commitments.sendAdd(commitments, c)) match {
        case Success((commitments1, add)) =>
          if (do_commit) self ! CMD_SIGN
          handleCommandSuccess(sender, add, d.copy(commitments = commitments1, downstreams = downstreams + (add.id -> origin)))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(add: UpdateAddHtlc, d@DATA_NORMAL(_, params, commitments, _, _)) =>
      Try(Commitments.receiveAdd(commitments, add)) match {
        case Success(commitments1) =>
          import scala.concurrent.ExecutionContext.Implicits.global
          params.autoSignInterval.map(interval => context.system.scheduler.scheduleOnce(interval, self, CMD_SIGN))
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(c@CMD_FULFILL_HTLC(id, r, do_commit), d: DATA_NORMAL) =>
      Try(Commitments.sendFulfill(d.commitments, c)) match {
        case Success((commitments1, fulfill)) =>
          if (do_commit) self ! CMD_SIGN
          handleCommandSuccess(sender, fulfill, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(fulfill@UpdateFulfillHtlc(_, id, r), d@DATA_NORMAL(channelId, params, commitments, _, downstreams)) =>
      Try(Commitments.receiveFulfill(d.commitments, fulfill)) match {
        case Success((commitments1, htlc)) =>
          propagateDownstream(htlc, Right(fulfill), downstreams(id))
          import scala.concurrent.ExecutionContext.Implicits.global
          params.autoSignInterval.map(interval => context.system.scheduler.scheduleOnce(interval, self, CMD_SIGN))
          stay using d.copy(commitments = commitments1, downstreams = downstreams - id)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(c@CMD_FAIL_HTLC(id, reason, do_commit), d: DATA_NORMAL) =>
      Try(Commitments.sendFail(d.commitments, c)) match {
        case Success((commitments1, fail)) =>
          if (do_commit) self ! CMD_SIGN
          handleCommandSuccess(sender, fail, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(fail@UpdateFailHtlc(_, id, reason), d@DATA_NORMAL(channelId, params, commitments, _, downstreams)) =>
      Try(Commitments.receiveFail(d.commitments, fail)) match {
        case Success((commitments1, htlc)) =>
          propagateDownstream(htlc, Left(fail), downstreams(id))
          import scala.concurrent.ExecutionContext.Implicits.global
          params.autoSignInterval.map(interval => context.system.scheduler.scheduleOnce(interval, self, CMD_SIGN))
          stay using d.copy(commitments = commitments1, downstreams = downstreams - id)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(CMD_SIGN, d: DATA_NORMAL) if d.commitments.remoteNextCommitInfo.isLeft =>
      // TODO: this is a temporary fix
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
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(msg: RevokeAndAck, d: DATA_NORMAL) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked
      Try(Commitments.receiveRevocation(d.commitments, msg)) match {
        case Success(commitments1) =>
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(CMD_CLOSE(ourScriptPubKey_opt), d: DATA_NORMAL) if d.localShutdown.isDefined =>
      handleCommandError(sender, new RuntimeException("closing already in progress"))

    case Event(c@CMD_CLOSE(ourScriptPubKey_opt), d: DATA_NORMAL) if Commitments.localHasChanges(d.commitments) =>
      // TODO: simplistic behavior, we could maybe sign+close
      handleCommandError(sender, new RuntimeException("cannot close when there are pending changes"))

    case Event(CMD_CLOSE(ourScriptPubKey_opt), d: DATA_NORMAL) =>
      ourScriptPubKey_opt.getOrElse(Script.write(d.params.localParams.defaultFinalScriptPubKey)) match {
        case finalScriptPubKey if Closing.isValidFinalScriptPubkey(finalScriptPubKey) =>
          val localShutdown = Shutdown(d.channelId, finalScriptPubKey)
          handleCommandSuccess(sender, localShutdown, d.copy(localShutdown = Some(localShutdown)))
        case _ => handleCommandError(sender, new RuntimeException("invalid final script"))
      }

    case Event(remoteShutdown@Shutdown(_, remoteScriptPubKey), d@DATA_NORMAL(channelId, params, commitments, ourShutdownOpt, downstreams)) if commitments.remoteChanges.proposed.size > 0 =>
      handleLocalError(new RuntimeException("it is illegal to send a shutdown while having unsigned changes"), d)

    case Event(remoteShutdown@Shutdown(_, remoteScriptPubKey), d@DATA_NORMAL(channelId, params, commitments, ourShutdownOpt, downstreams)) =>
      Try(ourShutdownOpt.map(s => (s, commitments)).getOrElse {
        require(Closing.isValidFinalScriptPubkey(remoteScriptPubKey), "invalid final script")
        // first if we have pending changes, we need to commit them
        val commitments2 = if (Commitments.localHasChanges(commitments)) {
          val (commitments1, commit) = Commitments.sendCommit(d.commitments)
          them ! commit
          commitments1
        } else commitments
        val shutdown = Shutdown(channelId, Script.write(params.localParams.defaultFinalScriptPubKey))
        them ! shutdown
        (shutdown, commitments2)
      }) match {
        case Success((localShutdown, commitments3))
          if (commitments3.remoteNextCommitInfo.isRight && commitments3.localCommit.spec.htlcs.size == 0 && commitments3.localCommit.spec.htlcs.size == 0)
            || (commitments3.remoteNextCommitInfo.isLeft && commitments3.localCommit.spec.htlcs.size == 0 && commitments3.remoteNextCommitInfo.left.get.spec.htlcs.size == 0) =>
          val closingSigned = Closing.makeFirstClosingTx(params, commitments3, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey)
          them ! closingSigned
          goto(NEGOTIATING) using DATA_NEGOTIATING(channelId, params, commitments3, localShutdown, remoteShutdown, closingSigned)
        case Success((localShutdown, commitments3)) =>
          goto(SHUTDOWN) using DATA_SHUTDOWN(channelId, params, commitments3, localShutdown, remoteShutdown, downstreams)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NORMAL) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NORMAL) => handleRemoteSpentOther(tx, d)

    case Event(e: Error, d: DATA_NORMAL) => handleRemoteError(e, d)

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
      Try(Commitments.sendFulfill(d.commitments, c)) match {
        case Success((commitments1, fulfill)) => handleCommandSuccess(sender, fulfill, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(fulfill@UpdateFulfillHtlc(_, id, r), d: DATA_SHUTDOWN) =>
      Try(Commitments.receiveFulfill(d.commitments, fulfill)) match {
        case Success((commitments1, htlc)) =>
          propagateDownstream(htlc, Right(fulfill), d.downstreams(id))
          stay using d.copy(commitments = commitments1, downstreams = d.downstreams - id)
        case Failure(cause) => handleLocalError(cause, d)
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
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(CMD_SIGN, d: DATA_SHUTDOWN) if d.commitments.remoteNextCommitInfo.isLeft =>
      // TODO: this is a temporary fix
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

    case Event(msg@CommitSig(_, theirSig, theirHtlcSigs), d@DATA_SHUTDOWN(channelId, params, commitments, localShutdown, remoteShutdown, _)) =>
      // TODO: we might have to propagate htlcs upstream depending on the outcome of https://github.com/ElementsProject/lightning/issues/29
      Try(Commitments.receiveCommit(d.commitments, msg)) match {
        case Success((commitments1, revocation)) if commitments1.hasNoPendingHtlcs =>
          them ! revocation
          val closingSigned = Closing.makeFirstClosingTx(params, commitments1, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey)
          them ! closingSigned
          goto(NEGOTIATING) using DATA_NEGOTIATING(channelId, params, commitments1, localShutdown, remoteShutdown, closingSigned)
        case Success((commitments1, revocation)) =>
          them ! revocation
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(msg: RevokeAndAck, d@DATA_SHUTDOWN(channelId, params, commitments, localShutdown, remoteShutdown, _)) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked
      Try(Commitments.receiveRevocation(d.commitments, msg)) match {
        case Success(commitments1) if commitments1.hasNoPendingHtlcs =>
          val closingSigned = Closing.makeFirstClosingTx(params, commitments, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey)
          them ! closingSigned
          goto(NEGOTIATING) using DATA_NEGOTIATING(channelId, params, commitments1, localShutdown, remoteShutdown, closingSigned)
        case Success(commitments1) =>
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_SHUTDOWN) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_SHUTDOWN) => handleRemoteSpentOther(tx, d)

    case Event(e: Error, d: DATA_SHUTDOWN) => handleRemoteError(e, d)
  }

  when(NEGOTIATING) {
    case Event(ClosingSigned(_, remoteClosingFee, remoteSig), d: DATA_NEGOTIATING) if remoteClosingFee == d.localClosingSigned.feeSatoshis =>
      Closing.checkClosingSignature(d.params, d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, Satoshi(remoteClosingFee), remoteSig) match {
        case Success(signedClosingTx) =>
          publishMutualClosing(signedClosingTx)
          goto(CLOSING) using DATA_CLOSING(d.commitments, ourSignature = Some(d.localClosingSigned), mutualClosePublished = Some(signedClosingTx))
        case Failure(cause) =>
          log.error(cause, "cannot verify their close signature")
          throw new RuntimeException("cannot verify their close signature", cause)
      }

    case Event(ClosingSigned(_, remoteClosingFee, remoteSig), d: DATA_NEGOTIATING) =>
      Closing.checkClosingSignature(d.params, d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, Satoshi(remoteClosingFee), remoteSig) match {
        case Success(signedClosingTx) =>
          val nextClosingFee = Closing.nextClosingFee(Satoshi(d.localClosingSigned.feeSatoshis), Satoshi(remoteClosingFee))
          val (_, closingSigned) = Closing.makeClosingTx(d.params, d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nextClosingFee)
          them ! closingSigned
          if (nextClosingFee == Satoshi(remoteClosingFee)) {
            publishMutualClosing(signedClosingTx)
            goto(CLOSING) using DATA_CLOSING(d.commitments, ourSignature = Some(closingSigned), mutualClosePublished = Some(signedClosingTx))
          } else {
            stay using d.copy(localClosingSigned = closingSigned)
          }
        case Failure(cause) =>
          log.error(cause, "cannot verify their close signature")
          throw new RuntimeException("cannot verify their close signature", cause)
      }

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NEGOTIATING) if tx.txid == Closing.makeClosingTx(d.params, d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, Satoshi(d.localClosingSigned.feeSatoshis))._1.tx.txid =>
      // happens when we agreed on a closeSig, but we don't know it yet: we receive the watcher notification before their ClosingSigned (which will match ours)
      stay()

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NEGOTIATING) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NEGOTIATING) => handleRemoteSpentOther(tx, d)

    case Event(e: Error, d: DATA_NEGOTIATING) => handleRemoteError(e, d)

  }

  when(CLOSING) {

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) if tx.txid == d.commitments.localCommit.publishableTxs.commitTx.tx.txid =>
      // we just initiated a uniclose moments ago and are now receiving the blockchain notification, there is nothing to do
      stay()

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) if Some(tx.txid) == d.mutualClosePublished.map(_.txid) =>
      // we just published a mutual close tx, we are notified but it's alright
      stay()

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) if tx.txid == d.commitments.remoteCommit.txid =>
      // counterparty may attempt to spend its last commit tx at any time
      handleRemoteSpentCurrent(tx, d)

    case Event((BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) =>
      // counterparty may attempt to spend a revoked commit tx at any time
      handleRemoteSpentOther(tx, d)

    case Event(BITCOIN_CLOSE_DONE, d: DATA_CLOSING) if d.mutualClosePublished.isDefined => goto(CLOSED)

    case Event(BITCOIN_SPEND_OURS_DONE, d: DATA_CLOSING) if d.localCommitPublished.isDefined => goto(CLOSED)

    case Event(BITCOIN_SPEND_THEIRS_DONE, d: DATA_CLOSING) if d.remoteCommitPublished.isDefined => goto(CLOSED)

    case Event(BITCOIN_PUNISHMENT_DONE, d: DATA_CLOSING) if d.revokedCommitPublished.size > 0 => goto(CLOSED)

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

    case Event(BITCOIN_FUNDING_LOST, _) => goto(ERR_FUNDING_LOST)

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
        case c: DATA_WAIT_FOR_FUNDING_LOCKED_INTERNAL => c.temporaryChannelId
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
    // TODO: relaying of payments is disabled
    /*val r = route.parseFrom(add.onionRoutingPacket)
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
              // TODO: we should decrement expiry !!
              upstream ! CMD_ADD_HTLC(amountMsat, add.paymentHash, add.expiry, upstream_route, Some(Origin(anchorId, add.id)))
              upstream ! CMD_SIGN
            case Failure(t: Throwable) =>
              // TODO: send "fail route error"
              log.warning(s"couldn't resolve upstream node, htlc #${add.id} will timeout", t)
          }
      case route_step(amount, Next.End(true)) +: rest =>*/
    log.info(s"we are the final recipient of htlc #${add.id}")
    context.system.eventStream.publish(PaymentReceived(self, add.paymentHash))
    paymentHandler ! add
    //}
  }

  def propagateDownstream(htlc: UpdateAddHtlc, fail_or_fulfill: Either[UpdateFailHtlc, UpdateFulfillHtlc], origin_opt: Option[Origin]) = {
    (origin_opt, fail_or_fulfill) match {
      // TODO: relaying of payments is disabled
      /*case (Some(origin), Left(fail)) =>
        val downstream = context.system.actorSelection(Register.actorPathToChannelId(origin.channelId))
        downstream ! CMD_SIGN
        downstream ! CMD_FAIL_HTLC(origin.htlc_id, fail.reason.toStringUtf8)
        downstream ! CMD_SIGN
      case (Some(origin), Right(fulfill)) =>
        val downstream = context.system.actorSelection(Register.actorPathToChannelId(origin.channelId))
        downstream ! CMD_SIGN
        downstream ! CMD_FULFILL_HTLC(origin.htlc_id, fulfill.paymentPreimage)
        downstream ! CMD_SIGN*/
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

  def handleLocalError(cause: Throwable, d: HasCommitments) = {
    log.error(cause, "")
    them ! Error(0, cause.getMessage.getBytes)
    spendLocalCurrent(d)
  }

  def handleRemoteError(e: Error, d: HasCommitments) = {
    log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
    spendLocalCurrent(d)
  }

  def publishMutualClosing(mutualClosing: Transaction) = {
    log.info(s"closingTxId=${mutualClosing.txid}")
    blockchain ! PublishAsap(mutualClosing)
    // TODO: hardcoded mindepth
    blockchain ! WatchConfirmed(self, mutualClosing.txid, 3, BITCOIN_CLOSE_DONE)
  }

  def spendLocalCurrent(d: HasCommitments) = {
    val tx = d.commitments.localCommit.publishableTxs.commitTx.tx

    blockchain ! PublishAsap(tx)
    // TODO hardcoded mindepth + shouldn't we watch the claim tx instead?
    blockchain ! WatchConfirmed(self, tx.txid, 3, BITCOIN_SPEND_OURS_DONE)

    val localCommitPublished = Helpers.Closing.claimCurrentLocalCommitTxOutputs(d.commitments, tx)
    localCommitPublished.claimMainDelayedOutputTx.foreach(tx => blockchain ! PublishAsap(tx))
    localCommitPublished.htlcSuccessTxs.foreach(tx => blockchain ! PublishAsap(tx))
    localCommitPublished.htlcTimeoutTxs.foreach(tx => blockchain ! PublishAsap(tx))
    localCommitPublished.claimHtlcDelayedTx.foreach(tx => blockchain ! PublishAsap(tx))

    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(localCommitPublished = Some(localCommitPublished))
      case _ => DATA_CLOSING(d.commitments, localCommitPublished = Some(localCommitPublished))
    }

    goto(CLOSING) using nextData
  }

  def handleRemoteSpentCurrent(tx: Transaction, d: HasCommitments) = {
    log.warning(s"they published their current commit in txid=${tx.txid}")
    require(tx.txid == d.commitments.remoteCommit.txid, "txid mismatch")

    // TODO hardcoded mindepth + shouldn't we watch the claim tx instead?
    blockchain ! WatchConfirmed(self, tx.txid, 3, BITCOIN_SPEND_THEIRS_DONE)

    val remoteCommitPublished = Helpers.Closing.claimCurrentRemoteCommitTxOutputs(d.commitments, tx)
    remoteCommitPublished.claimMainOutputTx.foreach(tx => blockchain ! PublishAsap(tx))
    remoteCommitPublished.claimHtlcSuccessTxs.foreach(tx => blockchain ! PublishAsap(tx))
    remoteCommitPublished.claimHtlcTimeoutTxs.foreach(tx => blockchain ! PublishAsap(tx))

    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(remoteCommitPublished = Some(remoteCommitPublished))
      case _ => DATA_CLOSING(d.commitments, remoteCommitPublished = Some(remoteCommitPublished))
    }

    goto(CLOSING) using nextData
  }

  def handleRemoteSpentOther(tx: Transaction, d: HasCommitments) = {
    log.warning(s"funding tx spent in txid=${tx.txid}")

    Helpers.Closing.claimRevokedRemoteCommitTxOutputs(d.commitments, tx) match {
      case Some(revokedCommitPublished) =>
        log.warning(s"txid=${tx.txid} was a revoked commitment, publishing the punishment tx")
        them ! Error(0, "Funding tx has been spent".getBytes)

        // TODO hardcoded mindepth + shouldn't we watch the claim tx instead?
        blockchain ! WatchConfirmed(self, tx.txid, 3, BITCOIN_PUNISHMENT_DONE)

        revokedCommitPublished.claimMainOutputTx.foreach(tx => blockchain ! PublishAsap(tx))
        revokedCommitPublished.mainPunishmentTx.foreach(tx => blockchain ! PublishAsap(tx))
        revokedCommitPublished.claimHtlcTimeoutTxs.foreach(tx => blockchain ! PublishAsap(tx))
        revokedCommitPublished.htlcTimeoutTxs.foreach(tx => blockchain ! PublishAsap(tx))
        revokedCommitPublished.htlcPunishmentTxs.foreach(tx => blockchain ! PublishAsap(tx))

        val nextData = d match {
          case closing: DATA_CLOSING => closing.copy(revokedCommitPublished = closing.revokedCommitPublished :+ revokedCommitPublished)
          case _ => DATA_CLOSING(d.commitments, revokedCommitPublished = revokedCommitPublished :: Nil)
        }
        goto(CLOSING) using nextData
      case None =>
        // the published tx was neither their current commitment nor a revoked one
        log.error(s"couldn't identify txid=${tx.txid}, something very bad is going on!!!")
        goto(ERR_INFORMATION_LEAK)
    }
  }

  def handleInformationLeak(d: HasCommitments) = {
    // this is never supposed to happen !!
    log.error(s"our funding tx ${d.commitments.anchorId} was spent !!")
    // TODO! channel id
    them ! Error(0, "Anchor has been spent".getBytes)
    // TODO: not enough
    blockchain ! PublishAsap(d.commitments.localCommit.publishableTxs.commitTx.tx)
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
          case d: HasCommitments => handleLocalError(t, d)
          case _ =>
            log.error(t, "")
            goto(CLOSED)
        }
      }
  }

}




