package fr.acinq.eclair.db.noop

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.HOSTED_DATA_COMMITMENTS
import fr.acinq.eclair.db.HostedChannelsDb

class NoopHostedChannelsDb extends HostedChannelsDb {

  def addOrUpdateChannel(state: HOSTED_DATA_COMMITMENTS): Unit = ()

  def getChannel(channelId: ByteVector32): Option[HOSTED_DATA_COMMITMENTS] = None

  def listHotChannels(): Seq[HOSTED_DATA_COMMITMENTS] = Nil
}
