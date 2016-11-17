package fr.acinq.eclair.wire

import java.io.{InputStream, OutputStream}

import fr.acinq.bitcoin.Protocol._
import fr.acinq.bitcoin.{BinaryData, BtcMessage}

/**
  * Created by PM on 15/11/2016.
  */

object OpenChannel extends BtcMessage[OpenChannel] {
  override def read(input: InputStream, protocolVersion: Long): OpenChannel = {
    val temporaryChannelId = uint64(input)
    val fundingSatoshis = uint64(input)
    val pushMsat = uint64(input)
    val dustLimitSatoshis = uint64(input)
    val maxHtlcValueInFlightMsat = uint64(input)
    val channelReserveSatoshis = uint64(input)
    val htlcMinimumMsat = uint32(input)
    val maxNumHtlcs = uint32(input)
    val feeratePerKb = uint32(input)
    val toSelfDelay = uint16(input)
    val fundingPubkey = bytes(input, 33)
    val hakdBasePoint = bytes(input, 33)
    val refundBasePoint = bytes(input, 33)
    OpenChannel(temporaryChannelId, fundingSatoshis, pushMsat, dustLimitSatoshis, maxHtlcValueInFlightMsat, channelReserveSatoshis,
      htlcMinimumMsat, maxNumHtlcs, feeratePerKb, toSelfDelay.toInt, fundingPubkey, hakdBasePoint, refundBasePoint)
  }

  override def write(input: OpenChannel, out: OutputStream, protocolVersion: Long) = {
    writeUInt64(input.temporaryChannelId, out)
    writeUInt64(input.fundingSatoshis, out)
    writeUInt64(input.pushMsat, out)
    writeUInt64(input.dustLimitSatoshis, out)
    writeUInt64(input.maxHtlcValueInFlightMsat, out)
    writeUInt64(input.channelReserveSatoshis, out)
    writeUInt32(input.htlcMinimumMsat, out)
    writeUInt32(input.maxNumHtlcs, out)
    writeUInt32(input.feeratePerKb, out)
    writeUInt16(input.toSelfDelay, out)
    out.write(input.fundingPubkey)
    out.write(input.hakdBasePoint)
    out.write(input.refundBasePoint)
  }
}

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