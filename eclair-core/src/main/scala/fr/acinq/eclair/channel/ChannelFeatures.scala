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

import fr.acinq.eclair.transactions.Transactions.{CommitmentFormat, DefaultCommitmentFormat, UnsafeLegacyAnchorOutputsCommitmentFormat, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat}
import fr.acinq.eclair.{ChannelTypeFeature, FeatureSupport, Features, InitFeature, PermanentChannelFeature}

/**
 * Created by t-bast on 24/06/2021.
 */

/**
 * Subset of Bolt 9 features used to configure a channel and applicable over the lifetime of that channel.
 * Even if one of these features is later disabled at the connection level, it will still apply to the channel until the
 * channel is upgraded or closed.
 */
case class ChannelFeatures(features: Set[PermanentChannelFeature]) {

  val channelType: SupportedChannelType = {
    val scidAlias = features.contains(Features.ScidAlias)
    val zeroConf = features.contains(Features.ZeroConf)
    if (hasFeature(Features.AnchorOutputsZeroFeeHtlcTx)) {
      ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias, zeroConf)
    } else if (hasFeature(Features.AnchorOutputs)) {
      ChannelTypes.AnchorOutputs(scidAlias, zeroConf)
    } else if (hasFeature(Features.StaticRemoteKey)) {
      ChannelTypes.StaticRemoteKey(scidAlias, zeroConf)
    } else {
      ChannelTypes.Standard(scidAlias, zeroConf)
    }
  }

  val paysDirectlyToWallet: Boolean = channelType.paysDirectlyToWallet
  val commitmentFormat: CommitmentFormat = channelType.commitmentFormat

  def hasFeature(feature: PermanentChannelFeature): Boolean = features.contains(feature)

  override def toString: String = features.mkString(",")

}

object ChannelFeatures {

  def apply(features: PermanentChannelFeature*): ChannelFeatures = ChannelFeatures(Set.from(features))

  /** Enrich the channel type with other permanent features that will be applied to the channel. */
  def apply(channelType: SupportedChannelType, localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature], announceChannel: Boolean): ChannelFeatures = {
    val additionalPermanentFeatures = Features.knownFeatures.collect {
      // If we both support 0-conf or scid_alias, we use it even if it wasn't in the channel-type.
      case Features.ScidAlias if Features.canUseFeature(localFeatures, remoteFeatures, Features.ScidAlias) && !announceChannel => Some(Features.ScidAlias)
      case Features.ZeroConf if Features.canUseFeature(localFeatures, remoteFeatures, Features.ZeroConf) => Some(Features.ZeroConf)
      // Other channel-type features are negotiated in the channel-type, we ignore their value from the init message.
      case _: ChannelTypeFeature => None
      // We add all other permanent channel features that aren't negotiated as part of the channel-type.
      case f: PermanentChannelFeature if Features.canUseFeature(localFeatures, remoteFeatures, f) => Some(f)
    }.flatten
    val allPermanentFeatures = channelType.features.toSeq ++ additionalPermanentFeatures
    ChannelFeatures(allPermanentFeatures: _*)
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

  /** True if our main output in the remote commitment is directly sent (without any delay) to one of our wallet addresses. */
  def paysDirectlyToWallet: Boolean

  /** Format of the channel transactions. */
  def commitmentFormat: CommitmentFormat
}

object ChannelTypes {

  // @formatter:off
  case class Standard(scidAlias: Boolean = false, zeroConf: Boolean = false) extends SupportedChannelType {
    override def features: Set[ChannelTypeFeature] = Set(
      if (scidAlias) Some(Features.ScidAlias) else None,
      if (zeroConf) Some(Features.ZeroConf) else None,
    ).flatten
    override def paysDirectlyToWallet: Boolean = false
    override def commitmentFormat: CommitmentFormat = DefaultCommitmentFormat
    override def toString: String = s"standard${if (scidAlias) "+scid_alias" else ""}${if (zeroConf) "+zeroconf" else ""}"
  }
  case class StaticRemoteKey(scidAlias: Boolean = false, zeroConf: Boolean = false) extends SupportedChannelType {
    override def features: Set[ChannelTypeFeature] = Set(
      if (scidAlias) Some(Features.ScidAlias) else None,
      if (zeroConf) Some(Features.ZeroConf) else None,
      Some(Features.StaticRemoteKey)
    ).flatten
    override def paysDirectlyToWallet: Boolean = true
    override def commitmentFormat: CommitmentFormat = DefaultCommitmentFormat
    override def toString: String = s"static_remotekey${if (scidAlias) "+scid_alias" else ""}${if (zeroConf) "+zeroconf" else ""}"
  }
  case class AnchorOutputs(scidAlias: Boolean = false, zeroConf: Boolean = false) extends SupportedChannelType {
    override def features: Set[ChannelTypeFeature] = Set(
      if (scidAlias) Some(Features.ScidAlias) else None,
      if (zeroConf) Some(Features.ZeroConf) else None,
      Some(Features.StaticRemoteKey),
      Some(Features.AnchorOutputs)
    ).flatten
    override def paysDirectlyToWallet: Boolean = false
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
    override def paysDirectlyToWallet: Boolean = false
    override def commitmentFormat: CommitmentFormat = ZeroFeeHtlcTxAnchorOutputsCommitmentFormat
    override def toString: String = s"anchor_outputs_zero_fee_htlc_tx${if (scidAlias) "+scid_alias" else ""}${if (zeroConf) "+zeroconf" else ""}"
  }
  case class UnsupportedChannelType(featureBits: Features[InitFeature]) extends ChannelType {
    override def features: Set[InitFeature] = featureBits.activated.keySet
    override def toString: String = s"0x${featureBits.toByteVector.toHex}"
  }
  // @formatter:on

  private val features2ChannelType: Map[Features[_ <: InitFeature], SupportedChannelType] = Set(
    Standard(),
    Standard(zeroConf = true),
    Standard(scidAlias = true),
    Standard(scidAlias = true, zeroConf = true),
    StaticRemoteKey(),
    StaticRemoteKey(zeroConf = true),
    StaticRemoteKey(scidAlias = true),
    StaticRemoteKey(scidAlias = true, zeroConf = true),
    AnchorOutputs(),
    AnchorOutputs(zeroConf = true),
    AnchorOutputs(scidAlias = true),
    AnchorOutputs(scidAlias = true, zeroConf = true),
    AnchorOutputsZeroFeeHtlcTx(),
    AnchorOutputsZeroFeeHtlcTx(zeroConf = true),
    AnchorOutputsZeroFeeHtlcTx(scidAlias = true),
    AnchorOutputsZeroFeeHtlcTx(scidAlias = true, zeroConf = true))
    .map(channelType => Features(channelType.features.map(_ -> FeatureSupport.Mandatory).toMap) -> channelType)
    .toMap

  // NB: Bolt 2: features must exactly match in order to identify a channel type.
  def fromFeatures(features: Features[InitFeature]): ChannelType = features2ChannelType.getOrElse(features, UnsupportedChannelType(features))

  /** Pick the channel type based on local and remote feature bits, as defined by the spec. */
  def defaultFromFeatures(localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature], announceChannel: Boolean): SupportedChannelType = {
    def canUse(feature: InitFeature): Boolean = Features.canUseFeature(localFeatures, remoteFeatures, feature)

    val scidAlias = canUse(Features.ScidAlias) && !announceChannel // alias feature is incompatible with public channel
    val zeroConf = canUse(Features.ZeroConf)
    if (canUse(Features.AnchorOutputsZeroFeeHtlcTx)) {
      AnchorOutputsZeroFeeHtlcTx(scidAlias, zeroConf)
    } else if (canUse(Features.AnchorOutputs)) {
      AnchorOutputs(scidAlias, zeroConf)
    } else if (canUse(Features.StaticRemoteKey)) {
      StaticRemoteKey(scidAlias, zeroConf)
    } else {
      Standard(scidAlias, zeroConf)
    }
  }

  /** Check if a given channel type is compatible with our features. */
  def areCompatible(localFeatures: Features[InitFeature], remoteChannelType: ChannelType): Option[SupportedChannelType] = remoteChannelType match {
    case _: UnsupportedChannelType => None
    // We ensure that we support the features necessary for this channel type.
    case proposedChannelType: SupportedChannelType =>
      if (proposedChannelType.features.forall(f => localFeatures.hasFeature(f))) {
        Some(proposedChannelType)
      } else {
        None
      }
  }

}
