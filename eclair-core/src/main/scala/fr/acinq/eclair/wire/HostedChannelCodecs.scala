package fr.acinq.eclair.wire

import fr.acinq.eclair.channel.{HOSTED_DATA_COMMITMENTS, HostedState}
import fr.acinq.eclair.wire.ChannelCodecs._
import fr.acinq.eclair.wire.HostedMessagesCodecs.{lastCrossSignedStateCodec, stateOverrideCodec}
import fr.acinq.eclair.wire.LightningMessageCodecs.{channelUpdateCodec, errorCodec}
import fr.acinq.eclair.wire.CommonCodecs.{bytes32, publicKey, uint64overflow}
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
      ("overriddenBalanceProposal" | optional(bool, stateOverrideCodec))
  }.as[HOSTED_DATA_COMMITMENTS]

  val hostedStateCodec: Codec[HostedState] = {
    (bytes32 withContext "channelId") ::
      (listOfN(uint16, updateMessageCodec) withContext "nextLocalUpdates") ::
      (listOfN(uint16, updateMessageCodec) withContext "nextRemoteUpdates") ::
      (lastCrossSignedStateCodec withContext "lastCrossSignedState")
  }.as[HostedState]
}