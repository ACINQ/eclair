package fr.acinq.eclair.db

/**
  * Created by fabrice on 25/02/17.
  */

import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.{Data, State}
import fr.acinq.eclair.crypto.TransportHandler

case class ChannelState(remotePubKey: PublicKey, state: State, data: Data) {
  def serialize = ChannelState.serializer.serialize(this)
}

object ChannelState {
  val serializer = new TransportHandler.Serializer[ChannelState] {
    def serialize(cs: ChannelState): BinaryData = JavaSerializer.serialize(cs)

    def deserialize(input: BinaryData): ChannelState = JavaSerializer.deserialize[ChannelState](input)
  }
}