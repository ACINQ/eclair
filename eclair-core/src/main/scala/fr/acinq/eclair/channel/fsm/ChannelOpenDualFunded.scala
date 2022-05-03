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
import fr.acinq.bitcoin.scalacompat.{SatoshiLong, Script}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel.InteractiveTx.InteractiveTxParams
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel.publish.TxPublisher.SetChannelId
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions.TxOwner
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Features, MilliSatoshiLong}

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
                                         |          tx_signatures          |
                                         |-------------------------------->|
         WAIT_FOR_DUAL_FUNDING_CONFIRMED |                                 | WAIT_FOR_DUAL_FUNDING_CONFIRMED
                                         |           tx_init_rbf           |
                                         |-------------------------------->|
                                         |           tx_ack_rbf            |
                                         |<--------------------------------|
       WAIT_FOR_DUAL_FUNDING_RBF_CREATED |                                 | WAIT_FOR_DUAL_FUNDING_RBF_CREATED
                                         |    <interactive-tx protocol>    |
                                         |                .                |
                                         |                .                |
                                         |                .                |
                                         |           tx_complete           |
                                         |-------------------------------->|
                                         |           tx_complete           |
                                         |<--------------------------------|
        WAIT_FOR_DUAL_FUNDING_RBF_SIGNED |                                 | WAIT_FOR_DUAL_FUNDING_RBF_SIGNED
                                         |        commitment_signed        |
                                         |-------------------------------->|
                                         |        commitment_signed        |
                                         |<--------------------------------|
                                         |          tx_signatures          |
                                         |<--------------------------------|
                                         |          tx_signatures          |
                                         |-------------------------------->|
         WAIT_FOR_DUAL_FUNDING_CONFIRMED |                                 | WAIT_FOR_DUAL_FUNDING_CONFIRMED
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
          val minimumDepth = Helpers.minDepthForFunding(nodeParams.channelConf, totalFundingAmount)
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
            minimumDepth = minimumDepth,
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
          // We've exchanged open_channel2 and accept_channel2, we now know the final channelId.
          val channelId = Helpers.computeChannelId(open, accept)
          peer ! ChannelIdAssigned(self, remoteNodeId, accept.temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
          txPublisher ! SetChannelId(remoteNodeId, channelId)
          context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, accept.temporaryChannelId, channelId))
          // We start the interactive-tx funding protocol.
          val localFundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
          val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey.publicKey, remoteParams.fundingPubKey)))
          val fundingParams = InteractiveTxParams(channelId, localParams.isInitiator, accept.fundingAmount, open.fundingAmount, fundingPubkeyScript, open.lockTime, open.dustLimit.max(accept.dustLimit), open.fundingFeerate)
          val fundingActor = context.spawnAnonymous(InteractiveTxFunder(remoteNodeId, fundingParams, wallet))
          fundingActor ! InteractiveTxFunder.Fund(self, Nil)
          goto(WAIT_FOR_DUAL_FUNDING_INTERNAL) using DATA_WAIT_FOR_DUAL_FUNDING_INTERNAL(channelId, localParams, remoteParams, fundingParams, open.commitmentFeerate, open.firstPerCommitmentPoint, open.channelFlags, d.init.channelConfig, channelFeatures, None) sending accept
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
          // We've exchanged open_channel2 and accept_channel2, we now know the final channelId.
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
          // We start the interactive-tx funding protocol.
          val localFundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
          val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey.publicKey, remoteParams.fundingPubKey)))
          val fundingParams = InteractiveTxParams(channelId, localParams.isInitiator, d.lastSent.fundingAmount, accept.fundingAmount, fundingPubkeyScript, d.lastSent.lockTime, d.lastSent.dustLimit.max(accept.dustLimit), d.lastSent.fundingFeerate)
          val fundingActor = context.spawnAnonymous(InteractiveTxFunder(remoteNodeId, fundingParams, wallet))
          fundingActor ! InteractiveTxFunder.Fund(self, Nil)
          goto(WAIT_FOR_DUAL_FUNDING_INTERNAL) using DATA_WAIT_FOR_DUAL_FUNDING_INTERNAL(channelId, localParams, remoteParams, fundingParams, d.lastSent.commitmentFeerate, accept.firstPerCommitmentPoint, d.lastSent.channelFlags, d.init.channelConfig, channelFeatures, None)
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
    case Event(InteractiveTxFunder.FundingSucceeded(localContributions), d: DATA_WAIT_FOR_DUAL_FUNDING_INTERNAL) =>
      val (txSession, msg_opt) = InteractiveTx.start(d.fundingParams, localContributions)
      d.remoteMessage.foreach(self ! _)
      val nextData = DATA_WAIT_FOR_DUAL_FUNDING_CREATED(d.channelId, d.localParams, d.remoteParams, d.fundingParams, txSession, d.commitTxFeerate, d.remoteFirstPerCommitmentPoint, d.channelFlags, d.channelConfig, d.channelFeatures)
      msg_opt match {
        case Some(msg) => goto(WAIT_FOR_DUAL_FUNDING_CREATED) using nextData sending msg
        case None => goto(WAIT_FOR_DUAL_FUNDING_CREATED) using nextData
      }

    case Event(InteractiveTxFunder.FundingFailed(t), d: DATA_WAIT_FOR_DUAL_FUNDING_INTERNAL) =>
      log.error(t, s"could not fund dual-funded channel: ")
      channelOpenReplyToUser(Left(LocalError(t)))
      handleLocalError(ChannelFundingError(d.channelId), d, None) // we use a generic exception and don't send the internal error to the peer

    case Event(msg: InteractiveTxMessage, d: DATA_WAIT_FOR_DUAL_FUNDING_INTERNAL) =>
      // When we're not the initiator, we may receive their first interactive-tx message while we're funding our contribution.
      stay() using d.copy(remoteMessage = Some(msg))

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

  when(WAIT_FOR_DUAL_FUNDING_CREATED)(handleExceptions {
    case Event(msg: InteractiveTxMessage, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      msg match {
        case msg: InteractiveTxConstructionMessage =>
          InteractiveTx.receive(d.txSession, d.fundingParams, msg) match {
            case Left(cause) => handleInteractiveTxError(InteractiveTx.dummyLocalTx(d.txSession), cause, d, Some(msg))
            case Right((txSession1, outgoingMsg_opt)) =>
              if (txSession1.isComplete) {
                InteractiveTx.validateTx(txSession1, d.fundingParams) match {
                  case Left(cause) => handleInteractiveTxError(InteractiveTx.dummyLocalTx(d.txSession), cause, d, Some(msg))
                  case Right((completeTx, fundingOutputIndex)) =>
                    val fundingTx = completeTx.buildUnsignedTx()
                    Funding.makeFirstCommitTxs(keyManager, d.channelConfig, d.channelFeatures, d.channelId, d.localParams, d.remoteParams, d.fundingParams.localAmount, d.fundingParams.remoteAmount, 0 msat, d.commitTxFeerate, fundingTx.hash, fundingOutputIndex, d.remoteFirstPerCommitmentPoint) match {
                      case Left(cause) => handleInteractiveTxError(InteractiveTx.dummyLocalTx(d.txSession), cause, d, Some(msg))
                      case Right((_, localCommitTx, _, remoteCommitTx)) =>
                        require(fundingTx.txOut(fundingOutputIndex).publicKeyScript == localCommitTx.input.txOut.publicKeyScript, s"pubkey script mismatch!")
                        val localSigOfRemoteTx = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(d.localParams.fundingKeyPath), TxOwner.Remote, d.channelFeatures.commitmentFormat)
                        val commitSig = CommitSig(d.channelId, localSigOfRemoteTx, Nil)
                        val nextData = DATA_WAIT_FOR_DUAL_FUNDING_SIGNED(d.channelId, d.localParams, d.remoteParams, d.fundingParams, d.commitTxFeerate, d.remoteFirstPerCommitmentPoint, d.channelFlags, d.channelConfig, d.channelFeatures)
                        goto(WAIT_FOR_DUAL_FUNDING_SIGNED) using nextData sending Seq(outgoingMsg_opt, Some(commitSig)).flatten
                    }
                }
              } else {
                stay() using d.copy(txSession = txSession1) sending outgoingMsg_opt
              }
          }
        case _: TxAbort => handleInteractiveTxError(InteractiveTx.dummyLocalTx(d.txSession), DualFundingAborted(d.channelId), d, Some(msg))
        case _: TxSignatures => handleInteractiveTxError(InteractiveTx.dummyLocalTx(d.txSession), UnexpectedFundingSignatures(d.channelId), d, Some(msg))
        case _: TxInitRbf => handleInteractiveTxError(InteractiveTx.dummyLocalTx(d.txSession), InvalidRbfAttempt(d.channelId), d, Some(msg))
        case _: TxAckRbf => handleInteractiveTxError(InteractiveTx.dummyLocalTx(d.txSession), InvalidRbfAttempt(d.channelId), d, Some(msg))
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      wallet.rollback(InteractiveTx.dummyLocalTx(d.txSession))
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.channelId)))
      handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      wallet.rollback(InteractiveTx.dummyLocalTx(d.txSession))
      channelOpenReplyToUser(Left(RemoteError(e)))
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      wallet.rollback(InteractiveTx.dummyLocalTx(d.txSession))
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      wallet.rollback(InteractiveTx.dummyLocalTx(d.txSession))
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED)
  })

  when(WAIT_FOR_DUAL_FUNDING_SIGNED)(handleExceptions {
    case Event(msg, d) => ???
  })

}
