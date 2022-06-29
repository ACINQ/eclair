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

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter.{ClassicActorContextOps, actorRefAdapter}
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.{SatoshiLong, Script, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.Helpers.{Funding, getRelayFees}
import fr.acinq.eclair.channel.InteractiveTxBuilder.{FullySignedSharedTransaction, InteractiveTxParams, PartiallySignedSharedTransaction}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel._
import fr.acinq.eclair.channel.publish.TxPublisher.SetChannelId
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Features, RealShortChannelId, ToMilliSatoshiConversion}

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

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
                                         |                                 |
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
      Helpers.validateParamsDualFundedNonInitiator(nodeParams, d.init.channelType, open, remoteNodeId, localParams.initFeatures, remoteInit.features) match {
        case Left(t) => handleLocalError(t, d, Some(open))
        case Right((channelFeatures, remoteShutdownScript)) =>
          context.system.eventStream.publish(ChannelCreated(self, peer, remoteNodeId, isInitiator = false, open.temporaryChannelId, open.commitmentFeerate, Some(open.fundingFeerate)))
          val localFundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
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
            fundingPubkey = localFundingPubkey,
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
          val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey, remoteParams.fundingPubKey)))
          val fundingParams = InteractiveTxParams(channelId, localParams.isInitiator, accept.fundingAmount, open.fundingAmount, fundingPubkeyScript, open.lockTime, open.dustLimit.max(accept.dustLimit), open.fundingFeerate)
          val txBuilder = context.spawnAnonymous(InteractiveTxBuilder(
            remoteNodeId, fundingParams, keyManager,
            localParams, remoteParams,
            open.commitmentFeerate,
            open.firstPerCommitmentPoint,
            open.channelFlags, d.init.channelConfig, channelFeatures,
            wallet
          ))
          txBuilder ! InteractiveTxBuilder.Start(self, Nil)
          goto(WAIT_FOR_DUAL_FUNDING_CREATED) using DATA_WAIT_FOR_DUAL_FUNDING_CREATED(channelId, txBuilder, None) sending accept
      }

    case Event(c: CloseCommand, d) => handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL)(handleExceptions {
    case Event(accept: AcceptDualFundedChannel, d: DATA_WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL) =>
      import d.init.{localParams, remoteInit}
      Helpers.validateParamsDualFundedInitiator(nodeParams, d.init.channelType, localParams.initFeatures, remoteInit.features, d.lastSent, accept) match {
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
          val txBuilder = context.spawnAnonymous(InteractiveTxBuilder(
            remoteNodeId, fundingParams, keyManager,
            localParams, remoteParams,
            d.lastSent.commitmentFeerate,
            accept.firstPerCommitmentPoint,
            d.lastSent.channelFlags, d.init.channelConfig, channelFeatures,
            wallet
          ))
          txBuilder ! InteractiveTxBuilder.Start(self, Nil)
          goto(WAIT_FOR_DUAL_FUNDING_CREATED) using DATA_WAIT_FOR_DUAL_FUNDING_CREATED(channelId, txBuilder, None)
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

  when(WAIT_FOR_DUAL_FUNDING_CREATED)(handleExceptions {
    case Event(msg: InteractiveTxMessage, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      msg match {
        case msg: InteractiveTxConstructionMessage =>
          d.txBuilder ! InteractiveTxBuilder.ReceiveTxMessage(msg)
          stay()
        case msg: TxSignatures =>
          d.txBuilder ! InteractiveTxBuilder.ReceiveTxSigs(msg)
          stay()
        case msg: TxAbort =>
          log.info("our peer aborted the dual funding flow: ascii='{}' bin={}", msg.toAscii, msg.data)
          d.txBuilder ! InteractiveTxBuilder.Abort
          channelOpenReplyToUser(Left(LocalError(DualFundingAborted(d.channelId))))
          goto(CLOSED)
        case _: TxInitRbf =>
          log.info("ignoring unexpected tx_init_rbf message")
          stay() sending Warning(d.channelId, InvalidRbfAttempt(d.channelId).getMessage)
        case _: TxAckRbf =>
          log.info("ignoring unexpected tx_ack_rbf message")
          stay() sending Warning(d.channelId, InvalidRbfAttempt(d.channelId).getMessage)
      }

    case Event(commitSig: CommitSig, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      d.txBuilder ! InteractiveTxBuilder.ReceiveCommitSig(commitSig)
      stay()

    case Event(channelReady: ChannelReady, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      log.info("received their channel_ready, deferring message")
      stay() using d.copy(deferred = Some(channelReady))

    case Event(msg: InteractiveTxBuilder.Response, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) => msg match {
      case InteractiveTxBuilder.SendMessage(msg) => stay() sending msg
      case InteractiveTxBuilder.Succeeded(fundingParams, fundingTx, commitments) =>
        d.deferred.foreach(self ! _)
        Funding.minDepthDualFunding(nodeParams.channelConf, commitments.channelFeatures, fundingParams) match {
          case Some(fundingMinDepth) =>
            blockchain ! WatchFundingConfirmed(self, commitments.commitInput.outPoint.txid, fundingMinDepth)
            val nextData = DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED(commitments, fundingTx, fundingParams, Nil, nodeParams.currentBlockHeight, nodeParams.currentBlockHeight, None, None)
            fundingTx match {
              case fundingTx: PartiallySignedSharedTransaction => goto(WAIT_FOR_DUAL_FUNDING_CONFIRMED) using nextData storing() sending fundingTx.localSigs
              case fundingTx: FullySignedSharedTransaction => goto(WAIT_FOR_DUAL_FUNDING_CONFIRMED) using nextData storing() sending fundingTx.localSigs calling publishFundingTx(fundingParams, fundingTx)
            }
          case None =>
            val commitTxs = Set(commitments.localCommit.commitTxAndRemoteSig.commitTx.tx.txid, commitments.remoteCommit.txid)
            blockchain ! WatchFundingSpent(self, commitments.commitInput.outPoint.txid, commitments.commitInput.outPoint.index.toInt, commitTxs)
            val (shortIds, channelReady) = acceptFundingTx(commitments, RealScidStatus.Unknown)
            val nextData = DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments, shortIds, Nil, channelReady)
            fundingTx match {
              case fundingTx: PartiallySignedSharedTransaction => goto(WAIT_FOR_DUAL_FUNDING_READY) using nextData storing() sending Seq(fundingTx.localSigs, channelReady)
              case fundingTx: FullySignedSharedTransaction => goto(WAIT_FOR_DUAL_FUNDING_READY) using nextData storing() sending Seq(fundingTx.localSigs, channelReady) calling publishFundingTx(fundingParams, fundingTx)
            }
        }
      case f: InteractiveTxBuilder.Failed =>
        channelOpenReplyToUser(Left(LocalError(f.cause)))
        goto(CLOSED) sending TxAbort(d.channelId, f.cause.getMessage)
    }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      d.txBuilder ! InteractiveTxBuilder.Abort
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.channelId)))
      handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      d.txBuilder ! InteractiveTxBuilder.Abort
      channelOpenReplyToUser(Left(RemoteError(e)))
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      d.txBuilder ! InteractiveTxBuilder.Abort
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, d: DATA_WAIT_FOR_DUAL_FUNDING_CREATED) =>
      d.txBuilder ! InteractiveTxBuilder.Abort
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED)
  })

  when(WAIT_FOR_DUAL_FUNDING_CONFIRMED)(handleExceptions {
    case Event(txSigs: TxSignatures, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      d.fundingTx match {
        case fundingTx: PartiallySignedSharedTransaction => InteractiveTxBuilder.addRemoteSigs(d.fundingParams, fundingTx, txSigs) match {
          case Left(cause) =>
            val unsignedFundingTx = fundingTx.tx.buildUnsignedTx()
            log.warning("received invalid tx_signatures for txid={} (current funding txid={}): {}", txSigs.txId, unsignedFundingTx.txid, cause.getMessage)
            // The funding transaction may still confirm (since our peer should be able to generate valid signatures),
            // so we cannot close the channel yet.
            stay() sending Error(d.channelId, InvalidFundingSignature(d.channelId, Some(unsignedFundingTx)).getMessage)
          case Right(fundingTx) =>
            log.info("publishing funding tx for channelId={} fundingTxId={}", d.channelId, fundingTx.signedTx.txid)
            val nextData = d.copy(fundingTx = fundingTx)
            stay() using nextData storing() calling publishFundingTx(d.fundingParams, fundingTx)
        }
        case _: FullySignedSharedTransaction =>
          log.warning("received duplicate tx_signatures")
          stay()
      }

    case Event(_: TxInitRbf, _: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      log.info("rbf not supported yet")
      stay()

    case Event(_: TxAckRbf, _: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      log.info("rbf not supported yet")
      stay()

    case Event(msg: InteractiveTxConstructionMessage, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      stay() sending Warning(d.channelId, UnexpectedInteractiveTxMessage(d.channelId, msg).getMessage)

    case Event(msg: TxAbort, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      stay() sending Warning(d.channelId, UnexpectedInteractiveTxMessage(d.channelId, msg).getMessage)

    case Event(WatchFundingConfirmedTriggered(blockHeight, txIndex, confirmedTx), d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      // We find which funding transaction got confirmed.
      val allFundingTxs = DualFundingTx(d.fundingTx, d.commitments) +: d.previousFundingTxs
      allFundingTxs.find(_.commitments.commitInput.outPoint.txid == confirmedTx.txid) match {
        case Some(DualFundingTx(_, commitments)) =>
          Try(Transaction.correctlySpends(commitments.fullySignedLocalCommitTx(keyManager).tx, Seq(confirmedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)) match {
            case Success(_) =>
              log.info(s"channelId=${commitments.channelId} was confirmed at blockHeight=$blockHeight txIndex=$txIndex with funding txid=${commitments.commitInput.outPoint.txid}")
              val commitTxs = Set(commitments.localCommit.commitTxAndRemoteSig.commitTx.tx.txid, commitments.remoteCommit.txid)
              blockchain ! WatchFundingSpent(self, commitments.commitInput.outPoint.txid, commitments.commitInput.outPoint.index.toInt, commitTxs)
              context.system.eventStream.publish(TransactionConfirmed(commitments.channelId, remoteNodeId, confirmedTx))
              val realScidStatus = RealScidStatus.Temporary(RealShortChannelId(blockHeight, txIndex, commitments.commitInput.outPoint.index.toInt))
              val (shortIds, channelReady) = acceptFundingTx(commitments, realScidStatus = realScidStatus)
              d.deferred.foreach(self ! _)
              val otherFundingTxs = allFundingTxs.filter(_.commitments.commitInput.outPoint.txid != confirmedTx.txid)
              goto(WAIT_FOR_DUAL_FUNDING_READY) using DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments, shortIds, otherFundingTxs, channelReady) storing() sending channelReady
            case Failure(t) =>
              log.error(t, s"rejecting channel with invalid funding tx: ${confirmedTx.bin}")
              allFundingTxs.foreach(f => wallet.rollback(f.fundingTx.tx.buildUnsignedTx()))
              goto(CLOSED)
          }
        case None =>
          log.error(s"rejecting channel with invalid funding tx that doesn't match any of our funding txs: ${confirmedTx.bin}")
          allFundingTxs.foreach(f => wallet.rollback(f.fundingTx.tx.buildUnsignedTx()))
          goto(CLOSED)
      }

    case Event(ProcessCurrentBlockHeight(c), d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) => handleNewBlockDualFundingUnconfirmed(c, d)

    case Event(e: BITCOIN_FUNDING_DOUBLE_SPENT, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) => handleDualFundingDoubleSpent(e, d)

    case Event(remoteChannelReady: ChannelReady, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      // We can skip waiting for confirmations if:
      //  - there is a single version of the funding tx (otherwise we don't know which one to use)
      //  - they didn't contribute to the funding output or we trust them to not double-spend
      val canUseZeroConf = remoteChannelReady.alias_opt.isDefined &&
        d.previousFundingTxs.isEmpty &&
        (d.fundingParams.remoteAmount == 0.sat || d.commitments.localParams.initFeatures.hasFeature(Features.ZeroConf))
      if (canUseZeroConf) {
        log.info("this chanel isn't zero-conf, but they sent an early channel_ready with an alias: no need to wait for confirmations")
        val commitTxs = Set(d.commitments.localCommit.commitTxAndRemoteSig.commitTx.tx.txid, d.commitments.remoteCommit.txid)
        blockchain ! WatchFundingSpent(self, d.commitments.commitInput.outPoint.txid, d.commitments.commitInput.outPoint.index.toInt, commitTxs)
        val (shortIds, localChannelReady) = acceptFundingTx(d.commitments, RealScidStatus.Unknown)
        self ! remoteChannelReady
        // NB: we will receive a WatchFundingConfirmedTriggered later that will simply be ignored
        goto(WAIT_FOR_DUAL_FUNDING_READY) using DATA_WAIT_FOR_DUAL_FUNDING_READY(d.commitments, shortIds, Nil, localChannelReady) storing() sending localChannelReady
      } else {
        log.info("received their channel_ready, deferring message")
        stay() using d.copy(deferred = Some(remoteChannelReady)) // no need to store, they will re-send if we get disconnected
      }

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) if d.commitments.announceChannel =>
      log.debug("received remote announcement signatures, delaying")
      // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
      // note: no need to persist their message, in case of disconnection they will resend it
      context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
      stay()

    case Event(c: CMD_FORCECLOSE, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      // We can't easily force-close until we know which funding transaction confirms.
      // A better option would be to double-spend the funding transaction(s).
      log.warning("cannot force-close while dual-funded transactions are unconfirmed")
      val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
      replyTo ! RES_FAILURE(c, CommandUnavailableInThisState(d.channelId, "force-close", stateName))
      stay()

    case Event(e: Error, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) => handleRemoteError(e, d)
  })

  when(WAIT_FOR_DUAL_FUNDING_READY)(handleExceptions {
    case Event(channelReady: ChannelReady, d: DATA_WAIT_FOR_DUAL_FUNDING_READY) =>
      val shortIds1 = d.shortIds.copy(remoteAlias_opt = channelReady.alias_opt)
      shortIds1.remoteAlias_opt.foreach { remoteAlias =>
        log.info("received remoteAlias={}", remoteAlias)
        context.system.eventStream.publish(ShortChannelIdAssigned(self, d.commitments.channelId, shortIds = shortIds1, remoteNodeId = remoteNodeId))
      }
      log.info("shortIds: real={} localAlias={} remoteAlias={}", shortIds1.real.toOption.getOrElse("none"), shortIds1.localAlias, shortIds1.remoteAlias_opt.getOrElse("none"))
      // we create a channel_update early so that we can use it to send payments through this channel, but it won't be propagated to other nodes since the channel is not yet announced
      val scidForChannelUpdate = Helpers.scidForChannelUpdate(channelAnnouncement_opt = None, shortIds1.localAlias)
      log.info("using shortChannelId={} for initial channel_update", scidForChannelUpdate)
      val fees = getRelayFees(nodeParams, remoteNodeId, d.commitments)
      val initialChannelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, scidForChannelUpdate, nodeParams.channelConf.expiryDelta, d.commitments.remoteParams.htlcMinimum, fees.feeBase, fees.feeProportionalMillionths, d.commitments.capacity.toMilliSatoshi, enable = Helpers.aboveReserve(d.commitments))
      // we need to periodically re-send channel updates, otherwise channel will be considered stale and get pruned by network
      context.system.scheduler.scheduleWithFixedDelay(initialDelay = REFRESH_CHANNEL_UPDATE_INTERVAL, delay = REFRESH_CHANNEL_UPDATE_INTERVAL, receiver = self, message = BroadcastChannelUpdate(PeriodicRefresh))
      // used to get the final shortChannelId, used in announcements (if minDepth >= ANNOUNCEMENTS_MINCONF this event will fire instantly)
      blockchain ! WatchFundingDeeplyBuried(self, d.commitments.commitInput.outPoint.txid, ANNOUNCEMENTS_MINCONF)
      goto(NORMAL) using DATA_NORMAL(d.commitments.copy(remoteNextCommitInfo = Right(channelReady.nextPerCommitmentPoint)), shortIds1, None, initialChannelUpdate, None, None, None) storing()

    case Event(_: TxInitRbf, d: DATA_WAIT_FOR_DUAL_FUNDING_READY) =>
      // Our peer may not have received the funding transaction confirmation.
      stay() sending TxAbort(d.channelId, InvalidRbfTxConfirmed(d.channelId).getMessage)

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_DUAL_FUNDING_READY) if d.commitments.announceChannel =>
      log.debug("received remote announcement signatures, delaying")
      // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
      // note: no need to persist their message, in case of disconnection they will resend it
      context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
      stay()

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_DUAL_FUNDING_READY) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_DUAL_FUNDING_READY) => handleInformationLeak(tx, d)

    case Event(e: Error, d: DATA_WAIT_FOR_DUAL_FUNDING_READY) => handleRemoteError(e, d)
  })

}
