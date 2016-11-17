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
                       hakdBasePoint: BinaryData,
                       refundBasePoint: BinaryData) extends ChannelMessage

case class AcceptChannel(temporaryChannelId: Long,
                         dustLimitSatoshis: Long,
                         maxHtlcValueInFlightMsat: Long,
                         channelReserveSatoshis: Long,
                         minimumDepth: Long,
                         htlcMinimumMsat: Long,
                         maxNumHtlcs: Long,
                         firstCommitmentKeyOffset: BinaryData,
                         toSelfDelay: Int,
                         fundingPubkey: BinaryData,
                         hakdBasePoint: BinaryData,
                         refundBasePoint: BinaryData) extends ChannelMessage

case class FundingCreated(temporaryChannelId: Long,
                          txid: BinaryData,
                          outputIndex: Int,
                          signature: BinaryData) extends ChannelMessage

case class FundingSigned(temporaryChannelId: Long,
                         signature: BinaryData) extends ChannelMessage

case class FundingLocked(temporaryChannelId: Long,
                         channelId: Long,
                         nextKeyOffset: BinaryData,
                         nextRevocationHalfKey: BinaryData) extends ChannelMessage

case class UpdateFee(channelId: Long,
                     feeratePerKb: Long) extends ChannelMessage

case class Shutdown(channelId: Long,
                    len: Long,
                    scriptPubKey: BinaryData) extends ChannelMessage

case class CloseSignature(channelId: Long,
                          feeSatoshis: Long,
                          signature: BinaryData) extends ChannelMessage