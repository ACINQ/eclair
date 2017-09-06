package fr.acinq.eclair.db

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}

trait NetworkDb {

  def addNode(n: NodeAnnouncement)

  def updateNode(n: NodeAnnouncement)

  def removeNode(nodeId: PublicKey)

  def listNodes(): Iterator[NodeAnnouncement]

  def addChannel(c: ChannelAnnouncement)

  /**
    * This method removes 1 channel announcement and 2 channel updates (at both ends of the same channel)
    * @param shortChannelId
    * @return
    */
  def removeChannel(shortChannelId: Long)

  def listChannels(): Iterator[ChannelAnnouncement]

  def addChannelUpdate(u: ChannelUpdate)

  def updateChannelUpdate(u: ChannelUpdate)

  def listChannelUpdates(): Iterator[ChannelUpdate]

}
