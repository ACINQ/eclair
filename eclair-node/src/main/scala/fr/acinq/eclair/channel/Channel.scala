package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM, LoggingFSM, Props}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.peer.CurrentBlockCount
import fr.acinq.eclair.channel.Helpers.{Closing, Funding}
import fr.acinq.eclair.crypto.TransportHandler.Serializer
import fr.acinq.eclair.crypto.{Generators, ShaChain}
import fr.acinq.eclair.db.{ChannelState, JavaSerializer, SimpleDb, SimpleTypedDb}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire._

import scala.compat.Platform
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Left, Success, Try}

case class ChannelRecord(id: Long, state: ChannelState)

/**
  * Created by PM on 20/08/2015.
  */

object Channel {
  def props(nodeParams: NodeParams, remote: ActorRef, blockchain: ActorRef, router: ActorRef, relayer: ActorRef) = Props(new Channel(nodeParams, remote, blockchain, router, relayer))

  def makeChannelDb(db: SimpleDb): SimpleTypedDb[Long, ChannelRecord] = {
    def channelid2String(id: Long) = s"channel-$id"

    def string2channelid(s: String) = if (s.startsWith("channel-")) Some(s.stripPrefix("channel-").toLong) else None

    new SimpleTypedDb[Long, ChannelRecord](
      channelid2String,
      string2channelid,
      new Serializer[ChannelRecord] {
        override def serialize(t: ChannelRecord): BinaryData = JavaSerializer.serialize(t)

        override def deserialize(bin: BinaryData): ChannelRecord = JavaSerializer.deserialize[ChannelRecord](bin)
      },
      db
    )
  }
}

class Channel(nodeParams: NodeParams, val r: ActorRef, val blockchain: ActorRef, router: ActorRef, relayer: ActorRef)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends LoggingFSM[State, Data] {

  import Channel._

  val forwarder = context.actorOf(Props(new Forwarder(nodeParams)), "forwarder")

  var remote = r
  var remoteNodeId: PublicKey = null

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
    case Event(initFunder@INPUT_INIT_FUNDER(remoteNodeId, temporaryChannelId, fundingSatoshis, pushMsat, localParams, remoteInit), Nothing) =>
      this.remoteNodeId = remoteNodeId
      context.system.eventStream.publish(ChannelCreated(self, context.parent, remoteNodeId, true, temporaryChannelId))
      val firstPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, 0)
      val open = OpenChannel(temporaryChannelId = temporaryChannelId,
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
      remote ! open
      goto(WAIT_FOR_ACCEPT_CHANNEL) using DATA_WAIT_FOR_ACCEPT_CHANNEL(initFunder, open)

    case Event(inputFundee@INPUT_INIT_FUNDEE(remoteNodeId, _, localParams, _), Nothing) if !localParams.isFunder =>
      this.remoteNodeId = remoteNodeId
      goto(WAIT_FOR_OPEN_CHANNEL) using DATA_WAIT_FOR_OPEN_CHANNEL(inputFundee)

    case Event(INPUT_RESTORED(channelId, cs@ChannelState(remotePubKey, state, data)), _) =>
      log.info(s"restoring channel $cs")
      remoteNodeId = remotePubKey
      context.system.eventStream.publish(ChannelRestored(self, context.parent, remoteNodeId, Helpers.getLocalParams(data).isFunder, Helpers.getChannelId(data), data))
      data match {
        // no need to go OFFLINE, we can directly switch to CLOSING
        case closing: DATA_CLOSING if closing.localCommitPublished.isDefined =>
          spendLocalCurrent(closing)

        case closing: DATA_CLOSING if closing.mutualClosePublished.isDefined =>
          publishMutualClosing(closing.mutualClosePublished.get)
          goto(CLOSING) using closing

        case closing: DATA_CLOSING if closing.remoteCommitPublished.isDefined =>
          handleRemoteSpentCurrent(closing.remoteCommitPublished.get.commitTx, closing)
          goto(CLOSING) using closing

        case closing: DATA_CLOSING if closing.nextRemoteCommitPublished.isDefined =>
          handleRemoteSpentNext(closing.nextRemoteCommitPublished.get.commitTx, closing)
          goto(CLOSING) using closing

        case closing: DATA_CLOSING if !closing.revokedCommitPublished.isEmpty =>
          closing.revokedCommitPublished.map(revokedCommitPublished => {
            handleRemoteSpentOther(revokedCommitPublished.commitTx, closing)
          })
          goto(CLOSING) using closing

        case closing: DATA_CLOSING =>
          goto(CLOSING) using closing

        case d: HasCommitments =>
          blockchain ! WatchSpent(self, d.commitments.commitInput.outPoint.txid, d.commitments.commitInput.outPoint.index.toInt, BITCOIN_FUNDING_SPENT) // TODO: should we wait for an acknowledgment from the watcher?
          Register.createAlias(remoteNodeId, d.commitments.channelId)
          goto(OFFLINE) using data

        case unexpected => log.error(s"restoring channel with unexpected state: $unexpected")
          goto(OFFLINE) using unexpected
      }
  })

  when(WAIT_FOR_OPEN_CHANNEL)(handleExceptions {
    case Event(open: OpenChannel, DATA_WAIT_FOR_OPEN_CHANNEL(INPUT_INIT_FUNDEE(_, _, localParams, remoteInit))) =>
      Try(Helpers.validateParams(nodeParams, open.channelReserveSatoshis, open.fundingSatoshis)) match {
        case Failure(t) =>
          log.warning(t.getMessage)
          remote ! Error(open.temporaryChannelId, t.getMessage.getBytes)
          goto(CLOSED)
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
            revocationBasepoint = localParams.revocationSecret.toPoint,
            paymentBasepoint = localParams.paymentKey.toPoint,
            delayedPaymentBasepoint = localParams.delayedPaymentKey.toPoint,
            firstPerCommitmentPoint = firstPerCommitmentPoint)
          remote ! accept
          val remoteParams = RemoteParams(
            dustLimitSatoshis = open.dustLimitSatoshis,
            maxHtlcValueInFlightMsat = open.maxHtlcValueInFlightMsat,
            channelReserveSatoshis = open.channelReserveSatoshis, // remote requires local to keep this much satoshis as direct payment
            htlcMinimumMsat = open.htlcMinimumMsat,
            feeratePerKw = open.feeratePerKw,
            toSelfDelay = open.toSelfDelay,
            maxAcceptedHtlcs = open.maxAcceptedHtlcs,
            fundingPubKey = open.fundingPubkey,
            revocationBasepoint = open.revocationBasepoint,
            paymentBasepoint = open.paymentBasepoint,
            delayedPaymentBasepoint = open.delayedPaymentBasepoint,
            globalFeatures = remoteInit.globalFeatures,
            localFeatures = remoteInit.localFeatures)
          log.debug(s"remote params: $remoteParams")
          val localParams1 = localParams.copy(feeratePerKw = open.feeratePerKw) // funder gets to choose the first feerate
          goto(WAIT_FOR_FUNDING_CREATED) using DATA_WAIT_FOR_FUNDING_CREATED(open.temporaryChannelId, localParams1, remoteParams, open.fundingSatoshis, open.pushMsat, open.firstPerCommitmentPoint, accept)
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) => handleRemoteErrorNoCommitments(e)
  })

  when(WAIT_FOR_ACCEPT_CHANNEL)(handleExceptions {
    case Event(accept: AcceptChannel, DATA_WAIT_FOR_ACCEPT_CHANNEL(INPUT_INIT_FUNDER(_, temporaryChannelId, fundingSatoshis, pushMsat, localParams, remoteInit), open)) =>
      Try(Helpers.validateParams(nodeParams, accept.channelReserveSatoshis, fundingSatoshis)) match {
        case Failure(t) =>
          log.warning(t.getMessage)
          remote ! Error(temporaryChannelId, t.getMessage.getBytes)
          goto(CLOSED)
        case _ =>
          // TODO: check equality of temporaryChannelId? or should be done upstream
          val remoteParams = RemoteParams(
            dustLimitSatoshis = accept.dustLimitSatoshis,
            maxHtlcValueInFlightMsat = accept.maxHtlcValueInFlightMsat,
            channelReserveSatoshis = accept.channelReserveSatoshis, // remote requires local to keep this much satoshis as direct payment
            htlcMinimumMsat = accept.htlcMinimumMsat,
            feeratePerKw = localParams.feeratePerKw, // funder gets to choose the first feerate
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
          blockchain ! MakeFundingTx(localFundingPubkey, remoteParams.fundingPubKey, Satoshi(fundingSatoshis))
          goto(WAIT_FOR_FUNDING_INTERNAL) using DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, localParams, remoteParams, fundingSatoshis, pushMsat, accept.firstPerCommitmentPoint, open)
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) => handleRemoteErrorNoCommitments(e)
  })

  when(WAIT_FOR_FUNDING_INTERNAL)(handleExceptions {
    case Event(MakeFundingTxResponse(fundingTx: Transaction, fundingTxOutputIndex: Int), DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, localParams, remoteParams, fundingSatoshis, pushMsat, remoteFirstPerCommitmentPoint, _)) =>
      // our wallet provided us with a funding tx
      log.info(s"funding tx txid=${fundingTx.txid}")

      // let's create the first commitment tx that spends the yet uncommitted funding tx
      val (localSpec, localCommitTx, remoteSpec, remoteCommitTx) = Funding.makeFirstCommitTxs(localParams, remoteParams, fundingSatoshis, pushMsat, fundingTx.hash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint)

      val localSigOfRemoteTx = Transactions.sign(remoteCommitTx, localParams.fundingPrivKey)
      // signature of their initial commitment tx that pays remote pushMsat
      val fundingCreated = FundingCreated(
        temporaryChannelId = temporaryChannelId,
        txid = fundingTx.hash,
        outputIndex = fundingTxOutputIndex,
        signature = localSigOfRemoteTx
      )
      remote ! fundingCreated
      goto(WAIT_FOR_FUNDING_SIGNED) using DATA_WAIT_FOR_FUNDING_SIGNED(temporaryChannelId, localParams, remoteParams, fundingTx, localSpec, localCommitTx, RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint), fundingCreated)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) => handleRemoteErrorNoCommitments(e)
  })

  when(WAIT_FOR_FUNDING_CREATED)(handleExceptions {
    case Event(FundingCreated(_, fundingTxHash, fundingTxOutputIndex, remoteSig), DATA_WAIT_FOR_FUNDING_CREATED(temporaryChannelId, localParams, remoteParams, fundingSatoshis: Long, pushMsat, remoteFirstPerCommitmentPoint, _)) =>
      // they fund the channel with their funding tx, so the money is theirs (but we are paid pushMsat)
      val (localSpec, localCommitTx, remoteSpec, remoteCommitTx) = Funding.makeFirstCommitTxs(localParams, remoteParams, fundingSatoshis: Long, pushMsat, fundingTxHash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint)

      // check remote signature validity
      val localSigOfLocalTx = Transactions.sign(localCommitTx, localParams.fundingPrivKey)
      val signedLocalCommitTx = Transactions.addSigs(localCommitTx, localParams.fundingPrivKey.publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
      Transactions.checkSpendable(signedLocalCommitTx) match {
        case Failure(cause) =>
          log.error(cause, "their FundingCreated message contains an invalid signature")
          remote ! Error(temporaryChannelId, cause.getMessage.getBytes)
          // we haven't anything at stake yet, we can just stop
          goto(CLOSED)
        case Success(_) =>
          log.info(s"signing remote tx: $remoteCommitTx")
          val localSigOfRemoteTx = Transactions.sign(remoteCommitTx, localParams.fundingPrivKey)
          val fundingSigned = FundingSigned(
            temporaryChannelId = temporaryChannelId,
            signature = localSigOfRemoteTx
          )
          remote ! fundingSigned

          // watch the funding tx transaction
          val commitInput = localCommitTx.input
          blockchain ! WatchSpent(self, commitInput.outPoint.txid, commitInput.outPoint.index.toInt, BITCOIN_FUNDING_SPENT) // TODO: should we wait for an acknowledgment from the watcher?
          blockchain ! WatchConfirmed(self, commitInput.outPoint.txid, nodeParams.minDepthBlocks, BITCOIN_FUNDING_DEPTHOK)

          val commitments = Commitments(localParams, remoteParams,
            LocalCommit(0, localSpec, PublishableTxs(signedLocalCommitTx, Nil), null), RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint),
            LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
            localNextHtlcId = 0L, remoteNextHtlcId = 0L,
            remoteNextCommitInfo = Right(null), // TODO: we will receive their next per-commitment point in the next message, so we temporarily put an empty byte array,
            unackedMessages = Nil,
            commitInput, ShaChain.init, channelId = temporaryChannelId) // TODO: we will compute the channelId at the next step, so we temporarily put 0
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
          goto(WAIT_FOR_FUNDING_CONFIRMED) using DATA_WAIT_FOR_FUNDING_CONFIRMED(temporaryChannelId, commitments, None, Right(fundingSigned))
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) => handleRemoteErrorNoCommitments(e)
  })

  when(WAIT_FOR_FUNDING_SIGNED)(handleExceptions {
    case Event(FundingSigned(_, remoteSig), DATA_WAIT_FOR_FUNDING_SIGNED(temporaryChannelId, localParams, remoteParams, fundingTx, localSpec, localCommitTx, remoteCommit, fundingCreated)) =>
      // we make sure that their sig checks out and that our first commit tx is spendable
      val localSigOfLocalTx = Transactions.sign(localCommitTx, localParams.fundingPrivKey)
      val signedLocalCommitTx = Transactions.addSigs(localCommitTx, localParams.fundingPrivKey.publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
      Transactions.checkSpendable(signedLocalCommitTx) match {
        case Failure(cause) =>
          log.error(cause, "their FundingSigned message contains an invalid signature")
          remote ! Error(temporaryChannelId, cause.getMessage.getBytes)
          // we haven't published anything yet, we can just stop
          goto(CLOSED)
        case Success(_) =>
          val commitInput = localCommitTx.input
          blockchain ! WatchSpent(self, commitInput.outPoint.txid, commitInput.outPoint.index.toInt, BITCOIN_FUNDING_SPENT) // TODO: should we wait for an acknowledgment from the watcher?
          blockchain ! WatchConfirmed(self, commitInput.outPoint.txid, nodeParams.minDepthBlocks, BITCOIN_FUNDING_DEPTHOK)
          blockchain ! PublishAsap(fundingTx)

          val commitments = Commitments(localParams, remoteParams,
            LocalCommit(0, localSpec, PublishableTxs(signedLocalCommitTx, Nil), null), remoteCommit,
            LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
            localNextHtlcId = 0L, remoteNextHtlcId = 0L,
            remoteNextCommitInfo = Right(null), // TODO: we will receive their next per-commitment point in the next message, so we temporarily put an empty byte array
            unackedMessages = Nil,
            commitInput, ShaChain.init, channelId = temporaryChannelId)
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
          goto(WAIT_FOR_FUNDING_CONFIRMED) using DATA_WAIT_FOR_FUNDING_CONFIRMED(temporaryChannelId, commitments, None, Left(fundingCreated))
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)

    case Event(e: Error, _) => handleRemoteErrorNoCommitments(e)
  })

  when(WAIT_FOR_FUNDING_CONFIRMED)(handleExceptions {
    case Event(msg: FundingLocked, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      log.info(s"received their FundingLocked, deferring message")
      goto(stateName) using d.copy(deferred = Some(msg))

    case Event(WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, blockHeight, txIndex), DATA_WAIT_FOR_FUNDING_CONFIRMED(temporaryChannelId, commitments, deferred, lastSent)) =>
      val channelId = toShortId(blockHeight, txIndex, commitments.commitInput.outPoint.index.toInt)
      blockchain ! WatchLost(self, commitments.anchorId, nodeParams.minDepthBlocks, BITCOIN_FUNDING_LOST)
      val nextPerCommitmentPoint = Generators.perCommitPoint(commitments.localParams.shaSeed, 1)
      val fundingLocked = FundingLocked(temporaryChannelId, channelId, nextPerCommitmentPoint)
      deferred.map(self ! _)
      remote ! fundingLocked
      // TODO: htlcIdx should not be 0 when resuming connection
      goto(WAIT_FOR_FUNDING_LOCKED) using DATA_WAIT_FOR_FUNDING_LOCKED(commitments.copy(channelId = channelId), fundingLocked)

    // TODO: not implemented, maybe should be done with a state timer and not a blockchain watch?
    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      remote ! Error(d.channelId, "Funding tx timed out".getBytes)
      goto(CLOSED)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, _), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleInformationLeak(d)

    case Event(CMD_CLOSE(_), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => spendLocalCurrent(d)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleRemoteError(e, d)
  })

  when(WAIT_FOR_FUNDING_LOCKED)(handleExceptions {
    case Event(FundingLocked(_, remoteChannelId, _), d: DATA_WAIT_FOR_FUNDING_LOCKED) if remoteChannelId != d.channelId =>
      // TODO: channel id mismatch, can happen if minDepth is to low, negotiation not suported yet
      handleLocalError(new RuntimeException(s"channel id mismatch local=${d.channelId} remote=$remoteChannelId"), d)

    case Event(FundingLocked(_, _, nextPerCommitmentPoint), d@DATA_WAIT_FOR_FUNDING_LOCKED(commitments, _)) =>
      log.info(s"channelId=${java.lang.Long.toUnsignedString(d.channelId)}")
      Register.createAlias(remoteNodeId, d.channelId)
      context.system.eventStream.publish(ChannelIdAssigned(self, d.channelId))
      // this clock will be used to detect htlc timeouts
      context.system.eventStream.subscribe(self, classOf[CurrentBlockCount])
      if (Funding.announceChannel(commitments.localParams.localFeatures, commitments.remoteParams.localFeatures)) {
        val (localNodeSig, localBitcoinSig) = Announcements.signChannelAnnouncement(d.channelId, nodeParams.privateKey, remoteNodeId, commitments.localParams.fundingPrivKey, commitments.remoteParams.fundingPubKey)
        val annSignatures = AnnouncementSignatures(d.channelId, localNodeSig, localBitcoinSig)
        // FD remote ! annSignatures
        goto(WAIT_FOR_ANN_SIGNATURES) using DATA_WAIT_FOR_ANN_SIGNATURES(commitments.copy(remoteNextCommitInfo = Right(nextPerCommitmentPoint)).addToUnackedMessages(annSignatures), annSignatures)
      } else {
        log.info(s"channel ${d.channelId} won't be announced")
        goto(NORMAL) using DATA_NORMAL(commitments.copy(remoteNextCommitInfo = Right(nextPerCommitmentPoint)), None)
      }

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_WAIT_FOR_FUNDING_LOCKED) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, _), d: DATA_WAIT_FOR_FUNDING_LOCKED) => handleInformationLeak(d)

    case Event(CMD_CLOSE(_), d: DATA_WAIT_FOR_FUNDING_LOCKED) => spendLocalCurrent(d)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_LOCKED) => handleRemoteError(e, d)
  })

  when(WAIT_FOR_ANN_SIGNATURES)(handleExceptions {
    case Event(AnnouncementSignatures(_, remoteNodeSig, remoteBitcoinSig), d@DATA_WAIT_FOR_ANN_SIGNATURES(commitments, _)) =>
      log.info(s"announcing channel ${d.channelId} on the network")
      val (localNodeSig, localBitcoinSig) = Announcements.signChannelAnnouncement(d.channelId, nodeParams.privateKey, remoteNodeId, commitments.localParams.fundingPrivKey, commitments.remoteParams.fundingPubKey)
      val channelAnn = Announcements.makeChannelAnnouncement(d.channelId, nodeParams.privateKey.publicKey, remoteNodeId, commitments.localParams.fundingPrivKey.publicKey, commitments.remoteParams.fundingPubKey, localNodeSig, remoteNodeSig, localBitcoinSig, remoteBitcoinSig)
      val nodeAnn = Announcements.makeNodeAnnouncement(nodeParams.privateKey, nodeParams.alias, nodeParams.color, nodeParams.address :: Nil, Platform.currentTime / 1000)
      val channelUpdate = Announcements.makeChannelUpdate(nodeParams.privateKey, remoteNodeId, d.commitments.channelId, nodeParams.expiryDeltaBlocks, nodeParams.htlcMinimumMsat, nodeParams.feeBaseMsat, nodeParams.feeProportionalMillionth, Platform.currentTime / 1000)
      router ! channelAnn
      router ! nodeAnn
      router ! channelUpdate
      // let's trigger the broadcast immediately so that we don't wait for 60 seconds to announce our newly created channel
      // we give 3 seconds for the router-watcher roundtrip
      context.system.scheduler.scheduleOnce(3 seconds, router, 'tick_broadcast)
      goto(NORMAL) using DATA_NORMAL(commitments, None)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_WAIT_FOR_ANN_SIGNATURES) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, _), d: DATA_WAIT_FOR_ANN_SIGNATURES) => handleInformationLeak(d)

    case Event(CMD_CLOSE(_), d: DATA_WAIT_FOR_ANN_SIGNATURES) => spendLocalCurrent(d)

    case Event(e: Error, d: DATA_WAIT_FOR_ANN_SIGNATURES) => handleRemoteError(e, d)
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

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) if d.unackedShutdown.isDefined =>
      handleCommandError(sender, new RuntimeException("cannot send new htlcs, closing in progress"))

    case Event(c@CMD_ADD_HTLC(amountMsat, rHash, expiry, route, downstream_opt, do_commit), d@DATA_NORMAL(commitments, _)) =>
      Try(Commitments.sendAdd(commitments, c)) match {
        case Success((commitments1, add)) =>
          val origin = downstream_opt.map(Relayed(_)).getOrElse(Local(sender))
          relayer ! Bind(add, origin)
          if (do_commit) self ! CMD_SIGN
          handleCommandSuccess(sender, add, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(add: UpdateAddHtlc, d@DATA_NORMAL(commitments, _)) =>
      Try(Commitments.receiveAdd(commitments, add)) match {
        case Success(commitments1) => goto(stateName) using d.copy(commitments = commitments1)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(c@CMD_FULFILL_HTLC(id, r, do_commit), d: DATA_NORMAL) =>
      Try(Commitments.sendFulfill(d.commitments, c)) match {
        case Success((commitments1, fulfill)) =>
          if (do_commit) self ! CMD_SIGN
          handleCommandSuccess(sender, fulfill, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(fulfill@UpdateFulfillHtlc(_, id, r), d@DATA_NORMAL(commitments, _)) =>
      Try(Commitments.receiveFulfill(d.commitments, fulfill)) match {
        case Success(Right(commitments1)) =>
          relayer ! ForwardFulfill(fulfill)
          goto(stateName) using d.copy(commitments = commitments1)
        case Success(Left(_)) => goto(stateName)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(c@CMD_FAIL_HTLC(id, reason, do_commit), d: DATA_NORMAL) =>
      Try(Commitments.sendFail(d.commitments, c)) match {
        case Success((commitments1, fail)) =>
          if (do_commit) self ! CMD_SIGN
          handleCommandSuccess(sender, fail, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(fail@UpdateFailHtlc(_, id, reason), d@DATA_NORMAL(_, _)) =>
      Try(Commitments.receiveFail(d.commitments, fail)) match {
        case Success(Right(commitments1)) =>
          relayer ! ForwardFail(fail)
          goto(stateName) using d.copy(commitments = commitments1)
        case Success(Left(_)) => goto(stateName)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(CMD_SIGN, d: DATA_NORMAL) =>
      d.commitments.remoteNextCommitInfo match {
        case _ if !Commitments.localHasChanges(d.commitments) =>
          log.info("ignoring CMD_SIGN (nothing to sign)")
          goto(stateName)
        case Right(_) =>
          Try(Commitments.sendCommit(d.commitments)) match {
            case Success((commitments1, commit)) =>
              log.debug(s"sending a new sig, spec:\n${Commitments.specs2String(commitments1)}")
              handleCommandSuccess(sender, commit, d.copy(commitments = commitments1))
            case Failure(cause) => handleCommandError(sender, cause)
          }
        case Left(waitForRevocation) =>
          log.debug(s"already in the process of signing, will sign again as soon as possible")
          goto(stateName) using d.copy(commitments = d.commitments.copy(remoteNextCommitInfo = Left(waitForRevocation.copy(reSignAsap = true))))
      }

    case Event(commit@CommitSig(_, theirSig, theirHtlcSigs), d: DATA_NORMAL) =>
      Try(Commitments.receiveCommit(d.commitments, commit)) match {
        case Success(Right((commitments1, revocation))) =>
          // FD remote ! revocation
          log.debug(s"received a new sig, spec:\n${Commitments.specs2String(commitments1)}")
          if (Commitments.localHasChanges(commitments1)) {
            // if we have newly acknowledged changes let's sign them
            self ! CMD_SIGN
          }
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments1))
          goto(stateName) using d.copy(commitments = commitments1)
        case Success(Left(_)) =>
          // this was an old commit, nothing to do
          goto(stateName)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(revocation: RevokeAndAck, d: DATA_NORMAL) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked
      Try(Commitments.receiveRevocation(d.commitments, revocation)) match {
        case Success(Right(commitments1)) =>
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
          goto(stateName) using d.copy(commitments = commitments1)
        case Success(Left(_)) =>
          // this was an old revocation, nothing to do
          goto(stateName)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(CMD_CLOSE(ourScriptPubKey_opt), d: DATA_NORMAL) if d.unackedShutdown.isDefined =>
      handleCommandError(sender, new RuntimeException("closing already in progress"))

    case Event(c@CMD_CLOSE(ourScriptPubKey_opt), d: DATA_NORMAL) if Commitments.localHasChanges(d.commitments) =>
      // TODO: simplistic behavior, we could maybe sign+close
      handleCommandError(sender, new RuntimeException("cannot close when there are pending changes"))

    case Event(CMD_CLOSE(ourScriptPubKey_opt), d: DATA_NORMAL) =>
      ourScriptPubKey_opt.getOrElse(d.commitments.localParams.defaultFinalScriptPubKey) match {
        case finalScriptPubKey if Closing.isValidFinalScriptPubkey(finalScriptPubKey) =>
          val localShutdown = Shutdown(d.channelId, finalScriptPubKey)
          handleCommandSuccess(sender, localShutdown, d.copy(unackedShutdown = Some(localShutdown), commitments = d.commitments.copy(unackedMessages = d.commitments.unackedMessages :+ localShutdown)))
        case _ => handleCommandError(sender, new RuntimeException("invalid final script"))
      }

    case Event(remoteShutdown@Shutdown(_, remoteScriptPubKey), d@DATA_NORMAL(commitments, ourShutdownOpt)) if commitments.remoteChanges.proposed.size > 0 =>
      handleLocalError(new RuntimeException("it is illegal to send a shutdown while having unsigned changes"), d)

    case Event(remoteShutdown@Shutdown(_, remoteScriptPubKey), d@DATA_NORMAL(commitments, ourShutdownOpt)) =>
      Try(ourShutdownOpt.map(s => (s, commitments)).getOrElse {
        require(Closing.isValidFinalScriptPubkey(remoteScriptPubKey), "invalid final script")
        // first if we have pending changes, we need to commit them
        val commitments2 = if (Commitments.localHasChanges(commitments)) {
          val (commitments1, commit) = Commitments.sendCommit(d.commitments)
          // FD remote ! commit
          commitments1
        } else commitments
        val shutdown = Shutdown(d.channelId, commitments.localParams.defaultFinalScriptPubKey)
        // FD remote ! shutdown
        (shutdown, commitments2.copy(unackedMessages = commitments2.unackedMessages :+ shutdown))
      }) match {
        case Success((localShutdown, commitments3))
          if (commitments3.remoteNextCommitInfo.isRight && commitments3.localCommit.spec.htlcs.size == 0 && commitments3.localCommit.spec.htlcs.size == 0)
            || (commitments3.remoteNextCommitInfo.isLeft && commitments3.localCommit.spec.htlcs.size == 0 && commitments3.remoteNextCommitInfo.left.get.nextRemoteCommit.spec.htlcs.size == 0) =>
          val closingSigned = Closing.makeFirstClosingTx(commitments3, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey)
          // FD remote ! closingSigned
          goto(NEGOTIATING) using DATA_NEGOTIATING(commitments3.copy(unackedMessages = commitments3.unackedMessages :+ closingSigned), localShutdown, remoteShutdown, closingSigned)
        case Success((localShutdown, commitments3)) =>
          goto(SHUTDOWN) using DATA_SHUTDOWN(commitments3, localShutdown, remoteShutdown)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(CurrentBlockCount(count), d: DATA_NORMAL) if d.commitments.hasTimedoutHtlcs(count) =>
      handleLocalError(new RuntimeException(s"one or more htlcs timedout at blockheight=$count, closing the channel"), d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NORMAL) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NORMAL) if Some(tx.txid) == d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NORMAL) => handleRemoteSpentOther(tx, d)

    case Event(e: Error, d: DATA_NORMAL) => handleRemoteError(e, d)

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

    case Event(c@CMD_FULFILL_HTLC(id, r, do_commit), d: DATA_SHUTDOWN) =>
      Try(Commitments.sendFulfill(d.commitments, c)) match {
        case Success((commitments1, fulfill)) => handleCommandSuccess(sender, fulfill, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(fulfill@UpdateFulfillHtlc(_, id, r), d: DATA_SHUTDOWN) =>
      Try(Commitments.receiveFulfill(d.commitments, fulfill)) match {
        case Success(Right(commitments1)) =>
          relayer ! ForwardFulfill(fulfill)
          goto(stateName) using d.copy(commitments = commitments1)
        case Success(Left(_)) => goto(stateName)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(c@CMD_FAIL_HTLC(id, reason, do_commit), d: DATA_SHUTDOWN) =>
      Try(Commitments.sendFail(d.commitments, c)) match {
        case Success((commitments1, fail)) => handleCommandSuccess(sender, fail, d.copy(commitments = commitments1))
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(fail@UpdateFailHtlc(_, id, reason), d: DATA_SHUTDOWN) =>
      Try(Commitments.receiveFail(d.commitments, fail)) match {
        case Success(Right(commitments1)) =>
          relayer ! ForwardFail(fail)
          goto(stateName) using d.copy(commitments = commitments1)
        case Success(Left(_)) => goto(stateName)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(CMD_SIGN, d: DATA_SHUTDOWN) =>
      d.commitments.remoteNextCommitInfo match {
        case _ if !Commitments.localHasChanges(d.commitments) =>
          log.info("ignoring CMD_SIGN (nothing to sign)")
          goto(stateName)
        case Right(_) =>
          Try(Commitments.sendCommit(d.commitments)) match {
            case Success((commitments1, commit)) =>
              log.debug(s"sending a new sig, spec:\n${Commitments.specs2String(commitments1)}")
              handleCommandSuccess(sender, commit, d.copy(commitments = commitments1))
            case Failure(cause) => handleCommandError(sender, cause)
          }
        case Left(waitForRevocation) =>
          log.debug(s"already in the process of signing, will sign again as soon as possible")
          goto(stateName) using d.copy(commitments = d.commitments.copy(remoteNextCommitInfo = Left(waitForRevocation.copy(reSignAsap = true))))
      }

    case Event(msg@CommitSig(_, theirSig, theirHtlcSigs), d@DATA_SHUTDOWN(commitments, localShutdown, remoteShutdown)) =>
      // TODO: we might have to propagate htlcs upstream depending on the outcome of https://github.com/ElementsProject/lightning/issues/29
      Try(Commitments.receiveCommit(d.commitments, msg)) match {
        case Success(Right((commitments1, revocation))) if commitments1.hasNoPendingHtlcs =>
          // FD remote ! revocation
          val closingSigned = Closing.makeFirstClosingTx(commitments1, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey)
          // FD remote ! closingSigned
          log.debug(s"received a new sig, switching to NEGOTIATING spec:\n${Commitments.specs2String(commitments1)}")
          goto(NEGOTIATING) using DATA_NEGOTIATING(commitments1, localShutdown, remoteShutdown, closingSigned)
        case Success(Right((commitments1, revocation))) =>
          // FD remote ! revocation
          if (Commitments.localHasChanges(commitments1)) {
            // if we have newly acknowledged changes let's sign them
            self ! CMD_SIGN
          }
          log.debug(s"received a new sig, spec:\n${Commitments.specs2String(commitments1)}")
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments1))
          goto(stateName) using d.copy(commitments = commitments1)
        case Success(Left(_)) =>
          // this was an old commit, nothing to do
          goto(stateName)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(msg: RevokeAndAck, d@DATA_SHUTDOWN(commitments, localShutdown, remoteShutdown)) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked
      Try(Commitments.receiveRevocation(d.commitments, msg)) match {
        case Success(Right(commitments1)) if commitments1.hasNoPendingHtlcs =>
          val closingSigned = Closing.makeFirstClosingTx(commitments1, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey)

          // FD remote ! closingSigned
          log.debug(s"received a new rev, switching to NEGOTIATING spec:\n${Commitments.specs2String(commitments1)}")
          goto(NEGOTIATING) using DATA_NEGOTIATING(commitments1.copy(unackedMessages = commitments1.unackedMessages :+ closingSigned), localShutdown, remoteShutdown, closingSigned)
        case Success(Right(commitments1)) =>
          if (Commitments.localHasChanges(commitments1) && d.commitments.remoteNextCommitInfo.left.map(_.reSignAsap) == Left(true)) {
            self ! CMD_SIGN
          }
          log.debug(s"received a new rev, spec:\n${Commitments.specs2String(commitments1)}")
          goto(stateName) using d.copy(commitments = commitments1)
        case Success(Left(_)) =>
          // this was an old revocation, nothing to do
          goto(stateName)
        case Failure(cause) => handleLocalError(cause, d)
      }

    case Event(CurrentBlockCount(count), d: DATA_SHUTDOWN) if d.commitments.hasTimedoutHtlcs(count) =>
      handleLocalError(new RuntimeException(s"one or more htlcs timedout at blockheight=$count, closing the channel"), d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_SHUTDOWN) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_SHUTDOWN) if Some(tx.txid) == d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_SHUTDOWN) => handleRemoteSpentOther(tx, d)

    case Event(e: Error, d: DATA_SHUTDOWN) => handleRemoteError(e, d)

  })

  when(NEGOTIATING)(handleExceptions {

    case Event(ClosingSigned(_, remoteClosingFee, remoteSig), d: DATA_NEGOTIATING) if remoteClosingFee == d.localClosingSigned.feeSatoshis =>
      Closing.checkClosingSignature(d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, Satoshi(remoteClosingFee), remoteSig) match {
        case Success(signedClosingTx) =>
          publishMutualClosing(signedClosingTx)
          goto(CLOSING) using DATA_CLOSING(d.commitments, ourSignature = Some(d.localClosingSigned), mutualClosePublished = Some(signedClosingTx))
        case Failure(cause) =>
          log.error(cause, "cannot verify their close signature")
          throw new RuntimeException("cannot verify their close signature", cause)
      }

    case Event(ClosingSigned(_, remoteClosingFee, remoteSig), d: DATA_NEGOTIATING) =>
      Closing.checkClosingSignature(d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, Satoshi(remoteClosingFee), remoteSig) match {
        case Success(signedClosingTx) =>
          val nextClosingFee = Closing.nextClosingFee(Satoshi(d.localClosingSigned.feeSatoshis), Satoshi(remoteClosingFee))
          val (_, closingSigned) = Closing.makeClosingTx(d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nextClosingFee)
          remote ! closingSigned
          if (nextClosingFee == Satoshi(remoteClosingFee)) {
            publishMutualClosing(signedClosingTx)
            goto(CLOSING) using DATA_CLOSING(d.commitments, ourSignature = Some(closingSigned), mutualClosePublished = Some(signedClosingTx))
          } else {
            goto(stateName) using d.copy(localClosingSigned = closingSigned)
          }
        case Failure(cause) =>
          log.error(cause, "cannot verify their close signature")
          throw new RuntimeException("cannot verify their close signature", cause)
      }

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NEGOTIATING) if tx.txid == Closing.makeClosingTx(d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, Satoshi(d.localClosingSigned.feeSatoshis))._1.tx.txid =>
      // happens when we agreed on a closeSig, but we don't know it yet: we receive the watcher notification before their ClosingSigned (which will match ours)
      goto(stateName)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NEGOTIATING) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NEGOTIATING) if Some(tx.txid) == d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_NEGOTIATING) => handleRemoteSpentOther(tx, d)

    case Event(e: Error, d: DATA_NEGOTIATING) => handleRemoteError(e, d)

  })

  when(CLOSING) {

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) if tx.txid == d.commitments.localCommit.publishableTxs.commitTx.tx.txid =>
      // we just initiated a uniclose moments ago and are now receiving the blockchain notification, there is nothing to do
      goto(stateName)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) if Some(tx.txid) == d.mutualClosePublished.map(_.txid) =>
      // we just published a mutual close tx, we are notified but it's alright
      goto(stateName)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) if tx.txid == d.commitments.remoteCommit.txid =>
      // counterparty may attempt to spend its last commit tx at any time
      handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: DATA_CLOSING) =>
      // counterparty may attempt to spend a revoked commit tx at any time
      handleRemoteSpentOther(tx, d)

    case Event(WatchEventConfirmed(BITCOIN_CLOSE_DONE, _, _), d: DATA_CLOSING) if d.mutualClosePublished.isDefined => goto(CLOSED)

    case Event(WatchEventConfirmed(BITCOIN_LOCALCOMMIT_DONE, _, _), d: DATA_CLOSING) if d.localCommitPublished.isDefined => goto(CLOSED)

    case Event(WatchEventConfirmed(BITCOIN_REMOTECOMMIT_DONE, _, _), d: DATA_CLOSING) if d.remoteCommitPublished.isDefined => goto(CLOSED)

    case Event(WatchEventConfirmed(BITCOIN_NEXTREMOTECOMMIT_DONE, _, _), d: DATA_CLOSING) if d.nextRemoteCommitPublished.isDefined => goto(CLOSED)

    case Event(WatchEventConfirmed(BITCOIN_PENALTY_DONE, _, _), d: DATA_CLOSING) if d.revokedCommitPublished.size > 0 => goto(CLOSED)

    case Event(e: Error, d: DATA_CLOSING) => goto(stateName) // nothing to do, there is already a spending tx published
  }

  when(CLOSED, stateTimeout = 10 seconds) {
    case Event(StateTimeout, _) =>
      log.info("shutting down")
      stop(FSM.Normal)
  }

  when(OFFLINE) {
    case Event(INPUT_RECONNECTED(r), d: DATA_WAIT_FOR_OPEN_CHANNEL) =>
      remote = r
      goto(WAIT_FOR_OPEN_CHANNEL)

    case Event(INPUT_RECONNECTED(r), d: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      remote = r
      remote ! d.lastSent
      goto(WAIT_FOR_ACCEPT_CHANNEL)

    case Event(INPUT_RECONNECTED(r), d@DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, localParams, remoteParams, fundingSatoshis, pushMsat, _, lastSent)) =>
      remote = r
      remote ! d.lastSent
      // in this particular case we need to go to previous state because of the internal funding request to our wallet
      // let's rebuild the previous state data
      val remoteInit = Init(remoteParams.globalFeatures, remoteParams.localFeatures)
      val initFunder = INPUT_INIT_FUNDER(remoteNodeId, temporaryChannelId, fundingSatoshis, pushMsat, localParams, remoteInit)
      goto(WAIT_FOR_ACCEPT_CHANNEL) using DATA_WAIT_FOR_ACCEPT_CHANNEL(initFunder, lastSent)

    case Event(INPUT_RECONNECTED(r), d: DATA_WAIT_FOR_FUNDING_CREATED) =>
      remote = r
      remote ! d.lastSent
      goto(WAIT_FOR_FUNDING_CREATED)

    case Event(INPUT_RECONNECTED(r), d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      remote = r
      remote ! d.lastSent
      goto(WAIT_FOR_FUNDING_SIGNED)

    case Event(INPUT_RECONNECTED(r), DATA_WAIT_FOR_FUNDING_CONFIRMED(_, commitments, _, Left(fundingCreated))) =>
      remote = r
      remote ! fundingCreated
      blockchain ! WatchConfirmed(self, commitments.commitInput.outPoint.txid, nodeParams.minDepthBlocks, BITCOIN_FUNDING_DEPTHOK)
      goto(WAIT_FOR_FUNDING_CONFIRMED)

    case Event(INPUT_RECONNECTED(r), DATA_WAIT_FOR_FUNDING_CONFIRMED(_, commitments, _, Right(fundingSigned))) =>
      remote = r
      remote ! fundingSigned
      blockchain ! WatchConfirmed(self, commitments.commitInput.outPoint.txid, nodeParams.minDepthBlocks, BITCOIN_FUNDING_DEPTHOK)
      goto(WAIT_FOR_FUNDING_CONFIRMED)

    case Event(INPUT_RECONNECTED(r), d: DATA_WAIT_FOR_FUNDING_LOCKED) =>
      remote = r
      remote ! d.lastSent
      goto(WAIT_FOR_FUNDING_LOCKED)

    case Event(INPUT_RECONNECTED(r), d: DATA_WAIT_FOR_ANN_SIGNATURES) =>
      remote = r
      remote ! d.lastSent
      goto(WAIT_FOR_ANN_SIGNATURES)

    case Event(INPUT_RECONNECTED(r), d: DATA_NORMAL) if d.commitments.localCommit.index == 0 && d.commitments.remoteCommit.index == 0 && d.commitments.remoteChanges.proposed.size == 0 && d.commitments.remoteNextCommitInfo.isRight =>
      remote = r
      // this is a brand new channel
      if (Funding.announceChannel(d.commitments.localParams.localFeatures, d.commitments.remoteParams.localFeatures)) {
        val (localNodeSig, localBitcoinSig) = Announcements.signChannelAnnouncement(d.channelId, nodeParams.privateKey, remoteNodeId, d.commitments.localParams.fundingPrivKey, d.commitments.remoteParams.fundingPubKey)
        val annSignatures = AnnouncementSignatures(d.channelId, localNodeSig, localBitcoinSig)
        remote ! annSignatures
      } else {
        // TODO: not supported
      }
      goto(NORMAL)

    case Event(INPUT_RECONNECTED(r), d@DATA_NORMAL(commitments, _)) =>
      remote = r
      log.info(s"resuming with ${Commitments.changes2String(commitments)}")
      //val resend = commitments.unackedMessages.filterNot(_.isInstanceOf[RevokeAndAck])
      val resend = commitments.unackedMessages //.filterNot(_.isInstanceOf[RevokeAndAck])
      log.info(s"re-sending: ${resend.map(Commitments.msg2String(_)).mkString(" ")}")
      resend.foreach(remote ! _)
      if (Commitments.localHasChanges(commitments)) {
        // if we have newly acknowledged changes let's sign them
        self ! CMD_SIGN
      }
      goto(NORMAL)

    case Event(INPUT_RECONNECTED(r), d: DATA_NEGOTIATING) =>
      goto(NEGOTIATING) using (d)

    case Event(INPUT_RECONNECTED(r), d: DATA_CLOSING) =>
      goto(CLOSING) using (d)

    case Event(c@CMD_ADD_HTLC(amountMsat, rHash, expiry, route, downstream_opt, do_commit), d@DATA_NORMAL(commitments, _)) =>
      log.info(s"we are disconnected so we just include the add in our commitments")
      Try(Commitments.sendAdd(commitments, c)) match {
        case Success((commitments1, add)) =>
          val origin = downstream_opt.map(Relayed(_)).getOrElse(Local(sender))
          relayer ! Bind(add, origin)
          sender ! "ok"
          goto(stateName) using d.copy(commitments = commitments1)
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(c@CMD_FULFILL_HTLC(id, r, do_commit), d: DATA_NORMAL) =>
      log.info(s"we are disconnected so we just include the fulfill in our commitments")
      Try(Commitments.sendFulfill(d.commitments, c)) match {
        case Success((commitments1, fulfill)) =>
          sender ! "ok"
          goto(stateName) using d.copy(commitments = commitments1)
        case Failure(cause) => handleCommandError(sender, cause)
      }

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: HasCommitments) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: HasCommitments) if Some(tx.txid) == d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx: Transaction), d: HasCommitments) => handleRemoteSpentOther(tx, d)
  }

  when(ERR_INFORMATION_LEAK, stateTimeout = 10 seconds) {
    case Event(StateTimeout, _) =>
      log.info("shutting down")
      stop(FSM.Normal)
  }

  whenUnhandled {

    case Event(INPUT_DISCONNECTED, _) =>
      remote = null
      goto(OFFLINE)

    case Event(WatchEventLost(BITCOIN_FUNDING_LOST), _) => goto(ERR_FUNDING_LOST)

    case Event(CMD_GETSTATE, _) =>
      sender ! stateName
      goto(stateName)

    case Event(CMD_GETSTATEDATA, _) =>
      sender ! stateData
      goto(stateName)

    case Event(CMD_GETINFO, _) =>
      sender ! RES_GETINFO(remoteNodeId, stateData match {
        // TODO
        case c: DATA_WAIT_FOR_OPEN_CHANNEL => c.initFundee.temporaryChannelId
        case c: DATA_WAIT_FOR_ACCEPT_CHANNEL => c.initFunder.temporaryChannelId
        case c: DATA_WAIT_FOR_FUNDING_CREATED => c.temporaryChannelId
        case c: DATA_WAIT_FOR_FUNDING_CONFIRMED => c.temporaryChannelId
        case c: DATA_NORMAL => c.commitments.channelId
        case c: DATA_SHUTDOWN => c.channelId
        case c: DATA_NEGOTIATING => c.channelId
        case c: DATA_CLOSING => 0L
        case _ => 0L
      }, stateName, stateData)
      goto(stateName)

    // we only care about this event in NORMAL and SHUTDOWN state, and we never unregister to the event stream
    case Event(CurrentBlockCount(_), _) => goto(stateName)
  }

  onTransition {
    case previousState -> currentState =>
      if (currentState != previousState) {
        context.system.eventStream.publish(ChannelStateChanged(self, context.parent, remoteNodeId, previousState, currentState, nextStateData))
      }
      (stateData, nextStateData) match {
        case (from: HasCommitments, to: HasCommitments) =>
          // use the the transition callback to store state and send messages
          val nextChannelId = to.channelId
          val toSend = if (remote == null) {
            Seq()
          } else {
            // we only send newly added unacked messages
            val nextMessages = to.commitments.unackedMessages
            val currentMessages = from.commitments.unackedMessages
            nextMessages.filterNot(c => currentMessages.contains(c))
          }

          forwarder ! StoreAndForward(toSend, remote, nextChannelId, ChannelState(remoteNodeId, currentState, nextStateData))

        case (_, to: HasCommitments) =>
          val nextChannelId = to.channelId
          forwarder ! StoreAndForward(Nil, remote, nextChannelId, ChannelState(remoteNodeId, currentState, nextStateData))

        case _ => ()
      }
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

  def handleCommandSuccess(sender: ActorRef, msg: LightningMessage, newData: Data) = {
    // FD remote ! msg
    if (sender != self) {
      sender ! "ok"
    }
    goto(stateName) using newData
  }

  def handleCommandError(sender: ActorRef, cause: Throwable) = {
    log.error(cause, "")
    sender ! cause.getMessage
    goto(stateName)
  }

  def handleLocalError(cause: Throwable, d: HasCommitments) = {
    log.error(cause, "")
    remote ! Error(d.channelId, cause.getMessage.getBytes)
    spendLocalCurrent(d)
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

  def publishMutualClosing(mutualClosing: Transaction) = {
    log.info(s"closingTxId=${
      mutualClosing.txid
    }")
    blockchain ! PublishAsap(mutualClosing)
    // TODO: hardcoded mindepth
    blockchain ! WatchConfirmed(self, mutualClosing.txid, 3, BITCOIN_CLOSE_DONE)
  }

  def spendLocalCurrent(d: HasCommitments) = {
    val commitTx = d.commitments.localCommit.publishableTxs.commitTx.tx

    blockchain ! PublishAsap(commitTx)

    // TODO hardcoded mindepth + shouldn't we watch the claim tx instead?
    blockchain ! WatchConfirmed(self, commitTx.txid, 3, BITCOIN_LOCALCOMMIT_DONE)

    val localCommitPublished = Helpers.Closing.claimCurrentLocalCommitTxOutputs(d.commitments, commitTx)
    localCommitPublished.claimMainDelayedOutputTx.foreach(tx => blockchain ! PublishAsap(tx))
    localCommitPublished.htlcSuccessTxs.foreach(tx => blockchain ! PublishAsap(tx))
    localCommitPublished.htlcTimeoutTxs.foreach(tx => blockchain ! PublishAsap(tx))
    localCommitPublished.claimHtlcDelayedTx.foreach(tx => blockchain ! PublishAsap(tx))

    // we also watch the htlc-timeout outputs in order to extract payment preimages
    localCommitPublished.htlcTimeoutTxs.foreach(tx => {
      require(tx.txIn.size == 1, s"an htlc-timeout tx must have exactly 1 input (has ${
        tx.txIn.size
      })")
      val outpoint = tx.txIn(0).outPoint
      log.info(s"watching output ${
        outpoint.index
      } of commit tx ${
        outpoint.txid
      }")
      blockchain ! WatchSpent(relayer, outpoint.txid, outpoint.index.toInt, BITCOIN_HTLC_SPENT)
    })

    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(localCommitPublished = Some(localCommitPublished))
      case _ => DATA_CLOSING(d.commitments, localCommitPublished = Some(localCommitPublished))
    }

    goto(CLOSING) using nextData
  }

  def handleRemoteSpent(commitTx: Transaction, d: HasCommitments, remoteCommit: RemoteCommit): RemoteCommitPublished = {
    val remoteCommitPublished = Helpers.Closing.claimRemoteCommitTxOutputs(d.commitments, remoteCommit, commitTx)
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
    remoteCommitPublished
  }

  def handleRemoteSpentCurrent(commitTx: Transaction, d: HasCommitments) = {
    log.warning(s"they published their current commit in txid=${commitTx.txid}")
    require(commitTx.txid == d.commitments.remoteCommit.txid, "txid mismatch")

    // TODO hardcoded mindepth + shouldn't we watch the claim tx instead?
    blockchain ! WatchConfirmed(self, commitTx.txid, 3, BITCOIN_REMOTECOMMIT_DONE)

    val remoteCommitPublished = handleRemoteSpent(commitTx, d, d.commitments.remoteCommit)

    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(remoteCommitPublished = Some(remoteCommitPublished))
      case _ => DATA_CLOSING(d.commitments, remoteCommitPublished = Some(remoteCommitPublished))
    }

    goto(CLOSING) using nextData
  }

  def handleRemoteSpentNext(commitTx: Transaction, d: HasCommitments) = {
    log.warning(s"they published their next commit in txid=${commitTx.txid}")
    require(d.commitments.remoteNextCommitInfo.isLeft, "next remote commit must be defined")
    val remoteCommit = d.commitments.remoteNextCommitInfo.left.get.nextRemoteCommit
    require(commitTx.txid == remoteCommit.txid, "txid mismatch")

    // TODO hardcoded mindepth + shouldn't we watch the claim tx instead?
    blockchain ! WatchConfirmed(self, commitTx.txid, 3, BITCOIN_NEXTREMOTECOMMIT_DONE)

    val remoteCommitPublished = handleRemoteSpent(commitTx, d, remoteCommit)

    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(nextRemoteCommitPublished = Some(remoteCommitPublished))
      case _ => DATA_CLOSING(d.commitments, nextRemoteCommitPublished = Some(remoteCommitPublished))
    }

    goto(CLOSING) using nextData
  }

  def handleRemoteSpentOther(tx: Transaction, d: HasCommitments) = {
    log.warning(s"funding tx spent in txid=${tx.txid}")

    Helpers.Closing.claimRevokedRemoteCommitTxOutputs(d.commitments, tx) match {
      case Some(revokedCommitPublished) =>
        log.warning(s"txid=${tx.txid} was a revoked commitment, publishing the penalty tx")
        remote ! Error(d.channelId, "Funding tx has been spent".getBytes)

        // TODO hardcoded mindepth + shouldn't we watch the claim tx instead?
        blockchain ! WatchConfirmed(self, tx.txid, 3, BITCOIN_PENALTY_DONE)

        revokedCommitPublished.claimMainOutputTx.foreach(tx => blockchain ! PublishAsap(tx))
        revokedCommitPublished.mainPenaltyTx.foreach(tx => blockchain ! PublishAsap(tx))
        revokedCommitPublished.claimHtlcTimeoutTxs.foreach(tx => blockchain ! PublishAsap(tx))
        revokedCommitPublished.htlcTimeoutTxs.foreach(tx => blockchain ! PublishAsap(tx))
        revokedCommitPublished.htlcPenaltyTxs.foreach(tx => blockchain ! PublishAsap(tx))

        val nextData = d match {
          case closing: DATA_CLOSING => closing.copy(revokedCommitPublished = closing.revokedCommitPublished :+ revokedCommitPublished)
          case _ => DATA_CLOSING(d.commitments, revokedCommitPublished = revokedCommitPublished :: Nil)
        }
        goto(CLOSING) using nextData
      case None =>
        // the published tx was neither their current commitment nor a revoked one
        log.error(s"couldn't identify txid=${
          tx.txid
        }, something very bad is going on!!!")
        goto(ERR_INFORMATION_LEAK)
    }
  }

  def handleInformationLeak(d: HasCommitments) = {
    // this is never supposed to happen !!
    log.error(s"our funding tx ${
      d.commitments.anchorId
    } was spent !!")
    remote ! Error(d.channelId, "Funding tx has been spent".getBytes)
    // TODO: not enough
    val commitTx = d.commitments.localCommit.publishableTxs.commitTx.tx
    blockchain ! PublishAsap(commitTx)
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




