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
import fr.acinq.bitcoin.scalacompat.{SatoshiLong, Script}
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel.LocalFundingStatus.SingleFundedUnconfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel._
import fr.acinq.eclair.channel.publish.TxPublisher.SetChannelId
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.Transactions.TxOwner
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.wire.protocol.{AcceptChannel, AnnouncementSignatures, ChannelReady, ChannelTlv, Error, FundingCreated, FundingSigned, OpenChannel, TlvStream}
import fr.acinq.eclair.{MilliSatoshiLong, RealShortChannelId, UInt64, randomKey, toLongId}
import scodec.bits.ByteVector

import scala.util.{Failure, Success}

/**
 * Created by t-bast on 28/03/2022.
 */

/**
 * This trait contains the state machine for the single-funder channel funding flow.
 */
trait ChannelOpenSingleFunded extends SingleFundingHandlers with ErrorHandlers {

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
         WAIT_FOR_CHANNEL_READY|                                |WAIT_FOR_CHANNEL_READY
                               | channel_ready    channel_ready |
                               |---------------  ---------------|
                               |               \/               |
                               |               /\               |
                               |<--------------  -------------->|
                         NORMAL|                                |NORMAL
 */

  when(WAIT_FOR_INIT_SINGLE_FUNDED_CHANNEL)(handleExceptions {
    case Event(input: INPUT_INIT_CHANNEL_INITIATOR, _) =>
      val fundingPubKey = keyManager.fundingPublicKey(input.localParams.fundingKeyPath).publicKey
      val channelKeyPath = keyManager.keyPath(input.localParams, input.channelConfig)
      // In order to allow TLV extensions and keep backwards-compatibility, we include an empty upfront_shutdown_script if this feature is not used
      // See https://github.com/lightningnetwork/lightning-rfc/pull/714.
      val localShutdownScript = input.localParams.upfrontShutdownScript_opt.getOrElse(ByteVector.empty)
      val open = OpenChannel(
        chainHash = nodeParams.chainHash,
        temporaryChannelId = input.temporaryChannelId,
        fundingSatoshis = input.fundingAmount,
        pushMsat = input.pushAmount_opt.getOrElse(0 msat),
        dustLimitSatoshis = input.localParams.dustLimit,
        maxHtlcValueInFlightMsat = UInt64(input.localParams.maxHtlcValueInFlightMsat.toLong),
        channelReserveSatoshis = input.localParams.requestedChannelReserve_opt.get,
        htlcMinimumMsat = input.localParams.htlcMinimum,
        feeratePerKw = input.commitTxFeerate,
        toSelfDelay = input.localParams.toSelfDelay,
        maxAcceptedHtlcs = input.localParams.maxAcceptedHtlcs,
        fundingPubkey = fundingPubKey,
        revocationBasepoint = keyManager.revocationPoint(channelKeyPath).publicKey,
        paymentBasepoint = input.localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey),
        delayedPaymentBasepoint = keyManager.delayedPaymentPoint(channelKeyPath).publicKey,
        htlcBasepoint = keyManager.htlcPoint(channelKeyPath).publicKey,
        firstPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0),
        channelFlags = input.channelFlags,
        tlvStream = TlvStream(
          ChannelTlv.UpfrontShutdownScriptTlv(localShutdownScript),
          ChannelTlv.ChannelTypeTlv(input.channelType)
        ))
      goto(WAIT_FOR_ACCEPT_CHANNEL) using DATA_WAIT_FOR_ACCEPT_CHANNEL(input, open) sending open
  })

  when(WAIT_FOR_OPEN_CHANNEL)(handleExceptions {
    case Event(open: OpenChannel, d: DATA_WAIT_FOR_OPEN_CHANNEL) =>
      Helpers.validateParamsSingleFundedFundee(nodeParams, d.initFundee.channelType, d.initFundee.localParams.initFeatures, open, remoteNodeId, d.initFundee.remoteInit.features) match {
        case Left(t) => handleLocalError(t, d, Some(open))
        case Right((channelFeatures, remoteShutdownScript)) =>
          context.system.eventStream.publish(ChannelCreated(self, peer, remoteNodeId, isInitiator = false, open.temporaryChannelId, open.feeratePerKw, None))
          val fundingPubkey = keyManager.fundingPublicKey(d.initFundee.localParams.fundingKeyPath).publicKey
          val channelKeyPath = keyManager.keyPath(d.initFundee.localParams, d.initFundee.channelConfig)
          val minimumDepth = Funding.minDepthFundee(nodeParams.channelConf, d.initFundee.localParams.initFeatures, open.fundingSatoshis)
          log.info("will use fundingMinDepth={}", minimumDepth)
          // In order to allow TLV extensions and keep backwards-compatibility, we include an empty upfront_shutdown_script if this feature is not used.
          // See https://github.com/lightningnetwork/lightning-rfc/pull/714.
          val localShutdownScript = d.initFundee.localParams.upfrontShutdownScript_opt.getOrElse(ByteVector.empty)
          val accept = AcceptChannel(temporaryChannelId = open.temporaryChannelId,
            dustLimitSatoshis = d.initFundee.localParams.dustLimit,
            maxHtlcValueInFlightMsat = UInt64(d.initFundee.localParams.maxHtlcValueInFlightMsat.toLong),
            channelReserveSatoshis = d.initFundee.localParams.requestedChannelReserve_opt.get,
            minimumDepth = minimumDepth.getOrElse(0),
            htlcMinimumMsat = d.initFundee.localParams.htlcMinimum,
            toSelfDelay = d.initFundee.localParams.toSelfDelay,
            maxAcceptedHtlcs = d.initFundee.localParams.maxAcceptedHtlcs,
            fundingPubkey = fundingPubkey,
            revocationBasepoint = keyManager.revocationPoint(channelKeyPath).publicKey,
            paymentBasepoint = d.initFundee.localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey),
            delayedPaymentBasepoint = keyManager.delayedPaymentPoint(channelKeyPath).publicKey,
            htlcBasepoint = keyManager.htlcPoint(channelKeyPath).publicKey,
            firstPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0),
            tlvStream = TlvStream(
              ChannelTlv.UpfrontShutdownScriptTlv(localShutdownScript),
              ChannelTlv.ChannelTypeTlv(d.initFundee.channelType)
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
            initFeatures = d.initFundee.remoteInit.features,
            upfrontShutdownScript_opt = remoteShutdownScript)
          log.debug("remote params: {}", remoteParams)
          val params = ChannelParams(d.initFundee.temporaryChannelId, d.initFundee.channelConfig, channelFeatures, d.initFundee.localParams, remoteParams, open.channelFlags)
          goto(WAIT_FOR_FUNDING_CREATED) using DATA_WAIT_FOR_FUNDING_CREATED(params, open.fundingSatoshis, open.pushMsat, open.feeratePerKw, open.firstPerCommitmentPoint) sending accept
      }

    case Event(c: CloseCommand, d) => handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_OPEN_CHANNEL) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_ACCEPT_CHANNEL)(handleExceptions {
    case Event(accept: AcceptChannel, d@DATA_WAIT_FOR_ACCEPT_CHANNEL(init, open)) =>
      Helpers.validateParamsSingleFundedFunder(nodeParams, init.channelType, init.localParams.initFeatures, init.remoteInit.features, open, accept) match {
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
            initFeatures = init.remoteInit.features,
            upfrontShutdownScript_opt = remoteShutdownScript)
          log.debug("remote params: {}", remoteParams)
          log.info("remote will use fundingMinDepth={}", accept.minimumDepth)
          val localFundingPubkey = keyManager.fundingPublicKey(init.localParams.fundingKeyPath)
          val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey.publicKey, remoteParams.fundingPubKey)))
          wallet.makeFundingTx(fundingPubkeyScript, init.fundingAmount, init.fundingTxFeerate).pipeTo(self)
          val params = ChannelParams(init.temporaryChannelId, init.channelConfig, channelFeatures, init.localParams, remoteParams, open.channelFlags)
          goto(WAIT_FOR_FUNDING_INTERNAL) using DATA_WAIT_FOR_FUNDING_INTERNAL(params, init.fundingAmount, init.pushAmount_opt.getOrElse(0 msat), init.commitTxFeerate, accept.firstPerCommitmentPoint)
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
    case Event(MakeFundingTxResponse(fundingTx, fundingTxOutputIndex, fundingTxFee), d@DATA_WAIT_FOR_FUNDING_INTERNAL(params, fundingAmount, pushMsat, commitTxFeerate, remoteFirstPerCommitmentPoint)) =>
      val temporaryChannelId = params.channelId
      // let's create the first commitment tx that spends the yet uncommitted funding tx
      Funding.makeFirstCommitTxs(keyManager, params, localFundingAmount = fundingAmount, remoteFundingAmount = 0 sat, localPushAmount = pushMsat, remotePushAmount = 0 msat, commitTxFeerate, fundingTx.hash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint) match {
        case Left(ex) => handleLocalError(ex, d, None)
        case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx)) =>
          require(fundingTx.txOut(fundingTxOutputIndex).publicKeyScript == localCommitTx.input.txOut.publicKeyScript, s"pubkey script mismatch!")
          val localSigOfRemoteTx = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(params.localParams.fundingKeyPath), TxOwner.Remote, params.channelFeatures.commitmentFormat)
          // signature of their initial commitment tx that pays remote pushMsat
          val fundingCreated = FundingCreated(
            temporaryChannelId = temporaryChannelId,
            fundingTxid = fundingTx.hash,
            fundingOutputIndex = fundingTxOutputIndex,
            signature = localSigOfRemoteTx
          )
          val channelId = toLongId(fundingTx.hash, fundingTxOutputIndex)
          val params1 = params.copy(channelId = channelId)
          peer ! ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
          txPublisher ! SetChannelId(remoteNodeId, channelId)
          context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId))
          // NB: we don't send a ChannelSignatureSent for the first commit
          goto(WAIT_FOR_FUNDING_SIGNED) using DATA_WAIT_FOR_FUNDING_SIGNED(params1, fundingTx, fundingTxFee, localSpec, localCommitTx, RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint), fundingCreated) sending fundingCreated
      }

    case Event(Status.Failure(t), d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      log.error(t, s"wallet returned error: ")
      channelOpenReplyToUser(Left(LocalError(t)))
      handleLocalError(ChannelFundingError(d.channelId), d, None) // we use a generic exception and don't send the internal error to the peer

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.channelId)))
      handleFastClose(c, d.channelId)

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
    case Event(FundingCreated(_, fundingTxHash, fundingTxOutputIndex, remoteSig, _), d@DATA_WAIT_FOR_FUNDING_CREATED(params, fundingAmount, pushMsat, commitTxFeerate, remoteFirstPerCommitmentPoint)) =>
      val temporaryChannelId = params.channelId
      // they fund the channel with their funding tx, so the money is theirs (but we are paid pushMsat)
      Funding.makeFirstCommitTxs(keyManager, params, localFundingAmount = 0 sat, remoteFundingAmount = fundingAmount, localPushAmount = 0 msat, remotePushAmount = pushMsat, commitTxFeerate, fundingTxHash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint) match {
        case Left(ex) => handleLocalError(ex, d, None)
        case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx)) =>
          // check remote signature validity
          val fundingPubKey = keyManager.fundingPublicKey(params.localParams.fundingKeyPath)
          val localSigOfLocalTx = keyManager.sign(localCommitTx, fundingPubKey, TxOwner.Local, params.channelFeatures.commitmentFormat)
          val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, params.remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
          Transactions.checkSpendable(signedLocalCommitTx) match {
            case Failure(_) => handleLocalError(InvalidCommitmentSignature(temporaryChannelId, signedLocalCommitTx.tx.txid), d, None)
            case Success(_) =>
              val localSigOfRemoteTx = keyManager.sign(remoteCommitTx, fundingPubKey, TxOwner.Remote, params.channelFeatures.commitmentFormat)
              val channelId = toLongId(fundingTxHash, fundingTxOutputIndex)
              val fundingSigned = FundingSigned(
                channelId = channelId,
                signature = localSigOfRemoteTx
              )
              val commitment = Commitment(
                localFundingStatus = SingleFundedUnconfirmedFundingTx(None),
                remoteFundingStatus = RemoteFundingStatus.NotLocked,
                localCommit = LocalCommit(0, localSpec, CommitTxAndRemoteSig(localCommitTx, remoteSig), htlcTxsAndRemoteSigs = Nil),
                remoteCommit = RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint),
                nextRemoteCommit_opt = None)
              val commitments = Commitments(
                params = params.copy(channelId = channelId),
                changes = CommitmentChanges.init(),
                active = List(commitment),
                remoteNextCommitInfo = Right(randomKey().publicKey), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array
                remotePerCommitmentSecrets = ShaChain.init,
                originChannels = Map.empty)
              peer ! ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
              txPublisher ! SetChannelId(remoteNodeId, channelId)
              context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId))
              context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
              // NB: we don't send a ChannelSignatureSent for the first commit
              log.info(s"waiting for them to publish the funding tx for channelId=$channelId fundingTxid=${commitment.fundingTxId}")
              watchFundingConfirmed(commitment.fundingTxId, Funding.minDepthFundee(nodeParams.channelConf, params.localParams.initFeatures, fundingAmount))
              goto(WAIT_FOR_FUNDING_CONFIRMED) using DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, nodeParams.currentBlockHeight, None, Right(fundingSigned)) storing() sending fundingSigned
          }
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_CREATED) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.channelId)))
      handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_CREATED) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_SIGNED)(handleExceptions {
    case Event(msg@FundingSigned(_, remoteSig, _), d@DATA_WAIT_FOR_FUNDING_SIGNED(params, fundingTx, fundingTxFee, localSpec, localCommitTx, remoteCommit, fundingCreated)) =>
      // we make sure that their sig checks out and that our first commit tx is spendable
      val fundingPubKey = keyManager.fundingPublicKey(params.localParams.fundingKeyPath)
      val localSigOfLocalTx = keyManager.sign(localCommitTx, fundingPubKey, TxOwner.Local, params.channelFeatures.commitmentFormat)
      val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, params.remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
      Transactions.checkSpendable(signedLocalCommitTx) match {
        case Failure(cause) =>
          // we rollback the funding tx, it will never be published
          wallet.rollback(fundingTx)
          channelOpenReplyToUser(Left(LocalError(cause)))
          handleLocalError(InvalidCommitmentSignature(d.channelId, signedLocalCommitTx.tx.txid), d, Some(msg))
        case Success(_) =>
          val commitment = Commitment(
            localFundingStatus = SingleFundedUnconfirmedFundingTx(Some(fundingTx)),
            remoteFundingStatus = RemoteFundingStatus.NotLocked,
            localCommit = LocalCommit(0, localSpec, CommitTxAndRemoteSig(localCommitTx, remoteSig), htlcTxsAndRemoteSigs = Nil),
            remoteCommit = remoteCommit,
            nextRemoteCommit_opt = None
          )
          val commitments = Commitments(
            params = params,
            changes = CommitmentChanges.init(),
            active = List(commitment),
            remoteNextCommitInfo = Right(randomKey().publicKey), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array
            remotePerCommitmentSecrets = ShaChain.init,
            originChannels = Map.empty)
          val blockHeight = nodeParams.currentBlockHeight
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
          log.info(s"publishing funding tx fundingTxid=${commitment.fundingTxId}")
          watchFundingConfirmed(commitment.fundingTxId, Funding.minDepthFunder(params.localParams.initFeatures))
          // we will publish the funding tx only after the channel state has been written to disk because we want to
          // make sure we first persist the commitment that returns back the funds to us in case of problem
          goto(WAIT_FOR_FUNDING_CONFIRMED) using DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, blockHeight, None, Left(fundingCreated)) storing() calling publishFundingTx(d.channelId, fundingTx, fundingTxFee)
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
    case Event(remoteChannelReady: ChannelReady, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      if (remoteChannelReady.alias_opt.isDefined && d.commitments.params.localParams.isInitiator) {
        log.info("this channel isn't zero-conf, but we are funder and they sent an early channel_ready with an alias: no need to wait for confirmations")
        // NB: we will receive a WatchFundingConfirmedTriggered later that will simply be ignored
        blockchain ! WatchPublished(self, d.commitments.latest.fundingTxId)
      }
      log.debug("received their channel_ready, deferring message")
      stay() using d.copy(deferred = Some(remoteChannelReady)) // no need to store, they will re-send if we get disconnected

    case Event(w: WatchPublishedTriggered, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      val fundingStatus = LocalFundingStatus.ZeroconfPublishedFundingTx(w.tx)
      d.commitments.updateLocalFundingStatus(w.tx.txid, fundingStatus) match {
        case Right((commitments1, _)) =>
          log.info("funding txid={} was successfully published for zero-conf channelId={}", w.tx.txid, d.channelId)
          // we still watch the funding tx for confirmation even if we can use the zero-conf channel right away
          watchFundingConfirmed(w.tx.txid, Some(nodeParams.channelConf.minDepthBlocks))
          val realScidStatus = RealScidStatus.Unknown
          val shortIds = createShortIds(d.channelId, realScidStatus)
          val channelReady = createChannelReady(shortIds, d.commitments.params)
          d.deferred.foreach(self ! _)
          goto(WAIT_FOR_CHANNEL_READY) using DATA_WAIT_FOR_CHANNEL_READY(commitments1, shortIds) storing() sending channelReady
        case Left(_) => stay()
      }

    case Event(w: WatchFundingConfirmedTriggered, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      acceptFundingTxConfirmed(w, d) match {
        case Right((commitments1, _)) =>
          val realScidStatus = RealScidStatus.Temporary(RealShortChannelId(w.blockHeight, w.txIndex, d.commitments.latest.commitInput.outPoint.index.toInt))
          val shortIds = createShortIds(d.channelId, realScidStatus)
          val channelReady = createChannelReady(shortIds, d.commitments.params)
          d.deferred.foreach(self ! _)
          goto(WAIT_FOR_CHANNEL_READY) using DATA_WAIT_FOR_CHANNEL_READY(commitments1, shortIds) storing() sending channelReady
        case Left(_) => stay()
      }

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if d.commitments.announceChannel =>
      delayEarlyAnnouncementSigs(remoteAnnSigs)
      stay()

    case Event(getTxResponse: GetTxWithMetaResponse, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if getTxResponse.txid == d.commitments.latest.fundingTxId => handleGetFundingTx(getTxResponse, d.waitingSince, d.fundingTx_opt)

    case Event(BITCOIN_FUNDING_PUBLISH_FAILED, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingPublishFailed(d)

    case Event(ProcessCurrentBlockHeight(c), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => d.fundingTx_opt match {
      case Some(_) => stay() // we are funder, we're still waiting for the funding tx to be confirmed
      case None if c.blockHeight - d.waitingSince > FUNDING_TIMEOUT_FUNDEE =>
        log.warning(s"funding tx hasn't been published in ${c.blockHeight - d.waitingSince} blocks")
        self ! BITCOIN_FUNDING_TIMEOUT
        stay()
      case None => stay() // let's wait longer
    }

    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingTimeout(d)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleRemoteError(e, d)
  })

  when(WAIT_FOR_CHANNEL_READY)(handleExceptions {
    case Event(channelReady: ChannelReady, d: DATA_WAIT_FOR_CHANNEL_READY) =>
      val d1 = receiveChannelReady(d.shortIds, channelReady, d.commitments)
      goto(NORMAL) using d1 storing()

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_CHANNEL_READY) if d.commitments.announceChannel =>
      delayEarlyAnnouncementSigs(remoteAnnSigs)
      stay()

    case Event(e: Error, d: DATA_WAIT_FOR_CHANNEL_READY) => handleRemoteError(e, d)
  })

}
