package fr.acinq.eclair.wire

import java.net.InetSocketAddress

import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}

/**
  * Created by PM on 15/11/2016.
  */

// @formatter:off
sealed trait LightningMessage
sealed trait SetupMessage extends LightningMessage
sealed trait ChannelMessage extends LightningMessage
sealed trait HtlcMessage extends LightningMessage
sealed trait RoutingMessage extends LightningMessage
sealed trait HasTemporaryChannelId extends LightningMessage { def temporaryChannelId: Long } // <- not in the spec
sealed trait HasChannelId extends LightningMessage { def channelId: Long } // <- not in the spec
sealed trait UpdateMessage extends HtlcMessage // <- not in the spec
// @formatter:on

case class Init(globalFeatures: BinaryData,
                localFeatures: BinaryData) extends SetupMessage

case class Error(channelId: Long,
                 data: BinaryData) extends SetupMessage with HasChannelId

case class OpenChannel(temporaryChannelId: Long,
                       fundingSatoshis: Long,
                       pushMsat: Long,
                       dustLimitSatoshis: Long,
                       maxHtlcValueInFlightMsat: Long,
                       channelReserveSatoshis: Long,
                       htlcMinimumMsat: Long,
                       feeratePerKw: Long,
                       toSelfDelay: Int,
                       maxAcceptedHtlcs: Int,
                       fundingPubkey: PublicKey,
                       revocationBasepoint: Point,
                       paymentBasepoint: Point,
                       delayedPaymentBasepoint: Point,
                       firstPerCommitmentPoint: Point) extends ChannelMessage with HasTemporaryChannelId

case class AcceptChannel(temporaryChannelId: Long,
                         dustLimitSatoshis: Long,
                         maxHtlcValueInFlightMsat: Long,
                         channelReserveSatoshis: Long,
                         minimumDepth: Long,
                         htlcMinimumMsat: Long,
                         toSelfDelay: Int,
                         maxAcceptedHtlcs: Int,
                         fundingPubkey: PublicKey,
                         revocationBasepoint: Point,
                         paymentBasepoint: Point,
                         delayedPaymentBasepoint: Point,
                         firstPerCommitmentPoint: Point) extends ChannelMessage with HasTemporaryChannelId

case class FundingCreated(temporaryChannelId: Long,
                          txid: BinaryData,
                          outputIndex: Int,
                          signature: BinaryData) extends ChannelMessage with HasTemporaryChannelId

case class FundingSigned(temporaryChannelId: Long,
                         signature: BinaryData) extends ChannelMessage with HasTemporaryChannelId

case class FundingLocked(temporaryChannelId: Long,
                         channelId: Long,
                         nextPerCommitmentPoint: Point) extends ChannelMessage with HasTemporaryChannelId

case class Shutdown(channelId: Long,
                    scriptPubKey: BinaryData) extends ChannelMessage with HasChannelId

case class ClosingSigned(channelId: Long,
                         feeSatoshis: Long,
                         signature: BinaryData) extends ChannelMessage with HasChannelId

case class UpdateAddHtlc(channelId: Long,
                         id: Long,
                         amountMsat: Long,
                         expiry: Long,
                         paymentHash: BinaryData,
                         onionRoutingPacket: BinaryData) extends HtlcMessage with UpdateMessage with HasChannelId

case class UpdateFulfillHtlc(channelId: Long,
                             id: Long,
                             paymentPreimage: BinaryData) extends HtlcMessage with UpdateMessage with HasChannelId

case class UpdateFailHtlc(channelId: Long,
                          id: Long,
                          reason: BinaryData) extends HtlcMessage with UpdateMessage with HasChannelId

case class CommitSig(channelId: Long,
                     signature: BinaryData,
                     htlcSignatures: List[BinaryData]) extends HtlcMessage with HasChannelId

case class RevokeAndAck(channelId: Long,
                        perCommitmentSecret: Scalar,
                        nextPerCommitmentPoint: Point,
                        htlcTimeoutSignatures: List[BinaryData]) extends HtlcMessage with HasChannelId

case class UpdateFee(channelId: Long,
                     feeratePerKw: Long) extends ChannelMessage with UpdateMessage with HasChannelId

case class ChannelAnnouncement(nodeSignature1: BinaryData,
                               nodeSignature2: BinaryData,
                               bitcoinSignature1: BinaryData,
                               bitcoinSignature2: BinaryData,
                               channelId: Long,
                               nodeId1: BinaryData,
                               nodeId2: BinaryData,
                               bitcoinKey1: BinaryData,
                               bitcoinKey2: BinaryData) extends RoutingMessage

case class NodeAnnouncement(signature: BinaryData,
                            timestamp: Long,
                            nodeId: BinaryData,
                            rgbColor: (Byte, Byte, Byte),
                            alias: String,
                            features: BinaryData,
                            // TODO: check address order + support padding data (type 0)
                            addresses: List[InetSocketAddress]) extends RoutingMessage

case class ChannelUpdate(signature: BinaryData,
                         channelId: Long,
                         timestamp: Long,
                         flags: BinaryData,
                         cltvExpiryDelta: Int,
                         htlcMinimumMsat: Long,
                         feeBaseMsat: Long,
                         feeProportionalMillionths: Long) extends RoutingMessage

case class AnnouncementSignatures(channelId: Long,
                                  nodeSignature: BinaryData,
                                  bitcoinSignature: BinaryData) extends RoutingMessage with HasChannelId

case class PerHopPayload(amt_to_forward: Long,
                         outgoing_cltv_value: Int)