/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.wire

import java.math.BigInteger
import java.net.{Inet4Address, Inet6Address, InetAddress}

import com.google.common.cache.{CacheBuilder, CacheLoader}
import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, Scalar}
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.crypto.{Generators, Sphinx}
import fr.acinq.eclair.wire.FixedSizeStrictCodec.bytesStrict
import fr.acinq.eclair.{ShortChannelId, UInt64, wire}
import org.apache.commons.codec.binary.Base32
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound}

import scala.util.{Failure, Success, Try}


/**
  * Created by PM on 15/11/2016.
  */
object LightningMessageCodecs {

  def attemptFromTry[T](f: => T): Attempt[T] = Try(f) match {
    case Success(t) => Attempt.successful(t)
    case Failure(t) => Attempt.failure(Err(s"deserialization error: ${t.getMessage}"))
  }

  // this codec can be safely used for values < 2^63 and will fail otherwise
  // (for something smarter see https://github.com/yzernik/bitcoin-scodec/blob/master/src/main/scala/io/github/yzernik/bitcoinscodec/structures/UInt64.scala)
  val uint64: Codec[Long] = int64.narrow(l => if (l >= 0) Attempt.Successful(l) else Attempt.failure(Err(s"overflow for value $l")), l => l)

  val uint64ex: Codec[UInt64] = bytes(8).xmap(b => UInt64(b), a => a.toByteVector.padLeft(8))

  def bytes32: Codec[ByteVector32] = limitedSizeBytes(32, bytesStrict(32).xmap(d => ByteVector32(d), d => d.bytes))

  def varsizebinarydata: Codec[ByteVector] = variableSizeBytes(uint16, bytes)

  def listofsignatures: Codec[List[ByteVector]] = listOfN(uint16, signature)

  def ipv4address: Codec[Inet4Address] = bytes(4).xmap(b => InetAddress.getByAddress(b.toArray).asInstanceOf[Inet4Address], a => ByteVector(a.getAddress))

  def ipv6address: Codec[Inet6Address] = bytes(16).exmap(b => attemptFromTry(Inet6Address.getByAddress(null, b.toArray, null)), a => attemptFromTry(ByteVector(a.getAddress)))

  def base32(size: Int): Codec[String] = bytes(size).xmap(b => new Base32().encodeAsString(b.toArray).toLowerCase, a => ByteVector(new Base32().decode(a.toUpperCase())))

  def nodeaddress: Codec[NodeAddress] =
    discriminated[NodeAddress].by(uint8)
      .typecase(1, (ipv4address :: uint16).as[IPv4])
      .typecase(2, (ipv6address :: uint16).as[IPv6])
      .typecase(3, (base32(10) :: uint16).as[Tor2])
      .typecase(4, (base32(35) :: uint16).as[Tor3])

  // this one is a bit different from most other codecs: the first 'len' element is *not* the number of items
  // in the list but rather the  number of bytes of the encoded list. The rationale is once we've read this
  // number of bytes we can just skip to the next field
  def listofnodeaddresses: Codec[List[NodeAddress]] = variableSizeBytes(uint16, list(nodeaddress))

  def shortchannelid: Codec[ShortChannelId] = int64.xmap(l => ShortChannelId(l), s => s.toLong)

  def signature: Codec[ByteVector] = Codec[ByteVector](
    (der: ByteVector) => bytes(64).encode(der2wire(der)),
    (wire: BitVector) => bytes(64).decode(wire).map(_.map(b => wire2der(b)))
  )

  def scalar: Codec[Scalar] = Codec[Scalar](
    (value: Scalar) => bytes(32).encode(ByteVector(value.toBin.toArray)),
    (wire: BitVector) => bytes(32).decode(wire).map(_.map(b => Scalar(b)))
  )

  def point: Codec[Point] = Codec[Point](
    (point: Point) => bytes(33).encode(point.toBin(compressed = true)),
    (wire: BitVector) => bytes(33).decode(wire).map(_.map(b => Point(b)))
  )

  def privateKey: Codec[PrivateKey] = Codec[PrivateKey](
    (priv: PrivateKey) => bytes(32).encode(priv.value.toBin),
    (wire: BitVector) => bytes(32).decode(wire).map(_.map(b => PrivateKey(b, compressed = true)))
  )

  def publicKey: Codec[PublicKey] = Codec[PublicKey](
    (pub: PublicKey) => bytes(33).encode(pub.value.toBin(compressed = true)),
    (wire: BitVector) => bytes(33).decode(wire).map(_.map(b => PublicKey(b)))
  )

  def optionalSignature: Codec[Option[ByteVector]] = Codec[Option[ByteVector]](
    (der: Option[ByteVector]) => der match {
      case Some(sig) => bytes(64).encode(der2wire(sig))
      case None => bytes(64).encode(ByteVector.fill[Byte](64)(0))
    },
    (wire: BitVector) => bytes(64).decode(wire).map(_.map(b => {
      if (b.toArray.exists(_ != 0)) Some(wire2der(b)) else None
    }))
  )

  def rgb: Codec[Color] = bytes(3).xmap(buf => Color(buf(0), buf(1), buf(2)), t => ByteVector(t.r, t.g, t.b))

  def zeropaddedstring(size: Int): Codec[String] = fixedSizeBytes(32, utf8).xmap(s => s.takeWhile(_ != '\u0000'), s => s)

  def der2wire(signature: ByteVector): ByteVector = {
    require(Crypto.isDERSignature(signature), s"invalid DER signature $signature")
    val (r, s) = Crypto.decodeSignature(signature)
    Generators.fixSize(ByteVector.view(r.toByteArray.dropWhile(_ == 0))) ++ Generators.fixSize(ByteVector.view(s.toByteArray.dropWhile(_ == 0)))
  }

  def wire2der(sig: ByteVector): ByteVector = {
    require(sig.length == 64, "wire signature length must be 64")
    val r = new BigInteger(1, sig.take(32).toArray)
    val s = new BigInteger(1, sig.takeRight(32).toArray)
    Crypto.encodeSignature(r, s) :+ fr.acinq.bitcoin.SIGHASH_ALL.toByte // wtf ??
  }

  val initCodec: Codec[Init] = (
    ("globalFeatures" | varsizebinarydata) ::
      ("localFeatures" | varsizebinarydata)).as[Init]

  val errorCodec: Codec[Error] = (
    ("channelId" | bytes32) ::
      ("data" | varsizebinarydata)).as[Error]

  val pingCodec: Codec[Ping] = (
    ("pongLength" | uint16) ::
      ("data" | varsizebinarydata)).as[Ping]

  val pongCodec: Codec[Pong] =
    ("data" | varsizebinarydata).as[Pong]

  val channelReestablishCodec: Codec[ChannelReestablish] = (
    ("channelId" | bytes32) ::
      ("nextLocalCommitmentNumber" | uint64) ::
      ("nextRemoteRevocationNumber" | uint64) ::
      ("yourLastPerCommitmentSecret" | optional(bitsRemaining, scalar)) ::
      ("myCurrentPerCommitmentPoint" | optional(bitsRemaining, point))).as[ChannelReestablish]

  val openChannelCodec: Codec[OpenChannel] = (
    ("chainHash" | bytes32) ::
      ("temporaryChannelId" | bytes32) ::
      ("fundingSatoshis" | uint64) ::
      ("pushMsat" | uint64) ::
      ("dustLimitSatoshis" | uint64) ::
      ("maxHtlcValueInFlightMsat" | uint64ex) ::
      ("channelReserveSatoshis" | uint64) ::
      ("htlcMinimumMsat" | uint64) ::
      ("feeratePerKw" | uint32) ::
      ("toSelfDelay" | uint16) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("fundingPubkey" | publicKey) ::
      ("revocationBasepoint" | point) ::
      ("paymentBasepoint" | point) ::
      ("delayedPaymentBasepoint" | point) ::
      ("htlcBasepoint" | point) ::
      ("firstPerCommitmentPoint" | point) ::
      ("channelFlags" | byte)).as[OpenChannel]

  val acceptChannelCodec: Codec[AcceptChannel] = (
    ("temporaryChannelId" | bytes32) ::
      ("dustLimitSatoshis" | uint64) ::
      ("maxHtlcValueInFlightMsat" | uint64ex) ::
      ("channelReserveSatoshis" | uint64) ::
      ("htlcMinimumMsat" | uint64) ::
      ("minimumDepth" | uint32) ::
      ("toSelfDelay" | uint16) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("fundingPubkey" | publicKey) ::
      ("revocationBasepoint" | point) ::
      ("paymentBasepoint" | point) ::
      ("delayedPaymentBasepoint" | point) ::
      ("htlcBasepoint" | point) ::
      ("firstPerCommitmentPoint" | point)).as[AcceptChannel]

  val fundingCreatedCodec: Codec[FundingCreated] = (
    ("temporaryChannelId" | bytes32) ::
      ("fundingTxid" | bytes32) ::
      ("fundingOutputIndex" | uint16) ::
      ("signature" | signature)).as[FundingCreated]

  val fundingSignedCodec: Codec[FundingSigned] = (
    ("channelId" | bytes32) ::
      ("signature" | signature)).as[FundingSigned]

  val fundingLockedCodec: Codec[FundingLocked] = (
    ("channelId" | bytes32) ::
      ("nextPerCommitmentPoint" | point)).as[FundingLocked]

  val shutdownCodec: Codec[wire.Shutdown] = (
    ("channelId" | bytes32) ::
      ("scriptPubKey" | varsizebinarydata)).as[Shutdown]

  val closingSignedCodec: Codec[ClosingSigned] = (
    ("channelId" | bytes32) ::
      ("feeSatoshis" | uint64) ::
      ("signature" | signature)).as[ClosingSigned]

  val updateAddHtlcCodec: Codec[UpdateAddHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64) ::
      ("amountMsat" | uint64) ::
      ("paymentHash" | bytes32) ::
      ("expiry" | uint32) ::
      ("onionRoutingPacket" | bytes(Sphinx.PacketLength))).as[UpdateAddHtlc]

  val updateFulfillHtlcCodec: Codec[UpdateFulfillHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64) ::
      ("paymentPreimage" | bytes32)).as[UpdateFulfillHtlc]

  val updateFailHtlcCodec: Codec[UpdateFailHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64) ::
      ("reason" | varsizebinarydata)).as[UpdateFailHtlc]

  val updateFailMalformedHtlcCodec: Codec[UpdateFailMalformedHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64) ::
      ("onionHash" | bytes32) ::
      ("failureCode" | uint16)).as[UpdateFailMalformedHtlc]

  val commitSigCodec: Codec[CommitSig] = (
    ("channelId" | bytes32) ::
      ("signature" | signature) ::
      ("htlcSignatures" | listofsignatures)).as[CommitSig]

  val revokeAndAckCodec: Codec[RevokeAndAck] = (
    ("channelId" | bytes32) ::
      ("perCommitmentSecret" | scalar) ::
      ("nextPerCommitmentPoint" | point)
    ).as[RevokeAndAck]

  val updateFeeCodec: Codec[UpdateFee] = (
    ("channelId" | bytes32) ::
      ("feeratePerKw" | uint32)).as[UpdateFee]

  val announcementSignaturesCodec: Codec[AnnouncementSignatures] = (
    ("channelId" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      ("nodeSignature" | signature) ::
      ("bitcoinSignature" | signature)).as[AnnouncementSignatures]

  val channelAnnouncementWitnessCodec = (
    ("features" | varsizebinarydata) ::
      ("chainHash" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      ("nodeId1" | publicKey) ::
      ("nodeId2" | publicKey) ::
      ("bitcoinKey1" | publicKey) ::
      ("bitcoinKey2" | publicKey))

  val channelAnnouncementCodec: Codec[ChannelAnnouncement] = (
    ("nodeSignature1" | signature) ::
      ("nodeSignature2" | signature) ::
      ("bitcoinSignature1" | signature) ::
      ("bitcoinSignature2" | signature) ::
      channelAnnouncementWitnessCodec).as[ChannelAnnouncement]

  val nodeAnnouncementWitnessCodec = (
    ("features" | varsizebinarydata) ::
      ("timestamp" | uint32) ::
      ("nodeId" | publicKey) ::
      ("rgbColor" | rgb) ::
      ("alias" | zeropaddedstring(32)) ::
      ("addresses" | listofnodeaddresses))

  val nodeAnnouncementCodec: Codec[NodeAnnouncement] = (
    ("signature" | signature) ::
      nodeAnnouncementWitnessCodec).as[NodeAnnouncement]

  val channelUpdateChecksumCodec =
      ("shortChannelId" | shortchannelid) ::
      (("messageFlags" | byte) >>:~ { messageFlags =>
        ("channelFlags" | byte) ::
          ("cltvExpiryDelta" | uint16) ::
          ("htlcMinimumMsat" | uint64) ::
          ("feeBaseMsat" | uint32) ::
          ("feeProportionalMillionths" | uint32) ::
          ("htlcMaximumMsat" | conditional((messageFlags & 1) != 0, uint64))
      })

  val channelUpdateWitnessCodec =
    ("chainHash" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      ("timestamp" | uint32) ::
      (("messageFlags" | byte) >>:~ { messageFlags =>
        ("channelFlags" | byte) ::
          ("cltvExpiryDelta" | uint16) ::
          ("htlcMinimumMsat" | uint64) ::
          ("feeBaseMsat" | uint32) ::
          ("feeProportionalMillionths" | uint32) ::
          ("htlcMaximumMsat" | conditional((messageFlags & 1) != 0, uint64))
      })

  val channelUpdateCodec: Codec[ChannelUpdate] = (
    ("signature" | signature) ::
      channelUpdateWitnessCodec).as[ChannelUpdate]

  val encodedShortChannelIdsCodec: Codec[EncodedShortChannelIds] =
    discriminated[EncodedShortChannelIds].by(byte)
      .\(0) { case a@EncodedShortChannelIds(EncodingType.UNCOMPRESSED, _) => a }((provide[EncodingType](EncodingType.UNCOMPRESSED) :: list(shortchannelid)).as[EncodedShortChannelIds])
      .\(1) { case a@EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, _) => a }((provide[EncodingType](EncodingType.COMPRESSED_ZLIB) :: zlib(list(shortchannelid))).as[EncodedShortChannelIds])

  val encodedQueryFlagsCodec: Codec[EncodedQueryFlags] =
    discriminated[EncodedQueryFlags].by(byte)
      .\(0) { case a@EncodedQueryFlags(EncodingType.UNCOMPRESSED, _) => a }((provide[EncodingType](EncodingType.UNCOMPRESSED) :: list(byte)).as[EncodedQueryFlags])
      .\(1) { case a@EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, _) => a }((provide[EncodingType](EncodingType.COMPRESSED_ZLIB) :: zlib(list(byte))).as[EncodedQueryFlags])

  val queryShortChannelIdsCodec: Codec[QueryShortChannelIds] = (
    ("chainHash" | bytes32) ::
      ("shortChannelIds" | variableSizeBytes(uint16, encodedShortChannelIdsCodec)) ::
      ("queryFlags_opt" | optional(bitsRemaining, variableSizeBytes(uint16, encodedQueryFlagsCodec)))
    ).as[QueryShortChannelIds]

  val replyShortChanelIdsEndCodec: Codec[ReplyShortChannelIdsEnd] = (
    ("chainHash" | bytes32) ::
      ("complete" | byte)
    ).as[ReplyShortChannelIdsEnd]

  val extendedQueryFlagsCodec: Codec[ExtendedQueryFlags] =
    discriminated[ExtendedQueryFlags].by(byte)
    .typecase(1, provide(ExtendedQueryFlags.TIMESTAMPS_AND_CHECKSUMS))

  val queryChannelRangeCodec: Codec[QueryChannelRange] = (
    ("chainHash" | bytes32) ::
      ("firstBlockNum" | uint32) ::
      ("numberOfBlocks" | uint32) ::
      ("optionExtendedQueryFlags" | optional(bitsRemaining, extendedQueryFlagsCodec))
    ).as[QueryChannelRange]

  val timestampsAndChecksumsCodec: Codec[TimestampsAndChecksums] = (
        ("timestamp1" | uint32) ::
        ("timestamp2" | uint32) ::
        ("checksum1" | uint32) ::
        ("checksum2" | uint32)
      ).as[TimestampsAndChecksums]

  val extendedInfoCodec: Codec[ExtendedInfo] = list(timestampsAndChecksumsCodec).as[ExtendedInfo]

  val replyChannelRangeCodec: Codec[ReplyChannelRange] = (
    ("chainHash" | bytes32) ::
      ("firstBlockNum" | uint32) ::
      ("numberOfBlocks" | uint32) ::
      ("complete" | byte) ::
      ("shortChannelIds" | variableSizeBytes(uint16, encodedShortChannelIdsCodec)) ::
      ("optionExtendedQueryFlags_opt" | optional(bitsRemaining, extendedQueryFlagsCodec)) ::
      ("extendedInfo_opt" | optional(bitsRemaining, variableSizeBytes(uint16, extendedInfoCodec)))
    ).as[ReplyChannelRange]

  val gossipTimestampFilterCodec: Codec[GossipTimestampFilter] = (
    ("chainHash" | bytes32) ::
      ("firstTimestamp" | uint32) ::
      ("timestampRange" | uint32)
    ).as[GossipTimestampFilter]

  val lightningMessageCodec = discriminated[LightningMessage].by(uint16)
    .typecase(16, initCodec)
    .typecase(17, errorCodec)
    .typecase(18, pingCodec)
    .typecase(19, pongCodec)
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
    .typecase(135, updateFailMalformedHtlcCodec)
    .typecase(136, channelReestablishCodec)
    .typecase(256, channelAnnouncementCodec)
    .typecase(257, nodeAnnouncementCodec)
    .typecase(258, channelUpdateCodec)
    .typecase(259, announcementSignaturesCodec)
    .typecase(261, queryShortChannelIdsCodec)
    .typecase(262, replyShortChanelIdsEndCodec)
    .typecase(263, queryChannelRangeCodec)
    .typecase(264, replyChannelRangeCodec)
    .typecase(265, gossipTimestampFilterCodec)

  /**
    * A codec that caches serialized routing messages
    */
  val cachedLightningMessageCodec = new Codec[LightningMessage] {

    override def sizeBound: SizeBound = lightningMessageCodec.sizeBound

    val cache = CacheBuilder
      .newBuilder
      .weakKeys() // will cleanup values when keys are garbage collected
      .build(new CacheLoader[LightningMessage, Attempt[BitVector]] {
      override def load(key: LightningMessage): Attempt[BitVector] = lightningMessageCodec.encode(key)
    })

    override def encode(value: LightningMessage): Attempt[BitVector] = value match {
      case _: ChannelAnnouncement => cache.get(value) // we only cache serialized routing messages
      case _: NodeAnnouncement => cache.get(value) // we only cache serialized routing messages
      case _: ChannelUpdate => cache.get(value) // we only cache serialized routing messages
      case _ => lightningMessageCodec.encode(value)
    }

    override def decode(bits: BitVector): Attempt[DecodeResult[LightningMessage]] = lightningMessageCodec.decode(bits)
  }

  val perHopPayloadCodec: Codec[PerHopPayload] = (
    ("realm" | constant(ByteVector.fromByte(0))) ::
      ("short_channel_id" | shortchannelid) ::
      ("amt_to_forward" | uint64) ::
      ("outgoing_cltv_value" | uint32) ::
      ("unused_with_v0_version_on_header" | ignore(8 * 12))).as[PerHopPayload]

}