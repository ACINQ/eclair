package fr.acinq.eclair.wire

import java.net.{Inet4Address, Inet6Address, InetAddress}

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.wire
import scodec.bits.{BitVector, ByteVector, HexStringSyntax}
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

  def listofbinarydata(size: Int): Codec[List[BinaryData]] = listOfN(uint16, binarydata(size))

  def ipv6: Codec[InetAddress] = Codec[InetAddress](
    (ia: InetAddress) => ia match {
      case a: Inet4Address => bytes(16).encode(hex"00 00 00 00 00 00 00 00 00 00 FF FF" ++ ByteVector(a.getAddress))
      case a: Inet6Address => bytes(16).encode(ByteVector(a.getAddress))
    },
    (buf: BitVector) => bytes(16).decode(buf).map(_.map(b => InetAddress.getByAddress(b.toArray)))
  )

  def rgb: Codec[(Byte, Byte, Byte)] = bytes(3).xmap(buf => (buf(0), buf(1), buf(2)), t => ByteVector(t._1, t._2, t._3))

  def string21: Codec[String] = fixedSizeBytes(21, utf8).xmap(s => s.takeWhile(_ != '\0'), s => s)

  val initCodec: Codec[Init] = (
    ("globalFeatures" | varsizebinarydata) ::
      ("localFeatures" | varsizebinarydata)).as[Init]

  val errorCodec: Codec[Error] = (
    ("channelId" | int64) ::
      ("data" | varsizebinarydata)).as[Error]

  val openChannelCodec: Codec[OpenChannel] = (
    ("temporaryChannelId" | int64) ::
      ("fundingSatoshis" | uint64) ::
      ("pushMsat" | uint64) ::
      ("dustLimitSatoshis" | uint64) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserveSatoshis" | uint64) ::
      ("htlcMinimumMsat" | uint32) ::
      ("feeratePerKw" | uint32) ::
      ("toSelfDelay" | uint16) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("fundingPubkey" | binarydata(33)) ::
      ("revocationBasepoint" | binarydata(33)) ::
      ("paymentBasepoint" | binarydata(33)) ::
      ("delayedPaymentBasepoint" | binarydata(33)) ::
      ("firstPerCommitmentPoint" | binarydata(33))).as[OpenChannel]

  val acceptChannelCodec: Codec[AcceptChannel] = (
    ("temporaryChannelId" | int64) ::
      ("dustLimitSatoshis" | uint64) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserveSatoshis" | uint64) ::
      ("minimumDepth" | uint32) ::
      ("htlcMinimumMsat" | uint32) ::
      ("toSelfDelay" | uint16) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("fundingPubkey" | binarydata(33)) ::
      ("revocationBasepoint" | binarydata(33)) ::
      ("paymentBasepoint" | binarydata(33)) ::
      ("delayedPaymentBasepoint" | binarydata(33)) ::
      ("firstPerCommitmentPoint" | binarydata(33))).as[AcceptChannel]

  val fundingCreatedCodec: Codec[FundingCreated] = (
    ("temporaryChannelId" | int64) ::
      ("txid" | binarydata(32)) ::
      ("outputIndex" | uint16) ::
      ("signature" | binarydata(64))).as[FundingCreated]

  val fundingSignedCodec: Codec[FundingSigned] = (
    ("temporaryChannelId" | int64) ::
      ("signature" | binarydata(64))).as[FundingSigned]

  val fundingLockedCodec: Codec[FundingLocked] = (
    ("temporaryChannelId" | int64) ::
      ("channelId" | int64) ::
      ("announcementNodeSignature" | binarydata(64)) ::
      ("announcementBitcoinSignature" | binarydata(64)) ::
      ("nextPerCommitmentPoint" | binarydata(33))).as[FundingLocked]

  val shutdownCodec: Codec[wire.Shutdown] = (
    ("channelId" | int64) ::
      ("scriptPubKey" | varsizebinarydata)).as[Shutdown]

  val closingSignedCodec: Codec[ClosingSigned] = (
    ("channelId" | int64) ::
      ("feeSatoshis" | uint64) ::
      ("signature" | binarydata(64))).as[ClosingSigned]

  val updateAddHtlcCodec: Codec[UpdateAddHtlc] = (
    ("channelId" | int64) ::
      ("id" | uint64) ::
      ("amountMsat" | uint32) ::
      ("expiry" | uint32) ::
      ("paymentHash" | binarydata(32)) ::
      ("onionRoutingPacket" | binarydata(1254))).as[UpdateAddHtlc]

  val updateFulfillHtlcCodec: Codec[UpdateFulfillHtlc] = (
    ("channelId" | int64) ::
      ("id" | uint64) ::
      ("paymentPreimage" | binarydata(32))).as[UpdateFulfillHtlc]

  val updateFailHtlcCodec: Codec[UpdateFailHtlc] = (
    ("channelId" | int64) ::
      ("id" | uint64) ::
      ("reason" | binarydata(154))).as[UpdateFailHtlc]

  val commitSigCodec: Codec[CommitSig] = (
    ("channelId" | int64) ::
      ("signature" | binarydata(64)) ::
      ("htlcSignatures" | listofbinarydata(64))).as[CommitSig]

  val revokeAndAckCodec: Codec[RevokeAndAck] = (
    ("channelId" | int64) ::
      ("perCommitmentSecret" | binarydata(32)) ::
      ("nextPerCommitmentPoint" | binarydata(33)) ::
      ("padding" | ignore(3)) ::
      ("htlcTimeoutSignature" | listofbinarydata(64))
    ).as[RevokeAndAck]

  val updateFeeCodec: Codec[UpdateFee] = (
    ("channelId" | int64) ::
      ("feeratePerKw" | uint32)).as[UpdateFee]

  val channelAnnouncementCodec: Codec[ChannelAnnouncement] = (
    ("nodeSignature1" | binarydata(64)) ::
      ("nodeSignature2" | binarydata(64)) ::
      ("channelId" | int64) ::
      ("bitcoinSignature1" | binarydata(64)) ::
      ("bitcoinSignature2" | binarydata(64)) ::
      ("nodeId1" | binarydata(33)) ::
      ("nodeId2" | binarydata(33)) ::
      ("bitcoinKey1" | binarydata(33)) ::
      ("bitcoinKey2" | binarydata(33))).as[ChannelAnnouncement]

  val nodeAnnouncementCodec: Codec[NodeAnnouncement] = (
    ("signature" | binarydata(64)) ::
      ("timestamp" | uint32) ::
      ("ip" | ipv6) ::
      ("port" | uint16) ::
      ("nodeId" | binarydata(32)) ::
      ("rgbColor" | rgb) ::
      ("alias" | string21) ::
      ("padding" | ignore(32 - 21))).as[NodeAnnouncement]

  val channelUpdateCodec: Codec[ChannelUpdate] = (
    ("signature" | binarydata(64)) ::
      ("channelId" | int64) ::
      ("timestamp" | uint32) ::
      ("flags" | binarydata(2)) ::
      ("expiry" | uint16) ::
      ("htlcMinimumMsat" | uint32) ::
      ("feeBaseMsat" | uint32) ::
      ("feeProportionalMillionths" | uint32)).as[ChannelUpdate]

  val lightningMessageCodec = discriminated[LightningMessage].by(uint16)
    .typecase(16, initCodec)
    .typecase(17, errorCodec)
    .typecase(32, openChannelCodec)
    .typecase(33, acceptChannelCodec)
    .typecase(34, fundingCreatedCodec)
    .typecase(35, fundingSignedCodec)
    .typecase(36, fundingLockedCodec)
    .typecase(38, shutdownCodec)
    .typecase(39, closingSignedCodec)
    .typecase(128, updateAddHtlcCodec)
    .typecase(130, updateFulfillHtlcCodec)
    .typecase(131, updateFailHtlcCodec)
    .typecase(132, commitSigCodec)
    .typecase(133, revokeAndAckCodec)
    .typecase(134, updateFeeCodec)
    .typecase(256, channelAnnouncementCodec)
    .typecase(257, nodeAnnouncementCodec)
    .typecase(258, channelUpdateCodec)

}
