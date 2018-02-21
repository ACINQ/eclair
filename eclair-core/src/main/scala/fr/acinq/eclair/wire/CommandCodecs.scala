package fr.acinq.eclair.wire

import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FAIL_MALFORMED_HTLC, CMD_FULFILL_HTLC, Command}
import fr.acinq.eclair.wire.FailureMessageCodecs.failureMessageCodec
import fr.acinq.eclair.wire.LightningMessageCodecs._
import scodec.Codec
import scodec.codecs._

object CommandCodecs {

  val cmdFulfillCodec: Codec[CMD_FULFILL_HTLC] =
    (("id" | int64) ::
      ("r" | binarydata(32)) ::
      ("commit" | provide(false))).as[CMD_FULFILL_HTLC]

  val cmdFailCodec: Codec[CMD_FAIL_HTLC] =
    (("id" | int64) ::
      ("reason" | either(bool, varsizebinarydata, failureMessageCodec)) ::
      ("commit" | provide(false))).as[CMD_FAIL_HTLC]

  val cmdFailMalformedCodec: Codec[CMD_FAIL_MALFORMED_HTLC] =
    (("id" | int64) ::
      ("onionHash" | binarydata(32)) ::
      ("failureCode" | uint16) ::
      ("commit" | provide(false))).as[CMD_FAIL_MALFORMED_HTLC]

  val cmdCodec: Codec[Command] = discriminated[Command].by(uint16)
    .typecase(0, cmdFulfillCodec)
    .typecase(1, cmdFailCodec)
    .typecase(2, cmdFailMalformedCodec)

}
