package fr.acinq.eclair.wire

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.wire.LightningMessageCodecs.{binarydata, channelUpdateCodec, uint64}
import scodec.Codec
import scodec.codecs._

/**
  * see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md
  * Created by fabrice on 14/03/17.
  */

// @formatter:off
sealed trait FailureMessage { def message: String }
sealed trait BadOnion extends FailureMessage { def onionHash: BinaryData }
sealed trait Perm extends FailureMessage
sealed trait Node extends FailureMessage
sealed trait Update extends FailureMessage { def update: ChannelUpdate }

case object InvalidRealm extends Perm { def message = "realm was not understood by the processing node" }
case object TemporaryNodeFailure extends Node { def message = "general temporary failure of the processing node" }
case object PermanentNodeFailure extends Perm with Node { def message = "general permanent failure of the processing node" }
case object RequiredNodeFeatureMissing extends Perm with Node { def message = "processing node requires features that are missing from this onion" }
case class InvalidOnionVersion(onionHash: BinaryData) extends BadOnion with Perm { def message = "onion version was not understood by the processing node" }
case class InvalidOnionHmac(onionHash: BinaryData) extends BadOnion with Perm { def message = "onion HMAC was incorrect when it reached the processing node" }
case class InvalidOnionKey(onionHash: BinaryData) extends BadOnion with Perm { def message = "ephemeral key was unparsable by the processing node" }
case class TemporaryChannelFailure(update: ChannelUpdate) extends Update { def message = s"channel ${update.shortChannelId} is currently unavailable" }
case object PermanentChannelFailure extends Perm { def message = "channel is permanently unavailable" }
case object RequiredChannelFeatureMissing extends Perm { def message = "channel requires features not present in the onion" }
case object UnknownNextPeer extends Perm { def message = "processing node does not know the next peer in the route" }
case class AmountBelowMinimum(amountMsat: Long, update: ChannelUpdate) extends Update { def message = s"payment amount was below the minimum required by the channel" }
case class FeeInsufficient(amountMsat: Long, update: ChannelUpdate) extends Update { def message = s"payment fee was below the minimum required by the channel" }
case class ChannelDisabled(flags: BinaryData, update: ChannelUpdate) extends Update { def message = "channel is currently disabled" }
case class IncorrectCltvExpiry(expiry: Long, update: ChannelUpdate) extends Update { def message = "payment expiry doesn't match the value in the onion" }
case object UnknownPaymentHash extends Perm { def message = "payment hash is unknown to the final node" }
case object IncorrectPaymentAmount extends Perm { def message = "payment amount is incorrect" }
case class ExpiryTooSoon(update: ChannelUpdate) extends Update { def message = "payment expiry is too close to the current block height for safe handling by the relaying node" }
case object FinalExpiryTooSoon extends FailureMessage { def message = "payment expiry is too close to the current block height for safe handling by the final node" }
case class FinalIncorrectCltvExpiry(expiry: Long) extends FailureMessage { def message = "payment expiry doesn't match the value in the onion" }
case class FinalIncorrectHtlcAmount(amountMsat: Long) extends FailureMessage { def message = "payment amount is incorrect in the final htlc" }
case object ExpiryTooFar extends FailureMessage { def message = "payment expiry is too far in the future" }
// @formatter:on

object FailureMessageCodecs {
  val BADONION = 0x8000
  val PERM = 0x4000
  val NODE = 0x2000
  val UPDATE = 0x1000

  val sha256Codec: Codec[BinaryData] = ("sha256Codec" | binarydata(32))

  val channelUpdateWithLengthCodec = variableSizeBytes(uint16, channelUpdateCodec)

  val failureMessageCodec = discriminated[FailureMessage].by(uint16)
    .typecase(PERM | 1, provide(InvalidRealm))
    .typecase(NODE | 2, provide(TemporaryNodeFailure))
    .typecase(PERM | 2, provide(PermanentNodeFailure))
    .typecase(PERM | NODE | 3, provide(RequiredNodeFeatureMissing))
    .typecase(BADONION | PERM | 4, sha256Codec.as[InvalidOnionVersion])
    .typecase(BADONION | PERM | 5, sha256Codec.as[InvalidOnionHmac])
    .typecase(BADONION | PERM | 6, sha256Codec.as[InvalidOnionKey])
    .typecase(UPDATE | 7, (("channelUpdate" | channelUpdateWithLengthCodec)).as[TemporaryChannelFailure])
    .typecase(PERM | 8, provide(PermanentChannelFailure))
    .typecase(PERM | 9, provide(RequiredChannelFeatureMissing))
    .typecase(PERM | 10, provide(UnknownNextPeer))
    .typecase(UPDATE | 11, (("amountMsat" | uint64) :: ("channelUpdate" | channelUpdateWithLengthCodec)).as[AmountBelowMinimum])
    .typecase(UPDATE | 12, (("amountMsat" | uint64) :: ("channelUpdate" | channelUpdateWithLengthCodec)).as[FeeInsufficient])
    .typecase(UPDATE | 13, (("expiry" | uint32) :: ("channelUpdate" | channelUpdateWithLengthCodec)).as[IncorrectCltvExpiry])
    .typecase(UPDATE | 14, (("channelUpdate" | channelUpdateWithLengthCodec)).as[ExpiryTooSoon])
    .typecase(UPDATE | 20, (("flags" | binarydata(2)) :: ("channelUpdate" | channelUpdateWithLengthCodec)).as[ChannelDisabled])
    .typecase(PERM | 15, provide(UnknownPaymentHash))
    .typecase(PERM | 16, provide(IncorrectPaymentAmount))
    .typecase(17, provide(FinalExpiryTooSoon))
    .typecase(18, (("expiry" | uint32)).as[FinalIncorrectCltvExpiry])
    .typecase(19, (("amountMsat" | uint32)).as[FinalIncorrectHtlcAmount])
    .typecase(21, provide(ExpiryTooFar))
}
