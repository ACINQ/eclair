package fr.acinq.eclair.wire

import fr.acinq.eclair.channel.HOSTED_DATA_COMMITMENTS
import fr.acinq.eclair.wire.ChannelCodecs._
import fr.acinq.eclair.wire.CommonCodecs.{shortchannelid, uint64overflow, bytes32}
import fr.acinq.eclair.wire.LightningMessageCodecs.{errorCodec, channelUpdateCodec}
import fr.acinq.eclair.wire.HostedMessagesCodecs.lastCrossSignedStateCodec
import scodec.codecs._
import scodec.Codec

object HostedChannelCodecs {
  val HOSTED_DATA_COMMITMENTS_Codec: Codec[HOSTED_DATA_COMMITMENTS] = (
    ("channelVersion" | channelVersionCodec) ::
      ("lastLocalCrossSignedState" | lastCrossSignedStateCodec) ::
      ("allLocalUpdates" | uint64overflow) ::
      ("allRemoteUpdates" | uint64overflow) ::
      ("localChanges" | localChangesCodec) ::
      ("remoteUpdates" | listOfN(uint16, updateMessageCodec)) ::
      ("localSpec" | commitmentSpecCodec) ::
      ("originChannels" | originsMapCodec) ::
      ("channelId" | bytes32) ::
      ("isHost" | bool) ::
      ("channelUpdate" | variableSizeBytes(uint16, channelUpdateCodec)) ::
      ("localError" | optional(bool, errorCodec)) ::
      ("remoteError" | optional(bool, errorCodec))
    ).as[HOSTED_DATA_COMMITMENTS]
}