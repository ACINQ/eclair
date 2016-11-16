package fr.acinq.eclair.wire.bolt2.sdc

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.wire.bolt2.custom._
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.{Attempt, Codec, Err}


/**
  * Created by PM on 15/11/2016.
  */
object Codecs {

  // this codec can be safely used for values < 2^63 and will fail otherwise
  // (for something smarter see https://github.com/yzernik/bitcoin-scodec/blob/master/src/main/scala/io/github/yzernik/bitcoinscodec/structures/UInt64.scala)
  val uint64: Codec[Long] = int64.narrow(l => if (l >= 0) Attempt.Successful(l) else Attempt.failure(Err(s"overflow for value $l")), l => l)

  def binarydata(size: Int): Codec[BinaryData] = bytes(size).xmap(d => BinaryData(d.toSeq), d => ByteVector(d.data))

  val openChannelCodec: Codec[OpenChannel] = (
    ("temporaryChannelId" | uint64) ::
      ("fundingSatoshis" | uint64) ::
      ("pushMsat" | uint64) ::
      ("dustLimitSatoshis" | uint64) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserveSatoshis" | uint64) ::
      ("htlcMinimumMsat" | uint32) ::
      ("maxNumHtlcs" | uint32) ::
      ("feeratePerKb" | uint32) ::
      ("toSelfDelay" | uint16) ::
      ("fundingPubkey" | binarydata(33)) ::
      ("hakdBasePoint" | binarydata(33)) ::
      ("refundBasePoint" | binarydata(33))).as[OpenChannel]

  val acceptChannelCodec: Codec[AcceptChannel] = (
    ("temporaryChannelId" | uint64) ::
      ("dustLimitSatoshis" | uint64) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserveSatoshis" | uint64) ::
      ("minimumDepth" | uint32) ::
      ("htlcMinimumMsat" | uint32) ::
      ("maxNumHtlcs" | uint32) ::
      ("firstCommitmentKeyOffset" | binarydata(32)) ::
      ("toSelfDelay" | uint16) ::
      ("fundingPubkey" | binarydata(33)) ::
      ("hakdBasePoint" | binarydata(33)) ::
      ("refundBasePoint" | binarydata(33))).as[AcceptChannel]

  val fundingCreatedCodec: Codec[FundingCreated] = (
    ("temporaryChannelId" | uint64) ::
      ("txid" | binarydata(32)) ::
      ("outputIndex" | uint16) ::
      ("signature" | binarydata(64))).as[FundingCreated]

  val fundingSignedCodec: Codec[FundingSigned] = (
    ("temporaryChannelId" | uint64) ::
      ("signature" | binarydata(64))).as[FundingSigned]

  val fundingLockedCodec: Codec[FundingLocked] = (
    ("temporaryChannelId" | uint64) ::
    ("channelId" | uint64) ::
      ("nextKeyOffset" | binarydata(32)) ::
      ("nextRevocationHalfKey" | binarydata(33))).as[FundingLocked]

  val lightningMessageCodec = discriminated[LightningMessage].by(uint32)
    .typecase(32L, openChannelCodec)
    .typecase(33L, acceptChannelCodec)
    .typecase(34L, fundingCreatedCodec)
    .typecase(35L, fundingSignedCodec)
    .typecase(36L, fundingLockedCodec)

}
