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

import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import fr.acinq.eclair.{ShortChannelId, UInt64}

import scala.util.{Success, Try}

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


case class NodeAnnouncement(signature: BinaryData,
                            features: BinaryData,
                            timestamp: Long,
                            nodeId: PublicKey,
                            rgbColor: Color,
                            alias: String,
                            addresses: List[NodeAddress]) extends RoutingMessage with HasTimestamp

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

case class QueryShortChannelIds(chainHash: BinaryData,
                                data: BinaryData) extends RoutingMessage with HasChainHash

case class QueryChannelRange(chainHash: BinaryData,
                             firstBlockNum: Long,
                             numberOfBlocks: Long) extends RoutingMessage with HasChainHash

case class ReplyChannelRange(chainHash: BinaryData,
                             firstBlockNum: Long,
                             numberOfBlocks: Long,
                             complete: Byte,
                             data: BinaryData) extends RoutingMessage with HasChainHash

case class ReplyShortChannelIdsEnd(chainHash: BinaryData,
                                   complete: Byte) extends RoutingMessage with HasChainHash

case class GossipTimestampFilter(chainHash: BinaryData,
                                 firstTimestamp: Long,
                                 timestampRange: Long) extends RoutingMessage with HasChainHash