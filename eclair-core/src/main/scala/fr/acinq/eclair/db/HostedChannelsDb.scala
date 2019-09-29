package fr.acinq.eclair.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.HOSTED_DATA_COMMITMENTS

trait HostedChannelsDb {

  def addOrUpdateChannel(state: HOSTED_DATA_COMMITMENTS): Unit

  def getChannel(channelId: ByteVector32): Option[HOSTED_DATA_COMMITMENTS]

  def listHotChannels(): Set[HOSTED_DATA_COMMITMENTS]
}