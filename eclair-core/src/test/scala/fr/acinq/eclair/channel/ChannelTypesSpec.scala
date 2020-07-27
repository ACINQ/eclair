package fr.acinq.eclair.channel

import fr.acinq.eclair.transactions.Transactions
import org.scalatest.funsuite.AnyFunSuite

class ChannelTypesSpec extends AnyFunSuite {

  test("standard channel features include deterministic channel key path") {
    assert(!ChannelVersion.ZEROES.hasPubkeyKeyPath)
    assert(ChannelVersion.STANDARD.hasPubkeyKeyPath)
    assert(ChannelVersion.STATIC_REMOTEKEY.hasStaticRemotekey)
    assert(ChannelVersion.STATIC_REMOTEKEY.hasPubkeyKeyPath)
  }

  test("anchor outputs includes static remote key") {
    assert(ChannelVersion.ANCHOR_OUTPUTS.hasPubkeyKeyPath)
    assert(ChannelVersion.ANCHOR_OUTPUTS.hasStaticRemotekey)
  }

  test("channel version determines commitment format") {
    assert(ChannelVersion.ZEROES.commitmentFormat === Transactions.DefaultCommitmentFormat)
    assert(ChannelVersion.STANDARD.commitmentFormat === Transactions.DefaultCommitmentFormat)
    assert(ChannelVersion.STATIC_REMOTEKEY.commitmentFormat === Transactions.DefaultCommitmentFormat)
    assert(ChannelVersion.ANCHOR_OUTPUTS.commitmentFormat === Transactions.AnchorOutputsCommitmentFormat)
  }

  test("pick channel version based on local and remote features") {
    import fr.acinq.eclair.FeatureSupport._
    import fr.acinq.eclair.Features._
    import fr.acinq.eclair.{ActivatedFeature, Features}

    case class TestCase(localFeatures: Features, remoteFeatures: Features, expectedChannelVersion: ChannelVersion)
    val testCases = Seq(
      TestCase(Features.empty, Features.empty, ChannelVersion.STANDARD),
      TestCase(Features(Set(ActivatedFeature(StaticRemoteKey, Optional))), Features.empty, ChannelVersion.STANDARD),
      TestCase(Features.empty, Features(Set(ActivatedFeature(StaticRemoteKey, Optional))), ChannelVersion.STANDARD),
      TestCase(Features(Set(ActivatedFeature(StaticRemoteKey, Optional))), Features(Set(ActivatedFeature(StaticRemoteKey, Optional))), ChannelVersion.STATIC_REMOTEKEY),
      TestCase(Features(Set(ActivatedFeature(StaticRemoteKey, Optional))), Features(Set(ActivatedFeature(StaticRemoteKey, Mandatory))), ChannelVersion.STATIC_REMOTEKEY),
      TestCase(Features(Set(ActivatedFeature(StaticRemoteKey, Optional), ActivatedFeature(AnchorOutputs, Optional))), Features(Set(ActivatedFeature(StaticRemoteKey, Optional))), ChannelVersion.STATIC_REMOTEKEY),
      TestCase(Features(Set(ActivatedFeature(StaticRemoteKey, Mandatory), ActivatedFeature(AnchorOutputs, Optional))), Features(Set(ActivatedFeature(StaticRemoteKey, Optional), ActivatedFeature(AnchorOutputs, Optional))), ChannelVersion.ANCHOR_OUTPUTS)
    )

    for (testCase <- testCases) {
      assert(ChannelVersion.pickChannelVersion(testCase.localFeatures, testCase.remoteFeatures) === testCase.expectedChannelVersion)
    }
  }

}
