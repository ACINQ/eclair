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

import fr.acinq.eclair.FeatureSupport.Optional
import fr.acinq.eclair.Features
import fr.acinq.eclair.Features.{AnchorOutputs, StaticRemoteKey}
import fr.acinq.eclair.transactions.Transactions.{AnchorOutputsCommitmentFormat, CommitmentFormat, DefaultCommitmentFormat}

/**
 * Created by t-bast on 24/06/2021.
 */

/**
 * A channel type is a specific combination of Bolt 9 features, defined in the RFC (Bolt 2).
 */
case class ChannelType(features: Features) {

  val commitmentFormat: CommitmentFormat = {
    if (features.hasFeature(AnchorOutputs)) {
      AnchorOutputsCommitmentFormat
    } else {
      DefaultCommitmentFormat
    }
  }

  /**
   * True if our main output in the remote commitment is directly sent (without any delay) to one of our wallet addresses.
   */
  def paysDirectlyToWallet: Boolean = {
    features.hasFeature(Features.StaticRemoteKey) && !features.hasFeature(Features.AnchorOutputs)
  }

}

object ChannelTypes {

  val standard = ChannelType(Features.empty)
  val staticRemoteKey = ChannelType(Features(StaticRemoteKey -> Optional))
  val anchorOutputs = ChannelType(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional))

  /**
   * Pick the channel type that should be applied based on features alone (in case our peer doesn't support explicit channel type negotiation).
   */
  def pickChannelType(localFeatures: Features, remoteFeatures: Features): ChannelType = {
    if (Features.canUseFeature(localFeatures, remoteFeatures, AnchorOutputs)) {
      anchorOutputs
    } else if (Features.canUseFeature(localFeatures, remoteFeatures, StaticRemoteKey)) {
      staticRemoteKey
    } else {
      standard
    }
  }

}
