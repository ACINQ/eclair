package fr.acinq.eclair.wire

import fr.acinq.bitcoin.BinaryData

/**
  * Created by PM on 15/11/2016.
  */

// @formatter:off
sealed trait LightningMessage
sealed trait SetupMessage extends LightningMessage
sealed trait ChannelMessage extends LightningMessage
sealed trait HtlcMessage extends LightningMessage
sealed trait RoutingMessage extends LightningMessage
// @formatter:on

case class Init(globalFeatures: BinaryData,
                localFeatures: BinaryData) extends SetupMessage

case class Error(channelId: Long,
                 data: BinaryData) extends SetupMessage

case class OpenChannel(temporaryChannelId: Long,
                       fundingSatoshis: Long,
                       pushMsat: Long,
                       dustLimitSatoshis: Long,
                       maxHtlcValueInFlightMsat: Long,
                       channelReserveSatoshis: Long,
                       htlcMinimumMsat: Long,
                       maxNumHtlcs: Long,
                       feeratePerKb: Long,
                       toSelfDelay: Int,
                       fundingPubkey: BinaryData,
                       revocationBasepoint: BinaryData,
                       paymentBasepoint: BinaryData,
                       delayedPaymentBasepoint: BinaryData,
                       firstPerCommitmentPoint: BinaryData) extends ChannelMessage

case class AcceptChannel(temporaryChannelId: Long,
                         dustLimitSatoshis: Long,
                         maxHtlcValueInFlightMsat: Long,
                         channelReserveSatoshis: Long,
                         minimumDepth: Long,
                         htlcMinimumMsat: Long,
                         maxNumHtlcs: Long,
                         toSelfDelay: Int,
                         fundingPubkey: BinaryData,
                         revocationBasepoint: BinaryData,
                         paymentBasepoint: BinaryData,
                         delayedPaymentBasepoint: BinaryData,
                         firstPerCommitmentPoint: BinaryData) extends ChannelMessage

case class FundingCreated(temporaryChannelId: Long,
                          txid: BinaryData,
                          outputIndex: Int,
                          signature: BinaryData) extends ChannelMessage

case class FundingSigned(temporaryChannelId: Long,
                         signature: BinaryData) extends ChannelMessage

case class FundingLocked(temporaryChannelId: Long,
                         channelId: Long,
                         nextPerCommitmentPoint: BinaryData) extends ChannelMessage

case class UpdateFee(channelId: Long,
                     feeratePerKb: Long) extends ChannelMessage

case class Shutdown(channelId: Long,
                    scriptPubKey: BinaryData) extends ChannelMessage

case class ClosingSigned(channelId: Long,
                         feeSatoshis: Long,
                         signature: BinaryData) extends ChannelMessage

case class AddHtlc(channelId: Long,
                   id: Long,
                   amountMsat: Long,
                   expiry: Long,
                   paymentHash: BinaryData,
                   onionRoutingPacket: BinaryData) extends HtlcMessage

case class UpdateFulfillHtlc(channelId: Long,
                             id: Long,
                             paymentPreimage: BinaryData) extends HtlcMessage

case class UpdateFailHtlc(channelId: Long,
                          id: Long,
                          reason: BinaryData) extends HtlcMessage

case class CommitSig(channelId: Long,
                     signature: BinaryData,
                     htlcSignatures: List[BinaryData]) extends HtlcMessage

case class RevokeAndAck(channelId: Long,
                        perCommitmentSecret: BinaryData,
                        nextPerCommitmentPoint: BinaryData,
                        padding: BinaryData,
                        htlcTimeoutSignatures: List[BinaryData]) extends HtlcMessage

