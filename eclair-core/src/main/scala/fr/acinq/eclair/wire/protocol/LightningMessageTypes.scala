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
import com.google.common.net.InetAddresses
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, OutPoint, Satoshi, SatoshiLong, ScriptWitness, Transaction}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{ChannelFlags, ChannelType}
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.wire.protocol.ChannelReadyTlv.ShortChannelIdTlv
import fr.acinq.eclair.{Alias, BlockHeight, CltvExpiry, CltvExpiryDelta, Feature, Features, InitFeature, MilliSatoshi, MilliSatoshiLong, RealShortChannelId, ShortChannelId, TimestampSecond, UInt64, isAsciiPrintable}
import scodec.bits.ByteVector

import java.net.{Inet4Address, Inet6Address, InetAddress}
import java.nio.charset.StandardCharsets
import scala.util.Try

/**
 * Created by PM on 15/11/2016.
 */

// @formatter:off
sealed trait LightningMessage extends Serializable
sealed trait SetupMessage extends LightningMessage
sealed trait ChannelMessage extends LightningMessage
sealed trait InteractiveTxMessage extends LightningMessage
sealed trait InteractiveTxConstructionMessage extends InteractiveTxMessage // <- not in the spec
sealed trait HtlcMessage extends LightningMessage
sealed trait RoutingMessage extends LightningMessage
sealed trait AnnouncementMessage extends RoutingMessage // <- not in the spec
sealed trait HasTimestamp extends LightningMessage { def timestamp: TimestampSecond }
sealed trait HasTemporaryChannelId extends LightningMessage { def temporaryChannelId: ByteVector32 } // <- not in the spec
sealed trait HasChannelId extends LightningMessage { def channelId: ByteVector32 } // <- not in the spec
sealed trait HasChainHash extends LightningMessage { def chainHash: ByteVector32 } // <- not in the spec
sealed trait HasSerialId extends LightningMessage { def serialId: UInt64 } // <- not in the spec
sealed trait UpdateMessage extends HtlcMessage // <- not in the spec
sealed trait HtlcSettlementMessage extends UpdateMessage { def id: Long } // <- not in the spec
sealed trait HtlcFailureMessage extends HtlcSettlementMessage // <- not in the spec
// @formatter:on

case class Init(features: Features[InitFeature], tlvStream: TlvStream[InitTlv] = TlvStream.empty) extends SetupMessage {
  val networks = tlvStream.get[InitTlv.Networks].map(_.chainHashes).getOrElse(Nil)
  val remoteAddress_opt = tlvStream.get[InitTlv.RemoteAddress].map(_.address)
}

case class Warning(channelId: ByteVector32, data: ByteVector, tlvStream: TlvStream[WarningTlv] = TlvStream.empty) extends SetupMessage with HasChannelId {
  def toAscii: String = if (isAsciiPrintable(data)) new String(data.toArray, StandardCharsets.US_ASCII) else "n/a"
}

object Warning {
  // @formatter:off
  def apply(channelId: ByteVector32, msg: String): Warning = Warning(channelId, ByteVector.view(msg.getBytes(Charsets.US_ASCII)))
  def apply(msg: String): Warning = Warning(ByteVector32.Zeroes, ByteVector.view(msg.getBytes(Charsets.US_ASCII)))
  // @formatter:on
}

case class Error(channelId: ByteVector32, data: ByteVector, tlvStream: TlvStream[ErrorTlv] = TlvStream.empty) extends SetupMessage with HasChannelId {
  def toAscii: String = if (isAsciiPrintable(data)) new String(data.toArray, StandardCharsets.US_ASCII) else "n/a"
}

object Error {
  def apply(channelId: ByteVector32, msg: String): Error = Error(channelId, ByteVector.view(msg.getBytes(Charsets.US_ASCII)))
}

case class Ping(pongLength: Int, data: ByteVector, tlvStream: TlvStream[PingTlv] = TlvStream.empty) extends SetupMessage

case class Pong(data: ByteVector, tlvStream: TlvStream[PongTlv] = TlvStream.empty) extends SetupMessage

case class TxAddInput(channelId: ByteVector32,
                      serialId: UInt64,
                      previousTx_opt: Option[Transaction],
                      previousTxOutput: Long,
                      sequence: Long,
                      tlvStream: TlvStream[TxAddInputTlv] = TlvStream.empty) extends InteractiveTxConstructionMessage with HasChannelId with HasSerialId {
  val sharedInput_opt: Option[OutPoint] = tlvStream.get[TxAddInputTlv.SharedInputTxId].map(i => OutPoint(i.txid.reverse, previousTxOutput))
}

object TxAddInput {
  def apply(channelId: ByteVector32, serialId: UInt64, sharedInput: OutPoint, sequence: Long): TxAddInput = {
    TxAddInput(channelId, serialId, None, sharedInput.index, sequence, TlvStream(TxAddInputTlv.SharedInputTxId(sharedInput.txid)))
  }
}

case class TxAddOutput(channelId: ByteVector32,
                       serialId: UInt64,
                       amount: Satoshi,
                       pubkeyScript: ByteVector,
                       tlvStream: TlvStream[TxAddOutputTlv] = TlvStream.empty) extends InteractiveTxConstructionMessage with HasChannelId with HasSerialId

case class TxRemoveInput(channelId: ByteVector32,
                         serialId: UInt64,
                         tlvStream: TlvStream[TxRemoveInputTlv] = TlvStream.empty) extends InteractiveTxConstructionMessage with HasChannelId with HasSerialId

case class TxRemoveOutput(channelId: ByteVector32,
                          serialId: UInt64,
                          tlvStream: TlvStream[TxRemoveOutputTlv] = TlvStream.empty) extends InteractiveTxConstructionMessage with HasChannelId with HasSerialId

case class TxComplete(channelId: ByteVector32,
                      tlvStream: TlvStream[TxCompleteTlv] = TlvStream.empty) extends InteractiveTxConstructionMessage with HasChannelId

case class TxSignatures(channelId: ByteVector32,
                        txHash: ByteVector32,
                        witnesses: Seq[ScriptWitness],
                        tlvStream: TlvStream[TxSignaturesTlv] = TlvStream.empty) extends InteractiveTxMessage with HasChannelId {
  val txId: ByteVector32 = txHash.reverse
  val previousFundingTxSig_opt: Option[ByteVector64] = tlvStream.get[TxSignaturesTlv.PreviousFundingTxSig].map(_.sig)
}

object TxSignatures {
  def apply(channelId: ByteVector32, tx: Transaction, witnesses: Seq[ScriptWitness], previousFundingSig_opt: Option[ByteVector64]): TxSignatures = {
    TxSignatures(channelId, tx.hash, witnesses, TlvStream(previousFundingSig_opt.map(TxSignaturesTlv.PreviousFundingTxSig).toSet[TxSignaturesTlv]))
  }
}

case class TxInitRbf(channelId: ByteVector32,
                     lockTime: Long,
                     feerate: FeeratePerKw,
                     tlvStream: TlvStream[TxInitRbfTlv] = TlvStream.empty) extends InteractiveTxMessage with HasChannelId {
  val fundingContribution: Satoshi = tlvStream.get[TxRbfTlv.SharedOutputContributionTlv].map(_.amount).getOrElse(0 sat)
}

object TxInitRbf {
  def apply(channelId: ByteVector32, lockTime: Long, feerate: FeeratePerKw, fundingContribution: Satoshi): TxInitRbf =
    TxInitRbf(channelId, lockTime, feerate, TlvStream[TxInitRbfTlv](TxRbfTlv.SharedOutputContributionTlv(fundingContribution)))
}

case class TxAckRbf(channelId: ByteVector32,
                    tlvStream: TlvStream[TxAckRbfTlv] = TlvStream.empty) extends InteractiveTxMessage with HasChannelId {
  val fundingContribution: Satoshi = tlvStream.get[TxRbfTlv.SharedOutputContributionTlv].map(_.amount).getOrElse(0 sat)
}

object TxAckRbf {
  def apply(channelId: ByteVector32, fundingContribution: Satoshi): TxAckRbf =
    TxAckRbf(channelId, TlvStream[TxAckRbfTlv](TxRbfTlv.SharedOutputContributionTlv(fundingContribution)))
}

case class TxAbort(channelId: ByteVector32,
                   data: ByteVector,
                   tlvStream: TlvStream[TxAbortTlv] = TlvStream.empty) extends InteractiveTxMessage with HasChannelId {
  def toAscii: String = if (isAsciiPrintable(data)) new String(data.toArray, StandardCharsets.US_ASCII) else "n/a"
}

object TxAbort {
  def apply(channelId: ByteVector32, msg: String): TxAbort = TxAbort(channelId, ByteVector.view(msg.getBytes(Charsets.US_ASCII)))
}

case class ChannelReestablish(channelId: ByteVector32,
                              nextLocalCommitmentNumber: Long,
                              nextRemoteRevocationNumber: Long,
                              yourLastPerCommitmentSecret: PrivateKey,
                              myCurrentPerCommitmentPoint: PublicKey,
                              tlvStream: TlvStream[ChannelReestablishTlv] = TlvStream.empty) extends ChannelMessage with HasChannelId

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
                       channelFlags: ChannelFlags,
                       tlvStream: TlvStream[OpenChannelTlv] = TlvStream.empty) extends ChannelMessage with HasTemporaryChannelId with HasChainHash {
  val upfrontShutdownScript_opt: Option[ByteVector] = tlvStream.get[ChannelTlv.UpfrontShutdownScriptTlv].map(_.script)
  val channelType_opt: Option[ChannelType] = tlvStream.get[ChannelTlv.ChannelTypeTlv].map(_.channelType)
}

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
                         tlvStream: TlvStream[AcceptChannelTlv] = TlvStream.empty) extends ChannelMessage with HasTemporaryChannelId {
  val upfrontShutdownScript_opt: Option[ByteVector] = tlvStream.get[ChannelTlv.UpfrontShutdownScriptTlv].map(_.script)
  val channelType_opt: Option[ChannelType] = tlvStream.get[ChannelTlv.ChannelTypeTlv].map(_.channelType)
}

// NB: this message is named open_channel2 in the specification.
case class OpenDualFundedChannel(chainHash: ByteVector32,
                                 temporaryChannelId: ByteVector32,
                                 fundingFeerate: FeeratePerKw,
                                 commitmentFeerate: FeeratePerKw,
                                 fundingAmount: Satoshi,
                                 dustLimit: Satoshi,
                                 maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                                 htlcMinimum: MilliSatoshi,
                                 toSelfDelay: CltvExpiryDelta,
                                 maxAcceptedHtlcs: Int,
                                 lockTime: Long,
                                 fundingPubkey: PublicKey,
                                 revocationBasepoint: PublicKey,
                                 paymentBasepoint: PublicKey,
                                 delayedPaymentBasepoint: PublicKey,
                                 htlcBasepoint: PublicKey,
                                 firstPerCommitmentPoint: PublicKey,
                                 secondPerCommitmentPoint: PublicKey,
                                 channelFlags: ChannelFlags,
                                 tlvStream: TlvStream[OpenDualFundedChannelTlv] = TlvStream.empty) extends ChannelMessage with HasTemporaryChannelId with HasChainHash {
  val upfrontShutdownScript_opt: Option[ByteVector] = tlvStream.get[ChannelTlv.UpfrontShutdownScriptTlv].map(_.script)
  val channelType_opt: Option[ChannelType] = tlvStream.get[ChannelTlv.ChannelTypeTlv].map(_.channelType)
  val requireConfirmedInputs: Boolean = tlvStream.get[ChannelTlv.RequireConfirmedInputsTlv].nonEmpty
  val pushAmount: MilliSatoshi = tlvStream.get[ChannelTlv.PushAmountTlv].map(_.amount).getOrElse(0 msat)
}

// NB: this message is named accept_channel2 in the specification.
case class AcceptDualFundedChannel(temporaryChannelId: ByteVector32,
                                   fundingAmount: Satoshi,
                                   dustLimit: Satoshi,
                                   maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                                   htlcMinimum: MilliSatoshi,
                                   minimumDepth: Long,
                                   toSelfDelay: CltvExpiryDelta,
                                   maxAcceptedHtlcs: Int,
                                   fundingPubkey: PublicKey,
                                   revocationBasepoint: PublicKey,
                                   paymentBasepoint: PublicKey,
                                   delayedPaymentBasepoint: PublicKey,
                                   htlcBasepoint: PublicKey,
                                   firstPerCommitmentPoint: PublicKey,
                                   secondPerCommitmentPoint: PublicKey,
                                   tlvStream: TlvStream[AcceptDualFundedChannelTlv] = TlvStream.empty) extends ChannelMessage with HasTemporaryChannelId {
  val upfrontShutdownScript_opt: Option[ByteVector] = tlvStream.get[ChannelTlv.UpfrontShutdownScriptTlv].map(_.script)
  val channelType_opt: Option[ChannelType] = tlvStream.get[ChannelTlv.ChannelTypeTlv].map(_.channelType)
  val requireConfirmedInputs: Boolean = tlvStream.get[ChannelTlv.RequireConfirmedInputsTlv].nonEmpty
  val pushAmount: MilliSatoshi = tlvStream.get[ChannelTlv.PushAmountTlv].map(_.amount).getOrElse(0 msat)
}

case class FundingCreated(temporaryChannelId: ByteVector32,
                          fundingTxid: ByteVector32,
                          fundingOutputIndex: Int,
                          signature: ByteVector64,
                          tlvStream: TlvStream[FundingCreatedTlv] = TlvStream.empty) extends ChannelMessage with HasTemporaryChannelId

case class FundingSigned(channelId: ByteVector32,
                         signature: ByteVector64,
                         tlvStream: TlvStream[FundingSignedTlv] = TlvStream.empty) extends ChannelMessage with HasChannelId

case class ChannelReady(channelId: ByteVector32,
                        nextPerCommitmentPoint: PublicKey,
                        tlvStream: TlvStream[ChannelReadyTlv] = TlvStream.empty) extends ChannelMessage with HasChannelId {
  val alias_opt: Option[Alias] = tlvStream.get[ShortChannelIdTlv].map(_.alias)
}

case class Shutdown(channelId: ByteVector32,
                    scriptPubKey: ByteVector,
                    tlvStream: TlvStream[ShutdownTlv] = TlvStream.empty) extends ChannelMessage with HasChannelId

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
                         onionRoutingPacket: OnionRoutingPacket,
                         tlvStream: TlvStream[UpdateAddHtlcTlv]) extends HtlcMessage with UpdateMessage with HasChannelId {
  val blinding_opt: Option[PublicKey] = tlvStream.get[UpdateAddHtlcTlv.BlindingPoint].map(_.publicKey)
}

object UpdateAddHtlc {
  def apply(channelId: ByteVector32,
            id: Long,
            amountMsat: MilliSatoshi,
            paymentHash: ByteVector32,
            cltvExpiry: CltvExpiry,
            onionRoutingPacket: OnionRoutingPacket,
            blinding_opt: Option[PublicKey]): UpdateAddHtlc = {
    val tlvs = blinding_opt.map(UpdateAddHtlcTlv.BlindingPoint).toSet[UpdateAddHtlcTlv]
    UpdateAddHtlc(channelId, id, amountMsat, paymentHash, cltvExpiry, onionRoutingPacket, TlvStream(tlvs))
  }
}

case class UpdateFulfillHtlc(channelId: ByteVector32,
                             id: Long,
                             paymentPreimage: ByteVector32,
                             tlvStream: TlvStream[UpdateFulfillHtlcTlv] = TlvStream.empty) extends HtlcMessage with UpdateMessage with HasChannelId with HtlcSettlementMessage

case class UpdateFailHtlc(channelId: ByteVector32,
                          id: Long,
                          reason: ByteVector,
                          tlvStream: TlvStream[UpdateFailHtlcTlv] = TlvStream.empty) extends HtlcMessage with UpdateMessage with HasChannelId with HtlcFailureMessage

case class UpdateFailMalformedHtlc(channelId: ByteVector32,
                                   id: Long,
                                   onionHash: ByteVector32,
                                   failureCode: Int,
                                   tlvStream: TlvStream[UpdateFailMalformedHtlcTlv] = TlvStream.empty) extends HtlcMessage with UpdateMessage with HasChannelId with HtlcFailureMessage

case class CommitSig(channelId: ByteVector32,
                     signature: ByteVector64,
                     htlcSignatures: List[ByteVector64],
                     tlvStream: TlvStream[CommitSigTlv] = TlvStream.empty) extends HtlcMessage with HasChannelId {
  val fundingTxId_opt: Option[ByteVector32] = tlvStream.get[CommitSigTlv.FundingTxIdTlv].map(_.txId)
}

case class RevokeAndAck(channelId: ByteVector32,
                        perCommitmentSecret: PrivateKey,
                        nextPerCommitmentPoint: PublicKey,
                        tlvStream: TlvStream[RevokeAndAckTlv] = TlvStream.empty) extends HtlcMessage with HasChannelId

case class UpdateFee(channelId: ByteVector32,
                     feeratePerKw: FeeratePerKw,
                     tlvStream: TlvStream[UpdateFeeTlv] = TlvStream.empty) extends ChannelMessage with UpdateMessage with HasChannelId

case class AnnouncementSignatures(channelId: ByteVector32,
                                  shortChannelId: RealShortChannelId,
                                  nodeSignature: ByteVector64,
                                  bitcoinSignature: ByteVector64,
                                  tlvStream: TlvStream[AnnouncementSignaturesTlv] = TlvStream.empty) extends RoutingMessage with HasChannelId

case class ChannelAnnouncement(nodeSignature1: ByteVector64,
                               nodeSignature2: ByteVector64,
                               bitcoinSignature1: ByteVector64,
                               bitcoinSignature2: ByteVector64,
                               features: Features[Feature],
                               chainHash: ByteVector32,
                               shortChannelId: RealShortChannelId,
                               nodeId1: PublicKey,
                               nodeId2: PublicKey,
                               bitcoinKey1: PublicKey,
                               bitcoinKey2: PublicKey,
                               tlvStream: TlvStream[ChannelAnnouncementTlv] = TlvStream.empty) extends RoutingMessage with AnnouncementMessage with HasChainHash

case class Color(r: Byte, g: Byte, b: Byte) {
  override def toString: String = f"#$r%02x$g%02x$b%02x" // to hexa s"#  ${r}%02x ${r & 0xFF}${g & 0xFF}${b & 0xFF}"
}

// @formatter:off
sealed trait NodeAddress { def host: String; def port: Int; override def toString: String = s"$host:$port" }
sealed trait OnionAddress extends NodeAddress
sealed trait IPAddress extends NodeAddress
// @formatter:on

object NodeAddress {
  /**
   * Creates a NodeAddress from a host and port.
   *
   * Note that only IP v4 and v6 hosts will be resolved, onion and DNS hosts names will not be resolved.
   *
   * We don't attempt to resolve onion addresses (it will be done by the tor proxy), so we just recognize them based on
   * the .onion TLD and rely on their length to separate v2/v3.
   *
   * Host names that are not Tor, IPv4 or IPv6 are assumed to be a DNS name and are not immediately resolved.
   *
   */
  def fromParts(host: String, port: Int): Try[NodeAddress] = Try {
    host match {
      case _ if host.endsWith(".onion") && host.length == 22 => Tor2(host.dropRight(6), port)
      case _ if host.endsWith(".onion") && host.length == 62 => Tor3(host.dropRight(6), port)
      case _ if InetAddresses.isInetAddress(host.filterNot(Set('[', ']'))) => IPAddress(InetAddress.getByName(host), port)
      case _ => DnsHostname(host, port)
    }
  }

  private def isPrivate(address: InetAddress): Boolean = address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress

  def isPublicIPAddress(address: NodeAddress): Boolean = {
    address match {
      case IPv4(ipv4, _) if !isPrivate(ipv4) => true
      case IPv6(ipv6, _) if !isPrivate(ipv6) => true
      case _ => false
    }
  }
}

object IPAddress {
  def apply(inetAddress: InetAddress, port: Int): IPAddress = inetAddress match {
    case address: Inet4Address => IPv4(address, port)
    case address: Inet6Address => IPv6(address, port)
  }
}

// @formatter:off
case class IPv4(ipv4: Inet4Address, port: Int) extends IPAddress { override def host: String = InetAddresses.toUriString(ipv4) }
case class IPv6(ipv6: Inet6Address, port: Int) extends IPAddress { override def host: String = InetAddresses.toUriString(ipv6) }
case class Tor2(tor2: String, port: Int) extends OnionAddress { override def host: String = tor2 + ".onion" }
case class Tor3(tor3: String, port: Int) extends OnionAddress { override def host: String = tor3 + ".onion" }
case class DnsHostname(dnsHostname: String, port: Int) extends IPAddress {override def host: String = dnsHostname}
// @formatter:on

case class NodeAnnouncement(signature: ByteVector64,
                            features: Features[Feature],
                            timestamp: TimestampSecond,
                            nodeId: PublicKey,
                            rgbColor: Color,
                            alias: String,
                            addresses: List[NodeAddress],
                            tlvStream: TlvStream[NodeAnnouncementTlv] = TlvStream.empty) extends RoutingMessage with AnnouncementMessage with HasTimestamp {

  val validAddresses: List[NodeAddress] = {
    // if port is equal to 0, SHOULD ignore ipv6_addr OR ipv4_addr OR hostname; SHOULD ignore Tor v2 onion services.
    val validAddresses = addresses.filter(address => address.port != 0 || address.isInstanceOf[Tor3]).filterNot(address => address.isInstanceOf[Tor2])
    // if more than one type 5 address is announced, SHOULD ignore the additional data.
    validAddresses.filter(!_.isInstanceOf[DnsHostname]) ++ validAddresses.find(_.isInstanceOf[DnsHostname])
  }

  val shouldRebroadcast: Boolean = {
    // if more than one type 5 address is announced, MUST not forward the node_announcement.
    addresses.count(address => address.isInstanceOf[DnsHostname]) <= 1
  }
}

case class ChannelUpdate(signature: ByteVector64,
                         chainHash: ByteVector32,
                         shortChannelId: ShortChannelId,
                         timestamp: TimestampSecond,
                         messageFlags: ChannelUpdate.MessageFlags,
                         channelFlags: ChannelUpdate.ChannelFlags,
                         cltvExpiryDelta: CltvExpiryDelta,
                         htlcMinimumMsat: MilliSatoshi,
                         feeBaseMsat: MilliSatoshi,
                         feeProportionalMillionths: Long,
                         htlcMaximumMsat: MilliSatoshi,
                         tlvStream: TlvStream[ChannelUpdateTlv] = TlvStream.empty) extends RoutingMessage with AnnouncementMessage with HasTimestamp with HasChainHash {
  val dontForward: Boolean = messageFlags.dontForward

  def toStringShort: String = s"cltvExpiryDelta=$cltvExpiryDelta,feeBase=$feeBaseMsat,feeProportionalMillionths=$feeProportionalMillionths"

  def relayFees: Relayer.RelayFees = Relayer.RelayFees(feeBase = feeBaseMsat, feeProportionalMillionths = feeProportionalMillionths)
}

object ChannelUpdate {
  case class MessageFlags(dontForward: Boolean)

  case class ChannelFlags(isEnabled: Boolean, isNode1: Boolean)

  object ChannelFlags {
    /** for tests */
    val DUMMY: ChannelFlags = ChannelFlags(isEnabled = true, isNode1 = true)
  }
}

// @formatter:off
sealed trait EncodingType
object EncodingType {
  case object UNCOMPRESSED extends EncodingType
  case object COMPRESSED_ZLIB extends EncodingType
}
// @formatter:on

case class EncodedShortChannelIds(encoding: EncodingType, array: List[RealShortChannelId]) {
  /** custom toString because it can get huge in logs */
  override def toString: String = s"EncodedShortChannelIds($encoding,${array.headOption.getOrElse("")}->${array.lastOption.getOrElse("")} size=${array.size})"
}

case class QueryShortChannelIds(chainHash: ByteVector32, shortChannelIds: EncodedShortChannelIds, tlvStream: TlvStream[QueryShortChannelIdsTlv] = TlvStream.empty) extends RoutingMessage with HasChainHash {
  val queryFlags_opt: Option[QueryShortChannelIdsTlv.EncodedQueryFlags] = tlvStream.get[QueryShortChannelIdsTlv.EncodedQueryFlags]
}

case class ReplyShortChannelIdsEnd(chainHash: ByteVector32, complete: Byte, tlvStream: TlvStream[ReplyShortChannelIdsEndTlv] = TlvStream.empty) extends RoutingMessage with HasChainHash

case class QueryChannelRange(chainHash: ByteVector32, firstBlock: BlockHeight, numberOfBlocks: Long, tlvStream: TlvStream[QueryChannelRangeTlv] = TlvStream.empty) extends RoutingMessage {
  val queryFlags_opt: Option[QueryChannelRangeTlv.QueryFlags] = tlvStream.get[QueryChannelRangeTlv.QueryFlags]
}

case class ReplyChannelRange(chainHash: ByteVector32, firstBlock: BlockHeight, numberOfBlocks: Long, syncComplete: Byte, shortChannelIds: EncodedShortChannelIds, tlvStream: TlvStream[ReplyChannelRangeTlv] = TlvStream.empty) extends RoutingMessage {
  val timestamps_opt: Option[ReplyChannelRangeTlv.EncodedTimestamps] = tlvStream.get[ReplyChannelRangeTlv.EncodedTimestamps]
  val checksums_opt: Option[ReplyChannelRangeTlv.EncodedChecksums] = tlvStream.get[ReplyChannelRangeTlv.EncodedChecksums]
}

object ReplyChannelRange {
  def apply(chainHash: ByteVector32,
            firstBlock: BlockHeight,
            numberOfBlocks: Long,
            syncComplete: Byte,
            shortChannelIds: EncodedShortChannelIds,
            timestamps: Option[ReplyChannelRangeTlv.EncodedTimestamps],
            checksums: Option[ReplyChannelRangeTlv.EncodedChecksums]): ReplyChannelRange = {
    timestamps.foreach(ts => require(ts.timestamps.length == shortChannelIds.array.length))
    checksums.foreach(cs => require(cs.checksums.length == shortChannelIds.array.length))
    new ReplyChannelRange(chainHash, firstBlock, numberOfBlocks, syncComplete, shortChannelIds, TlvStream(Set(timestamps, checksums).flatten[ReplyChannelRangeTlv]))
  }
}

case class GossipTimestampFilter(chainHash: ByteVector32, firstTimestamp: TimestampSecond, timestampRange: Long, tlvStream: TlvStream[GossipTimestampFilterTlv] = TlvStream.empty) extends RoutingMessage with HasChainHash

case class OnionMessage(blindingKey: PublicKey, onionRoutingPacket: OnionRoutingPacket, tlvStream: TlvStream[OnionMessageTlv] = TlvStream.empty) extends LightningMessage

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