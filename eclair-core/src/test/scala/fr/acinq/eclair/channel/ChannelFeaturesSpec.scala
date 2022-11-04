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
import fr.acinq.eclair.channel.states.ChannelStateTestsBase
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.{Feature, Features, InitFeature, TestKitBaseClass}
import org.scalatest.funsuite.AnyFunSuiteLike

class ChannelFeaturesSpec extends TestKitBaseClass with AnyFunSuiteLike with ChannelStateTestsBase {

  test("channel features determines commitment format") {
    val standardChannel = ChannelFeatures()
    val staticRemoteKeyChannel = ChannelFeatures(Features.StaticRemoteKey)
    val anchorOutputsChannel = ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputs)
    val anchorOutputsZeroFeeHtlcsChannel = ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputsZeroFeeHtlcTx)

    assert(!standardChannel.hasFeature(Features.StaticRemoteKey))
    assert(!standardChannel.hasFeature(Features.AnchorOutputs))
    assert(standardChannel.commitmentFormat == Transactions.DefaultCommitmentFormat)
    assert(!standardChannel.paysDirectlyToWallet)

    assert(staticRemoteKeyChannel.hasFeature(Features.StaticRemoteKey))
    assert(!staticRemoteKeyChannel.hasFeature(Features.AnchorOutputs))
    assert(staticRemoteKeyChannel.commitmentFormat == Transactions.DefaultCommitmentFormat)
    assert(staticRemoteKeyChannel.paysDirectlyToWallet)

    assert(anchorOutputsChannel.hasFeature(Features.StaticRemoteKey))
    assert(anchorOutputsChannel.hasFeature(Features.AnchorOutputs))
    assert(!anchorOutputsChannel.hasFeature(Features.AnchorOutputsZeroFeeHtlcTx))
    assert(anchorOutputsChannel.commitmentFormat == Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat)
    assert(!anchorOutputsChannel.paysDirectlyToWallet)

    assert(anchorOutputsZeroFeeHtlcsChannel.hasFeature(Features.StaticRemoteKey))
    assert(anchorOutputsZeroFeeHtlcsChannel.hasFeature(Features.AnchorOutputsZeroFeeHtlcTx))
    assert(!anchorOutputsZeroFeeHtlcsChannel.hasFeature(Features.AnchorOutputs))
    assert(anchorOutputsZeroFeeHtlcsChannel.commitmentFormat == Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    assert(!anchorOutputsZeroFeeHtlcsChannel.paysDirectlyToWallet)
  }

  test("pick channel type based on local and remote features") {
    case class TestCase(localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature], announceChannel: Boolean, expectedChannelType: ChannelType)
    val testCases = Seq(
      TestCase(Features.empty, Features.empty, announceChannel = true, ChannelTypes.Standard()),
      TestCase(Features(ScidAlias -> Optional), Features(ScidAlias -> Optional), announceChannel = false, ChannelTypes.Standard(scidAlias = true)),
      TestCase(Features(StaticRemoteKey -> Optional), Features.empty, announceChannel = true, ChannelTypes.Standard()),
      TestCase(Features.empty, Features(StaticRemoteKey -> Optional), announceChannel = true, ChannelTypes.Standard()),
      TestCase(Features.empty, Features(StaticRemoteKey -> Mandatory), announceChannel = true, ChannelTypes.Standard()),
      TestCase(Features(StaticRemoteKey -> Optional, Wumbo -> Mandatory), Features(Wumbo -> Mandatory), announceChannel = true, ChannelTypes.Standard()),
      TestCase(Features(StaticRemoteKey -> Optional), Features(StaticRemoteKey -> Optional), announceChannel = true, ChannelTypes.StaticRemoteKey()),
      TestCase(Features(StaticRemoteKey -> Optional), Features(StaticRemoteKey -> Mandatory), announceChannel = true, ChannelTypes.StaticRemoteKey()),
      TestCase(Features(StaticRemoteKey -> Optional, ScidAlias -> Mandatory), Features(StaticRemoteKey -> Mandatory, ScidAlias -> Mandatory), announceChannel = false, ChannelTypes.StaticRemoteKey(scidAlias = true)),
      TestCase(Features(StaticRemoteKey -> Optional, Wumbo -> Optional), Features(StaticRemoteKey -> Mandatory, Wumbo -> Mandatory), announceChannel = true, ChannelTypes.StaticRemoteKey()),
      TestCase(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional), Features(StaticRemoteKey -> Optional), announceChannel = true, ChannelTypes.StaticRemoteKey()),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional), announceChannel = true, ChannelTypes.StaticRemoteKey()),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional), announceChannel = true, ChannelTypes.AnchorOutputs()),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, ScidAlias -> Optional, ZeroConf -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, ScidAlias -> Mandatory, ZeroConf -> Optional), announceChannel = false, ChannelTypes.AnchorOutputs(scidAlias = true, zeroConf = true)),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional), announceChannel = true, ChannelTypes.AnchorOutputs()),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional), announceChannel = true, ChannelTypes.AnchorOutputsZeroFeeHtlcTx()),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Optional), announceChannel = true, ChannelTypes.AnchorOutputsZeroFeeHtlcTx()),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Mandatory), announceChannel = true, ChannelTypes.AnchorOutputsZeroFeeHtlcTx()),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, Features.ScidAlias -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional), announceChannel = true, ChannelTypes.AnchorOutputsZeroFeeHtlcTx()),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, Features.ScidAlias -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, Features.ScidAlias -> Optional), announceChannel = true, ChannelTypes.AnchorOutputsZeroFeeHtlcTx()),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, Features.ScidAlias -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, Features.ScidAlias -> Optional), announceChannel = false, ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true)),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, Features.ScidAlias -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, Features.ZeroConf -> Optional), announceChannel = true, ChannelTypes.AnchorOutputsZeroFeeHtlcTx()),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, Features.ZeroConf -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, Features.ZeroConf -> Optional), announceChannel = true, ChannelTypes.AnchorOutputsZeroFeeHtlcTx(zeroConf = true)),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, Features.ScidAlias -> Mandatory, Features.ZeroConf -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, Features.ScidAlias -> Optional, Features.ZeroConf -> Optional), announceChannel = true, ChannelTypes.AnchorOutputsZeroFeeHtlcTx(zeroConf = true)),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, Features.ScidAlias -> Mandatory, Features.ZeroConf -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, Features.ScidAlias -> Optional, Features.ZeroConf -> Optional), announceChannel = false, ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true, zeroConf = true)),
    )

    for (testCase <- testCases) {
      assert(ChannelTypes.defaultFromFeatures(testCase.localFeatures, testCase.remoteFeatures, announceChannel = testCase.announceChannel) == testCase.expectedChannelType, s"localFeatures=${testCase.localFeatures} remoteFeatures=${testCase.remoteFeatures}")
    }
  }

  test("create channel type from features") {
    case class TestCase(features: Features[InitFeature], expectedChannelType: ChannelType)

    val validChannelTypes = Seq(
      TestCase(Features.empty, ChannelTypes.Standard()),
      TestCase(Features(ScidAlias -> Mandatory), ChannelTypes.Standard(scidAlias = true)),
      TestCase(Features(StaticRemoteKey -> Mandatory), ChannelTypes.StaticRemoteKey()),
      TestCase(Features(StaticRemoteKey -> Mandatory, ScidAlias -> Mandatory), ChannelTypes.StaticRemoteKey(scidAlias = true)),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Mandatory), ChannelTypes.AnchorOutputs()),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Mandatory, ScidAlias -> Mandatory, ZeroConf -> Mandatory), ChannelTypes.AnchorOutputs(scidAlias = true, zeroConf = true)),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Mandatory), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Mandatory, ScidAlias -> Mandatory), ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true)),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Mandatory, ZeroConf -> Mandatory), ChannelTypes.AnchorOutputsZeroFeeHtlcTx(zeroConf = true)),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Mandatory, ScidAlias -> Mandatory, ZeroConf -> Mandatory), ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true, zeroConf = true)),
    )
    for (testCase <- validChannelTypes) {
      assert(ChannelTypes.fromFeatures(testCase.features) == testCase.expectedChannelType, testCase.features)
    }

    val invalidChannelTypes: Seq[Features[InitFeature]] = Seq(
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
      assert(ChannelTypes.fromFeatures(features) == ChannelTypes.UnsupportedChannelType(features), features)
    }
  }

  test("enrich channel type with optional permanent channel features") {
    case class TestCase(channelType: SupportedChannelType, localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature], announceChannel: Boolean, expected: Set[Feature])
    val testCases = Seq(
      TestCase(ChannelTypes.Standard(), Features(Wumbo -> Optional), Features.empty, announceChannel = true, Set.empty),
      TestCase(ChannelTypes.Standard(), Features(Wumbo -> Optional), Features(Wumbo -> Optional), announceChannel = true, Set(Wumbo)),
      TestCase(ChannelTypes.Standard(), Features(Wumbo -> Mandatory), Features(Wumbo -> Optional), announceChannel = true, Set(Wumbo)),
      TestCase(ChannelTypes.StaticRemoteKey(), Features(Wumbo -> Optional), Features.empty, announceChannel = true, Set(StaticRemoteKey)),
      TestCase(ChannelTypes.StaticRemoteKey(), Features(Wumbo -> Optional), Features(Wumbo -> Optional), announceChannel = true, Set(StaticRemoteKey, Wumbo)),
      TestCase(ChannelTypes.AnchorOutputs(), Features.empty, Features(Wumbo -> Optional), announceChannel = true, Set(StaticRemoteKey, AnchorOutputs)),
      TestCase(ChannelTypes.AnchorOutputs(), Features(Wumbo -> Optional), Features(Wumbo -> Mandatory), announceChannel = true, Set(StaticRemoteKey, AnchorOutputs, Wumbo)),
      TestCase(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), Features.empty, Features(Wumbo -> Optional), announceChannel = true, Set(StaticRemoteKey, AnchorOutputsZeroFeeHtlcTx)),
      TestCase(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), Features(ScidAlias -> Optional, ZeroConf -> Optional), Features(ScidAlias -> Optional, ZeroConf -> Optional), announceChannel = true, Set(StaticRemoteKey, AnchorOutputsZeroFeeHtlcTx, ZeroConf)),
      TestCase(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), Features(ScidAlias -> Optional, ZeroConf -> Optional), Features(ScidAlias -> Optional, ZeroConf -> Optional), announceChannel = false, Set(StaticRemoteKey, AnchorOutputsZeroFeeHtlcTx, ScidAlias, ZeroConf)),
      TestCase(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true), Features.empty, Features(Wumbo -> Optional), announceChannel = false, Set(StaticRemoteKey, AnchorOutputsZeroFeeHtlcTx, ScidAlias)),
      TestCase(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true, zeroConf = true), Features.empty, Features(Wumbo -> Optional), announceChannel = false, Set(StaticRemoteKey, AnchorOutputsZeroFeeHtlcTx, ScidAlias, ZeroConf)),
      TestCase(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), Features(Wumbo -> Optional), Features(Wumbo -> Mandatory), announceChannel = true, Set(StaticRemoteKey, AnchorOutputsZeroFeeHtlcTx, Wumbo)),
      TestCase(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), Features(DualFunding -> Optional, Wumbo -> Optional), Features(DualFunding -> Optional, Wumbo -> Optional), announceChannel = true, Set(StaticRemoteKey, AnchorOutputsZeroFeeHtlcTx, Wumbo, DualFunding)),
    )
    testCases.foreach(t => assert(ChannelFeatures(t.channelType, t.localFeatures, t.remoteFeatures, t.announceChannel).features == t.expected, s"channelType=${t.channelType} localFeatures=${t.localFeatures} remoteFeatures=${t.remoteFeatures}"))
  }

}
