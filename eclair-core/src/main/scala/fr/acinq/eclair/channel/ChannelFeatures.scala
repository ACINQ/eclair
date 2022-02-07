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
import fr.acinq.eclair.{Feature, FeatureScope, FeatureSupport, Features, InitFeature}

/**
 * Created by t-bast on 24/06/2021.
 */

/**
 * Subset of Bolt 9 features used to configure a channel and applicable over the lifetime of that channel.
 * Even if one of these features is later disabled at the connection level, it will still apply to the channel until the
 * channel is upgraded or closed.
 */
case class ChannelFeatures(features: Set[Feature with FeatureScope]) {

  val channelType: SupportedChannelType = {
    if (hasFeature(Features.AnchorOutputsZeroFeeHtlcTx)) {
      ChannelTypes.AnchorOutputsZeroFeeHtlcTx
    } else if (hasFeature(Features.AnchorOutputs)) {
      ChannelTypes.AnchorOutputs
    } else if (hasFeature(Features.StaticRemoteKey)) {
      ChannelTypes.StaticRemoteKey
    } else {
      ChannelTypes.Standard
    }
  }

  val paysDirectlyToWallet: Boolean = channelType.paysDirectlyToWallet
  val commitmentFormat: CommitmentFormat = channelType.commitmentFormat

  def hasFeature(feature: Feature with FeatureScope): Boolean = features.contains(feature)

  override def toString: String = features.mkString(",")

}

object ChannelFeatures {

  def apply(features: (Feature with FeatureScope)*): ChannelFeatures = ChannelFeatures(Set.from(features))

  /** Enrich the channel type with other permanent features that will be applied to the channel. */
  def apply(channelType: ChannelType, localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature]): ChannelFeatures = {
    // NB: we don't include features that can be safely activated/deactivated without impacting the channel's operation,
    // such as option_dataloss_protect or option_shutdown_anysegwit.
    val availableFeatures: Seq[Feature with FeatureScope] = Seq(Features.Wumbo, Features.UpfrontShutdownScript).filter(f => Features.canUseFeature(localFeatures, remoteFeatures, f))
    val allFeatures = channelType.features.toSeq ++ availableFeatures
    ChannelFeatures(allFeatures: _*)
  }

}

/** A channel type is a specific set of even feature bits that represent persistent channel features as defined in Bolt 2. */
sealed trait ChannelType {
  /** Features representing that channel type. */
  def features: Set[Feature with InitFeature]
}

sealed trait SupportedChannelType extends ChannelType {
  /** True if our main output in the remote commitment is directly sent (without any delay) to one of our wallet addresses. */
  def paysDirectlyToWallet: Boolean

  /** Format of the channel transactions. */
  def commitmentFormat: CommitmentFormat
}

object ChannelTypes {

  // @formatter:off
  case object Standard extends SupportedChannelType {
    override def features: Set[Feature with InitFeature] = Set.empty
    override def paysDirectlyToWallet: Boolean = false
    override def commitmentFormat: CommitmentFormat = DefaultCommitmentFormat
    override def toString: String = "standard"
  }
  case object StaticRemoteKey extends SupportedChannelType {
    override def features: Set[Feature with InitFeature] = Set(Features.StaticRemoteKey)
    override def paysDirectlyToWallet: Boolean = true
    override def commitmentFormat: CommitmentFormat = DefaultCommitmentFormat
    override def toString: String = "static_remotekey"
  }
  case object AnchorOutputs extends SupportedChannelType {
    override def features: Set[Feature with InitFeature] = Set(Features.StaticRemoteKey, Features.AnchorOutputs)
    override def paysDirectlyToWallet: Boolean = false
    override def commitmentFormat: CommitmentFormat = UnsafeLegacyAnchorOutputsCommitmentFormat
    override def toString: String = "anchor_outputs"
  }
  case object AnchorOutputsZeroFeeHtlcTx extends SupportedChannelType {
    override def features: Set[Feature with InitFeature] = Set(Features.StaticRemoteKey, Features.AnchorOutputsZeroFeeHtlcTx)
    override def paysDirectlyToWallet: Boolean = false
    override def commitmentFormat: CommitmentFormat = ZeroFeeHtlcTxAnchorOutputsCommitmentFormat
    override def toString: String = "anchor_outputs_zero_fee_htlc_tx"
  }
  case class UnsupportedChannelType(featureBits: Features[InitFeature]) extends ChannelType {
    override def features: Set[Feature with InitFeature] = featureBits.activated.keySet
    override def toString: String = s"0x${featureBits.toByteVector.toHex}"
  }
  // @formatter:on

  private val features2ChannelType: Map[Features[InitFeature], SupportedChannelType] = Set(Standard, StaticRemoteKey, AnchorOutputs, AnchorOutputsZeroFeeHtlcTx)
    .map(channelType => Features[InitFeature](channelType.features.map(_ -> FeatureSupport.Mandatory).toMap) -> channelType)
    .toMap

  // NB: Bolt 2: features must exactly match in order to identify a channel type.
  def fromFeatures(features: Features[InitFeature]): ChannelType = features2ChannelType.getOrElse(features, UnsupportedChannelType(features))


  /** Pick the channel type based on local and remote feature bits, as defined by the spec. */
  def defaultFromFeatures(localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature]): SupportedChannelType = {
    if (Features.canUseFeature(localFeatures, remoteFeatures, Features.AnchorOutputsZeroFeeHtlcTx)) {
      AnchorOutputsZeroFeeHtlcTx
    } else if (Features.canUseFeature(localFeatures, remoteFeatures, Features.AnchorOutputs)) {
      AnchorOutputs
    } else if (Features.canUseFeature(localFeatures, remoteFeatures, Features.StaticRemoteKey)) {
      StaticRemoteKey
    } else {
      Standard
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
