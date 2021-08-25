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

import fr.acinq.eclair.Features.{AnchorOutputs, OptionUpfrontShutdownScript, StaticRemoteKey, Wumbo}
import fr.acinq.eclair.transactions.Transactions.{AnchorOutputsCommitmentFormat, CommitmentFormat, DefaultCommitmentFormat}
import fr.acinq.eclair.{Feature, FeatureSupport, Features}

/**
 * Created by t-bast on 24/06/2021.
 */

/**
 * Subset of Bolt 9 features used to configure a channel and applicable over the lifetime of that channel.
 * Even if one of these features is later disabled at the connection level, it will still apply to the channel until the
 * channel is upgraded or closed.
 */
case class ChannelFeatures(activated: Set[Feature]) {

  /** Format of the channel transactions. */
  val commitmentFormat: CommitmentFormat = {
    if (hasFeature(AnchorOutputs)) {
      AnchorOutputsCommitmentFormat
    } else {
      DefaultCommitmentFormat
    }
  }

  val channelType: SupportedChannelType = {
    if (hasFeature(AnchorOutputs)) {
      ChannelTypes.AnchorOutputs
    } else if (hasFeature(StaticRemoteKey)) {
      ChannelTypes.StaticRemoteKey
    } else {
      ChannelTypes.Standard
    }
  }

  val paysDirectlyToWallet: Boolean = channelType.paysDirectlyToWallet

  def hasFeature(feature: Feature): Boolean = activated.contains(feature)

  override def toString: String = activated.mkString(",")

}

object ChannelFeatures {

  def apply(features: Feature*): ChannelFeatures = ChannelFeatures(Set.from(features))

  /** Enrich the channel type with other permanent features that will be applied to the channel. */
  def apply(channelType: ChannelType, localFeatures: Features, remoteFeatures: Features): ChannelFeatures = {
    // NB: we don't include features that can be safely activated/deactivated without impacting the channel's operation,
    // such as option_dataloss_protect or option_shutdown_anysegwit.
    val availableFeatures: Seq[Feature] = Seq(Wumbo, OptionUpfrontShutdownScript).filter(f => Features.canUseFeature(localFeatures, remoteFeatures, f))
    val allFeatures = channelType.features.toSeq ++ availableFeatures
    ChannelFeatures(allFeatures: _*)
  }

}

/** A channel type is a specific set of even feature bits that represent persistent channel features as defined in Bolt 2. */
sealed trait ChannelType {
  /** Features representing that channel type. */
  def features: Set[Feature]
}

sealed trait SupportedChannelType extends ChannelType {
  /** True if our main output in the remote commitment is directly sent (without any delay) to one of our wallet addresses. */
  def paysDirectlyToWallet: Boolean
}

object ChannelTypes {

  // @formatter:off
  case object Standard extends SupportedChannelType {
    override def features: Set[Feature] = Set.empty
    override def paysDirectlyToWallet: Boolean = false
    override def toString: String = "standard"
  }
  case object StaticRemoteKey extends SupportedChannelType {
    override def features: Set[Feature] = Set(Features.StaticRemoteKey)
    override def paysDirectlyToWallet: Boolean = true
    override def toString: String = "static_remotekey"
  }
  case object AnchorOutputs extends SupportedChannelType {
    override def features: Set[Feature] = Set(Features.StaticRemoteKey, Features.AnchorOutputs)
    override def paysDirectlyToWallet: Boolean = false
    override def toString: String = "anchor_outputs"
  }
  case class UnsupportedChannelType(featureBits: Features) extends ChannelType {
    override def features: Set[Feature] = featureBits.activated.keySet
    override def toString: String = s"0x${featureBits.toByteVector.toHex}"
  }
  // @formatter:on

  // NB: Bolt 2: features must exactly match in order to identify a channel type.
  def fromFeatures(features: Features): ChannelType = features match {
    case f if f == Features(Features.StaticRemoteKey -> FeatureSupport.Mandatory, Features.AnchorOutputs -> FeatureSupport.Mandatory) => AnchorOutputs
    case f if f == Features(Features.StaticRemoteKey -> FeatureSupport.Mandatory) => StaticRemoteKey
    case f if f == Features.empty => Standard
    case _ => UnsupportedChannelType(features)
  }

  /** Pick the channel type based on local and remote feature bits. */
  def pickChannelType(localFeatures: Features, remoteFeatures: Features): SupportedChannelType = {
    if (Features.canUseFeature(localFeatures, remoteFeatures, Features.AnchorOutputs)) {
      AnchorOutputs
    } else if (Features.canUseFeature(localFeatures, remoteFeatures, Features.StaticRemoteKey)) {
      StaticRemoteKey
    } else {
      Standard
    }
  }

}
