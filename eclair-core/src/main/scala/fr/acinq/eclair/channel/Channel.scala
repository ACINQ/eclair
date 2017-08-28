package fr.acinq.eclair.channel

import akka.actor.{ActorRef, DiagnosticActorLogging, FSM, LoggingFSM, OneForOneStrategy, Props, Status, SupervisorStrategy}
import akka.event.Logging.MDC
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.Helpers.{Closing, Funding}
import fr.acinq.eclair.crypto.{Generators, ShaChain, Sphinx}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.{ChannelReestablish, _}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Left, Success, Try}


/**
  * Created by PM on 20/08/2015.
  */

object Channel {
  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, blockchain: ActorRef, router: ActorRef, relayer: ActorRef) = Props(new Channel(nodeParams, remoteNodeId, blockchain, router, relayer))
}

class Channel(val nodeParams: NodeParams, remoteNodeId: PublicKey, blockchain: ActorRef, router: ActorRef, relayer: ActorRef)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends LoggingFSM[State, Data] with FSMDiagnosticActorLogging[State, Data] {

  val forwarder = context.actorOf(Props(new Forwarder(nodeParams)), "forwarder")

  // see https://github.com/lightningnetwork/lightning-rfc/blob/master/07-routing-gossip.md#requirements
  val ANNOUNCEMENTS_MINCONF = 6

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
    case Event(initFunder@INPUT_INIT_FUNDER(temporaryChannelId, fundingSatoshis, pushMsat, initialFeeratePerKw, localParams, remote, remoteInit, channelFlags), Nothing) =>
      context.system.eventStream.publish(ChannelCreated(self, context.parent, remoteNodeId, true, temporaryChannelId))
      forwarder ! remote
      val firstPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, 0)
      val open = OpenChannel(nodeParams.chainHash,
        temporaryChannelId = temporaryChannelId,
        fundingSatoshis = fundingSatoshis,
        pushMsat = pushMsat,
        dustLimitSatoshis = localParams.dustLimitSatoshis,
        maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
        channelReserveSatoshis = localParams.channelReserveSatoshis,
        htlcMinimumMsat = localParams.htlcMinimumMsat,
        feeratePerKw = initialFeeratePerKw,
        toSelfDelay = localParams.toSelfDelay,
        maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
        fundingPubkey = localParams.fundingPrivKey.publicKey,
        revocationBasepoint = localParams.revocationBasepoint,
        paymentBasepoint = localParams.paymentBasepoint,
        delayedPaymentBasepoint = localParams.delayedPaymentBasepoint,
        firstPerCommitmentPoint = firstPerCommitmentPoint,
        channelFlags = channelFlags)
      goto(WAIT_FOR_ACCEPT_CHANNEL) using DATA_WAIT_FOR_ACCEPT_CHANNEL(initFunder, open) sending open

    case Event(inputFundee@INPUT_INIT_FUNDEE(_, localParams, remote, _), Nothing) if !localParams.isFunder =>
      forwarder ! remote
      goto(WAIT_FOR_OPEN_CHANNEL) using DATA_WAIT_FOR_OPEN_CHANNEL(inputFundee)

    case Event(INPUT_RESTORED(data), _) =>
      log.info(s"restoring channel $data")
      context.system.eventStream.publish(ChannelRestored(self, context.parent, remoteNodeId, data.commitments.localParams.isFunder, data.channelId, data))
      // TODO: should we wait for an acknowledgment from the watcher?
      blockchain ! WatchSpent(self, data.commitments.commitInput.outPoint.txid, data.commitments.commitInput.outPoint.index.toInt, BITCOIN_FUNDING_SPENT)
      blockchain ! WatchLost(self, data.commitments.commitInput.outPoint.txid, nodeParams.minDepthBlocks, BITCOIN_FUNDING_LOST)
      data match {
        //NB: order matters!
        case closing: DATA_CLOSING =>
          closing.mutualClosePublished.map(doPublish(_))
          closing.localCommitPublished.foreach(doPublish(_))
          closing.remoteCommitPublished.foreach(doPublish(_, BITCOIN_REMOTECOMMIT_DONE))
          closing.nextRemoteCommitPublished.foreach(doPublish(_, BITCOIN_NEXTREMOTECOMMIT_DONE))
          closing.revokedCommitPublished.foreach(doPublish(_))
          // no need to go OFFLINE, we can directly switch to CLOSING
          goto(CLOSING) using closing

        case d: HasCommitments =>
          d match {
            case DATA_NORMAL(_, Some(shortChannelId), _, _, _) =>
              context.system.eventStream.publish(ShortChannelIdAssigned(self, d.channelId, shortChannelId))
              val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, shortChannelId, nodeParams.expiryDeltaBlocks, nodeParams.htlcMinimumMsat, nodeParams.feeBaseMsat, nodeParams.feeProportionalMillionth)
              relayer ! channelUpdate
            case _ => ()
          }
          goto(OFFLINE) using d
      }
  })

  when(WAIT_FOR_OPEN_CHANNEL)(handleExceptions {
    case Event(open: OpenChannel, DATA_WAIT_FOR_OPEN_CHANNEL(INPUT_INIT_FUNDEE(_, localParams, _, remoteInit))) =>
      Try(Helpers.validateParamsFundee(nodeParams, open.channelReserveSatoshis, open.fundingSatoshis, open.chainHash)) match {
        case Failure(t) =>
          log.warning(t.getMessage)
          val error = Error(open.temporaryChannelId, t.getMessage.getBytes)
          goto(CLOSED) sending error
        case Success(_) =>
          context.system.eventStream.publish(ChannelCreated(self, context.parent, remoteNodeId, false, open.temporaryChannelId))
          // TODO: maybe also check uniqueness of temporary channel id
          val minimumDepth = nodeParams.minDepthBlocks
          val firstPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, 0)
          val accept = AcceptChannel(temporaryChannelId = open.temporaryChannelId,
            dustLimitSatoshis = localParams.dustLimitSatoshis,
            maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
            channelReserveSatoshis = localParams.channelReserveSatoshis,
            minimumDepth = minimumDepth,
            htlcMinimumMsat = localParams.htlcMinimumMsat,
            toSelfDelay = localParams.toSelfDelay,
            maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
            fundingPubkey = localParams.fundingPrivKey.publicKey,
            revocationBasepoint = localParams.revocationBasepoint,
            paymentBasepoint = localParams.paymentBasepoint,
            delayedPaymentBasepoint = localParams.delayedPaymentBasepoint,
            firstPerCommitmentPoint = firstPerCommitmentPoint)
          val remoteParams = RemoteParams(
            nodeId = remoteNodeId,
            dustLimitSatoshis = open.dustLimitSatoshis,
            maxHtlcValueInFlightMsat = open.maxHtlcValueInFlightMsat,
            channelReserveSatoshis = open.channelReserveSatoshis, // remote requires local to keep this much satoshis as direct payment
            htlcMinimumMsat = open.htlcMinimumMsat,
            toSelfDelay = open.toSelfDelay,
            maxAcceptedHtlcs = open.maxAcceptedHtlcs,
            fundingPubKey = open.fundingPubkey,
            revocationBasepoint = open.revocationBasepoint,
            paymentBasepoint = open.paymentBasepoint,
            delayedPaymentBasepoint = open.delayedPaymentBasepoint,
            globalFeatures = remoteInit.globalFeatures,
            localFeatures = remoteInit.localFeatures)
          log.debug(s"remote params: $remoteParams")
          goto(WAIT_FOR_FUNDING_CREATED) using DATA_WAIT_FOR_FUNDING_CREATED(open.temporaryChannelId, localParams, remoteParams, open.fundingSatoshis, open.pushMsat, open.feeratePerKw, open.firstPerCommitmentPoint, open.channelFlags, accept) sending accept
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) => handleRemoteErrorNoCommitments(e)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_ACCEPT_CHANNEL)(handleExceptions {
    case Event(accept: AcceptChannel, DATA_WAIT_FOR_ACCEPT_CHANNEL(INPUT_INIT_FUNDER(temporaryChannelId, fundingSatoshis, pushMsat, initialFeeratePerKw, localParams, _, remoteInit, _), open)) =>
      Try(Helpers.validateParamsFunder(nodeParams, accept.channelReserveSatoshis, fundingSatoshis)) match {
        case Failure(t) =>
          log.warning(t.getMessage)
          val error = Error(temporaryChannelId, t.getMessage.getBytes)
          goto(CLOSED) sending error
        case _ =>
          // TODO: check equality of temporaryChannelId? or should be done upstream
          val remoteParams = RemoteParams(
            nodeId = remoteNodeId,
            dustLimitSatoshis = accept.dustLimitSatoshis,
            maxHtlcValueInFlightMsat = accept.maxHtlcValueInFlightMsat,
            channelReserveSatoshis = accept.channelReserveSatoshis, // remote requires local to keep this much satoshis as direct payment
            htlcMinimumMsat = accept.htlcMinimumMsat,
            toSelfDelay = accept.toSelfDelay,
            maxAcceptedHtlcs = accept.maxAcceptedHtlcs,
            fundingPubKey = accept.fundingPubkey,
            revocationBasepoint = accept.revocationBasepoint,
            paymentBasepoint = accept.paymentBasepoint,
            delayedPaymentBasepoint = accept.delayedPaymentBasepoint,
            globalFeatures = remoteInit.globalFeatures,
            localFeatures = remoteInit.localFeatures)
          log.debug(s"remote params: $remoteParams")
          val localFundingPubkey = localParams.fundingPrivKey.publicKey
          // we assume that our funding parent tx is about 250 bytes, that the feereate-per-kb is 2*feerate-per-kw and we double the fee estimate
          // to give the parent a hefty fee
          blockchain ! MakeFundingTx(localFundingPubkey, remoteParams.fundingPubKey, Satoshi(fundingSatoshis), Globals.feeratePerKw.get())
          goto(WAIT_FOR_FUNDING_INTERNAL) using DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, localParams, remoteParams, fundingSatoshis, pushMsat, initialFeeratePerKw, accept.firstPerCommitmentPoint, open)
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) => handleRemoteErrorNoCommitments(e)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_INTERNAL)(handleExceptions {
    case Event(fundingResponse@MakeFundingTxResponse(parentTx: Transaction, fundingTx: Transaction, fundingTxOutputIndex: Int, priv: PrivateKey), data@DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, localParams, remoteParams, fundingSatoshis, pushMsat, initialFeeratePerKw, remoteFirstPerCommitmentPoint, _)) =>
      // we watch the first input of the parent tx, so that we can detect when it is spent by a malleated avatar
      val input0 = parentTx.txIn.head
      blockchain ! WatchSpent(self, input0.outPoint.txid, input0.outPoint.index.toInt, BITCOIN_INPUT_SPENT(parentTx))
      // and we publish the parent tx
      log.info(s"publishing parent tx: txid=${parentTx.txid} tx=${Transaction.write(parentTx)}")
      // we use a small delay so that we are sure Publish doesn't race with WatchSpent (which is ok but generates unnecessary warnings)
      context.system.scheduler.scheduleOnce(100 milliseconds, blockchain, PublishAsap(parentTx))
      goto(WAIT_FOR_FUNDING_PARENT) using DATA_WAIT_FOR_FUNDING_PARENT(fundingResponse, Set(parentTx), data)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) => handleRemoteErrorNoCommitments(e)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_PARENT)(handleExceptions {
    case Event(WatchEventSpent(BITCOIN_INPUT_SPENT(parentTx), spendingTx), DATA_WAIT_FOR_FUNDING_PARENT(fundingResponse, parentCandidates, data)) =>
      if (parentTx.txid != spendingTx.txid) {
        // an input of our parent tx was spent by a tx that we're not aware of (i.e. a malleated version of our parent tx)
        // set a new watch; if it is confirmed, we'll use it as the new parent for our funding tx
        log.warning(s"parent tx has been malleated: originalParentTxid=${parentTx.txid} malleated=${spendingTx.txid}")
      }
      blockchain ! WatchConfirmed(self, spendingTx.txid, minDepth = 1, BITCOIN_TX_CONFIRMED(spendingTx))
      stay using DATA_WAIT_FOR_FUNDING_PARENT(fundingResponse, parentCandidates + spendingTx, data)

    case Event(WatchEventConfirmed(BITCOIN_TX_CONFIRMED(tx), _, _), DATA_WAIT_FOR_FUNDING_PARENT(fundingResponse, _, data)) =>
      // a potential parent for our funding tx has been confirmed, let's update our funding tx
      Try(Helpers.Funding.replaceParent(fundingResponse, tx)) match {
        case Success(MakeFundingTxResponse(_, fundingTx, fundingTxOutputIndex, _)) =>
          // let's create the first commitment tx that spends the yet uncommitted funding tx
          import data._
          val (localSpec, localCommitTx, remoteSpec, remoteCommitTx) = Funding.makeFirstCommitTxs(localParams, remoteParams, fundingSatoshis, pushMsat, initialFeeratePerKw, fundingTx.hash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint, nodeParams.maxFeerateMismatch)

          val localSigOfRemoteTx = Transactions.sign(remoteCommitTx, localParams.fundingPrivKey)
          // signature of their initial commitment tx that pays remote pushMsat
          val fundingCreated = FundingCreated(
            temporaryChannelId = temporaryChannelId,
            fundingTxid = fundingTx.hash,
            fundingOutputIndex = fundingTxOutputIndex,
            signature = localSigOfRemoteTx
          )
          val channelId = toLongId(fundingTx.hash, fundingTxOutputIndex)
          context.parent ! ChannelIdAssigned(self, temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
          context.system.eventStream.publish(ChannelIdAssigned(self, temporaryChannelId, channelId))
          goto(WAIT_FOR_FUNDING_SIGNED) using DATA_WAIT_FOR_FUNDING_SIGNED(channelId, localParams, remoteParams, fundingTx, localSpec, localCommitTx, RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint), data.lastSent.channelFlags, fundingCreated) sending fundingCreated
        case Failure(cause) =>
          log.warning(s"confirmed tx ${tx.txid} is not an input to our funding tx")
          stay()
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) => handleRemoteErrorNoCommitments(e)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_CREATED)(handleExceptions {
    case Event(FundingCreated(_, fundingTxHash, fundingTxOutputIndex, remoteSig), DATA_WAIT_FOR_FUNDING_CREATED(temporaryChannelId, localParams, remoteParams, fundingSatoshis, pushMsat, initialFeeratePerKw, remoteFirstPerCommitmentPoint, channelFlags, _)) =>
      // they fund the channel with their funding tx, so the money is theirs (but we are paid pushMsat)
      val (localSpec, localCommitTx, remoteSpec, remoteCommitTx) = Funding.makeFirstCommitTxs(localParams, remoteParams, fundingSatoshis: Long, pushMsat, initialFeeratePerKw, fundingTxHash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint, nodeParams.maxFeerateMismatch)

      // check remote signature validity
      val localSigOfLocalTx = Transactions.sign(localCommitTx, localParams.fundingPrivKey)
      val signedLocalCommitTx = Transactions.addSigs(localCommitTx, localParams.fundingPrivKey.publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
      Transactions.checkSpendable(signedLocalCommitTx) match {
        case Failure(cause) =>
          log.error(cause, "their FundingCreated message contains an invalid signature")
          val error = Error(temporaryChannelId, cause.getMessage.getBytes)
          // we haven't anything at stake yet, we can just stop
          goto(CLOSED) sending error
        case Success(_) =>
          val localSigOfRemoteTx = Transactions.sign(remoteCommitTx, localParams.fundingPrivKey)
          val channelId = toLongId(fundingTxHash, fundingTxOutputIndex)
          // watch the funding tx transaction
          val commitInput = localCommitTx.input
          blockchain ! WatchSpent(self, commitInput.outPoint.txid, commitInput.outPoint.index.toInt, BITCOIN_FUNDING_SPENT) // TODO: should we wait for an acknowledgment from the watcher?
          blockchain ! WatchConfirmed(self, commitInput.outPoint.txid, nodeParams.minDepthBlocks, BITCOIN_FUNDING_DEPTHOK)
          val fundingSigned = FundingSigned(
            channelId = channelId,
            signature = localSigOfRemoteTx
          )
          val commitments = Commitments(localParams, remoteParams, channelFlags,
            LocalCommit(0, localSpec, PublishableTxs(signedLocalCommitTx, Nil)), RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint),
            LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
            localNextHtlcId = 0L, remoteNextHtlcId = 0L,
            remoteNextCommitInfo = Right(randomKey.publicKey), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array,
            commitInput, ShaChain.init, channelId = channelId)
          log.info(s"waiting for them to publish the funding tx for channelId=$channelId fundingTxid=${commitInput.outPoint.txid}")
          context.parent ! ChannelIdAssigned(self, temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
          context.system.eventStream.publish(ChannelIdAssigned(self, temporaryChannelId, channelId))
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
          goto(WAIT_FOR_FUNDING_CONFIRMED) using store(DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, None, Right(fundingSigned))) sending fundingSigned
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) => handleRemoteErrorNoCommitments(e)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_SIGNED)(handleExceptions {
    case Event(FundingSigned(_, remoteSig), DATA_WAIT_FOR_FUNDING_SIGNED(channelId, localParams, remoteParams, fundingTx, localSpec, localCommitTx, remoteCommit, channelFlags, fundingCreated)) =>
      // we make sure that their sig checks out and that our first commit tx is spendable
      val localSigOfLocalTx = Transactions.sign(localCommitTx, localParams.fundingPrivKey)
      val signedLocalCommitTx = Transactions.addSigs(localCommitTx, localParams.fundingPrivKey.publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
      Transactions.checkSpendable(signedLocalCommitTx) match {
        case Failure(cause) =>
          log.error(cause, "their FundingSigned message contains an invalid signature")
          val error = Error(channelId, cause.getMessage.getBytes)
          // we haven't published anything yet, we can just stop
          goto(CLOSED) sending error
        case Success(_) =>
          val commitInput = localCommitTx.input
          val commitments = Commitments(localParams, remoteParams, channelFlags,
            LocalCommit(0, localSpec, PublishableTxs(signedLocalCommitTx, Nil)), remoteCommit,
            LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
            localNextHtlcId = 0L, remoteNextHtlcId = 0L,
            remoteNextCommitInfo = Right(randomKey.publicKey), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array
            commitInput, ShaChain.init, channelId = channelId)
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
          log.info(s"publishing funding tx for channelId=$channelId fundingTxid=${commitInput.outPoint.txid}")
          // we do this to make sure that the channel state has been written to disk when we publish the funding tx
          val nextState = store(DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, None, Left(fundingCreated)))
          blockchain ! WatchSpent(self, commitInput.outPoint.txid, commitInput.outPoint.index.toInt, BITCOIN_FUNDING_SPENT) // TODO: should we wait for an acknowledgment from the watcher?
          blockchain ! WatchConfirmed(self, commitInput.outPoint.txid, nodeParams.minDepthBlocks, BITCOIN_FUNDING_DEPTHOK)
          blockchain ! PublishAsap(fundingTx)
          goto(WAIT_FOR_FUNDING_CONFIRMED) using nextState
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) => handleRemoteErrorNoCommitments(e)
  })

  when(WAIT_FOR_FUNDING_CONFIRMED)(handleExceptions {
    case Event(msg: FundingLocked, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      log.info(s"received their FundingLocked, deferring message")
      stay using d.copy(deferred = Some(msg))

    case Event(WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, blockHeight, txIndex), DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, deferred, lastSent)) =>
      log.info(s"channelId=${commitments.channelId} was confirmed at blockHeight=$blockHeight txIndex=$txIndex")
      blockchain ! WatchLost(self, commitments.commitInput.outPoint.txid, nodeParams.minDepthBlocks, BITCOIN_FUNDING_LOST)
      val nextPerCommitmentPoint = Generators.perCommitPoint(commitments.localParams.shaSeed, 1)
      val fundingLocked = FundingLocked(commitments.channelId, nextPerCommitmentPoint)
      deferred.map(self ! _)
      goto(WAIT_FOR_FUNDING_LOCKED) using store(DATA_WAIT_FOR_FUNDING_LOCKED(commitments, fundingLocked)) sending fundingLocked

    // TODO: not implemented, maybe should be done with a state timer and not a blockchain watch?
    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      val error = Error(d.channelId, "Funding tx timed out".getBytes)
      goto(CLOSED) sending error

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, _), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleInformationLeak(d)

    case Event(CMD_CLOSE(_), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => spendLocalCurrent(d)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleRemoteError(e, d)
  })

  when(WAIT_FOR_FUNDING_LOCKED)(handleExceptions {
    case Event(FundingLocked(_, nextPerCommitmentPoint), d@DATA_WAIT_FOR_FUNDING_LOCKED(commitments, _)) =>
      // this clock will be used to detect htlc timeouts
      context.system.eventStream.subscribe(self, classOf[CurrentBlockCount])
      context.system.eventStream.subscribe(self, classOf[CurrentFeerate])
      if (d.commitments.announceChannel) {
        // used for announcement of channel (if minDepth >= ANNOUNCEMENTS_MINCONF this event will fire instantly)
        blockchain ! WatchConfirmed(self, commitments.commitInput.outPoint.txid, ANNOUNCEMENTS_MINCONF, BITCOIN_FUNDING_DEEPLYBURIED)
      }
      goto(NORMAL) using store(DATA_NORMAL(commitments.copy(remoteNextCommitInfo = Right(nextPerCommitmentPoint)), None, None, None, None))

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_WAIT_FOR_FUNDING_LOCKED) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, _), d: DATA_WAIT_FOR_FUNDING_LOCKED) => handleInformationLeak(d)

    case Event(CMD_CLOSE(_), d: DATA_WAIT_FOR_FUNDING_LOCKED) => spendLocalCurrent(d)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_LOCKED) => handleRemoteError(e, d)
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

  when(NORMAL)(handleExceptions {
    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) if d.localShutdown.isDefined =>
      handleCommandError(ClosingInProgress)

    case Event(c@CMD_ADD_HTLC(_, _, _, _, downstream_opt, do_commit), d: DATA_NORMAL) =>
      Try(Commitments.sendAdd(d.commitments, c)) match {
        case Success(Right((commitments1, add))) =>
          val origin = downstream_opt match {
            case Some(u) => Relayed(sender, u)
            case None => Local(sender)
          }
          relayer ! AddHtlcSucceeded(add, origin)
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending add
        case Success(Left(error)) =>
          relayer ! AddHtlcFailed(c, error)
          handleCommandError(error)
        case Failure(cause) => handleCommandError(cause)
      }

    case Event(add: UpdateAddHtlc, d: DATA_NORMAL) =>
      Try(Commitments.receiveAdd(d.commitments, add)) match {
        case Success(commitments1) => stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(c: CMD_FULFILL_HTLC, d: DATA_NORMAL) =>
      Try(Commitments.sendFulfill(d.commitments, c)) match {
        case Success((commitments1, fulfill)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending fulfill
        case Failure(cause) => handleCommandError(cause)
      }

    case Event(fulfill: UpdateFulfillHtlc, d: DATA_NORMAL) =>
      Try(Commitments.receiveFulfill(d.commitments, fulfill)) match {
        case Success(Right(commitments1)) =>
          relayer ! ForwardFulfill(fulfill)
          stay using d.copy(commitments = commitments1)
        case Success(Left(_)) => stay
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(c: CMD_FAIL_HTLC, d: DATA_NORMAL) =>
      Try(Commitments.sendFail(d.commitments, c, nodeParams.privateKey)) match {
        case Success((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending fail
        case Failure(cause) => handleCommandError(cause)
      }

    case Event(c: CMD_FAIL_MALFORMED_HTLC, d: DATA_NORMAL) =>
      Try(Commitments.sendFailMalformed(d.commitments, c)) match {
        case Success((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending fail
        case Failure(cause) => handleCommandError(cause)
      }

    case Event(fail: UpdateFailHtlc, d: DATA_NORMAL) =>
      Try(Commitments.receiveFail(d.commitments, fail)) match {
        case Success(Right(commitments1)) =>
          relayer ! ForwardFail(fail)
          stay using d.copy(commitments = commitments1)
        case Success(Left(_)) => stay
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(fail: UpdateFailMalformedHtlc, d: DATA_NORMAL) =>
      Try(Commitments.receiveFailMalformed(d.commitments, fail)) match {
        case Success(Right(commitments1)) =>
          relayer ! ForwardFailMalformed(fail)
          stay using d.copy(commitments = commitments1)
        case Success(Left(_)) => stay
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(c: CMD_UPDATE_FEE, d: DATA_NORMAL) =>
      Try(Commitments.sendFee(d.commitments, c)) match {
        case Success((commitments1, fee)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending fee
        case Failure(cause) => handleCommandError(cause)
      }

    case Event(fee: UpdateFee, d: DATA_NORMAL) =>
      Try(Commitments.receiveFee(d.commitments, fee, nodeParams.maxFeerateMismatch)) match {
        case Success(commitments1) => stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(CMD_SIGN, d: DATA_NORMAL) =>
      d.commitments.remoteNextCommitInfo match {
        case _ if !Commitments.localHasChanges(d.commitments) =>
          log.info("ignoring CMD_SIGN (nothing to sign)")
          stay
        case Right(_) =>
          Try(Commitments.sendCommit(d.commitments)) match {
            case Success((commitments1, commit)) =>
              log.debug(s"sending a new sig, spec:\n${Commitments.specs2String(commitments1)}")
              handleCommandSuccess(sender, store(d.copy(commitments = commitments1))) sending commit
            case Failure(cause) => handleCommandError(cause)
          }
        case Left(waitForRevocation) =>
          log.debug(s"already in the process of signing, will sign again as soon as possible")
          val commitments1 = d.commitments.copy(remoteNextCommitInfo = Left(waitForRevocation.copy(reSignAsap = true)))
          stay using d.copy(commitments = commitments1)
      }

    case Event(commit: CommitSig, d: DATA_NORMAL) =>
      Try(Commitments.receiveCommit(d.commitments, commit)) match {
        case Success((commitments1, revocation)) =>
          log.debug(s"received a new sig, spec:\n${Commitments.specs2String(commitments1)}")
          if (Commitments.localHasChanges(commitments1)) {
            // if we have newly acknowledged changes let's sign them
            self ! CMD_SIGN
          }
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments1))
          stay using store(d.copy(commitments = commitments1)) sending revocation
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(revocation: RevokeAndAck, d: DATA_NORMAL) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked
      Try(Commitments.receiveRevocation(d.commitments, revocation)) match {
        case Success(commitments1) =>
          // we forward HTLCs only when they have been committed by both sides
          // it always happen when we receive a revocation, because, we always sign our changes before they sign them
          d.commitments.remoteChanges.signed.collect {
            case htlc: UpdateAddHtlc =>
              log.debug(s"relaying $htlc")
              relayer ! ForwardAdd(htlc)
          }
          log.debug(s"received a new rev, spec:\n${Commitments.specs2String(commitments1)}")
          if (Commitments.localHasChanges(commitments1) && d.commitments.remoteNextCommitInfo.left.map(_.reSignAsap) == Left(true)) {
            self ! CMD_SIGN
          }
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(CMD_CLOSE(localScriptPubKey_opt), d: DATA_NORMAL) =>
      val localScriptPubKey = localScriptPubKey_opt.getOrElse(d.commitments.localParams.defaultFinalScriptPubKey)
      if (d.localShutdown.isDefined)
        handleCommandError(ClosingAlreadyInProgress)
      else if (Commitments.localHasChanges(d.commitments))
      // TODO: simplistic behavior, we could also sign-then-close
        handleCommandError(CannotCloseWithPendingChanges)
      else if (!Closing.isValidFinalScriptPubkey(localScriptPubKey))
        handleCommandError(InvalidFinalScript)
      else {
        val shutdown = Shutdown(d.channelId, localScriptPubKey)
        handleCommandSuccess(sender, store(d.copy(localShutdown = Some(shutdown)))) sending shutdown
      }

    case Event(Shutdown(_, _), d: DATA_NORMAL) if d.commitments.remoteChanges.proposed.size > 0 =>
      handleLocalError(CannotCloseWithPendingChanges, d)

    case Event(remoteShutdown@Shutdown(_, remoteScriptPubKey), d: DATA_NORMAL) =>
      if (!Closing.isValidFinalScriptPubkey(remoteScriptPubKey)) throw InvalidFinalScript
      Try(d.localShutdown.map(s => (s, d.commitments)).getOrElse {
        // first if we have pending changes, we need to commit them
        val commitments2 = if (Commitments.localHasChanges(d.commitments)) {
          val (commitments1, commit) = Commitments.sendCommit(d.commitments)
          forwarder ! commit
          commitments1
        } else d.commitments
        val shutdown = Shutdown(d.channelId, d.commitments.localParams.defaultFinalScriptPubKey)
        forwarder ! shutdown
        d.shortChannelId.map {
          case shortChannelId =>
            // we announce that channel is disabled
            log.info(s"disabling the channel (closing initiated)")
            val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, shortChannelId, nodeParams.expiryDeltaBlocks, nodeParams.htlcMinimumMsat, nodeParams.feeBaseMsat, nodeParams.feeProportionalMillionth, enable = false)
            router ! channelUpdate
        }
        (shutdown, commitments2)
      }) match {
        case Success((localShutdown, commitments3))
          if (commitments3.remoteNextCommitInfo.isRight && commitments3.localCommit.spec.htlcs.size == 0 && commitments3.remoteCommit.spec.htlcs.size == 0)
            || (commitments3.remoteNextCommitInfo.isLeft && commitments3.localCommit.spec.htlcs.size == 0 && commitments3.remoteNextCommitInfo.left.get.nextRemoteCommit.spec.htlcs.size == 0) =>
          val closingSigned = Closing.makeFirstClosingTx(commitments3, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey)
          goto(NEGOTIATING) using store(DATA_NEGOTIATING(commitments3, localShutdown, remoteShutdown, closingSigned)) sending closingSigned
        case Success((localShutdown, commitments3)) =>
          goto(SHUTDOWN) using store(DATA_SHUTDOWN(commitments3, localShutdown, remoteShutdown))
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(CurrentBlockCount(count), d: DATA_NORMAL) if d.commitments.hasTimedoutOutgoingHtlcs(count) =>
      handleLocalError(HtlcTimedout, d)

    case Event(CurrentFeerate(feeratePerKw), d: DATA_NORMAL) =>
      d.commitments.localParams.isFunder match {
        case true if Helpers.shouldUpdateFee(d.commitments.localCommit.spec.feeratePerKw, feeratePerKw, nodeParams.updateFeeMinDiffRatio) =>
          self ! CMD_UPDATE_FEE(feeratePerKw, commit = true)
          stay
        case false if Helpers.isFeeDiffTooHigh(d.commitments.localCommit.spec.feeratePerKw, feeratePerKw, nodeParams.maxFeerateMismatch) =>
          handleLocalError(FeerateTooDifferent(localFeeratePerKw = feeratePerKw, remoteFeeratePerKw = d.commitments.localCommit.spec.feeratePerKw), d)
        case _ => stay
      }

    case Event(WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, blockHeight, txIndex), d: DATA_NORMAL) if d.commitments.announceChannel && d.shortChannelId.isEmpty =>
      val shortChannelId = toShortId(blockHeight, txIndex, d.commitments.commitInput.outPoint.index.toInt)
      log.info(s"funding tx is deeply buried at blockHeight=$blockHeight txIndex=$txIndex, sending announcements")
      // TODO: empty features
      val features = BinaryData("")
      val (localNodeSig, localBitcoinSig) = Announcements.signChannelAnnouncement(nodeParams.chainHash, shortChannelId, nodeParams.privateKey, remoteNodeId, d.commitments.localParams.fundingPrivKey, d.commitments.remoteParams.fundingPubKey, features)
      val annSignatures = AnnouncementSignatures(d.channelId, shortChannelId, localNodeSig, localBitcoinSig)
      stay using d.copy(localAnnouncementSignatures = Some(annSignatures)) sending annSignatures

    case Event(remoteAnnSigs: AnnouncementSignatures, d@DATA_NORMAL(commitments, None, _, _, _)) if d.commitments.announceChannel =>
      // announce channels only if we want to and our peer too
      // we would already have closed the connection if we require channels to be announced (even feature bit) but our
      // peer does not want channels to be announced
      d.localAnnouncementSignatures match {
        case Some(localAnnSigs) =>
          require(localAnnSigs.shortChannelId == remoteAnnSigs.shortChannelId, s"shortChannelId mismatch: local=${localAnnSigs.shortChannelId} remote=${remoteAnnSigs.shortChannelId}")
          log.info(s"announcing channelId=${d.channelId} on the network with shortId=${localAnnSigs.shortChannelId}")
          import commitments.{localParams, remoteParams}
          val channelAnn = Announcements.makeChannelAnnouncement(nodeParams.chainHash, localAnnSigs.shortChannelId, localParams.nodeId, remoteParams.nodeId, localParams.fundingPrivKey.publicKey, remoteParams.fundingPubKey, localAnnSigs.nodeSignature, remoteAnnSigs.nodeSignature, localAnnSigs.bitcoinSignature, remoteAnnSigs.bitcoinSignature)
          val nodeAnn = Announcements.makeNodeAnnouncement(nodeParams.privateKey, nodeParams.alias, nodeParams.color, nodeParams.publicAddresses)
          val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, localAnnSigs.shortChannelId, nodeParams.expiryDeltaBlocks, nodeParams.htlcMinimumMsat, nodeParams.feeBaseMsat, nodeParams.feeProportionalMillionth)
          router ! channelAnn
          router ! nodeAnn
          router ! channelUpdate
          relayer ! channelUpdate
          // TODO: remove this later when we use testnet/mainnet
          // let's trigger the broadcast immediately so that we don't wait for 60 seconds to announce our newly created channel
          // we give 3 seconds for the router-watcher roundtrip
          context.system.scheduler.scheduleOnce(3 seconds, router, 'tick_broadcast)
          context.system.eventStream.publish(ShortChannelIdAssigned(self, d.channelId, localAnnSigs.shortChannelId))
          // we acknowledge our AnnouncementSignatures message
          stay using store(d.copy(shortChannelId = Some(localAnnSigs.shortChannelId), localAnnouncementSignatures = None))
        case None =>
          log.info(s"received remote announcement signatures, delaying")
          // our watcher didn't notify yet that the tx has reached ANNOUNCEMENTS_MINCONF confirmations, let's delay remote's message
          context.system.scheduler.scheduleOnce(5 seconds, self, remoteAnnSigs)
          stay
      }

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NORMAL) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NORMAL) if Some(tx.txid) == d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NORMAL) => handleRemoteSpentOther(tx, d)

    case Event(INPUT_DISCONNECTED, d: DATA_NORMAL) =>
      d.commitments.localChanges.proposed.collect {
        case add: UpdateAddHtlc => relayer ! AddHtlcDiscarded(add)
      }
      d.shortChannelId match {
        case Some(shortChannelId) =>
          // if channel has be announced, we disable it
          log.info(s"disabling the channel (disconnected)")
          val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, shortChannelId, nodeParams.expiryDeltaBlocks, nodeParams.htlcMinimumMsat, nodeParams.feeBaseMsat, nodeParams.feeProportionalMillionth, enable = false)
          router ! channelUpdate
        case None => {}
      }
      goto(OFFLINE)

    case Event(e: Error, d: DATA_NORMAL) => handleRemoteError(e, d)

    case Event(_: FundingLocked, _: DATA_NORMAL) => stay // will happen after a reconnection if no updates were ever committed to the channel

  })

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

  when(SHUTDOWN)(handleExceptions {
    case Event(c: CMD_FULFILL_HTLC, d: DATA_SHUTDOWN) =>
      Try(Commitments.sendFulfill(d.commitments, c)) match {
        case Success((commitments1, fulfill)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending fulfill
        case Failure(cause) => handleCommandError(cause)
      }

    case Event(fulfill: UpdateFulfillHtlc, d: DATA_SHUTDOWN) =>
      Try(Commitments.receiveFulfill(d.commitments, fulfill)) match {
        case Success(Right(commitments1)) =>
          relayer ! ForwardFulfill(fulfill)
          stay using d.copy(commitments = commitments1)
        case Success(Left(_)) => stay
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(c: CMD_FAIL_HTLC, d: DATA_SHUTDOWN) =>
      Try(Commitments.sendFail(d.commitments, c, nodeParams.privateKey)) match {
        case Success((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending fail
        case Failure(cause) => handleCommandError(cause)
      }

    case Event(c: CMD_FAIL_MALFORMED_HTLC, d: DATA_SHUTDOWN) =>
      Try(Commitments.sendFailMalformed(d.commitments, c)) match {
        case Success((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending fail
        case Failure(cause) => handleCommandError(cause)
      }

    case Event(fail: UpdateFailHtlc, d: DATA_SHUTDOWN) =>
      Try(Commitments.receiveFail(d.commitments, fail)) match {
        case Success(Right(commitments1)) =>
          relayer ! ForwardFail(fail)
          stay using d.copy(commitments = commitments1)
        case Success(Left(_)) => stay
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(fail: UpdateFailMalformedHtlc, d: DATA_SHUTDOWN) =>
      Try(Commitments.receiveFailMalformed(d.commitments, fail)) match {
        case Success(Right(commitments1)) =>
          relayer ! ForwardFailMalformed(fail)
          stay using d.copy(commitments = commitments1)
        case Success(Left(_)) => stay
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(c: CMD_UPDATE_FEE, d: DATA_SHUTDOWN) =>
      Try(Commitments.sendFee(d.commitments, c)) match {
        case Success((commitments1, fee)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending fee
        case Failure(cause) => handleCommandError(cause)
      }

    case Event(fee: UpdateFee, d: DATA_SHUTDOWN) =>
      Try(Commitments.receiveFee(d.commitments, fee, nodeParams.maxFeerateMismatch)) match {
        case Success(commitments1) => stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(CMD_SIGN, d: DATA_SHUTDOWN) =>
      d.commitments.remoteNextCommitInfo match {
        case _ if !Commitments.localHasChanges(d.commitments) =>
          log.info("ignoring CMD_SIGN (nothing to sign)")
          stay
        case Right(_) =>
          Try(Commitments.sendCommit(d.commitments)) match {
            case Success((commitments1, commit)) =>
              log.debug(s"sending a new sig, spec:\n${Commitments.specs2String(commitments1)}")
              handleCommandSuccess(sender, store(d.copy(commitments = commitments1))) sending commit
            case Failure(cause) => handleCommandError(cause)
          }
        case Left(waitForRevocation) =>
          log.debug(s"already in the process of signing, will sign again as soon as possible")
          stay using d.copy(commitments = d.commitments.copy(remoteNextCommitInfo = Left(waitForRevocation.copy(reSignAsap = true))))
      }

    case Event(msg: CommitSig, d@DATA_SHUTDOWN(_, localShutdown, remoteShutdown)) =>
      Try(Commitments.receiveCommit(d.commitments, msg)) map {
        case (commitments1, revocation) =>
          // we always reply with a revocation
          log.debug(s"received a new sig:\n${Commitments.specs2String(commitments1)}")
          forwarder ! revocation
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments1))
          commitments1
      } match {
        case Success(commitments1) if commitments1.hasNoPendingHtlcs =>
          val closingSigned = Closing.makeFirstClosingTx(commitments1, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey)
          goto(NEGOTIATING) using store(DATA_NEGOTIATING(commitments1, localShutdown, remoteShutdown, closingSigned)) sending closingSigned
        case Success(commitments1) =>
          if (Commitments.localHasChanges(commitments1)) {
            // if we have newly acknowledged changes let's sign them
            self ! CMD_SIGN
          }
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(msg: RevokeAndAck, d@DATA_SHUTDOWN(commitments, localShutdown, remoteShutdown)) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked including the shutdown message
      Try(Commitments.receiveRevocation(commitments, msg)) match {
        case Success(commitments1) if commitments1.hasNoPendingHtlcs =>
          log.debug(s"received a new rev, switching to NEGOTIATING spec:\n${Commitments.specs2String(commitments1)}")
          val closingSigned = Closing.makeFirstClosingTx(commitments1, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey)
          goto(NEGOTIATING) using store(DATA_NEGOTIATING(commitments1, localShutdown, remoteShutdown, closingSigned)) sending closingSigned
        case Success(commitments1) =>
          if (Commitments.localHasChanges(commitments1) && d.commitments.remoteNextCommitInfo.left.map(_.reSignAsap) == Left(true)) {
            self ! CMD_SIGN
          }
          log.debug(s"received a new rev, spec:\n${Commitments.specs2String(commitments1)}")
          stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(CurrentBlockCount(count), d: DATA_SHUTDOWN) if d.commitments.hasTimedoutOutgoingHtlcs(count) =>
      handleLocalError(HtlcTimedout, d)

    case Event(CurrentFeerate(feeratePerKw), d: DATA_SHUTDOWN) =>
      d.commitments.localParams.isFunder match {
        case true if Helpers.shouldUpdateFee(d.commitments.localCommit.spec.feeratePerKw, feeratePerKw, nodeParams.updateFeeMinDiffRatio) =>
          self ! CMD_UPDATE_FEE(feeratePerKw, commit = true)
          stay
        case false if Helpers.isFeeDiffTooHigh(d.commitments.localCommit.spec.feeratePerKw, feeratePerKw, nodeParams.maxFeerateMismatch) =>
          handleLocalError(FeerateTooDifferent(localFeeratePerKw = feeratePerKw, remoteFeeratePerKw = d.commitments.localCommit.spec.feeratePerKw), d)
        case _ => stay
      }

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_SHUTDOWN) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_SHUTDOWN) if Some(tx.txid) == d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_SHUTDOWN) => handleRemoteSpentOther(tx, d)

    case Event(CMD_CLOSE(_), d: DATA_SHUTDOWN) => handleCommandError(ClosingAlreadyInProgress)

    case Event(e: Error, d: DATA_SHUTDOWN) => handleRemoteError(e, d)

  })

  when(NEGOTIATING)(handleExceptions {
    case Event(ClosingSigned(_, remoteClosingFee, remoteSig), d: DATA_NEGOTIATING) =>
      Closing.checkClosingSignature(d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, Satoshi(remoteClosingFee), remoteSig) match {
        case Success(signedClosingTx) if remoteClosingFee == d.localClosingSigned.feeSatoshis =>
          handleMutualClose(signedClosingTx, d)
        case Success(signedClosingTx) =>
          val nextClosingFee = Closing.nextClosingFee(Satoshi(d.localClosingSigned.feeSatoshis), Satoshi(remoteClosingFee))
          val (_, closingSigned) = Closing.makeClosingTx(d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nextClosingFee)
          if (nextClosingFee == Satoshi(remoteClosingFee)) {
            handleMutualClose(signedClosingTx, store(d)) sending closingSigned
          } else {
            stay using store(d.copy(localClosingSigned = closingSigned)) sending closingSigned
          }
        case Failure(cause) =>
          log.error(cause, "cannot verify their close signature")
          throw InvalidCloseSignature
      }

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NEGOTIATING) if tx.txid == Closing.makeClosingTx(d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, Satoshi(d.localClosingSigned.feeSatoshis))._1.tx.txid =>
      // happens when we agreed on a closeSig, but we don't know it yet: we receive the watcher notification before their ClosingSigned (which will match ours)
      stay

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NEGOTIATING) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NEGOTIATING) if Some(tx.txid) == d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NEGOTIATING) => handleRemoteSpentOther(tx, d)

    case Event(CMD_CLOSE(_), d: DATA_NEGOTIATING) => handleCommandError(ClosingAlreadyInProgress)

    case Event(e: Error, d: DATA_NEGOTIATING) => handleRemoteError(e, d)

  })

  when(CLOSING)(handleExceptions {
    case Event(c: CMD_FULFILL_HTLC, d: DATA_CLOSING) =>
      Try(Commitments.sendFulfill(d.commitments, c)) match {
        case Success((commitments1, _)) =>
          log.info(s"got valid payment preimage, recalculating transactions to redeem the corresponding htlc on-chain")
          val localCommitPublished1 = d.localCommitPublished.map {
            case localCommitPublished =>
              val localCommitPublished1 = Helpers.Closing.claimCurrentLocalCommitTxOutputs(commitments1, localCommitPublished.commitTx)
              doPublish(localCommitPublished1)
              localCommitPublished1
          }
          val remoteCommitPublished1 = d.remoteCommitPublished.map {
            case remoteCommitPublished =>
              val remoteCommitPublished1 = Helpers.Closing.claimRemoteCommitTxOutputs(commitments1, commitments1.remoteCommit, remoteCommitPublished.commitTx)
              doPublish(remoteCommitPublished1, BITCOIN_REMOTECOMMIT_DONE)
              remoteCommitPublished1
          }
          val nextRemoteCommitPublished1 = d.nextRemoteCommitPublished.map {
            case remoteCommitPublished =>
              val remoteCommitPublished1 = Helpers.Closing.claimRemoteCommitTxOutputs(commitments1, commitments1.remoteCommit, remoteCommitPublished.commitTx)
              doPublish(remoteCommitPublished1, BITCOIN_NEXTREMOTECOMMIT_DONE)
              remoteCommitPublished1
          }
          stay using store(d.copy(commitments = commitments1, localCommitPublished = localCommitPublished1, remoteCommitPublished = remoteCommitPublished1, nextRemoteCommitPublished = nextRemoteCommitPublished1))
        case Failure(cause) => handleCommandError(cause)
      }

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) if Some(tx.txid) == d.mutualClosePublished.map(_.txid) =>
      // we just published a mutual close tx, we are notified but it's alright
      stay

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) if Some(tx.txid) == d.localCommitPublished.map(_.commitTx.txid) =>
      // this is because WatchSpent watches never expire and we are notified multiple times
      stay

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) if Some(tx.txid) == d.remoteCommitPublished.map(_.commitTx.txid) =>
      // this is because WatchSpent watches never expire and we are notified multiple times
      stay

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) if Some(tx.txid) == d.nextRemoteCommitPublished.map(_.commitTx.txid) =>
      // this is because WatchSpent watches never expire and we are notified multiple times
      stay

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) if tx.txid == d.commitments.remoteCommit.txid =>
      // counterparty may attempt to spend its last commit tx at any time
      handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) if Some(tx.txid) == d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid) =>
      // counterparty may attempt to spend its last commit tx at any time
      handleRemoteSpentNext(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) =>
      // counterparty may attempt to spend a revoked commit tx at any time
      handleRemoteSpentOther(tx, d)

    case Event(WatchEventConfirmed(BITCOIN_CLOSE_DONE, _, _), d: DATA_CLOSING) if d.mutualClosePublished.isDefined => goto(CLOSED)

    case Event(WatchEventConfirmed(BITCOIN_LOCALCOMMIT_DONE, _, _), d: DATA_CLOSING) if d.localCommitPublished.isDefined => goto(CLOSED)

    case Event(WatchEventConfirmed(BITCOIN_REMOTECOMMIT_DONE, _, _), d: DATA_CLOSING) if d.remoteCommitPublished.isDefined => goto(CLOSED)

    case Event(WatchEventConfirmed(BITCOIN_NEXTREMOTECOMMIT_DONE, _, _), d: DATA_CLOSING) if d.nextRemoteCommitPublished.isDefined => goto(CLOSED)

    case Event(WatchEventConfirmed(BITCOIN_PENALTY_DONE, _, _), d: DATA_CLOSING) if d.revokedCommitPublished.size > 0 => goto(CLOSED)

    case Event(CMD_CLOSE(_), d: DATA_CLOSING) => handleCommandError(ClosingAlreadyInProgress)

    case Event(e: Error, d: DATA_CLOSING) => stay // nothing to do, there is already a spending tx published

    case Event(INPUT_DISCONNECTED, _) =>
      log.info(s"we are disconnected, but it does not matter anymore")
      stay
  })

  when(CLOSED, stateTimeout = 10 seconds)(handleExceptions {
    case Event(StateTimeout, _) =>
      stateData match {
        case d: HasCommitments =>
          log.info(s"deleting database record for channelId=${d.channelId}")
          nodeParams.channelsDb.removeChannel(d.channelId)
        case _ => {}
      }
      log.info("shutting down")
      stop(FSM.Normal)

    case Event(INPUT_DISCONNECTED, _) => stay
  })

  when(OFFLINE)(handleExceptions {
    case Event(INPUT_RECONNECTED(r), d: HasCommitments) =>
      forwarder ! r
      val channelReestablish = ChannelReestablish(
        channelId = d.channelId,
        nextLocalCommitmentNumber = d.commitments.localCommit.index + 1,
        nextRemoteRevocationNumber = d.commitments.remoteCommit.index
      )
      goto(SYNCING) sending channelReestablish

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) =>
      log.info(s"rejecting htlc (disconnected)")
      relayer ! AddHtlcFailed(c, ChannelUnavailable)
      handleCommandError(ChannelUnavailable)

    case Event(CMD_CLOSE(_), d: HasCommitments) => handleLocalError(ForcedLocalCommit("can't do a mutual close while disconnected"), d) replying "ok"

    case Event(CurrentBlockCount(count), d: HasCommitments) if d.commitments.hasTimedoutOutgoingHtlcs(count) =>
      handleLocalError(HtlcTimedout, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: HasCommitments) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: HasCommitments) if Some(tx.txid) == d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: HasCommitments) => handleRemoteSpentOther(tx, d)
  })

  when(SYNCING)(handleExceptions {
    case Event(_: ChannelReestablish, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      // we put back the watch (operation is idempotent) because the event may have been fired while we were in OFFLINE
      blockchain ! WatchConfirmed(self, d.commitments.commitInput.outPoint.txid, nodeParams.minDepthBlocks, BITCOIN_FUNDING_DEPTHOK)
      goto(WAIT_FOR_FUNDING_CONFIRMED)

    case Event(_: ChannelReestablish, d: DATA_WAIT_FOR_FUNDING_LOCKED) =>
      log.info(s"re-sending fundingLocked")
      val nextPerCommitmentPoint = Generators.perCommitPoint(d.commitments.localParams.shaSeed, 1)
      val fundingLocked = FundingLocked(d.commitments.channelId, nextPerCommitmentPoint)
      goto(WAIT_FOR_FUNDING_LOCKED) sending (fundingLocked)

    case Event(channelReestablish: ChannelReestablish, d: DATA_NORMAL) =>

      val commitments1 = if (channelReestablish.nextLocalCommitmentNumber == 1 && d.commitments.localCommit.index == 0) {
        // no new commitment was exchanged after NORMAL state was reached
        log.info(s"re-sending fundingLocked")
        val nextPerCommitmentPoint = Generators.perCommitPoint(d.commitments.localParams.shaSeed, 1)
        val fundingLocked = FundingLocked(d.commitments.channelId, nextPerCommitmentPoint)
        forwarder ! fundingLocked
        d.commitments
      } else {
        handleSync(channelReestablish, d)
      }

      d.localShutdown.map {
        case localShutdown =>
          log.info(s"re-sending localShutdown")
          forwarder ! localShutdown
      }

      // we put back the watch (operation is idempotent) because the event may have been fired while we were in OFFLINE
      if (d.commitments.announceChannel && d.shortChannelId.isEmpty) {
        blockchain ! WatchConfirmed(self, d.commitments.commitInput.outPoint.txid, ANNOUNCEMENTS_MINCONF, BITCOIN_FUNDING_DEEPLYBURIED)
      }

      d.shortChannelId.map {
        case shortChannelId =>
          // we re-enable the channel
          log.info(s"enabling the channel (reconnected)")
          val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, shortChannelId, nodeParams.expiryDeltaBlocks, nodeParams.htlcMinimumMsat, nodeParams.feeBaseMsat, nodeParams.feeProportionalMillionth, enable = true)
          router ! channelUpdate
      }
      goto(NORMAL) using d.copy(commitments = commitments1)

    case Event(channelReestablish: ChannelReestablish, d: DATA_SHUTDOWN) =>
      val commitments1 = handleSync(channelReestablish, d)
      forwarder ! d.localShutdown
      goto(SHUTDOWN) using d.copy(commitments = commitments1)

    case Event(_: ChannelReestablish, d: DATA_NEGOTIATING) =>
      forwarder ! d.localClosingSigned
      goto(NEGOTIATING)

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) =>
      log.info(s"rejecting htlc (syncing)")
      relayer ! AddHtlcFailed(c, ChannelUnavailable)
      handleCommandError(ChannelUnavailable)

    case Event(CMD_CLOSE(_), d: HasCommitments) => handleLocalError(ForcedLocalCommit("can't do a mutual close while syncing"), d)

    case Event(CurrentBlockCount(count), d: HasCommitments) if d.commitments.hasTimedoutOutgoingHtlcs(count) =>
      handleLocalError(HtlcTimedout, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: HasCommitments) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: HasCommitments) if Some(tx.txid) == d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: HasCommitments) => handleRemoteSpentOther(tx, d)
  })

  when(ERR_INFORMATION_LEAK, stateTimeout = 10 seconds) {
    case Event(StateTimeout, _) =>
      log.info("shutting down")
      stop(FSM.Normal)
  }

  whenUnhandled {

    case Event(INPUT_PUBLISH_LOCALCOMMIT, d: HasCommitments) => handleLocalError(ForcedLocalCommit("manual unilateral close"), d)

    case Event(INPUT_DISCONNECTED, _) => goto(OFFLINE)

    case Event(WatchEventLost(BITCOIN_FUNDING_LOST), _) => goto(ERR_FUNDING_LOST)

    case Event(CMD_GETSTATE, _) =>
      sender ! stateName
      stay

    case Event(CMD_GETSTATEDATA, _) =>
      sender ! stateData
      stay

    case Event(CMD_GETINFO, _) =>
      val channelId = Helpers.getChannelId(stateData)
      sender ! RES_GETINFO(remoteNodeId, channelId, stateName, stateData)
      stay

    // we only care about this event in NORMAL and SHUTDOWN state, and we never unregister to the event stream
    case Event(CurrentBlockCount(_), _) => stay

    // we only care about this event in NORMAL and SHUTDOWN state, and we never unregister to the event stream
    case Event(CurrentFeerate(_), _) => stay

    case Event("ok", _) => stay // noop handler
  }

  onTransition {
    case WAIT_FOR_INIT_INTERNAL -> WAIT_FOR_INIT_INTERNAL => {} // called at channel initialization
    case WAIT_FOR_INIT_INTERNAL -> OFFLINE =>
      context.system.eventStream.publish(ChannelStateChanged(self, context.parent, remoteNodeId, WAIT_FOR_INIT_INTERNAL, OFFLINE, nextStateData))
    case state -> nextState if nextState != state =>
      context.system.eventStream.publish(ChannelStateChanged(self, context.parent, remoteNodeId, state, nextState, nextStateData))
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

  def handleCommandSuccess(sender: ActorRef, newData: Data) = {
    stay using newData replying "ok"
  }

  def handleCommandError(cause: Throwable) = {
    cause match {
      case _: ChannelException => log.error(s"$cause")
      case _ => log.error(cause, "")
    }
    stay replying Status.Failure(cause)
  }

  def handleLocalError(cause: Throwable, d: HasCommitments) = {
    log.error(cause, "")
    val error = Error(d.channelId, cause.getMessage.getBytes)
    spendLocalCurrent(d) sending error
  }

  def handleRemoteErrorNoCommitments(e: Error) = {
    // when there is no commitment yet, we just go to CLOSED state in case an error occurs
    log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
    goto(CLOSED)
  }

  def handleRemoteError(e: Error, d: HasCommitments) = {
    log.error(s"peer sent $e, closing connection") // see bolt #2: A node MUST fail the connection if it receives an err message
    spendLocalCurrent(d)
  }

  def handleMutualClose(closingTx: Transaction, d: DATA_NEGOTIATING) = {
    log.info(s"closingTxId=${closingTx.txid}")
    val mutualClosePublished = Some(closingTx)
    doPublish(closingTx)
    val nextData = DATA_CLOSING(d.commitments, mutualClosePublished)
    goto(CLOSING) using store(nextData)
  }

  def doPublish(closingTx: Transaction) = {
    blockchain ! PublishAsap(closingTx)
    blockchain ! WatchConfirmed(self, closingTx.txid, nodeParams.minDepthBlocks, BITCOIN_CLOSE_DONE)
  }

  def spendLocalCurrent(d: HasCommitments) = {
    val commitTx = d.commitments.localCommit.publishableTxs.commitTx.tx

    val localCommitPublished = Helpers.Closing.claimCurrentLocalCommitTxOutputs(d.commitments, commitTx)
    doPublish(localCommitPublished)

    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(localCommitPublished = Some(localCommitPublished))
      case _ => DATA_CLOSING(d.commitments, localCommitPublished = Some(localCommitPublished))
    }

    goto(CLOSING) using store(nextData)
  }

  def doPublish(localCommitPublished: LocalCommitPublished) = {
    blockchain ! PublishAsap(localCommitPublished.commitTx)

    // if there is a claim-main-delayed-output tx, we watch it, otherwise we watch the commit tx
    // NB: we do not watch for htlcs txes!!
    // this may lead to some htlcs not been claimed because the channel will be considered close and deleted before the claiming txes are published
    localCommitPublished.claimMainDelayedOutputTx match {
      case Some(tx) => blockchain ! WatchConfirmed(self, tx.txid, nodeParams.minDepthBlocks, BITCOIN_LOCALCOMMIT_DONE)
      case None => blockchain ! WatchConfirmed(self, localCommitPublished.commitTx.txid, nodeParams.minDepthBlocks, BITCOIN_LOCALCOMMIT_DONE)
    }

    localCommitPublished.claimMainDelayedOutputTx.foreach(tx => blockchain ! PublishAsap(tx))
    localCommitPublished.htlcSuccessTxs.foreach(tx => blockchain ! PublishAsap(tx))
    localCommitPublished.htlcTimeoutTxs.foreach(tx => blockchain ! PublishAsap(tx))
    localCommitPublished.claimHtlcDelayedTx.foreach(tx => blockchain ! PublishAsap(tx))

    // we also watch the htlc-timeout outputs in order to extract payment preimages
    localCommitPublished.htlcTimeoutTxs.foreach(tx => {
      require(tx.txIn.size == 1, s"an htlc-timeout tx must have exactly 1 input (has ${tx.txIn.size})")
      val outpoint = tx.txIn(0).outPoint
      log.info(s"watching output ${outpoint.index} of commit tx ${outpoint.txid}")
      blockchain ! WatchSpent(relayer, outpoint.txid, outpoint.index.toInt, BITCOIN_HTLC_SPENT)
    })
  }

  def handleRemoteSpentCurrent(commitTx: Transaction, d: HasCommitments) = {
    log.warning(s"they published their current commit in txid=${commitTx.txid}")
    require(commitTx.txid == d.commitments.remoteCommit.txid, "txid mismatch")

    val remoteCommitPublished = Helpers.Closing.claimRemoteCommitTxOutputs(d.commitments, d.commitments.remoteCommit, commitTx)
    doPublish(remoteCommitPublished, BITCOIN_REMOTECOMMIT_DONE)

    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(remoteCommitPublished = Some(remoteCommitPublished))
      case _ => DATA_CLOSING(d.commitments, remoteCommitPublished = Some(remoteCommitPublished))
    }

    goto(CLOSING) using store(nextData)
  }

  def handleRemoteSpentNext(commitTx: Transaction, d: HasCommitments) = {
    log.warning(s"they published their next commit in txid=${commitTx.txid}")
    require(d.commitments.remoteNextCommitInfo.isLeft, "next remote commit must be defined")
    val remoteCommit = d.commitments.remoteNextCommitInfo.left.get.nextRemoteCommit
    require(commitTx.txid == remoteCommit.txid, "txid mismatch")

    val remoteCommitPublished = Helpers.Closing.claimRemoteCommitTxOutputs(d.commitments, remoteCommit, commitTx)
    doPublish(remoteCommitPublished, BITCOIN_NEXTREMOTECOMMIT_DONE)

    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(nextRemoteCommitPublished = Some(remoteCommitPublished))
      case _ => DATA_CLOSING(d.commitments, nextRemoteCommitPublished = Some(remoteCommitPublished))
    }

    goto(CLOSING) using store(nextData)
  }

  def doPublish(remoteCommitPublished: RemoteCommitPublished, event: BitcoinEvent) = {
    require(event == BITCOIN_REMOTECOMMIT_DONE || event == BITCOIN_NEXTREMOTECOMMIT_DONE)

    // if there is a claim-main-output tx, we watch it, otherwise we watch the commit tx
    // NB: we do not watch for htlcs txes!!
    // this may lead to some htlcs not been claimed because the channel will be considered close and deleted before the claiming txes are published
    remoteCommitPublished.claimMainOutputTx match {
      case Some(tx) => blockchain ! WatchConfirmed(self, tx.txid, nodeParams.minDepthBlocks, event)
      case None => blockchain ! WatchConfirmed(self, remoteCommitPublished.commitTx.txid, nodeParams.minDepthBlocks, event)
    }

    remoteCommitPublished.claimMainOutputTx.foreach(tx => blockchain ! PublishAsap(tx))
    remoteCommitPublished.claimHtlcSuccessTxs.foreach(tx => blockchain ! PublishAsap(tx))
    remoteCommitPublished.claimHtlcTimeoutTxs.foreach(tx => blockchain ! PublishAsap(tx))

    // we also watch the htlc-sent outputs in order to extract payment preimages
    remoteCommitPublished.claimHtlcTimeoutTxs.foreach(tx => {
      require(tx.txIn.size == 1, s"a claim-htlc-timeout tx must have exactly 1 input (has ${tx.txIn.size})")
      val outpoint = tx.txIn(0).outPoint
      log.info(s"watching output ${outpoint.index} of commit tx ${outpoint.txid}")
      blockchain ! WatchSpent(relayer, outpoint.txid, outpoint.index.toInt, BITCOIN_HTLC_SPENT)
    })
  }

  def handleRemoteSpentOther(tx: Transaction, d: HasCommitments) = {
    log.warning(s"funding tx spent in txid=${tx.txid}")

    Helpers.Closing.claimRevokedRemoteCommitTxOutputs(d.commitments, tx) match {
      case Some(revokedCommitPublished) =>
        log.warning(s"txid=${tx.txid} was a revoked commitment, publishing the penalty tx")
        val error = Error(d.channelId, "Funding tx has been spent".getBytes)

        doPublish(revokedCommitPublished)

        val nextData = d match {
          case closing: DATA_CLOSING => closing.copy(revokedCommitPublished = closing.revokedCommitPublished :+ revokedCommitPublished)
          case _ => DATA_CLOSING(d.commitments, revokedCommitPublished = revokedCommitPublished :: Nil)
        }
        goto(CLOSING) using store(nextData) sending error
      case None =>
        // the published tx was neither their current commitment nor a revoked one
        log.error(s"couldn't identify txid=${tx.txid}, something very bad is going on!!!")
        goto(ERR_INFORMATION_LEAK)
    }
  }

  def doPublish(revokedCommitPublished: RevokedCommitPublished) = {

    // if there is a main-penalty or a claim-main-output tx, we watch them, otherwise we watch the commit tx
    // NB: we do not watch for htlcs txes, but we don't steal them currently anyway
    (revokedCommitPublished.mainPenaltyTx, revokedCommitPublished.claimMainOutputTx) match {
      case (Some(tx), _) => blockchain ! WatchConfirmed(self, tx.txid, nodeParams.minDepthBlocks, BITCOIN_PENALTY_DONE)
      case (None, Some(tx)) => blockchain ! WatchConfirmed(self, tx.txid, nodeParams.minDepthBlocks, BITCOIN_PENALTY_DONE)
      case _ => blockchain ! WatchConfirmed(self, revokedCommitPublished.commitTx.txid, nodeParams.minDepthBlocks, BITCOIN_PENALTY_DONE)
    }

    revokedCommitPublished.claimMainOutputTx.foreach(tx => blockchain ! PublishAsap(tx))
    revokedCommitPublished.mainPenaltyTx.foreach(tx => blockchain ! PublishAsap(tx))
    revokedCommitPublished.claimHtlcTimeoutTxs.foreach(tx => blockchain ! PublishAsap(tx))
    revokedCommitPublished.htlcTimeoutTxs.foreach(tx => blockchain ! PublishAsap(tx))
    revokedCommitPublished.htlcPenaltyTxs.foreach(tx => blockchain ! PublishAsap(tx))
  }

  def handleInformationLeak(d: HasCommitments) = {
    // this is never supposed to happen !!
    log.error(s"our funding tx ${d.commitments.commitInput.outPoint.txid} was spent !!")
    val error = Error(d.channelId, "Funding tx has been spent".getBytes)

    // let's try to spend our current local tx
    val commitTx = d.commitments.localCommit.publishableTxs.commitTx.tx
    val localCommitPublished = Helpers.Closing.claimCurrentLocalCommitTxOutputs(d.commitments, commitTx)
    doPublish(localCommitPublished)

    goto(ERR_INFORMATION_LEAK) sending error
  }

  def handleSync(channelReestablish: ChannelReestablish, d: HasCommitments): Commitments = {
    // first we clean up unacknowledged updates
    log.debug(s"discarding proposed OUT: ${d.commitments.localChanges.proposed.map(Commitments.msg2String(_)).mkString(",")}")
    log.debug(s"discarding proposed IN: ${d.commitments.remoteChanges.proposed.map(Commitments.msg2String(_)).mkString(",")}")
    val commitments1 = d.commitments.copy(
      localChanges = d.commitments.localChanges.copy(proposed = Nil),
      remoteChanges = d.commitments.remoteChanges.copy(proposed = Nil),
      localNextHtlcId = d.commitments.localNextHtlcId - d.commitments.localChanges.proposed.collect { case u: UpdateAddHtlc => u }.size,
      remoteNextHtlcId = d.commitments.remoteNextHtlcId - d.commitments.remoteChanges.proposed.collect { case u: UpdateAddHtlc => u }.size)
    log.debug(s"localNextHtlcId=${d.commitments.localNextHtlcId}->${commitments1.localNextHtlcId}")
    log.debug(s"remoteNextHtlcId=${d.commitments.remoteNextHtlcId}->${commitments1.remoteNextHtlcId}")

    def resendRevocation = {
      // let's see the state of remote sigs
      if (commitments1.localCommit.index == channelReestablish.nextRemoteRevocationNumber) {
        // nothing to do
      } else if (commitments1.localCommit.index == channelReestablish.nextRemoteRevocationNumber + 1) {
        // our last revocation got lost, let's resend it
        log.debug(s"re-sending last revocation")
        val localPerCommitmentSecret = Generators.perCommitSecret(commitments1.localParams.shaSeed, d.commitments.localCommit.index - 1)
        val localNextPerCommitmentPoint = Generators.perCommitPoint(commitments1.localParams.shaSeed, d.commitments.localCommit.index + 1)
        val revocation = RevokeAndAck(
          channelId = commitments1.channelId,
          perCommitmentSecret = localPerCommitmentSecret,
          nextPerCommitmentPoint = localNextPerCommitmentPoint
        )
        forwarder ! revocation
      } else throw RevocationSyncError
    }

    // re-sending sig/rev (in the right order)
    val htlcsIn = commitments1.remoteNextCommitInfo match {
      case Left(waitingForRevocation) if waitingForRevocation.nextRemoteCommit.index + 1 == channelReestablish.nextLocalCommitmentNumber =>
        // we had sent a new sig and were waiting for their revocation
        // they had received the new sig but their revocation was lost during the disconnection
        // they will send us the revocation, nothing to do here
        log.debug(s"waiting for them to re-send their last revocation")
        resendRevocation
      case Left(waitingForRevocation) if waitingForRevocation.nextRemoteCommit.index == channelReestablish.nextLocalCommitmentNumber =>
        // we had sent a new sig and were waiting for their revocation
        // they didn't receive the new sig because of the disconnection
        // we just resend the same updates and the same sig

        val revWasSentLast = commitments1.localCommit.index > waitingForRevocation.sentAfterLocalCommitIndex
        if (!revWasSentLast) resendRevocation

        log.debug(s"re-sending previously local signed changes: ${commitments1.localChanges.signed.map(Commitments.msg2String(_)).mkString(",")}")
        commitments1.localChanges.signed.foreach(forwarder ! _)
        log.debug(s"re-sending the exact same previous sig")
        forwarder ! waitingForRevocation.sent

        if (revWasSentLast) resendRevocation
      case Right(_) if commitments1.remoteCommit.index + 1 == channelReestablish.nextLocalCommitmentNumber =>
        // there wasn't any sig in-flight when the disconnection occured
        resendRevocation
      case _ => throw CommitmentSyncError
    }

    // let's now fail all pending htlc for which we are the final payee
    val htlcsToFail = commitments1.remoteCommit.spec.htlcs.collect {
      case Htlc(OUT, add, _) => add
    }.filter {
      case add =>
        Try(Sphinx.parsePacket(nodeParams.privateKey, add.paymentHash, add.onionRoutingPacket))
          .map(_.nextPacket.isLastPacket)
          .getOrElse(true) // we also fail htlcs which onion we can't decode (message won't be precise)
    }
    log.debug(s"failing htlcs=${htlcsToFail.map(Commitments.msg2String(_)).mkString(",")}")
    htlcsToFail.foreach(add => self ! CMD_FAIL_HTLC(add.id, Right(TemporaryNodeFailure), commit = true))

    // have I something to sign?
    if (Commitments.localHasChanges(commitments1)) {
      self ! CMD_SIGN
    }

    commitments1
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
          case d: Data =>
            log.error(t, "")
            val error = Error(Helpers.getChannelId(d), t.getMessage.getBytes)
            goto(CLOSED) sending error
        }
      }
  }

  def store[T](d: T)(implicit tp: T <:< HasCommitments): T = {
    log.debug(s"updating database record for channelId=${d.channelId}")
    nodeParams.channelsDb.addOrUpdateChannel(d)
    d
  }

  implicit def state2mystate(state: FSM.State[fr.acinq.eclair.channel.State, Data]): MyState = MyState(state)

  case class MyState(state: FSM.State[fr.acinq.eclair.channel.State, Data]) {

    def sending(msgs: Seq[LightningMessage]): FSM.State[fr.acinq.eclair.channel.State, Data] = {
      msgs.foreach(forwarder ! _)
      state
    }

    def sending(msg: LightningMessage): FSM.State[fr.acinq.eclair.channel.State, Data] = {
      forwarder ! msg
      state
    }

    //    def storing(): FSM.State[fr.acinq.eclair.channel.State, Data] = {
    //      state.stateData match {
    //        case d: HasCommitments =>
    //          log.debug(s"updating database record for channelId=${d.channelId} (state=$state)")
    //          nodeParams.channelsDb.put(d.channelId, d)
    //        case _ => {}
    //      }
    //      state
    //    }
  }

  override def mdc(currentMessage: Any): MDC = {
    val id = Helpers.getChannelId(stateData)
    Map("channelId" -> id)
  }

  // we let the peer decide what to do
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Escalate }

  initialize()

}




