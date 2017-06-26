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
sealed trait HasTemporaryChannelId extends LightningMessage { def temporaryChannelId: BinaryData } // <- not in the spec
sealed trait HasChannelId extends LightningMessage { def channelId: BinaryData } // <- not in the spec
sealed trait UpdateMessage extends HtlcMessage // <- not in the spec
// @formatter:on

case class Init(globalFeatures: BinaryData,
                localFeatures: BinaryData) extends SetupMessage

case class Error(channelId: BinaryData,
                 data: BinaryData) extends SetupMessage with HasChannelId

case class Ping(pongLength: Int, data: BinaryData) extends SetupMessage

case class Pong(data: BinaryData) extends SetupMessage

case class ChannelReestablish(
                             channelId: BinaryData,
                             nextLocalCommitmentNumber: Long,
                             nextRemoteRevocationNumber: Long) extends ChannelMessage with HasChannelId

case class OpenChannel(chainHash: BinaryData,
                       temporaryChannelId: BinaryData,
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

case class AcceptChannel(temporaryChannelId: BinaryData,
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
                         expiry: Long,
                         paymentHash: BinaryData,
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
                                  shortChannelId: Long,
                                  nodeSignature: BinaryData,
                                  bitcoinSignature: BinaryData) extends RoutingMessage with HasChannelId

case class ChannelAnnouncement(nodeSignature1: BinaryData,
                               nodeSignature2: BinaryData,
                               bitcoinSignature1: BinaryData,
                               bitcoinSignature2: BinaryData,
                               shortChannelId: Long,
                               nodeId1: BinaryData,
                               nodeId2: BinaryData,
                               bitcoinKey1: BinaryData,
                               bitcoinKey2: BinaryData,
                               features: BinaryData) extends RoutingMessage

case class NodeAnnouncement(signature: BinaryData,
                            timestamp: Long,
                            nodeId: BinaryData,
                            rgbColor: (Byte, Byte, Byte),
                            alias: String,
                            features: BinaryData,
                            // TODO: check address order + support padding data (type 0)
                            addresses: List[InetSocketAddress]) extends RoutingMessage

case class ChannelUpdate(signature: BinaryData,
                         shortChannelId: Long,
                         timestamp: Long,
                         flags: BinaryData,
                         cltvExpiryDelta: Int,
                         htlcMinimumMsat: Long,
                         feeBaseMsat: Long,
                         feeProportionalMillionths: Long) extends RoutingMessage

case class PerHopPayload(channel_id: Long,
                         amtToForward: Long,
                         outgoingCltvValue: Int)