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

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.channel._
import fr.acinq.eclair.wire.protocol.{FailureMessageCodecs, FailureMessageTlv, GenericTlv, TemporaryNodeFailure, TlvStream}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.{ByteVector, HexStringSyntax}

/**
 * Created by PM on 31/05/2016.
 */

class CommandCodecsSpec extends AnyFunSuite {

  test("encode/decode all settlement commands") {
    val testCases: Map[HtlcSettlementCommand, ByteVector] = Map(
      CMD_FULFILL_HTLC(1573, ByteVector32(hex"e64e7c07667366e517886af99a25a5dd547014c95ba392ea4623fbf47fe00927")) -> hex"0000 0000000000000625 e64e7c07667366e517886af99a25a5dd547014c95ba392ea4623fbf47fe00927",
      CMD_FAIL_HTLC(42456, Left(hex"d21a88a158067efecbee41da24e1d7407747f135e585e7417843729d3bff5c160817d14ce569761d93749a23d227edc0ade99c1a8d59541e45e1f623af2602d568a9a3c3bca71f1b4860ae0b599ba016c58224eab7721ed930eb2bdfd83ff940cc9e8106b0bd6b2027821f8d102b8c680664d90ce9e69d8bb96453a7b495710b83c13e4b3085bb0156b7091ed927305c44")) -> hex"0003 000000000000a5d8 00 0091 d21a88a158067efecbee41da24e1d7407747f135e585e7417843729d3bff5c160817d14ce569761d93749a23d227edc0ade99c1a8d59541e45e1f623af2602d568a9a3c3bca71f1b4860ae0b599ba016c58224eab7721ed930eb2bdfd83ff940cc9e8106b0bd6b2027821f8d102b8c680664d90ce9e69d8bb96453a7b495710b83c13e4b3085bb0156b7091ed927305c44",
      CMD_FAIL_HTLC(253, Right(TemporaryNodeFailure())) -> hex"0003 00000000000000fd ff 0002 2002",
      CMD_FAIL_HTLC(253, Right(TemporaryNodeFailure(TlvStream(Set.empty[FailureMessageTlv], Set(GenericTlv(UInt64(17), hex"deadbeef")))))) -> hex"0003 00000000000000fd ff 0008 2002 1104deadbeef",
      CMD_FAIL_MALFORMED_HTLC(7984, ByteVector32(hex"17cc093e177c7a7fcaa9e96ab407146c8886546a5690f945c98ac20c4ab3b4f3"), FailureMessageCodecs.BADONION) -> hex"0002 0000000000001f30 17cc093e177c7a7fcaa9e96ab407146c8886546a5690f945c98ac20c4ab3b4f38000",
    )

    testCases.foreach { case (command, bin) =>
      val encoded = CommandCodecs.cmdCodec.encode(command).require.bytes
      assert(encoded == bin)
      val decoded = CommandCodecs.cmdCodec.decode(bin.bits).require.value
      assert(command == decoded)
    }
  }

  test("backward compatibility") {
    val data32 = ByteVector32(hex"e4927c04913251b44d0a3a8e57ded746fee80ff3b424e70dad2a1428eeba86cb")
    val data123 = hex"fea75bb8cf45349eb544d8da832af5af30eefa671ec27cf2e4867bacada2dbe00a6ce5141164aa153ac8b4b25c75c3af15c4b5cb6a293607751a079bc546da17f654b76a74bc57b6b21ed73d2d3909f3682f01b85418a0f0ecddb759e9481d4563a572ac1ddcb77c64ae167d8dfbd889703cb5c33b4b9636bad472"
    val testCases = Map(
      hex"0000 000000000000002ae4927c04913251b44d0a3a8e57ded746fee80ff3b424e70dad2a1428eeba86cb" -> CMD_FULFILL_HTLC(42, data32, commit = false, None),
      hex"0001 000000000000002a003dff53addc67a29a4f5aa26c6d41957ad798777d338f613e7972433dd656d16df00536728a08b2550a9d645a592e3ae1d78ae25ae5b5149b03ba8d03cde2a36d0bfb2a5bb53a5e2bdb590f6b9e969c84f9b41780dc2a0c5078766edbacf4a40ea2b1d2b9560eee5bbe32570b3ec6fdec44b81e5ae19da5cb1b5d6a3900" -> CMD_FAIL_HTLC(42, Left(data123), None, commit = false, None),
      hex"0001 000000000000002a900100" -> CMD_FAIL_HTLC(42, Right(TemporaryNodeFailure())),
      hex"0002 000000000000002ae4927c04913251b44d0a3a8e57ded746fee80ff3b424e70dad2a1428eeba86cb01c8" -> CMD_FAIL_MALFORMED_HTLC(42, data32, 456, commit = false, None),
    )

    testCases.foreach { case (bin, command) =>
      val decoded = CommandCodecs.cmdCodec.decode(bin.bits).require
      assert(decoded.value == command)
    }
  }

}
