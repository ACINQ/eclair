package fr.acinq.eclair.db

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.channel.HasCommitments

trait ChannelsDb {

  def addOrUpdateChannel(state: HasCommitments)

  def removeChannel(channelId: BinaryData)

  def listChannels(): Seq[HasCommitments]

}
