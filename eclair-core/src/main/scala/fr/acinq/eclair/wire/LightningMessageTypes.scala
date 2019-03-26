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

import java.net.{Inet4Address, Inet6Address, InetAddress, InetSocketAddress}

import com.google.common.base.Charsets
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.{ShortChannelId, UInt64}
import scodec.bits.ByteVector

import scala.util.Try

/**
  * Created by PM on 15/11/2016.
  */

// @formatter:off
sealed trait LightningMessage
sealed trait SetupMessage extends LightningMessage
sealed trait ChannelMessage extends LightningMessage
sealed trait HtlcMessage extends LightningMessage
sealed trait RoutingMessage extends LightningMessage
sealed trait HasTimestamp extends LightningMessage { def timestamp: Long }
sealed trait HasTemporaryChannelId extends LightningMessage { def temporaryChannelId: ByteVector32 } // <- not in the spec
sealed trait HasChannelId extends LightningMessage { def channelId: ByteVector32 } // <- not in the spec
sealed trait HasChainHash extends LightningMessage { def chainHash: ByteVector32 } // <- not in the spec
sealed trait UpdateMessage extends HtlcMessage // <- not in the spec
// @formatter:on

case class Init(globalFeatures: ByteVector,
                localFeatures: ByteVector) extends SetupMessage

case class Error(channelId: ByteVector32,
                 data: ByteVector) extends SetupMessage with HasChannelId

object Error {
  def apply(channelId: ByteVector32, msg: String): Error = Error(channelId, ByteVector.view(msg.getBytes(Charsets.US_ASCII)))
}

case class Ping(pongLength: Int, data: ByteVector) extends SetupMessage

case class Pong(data: ByteVector) extends SetupMessage

case class ChannelReestablish(channelId: ByteVector32,
                              nextLocalCommitmentNumber: Long,
                              nextRemoteRevocationNumber: Long,
                              yourLastPerCommitmentSecret: Option[Scalar] = None,
                              myCurrentPerCommitmentPoint: Option[Point] = None) extends ChannelMessage with HasChannelId

case class OpenChannel(chainHash: ByteVector32,
                       temporaryChannelId: ByteVector32,
                       fundingSatoshis: Long,
                       pushMsat: Long,
                       dustLimitSatoshis: Long,
                       maxHtlcValueInFlightMsat: UInt64,
                       channelReserveSatoshis: Long,
                       htlcMinimumMsat: Long,
                       feeratePerKw: Long,
                       toSelfDelay: Int,
                       maxAcceptedHtlcs: Int,
                       fundingPubkey: PublicKey,
                       revocationBasepoint: Point,
                       paymentBasepoint: Point,
                       delayedPaymentBasepoint: Point,
                       htlcBasepoint: Point,
                       firstPerCommitmentPoint: Point,
                       channelFlags: Byte) extends ChannelMessage with HasTemporaryChannelId with HasChainHash

case class AcceptChannel(temporaryChannelId: ByteVector32,
                         dustLimitSatoshis: Long,
                         maxHtlcValueInFlightMsat: UInt64,
                         channelReserveSatoshis: Long,
                         htlcMinimumMsat: Long,
                         minimumDepth: Long,
                         toSelfDelay: Int,
                         maxAcceptedHtlcs: Int,
                         fundingPubkey: PublicKey,
                         revocationBasepoint: Point,
                         paymentBasepoint: Point,
                         delayedPaymentBasepoint: Point,
                         htlcBasepoint: Point,
                         firstPerCommitmentPoint: Point) extends ChannelMessage with HasTemporaryChannelId

case class FundingCreated(temporaryChannelId: ByteVector32,
                          fundingTxid: ByteVector32,
                          fundingOutputIndex: Int,
                          signature: ByteVector) extends ChannelMessage with HasTemporaryChannelId

case class FundingSigned(channelId: ByteVector32,
                         signature: ByteVector) extends ChannelMessage with HasChannelId

case class FundingLocked(channelId: ByteVector32,
                         nextPerCommitmentPoint: Point) extends ChannelMessage with HasChannelId

case class Shutdown(channelId: ByteVector32,
                    scriptPubKey: ByteVector) extends ChannelMessage with HasChannelId

case class ClosingSigned(channelId: ByteVector32,
                         feeSatoshis: Long,
                         signature: ByteVector) extends ChannelMessage with HasChannelId

case class UpdateAddHtlc(channelId: ByteVector32,
                         id: Long,
                         amountMsat: Long,
                         paymentHash: ByteVector32,
                         cltvExpiry: Long,
                         onionRoutingPacket: ByteVector) extends HtlcMessage with UpdateMessage with HasChannelId

case class UpdateFulfillHtlc(channelId: ByteVector32,
                             id: Long,
                             paymentPreimage: ByteVector32) extends HtlcMessage with UpdateMessage with HasChannelId

case class UpdateFailHtlc(channelId: ByteVector32,
                          id: Long,
                          reason: ByteVector) extends HtlcMessage with UpdateMessage with HasChannelId

case class UpdateFailMalformedHtlc(channelId: ByteVector32,
                                   id: Long,
                                   onionHash: ByteVector32,
                                   failureCode: Int) extends HtlcMessage with UpdateMessage with HasChannelId

case class CommitSig(channelId: ByteVector32,
                     signature: ByteVector,
                     htlcSignatures: List[ByteVector]) extends HtlcMessage with HasChannelId

case class RevokeAndAck(channelId: ByteVector32,
                        perCommitmentSecret: Scalar,
                        nextPerCommitmentPoint: Point) extends HtlcMessage with HasChannelId

case class UpdateFee(channelId: ByteVector32,
                     feeratePerKw: Long) extends ChannelMessage with UpdateMessage with HasChannelId

case class AnnouncementSignatures(channelId: ByteVector32,
                                  shortChannelId: ShortChannelId,
                                  nodeSignature: ByteVector,
                                  bitcoinSignature: ByteVector) extends RoutingMessage with HasChannelId

case class ChannelAnnouncement(nodeSignature1: ByteVector,
                               nodeSignature2: ByteVector,
                               bitcoinSignature1: ByteVector,
                               bitcoinSignature2: ByteVector,
                               features: ByteVector,
                               chainHash: ByteVector32,
                               shortChannelId: ShortChannelId,
                               nodeId1: PublicKey,
                               nodeId2: PublicKey,
                               bitcoinKey1: PublicKey,
                               bitcoinKey2: PublicKey) extends RoutingMessage with HasChainHash

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
    *
    * @param host
    * @param port
    * @return
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


case class NodeAnnouncement(signature: ByteVector,
                            features: ByteVector,
                            timestamp: Long,
                            nodeId: PublicKey,
                            rgbColor: Color,
                            alias: String,
                            addresses: List[NodeAddress]) extends RoutingMessage with HasTimestamp

case class ChannelUpdate(signature: ByteVector,
                         chainHash: ByteVector32,
                         shortChannelId: ShortChannelId,
                         timestamp: Long,
                         messageFlags: Byte,
                         channelFlags: Byte,
                         cltvExpiryDelta: Int,
                         htlcMinimumMsat: Long,
                         feeBaseMsat: Long,
                         feeProportionalMillionths: Long,
                         htlcMaximumMsat: Option[Long]) extends RoutingMessage with HasTimestamp with HasChainHash {
  require(((messageFlags & 1) != 0) == htlcMaximumMsat.isDefined, "htlcMaximumMsat is not consistent with messageFlags")

  def isNode1 = Announcements.isNode1(channelFlags)
}

case class PerHopPayload(shortChannelId: ShortChannelId,
                         amtToForward: Long,
                         outgoingCltvValue: Long)

// @formatter:off
sealed trait EncodingType
object EncodingType {
  case object UNCOMPRESSED extends EncodingType
  case object COMPRESSED_ZLIB extends EncodingType
}
// @formatter:on

case object QueryFlagTypes {
  val INCLUDE_CHANNEL_ANNOUNCEMENT: Byte = 1
  val INCLUDE_CHANNEL_UPDATE_1: Byte = 2
  val INCLUDE_CHANNEL_UPDATE_2: Byte = 4
  val INCLUDE_ALL: Byte = (INCLUDE_CHANNEL_ANNOUNCEMENT | INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2).toByte

  def includeAnnouncement(flag: Byte) = (flag & QueryFlagTypes.INCLUDE_CHANNEL_ANNOUNCEMENT) != 0

  def includeUpdate1(flag: Byte) = (flag & QueryFlagTypes.INCLUDE_CHANNEL_UPDATE_1) != 0

  def includeUpdate2(flag: Byte) = (flag & QueryFlagTypes.INCLUDE_CHANNEL_UPDATE_2) != 0
}

case class EncodedShortChannelIds(encoding: EncodingType,
                                  array: List[ShortChannelId])

case class EncodedQueryFlags(encoding: EncodingType,
                             array: List[Byte])

case class QueryShortChannelIds(chainHash: ByteVector32,
                                shortChannelIds: EncodedShortChannelIds,
                                queryFlags_opt: Option[EncodedQueryFlags]) extends RoutingMessage with HasChainHash

case class ReplyShortChannelIdsEnd(chainHash: ByteVector32,
                                   complete: Byte) extends RoutingMessage with HasChainHash

// @formatter:off
sealed trait ExtendedQueryFlags
object ExtendedQueryFlags {
  case object TIMESTAMPS_AND_CHECKSUMS extends ExtendedQueryFlags
}
// @formatter:on

case class QueryChannelRange(chainHash: ByteVector32,
                             firstBlockNum: Long,
                             numberOfBlocks: Long,
                             extendedQueryFlags_opt: Option[ExtendedQueryFlags]) extends RoutingMessage with HasChainHash

case class ReplyChannelRange(chainHash: ByteVector32,
                             firstBlockNum: Long,
                             numberOfBlocks: Long,
                             complete: Byte,
                             shortChannelIds: EncodedShortChannelIds,
                             extendedQueryFlags_opt: Option[ExtendedQueryFlags],
                             extendedInfo_opt: Option[ExtendedInfo]) extends RoutingMessage with HasChainHash {
  extendedInfo_opt.foreach(extendedInfo => require(shortChannelIds.array.size == extendedInfo.array.size, s"shortChannelIds.size=${shortChannelIds.array.size} != extendedInfo.size=${extendedInfo.array.size}"))
}

case class GossipTimestampFilter(chainHash: ByteVector32,
                                 firstTimestamp: Long,
                                 timestampRange: Long) extends RoutingMessage with HasChainHash

case class TimestampsAndChecksums(timestamp1: Long,
                                  checksum1: Long,
                                  timestamp2: Long,
                                  checksum2: Long)

case class ExtendedInfo(array: List[TimestampsAndChecksums])