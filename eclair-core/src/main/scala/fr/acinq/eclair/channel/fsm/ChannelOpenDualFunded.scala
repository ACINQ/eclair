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
import akka.actor.{ActorRef, Status}
import akka.pattern.pipe
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.{SatoshiLong, Script, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.Helpers.{Funding, getRelayFees}
import fr.acinq.eclair.channel.InteractiveTx.{FullySignedSharedTransaction, InteractiveTxParams, PartiallySignedSharedTransaction}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel._
import fr.acinq.eclair.channel.publish.TxPublisher.SetChannelId
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions.TxOwner
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Features, MilliSatoshiLong, ShortChannelId, ToMilliSatoshiConversion, randomKey}

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
      goto(WAIT_FOR_DUAL_FUNDING_CREATED) using nextData sending msg_opt

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
                        require(fundingTx.txOut(fundingOutputIndex).publicKeyScript == localCommitTx.input.txOut.publicKeyScript, "pubkey script mismatch!")
                        val localSigOfRemoteTx = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(d.localParams.fundingKeyPath), TxOwner.Remote, d.channelFeatures.commitmentFormat)
                        val commitSig = CommitSig(d.channelId, localSigOfRemoteTx, Nil)
                        val nextData = DATA_WAIT_FOR_DUAL_FUNDING_SIGNED(d.channelId, d.localParams, d.remoteParams, d.fundingParams, completeTx, fundingOutputIndex, d.commitTxFeerate, d.remoteFirstPerCommitmentPoint, d.channelFlags, d.channelConfig, d.channelFeatures, signingInProgress = false, None, None)
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
    case Event(commit: CommitSig, d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED) =>
      Funding.makeFirstCommitTxs(keyManager, d.channelConfig, d.channelFeatures, d.channelId, d.localParams, d.remoteParams, d.fundingParams.localAmount, d.fundingParams.remoteAmount, 0 msat, d.commitTxFeerate, d.fundingTx.hash, d.fundingOutputIndex, d.remoteFirstPerCommitmentPoint) match {
        case Left(cause) => handleInteractiveTxError(d.fundingTx, cause, d, Some(commit))
        case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx)) =>
          val fundingPubKey = keyManager.fundingPublicKey(d.localParams.fundingKeyPath)
          val localSigOfLocalTx = keyManager.sign(localCommitTx, fundingPubKey, TxOwner.Local, d.channelFeatures.commitmentFormat)
          val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, d.remoteParams.fundingPubKey, localSigOfLocalTx, commit.signature)
          Transactions.checkSpendable(signedLocalCommitTx) match {
            case Failure(_) => handleInteractiveTxError(d.fundingTx, InvalidCommitmentSignature(d.channelId, signedLocalCommitTx.tx), d, Some(commit))
            case Success(_) =>
              val commitments = Commitments(
                d.channelId, d.channelConfig, d.channelFeatures,
                d.localParams, d.remoteParams, d.channelFlags,
                LocalCommit(0, localSpec, CommitTxAndRemoteSig(localCommitTx, commit.signature), htlcTxsAndRemoteSigs = Nil),
                RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, d.remoteFirstPerCommitmentPoint),
                LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
                localNextHtlcId = 0L, remoteNextHtlcId = 0L,
                originChannels = Map.empty,
                remoteNextCommitInfo = Right(randomKey().publicKey), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array,
                localCommitTx.input,
                ShaChain.init)
              context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
              // The peer with the lowest total of input amount must transmit its `tx_signatures` first.
              if (d.fundingParams.localAmount <= d.fundingParams.remoteAmount) {
                InteractiveTx.signTx(d.channelId, d.sharedTx, wallet).pipeTo(self)
                stay() using d.copy(commitments_opt = Some(commitments), signingInProgress = true)
              } else {
                stay() using d.copy(commitments_opt = Some(commitments))
              }
          }
      }

    case Event(txSigs: TxSignatures, d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED) =>
      d.commitments_opt match {
        case Some(_) if d.signingInProgress =>
          stay() using d.copy(remoteSigs_opt = Some(txSigs))
        case Some(_) =>
          InteractiveTx.signTx(d.channelId, d.sharedTx, wallet).pipeTo(self)
          stay() using d.copy(remoteSigs_opt = Some(txSigs), signingInProgress = true)
        case None =>
          log.error("received funding tx signatures before commitment signatures, aborting")
          handleInteractiveTxError(d.fundingTx, ChannelFundingError(d.channelId), d, Some(txSigs))
      }

    case Event(partiallySignedTx: PartiallySignedSharedTransaction, d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED) =>
      val fundingMinDepth = Helpers.minDepthForFunding(nodeParams.channelConf, d.fundingParams.fundingAmount)
      d.commitments_opt match {
        case Some(commitments) => d.remoteSigs_opt match {
          case Some(remoteSigs) => InteractiveTx.addRemoteSigs(d.fundingParams, partiallySignedTx, remoteSigs) match {
            case Left(cause) => handleInteractiveTxError(d.fundingTx, cause, d, Some(remoteSigs))
            case Right(signedTx) =>
              log.info("publishing funding tx for channelId={} fundingTxId={}", d.channelId, d.fundingTx.txid)
              blockchain ! WatchFundingConfirmed(self, d.fundingTx.txid, fundingMinDepth)
              // NB: we publish the funding tx only *after* the channel state has been written to disk because we want
              // to make sure we first persist the commitment that returns back the funds to us in case of problem
              val nextData = DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED(commitments, signedTx, d.fundingParams, Nil, nodeParams.currentBlockHeight, nodeParams.currentBlockHeight, None)
              goto(WAIT_FOR_DUAL_FUNDING_CONFIRMED) using nextData storing() sending partiallySignedTx.localSigs calling publishFundingTx(nextData)
          }
          case None =>
            // We send our `tx_signatures` first, we must remember the channel even though we don't have the fully
            // signed funding transaction yet. Our peer may publish the funding transaction without explicitly sending
            // us their signatures.
            blockchain ! WatchFundingConfirmed(self, d.fundingTx.txid, fundingMinDepth)
            val nextData = DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED(commitments, partiallySignedTx, d.fundingParams, Nil, nodeParams.currentBlockHeight, nodeParams.currentBlockHeight, None)
            goto(WAIT_FOR_DUAL_FUNDING_CONFIRMED) using nextData storing() sending partiallySignedTx.localSigs
        }
        case None =>
          log.error("funding tx signed before commitment was signed, aborting")
          handleInteractiveTxError(d.fundingTx, ChannelFundingError(d.channelId), d, None)
      }

    case Event(f: Status.Failure, d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED) =>
      log.error(f.cause, "could not sign dual-funded transaction: ")
      handleInteractiveTxError(d.fundingTx, ChannelFundingError(d.channelId), d, None)

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED) =>
      wallet.rollback(d.fundingTx)
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.channelId)))
      handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED) =>
      wallet.rollback(d.fundingTx)
      channelOpenReplyToUser(Left(RemoteError(e)))
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED) =>
      wallet.rollback(d.fundingTx)
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED) =>
      wallet.rollback(d.fundingTx)
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED)
  })

  when(WAIT_FOR_DUAL_FUNDING_CONFIRMED)(handleExceptions {
    case Event(txSigs: TxSignatures, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      d.fundingTx match {
        case fundingTx: PartiallySignedSharedTransaction => InteractiveTx.addRemoteSigs(d.fundingParams, fundingTx, txSigs) match {
          case Left(cause) =>
            log.warning("received invalid tx_signatures: {}", cause.getMessage)
            // The funding transaction may still confirm, so we cannot close the channel yet.
            stay() sending Error(d.channelId, InvalidFundingSignature(d.channelId, Some(d.fundingTx.tx.buildUnsignedTx())).getMessage)
          case Right(fundingTx) =>
            log.info("publishing funding tx for channelId={} fundingTxId={}", d.channelId, fundingTx.signedTx.txid)
            val nextData = d.copy(fundingTx = fundingTx)
            stay() using nextData storing() calling publishFundingTx(nextData)
        }
        case _: FullySignedSharedTransaction =>
          log.info("received duplicate tx_signatures")
          stay()
      }

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
              val channelKeyPath = keyManager.keyPath(commitments.localParams, commitments.channelConfig)
              val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
              val fundingLocked = FundingLocked(commitments.channelId, nextPerCommitmentPoint)
              d.deferred.foreach(self ! _)
              val shortChannelId = ShortChannelId(blockHeight, txIndex, commitments.commitInput.outPoint.index.toInt)
              val otherFundingTxs = allFundingTxs.filter(_.commitments.commitInput.outPoint.txid != confirmedTx.txid)
              if (otherFundingTxs.nonEmpty) {
                wallet.rollback(InteractiveTx.dummyLocalTx(otherFundingTxs.map(_.fundingTx.tx)))
              }
              goto(WAIT_FOR_DUAL_FUNDING_LOCKED) using DATA_WAIT_FOR_DUAL_FUNDING_LOCKED(commitments, shortChannelId, otherFundingTxs, fundingLocked) storing() sending fundingLocked
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

    case Event(msg: TxInitRbf, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      log.info("our peer wants to raise the feerate of the funding transaction (target={})", msg.feerate)
      stay() sending TxAbort(d.channelId, "rbf not supported yet")

    case Event(msg: FundingLocked, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      log.info("received their funding_locked, deferring message")
      stay() using d.copy(deferred = Some(msg)) // no need to store, they will re-send if we get disconnected

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) if d.commitments.announceChannel =>
      log.debug("received remote announcement signatures, delaying")
      // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
      // note: no need to persist their message, in case of disconnection they will resend it
      context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
      stay()

    case Event(c: CMD_FORCECLOSE, d) =>
      // We can't easily force-close until we know which funding transaction confirms.
      // A better option would be to double-spend the funding transaction(s).
      log.warning("cannot force-close while dual-funded transactions are unconfirmed")
      val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
      replyTo ! RES_FAILURE(c, CommandUnavailableInThisState(d.channelId, "force-close", stateName))
      stay()

    case Event(e: Error, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) => handleRemoteError(e, d)
  })

  when(WAIT_FOR_DUAL_FUNDING_LOCKED)(handleExceptions {
    case Event(FundingLocked(_, nextPerCommitmentPoint, _), d: DATA_WAIT_FOR_DUAL_FUNDING_LOCKED) =>
      // used to get the final shortChannelId, used in announcements (if minDepth >= ANNOUNCEMENTS_MINCONF this event will fire instantly)
      blockchain ! WatchFundingDeeplyBuried(self, d.commitments.commitInput.outPoint.txid, ANNOUNCEMENTS_MINCONF)
      context.system.eventStream.publish(ShortChannelIdAssigned(self, d.commitments.channelId, d.shortChannelId, None))
      // we create a channel_update early so that we can use it to send payments through this channel, but it won't be propagated to other nodes since the channel is not yet announced
      val fees = getRelayFees(nodeParams, remoteNodeId, d.commitments)
      val initialChannelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, d.shortChannelId, nodeParams.channelConf.expiryDelta, d.commitments.remoteParams.htlcMinimum, fees.feeBase, fees.feeProportionalMillionths, d.commitments.capacity.toMilliSatoshi, enable = Helpers.aboveReserve(d.commitments))
      // we need to periodically re-send channel updates, otherwise channel will be considered stale and get pruned by network
      context.system.scheduler.scheduleWithFixedDelay(initialDelay = REFRESH_CHANNEL_UPDATE_INTERVAL, delay = REFRESH_CHANNEL_UPDATE_INTERVAL, receiver = self, message = BroadcastChannelUpdate(PeriodicRefresh))
      goto(NORMAL) using DATA_NORMAL(d.commitments.copy(remoteNextCommitInfo = Right(nextPerCommitmentPoint)), d.shortChannelId, buried = false, None, initialChannelUpdate, None, None, None) storing()

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_DUAL_FUNDING_LOCKED) if d.commitments.announceChannel =>
      log.debug("received remote announcement signatures, delaying")
      // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
      // note: no need to persist their message, in case of disconnection they will resend it
      context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
      stay()

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_DUAL_FUNDING_LOCKED) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_DUAL_FUNDING_LOCKED) => handleInformationLeak(tx, d)

    case Event(e: Error, d: DATA_WAIT_FOR_DUAL_FUNDING_LOCKED) => handleRemoteError(e, d)
  })

}
