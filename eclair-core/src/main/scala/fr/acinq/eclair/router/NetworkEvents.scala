package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}

/**
  * Created by PM on 02/02/2017.
  */
trait NetworkEvent

case class NodeDiscovered(ann: NodeAnnouncement) extends NetworkEvent

case class NodeUpdated(ann: NodeAnnouncement) extends NetworkEvent

case class NodeLost(nodeId: PublicKey) extends NetworkEvent

case class ChannelDiscovered(ann: ChannelAnnouncement, capacity: Satoshi) extends NetworkEvent

case class ChannelLost(shortChannelId: ShortChannelId) extends NetworkEvent

case class ChannelUpdateReceived(ann: ChannelUpdate) extends NetworkEvent
