package fr.acinq.eclair.channel

import java.nio.charset.StandardCharsets

import akka.actor.{ActorRef, FSM, OneForOneStrategy, Props, Status, SupervisorStrategy}
import akka.event.Logging.MDC
import akka.pattern.pipe
import fr.acinq.bitcoin.Crypto.{PublicKey, Scalar, sha256}
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.Helpers.{Closing, Funding}
import fr.acinq.eclair.crypto.{Generators, LocalKeyManager, ShaChain, Sphinx}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.{ChannelReestablish, _}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}


/**
  * Created by PM on 20/08/2015.
  */

object Channel {
  def props(nodeParams: NodeParams, wallet: EclairWallet, remoteNodeId: PublicKey, blockchain: ActorRef, router: ActorRef, relayer: ActorRef, origin_opt: Option[ActorRef]) = Props(new Channel(nodeParams, wallet, remoteNodeId, blockchain, router, relayer, origin_opt))

  // see https://github.com/lightningnetwork/lightning-rfc/blob/master/07-routing-gossip.md#requirements
  val ANNOUNCEMENTS_MINCONF = 6

  // https://github.com/lightningnetwork/lightning-rfc/blob/master/02-peer-protocol.md#requirements
  val MAX_FUNDING_SATOSHIS = 16777216L // = 2^24
  val MIN_FUNDING_SATOSHIS = 1000
  val MAX_ACCEPTED_HTLCS = 483

  // we don't want the counterparty to use a dust limit lower than that, because they wouldn't only hurt themselves we may need them to publish their commit tx in certain cases (backup/restore)
  val MIN_DUSTLIMIT = 546

  // we won't exchange more than this many signatures when negotiating the closing fee
  val MAX_NEGOTIATION_ITERATIONS = 20

  // this is defined in BOLT 11
  val MIN_CLTV_EXPIRY = 9L
  val MAX_CLTV_EXPIRY = 7 * 144L // one week

  case object TickRefreshChannelUpdate
}

class Channel(val nodeParams: NodeParams, wallet: EclairWallet, remoteNodeId: PublicKey, blockchain: ActorRef, router: ActorRef, relayer: ActorRef, origin_opt: Option[ActorRef] = None)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends FSM[State, Data] with FSMDiagnosticActorLogging[State, Data] {

  import Channel._
  import nodeParams.keyManager

  // we pass these to helpers classes so that they have the logging context
  implicit def implicitLog = log

  val forwarder = context.actorOf(Props(new Forwarder(nodeParams)), "forwarder")

  // this will be used to detect htlc timeouts
  context.system.eventStream.subscribe(self, classOf[CurrentBlockCount])
  // this will be used to make sure the current commitment fee is up-to-date
  context.system.eventStream.subscribe(self, classOf[CurrentFeerates])
  // we need to periodically re-send channel updates, otherwise channel will be considered stale and get pruned by network
  setTimer(TickRefreshChannelUpdate.toString, TickRefreshChannelUpdate, 1 day, repeat = true)

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
    case Event(initFunder@INPUT_INIT_FUNDER(temporaryChannelId, fundingSatoshis, pushMsat, initialFeeratePerKw, fundingTxFeeratePerKw, localParams, remote, remoteInit, channelFlags), Nothing) =>
      context.system.eventStream.publish(ChannelCreated(self, context.parent, remoteNodeId, true, temporaryChannelId))
      forwarder ! remote
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
        fundingPubkey = keyManager.fundingPublicKey(localParams.channelKeyPath).publicKey,
        revocationBasepoint = keyManager.revocationPoint(localParams.channelKeyPath).publicKey,
        paymentBasepoint = keyManager.paymentPoint(localParams.channelKeyPath).publicKey,
        delayedPaymentBasepoint = keyManager.delayedPaymentPoint(localParams.channelKeyPath).publicKey,
        htlcBasepoint = keyManager.htlcPoint(localParams.channelKeyPath).publicKey,
        firstPerCommitmentPoint = keyManager.commitmentPoint(localParams.channelKeyPath, 0),
        channelFlags = channelFlags)
      goto(WAIT_FOR_ACCEPT_CHANNEL) using DATA_WAIT_FOR_ACCEPT_CHANNEL(initFunder, open) sending open

    case Event(inputFundee@INPUT_INIT_FUNDEE(_, localParams, remote, _), Nothing) if !localParams.isFunder =>
      forwarder ! remote
      goto(WAIT_FOR_OPEN_CHANNEL) using DATA_WAIT_FOR_OPEN_CHANNEL(inputFundee)

    case Event(INPUT_RESTORED(data), _) =>
      log.info(s"restoring channel channelId=${data.channelId}")
      context.system.eventStream.publish(ChannelRestored(self, context.parent, remoteNodeId, data.commitments.localParams.isFunder, data.channelId, data))
      data match {
        //NB: order matters!
        case closing: DATA_CLOSING =>
          // we don't put back the WatchSpent if the commitment tx has already been published and the spending tx already reached mindepth
          val commitTxOutpoint = closing.commitments.commitInput.outPoint
          if (closing.localCommitPublished.exists(_.irrevocablySpent.contains(commitTxOutpoint)) ||
            closing.remoteCommitPublished.exists(_.irrevocablySpent.contains(commitTxOutpoint)) ||
            closing.nextRemoteCommitPublished.exists(_.irrevocablySpent.contains(commitTxOutpoint)) ||
            closing.revokedCommitPublished.exists(_.irrevocablySpent.contains(commitTxOutpoint))) {
            log.info(s"funding tx has already been spent and spending tx reached mindepth, no need to put back the watch-spent")
          } else {
            // TODO: should we wait for an acknowledgment from the watcher?
            blockchain ! WatchSpent(self, data.commitments.commitInput.outPoint.txid, data.commitments.commitInput.outPoint.index.toInt, data.commitments.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)
            blockchain ! WatchLost(self, data.commitments.commitInput.outPoint.txid, nodeParams.minDepthBlocks, BITCOIN_FUNDING_LOST)
          }
          closing.mutualClosePublished.map(doPublish(_))
          closing.localCommitPublished.foreach(doPublish(_))
          closing.remoteCommitPublished.foreach(doPublish(_))
          closing.nextRemoteCommitPublished.foreach(doPublish(_))
          closing.revokedCommitPublished.foreach(doPublish(_))
          // no need to go OFFLINE, we can directly switch to CLOSING
          goto(CLOSING) using closing

        case normal: DATA_NORMAL =>
          // TODO: should we wait for an acknowledgment from the watcher?
          blockchain ! WatchSpent(self, data.commitments.commitInput.outPoint.txid, data.commitments.commitInput.outPoint.index.toInt, data.commitments.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)
          blockchain ! WatchLost(self, data.commitments.commitInput.outPoint.txid, nodeParams.minDepthBlocks, BITCOIN_FUNDING_LOST)
          context.system.eventStream.publish(ShortChannelIdAssigned(self, normal.channelId, normal.channelUpdate.shortChannelId))
          // we rebuild a channel_update for two reasons:
          // - we want to reload values from configuration
          // - if eclair was previously killed, it might not have had time to publish a channel_update with enable=false
          val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, normal.channelUpdate.shortChannelId, nodeParams.expiryDeltaBlocks, normal.commitments.remoteParams.htlcMinimumMsat, nodeParams.feeBaseMsat, nodeParams.feeProportionalMillionth, enable = false)
          goto(OFFLINE) using normal.copy(channelUpdate = channelUpdate)

        case _ =>
          // TODO: should we wait for an acknowledgment from the watcher?
          blockchain ! WatchSpent(self, data.commitments.commitInput.outPoint.txid, data.commitments.commitInput.outPoint.index.toInt, data.commitments.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)
          blockchain ! WatchLost(self, data.commitments.commitInput.outPoint.txid, nodeParams.minDepthBlocks, BITCOIN_FUNDING_LOST)
          goto(OFFLINE) using data
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED) replying "ok"
  })

  when(WAIT_FOR_OPEN_CHANNEL)(handleExceptions {
    case Event(open: OpenChannel, DATA_WAIT_FOR_OPEN_CHANNEL(INPUT_INIT_FUNDEE(temporaryChannelId, localParams, _, remoteInit))) =>
      Try(Helpers.validateParamsFundee(nodeParams, open)) match {
        case Failure(t) =>
          log.warning(t.getMessage)
          val error = Error(open.temporaryChannelId, t.getMessage.getBytes)
          goto(CLOSED) sending error
        case Success(_) =>
          context.system.eventStream.publish(ChannelCreated(self, context.parent, remoteNodeId, false, open.temporaryChannelId))
          // TODO: maybe also check uniqueness of temporary channel id
          val minimumDepth = nodeParams.minDepthBlocks
          val accept = AcceptChannel(temporaryChannelId = open.temporaryChannelId,
            dustLimitSatoshis = localParams.dustLimitSatoshis,
            maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
            channelReserveSatoshis = localParams.channelReserveSatoshis,
            minimumDepth = minimumDepth,
            htlcMinimumMsat = localParams.htlcMinimumMsat,
            toSelfDelay = localParams.toSelfDelay,
            maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
            fundingPubkey = keyManager.fundingPublicKey(localParams.channelKeyPath).publicKey,
            revocationBasepoint = keyManager.revocationPoint(localParams.channelKeyPath).publicKey,
            paymentBasepoint = keyManager.paymentPoint(localParams.channelKeyPath).publicKey,
            delayedPaymentBasepoint = keyManager.delayedPaymentPoint(localParams.channelKeyPath).publicKey,
            htlcBasepoint = keyManager.htlcPoint(localParams.channelKeyPath).publicKey,
            firstPerCommitmentPoint = keyManager.commitmentPoint(localParams.channelKeyPath, 0))
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
            htlcBasepoint = open.htlcBasepoint,
            globalFeatures = remoteInit.globalFeatures,
            localFeatures = remoteInit.localFeatures)
          log.debug(s"remote params: $remoteParams")
          goto(WAIT_FOR_FUNDING_CREATED) using DATA_WAIT_FOR_FUNDING_CREATED(open.temporaryChannelId, localParams, remoteParams, open.fundingSatoshis, open.pushMsat, open.feeratePerKw, open.firstPerCommitmentPoint, open.channelFlags, accept) sending accept
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED) replying "ok"

    case Event(e: Error, d: DATA_WAIT_FOR_OPEN_CHANNEL) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_ACCEPT_CHANNEL)(handleExceptions {
    case Event(accept: AcceptChannel, DATA_WAIT_FOR_ACCEPT_CHANNEL(INPUT_INIT_FUNDER(temporaryChannelId, fundingSatoshis, pushMsat, initialFeeratePerKw, fundingTxFeeratePerKw, localParams, _, remoteInit, _), open)) =>
      Try(Helpers.validateParamsFunder(nodeParams, open, accept)) match {
        case Failure(t) =>
          log.warning(t.getMessage)
          val error = Error(temporaryChannelId, t.getMessage.getBytes)
          replyToUser(Left(Left(t)))
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
            htlcBasepoint = accept.htlcBasepoint,
            globalFeatures = remoteInit.globalFeatures,
            localFeatures = remoteInit.localFeatures)
          log.debug(s"remote params: $remoteParams")
          val localFundingPubkey = keyManager.fundingPublicKey(localParams.channelKeyPath).publicKey
          val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey, remoteParams.fundingPubKey)))
          wallet.makeFundingTx(fundingPubkeyScript, Satoshi(fundingSatoshis), fundingTxFeeratePerKw).pipeTo(self)
          goto(WAIT_FOR_FUNDING_INTERNAL) using DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, localParams, remoteParams, fundingSatoshis, pushMsat, initialFeeratePerKw, accept.firstPerCommitmentPoint, open)
      }

    case Event(CMD_CLOSE(_), _) =>
      replyToUser(Right("closed"))
      goto(CLOSED) replying "ok"

    case Event(e: Error, d: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      replyToUser(Left(Right(e)))
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) =>
      replyToUser(Left(Left(new RuntimeException("disconnected"))))
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_INTERNAL)(handleExceptions {
    case Event(MakeFundingTxResponse(fundingTx, fundingTxOutputIndex), data@DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, localParams, remoteParams, fundingSatoshis, pushMsat, initialFeeratePerKw, remoteFirstPerCommitmentPoint, open)) =>
      // let's create the first commitment tx that spends the yet uncommitted funding tx
      val (localSpec, localCommitTx, remoteSpec, remoteCommitTx) = Funding.makeFirstCommitTxs(keyManager, temporaryChannelId, localParams, remoteParams, fundingSatoshis, pushMsat, initialFeeratePerKw, fundingTx.hash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint, nodeParams.maxFeerateMismatch)
      require(fundingTx.txOut(fundingTxOutputIndex).publicKeyScript == localCommitTx.input.txOut.publicKeyScript, s"pubkey script mismatch!")
      val localSigOfRemoteTx = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(localParams.channelKeyPath))
      // signature of their initial commitment tx that pays remote pushMsat
      val fundingCreated = FundingCreated(
        temporaryChannelId = temporaryChannelId,
        fundingTxid = fundingTx.hash,
        fundingOutputIndex = fundingTxOutputIndex,
        signature = localSigOfRemoteTx
      )
      val channelId = toLongId(fundingTx.hash, fundingTxOutputIndex)
      context.parent ! ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
      context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId))
      // NB: we don't send a ChannelSignatureSent for the first commit
      goto(WAIT_FOR_FUNDING_SIGNED) using DATA_WAIT_FOR_FUNDING_SIGNED(channelId, localParams, remoteParams, fundingTx, localSpec, localCommitTx, RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint), open.channelFlags, fundingCreated) sending fundingCreated

    case Event(Status.Failure(t), d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      log.error(t, s"wallet returned error: ")
      val exc = ChannelFundingError(d.temporaryChannelId)
      val error = Error(d.temporaryChannelId, exc.getMessage.getBytes)
      replyToUser(Left(Left(t)))
      goto(CLOSED) sending error

    case Event(CMD_CLOSE(_), _) =>
      replyToUser(Right("closed"))
      goto(CLOSED) replying "ok"

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      replyToUser(Left(Right(e)))
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) =>
      replyToUser(Left(Left(new RuntimeException("disconnected"))))
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_CREATED)(handleExceptions {
    case Event(FundingCreated(_, fundingTxHash, fundingTxOutputIndex, remoteSig), DATA_WAIT_FOR_FUNDING_CREATED(temporaryChannelId, localParams, remoteParams, fundingSatoshis, pushMsat, initialFeeratePerKw, remoteFirstPerCommitmentPoint, channelFlags, _)) =>
      // they fund the channel with their funding tx, so the money is theirs (but we are paid pushMsat)
      val (localSpec, localCommitTx, remoteSpec, remoteCommitTx) = Funding.makeFirstCommitTxs(keyManager, temporaryChannelId, localParams, remoteParams, fundingSatoshis: Long, pushMsat, initialFeeratePerKw, fundingTxHash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint, nodeParams.maxFeerateMismatch)

      // check remote signature validity
      val localSigOfLocalTx = keyManager.sign(localCommitTx, keyManager.fundingPublicKey(localParams.channelKeyPath))
      val signedLocalCommitTx = Transactions.addSigs(localCommitTx, keyManager.fundingPublicKey(localParams.channelKeyPath).publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
      Transactions.checkSpendable(signedLocalCommitTx) match {
        case Failure(cause) =>
          log.error(cause, "their FundingCreated message contains an invalid signature")
          val exc = InvalidCommitmentSignature(temporaryChannelId, signedLocalCommitTx.tx)
          val error = Error(temporaryChannelId, exc.getMessage.getBytes)
          // we haven't anything at stake yet, we can just stop
          goto(CLOSED) sending error
        case Success(_) =>
          val localSigOfRemoteTx = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(localParams.channelKeyPath))
          val channelId = toLongId(fundingTxHash, fundingTxOutputIndex)
          // watch the funding tx transaction
          val commitInput = localCommitTx.input
          val fundingSigned = FundingSigned(
            channelId = channelId,
            signature = localSigOfRemoteTx
          )
          val commitments = Commitments(localParams, remoteParams, channelFlags,
            LocalCommit(0, localSpec, PublishableTxs(signedLocalCommitTx, Nil)), RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint),
            LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
            localNextHtlcId = 0L, remoteNextHtlcId = 0L,
            originChannels = Map.empty,
            remoteNextCommitInfo = Right(randomKey.publicKey), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array,
            commitInput, ShaChain.init, channelId = channelId)
          context.parent ! ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
          context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId))
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
          // NB: we don't send a ChannelSignatureSent for the first commit
          log.info(s"waiting for them to publish the funding tx for channelId=$channelId fundingTxid=${commitInput.outPoint.txid}")
          blockchain ! WatchSpent(self, commitInput.outPoint.txid, commitInput.outPoint.index.toInt, commitments.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT) // TODO: should we wait for an acknowledgment from the watcher?
          blockchain ! WatchConfirmed(self, commitInput.outPoint.txid, commitments.commitInput.txOut.publicKeyScript, nodeParams.minDepthBlocks, BITCOIN_FUNDING_DEPTHOK)
          goto(WAIT_FOR_FUNDING_CONFIRMED) using store(DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, None, Right(fundingSigned))) sending fundingSigned
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED) replying "ok"

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_CREATED) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_SIGNED)(handleExceptions {
    case Event(FundingSigned(_, remoteSig), DATA_WAIT_FOR_FUNDING_SIGNED(channelId, localParams, remoteParams, fundingTx, localSpec, localCommitTx, remoteCommit, channelFlags, fundingCreated)) =>
      // we make sure that their sig checks out and that our first commit tx is spendable
      val localSigOfLocalTx = keyManager.sign(localCommitTx, keyManager.fundingPublicKey(localParams.channelKeyPath))
      val signedLocalCommitTx = Transactions.addSigs(localCommitTx, keyManager.fundingPublicKey(localParams.channelKeyPath).publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
      Transactions.checkSpendable(signedLocalCommitTx) match {
        case Failure(cause) =>
          log.error(cause, "their FundingSigned message contains an invalid signature")
          val exc = InvalidCommitmentSignature(channelId, signedLocalCommitTx.tx)
          val error = Error(channelId, exc.getMessage.getBytes)
          // we rollback the funding tx, it will never be published
          wallet.rollback(fundingTx)
          replyToUser(Left(Left(cause)))
          // we haven't published anything yet, we can just stop
          goto(CLOSED) sending error
        case Success(_) =>
          val commitInput = localCommitTx.input
          val commitments = Commitments(localParams, remoteParams, channelFlags,
            LocalCommit(0, localSpec, PublishableTxs(signedLocalCommitTx, Nil)), remoteCommit,
            LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
            localNextHtlcId = 0L, remoteNextHtlcId = 0L,
            originChannels = Map.empty,
            remoteNextCommitInfo = Right(randomKey.publicKey), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array
            commitInput, ShaChain.init, channelId = channelId)
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
          log.info(s"publishing funding tx for channelId=$channelId fundingTxid=${commitInput.outPoint.txid}")
          // we do this to make sure that the channel state has been written to disk when we publish the funding tx
          val nextState = store(DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, None, Left(fundingCreated)))
          blockchain ! WatchSpent(self, commitments.commitInput.outPoint.txid, commitments.commitInput.outPoint.index.toInt, commitments.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT) // TODO: should we wait for an acknowledgment from the watcher?
          blockchain ! WatchConfirmed(self, commitments.commitInput.outPoint.txid, commitments.commitInput.txOut.publicKeyScript, nodeParams.minDepthBlocks, BITCOIN_FUNDING_DEPTHOK)
          log.info(s"committing txid=${fundingTx.txid}")
          wallet.commit(fundingTx).onComplete {
            case Success(true) =>
              replyToUser(Right(s"created channel $channelId"))
            case Success(false) =>
              replyToUser(Left(Left(new RuntimeException("couldn't publish funding tx"))))
              self ! BITCOIN_FUNDING_PUBLISH_FAILED // fail-fast: this should be returned only when we are really sure the tx has *not* been published
            case Failure(t) =>
              replyToUser(Left(Left(t)))
              log.error(t, s"error while committing funding tx: ") // tx may still have been published, can't fail-fast
          }
          goto(WAIT_FOR_FUNDING_CONFIRMED) using nextState
      }

    case Event(CMD_CLOSE(_) | CMD_FORCECLOSE, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      replyToUser(Right("closed"))
      goto(CLOSED) replying "ok"

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      replyToUser(Left(Right(e)))
      handleRemoteError(e, d)
  })

  when(WAIT_FOR_FUNDING_CONFIRMED)(handleExceptions {
    case Event(msg: FundingLocked, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      log.info(s"received their FundingLocked, deferring message")
      stay using d.copy(deferred = Some(msg)) // no need to store, they will re-send if we get disconnected

    case Event(WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, blockHeight, txIndex), DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, deferred, _)) =>
      log.info(s"channelId=${commitments.channelId} was confirmed at blockHeight=$blockHeight txIndex=$txIndex")
      blockchain ! WatchLost(self, commitments.commitInput.outPoint.txid, nodeParams.minDepthBlocks, BITCOIN_FUNDING_LOST)
      val nextPerCommitmentPoint = keyManager.commitmentPoint(commitments.localParams.channelKeyPath, 1)
      val fundingLocked = FundingLocked(commitments.channelId, nextPerCommitmentPoint)
      deferred.map(self ! _)
      // this is the temporary channel id that we will use in our channel_update message, the goal is to be able to use our channel
      // as soon as it reaches NORMAL state, and before it is announced on the network
      // (this id might be updated when the funding tx gets deeply buried, if there was a reorg in the meantime)
      val shortChannelId = ShortChannelId(blockHeight, txIndex, commitments.commitInput.outPoint.index.toInt)
      goto(WAIT_FOR_FUNDING_LOCKED) using store(DATA_WAIT_FOR_FUNDING_LOCKED(commitments, shortChannelId, fundingLocked)) sending fundingLocked

    case Event(BITCOIN_FUNDING_PUBLISH_FAILED, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      log.error(s"failed to publish funding tx")
      val exc = ChannelFundingError(d.channelId)
      val error = Error(d.channelId, exc.getMessage.getBytes)
      // note: implementation guarantees that the tx will not ever be published, so we can close the channel right away
      goto(CLOSED) sending error

    // TODO: not implemented, users will have to manually close
    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      val exc = FundingTxTimedout(d.channelId)
      val error = Error(d.channelId, exc.getMessage.getBytes)
      goto(ERR_FUNDING_TIMEOUT) sending error

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if d.commitments.announceChannel =>
      log.debug(s"received remote announcement signatures, delaying")
      // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
      // note: no need to persist their message, in case of disconnection they will resend it
      context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
      stay

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleInformationLeak(tx, d)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleRemoteError(e, d)
  })

  when(WAIT_FOR_FUNDING_LOCKED)(handleExceptions {
    case Event(FundingLocked(_, nextPerCommitmentPoint), d@DATA_WAIT_FOR_FUNDING_LOCKED(commitments, shortChannelId, _)) =>
      // used to get the final shortChannelId, used in announcements (if minDepth >= ANNOUNCEMENTS_MINCONF this event will fire instantly)
      blockchain ! WatchConfirmed(self, commitments.commitInput.outPoint.txid, commitments.commitInput.txOut.publicKeyScript, ANNOUNCEMENTS_MINCONF, BITCOIN_FUNDING_DEEPLYBURIED)
      context.system.eventStream.publish(ShortChannelIdAssigned(self, commitments.channelId, shortChannelId))
      val initialChannelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, shortChannelId, nodeParams.expiryDeltaBlocks, d.commitments.remoteParams.htlcMinimumMsat, nodeParams.feeBaseMsat, nodeParams.feeProportionalMillionth, enable = true)
      goto(NORMAL) using store(DATA_NORMAL(commitments.copy(remoteNextCommitInfo = Right(nextPerCommitmentPoint)), shortChannelId, buried = false, None, initialChannelUpdate, None, None))

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_FUNDING_LOCKED) if d.commitments.announceChannel =>
      log.debug(s"received remote announcement signatures, delaying")
      // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
      // note: no need to persist their message, in case of disconnection they will resend it
      context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
      stay

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_WAIT_FOR_FUNDING_LOCKED) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_WAIT_FOR_FUNDING_LOCKED) => handleInformationLeak(tx, d)

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
    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) if d.localShutdown.isDefined || d.remoteShutdown.isDefined =>
      // note: spec would allow us to keep sending new htlcs after having received their shutdown (and not sent ours)
      // but we want to converge as fast as possible and they would probably not route them anyway
      val error = NoMoreHtlcsClosingInProgress(d.channelId)
      handleCommandError(AddHtlcFailed(d.channelId, c.paymentHash, error, origin(c), Some(d.channelUpdate)), c)

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) =>
      Try(Commitments.sendAdd(d.commitments, c, origin(c))) match {
        case Success(Right((commitments1, add))) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending add
        case Success(Left(error)) => handleCommandError(AddHtlcFailed(d.channelId, c.paymentHash, error, origin(c), Some(d.channelUpdate)), c)
        case Failure(cause) => handleCommandError(AddHtlcFailed(d.channelId, c.paymentHash, cause, origin(c), Some(d.channelUpdate)), c)
      }

    case Event(add: UpdateAddHtlc, d: DATA_NORMAL) =>
      Try(Commitments.receiveAdd(d.commitments, add)) match {
        case Success(commitments1) => stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleLocalError(cause, d, Some(add))
      }

    case Event(c: CMD_FULFILL_HTLC, d: DATA_NORMAL) =>
      Try(Commitments.sendFulfill(d.commitments, c)) match {
        case Success((commitments1, fulfill)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending fulfill
        case Failure(cause) => handleCommandError(cause, c)
      }

    case Event(fulfill: UpdateFulfillHtlc, d: DATA_NORMAL) =>
      Try(Commitments.receiveFulfill(d.commitments, fulfill)) match {
        case Success(Right((commitments1, origin, htlc))) =>
          relayer ! ForwardFulfill(fulfill, origin, htlc)
          stay using d.copy(commitments = commitments1)
        case Success(Left(_)) => stay
        case Failure(cause) => handleLocalError(cause, d, Some(fulfill))
      }

    case Event(c: CMD_FAIL_HTLC, d: DATA_NORMAL) =>
      Try(Commitments.sendFail(d.commitments, c, nodeParams.privateKey)) match {
        case Success((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending fail
        case Failure(cause) => handleCommandError(cause, c)
      }

    case Event(c: CMD_FAIL_MALFORMED_HTLC, d: DATA_NORMAL) =>
      Try(Commitments.sendFailMalformed(d.commitments, c)) match {
        case Success((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending fail
        case Failure(cause) => handleCommandError(cause, c)
      }

    case Event(fail: UpdateFailHtlc, d: DATA_NORMAL) =>
      Try(Commitments.receiveFail(d.commitments, fail)) match {
        case Success(Right((commitments1, origin, htlc))) =>
          relayer ! ForwardFail(fail, origin, htlc)
          stay using d.copy(commitments = commitments1)
        case Success(Left(_)) => stay
        case Failure(cause) => handleLocalError(cause, d, Some(fail))
      }

    case Event(fail: UpdateFailMalformedHtlc, d: DATA_NORMAL) =>
      Try(Commitments.receiveFailMalformed(d.commitments, fail)) match {
        case Success(Right((commitments1, origin, htlc))) =>
          relayer ! ForwardFailMalformed(fail, origin, htlc)
          stay using d.copy(commitments = commitments1)
        case Success(Left(_)) => stay
        case Failure(cause) => handleLocalError(cause, d, Some(fail))
      }

    case Event(c: CMD_UPDATE_FEE, d: DATA_NORMAL) =>
      Try(Commitments.sendFee(d.commitments, c)) match {
        case Success((commitments1, fee)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending fee
        case Failure(cause) => handleCommandError(cause, c)
      }

    case Event(fee: UpdateFee, d: DATA_NORMAL) =>
      Try(Commitments.receiveFee(d.commitments, fee, nodeParams.maxFeerateMismatch)) match {
        case Success(commitments1) => stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleLocalError(cause, d, Some(fee))
      }

    case Event(c@CMD_SIGN, d: DATA_NORMAL) =>
      d.commitments.remoteNextCommitInfo match {
        case _ if !Commitments.localHasChanges(d.commitments) =>
          log.debug("ignoring CMD_SIGN (nothing to sign)")
          stay
        case Right(_) =>
          Try(Commitments.sendCommit(d.commitments, keyManager)) match {
            case Success((commitments1, commit)) =>
              log.debug(s"sending a new sig, spec:\n${Commitments.specs2String(commitments1)}")
              commitments1.localChanges.signed.collect {
                case u: UpdateFulfillHtlc => relayer ! CommandBuffer.CommandAck(u.channelId, u.id)
                case u: UpdateFailHtlc => relayer ! CommandBuffer.CommandAck(u.channelId, u.id)
                case u: UpdateFailMalformedHtlc => relayer ! CommandBuffer.CommandAck(u.channelId, u.id)
              }
              context.system.eventStream.publish(ChannelSignatureSent(self, commitments1))
              handleCommandSuccess(sender, store(d.copy(commitments = commitments1))) sending commit
            case Failure(cause) => handleCommandError(cause, c)
          }
        case Left(waitForRevocation) =>
          log.debug(s"already in the process of signing, will sign again as soon as possible")
          val commitments1 = d.commitments.copy(remoteNextCommitInfo = Left(waitForRevocation.copy(reSignAsap = true)))
          stay using d.copy(commitments = commitments1)
      }

    case Event(commit: CommitSig, d: DATA_NORMAL) =>
      Try(Commitments.receiveCommit(d.commitments, commit, keyManager)) match {
        case Success((commitments1, revocation)) =>
          log.debug(s"received a new sig, spec:\n${Commitments.specs2String(commitments1)}")
          if (Commitments.localHasChanges(commitments1)) {
            // if we have newly acknowledged changes let's sign them
            self ! CMD_SIGN
          }
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments1))
          stay using store(d.copy(commitments = commitments1)) sending revocation
        case Failure(cause) => handleLocalError(cause, d, Some(commit))
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
          if (d.remoteShutdown.isDefined && !Commitments.localHasUnsignedOutgoingHtlcs(commitments1)) {
            // we were waiting for our pending htlcs to be signed before replying with our local shutdown
            val localShutdown = Shutdown(d.channelId, commitments1.localParams.defaultFinalScriptPubKey)
            // note: it means that we had pending htlcs to sign, therefore we go to SHUTDOWN, not to NEGOTIATING
            require(commitments1.remoteCommit.spec.htlcs.size > 0, "we must have just signed new htlcs, otherwise we would have sent our Shutdown earlier")
            goto(SHUTDOWN) using store(DATA_SHUTDOWN(commitments1, localShutdown, d.remoteShutdown.get)) sending localShutdown
          } else {
            stay using store(d.copy(commitments = commitments1))
          }
        case Failure(cause) => handleLocalError(cause, d, Some(revocation))
      }

    case Event(c@CMD_CLOSE(localScriptPubKey_opt), d: DATA_NORMAL) =>
      val localScriptPubKey = localScriptPubKey_opt.getOrElse(d.commitments.localParams.defaultFinalScriptPubKey)
      if (d.localShutdown.isDefined)
        handleCommandError(ClosingAlreadyInProgress((d.channelId)), c)
      else if (Commitments.localHasUnsignedOutgoingHtlcs(d.commitments))
      // TODO: simplistic behavior, we could also sign-then-close
        handleCommandError(CannotCloseWithUnsignedOutgoingHtlcs((d.channelId)), c)
      else if (!Closing.isValidFinalScriptPubkey(localScriptPubKey))
        handleCommandError(InvalidFinalScript(d.channelId), c)
      else {
        val shutdown = Shutdown(d.channelId, localScriptPubKey)
        handleCommandSuccess(sender, store(d.copy(localShutdown = Some(shutdown)))) sending shutdown
      }

    case Event(remoteShutdown@Shutdown(_, remoteScriptPubKey), d: DATA_NORMAL) =>
      // they have pending unsigned htlcs         => they violated the spec, close the channel
      // they don't have pending unsigned htlcs
      //    we have pending unsigned htlcs
      //      we already sent a shutdown message  => spec violation (we can't send htlcs after having sent shutdown)
      //      we did not send a shutdown message
      //        we are ready to sign              => we stop sending further htlcs, we initiate a signature
      //        we are waiting for a rev          => we stop sending further htlcs, we wait for their revocation, will resign immediately after, and then we will send our shutdown message
      //    we have no pending unsigned htlcs
      //      we already sent a shutdown message
      //        there are pending signed htlcs    => send our shutdown message, go to SHUTDOWN
      //        there are no htlcs                => send our shutdown message, go to NEGOTIATING
      //      we did not send a shutdown message
      //        there are pending signed htlcs    => go to SHUTDOWN
      //        there are no htlcs                => go to NEGOTIATING

      if (!Closing.isValidFinalScriptPubkey(remoteScriptPubKey)) {
        handleLocalError(InvalidFinalScript(d.channelId), d, Some(remoteShutdown))
      } else if (Commitments.remoteHasUnsignedOutgoingHtlcs(d.commitments)) {
        handleLocalError(CannotCloseWithUnsignedOutgoingHtlcs(d.channelId), d, Some(remoteShutdown))
      } else if (Commitments.localHasUnsignedOutgoingHtlcs(d.commitments)) { // do we have unsigned outgoing htlcs?
        require(d.localShutdown.isEmpty, "can't have pending unsigned outgoing htlcs after having sent Shutdown")
        // are we in the middle of a signature?
        d.commitments.remoteNextCommitInfo match {
          case Left(waitForRevocation) =>
            // yes, let's just schedule a new signature ASAP, which will include all pending unsigned htlcs
            val commitments1 = d.commitments.copy(remoteNextCommitInfo = Left(waitForRevocation.copy(reSignAsap = true)))
            // in the meantime we won't send new htlcs
            stay using d.copy(commitments = commitments1, remoteShutdown = Some(remoteShutdown))
          case Right(_) =>
            // no, let's sign right away
            self ! CMD_SIGN
            // in the meantime we won't send new htlcs
            stay using d.copy(remoteShutdown = Some(remoteShutdown))
        }
      } else {
        // so we don't have any unsigned outgoing htlcs
        val (localShutdown, sendList) = d.localShutdown match {
          case Some(localShutdown) =>
            (localShutdown, Nil)
          case None =>
            val localShutdown = Shutdown(d.channelId, d.commitments.localParams.defaultFinalScriptPubKey)
            // we need to send our shutdown if we didn't previously
            (localShutdown, localShutdown :: Nil)
        }
        // are there pending signed htlcs on either side? we need to have received their last revocation!
        if (d.commitments.hasNoPendingHtlcs) {
          // there are no pending signed htlcs, let's go directly to NEGOTIATING
          if (d.commitments.localParams.isFunder) {
            // we are funder, need to initiate the negotiation by sending the first closing_signed
            val (closingTx, closingSigned) = Closing.makeFirstClosingTx(keyManager, d.commitments, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey)
            goto(NEGOTIATING) using store(DATA_NEGOTIATING(d.commitments, localShutdown, remoteShutdown, List(List(ClosingTxProposed(closingTx.tx, closingSigned))), bestUnpublishedClosingTx_opt = None)) sending sendList :+ closingSigned
          } else {
            // we are fundee, will wait for their closing_signed
            goto(NEGOTIATING) using store(DATA_NEGOTIATING(d.commitments, localShutdown, remoteShutdown, closingTxProposed = List(List()), bestUnpublishedClosingTx_opt = None)) sending sendList
          }

        } else {
          // there are some pending signed htlcs, we need to fail/fulfill them
          goto(SHUTDOWN) using store(DATA_SHUTDOWN(d.commitments, localShutdown, remoteShutdown)) sending sendList
        }
      }

    case Event(c@CurrentBlockCount(count), d: DATA_NORMAL) if d.commitments.hasTimedoutOutgoingHtlcs(count) =>
      handleLocalError(HtlcTimedout(d.channelId), d, Some(c))

    case Event(c@CurrentFeerates(feeratesPerKw), d: DATA_NORMAL) =>
      val networkFeeratePerKw = feeratesPerKw.block_1
      d.commitments.localParams.isFunder match {
        case true if Helpers.shouldUpdateFee(d.commitments.localCommit.spec.feeratePerKw, networkFeeratePerKw, nodeParams.updateFeeMinDiffRatio) =>
          self ! CMD_UPDATE_FEE(networkFeeratePerKw, commit = true)
          stay
        case false if Helpers.isFeeDiffTooHigh(d.commitments.localCommit.spec.feeratePerKw, networkFeeratePerKw, nodeParams.maxFeerateMismatch) =>
          handleLocalError(FeerateTooDifferent(d.channelId, localFeeratePerKw = networkFeeratePerKw, remoteFeeratePerKw = d.commitments.localCommit.spec.feeratePerKw), d, Some(c))
        case _ => stay
      }

    case Event(WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, blockHeight, txIndex), d: DATA_NORMAL) if d.channelAnnouncement.isEmpty =>
      val shortChannelId = ShortChannelId(blockHeight, txIndex, d.commitments.commitInput.outPoint.index.toInt)
      log.info(s"funding tx is deeply buried at blockHeight=$blockHeight txIndex=$txIndex shortChannelId=$shortChannelId")
      // if final shortChannelId is different from the one we had before, we need to re-announce it
      val channelUpdate = if (shortChannelId != d.shortChannelId) {
        log.info(s"short channel id changed, probably due to a chain reorg: old=${d.shortChannelId} new=$shortChannelId")
        // we need to re-announce this shortChannelId
        context.system.eventStream.publish(ShortChannelIdAssigned(self, d.channelId, shortChannelId))
        // we re-announce the channelUpdate for the same reason
        Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, shortChannelId, d.channelUpdate.cltvExpiryDelta, d.channelUpdate.htlcMinimumMsat, d.channelUpdate.feeBaseMsat, d.channelUpdate.feeProportionalMillionths, enable = true)
      } else d.channelUpdate
      val localAnnSigs_opt = if (d.commitments.announceChannel) {
        // if channel is public we need to send our announcement_signatures in order to generate the channel_announcement
        Some(Helpers.makeAnnouncementSignatures(nodeParams, d.commitments, shortChannelId))
      } else None
      // we use GOTO instead of stay because we want to fire transitions
      goto(NORMAL) using store(d.copy(shortChannelId = shortChannelId, buried = true, channelUpdate = channelUpdate)) sending localAnnSigs_opt.toSeq

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_NORMAL) if d.commitments.announceChannel =>
      // channels are publicly announced if both parties want it (defined as feature bit)
      if (d.buried) {
        // we are aware that the channel has reached enough confirmations
        // we already had sent our announcement_signatures but we don't store them so we need to recompute it
        val localAnnSigs = Helpers.makeAnnouncementSignatures(nodeParams, d.commitments, d.shortChannelId)
        d.channelAnnouncement match {
          case None =>
            require(d.shortChannelId == remoteAnnSigs.shortChannelId, s"shortChannelId mismatch: local=${d.shortChannelId} remote=${remoteAnnSigs.shortChannelId}")
            log.info(s"announcing channelId=${d.channelId} on the network with shortId=${d.shortChannelId}")
            import d.commitments.{localParams, remoteParams}
            val channelAnn = Announcements.makeChannelAnnouncement(nodeParams.chainHash, localAnnSigs.shortChannelId, nodeParams.nodeId, remoteParams.nodeId, keyManager.fundingPublicKey(localParams.channelKeyPath).publicKey, remoteParams.fundingPubKey, localAnnSigs.nodeSignature, remoteAnnSigs.nodeSignature, localAnnSigs.bitcoinSignature, remoteAnnSigs.bitcoinSignature)
            // we use GOTO instead of stay because we want to fire transitions
            goto(NORMAL) using store(d.copy(channelAnnouncement = Some(channelAnn)))
          case Some(_) =>
            // they have sent their announcement sigs, but we already have a valid channel announcement
            // this can happen if our announcement_signatures was lost during a disconnection
            // specs says that we "MUST respond to the first announcement_signatures message after reconnection with its own announcement_signatures message"
            // current implementation always replies to announcement_signatures, not only the first time
            // TODO: we should only be nice once, current behaviour opens way to DOS, but this should be handled higher in the stack anyway
            log.info(s"re-sending our announcement sigs")
            stay sending localAnnSigs
        }
      } else {
        // our watcher didn't notify yet that the tx has reached ANNOUNCEMENTS_MINCONF confirmations, let's delay remote's message
        // note: no need to persist their message, in case of disconnection they will resend it
        log.debug(s"received remote announcement signatures, delaying")
        context.system.scheduler.scheduleOnce(5 seconds, self, remoteAnnSigs)
        stay
      }

    case Event(TickRefreshChannelUpdate, d: DATA_NORMAL) =>
      // periodic refresh is used as a keep alive
      log.debug(s"sending channel_update announcement (refresh)")
      val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, d.shortChannelId, d.channelUpdate.cltvExpiryDelta, d.channelUpdate.htlcMinimumMsat, d.channelUpdate.feeBaseMsat, d.channelUpdate.feeProportionalMillionths, enable = true)
      stay using store(d.copy(channelUpdate = channelUpdate))

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_NORMAL) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_NORMAL) if Some(tx.txid) == d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_NORMAL) => handleRemoteSpentOther(tx, d)

    case Event(INPUT_DISCONNECTED, d: DATA_NORMAL) =>
      // we disable the channel
      log.debug(s"sending channel_update announcement (disable)")
      val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, d.shortChannelId, d.channelUpdate.cltvExpiryDelta, d.channelUpdate.htlcMinimumMsat, d.channelUpdate.feeBaseMsat, d.channelUpdate.feeProportionalMillionths, enable = false)
      d.commitments.localChanges.proposed.collect {
        case add: UpdateAddHtlc => relayer ! Status.Failure(AddHtlcFailed(d.channelId, add.paymentHash, ChannelUnavailable(d.channelId), d.commitments.originChannels(add.id), Some(channelUpdate)))
      }
      goto(OFFLINE) using d.copy(channelUpdate = channelUpdate)

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
        case Failure(cause) => handleCommandError(cause, c)
      }

    case Event(fulfill: UpdateFulfillHtlc, d: DATA_SHUTDOWN) =>
      Try(Commitments.receiveFulfill(d.commitments, fulfill)) match {
        case Success(Right((commitments1, origin, htlc))) =>
          relayer ! ForwardFulfill(fulfill, origin, htlc)
          stay using d.copy(commitments = commitments1)
        case Success(Left(_)) => stay
        case Failure(cause) => handleLocalError(cause, d, Some(fulfill))
      }

    case Event(c: CMD_FAIL_HTLC, d: DATA_SHUTDOWN) =>
      Try(Commitments.sendFail(d.commitments, c, nodeParams.privateKey)) match {
        case Success((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending fail
        case Failure(cause) => handleCommandError(cause, c)
      }

    case Event(c: CMD_FAIL_MALFORMED_HTLC, d: DATA_SHUTDOWN) =>
      Try(Commitments.sendFailMalformed(d.commitments, c)) match {
        case Success((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending fail
        case Failure(cause) => handleCommandError(cause, c)
      }

    case Event(fail: UpdateFailHtlc, d: DATA_SHUTDOWN) =>
      Try(Commitments.receiveFail(d.commitments, fail)) match {
        case Success(Right((commitments1, origin, htlc))) =>
          relayer ! ForwardFail(fail, origin, htlc)
          stay using d.copy(commitments = commitments1)
        case Success(Left(_)) => stay
        case Failure(cause) => handleLocalError(cause, d, Some(fail))
      }

    case Event(fail: UpdateFailMalformedHtlc, d: DATA_SHUTDOWN) =>
      Try(Commitments.receiveFailMalformed(d.commitments, fail)) match {
        case Success(Right((commitments1, origin, htlc))) =>
          relayer ! ForwardFailMalformed(fail, origin, htlc)
          stay using d.copy(commitments = commitments1)
        case Success(Left(_)) => stay
        case Failure(cause) => handleLocalError(cause, d, Some(fail))
      }

    case Event(c: CMD_UPDATE_FEE, d: DATA_SHUTDOWN) =>
      Try(Commitments.sendFee(d.commitments, c)) match {
        case Success((commitments1, fee)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, d.copy(commitments = commitments1)) sending fee
        case Failure(cause) => handleCommandError(cause, c)
      }

    case Event(fee: UpdateFee, d: DATA_SHUTDOWN) =>
      Try(Commitments.receiveFee(d.commitments, fee, nodeParams.maxFeerateMismatch)) match {
        case Success(commitments1) => stay using d.copy(commitments = commitments1)
        case Failure(cause) => handleLocalError(cause, d, Some(fee))
      }

    case Event(c@CMD_SIGN, d: DATA_SHUTDOWN) =>
      d.commitments.remoteNextCommitInfo match {
        case _ if !Commitments.localHasChanges(d.commitments) =>
          log.debug("ignoring CMD_SIGN (nothing to sign)")
          stay
        case Right(_) =>
          Try(Commitments.sendCommit(d.commitments, keyManager)) match {
            case Success((commitments1, commit)) =>
              log.debug(s"sending a new sig, spec:\n${Commitments.specs2String(commitments1)}")
              commitments1.localChanges.signed.collect {
                case u: UpdateFulfillHtlc => relayer ! CommandBuffer.CommandAck(u.channelId, u.id)
                case u: UpdateFailHtlc => relayer ! CommandBuffer.CommandAck(u.channelId, u.id)
                case u: UpdateFailMalformedHtlc => relayer ! CommandBuffer.CommandAck(u.channelId, u.id)
              }
              context.system.eventStream.publish(ChannelSignatureSent(self, commitments1))
              handleCommandSuccess(sender, store(d.copy(commitments = commitments1))) sending commit
            case Failure(cause) => handleCommandError(cause, c)
          }
        case Left(waitForRevocation) =>
          log.debug(s"already in the process of signing, will sign again as soon as possible")
          stay using d.copy(commitments = d.commitments.copy(remoteNextCommitInfo = Left(waitForRevocation.copy(reSignAsap = true))))
      }

    case Event(commit: CommitSig, d@DATA_SHUTDOWN(_, localShutdown, remoteShutdown)) =>
      Try(Commitments.receiveCommit(d.commitments, commit, keyManager)) map {
        case (commitments1, revocation) =>
          // we always reply with a revocation
          log.debug(s"received a new sig:\n${Commitments.specs2String(commitments1)}")
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments1))
          (commitments1, revocation)
      } match {
        case Success((commitments1, revocation)) if commitments1.hasNoPendingHtlcs =>
          if (d.commitments.localParams.isFunder) {
            // we are funder, need to initiate the negotiation by sending the first closing_signed
            val (closingTx, closingSigned) = Closing.makeFirstClosingTx(keyManager, commitments1, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey)
            goto(NEGOTIATING) using store(DATA_NEGOTIATING(commitments1, localShutdown, remoteShutdown, List(List(ClosingTxProposed(closingTx.tx, closingSigned))), bestUnpublishedClosingTx_opt = None)) sending revocation :: closingSigned :: Nil
          } else {
            // we are fundee, will wait for their closing_signed
            goto(NEGOTIATING) using store(DATA_NEGOTIATING(commitments1, localShutdown, remoteShutdown, closingTxProposed = List(List()), bestUnpublishedClosingTx_opt = None)) sending revocation
          }
        case Success((commitments1, revocation)) =>
          if (Commitments.localHasChanges(commitments1)) {
            // if we have newly acknowledged changes let's sign them
            self ! CMD_SIGN
          }
          stay using store(d.copy(commitments = commitments1)) sending revocation
        case Failure(cause) => handleLocalError(cause, d, Some(commit))
      }

    case Event(revocation: RevokeAndAck, d@DATA_SHUTDOWN(commitments, localShutdown, remoteShutdown)) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked including the shutdown message
      Try(Commitments.receiveRevocation(commitments, revocation)) match {
        case Success(commitments1) if commitments1.hasNoPendingHtlcs =>
          log.debug(s"received a new rev, switching to NEGOTIATING spec:\n${Commitments.specs2String(commitments1)}")
          if (d.commitments.localParams.isFunder) {
            // we are funder, need to initiate the negotiation by sending the first closing_signed
            val (closingTx, closingSigned) = Closing.makeFirstClosingTx(keyManager, commitments1, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey)
            goto(NEGOTIATING) using store(DATA_NEGOTIATING(commitments1, localShutdown, remoteShutdown, List(List(ClosingTxProposed(closingTx.tx, closingSigned))), bestUnpublishedClosingTx_opt = None)) sending closingSigned
          } else {
            // we are fundee, will wait for their closing_signed
            goto(NEGOTIATING) using store(DATA_NEGOTIATING(commitments1, localShutdown, remoteShutdown, closingTxProposed = List(List()), bestUnpublishedClosingTx_opt = None))
          }
        case Success(commitments1) =>
          // BOLT 2: A sending node SHOULD fail to route any HTLC added after it sent shutdown.
          d.commitments.remoteChanges.signed.collect {
            case htlc: UpdateAddHtlc =>
              log.debug(s"closing in progress: failing $htlc")
              self ! CMD_FAIL_HTLC(htlc.id, Right(PermanentChannelFailure), commit = true)
          }
          if (Commitments.localHasChanges(commitments1) && d.commitments.remoteNextCommitInfo.left.map(_.reSignAsap) == Left(true)) {
            self ! CMD_SIGN
          }
          log.debug(s"received a new rev, spec:\n${Commitments.specs2String(commitments1)}")
          stay using store(d.copy(commitments = commitments1))
        case Failure(cause) => handleLocalError(cause, d, Some(revocation))
      }

    case Event(c@CurrentBlockCount(count), d: DATA_SHUTDOWN) if d.commitments.hasTimedoutOutgoingHtlcs(count) =>
      handleLocalError(HtlcTimedout(d.channelId), d, Some(c))

    case Event(c@CurrentFeerates(feerates), d: DATA_SHUTDOWN) =>
      val networkFeeratePerKw = feerates.block_1
      d.commitments.localParams.isFunder match {
        case true if Helpers.shouldUpdateFee(d.commitments.localCommit.spec.feeratePerKw, networkFeeratePerKw, nodeParams.updateFeeMinDiffRatio) =>
          self ! CMD_UPDATE_FEE(networkFeeratePerKw, commit = true)
          stay
        case false if Helpers.isFeeDiffTooHigh(d.commitments.localCommit.spec.feeratePerKw, networkFeeratePerKw, nodeParams.maxFeerateMismatch) =>
          handleLocalError(FeerateTooDifferent(d.channelId, localFeeratePerKw = networkFeeratePerKw, remoteFeeratePerKw = d.commitments.localCommit.spec.feeratePerKw), d, Some(c))
        case _ => stay
      }

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_SHUTDOWN) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_SHUTDOWN) if Some(tx.txid) == d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_SHUTDOWN) => handleRemoteSpentOther(tx, d)

    case Event(c: CMD_CLOSE, d: DATA_SHUTDOWN) => handleCommandError(ClosingAlreadyInProgress(d.channelId), c)

    case Event(e: Error, d: DATA_SHUTDOWN) => handleRemoteError(e, d)

  })

  when(NEGOTIATING)(handleExceptions {
    case Event(c@ClosingSigned(_, remoteClosingFee, remoteSig), d: DATA_NEGOTIATING) =>
      log.info(s"received closingFeeSatoshis=$remoteClosingFee")
      Closing.checkClosingSignature(keyManager, d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, Satoshi(remoteClosingFee), remoteSig) match {
        case Success(signedClosingTx) if Some(remoteClosingFee) == d.closingTxProposed.last.lastOption.map(_.localClosingSigned.feeSatoshis) || d.closingTxProposed.flatten.size >= MAX_NEGOTIATION_ITERATIONS =>
          // we close when we converge or when there were too many iterations
          handleMutualClose(signedClosingTx, Left(d.copy(bestUnpublishedClosingTx_opt = Some(signedClosingTx))))
        case Success(signedClosingTx) =>
          // if we are fundee and we were waiting for them to send their first closing_signed, we don't have a lastLocalClosingFee, so we compute a firstClosingFee
          val lastLocalClosingFee = d.closingTxProposed.last.lastOption.map(_.localClosingSigned.feeSatoshis).map(Satoshi)
          val nextClosingFee = Closing.nextClosingFee(
            localClosingFee = lastLocalClosingFee.getOrElse(Closing.firstClosingFee(d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey)),
            remoteClosingFee = Satoshi(remoteClosingFee))
          val (closingTx, closingSigned) = Closing.makeClosingTx(keyManager, d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nextClosingFee)
          if (Some(nextClosingFee) == lastLocalClosingFee) {
            // next computed fee is the same than the one we previously sent (probably because of rounding), let's close now
            handleMutualClose(signedClosingTx, Left(d.copy(bestUnpublishedClosingTx_opt = Some(signedClosingTx))))
          } else if (nextClosingFee == Satoshi(remoteClosingFee)) {
            // we have converged!
            val closingTxProposed1 = d.closingTxProposed match {
              case previousNegotiations :+ currentNegotiation => previousNegotiations :+ (currentNegotiation :+ ClosingTxProposed(closingTx.tx, closingSigned))
            }
            handleMutualClose(signedClosingTx, Left(store(d.copy(closingTxProposed = closingTxProposed1, bestUnpublishedClosingTx_opt = Some(signedClosingTx))))) sending closingSigned
          } else {
            log.info(s"proposing closingFeeSatoshis=${closingSigned.feeSatoshis}")
            val closingTxProposed1 = d.closingTxProposed match {
              case previousNegotiations :+ currentNegotiation => previousNegotiations :+ (currentNegotiation :+ ClosingTxProposed(closingTx.tx, closingSigned))
            }
            stay using store(d.copy(closingTxProposed = closingTxProposed1, bestUnpublishedClosingTx_opt = Some(signedClosingTx))) sending closingSigned
          }
        case Failure(cause) => handleLocalError(cause, d, Some(c))
      }

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_NEGOTIATING) if d.closingTxProposed.flatten.map(_.unsignedTx.txid).contains(tx.txid) =>
      // they can publish a closing tx with any sig we sent them, even if we are not done negotiating
      handleMutualClose(tx, Left(d))

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_NEGOTIATING) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_NEGOTIATING) if Some(tx.txid) == d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_NEGOTIATING) => handleRemoteSpentOther(tx, d)

    case Event(c: CMD_CLOSE, d: DATA_NEGOTIATING) => handleCommandError(ClosingAlreadyInProgress(d.channelId), c)

    case Event(e: Error, d: DATA_NEGOTIATING) => handleRemoteError(e, d)

  })

  when(CLOSING)(handleExceptions {
    case Event(c: CMD_FULFILL_HTLC, d: DATA_CLOSING) =>
      Try(Commitments.sendFulfill(d.commitments, c)) match {
        case Success((commitments1, _)) =>
          log.info(s"got valid payment preimage, recalculating transactions to redeem the corresponding htlc on-chain")
          val localCommitPublished1 = d.localCommitPublished.map {
            case localCommitPublished =>
              val localCommitPublished1 = Helpers.Closing.claimCurrentLocalCommitTxOutputs(keyManager, commitments1, localCommitPublished.commitTx)
              doPublish(localCommitPublished1)
              localCommitPublished1
          }
          val remoteCommitPublished1 = d.remoteCommitPublished.map {
            case remoteCommitPublished =>
              val remoteCommitPublished1 = Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, commitments1, commitments1.remoteCommit, remoteCommitPublished.commitTx)
              doPublish(remoteCommitPublished1)
              remoteCommitPublished1
          }
          val nextRemoteCommitPublished1 = d.nextRemoteCommitPublished.map {
            case remoteCommitPublished =>
              val remoteCommitPublished1 = Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, commitments1, commitments1.remoteCommit, remoteCommitPublished.commitTx)
              doPublish(remoteCommitPublished1)
              remoteCommitPublished1
          }
          stay using store(d.copy(commitments = commitments1, localCommitPublished = localCommitPublished1, remoteCommitPublished = remoteCommitPublished1, nextRemoteCommitPublished = nextRemoteCommitPublished1))
        case Failure(cause) => handleCommandError(cause, c)
      }

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_CLOSING) =>
      if (d.mutualCloseProposed.map(_.txid).contains(tx.txid)) {
        // at any time they can publish a closing tx with any sig we sent them
        handleMutualClose(tx, Right(d))
      } else if (d.mutualClosePublished.map(_.txid).contains(tx.txid)) {
        // we have published a closing tx which isn't one that we proposed, and used it instead of our last commitment when an error happened
        handleMutualClose(tx, Right(d))
      } else if (Some(tx.txid) == d.localCommitPublished.map(_.commitTx.txid)) {
        // this is because WatchSpent watches never expire and we are notified multiple times
        stay
      } else if (Some(tx.txid) == d.remoteCommitPublished.map(_.commitTx.txid)) {
        // this is because WatchSpent watches never expire and we are notified multiple times
        stay
      } else if (Some(tx.txid) == d.nextRemoteCommitPublished.map(_.commitTx.txid)) {
        // this is because WatchSpent watches never expire and we are notified multiple times
        stay
      } else if (Some(tx.txid) == d.futureRemoteCommitPublished.map(_.commitTx.txid)) {
        // this is because WatchSpent watches never expire and we are notified multiple times
        stay
      } else if (tx.txid == d.commitments.remoteCommit.txid) {
        // counterparty may attempt to spend its last commit tx at any time
        handleRemoteSpentCurrent(tx, d)
      } else if (Some(tx.txid) == d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid)) {
        // counterparty may attempt to spend its last commit tx at any time
        handleRemoteSpentNext(tx, d)
      } else {
        // counterparty may attempt to spend a revoked commit tx at any time
        handleRemoteSpentOther(tx, d)
      }

    case Event(WatchEventSpent(BITCOIN_OUTPUT_SPENT, tx), d: DATA_CLOSING) =>
      // one of the outputs of the local/remote/revoked commit was spent
      // we just put a watch to be notified when it is confirmed
      blockchain ! WatchConfirmed(self, tx, nodeParams.minDepthBlocks, BITCOIN_TX_CONFIRMED(tx))
      // when a remote or local commitment tx containing outgoing htlcs is published on the network,
      // we watch it in order to extract payment preimage if funds are pulled by the counterparty
      // we can then use these preimages to fulfill origin htlcs
      log.warning(s"processing BITCOIN_OUTPUT_SPENT with txid=${tx.txid} tx=$tx")
      val extracted = Closing.extractPreimages(d.commitments.localCommit, tx)
      extracted map { case (htlc, fulfill) =>
        val origin = d.commitments.originChannels(fulfill.id)
        log.warning(s"fulfilling htlc #${fulfill.id} paymentHash=${sha256(fulfill.paymentPreimage)} origin=$origin")
        relayer ! ForwardFulfill(fulfill, origin, htlc)
      }
      stay

    case Event(WatchEventConfirmed(BITCOIN_TX_CONFIRMED(tx), _, _), d: DATA_CLOSING) =>
      log.info(s"txid=${tx.txid} has reached mindepth, updating closing state")
      // first we check if this tx belongs to a one of the current local/remote commits and update it
      val localCommitPublished1 = d.localCommitPublished.map(Closing.updateLocalCommitPublished(_, tx))
      val remoteCommitPublished1 = d.remoteCommitPublished.map(Closing.updateRemoteCommitPublished(_, tx))
      val nextRemoteCommitPublished1 = d.nextRemoteCommitPublished.map(Closing.updateRemoteCommitPublished(_, tx))
      val futureRemoteCommitPublished1 = d.futureRemoteCommitPublished.map(Closing.updateRemoteCommitPublished(_, tx))
      val revokedCommitPublished1 = d.revokedCommitPublished.map(Closing.updateRevokedCommitPublished(_, tx))
      // we may need to fail some htlcs in case a commitment tx was published and they have reached the timeout threshold
      val timedoutHtlcs =
        Closing.timedoutHtlcs(d.commitments.localCommit, Satoshi(d.commitments.localParams.dustLimitSatoshis), tx) ++
          Closing.timedoutHtlcs(d.commitments.remoteCommit, Satoshi(d.commitments.remoteParams.dustLimitSatoshis), tx) ++
          d.commitments.remoteNextCommitInfo.left.toSeq.flatMap(r => Closing.timedoutHtlcs(r.nextRemoteCommit, Satoshi(d.commitments.remoteParams.dustLimitSatoshis), tx))
      timedoutHtlcs.foreach { add =>
        val origin = d.commitments.originChannels(add.id)
        log.warning(s"failing htlc #${add.id} paymentHash=${add.paymentHash} origin=$origin: htlc timed out")
        relayer ! Status.Failure(AddHtlcFailed(d.channelId, add.paymentHash, HtlcTimedout(d.channelId), origin, None))
      }
      // then let's see if any of the possible close scenarii can be considered done
      val mutualCloseDone = d.mutualClosePublished.exists(_.txid == tx.txid) // this case is trivial, in a mutual close scenario we only need to make sure that one of the closing txes is confirmed
      val localCommitDone = localCommitPublished1.map(Closing.isLocalCommitDone(_)).getOrElse(false)
      val remoteCommitDone = remoteCommitPublished1.map(Closing.isRemoteCommitDone(_)).getOrElse(false)
      val nextRemoteCommitDone = nextRemoteCommitPublished1.map(Closing.isRemoteCommitDone(_)).getOrElse(false)
      val futureRemoteCommitDone = futureRemoteCommitPublished1.map(Closing.isRemoteCommitDone(_)).getOrElse(false)
      val revokedCommitDone = revokedCommitPublished1.map(Closing.isRevokedCommitDone(_)).exists(_ == true) // we only need one revoked commit done
    // finally, if one of the unilateral closes is done, we move to CLOSED state, otherwise we stay (note that we don't store the state)
    val d1 = d.copy(localCommitPublished = localCommitPublished1, remoteCommitPublished = remoteCommitPublished1, nextRemoteCommitPublished = nextRemoteCommitPublished1, futureRemoteCommitPublished = futureRemoteCommitPublished1, revokedCommitPublished = revokedCommitPublished1)
      val closeType_opt = if (mutualCloseDone) {
        Some("mutual")
      } else if (localCommitDone) {
        Some("local")
      } else if (remoteCommitDone || nextRemoteCommitDone) {
        Some("remote")
      } else if (futureRemoteCommitDone) {
        Some("recovery")
      } else if (revokedCommitDone) {
        Some("revoked")
      } else {
        None
      }
      closeType_opt match {
        case Some(closeType) =>
          log.info(s"channel closed (type=$closeType)")
          goto(CLOSED) using store(d1)
        case None =>
          stay using store(d1)
      }

    case Event(_: ChannelReestablish, d: DATA_CLOSING) =>
      // they haven't detected that we were closing and are trying to reestablish a connection
      // we give them one of the published txes as a hint
      // note spendingTx != Nil (that's a requirement of DATA_CLOSING)
      val exc = FundingTxSpent(d.channelId, d.spendingTxes.head)
      val error = Error(d.channelId, exc.getMessage.getBytes)
      stay sending error

    case Event(c: CMD_CLOSE, d: DATA_CLOSING) => handleCommandError(ClosingAlreadyInProgress(d.channelId), c)

    case Event(e: Error, d: DATA_CLOSING) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED | INPUT_RECONNECTED(_), _) => stay // we don't really care at this point
  })

  when(CLOSED)(handleExceptions {
    case Event('shutdown, _) =>
      stateData match {
        case d: HasCommitments =>
          log.info(s"deleting database record for channelId=${d.channelId}")
          nodeParams.channelsDb.removeChannel(d.channelId)
        case _ => {}
      }
      log.info("shutting down")
      stop(FSM.Normal)

    case Event(MakeFundingTxResponse(fundingTx, _), _) =>
      // this may happen if connection is lost, or remote sends an error while we were waiting for the funding tx to be created by our wallet
      // in that case we rollback the tx
      wallet.rollback(fundingTx)
      stay

    case Event(INPUT_DISCONNECTED, _) => stay // we are disconnected, but it doesn't matter anymore
  })

  when(OFFLINE)(handleExceptions {
    case Event(INPUT_RECONNECTED(r), d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) =>
      forwarder ! r
      // they already proved that we have an outdated commitment
      // there isn't much to do except asking them again to publish their current commitment by sending an error
      val exc = PleasePublishYourCommitment(d.channelId)
      val error = Error(d.channelId, exc.getMessage.getBytes)
      goto(WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) sending error

    case Event(INPUT_RECONNECTED(r), d: HasCommitments) =>
      forwarder ! r

      val yourLastPerCommitmentSecret = d.commitments.remotePerCommitmentSecrets.lastIndex.flatMap(d.commitments.remotePerCommitmentSecrets.getHash).getOrElse(Sphinx zeroes 32)
      val myCurrentPerCommitmentPoint = keyManager.commitmentPoint(d.commitments.localParams.channelKeyPath, d.commitments.localCommit.index)

      val channelReestablish = ChannelReestablish(
        channelId = d.channelId,
        nextLocalCommitmentNumber = d.commitments.localCommit.index + 1,
        nextRemoteRevocationNumber = d.commitments.remoteCommit.index,
        yourLastPerCommitmentSecret = Some(Scalar(yourLastPerCommitmentSecret)),
        myCurrentPerCommitmentPoint = Some(myCurrentPerCommitmentPoint)
      )

      goto(SYNCING) sending channelReestablish

    case Event(c@CurrentBlockCount(count), d: HasCommitments) if d.commitments.hasTimedoutOutgoingHtlcs(count) =>
      // note: this can only happen if state is NORMAL or SHUTDOWN
      // -> in NEGOTIATING there are no more htlcs
      // -> in CLOSING we either have mutual closed (so no more htlcs), or already have unilaterally closed (so no action required), and we can't be in OFFLINE state anyway
      handleLocalError(HtlcTimedout(d.channelId), d, Some(c))

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_NEGOTIATING) if d.closingTxProposed.flatten.map(_.unsignedTx.txid).contains(tx.txid) => handleMutualClose(tx, Left(d))

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: HasCommitments) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: HasCommitments) if d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid).contains(tx.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) => handleRemoteSpentFuture(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: HasCommitments) => handleRemoteSpentOther(tx, d)

  })

  when(SYNCING)(handleExceptions {
    case Event(_: ChannelReestablish, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      // we put back the watch (operation is idempotent) because the event may have been fired while we were in OFFLINE
      blockchain ! WatchConfirmed(self, d.commitments.commitInput.outPoint.txid, d.commitments.commitInput.txOut.publicKeyScript, nodeParams.minDepthBlocks, BITCOIN_FUNDING_DEPTHOK)
      goto(WAIT_FOR_FUNDING_CONFIRMED)

    case Event(_: ChannelReestablish, d: DATA_WAIT_FOR_FUNDING_LOCKED) =>
      log.debug(s"re-sending fundingLocked")
      val nextPerCommitmentPoint = keyManager.commitmentPoint(d.commitments.localParams.channelKeyPath, 1)
      val fundingLocked = FundingLocked(d.commitments.channelId, nextPerCommitmentPoint)
      goto(WAIT_FOR_FUNDING_LOCKED) sending fundingLocked

    case Event(channelReestablish@ChannelReestablish(_, _, nextRemoteRevocationNumber, Some(yourLastPerCommitmentSecret), _), d: DATA_NORMAL)
      if d.commitments.localCommit.index < nextRemoteRevocationNumber =>
      // if next_remote_revocation_number is greater than our local commitment index, it means that either we are using an outdated commitment, or they are lying
      // but first we need to make sure that the last per_commitment_secret that they claim to have received from us is correct for that next_remote_revocation_number minus 1
      if (keyManager.commitmentSecret(d.commitments.localParams.channelKeyPath, nextRemoteRevocationNumber - 1) == yourLastPerCommitmentSecret) {
        log.warning(s"counterparty proved that we have an outdated (revoked) local commitment!!! ourCommitmentNumber=${d.commitments.localCommit.index} theirCommitmentNumber=${nextRemoteRevocationNumber}")
        // their data checks out, we indeed seem to be using an old revoked commitment, and must absolutely *NOT* publish it, because that would be a cheating attempt and they
        // would punish us by taking all the funds in the channel
        val exc = PleasePublishYourCommitment(d.channelId)
        val error = Error(d.channelId, exc.getMessage.getBytes)
        goto(WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) using store(DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(d.commitments, channelReestablish)) sending error
      } else {
        // they lied! the last per_commitment_secret they claimed to have received from us is invalid
        throw CommitmentSyncError(d.channelId)
      }

    case Event(channelReestablish: ChannelReestablish, d: DATA_NORMAL) =>
      if (channelReestablish.nextLocalCommitmentNumber == 1 && d.commitments.localCommit.index == 0) {
        // If next_local_commitment_number is 1 in both the channel_reestablish it sent and received, then the node MUST retransmit funding_locked, otherwise it MUST NOT
        log.debug(s"re-sending fundingLocked")
        val nextPerCommitmentPoint = keyManager.commitmentPoint(d.commitments.localParams.channelKeyPath, 1)
        val fundingLocked = FundingLocked(d.commitments.channelId, nextPerCommitmentPoint)
        forwarder ! fundingLocked
      }

      val commitments1 = handleSync(channelReestablish, d)

      // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
      d.localShutdown.foreach {
        localShutdown =>
          log.debug(s"re-sending localShutdown")
          forwarder ! localShutdown
      }

      if (!d.buried) {
        // even if we were just disconnected/reconnected, we need to put back the watch because the event may have been
        // fired while we were in OFFLINE (if not, the operation is idempotent anyway)
        blockchain ! WatchConfirmed(self, d.commitments.commitInput.outPoint.txid, d.commitments.commitInput.txOut.publicKeyScript, ANNOUNCEMENTS_MINCONF, BITCOIN_FUNDING_DEEPLYBURIED)
      } else {
        // channel has been buried enough, should we (re)send our announcement sigs?
        d.channelAnnouncement match {
          case None if !d.commitments.announceChannel =>
            // that's a private channel, nothing to do
            ()
          case None =>
            // BOLT 7: a node SHOULD retransmit the announcement_signatures message if it has not received an announcement_signatures message
            val localAnnSigs = Helpers.makeAnnouncementSignatures(nodeParams, d.commitments, d.shortChannelId)
            forwarder ! localAnnSigs
          case Some(_) =>
            // channel was already announced, nothing to do
            ()
        }
      }
      // re-enable the channel
      val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, d.shortChannelId, nodeParams.expiryDeltaBlocks, d.commitments.remoteParams.htlcMinimumMsat, nodeParams.feeBaseMsat, nodeParams.feeProportionalMillionth, enable = true)

      goto(NORMAL) using d.copy(commitments = commitments1, channelUpdate = channelUpdate)

    case Event(channelReestablish: ChannelReestablish, d: DATA_SHUTDOWN) =>
      val commitments1 = handleSync(channelReestablish, d)
      // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
      goto(SHUTDOWN) using d.copy(commitments = commitments1) sending d.localShutdown

    case Event(_: ChannelReestablish, d: DATA_NEGOTIATING) =>
      // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
      // negotiation restarts from the beginning, and is initialized by the funder
      // note: in any case we still need to keep all previously sent closing_signed, because they may publish one of them
      if (d.commitments.localParams.isFunder) {
        // we could use the last closing_signed we sent, but network fees may have changed while we were offline so it is better to restart from scratch
        val (closingTx, closingSigned) = Closing.makeFirstClosingTx(keyManager, d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey)
        val closingTxProposed1 = d.closingTxProposed :+ List(ClosingTxProposed(closingTx.tx, closingSigned))
        goto(NEGOTIATING) using store(d.copy(closingTxProposed = closingTxProposed1)) sending d.localShutdown :: closingSigned :: Nil
      } else {
        // we start a new round of negotiation
        val closingTxProposed1 = if (d.closingTxProposed.last.isEmpty) d.closingTxProposed else d.closingTxProposed :+ List()
        goto(NEGOTIATING) using d.copy(closingTxProposed = closingTxProposed1) sending d.localShutdown
      }

    case Event(c@CurrentBlockCount(count), d: HasCommitments) if d.commitments.hasTimedoutOutgoingHtlcs(count) => handleLocalError(HtlcTimedout(d.channelId), d, Some(c))

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_NEGOTIATING) if d.closingTxProposed.flatten.map(_.unsignedTx.txid).contains(tx.txid) => handleMutualClose(tx, Left(d))

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: HasCommitments) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: HasCommitments) if d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.txid).contains(tx.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: HasCommitments) => handleRemoteSpentOther(tx, d)

    case Event(e: Error, d: HasCommitments) => handleRemoteError(e, d)
  })

  when(WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)(handleExceptions {
    case Event(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx), d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) => handleRemoteSpentFuture(tx, d)
  })

  def errorStateHandler: StateFunction = {
    case Event('nevermatches, _) => stay // we can't define a state with no event handler, so we put a dummy one here
  }

  when(ERR_INFORMATION_LEAK)(errorStateHandler)

  when(ERR_FUNDING_TIMEOUT)(errorStateHandler)

  when(ERR_FUNDING_LOST)(errorStateHandler)

  whenUnhandled {

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

    case Event(c: CMD_ADD_HTLC, d: HasCommitments) =>
      log.info(s"rejecting htlc request in state=$stateName")
      val error = ChannelUnavailable(d.channelId)
      d match {
        case normal: DATA_NORMAL => handleCommandError(AddHtlcFailed(d.channelId, c.paymentHash, error, origin(c), Some(normal.channelUpdate)), c) // can happen if we are in OFFLINE or SYNCING state (channelUpdate will have enable=false)
        case _ => handleCommandError(AddHtlcFailed(d.channelId, c.paymentHash, error, origin(c), None), c) // we don't provide a channel_update: this will be a permanent channel failure
      }

    case Event(c: CMD_CLOSE, d) => handleCommandError(CannotCloseInThisState(Helpers.getChannelId(d), stateName), c)

    case Event(c@CMD_FORCECLOSE, d) =>
      d match {
        case data: HasCommitments => handleLocalError(ForcedLocalCommit(data.channelId, "forced local commit"), data, Some(c)) replying "ok"
        case _ => handleCommandError(CannotCloseInThisState(Helpers.getChannelId(d), stateName), c)
      }

    // we only care about this event in NORMAL and SHUTDOWN state, and we never unregister to the event stream
    case Event(CurrentBlockCount(_), _) => stay

    // we only care about this event in NORMAL and SHUTDOWN state, and we never unregister to the event stream
    case Event(CurrentFeerates(_), _) => stay

    // we only care about this event in NORMAL state, and the scheduler is never disabled
    case Event(TickRefreshChannelUpdate, _) => stay

    // we receive this when we send command to ourselves
    case Event("ok", _) => stay
  }

  onTransition {
    case WAIT_FOR_INIT_INTERNAL -> WAIT_FOR_INIT_INTERNAL => () // called at channel initialization
    case state -> nextState =>
      if (state != nextState) {
        context.system.eventStream.publish(ChannelStateChanged(self, context.parent, remoteNodeId, state, nextState, nextStateData))
      }
      if (nextState == CLOSED) {
        // channel is closed, scheduling this actor for self destruction
        context.system.scheduler.scheduleOnce(10 seconds, self, 'shutdown)
      }

      // if channel is private, we send the channel_update directly to remote
      // they need it "to learn the other end's forwarding parameters" (BOLT 7)
      (stateData, nextStateData) match {
        case (d1: DATA_NORMAL, d2: DATA_NORMAL) if !d1.commitments.announceChannel && !d1.buried && d2.buried =>
          // for a private channel, when the tx was just buried we need to send the channel_update to our peer (even if it didn't change)
          forwarder ! d2.channelUpdate
        case (d1: DATA_NORMAL, d2: DATA_NORMAL) if !d1.commitments.announceChannel && d1.channelUpdate != d2.channelUpdate && d2.buried =>
          // otherwise, we only send it when it is different, and tx is already buried
          forwarder ! d2.channelUpdate
        case _ => ()
      }

      (stateData, nextStateData) match {
        case (d1: DATA_NORMAL, d2: DATA_NORMAL) if d1.channelUpdate == d2.channelUpdate && d1.channelAnnouncement == d2.channelAnnouncement =>
          // don't do anything if neither the channel_update nor the channel_announcement didn't change
          ()
        case (_, normal: DATA_NORMAL) =>
          // whenever we go to a state with NORMAL data (can be OFFLINE or NORMAL), we send out the new channel_update (most of the time it will just be to enable/disable the channel)
          context.system.eventStream.publish(LocalChannelUpdate(self, normal.commitments.channelId, normal.shortChannelId, normal.commitments.remoteParams.nodeId, normal.channelAnnouncement, normal.channelUpdate))
        case (normal: DATA_NORMAL, _) =>
          // when we finally leave the NORMAL state (or OFFLINE with NORMAL data) to got to SHUTDOWN/NEGOTIATING/CLOSING/ERR*, we advertise the fact that channel can't be used for payments anymore
          // if the channel is private we don't really need to tell the counterparty because it is already aware that the channel is being closed
          context.system.eventStream.publish(LocalChannelDown(self, normal.commitments.channelId, normal.shortChannelId, normal.commitments.remoteParams.nodeId))
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

  /**
    * This function is used to return feedback to user at channel opening
    */
  def replyToUser(message: Either[Either[Throwable, Error], String]) = {
    val m = message match {
      case Left(Left(t)) => Status.Failure(t)
      case Left(Right(e)) => Status.Failure(new RuntimeException(s"peer sent error: '${if (isAsciiPrintable(e.data)) new String(e.data, StandardCharsets.US_ASCII) else e.data.toString()}'"))
      case Right(s) => s
    }
    origin_opt.map(_ ! m)
  }

  def handleCommandSuccess(sender: ActorRef, newData: Data) = {
    stay using newData replying "ok"
  }

  def handleCommandError(cause: Throwable, cmd: Command) = {
    log.error(s"${cause.getMessage} while processing cmd=${cmd.getClass.getSimpleName} in state=$stateName")
    cause match {
      case _: ChannelException => ()
      case _ => log.error(cause, s"msg=$cmd stateData=$stateData ")
    }
    stay replying Status.Failure(cause)
  }

  def handleLocalError(cause: Throwable, d: HasCommitments, msg: Option[Any]) = {
    cause match {
      case _: ForcedLocalCommit => log.warning(s"force-closing channel at user request")
      case _ => log.error(s"${cause.getMessage} while processing msg=${msg.getOrElse("n/a").getClass.getSimpleName} in state=$stateName")
    }
    cause match {
      case _: ChannelException => ()
      case _ => log.error(cause, s"msg=${msg.getOrElse("n/a")} stateData=$stateData ")
    }
    val error = Error(d.channelId, cause.getMessage.getBytes)

    d match {
      case negotiating@DATA_NEGOTIATING(_, _, _, _, Some(bestUnpublishedClosingTx)) =>
        log.info(s"we have a valid closing tx, publishing it instead of our commitment: closingTxId=${bestUnpublishedClosingTx.txid}")
        // if we were in the process of closing and already received a closing sig from the counterparty, it's always better to use that
        handleMutualClose(bestUnpublishedClosingTx, Left(negotiating))
      case _ =>
        // otherwise we use our latest commitment
        spendLocalCurrent(d) sending error
    }
  }

  def handleRemoteError(e: Error, d: Data) = {
    // see BOLT 1: only print out data verbatim if is composed of printable ASCII characters
    log.error(s"peer sent error: ascii='${if (isAsciiPrintable(e.data)) new String(e.data, StandardCharsets.US_ASCII) else "n/a"}' bin=${e.data}")
    d match {
      case _: DATA_CLOSING => stay // nothing to do, there is already a spending tx published
      case negotiating@DATA_NEGOTIATING(_, _, _, _, Some(bestUnpublishedClosingTx)) =>
        // if we were in the process of closing and already received a closing sig from the counterparty, it's always better to use that
        handleMutualClose(bestUnpublishedClosingTx, Left(negotiating))
      case hasCommitments: HasCommitments => spendLocalCurrent(hasCommitments)
      case _ => goto(CLOSED) // when there is no commitment yet, we just go to CLOSED state in case an error occurs
    }
  }

  def handleMutualClose(closingTx: Transaction, d: Either[DATA_NEGOTIATING, DATA_CLOSING]) = {
    log.info(s"closing tx published: closingTxId=${closingTx.txid}")

    doPublish(closingTx)

    val nextData = d match {
      case Left(negotiating) => DATA_CLOSING(negotiating.commitments, negotiating.closingTxProposed.flatten.map(_.unsignedTx), mutualClosePublished = closingTx :: Nil)
      case Right(closing) => closing.copy(mutualClosePublished = closing.mutualClosePublished :+ closingTx)
    }
    goto(CLOSING) using store(nextData)
  }

  def doPublish(closingTx: Transaction) = {
    blockchain ! PublishAsap(closingTx)
    blockchain ! WatchConfirmed(self, closingTx, nodeParams.minDepthBlocks, BITCOIN_TX_CONFIRMED(closingTx))
  }

  def spendLocalCurrent(d: HasCommitments) = {

    val outdatedCommitment = d match {
      case _: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT => true
      case closing: DATA_CLOSING if closing.futureRemoteCommitPublished.isDefined => true
      case _ => false
    }

    if (outdatedCommitment) {
      log.warning("we have an outdated commitment: will not publish our local tx")
      stay
    } else {
      val commitTx = d.commitments.localCommit.publishableTxs.commitTx.tx

      val localCommitPublished = Helpers.Closing.claimCurrentLocalCommitTxOutputs(keyManager, d.commitments, commitTx)
      doPublish(localCommitPublished)

    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(localCommitPublished = Some(localCommitPublished))
      case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, negotiating.closingTxProposed.flatten.map(_.unsignedTx), localCommitPublished = Some(localCommitPublished))
      case _ => DATA_CLOSING(d.commitments, mutualCloseProposed = Nil, localCommitPublished = Some(localCommitPublished))
    }

      goto(CLOSING) using store(nextData)
    }
  }

  /**
    * This helper method will publish txes only if they haven't yet reached minDepth
    *
    * @param txes
    * @param irrevocablySpent
    */
  def publishIfNeeded(txes: Iterable[Transaction], irrevocablySpent: Map[OutPoint, BinaryData]) = {
    val (skip, process) = txes.partition(Closing.inputsAlreadySpent(_, irrevocablySpent))
    process.foreach(tx => blockchain ! PublishAsap(tx))
    skip.foreach(tx => log.info(s"no need to republish txid=${tx.txid}, it has already been confirmed"))
  }

  /**
    * This helper method will watch txes only if they haven't yet reached minDepth
    *
    * @param txes
    * @param irrevocablySpent
    */
  def watchConfirmedIfNeeded(txes: Iterable[Transaction], irrevocablySpent: Map[OutPoint, BinaryData]) = {
    val (skip, process) = txes.partition(Closing.inputsAlreadySpent(_, irrevocablySpent))
    process.foreach(tx => blockchain ! WatchConfirmed(self, tx, nodeParams.minDepthBlocks, BITCOIN_TX_CONFIRMED(tx)))
    skip.foreach(tx => log.info(s"no need to watch txid=${tx.txid}, it has already been confirmed"))
  }

  /**
    * This helper method will watch txes only if the utxo they spend hasn't already been irrevocably spent
    *
    * @param parentTx
    * @param txes
    * @param irrevocablySpent
    */
  def watchSpentIfNeeded(parentTx: Transaction, txes: Iterable[Transaction], irrevocablySpent: Map[OutPoint, BinaryData]) = {
    val (skip, process) = txes.partition(Closing.inputsAlreadySpent(_, irrevocablySpent))
    process.foreach(tx => blockchain ! WatchSpent(self, parentTx, tx.txIn.head.outPoint.index.toInt, BITCOIN_OUTPUT_SPENT))
    skip.foreach(tx => log.info(s"no need to watch txid=${tx.txid}, it has already been confirmed"))
  }

  def doPublish(localCommitPublished: LocalCommitPublished) = {
    import localCommitPublished._

    val publishQueue = List(commitTx) ++ claimMainDelayedOutputTx ++ htlcSuccessTxs ++ htlcTimeoutTxs ++ claimHtlcDelayedTx
    publishIfNeeded(publishQueue, irrevocablySpent)

    // we watch:
    // - the commitment tx itself, so that we can handle the case where we don't have any outputs
    // - 'final txes' that send funds to our wallet and that spend outputs that only us control
    val watchConfirmedQueue = List(commitTx) ++ claimMainDelayedOutputTx ++ claimHtlcDelayedTx
    watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent)

    // we watch outputs of the commitment tx that both parties may spend
    val watchSpentQueue = htlcSuccessTxs ++ htlcTimeoutTxs
    watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent)
  }

  def handleRemoteSpentCurrent(commitTx: Transaction, d: HasCommitments) = {
    log.warning(s"they published their current commit in txid=${commitTx.txid}")
    require(commitTx.txid == d.commitments.remoteCommit.txid, "txid mismatch")

    val remoteCommitPublished = Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, d.commitments, d.commitments.remoteCommit, commitTx)
    doPublish(remoteCommitPublished)

    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(remoteCommitPublished = Some(remoteCommitPublished))
      case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, negotiating.closingTxProposed.flatten.map(_.unsignedTx), remoteCommitPublished = Some(remoteCommitPublished))
      case _ => DATA_CLOSING(d.commitments, mutualCloseProposed = Nil, remoteCommitPublished = Some(remoteCommitPublished))
    }

    goto(CLOSING) using store(nextData)
  }

  def handleRemoteSpentFuture(commitTx: Transaction, d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) = {
    log.warning(s"they published their future commit (because we asked them to) in txid=${commitTx.txid}")
    // if we are in this state, then this field is defined
    val remotePerCommitmentPoint = d.remoteChannelReestablish.myCurrentPerCommitmentPoint.get
    val remoteCommitPublished = Helpers.Closing.claimRemoteCommitMainOutput(keyManager, d.commitments, remotePerCommitmentPoint, commitTx)
    val nextData = DATA_CLOSING(d.commitments, Nil, futureRemoteCommitPublished = Some(remoteCommitPublished))

    doPublish(remoteCommitPublished)
    goto(CLOSING) using store(nextData)
  }

  def handleRemoteSpentNext(commitTx: Transaction, d: HasCommitments) = {
    log.warning(s"they published their next commit in txid=${commitTx.txid}")
    require(d.commitments.remoteNextCommitInfo.isLeft, "next remote commit must be defined")
    val remoteCommit = d.commitments.remoteNextCommitInfo.left.get.nextRemoteCommit
    require(commitTx.txid == remoteCommit.txid, "txid mismatch")

    val remoteCommitPublished = Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, d.commitments, remoteCommit, commitTx)
    doPublish(remoteCommitPublished)

    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(nextRemoteCommitPublished = Some(remoteCommitPublished))
      case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, negotiating.closingTxProposed.flatten.map(_.unsignedTx), nextRemoteCommitPublished = Some(remoteCommitPublished))
      case _ => DATA_CLOSING(d.commitments, mutualCloseProposed = Nil, nextRemoteCommitPublished = Some(remoteCommitPublished))
    }

    goto(CLOSING) using store(nextData)
  }

  def doPublish(remoteCommitPublished: RemoteCommitPublished) = {
    import remoteCommitPublished._

    val publishQueue = claimMainOutputTx ++ claimHtlcSuccessTxs ++ claimHtlcTimeoutTxs
    publishIfNeeded(publishQueue, irrevocablySpent)

    // we watch:
    // - the commitment tx itself, so that we can handle the case where we don't have any outputs
    // - 'final txes' that send funds to our wallet and that spend outputs that only us control
    val watchConfirmedQueue = List(commitTx) ++ claimMainOutputTx
    watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent)

    // we watch outputs of the commitment tx that both parties may spend
    val watchSpentQueue = claimHtlcTimeoutTxs ++ claimHtlcSuccessTxs
    watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent)
  }

  def handleRemoteSpentOther(tx: Transaction, d: HasCommitments) = {
    log.warning(s"funding tx spent in txid=${tx.txid}")

    Helpers.Closing.claimRevokedRemoteCommitTxOutputs(keyManager, d.commitments, tx) match {
      case Some(revokedCommitPublished) =>
        log.warning(s"txid=${tx.txid} was a revoked commitment, publishing the penalty tx")
        val exc = FundingTxSpent(d.channelId, tx)
        val error = Error(d.channelId, exc.getMessage.getBytes)

        doPublish(revokedCommitPublished)

        val nextData = d match {
          case closing: DATA_CLOSING => closing.copy(revokedCommitPublished = closing.revokedCommitPublished :+ revokedCommitPublished)
          case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, negotiating.closingTxProposed.flatten.map(_.unsignedTx), revokedCommitPublished = revokedCommitPublished :: Nil)
          case _ => DATA_CLOSING(d.commitments, mutualCloseProposed = Nil, revokedCommitPublished = revokedCommitPublished :: Nil)
        }
        goto(CLOSING) using store(nextData) sending error
      case None =>
        // the published tx was neither their current commitment nor a revoked one
        log.error(s"couldn't identify txid=${tx.txid}, something very bad is going on!!!")
        goto(ERR_INFORMATION_LEAK)
    }
  }

  def doPublish(revokedCommitPublished: RevokedCommitPublished) = {
    import revokedCommitPublished._

    val publishQueue = claimMainOutputTx ++ mainPenaltyTx ++ claimHtlcTimeoutTxs ++ htlcTimeoutTxs ++ htlcPenaltyTxs
    publishIfNeeded(publishQueue, irrevocablySpent)

    // we watch:
    // - the commitment tx itself, so that we can handle the case where we don't have any outputs
    // - 'final txes' that send funds to our wallet and that spend outputs that only us control
    val watchConfirmedQueue = List(commitTx) ++ claimMainOutputTx ++ htlcPenaltyTxs
    watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent)

    // we watch outputs of the commitment tx that both parties may spend
    val watchSpentQueue = mainPenaltyTx ++ claimHtlcTimeoutTxs ++ htlcTimeoutTxs
    watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent)
  }

  def handleInformationLeak(tx: Transaction, d: HasCommitments) = {
    // this is never supposed to happen !!
    log.error(s"our funding tx ${d.commitments.commitInput.outPoint.txid} was spent by txid=${tx.txid} !!")
    val exc = FundingTxSpent(d.channelId, tx)
    val error = Error(d.channelId, exc.getMessage.getBytes)

    // let's try to spend our current local tx
    val commitTx = d.commitments.localCommit.publishableTxs.commitTx.tx
    val localCommitPublished = Helpers.Closing.claimCurrentLocalCommitTxOutputs(keyManager, d.commitments, commitTx)
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
        val localPerCommitmentSecret = keyManager.commitmentSecret(commitments1.localParams.channelKeyPath, d.commitments.localCommit.index - 1)
        val localNextPerCommitmentPoint = keyManager.commitmentPoint(commitments1.localParams.channelKeyPath, d.commitments.localCommit.index + 1)
        val revocation = RevokeAndAck(
          channelId = commitments1.channelId,
          perCommitmentSecret = localPerCommitmentSecret,
          nextPerCommitmentPoint = localNextPerCommitmentPoint
        )
        forwarder ! revocation
      } else throw RevocationSyncError(d.channelId)
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
        // there wasn't any sig in-flight when the disconnection occurred
        resendRevocation
      case _ => throw CommitmentSyncError(d.channelId)
    }

    // let's now fail all pending htlc for which we are the final payee
    val htlcsToFail = commitments1.remoteCommit.spec.htlcs.collect {
      case DirectedHtlc(OUT, add) if Sphinx.parsePacket(nodeParams.privateKey, add.paymentHash, add.onionRoutingPacket)
        .map(_.nextPacket.isLastPacket).getOrElse(true) => add // we also fail htlcs which onion we can't decode (message won't be precise)
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
          case d: HasCommitments =>
            handleLocalError(t, d, None)
          case d: Data =>
            log.error(t, "")
            val error = Error(Helpers.getChannelId(d), t.getMessage.getBytes)
            goto(CLOSED) sending error
        }
      }
  }

  def origin(c: CMD_ADD_HTLC): Origin = c.upstream_opt match {
    case None => Local(Some(sender))
    case Some(u) => Relayed(u.channelId, u.id, u.amountMsat, c.amountMsat)
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

  }

  override def mdc(currentMessage: Any): MDC = {
    val id = Helpers.getChannelId(stateData)
    Map("channelId" -> id)
  }

  // we let the peer decide what to do
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Escalate }

  initialize()

}




