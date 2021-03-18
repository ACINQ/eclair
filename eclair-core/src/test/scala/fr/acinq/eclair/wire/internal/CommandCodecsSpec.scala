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

package fr.acinq.eclair.wire.internal

import fr.acinq.eclair.channel._
import fr.acinq.eclair.wire.CommonCodecs.{bytes32, varsizebinarydata}
import fr.acinq.eclair.wire.FailureMessageCodecs.failureMessageCodec
import fr.acinq.eclair.wire.{FailureMessageCodecs, TemporaryNodeFailure}
import fr.acinq.eclair.{randomBytes, randomBytes32}
import org.scalatest.funsuite.AnyFunSuite
import scodec.DecodeResult
import scodec.bits.BitVector
import scodec.codecs._
import shapeless.HNil

/**
 * Created by PM on 31/05/2016.
 */

class CommandCodecsSpec extends AnyFunSuite {

  test("encode/decode all channel messages") {
    val msgs: List[HtlcSettlementCommand] =
      CMD_FULFILL_HTLC(1573L, randomBytes32) ::
        CMD_FAIL_HTLC(42456L, Left(randomBytes(145))) ::
        CMD_FAIL_HTLC(253, Right(TemporaryNodeFailure)) ::
        CMD_FAIL_MALFORMED_HTLC(7984, randomBytes32, FailureMessageCodecs.BADONION) :: Nil

    msgs.foreach {
      msg =>
        val encoded = CommandCodecs.cmdCodec.encode(msg).require
        val decoded = CommandCodecs.cmdCodec.decode(encoded).require
        assert(msg === decoded.value)
    }
  }

  test("backward compatibility") {

    val data32 = randomBytes32
    val data123 = randomBytes(123)

      val legacyCmdFulfillCodec =
        (("id" | int64) ::
          ("r" | bytes32) ::
          ("commit" | provide(false)))
      assert(CommandCodecs.cmdFulfillCodec.decode(legacyCmdFulfillCodec.encode(42 :: data32 :: true :: HNil).require).require ===
        DecodeResult(CMD_FULFILL_HTLC(42, data32, commit = false, None), BitVector.empty))

    val legacyCmdFailCodec =
      (("id" | int64) ::
        ("reason" | either(bool, varsizebinarydata, failureMessageCodec)) ::
        ("commit" | provide(false)))
    assert(CommandCodecs.cmdFailCodec.decode(legacyCmdFailCodec.encode(42 :: Left(data123) :: true :: HNil).require).require ===
      DecodeResult(CMD_FAIL_HTLC(42, Left(data123), commit = false, None), BitVector.empty))

    val legacyCmdFailMalformedCodec =
      (("id" | int64) ::
        ("onionHash" | bytes32) ::
        ("failureCode" | uint16) ::
        ("commit" | provide(false)))
    assert(CommandCodecs.cmdFailMalformedCodec.decode(legacyCmdFailMalformedCodec.encode(42 :: data32 :: 456 :: true :: HNil).require).require ===
      DecodeResult(CMD_FAIL_MALFORMED_HTLC(42, data32, 456, commit = false, None), BitVector.empty))
  }
}
