package fr.acinq.eclair.integration.basic.channel

import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.channel.states.ChannelStateTestsTags
import fr.acinq.eclair.channel.{DATA_NORMAL, LocalFundingStatus, RES_SPLICE}
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture.{getChannelData, getPeerChannels, sendPayment, spliceIn}
import fr.acinq.eclair.integration.basic.fixtures.composite.ThreeNodesFixture
import fr.acinq.eclair.testutils.FixtureSpec
import fr.acinq.eclair.{FeatureSupport, Features, MilliSatoshiLong}
import org.scalatest.Inside.inside
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.{Tag, TestData}
import scodec.bits.HexStringSyntax

/**
 * These test checks the gossip sent between nodes by the Router
 */
class GossipIntegrationSpec extends FixtureSpec with IntegrationPatience {

  type FixtureParam = ThreeNodesFixture

  import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture.{connect, getRouterData, knownFundingTxs, nodeParamsFor, openChannel, watcherAutopilot}

  override def createFixture(testData: TestData): FixtureParam = {
    // seeds have been chosen so that node ids start with 02aaaa for alice, 02bbbb for bob, etc.
    val aliceParams = nodeParamsFor("alice", ByteVector32(hex"b4acd47335b25ab7b84b8c020997b12018592bb4631b868762154d77fa8b93a3"))
      .modify(_.features).using(_.add(Features.SplicePrototype, FeatureSupport.Optional))
    val bobParams = nodeParamsFor("bob", ByteVector32(hex"7620226fec887b0b2ebe76492e5a3fd3eb0e47cd3773263f6a81b59a704dc492"))
      .modify(_.features).using(_.add(Features.SplicePrototype, FeatureSupport.Optional))
    val carolParams = nodeParamsFor("carol", ByteVector32(hex"ebd5a5d3abfb3ef73731eb3418d918f247445183180522674666db98a66411cc"))
    ThreeNodesFixture(aliceParams, bobParams, carolParams, testData.name)
  }

  override def cleanupFixture(fixture: ThreeNodesFixture): Unit = {
    fixture.cleanup()
  }

  test("propagate channel_announcement and channel_update after splicing channel", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    connect(alice, bob)
    connect(bob, carol)

    // We put the watchers on auto pilot to confirm funding txs.
    alice.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol)))
    bob.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol)))
    carol.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol)))

    val channelId_ab = openChannel(alice, bob, 100_000 sat).channelId
    val channelId_bc = openChannel(bob, carol, 100_000 sat).channelId
    val channels = getPeerChannels(alice, bob.nodeId) ++ getPeerChannels(bob, carol.nodeId)
    assert(channels.map(_.data.channelId).toSet == Set(channelId_ab, channelId_bc))

    val scid_ab = eventually {
      assert(getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.isInstanceOf[LocalFundingStatus.ConfirmedFundingTx])
      assert(getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.isInstanceOf[LocalFundingStatus.ConfirmedFundingTx])
      val scid_ab = getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].commitments.latest.shortChannelId_opt.get
      // Wait for Alice to receive both initial local channel updates.
      inside(getRouterData(alice)) { routerData =>
        val channel_ab = routerData.channels(scid_ab)
        val receivedUpdates = Seq(channel_ab.update_1_opt, channel_ab.update_2_opt).flatten
        assert(receivedUpdates.count(_.shortChannelId == scid_ab) == 2)
      }
      scid_ab
    }

    // We splice in to increase the capacity of the alice->bob channel.
    val spliceTxId = spliceIn(alice, channelId_ab, 100_000 sat, None).asInstanceOf[RES_SPLICE].fundingTxId

    // The announcement for the splice transaction and the corresponding channel updates are broadcast.
    eventually {
      val channelData_alice = getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL]
      val channelData_bob = getChannelData(bob, channelId_ab).asInstanceOf[DATA_NORMAL]
      // The splice increased the channel capacity.
      assert(channelData_alice.commitments.latest.fundingTxId == spliceTxId)
      assert(channelData_alice.commitments.latest.capacity == 200_000.sat)
      assert(channelData_bob.commitments.latest.capacity == 200_000.sat)
      // The splice transaction changed the short_channel_id.
      val splice_scid_ab = channelData_alice.commitments.latest.shortChannelId_opt.get
      assert(splice_scid_ab != scid_ab)
      assert(channelData_bob.commitments.latest.shortChannelId_opt.contains(splice_scid_ab))
      val scid_bc = getChannelData(bob, channelId_bc).asInstanceOf[DATA_NORMAL].commitments.latest.shortChannelId_opt.get

      // Alice creates a channel_announcement for the splice transaction and updates the graph.
      val spliceAnn = inside(getRouterData(alice)) { routerData =>
        routerData.spentChannels match {
          case spentChannels if spentChannels.isEmpty =>
            assert(routerData.channels.keys == Set(splice_scid_ab, scid_bc))
          case spentChannels =>
            // Handle the special case where Alice receives ExternalChannelSpent after validating the local channel update
            // for the splice and treating it as a new channel; the original splice will be removed when the splice tx confirms.
            assert(spentChannels.contains(spliceTxId) && spentChannels(spliceTxId) == Set(scid_ab))
            assert(routerData.channels.keys == Set(splice_scid_ab, scid_bc, scid_ab))
        }
        val channel_ab = routerData.channels(splice_scid_ab)
        assert(channel_ab.capacity == 200_000.sat)
        assert(channel_ab.update_1_opt.nonEmpty && channel_ab.update_2_opt.nonEmpty)
        assert(channel_ab.meta_opt.nonEmpty)
        Seq(channel_ab.update_1_opt, channel_ab.update_2_opt).flatten.foreach(u => assert(u.shortChannelId == splice_scid_ab))
        val edge_ab_opt = routerData.graphWithBalances.graph.getEdgesBetween(alice.nodeId, bob.nodeId).find(_.desc.shortChannelId == splice_scid_ab)
        assert(edge_ab_opt.nonEmpty)
        assert(edge_ab_opt.get.capacity == 200_000.sat)
        assert(edge_ab_opt.get.balance_opt.get > 100_000_000.msat)
        channel_ab.ann
      }

      // Bob also creates a channel_announcement for the splice transaction and updates the graph.
      inside(getRouterData(bob)) { routerData =>
        routerData.spentChannels match {
          case spentChannels if spentChannels.isEmpty =>
            assert(routerData.channels.keys == Set(splice_scid_ab, scid_bc))
          case spentChannels =>
            // Handle the special case where Bob receives ExternalChannelSpent after validating the local channel update
            // for the splice and treating it as a new channel; the original splice will be removed when the splice tx confirms.
            assert(spentChannels.contains(spliceTxId) && spentChannels(spliceTxId) == Set(scid_ab))
            assert(routerData.channels.keys == Set(splice_scid_ab, scid_bc, scid_ab))
        }
        assert(routerData.channels.get(splice_scid_ab).map(_.ann).contains(spliceAnn))
        routerData.channels.get(splice_scid_ab).foreach(c => {
          assert(c.capacity == 200_000.sat)
          assert(c.fundingTxId == spliceTxId)
          assert(c.update_1_opt.nonEmpty && c.update_2_opt.nonEmpty)
          assert(c.meta_opt.nonEmpty)
          Seq(c.update_1_opt, c.update_2_opt).flatten.foreach(u => assert(u.shortChannelId == splice_scid_ab))
        })
        val edge_ab_opt = routerData.graphWithBalances.graph.getEdgesBetween(alice.nodeId, bob.nodeId).find(_.desc.shortChannelId == splice_scid_ab)
        assert(edge_ab_opt.get.capacity == 200_000.sat)
        assert(edge_ab_opt.get.balance_opt.get > 100_000_000.msat)
      }

      // The channel_announcement for the splice propagates to Carol.
      inside(getRouterData(carol)) { routerData =>
        routerData.spentChannels match {
          case spentChannels if spentChannels.isEmpty =>
            assert(routerData.channels.keys == Set(splice_scid_ab, scid_bc))
          case spentChannels =>
            // Handle the special case where Carol receives ExternalChannelSpent after validating the local channel update
            // for the splice and treating it as a new channel; the original splice will be removed when the splice tx confirms.
            assert(spentChannels.contains(spliceTxId) && spentChannels(spliceTxId) == Set(scid_ab))
            assert(routerData.channels.keys == Set(splice_scid_ab, scid_bc, scid_ab))
        }
        assert(routerData.channels.get(splice_scid_ab).map(_.ann).contains(spliceAnn))
        routerData.channels.get(splice_scid_ab).foreach(c => {
          assert(c.capacity == 200_000.sat)
          assert(c.fundingTxId == spliceTxId)
          assert(c.update_1_opt.nonEmpty && c.update_2_opt.nonEmpty)
          assert(c.meta_opt.isEmpty)
          Seq(c.update_1_opt, c.update_2_opt).flatten.foreach(u => assert(u.shortChannelId == splice_scid_ab))
        })
        val edge_ba_opt = routerData.graphWithBalances.graph.getEdgesBetween(bob.nodeId, alice.nodeId).find(_.desc.shortChannelId == splice_scid_ab)
        assert(edge_ba_opt.get.capacity == 200_000.sat)
        assert(edge_ba_opt.get.balance_opt.isEmpty)
      }
    }

    // Payments can be made using the spliced channel.
    assert(sendPayment(alice, carol, 75_000_000 msat).isRight)
    assert(sendPayment(carol, alice, 60_000_000 msat).isRight)
  }
}