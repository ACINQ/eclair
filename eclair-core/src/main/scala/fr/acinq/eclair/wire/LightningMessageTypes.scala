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

package fr.acinq.eclair.wire

import java.net.{Inet4Address, Inet6Address, InetAddress, InetSocketAddress}
import java.nio.ByteOrder.LITTLE_ENDIAN
import com.google.common.base.Charsets
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, LexicographicalOrdering, Protocol, Satoshi}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, MilliSatoshi, ShortChannelId, UInt64}
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
                 data: ByteVector) extends SetupMessage with HasChannelId {

  lazy val taggedData: ByteVector = data.drop(2)

  lazy val tag: ByteVector = data.take(2)
}

object Error {
  def apply(channelId: ByteVector32, msg: String): Error = Error(channelId, ByteVector.view(msg.getBytes(Charsets.US_ASCII)))
}

case class Ping(pongLength: Int, data: ByteVector) extends SetupMessage

case class Pong(data: ByteVector) extends SetupMessage

case class ChannelReestablish(channelId: ByteVector32,
                              nextLocalCommitmentNumber: Long,
                              nextRemoteRevocationNumber: Long,
                              yourLastPerCommitmentSecret: Option[PrivateKey] = None,
                              myCurrentPerCommitmentPoint: Option[PublicKey] = None) extends ChannelMessage with HasChannelId

case class OpenChannel(chainHash: ByteVector32,
                       temporaryChannelId: ByteVector32,
                       fundingSatoshis: Satoshi,
                       pushMsat: MilliSatoshi,
                       dustLimitSatoshis: Satoshi,
                       maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                       channelReserveSatoshis: Satoshi,
                       htlcMinimumMsat: MilliSatoshi,
                       feeratePerKw: Long,
                       toSelfDelay: CltvExpiryDelta,
                       maxAcceptedHtlcs: Int,
                       fundingPubkey: PublicKey,
                       revocationBasepoint: PublicKey,
                       paymentBasepoint: PublicKey,
                       delayedPaymentBasepoint: PublicKey,
                       htlcBasepoint: PublicKey,
                       firstPerCommitmentPoint: PublicKey,
                       channelFlags: Byte) extends ChannelMessage with HasTemporaryChannelId with HasChainHash

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
                         firstPerCommitmentPoint: PublicKey) extends ChannelMessage with HasTemporaryChannelId

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
                         signature: ByteVector64) extends ChannelMessage with HasChannelId

case class UpdateAddHtlc(channelId: ByteVector32,
                         id: Long,
                         amountMsat: MilliSatoshi,
                         paymentHash: ByteVector32,
                         cltvExpiry: CltvExpiry,
                         onionRoutingPacket: OnionRoutingPacket,
                         tlvStream: TlvStream[Tlv] = TlvStream.empty) extends HtlcMessage with UpdateMessage with HasChannelId {

  lazy val secret: Option[UpdateAddSecretTlv.Secret] = tlvStream.get[UpdateAddSecretTlv.Secret]
}

case class UpdateFulfillHtlc(channelId: ByteVector32,
                             id: Long,
                             paymentPreimage: ByteVector32) extends HtlcMessage with UpdateMessage with HasChannelId{

  lazy val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage)
}

case class UpdateFailHtlc(channelId: ByteVector32,
                          id: Long,
                          reason: ByteVector) extends HtlcMessage with UpdateMessage with HasChannelId

case class UpdateFailMalformedHtlc(channelId: ByteVector32,
                                   id: Long,
                                   onionHash: ByteVector32,
                                   failureCode: Int) extends HtlcMessage with UpdateMessage with HasChannelId

case class CommitSig(channelId: ByteVector32,
                     signature: ByteVector64,
                     htlcSignatures: List[ByteVector64]) extends HtlcMessage with HasChannelId

case class RevokeAndAck(channelId: ByteVector32,
                        perCommitmentSecret: PrivateKey,
                        nextPerCommitmentPoint: PublicKey) extends HtlcMessage with HasChannelId

case class UpdateFee(channelId: ByteVector32,
                     feeratePerKw: Long) extends ChannelMessage with UpdateMessage with HasChannelId

case class AnnouncementSignatures(channelId: ByteVector32,
                                  shortChannelId: ShortChannelId,
                                  nodeSignature: ByteVector64,
                                  bitcoinSignature: ByteVector64) extends RoutingMessage with HasChannelId

case class ChannelAnnouncement(nodeSignature1: ByteVector64,
                               nodeSignature2: ByteVector64,
                               bitcoinSignature1: ByteVector64,
                               bitcoinSignature2: ByteVector64,
                               features: ByteVector,
                               chainHash: ByteVector32,
                               shortChannelId: ShortChannelId,
                               nodeId1: PublicKey,
                               nodeId2: PublicKey,
                               bitcoinKey1: PublicKey,
                               bitcoinKey2: PublicKey,
                               unknownFields: ByteVector = ByteVector.empty) extends RoutingMessage with HasChainHash

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
                            features: ByteVector,
                            timestamp: Long,
                            nodeId: PublicKey,
                            rgbColor: Color,
                            alias: String,
                            addresses: List[NodeAddress],
                            unknownFields: ByteVector = ByteVector.empty) extends RoutingMessage with HasTimestamp

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
                         unknownFields: ByteVector = ByteVector.empty) extends RoutingMessage with HasTimestamp with HasChainHash {
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

case class EncodedShortChannelIds(encoding: EncodingType,
                                  array: List[ShortChannelId])

case class QueryShortChannelIds(chainHash: ByteVector32,
                                shortChannelIds: EncodedShortChannelIds,
                                tlvStream: TlvStream[QueryShortChannelIdsTlv] = TlvStream.empty) extends RoutingMessage with HasChainHash {
  val queryFlags_opt: Option[QueryShortChannelIdsTlv.EncodedQueryFlags] = tlvStream.get[QueryShortChannelIdsTlv.EncodedQueryFlags]
}

case class ReplyShortChannelIdsEnd(chainHash: ByteVector32,
                                   complete: Byte) extends RoutingMessage with HasChainHash


case class QueryChannelRange(chainHash: ByteVector32,
                             firstBlockNum: Long,
                             numberOfBlocks: Long,
                             tlvStream: TlvStream[QueryChannelRangeTlv] = TlvStream.empty) extends RoutingMessage {
  val queryFlags_opt: Option[QueryChannelRangeTlv.QueryFlags] = tlvStream.get[QueryChannelRangeTlv.QueryFlags]
}

case class ReplyChannelRange(chainHash: ByteVector32,
                             firstBlockNum: Long,
                             numberOfBlocks: Long,
                             complete: Byte,
                             shortChannelIds: EncodedShortChannelIds,
                             tlvStream: TlvStream[ReplyChannelRangeTlv] = TlvStream.empty) extends RoutingMessage {
  val timestamps_opt: Option[ReplyChannelRangeTlv.EncodedTimestamps] = tlvStream.get[ReplyChannelRangeTlv.EncodedTimestamps]

  val checksums_opt: Option[ReplyChannelRangeTlv.EncodedChecksums] = tlvStream.get[ReplyChannelRangeTlv.EncodedChecksums]
}

object ReplyChannelRange {
  def apply(chainHash: ByteVector32,
            firstBlockNum: Long,
            numberOfBlocks: Long,
            complete: Byte,
            shortChannelIds: EncodedShortChannelIds,
            timestamps: Option[ReplyChannelRangeTlv.EncodedTimestamps],
            checksums: Option[ReplyChannelRangeTlv.EncodedChecksums]) = {
    timestamps.foreach(ts => require(ts.timestamps.length == shortChannelIds.array.length))
    checksums.foreach(cs => require(cs.checksums.length == shortChannelIds.array.length))
    new ReplyChannelRange(chainHash, firstBlockNum, numberOfBlocks, complete, shortChannelIds, TlvStream(timestamps.toList ::: checksums.toList))
  }
}


case class GossipTimestampFilter(chainHash: ByteVector32,
                                 firstTimestamp: Long,
                                 timestampRange: Long) extends RoutingMessage with HasChainHash

// Hosted message types

trait HostedChannelMessage extends ChannelMessage

case class InvokeHostedChannel(chainHash: ByteVector32,
                               refundScriptPubKey: ByteVector) extends HostedChannelMessage

case class InitHostedChannel(maxHtlcValueInFlightMsat: UInt64,
                             htlcMinimumMsat: MilliSatoshi,
                             maxAcceptedHtlcs: Int,
                             channelCapacityMsat: MilliSatoshi,
                             liabilityDeadlineBlockdays: Int,
                             minimalOnchainRefundAmountSatoshis: Satoshi,
                             initialClientBalanceMsat: MilliSatoshi) extends HostedChannelMessage

case class LastCrossSignedState(refundScriptPubKey: ByteVector,
                                initHostedChannel: InitHostedChannel,
                                blockDay: Long,
                                localBalanceMsat: MilliSatoshi,
                                remoteBalanceMsat: MilliSatoshi,
                                localUpdates: Long,
                                remoteUpdates: Long,
                                incomingHtlcs: List[UpdateAddHtlc],
                                outgoingHtlcs: List[UpdateAddHtlc],
                                remoteSignature: ByteVector64) extends HostedChannelMessage {

  lazy val reverse: LastCrossSignedState =
    copy(localUpdates = remoteUpdates,
      remoteUpdates = localUpdates,
      localBalanceMsat = remoteBalanceMsat,
      remoteBalanceMsat = localBalanceMsat,
      incomingHtlcs = outgoingHtlcs,
      outgoingHtlcs = incomingHtlcs)

  lazy val hostedSigHash: ByteVector32 = {
    val inPayments = incomingHtlcs.map(LightningMessageCodecs.updateAddHtlcCodec.encode(_).require.toByteVector).sortWith(LexicographicalOrdering.isLessThan)
    val outPayments = outgoingHtlcs.map(LightningMessageCodecs.updateAddHtlcCodec.encode(_).require.toByteVector).sortWith(LexicographicalOrdering.isLessThan)

    val preimage =
      refundScriptPubKey ++
        Protocol.writeUInt16(initHostedChannel.liabilityDeadlineBlockdays, LITTLE_ENDIAN) ++
        Protocol.writeUInt64(initHostedChannel.minimalOnchainRefundAmountSatoshis.toLong, LITTLE_ENDIAN) ++
        Protocol.writeUInt64(initHostedChannel.channelCapacityMsat.toLong, LITTLE_ENDIAN) ++
        Protocol.writeUInt64(initHostedChannel.initialClientBalanceMsat.toLong, LITTLE_ENDIAN) ++
        Protocol.writeUInt32(blockDay, LITTLE_ENDIAN) ++
        Protocol.writeUInt64(localBalanceMsat.toLong, LITTLE_ENDIAN) ++
        Protocol.writeUInt64(remoteBalanceMsat.toLong, LITTLE_ENDIAN) ++
        Protocol.writeUInt32(localUpdates, LITTLE_ENDIAN) ++
        Protocol.writeUInt32(remoteUpdates, LITTLE_ENDIAN) ++
        inPayments.foldLeft(ByteVector.empty) { case (acc, htlc) => acc ++ htlc } ++
        outPayments.foldLeft(ByteVector.empty) { case (acc, htlc) => acc ++ htlc }

    Crypto.sha256(preimage)
  }

  def makeSignature(priv: PrivateKey): ByteVector64 = Crypto.sign(hostedSigHash, priv)
  def verifyRemoteSignature(pub: PublicKey): Boolean = Crypto.verifySignature(hostedSigHash, remoteSignature, pub)
  def makeStateUpdate(priv: PrivateKey) = StateUpdate(blockDay, localUpdates, remoteUpdates, makeSignature(priv))
}

case class StateUpdate(blockDay: Long,
                       localUpdates: Long,
                       remoteUpdates: Long,
                       localSigOfRemoteLCSS: ByteVector64) extends HostedChannelMessage

case class StateOverride(blockDay: Long,
                         localBalanceMsat: MilliSatoshi,
                         localUpdates: Long,
                         remoteUpdates: Long,
                         localSigOfRemoteLCSS: ByteVector64) extends HostedChannelMessage