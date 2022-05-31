package fr.acinq.eclair.router.graph.structure

import fr.acinq.bitcoin.scalacompat.{Satoshi, SatoshiLong}
import fr.acinq.eclair.{MilliSatoshi, ToMilliSatoshiConversion}
import fr.acinq.eclair.router.Router.{AssistedChannel, ChannelDesc, ChannelRelayParams, PrivateChannel, PublicChannel}
import fr.acinq.eclair.wire.protocol.ChannelUpdate
import fr.acinq.eclair._


/**
 * Representation of an edge of the graph
 *
 * @param desc        channel description
 * @param params      source of the channel parameters: can be a channel_update or hints from an invoice
 * @param capacity    channel capacity
 * @param balance_opt (optional) available balance that can be sent through this edge
 */
case class GraphEdge private(desc: ChannelDesc, params: ChannelRelayParams, capacity: Satoshi, balance_opt: Option[MilliSatoshi]) {

  def maxHtlcAmount(reservedCapacity: MilliSatoshi): MilliSatoshi = Seq(
    balance_opt.map(balance => balance - reservedCapacity),
    params.htlcMaximum_opt,
    Some(capacity.toMilliSatoshi - reservedCapacity)
  ).flatten.min.max(0 msat)

  def fee(amount: MilliSatoshi): MilliSatoshi = params.fee(amount)
}

object GraphEdge {
  def apply(u: ChannelUpdate, pc: PublicChannel): GraphEdge = GraphEdge(
    desc = ChannelDesc(u, pc.ann),
    params = ChannelRelayParams.FromAnnouncement(u),
    capacity = pc.capacity,
    balance_opt = pc.getBalanceSameSideAs(u)
  )

  def apply(u: ChannelUpdate, pc: PrivateChannel): GraphEdge = GraphEdge(
    desc = ChannelDesc(u, pc),
    params = ChannelRelayParams.FromAnnouncement(u),
    capacity = pc.capacity,
    balance_opt = pc.getBalanceSameSideAs(u)
  )

  def apply(ac: AssistedChannel): GraphEdge = GraphEdge(
    desc = ChannelDesc(ac.shortChannelId, ac.nodeId, ac.nextNodeId),
    params = ac.params,
    // Bolt 11 routing hints don't include the channel's capacity, so we round up the maximum htlc amount
    capacity = ac.params.htlcMaximum.truncateToSatoshi + 1.sat,
    // we assume channels provided as hints have enough balance to handle the payment
    balance_opt = Some(ac.params.htlcMaximum)
  )
}
