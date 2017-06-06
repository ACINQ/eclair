package fr.acinq.eclair.router

import fr.acinq.bitcoin.{BinaryData, Satoshi}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}

/**
  * Created by PM on 02/02/2017.
  */
trait NetworkEvent

case class NodeDiscovered(ann: NodeAnnouncement) extends NetworkEvent

case class NodeUpdated(ann: NodeAnnouncement) extends NetworkEvent

case class NodeLost(nodeId: BinaryData) extends NetworkEvent

case class ChannelDiscovered(ann: ChannelAnnouncement, capacity: Satoshi) extends NetworkEvent

case class ChannelLost(channelId: Long) extends NetworkEvent

case class ChannelUpdateReceived(ann: ChannelUpdate) extends NetworkEvent
