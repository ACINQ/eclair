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

import java.net.{Inet4Address, Inet6Address, InetSocketAddress}

import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import fr.acinq.eclair.{ShortChannelId, UInt64}
import scodec.bits.BitVector

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
sealed trait HasTemporaryChannelId extends LightningMessage { def temporaryChannelId: BinaryData } // <- not in the spec
sealed trait HasChannelId extends LightningMessage { def channelId: BinaryData } // <- not in the spec
sealed trait HasChainHash extends LightningMessage { def chainHash: BinaryData } // <- not in the spec
sealed trait UpdateMessage extends HtlcMessage // <- not in the spec
// @formatter:on

case class Init(globalFeatures: BinaryData,
                localFeatures: BinaryData) extends SetupMessage

case class Error(channelId: BinaryData,
                 data: BinaryData) extends SetupMessage with HasChannelId

case class Ping(pongLength: Int, data: BinaryData) extends SetupMessage

case class Pong(data: BinaryData) extends SetupMessage

case class ChannelReestablish(channelId: BinaryData,
                              nextLocalCommitmentNumber: Long,
                              nextRemoteRevocationNumber: Long,
                              yourLastPerCommitmentSecret: Option[Scalar] = None,
                              myCurrentPerCommitmentPoint: Option[Point] = None) extends ChannelMessage with HasChannelId

case class OpenChannel(chainHash: BinaryData,
                       temporaryChannelId: BinaryData,
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

case class AcceptChannel(temporaryChannelId: BinaryData,
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

case class FundingCreated(temporaryChannelId: BinaryData,
                          fundingTxid: BinaryData,
                          fundingOutputIndex: Int,
                          signature: BinaryData) extends ChannelMessage with HasTemporaryChannelId

case class FundingSigned(channelId: BinaryData,
                         signature: BinaryData) extends ChannelMessage with HasChannelId

case class FundingLocked(channelId: BinaryData,
                         nextPerCommitmentPoint: Point) extends ChannelMessage with HasChannelId

case class Shutdown(channelId: BinaryData,
                    scriptPubKey: BinaryData) extends ChannelMessage with HasChannelId

case class ClosingSigned(channelId: BinaryData,
                         feeSatoshis: Long,
                         signature: BinaryData) extends ChannelMessage with HasChannelId

case class UpdateAddHtlc(channelId: BinaryData,
                         id: Long,
                         amountMsat: Long,
                         paymentHash: BinaryData,
                         cltvExpiry: Long,
                         onionRoutingPacket: BinaryData) extends HtlcMessage with UpdateMessage with HasChannelId

case class UpdateFulfillHtlc(channelId: BinaryData,
                             id: Long,
                             paymentPreimage: BinaryData) extends HtlcMessage with UpdateMessage with HasChannelId

case class UpdateFailHtlc(channelId: BinaryData,
                          id: Long,
                          reason: BinaryData) extends HtlcMessage with UpdateMessage with HasChannelId

case class UpdateFailMalformedHtlc(channelId: BinaryData,
                                   id: Long,
                                   onionHash: BinaryData,
                                   failureCode: Int) extends HtlcMessage with UpdateMessage with HasChannelId

case class CommitSig(channelId: BinaryData,
                     signature: BinaryData,
                     htlcSignatures: List[BinaryData]) extends HtlcMessage with HasChannelId

case class RevokeAndAck(channelId: BinaryData,
                        perCommitmentSecret: Scalar,
                        nextPerCommitmentPoint: Point) extends HtlcMessage with HasChannelId

case class UpdateFee(channelId: BinaryData,
                     feeratePerKw: Long) extends ChannelMessage with UpdateMessage with HasChannelId

case class AnnouncementSignatures(channelId: BinaryData,
                                  shortChannelId: ShortChannelId,
                                  nodeSignature: BinaryData,
                                  bitcoinSignature: BinaryData) extends RoutingMessage with HasChannelId

case class ChannelAnnouncement(nodeSignature1: BinaryData,
                               nodeSignature2: BinaryData,
                               bitcoinSignature1: BinaryData,
                               bitcoinSignature2: BinaryData,
                               features: BinaryData,
                               chainHash: BinaryData,
                               shortChannelId: ShortChannelId,
                               nodeId1: PublicKey,
                               nodeId2: PublicKey,
                               bitcoinKey1: PublicKey,
                               bitcoinKey2: PublicKey) extends RoutingMessage with HasChainHash

case class Color(r: Byte, g: Byte, b: Byte) {
  override def toString: String = f"#$r%02x$g%02x$b%02x" // to hexa s"#  ${r}%02x ${r & 0xFF}${g & 0xFF}${b & 0xFF}"
}

// @formatter:off
sealed trait NodeAddress
case object NodeAddress {
  def apply(inetSocketAddress: InetSocketAddress): NodeAddress = inetSocketAddress.getAddress match {
    case a: Inet4Address => IPv4(a, inetSocketAddress.getPort)
    case a: Inet6Address => IPv6(a, inetSocketAddress.getPort)
    case _ => throw new RuntimeException(s"Invalid socket address $inetSocketAddress")
  }
}
case object Padding extends NodeAddress
case class IPv4(ipv4: Inet4Address, port: Int) extends NodeAddress
case class IPv6(ipv6: Inet6Address, port: Int) extends NodeAddress
case class Tor2(tor2: BinaryData, port: Int) extends NodeAddress { require(tor2.size == 10) }
case class Tor3(tor3: BinaryData, port: Int) extends NodeAddress { require(tor3.size == 35) }
// @formatter:on

case class NodeAnnouncement(signature: BinaryData,
                            features: BinaryData,
                            timestamp: Long,
                            nodeId: PublicKey,
                            rgbColor: Color,
                            alias: String,
                            addresses: List[NodeAddress]) extends RoutingMessage with HasTimestamp {
  def socketAddresses: List[InetSocketAddress] = addresses.collect {
    case IPv4(a, port) => new InetSocketAddress(a, port)
    case IPv6(a, port) => new InetSocketAddress(a, port)
  }
}

case class ChannelUpdate(signature: BinaryData,
                         chainHash: BinaryData,
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
}

case class PerHopPayload(shortChannelId: ShortChannelId,
                         amtToForward: Long,
                         outgoingCltvValue: Long)

// BOLT 1.0 channel queries

case object EncodingTypes {
  val UNCOMPRESSED: Byte = 0
  val COMPRESSED_ZLIB: Byte = 1
}

case class EncodedShortChannelIds(encoding: Byte,
                                  array: List[ShortChannelId])

case class QueryShortChannelIds(chainHash: BinaryData,
                                data: EncodedShortChannelIds) extends RoutingMessage with HasChainHash

case class ReplyShortChannelIdsEnd(chainHash: BinaryData,
                                   complete: Byte) extends RoutingMessage with HasChainHash

case class QueryChannelRange(chainHash: BinaryData,
                             firstBlockNum: Long,
                             numberOfBlocks: Long) extends RoutingMessage with HasChainHash

case class ReplyChannelRange(chainHash: BinaryData,
                             firstBlockNum: Long,
                             numberOfBlocks: Long,
                             complete: Byte,
                             data: EncodedShortChannelIds) extends RoutingMessage with HasChainHash

case class GossipTimestampFilter(chainHash: BinaryData,
                                 firstTimestamp: Long,
                                 timestampRange: Long) extends RoutingMessage with HasChainHash

// prototype queries, used by eclair only, to be removed asap

case object FlagTypes {
  val INCLUDE_CHANNEL_UPDATE_1: Byte = 1
  val INCLUDE_CHANNEL_UPDATE_2: Byte = 2
  val INCLUDE_ANNOUNCEMENT: Byte = 4

  def includeAnnouncement(flag: Byte) = (flag & FlagTypes.INCLUDE_ANNOUNCEMENT) != 0

  def includeUpdate1(flag: Byte) = (flag & FlagTypes.INCLUDE_CHANNEL_UPDATE_1) != 0

  def includeUpdate2(flag: Byte) = (flag & FlagTypes.INCLUDE_CHANNEL_UPDATE_2) != 0
}

case class QueryShortChannelIdsDeprecated(chainHash: BinaryData,
                                          flag: Byte,
                                          data: EncodedShortChannelIds) extends RoutingMessage with HasChainHash

case class ReplyShortChannelIdsEndDeprecated(chainHash: BinaryData,
                                             complete: Byte) extends RoutingMessage with HasChainHash

case class QueryChannelRangeDeprecated(chainHash: BinaryData,
                                       firstBlockNum: Long,
                                       numberOfBlocks: Long) extends RoutingMessage with HasChainHash

case class ShortChannelIdWithTimestamp(shortChannelId: ShortChannelId,
                                       timestamp: Long)

case class EncodedShortChannelIdsWithTimestamp(encoding: Byte,
                                               array: List[ShortChannelIdWithTimestamp])

case class ReplyChannelRangeDeprecated(chainHash: BinaryData,
                                       firstBlockNum: Long,
                                       numberOfBlocks: Long,
                                       complete: Byte,
                                       data: EncodedShortChannelIdsWithTimestamp) extends RoutingMessage with HasChainHash

// proposal for BOLT 1.1 channel queries

case class ShortChannelIdAndFlag(shortChannelId: ShortChannelId,
                                 flag: Byte)

case class EncodedShortChannelIdsAndFlag(encoding: Byte,
                                         array: List[ShortChannelIdAndFlag])

case class QueryShortChannelIdsWithFlags(chainHash: BinaryData,
                                         data: EncodedShortChannelIdsAndFlag) extends RoutingMessage with HasChainHash

case class ReplyShortChannelIdsWithFlagsEnd(chainHash: BinaryData,
                                            complete: Byte) extends RoutingMessage with HasChainHash

// 2nd proposal with checksums

case class QueryChannelRangeWithChecksums(chainHash: BinaryData,
                                          firstBlockNum: Long,
                                          numberOfBlocks: Long) extends RoutingMessage with HasChainHash

case class ShortChannelIdWithChecksums(shortChannelId: ShortChannelId,
                                       timestamp1: Long,
                                       timestamp2: Long,
                                       checksum1: Long,
                                       checksum2: Long)

case class EncodedShortChannelIdsWithChecksums(encoding: Byte,
                                               array: List[ShortChannelIdWithChecksums])

case class ReplyChannelRangeWithChecksums(chainHash: BinaryData,
                                          firstBlockNum: Long,
                                          numberOfBlocks: Long,
                                          complete: Byte,
                                          data: EncodedShortChannelIdsWithChecksums) extends RoutingMessage with HasChainHash