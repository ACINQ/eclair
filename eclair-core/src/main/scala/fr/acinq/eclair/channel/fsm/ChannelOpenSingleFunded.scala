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
import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.ChannelSpendSignature.{IndividualSignature, PartialSignatureWithNonce}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel.LocalFundingStatus.SingleFundedUnconfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel._
import fr.acinq.eclair.channel.publish.TxPublisher.SetChannelId
import fr.acinq.eclair.crypto.keymanager.{LocalCommitmentKeys, RemoteCommitmentKeys}
import fr.acinq.eclair.crypto.{NonceGenerator, ShaChain}
import fr.acinq.eclair.io.Peer.OpenChannelResponse
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.{AnchorOutputsCommitmentFormat, DefaultCommitmentFormat, SimpleTaprootChannelCommitmentFormat}
import fr.acinq.eclair.wire.protocol.{AcceptChannel, AcceptChannelTlv, AnnouncementSignatures, ChannelReady, ChannelTlv, Error, FundingCreated, FundingSigned, OpenChannel, OpenChannelTlv, TlvStream}
import fr.acinq.eclair.{MilliSatoshiLong, randomKey, toLongId}
import scodec.bits.ByteVector

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
      val fundingKey = channelKeys.fundingKey(fundingTxIndex = 0)
      // In order to allow TLV extensions and keep backwards-compatibility, we include an empty upfront_shutdown_script if this feature is not used
      // See https://github.com/lightningnetwork/lightning-rfc/pull/714.
      val localShutdownScript = input.localChannelParams.upfrontShutdownScript_opt.getOrElse(ByteVector.empty)
      val localNonce = input.channelType.commitmentFormat match {
        case _: SimpleTaprootChannelCommitmentFormat => Some(NonceGenerator.verificationNonce(NonceGenerator.dummyFundingTxId, fundingKey, NonceGenerator.dummyRemoteFundingPubKey, 0).publicNonce)
        case _: AnchorOutputsCommitmentFormat | DefaultCommitmentFormat => None
      }
      val open = OpenChannel(
        chainHash = nodeParams.chainHash,
        temporaryChannelId = input.temporaryChannelId,
        fundingSatoshis = input.fundingAmount,
        pushMsat = input.pushAmount_opt.getOrElse(0 msat),
        dustLimitSatoshis = input.proposedCommitParams.localDustLimit,
        maxHtlcValueInFlightMsat = input.proposedCommitParams.localMaxHtlcValueInFlight,
        channelReserveSatoshis = input.localChannelParams.initialRequestedChannelReserve_opt.get,
        htlcMinimumMsat = input.proposedCommitParams.localHtlcMinimum,
        feeratePerKw = input.commitTxFeerate,
        toSelfDelay = input.proposedCommitParams.toRemoteDelay,
        maxAcceptedHtlcs = input.proposedCommitParams.localMaxAcceptedHtlcs,
        fundingPubkey = fundingKey.publicKey,
        revocationBasepoint = channelKeys.revocationBasePoint,
        paymentBasepoint = input.localChannelParams.walletStaticPaymentBasepoint.getOrElse(channelKeys.paymentBasePoint),
        delayedPaymentBasepoint = channelKeys.delayedPaymentBasePoint,
        htlcBasepoint = channelKeys.htlcBasePoint,
        firstPerCommitmentPoint = channelKeys.commitmentPoint(0),
        channelFlags = input.channelFlags,
        tlvStream = TlvStream(
          Set(
            Some(ChannelTlv.UpfrontShutdownScriptTlv(localShutdownScript)),
            Some(ChannelTlv.ChannelTypeTlv(input.channelType)),
            localNonce.map(n => ChannelTlv.NextLocalNonceTlv(n))
          ).flatten[OpenChannelTlv]
        ))
      goto(WAIT_FOR_ACCEPT_CHANNEL) using DATA_WAIT_FOR_ACCEPT_CHANNEL(input, open) sending open
  })

  when(WAIT_FOR_OPEN_CHANNEL)(handleExceptions {
    case Event(open: OpenChannel, d: DATA_WAIT_FOR_OPEN_CHANNEL) =>
      Helpers.validateParamsSingleFundedFundee(nodeParams, d.initFundee.channelType, d.initFundee.localChannelParams.initFeatures, open, remoteNodeId, d.initFundee.remoteInit.features) match {
        case Left(t) => handleLocalError(t, d, Some(open))
        case Right((channelFeatures, remoteShutdownScript)) =>
          context.system.eventStream.publish(ChannelCreated(self, peer, remoteNodeId, isOpener = false, open.temporaryChannelId, open.feeratePerKw, None))
          val remoteChannelParams = RemoteChannelParams(
            nodeId = remoteNodeId,
            initialRequestedChannelReserve_opt = Some(open.channelReserveSatoshis), // our peer requires us to always have at least that much satoshis in our balance
            revocationBasepoint = open.revocationBasepoint,
            paymentBasepoint = open.paymentBasepoint,
            delayedPaymentBasepoint = open.delayedPaymentBasepoint,
            htlcBasepoint = open.htlcBasepoint,
            initFeatures = d.initFundee.remoteInit.features,
            upfrontShutdownScript_opt = remoteShutdownScript)
          val fundingKey = channelKeys.fundingKey(fundingTxIndex = 0)
          val channelParams = ChannelParams(d.initFundee.temporaryChannelId, d.initFundee.channelConfig, channelFeatures, d.initFundee.localChannelParams, remoteChannelParams, open.channelFlags)
          val localCommitParams = CommitParams(d.initFundee.proposedCommitParams.localDustLimit, d.initFundee.proposedCommitParams.localHtlcMinimum, d.initFundee.proposedCommitParams.localMaxHtlcValueInFlight, d.initFundee.proposedCommitParams.localMaxAcceptedHtlcs, open.toSelfDelay)
          val remoteCommitParams = CommitParams(open.dustLimitSatoshis, open.htlcMinimumMsat, open.maxHtlcValueInFlightMsat, open.maxAcceptedHtlcs, d.initFundee.proposedCommitParams.toRemoteDelay)
          // In order to allow TLV extensions and keep backwards-compatibility, we include an empty upfront_shutdown_script if this feature is not used.
          // See https://github.com/lightningnetwork/lightning-rfc/pull/714.
          val localShutdownScript = d.initFundee.localChannelParams.upfrontShutdownScript_opt.getOrElse(ByteVector.empty)
          val localNonce = d.initFundee.channelType.commitmentFormat match {
            case _: SimpleTaprootChannelCommitmentFormat => Some(NonceGenerator.verificationNonce(NonceGenerator.dummyFundingTxId, fundingKey, NonceGenerator.dummyRemoteFundingPubKey, 0).publicNonce)
            case _: AnchorOutputsCommitmentFormat | DefaultCommitmentFormat => None
          }
          val accept = AcceptChannel(temporaryChannelId = open.temporaryChannelId,
            dustLimitSatoshis = localCommitParams.dustLimit,
            maxHtlcValueInFlightMsat = localCommitParams.maxHtlcValueInFlight,
            channelReserveSatoshis = d.initFundee.localChannelParams.initialRequestedChannelReserve_opt.get,
            minimumDepth = channelParams.minDepth(nodeParams.channelConf.minDepth).getOrElse(0).toLong,
            htlcMinimumMsat = localCommitParams.htlcMinimum,
            toSelfDelay = remoteCommitParams.toSelfDelay,
            maxAcceptedHtlcs = localCommitParams.maxAcceptedHtlcs,
            fundingPubkey = fundingKey.publicKey,
            revocationBasepoint = channelKeys.revocationBasePoint,
            paymentBasepoint = d.initFundee.localChannelParams.walletStaticPaymentBasepoint.getOrElse(channelKeys.paymentBasePoint),
            delayedPaymentBasepoint = channelKeys.delayedPaymentBasePoint,
            htlcBasepoint = channelKeys.htlcBasePoint,
            firstPerCommitmentPoint = channelKeys.commitmentPoint(0),
            tlvStream = TlvStream(Set(
              Some(ChannelTlv.UpfrontShutdownScriptTlv(localShutdownScript)),
              Some(ChannelTlv.ChannelTypeTlv(d.initFundee.channelType)),
              localNonce.map(n => ChannelTlv.NextLocalNonceTlv(n))
            ).flatten[AcceptChannelTlv]))
          remoteNextCommitNonces = open.commitNonce_opt.map(n => NonceGenerator.dummyFundingTxId -> n).toMap
          goto(WAIT_FOR_FUNDING_CREATED) using DATA_WAIT_FOR_FUNDING_CREATED(channelParams, d.initFundee.channelType, localCommitParams, remoteCommitParams, open.fundingSatoshis, open.pushMsat, open.feeratePerKw, open.fundingPubkey, open.firstPerCommitmentPoint) sending accept
      }

    case Event(c: CloseCommand, d) => handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_OPEN_CHANNEL) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_ACCEPT_CHANNEL)(handleExceptions {
    case Event(accept: AcceptChannel, d: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      Helpers.validateParamsSingleFundedFunder(nodeParams, d.initFunder.channelType, d.initFunder.localChannelParams.initFeatures, d.initFunder.remoteInit.features, d.lastSent, accept) match {
        case Left(t) =>
          d.initFunder.replyTo ! OpenChannelResponse.Rejected(t.getMessage)
          handleLocalError(t, d, Some(accept))
        case Right((channelFeatures, remoteShutdownScript)) =>
          val remoteChannelParams = RemoteChannelParams(
            nodeId = remoteNodeId,
            initialRequestedChannelReserve_opt = Some(accept.channelReserveSatoshis), // our peer requires us to always have at least that much satoshis in our balance
            revocationBasepoint = accept.revocationBasepoint,
            paymentBasepoint = accept.paymentBasepoint,
            delayedPaymentBasepoint = accept.delayedPaymentBasepoint,
            htlcBasepoint = accept.htlcBasepoint,
            initFeatures = d.initFunder.remoteInit.features,
            upfrontShutdownScript_opt = remoteShutdownScript)
          log.info("remote will use fundingMinDepth={}", accept.minimumDepth)
          val localFundingKey = channelKeys.fundingKey(fundingTxIndex = 0)
          val fundingPubkeyScript = Transactions.makeFundingScript(localFundingKey.publicKey, accept.fundingPubkey, d.initFunder.channelType.commitmentFormat).pubkeyScript
          wallet.makeFundingTx(fundingPubkeyScript, d.initFunder.fundingAmount, d.initFunder.fundingTxFeerate, d.initFunder.fundingTxFeeBudget_opt).pipeTo(self)
          val channelParams = ChannelParams(d.initFunder.temporaryChannelId, d.initFunder.channelConfig, channelFeatures, d.initFunder.localChannelParams, remoteChannelParams, d.lastSent.channelFlags)
          val localCommitParams = CommitParams(d.initFunder.proposedCommitParams.localDustLimit, d.initFunder.proposedCommitParams.localHtlcMinimum, d.initFunder.proposedCommitParams.localMaxHtlcValueInFlight, d.initFunder.proposedCommitParams.localMaxAcceptedHtlcs, accept.toSelfDelay)
          val remoteCommitParams = CommitParams(accept.dustLimitSatoshis, accept.htlcMinimumMsat, accept.maxHtlcValueInFlightMsat, accept.maxAcceptedHtlcs, d.initFunder.proposedCommitParams.toRemoteDelay)
          remoteNextCommitNonces = accept.commitNonce_opt.map(n => NonceGenerator.dummyFundingTxId -> n).toMap
          goto(WAIT_FOR_FUNDING_INTERNAL) using DATA_WAIT_FOR_FUNDING_INTERNAL(channelParams, d.initFunder.channelType, localCommitParams, remoteCommitParams, d.initFunder.fundingAmount, d.initFunder.pushAmount_opt.getOrElse(0 msat), d.initFunder.commitTxFeerate, accept.fundingPubkey, accept.firstPerCommitmentPoint, d.initFunder.replyTo)
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      d.initFunder.replyTo ! OpenChannelResponse.Cancelled
      handleFastClose(c, d.lastSent.temporaryChannelId)

    case Event(e: Error, d: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      d.initFunder.replyTo ! OpenChannelResponse.RemoteError(e.toAscii)
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      d.initFunder.replyTo ! OpenChannelResponse.Disconnected
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, d: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      d.initFunder.replyTo ! OpenChannelResponse.TimedOut
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_INTERNAL)(handleExceptions {
    case Event(MakeFundingTxResponse(fundingTx, fundingTxOutputIndex, fundingTxFee), d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      val temporaryChannelId = d.channelParams.channelId
      // let's create the first commitment tx that spends the yet uncommitted funding tx
      val fundingKey = channelKeys.fundingKey(fundingTxIndex = 0)
      val localCommitmentKeys = LocalCommitmentKeys(d.channelParams, channelKeys, localCommitIndex = 0, d.commitmentFormat)
      val remoteCommitmentKeys = RemoteCommitmentKeys(d.channelParams, channelKeys, d.remoteFirstPerCommitmentPoint, d.commitmentFormat)
      Funding.makeFirstCommitTxs(d.channelParams, d.localCommitParams, d.remoteCommitParams, localFundingAmount = d.fundingAmount, remoteFundingAmount = 0 sat, localPushAmount = d.pushAmount, remotePushAmount = 0 msat, d.commitTxFeerate, d.commitmentFormat, fundingTx.txid, fundingTxOutputIndex, fundingKey, d.remoteFundingPubKey, localCommitmentKeys, remoteCommitmentKeys) match {
        case Left(ex) => handleLocalError(ex, d, None)
        case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx)) =>
          require(fundingTx.txOut(fundingTxOutputIndex).publicKeyScript == localCommitTx.input.txOut.publicKeyScript, "pubkey script mismatch!")
          val remoteCommit = RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, d.remoteFirstPerCommitmentPoint)
          val localSigOfRemoteTx = d.commitmentFormat match {
            case _: SimpleTaprootChannelCommitmentFormat =>
              val localNonce = NonceGenerator.verificationNonce(NonceGenerator.dummyFundingTxId, fundingKey, NonceGenerator.dummyRemoteFundingPubKey, 0)
              remoteNextCommitNonces.get(NonceGenerator.dummyFundingTxId) match {
                case Some(remoteNonce) =>
                  remoteCommitTx.partialSign(fundingKey, d.remoteFundingPubKey, localNonce, Seq(localNonce.publicNonce, remoteNonce)) match {
                    case Left(_) => Left(InvalidCommitNonce(d.channelId, NonceGenerator.dummyFundingTxId, commitmentNumber = 0))
                    case Right(psig) => Right(psig)
                  }
                case None => Left(MissingCommitNonce(d.channelId, NonceGenerator.dummyFundingTxId, commitmentNumber = 0))
              }
            case _: AnchorOutputsCommitmentFormat | DefaultCommitmentFormat => Right(remoteCommitTx.sign(fundingKey, d.remoteFundingPubKey))
          }
          localSigOfRemoteTx match {
            case Left(f) => handleLocalError(f, d, None)
            case Right(localSig) =>
              val fundingCreated = FundingCreated(temporaryChannelId, fundingTx.txid, fundingTxOutputIndex, localSig)
              val channelId = toLongId(fundingTx.txid, fundingTxOutputIndex)
              val channelParams1 = d.channelParams.copy(channelId = channelId)
              peer ! ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
              txPublisher ! SetChannelId(remoteNodeId, channelId)
              context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId))
              // NB: we don't send a ChannelSignatureSent for the first commit
              goto(WAIT_FOR_FUNDING_SIGNED) using DATA_WAIT_FOR_FUNDING_SIGNED(channelParams1, d.channelType, d.localCommitParams, d.remoteCommitParams, d.remoteFundingPubKey, fundingTx, fundingTxFee, localSpec, localCommitTx, remoteCommit, fundingCreated, d.replyTo) sending fundingCreated
          }
      }

    case Event(Status.Failure(t), d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      log.error(t, s"wallet returned error: ")
      d.replyTo ! OpenChannelResponse.Rejected(s"wallet error: ${t.getMessage}")
      handleLocalError(ChannelFundingError(d.channelId), d, None) // we use a generic exception and don't send the internal error to the peer

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      d.replyTo ! OpenChannelResponse.Cancelled
      handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      d.replyTo ! OpenChannelResponse.RemoteError(e.toAscii)
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      d.replyTo ! OpenChannelResponse.Disconnected
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      d.replyTo ! OpenChannelResponse.TimedOut
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_CREATED)(handleExceptions {
    case Event(fc@FundingCreated(_, fundingTxId, fundingTxOutputIndex, _, _), d: DATA_WAIT_FOR_FUNDING_CREATED) =>
      val temporaryChannelId = d.channelParams.channelId
      val fundingKey = channelKeys.fundingKey(fundingTxIndex = 0)
      val localCommitmentKeys = LocalCommitmentKeys(d.channelParams, channelKeys, localCommitIndex = 0, d.commitmentFormat)
      val remoteCommitmentKeys = RemoteCommitmentKeys(d.channelParams, channelKeys, d.remoteFirstPerCommitmentPoint, d.commitmentFormat)
      Funding.makeFirstCommitTxs(d.channelParams, d.localCommitParams, d.remoteCommitParams, localFundingAmount = 0 sat, remoteFundingAmount = d.fundingAmount, localPushAmount = 0 msat, remotePushAmount = d.pushAmount, d.commitTxFeerate, d.commitmentFormat, fundingTxId, fundingTxOutputIndex, fundingKey, d.remoteFundingPubKey, localCommitmentKeys, remoteCommitmentKeys) match {
        case Left(ex) => handleLocalError(ex, d, Some(fc))
        case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx)) =>
          // check remote signature validity
          val isRemoteSigValid = fc.sigOrPartialSig match {
            case psig: PartialSignatureWithNonce =>
              val localNonce = NonceGenerator.verificationNonce(NonceGenerator.dummyFundingTxId, fundingKey, NonceGenerator.dummyRemoteFundingPubKey, 0)
              localCommitTx.checkRemotePartialSignature(fundingKey.publicKey, d.remoteFundingPubKey, psig, localNonce.publicNonce)
            case sig: IndividualSignature =>
              localCommitTx.checkRemoteSig(fundingKey.publicKey, d.remoteFundingPubKey, sig)
          }
          isRemoteSigValid match {
            case false => handleLocalError(InvalidCommitmentSignature(temporaryChannelId, fundingTxId, commitmentNumber = 0, localCommitTx.tx), d, Some(fc))
            case true =>
              val channelId = toLongId(fundingTxId, fundingTxOutputIndex)
              val localSigOfRemoteTx = d.commitmentFormat match {
                case _: SimpleTaprootChannelCommitmentFormat =>
                  val localNonce = NonceGenerator.verificationNonce(NonceGenerator.dummyFundingTxId, fundingKey, NonceGenerator.dummyRemoteFundingPubKey, 0)
                  remoteNextCommitNonces.get(NonceGenerator.dummyFundingTxId) match {
                    case Some(remoteNonce) =>
                      remoteCommitTx.partialSign(fundingKey, d.remoteFundingPubKey, localNonce, Seq(localNonce.publicNonce, remoteNonce)) match {
                        case Left(_) => Left(InvalidCommitNonce(channelId, NonceGenerator.dummyFundingTxId, commitmentNumber = 0))
                        case Right(psig) => Right(psig)
                      }
                    case None => Left(MissingCommitNonce(channelId, NonceGenerator.dummyFundingTxId, commitmentNumber = 0))
                  }
                case _: AnchorOutputsCommitmentFormat | DefaultCommitmentFormat => Right(remoteCommitTx.sign(fundingKey, d.remoteFundingPubKey))
              }
              localSigOfRemoteTx match {
                case Left(f) => handleLocalError(f, d, Some(fc))
                case Right(localSig) =>
                  val fundingSigned = FundingSigned(channelId, localSig)
                  val commitment = Commitment(
                    fundingTxIndex = 0,
                    firstRemoteCommitIndex = 0,
                    fundingInput = localCommitTx.input.outPoint,
                    fundingAmount = localCommitTx.input.txOut.amount,
                    remoteFundingPubKey = d.remoteFundingPubKey,
                    localFundingStatus = SingleFundedUnconfirmedFundingTx(None),
                    remoteFundingStatus = RemoteFundingStatus.NotLocked,
                    commitmentFormat = d.commitmentFormat,
                    localCommitParams = d.localCommitParams,
                    localCommit = LocalCommit(0, localSpec, localCommitTx.tx.txid, fc.sigOrPartialSig, htlcRemoteSigs = Nil),
                    remoteCommitParams = d.remoteCommitParams,
                    remoteCommit = RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, d.remoteFirstPerCommitmentPoint),
                    nextRemoteCommit_opt = None)
                  val commitments = Commitments(
                    channelParams = d.channelParams.copy(channelId = channelId),
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
                  log.info("waiting for them to publish the funding tx for channelId={} fundingTxid={}", channelId, commitment.fundingTxId)
                  watchFundingConfirmed(commitment.fundingTxId, d.channelParams.minDepth(nodeParams.channelConf.minDepth), delay_opt = None)
                  goto(WAIT_FOR_FUNDING_CONFIRMED) using DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, nodeParams.currentBlockHeight, None, Right(fundingSigned)) storing() sending fundingSigned
              }
          }
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_CREATED) => handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_CREATED) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_SIGNED)(handleExceptions {
    case Event(fundingSigned: FundingSigned, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we make sure that their sig checks out and that our first commit tx is spendable
      val fundingKey = channelKeys.fundingKey(fundingTxIndex = 0)
      val isRemoteSigValid = fundingSigned.sigOrPartialSig match {
        case psig: PartialSignatureWithNonce =>
          val localNonce = NonceGenerator.verificationNonce(NonceGenerator.dummyFundingTxId, fundingKey, NonceGenerator.dummyRemoteFundingPubKey, 0)
          d.localCommitTx.checkRemotePartialSignature(fundingKey.publicKey, d.remoteFundingPubKey, psig, localNonce.publicNonce)
        case sig: IndividualSignature =>
          d.localCommitTx.checkRemoteSig(fundingKey.publicKey, d.remoteFundingPubKey, sig)
      }
      isRemoteSigValid match {
        case false =>
          // we rollback the funding tx, it will never be published
          wallet.rollback(d.fundingTx)
          d.replyTo ! OpenChannelResponse.Rejected("invalid commit signatures")
          handleLocalError(InvalidCommitmentSignature(d.channelId, d.fundingTx.txid, commitmentNumber = 0, d.localCommitTx.tx), d, Some(fundingSigned))
        case true =>
          val commitment = Commitment(
            fundingTxIndex = 0,
            firstRemoteCommitIndex = 0,
            fundingInput = d.localCommitTx.input.outPoint,
            fundingAmount = d.localCommitTx.input.txOut.amount,
            remoteFundingPubKey = d.remoteFundingPubKey,
            localFundingStatus = SingleFundedUnconfirmedFundingTx(Some(d.fundingTx)),
            remoteFundingStatus = RemoteFundingStatus.NotLocked,
            commitmentFormat = d.commitmentFormat,
            localCommitParams = d.localCommitParams,
            localCommit = LocalCommit(0, d.localSpec, d.localCommitTx.tx.txid, fundingSigned.sigOrPartialSig, htlcRemoteSigs = Nil),
            remoteCommitParams = d.remoteCommitParams,
            remoteCommit = d.remoteCommit,
            nextRemoteCommit_opt = None
          )
          val commitments = Commitments(
            channelParams = d.channelParams,
            changes = CommitmentChanges.init(),
            active = List(commitment),
            remoteNextCommitInfo = Right(randomKey().publicKey), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array
            remotePerCommitmentSecrets = ShaChain.init,
            originChannels = Map.empty)
          val blockHeight = nodeParams.currentBlockHeight
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
          log.info("publishing funding tx fundingTxId={}", commitment.fundingTxId)
          watchFundingConfirmed(commitment.fundingTxId, d.channelParams.minDepth(nodeParams.channelConf.minDepth), delay_opt = None)
          // we will publish the funding tx only after the channel state has been written to disk because we want to
          // make sure we first persist the commitment that returns back the funds to us in case of problem
          goto(WAIT_FOR_FUNDING_CONFIRMED) using DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, blockHeight, None, Left(d.lastSent)) storing() calling publishFundingTx(d.channelId, d.fundingTx, d.fundingTxFee, d.replyTo)
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      d.replyTo ! OpenChannelResponse.Cancelled
      handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      d.replyTo ! OpenChannelResponse.RemoteError(e.toAscii)
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      d.replyTo ! OpenChannelResponse.Disconnected
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      d.replyTo ! OpenChannelResponse.TimedOut
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_CONFIRMED)(handleExceptions {
    case Event(remoteChannelReady: ChannelReady, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      // We are here if:
      //  - we're using zero-conf, but our peer was very fast and we received their channel_ready before our watcher
      //    notification that the funding tx has been successfully published: in that case we don't put a duplicate watch
      //  - we're not using zero-conf, but our peer decided to trust us anyway, in which case we can skip waiting for
      //    confirmations if we're the initiator (no risk of double-spend) and they provided a channel alias
      val switchToZeroConf = d.commitments.localChannelParams.isChannelOpener &&
        remoteChannelReady.alias_opt.isDefined &&
        !d.commitments.channelParams.zeroConf
      if (switchToZeroConf) {
        log.info("this channel isn't zero-conf, but we are funder and they sent an early channel_ready with an alias: no need to wait for confirmations")
        blockchain ! WatchPublished(self, d.commitments.latest.fundingTxId)
      }
      log.debug("received their channel_ready, deferring message")
      stay() using d.copy(deferred = Some(remoteChannelReady)) // no need to store, they will re-send if we get disconnected

    case Event(w: WatchPublishedTriggered, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      val fundingStatus = LocalFundingStatus.ZeroconfPublishedFundingTx(w.tx, None, None)
      d.commitments.updateLocalFundingStatus(w.tx.txid, fundingStatus, lastAnnouncedFundingTxId_opt = None) match {
        case Right((commitments1, _)) =>
          log.info("funding txid={} was successfully published for zero-conf channelId={}", w.tx.txid, d.channelId)
          // We still watch the funding tx for confirmation even if we can use the zero-conf channel right away.
          watchFundingConfirmed(w.tx.txid, Some(nodeParams.channelConf.minDepth), delay_opt = None)
          val shortIds = createShortIdAliases(d.channelId)
          val channelReady = createChannelReady(shortIds, d.commitments)
          d.deferred.foreach(self ! _)
          goto(WAIT_FOR_CHANNEL_READY) using DATA_WAIT_FOR_CHANNEL_READY(commitments1, shortIds) storing() sending channelReady
        case Left(_) => stay()
      }

    case Event(w: WatchFundingConfirmedTriggered, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      acceptFundingTxConfirmed(w, d) match {
        case Right((commitments1, _)) =>
          val shortIds = createShortIdAliases(d.channelId)
          val channelReady = createChannelReady(shortIds, d.commitments)
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
        log.warning("funding tx hasn't been published in {} blocks", c.blockHeight - d.waitingSince)
        self ! BITCOIN_FUNDING_TIMEOUT
        stay()
      case None => stay() // let's wait longer
    }

    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingTimeout(d)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleRemoteError(e, d)
  })

  when(WAIT_FOR_CHANNEL_READY)(handleExceptions {
    case Event(channelReady: ChannelReady, d: DATA_WAIT_FOR_CHANNEL_READY) =>
      val d1 = receiveChannelReady(d.aliases, channelReady, d.commitments)
      val annSigs_opt = d1.commitments.all.find(_.fundingTxIndex == 0).flatMap(_.signAnnouncement(nodeParams, d1.commitments.channelParams, channelKeys.fundingKey(fundingTxIndex = 0)))
      annSigs_opt.foreach(annSigs => announcementSigsSent += annSigs.shortChannelId)
      goto(NORMAL) using d1 storing() sending annSigs_opt.toSeq

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_CHANNEL_READY) if d.commitments.announceChannel =>
      delayEarlyAnnouncementSigs(remoteAnnSigs)
      stay()

    case Event(e: Error, d: DATA_WAIT_FOR_CHANNEL_READY) => handleRemoteError(e, d)
  })

}
