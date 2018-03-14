package fr.acinq.eclair.db

import fr.acinq.bitcoin.{BinaryData, Satoshi}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}

trait NetworkDb {

  def addNode(n: NodeAnnouncement)

  def updateNode(n: NodeAnnouncement)

  def removeNode(nodeId: PublicKey)

  def listNodes(): Seq[NodeAnnouncement]

  def addChannel(c: ChannelAnnouncement, txid: BinaryData, capacity: Satoshi)

  /**
    * This method removes 1 channel announcement and 2 channel updates (at both ends of the same channel)
    *
    * @param shortChannelId
    * @return
    */
  def removeChannel(shortChannelId: ShortChannelId)

  def listChannels(): Map[ChannelAnnouncement, (BinaryData, Satoshi)]

  def addChannelUpdate(u: ChannelUpdate)

  def updateChannelUpdate(u: ChannelUpdate)

  def listChannelUpdates(): Seq[ChannelUpdate]

}
