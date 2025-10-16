/*
 * Copyright 2021 ACINQ SAS
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

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.{ChannelTypeFeature, FeatureSupport, Features, InitFeature, PermanentChannelFeature}

/**
 * Created by t-bast on 24/06/2021.
 */

/**
 * Subset of Bolt 9 features used to configure a channel and applicable over the lifetime of that channel.
 * Even if one of these features is later disabled at the connection level, it will still apply to the channel.
 */
case class ChannelFeatures(features: Set[PermanentChannelFeature]) {

  def hasFeature(feature: PermanentChannelFeature): Boolean = features.contains(feature)

  override def toString: String = features.mkString(",")

}

object ChannelFeatures {

  def apply(features: PermanentChannelFeature*): ChannelFeatures = ChannelFeatures(Set.from(features))

  /** Configure permanent channel features based on local and remote feature. */
  def apply(channelType: SupportedChannelType, localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature], announceChannel: Boolean): ChannelFeatures = {
    val permanentFeatures = Features.knownFeatures.collect {
      // If we both support 0-conf or scid_alias, we use it even if it wasn't in the channel-type.
      // Note that we cannot use scid_alias if the channel is announced.
      case Features.ScidAlias if Features.canUseFeature(localFeatures, remoteFeatures, Features.ScidAlias) && !announceChannel => Some(Features.ScidAlias)
      case Features.ScidAlias => None
      case Features.ZeroConf if Features.canUseFeature(localFeatures, remoteFeatures, Features.ZeroConf) => Some(Features.ZeroConf)
      // We add all other permanent channel features that we both support.
      case f: PermanentChannelFeature if Features.canUseFeature(localFeatures, remoteFeatures, f) => Some(f)
    }.flatten
    // Some permanent features can be negotiated as part of the channel-type.
    val channelTypeFeatures = channelType.features.collect { case f: PermanentChannelFeature => f }
    ChannelFeatures(permanentFeatures ++ channelTypeFeatures)
  }

}

/** A channel type is a specific set of even feature bits that represent persistent channel features as defined in Bolt 2. */
sealed trait ChannelType {
  /** Features representing that channel type. */
  def features: Set[_ <: InitFeature]
}

sealed trait SupportedChannelType extends ChannelType {
  /** Known channel-type features */
  override def features: Set[ChannelTypeFeature]

  /** Format of the channel transactions. */
  def commitmentFormat: CommitmentFormat
}

object ChannelTypes {

  // @formatter:off
  case class AnchorOutputs(scidAlias: Boolean = false, zeroConf: Boolean = false) extends SupportedChannelType {
    override def features: Set[ChannelTypeFeature] = Set(
      if (scidAlias) Some(Features.ScidAlias) else None,
      if (zeroConf) Some(Features.ZeroConf) else None,
      Some(Features.StaticRemoteKey),
      Some(Features.AnchorOutputs)
    ).flatten
    override def commitmentFormat: CommitmentFormat = UnsafeLegacyAnchorOutputsCommitmentFormat
    override def toString: String = s"anchor_outputs${if (scidAlias) "+scid_alias" else ""}${if (zeroConf) "+zeroconf" else ""}"
  }
  case class AnchorOutputsZeroFeeHtlcTx(scidAlias: Boolean = false, zeroConf: Boolean = false) extends SupportedChannelType {
    override def features: Set[ChannelTypeFeature] = Set(
      if (scidAlias) Some(Features.ScidAlias) else None,
      if (zeroConf) Some(Features.ZeroConf) else None,
      Some(Features.StaticRemoteKey),
      Some(Features.AnchorOutputsZeroFeeHtlcTx)
    ).flatten
    override def commitmentFormat: CommitmentFormat = ZeroFeeHtlcTxAnchorOutputsCommitmentFormat
    override def toString: String = s"anchor_outputs_zero_fee_htlc_tx${if (scidAlias) "+scid_alias" else ""}${if (zeroConf) "+zeroconf" else ""}"
  }
  case class SimpleTaprootChannelsStaging(scidAlias: Boolean = false, zeroConf: Boolean = false) extends SupportedChannelType {
    override def features: Set[ChannelTypeFeature] = Set(
      if (scidAlias) Some(Features.ScidAlias) else None,
      if (zeroConf) Some(Features.ZeroConf) else None,
      Some(Features.SimpleTaprootChannelsStaging),
    ).flatten
    override def commitmentFormat: CommitmentFormat = ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat
    override def toString: String = s"simple_taproot_channel_staging${if (scidAlias) "+scid_alias" else ""}${if (zeroConf) "+zeroconf" else ""}"
  }

  case class UnsupportedChannelType(featureBits: Features[InitFeature]) extends ChannelType {
    override def features: Set[InitFeature] = featureBits.activated.keySet
    override def toString: String = s"0x${featureBits.toByteVector.toHex}"
  }

  // Phoenix uses custom channel types, that we may remove in the future.
  case object SimpleTaprootChannelsPhoenix extends SupportedChannelType {
    override def features: Set[ChannelTypeFeature] = Set(Features.PhoenixZeroReserve, Features.SimpleTaprootChannelsPhoenix)
    override def commitmentFormat: CommitmentFormat = PhoenixSimpleTaprootChannelCommitmentFormat
    override def toString: String = "phoenix_simple_taproot_channel"
  }

  // @formatter:on

  private val features2ChannelType: Map[Features[_ <: InitFeature], SupportedChannelType] = Set(
    AnchorOutputs(),
    AnchorOutputs(zeroConf = true),
    AnchorOutputs(scidAlias = true),
    AnchorOutputs(scidAlias = true, zeroConf = true),
    AnchorOutputsZeroFeeHtlcTx(),
    AnchorOutputsZeroFeeHtlcTx(zeroConf = true),
    AnchorOutputsZeroFeeHtlcTx(scidAlias = true),
    AnchorOutputsZeroFeeHtlcTx(scidAlias = true, zeroConf = true),
    SimpleTaprootChannelsStaging(),
    SimpleTaprootChannelsStaging(zeroConf = true),
    SimpleTaprootChannelsStaging(scidAlias = true),
    SimpleTaprootChannelsStaging(scidAlias = true, zeroConf = true),
    SimpleTaprootChannelsPhoenix,
  ).map {
    channelType => Features(channelType.features.map(_ -> FeatureSupport.Mandatory).toMap) -> channelType
  }.toMap

  // NB: Bolt 2: features must exactly match in order to identify a channel type.
  def fromFeatures(features: Features[InitFeature]): ChannelType = features2ChannelType.getOrElse(features, UnsupportedChannelType(features))

  /** Check if a given channel type is compatible with our features. */
  def areCompatible(channelId: ByteVector32, localFeatures: Features[InitFeature], remoteChannelType_opt: Option[ChannelType]): Either[ChannelException, SupportedChannelType] = remoteChannelType_opt match {
    case None => Left(MissingChannelType(channelId))
    case Some(channelType: UnsupportedChannelType) => Left(InvalidChannelType(channelId, channelType))
    // We ensure that we support the features necessary for this channel type.
    case Some(proposedChannelType: SupportedChannelType) if proposedChannelType.features.forall(f => localFeatures.hasFeature(f)) => Right(proposedChannelType)
    case Some(proposedChannelType: SupportedChannelType) => Left(InvalidChannelType(channelId, proposedChannelType))
  }

}
