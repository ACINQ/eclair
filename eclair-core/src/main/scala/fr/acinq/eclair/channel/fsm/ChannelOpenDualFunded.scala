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

package fr.acinq.eclair.channel.fsm

import akka.actor.typed.scaladsl.adapter.{ClassicActorContextOps, actorRefAdapter}
import com.softwaremill.quicklens.{ModifyPimp, QuicklensAt}
import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel.ChannelTypes.SimpleTaprootChannelsStaging
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel._
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.{FullySignedSharedTransaction, InteractiveTxParams, PartiallySignedSharedTransaction, RequireConfirmedInputs}
import fr.acinq.eclair.channel.fund.{InteractiveTxBuilder, InteractiveTxSigningSession}
import fr.acinq.eclair.channel.publish.TxPublisher.SetChannelId
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.io.Peer.{LiquidityPurchaseSigned, OpenChannelResponse}
import fr.acinq.eclair.transactions.Transactions.SimpleTaprootChannelsStagingCommitmentFormat
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{MilliSatoshiLong, RealShortChannelId, ToMilliSatoshiConversion, UInt64, randomBytes32}

/**
 * Created by t-bast on 19/04/2022.
 */

trait ChannelOpenDualFunded extends DualFundingHandlers with ErrorHandlers {

  this: Channel =>

  /*
                                     INITIATOR                       NON_INITIATOR
                                         |                                 |
                                         |          open_channel2          | WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL
                                         |-------------------------------->|
     WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL |                                 |
                                         |         accept_channel2         |
                                         |<--------------------------------|
           WAIT_FOR_DUAL_FUNDING_CREATED |                                 | WAIT_FOR_DUAL_FUNDING_CREATED
                                         |    <interactive-tx protocol>    |
                                         |                .                |
                                         |                .                |
                                         |                .                |
                                         |           tx_complete           |
                                         |-------------------------------->|
                                         |           tx_complete           |
                                         |<--------------------------------|
            WAIT_FOR_DUAL_FUNDING_SIGNED |                                 | WAIT_FOR_DUAL_FUNDING_SIGNED
                                         |        commitment_signed        |
                                         |-------------------------------->|
                                         |        commitment_signed        |
                                         |<--------------------------------|
                                         |          tx_signatures          |
                                         |<--------------------------------|
                                         |                                 | WAIT_FOR_DUAL_FUNDING_CONFIRMED
                                         |          tx_signatures          |
                                         |-------------------------------->|
         WAIT_FOR_DUAL_FUNDING_CONFIRMED |                                 |
                                         |           tx_init_rbf           |
                                         |-------------------------------->|
                                         |           tx_ack_rbf            |
                                         |<--------------------------------|
                                         |                                 |
                                         |    <interactive-tx protocol>    |
                                         |                .                |
                                         |                .                |
                                         |                .                |
                                         |           tx_complete           |
                                         |-------------------------------->|
                                         |           tx_complete           |
                                         |<--------------------------------|
                                         |                                 |
                                         |        commitment_signed        |
                                         |-------------------------------->|
                                         |        commitment_signed        |
                                         |<--------------------------------|
                                         |          tx_signatures          |
                                         |<--------------------------------|
                                         |          tx_signatures          |
                                         |-------------------------------->|
                                         |                                 |
                                         |      <other rbf attempts>       |
                                         |                .                |
                                         |                .                |
                                         |                .                |
            WAIT_FOR_DUAL_FUNDING_LOCKED |                                 | WAIT_FOR_DUAL_FUNDING_LOCKED
                                         | funding_locked   funding_locked |
                                         |----------------  ---------------|
                                         |                \/               |
                                         |                /\               |
                                         |<---------------  -------------->|
                                  NORMAL |                                 | NORMAL
 */

  when(WAIT_FOR_INIT_DUAL_FUNDED_CHANNEL)(handleExceptions {
    case Event(input: INPUT_INIT_CHANNEL_INITIATOR, _) =>
      val fundingPubKey = keyManager.fundingPublicKey(input.localParams.fundingKeyPath, fundingTxIndex = 0).publicKey
      val channelKeyPath = keyManager.keyPath(input.localParams, input.channelConfig)
      val upfrontShutdownScript_opt = input.localParams.upfrontShutdownScript_opt.map(scriptPubKey => ChannelTlv.UpfrontShutdownScriptTlv(scriptPubKey))
      val tlvs: Set[OpenDualFundedChannelTlv] = Set(
        upfrontShutdownScript_opt,
        Some(ChannelTlv.ChannelTypeTlv(input.channelType)),
        if (input.requireConfirmedInputs) Some(ChannelTlv.RequireConfirmedInputsTlv()) else None,
        input.requestFunding_opt.map(ChannelTlv.RequestFundingTlv),
        input.pushAmount_opt.map(amount => ChannelTlv.PushAmountTlv(amount)),
        if (input.channelType.commitmentFormat == SimpleTaprootChannelsStagingCommitmentFormat) Some(ChannelTlv.NextLocalNoncesTlv(
          List(
            keyManager.verificationNonce(input.localParams.fundingKeyPath, fundingTxIndex = 0, channelKeyPath, 0)._2,
            keyManager.verificationNonce(input.localParams.fundingKeyPath, fundingTxIndex = 0, channelKeyPath, 1)._2
          )
        )) else None
      ).flatten
      val open = OpenDualFundedChannel(
        chainHash = nodeParams.chainHash,
        temporaryChannelId = input.temporaryChannelId,
        fundingFeerate = input.fundingTxFeerate,
        commitmentFeerate = input.commitTxFeerate,
        fundingAmount = input.fundingAmount,
        dustLimit = input.localParams.dustLimit,
        maxHtlcValueInFlightMsat = UInt64(input.localParams.maxHtlcValueInFlightMsat.toLong),
        htlcMinimum = input.localParams.htlcMinimum,
        toSelfDelay = input.localParams.toSelfDelay,
        maxAcceptedHtlcs = input.localParams.maxAcceptedHtlcs,
        lockTime = nodeParams.currentBlockHeight.toLong,
        fundingPubkey = fundingPubKey,
        revocationBasepoint = keyManager.revocationPoint(channelKeyPath).publicKey,
        paymentBasepoint = input.localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey),
        delayedPaymentBasepoint = keyManager.delayedPaymentPoint(channelKeyPath).publicKey,
        htlcBasepoint = keyManager.htlcPoint(channelKeyPath).publicKey,
        firstPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0),
        secondPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1),
        channelFlags = input.channelFlags,
        tlvStream = TlvStream(tlvs))
      goto(WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL) using DATA_WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL(input, open) sending open
  })

  when(WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL)(handleExceptions {
    case Event(open: OpenDualFundedChannel, d: DATA_WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL) =>
      import d.init.{localParams, remoteInit}
      val localFundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath, fundingTxIndex = 0).publicKey
      val fundingScript = Funding.makeFundingPubKeyScript(localFundingPubkey, open.fundingPubkey)
      Helpers.validateParamsDualFundedNonInitiator(nodeParams, d.init.channelType, open, fundingScript, remoteNodeId, localParams.initFeatures, remoteInit.features, d.init.fundingContribution_opt) match {
        case Left(t) => handleLocalError(t, d, Some(open))
        case Right((channelFeatures, remoteShutdownScript, willFund_opt)) =>
          context.system.eventStream.publish(ChannelCreated(self, peer, remoteNodeId, isOpener = false, open.temporaryChannelId, open.commitmentFeerate, Some(open.fundingFeerate)))
          val remoteParams = RemoteParams(
            nodeId = remoteNodeId,
            dustLimit = open.dustLimit,
            maxHtlcValueInFlightMsat = open.maxHtlcValueInFlightMsat,
            initialRequestedChannelReserve_opt = None, // channel reserve will be computed based on channel capacity
            htlcMinimum = open.htlcMinimum,
            toSelfDelay = open.toSelfDelay,
            maxAcceptedHtlcs = open.maxAcceptedHtlcs,
            revocationBasepoint = open.revocationBasepoint,
            paymentBasepoint = open.paymentBasepoint,
            delayedPaymentBasepoint = open.delayedPaymentBasepoint,
            htlcBasepoint = open.htlcBasepoint,
            initFeatures = remoteInit.features,
            upfrontShutdownScript_opt = remoteShutdownScript)
          log.debug("remote params: {}", remoteParams)
          val channelKeyPath = keyManager.keyPath(localParams, d.init.channelConfig)
          val revocationBasePoint = keyManager.revocationPoint(channelKeyPath).publicKey
          // We've exchanged open_channel2 and accept_channel2, we now know the final channelId.
          val channelId = Helpers.computeChannelId(open.revocationBasepoint, revocationBasePoint)
          val channelParams = ChannelParams(channelId, d.init.channelConfig, channelFeatures, localParams, remoteParams, open.channelFlags)
          val localAmount = d.init.fundingContribution_opt.map(_.fundingAmount).getOrElse(0 sat)
          val remoteAmount = open.fundingAmount
          // At this point, the min_depth is an estimate and may change after we know exactly how our peer contributes
          // to the funding transaction. Maybe they will contribute 0 satoshis to the shared output, but still add inputs
          // and outputs.
          val minDepth_opt = channelParams.minDepthFundee(nodeParams.channelConf.minDepthBlocks, localAmount + remoteAmount)
          val upfrontShutdownScript_opt = localParams.upfrontShutdownScript_opt.map(scriptPubKey => ChannelTlv.UpfrontShutdownScriptTlv(scriptPubKey))
          val tlvs: Set[AcceptDualFundedChannelTlv] = Set(
            upfrontShutdownScript_opt,
            Some(ChannelTlv.ChannelTypeTlv(d.init.channelType)),
            if (d.init.requireConfirmedInputs) Some(ChannelTlv.RequireConfirmedInputsTlv()) else None,
            willFund_opt.map(l => ChannelTlv.ProvideFundingTlv(l.willFund)),
            open.useFeeCredit_opt.map(c => ChannelTlv.FeeCreditUsedTlv(c)),
            d.init.pushAmount_opt.map(amount => ChannelTlv.PushAmountTlv(amount)),
            if (channelParams.commitmentFormat == SimpleTaprootChannelsStagingCommitmentFormat) Some(ChannelTlv.NextLocalNoncesTlv(
              List(
                keyManager.verificationNonce(localParams.fundingKeyPath, fundingTxIndex = 0, channelKeyPath, 0)._2,
                keyManager.verificationNonce(localParams.fundingKeyPath, fundingTxIndex = 0, channelKeyPath, 1)._2
              ))) else None
          ).flatten
          log.debug("sending AcceptDualFundedChannel with {}", tlvs)
          val accept = AcceptDualFundedChannel(
            temporaryChannelId = open.temporaryChannelId,
            fundingAmount = localAmount,
            dustLimit = localParams.dustLimit,
            maxHtlcValueInFlightMsat = UInt64(localParams.maxHtlcValueInFlightMsat.toLong),
            htlcMinimum = localParams.htlcMinimum,
            minimumDepth = minDepth_opt.getOrElse(0),
            toSelfDelay = localParams.toSelfDelay,
            maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
            fundingPubkey = localFundingPubkey,
            revocationBasepoint = revocationBasePoint,
            paymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey),
            delayedPaymentBasepoint = keyManager.delayedPaymentPoint(channelKeyPath).publicKey,
            htlcBasepoint = keyManager.htlcPoint(channelKeyPath).publicKey,
            firstPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0),
            secondPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1),
            tlvStream = TlvStream(tlvs))
          peer ! ChannelIdAssigned(self, remoteNodeId, accept.temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
          txPublisher ! SetChannelId(remoteNodeId, channelId)
          context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, accept.temporaryChannelId, channelId))
          // We start the interactive-tx funding protocol.
          val fundingParams = InteractiveTxParams(
            channelId = channelId,
            isInitiator = localParams.isChannelOpener,
            localContribution = accept.fundingAmount,
            remoteContribution = open.fundingAmount,
            sharedInput_opt = None,
            remoteFundingPubKey = open.fundingPubkey,
            localOutputs = Nil,
            lockTime = open.lockTime,
            dustLimit = open.dustLimit.max(accept.dustLimit),
            targetFeerate = open.fundingFeerate,
            requireConfirmedInputs = RequireConfirmedInputs(forLocal = open.requireConfirmedInputs, forRemote = accept.requireConfirmedInputs)
          )
          val purpose = InteractiveTxBuilder.FundingTx(open.commitmentFeerate, open.firstPerCommitmentPoint, feeBudget_opt = None)
          val txBuilder = context.spawnAnonymous(InteractiveTxBuilder(
            randomBytes32(),
            nodeParams, fundingParams,
            channelParams, purpose,
            localPushAmount = accept.pushAmount, remotePushAmount = open.pushAmount,
            willFund_opt.map(_.purchase),
            wallet,
            open.firstRemoteNonce))
          txBuilder ! InteractiveTxBuilder.Start(self)
          setRemoteNextLocalNonces("received OpenDualFundedChannel", open.secondRemoteNonce.toList)
          goto(WAIT_FOR_DUAL_FUNDING_CREATED) using DATA_WAIT_FOR_DUAL_FUNDING_CREATED(channelId, channelParams, open.secondPerCommitmentPoint, accept.pushAmount, open.pushAmount, txBuilder, deferred = None, replyTo_opt = None) sending accept
      }

    case Event(c: CloseCommand, d) => handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL)(handleExceptions {
    case Event(accept: AcceptDualFundedChannel, d: DATA_WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL) =>
      import d.init.{localParams, remoteInit}
      Helpers.validateParamsDualFundedInitiator(nodeParams, remoteNodeId, d.init.channelType, localParams.initFeatures, remoteInit.features, d.lastSent, accept) match {
        case Left(t) =>
          d.init.replyTo ! OpenChannelResponse.Rejected(t.getMessage)
          handleLocalError(t, d, Some(accept))
        case Right((channelFeatures, remoteShutdownScript, liquidityPurchase_opt)) =>
          // We've exchanged open_channel2 and accept_channel2, we now know the final channelId.
          val channelId = Helpers.computeChannelId(d.lastSent.revocationBasepoint, accept.revocationBasepoint)
          peer ! ChannelIdAssigned(self, remoteNodeId, accept.temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
          txPublisher ! SetChannelId(remoteNodeId, channelId)
          context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, accept.temporaryChannelId, channelId))
          val remoteParams = RemoteParams(
            nodeId = remoteNodeId,
            dustLimit = accept.dustLimit,
            maxHtlcValueInFlightMsat = accept.maxHtlcValueInFlightMsat,
            initialRequestedChannelReserve_opt = None, // channel reserve will be computed based on channel capacity
            htlcMinimum = accept.htlcMinimum,
            toSelfDelay = accept.toSelfDelay,
            maxAcceptedHtlcs = accept.maxAcceptedHtlcs,
            revocationBasepoint = accept.revocationBasepoint,
            paymentBasepoint = accept.paymentBasepoint,
            delayedPaymentBasepoint = accept.delayedPaymentBasepoint,
            htlcBasepoint = accept.htlcBasepoint,
            initFeatures = remoteInit.features,
            upfrontShutdownScript_opt = remoteShutdownScript)
          log.debug("remote params: {}", remoteParams)
          // We start the interactive-tx funding protocol.
          val channelParams = ChannelParams(channelId, d.init.channelConfig, channelFeatures, localParams, remoteParams, d.lastSent.channelFlags)
          val localAmount = d.lastSent.fundingAmount
          val remoteAmount = accept.fundingAmount
          val fundingParams = InteractiveTxParams(
            channelId = channelId,
            isInitiator = localParams.isChannelOpener,
            localContribution = localAmount,
            remoteContribution = remoteAmount,
            sharedInput_opt = None,
            remoteFundingPubKey = accept.fundingPubkey,
            localOutputs = Nil,
            lockTime = d.lastSent.lockTime,
            dustLimit = d.lastSent.dustLimit.max(accept.dustLimit),
            targetFeerate = d.lastSent.fundingFeerate,
            requireConfirmedInputs = RequireConfirmedInputs(forLocal = accept.requireConfirmedInputs, forRemote = d.lastSent.requireConfirmedInputs)
          )
          val purpose = InteractiveTxBuilder.FundingTx(d.lastSent.commitmentFeerate, accept.firstPerCommitmentPoint, feeBudget_opt = d.init.fundingTxFeeBudget_opt)
          val txBuilder = context.spawnAnonymous(InteractiveTxBuilder(
            randomBytes32(),
            nodeParams, fundingParams,
            channelParams, purpose,
            localPushAmount = d.lastSent.pushAmount, remotePushAmount = accept.pushAmount,
            liquidityPurchase_opt = liquidityPurchase_opt,
            wallet,
            accept.firstRemoteNonce))
          txBuilder ! InteractiveTxBuilder.Start(self)
          setRemoteNextLocalNonces("received AcceptDualFundedChannel", accept.secondRemoteNonce.toList)
          goto(WAIT_FOR_DUAL_FUNDING_CREATED) using DATA_WAIT_FOR_DUAL_FUNDING_CREATED(channelId, channelParams, accept.secondPerCommitmentPoint, d.lastSent.pushAmount, accept.pushAmount, txBuilder, deferred = None, replyTo_opt = Some(d.init.replyTo))
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL) =>
      d.init.replyTo ! OpenChannelResponse.Cancelled
      handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL) =>
      d.init.replyTo ! OpenChannelResponse.RemoteError(e.toAscii)
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL) =>
      d.init.replyTo ! OpenChannelResponse.Disconnected
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, d: DATA_WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL) =>
      d.init.replyTo ! OpenChannelResponse.TimedOut
      goto(CLOSED)
  })

  when(WAIT_FOR_DUAL_FUNDING_CREATED)(handleExceptions {
    case Event(msg: InteractiveTxMessage, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      msg match {
        case msg: InteractiveTxConstructionMessage =>
          d.txBuilder ! InteractiveTxBuilder.ReceiveMessage(msg)
          stay()
        case msg: TxAbort =>
          log.info("our peer aborted the dual funding flow: ascii='{}' bin={}", msg.toAscii, msg.data)
          d.txBuilder ! InteractiveTxBuilder.Abort
          d.replyTo_opt.foreach(_ ! OpenChannelResponse.RemoteError(msg.toAscii))
          goto(CLOSED) sending TxAbort(d.channelId, DualFundingAborted(d.channelId).getMessage)
        case _: TxSignatures =>
          log.info("received unexpected tx_signatures")
          d.txBuilder ! InteractiveTxBuilder.Abort
          d.replyTo_opt.foreach(_ ! OpenChannelResponse.Rejected(UnexpectedFundingSignatures(d.channelId).getMessage))
          goto(CLOSED) sending TxAbort(d.channelId, UnexpectedFundingSignatures(d.channelId).getMessage)
        case _: TxInitRbf =>
          log.info("ignoring unexpected tx_init_rbf message")
          stay() sending Warning(d.channelId, InvalidRbfAttempt(d.channelId).getMessage)
        case _: TxAckRbf =>
          log.info("ignoring unexpected tx_ack_rbf message")
          stay() sending Warning(d.channelId, InvalidRbfAttempt(d.channelId).getMessage)
      }

    case Event(commitSig: CommitSig, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      log.debug("received their commit_sig, deferring message")
      stay() using d.copy(deferred = Some(commitSig))

    case Event(msg: InteractiveTxBuilder.Response, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) => msg match {
      case InteractiveTxBuilder.SendMessage(_, msg) => stay() sending msg
      case InteractiveTxBuilder.Succeeded(status, commitSig, liquidityPurchase_opt) =>
        d.deferred.foreach(self ! _)
        d.replyTo_opt.foreach(_ ! OpenChannelResponse.Created(d.channelId, status.fundingTx.txId, status.fundingTx.tx.localFees.truncateToSatoshi))
        liquidityPurchase_opt.collect {
          case purchase if !status.fundingParams.isInitiator => peer ! LiquidityPurchaseSigned(d.channelId, status.fundingTx.txId, status.fundingTxIndex, d.channelParams.remoteParams.htlcMinimum, purchase)
        }
        val d1 = DATA_WAIT_FOR_DUAL_FUNDING_SIGNED(d.channelParams, d.secondRemotePerCommitmentPoint, d.localPushAmount, d.remotePushAmount, status, None)
        goto(WAIT_FOR_DUAL_FUNDING_SIGNED) using d1 storing() sending commitSig
      case f: InteractiveTxBuilder.Failed =>
        d.replyTo_opt.foreach(_ ! OpenChannelResponse.Rejected(f.cause.getMessage))
        goto(CLOSED) sending TxAbort(d.channelId, f.cause.getMessage)
    }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      d.txBuilder ! InteractiveTxBuilder.Abort
      d.replyTo_opt.foreach(_ ! OpenChannelResponse.Cancelled)
      handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      d.txBuilder ! InteractiveTxBuilder.Abort
      d.replyTo_opt.foreach(_ ! OpenChannelResponse.RemoteError(e.toAscii))
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      d.txBuilder ! InteractiveTxBuilder.Abort
      d.replyTo_opt.foreach(_ ! OpenChannelResponse.Disconnected)
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      d.txBuilder ! InteractiveTxBuilder.Abort
      d.replyTo_opt.foreach(_ ! OpenChannelResponse.TimedOut)
      goto(CLOSED)
  })

  when(WAIT_FOR_DUAL_FUNDING_SIGNED)(handleExceptions {
    case Event(commitSig: CommitSig, d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED) =>
      d.signingSession.receiveCommitSig(nodeParams, d.channelParams, commitSig) match {
        case Left(f) =>
          rollbackFundingAttempt(d.signingSession.fundingTx.tx, Nil)
          goto(CLOSED) sending Error(d.channelId, f.getMessage)
        case Right(signingSession1) => signingSession1 match {
          case signingSession1: InteractiveTxSigningSession.WaitingForSigs =>
            // In theory we don't have to store their commit_sig here, as they would re-send it if we disconnect, but
            // it is more consistent with the case where we send our tx_signatures first.
            val d1 = d.copy(signingSession = signingSession1)
            stay() using d1 storing()
          case signingSession1: InteractiveTxSigningSession.SendingSigs =>
            // We don't have their tx_sigs, but they have ours, and could publish the funding tx without telling us.
            // That's why we move on immediately to the next step, and will update our unsigned funding tx when we
            // receive their tx_sigs.
            val minDepth_opt = d.channelParams.minDepthDualFunding(nodeParams.channelConf.minDepthBlocks, signingSession1.fundingTx.sharedTx.tx)
            watchFundingConfirmed(d.signingSession.fundingTx.txId, minDepth_opt, delay_opt = None)
            val commitments = Commitments(
              params = d.channelParams,
              changes = CommitmentChanges.init(),
              active = List(signingSession1.commitment),
              remoteNextCommitInfo = Right(d.secondRemotePerCommitmentPoint),
              remotePerCommitmentSecrets = ShaChain.init,
              originChannels = Map.empty
            )
            val d1 = DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED(commitments, d.localPushAmount, d.remotePushAmount, nodeParams.currentBlockHeight, nodeParams.currentBlockHeight, RbfStatus.NoRbf, None)
            goto(WAIT_FOR_DUAL_FUNDING_CONFIRMED) using d1 storing() sending signingSession1.localSigs
        }
      }

    case Event(msg: InteractiveTxMessage, d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED) =>
      msg match {
        case txSigs: TxSignatures =>
          d.signingSession.receiveTxSigs(nodeParams, d.channelParams, txSigs) match {
            case Left(f) =>
              rollbackFundingAttempt(d.signingSession.fundingTx.tx, Nil)
              goto(CLOSED) sending Error(d.channelId, f.getMessage)
            case Right(signingSession) =>
              val minDepth_opt = d.channelParams.minDepthDualFunding(nodeParams.channelConf.minDepthBlocks, signingSession.fundingTx.sharedTx.tx)
              watchFundingConfirmed(d.signingSession.fundingTx.txId, minDepth_opt, delay_opt = None)
              val commitments = Commitments(
                params = d.channelParams,
                changes = CommitmentChanges.init(),
                active = List(signingSession.commitment),
                remoteNextCommitInfo = Right(d.secondRemotePerCommitmentPoint),
                remotePerCommitmentSecrets = ShaChain.init,
                originChannels = Map.empty
              )
              val d1 = DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED(commitments, d.localPushAmount, d.remotePushAmount, nodeParams.currentBlockHeight, nodeParams.currentBlockHeight, RbfStatus.NoRbf, None)
              goto(WAIT_FOR_DUAL_FUNDING_CONFIRMED) using d1 storing() sending signingSession.localSigs calling publishFundingTx(signingSession.fundingTx)
          }
        case msg: TxAbort =>
          log.info("our peer aborted the dual funding flow: ascii='{}' bin={}", msg.toAscii, msg.data)
          rollbackFundingAttempt(d.signingSession.fundingTx.tx, Nil)
          goto(CLOSED) sending TxAbort(d.channelId, DualFundingAborted(d.channelId).getMessage)
        case msg: InteractiveTxConstructionMessage =>
          log.info("received unexpected interactive-tx message: {}", msg.getClass.getSimpleName)
          stay() sending Warning(d.channelId, UnexpectedInteractiveTxMessage(d.channelId, msg).getMessage)
        case _: TxInitRbf =>
          log.info("ignoring unexpected tx_init_rbf message")
          stay() sending Warning(d.channelId, InvalidRbfAttempt(d.channelId).getMessage)
        case _: TxAckRbf =>
          log.info("ignoring unexpected tx_ack_rbf message")
          stay() sending Warning(d.channelId, InvalidRbfAttempt(d.channelId).getMessage)
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED) =>
      rollbackFundingAttempt(d.signingSession.fundingTx.tx, Nil)
      handleFastClose(c, d.channelId) sending Error(d.channelId, DualFundingAborted(d.channelId).getMessage)

    case Event(e: Error, d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED) =>
      // handleRemoteError takes care of rolling back the funding tx
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED) =>
      // We should be able to complete the channel open when reconnecting.
      goto(OFFLINE) using d
  })

  when(WAIT_FOR_DUAL_FUNDING_CONFIRMED)(handleExceptions {
    case Event(txSigs: TxSignatures, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      d.latestFundingTx.sharedTx match {
        case fundingTx: PartiallySignedSharedTransaction => InteractiveTxSigningSession.addRemoteSigs(keyManager, d.commitments.params, d.latestFundingTx.fundingParams, fundingTx, txSigs) match {
          case Left(cause) =>
            val unsignedFundingTx = fundingTx.tx.buildUnsignedTx()
            log.warning("received invalid tx_signatures for txid={} (current funding txid={}): {}", txSigs.txId, unsignedFundingTx.txid, cause.getMessage)
            // The funding transaction may still confirm (since our peer should be able to generate valid signatures),
            // so we cannot close the channel yet.
            stay() sending Error(d.channelId, InvalidFundingSignature(d.channelId, Some(unsignedFundingTx.txid)).getMessage)
          case Right(fundingTx) =>
            log.info("publishing funding tx for channelId={} fundingTxId={}", d.channelId, fundingTx.signedTx.txid)
            val dfu1 = d.latestFundingTx.copy(sharedTx = fundingTx)
            val d1 = d.modify(_.commitments.active.at(0).localFundingStatus).setTo(dfu1)
            stay() using d1 storing() calling publishFundingTx(dfu1)
        }
        case _: FullySignedSharedTransaction =>
          d.rbfStatus match {
            case RbfStatus.RbfWaitingForSigs(signingSession) =>
              signingSession.receiveTxSigs(nodeParams, d.commitments.params, txSigs) match {
                case Left(f) =>
                  rollbackRbfAttempt(signingSession, d)
                  stay() using d.copy(rbfStatus = RbfStatus.RbfAborted) sending TxAbort(d.channelId, f.getMessage)
                case Right(signingSession1) =>
                  val minDepth_opt = d.commitments.params.minDepthDualFunding(nodeParams.channelConf.minDepthBlocks, signingSession1.fundingTx.sharedTx.tx)
                  watchFundingConfirmed(signingSession.fundingTx.txId, minDepth_opt, delay_opt = None)
                  val commitments1 = d.commitments.add(signingSession1.commitment)
                  val d1 = DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED(commitments1, d.localPushAmount, d.remotePushAmount, d.waitingSince, d.lastChecked, RbfStatus.NoRbf, d.deferred)
                  stay() using d1 storing() sending signingSession1.localSigs calling publishFundingTx(signingSession1.fundingTx)
              }
            case _ if d.commitments.all.exists(_.fundingTxId == txSigs.txId) =>
              log.debug("ignoring tx_signatures that we already received for txId={}", txSigs.txId)
              stay()
            case _ =>
              log.debug("rejecting unexpected tx_signatures for txId={}", txSigs.txId)
              reportRbfFailure(d.rbfStatus, UnexpectedFundingSignatures(d.channelId))
              stay() using d.copy(rbfStatus = RbfStatus.RbfAborted) sending TxAbort(d.channelId, UnexpectedFundingSignatures(d.channelId).getMessage)
          }
      }

    case Event(cmd: CMD_BUMP_FUNDING_FEE, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      val zeroConf = d.commitments.params.minDepthDualFunding(nodeParams.channelConf.minDepthBlocks, d.latestFundingTx.sharedTx.tx).isEmpty
      if (!d.latestFundingTx.fundingParams.isInitiator) {
        cmd.replyTo ! RES_FAILURE(cmd, InvalidRbfNonInitiator(d.channelId))
        stay()
      } else if (zeroConf) {
        cmd.replyTo ! RES_FAILURE(cmd, InvalidRbfZeroConf(d.channelId))
        stay()
      } else {
        d.rbfStatus match {
          case RbfStatus.NoRbf =>
            val minNextFeerate = d.latestFundingTx.fundingParams.minNextFeerate
            if (cmd.targetFeerate < minNextFeerate) {
              cmd.replyTo ! RES_FAILURE(cmd, InvalidRbfFeerate(d.channelId, cmd.targetFeerate, minNextFeerate))
              stay()
            } else {
              val txInitRbf = TxInitRbf(d.channelId, cmd.lockTime, cmd.targetFeerate, d.latestFundingTx.fundingParams.localContribution, d.latestFundingTx.fundingParams.requireConfirmedInputs.forRemote, cmd.requestFunding_opt)
              stay() using d.copy(rbfStatus = RbfStatus.RbfRequested(cmd)) sending txInitRbf
            }
          case _ =>
            log.warning("cannot initiate rbf, another one is already in progress")
            cmd.replyTo ! RES_FAILURE(cmd, InvalidRbfAlreadyInProgress(d.channelId))
            stay()
        }
      }

    case Event(msg: TxInitRbf, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      val zeroConf = d.commitments.params.minDepthDualFunding(nodeParams.channelConf.minDepthBlocks, d.latestFundingTx.sharedTx.tx).isEmpty
      if (d.latestFundingTx.fundingParams.isInitiator) {
        // Only the initiator is allowed to initiate RBF.
        log.info("rejecting tx_init_rbf, we're the initiator, not them!")
        stay() sending Error(d.channelId, InvalidRbfNonInitiator(d.channelId).getMessage)
      } else if (zeroConf) {
        log.info("rejecting tx_init_rbf, we're using zero-conf")
        stay() using d.copy(rbfStatus = RbfStatus.RbfAborted) sending TxAbort(d.channelId, InvalidRbfZeroConf(d.channelId).getMessage)
      } else {
        val minNextFeerate = d.latestFundingTx.fundingParams.minNextFeerate
        d.rbfStatus match {
          case RbfStatus.NoRbf =>
            val remainingRbfAttempts = nodeParams.channelConf.remoteRbfLimits.maxAttempts - d.previousFundingTxs.length
            if (msg.feerate < minNextFeerate) {
              log.info("rejecting rbf attempt: the new feerate must be at least {} (proposed={})", minNextFeerate, msg.feerate)
              stay() using d.copy(rbfStatus = RbfStatus.RbfAborted) sending TxAbort(d.channelId, InvalidRbfFeerate(d.channelId, msg.feerate, minNextFeerate).getMessage)
            } else if (d.remotePushAmount > msg.fundingContribution) {
              log.info("rejecting rbf attempt: invalid amount pushed (fundingAmount={}, pushAmount={})", msg.fundingContribution, d.remotePushAmount)
              stay() using d.copy(rbfStatus = RbfStatus.RbfAborted) sending TxAbort(d.channelId, InvalidPushAmount(d.channelId, d.remotePushAmount, msg.fundingContribution.toMilliSatoshi).getMessage)
            } else if (remainingRbfAttempts <= 0) {
              log.info("rejecting rbf attempt: maximum number of attempts reached (max={})", nodeParams.channelConf.remoteRbfLimits.maxAttempts)
              stay() using d.copy(rbfStatus = RbfStatus.RbfAborted) sending TxAbort(d.channelId, InvalidRbfAttemptsExhausted(d.channelId, nodeParams.channelConf.remoteRbfLimits.maxAttempts).getMessage)
            } else if (nodeParams.currentBlockHeight < d.latestFundingTx.createdAt + nodeParams.channelConf.remoteRbfLimits.attemptDeltaBlocks) {
              log.info("rejecting rbf attempt: last attempt was less than {} blocks ago", nodeParams.channelConf.remoteRbfLimits.attemptDeltaBlocks)
              stay() using d.copy(rbfStatus = RbfStatus.RbfAborted) sending TxAbort(d.channelId, InvalidRbfAttemptTooSoon(d.channelId, d.latestFundingTx.createdAt, d.latestFundingTx.createdAt + nodeParams.channelConf.remoteRbfLimits.attemptDeltaBlocks).getMessage)
            } else {
              val fundingScript = d.commitments.latest.commitInput.txOut.publicKeyScript
              LiquidityAds.validateRequest(nodeParams.privateKey, d.channelId, fundingScript, msg.feerate, isChannelCreation = true, msg.requestFunding_opt, nodeParams.willFundRates_opt, None) match {
                case Left(t) =>
                  log.warning("rejecting rbf attempt: invalid liquidity ads request ({})", t.getMessage)
                  stay() using d.copy(rbfStatus = RbfStatus.RbfAborted) sending TxAbort(d.channelId, t.getMessage)
                case Right(willFund_opt) =>
                  log.info("our peer wants to raise the feerate of the funding transaction (previous={} target={})", d.latestFundingTx.fundingParams.targetFeerate, msg.feerate)
                  // We contribute the amount of liquidity requested by our peer, if liquidity ads is active.
                  val fundingParams = d.latestFundingTx.fundingParams.copy(
                    localContribution = willFund_opt.map(_.purchase.amount).getOrElse(d.latestFundingTx.fundingParams.localContribution),
                    remoteContribution = msg.fundingContribution,
                    lockTime = msg.lockTime,
                    targetFeerate = msg.feerate,
                    requireConfirmedInputs = d.latestFundingTx.fundingParams.requireConfirmedInputs.copy(forLocal = msg.requireConfirmedInputs)
                  )
                  val txBuilder = context.spawnAnonymous(InteractiveTxBuilder(
                    randomBytes32(),
                    nodeParams, fundingParams,
                    channelParams = d.commitments.params,
                    purpose = InteractiveTxBuilder.PreviousTxRbf(d.commitments.active.head, 0 msat, 0 msat, previousTransactions = d.allFundingTxs.map(_.sharedTx), feeBudget_opt = None),
                    localPushAmount = d.localPushAmount, remotePushAmount = d.remotePushAmount,
                    liquidityPurchase_opt = willFund_opt.map(_.purchase),
                    wallet,
                    None))
                  txBuilder ! InteractiveTxBuilder.Start(self)
                  val toSend = Seq(
                    Some(TxAckRbf(d.channelId, fundingParams.localContribution, d.latestFundingTx.fundingParams.requireConfirmedInputs.forRemote, willFund_opt.map(_.willFund))),
                    if (remainingRbfAttempts <= 3) Some(Warning(d.channelId, s"will accept at most ${remainingRbfAttempts - 1} future rbf attempts")) else None,
                  ).flatten
                  stay() using d.copy(rbfStatus = RbfStatus.RbfInProgress(cmd_opt = None, txBuilder, remoteCommitSig = None)) sending toSend
              }
            }
          case RbfStatus.RbfAborted =>
            log.info("rejecting rbf attempt: our previous tx_abort was not acked")
            stay() sending Warning(d.channelId, InvalidRbfTxAbortNotAcked(d.channelId).getMessage)
          case _: RbfStatus.RbfRequested | _: RbfStatus.RbfInProgress | _: RbfStatus.RbfWaitingForSigs =>
            log.info("rejecting rbf attempt: the current rbf attempt must be completed or aborted first")
            stay() sending Warning(d.channelId, InvalidRbfAlreadyInProgress(d.channelId).getMessage)
        }
      }

    case Event(msg: TxAckRbf, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      d.rbfStatus match {
        case RbfStatus.RbfRequested(cmd) if d.remotePushAmount > msg.fundingContribution =>
          log.info("rejecting rbf attempt: invalid amount pushed (fundingAmount={}, pushAmount={})", msg.fundingContribution, d.remotePushAmount)
          val error = InvalidPushAmount(d.channelId, d.remotePushAmount, msg.fundingContribution.toMilliSatoshi)
          cmd.replyTo ! RES_FAILURE(cmd, error)
          stay() using d.copy(rbfStatus = RbfStatus.RbfAborted) sending TxAbort(d.channelId, error.getMessage)
        case RbfStatus.RbfRequested(cmd) =>
          val fundingParams = d.latestFundingTx.fundingParams.copy(
            // we don't change our funding contribution
            remoteContribution = msg.fundingContribution,
            lockTime = cmd.lockTime,
            targetFeerate = cmd.targetFeerate,
          )
          val fundingScript = d.commitments.latest.commitInput.txOut.publicKeyScript
          LiquidityAds.validateRemoteFunding(cmd.requestFunding_opt, remoteNodeId, d.channelId, fundingScript, msg.fundingContribution, cmd.targetFeerate, isChannelCreation = true, msg.willFund_opt) match {
            case Left(t) =>
              log.warning("rejecting rbf attempt: invalid liquidity ads response ({})", t.getMessage)
              cmd.replyTo ! RES_FAILURE(cmd, t)
              stay() using d.copy(rbfStatus = RbfStatus.RbfAborted) sending TxAbort(d.channelId, t.getMessage)
            case Right(liquidityPurchase_opt) =>
              log.info("our peer accepted our rbf attempt and will contribute {} to the funding transaction", msg.fundingContribution)
              val txBuilder = context.spawnAnonymous(InteractiveTxBuilder(
                randomBytes32(),
                nodeParams, fundingParams,
                channelParams = d.commitments.params,
                purpose = InteractiveTxBuilder.PreviousTxRbf(d.commitments.active.head, 0 msat, 0 msat, previousTransactions = d.allFundingTxs.map(_.sharedTx), feeBudget_opt = Some(cmd.fundingFeeBudget)),
                localPushAmount = d.localPushAmount, remotePushAmount = d.remotePushAmount,
                liquidityPurchase_opt = liquidityPurchase_opt,
                wallet,
                None))
              txBuilder ! InteractiveTxBuilder.Start(self)
              stay() using d.copy(rbfStatus = RbfStatus.RbfInProgress(cmd_opt = Some(cmd), txBuilder, remoteCommitSig = None))
          }
        case _ =>
          log.info("ignoring unexpected tx_ack_rbf")
          stay() sending Warning(d.channelId, UnexpectedInteractiveTxMessage(d.channelId, msg).getMessage)
      }

    case Event(msg: InteractiveTxConstructionMessage, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      d.rbfStatus match {
        case RbfStatus.RbfInProgress(_, txBuilder, _) =>
          txBuilder ! InteractiveTxBuilder.ReceiveMessage(msg)
          stay()
        case _ =>
          log.info("ignoring unexpected interactive-tx message: {}", msg.getClass.getSimpleName)
          stay() sending Warning(d.channelId, UnexpectedInteractiveTxMessage(d.channelId, msg).getMessage)
      }

    case Event(commitSig: CommitSig, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      d.rbfStatus match {
        case s: RbfStatus.RbfInProgress =>
          log.debug("received their commit_sig, deferring message")
          stay() using d.copy(rbfStatus = s.copy(remoteCommitSig = Some(commitSig)))
        case RbfStatus.RbfWaitingForSigs(signingSession) =>
          signingSession.receiveCommitSig(nodeParams, d.commitments.params, commitSig) match {
            case Left(f) =>
              rollbackRbfAttempt(signingSession, d)
              stay() using d.copy(rbfStatus = RbfStatus.RbfAborted) sending TxAbort(d.channelId, f.getMessage)
            case Right(signingSession1) => signingSession1 match {
              case signingSession1: InteractiveTxSigningSession.WaitingForSigs =>
                // No need to store their commit_sig, they will re-send it if we disconnect.
                stay() using d.copy(rbfStatus = RbfStatus.RbfWaitingForSigs(signingSession1))
              case signingSession1: InteractiveTxSigningSession.SendingSigs =>
                val minDepth_opt = d.commitments.params.minDepthDualFunding(nodeParams.channelConf.minDepthBlocks, signingSession1.fundingTx.sharedTx.tx)
                watchFundingConfirmed(signingSession.fundingTx.txId, minDepth_opt, delay_opt = None)
                val commitments1 = d.commitments.add(signingSession1.commitment)
                val d1 = DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED(commitments1, d.localPushAmount, d.remotePushAmount, d.waitingSince, d.lastChecked, RbfStatus.NoRbf, d.deferred)
                stay() using d1 storing() sending signingSession1.localSigs
            }
          }
        case _ =>
          log.info("ignoring redundant commit_sig")
          stay()
      }

    case Event(msg: TxAbort, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      d.rbfStatus match {
        case RbfStatus.RbfInProgress(cmd_opt, txBuilder, _) =>
          log.info("our peer aborted the rbf attempt: ascii='{}' bin={}", msg.toAscii, msg.data)
          cmd_opt.foreach(cmd => cmd.replyTo ! RES_FAILURE(cmd, RbfAttemptAborted(d.channelId)))
          txBuilder ! InteractiveTxBuilder.Abort
          stay() using d.copy(rbfStatus = RbfStatus.NoRbf) sending TxAbort(d.channelId, RbfAttemptAborted(d.channelId).getMessage)
        case RbfStatus.RbfWaitingForSigs(signingSession) =>
          log.info("our peer aborted the rbf attempt: ascii='{}' bin={}", msg.toAscii, msg.data)
          rollbackRbfAttempt(signingSession, d)
          stay() using d.copy(rbfStatus = RbfStatus.NoRbf) sending TxAbort(d.channelId, RbfAttemptAborted(d.channelId).getMessage)
        case RbfStatus.RbfRequested(cmd) =>
          log.info("our peer rejected our rbf attempt: ascii='{}' bin={}", msg.toAscii, msg.data)
          cmd.replyTo ! RES_FAILURE(cmd, new RuntimeException(s"rbf attempt rejected by our peer: ${msg.toAscii}"))
          stay() using d.copy(rbfStatus = RbfStatus.NoRbf) sending TxAbort(d.channelId, RbfAttemptAborted(d.channelId).getMessage)
        case RbfStatus.RbfAborted =>
          log.debug("our peer acked our previous tx_abort")
          stay() using d.copy(rbfStatus = RbfStatus.NoRbf)
        case RbfStatus.NoRbf =>
          log.info("our peer wants to abort the dual funding flow, but we've already negotiated a funding transaction: ascii='{}' bin={}", msg.toAscii, msg.data)
          // We ack their tx_abort but we keep monitoring the funding transaction until it's confirmed or double-spent.
          stay() sending TxAbort(d.channelId, DualFundingAborted(d.channelId).getMessage)
      }

    case Event(msg: InteractiveTxBuilder.Response, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      d.rbfStatus match {
        case RbfStatus.RbfInProgress(cmd_opt, _, remoteCommitSig_opt) =>
          msg match {
            case InteractiveTxBuilder.SendMessage(_, msg) => stay() sending msg
            case InteractiveTxBuilder.Succeeded(signingSession, commitSig, liquidityPurchase_opt) =>
              cmd_opt.foreach(cmd => cmd.replyTo ! RES_BUMP_FUNDING_FEE(rbfIndex = d.previousFundingTxs.length, signingSession.fundingTx.txId, signingSession.fundingTx.tx.localFees.truncateToSatoshi))
              remoteCommitSig_opt.foreach(self ! _)
              liquidityPurchase_opt.collect {
                case purchase if !signingSession.fundingParams.isInitiator => peer ! LiquidityPurchaseSigned(d.channelId, signingSession.fundingTx.txId, signingSession.fundingTxIndex, d.commitments.params.remoteParams.htlcMinimum, purchase)
              }
              val d1 = d.copy(rbfStatus = RbfStatus.RbfWaitingForSigs(signingSession))
              stay() using d1 storing() sending commitSig
            case f: InteractiveTxBuilder.Failed =>
              log.info("rbf attempt failed: {}", f.cause.getMessage)
              cmd_opt.foreach(cmd => cmd.replyTo ! RES_FAILURE(cmd, f.cause))
              stay() using d.copy(rbfStatus = RbfStatus.RbfAborted) sending TxAbort(d.channelId, f.cause.getMessage)
          }
        case _ =>
          // This can happen if we received a tx_abort right before receiving the interactive-tx result.
          log.warning("ignoring interactive-tx result with rbfStatus={}", d.rbfStatus.getClass.getSimpleName)
          stay()
      }

    case Event(w: WatchPublishedTriggered, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      log.info("funding txid={} was successfully published for zero-conf channelId={}", w.tx.txid, d.channelId)
      val fundingStatus = LocalFundingStatus.ZeroconfPublishedFundingTx(w.tx, d.commitments.localFundingSigs(w.tx.txid), d.commitments.liquidityPurchase(w.tx.txid))
      d.commitments.updateLocalFundingStatus(w.tx.txid, fundingStatus) match {
        case Right((commitments1, _)) =>
          // we still watch the funding tx for confirmation even if we can use the zero-conf channel right away
          watchFundingConfirmed(w.tx.txid, Some(nodeParams.channelConf.minDepthBlocks), delay_opt = None)
          val realScidStatus = RealScidStatus.Unknown
          val shortIds = createShortIds(d.channelId, realScidStatus)
          val channelReady = createChannelReady(shortIds, d.commitments.params)
          d.deferred.foreach(self ! _)
          goto(WAIT_FOR_DUAL_FUNDING_READY) using DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments1, shortIds) storing() sending channelReady
        case Left(_) => stay()
      }

    case Event(w: WatchFundingConfirmedTriggered, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      acceptFundingTxConfirmed(w, d) match {
        case Right((commitments1, commitment)) =>
          val realScidStatus = RealScidStatus.Temporary(RealShortChannelId(w.blockHeight, w.txIndex, commitment.commitInput.outPoint.index.toInt))
          val shortIds = createShortIds(d.channelId, realScidStatus)
          val channelReady = createChannelReady(shortIds, d.commitments.params)
          reportRbfFailure(d.rbfStatus, InvalidRbfTxConfirmed(d.channelId))
          val toSend = d.rbfStatus match {
            case RbfStatus.NoRbf | RbfStatus.RbfAborted => Seq(channelReady)
            case _: RbfStatus.RbfRequested | _: RbfStatus.RbfInProgress | _: RbfStatus.RbfWaitingForSigs => Seq(TxAbort(d.channelId, InvalidRbfTxConfirmed(d.channelId).getMessage), channelReady)
          }
          d.deferred.foreach(self ! _)
          goto(WAIT_FOR_DUAL_FUNDING_READY) using DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments1, shortIds) storing() sending toSend
        case Left(_) => stay()
      }

    case Event(ProcessCurrentBlockHeight(c), d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) => handleNewBlockDualFundingUnconfirmed(c, d)

    case Event(e: BITCOIN_FUNDING_DOUBLE_SPENT, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      reportRbfFailure(d.rbfStatus, FundingTxDoubleSpent(d.channelId))
      handleDualFundingDoubleSpent(e, d)

    case Event(remoteChannelReady: ChannelReady, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      if (switchToZeroConf(remoteChannelReady, d)) {
        log.info("this channel isn't zero-conf, but they sent an early channel_ready with an alias: no need to wait for confirmations")
        blockchain ! WatchPublished(self, d.commitments.latest.fundingTxId)
      }
      log.debug("received their channel_ready, deferring message")
      stay() using d.copy(deferred = Some(remoteChannelReady)) // no need to store, they will re-send if we get disconnected

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) if d.commitments.announceChannel =>
      delayEarlyAnnouncementSigs(remoteAnnSigs)
      stay()

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      reportRbfFailure(d.rbfStatus, new RuntimeException("rbf attempt failed: disconnected"))
      val d1 = d.rbfStatus match {
        // We keep track of the RBF status: we should be able to complete the signature steps on reconnection.
        case _: RbfStatus.RbfWaitingForSigs => d
        case _ => d.copy(rbfStatus = RbfStatus.NoRbf)
      }
      goto(OFFLINE) using d1

    case Event(e: Error, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      reportRbfFailure(d.rbfStatus, new RuntimeException(s"remote error: ${e.toAscii}"))
      handleRemoteError(e, d)
  })

  when(WAIT_FOR_DUAL_FUNDING_READY)(handleExceptions {
    case Event(channelReady: ChannelReady, d: DATA_WAIT_FOR_DUAL_FUNDING_READY) =>
      val d1 = receiveChannelReady(d.shortIds, channelReady, d.commitments)
      goto(NORMAL) using d1 storing()

    case Event(_: TxInitRbf, d: DATA_WAIT_FOR_DUAL_FUNDING_READY) =>
      // Our peer may not have received the funding transaction confirmation.
      stay() sending TxAbort(d.channelId, InvalidRbfTxConfirmed(d.channelId).getMessage)

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_DUAL_FUNDING_READY) if d.commitments.announceChannel =>
      delayEarlyAnnouncementSigs(remoteAnnSigs)
      stay()

    case Event(e: Error, d: DATA_WAIT_FOR_DUAL_FUNDING_READY) => handleRemoteError(e, d)
  })

}
