package fr.acinq.eclair.wire

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.wire
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

  def varsizebinarydata: Codec[BinaryData] = variableSizeBytes(uint16, bytes.xmap(d => BinaryData(d.toSeq), d => ByteVector(d.data)))

  def listofbinarydata(size: Int): Codec[List[BinaryData]] = listOfN(int32, binarydata(size))

  val initCodec: Codec[Init] = (
    ("globalFeatures" | varsizebinarydata) ::
      ("localFeatures" | varsizebinarydata)).as[Init]

  val errorCodec: Codec[Error] = (
    ("channelId" | uint64) ::
      ("data" | varsizebinarydata)).as[Error]

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
      ("revocationBasepoint" | binarydata(33)) ::
      ("paymentBasepoint" | binarydata(33)) ::
      ("delayedPaymentBasepoint" | binarydata(33)) ::
      ("firstPerCommitmentPoint" | binarydata(33))).as[OpenChannel]

  val acceptChannelCodec: Codec[AcceptChannel] = (
    ("temporaryChannelId" | uint64) ::
      ("dustLimitSatoshis" | uint64) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserveSatoshis" | uint64) ::
      ("minimumDepth" | uint32) ::
      ("htlcMinimumMsat" | uint32) ::
      ("maxNumHtlcs" | uint32) ::
      ("toSelfDelay" | uint16) ::
      ("fundingPubkey" | binarydata(33)) ::
      ("revocationBasepoint" | binarydata(33)) ::
      ("paymentBasepoint" | binarydata(33)) ::
      ("delayedPaymentBasepoint" | binarydata(33)) ::
      ("firstPerCommitmentPoint" | binarydata(33))).as[AcceptChannel]

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
      ("nextPerCommitmentPoint" | binarydata(33))).as[FundingLocked]

  val updateFeeCodec: Codec[UpdateFee] = (
    ("channelId" | uint64) ::
      ("feeratePerKb" | uint32)).as[UpdateFee]

  val shutdownCodec: Codec[wire.Shutdown] = (
    ("channelId" | uint64) ::
      ("scriptPubKey" | varsizebinarydata)).as[Shutdown]

  val closingSignedCodec: Codec[ClosingSigned] = (
    ("channelId" | uint64) ::
      ("feeSatoshis" | uint64) ::
      ("signature" | binarydata(64))).as[ClosingSigned]

  val addHtlcCodec: Codec[AddHtlc] = (
    ("channelId" | uint64) ::
      ("id" | uint64) ::
      ("amountMsat" | uint32) ::
      ("expiry" | uint32) ::
      ("paymentHash" | binarydata(32)) ::
      ("onionRoutingPacket" | binarydata(1254))).as[AddHtlc]

  val updateFulfillHtlcCodec: Codec[UpdateFulfillHtlc] = (
    ("channelId" | uint64) ::
      ("id" | uint64) ::
      ("paymentPreimage" | binarydata(32))).as[UpdateFulfillHtlc]

  val updateFailHtlcCodec: Codec[UpdateFailHtlc] = (
    ("channelId" | uint64) ::
      ("id" | uint64) ::
      ("reason" | binarydata(154))).as[UpdateFailHtlc]

  val commitSigCodec: Codec[CommitSig] = (
    ("channelId" | uint64) ::
      ("signature" | binarydata(64)) ::
      ("htlcSignatures" | listofbinarydata(64))).as[CommitSig]

  val revokeAndAckCodec: Codec[RevokeAndAck] = (
    ("channelId" | uint64) ::
      ("perCommitmentSecret" | binarydata(32)) ::
      ("nextPerCommitmentPoint" | binarydata(33)) ::
      ("padding" | binarydata(3)) ::
      ("htlcTimeoutSignature" | listofbinarydata(64))
    ).as[RevokeAndAck]

  val lightningMessageCodec = discriminated[LightningMessage].by(uint16)
    .typecase(16, initCodec)
    .typecase(17, errorCodec)
    .typecase(32, openChannelCodec)
    .typecase(33, acceptChannelCodec)
    .typecase(34, fundingCreatedCodec)
    .typecase(35, fundingSignedCodec)
    .typecase(36, fundingLockedCodec)
    .typecase(37, updateFeeCodec)
    .typecase(38, shutdownCodec)
    .typecase(39, closingSignedCodec)
    .typecase(128, addHtlcCodec)
    .typecase(130, updateFulfillHtlcCodec)
    .typecase(131, updateFailHtlcCodec)
    .typecase(132, commitSigCodec)
    .typecase(133, revokeAndAckCodec)

}
