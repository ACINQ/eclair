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

import fr.acinq.eclair.FeatureSupport._
import fr.acinq.eclair.Features._
import fr.acinq.eclair.channel.states.ChannelStateTestsHelperMethods
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.{Features, TestKitBaseClass}
import org.scalatest.funsuite.AnyFunSuiteLike

class ChannelFeaturesSpec extends TestKitBaseClass with AnyFunSuiteLike with ChannelStateTestsHelperMethods {

  test("channel features determines commitment format") {
    val standardChannel = ChannelFeatures()
    val staticRemoteKeyChannel = ChannelFeatures(Features.StaticRemoteKey)
    val anchorOutputsChannel = ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputs)
    val anchorOutputsZeroFeeHtlcsChannel = ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputsZeroFeeHtlcTx)

    assert(!standardChannel.hasFeature(Features.StaticRemoteKey))
    assert(!standardChannel.hasFeature(Features.AnchorOutputs))
    assert(standardChannel.commitmentFormat === Transactions.DefaultCommitmentFormat)
    assert(!standardChannel.paysDirectlyToWallet)

    assert(staticRemoteKeyChannel.hasFeature(Features.StaticRemoteKey))
    assert(!staticRemoteKeyChannel.hasFeature(Features.AnchorOutputs))
    assert(staticRemoteKeyChannel.commitmentFormat === Transactions.DefaultCommitmentFormat)
    assert(staticRemoteKeyChannel.paysDirectlyToWallet)

    assert(anchorOutputsChannel.hasFeature(Features.StaticRemoteKey))
    assert(anchorOutputsChannel.hasFeature(Features.AnchorOutputs))
    assert(!anchorOutputsChannel.hasFeature(Features.AnchorOutputsZeroFeeHtlcTx))
    assert(anchorOutputsChannel.commitmentFormat === Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat)
    assert(!anchorOutputsChannel.paysDirectlyToWallet)

    assert(anchorOutputsZeroFeeHtlcsChannel.hasFeature(Features.StaticRemoteKey))
    assert(anchorOutputsZeroFeeHtlcsChannel.hasFeature(Features.AnchorOutputsZeroFeeHtlcTx))
    assert(!anchorOutputsZeroFeeHtlcsChannel.hasFeature(Features.AnchorOutputs))
    assert(anchorOutputsZeroFeeHtlcsChannel.commitmentFormat === Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    assert(!anchorOutputsZeroFeeHtlcsChannel.paysDirectlyToWallet)
  }

  test("pick channel type based on local and remote features") {
    case class TestCase(localFeatures: Features, remoteFeatures: Features, expectedChannelType: ChannelType)
    val testCases = Seq(
      TestCase(Features.empty, Features.empty, ChannelTypes.Standard),
      TestCase(Features(StaticRemoteKey -> Optional), Features.empty, ChannelTypes.Standard),
      TestCase(Features.empty, Features(StaticRemoteKey -> Optional), ChannelTypes.Standard),
      TestCase(Features.empty, Features(StaticRemoteKey -> Mandatory), ChannelTypes.Standard),
      TestCase(Features(StaticRemoteKey -> Optional, Wumbo -> Mandatory), Features(Wumbo -> Mandatory), ChannelTypes.Standard),
      TestCase(Features(StaticRemoteKey -> Optional), Features(StaticRemoteKey -> Optional), ChannelTypes.StaticRemoteKey),
      TestCase(Features(StaticRemoteKey -> Optional), Features(StaticRemoteKey -> Mandatory), ChannelTypes.StaticRemoteKey),
      TestCase(Features(StaticRemoteKey -> Optional, Wumbo -> Optional), Features(StaticRemoteKey -> Mandatory, Wumbo -> Mandatory), ChannelTypes.StaticRemoteKey),
      TestCase(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional), Features(StaticRemoteKey -> Optional), ChannelTypes.StaticRemoteKey),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional), ChannelTypes.StaticRemoteKey),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional), ChannelTypes.AnchorOutputs),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional), ChannelTypes.AnchorOutputs),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional), ChannelTypes.AnchorOutputsZeroFeeHtlcTx),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Optional), ChannelTypes.AnchorOutputsZeroFeeHtlcTx),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Mandatory), ChannelTypes.AnchorOutputsZeroFeeHtlcTx),
    )

    for (testCase <- testCases) {
      assert(ChannelTypes.pickChannelType(testCase.localFeatures, testCase.remoteFeatures) === testCase.expectedChannelType)
    }
  }

  test("create channel type from features") {
    val validChannelTypes = Seq(
      Features.empty -> ChannelTypes.Standard,
      Features(StaticRemoteKey -> Mandatory) -> ChannelTypes.StaticRemoteKey,
      Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Mandatory) -> ChannelTypes.AnchorOutputs,
      Features(StaticRemoteKey -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Mandatory) -> ChannelTypes.AnchorOutputsZeroFeeHtlcTx,
    )
    for ((features, expected) <- validChannelTypes) {
      assert(ChannelTypes.fromFeatures(features) === expected)
    }

    val invalidChannelTypes = Seq(
      Features(Wumbo -> Optional),
      Features(StaticRemoteKey -> Optional),
      Features(StaticRemoteKey -> Mandatory, Wumbo -> Optional),
      Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional),
      Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional),
      Features(StaticRemoteKey -> Optional, AnchorOutputs -> Mandatory),
      Features(StaticRemoteKey -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional),
      Features(StaticRemoteKey -> Optional, AnchorOutputsZeroFeeHtlcTx -> Mandatory),
      Features(StaticRemoteKey -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Optional),
      Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Mandatory),
      Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Mandatory),
      Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Mandatory, Wumbo -> Optional),
    )
    for (features <- invalidChannelTypes) {
      assert(ChannelTypes.fromFeatures(features) === ChannelTypes.UnsupportedChannelType(features))
    }
  }

  test("enrich channel type with other permanent channel features") {
    assert(ChannelFeatures(ChannelTypes.Standard, Features(Wumbo -> Optional), Features.empty).activated.isEmpty)
    assert(ChannelFeatures(ChannelTypes.Standard, Features(Wumbo -> Optional), Features(Wumbo -> Optional)).activated === Set(Wumbo))
    assert(ChannelFeatures(ChannelTypes.Standard, Features(Wumbo -> Mandatory), Features(Wumbo -> Optional)).activated === Set(Wumbo))
    assert(ChannelFeatures(ChannelTypes.StaticRemoteKey, Features(Wumbo -> Optional), Features.empty).activated === Set(StaticRemoteKey))
    assert(ChannelFeatures(ChannelTypes.StaticRemoteKey, Features(Wumbo -> Optional), Features(Wumbo -> Optional)).activated === Set(StaticRemoteKey, Wumbo))
    assert(ChannelFeatures(ChannelTypes.AnchorOutputs, Features.empty, Features(Wumbo -> Optional)).activated === Set(StaticRemoteKey, AnchorOutputs))
    assert(ChannelFeatures(ChannelTypes.AnchorOutputs, Features(Wumbo -> Optional), Features(Wumbo -> Mandatory)).activated === Set(StaticRemoteKey, AnchorOutputs, Wumbo))
    assert(ChannelFeatures(ChannelTypes.AnchorOutputsZeroFeeHtlcTx, Features.empty, Features(Wumbo -> Optional)).activated === Set(StaticRemoteKey, AnchorOutputsZeroFeeHtlcTx))
    assert(ChannelFeatures(ChannelTypes.AnchorOutputsZeroFeeHtlcTx, Features(Wumbo -> Optional), Features(Wumbo -> Mandatory)).activated === Set(StaticRemoteKey, AnchorOutputsZeroFeeHtlcTx, Wumbo))
  }

}
