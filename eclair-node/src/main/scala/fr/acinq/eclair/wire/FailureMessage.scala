package fr.acinq.eclair.wire

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.wire.LightningMessageCodecs.{binarydata, channelUpdateCodec}
import scodec.Codec
import scodec.codecs._


/**
  * see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md
  * Created by fabrice on 14/03/17.
  */

// @formatter:off
sealed trait FailureMessage
sealed trait BadOnion extends FailureMessage { def onionHash: BinaryData }
sealed trait Perm extends FailureMessage
sealed trait Node extends FailureMessage
sealed trait Update extends FailureMessage { def update: ChannelUpdate }

case object InvalidRealm extends Perm
case object TemporaryNodeFailure extends Node
case object PermanentNodeFailure extends Perm
case object RequiredNodeFeatureMissing extends Perm with Node
case class InvalidOnionVersion(onionHash: BinaryData) extends BadOnion with Perm
case class InvalidOnionHmac(onionHash: BinaryData) extends BadOnion with Perm
case class InvalidOnionKey(onionHash: BinaryData) extends BadOnion with Perm
case object TemporaryChannelFailure extends FailureMessage
case object PermanentChannelFailure extends Perm
case object UnknownNextPeer extends Perm
case class AmountBelowMinimum(amountMsat: Long, update: ChannelUpdate) extends Update
case class InsufficientFee(amountMsat: Long, update: ChannelUpdate) extends Update
case class IncorrectCltvExpiry(expiry: Long, update: ChannelUpdate) extends Update
case class ExpiryTooSoon(update: ChannelUpdate) extends Update
case object UnknownPaymentHash extends Perm
case object IncorrectPaymentAmount extends Perm
case object FinalExpiryTooSoon extends Perm
// @formatter:on

object FailureMessageCodecs {
  val BADONION = 0x8000
  val PERM = 0x4000
  val NODE = 0x2000
  val UPDATE = 0x1000

  val sha256Codec: Codec[BinaryData] = ("sha256Codec" | binarydata(32))

  val failureMessageCodec = discriminated[FailureMessage].by(uint16)
    .typecase(PERM | 1, provide(InvalidRealm))
    .typecase(NODE | 2, provide(TemporaryNodeFailure))
    .typecase(PERM | 2, provide(PermanentNodeFailure))
    .typecase(PERM | NODE | 3, provide(RequiredNodeFeatureMissing))
    .typecase(BADONION | PERM | 4, sha256Codec.as[InvalidOnionVersion])
    .typecase(BADONION | PERM | 5, sha256Codec.as[InvalidOnionHmac])
    .typecase(BADONION | PERM | 6, sha256Codec.as[InvalidOnionKey])
    .typecase(7, provide(TemporaryChannelFailure))
    .typecase(PERM | 8, provide(PermanentChannelFailure))
    .typecase(PERM | 10, provide(UnknownNextPeer))
    .typecase(UPDATE | 11, (("amountMsat" | uint32) :: ("channelUpdate" | channelUpdateCodec)).as[AmountBelowMinimum])
    .typecase(UPDATE | 12, (("amountMsat" | uint32) :: ("channelUpdate" | channelUpdateCodec)).as[InsufficientFee])
    .typecase(UPDATE | 13, (("expiry" | uint32) :: ("channelUpdate" | channelUpdateCodec)).as[IncorrectCltvExpiry])
    .typecase(UPDATE | 14, (("channelUpdate" | channelUpdateCodec)).as[ExpiryTooSoon])
    .typecase(PERM | 15, provide(UnknownPaymentHash))
    .typecase(PERM | 16, provide(IncorrectPaymentAmount))
    .typecase(PERM | 17, provide(FinalExpiryTooSoon))
}
