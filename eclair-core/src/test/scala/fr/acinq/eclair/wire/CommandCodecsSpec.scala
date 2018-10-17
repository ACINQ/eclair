/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.wire

import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FAIL_MALFORMED_HTLC, CMD_FULFILL_HTLC, Command}
import fr.acinq.eclair.randomBytes
import org.scalatest.FunSuite

/**
  * Created by PM on 31/05/2016.
  */

class CommandCodecsSpec extends FunSuite {

  test("encode/decode all channel messages") {
    val msgs: List[Command] =
      CMD_FULFILL_HTLC(1573L, randomBytes(32)) ::
    CMD_FAIL_HTLC(42456L, Left(randomBytes(145))) ::
    CMD_FAIL_HTLC(253, Right(TemporaryNodeFailure)) ::
    CMD_FAIL_MALFORMED_HTLC(7984, randomBytes(32), FailureMessageCodecs.BADONION) :: Nil

    msgs.foreach {
      case msg => {
        val encoded = CommandCodecs.cmdCodec.encode(msg).require
        val decoded = CommandCodecs.cmdCodec.decode(encoded).require
        assert(msg === decoded.value)
      }
    }
  }
}
