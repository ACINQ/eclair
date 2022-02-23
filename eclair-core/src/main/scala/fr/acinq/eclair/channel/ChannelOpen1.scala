/*
 * Copyright 2022 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.channel

import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.actor.{ActorRef, FSM, Props, Status, typed}
import akka.event.Logging.MDC
import akka.pattern.pipe
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, SatoshiLong, Script, ScriptFlags, Transaction}
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.blockchain.OnChainChannelFunder
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.Channel._
import fr.acinq.eclair.channel.ChannelData.{WaitingForFundingConfirmed, WaitingForFundingLocked}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.transactions.Transactions.TxOwner
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.wire.protocol.{AcceptChannel, ChannelTlv, Error, FundingCreated, FundingLocked, FundingSigned, LightningMessage, OpenChannel, TlvStream}
import fr.acinq.eclair.{BlockHeight, FSMDiagnosticActorLogging, Features, Logs, NodeParams, NotificationsLogger, ShortChannelId, randomKey, toLongId}
import scodec.bits.ByteVector

import java.sql.SQLException
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

/**
 * Created by t-bast on 03/02/2022.
 */

/**
 * This actor follows the initial version of the channel open protocol.
 * In this version there is a funder and a fundee.
 * Only the funder can put funds in the channel and pays the on-chain fees.
 */
object ChannelOpen1 {

  def props(nodeParams: NodeParams, wallet: OnChainChannelFunder, remoteNodeId: PublicKey, channel: ActorRef, peer: ActorRef, blockchain: typed.ActorRef[ZmqWatcher.Command], origin_opt: Option[ActorRef]): Props = Props(new ChannelOpen1(nodeParams, wallet, remoteNodeId, channel, peer, blockchain, origin_opt))

  case class Restore(data: ChannelOpenData with PersistentChannelData)

  // @formatter:off
  sealed trait Action
  case class SendMessage(message: LightningMessage) extends Action
  case class UpdateData(data: ChannelOpenData) extends Action
  case class HandleGetFundingTx(getTxResponse: GetTxWithMetaResponse, waitingSince: BlockHeight, fundingTx_opt: Option[Transaction]) extends Action
  case class HandleLocalError(cause: Throwable, msg: Option[Any]) extends Action
  case class HandleRemoteError(e: Error) extends Action
  case class HandleRemoteSpentCurrent(commitTx: Transaction, d: ChannelOpenData with PersistentChannelData) extends Action
  case class HandleInformationLeak(tx: Transaction, d: ChannelOpenData with PersistentChannelData) extends Action
  case object CancelChannelOpen extends Action
  case class Disconnected(data: ChannelOpenData with PersistentChannelData) extends Action
  case class ChannelOpened(shortChannelId: ShortChannelId, commitments: Commitments) extends Action
  // @formatter:on

  // @formatter:off
  sealed trait State
  case object WAIT_FOR_INIT_INTERNAL extends State
  case object WAIT_FOR_OPEN_CHANNEL extends State
  case object WAIT_FOR_ACCEPT_CHANNEL extends State
  case object WAIT_FOR_FUNDING_INTERNAL extends State
  case object WAIT_FOR_FUNDING_CREATED extends State
  case object WAIT_FOR_FUNDING_SIGNED extends State
  case object WAIT_FOR_FUNDING_CONFIRMED extends State
  case object WAIT_FOR_FUNDING_LOCKED extends State
  // @formatter:on

  // @formatter:off
  sealed trait Data { def channelId: ByteVector32 }
  case class DATA_WAIT_FOR_INIT_INTERNAL() extends Data { val channelId: ByteVector32 = ByteVector32.Zeroes }
  case class DATA_WAIT_FOR_OPEN_CHANNEL(data: ChannelData.WaitingForOpenChannel) extends Data { val channelId: ByteVector32 = data.initFundee.temporaryChannelId }
  case class DATA_WAIT_FOR_ACCEPT_CHANNEL(data: ChannelData.WaitingForAcceptChannel) extends Data { val channelId: ByteVector32 = data.initFunder.temporaryChannelId }
  case class DATA_WAIT_FOR_FUNDING_INTERNAL(data: ChannelData.WaitingForFundingInternal) extends Data { val channelId: ByteVector32 = data.temporaryChannelId }
  case class DATA_WAIT_FOR_FUNDING_CREATED(data: ChannelData.WaitingForFundingCreated) extends Data { val channelId: ByteVector32 = data.temporaryChannelId }
  case class DATA_WAIT_FOR_FUNDING_SIGNED(data: ChannelData.WaitingForFundingSigned) extends Data { val channelId: ByteVector32 = data.channelId }
  case class DATA_WAIT_FOR_FUNDING_CONFIRMED(data: ChannelData.WaitingForFundingConfirmed) extends Data { val channelId: ByteVector32 = data.channelId }
  case class DATA_WAIT_FOR_FUNDING_LOCKED(data: ChannelData.WaitingForFundingLocked) extends Data { val channelId: ByteVector32 = data.channelId }
  // @formatter:on

}

class ChannelOpen1(val nodeParams: NodeParams, val wallet: OnChainChannelFunder, remoteNodeId: PublicKey, channel: ActorRef, peer: ActorRef, blockchain: typed.ActorRef[ZmqWatcher.Command], origin_opt: Option[ActorRef] = None)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends FSM[ChannelOpen1.State, ChannelOpen1.Data] with FSMDiagnosticActorLogging[ChannelOpen1.State, ChannelOpen1.Data] {

  import ChannelOpen1._

  private val keyManager: ChannelKeyManager = nodeParams.channelKeyManager

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

  startWith(WAIT_FOR_INIT_INTERNAL, DATA_WAIT_FOR_INIT_INTERNAL())

  when(WAIT_FOR_INIT_INTERNAL)(handleExceptions {
    case Event(initFunder@INPUT_INIT_FUNDER(temporaryChannelId, fundingSatoshis, pushMsat, initialFeeratePerKw, _, localParams, _, remoteInit, channelFlags, channelConfig, channelType), _: DATA_WAIT_FOR_INIT_INTERNAL) =>
      val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
      val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
      // In order to allow TLV extensions and keep backwards-compatibility, we include an empty upfront_shutdown_script if this feature is not used
      // See https://github.com/lightningnetwork/lightning-rfc/pull/714.
      val localShutdownScript = if (Features.canUseFeature(localParams.initFeatures, remoteInit.features, Features.UpfrontShutdownScript)) localParams.defaultFinalScriptPubKey else ByteVector.empty
      val open = OpenChannel(nodeParams.chainHash,
        temporaryChannelId = temporaryChannelId,
        fundingSatoshis = fundingSatoshis,
        pushMsat = pushMsat,
        dustLimitSatoshis = localParams.dustLimit,
        maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
        channelReserveSatoshis = localParams.channelReserve,
        htlcMinimumMsat = localParams.htlcMinimum,
        feeratePerKw = initialFeeratePerKw,
        toSelfDelay = localParams.toSelfDelay,
        maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
        fundingPubkey = fundingPubKey,
        revocationBasepoint = keyManager.revocationPoint(channelKeyPath).publicKey,
        paymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey),
        delayedPaymentBasepoint = keyManager.delayedPaymentPoint(channelKeyPath).publicKey,
        htlcBasepoint = keyManager.htlcPoint(channelKeyPath).publicKey,
        firstPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0),
        channelFlags = channelFlags,
        tlvStream = TlvStream(
          ChannelTlv.UpfrontShutdownScriptTlv(localShutdownScript),
          ChannelTlv.ChannelTypeTlv(channelType)
        ))
      channel ! SendMessage(open)
      goto(WAIT_FOR_ACCEPT_CHANNEL) using DATA_WAIT_FOR_ACCEPT_CHANNEL(ChannelData.WaitingForAcceptChannel(initFunder, open))

    case Event(inputFundee: INPUT_INIT_FUNDEE, _: DATA_WAIT_FOR_INIT_INTERNAL) if !inputFundee.localParams.isFunder =>
      goto(WAIT_FOR_OPEN_CHANNEL) using DATA_WAIT_FOR_OPEN_CHANNEL(ChannelData.WaitingForOpenChannel(inputFundee))

    case Event(Restore(data), _) =>
      data match {
        case d: WaitingForFundingConfirmed => goto(WAIT_FOR_FUNDING_CONFIRMED) using DATA_WAIT_FOR_FUNDING_CONFIRMED(d)
        case d: WaitingForFundingLocked => goto(WAIT_FOR_FUNDING_LOCKED) using DATA_WAIT_FOR_FUNDING_LOCKED(d)
      }
  })

  when(WAIT_FOR_OPEN_CHANNEL)(handleExceptions {
    case Event(open: OpenChannel, d: DATA_WAIT_FOR_OPEN_CHANNEL) =>
      import d.data.initFundee._
      Helpers.validateParamsFundee(nodeParams, channelType, localParams.initFeatures, open, remoteNodeId, remoteInit.features) match {
        case Left(t) =>
          channel ! HandleLocalError(t, Some(open))
          stop(FSM.Shutdown)
        case Right((channelFeatures, remoteShutdownScript)) =>
          context.system.eventStream.publish(ChannelCreated(channel, peer, remoteNodeId, isFunder = false, open.temporaryChannelId, open.feeratePerKw, None))
          val fundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
          val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
          val minimumDepth = Helpers.minDepthForFunding(nodeParams.channelConf, open.fundingSatoshis)
          // In order to allow TLV extensions and keep backwards-compatibility, we include an empty upfront_shutdown_script if this feature is not used.
          // See https://github.com/lightningnetwork/lightning-rfc/pull/714.
          val localShutdownScript = if (Features.canUseFeature(localParams.initFeatures, remoteInit.features, Features.UpfrontShutdownScript)) localParams.defaultFinalScriptPubKey else ByteVector.empty
          val accept = AcceptChannel(temporaryChannelId = open.temporaryChannelId,
            dustLimitSatoshis = localParams.dustLimit,
            maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
            channelReserveSatoshis = localParams.channelReserve,
            minimumDepth = minimumDepth,
            htlcMinimumMsat = localParams.htlcMinimum,
            toSelfDelay = localParams.toSelfDelay,
            maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
            fundingPubkey = fundingPubkey,
            revocationBasepoint = keyManager.revocationPoint(channelKeyPath).publicKey,
            paymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey),
            delayedPaymentBasepoint = keyManager.delayedPaymentPoint(channelKeyPath).publicKey,
            htlcBasepoint = keyManager.htlcPoint(channelKeyPath).publicKey,
            firstPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0),
            tlvStream = TlvStream(
              ChannelTlv.UpfrontShutdownScriptTlv(localShutdownScript),
              ChannelTlv.ChannelTypeTlv(channelType)
            ))
          val remoteParams = RemoteParams(
            nodeId = remoteNodeId,
            dustLimit = open.dustLimitSatoshis,
            maxHtlcValueInFlightMsat = open.maxHtlcValueInFlightMsat,
            channelReserve = open.channelReserveSatoshis, // remote requires local to keep this much satoshis as direct payment
            htlcMinimum = open.htlcMinimumMsat,
            toSelfDelay = open.toSelfDelay,
            maxAcceptedHtlcs = open.maxAcceptedHtlcs,
            fundingPubKey = open.fundingPubkey,
            revocationBasepoint = open.revocationBasepoint,
            paymentBasepoint = open.paymentBasepoint,
            delayedPaymentBasepoint = open.delayedPaymentBasepoint,
            htlcBasepoint = open.htlcBasepoint,
            initFeatures = remoteInit.features,
            shutdownScript = remoteShutdownScript)
          log.debug("remote params: {}", remoteParams)
          channel ! SendMessage(accept)
          val nextData = ChannelData.WaitingForFundingCreated(open.temporaryChannelId, localParams, remoteParams, open.fundingSatoshis, open.pushMsat, open.feeratePerKw, open.firstPerCommitmentPoint, open.channelFlags, channelConfig, channelFeatures, accept)
          goto(WAIT_FOR_FUNDING_CREATED) using DATA_WAIT_FOR_FUNDING_CREATED(nextData)
      }

    case Event(c: CloseCommand, d) => handleFastClose(c, d.channelId)

    case Event(e: Error, _) =>
      channel ! HandleRemoteError(e)
      stop(FSM.Shutdown)

    case Event(INPUT_DISCONNECTED, _) =>
      channel ! CancelChannelOpen
      stop(FSM.Shutdown)
  })

  when(WAIT_FOR_ACCEPT_CHANNEL)(handleExceptions {
    case Event(accept: AcceptChannel, d: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      import d.data.initFunder._
      Helpers.validateParamsFunder(nodeParams, channelType, localParams.initFeatures, remoteInit.features, d.data.lastSent, accept) match {
        case Left(t) =>
          channelOpenReplyToUser(Left(LocalError(t)))
          channel ! HandleLocalError(t, Some(accept))
          stop(FSM.Shutdown)
        case Right((channelFeatures, remoteShutdownScript)) =>
          val remoteParams = RemoteParams(
            nodeId = remoteNodeId,
            dustLimit = accept.dustLimitSatoshis,
            maxHtlcValueInFlightMsat = accept.maxHtlcValueInFlightMsat,
            channelReserve = accept.channelReserveSatoshis, // remote requires local to keep this much satoshis as direct payment
            htlcMinimum = accept.htlcMinimumMsat,
            toSelfDelay = accept.toSelfDelay,
            maxAcceptedHtlcs = accept.maxAcceptedHtlcs,
            fundingPubKey = accept.fundingPubkey,
            revocationBasepoint = accept.revocationBasepoint,
            paymentBasepoint = accept.paymentBasepoint,
            delayedPaymentBasepoint = accept.delayedPaymentBasepoint,
            htlcBasepoint = accept.htlcBasepoint,
            initFeatures = remoteInit.features,
            shutdownScript = remoteShutdownScript)
          log.debug("remote params: {}", remoteParams)
          val localFundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
          val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey.publicKey, remoteParams.fundingPubKey)))
          wallet.makeFundingTx(fundingPubkeyScript, fundingAmount, fundingTxFeeratePerKw).pipeTo(self)
          val nextData = ChannelData.WaitingForFundingInternal(temporaryChannelId, localParams, remoteParams, fundingAmount, pushAmount, initialFeeratePerKw, accept.firstPerCommitmentPoint, channelConfig, channelFeatures, d.data.lastSent)
          goto(WAIT_FOR_FUNDING_INTERNAL) using DATA_WAIT_FOR_FUNDING_INTERNAL(nextData)
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.data.lastSent.temporaryChannelId)))
      handleFastClose(c, d.data.lastSent.temporaryChannelId)

    case Event(e: Error, _) =>
      channelOpenReplyToUser(Left(RemoteError(e)))
      channel ! HandleRemoteError(e)
      stop(FSM.Shutdown)

    case Event(INPUT_DISCONNECTED, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      channel ! CancelChannelOpen
      stop(FSM.Shutdown)

    case Event(TickChannelOpenTimeout, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      channel ! CancelChannelOpen
      stop(FSM.Shutdown)
  })

  when(WAIT_FOR_FUNDING_INTERNAL)(handleExceptions {
    case Event(MakeFundingTxResponse(fundingTx, fundingTxOutputIndex, fundingTxFee), d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      import d.data._
      // let's create the first commitment tx that spends the yet uncommitted funding tx
      Funding.makeFirstCommitTxs(keyManager, channelConfig, channelFeatures, temporaryChannelId, localParams, remoteParams, fundingAmount, pushAmount, initialFeeratePerKw, fundingTx.hash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint) match {
        case Left(ex) =>
          channel ! HandleLocalError(ex, None)
          stop(FSM.Shutdown)
        case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx)) =>
          require(fundingTx.txOut(fundingTxOutputIndex).publicKeyScript == localCommitTx.input.txOut.publicKeyScript, s"pubkey script mismatch!")
          val localSigOfRemoteTx = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(localParams.fundingKeyPath), TxOwner.Remote, channelFeatures.commitmentFormat)
          // signature of their initial commitment tx that pays remote pushMsat
          val fundingCreated = FundingCreated(
            temporaryChannelId = temporaryChannelId,
            fundingTxid = fundingTx.hash,
            fundingOutputIndex = fundingTxOutputIndex,
            signature = localSigOfRemoteTx
          )
          val channelId = toLongId(fundingTx.hash, fundingTxOutputIndex)
          channel ! ChannelIdAssigned(channel, remoteNodeId, temporaryChannelId, channelId)
          // NB: we don't send a ChannelSignatureSent for the first commit
          channel ! SendMessage(fundingCreated)
          val nextData = ChannelData.WaitingForFundingSigned(channelId, localParams, remoteParams, fundingTx, fundingTxFee, localSpec, localCommitTx, RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint), lastSent.channelFlags, channelConfig, channelFeatures, fundingCreated)
          goto(WAIT_FOR_FUNDING_SIGNED) using DATA_WAIT_FOR_FUNDING_SIGNED(nextData)
      }

    case Event(Status.Failure(t), d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      log.error(t, "wallet returned error: ")
      channelOpenReplyToUser(Left(LocalError(t)))
      channel ! HandleLocalError(ChannelFundingError(d.data.temporaryChannelId), None) // we use a generic exception and don't reveal the internal error to our peer
      stop(FSM.Shutdown)

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.data.temporaryChannelId)))
      handleFastClose(c, d.data.temporaryChannelId)

    case Event(e: Error, _) =>
      channelOpenReplyToUser(Left(RemoteError(e)))
      channel ! HandleRemoteError(e)
      stop(FSM.Shutdown)

    case Event(INPUT_DISCONNECTED, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      channel ! CancelChannelOpen
      stop(FSM.Shutdown)

    case Event(TickChannelOpenTimeout, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      channel ! CancelChannelOpen
      stop(FSM.Shutdown)
  })

  when(WAIT_FOR_FUNDING_CREATED)(handleExceptions {
    case Event(FundingCreated(_, fundingTxHash, fundingTxOutputIndex, remoteSig, _), d: DATA_WAIT_FOR_FUNDING_CREATED) =>
      import d.data._
      // they fund the channel with their funding tx, so the money is theirs (but we are paid pushMsat)
      Funding.makeFirstCommitTxs(keyManager, channelConfig, channelFeatures, temporaryChannelId, localParams, remoteParams, fundingAmount, pushAmount, initialFeeratePerKw, fundingTxHash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint) match {
        case Left(ex) =>
          channel ! HandleLocalError(ex, None)
          stop(FSM.Shutdown)
        case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx)) =>
          // check remote signature validity
          val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
          val localSigOfLocalTx = keyManager.sign(localCommitTx, fundingPubKey, TxOwner.Local, channelFeatures.commitmentFormat)
          val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
          Transactions.checkSpendable(signedLocalCommitTx) match {
            case Failure(_) =>
              channel ! HandleLocalError(InvalidCommitmentSignature(temporaryChannelId, signedLocalCommitTx.tx), None)
              stop(FSM.Shutdown)
            case Success(_) =>
              val localSigOfRemoteTx = keyManager.sign(remoteCommitTx, fundingPubKey, TxOwner.Remote, channelFeatures.commitmentFormat)
              val channelId = toLongId(fundingTxHash, fundingTxOutputIndex)
              // watch the funding tx transaction
              val commitInput = localCommitTx.input
              val fundingSigned = FundingSigned(
                channelId = channelId,
                signature = localSigOfRemoteTx
              )
              val commitments = Commitments(channelId, channelConfig, channelFeatures, localParams, remoteParams, channelFlags,
                LocalCommit(0, localSpec, CommitTxAndRemoteSig(localCommitTx, remoteSig), htlcTxsAndRemoteSigs = Nil), RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint),
                LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
                localNextHtlcId = 0L, remoteNextHtlcId = 0L,
                originChannels = Map.empty,
                remoteNextCommitInfo = Right(randomKey().publicKey), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array,
                commitInput, ShaChain.init)
              channel ! ChannelIdAssigned(channel, remoteNodeId, temporaryChannelId, channelId)
              context.system.eventStream.publish(ChannelSignatureReceived(channel, commitments))
              // NB: we don't send a ChannelSignatureSent for the first commit
              log.info(s"waiting for them to publish the funding tx for channelId=$channelId fundingTxid=${commitInput.outPoint.txid}")
              watchFundingTx(commitments)
              val fundingMinDepth = Helpers.minDepthForFunding(nodeParams.channelConf, fundingAmount)
              blockchain ! WatchFundingConfirmed(self, commitInput.outPoint.txid, fundingMinDepth)
              val nextData = WaitingForFundingConfirmed(commitments, None, nodeParams.currentBlockHeight, None, Right(fundingSigned))
              storeState(nextData)
              channel ! SendMessage(fundingSigned)
              goto(WAIT_FOR_FUNDING_CONFIRMED) using DATA_WAIT_FOR_FUNDING_CONFIRMED(nextData)
          }
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_CREATED) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.data.temporaryChannelId)))
      handleFastClose(c, d.data.temporaryChannelId)

    case Event(e: Error, _) =>
      channel ! HandleRemoteError(e)
      stop(FSM.Shutdown)

    case Event(INPUT_DISCONNECTED, _) =>
      channel ! CancelChannelOpen
      stop(FSM.Shutdown)

    case Event(TickChannelOpenTimeout, _) =>
      channel ! CancelChannelOpen
      stop(FSM.Shutdown)
  })

  when(WAIT_FOR_FUNDING_SIGNED)(handleExceptions {
    case Event(msg@FundingSigned(_, remoteSig, _), d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      import d.data._
      // we make sure that their sig checks out and that our first commit tx is spendable
      val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
      val localSigOfLocalTx = keyManager.sign(localCommitTx, fundingPubKey, TxOwner.Local, channelFeatures.commitmentFormat)
      val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
      Transactions.checkSpendable(signedLocalCommitTx) match {
        case Failure(cause) =>
          // we rollback the funding tx, it will never be published
          wallet.rollback(fundingTx)
          channelOpenReplyToUser(Left(LocalError(cause)))
          channel ! HandleLocalError(InvalidCommitmentSignature(d.channelId, signedLocalCommitTx.tx), Some(msg))
          stop(FSM.Shutdown)
        case Success(_) =>
          val commitInput = localCommitTx.input
          val commitments = Commitments(d.channelId, channelConfig, channelFeatures, localParams, remoteParams, channelFlags,
            LocalCommit(0, localSpec, CommitTxAndRemoteSig(localCommitTx, remoteSig), htlcTxsAndRemoteSigs = Nil), remoteCommit,
            LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
            localNextHtlcId = 0L, remoteNextHtlcId = 0L,
            originChannels = Map.empty,
            remoteNextCommitInfo = Right(randomKey().publicKey), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array
            commitInput, ShaChain.init)
          val blockHeight = nodeParams.currentBlockHeight
          context.system.eventStream.publish(ChannelSignatureReceived(channel, commitments))
          log.info(s"publishing funding tx for channelId=${d.channelId} fundingTxid=${commitInput.outPoint.txid}")
          watchFundingTx(commitments)
          blockchain ! WatchFundingConfirmed(self, commitInput.outPoint.txid, nodeParams.channelConf.minDepthBlocks)
          log.info(s"committing txid=${fundingTx.txid}")
          val nextData = WaitingForFundingConfirmed(commitments, Some(fundingTx), blockHeight, None, Left(lastSent))
          storeState(nextData)
          // we publish the funding tx only after the channel state has been written to disk because we want to make
          // sure we first persist the commitment that returns back the funds to us in case of problem
          wallet.commit(fundingTx).onComplete {
            case Success(true) =>
              context.system.eventStream.publish(TransactionPublished(commitments.channelId, remoteNodeId, fundingTx, fundingTxFee, "funding"))
              channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelOpened(d.channelId)))
            case Success(false) =>
              channelOpenReplyToUser(Left(LocalError(new RuntimeException("couldn't publish funding tx"))))
              self ! BITCOIN_FUNDING_PUBLISH_FAILED // fail-fast: this should be returned only when we are really sure the tx has *not* been published
            case Failure(t) =>
              channelOpenReplyToUser(Left(LocalError(t)))
              log.error(t, "error while committing funding tx: ") // tx may still have been published, can't fail-fast
          }
          goto(WAIT_FOR_FUNDING_CONFIRMED) using DATA_WAIT_FOR_FUNDING_CONFIRMED(nextData)
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.data.fundingTx)
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.channelId)))
      handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.data.fundingTx)
      channelOpenReplyToUser(Left(RemoteError(e)))
      channel ! HandleRemoteError(e)
      stop(FSM.Shutdown)

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.data.fundingTx)
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      channel ! CancelChannelOpen
      stop(FSM.Shutdown)

    case Event(TickChannelOpenTimeout, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.data.fundingTx)
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      channel ! CancelChannelOpen
      stop(FSM.Shutdown)
  })

  when(WAIT_FOR_FUNDING_CONFIRMED)(handleExceptions {
    case Event(msg: FundingLocked, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      log.info("received their FundingLocked, deferring message")
      stay() using d.modify(_.data.deferred).setTo(Some(msg)) // no need to store, they will re-send if we get disconnected

    case Event(WatchFundingConfirmedTriggered(blockHeight, txIndex, fundingTx), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      import d.data.commitments
      Try(Transaction.correctlySpends(commitments.fullySignedLocalCommitTx(keyManager).tx, Seq(fundingTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)) match {
        case Success(_) =>
          log.info(s"channelId=${d.channelId} was confirmed at blockHeight=$blockHeight txIndex=$txIndex")
          blockchain ! WatchFundingLost(channel, commitments.commitInput.outPoint.txid, nodeParams.channelConf.minDepthBlocks)
          if (!commitments.localParams.isFunder) context.system.eventStream.publish(TransactionPublished(d.channelId, remoteNodeId, fundingTx, 0 sat, "funding"))
          context.system.eventStream.publish(TransactionConfirmed(d.channelId, remoteNodeId, fundingTx))
          val channelKeyPath = keyManager.keyPath(commitments.localParams, commitments.channelConfig)
          val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
          val fundingLocked = FundingLocked(d.channelId, nextPerCommitmentPoint)
          d.data.deferred.foreach(self ! _)
          // this is the temporary channel id that we will use in our channel_update message, the goal is to be able to use our channel
          // as soon as it reaches NORMAL state, and before it is announced on the network
          // (this id might be updated when the funding tx gets deeply buried, if there was a reorg in the meantime)
          val shortChannelId = ShortChannelId(blockHeight, txIndex, commitments.commitInput.outPoint.index.toInt)
          val nextData = WaitingForFundingLocked(commitments, shortChannelId, fundingLocked)
          storeState(nextData)
          channel ! SendMessage(fundingLocked)
          goto(WAIT_FOR_FUNDING_LOCKED) using DATA_WAIT_FOR_FUNDING_LOCKED(nextData)
        case Failure(t) =>
          log.error(t, s"rejecting channel with invalid funding tx: ${fundingTx.bin}")
          channel ! CancelChannelOpen
          stop(FSM.Shutdown)
      }

    case Event(ProcessCurrentBlockHeight(c), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => d.data.fundingTx match {
      case Some(_) => stay() // we are funder, we're still waiting for the funding tx to be confirmed
      case None if c.blockHeight - d.data.waitingSince > Channel.FUNDING_TIMEOUT_FUNDEE =>
        log.warning(s"funding tx hasn't been published in ${c.blockHeight - d.data.waitingSince} blocks")
        self ! BITCOIN_FUNDING_TIMEOUT
        stay()
      case None => stay() // let's wait longer
    }

    case Event(getTxResponse: GetTxWithMetaResponse, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if getTxResponse.txid == d.data.commitments.commitInput.outPoint.txid =>
      channel ! HandleGetFundingTx(getTxResponse, d.data.waitingSince, d.data.fundingTx)
      stay()

    case Event(BITCOIN_FUNDING_PUBLISH_FAILED, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingPublishFailed(d.data)

    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingTimeout(d.data)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if tx.txid == d.data.commitments.remoteCommit.txid =>
      channel ! HandleRemoteSpentCurrent(tx, d.data)
      stop(FSM.Shutdown)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      channel ! HandleInformationLeak(tx, d.data)
      stop(FSM.Shutdown)

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleCloseAfterFunding(c, d.channelId)

    case Event(e: Error, _) =>
      channel ! HandleRemoteError(e)
      stop(FSM.Shutdown)

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      channel ! Disconnected(d.data)
      stop(FSM.Shutdown)
  })

  when(WAIT_FOR_FUNDING_LOCKED)(handleExceptions {
    case Event(FundingLocked(_, nextPerCommitmentPoint, _), d: DATA_WAIT_FOR_FUNDING_LOCKED) =>
      channel ! ChannelOpened(d.data.shortChannelId, d.data.commitments.copy(remoteNextCommitInfo = Right(nextPerCommitmentPoint)))
      stop(FSM.Shutdown)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_FUNDING_LOCKED) if tx.txid == d.data.commitments.remoteCommit.txid =>
      channel ! HandleRemoteSpentCurrent(tx, d.data)
      stop(FSM.Shutdown)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_FUNDING_LOCKED) =>
      channel ! HandleInformationLeak(tx, d.data)
      stop(FSM.Shutdown)

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_LOCKED) => handleCloseAfterFunding(c, d.channelId)

    case Event(e: Error, _) =>
      channel ! HandleRemoteError(e)
      stop(FSM.Shutdown)

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_FUNDING_LOCKED) =>
      channel ! Disconnected(d.data)
      stop(FSM.Shutdown)
  })

  whenUnhandled {
    // we only care about this event while waiting for the funding tx to confirm, we can ignore it in earlier states
    case Event(ProcessCurrentBlockHeight(_), _) => stay()

    case Event(message: LightningMessage, _) =>
      log.warning("received unexpected message {} in state={}", message, stateName)
      stay()
  }

  onTransition {
    case _ => nextStateData match {
      case _: DATA_WAIT_FOR_INIT_INTERNAL => // nothing to do
      case DATA_WAIT_FOR_OPEN_CHANNEL(data) => channel ! UpdateData(data)
      case DATA_WAIT_FOR_ACCEPT_CHANNEL(data) => channel ! UpdateData(data)
      case DATA_WAIT_FOR_FUNDING_INTERNAL(data) => channel ! UpdateData(data)
      case DATA_WAIT_FOR_FUNDING_CREATED(data) => channel ! UpdateData(data)
      case DATA_WAIT_FOR_FUNDING_SIGNED(data) => channel ! UpdateData(data)
      case DATA_WAIT_FOR_FUNDING_CONFIRMED(data) => channel ! UpdateData(data)
      case DATA_WAIT_FOR_FUNDING_LOCKED(data) => channel ! UpdateData(data)
    }
  }

  private def storeState(d: ChannelOpenData with PersistentChannelData): Unit = {
    nodeParams.db.channels.addOrUpdateChannel(d)
    context.system.eventStream.publish(ChannelPersisted(channel, remoteNodeId, d.channelId, d))
  }

  private def watchFundingTx(commitments: Commitments, additionalKnownSpendingTxs: Set[ByteVector32] = Set.empty): Unit = {
    val knownSpendingTxs = Set(commitments.localCommit.commitTxAndRemoteSig.commitTx.tx.txid, commitments.remoteCommit.txid) ++ commitments.remoteNextCommitInfo.left.toSeq.map(_.nextRemoteCommit.txid).toSet ++ additionalKnownSpendingTxs
    // NB: we put that watch on behalf of the channel actor: this ensures that it's able to detect when our peer force
    // closes after the channel open is complete.
    blockchain ! WatchFundingSpent(channel, commitments.commitInput.outPoint.txid, commitments.commitInput.outPoint.index.toInt, knownSpendingTxs)
  }

  private def handleFundingTimeout(d: ChannelOpenData): State = {
    log.warning(s"funding tx hasn't been confirmed in time, cancelling channel delay=${Channel.FUNDING_TIMEOUT_FUNDEE}")
    val exc = FundingTxTimedout(d.channelId)
    val error = Error(d.channelId, exc.getMessage)
    context.system.eventStream.publish(ChannelErrorOccurred(channel, d.channelId, remoteNodeId, LocalError(exc), isFatal = true))
    channel ! SendMessage(error)
    channel ! CancelChannelOpen
    stop(FSM.Shutdown)
  }

  private def handleFundingPublishFailed(d: ChannelOpenData): State = {
    log.error("failed to publish funding tx")
    val exc = ChannelFundingError(d.channelId)
    val error = Error(d.channelId, exc.getMessage)
    context.system.eventStream.publish(ChannelErrorOccurred(channel, d.channelId, remoteNodeId, LocalError(exc), isFatal = true))
    channel ! SendMessage(error)
    // NB: the implementation *guarantees* that in case of BITCOIN_FUNDING_PUBLISH_FAILED, the funding tx hasn't and
    // will never be published, so we can close the channel right away without publishing our commit tx.
    channel ! CancelChannelOpen
    stop(FSM.Shutdown)
  }

  /**
   * This function is used to return feedback to user at channel opening
   */
  private def channelOpenReplyToUser(message: Either[ChannelOpenError, ChannelOpenResponse]): Unit = {
    val m = message match {
      case Left(LocalError(t)) => Status.Failure(t)
      case Left(RemoteError(e)) => Status.Failure(new RuntimeException(s"peer sent error: ascii='${e.toAscii}' bin=${e.data.toHex}"))
      case Right(s) => s
    }
    origin_opt.foreach(_ ! m)
  }

  private def handleFastClose(c: CloseCommand, channelId: ByteVector32): State = {
    val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
    replyTo ! RES_SUCCESS(c, channelId)
    channel ! CancelChannelOpen
    stop(FSM.Shutdown)
  }

  private def handleCloseAfterFunding(c: CloseCommand, channelId: ByteVector32): State = {
    val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
    c match {
      case _: CMD_CLOSE =>
        replyTo ! RES_FAILURE(c, CommandUnavailableInThisState(channelId, "close", OPENING))
        stay()
      case _: CMD_FORCECLOSE =>
        replyTo ! RES_SUCCESS(c, channelId)
        channel ! HandleLocalError(ForcedLocalCommit(channelId), Some(c))
        stop(FSM.Shutdown)
    }
  }

  /**
   * This helper function runs the state's default event handlers, and react to exceptions by unilaterally closing the channel
   */
  private def handleExceptions(s: StateFunction): StateFunction = {
    case event if s.isDefinedAt(event) =>
      try {
        s(event)
      } catch {
        case t: SQLException =>
          log.error(t, "fatal database error\n")
          NotificationsLogger.logFatalError("eclair is shutting down because of a fatal database error", t)
          sys.exit(1)
        case t: Throwable =>
          channel ! HandleLocalError(t, None)
          stop(FSM.Shutdown)
      }
  }

  override def mdc(currentMessage: Any): MDC = {
    val category_opt = LogCategory(currentMessage)
    Logs.mdc(category_opt, remoteNodeId_opt = Some(remoteNodeId), channelId_opt = Some(stateData.channelId))
  }

}
