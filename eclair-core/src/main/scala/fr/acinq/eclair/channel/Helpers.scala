/*
 * Copyright 2019 ACINQ SAS
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

import akka.event.{DiagnosticLoggingAdapter, LoggingAdapter}
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey, sha256}
import fr.acinq.bitcoin.scalacompat.Script._
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.OnChainPubkeyCache
import fr.acinq.eclair.blockchain.fee._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.REFRESH_CHANNEL_UPDATE_INTERVAL
import fr.acinq.eclair.crypto.keymanager.ChannelKeys
import fr.acinq.eclair.crypto.{Generators, ShaChain}
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.DirectedHtlc._
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.protocol._
import scodec.bits.ByteVector

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Created by PM on 20/05/2016.
 */

object Helpers {
  /**
   * We update local/global features at reconnection
   */
  def updateFeatures(data: PersistentChannelData, localInit: Init, remoteInit: Init): PersistentChannelData = {
    data match {
      case d: DATA_WAIT_FOR_FUNDING_CONFIRMED => d.copy(commitments = d.commitments.updateInitFeatures(localInit, remoteInit))
      case d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED => d.copy(channelParams = d.channelParams.updateFeatures(localInit, remoteInit))
      case d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED => d.copy(commitments = d.commitments.updateInitFeatures(localInit, remoteInit))
      case d: DATA_WAIT_FOR_CHANNEL_READY => d.copy(commitments = d.commitments.updateInitFeatures(localInit, remoteInit))
      case d: DATA_WAIT_FOR_DUAL_FUNDING_READY => d.copy(commitments = d.commitments.updateInitFeatures(localInit, remoteInit))
      case d: DATA_NORMAL => d.copy(commitments = d.commitments.updateInitFeatures(localInit, remoteInit))
      case d: DATA_SHUTDOWN => d.copy(commitments = d.commitments.updateInitFeatures(localInit, remoteInit))
      case d: DATA_NEGOTIATING => d.copy(commitments = d.commitments.updateInitFeatures(localInit, remoteInit))
      case d: DATA_NEGOTIATING_SIMPLE => d.copy(commitments = d.commitments.updateInitFeatures(localInit, remoteInit))
      case d: DATA_CLOSING => d.copy(commitments = d.commitments.updateInitFeatures(localInit, remoteInit))
      case d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT => d.copy(commitments = d.commitments.updateInitFeatures(localInit, remoteInit))
    }
  }

  def updateCommitments(data: ChannelDataWithCommitments, commitments: Commitments): PersistentChannelData = {
    data match {
      case d: DATA_WAIT_FOR_FUNDING_CONFIRMED => d.copy(commitments = commitments)
      case d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED => d.copy(commitments = commitments)
      case d: DATA_WAIT_FOR_CHANNEL_READY => d.copy(commitments = commitments)
      case d: DATA_WAIT_FOR_DUAL_FUNDING_READY => d.copy(commitments = commitments)
      case d: DATA_NORMAL => d.copy(commitments = commitments)
      case d: DATA_SHUTDOWN => d.copy(commitments = commitments)
      case d: DATA_NEGOTIATING => d.copy(commitments = commitments)
      case d: DATA_NEGOTIATING_SIMPLE => d.copy(commitments = commitments)
      case d: DATA_CLOSING => d.copy(commitments = commitments)
      case d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT => d.copy(commitments = commitments)
    }
  }

  private def extractShutdownScript(channelId: ByteVector32, localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature], upfrontShutdownScript_opt: Option[ByteVector]): Either[ChannelException, Option[ByteVector]] = {
    val canUseUpfrontShutdownScript = Features.canUseFeature(localFeatures, remoteFeatures, Features.UpfrontShutdownScript)
    val canUseAnySegwit = Features.canUseFeature(localFeatures, remoteFeatures, Features.ShutdownAnySegwit)
    val canUseOpReturn = Features.canUseFeature(localFeatures, remoteFeatures, Features.SimpleClose)
    extractShutdownScript(channelId, canUseUpfrontShutdownScript, canUseAnySegwit, canUseOpReturn, upfrontShutdownScript_opt)
  }

  private def extractShutdownScript(channelId: ByteVector32, hasOptionUpfrontShutdownScript: Boolean, allowAnySegwit: Boolean, allowOpReturn: Boolean, upfrontShutdownScript_opt: Option[ByteVector]): Either[ChannelException, Option[ByteVector]] = {
    (hasOptionUpfrontShutdownScript, upfrontShutdownScript_opt) match {
      case (true, None) => Left(MissingUpfrontShutdownScript(channelId))
      case (true, Some(script)) if script.isEmpty => Right(None) // but the provided script can be empty
      case (true, Some(script)) if !Closing.MutualClose.isValidFinalScriptPubkey(script, allowAnySegwit, allowOpReturn) => Left(InvalidFinalScript(channelId))
      case (true, Some(script)) => Right(Some(script))
      case (false, Some(_)) => Right(None) // they provided a script but the feature is not active, we just ignore it
      case _ => Right(None)
    }
  }

  /** Called by the fundee of a single-funded channel. */
  def validateParamsSingleFundedFundee(nodeParams: NodeParams, channelType: SupportedChannelType, localFeatures: Features[InitFeature], open: OpenChannel, remoteNodeId: PublicKey, remoteFeatures: Features[InitFeature]): Either[ChannelException, (ChannelFeatures, Option[ByteVector])] = {
    // BOLT #2: if the chain_hash value, within the open_channel, message is set to a hash of a chain that is unknown to the receiver:
    // MUST reject the channel.
    if (nodeParams.chainHash != open.chainHash) return Left(InvalidChainHash(open.temporaryChannelId, local = nodeParams.chainHash, remote = open.chainHash))

    // BOLT #2: Channel funding limits
    if (open.fundingSatoshis < nodeParams.channelConf.minFundingSatoshis(open.channelFlags)) return Left(FundingAmountTooLow(open.temporaryChannelId, open.fundingSatoshis, nodeParams.channelConf.minFundingSatoshis(open.channelFlags)))
    if (open.fundingSatoshis >= Channel.MAX_FUNDING_WITHOUT_WUMBO && !localFeatures.hasFeature(Features.Wumbo)) return Left(FundingAmountTooHigh(open.temporaryChannelId, open.fundingSatoshis, Channel.MAX_FUNDING_WITHOUT_WUMBO))

    // BOLT #2: The receiving node MUST fail the channel if: push_msat is greater than funding_satoshis * 1000.
    if (open.pushMsat > open.fundingSatoshis) return Left(InvalidPushAmount(open.temporaryChannelId, open.pushMsat, open.fundingSatoshis.toMilliSatoshi))

    // BOLT #2: The receiving node MUST fail the channel if: to_self_delay is unreasonably large.
    if (open.toSelfDelay > nodeParams.channelConf.maxToLocalDelay) return Left(ToSelfDelayTooHigh(open.temporaryChannelId, open.toSelfDelay, nodeParams.channelConf.maxToLocalDelay))

    // BOLT #2: The receiving node MUST fail the channel if: max_accepted_htlcs is greater than 483.
    if (open.maxAcceptedHtlcs > Channel.MAX_ACCEPTED_HTLCS) return Left(InvalidMaxAcceptedHtlcs(open.temporaryChannelId, open.maxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS))

    // BOLT #2: The receiving node MUST fail the channel if: it considers feerate_per_kw too small for timely processing.
    if (isFeeTooSmall(open.feeratePerKw)) return Left(FeerateTooSmall(open.temporaryChannelId, open.feeratePerKw))

    if (open.dustLimitSatoshis > nodeParams.channelConf.maxRemoteDustLimit) return Left(DustLimitTooLarge(open.temporaryChannelId, open.dustLimitSatoshis, nodeParams.channelConf.maxRemoteDustLimit))

    // BOLT #2: The receiving node MUST fail the channel if: dust_limit_satoshis is greater than channel_reserve_satoshis.
    if (open.dustLimitSatoshis > open.channelReserveSatoshis) return Left(DustLimitTooLarge(open.temporaryChannelId, open.dustLimitSatoshis, open.channelReserveSatoshis))
    if (open.dustLimitSatoshis < Channel.MIN_DUST_LIMIT) return Left(DustLimitTooSmall(open.temporaryChannelId, open.dustLimitSatoshis, Channel.MIN_DUST_LIMIT))

    // BOLT #2: The receiving node MUST fail the channel if both to_local and to_remote amounts for the initial commitment
    // transaction are less than or equal to channel_reserve_satoshis (see BOLT 3).
    val (toLocalMsat, toRemoteMsat) = (open.pushMsat, open.fundingSatoshis.toMilliSatoshi - open.pushMsat)
    if (toLocalMsat < open.channelReserveSatoshis && toRemoteMsat < open.channelReserveSatoshis) {
      return Left(ChannelReserveNotMet(open.temporaryChannelId, toLocalMsat, toRemoteMsat, open.channelReserveSatoshis))
    }

    val channelFeatures = ChannelFeatures(channelType, localFeatures, remoteFeatures, open.channelFlags.announceChannel)

    // BOLT #2: The receiving node MUST fail the channel if: it considers feerate_per_kw too small for timely processing or unreasonably large.
    val localFeeratePerKw = nodeParams.onChainFeeConf.getCommitmentFeerate(nodeParams.currentBitcoinCoreFeerates, remoteNodeId, channelFeatures.commitmentFormat, open.fundingSatoshis)
    if (nodeParams.onChainFeeConf.feerateToleranceFor(remoteNodeId).isFeeDiffTooHigh(channelFeatures.commitmentFormat, localFeeratePerKw, open.feeratePerKw)) return Left(FeerateTooDifferent(open.temporaryChannelId, localFeeratePerKw, open.feeratePerKw))

    // we don't check that the funder's amount for the initial commitment transaction is sufficient for full fee payment
    // now, but it will be done later when we receive `funding_created`

    val reserveToFundingRatio = open.channelReserveSatoshis.toLong.toDouble / Math.max(open.fundingSatoshis.toLong, 1)
    if (reserveToFundingRatio > nodeParams.channelConf.maxReserveToFundingRatio) return Left(ChannelReserveTooHigh(open.temporaryChannelId, open.channelReserveSatoshis, reserveToFundingRatio, nodeParams.channelConf.maxReserveToFundingRatio))

    extractShutdownScript(open.temporaryChannelId, localFeatures, remoteFeatures, open.upfrontShutdownScript_opt).map(script_opt => (channelFeatures, script_opt))
  }

  /** Called by the non-initiator of a dual-funded channel. */
  def validateParamsDualFundedNonInitiator(nodeParams: NodeParams,
                                           channelType: SupportedChannelType,
                                           open: OpenDualFundedChannel,
                                           fundingScript: ByteVector,
                                           remoteNodeId: PublicKey,
                                           localFeatures: Features[InitFeature],
                                           remoteFeatures: Features[InitFeature],
                                           addFunding_opt: Option[LiquidityAds.AddFunding]): Either[ChannelException, (ChannelFeatures, Option[ByteVector], Option[LiquidityAds.WillFundPurchase])] = {
    // BOLT #2: if the chain_hash value, within the open_channel, message is set to a hash of a chain that is unknown to the receiver:
    // MUST reject the channel.
    if (nodeParams.chainHash != open.chainHash) return Left(InvalidChainHash(open.temporaryChannelId, local = nodeParams.chainHash, remote = open.chainHash))

    // BOLT #2: Channel funding limits
    if (open.fundingAmount < nodeParams.channelConf.minFundingSatoshis(open.channelFlags)) return Left(FundingAmountTooLow(open.temporaryChannelId, open.fundingAmount, nodeParams.channelConf.minFundingSatoshis(open.channelFlags)))
    if (open.fundingAmount >= Channel.MAX_FUNDING_WITHOUT_WUMBO && !localFeatures.hasFeature(Features.Wumbo)) return Left(FundingAmountTooHigh(open.temporaryChannelId, open.fundingAmount, Channel.MAX_FUNDING_WITHOUT_WUMBO))

    // BOLT #2: The receiving node MUST fail the channel if: push_msat is greater than funding_satoshis * 1000.
    if (open.pushAmount > open.fundingAmount) return Left(InvalidPushAmount(open.temporaryChannelId, open.pushAmount, open.fundingAmount.toMilliSatoshi))

    // BOLT #2: The receiving node MUST fail the channel if: to_self_delay is unreasonably large.
    if (open.toSelfDelay > nodeParams.channelConf.maxToLocalDelay) return Left(ToSelfDelayTooHigh(open.temporaryChannelId, open.toSelfDelay, nodeParams.channelConf.maxToLocalDelay))

    // BOLT #2: The receiving node MUST fail the channel if: max_accepted_htlcs is greater than 483.
    if (open.maxAcceptedHtlcs > Channel.MAX_ACCEPTED_HTLCS) return Left(InvalidMaxAcceptedHtlcs(open.temporaryChannelId, open.maxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS))

    // BOLT #2: The receiving node MUST fail the channel if: it considers feerate_per_kw too small for timely processing.
    if (isFeeTooSmall(open.commitmentFeerate)) return Left(FeerateTooSmall(open.temporaryChannelId, open.commitmentFeerate))

    if (open.dustLimit < Channel.MIN_DUST_LIMIT) return Left(DustLimitTooSmall(open.temporaryChannelId, open.dustLimit, Channel.MIN_DUST_LIMIT))
    if (open.dustLimit > nodeParams.channelConf.maxRemoteDustLimit) return Left(DustLimitTooLarge(open.temporaryChannelId, open.dustLimit, nodeParams.channelConf.maxRemoteDustLimit))

    val channelFeatures = ChannelFeatures(channelType, localFeatures, remoteFeatures, open.channelFlags.announceChannel)

    // BOLT #2: The receiving node MUST fail the channel if: it considers feerate_per_kw too small for timely processing or unreasonably large.
    val localFeeratePerKw = nodeParams.onChainFeeConf.getCommitmentFeerate(nodeParams.currentBitcoinCoreFeerates, remoteNodeId, channelFeatures.commitmentFormat, open.fundingAmount)
    if (nodeParams.onChainFeeConf.feerateToleranceFor(remoteNodeId).isFeeDiffTooHigh(channelFeatures.commitmentFormat, localFeeratePerKw, open.commitmentFeerate)) return Left(FeerateTooDifferent(open.temporaryChannelId, localFeeratePerKw, open.commitmentFeerate))

    for {
      script_opt <- extractShutdownScript(open.temporaryChannelId, localFeatures, remoteFeatures, open.upfrontShutdownScript_opt)
      willFund_opt <- LiquidityAds.validateRequest(nodeParams.privateKey, open.temporaryChannelId, fundingScript, open.fundingFeerate, isChannelCreation = true, open.requestFunding_opt, addFunding_opt.flatMap(_.rates_opt), open.useFeeCredit_opt)
    } yield (channelFeatures, script_opt, willFund_opt)
  }

  private def validateChannelType(channelId: ByteVector32, channelType: SupportedChannelType, channelFlags: ChannelFlags, openChannelType_opt: Option[ChannelType], acceptChannelType_opt: Option[ChannelType], localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature]): Option[ChannelException] = {
    acceptChannelType_opt match {
      case Some(theirChannelType) if acceptChannelType_opt != openChannelType_opt =>
        // if channel_type is set, and channel_type was set in open_channel, and they are not equal types: MUST reject the channel.
        Some(InvalidChannelType(channelId, channelType, theirChannelType))
      case None if Features.canUseFeature(localFeatures, remoteFeatures, Features.ChannelType) =>
        // Bolt 2: if `option_channel_type` is negotiated: MUST set `channel_type`
        Some(MissingChannelType(channelId))
      case None if channelType != ChannelTypes.defaultFromFeatures(localFeatures, remoteFeatures, channelFlags.announceChannel) =>
        // If we have overridden the default channel type, but they didn't support explicit channel type negotiation,
        // we need to abort because they expect a different channel type than what we offered.
        Some(InvalidChannelType(channelId, channelType, ChannelTypes.defaultFromFeatures(localFeatures, remoteFeatures, channelFlags.announceChannel)))
      case _ =>
        // we agree on channel type
        None
    }
  }

  /** Called by the funder of a single-funded channel. */
  def validateParamsSingleFundedFunder(nodeParams: NodeParams, channelType: SupportedChannelType, localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature], open: OpenChannel, accept: AcceptChannel): Either[ChannelException, (ChannelFeatures, Option[ByteVector])] = {
    validateChannelType(open.temporaryChannelId, channelType, open.channelFlags, open.channelType_opt, accept.channelType_opt, localFeatures, remoteFeatures) match {
      case Some(t) => return Left(t)
      case None => // we agree on channel type
    }

    if (accept.maxAcceptedHtlcs > Channel.MAX_ACCEPTED_HTLCS) return Left(InvalidMaxAcceptedHtlcs(accept.temporaryChannelId, accept.maxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS))

    if (accept.dustLimitSatoshis > nodeParams.channelConf.maxRemoteDustLimit) return Left(DustLimitTooLarge(open.temporaryChannelId, accept.dustLimitSatoshis, nodeParams.channelConf.maxRemoteDustLimit))
    if (accept.dustLimitSatoshis < Channel.MIN_DUST_LIMIT) return Left(DustLimitTooSmall(accept.temporaryChannelId, accept.dustLimitSatoshis, Channel.MIN_DUST_LIMIT))

    // BOLT #2: The receiving node MUST fail the channel if: dust_limit_satoshis is greater than channel_reserve_satoshis.
    if (accept.dustLimitSatoshis > accept.channelReserveSatoshis) return Left(DustLimitTooLarge(accept.temporaryChannelId, accept.dustLimitSatoshis, accept.channelReserveSatoshis))

    // if minimum_depth is unreasonably large:
    // MAY reject the channel.
    if (accept.toSelfDelay > nodeParams.channelConf.maxToLocalDelay) return Left(ToSelfDelayTooHigh(accept.temporaryChannelId, accept.toSelfDelay, nodeParams.channelConf.maxToLocalDelay))

    // if channel_reserve_satoshis is less than dust_limit_satoshis within the open_channel message:
    //  MUST reject the channel.
    if (accept.channelReserveSatoshis < open.dustLimitSatoshis) return Left(ChannelReserveBelowOurDustLimit(accept.temporaryChannelId, accept.channelReserveSatoshis, open.dustLimitSatoshis))

    // if channel_reserve_satoshis from the open_channel message is less than dust_limit_satoshis:
    // MUST reject the channel. Other fields have the same requirements as their counterparts in open_channel.
    if (open.channelReserveSatoshis < accept.dustLimitSatoshis) return Left(DustLimitAboveOurChannelReserve(accept.temporaryChannelId, accept.dustLimitSatoshis, open.channelReserveSatoshis))

    val reserveToFundingRatio = accept.channelReserveSatoshis.toLong.toDouble / Math.max(open.fundingSatoshis.toLong, 1)
    if (reserveToFundingRatio > nodeParams.channelConf.maxReserveToFundingRatio) return Left(ChannelReserveTooHigh(open.temporaryChannelId, accept.channelReserveSatoshis, reserveToFundingRatio, nodeParams.channelConf.maxReserveToFundingRatio))

    val channelFeatures = ChannelFeatures(channelType, localFeatures, remoteFeatures, open.channelFlags.announceChannel)
    extractShutdownScript(accept.temporaryChannelId, localFeatures, remoteFeatures, accept.upfrontShutdownScript_opt).map(script_opt => (channelFeatures, script_opt))
  }

  /** Called by the initiator of a dual-funded channel. */
  def validateParamsDualFundedInitiator(nodeParams: NodeParams,
                                        remoteNodeId: PublicKey,
                                        channelType: SupportedChannelType,
                                        localFeatures: Features[InitFeature],
                                        remoteFeatures: Features[InitFeature],
                                        open: OpenDualFundedChannel,
                                        accept: AcceptDualFundedChannel): Either[ChannelException, (ChannelFeatures, Option[ByteVector], Option[LiquidityAds.Purchase])] = {
    validateChannelType(open.temporaryChannelId, channelType, open.channelFlags, open.channelType_opt, accept.channelType_opt, localFeatures, remoteFeatures) match {
      case Some(t) => return Left(t)
      case None => // we agree on channel type
    }

    // BOLT #2: Channel funding limits
    if (accept.fundingAmount < 0.sat) return Left(FundingAmountTooLow(accept.temporaryChannelId, accept.fundingAmount, 0 sat))
    if (accept.fundingAmount > Channel.MAX_FUNDING_WITHOUT_WUMBO && !localFeatures.hasFeature(Features.Wumbo)) return Left(FundingAmountTooHigh(accept.temporaryChannelId, accept.fundingAmount, Channel.MAX_FUNDING_WITHOUT_WUMBO))

    // BOLT #2: The receiving node MUST fail the channel if: push_msat is greater than funding_satoshis * 1000.
    if (accept.pushAmount > accept.fundingAmount) return Left(InvalidPushAmount(accept.temporaryChannelId, accept.pushAmount, accept.fundingAmount.toMilliSatoshi))

    if (accept.maxAcceptedHtlcs > Channel.MAX_ACCEPTED_HTLCS) return Left(InvalidMaxAcceptedHtlcs(accept.temporaryChannelId, accept.maxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS))

    if (accept.dustLimit < Channel.MIN_DUST_LIMIT) return Left(DustLimitTooSmall(accept.temporaryChannelId, accept.dustLimit, Channel.MIN_DUST_LIMIT))
    if (accept.dustLimit > nodeParams.channelConf.maxRemoteDustLimit) return Left(DustLimitTooLarge(open.temporaryChannelId, accept.dustLimit, nodeParams.channelConf.maxRemoteDustLimit))

    // if minimum_depth is unreasonably large:
    // MAY reject the channel.
    if (accept.toSelfDelay > nodeParams.channelConf.maxToLocalDelay) return Left(ToSelfDelayTooHigh(accept.temporaryChannelId, accept.toSelfDelay, nodeParams.channelConf.maxToLocalDelay))

    for {
      script_opt <- extractShutdownScript(accept.temporaryChannelId, localFeatures, remoteFeatures, accept.upfrontShutdownScript_opt)
      fundingScript = Funding.makeFundingPubKeyScript(open.fundingPubkey, accept.fundingPubkey)
      liquidityPurchase_opt <- LiquidityAds.validateRemoteFunding(open.requestFunding_opt, remoteNodeId, accept.temporaryChannelId, fundingScript, accept.fundingAmount, open.fundingFeerate, isChannelCreation = true, accept.willFund_opt)
    } yield {
      val channelFeatures = ChannelFeatures(channelType, localFeatures, remoteFeatures, open.channelFlags.announceChannel)
      (channelFeatures, script_opt, liquidityPurchase_opt)
    }
  }

  /**
   * @param remoteFeeratePerKw remote fee rate per kiloweight
   * @return true if the remote fee rate is too small
   */
  private def isFeeTooSmall(remoteFeeratePerKw: FeeratePerKw): Boolean = {
    remoteFeeratePerKw < FeeratePerKw.MinimumFeeratePerKw
  }

  /** Compute the temporaryChannelId of a dual-funded channel. */
  def dualFundedTemporaryChannelId(channelKeys: ChannelKeys): ByteVector32 = {
    val revocationBasepoint = channelKeys.revocationBaseKey.publicKey
    Crypto.sha256(ByteVector.fill(33)(0) ++ revocationBasepoint.value)
  }

  /** Compute the channelId of a dual-funded channel. */
  def computeChannelId(openRevocationBasepoint: PublicKey, acceptRevocationBasepoint: PublicKey): ByteVector32 = {
    val bin = Seq(openRevocationBasepoint.value, acceptRevocationBasepoint.value)
      .sortWith(LexicographicalOrdering.isLessThan)
      .reduce(_ ++ _)
    Crypto.sha256(bin)
  }

  /**
   * We use the real scid if the channel has been announced, otherwise we use our local alias.
   */
  def scidForChannelUpdate(channelAnnouncement_opt: Option[ChannelAnnouncement], localAlias: Alias): ShortChannelId = {
    channelAnnouncement_opt.map(_.shortChannelId).getOrElse(localAlias)
  }

  def scidForChannelUpdate(d: DATA_NORMAL): ShortChannelId = scidForChannelUpdate(d.lastAnnouncement_opt, d.aliases.localAlias)

  /**
   * If our peer sent us an alias, that's what we must use in the channel_update we send them to ensure they're able to
   * match this update with the corresponding local channel. If they didn't send us an alias, it means we're not using
   * 0-conf and we'll use the real scid.
   */
  def channelUpdateForDirectPeer(nodeParams: NodeParams, channelUpdate: ChannelUpdate, realScid_opt: Option[RealShortChannelId], aliases: ShortIdAliases): ChannelUpdate = {
    aliases.remoteAlias_opt match {
      case Some(remoteAlias) => Announcements.updateScid(nodeParams.privateKey, channelUpdate, remoteAlias)
      case None => realScid_opt match {
        case Some(realScid) => Announcements.updateScid(nodeParams.privateKey, channelUpdate, realScid)
        // This case is a spec violation: this is a 0-conf channel, so our peer MUST send their alias.
        // They won't be able to match our channel_update with their local channel, too bad for them.
        case None => channelUpdate
      }
    }
  }

  /**
   * Compute the delay until we need to refresh the channel_update for our channel not to be considered stale by
   * other nodes.
   *
   * If current update more than [[Channel.REFRESH_CHANNEL_UPDATE_INTERVAL]] old then the delay will be zero.
   *
   * @return the delay until the next update
   */
  def nextChannelUpdateRefresh(currentUpdateTimestamp: TimestampSecond)(implicit log: DiagnosticLoggingAdapter): FiniteDuration = {
    val age = TimestampSecond.now() - currentUpdateTimestamp
    val delay = 0.days.max(REFRESH_CHANNEL_UPDATE_INTERVAL - age)
    Logs.withMdc(log)(Logs.mdc(category_opt = Some(Logs.LogCategory.CONNECTION))) {
      log.debug("current channel_update was created {} days ago, will refresh it in {} days", age.toDays, delay.toDays)
    }
    delay
  }

  /**
   * Computes a maximum HTLC amount adapted to the current balance to reduce chances that other nodes will try sending
   * payments that we can't relay.
   */
  def maxHtlcAmount(nodeParams: NodeParams, commitments: Commitments): MilliSatoshi = {
    if (!commitments.announceChannel) {
      // The channel is private, let's not change the channel update needlessly.
      return commitments.params.maxHtlcAmount
    }
    for (balanceThreshold <- nodeParams.channelConf.balanceThresholds) {
      if (commitments.availableBalanceForSend <= balanceThreshold.available) {
        return balanceThreshold.maxHtlcAmount.toMilliSatoshi.max(commitments.params.remoteParams.htlcMinimum).min(commitments.params.maxHtlcAmount)
      }
    }
    commitments.params.maxHtlcAmount
  }

  def getRelayFees(nodeParams: NodeParams, remoteNodeId: PublicKey, announceChannel: Boolean): RelayFees = {
    val defaultFees = nodeParams.relayParams.defaultFees(announceChannel)
    nodeParams.db.peers.getRelayFees(remoteNodeId).getOrElse(defaultFees)
  }

  object Funding {

    def makeFundingPubKeyScript(localFundingKey: PublicKey, remoteFundingKey: PublicKey): ByteVector = write(pay2wsh(multiSig2of2(localFundingKey, remoteFundingKey)))

    def makeFundingInputInfo(fundingTxId: TxId, fundingTxOutputIndex: Int, fundingSatoshis: Satoshi, fundingPubkey1: PublicKey, fundingPubkey2: PublicKey): InputInfo.SegwitInput = {
      val fundingScript = multiSig2of2(fundingPubkey1, fundingPubkey2)
      val fundingTxOut = TxOut(fundingSatoshis, pay2wsh(fundingScript))
      InputInfo.SegwitInput(OutPoint(fundingTxId, fundingTxOutputIndex), fundingTxOut, write(fundingScript))
    }

    /**
     * Creates both sides' first commitment transaction.
     *
     * @return (localSpec, localTx, remoteSpec, remoteTx)
     */
    def makeFirstCommitTxs(params: ChannelParams,
                           localFundingAmount: Satoshi, remoteFundingAmount: Satoshi,
                           localPushAmount: MilliSatoshi, remotePushAmount: MilliSatoshi,
                           commitTxFeerate: FeeratePerKw,
                           fundingTxId: TxId, fundingTxOutputIndex: Int,
                           localFundingKey: PrivateKey, remoteFundingPubKey: PublicKey,
                           localCommitKeys: LocalCommitmentKeys, remoteCommitKeys: RemoteCommitmentKeys): Either[ChannelException, (CommitmentSpec, CommitTx, CommitmentSpec, CommitTx)] = {
      makeCommitTxs(params,
        fundingAmount = localFundingAmount + remoteFundingAmount,
        toLocal = localFundingAmount.toMilliSatoshi - localPushAmount + remotePushAmount,
        toRemote = remoteFundingAmount.toMilliSatoshi + localPushAmount - remotePushAmount,
        localHtlcs = Set.empty,
        commitTxFeerate,
        fundingTxIndex = 0,
        fundingTxId, fundingTxOutputIndex,
        localFundingKey, remoteFundingPubKey,
        localCommitKeys, remoteCommitKeys,
        localCommitmentIndex = 0, remoteCommitmentIndex = 0).map {
        case (localSpec, localCommit, remoteSpec, remoteCommit, _) => (localSpec, localCommit, remoteSpec, remoteCommit)
      }
    }

    /**
     * This creates commitment transactions for both sides at an arbitrary `commitmentIndex` and with (optional) `htlc`
     * outputs. This function should only be used when commitments are synchronized (local and remote htlcs match).
     */
    def makeCommitTxs(params: ChannelParams,
                      fundingAmount: Satoshi,
                      toLocal: MilliSatoshi, toRemote: MilliSatoshi,
                      localHtlcs: Set[DirectedHtlc],
                      commitTxFeerate: FeeratePerKw,
                      fundingTxIndex: Long,
                      fundingTxId: TxId, fundingTxOutputIndex: Int,
                      localFundingKey: PrivateKey, remoteFundingPubKey: PublicKey,
                      localCommitKeys: LocalCommitmentKeys, remoteCommitKeys: RemoteCommitmentKeys,
                      localCommitmentIndex: Long, remoteCommitmentIndex: Long): Either[ChannelException, (CommitmentSpec, CommitTx, CommitmentSpec, CommitTx, Seq[HtlcTx])] = {
      val localSpec = CommitmentSpec(localHtlcs, commitTxFeerate, toLocal = toLocal, toRemote = toRemote)
      val remoteSpec = CommitmentSpec(localHtlcs.map(_.opposite), commitTxFeerate, toLocal = toRemote, toRemote = toLocal)

      if (!params.localParams.paysCommitTxFees) {
        // They are responsible for paying the commitment transaction fee: we need to make sure they can afford it!
        // Note that the reserve may not always be met: we could be using dual funding with a large funding amount on
        // our side and a small funding amount on their side. But we shouldn't care as long as they can pay the fees for
        // the commitment transaction.
        val fees = commitTxTotalCost(params.remoteParams.dustLimit, remoteSpec, params.commitmentFormat)
        val missing = fees - toRemote.truncateToSatoshi
        if (missing > 0.sat) {
          return Left(CannotAffordFirstCommitFees(params.channelId, missing = missing, fees = fees))
        }
      }

      val commitmentInput = makeFundingInputInfo(fundingTxId, fundingTxOutputIndex, fundingAmount, localFundingKey.publicKey, remoteFundingPubKey)
      val (localCommitTx, _) = Commitment.makeLocalTxs(params, localCommitKeys, localCommitmentIndex, localFundingKey, remoteFundingPubKey, commitmentInput, localSpec)
      val (remoteCommitTx, htlcTxs) = Commitment.makeRemoteTxs(params, remoteCommitKeys, remoteCommitmentIndex, localFundingKey, remoteFundingPubKey, commitmentInput, remoteSpec)
      val sortedHtlcTxs = htlcTxs.sortBy(_.input.outPoint.index)
      Right(localSpec, localCommitTx, remoteSpec, remoteCommitTx, sortedHtlcTxs)
    }

  }

  object Syncing {

    // @formatter:off
    sealed trait SyncResult
    object SyncResult {
      case class Success(retransmit: Seq[LightningMessage]) extends SyncResult
      sealed trait Failure extends SyncResult
      case class LocalLateProven(ourLocalCommitmentNumber: Long, theirRemoteCommitmentNumber: Long) extends Failure
      case class LocalLateUnproven(ourRemoteCommitmentNumber: Long, theirLocalCommitmentNumber: Long) extends Failure
      case class RemoteLying(ourLocalCommitmentNumber: Long, theirRemoteCommitmentNumber: Long, invalidPerCommitmentSecret: PrivateKey) extends Failure
      case object RemoteLate extends Failure
    }
    // @formatter:on

    /**
     * Check whether we are in sync with our peer.
     */
    def checkSync(channelKeys: ChannelKeys, commitments: Commitments, remoteChannelReestablish: ChannelReestablish): SyncResult = {

      // This is done in two steps:
      // - step 1: we check our local commitment
      // - step 2: we check the remote commitment
      // step 2 depends on step 1 because we need to preserve ordering between commit_sig and revocation

      // step 2: we check the remote commitment
      def checkRemoteCommit(remoteChannelReestablish: ChannelReestablish, retransmitRevocation_opt: Option[RevokeAndAck]): SyncResult = {
        commitments.remoteNextCommitInfo match {
          case Left(waitingForRevocation) if remoteChannelReestablish.nextLocalCommitmentNumber == commitments.nextRemoteCommitIndex =>
            // we just sent a new commit_sig but they didn't receive it
            // we resend the same updates and the same sig, and preserve the same ordering
            val signedUpdates = commitments.changes.localChanges.signed
            val commitSigs = commitments.active.flatMap(_.nextRemoteCommit_opt).map(_.sig)
            retransmitRevocation_opt match {
              case None =>
                SyncResult.Success(retransmit = signedUpdates ++ commitSigs)
              case Some(revocation) if commitments.localCommitIndex > waitingForRevocation.sentAfterLocalCommitIndex =>
                SyncResult.Success(retransmit = signedUpdates ++ commitSigs ++ Seq(revocation))
              case Some(revocation) =>
                SyncResult.Success(retransmit = Seq(revocation) ++ signedUpdates ++ commitSigs)
            }
          case Left(_) if remoteChannelReestablish.nextLocalCommitmentNumber == (commitments.nextRemoteCommitIndex + 1) =>
            // we just sent a new commit_sig, they have received it but we haven't received their revocation
            SyncResult.Success(retransmit = retransmitRevocation_opt.toSeq)
          case Left(_) if remoteChannelReestablish.nextLocalCommitmentNumber < commitments.nextRemoteCommitIndex =>
            // they are behind
            SyncResult.RemoteLate
          case Left(_) =>
            // we are behind
            SyncResult.LocalLateUnproven(
              ourRemoteCommitmentNumber = commitments.nextRemoteCommitIndex,
              theirLocalCommitmentNumber = remoteChannelReestablish.nextLocalCommitmentNumber - 1
            )
          case Right(_) if remoteChannelReestablish.nextLocalCommitmentNumber == (commitments.remoteCommitIndex + 1) =>
            // they have acknowledged the last commit_sig we sent
            SyncResult.Success(retransmit = retransmitRevocation_opt.toSeq)
          case Right(_) if remoteChannelReestablish.nextLocalCommitmentNumber == commitments.remoteCommitIndex && remoteChannelReestablish.nextFundingTxId_opt.nonEmpty =>
            // they haven't received the commit_sig we sent as part of signing a splice transaction
            // we will retransmit it before exchanging tx_signatures
            SyncResult.Success(retransmit = retransmitRevocation_opt.toSeq)
          case Right(_) if remoteChannelReestablish.nextLocalCommitmentNumber < (commitments.remoteCommitIndex + 1) =>
            // they are behind
            SyncResult.RemoteLate
          case Right(_) =>
            // we are behind
            SyncResult.LocalLateUnproven(
              ourRemoteCommitmentNumber = commitments.remoteCommitIndex,
              theirLocalCommitmentNumber = remoteChannelReestablish.nextLocalCommitmentNumber - 1
            )
        }
      }

      // step 1: we check our local commitment
      if (commitments.localCommitIndex == remoteChannelReestablish.nextRemoteRevocationNumber) {
        // our local commitment is in sync, let's check the remote commitment
        checkRemoteCommit(remoteChannelReestablish, retransmitRevocation_opt = None)
      } else if (commitments.localCommitIndex == remoteChannelReestablish.nextRemoteRevocationNumber + 1) {
        // they just sent a new commit_sig, we have received it but they didn't receive our revocation
        val localPerCommitmentSecret = channelKeys.commitmentSecret(commitments.localCommitIndex - 1)
        val localNextPerCommitmentPoint = channelKeys.commitmentPoint(commitments.localCommitIndex + 1)
        val revocation = RevokeAndAck(
          channelId = commitments.channelId,
          perCommitmentSecret = localPerCommitmentSecret,
          nextPerCommitmentPoint = localNextPerCommitmentPoint
        )
        checkRemoteCommit(remoteChannelReestablish, retransmitRevocation_opt = Some(revocation))
      } else if (commitments.localCommitIndex > remoteChannelReestablish.nextRemoteRevocationNumber + 1) {
        SyncResult.RemoteLate
      } else {
        // if next_remote_revocation_number is greater than our local commitment index, it means that either we are using an outdated commitment, or they are lying
        // but first we need to make sure that the last per_commitment_secret that they claim to have received from us is correct for that next_remote_revocation_number minus 1
        if (channelKeys.commitmentSecret(remoteChannelReestablish.nextRemoteRevocationNumber - 1) == remoteChannelReestablish.yourLastPerCommitmentSecret) {
          SyncResult.LocalLateProven(
            ourLocalCommitmentNumber = commitments.localCommitIndex,
            theirRemoteCommitmentNumber = remoteChannelReestablish.nextRemoteRevocationNumber
          )
        } else {
          // they lied! the last per_commitment_secret they claimed to have received from us is invalid
          SyncResult.RemoteLying(
            ourLocalCommitmentNumber = commitments.localCommitIndex,
            theirRemoteCommitmentNumber = remoteChannelReestablish.nextRemoteRevocationNumber,
            invalidPerCommitmentSecret = remoteChannelReestablish.yourLastPerCommitmentSecret
          )
        }
      }
    }

  }

  object Closing {

    // @formatter:off
    sealed trait ClosingType
    case class MutualClose(tx: ClosingTx) extends ClosingType
    case class LocalClose(localCommit: LocalCommit, localCommitPublished: LocalCommitPublished) extends ClosingType
    sealed trait RemoteClose extends ClosingType { def remoteCommit: RemoteCommit; def remoteCommitPublished: RemoteCommitPublished }
    case class CurrentRemoteClose(remoteCommit: RemoteCommit, remoteCommitPublished: RemoteCommitPublished) extends RemoteClose
    case class NextRemoteClose(remoteCommit: RemoteCommit, remoteCommitPublished: RemoteCommitPublished) extends RemoteClose
    case class RecoveryClose(remoteCommitPublished: RemoteCommitPublished) extends ClosingType
    case class RevokedClose(revokedCommitPublished: RevokedCommitPublished) extends ClosingType
    // @formatter:on

    /**
     * Indicates whether local has anything at stake in this channel
     *
     * @return true if channel was never open, or got closed immediately, had never any htlcs and local never had a positive balance
     */
    def nothingAtStake(data: PersistentChannelData): Boolean = data match {
      case _: ChannelDataWithoutCommitments => true
      case data: ChannelDataWithCommitments => data.commitments.active.forall(nothingAtStake)
    }

    def nothingAtStake(commitment: Commitment): Boolean =
      commitment.localCommit.index == 0 &&
        commitment.localCommit.spec.toLocal == 0.msat &&
        commitment.remoteCommit.index == 0 &&
        commitment.remoteCommit.spec.toRemote == 0.msat &&
        commitment.nextRemoteCommit_opt.isEmpty

    /**
     * As soon as a tx spending the funding tx has reached min_depth, we know what the closing type will be, before
     * the whole closing process finishes (e.g. there may still be delayed or unconfirmed child transactions). It can
     * save us from attempting to publish some transactions.
     *
     * Note that we can't tell for mutual close before it is already final, because only one tx needs to be confirmed.
     *
     * @param closing channel state data
     * @return the channel closing type, if applicable
     */
    def isClosingTypeAlreadyKnown(closing: DATA_CLOSING): Option[ClosingType] = {
      closing match {
        case _ if closing.localCommitPublished.exists(_.isConfirmed) =>
          Some(LocalClose(closing.commitments.latest.localCommit, closing.localCommitPublished.get))
        case _ if closing.remoteCommitPublished.exists(_.isConfirmed) =>
          Some(CurrentRemoteClose(closing.commitments.latest.remoteCommit, closing.remoteCommitPublished.get))
        case _ if closing.nextRemoteCommitPublished.exists(_.isConfirmed) =>
          Some(NextRemoteClose(closing.commitments.latest.nextRemoteCommit_opt.get.commit, closing.nextRemoteCommitPublished.get))
        case _ if closing.futureRemoteCommitPublished.exists(_.isConfirmed) =>
          Some(RecoveryClose(closing.futureRemoteCommitPublished.get))
        case _ if closing.revokedCommitPublished.exists(_.isConfirmed) =>
          Some(RevokedClose(closing.revokedCommitPublished.find(_.isConfirmed).get))
        case _ => None // we don't know yet what the closing type will be
      }
    }

    /**
     * Checks if a channel is closed (i.e. its closing tx has been confirmed)
     *
     * @param data                      channel state data
     * @param additionalConfirmedTx_opt additional confirmed transaction; we need this for the mutual close scenario
     *                                  because we don't store the closing tx in the channel state
     * @return the channel closing type, if applicable
     */
    def isClosed(data: PersistentChannelData, additionalConfirmedTx_opt: Option[Transaction]): Option[ClosingType] = data match {
      case closing: DATA_CLOSING if additionalConfirmedTx_opt.exists(closing.mutualClosePublished.map(_.tx).contains) =>
        val closingTx = closing.mutualClosePublished.find(_.tx.txid == additionalConfirmedTx_opt.get.txid).get
        Some(MutualClose(closingTx))
      case closing: DATA_CLOSING if closing.localCommitPublished.exists(_.isDone) =>
        Some(LocalClose(closing.commitments.latest.localCommit, closing.localCommitPublished.get))
      case closing: DATA_CLOSING if closing.remoteCommitPublished.exists(_.isDone) =>
        Some(CurrentRemoteClose(closing.commitments.latest.remoteCommit, closing.remoteCommitPublished.get))
      case closing: DATA_CLOSING if closing.nextRemoteCommitPublished.exists(_.isDone) =>
        Some(NextRemoteClose(closing.commitments.latest.nextRemoteCommit_opt.get.commit, closing.nextRemoteCommitPublished.get))
      case closing: DATA_CLOSING if closing.futureRemoteCommitPublished.exists(_.isDone) =>
        Some(RecoveryClose(closing.futureRemoteCommitPublished.get))
      case closing: DATA_CLOSING if closing.revokedCommitPublished.exists(_.isDone) =>
        Some(RevokedClose(closing.revokedCommitPublished.find(_.isDone).get))
      case _ => None
    }

    object MutualClose {

      def generateFinalScriptPubKey(wallet: OnChainPubkeyCache, allowAnySegwit: Boolean, renew: Boolean = true): ByteVector = {
        if (!allowAnySegwit) {
          // If our peer only supports segwit v0, we cannot let bitcoind choose the address type: we always use p2wpkh.
          val finalPubKey = wallet.getP2wpkhPubkey(renew)
          Script.write(Script.pay2wpkh(finalPubKey))
        } else {
          Script.write(wallet.getReceivePublicKeyScript(renew))
        }
      }

      def isValidFinalScriptPubkey(scriptPubKey: ByteVector, allowAnySegwit: Boolean, allowOpReturn: Boolean): Boolean = {
        Try(Script.parse(scriptPubKey)) match {
          case Success(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pubkeyHash, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) if pubkeyHash.size == 20 => true
          case Success(OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil) if scriptHash.size == 20 => true
          case Success(OP_0 :: OP_PUSHDATA(pubkeyHash, _) :: Nil) if pubkeyHash.size == 20 => true
          case Success(OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil) if scriptHash.size == 32 => true
          case Success((OP_1 | OP_2 | OP_3 | OP_4 | OP_5 | OP_6 | OP_7 | OP_8 | OP_9 | OP_10 | OP_11 | OP_12 | OP_13 | OP_14 | OP_15 | OP_16) :: OP_PUSHDATA(program, _) :: Nil) if allowAnySegwit && 2 <= program.length && program.length <= 40 => true
          case Success(OP_RETURN :: OP_PUSHDATA(data, code) :: Nil) if allowOpReturn => OP_PUSHDATA.isMinimal(data, code) && data.size >= 6 && data.size <= 80
          case _ => false
        }
      }

      def firstClosingFee(commitment: FullCommitment, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, feerates: ClosingFeerates)(implicit log: LoggingAdapter): ClosingFees = {
        // this is just to estimate the weight, it depends on size of the pubkey scripts
        val dummyClosingTx = Transactions.makeClosingTx(commitment.commitInput, localScriptPubkey, remoteScriptPubkey, commitment.localParams.paysClosingFees, Satoshi(0), Satoshi(0), commitment.localCommit.spec)
        val closingWeight = Transaction.weight(Transactions.addSigs(dummyClosingTx, Transactions.PlaceHolderPubKey, commitment.remoteFundingPubKey, Transactions.PlaceHolderSig, Transactions.PlaceHolderSig).tx)
        log.info(s"using feerates=$feerates for initial closing tx")
        feerates.computeFees(closingWeight)
      }

      def firstClosingFee(commitment: FullCommitment, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, feerates: FeeratesPerKw, onChainFeeConf: OnChainFeeConf)(implicit log: LoggingAdapter): ClosingFees = {
        val requestedFeerate = onChainFeeConf.getClosingFeerate(feerates)
        val preferredFeerate = commitment.params.commitmentFormat match {
          case DefaultCommitmentFormat =>
            // we "MUST set fee_satoshis less than or equal to the base fee of the final commitment transaction"
            requestedFeerate.min(commitment.localCommit.spec.commitTxFeerate)
          case _: AnchorOutputsCommitmentFormat => requestedFeerate
        }
        // NB: we choose a minimum fee that ensures the tx will easily propagate while allowing low fees since we can
        // always use CPFP to speed up confirmation if necessary.
        val closingFeerates = ClosingFeerates(preferredFeerate, preferredFeerate.min(ConfirmationPriority.Slow.getFeerate(feerates)), preferredFeerate * 2)
        firstClosingFee(commitment, localScriptPubkey, remoteScriptPubkey, closingFeerates)
      }

      def nextClosingFee(localClosingFee: Satoshi, remoteClosingFee: Satoshi): Satoshi = ((localClosingFee + remoteClosingFee) / 4) * 2

      def makeFirstClosingTx(channelKeys: ChannelKeys, commitment: FullCommitment, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, feerates: FeeratesPerKw, onChainFeeConf: OnChainFeeConf, closingFeerates_opt: Option[ClosingFeerates])(implicit log: LoggingAdapter): (ClosingTx, ClosingSigned) = {
        val closingFees = closingFeerates_opt match {
          case Some(closingFeerates) => firstClosingFee(commitment, localScriptPubkey, remoteScriptPubkey, closingFeerates)
          case None => firstClosingFee(commitment, localScriptPubkey, remoteScriptPubkey, feerates, onChainFeeConf)
        }
        makeClosingTx(channelKeys, commitment, localScriptPubkey, remoteScriptPubkey, closingFees)
      }

      def makeClosingTx(channelKeys: ChannelKeys, commitment: FullCommitment, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, closingFees: ClosingFees)(implicit log: LoggingAdapter): (ClosingTx, ClosingSigned) = {
        log.debug("making closing tx with closing fee={} and commitments:\n{}", closingFees.preferred, commitment.specs2String)
        val dustLimit = commitment.localParams.dustLimit.max(commitment.remoteParams.dustLimit)
        val closingTx = Transactions.makeClosingTx(commitment.commitInput, localScriptPubkey, remoteScriptPubkey, commitment.localParams.paysClosingFees, dustLimit, closingFees.preferred, commitment.localCommit.spec)
        val localClosingSig = closingTx.sign(channelKeys.fundingKey(commitment.fundingTxIndex), TxOwner.Local, commitment.params.commitmentFormat, Map.empty)
        val closingSigned = ClosingSigned(commitment.channelId, closingFees.preferred, localClosingSig, TlvStream(ClosingSignedTlv.FeeRange(closingFees.min, closingFees.max)))
        log.debug(s"signed closing txid=${closingTx.tx.txid} with closing fee=${closingSigned.feeSatoshis}")
        log.debug(s"closingTxid=${closingTx.tx.txid} closingTx=${closingTx.tx}}")
        (closingTx, closingSigned)
      }

      def checkClosingSignature(channelKeys: ChannelKeys, commitment: FullCommitment, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, remoteClosingFee: Satoshi, remoteClosingSig: ByteVector64)(implicit log: LoggingAdapter): Either[ChannelException, (ClosingTx, ClosingSigned)] = {
        val (closingTx, closingSigned) = makeClosingTx(channelKeys, commitment, localScriptPubkey, remoteScriptPubkey, ClosingFees(remoteClosingFee, remoteClosingFee, remoteClosingFee))
        if (checkClosingDustAmounts(closingTx)) {
          val signedClosingTx = Transactions.addSigs(closingTx, channelKeys.fundingKey(commitment.fundingTxIndex).publicKey, commitment.remoteFundingPubKey, closingSigned.signature, remoteClosingSig)
          Transactions.checkSpendable(signedClosingTx) match {
            case Success(_) => Right(signedClosingTx, closingSigned)
            case _ => Left(InvalidCloseSignature(commitment.channelId, signedClosingTx.tx.txid))
          }
        } else {
          Left(InvalidCloseAmountBelowDust(commitment.channelId, closingTx.tx.txid))
        }
      }

      /** We are the closer: we sign closing transactions for which we pay the fees. */
      def makeSimpleClosingTx(currentBlockHeight: BlockHeight, channelKeys: ChannelKeys, commitment: FullCommitment, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, feerate: FeeratePerKw): Either[ChannelException, (ClosingTxs, ClosingComplete)] = {
        // We must convert the feerate to a fee: we must build dummy transactions to compute their weight.
        val closingFee = {
          val dummyClosingTxs = Transactions.makeSimpleClosingTxs(commitment.commitInput, commitment.localCommit.spec, SimpleClosingTxFee.PaidByUs(0 sat), currentBlockHeight.toLong, localScriptPubkey, remoteScriptPubkey)
          dummyClosingTxs.preferred_opt match {
            case Some(dummyTx) =>
              val dummySignedTx = Transactions.addSigs(dummyTx, Transactions.PlaceHolderPubKey, Transactions.PlaceHolderPubKey, Transactions.PlaceHolderSig, Transactions.PlaceHolderSig)
              SimpleClosingTxFee.PaidByUs(Transactions.weight2fee(feerate, dummySignedTx.tx.weight()))
            case None => return Left(CannotGenerateClosingTx(commitment.channelId))
          }
        }
        // Now that we know the fee we're ready to pay, we can create our closing transactions.
        val closingTxs = Transactions.makeSimpleClosingTxs(commitment.commitInput, commitment.localCommit.spec, closingFee, currentBlockHeight.toLong, localScriptPubkey, remoteScriptPubkey)
        closingTxs.preferred_opt match {
          case Some(closingTx) if closingTx.fee > 0.sat => ()
          case _ => return Left(CannotGenerateClosingTx(commitment.channelId))
        }
        val localFundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
        val closingComplete = ClosingComplete(commitment.channelId, localScriptPubkey, remoteScriptPubkey, closingFee.fee, currentBlockHeight.toLong, TlvStream(Set(
          closingTxs.localAndRemote_opt.map(tx => ClosingTlv.CloserAndCloseeOutputs(tx.sign(localFundingKey, TxOwner.Local, commitment.params.commitmentFormat, Map.empty))),
          closingTxs.localOnly_opt.map(tx => ClosingTlv.CloserOutputOnly(tx.sign(localFundingKey, TxOwner.Local, commitment.params.commitmentFormat, Map.empty))),
          closingTxs.remoteOnly_opt.map(tx => ClosingTlv.CloseeOutputOnly(tx.sign(localFundingKey, TxOwner.Local, commitment.params.commitmentFormat, Map.empty))),
        ).flatten[ClosingTlv]))
        Right(closingTxs, closingComplete)
      }

      /**
       * We are the closee: we choose one of the closer's transactions and sign it back.
       *
       * Callers should ignore failures: since the protocol is fully asynchronous, failures here simply mean that they
       * are not using our latest script (race condition between our closing_complete and theirs).
       */
      def signSimpleClosingTx(channelKeys: ChannelKeys, commitment: FullCommitment, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, closingComplete: ClosingComplete): Either[ChannelException, (ClosingTx, ClosingSig)] = {
        val closingFee = SimpleClosingTxFee.PaidByThem(closingComplete.fees)
        val closingTxs = Transactions.makeSimpleClosingTxs(commitment.commitInput, commitment.localCommit.spec, closingFee, closingComplete.lockTime, localScriptPubkey, remoteScriptPubkey)
        // If our output isn't dust, they must provide a signature for a transaction that includes it.
        // Note that we're the closee, so we look for signatures including the closee output.
        (closingTxs.localAndRemote_opt, closingTxs.localOnly_opt) match {
          case (Some(_), Some(_)) if closingComplete.closerAndCloseeOutputsSig_opt.isEmpty && closingComplete.closeeOutputOnlySig_opt.isEmpty => return Left(MissingCloseSignature(commitment.channelId))
          case (Some(_), None) if closingComplete.closerAndCloseeOutputsSig_opt.isEmpty => return Left(MissingCloseSignature(commitment.channelId))
          case (None, Some(_)) if closingComplete.closeeOutputOnlySig_opt.isEmpty => return Left(MissingCloseSignature(commitment.channelId))
          case _ => ()
        }
        // We choose the closing signature that matches our preferred closing transaction.
        val closingTxsWithSigs = Seq(
          closingComplete.closerAndCloseeOutputsSig_opt.flatMap(remoteSig => closingTxs.localAndRemote_opt.map(tx => (tx, remoteSig, localSig => ClosingTlv.CloserAndCloseeOutputs(localSig)))),
          closingComplete.closeeOutputOnlySig_opt.flatMap(remoteSig => closingTxs.localOnly_opt.map(tx => (tx, remoteSig, localSig => ClosingTlv.CloseeOutputOnly(localSig)))),
          closingComplete.closerOutputOnlySig_opt.flatMap(remoteSig => closingTxs.remoteOnly_opt.map(tx => (tx, remoteSig, localSig => ClosingTlv.CloserOutputOnly(localSig)))),
        ).flatten
        closingTxsWithSigs.headOption match {
          case Some((closingTx, remoteSig, sigToTlv)) =>
            val localFundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
            val localSig = closingTx.sign(localFundingKey, TxOwner.Local, commitment.params.commitmentFormat, Map.empty)
            val signedClosingTx = Transactions.addSigs(closingTx, localFundingKey.publicKey, commitment.remoteFundingPubKey, localSig, remoteSig)
            Transactions.checkSpendable(signedClosingTx) match {
              case Failure(_) => Left(InvalidCloseSignature(commitment.channelId, signedClosingTx.tx.txid))
              case Success(_) => Right(signedClosingTx, ClosingSig(commitment.channelId, remoteScriptPubkey, localScriptPubkey, closingComplete.fees, closingComplete.lockTime, TlvStream(sigToTlv(localSig))))
            }
          case None => Left(MissingCloseSignature(commitment.channelId))
        }
      }

      /**
       * We are the closer: they sent us their signature so we should now have a fully signed closing transaction.
       *
       * Callers should ignore failures: since the protocol is fully asynchronous, failures here simply mean that we
       * sent another closing_complete before receiving their closing_sig, which is now obsolete: we ignore it and wait
       * for their next closing_sig that will match our latest closing_complete.
       */
      def receiveSimpleClosingSig(channelKeys: ChannelKeys, commitment: FullCommitment, closingTxs: ClosingTxs, closingSig: ClosingSig): Either[ChannelException, ClosingTx] = {
        val closingTxsWithSig = Seq(
          closingSig.closerAndCloseeOutputsSig_opt.flatMap(sig => closingTxs.localAndRemote_opt.map(tx => (tx, sig))),
          closingSig.closerOutputOnlySig_opt.flatMap(sig => closingTxs.localOnly_opt.map(tx => (tx, sig))),
          closingSig.closeeOutputOnlySig_opt.flatMap(sig => closingTxs.remoteOnly_opt.map(tx => (tx, sig))),
        ).flatten
        closingTxsWithSig.headOption match {
          case Some((closingTx, remoteSig)) =>
            val localFundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
            val localSig = closingTx.sign(localFundingKey, TxOwner.Local, commitment.params.commitmentFormat, Map.empty)
            val signedClosingTx = Transactions.addSigs(closingTx, localFundingKey.publicKey, commitment.remoteFundingPubKey, localSig, remoteSig)
            Transactions.checkSpendable(signedClosingTx) match {
              case Failure(_) => Left(InvalidCloseSignature(commitment.channelId, signedClosingTx.tx.txid))
              case Success(_) => Right(signedClosingTx)
            }
          case None => Left(MissingCloseSignature(commitment.channelId))
        }
      }

      /**
       * Check that all closing outputs are above bitcoin's dust limit for their script type, otherwise there is a risk
       * that the closing transaction will not be relayed to miners' mempool and will not confirm.
       * The various dust limits are detailed in https://github.com/lightningnetwork/lightning-rfc/blob/master/03-transactions.md#dust-limits
       */
      def checkClosingDustAmounts(closingTx: ClosingTx): Boolean = {
        closingTx.tx.txOut.forall(txOut => txOut.amount >= Scripts.dustLimit(txOut.publicKeyScript))
      }

    }

    /** Wraps transaction generation in a Try and filters failures to avoid one transaction negatively impacting a whole commitment. */
    private def withTxGenerationLog[T <: TransactionWithInputInfo](desc: String, logSuccess: Boolean = true, logSkipped: Boolean = true, logFailure: Boolean = true)(generateTx: => Either[TxGenerationSkipped, T])(implicit log: LoggingAdapter): Option[T] = {
      Try {
        generateTx
      } match {
        case Success(Right(txinfo)) =>
          if (logSuccess) log.info(s"tx generation success: desc=$desc txid=${txinfo.tx.txid} amount=${txinfo.tx.txOut.map(_.amount).sum} tx=${txinfo.tx}")
          Some(txinfo)
        case Success(Left(skipped)) =>
          if (logSkipped) log.info(s"tx generation skipped: desc=$desc reason: ${skipped.toString}")
          None
        case Failure(t) =>
          if (logFailure) log.warning(s"tx generation failure: desc=$desc reason: ${t.getMessage}")
          None
      }
    }

    /** Compute the fee paid by a commitment transaction. */
    def commitTxFee(commitInput: InputInfo, commitTx: Transaction, localPaysCommitTxFees: Boolean): Satoshi = {
      require(commitTx.txIn.size == 1, "transaction must have only one input")
      require(commitTx.txIn.exists(txIn => txIn.outPoint == commitInput.outPoint), "transaction must spend the funding output")
      if (localPaysCommitTxFees) commitInput.txOut.amount - commitTx.txOut.map(_.amount).sum else 0 sat
    }

    /**
     * This function checks if the proposed confirmation target is more aggressive than whatever confirmation target
     * we previously had. Note that absolute targets are always considered more aggressive than relative targets.
     */
    private def shouldUpdateAnchorTxs(anchorTxs: List[ClaimAnchorOutputTx], confirmationTarget: ConfirmationTarget): Boolean = {
      anchorTxs
        .collect { case tx: ClaimAnchorOutputTx => tx.confirmationTarget }
        .forall {
          case ConfirmationTarget.Absolute(current) => confirmationTarget match {
            case ConfirmationTarget.Absolute(proposed) => proposed < current
            case _: ConfirmationTarget.Priority => false
          }
          case ConfirmationTarget.Priority(current) => confirmationTarget match {
            case _: ConfirmationTarget.Absolute => true
            case ConfirmationTarget.Priority(proposed) => current < proposed
          }
        }
    }

    object LocalClose {

      /**
       * Claim all the HTLCs that we've received from our current commit tx. This will be done using 2nd stage HTLC transactions.
       *
       * @param commitment our commitment data, which includes payment preimages
       * @return a list of transactions (one per output of the commit tx that we can claim)
       */
      def claimCommitTxOutputs(channelKeys: ChannelKeys, commitment: FullCommitment, commitTx: Transaction, feerates: FeeratesPerKw, onChainFeeConf: OnChainFeeConf, finalScriptPubKey: ByteVector)(implicit log: LoggingAdapter): LocalCommitPublished = {
        require(commitment.localCommit.commitTxAndRemoteSig.commitTx.tx.txid == commitTx.txid, "txid mismatch, provided tx is not the current local commit tx")
        val fundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
        val commitmentKeys = commitment.localKeys(channelKeys)
        val feeratePerKwDelayed = onChainFeeConf.getClosingFeerate(feerates)

        // first we will claim our main output as soon as the delay is over
        val mainDelayedTx = withTxGenerationLog("local-main-delayed") {
          Transactions.makeClaimLocalDelayedOutputTx(commitmentKeys, commitTx, commitment.localParams.dustLimit, commitment.remoteParams.toSelfDelay, finalScriptPubKey, feeratePerKwDelayed).map(claimDelayed => {
            val sig = claimDelayed.sign(commitmentKeys.ourDelayedPaymentKey, TxOwner.Local, commitment.params.commitmentFormat, Map.empty)
            Transactions.addSigs(claimDelayed, sig)
          })
        }

        val htlcTxs: Map[OutPoint, Option[HtlcTx]] = claimHtlcOutputs(commitmentKeys, commitment)

        val lcp = LocalCommitPublished(
          commitTx = commitTx,
          claimMainDelayedOutputTx = mainDelayedTx,
          htlcTxs = htlcTxs,
          claimHtlcDelayedTxs = Nil, // we will claim these once the htlc txs are confirmed
          claimAnchorTxs = Nil,
          irrevocablySpent = Map.empty
        )
        val spendAnchors = htlcTxs.nonEmpty || onChainFeeConf.spendAnchorWithoutHtlcs
        if (spendAnchors) {
          // If we don't have pending HTLCs, we don't have funds at risk, so we can aim for a slower confirmation.
          val confirmCommitBefore = htlcTxs.values.flatten.map(htlcTx => htlcTx.confirmationTarget).minByOption(_.confirmBefore).getOrElse(ConfirmationTarget.Priority(onChainFeeConf.feeTargets.closing))
          claimAnchors(fundingKey, lcp, confirmCommitBefore)
        } else {
          lcp
        }
      }

      def claimAnchors(anchorKey: PrivateKey, lcp: LocalCommitPublished, confirmationTarget: ConfirmationTarget)(implicit log: LoggingAdapter): LocalCommitPublished = {
        if (shouldUpdateAnchorTxs(lcp.claimAnchorTxs, confirmationTarget)) {
          val claimAnchorTxs = List(
            withTxGenerationLog("local-anchor") {
              Transactions.makeClaimAnchorOutputTx(anchorKey, lcp.commitTx, confirmationTarget)
            },
          ).flatten
          lcp.copy(claimAnchorTxs = claimAnchorTxs)
        } else {
          lcp
        }
      }

      /**
       * Claim the outputs of a local commit tx corresponding to HTLCs.
       */
      def claimHtlcOutputs(commitKeys: LocalCommitmentKeys, commitment: FullCommitment)(implicit log: LoggingAdapter): Map[OutPoint, Option[HtlcTx]] = {
        // We collect all the preimages we wanted to reveal to our peer.
        val hash2Preimage: Map[ByteVector32, ByteVector32] = commitment.changes.localChanges.all.collect { case u: UpdateFulfillHtlc => u.paymentPreimage }.map(r => Crypto.sha256(r) -> r).toMap
        // We collect incoming HTLCs that we started failing but didn't cross-sign.
        val failedIncomingHtlcs: Set[Long] = commitment.changes.localChanges.all.collect {
          case u: UpdateFailHtlc => u.id
          case u: UpdateFailMalformedHtlc => u.id
        }.toSet
        // We collect incoming HTLCs that we haven't relayed: they may have been signed by our peer, but we haven't
        // received their revocation yet.
        val nonRelayedIncomingHtlcs: Set[Long] = commitment.changes.remoteChanges.all.collect { case add: UpdateAddHtlc => add.id }.toSet

        commitment.localCommit.htlcTxsAndRemoteSigs.collect {
          case HtlcTxAndRemoteSig(txInfo@HtlcSuccessTx(_, _, paymentHash, _, _), remoteSig) =>
            if (hash2Preimage.contains(paymentHash)) {
              // We immediately spend incoming htlcs for which we have the preimage.
              Some(txInfo.input.outPoint -> withTxGenerationLog("htlc-success") {
                val localSig = txInfo.sign(commitKeys.ourHtlcKey, TxOwner.Local, commitment.params.commitmentFormat, Map.empty)
                Right(Transactions.addSigs(txInfo, localSig, remoteSig, hash2Preimage(paymentHash), commitment.params.commitmentFormat))
              })
            } else if (failedIncomingHtlcs.contains(txInfo.htlcId)) {
              // We can ignore incoming htlcs that we started failing: our peer will claim them after the timeout.
              // We don't track those outputs because we want to move to the CLOSED state even if our peer never claims them.
              None
            } else if (nonRelayedIncomingHtlcs.contains(txInfo.htlcId)) {
              // Similarly, we can also ignore incoming htlcs that we haven't relayed, because we can't receive the preimage.
              None
            } else {
              // For all other incoming htlcs, we may receive the preimage later from downstream. We thus want to track
              // the corresponding outputs to ensure we don't move to the CLOSED state until they've been spent, either
              // by us if we receive the preimage, or by our peer after the timeout.
              Some(txInfo.input.outPoint -> None)
            }
          case HtlcTxAndRemoteSig(txInfo: HtlcTimeoutTx, remoteSig) =>
            // We track all outputs that belong to outgoing htlcs. Our peer may or may not have the preimage: if they
            // claim the output, we will learn the preimage from their transaction, otherwise we will get our funds
            // back after the timeout.
            Some(txInfo.input.outPoint -> withTxGenerationLog("htlc-timeout") {
              val localSig = txInfo.sign(commitKeys.ourHtlcKey, TxOwner.Local, commitment.params.commitmentFormat, Map.empty)
              Right(Transactions.addSigs(txInfo, localSig, remoteSig, commitment.params.commitmentFormat))
            })
        }.flatten.toMap
      }

      /**
       * Claim the output of a 2nd-stage HTLC transaction. If the provided transaction isn't an htlc, this will be a no-op.
       *
       * NB: with anchor outputs, it's possible to have transactions that spend *many* HTLC outputs at once, but we're not
       * doing that because it introduces a lot of subtle edge cases.
       */
      def claimHtlcDelayedOutput(localCommitPublished: LocalCommitPublished, channelKeys: ChannelKeys, commitment: FullCommitment, tx: Transaction, feerates: FeeratesPerKw, onChainFeeConf: OnChainFeeConf, finalScriptPubKey: ByteVector)(implicit log: LoggingAdapter): (LocalCommitPublished, Option[HtlcDelayedTx]) = {
        if (tx.txIn.exists(txIn => localCommitPublished.htlcTxs.contains(txIn.outPoint))) {
          val feeratePerKwDelayed = onChainFeeConf.getClosingFeerate(feerates)
          val commitKeys = commitment.localKeys(channelKeys)
          val htlcDelayedTx_opt = withTxGenerationLog("htlc-delayed") {
            // Note that this will return None if the transaction wasn't one of our HTLC transactions, which may happen
            // if our peer was able to claim the HTLC output before us (race condition between success and timeout).
            Transactions.makeHtlcDelayedTx(commitKeys, tx, commitment.localParams.dustLimit, commitment.remoteParams.toSelfDelay, finalScriptPubKey, feeratePerKwDelayed).map(claimDelayed => {
              val sig = claimDelayed.sign(commitKeys.ourDelayedPaymentKey, TxOwner.Local, commitment.params.commitmentFormat, Map.empty)
              Transactions.addSigs(claimDelayed, sig)
            })
          }
          val localCommitPublished1 = localCommitPublished.copy(claimHtlcDelayedTxs = localCommitPublished.claimHtlcDelayedTxs ++ htlcDelayedTx_opt.toSeq)
          (localCommitPublished1, htlcDelayedTx_opt)
        } else {
          (localCommitPublished, None)
        }
      }

    }

    object RemoteClose {

      /**
       * Claim all the HTLCs that we've received from their current commit tx, if the channel used option_static_remotekey
       * we don't need to claim our main output because it directly pays to one of our wallet's p2wpkh addresses.
       *
       * @param commitment   our commitment data, which includes payment preimages
       * @param remoteCommit the remote commitment data to use to claim outputs (it can be their current or next commitment)
       * @param commitTx     the remote commitment transaction that has just been published
       * @return a list of transactions (one per output of the commit tx that we can claim)
       */
      def claimCommitTxOutputs(channelKeys: ChannelKeys, commitment: FullCommitment, remoteCommit: RemoteCommit, commitTx: Transaction, feerates: FeeratesPerKw, onChainFeeConf: OnChainFeeConf, finalScriptPubKey: ByteVector)(implicit log: LoggingAdapter): RemoteCommitPublished = {
        require(remoteCommit.txid == commitTx.txid, "txid mismatch, provided tx is not the current remote commit tx")
        val fundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
        val commitKeys = commitment.remoteKeys(channelKeys, remoteCommit.remotePerCommitmentPoint)
        val htlcTxs: Map[OutPoint, Option[ClaimHtlcTx]] = claimHtlcOutputs(channelKeys, commitKeys, commitment, remoteCommit, feerates, finalScriptPubKey)
        val rcp = RemoteCommitPublished(
          commitTx = commitTx,
          claimMainOutputTx = claimMainOutput(commitment.params, commitKeys, commitTx, feerates, onChainFeeConf, finalScriptPubKey),
          claimHtlcTxs = htlcTxs,
          claimAnchorTxs = Nil,
          irrevocablySpent = Map.empty
        )
        val spendAnchors = htlcTxs.nonEmpty || onChainFeeConf.spendAnchorWithoutHtlcs
        if (spendAnchors) {
          // If we don't have pending HTLCs, we don't have funds at risk, so we use the normal closing priority.
          val confirmCommitBefore = htlcTxs.values.flatten.map(htlcTx => htlcTx.confirmationTarget).minByOption(_.confirmBefore).getOrElse(ConfirmationTarget.Priority(onChainFeeConf.feeTargets.closing))
          claimAnchors(fundingKey, rcp, confirmCommitBefore)
        } else {
          rcp
        }
      }

      def claimAnchors(anchorKey: PrivateKey, rcp: RemoteCommitPublished, confirmationTarget: ConfirmationTarget)(implicit log: LoggingAdapter): RemoteCommitPublished = {
        if (shouldUpdateAnchorTxs(rcp.claimAnchorTxs, confirmationTarget)) {
          val claimAnchorTxs = List(
            withTxGenerationLog("remote-anchor") {
              Transactions.makeClaimAnchorOutputTx(anchorKey, rcp.commitTx, confirmationTarget)
            },
          ).flatten
          rcp.copy(claimAnchorTxs = claimAnchorTxs)
        } else {
          rcp
        }
      }

      /**
       * Claim our main output only
       *
       * @param commitTx the remote commitment transaction that has just been published
       * @return an optional [[ClaimRemoteCommitMainOutputTx]] transaction claiming our main output
       */
      def claimMainOutput(params: ChannelParams, commitKeys: RemoteCommitmentKeys, commitTx: Transaction, feerates: FeeratesPerKw, onChainFeeConf: OnChainFeeConf, finalScriptPubKey: ByteVector)(implicit log: LoggingAdapter): Option[ClaimRemoteCommitMainOutputTx] = {
        commitKeys.ourPaymentKey match {
          case Left(_) =>
            // the commitment tx sends funds directly to our wallet, no claim tx needed
            None
          case Right(ourPaymentKey) =>
            val feeratePerKwMain = onChainFeeConf.getClosingFeerate(feerates)
            params.commitmentFormat match {
              case DefaultCommitmentFormat => withTxGenerationLog("remote-main") {
                Transactions.makeClaimP2WPKHOutputTx(commitKeys, commitTx, params.localParams.dustLimit, finalScriptPubKey, feeratePerKwMain).map(claimMain => {
                  val sig = claimMain.sign(ourPaymentKey, TxOwner.Local, params.commitmentFormat, Map.empty)
                  Transactions.addSigs(claimMain, commitKeys, sig)
                })
              }
              case _: AnchorOutputsCommitmentFormat => withTxGenerationLog("remote-main-delayed") {
                Transactions.makeClaimRemoteDelayedOutputTx(commitKeys, commitTx, params.localParams.dustLimit, finalScriptPubKey, feeratePerKwMain).map(claimMain => {
                  val sig = claimMain.sign(ourPaymentKey, TxOwner.Local, params.commitmentFormat, Map.empty)
                  Transactions.addSigs(claimMain, sig)
                })
              }
            }
        }
      }

      /**
       * Claim our htlc outputs only from the remote commitment.
       */
      def claimHtlcOutputs(channelKeys: ChannelKeys, commitKeys: RemoteCommitmentKeys, commitment: FullCommitment, remoteCommit: RemoteCommit, feerates: FeeratesPerKw, finalScriptPubKey: ByteVector)(implicit log: LoggingAdapter): Map[OutPoint, Option[ClaimHtlcTx]] = {
        val fundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
        val outputs = makeCommitTxOutputs(commitment.remoteFundingPubKey, fundingKey.publicKey, commitKeys.publicKeys, !commitment.localParams.paysCommitTxFees, commitment.remoteParams.dustLimit, commitment.localParams.toSelfDelay, remoteCommit.spec, commitment.params.commitmentFormat)
        val remoteCommitTx = makeCommitTx(commitment.commitInput, remoteCommit.index, commitment.params.remoteParams.paymentBasepoint, commitKeys.ourPaymentBasePoint, !commitment.params.localParams.isChannelOpener, outputs)
        require(remoteCommitTx.tx.txid == remoteCommit.txid, "txid mismatch, cannot recompute the current remote commit tx")
        // We need to use a rather high fee for htlc-claim because we compete with the counterparty.
        val feeratePerKwHtlc = feerates.fast

        // We collect all the preimages we wanted to reveal to our peer.
        val hash2Preimage: Map[ByteVector32, ByteVector32] = commitment.changes.localChanges.all.collect { case u: UpdateFulfillHtlc => u.paymentPreimage }.map(r => Crypto.sha256(r) -> r).toMap
        // We collect incoming HTLCs that we started failing but didn't cross-sign.
        val failedIncomingHtlcs: Set[Long] = commitment.changes.localChanges.all.collect {
          case u: UpdateFailHtlc => u.id
          case u: UpdateFailMalformedHtlc => u.id
        }.toSet
        // We collect incoming HTLCs that we haven't relayed: they may have been signed by our peer, but they haven't
        // sent their revocation yet.
        val nonRelayedIncomingHtlcs: Set[Long] = commitment.changes.remoteChanges.all.collect { case add: UpdateAddHtlc => add.id }.toSet

        // Remember we are looking at the remote commitment so IN for them is really OUT for us and vice versa.
        remoteCommit.spec.htlcs.collect {
          case OutgoingHtlc(add: UpdateAddHtlc) =>
            // NB: we first generate the tx skeleton and finalize it below if we have the preimage, so we set logSuccess to false to avoid logging twice.
            withTxGenerationLog("claim-htlc-success", logSuccess = false) {
              Transactions.makeClaimHtlcSuccessTx(commitKeys, remoteCommitTx.tx, commitment.localParams.dustLimit, outputs, finalScriptPubKey, add, feeratePerKwHtlc, commitment.params.commitmentFormat)
            }.map(claimHtlcTx => {
              if (hash2Preimage.contains(add.paymentHash)) {
                // We immediately spend incoming htlcs for which we have the preimage.
                Some(claimHtlcTx.input.outPoint -> withTxGenerationLog("claim-htlc-success") {
                  val sig = claimHtlcTx.sign(commitKeys.ourHtlcKey, TxOwner.Local, commitment.params.commitmentFormat, Map.empty)
                  Right(Transactions.addSigs(claimHtlcTx, sig, hash2Preimage(add.paymentHash)))
                })
              } else if (failedIncomingHtlcs.contains(add.id)) {
                // We can ignore incoming htlcs that we started failing: our peer will claim them after the timeout.
                // We don't track those outputs because we want to move to the CLOSED state even if our peer never claims them.
                None
              } else if (nonRelayedIncomingHtlcs.contains(add.id)) {
                // Similarly, we can also ignore incoming htlcs that we haven't relayed, because we can't receive the preimage.
                None
              } else {
                // For all other incoming htlcs, we may receive the preimage later from downstream. We thus want to track
                // the corresponding outputs to ensure we don't move to the CLOSED state until they've been spent, either
                // by us if we receive the preimage, or by our peer after the timeout.
                Some(claimHtlcTx.input.outPoint -> None)
              }
            })
          case IncomingHtlc(add: UpdateAddHtlc) =>
            // We track all outputs that belong to outgoing htlcs. Our peer may or may not have the preimage: if they
            // claim the output, we will learn the preimage from their transaction, otherwise we will get our funds
            // back after the timeout.
            // NB: we first generate the tx skeleton and finalize it below, so we set logSuccess to false to avoid logging twice.
            withTxGenerationLog("claim-htlc-timeout", logSuccess = false) {
              Transactions.makeClaimHtlcTimeoutTx(commitKeys, remoteCommitTx.tx, commitment.localParams.dustLimit, outputs, finalScriptPubKey, add, feeratePerKwHtlc, commitment.params.commitmentFormat)
            }.map(claimHtlcTx => {
              Some(claimHtlcTx.input.outPoint -> withTxGenerationLog("claim-htlc-timeout") {
                val sig = claimHtlcTx.sign(commitKeys.ourHtlcKey, TxOwner.Local, commitment.params.commitmentFormat, Map.empty)
                Right(Transactions.addSigs(claimHtlcTx, sig))
              })
            })
        }.toSeq.flatten.flatten.toMap
      }

    }

    object RevokedClose {

      /**
       * When an unexpected transaction spending the funding tx is detected, we must be in one of the following scenarios:
       *
       *  - it is a revoked commitment: we then extract the remote per-commitment secret and publish penalty transactions
       *  - it is a future commitment: if we lost future state, our peer could publish a future commitment (which may be
       *    revoked, but we won't be able to know because we lost the corresponding state)
       *  - it is not a valid commitment transaction: if our peer was able to steal our funding private key, they can
       *    spend the funding transaction however they want, and we won't be able to do anything about it
       *
       * This function returns the per-commitment secret in the first case, and None in the other cases.
       */
      def getRemotePerCommitmentSecret(params: ChannelParams, channelKeys: ChannelKeys, remotePerCommitmentSecrets: ShaChain, commitTx: Transaction): Option[(Long, PrivateKey)] = {
        import params._
        // a valid tx will always have at least one input, but this ensures we don't throw in tests
        val sequence = commitTx.txIn.headOption.map(_.sequence).getOrElse(0L)
        val obscuredTxNumber = Transactions.decodeTxNumber(sequence, commitTx.lockTime)
        val localPaymentPoint = localParams.walletStaticPaymentBasepoint.getOrElse(channelKeys.paymentBaseKey.publicKey)
        // this tx has been published by remote, so we need to invert local/remote params
        val txNumber = Transactions.obscuredCommitTxNumber(obscuredTxNumber, !localParams.isChannelOpener, remoteParams.paymentBasepoint, localPaymentPoint)
        if (txNumber > 0xffffffffffffL) {
          // txNumber must be lesser than 48 bits long
          None
        } else {
          // now we know what commit number this tx is referring to, we can derive the commitment point from the shachain
          remotePerCommitmentSecrets.getHash(0xFFFFFFFFFFFFL - txNumber).map(d => (txNumber, PrivateKey(d)))
        }
      }

      /**
       * When a revoked commitment transaction spending the funding tx is detected, we build a set of transactions that
       * will punish our peer by stealing all their funds.
       */
      def claimCommitTxOutputs(params: ChannelParams, channelKeys: ChannelKeys, commitTx: Transaction, commitmentNumber: Long, remotePerCommitmentSecret: PrivateKey, db: ChannelsDb, feerates: FeeratesPerKw, onChainFeeConf: OnChainFeeConf, finalScriptPubKey: ByteVector)(implicit log: LoggingAdapter): RevokedCommitPublished = {
        import params._
        log.warning("a revoked commit has been published with commitmentNumber={}", commitmentNumber)

        val commitKeys = RemoteCommitmentKeys(params, channelKeys, remotePerCommitmentSecret.publicKey)
        val revocationKey = Generators.revocationPrivKey(channelKeys.revocationBaseKey, remotePerCommitmentSecret)

        val feerateMain = onChainFeeConf.getClosingFeerate(feerates)
        // we need to use a high fee here for punishment txs because after a delay they can be spent by the counterparty
        val feeratePenalty = feerates.fast

        // first we will claim our main output right away
        val mainTx = commitKeys.ourPaymentKey match {
          case Left(_) =>
            log.info("channel uses option_static_remotekey to pay directly to our wallet, there is nothing to do")
            None
          case Right(ourPaymentKey) => commitmentFormat match {
            case DefaultCommitmentFormat => withTxGenerationLog("remote-main") {
              Transactions.makeClaimP2WPKHOutputTx(commitKeys, commitTx, localParams.dustLimit, finalScriptPubKey, feerateMain).map(claimMain => {
                val sig = claimMain.sign(ourPaymentKey, TxOwner.Local, commitmentFormat, Map.empty)
                Transactions.addSigs(claimMain, commitKeys, sig)
              })
            }
            case _: AnchorOutputsCommitmentFormat => withTxGenerationLog("remote-main-delayed") {
              Transactions.makeClaimRemoteDelayedOutputTx(commitKeys, commitTx, localParams.dustLimit, finalScriptPubKey, feerateMain).map(claimMain => {
                val sig = claimMain.sign(ourPaymentKey, TxOwner.Local, commitmentFormat, Map.empty)
                Transactions.addSigs(claimMain, sig)
              })
            }
          }
        }

        // then we punish them by stealing their main output
        val mainPenaltyTx = withTxGenerationLog("main-penalty") {
          Transactions.makeMainPenaltyTx(commitKeys, commitTx, localParams.dustLimit, finalScriptPubKey, localParams.toSelfDelay, feeratePenalty).map(txinfo => {
            val sig = txinfo.sign(revocationKey, TxOwner.Local, commitmentFormat, Map.empty)
            Transactions.addSigs(txinfo, sig)
          })
        }

        // we retrieve the information needed to rebuild htlc scripts
        val htlcInfos = db.listHtlcInfos(channelId, commitmentNumber)
        log.info("got {} htlcs for commitmentNumber={}", htlcInfos.size, commitmentNumber)
        val htlcsRedeemScripts = (
          htlcInfos.map { case (paymentHash, cltvExpiry) => Scripts.htlcReceived(commitKeys.publicKeys, paymentHash, cltvExpiry, commitmentFormat) } ++
            htlcInfos.map { case (paymentHash, _) => Scripts.htlcOffered(commitKeys.publicKeys, paymentHash, commitmentFormat) }
          )
          .map(redeemScript => Script.write(pay2wsh(redeemScript)) -> Script.write(redeemScript))
          .toMap

        // and finally we steal the htlc outputs
        val htlcPenaltyTxs = commitTx.txOut.zipWithIndex.collect { case (txOut, outputIndex) if htlcsRedeemScripts.contains(txOut.publicKeyScript) =>
          val htlcRedeemScript = htlcsRedeemScripts(txOut.publicKeyScript)
          withTxGenerationLog("htlc-penalty") {
            Transactions.makeHtlcPenaltyTx(commitTx, outputIndex, htlcRedeemScript, localParams.dustLimit, finalScriptPubKey, feeratePenalty).map(htlcPenalty => {
              val sig = htlcPenalty.sign(revocationKey, TxOwner.Local, commitmentFormat, Map.empty)
              Transactions.addSigs(htlcPenalty, commitKeys, sig)
            })
          }
        }.toList.flatten

        RevokedCommitPublished(
          commitTx = commitTx,
          claimMainOutputTx = mainTx,
          mainPenaltyTx = mainPenaltyTx,
          htlcPenaltyTxs = htlcPenaltyTxs,
          claimHtlcDelayedPenaltyTxs = Nil, // we will generate and spend those if they publish their HtlcSuccessTx or HtlcTimeoutTx
          irrevocablySpent = Map.empty
        )
      }

      /**
       * Claims the output of an [[HtlcSuccessTx]] or [[HtlcTimeoutTx]] transaction using a revocation key.
       *
       * In case a revoked commitment with pending HTLCs is published, there are two ways the HTLC outputs can be taken as punishment:
       * - by spending the corresponding output of the commitment tx, using [[HtlcPenaltyTx]] that we generate as soon as we detect that a revoked commit
       * as been spent; note that those transactions will compete with [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] published by the counterparty.
       * - by spending the delayed output of [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] if those get confirmed; because the output of these txs is protected by
       * an OP_CSV delay, we will have time to spend them with a revocation key. In that case, we generate the spending transactions "on demand",
       * this is the purpose of this method.
       *
       * NB: when anchor outputs is used, htlc transactions can be aggregated in a single transaction if they share the same
       * lockTime (thanks to the use of sighash_single | sighash_anyonecanpay), so we may need to claim multiple outputs.
       */
      def claimHtlcTxOutputs(params: ChannelParams, channelKeys: ChannelKeys, remotePerCommitmentSecrets: ShaChain, revokedCommitPublished: RevokedCommitPublished, htlcTx: Transaction, feerates: FeeratesPerKw, finalScriptPubKey: ByteVector)(implicit log: LoggingAdapter): (RevokedCommitPublished, Seq[ClaimHtlcDelayedOutputPenaltyTx]) = {
        // We published HTLC-penalty transactions for every HTLC output: this transaction may be ours, or it may be one
        // of their HTLC transactions that confirmed before our HTLC-penalty transaction. If it is spending an HTLC
        // output, we assume that it's an HTLC transaction published by our peer and try to create penalty transactions
        // that spend it, which will automatically be skipped if this was instead one of our HTLC-penalty transactions.
        val htlcOutputs = revokedCommitPublished.htlcPenaltyTxs.map(_.input.outPoint).toSet
        val spendsHtlcOutput = htlcTx.txIn.exists(txIn => htlcOutputs.contains(txIn.outPoint))
        if (spendsHtlcOutput) {
          getRemotePerCommitmentSecret(params, channelKeys, remotePerCommitmentSecrets, revokedCommitPublished.commitTx).map {
            case (_, remotePerCommitmentSecret) =>
              val commitmentKeys = RemoteCommitmentKeys(params, channelKeys, remotePerCommitmentSecret.publicKey)
              val revocationKey = Generators.revocationPrivKey(channelKeys.revocationBaseKey, remotePerCommitmentSecret)
              // We need to use a high fee when spending HTLC txs because after a delay they can also be spent by the counterparty.
              val feeratePerKwPenalty = feerates.fastest
              val penaltyTxs = Transactions.makeClaimHtlcDelayedOutputPenaltyTxs(commitmentKeys, htlcTx, params.localParams.dustLimit, params.localParams.toSelfDelay, finalScriptPubKey, feeratePerKwPenalty).flatMap(claimHtlcDelayedOutputPenaltyTx => {
                withTxGenerationLog("htlc-delayed-penalty") {
                  claimHtlcDelayedOutputPenaltyTx.map(htlcDelayedPenalty => {
                    val sig = htlcDelayedPenalty.sign(revocationKey, TxOwner.Local, params.commitmentFormat, Map.empty)
                    val signedTx = Transactions.addSigs(htlcDelayedPenalty, sig)
                    // We need to make sure that the tx is indeed valid.
                    Transaction.correctlySpends(signedTx.tx, Seq(htlcTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
                    log.warning("txId={} is a 2nd level htlc tx spending revoked commit txId={}: publishing htlc-penalty txId={}", htlcTx.txid, revokedCommitPublished.commitTx.txid, signedTx.tx.txid)
                    signedTx
                  })
                }
              })
              val revokedCommitPublished1 = revokedCommitPublished.copy(claimHtlcDelayedPenaltyTxs = revokedCommitPublished.claimHtlcDelayedPenaltyTxs ++ penaltyTxs)
              (revokedCommitPublished1, penaltyTxs)
          }.getOrElse((revokedCommitPublished, Nil))
        } else {
          (revokedCommitPublished, Nil)
        }
      }

    }

    /**
     * In CLOSING state, any time we see a new transaction, we try to extract a preimage from it in order to fulfill the
     * corresponding incoming htlc in an upstream channel.
     *
     * Not doing that would result in us losing money, because the downstream node would pull money from one side, and
     * the upstream node would get refunded after a timeout.
     *
     * @return a set of pairs (add, preimage) if extraction was successful:
     *           - add is the htlc in the downstream channel from which we extracted the preimage
     *           - preimage needs to be sent to the upstream channel
     */
    def extractPreimages(commitment: FullCommitment, tx: Transaction)(implicit log: LoggingAdapter): Set[(UpdateAddHtlc, ByteVector32)] = {
      val htlcSuccess = Scripts.extractPreimagesFromHtlcSuccess(tx)
      htlcSuccess.foreach(r => log.info("extracted paymentPreimage={} from tx={} (htlc-success)", r, tx))
      val claimHtlcSuccess = Scripts.extractPreimagesFromClaimHtlcSuccess(tx)
      claimHtlcSuccess.foreach(r => log.info("extracted paymentPreimage={} from tx={} (claim-htlc-success)", r, tx))
      val paymentPreimages = htlcSuccess ++ claimHtlcSuccess
      paymentPreimages.flatMap { paymentPreimage =>
        val paymentHash = sha256(paymentPreimage)
        // We only care about outgoing HTLCs when we're trying to learn a preimage to relay upstream.
        // Note that we may have already relayed the fulfill upstream if we already saw the preimage.
        val fromLocal = commitment.localCommit.spec.htlcs.collect {
          case OutgoingHtlc(add) if add.paymentHash == paymentHash => (add, paymentPreimage)
        }
        // From the remote point of view, those are incoming HTLCs.
        val fromRemote = commitment.remoteCommit.spec.htlcs.collect {
          case IncomingHtlc(add) if add.paymentHash == paymentHash => (add, paymentPreimage)
        }
        val fromNextRemote = commitment.nextRemoteCommit_opt.map(_.commit.spec.htlcs).getOrElse(Set.empty).collect {
          case IncomingHtlc(add) if add.paymentHash == paymentHash => (add, paymentPreimage)
        }
        fromLocal ++ fromRemote ++ fromNextRemote
      }
    }

    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we analyze it to find out if one or
     * more htlcs have timed out and need to be failed in an upstream channel. Trimmed htlcs can be failed as soon as
     * the commitment tx has been confirmed.
     *
     * @param tx a tx that has reached mindepth
     * @return a set of htlcs that need to be failed upstream
     */
    def trimmedOrTimedOutHtlcs(commitmentFormat: CommitmentFormat, localCommit: LocalCommit, localCommitPublished: LocalCommitPublished, localDustLimit: Satoshi, tx: Transaction)(implicit log: LoggingAdapter): Set[UpdateAddHtlc] = {
      val untrimmedHtlcs = Transactions.trimOfferedHtlcs(localDustLimit, localCommit.spec, commitmentFormat).map(_.add)
      if (tx.txid == localCommit.commitTxAndRemoteSig.commitTx.tx.txid) {
        // The commitment tx is confirmed: we can immediately fail all dust htlcs (they don't have an output in the tx).
        localCommit.spec.htlcs.collect(outgoing) -- untrimmedHtlcs
      } else {
        // Maybe this is a timeout tx: in that case we can resolve and fail the corresponding htlc.
        tx.txIn.flatMap(txIn => localCommitPublished.htlcTxs.get(txIn.outPoint) match {
          // This may also be our peer claiming the HTLC by revealing the preimage: in that case we have already
          // extracted the preimage with [[extractPreimages]] and relayed it upstream.
          case Some(Some(HtlcTimeoutTx(_, _, htlcId, _))) if Scripts.extractPreimagesFromClaimHtlcSuccess(tx).isEmpty =>
            untrimmedHtlcs.find(_.id == htlcId) match {
              case Some(htlc) =>
                log.info("htlc-timeout tx for htlc #{} paymentHash={} expiry={} has been confirmed (tx={})", htlcId, htlc.paymentHash, tx.lockTime, tx)
                Some(htlc)
              case None =>
                log.error("could not find htlc #{} for htlc-timeout tx={}", htlcId, tx)
                None
            }
          case _ => None
        }).toSet
      }
    }

    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we analyze it to find out if one or
     * more htlcs have timed out and need to be failed in an upstream channel. Trimmed htlcs can be failed as soon as
     * the commitment tx has been confirmed.
     *
     * @param tx a tx that has reached mindepth
     * @return a set of htlcs that need to be failed upstream
     */
    def trimmedOrTimedOutHtlcs(commitmentFormat: CommitmentFormat, remoteCommit: RemoteCommit, remoteCommitPublished: RemoteCommitPublished, remoteDustLimit: Satoshi, tx: Transaction)(implicit log: LoggingAdapter): Set[UpdateAddHtlc] = {
      val untrimmedHtlcs = Transactions.trimReceivedHtlcs(remoteDustLimit, remoteCommit.spec, commitmentFormat).map(_.add)
      if (tx.txid == remoteCommit.txid) {
        // The commitment tx is confirmed: we can immediately fail all dust htlcs (they don't have an output in the tx).
        remoteCommit.spec.htlcs.collect(incoming) -- untrimmedHtlcs
      } else {
        // Maybe this is a timeout tx: in that case we can resolve and fail the corresponding htlc.
        tx.txIn.flatMap(txIn => remoteCommitPublished.claimHtlcTxs.get(txIn.outPoint) match {
          // This may also be our peer claiming the HTLC by revealing the preimage: in that case we have already
          // extracted the preimage with [[extractPreimages]] and relayed it upstream.
          case Some(Some(ClaimHtlcTimeoutTx(_, _, htlcId, _))) if Scripts.extractPreimagesFromHtlcSuccess(tx).isEmpty =>
            untrimmedHtlcs.find(_.id == htlcId) match {
              case Some(htlc) =>
                log.info("claim-htlc-timeout tx for htlc #{} paymentHash={} expiry={} has been confirmed (tx={})", htlcId, htlc.paymentHash, tx.lockTime, tx)
                Some(htlc)
              case None =>
                log.error("could not find htlc #{} for claim-htlc-timeout tx={}", htlcId, tx)
                None
            }
          case _ => None
        }).toSet
      }
    }

    /**
     * As soon as a local or remote commitment reaches min_depth, we know which htlcs will be settled on-chain (whether
     * or not they actually have an output in the commitment tx).
     *
     * @param tx a transaction that is sufficiently buried in the blockchain
     */
    def onChainOutgoingHtlcs(localCommit: LocalCommit, remoteCommit: RemoteCommit, nextRemoteCommit_opt: Option[RemoteCommit], tx: Transaction): Set[UpdateAddHtlc] = {
      if (localCommit.commitTxAndRemoteSig.commitTx.tx.txid == tx.txid) {
        localCommit.spec.htlcs.collect(outgoing)
      } else if (remoteCommit.txid == tx.txid) {
        remoteCommit.spec.htlcs.collect(incoming)
      } else if (nextRemoteCommit_opt.map(_.txid).contains(tx.txid)) {
        nextRemoteCommit_opt.get.spec.htlcs.collect(incoming)
      } else {
        Set.empty
      }
    }

    /**
     * If a commitment tx reaches min_depth, we need to fail the outgoing htlcs that will never reach the blockchain.
     * It could be because only us had signed them, because a revoked commitment got confirmed, or the next commitment
     * didn't contain those HTLCs.
     */
    def overriddenOutgoingHtlcs(d: DATA_CLOSING, tx: Transaction): Set[UpdateAddHtlc] = {
      val localCommit = d.commitments.latest.localCommit
      val remoteCommit = d.commitments.latest.remoteCommit
      val nextRemoteCommit_opt = d.commitments.latest.nextRemoteCommit_opt.map(_.commit)
      // NB: from the p.o.v of remote, their incoming htlcs are our outgoing htlcs.
      val outgoingHtlcs = localCommit.spec.htlcs.collect(outgoing) ++ (remoteCommit.spec.htlcs ++ nextRemoteCommit_opt.map(_.spec.htlcs).getOrElse(Set.empty)).collect(incoming)
      if (localCommit.commitTxAndRemoteSig.commitTx.tx.txid == tx.txid) {
        // Our commit got confirmed: any htlc that is *not* in our commit will never reach the chain.
        outgoingHtlcs -- localCommit.spec.htlcs.collect(outgoing)
      } else if (d.revokedCommitPublished.map(_.commitTx.txid).contains(tx.txid)) {
        // A revoked commitment got confirmed: we will claim its outputs, but we also need to resolve upstream htlcs.
        // We consider *all* outgoing htlcs failed: our peer may reveal the preimage with an HTLC-success transaction,
        // but it's more likely that our penalty transaction will confirm first. In any case, since we will get those
        // funds back on-chain, it's as if the outgoing htlc had failed, therefore it doesn't hurt to be failed back
        // upstream. In the best case scenario, we already fulfilled upstream, then the fail will be a no-op and we
        // will pocket the htlc amount.
        outgoingHtlcs
      } else if (remoteCommit.txid == tx.txid) {
        // Their current commit got confirmed: any htlc that is *not* in their current commit will never reach the chain.
        outgoingHtlcs -- remoteCommit.spec.htlcs.collect(incoming)
      } else if (nextRemoteCommit_opt.map(_.txid).contains(tx.txid)) {
        // Their next commit got confirmed: any htlc that is *not* in their next commit will never reach the chain.
        outgoingHtlcs -- nextRemoteCommit_opt.map(_.spec.htlcs).getOrElse(Set.empty).collect(incoming)
      } else {
        Set.empty
      }
    }

    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
     * local commit scenario and keep track of it.
     *
     * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
     * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
     * want to wait forever before declaring that the channel is CLOSED.
     *
     * @param tx a transaction that has been irrevocably confirmed
     */
    def updateLocalCommitPublished(localCommitPublished: LocalCommitPublished, tx: Transaction): LocalCommitPublished = {
      // even if our txs only have one input, maybe our counterparty uses a different scheme so we need to iterate
      // over all of them to check if they are relevant
      val relevantOutpoints = tx.txIn.map(_.outPoint).filter(outPoint => {
        // is this the commit tx itself? (we could do this outside of the loop...)
        val isCommitTx = localCommitPublished.commitTx.txid == tx.txid
        // does the tx spend an output of the local commitment tx?
        val spendsTheCommitTx = localCommitPublished.commitTx.txid == outPoint.txid
        // is the tx one of our 3rd stage delayed txs? (a 3rd stage tx is a tx spending the output of an htlc tx, which
        // is itself spending the output of the commitment tx)
        val is3rdStageDelayedTx = localCommitPublished.claimHtlcDelayedTxs.map(_.input.outPoint).contains(outPoint)
        isCommitTx || spendsTheCommitTx || is3rdStageDelayedTx
      })
      // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
      localCommitPublished.copy(irrevocablySpent = localCommitPublished.irrevocablySpent ++ relevantOutpoints.map(o => o -> tx).toMap)
    }

    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
     * remote commit scenario and keep track of it.
     *
     * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
     * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
     * want to wait forever before declaring that the channel is CLOSED.
     *
     * @param tx a transaction that has been irrevocably confirmed
     */
    def updateRemoteCommitPublished(remoteCommitPublished: RemoteCommitPublished, tx: Transaction): RemoteCommitPublished = {
      // even if our txs only have one input, maybe our counterparty uses a different scheme so we need to iterate
      // over all of them to check if they are relevant
      val relevantOutpoints = tx.txIn.map(_.outPoint).filter(outPoint => {
        // is this the commit tx itself? (we could do this outside of the loop...)
        val isCommitTx = remoteCommitPublished.commitTx.txid == tx.txid
        // does the tx spend an output of the remote commitment tx?
        val spendsTheCommitTx = remoteCommitPublished.commitTx.txid == outPoint.txid
        isCommitTx || spendsTheCommitTx
      })
      // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
      remoteCommitPublished.copy(irrevocablySpent = remoteCommitPublished.irrevocablySpent ++ relevantOutpoints.map(o => o -> tx).toMap)
    }

    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
     * revoked commit scenario and keep track of it.
     *
     * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
     * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
     * want to wait forever before declaring that the channel is CLOSED.
     *
     * @param tx a transaction that has been irrevocably confirmed
     */
    def updateRevokedCommitPublished(revokedCommitPublished: RevokedCommitPublished, tx: Transaction): RevokedCommitPublished = {
      // even if our txs only have one input, maybe our counterparty uses a different scheme so we need to iterate
      // over all of them to check if they are relevant
      val relevantOutpoints = tx.txIn.map(_.outPoint).filter(outPoint => {
        // is this the commit tx itself? (we could do this outside of the loop...)
        val isCommitTx = revokedCommitPublished.commitTx.txid == tx.txid
        // does the tx spend an output of the remote commitment tx?
        val spendsTheCommitTx = revokedCommitPublished.commitTx.txid == outPoint.txid
        // is the tx one of our 3rd stage delayed txs? (a 3rd stage tx is a tx spending the output of an htlc tx, which
        // is itself spending the output of the commitment tx)
        val is3rdStageDelayedTx = revokedCommitPublished.claimHtlcDelayedPenaltyTxs.map(_.input.outPoint).contains(outPoint)
        isCommitTx || spendsTheCommitTx || is3rdStageDelayedTx
      })
      // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
      revokedCommitPublished.copy(irrevocablySpent = revokedCommitPublished.irrevocablySpent ++ relevantOutpoints.map(o => o -> tx).toMap)
    }

    /**
     * This helper function tells if some of the utxos consumed by the given transaction have already been irrevocably spent (possibly by this very transaction).
     *
     * It can be useful to:
     *   - not attempt to publish this tx when we know this will fail
     *   - not watch for confirmations if we know the tx is already confirmed
     *   - not watch the corresponding utxo when we already know the final spending tx
     *
     * @param tx               an arbitrary transaction
     * @param irrevocablySpent a map of known spent outpoints
     * @return true if we know for sure that the utxos consumed by the tx have already irrevocably been spent, false otherwise
     */
    def inputsAlreadySpent(tx: Transaction, irrevocablySpent: Map[OutPoint, Transaction]): Boolean = {
      tx.txIn.exists(txIn => irrevocablySpent.contains(txIn.outPoint))
    }

    def inputAlreadySpent(input: OutPoint, irrevocablySpent: Map[OutPoint, Transaction]): Boolean = {
      irrevocablySpent.contains(input)
    }

  }

}
