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
import fr.acinq.eclair.{Feature, Features}

/**
 * Created by t-bast on 24/06/2021.
 */

/**
 * Subset of Bolt 9 features used to configure a channel and applicable over the lifetime of that channel.
 * Even if one of these features is later disabled at the connection level, it will still apply to the channel until the
 * channel is upgraded or closed.
 */
case class ChannelFeatures(activated: Set[Feature]) {

  /** True if our main output in the remote commitment is directly sent (without any delay) to one of our wallet addresses. */
  val paysDirectlyToWallet: Boolean = {
    hasFeature(Features.StaticRemoteKey) && !hasFeature(Features.AnchorOutputs)
  }

  /** Format of the channel transactions. */
  val commitmentFormat: CommitmentFormat = {
    if (hasFeature(AnchorOutputs)) {
      AnchorOutputsCommitmentFormat
    } else {
      DefaultCommitmentFormat
    }
  }

  def hasFeature(feature: Feature): Boolean = activated.contains(feature)

  override def toString: String = activated.mkString(",")

}

object ChannelFeatures {

  def apply(features: Feature*): ChannelFeatures = ChannelFeatures(Set.from(features))

  /** Pick the channel features that should be used based on local and remote feature bits. */
  def pickChannelFeatures(localFeatures: Features, remoteFeatures: Features): ChannelFeatures = {
    // NB: we don't include features that can be safely activated/deactivated without impacting the channel's operation,
    // such as option_dataloss_protect or option_shutdown_anysegwit.
    val availableFeatures = Set[Feature](
      StaticRemoteKey,
      Wumbo,
      AnchorOutputs,
      OptionUpfrontShutdownScript
    ).filter(f => Features.canUseFeature(localFeatures, remoteFeatures, f))

    ChannelFeatures(availableFeatures)
  }

}
