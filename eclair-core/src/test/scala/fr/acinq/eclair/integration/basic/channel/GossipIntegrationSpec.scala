package fr.acinq.eclair.integration.basic.channel

import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.channel.states.ChannelStateTestsTags
import fr.acinq.eclair.channel.{DATA_NORMAL, RES_SPLICE}
import fr.acinq.eclair.integration.basic.ThreeNodesIntegrationSpec
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture.{getChannelData, getPeerChannels, spliceIn}
import fr.acinq.eclair.integration.basic.fixtures.composite.ThreeNodesFixture
import fr.acinq.eclair.{FeatureSupport, Features}
import org.scalatest.{Tag, TestData}
import scodec.bits.HexStringSyntax

/**
 * These test checks the gossip sent between nodes by the Router
 */
class GossipIntegrationSpec extends ThreeNodesIntegrationSpec {

  import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture.{connect, getRouterData, knownFundingTxs, nodeParamsFor, openChannel, watcherAutopilot}

  override def createFixture(testData: TestData): FixtureParam = {
    // seeds have been chosen so that node ids start with 02aaaa for alice, 02bbbb for bob, etc.
    val aliceParams = nodeParamsFor("alice", ByteVector32(hex"b4acd47335b25ab7b84b8c020997b12018592bb4631b868762154d77fa8b93a3"))
    val aliceParams1 = aliceParams.copy(
      features = aliceParams.features.add(Features.SplicePrototype, FeatureSupport.Optional)
    )
    val bobParams = nodeParamsFor("bob", ByteVector32(hex"7620226fec887b0b2ebe76492e5a3fd3eb0e47cd3773263f6a81b59a704dc492"))
    val bobParams1 = bobParams.copy(
      features = bobParams.features.add(Features.SplicePrototype, FeatureSupport.Optional)
    )
    val carolParams = nodeParamsFor("carol", ByteVector32(hex"ebd5a5d3abfb3ef73731eb3418d918f247445183180522674666db98a66411cc"))
    ThreeNodesFixture(aliceParams1, bobParams1, carolParams, testData.name)
  }

  override def cleanupFixture(fixture: ThreeNodesFixture): Unit = {
    fixture.cleanup()
  }

  test("send gossip when alice->bob channel is spliced", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    connect(alice, bob)
    connect(bob, carol)

    // we put watchers on auto pilot to confirm funding txs
    alice.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol)))
    bob.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol)))
    carol.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol)))

    val channelId_ab = openChannel(alice, bob, 100_000 sat).channelId
    val channelId_bc = openChannel(bob, carol, 100_000 sat).channelId
    val channels = getPeerChannels(alice, bob.nodeId) ++ getPeerChannels(bob, carol.nodeId)
    assert(channels.map(_.data.channelId).toSet == Set(channelId_ab, channelId_bc))

    // channels confirm deeply
    eventually {
      assert(getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].shortIds.real_opt.nonEmpty)
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].shortIds.real_opt.nonEmpty)
    }
    val scid_ab = getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].shortIds.real_opt.get
    val scid_bc = getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].shortIds.real_opt.get

    // splice in to increase capacity of alice->bob channel
    spliceIn(alice, channelId_ab, 100_000 sat, None).asInstanceOf[RES_SPLICE].fundingTxId

    // verify that the new capacity and scid are correctly propagated
    eventually {
      val channelData_alice1 = getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL]
      val channelData_bob1 = getChannelData(bob, channelId_ab).asInstanceOf[DATA_NORMAL]
      assert(channelData_alice1.commitments.latest.capacity == 200_000.sat)
      assert(channelData_bob1.commitments.latest.capacity == 200_000.sat)
      assert(channelData_alice1.shortIds.real_opt.get == channelData_bob1.shortIds.real_opt.get)
      val scid_ab1 = getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].shortIds.real_opt.get
      val ann_splice = getRouterData(alice).channels(scid_ab1)
      assert(ann_splice.capacity == 200_000.sat)
      assert(getRouterData(bob).channels(scid_ab1) == ann_splice)
      // TODO: after PR 2941, the slice ChannelAnnouncement will have a new scid and not be ignore by carol
      assert(getRouterData(carol).spentChannels.exists(_._2 == ann_splice.shortChannelId))
      // assert(scid_ab != scid_ab1)
      // assert(getRouterData(carol).channels(scid_ab1).capacity == 200_000.sat)
    }

  }
}