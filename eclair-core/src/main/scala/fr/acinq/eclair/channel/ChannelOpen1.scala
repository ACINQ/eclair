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

import akka.actor.{ActorRef, FSM, Status, typed}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Script, ScriptFlags, Transaction}
import fr.acinq.eclair.{FSMDiagnosticActorLogging, Features, NodeParams, ShortChannelId, randomKey, toLongId}
import fr.acinq.eclair.blockchain.OnChainChannelFunder
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{GetTxWithMeta, GetTxWithMetaResponse, WatchFundingConfirmed, WatchFundingConfirmedTriggered, WatchFundingDeeplyBuried, WatchFundingLost, WatchFundingSpentTriggered}
import fr.acinq.eclair.channel.Channel.{ANNOUNCEMENTS_MINCONF, BITCOIN_FUNDING_PUBLISH_FAILED, BITCOIN_FUNDING_TIMEOUT, BroadcastChannelUpdate, PeriodicRefresh, ProcessCurrentBlockHeight, REFRESH_CHANNEL_UPDATE_INTERVAL, TickChannelOpenTimeout}
import fr.acinq.eclair.channel.Helpers.{Closing, Funding, getRelayFees}
import fr.acinq.eclair.channel.publish.TxPublisher.SetChannelId
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent.EventType
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.transactions.Transactions.TxOwner
import fr.acinq.eclair.wire.protocol.{AcceptChannel, AnnouncementSignatures, ChannelTlv, Error, FundingCreated, FundingLocked, FundingSigned, OpenChannel, TlvStream}
import scodec.bits.ByteVector

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
class ChannelOpen1(val nodeParams: NodeParams, val wallet: OnChainChannelFunder, remoteNodeId: PublicKey, blockchain: typed.ActorRef[ZmqWatcher.Command], origin_opt: Option[ActorRef] = None)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends FSM[ChannelState, ChannelStateData] with FSMDiagnosticActorLogging[ChannelState, ChannelStateData] {

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
    case Event(initFunder@INPUT_INIT_FUNDER(temporaryChannelId, fundingSatoshis, pushMsat, initialFeeratePerKw, fundingTxFeeratePerKw, localParams, remote, remoteInit, channelFlags, channelConfig, channelType), _: DATA_WAIT_FOR_INIT_INTERNAL) =>
      context.system.eventStream.publish(ChannelCreated(self, peer, remoteNodeId, isFunder = true, temporaryChannelId, initialFeeratePerKw, Some(fundingTxFeeratePerKw)))
      activeConnection = remote
      txPublisher ! SetChannelId(remoteNodeId, temporaryChannelId)
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
      goto(WAIT_FOR_ACCEPT_CHANNEL) using DATA_WAIT_FOR_ACCEPT_CHANNEL(initFunder, open) sending open

    case Event(inputFundee@INPUT_INIT_FUNDEE(_, localParams, remote, _, _, _), _: DATA_WAIT_FOR_INIT_INTERNAL) if !localParams.isFunder =>
      activeConnection = remote
      txPublisher ! SetChannelId(remoteNodeId, inputFundee.temporaryChannelId)
      goto(WAIT_FOR_OPEN_CHANNEL) using DATA_WAIT_FOR_OPEN_CHANNEL(inputFundee)

    case Event(INPUT_RESTORED(data), _) =>
      log.debug("restoring channel")
      context.system.eventStream.publish(ChannelRestored(self, data.channelId, peer, remoteNodeId, data))
      txPublisher ! SetChannelId(remoteNodeId, data.channelId)
      data match {
        // NB: order matters!
        case closing: ChannelData.Closing if Closing.nothingAtStake(closing) =>
          log.info("we have nothing at stake, going straight to CLOSED")
          goto(CLOSED) using DATA_CLOSED(Some(closing))
        case closing: ChannelData.Closing =>
          val isFunder = closing.commitments.localParams.isFunder
          // we don't put back the WatchSpent if the commitment tx has already been published and the spending tx already reached mindepth
          val closingType_opt = Closing.isClosingTypeAlreadyKnown(closing)
          log.info(s"channel is closing (closingType=${closingType_opt.map(c => EventType.Closed(c).label).getOrElse("UnknownYet")})")
          // if the closing type is known:
          // - there is no need to watch the funding tx because it has already been spent and the spending tx has already reached mindepth
          // - there is no need to attempt to publish transactions for other type of closes
          closingType_opt match {
            case Some(c: Closing.MutualClose) =>
              doPublish(c.tx, isFunder)
            case Some(c: Closing.LocalClose) =>
              doPublish(c.localCommitPublished, closing.commitments)
            case Some(c: Closing.RemoteClose) =>
              doPublish(c.remoteCommitPublished, closing.commitments)
            case Some(c: Closing.RecoveryClose) =>
              doPublish(c.remoteCommitPublished, closing.commitments)
            case Some(c: Closing.RevokedClose) =>
              doPublish(c.revokedCommitPublished)
            case None =>
              // in all other cases we need to be ready for any type of closing
              watchFundingTx(data.commitments, closing.spendingTxs.map(_.txid).toSet)
              closing.mutualClosePublished.foreach(mcp => doPublish(mcp, isFunder))
              closing.localCommitPublished.foreach(lcp => doPublish(lcp, closing.commitments))
              closing.remoteCommitPublished.foreach(rcp => doPublish(rcp, closing.commitments))
              closing.nextRemoteCommitPublished.foreach(rcp => doPublish(rcp, closing.commitments))
              closing.revokedCommitPublished.foreach(doPublish)
              closing.futureRemoteCommitPublished.foreach(rcp => doPublish(rcp, closing.commitments))

              // if commitment number is zero, we also need to make sure that the funding tx has been published
              if (closing.commitments.localCommit.index == 0 && closing.commitments.remoteCommit.index == 0) {
                blockchain ! GetTxWithMeta(self, closing.commitments.commitInput.outPoint.txid)
              }
          }
          // no need to go OFFLINE, we can directly switch to CLOSING
          if (closing.waitingSince.toLong > 1_500_000_000) {
            // we were using timestamps instead of block heights when the channel was created: we reset it *and* we use block heights
            goto(CLOSING) using DATA_CLOSING(closing.copy(waitingSince = nodeParams.currentBlockHeight)) storing()
          } else {
            goto(CLOSING) using DATA_CLOSING(closing)
          }

        case normal: ChannelData.Normal =>
          watchFundingTx(data.commitments)
          context.system.eventStream.publish(ShortChannelIdAssigned(self, normal.channelId, normal.channelUpdate.shortChannelId, None))

          // we check the configuration because the values for channel_update may have changed while eclair was down
          val fees = getRelayFees(nodeParams, remoteNodeId, data.commitments)
          if (fees.feeBase != normal.channelUpdate.feeBaseMsat ||
            fees.feeProportionalMillionths != normal.channelUpdate.feeProportionalMillionths ||
            nodeParams.channelConf.expiryDelta != normal.channelUpdate.cltvExpiryDelta) {
            log.info("refreshing channel_update due to configuration changes")
            self ! CMD_UPDATE_RELAY_FEE(ActorRef.noSender, fees.feeBase, fees.feeProportionalMillionths, Some(nodeParams.channelConf.expiryDelta))
          }
          // we need to periodically re-send channel updates, otherwise channel will be considered stale and get pruned by network
          // we take into account the date of the last update so that we don't send superfluous updates when we restart the app
          val periodicRefreshInitialDelay = Helpers.nextChannelUpdateRefresh(normal.channelUpdate.timestamp)
          context.system.scheduler.scheduleWithFixedDelay(initialDelay = periodicRefreshInitialDelay, delay = REFRESH_CHANNEL_UPDATE_INTERVAL, receiver = self, message = BroadcastChannelUpdate(PeriodicRefresh))

          goto(OFFLINE) using DATA_OFFLINE(normal)

        case funding: ChannelData.WaitingForFundingConfirmed =>
          watchFundingTx(funding.commitments)
          // we make sure that the funding tx has been published
          blockchain ! GetTxWithMeta(self, funding.commitments.commitInput.outPoint.txid)
          if (funding.waitingSince.toLong > 1_500_000_000) {
            // we were using timestamps instead of block heights when the channel was created: we reset it *and* we use block heights
            goto(OFFLINE) using DATA_OFFLINE(funding.copy(waitingSince = nodeParams.currentBlockHeight)) storing()
          } else {
            goto(OFFLINE) using DATA_OFFLINE(funding)
          }

        case _ =>
          watchFundingTx(data.commitments)
          goto(OFFLINE) using DATA_OFFLINE(data)
      }

    case Event(c: CloseCommand, d) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.channelId)))
      handleFastClose(c, d.channelId)

    case Event(TickChannelOpenTimeout, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED) using DATA_CLOSED(None)
  })

  when(WAIT_FOR_OPEN_CHANNEL)(handleExceptions {
    case Event(open: OpenChannel, DATA_WAIT_FOR_OPEN_CHANNEL(INPUT_INIT_FUNDEE(_, localParams, _, remoteInit, channelConfig, channelType))) =>
      Helpers.validateParamsFundee(nodeParams, channelType, localParams.initFeatures, open, remoteNodeId, remoteInit.features) match {
        case Left(t) => handleLocalError(t, Some(open))
        case Right((channelFeatures, remoteShutdownScript)) =>
          context.system.eventStream.publish(ChannelCreated(self, peer, remoteNodeId, isFunder = false, open.temporaryChannelId, open.feeratePerKw, None))
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
          goto(WAIT_FOR_FUNDING_CREATED) using DATA_WAIT_FOR_FUNDING_CREATED(open.temporaryChannelId, localParams, remoteParams, open.fundingSatoshis, open.pushMsat, open.feeratePerKw, open.firstPerCommitmentPoint, open.channelFlags, channelConfig, channelFeatures, accept) sending accept
      }

    case Event(c: CloseCommand, d) => handleFastClose(c, d.channelId)

    case Event(e: Error, _: DATA_WAIT_FOR_OPEN_CHANNEL) => handleRemoteError(e)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED) using DATA_CLOSED(None)
  })

  when(WAIT_FOR_ACCEPT_CHANNEL)(handleExceptions {
    case Event(accept: AcceptChannel, DATA_WAIT_FOR_ACCEPT_CHANNEL(INPUT_INIT_FUNDER(temporaryChannelId, fundingSatoshis, pushMsat, initialFeeratePerKw, fundingTxFeeratePerKw, localParams, _, remoteInit, _, channelConfig, channelType), open)) =>
      Helpers.validateParamsFunder(nodeParams, channelType, localParams.initFeatures, remoteInit.features, open, accept) match {
        case Left(t) =>
          channelOpenReplyToUser(Left(LocalError(t)))
          handleLocalError(t, Some(accept))
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
          wallet.makeFundingTx(fundingPubkeyScript, fundingSatoshis, fundingTxFeeratePerKw).pipeTo(self)
          goto(WAIT_FOR_FUNDING_INTERNAL) using DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, localParams, remoteParams, fundingSatoshis, pushMsat, initialFeeratePerKw, accept.firstPerCommitmentPoint, channelConfig, channelFeatures, open)
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.lastSent.temporaryChannelId)))
      handleFastClose(c, d.lastSent.temporaryChannelId)

    case Event(e: Error, _: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      channelOpenReplyToUser(Left(RemoteError(e)))
      handleRemoteError(e)

    case Event(INPUT_DISCONNECTED, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED) using DATA_CLOSED(None)

    case Event(TickChannelOpenTimeout, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED) using DATA_CLOSED(None)
  })

  when(WAIT_FOR_FUNDING_INTERNAL)(handleExceptions {
    case Event(MakeFundingTxResponse(fundingTx, fundingTxOutputIndex, fundingTxFee), DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, localParams, remoteParams, fundingAmount, pushMsat, initialFeeratePerKw, remoteFirstPerCommitmentPoint, channelConfig, channelFeatures, open)) =>
      // let's create the first commitment tx that spends the yet uncommitted funding tx
      Funding.makeFirstCommitTxs(keyManager, channelConfig, channelFeatures, temporaryChannelId, localParams, remoteParams, fundingAmount, pushMsat, initialFeeratePerKw, fundingTx.hash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint) match {
        case Left(ex) => handleLocalError(ex, None)
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
          peer ! ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
          txPublisher ! SetChannelId(remoteNodeId, channelId)
          context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId))
          // NB: we don't send a ChannelSignatureSent for the first commit
          goto(WAIT_FOR_FUNDING_SIGNED) using DATA_WAIT_FOR_FUNDING_SIGNED(channelId, localParams, remoteParams, fundingTx, fundingTxFee, localSpec, localCommitTx, RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint), open.channelFlags, channelConfig, channelFeatures, fundingCreated) sending fundingCreated
      }

    case Event(Status.Failure(t), d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      log.error(t, s"wallet returned error: ")
      channelOpenReplyToUser(Left(LocalError(t)))
      handleLocalError(ChannelFundingError(d.temporaryChannelId), None) // we use a generic exception and don't send the internal error to the peer

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.temporaryChannelId)))
      handleFastClose(c, d.temporaryChannelId)

    case Event(e: Error, _: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      channelOpenReplyToUser(Left(RemoteError(e)))
      handleRemoteError(e)

    case Event(INPUT_DISCONNECTED, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED) using DATA_CLOSED(None)

    case Event(TickChannelOpenTimeout, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED) using DATA_CLOSED(None)
  })

  when(WAIT_FOR_FUNDING_CREATED)(handleExceptions {
    case Event(FundingCreated(_, fundingTxHash, fundingTxOutputIndex, remoteSig, _), DATA_WAIT_FOR_FUNDING_CREATED(temporaryChannelId, localParams, remoteParams, fundingAmount, pushMsat, initialFeeratePerKw, remoteFirstPerCommitmentPoint, channelFlags, channelConfig, channelFeatures, _)) =>
      // they fund the channel with their funding tx, so the money is theirs (but we are paid pushMsat)
      Funding.makeFirstCommitTxs(keyManager, channelConfig, channelFeatures, temporaryChannelId, localParams, remoteParams, fundingAmount, pushMsat, initialFeeratePerKw, fundingTxHash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint) match {
        case Left(ex) => handleLocalError(ex, None)
        case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx)) =>
          // check remote signature validity
          val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
          val localSigOfLocalTx = keyManager.sign(localCommitTx, fundingPubKey, TxOwner.Local, channelFeatures.commitmentFormat)
          val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
          Transactions.checkSpendable(signedLocalCommitTx) match {
            case Failure(_) => handleLocalError(InvalidCommitmentSignature(temporaryChannelId, signedLocalCommitTx.tx), None)
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
              peer ! ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
              txPublisher ! SetChannelId(remoteNodeId, channelId)
              context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId))
              context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
              // NB: we don't send a ChannelSignatureSent for the first commit
              log.info(s"waiting for them to publish the funding tx for channelId=$channelId fundingTxid=${commitInput.outPoint.txid}")
              watchFundingTx(commitments)
              val fundingMinDepth = Helpers.minDepthForFunding(nodeParams.channelConf, fundingAmount)
              blockchain ! WatchFundingConfirmed(self, commitInput.outPoint.txid, fundingMinDepth)
              goto(WAIT_FOR_FUNDING_CONFIRMED) using DATA_WAIT_FOR_FUNDING_CONFIRMED(ChannelData.WaitingForFundingConfirmed(commitments, None, nodeParams.currentBlockHeight, None, Right(fundingSigned))) storing() sending fundingSigned
          }
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_CREATED) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.temporaryChannelId)))
      handleFastClose(c, d.temporaryChannelId)

    case Event(e: Error, _: DATA_WAIT_FOR_FUNDING_CREATED) => handleRemoteError(e)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED) using DATA_CLOSED(None)
  })

  when(WAIT_FOR_FUNDING_SIGNED)(handleExceptions {
    case Event(msg@FundingSigned(_, remoteSig, _), d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we make sure that their sig checks out and that our first commit tx is spendable
      val fundingPubKey = keyManager.fundingPublicKey(d.localParams.fundingKeyPath)
      val localSigOfLocalTx = keyManager.sign(d.localCommitTx, fundingPubKey, TxOwner.Local, d.channelFeatures.commitmentFormat)
      val signedLocalCommitTx = Transactions.addSigs(d.localCommitTx, fundingPubKey.publicKey, d.remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
      Transactions.checkSpendable(signedLocalCommitTx) match {
        case Failure(cause) =>
          // we rollback the funding tx, it will never be published
          wallet.rollback(d.fundingTx)
          channelOpenReplyToUser(Left(LocalError(cause)))
          handleLocalError(InvalidCommitmentSignature(d.channelId, signedLocalCommitTx.tx), Some(msg))
        case Success(_) =>
          val commitInput = d.localCommitTx.input
          val commitments = Commitments(d.channelId, d.channelConfig, d.channelFeatures, d.localParams, d.remoteParams, d.channelFlags,
            LocalCommit(0, d.localSpec, CommitTxAndRemoteSig(d.localCommitTx, remoteSig), htlcTxsAndRemoteSigs = Nil), d.remoteCommit,
            LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
            localNextHtlcId = 0L, remoteNextHtlcId = 0L,
            originChannels = Map.empty,
            remoteNextCommitInfo = Right(randomKey().publicKey), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array
            commitInput, ShaChain.init)
          val blockHeight = nodeParams.currentBlockHeight
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
          log.info(s"publishing funding tx for channelId=${d.channelId} fundingTxid=${commitInput.outPoint.txid}")
          watchFundingTx(commitments)
          blockchain ! WatchFundingConfirmed(self, commitInput.outPoint.txid, nodeParams.channelConf.minDepthBlocks)
          log.info(s"committing txid=${d.fundingTx.txid}")

          // we will publish the funding tx only after the channel state has been written to disk because we want to
          // make sure we first persist the commitment that returns back the funds to us in case of problem
          def publishFundingTx(): Unit = {
            wallet.commit(d.fundingTx).onComplete {
              case Success(true) =>
                context.system.eventStream.publish(TransactionPublished(commitments.channelId, remoteNodeId, d.fundingTx, d.fundingTxFee, "funding"))
                channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelOpened(d.channelId)))
              case Success(false) =>
                channelOpenReplyToUser(Left(LocalError(new RuntimeException("couldn't publish funding tx"))))
                self ! BITCOIN_FUNDING_PUBLISH_FAILED // fail-fast: this should be returned only when we are really sure the tx has *not* been published
              case Failure(t) =>
                channelOpenReplyToUser(Left(LocalError(t)))
                log.error(t, s"error while committing funding tx: ") // tx may still have been published, can't fail-fast
            }
          }

          goto(WAIT_FOR_FUNDING_CONFIRMED) using DATA_WAIT_FOR_FUNDING_CONFIRMED(ChannelData.WaitingForFundingConfirmed(commitments, Some(d.fundingTx), blockHeight, None, Left(d.lastSent))) storing() calling publishFundingTx()
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.channelId)))
      handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      channelOpenReplyToUser(Left(RemoteError(e)))
      handleRemoteError(e)

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED) using DATA_CLOSED(None)

    case Event(TickChannelOpenTimeout, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED) using DATA_CLOSED(None)
  })

  when(WAIT_FOR_FUNDING_CONFIRMED)(handleExceptions {
    case Event(msg: FundingLocked, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      log.info(s"received their FundingLocked, deferring message")
      stay() using d.modify(_.data.deferred).setTo(Some(msg)) // no need to store, they will re-send if we get disconnected

    case Event(WatchFundingConfirmedTriggered(blockHeight, txIndex, fundingTx), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      import d.data.commitments
      Try(Transaction.correctlySpends(commitments.fullySignedLocalCommitTx(keyManager).tx, Seq(fundingTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)) match {
        case Success(_) =>
          log.info(s"channelId=${d.channelId} was confirmed at blockHeight=$blockHeight txIndex=$txIndex")
          blockchain ! WatchFundingLost(self, commitments.commitInput.outPoint.txid, nodeParams.channelConf.minDepthBlocks)
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
          goto(WAIT_FOR_FUNDING_LOCKED) using DATA_WAIT_FOR_FUNDING_LOCKED(ChannelData.WaitingForFundingLocked(commitments, shortChannelId, fundingLocked)) storing() sending fundingLocked
        case Failure(t) =>
          log.error(t, s"rejecting channel with invalid funding tx: ${fundingTx.bin}")
          goto(CLOSED) using DATA_CLOSED(Some(d.data))
      }

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if d.data.commitments.announceChannel =>
      log.debug("received remote announcement signatures, delaying")
      // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
      // note: no need to persist their message, in case of disconnection they will resend it
      context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
      stay()

    case Event(getTxResponse: GetTxWithMetaResponse, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if getTxResponse.txid == d.data.commitments.commitInput.outPoint.txid => handleGetFundingTx(getTxResponse, d.data.waitingSince, d.data.fundingTx)

    case Event(BITCOIN_FUNDING_PUBLISH_FAILED, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingPublishFailed(d.data)

    case Event(ProcessCurrentBlockHeight(c), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => d.data.fundingTx match {
      case Some(_) => stay() // we are funder, we're still waiting for the funding tx to be confirmed
      case None if c.blockHeight - d.data.waitingSince > FUNDING_TIMEOUT_FUNDEE =>
        log.warning(s"funding tx hasn't been published in ${c.blockHeight - d.data.waitingSince} blocks")
        self ! BITCOIN_FUNDING_TIMEOUT
        stay()
      case None => stay() // let's wait longer
    }

    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingTimeout(d.data)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if tx.txid == d.data.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d.data)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleInformationLeak(tx, d.data)

    case Event(e: Error, _: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleRemoteError(e)
  })

  when(WAIT_FOR_FUNDING_LOCKED)(handleExceptions {
    case Event(FundingLocked(_, nextPerCommitmentPoint, _), d: DATA_WAIT_FOR_FUNDING_LOCKED) =>
      import d.data.{commitments, shortChannelId}
      // used to get the final shortChannelId, used in announcements (if minDepth >= ANNOUNCEMENTS_MINCONF this event will fire instantly)
      blockchain ! WatchFundingDeeplyBuried(self, commitments.commitInput.outPoint.txid, ANNOUNCEMENTS_MINCONF)
      context.system.eventStream.publish(ShortChannelIdAssigned(self, commitments.channelId, d.data.shortChannelId, None))
      // we create a channel_update early so that we can use it to send payments through this channel, but it won't be propagated to other nodes since the channel is not yet announced
      val fees = getRelayFees(nodeParams, remoteNodeId, commitments)
      val initialChannelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, shortChannelId, nodeParams.channelConf.expiryDelta, commitments.remoteParams.htlcMinimum, fees.feeBase, fees.feeProportionalMillionths, commitments.capacity.toMilliSatoshi, enable = Helpers.aboveReserve(commitments))
      // we need to periodically re-send channel updates, otherwise channel will be considered stale and get pruned by network
      context.system.scheduler.scheduleWithFixedDelay(initialDelay = REFRESH_CHANNEL_UPDATE_INTERVAL, delay = REFRESH_CHANNEL_UPDATE_INTERVAL, receiver = self, message = BroadcastChannelUpdate(PeriodicRefresh))
      goto(NORMAL) using DATA_NORMAL(ChannelData.Normal(commitments.copy(remoteNextCommitInfo = Right(nextPerCommitmentPoint)), shortChannelId, buried = false, None, initialChannelUpdate, None, None, None)) storing()

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_FUNDING_LOCKED) if d.data.commitments.announceChannel =>
      log.debug("received remote announcement signatures, delaying")
      // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
      // note: no need to persist their message, in case of disconnection they will resend it
      context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
      stay()

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_FUNDING_LOCKED) if tx.txid == d.data.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d.data)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_FUNDING_LOCKED) => handleInformationLeak(tx, d.data)

    case Event(e: Error, _: DATA_WAIT_FOR_FUNDING_LOCKED) => handleRemoteError(e)
  })

}
