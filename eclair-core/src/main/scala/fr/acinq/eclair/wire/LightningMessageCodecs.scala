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
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.crypto.{Generators, Sphinx}
import fr.acinq.eclair.wire.FixedSizeStrictCodec.bytesStrict
import fr.acinq.eclair.{ShortChannelId, UInt64, wire}
import org.apache.commons.codec.binary.Base32
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound}
import shapeless.nat._

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

  val uint64ex: Codec[UInt64] = bytes(8).xmap(b => UInt64(b.toArray), a => ByteVector(a.toByteArray).padLeft(8))

  def binarydata(size: Int): Codec[BinaryData] = limitedSizeBytes(size, bytesStrict(size).xmap(d => BinaryData(d.toArray), d => ByteVector(d.data)))

  def varsizebinarydata: Codec[BinaryData] = variableSizeBytes(uint16, bytes.xmap(d => BinaryData(d.toArray), d => ByteVector(d.data)))

  def listofsignatures: Codec[List[BinaryData]] = listOfN(uint16, signature)

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

  def signature: Codec[BinaryData] = Codec[BinaryData](
    (der: BinaryData) => bytes(64).encode(ByteVector(der2wire(der).toArray)),
    (wire: BitVector) => bytes(64).decode(wire).map(_.map(b => wire2der(b.toArray)))
  )

  def scalar: Codec[Scalar] = Codec[Scalar](
    (value: Scalar) => bytes(32).encode(ByteVector(value.toBin.toArray)),
    (wire: BitVector) => bytes(32).decode(wire).map(_.map(b => Scalar(b.toArray)))
  )

  def point: Codec[Point] = Codec[Point](
    (point: Point) => bytes(33).encode(ByteVector(point.toBin(compressed = true).toArray)),
    (wire: BitVector) => bytes(33).decode(wire).map(_.map(b => Point(b.toArray)))
  )

  def privateKey: Codec[PrivateKey] = Codec[PrivateKey](
    (priv: PrivateKey) => bytes(32).encode(ByteVector(priv.value.toBin.toArray)),
    (wire: BitVector) => bytes(32).decode(wire).map(_.map(b => PrivateKey(b.toArray, compressed = true)))
  )

  def publicKey: Codec[PublicKey] = Codec[PublicKey](
    (pub: PublicKey) => bytes(33).encode(ByteVector(pub.value.toBin(compressed = true).toArray)),
    (wire: BitVector) => bytes(33).decode(wire).map(_.map(b => PublicKey(b.toArray)))
  )

  def optionalSignature: Codec[Option[BinaryData]] = Codec[Option[BinaryData]](
    (der: Option[BinaryData]) => der match {
      case Some(sig) => bytes(64).encode(ByteVector(der2wire(sig).toArray))
      case None => bytes(64).encode(ByteVector.fill[Byte](64)(0))
    },
    (wire: BitVector) => bytes(64).decode(wire).map(_.map(b => {
      val a = b.toArray
      if (a.exists(_ != 0)) Some(wire2der(a)) else None
    }))
  )

  def rgb: Codec[Color] = bytes(3).xmap(buf => Color(buf(0), buf(1), buf(2)), t => ByteVector(t.r, t.g, t.b))

  def zeropaddedstring(size: Int): Codec[String] = fixedSizeBytes(32, utf8).xmap(s => s.takeWhile(_ != '\u0000'), s => s)

  def der2wire(signature: BinaryData): BinaryData = {
    require(Crypto.isDERSignature(signature), s"invalid DER signature $signature")
    val (r, s) = Crypto.decodeSignature(signature)
    Generators.fixSize(r.toByteArray.dropWhile(_ == 0)) ++ Generators.fixSize(s.toByteArray.dropWhile(_ == 0))
  }

  def wire2der(sig: BinaryData): BinaryData = {
    require(sig.length == 64, "wire signature length must be 64")
    val r = new BigInteger(1, sig.take(32).toArray)
    val s = new BigInteger(1, sig.takeRight(32).toArray)
    Crypto.encodeSignature(r, s) :+ fr.acinq.bitcoin.SIGHASH_ALL.toByte // wtf ??
  }

  val initCodec: Codec[Init] = (
    ("globalFeatures" | varsizebinarydata) ::
      ("localFeatures" | varsizebinarydata)).as[Init]

  val errorCodec: Codec[Error] = (
    ("channelId" | binarydata(32)) ::
      ("data" | varsizebinarydata)).as[Error]

  val pingCodec: Codec[Ping] = (
    ("pongLength" | uint16) ::
      ("data" | varsizebinarydata)).as[Ping]

  val pongCodec: Codec[Pong] =
    ("data" | varsizebinarydata).as[Pong]

  val channelReestablishCodec: Codec[ChannelReestablish] = (
    ("channelId" | binarydata(32)) ::
      ("nextLocalCommitmentNumber" | uint64) ::
      ("nextRemoteRevocationNumber" | uint64) ::
      ("yourLastPerCommitmentSecret" | optional(bitsRemaining, scalar)) ::
      ("myCurrentPerCommitmentPoint" | optional(bitsRemaining, point))).as[ChannelReestablish]

  val openChannelCodec: Codec[OpenChannel] = (
    ("chainHash" | binarydata(32)) ::
      ("temporaryChannelId" | binarydata(32)) ::
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
    ("temporaryChannelId" | binarydata(32)) ::
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
    ("temporaryChannelId" | binarydata(32)) ::
      ("fundingTxid" | binarydata(32)) ::
      ("fundingOutputIndex" | uint16) ::
      ("signature" | signature)).as[FundingCreated]

  val fundingSignedCodec: Codec[FundingSigned] = (
    ("channelId" | binarydata(32)) ::
      ("signature" | signature)).as[FundingSigned]

  val fundingLockedCodec: Codec[FundingLocked] = (
    ("channelId" | binarydata(32)) ::
      ("nextPerCommitmentPoint" | point)).as[FundingLocked]

  val shutdownCodec: Codec[wire.Shutdown] = (
    ("channelId" | binarydata(32)) ::
      ("scriptPubKey" | varsizebinarydata)).as[Shutdown]

  val closingSignedCodec: Codec[ClosingSigned] = (
    ("channelId" | binarydata(32)) ::
      ("feeSatoshis" | uint64) ::
      ("signature" | signature)).as[ClosingSigned]

  val updateAddHtlcCodec: Codec[UpdateAddHtlc] = (
    ("channelId" | binarydata(32)) ::
      ("id" | uint64) ::
      ("amountMsat" | uint64) ::
      ("paymentHash" | binarydata(32)) ::
      ("expiry" | uint32) ::
      ("onionRoutingPacket" | binarydata(Sphinx.PacketLength))).as[UpdateAddHtlc]

  val updateFulfillHtlcCodec: Codec[UpdateFulfillHtlc] = (
    ("channelId" | binarydata(32)) ::
      ("id" | uint64) ::
      ("paymentPreimage" | binarydata(32))).as[UpdateFulfillHtlc]

  val updateFailHtlcCodec: Codec[UpdateFailHtlc] = (
    ("channelId" | binarydata(32)) ::
      ("id" | uint64) ::
      ("reason" | varsizebinarydata)).as[UpdateFailHtlc]

  val updateFailMalformedHtlcCodec: Codec[UpdateFailMalformedHtlc] = (
    ("channelId" | binarydata(32)) ::
      ("id" | uint64) ::
      ("onionHash" | binarydata(32)) ::
      ("failureCode" | uint16)).as[UpdateFailMalformedHtlc]

  val commitSigCodec: Codec[CommitSig] = (
    ("channelId" | binarydata(32)) ::
      ("signature" | signature) ::
      ("htlcSignatures" | listofsignatures)).as[CommitSig]

  val revokeAndAckCodec: Codec[RevokeAndAck] = (
    ("channelId" | binarydata(32)) ::
      ("perCommitmentSecret" | scalar) ::
      ("nextPerCommitmentPoint" | point)
    ).as[RevokeAndAck]

  val updateFeeCodec: Codec[UpdateFee] = (
    ("channelId" | binarydata(32)) ::
      ("feeratePerKw" | uint32)).as[UpdateFee]

  val announcementSignaturesCodec: Codec[AnnouncementSignatures] = (
    ("channelId" | binarydata(32)) ::
      ("shortChannelId" | shortchannelid) ::
      ("nodeSignature" | signature) ::
      ("bitcoinSignature" | signature)).as[AnnouncementSignatures]

  val channelAnnouncementWitnessCodec = (
    ("features" | varsizebinarydata) ::
      ("chainHash" | binarydata(32)) ::
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

  val channelUpdateWitnessCodec =
    ("chainHash" | binarydata(32)) ::
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

  val queryShortChannelIdsCodec: Codec[QueryShortChannelIds] = (
    ("chainHash" | binarydata(32)) ::
      ("data" | varsizebinarydata)
    ).as[QueryShortChannelIds]

  val replyShortChanelIdsEndCodec: Codec[ReplyShortChannelIdsEnd] = (
    ("chainHash" | binarydata(32)) ::
      ("complete" | byte)
    ).as[ReplyShortChannelIdsEnd]

  val queryChannelRangeCodec: Codec[QueryChannelRange] = (
    ("chainHash" | binarydata(32)) ::
      ("firstBlockNum" | uint32) ::
      ("numberOfBlocks" | uint32)
    ).as[QueryChannelRange]

  val replyChannelRangeCodec: Codec[ReplyChannelRange] = (
    ("chainHash" | binarydata(32)) ::
      ("firstBlockNum" | uint32) ::
      ("numberOfBlocks" | uint32) ::
      ("complete" | byte) ::
      ("data" | varsizebinarydata)
    ).as[ReplyChannelRange]

  val gossipTimestampFilterCodec: Codec[GossipTimestampFilter] = (
    ("chainHash" | binarydata(32)) ::
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