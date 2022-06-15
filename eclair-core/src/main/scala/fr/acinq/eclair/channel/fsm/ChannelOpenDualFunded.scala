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

import fr.acinq.bitcoin.scalacompat.{SatoshiLong, Script}
import fr.acinq.eclair.Features
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel.publish.TxPublisher.SetChannelId
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.protocol.{AcceptDualFundedChannel, AcceptDualFundedChannelTlv, ChannelTlv, Error, OpenDualFundedChannel, OpenDualFundedChannelTlv, TlvStream}

/**
 * Created by t-bast on 19/04/2022.
 */

trait ChannelOpenDualFunded extends FundingHandlers with ErrorHandlers {

  this: Channel =>

  /*
                                     INITIATOR                       NON_INITIATOR
                                         |                                 |
                                         |          open_channel2          | WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL
                                         |-------------------------------->|
     WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL |                                 |
                                         |         accept_channel2         |
                                         |<--------------------------------|
          WAIT_FOR_DUAL_FUNDING_COMPLETE |                                 | WAIT_FOR_DUAL_FUNDING_COMPLETE
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
                                         |          tx_signatures          |
                                         |-------------------------------->|
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
      val fundingPubKey = keyManager.fundingPublicKey(input.localParams.fundingKeyPath).publicKey
      val channelKeyPath = keyManager.keyPath(input.localParams, input.channelConfig)
      val tlvs: TlvStream[OpenDualFundedChannelTlv] = if (Features.canUseFeature(input.localParams.initFeatures, input.remoteInit.features, Features.UpfrontShutdownScript)) {
        TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(input.localParams.defaultFinalScriptPubKey), ChannelTlv.ChannelTypeTlv(input.channelType))
      } else {
        TlvStream(ChannelTlv.ChannelTypeTlv(input.channelType))
      }
      val open = OpenDualFundedChannel(
        chainHash = nodeParams.chainHash,
        temporaryChannelId = input.temporaryChannelId,
        fundingFeerate = input.fundingTxFeerate,
        commitmentFeerate = input.commitTxFeerate,
        fundingAmount = input.fundingAmount,
        dustLimit = input.localParams.dustLimit,
        maxHtlcValueInFlightMsat = input.localParams.maxHtlcValueInFlightMsat,
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
        channelFlags = input.channelFlags,
        tlvStream = tlvs)
      goto(WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL) using DATA_WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL(input, open) sending open
  })

  when(WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL)(handleExceptions {
    case Event(open: OpenDualFundedChannel, d: DATA_WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL) =>
      import d.init.{localParams, remoteInit}
      Helpers.validateParamsNonInitiator(nodeParams, d.init.channelType, open, remoteNodeId, localParams.initFeatures, remoteInit.features) match {
        case Left(t) => handleLocalError(t, d, Some(open))
        case Right((channelFeatures, remoteShutdownScript)) =>
          context.system.eventStream.publish(ChannelCreated(self, peer, remoteNodeId, isInitiator = false, open.temporaryChannelId, open.commitmentFeerate, Some(open.fundingFeerate)))
          val fundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
          val channelKeyPath = keyManager.keyPath(localParams, d.init.channelConfig)
          val totalFundingAmount = open.fundingAmount + d.init.fundingContribution_opt.getOrElse(0 sat)
          val minimumDepth = Funding.minDepthFundee(nodeParams.channelConf, channelFeatures, totalFundingAmount)
          val tlvs: TlvStream[AcceptDualFundedChannelTlv] = if (Features.canUseFeature(localParams.initFeatures, remoteInit.features, Features.UpfrontShutdownScript)) {
            TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(localParams.defaultFinalScriptPubKey), ChannelTlv.ChannelTypeTlv(d.init.channelType))
          } else {
            TlvStream(ChannelTlv.ChannelTypeTlv(d.init.channelType))
          }
          val accept = AcceptDualFundedChannel(
            temporaryChannelId = open.temporaryChannelId,
            fundingAmount = d.init.fundingContribution_opt.getOrElse(0 sat),
            dustLimit = localParams.dustLimit,
            maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
            htlcMinimum = localParams.htlcMinimum,
            minimumDepth = minimumDepth.getOrElse(0),
            toSelfDelay = localParams.toSelfDelay,
            maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
            fundingPubkey = fundingPubkey,
            revocationBasepoint = keyManager.revocationPoint(channelKeyPath).publicKey,
            paymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey),
            delayedPaymentBasepoint = keyManager.delayedPaymentPoint(channelKeyPath).publicKey,
            htlcBasepoint = keyManager.htlcPoint(channelKeyPath).publicKey,
            firstPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0),
            tlvStream = tlvs)
          val remoteParams = RemoteParams(
            nodeId = remoteNodeId,
            dustLimit = open.dustLimit,
            maxHtlcValueInFlightMsat = open.maxHtlcValueInFlightMsat,
            requestedChannelReserve_opt = None, // channel reserve will be computed based on channel capacity
            htlcMinimum = open.htlcMinimum,
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
          val channelId = Helpers.computeChannelId(open, accept)
          peer ! ChannelIdAssigned(self, remoteNodeId, accept.temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
          txPublisher ! SetChannelId(remoteNodeId, channelId)
          context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, accept.temporaryChannelId, channelId))
          goto(WAIT_FOR_DUAL_FUNDING_INTERNAL) using DATA_WAIT_FOR_DUAL_FUNDING_INTERNAL(channelId, localParams, remoteParams, channelFeatures) sending accept
      }

    case Event(c: CloseCommand, d) => handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL)(handleExceptions {
    case Event(accept: AcceptDualFundedChannel, d: DATA_WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL) =>
      import d.init.{localParams, remoteInit}
      Helpers.validateParamsInitiator(nodeParams, d.init.channelType, localParams.initFeatures, remoteInit.features, d.lastSent, accept) match {
        case Left(t) =>
          channelOpenReplyToUser(Left(LocalError(t)))
          handleLocalError(t, d, Some(accept))
        case Right((channelFeatures, remoteShutdownScript)) =>
          val channelId = Helpers.computeChannelId(d.lastSent, accept)
          peer ! ChannelIdAssigned(self, remoteNodeId, accept.temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
          txPublisher ! SetChannelId(remoteNodeId, channelId)
          context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, accept.temporaryChannelId, channelId))
          val remoteParams = RemoteParams(
            nodeId = remoteNodeId,
            dustLimit = accept.dustLimit,
            maxHtlcValueInFlightMsat = accept.maxHtlcValueInFlightMsat,
            requestedChannelReserve_opt = None, // channel reserve will be computed based on channel capacity
            htlcMinimum = accept.htlcMinimum,
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
          goto(WAIT_FOR_DUAL_FUNDING_INTERNAL) using DATA_WAIT_FOR_DUAL_FUNDING_INTERNAL(channelId, localParams, remoteParams, channelFeatures)
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.channelId)))
      handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL) =>
      channelOpenReplyToUser(Left(RemoteError(e)))
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED)
  })

  when(WAIT_FOR_DUAL_FUNDING_INTERNAL)(handleExceptions {
    case Event(c: CloseCommand, d: DATA_WAIT_FOR_DUAL_FUNDING_INTERNAL) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.channelId)))
      handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_DUAL_FUNDING_INTERNAL) =>
      channelOpenReplyToUser(Left(RemoteError(e)))
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED)
  })

}
