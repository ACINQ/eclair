package fr.acinq.eclair.wire

import fr.acinq.eclair.channel.HOSTED_DATA_COMMITMENTS
import fr.acinq.eclair.wire.ChannelCodecs._
import fr.acinq.eclair.wire.CommonCodecs.{uint64overflow, bytes32}
import fr.acinq.eclair.wire.LightningMessageCodecs.{errorCodec, channelUpdateCodec}
import fr.acinq.eclair.wire.HostedMessagesCodecs.lastCrossSignedStateCodec
import scodec.codecs._
import scodec.Codec

object HostedChannelCodecs {
  val HOSTED_DATA_COMMITMENTS_Codec: Codec[HOSTED_DATA_COMMITMENTS] = (
    ("channelVersion" | channelVersionCodec) ::
      ("lastCrossSignedState" | lastCrossSignedStateCodec) ::
      ("allLocalUpdates" | uint64overflow) ::
      ("allRemoteUpdates" | uint64overflow) ::
      ("localUpdates" | listOfN(uint16, updateMessageCodec)) ::
      ("remoteUpdates" | listOfN(uint16, updateMessageCodec)) ::
      ("localSpec" | commitmentSpecCodec) ::
      ("channelId" | bytes32) ::
      ("isHost" | bool) ::
      ("channelUpdateOpt" | optional(bool, variableSizeBytes(uint16, channelUpdateCodec))) ::
      ("localError" | optional(bool, errorCodec)) ::
      ("remoteError" | optional(bool, errorCodec))
    ).as[HOSTED_DATA_COMMITMENTS]
}