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

import akka.actor.Status
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.pattern.pipe
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.{SatoshiLong, Script, Transaction}
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.Helpers.{Funding, getRelayFees}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel._
import fr.acinq.eclair.channel.publish.TxPublisher.SetChannelId
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions.TxOwner
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.wire.protocol.{AcceptChannel, AnnouncementSignatures, ChannelReady, ChannelReadyTlv, ChannelTlv, Error, FundingCreated, FundingSigned, OpenChannel, TlvStream}
import fr.acinq.eclair.{BlockHeight, Features, ShortChannelId, ToMilliSatoshiConversion, randomKey, toLongId}
import scodec.bits.ByteVector

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

/**
 * Created by t-bast on 28/03/2022.
 */

/**
 * This trait contains the state machine for the single-funder channel funding flow.
 */
trait ChannelOpenSingleFunder extends FundingHandlers with ErrorHandlers {

  this: Channel =>

  /*
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

  when(WAIT_FOR_OPEN_CHANNEL)(handleExceptions {
    case Event(open: OpenChannel, d@DATA_WAIT_FOR_OPEN_CHANNEL(INPUT_INIT_FUNDEE(_, localParams, _, remoteInit, channelConfig, channelType))) =>
      Helpers.validateParamsFundee(nodeParams, channelType, localParams.initFeatures, open, remoteNodeId, remoteInit.features) match {
        case Left(t) => handleLocalError(t, d, Some(open))
        case Right((channelFeatures, remoteShutdownScript)) =>
          context.system.eventStream.publish(ChannelCreated(self, peer, remoteNodeId, isInitiator = false, open.temporaryChannelId, open.feeratePerKw, None))
          val fundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
          val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
          val minimumDepth = Funding.minDepthFundee(nodeParams.channelConf, channelFeatures, open.fundingSatoshis)
          // In order to allow TLV extensions and keep backwards-compatibility, we include an empty upfront_shutdown_script if this feature is not used.
          // See https://github.com/lightningnetwork/lightning-rfc/pull/714.
          val localShutdownScript = if (Features.canUseFeature(localParams.initFeatures, remoteInit.features, Features.UpfrontShutdownScript)) localParams.defaultFinalScriptPubKey else ByteVector.empty
          val accept = AcceptChannel(temporaryChannelId = open.temporaryChannelId,
            dustLimitSatoshis = localParams.dustLimit,
            maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
            channelReserveSatoshis = localParams.requestedChannelReserve_opt.getOrElse(0 sat),
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
            requestedChannelReserve_opt = Some(open.channelReserveSatoshis), // our peer requires us to always have at least that much satoshis in our balance
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

    case Event(e: Error, d: DATA_WAIT_FOR_OPEN_CHANNEL) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_ACCEPT_CHANNEL)(handleExceptions {
    case Event(accept: AcceptChannel, d@DATA_WAIT_FOR_ACCEPT_CHANNEL(INPUT_INIT_FUNDER(temporaryChannelId, fundingSatoshis, pushMsat, commitTxFeerate, fundingTxFeerate, localParams, _, remoteInit, _, channelConfig, channelType), open)) =>
      Helpers.validateParamsFunder(nodeParams, channelType, localParams.initFeatures, remoteInit.features, open, accept) match {
        case Left(t) =>
          channelOpenReplyToUser(Left(LocalError(t)))
          handleLocalError(t, d, Some(accept))
        case Right((channelFeatures, remoteShutdownScript)) =>
          val remoteParams = RemoteParams(
            nodeId = remoteNodeId,
            dustLimit = accept.dustLimitSatoshis,
            maxHtlcValueInFlightMsat = accept.maxHtlcValueInFlightMsat,
            requestedChannelReserve_opt = Some(accept.channelReserveSatoshis), // our peer requires us to always have at least that much satoshis in our balance
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
          log.info("remote indicates they are going to use fundingMinDepth={}", accept.minimumDepth)
          val localFundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
          val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey.publicKey, remoteParams.fundingPubKey)))
          wallet.makeFundingTx(fundingPubkeyScript, fundingSatoshis, fundingTxFeerate).pipeTo(self)
          goto(WAIT_FOR_FUNDING_INTERNAL) using DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, localParams, remoteParams, fundingSatoshis, pushMsat, commitTxFeerate, accept.firstPerCommitmentPoint, channelConfig, channelFeatures, open)
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.lastSent.temporaryChannelId)))
      handleFastClose(c, d.lastSent.temporaryChannelId)

    case Event(e: Error, d: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      channelOpenReplyToUser(Left(RemoteError(e)))
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_INTERNAL)(handleExceptions {
    case Event(MakeFundingTxResponse(fundingTx, fundingTxOutputIndex, fundingTxFee), d@DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, localParams, remoteParams, fundingAmount, pushMsat, commitTxFeerate, remoteFirstPerCommitmentPoint, channelConfig, channelFeatures, open)) =>
      // let's create the first commitment tx that spends the yet uncommitted funding tx
      Funding.makeFirstCommitTxs(keyManager, channelConfig, channelFeatures, temporaryChannelId, localParams, remoteParams, fundingAmount, pushMsat, commitTxFeerate, fundingTx.hash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint) match {
        case Left(ex) => handleLocalError(ex, d, None)
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
      handleLocalError(ChannelFundingError(d.temporaryChannelId), d, None) // we use a generic exception and don't send the internal error to the peer

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.temporaryChannelId)))
      handleFastClose(c, d.temporaryChannelId)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      channelOpenReplyToUser(Left(RemoteError(e)))
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_CREATED)(handleExceptions {
    case Event(FundingCreated(_, fundingTxHash, fundingTxOutputIndex, remoteSig, _), d@DATA_WAIT_FOR_FUNDING_CREATED(temporaryChannelId, localParams, remoteParams, fundingAmount, pushMsat, commitTxFeerate, remoteFirstPerCommitmentPoint, channelFlags, channelConfig, channelFeatures, _)) =>
      // they fund the channel with their funding tx, so the money is theirs (but we are paid pushMsat)
      Funding.makeFirstCommitTxs(keyManager, channelConfig, channelFeatures, temporaryChannelId, localParams, remoteParams, fundingAmount, pushMsat, commitTxFeerate, fundingTxHash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint) match {
        case Left(ex) => handleLocalError(ex, d, None)
        case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx)) =>
          // check remote signature validity
          val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
          val localSigOfLocalTx = keyManager.sign(localCommitTx, fundingPubKey, TxOwner.Local, channelFeatures.commitmentFormat)
          val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
          Transactions.checkSpendable(signedLocalCommitTx) match {
            case Failure(_) => handleLocalError(InvalidCommitmentSignature(temporaryChannelId, signedLocalCommitTx.tx), d, None)
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
              val fundingMinDepth = Funding.minDepthFundee(nodeParams.channelConf, commitments.channelFeatures, fundingAmount)
              blockchain ! WatchFundingConfirmed(self, commitInput.outPoint.txid, fundingMinDepth)
              goto(WAIT_FOR_FUNDING_CONFIRMED) using DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, None, nodeParams.currentBlockHeight, None, Right(fundingSigned)) storing() sending fundingSigned
          }
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_CREATED) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.temporaryChannelId)))
      handleFastClose(c, d.temporaryChannelId)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_CREATED) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_SIGNED)(handleExceptions {
    case Event(msg@FundingSigned(_, remoteSig, _), d@DATA_WAIT_FOR_FUNDING_SIGNED(channelId, localParams, remoteParams, fundingTx, fundingTxFee, localSpec, localCommitTx, remoteCommit, channelFlags, channelConfig, channelFeatures, fundingCreated)) =>
      // we make sure that their sig checks out and that our first commit tx is spendable
      val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
      val localSigOfLocalTx = keyManager.sign(localCommitTx, fundingPubKey, TxOwner.Local, channelFeatures.commitmentFormat)
      val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
      Transactions.checkSpendable(signedLocalCommitTx) match {
        case Failure(cause) =>
          // we rollback the funding tx, it will never be published
          wallet.rollback(fundingTx)
          channelOpenReplyToUser(Left(LocalError(cause)))
          handleLocalError(InvalidCommitmentSignature(channelId, signedLocalCommitTx.tx), d, Some(msg))
        case Success(_) =>
          val commitInput = localCommitTx.input
          val commitments = Commitments(channelId, channelConfig, channelFeatures, localParams, remoteParams, channelFlags,
            LocalCommit(0, localSpec, CommitTxAndRemoteSig(localCommitTx, remoteSig), htlcTxsAndRemoteSigs = Nil), remoteCommit,
            LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
            localNextHtlcId = 0L, remoteNextHtlcId = 0L,
            originChannels = Map.empty,
            remoteNextCommitInfo = Right(randomKey().publicKey), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array
            commitInput, ShaChain.init)
          val blockHeight = nodeParams.currentBlockHeight
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
          log.info(s"publishing funding tx for channelId=$channelId fundingTxid=${commitInput.outPoint.txid}")
          watchFundingTx(commitments)
          val fundingMinDepth = Funding.minDepthFunder(commitments.channelFeatures)
          blockchain ! WatchFundingConfirmed(self, commitInput.outPoint.txid, fundingMinDepth)
          log.info(s"committing txid=${fundingTx.txid}")

          // we will publish the funding tx only after the channel state has been written to disk because we want to
          // make sure we first persist the commitment that returns back the funds to us in case of problem
          def publishFundingTx(): Unit = {
            wallet.commit(fundingTx).onComplete {
              case Success(true) =>
                context.system.eventStream.publish(TransactionPublished(commitments.channelId, remoteNodeId, fundingTx, fundingTxFee, "funding"))
                channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelOpened(channelId)))
              case Success(false) =>
                channelOpenReplyToUser(Left(LocalError(new RuntimeException("couldn't publish funding tx"))))
                self ! BITCOIN_FUNDING_PUBLISH_FAILED // fail-fast: this should be returned only when we are really sure the tx has *not* been published
              case Failure(t) =>
                channelOpenReplyToUser(Left(LocalError(t)))
                log.error(t, s"error while committing funding tx: ") // tx may still have been published, can't fail-fast
            }
          }

          goto(WAIT_FOR_FUNDING_CONFIRMED) using DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, Some(fundingTx), blockHeight, None, Left(fundingCreated)) storing() calling publishFundingTx()
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
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_CONFIRMED)(handleExceptions {
    case Event(msg: ChannelReady, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      log.info(s"received their ChannelReady, deferring message")
      stay() using d.copy(deferred = Some(msg)) // no need to store, they will re-send if we get disconnected

    case Event(WatchFundingConfirmedTriggered(blockHeight, txIndex, fundingTx), d@DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, _, _, deferred, _)) =>
      Try(Transaction.correctlySpends(commitments.fullySignedLocalCommitTx(keyManager).tx, Seq(fundingTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)) match {
        case Success(_) =>
          log.info(s"channelId=${commitments.channelId} was confirmed at blockHeight=$blockHeight txIndex=$txIndex")
          blockchain ! WatchFundingLost(self, commitments.commitInput.outPoint.txid, nodeParams.channelConf.minDepthBlocks)
          if (!d.commitments.localParams.isInitiator) context.system.eventStream.publish(TransactionPublished(commitments.channelId, remoteNodeId, fundingTx, 0 sat, "funding"))
          context.system.eventStream.publish(TransactionConfirmed(commitments.channelId, remoteNodeId, fundingTx))
          val channelKeyPath = keyManager.keyPath(d.commitments.localParams, commitments.channelConfig)
          val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
          deferred.foreach(self ! _)
          // this is the real scid, it might change when the funding tx gets deeply buried (if there was a reorg in the meantime)
          val realShortChannelId_opt = if (blockHeight == BlockHeight(0)) {
            // If we are using zero-conf then the transaction may not have been confirmed yet, that's why the block
            // height is zero. In some cases (e.g. we were down for some time) the tx may actually have confirmed, in
            // that case we will have a real scid even if the channel is zero-conf
            None
          }
          else {
            Some(ShortChannelId(blockHeight, txIndex, commitments.commitInput.outPoint.index.toInt))
          }
          // the alias will use in our channel_update message, the goal is to be able to use our channel
          // as soon as it reaches NORMAL state, and before it is announced on the network
          val localAlias = ShortChannelId.generateLocalAlias()
          // we always send our local alias, even if it isn't explicitly supported, that's an optional TLV anyway
          val channelReady = ChannelReady(
            channelId = commitments.channelId,
            nextPerCommitmentPoint = nextPerCommitmentPoint,
            tlvStream = TlvStream(ChannelReadyTlv.ShortChannelIdTlv(localAlias))
          )

          // we announce our identifiers as early as we can
          context.system.eventStream.publish(ShortChannelIdAssigned(self, commitments.channelId, realShortChannelId_opt = realShortChannelId_opt, localAlias = localAlias, remoteAlias_opt = None, remoteNodeId = remoteNodeId))

          goto(WAIT_FOR_CHANNEL_READY) using DATA_WAIT_FOR_CHANNEL_READY(commitments, realShortChannelId_opt = realShortChannelId_opt, localAlias = localAlias, channelReady) storing() sending channelReady
        case Failure(t) =>
          log.error(t, s"rejecting channel with invalid funding tx: ${fundingTx.bin}")
          goto(CLOSED)
      }

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if d.commitments.announceChannel =>
      log.debug("received remote announcement signatures, delaying")
      // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
      // note: no need to persist their message, in case of disconnection they will resend it
      context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
      stay()

    case Event(getTxResponse: GetTxWithMetaResponse, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if getTxResponse.txid == d.commitments.commitInput.outPoint.txid => handleGetFundingTx(getTxResponse, d.waitingSince, d.fundingTx)

    case Event(BITCOIN_FUNDING_PUBLISH_FAILED, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingPublishFailed(d)

    case Event(ProcessCurrentBlockHeight(c), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => d.fundingTx match {
      case Some(_) => stay() // we are funder, we're still waiting for the funding tx to be confirmed
      case None if c.blockHeight - d.waitingSince > FUNDING_TIMEOUT_FUNDEE =>
        log.warning(s"funding tx hasn't been published in ${c.blockHeight - d.waitingSince} blocks")
        self ! BITCOIN_FUNDING_TIMEOUT
        stay()
      case None => stay() // let's wait longer
    }

    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingTimeout(d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleInformationLeak(tx, d)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleRemoteError(e, d)
  })

  when(WAIT_FOR_CHANNEL_READY)(handleExceptions {
    case Event(channelReady: ChannelReady, d@DATA_WAIT_FOR_CHANNEL_READY(commitments, realShortChannelId_opt, localAlias, _)) =>
      // used to get the final shortChannelId, used in announcements (if minDepth >= ANNOUNCEMENTS_MINCONF this event will fire instantly)
      blockchain ! WatchFundingDeeplyBuried(self, commitments.commitInput.outPoint.txid, ANNOUNCEMENTS_MINCONF)
      val remoteAlias_opt = channelReady.alias_opt
      remoteAlias_opt.foreach { remoteAlias =>
        log.info("received remoteAlias={}", remoteAlias)
        context.system.eventStream.publish(ShortChannelIdAssigned(self, commitments.channelId, realShortChannelId_opt = realShortChannelId_opt, localAlias = localAlias, remoteAlias_opt = Some(remoteAlias), remoteNodeId = remoteNodeId))
      }
      // we create a channel_update early so that we can use it to send payments through this channel, but it won't be propagated to other nodes since the channel is not yet announced
      val scidForChannelUpdate = Helpers.scidForChannelUpdate(commitments.channelFlags, realShortChannelId_opt, remoteAlias_opt)
      log.info("using shortChannelId={} for initial channel_update", scidForChannelUpdate)
      val relayFees = getRelayFees(nodeParams, remoteNodeId, commitments)
      val initialChannelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, scidForChannelUpdate, nodeParams.channelConf.expiryDelta, d.commitments.remoteParams.htlcMinimum, relayFees.feeBase, relayFees.feeProportionalMillionths, commitments.capacity.toMilliSatoshi, enable = Helpers.aboveReserve(d.commitments))
      // we need to periodically re-send channel updates, otherwise channel will be considered stale and get pruned by network
      context.system.scheduler.scheduleWithFixedDelay(initialDelay = REFRESH_CHANNEL_UPDATE_INTERVAL, delay = REFRESH_CHANNEL_UPDATE_INTERVAL, receiver = self, message = BroadcastChannelUpdate(PeriodicRefresh))
      goto(NORMAL) using DATA_NORMAL(commitments.copy(remoteNextCommitInfo = Right(channelReady.nextPerCommitmentPoint)), realShortChannelId_opt = realShortChannelId_opt, buried = false, None, initialChannelUpdate, localAlias = localAlias, remoteAlias_opt = remoteAlias_opt, None, None, None) storing()

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_CHANNEL_READY) if d.commitments.announceChannel =>
      log.debug("received remote announcement signatures, delaying")
      // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
      // note: no need to persist their message, in case of disconnection they will resend it
      context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
      stay()

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_CHANNEL_READY) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_CHANNEL_READY) => handleInformationLeak(tx, d)

    case Event(e: Error, d: DATA_WAIT_FOR_CHANNEL_READY) => handleRemoteError(e, d)
  })

}
