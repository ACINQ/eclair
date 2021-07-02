/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.wire.protocol

import com.google.common.base.Charsets
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Satoshi}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, ShortChannelId, UInt64}
import scodec.bits.ByteVector

import java.net.{Inet4Address, Inet6Address, InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import scala.util.Try

/**
 * Created by PM on 15/11/2016.
 */

// @formatter:off
sealed trait LightningMessage extends Serializable
sealed trait SetupMessage extends LightningMessage
sealed trait ChannelMessage extends LightningMessage
sealed trait HtlcMessage extends LightningMessage
sealed trait RoutingMessage extends LightningMessage
sealed trait AnnouncementMessage extends RoutingMessage // <- not in the spec
sealed trait HasTimestamp extends LightningMessage { def timestamp: Long }
sealed trait HasTemporaryChannelId extends LightningMessage { def temporaryChannelId: ByteVector32 } // <- not in the spec
sealed trait HasChannelId extends LightningMessage { def channelId: ByteVector32 } // <- not in the spec
sealed trait HasChainHash extends LightningMessage { def chainHash: ByteVector32 } // <- not in the spec
sealed trait UpdateMessage extends HtlcMessage // <- not in the spec
sealed trait HtlcSettlementMessage extends UpdateMessage { def id: Long } // <- not in the spec
// @formatter:on

case class Init(features: Features, tlvs: TlvStream[InitTlv] = TlvStream.empty) extends SetupMessage {
  val networks = tlvs.get[InitTlv.Networks].map(_.chainHashes).getOrElse(Nil)
}

case class Warning(channelId: ByteVector32, data: ByteVector) extends SetupMessage with HasChannelId {
  // @formatter:off
  val isGlobal: Boolean = channelId == ByteVector32.Zeroes
  def toAscii: String = if (fr.acinq.eclair.isAsciiPrintable(data)) new String(data.toArray, StandardCharsets.US_ASCII) else "n/a"
  // @formatter:on
}

object Warning {
  // @formatter:off
  def apply(channelId: ByteVector32, msg: String): Warning = Warning(channelId, ByteVector.view(msg.getBytes(Charsets.US_ASCII)))
  def apply(msg: String): Warning = Warning(ByteVector32.Zeroes, ByteVector.view(msg.getBytes(Charsets.US_ASCII)))
  // @formatter:on
}

case class Error(channelId: ByteVector32, data: ByteVector) extends SetupMessage with HasChannelId {
  def toAscii: String = if (fr.acinq.eclair.isAsciiPrintable(data)) new String(data.toArray, StandardCharsets.US_ASCII) else "n/a"
}

object Error {
  def apply(channelId: ByteVector32, msg: String): Error = Error(channelId, ByteVector.view(msg.getBytes(Charsets.US_ASCII)))
}

case class Ping(pongLength: Int, data: ByteVector) extends SetupMessage

case class Pong(data: ByteVector) extends SetupMessage

case class ChannelReestablish(channelId: ByteVector32,
                              nextLocalCommitmentNumber: Long,
                              nextRemoteRevocationNumber: Long,
                              yourLastPerCommitmentSecret: PrivateKey,
                              myCurrentPerCommitmentPoint: PublicKey) extends ChannelMessage with HasChannelId

case class OpenChannel(chainHash: ByteVector32,
                       temporaryChannelId: ByteVector32,
                       fundingSatoshis: Satoshi,
                       pushMsat: MilliSatoshi,
                       dustLimitSatoshis: Satoshi,
                       maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                       channelReserveSatoshis: Satoshi,
                       htlcMinimumMsat: MilliSatoshi,
                       feeratePerKw: FeeratePerKw,
                       toSelfDelay: CltvExpiryDelta,
                       maxAcceptedHtlcs: Int,
                       fundingPubkey: PublicKey,
                       revocationBasepoint: PublicKey,
                       paymentBasepoint: PublicKey,
                       delayedPaymentBasepoint: PublicKey,
                       htlcBasepoint: PublicKey,
                       firstPerCommitmentPoint: PublicKey,
                       channelFlags: Byte,
                       tlvStream: TlvStream[OpenChannelTlv] = TlvStream.empty) extends ChannelMessage with HasTemporaryChannelId with HasChainHash

case class AcceptChannel(temporaryChannelId: ByteVector32,
                         dustLimitSatoshis: Satoshi,
                         maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                         channelReserveSatoshis: Satoshi,
                         htlcMinimumMsat: MilliSatoshi,
                         minimumDepth: Long,
                         toSelfDelay: CltvExpiryDelta,
                         maxAcceptedHtlcs: Int,
                         fundingPubkey: PublicKey,
                         revocationBasepoint: PublicKey,
                         paymentBasepoint: PublicKey,
                         delayedPaymentBasepoint: PublicKey,
                         htlcBasepoint: PublicKey,
                         firstPerCommitmentPoint: PublicKey,
                         tlvStream: TlvStream[AcceptChannelTlv] = TlvStream.empty) extends ChannelMessage with HasTemporaryChannelId

case class FundingCreated(temporaryChannelId: ByteVector32,
                          fundingTxid: ByteVector32,
                          fundingOutputIndex: Int,
                          signature: ByteVector64) extends ChannelMessage with HasTemporaryChannelId

case class FundingSigned(channelId: ByteVector32,
                         signature: ByteVector64) extends ChannelMessage with HasChannelId

case class FundingLocked(channelId: ByteVector32,
                         nextPerCommitmentPoint: PublicKey) extends ChannelMessage with HasChannelId

case class Shutdown(channelId: ByteVector32,
                    scriptPubKey: ByteVector) extends ChannelMessage with HasChannelId

case class ClosingSigned(channelId: ByteVector32,
                         feeSatoshis: Satoshi,
                         signature: ByteVector64,
                         tlvStream: TlvStream[ClosingSignedTlv] = TlvStream.empty) extends ChannelMessage with HasChannelId {
  val feeRange_opt = tlvStream.get[ClosingSignedTlv.FeeRange]
}

case class UpdateAddHtlc(channelId: ByteVector32,
                         id: Long,
                         amountMsat: MilliSatoshi,
                         paymentHash: ByteVector32,
                         cltvExpiry: CltvExpiry,
                         onionRoutingPacket: OnionRoutingPacket) extends HtlcMessage with UpdateMessage with HasChannelId

case class UpdateFulfillHtlc(channelId: ByteVector32,
                             id: Long,
                             paymentPreimage: ByteVector32) extends HtlcMessage with UpdateMessage with HasChannelId with HtlcSettlementMessage

case class UpdateFailHtlc(channelId: ByteVector32,
                          id: Long,
                          reason: ByteVector) extends HtlcMessage with UpdateMessage with HasChannelId with HtlcSettlementMessage

case class UpdateFailMalformedHtlc(channelId: ByteVector32,
                                   id: Long,
                                   onionHash: ByteVector32,
                                   failureCode: Int) extends HtlcMessage with UpdateMessage with HasChannelId with HtlcSettlementMessage

case class CommitSig(channelId: ByteVector32,
                     signature: ByteVector64,
                     htlcSignatures: List[ByteVector64]) extends HtlcMessage with HasChannelId

case class RevokeAndAck(channelId: ByteVector32,
                        perCommitmentSecret: PrivateKey,
                        nextPerCommitmentPoint: PublicKey) extends HtlcMessage with HasChannelId

case class UpdateFee(channelId: ByteVector32,
                     feeratePerKw: FeeratePerKw) extends ChannelMessage with UpdateMessage with HasChannelId

case class AnnouncementSignatures(channelId: ByteVector32,
                                  shortChannelId: ShortChannelId,
                                  nodeSignature: ByteVector64,
                                  bitcoinSignature: ByteVector64) extends RoutingMessage with HasChannelId

case class ChannelAnnouncement(nodeSignature1: ByteVector64,
                               nodeSignature2: ByteVector64,
                               bitcoinSignature1: ByteVector64,
                               bitcoinSignature2: ByteVector64,
                               features: Features,
                               chainHash: ByteVector32,
                               shortChannelId: ShortChannelId,
                               nodeId1: PublicKey,
                               nodeId2: PublicKey,
                               bitcoinKey1: PublicKey,
                               bitcoinKey2: PublicKey,
                               unknownFields: ByteVector = ByteVector.empty) extends RoutingMessage with AnnouncementMessage with HasChainHash

case class Color(r: Byte, g: Byte, b: Byte) {
  override def toString: String = f"#$r%02x$g%02x$b%02x" // to hexa s"#  ${r}%02x ${r & 0xFF}${g & 0xFF}${b & 0xFF}"
}

// @formatter:off
sealed trait NodeAddress { def socketAddress: InetSocketAddress }
sealed trait OnionAddress extends NodeAddress
object NodeAddress {
  /**
    * Creates a NodeAddress from a host and port.
    *
    * Note that non-onion hosts will be resolved.
    *
    * We don't attempt to resolve onion addresses (it will be done by the tor proxy), so we just recognize them based on
    * the .onion TLD and rely on their length to separate v2/v3.
    */
  def fromParts(host: String, port: Int): Try[NodeAddress] = Try {
    host match {
      case _ if host.endsWith(".onion") && host.length == 22 => Tor2(host.dropRight(6), port)
      case _ if host.endsWith(".onion") && host.length == 62 => Tor3(host.dropRight(6), port)
      case _  => InetAddress.getByName(host) match {
        case a: Inet4Address => IPv4(a, port)
        case a: Inet6Address => IPv6(a, port)
      }
    }
  }
}
case class IPv4(ipv4: Inet4Address, port: Int) extends NodeAddress { override def socketAddress = new InetSocketAddress(ipv4, port) }
case class IPv6(ipv6: Inet6Address, port: Int) extends NodeAddress { override def socketAddress = new InetSocketAddress(ipv6, port) }
case class Tor2(tor2: String, port: Int) extends OnionAddress { override def socketAddress = InetSocketAddress.createUnresolved(tor2 + ".onion", port) }
case class Tor3(tor3: String, port: Int) extends OnionAddress { override def socketAddress = InetSocketAddress.createUnresolved(tor3 + ".onion", port) }
// @formatter:on


case class NodeAnnouncement(signature: ByteVector64,
                            features: Features,
                            timestamp: Long,
                            nodeId: PublicKey,
                            rgbColor: Color,
                            alias: String,
                            addresses: List[NodeAddress],
                            unknownFields: ByteVector = ByteVector.empty) extends RoutingMessage with AnnouncementMessage with HasTimestamp

case class ChannelUpdate(signature: ByteVector64,
                         chainHash: ByteVector32,
                         shortChannelId: ShortChannelId,
                         timestamp: Long,
                         messageFlags: Byte,
                         channelFlags: Byte,
                         cltvExpiryDelta: CltvExpiryDelta,
                         htlcMinimumMsat: MilliSatoshi,
                         feeBaseMsat: MilliSatoshi,
                         feeProportionalMillionths: Long,
                         htlcMaximumMsat: Option[MilliSatoshi],
                         unknownFields: ByteVector = ByteVector.empty) extends RoutingMessage with AnnouncementMessage with HasTimestamp with HasChainHash {
  require(((messageFlags & 1) != 0) == htlcMaximumMsat.isDefined, "htlcMaximumMsat is not consistent with messageFlags")

  def isNode1 = Announcements.isNode1(channelFlags)
}

// @formatter:off
sealed trait EncodingType
object EncodingType {
  case object UNCOMPRESSED extends EncodingType
  case object COMPRESSED_ZLIB extends EncodingType
}
// @formatter:on

case class EncodedShortChannelIds(encoding: EncodingType, array: List[ShortChannelId]) {
  /** custom toString because it can get huge in logs */
  override def toString: String = s"EncodedShortChannelIds($encoding,${array.headOption.getOrElse("")}->${array.lastOption.getOrElse("")} size=${array.size})"
}

case class QueryShortChannelIds(chainHash: ByteVector32, shortChannelIds: EncodedShortChannelIds, tlvStream: TlvStream[QueryShortChannelIdsTlv] = TlvStream.empty) extends RoutingMessage with HasChainHash {
  val queryFlags_opt: Option[QueryShortChannelIdsTlv.EncodedQueryFlags] = tlvStream.get[QueryShortChannelIdsTlv.EncodedQueryFlags]
}

case class ReplyShortChannelIdsEnd(chainHash: ByteVector32, complete: Byte) extends RoutingMessage with HasChainHash

case class QueryChannelRange(chainHash: ByteVector32, firstBlockNum: Long, numberOfBlocks: Long, tlvStream: TlvStream[QueryChannelRangeTlv] = TlvStream.empty) extends RoutingMessage {
  val queryFlags_opt: Option[QueryChannelRangeTlv.QueryFlags] = tlvStream.get[QueryChannelRangeTlv.QueryFlags]
}

case class ReplyChannelRange(chainHash: ByteVector32, firstBlockNum: Long, numberOfBlocks: Long, syncComplete: Byte, shortChannelIds: EncodedShortChannelIds, tlvStream: TlvStream[ReplyChannelRangeTlv] = TlvStream.empty) extends RoutingMessage {
  val timestamps_opt: Option[ReplyChannelRangeTlv.EncodedTimestamps] = tlvStream.get[ReplyChannelRangeTlv.EncodedTimestamps]
  val checksums_opt: Option[ReplyChannelRangeTlv.EncodedChecksums] = tlvStream.get[ReplyChannelRangeTlv.EncodedChecksums]
}

object ReplyChannelRange {
  def apply(chainHash: ByteVector32,
            firstBlockNum: Long,
            numberOfBlocks: Long,
            syncComplete: Byte,
            shortChannelIds: EncodedShortChannelIds,
            timestamps: Option[ReplyChannelRangeTlv.EncodedTimestamps],
            checksums: Option[ReplyChannelRangeTlv.EncodedChecksums]): ReplyChannelRange = {
    timestamps.foreach(ts => require(ts.timestamps.length == shortChannelIds.array.length))
    checksums.foreach(cs => require(cs.checksums.length == shortChannelIds.array.length))
    new ReplyChannelRange(chainHash, firstBlockNum, numberOfBlocks, syncComplete, shortChannelIds, TlvStream(timestamps.toList ::: checksums.toList))
  }
}

case class GossipTimestampFilter(chainHash: ByteVector32, firstTimestamp: Long, timestampRange: Long) extends RoutingMessage with HasChainHash

// NB: blank lines to minimize merge conflicts

//

//

//

//

//

//

//

//

case class UnknownMessage(tag: Int, data: ByteVector) extends LightningMessage