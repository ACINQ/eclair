package fr.acinq.eclair.wire

import fr.acinq.eclair.channel.HOSTED_DATA_COMMITMENTS
import fr.acinq.eclair.wire.ChannelCodecs._
import fr.acinq.eclair.wire.CommonCodecs.{millisatoshi, bytes32, publicKey, uint64overflow}
import fr.acinq.eclair.wire.LightningMessageCodecs.{errorCodec, channelUpdateCodec}
import fr.acinq.eclair.wire.HostedMessagesCodecs.lastCrossSignedStateCodec
import scodec.codecs._
import scodec.Codec

object HostedChannelCodecs {
  val HOSTED_DATA_COMMITMENTS_Codec: Codec[HOSTED_DATA_COMMITMENTS] = {
    ("remoteNodeId" | publicKey) ::
      ("channelVersion" | channelVersionCodec) ::
      ("lastCrossSignedState" | lastCrossSignedStateCodec) ::
      ("futureUpdates" | listOfN(uint8, either(bool, updateMessageCodec, updateMessageCodec))) ::
      ("localSpec" | commitmentSpecCodec) ::
      ("originChannels" | originsMapCodec) ::
      ("channelId" | bytes32) ::
      ("isHost" | bool) ::
      ("channelUpdateOpt" | variableSizeBytes(uint16, channelUpdateCodec)) ::
      ("localError" | optional(bool, errorCodec)) ::
      ("remoteError" | optional(bool, errorCodec)) ::
      ("resolvedOutgoingHtlcLeftoverIds" | setCodec(uint64overflow)) ::
      ("overriddenBalanceProposal" | optional(bool, millisatoshi))
  }.as[HOSTED_DATA_COMMITMENTS]
}