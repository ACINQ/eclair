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

package fr.acinq.eclair.wire.protocol

import com.google.common.net.InetAddresses
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.ChannelFlags
import fr.acinq.eclair.crypto.Hmac256
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.{UInt64, randomBytes32}
import org.scalatest.funsuite.AnyFunSuite
import scodec.DecodeResult
import scodec.bits.{BinStringSyntax, BitVector, HexStringSyntax}
import scodec.codecs.uint32

import java.net.{Inet4Address, Inet6Address, InetAddress}

/**
 * Created by t-bast on 20/06/2019.
 */

class CommonCodecsSpec extends AnyFunSuite {

  test("encode/decode with uint64 codec") {
    val expected = Map(
      UInt64(0) -> hex"00 00 00 00 00 00 00 00",
      UInt64(42) -> hex"00 00 00 00 00 00 00 2a",
      UInt64(6211610197754262546L) -> hex"56 34 12 90 78 56 34 12",
      UInt64(hex"ff ff ff ff ff ff ff ff") -> hex"ff ff ff ff ff ff ff ff"
    ).view.mapValues(_.toBitVector)

    for ((uint, ref) <- expected) {
      val encoded = uint64.encode(uint).require
      assert(ref == encoded)
      val decoded = uint64.decode(encoded).require.value
      assert(uint == decoded)
    }
  }

  test("encode/decode UInt64") {
    val refs = Seq(
      UInt64(hex"ffffffffffffffff"),
      UInt64(hex"fffffffffffffffe"),
      UInt64(hex"efffffffffffffff"),
      UInt64(hex"effffffffffffffe")
    )
    assert(refs.forall(value => uint64.decode(uint64.encode(value).require).require.value == value))
  }

  test("encode/decode with varint codec") {
    val expected = Map(
      UInt64(0L) -> hex"00",
      UInt64(42L) -> hex"2a",
      UInt64(253L) -> hex"fd 00 fd",
      UInt64(254L) -> hex"fd 00 fe",
      UInt64(255L) -> hex"fd 00 ff",
      UInt64(550L) -> hex"fd 02 26",
      UInt64(998000L) -> hex"fe 00 0f 3a 70",
      UInt64(1311768467284833366L) -> hex"ff 12 34 56 78 90 12 34 56",
      UInt64.MaxValue -> hex"ff ff ff ff ff ff ff ff ff"
    ).view.mapValues(_.toBitVector)

    for ((uint, ref) <- expected) {
      val encoded = varint.encode(uint).require
      assert(ref == encoded, ref)
      val decoded = varint.decode(encoded).require.value
      assert(uint == decoded, uint)
    }
  }

  test("decode invalid varint") {
    val testCases = Seq(
      hex"fd", // truncated
      hex"fe 01", // truncated
      hex"fe", // truncated
      hex"fe 12 34", // truncated
      hex"ff", // truncated
      hex"ff 12 34 56 78", // truncated
      hex"fd 00 00", // not minimally-encoded
      hex"fd 00 fc", // not minimally-encoded
      hex"fe 00 00 00 00", // not minimally-encoded
      hex"fe 00 00 ff ff", // not minimally-encoded
      hex"ff 00 00 00 00 00 00 00 00", // not minimally-encoded
      hex"ff 00 00 00 00 01 ff ff ff", // not minimally-encoded
      hex"ff 00 00 00 00 ff ff ff ff" // not minimally-encoded
    ).map(_.toBitVector)

    for (testCase <- testCases) {
      assert(varint.decode(testCase).isFailure, testCase.toByteVector)
    }
  }

  test("encode/decode with varintoverflow codec") {
    val expected = Map(
      0L -> hex"00",
      42L -> hex"2a",
      253L -> hex"fd 00 fd",
      254L -> hex"fd 00 fe",
      255L -> hex"fd 00 ff",
      550L -> hex"fd 02 26",
      998000L -> hex"fe 00 0f 3a 70",
      1311768467284833366L -> hex"ff 12 34 56 78 90 12 34 56",
      Long.MaxValue -> hex"ff 7f ff ff ff ff ff ff ff"
    ).view.mapValues(_.toBitVector)

    for ((long, ref) <- expected) {
      val encoded = varintoverflow.encode(long).require
      assert(ref == encoded, ref)
      val decoded = varintoverflow.decode(encoded).require.value
      assert(long == decoded, long)
    }
  }

  test("decode invalid varintoverflow") {
    val testCases = Seq(
      hex"ff 80 00 00 00 00 00 00 00",
      hex"ff ff ff ff ff ff ff ff ff"
    ).map(_.toBitVector)

    for (testCase <- testCases) {
      assert(varintoverflow.decode(testCase).isFailure, testCase.toByteVector)
    }
  }

  test("encode/decode channel flags") {
    val testCases = Map(
      bin"00000000" -> ChannelFlags(announceChannel = false),
      bin"00000001" -> ChannelFlags(announceChannel = true),
    )
    testCases.foreach { case (bin, obj) =>
      assert(channelflags.decode(bin).require == DecodeResult(obj, BitVector.empty))
      assert(channelflags.encode(obj).require == bin)
    }

    // BOLT 2: The receiving node MUST [...] ignore undefined bits in channel_flags.
    assert(channelflags.decode(bin"11111111").require == DecodeResult(ChannelFlags(announceChannel = true), BitVector.empty))
    assert(channelflags.decode(bin"11111110").require == DecodeResult(ChannelFlags(announceChannel = false), BitVector.empty))
  }

  test("encode/decode with rgb codec") {
    val color = Color(47.toByte, 255.toByte, 142.toByte)
    val bin = rgb.encode(color).require
    assert(bin == hex"2f ff 8e".toBitVector)
    val color2 = rgb.decode(bin).require.value
    assert(color == color2)
  }

  test("encode/decode all kind of IPv6 addresses with ipv6address codec") {
    {
      // IPv4 mapped
      val bin = hex"00000000000000000000ffffae8a0b08".toBitVector
      val ipv6 = Inet6Address.getByAddress(null, bin.toByteArray, null)
      val bin2 = ipv6address.encode(ipv6).require
      assert(bin == bin2)
    }

    {
      // regular IPv6 address
      val ipv6 = InetAddresses.forString("1080:0:0:0:8:800:200C:417A").asInstanceOf[Inet6Address]
      val bin = ipv6address.encode(ipv6).require
      val ipv62 = ipv6address.decode(bin).require.value
      assert(ipv6 == ipv62)
    }
  }

  test("encode/decode with nodeaddress codec") {
    {
      val ipv4addr = InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)).asInstanceOf[Inet4Address]
      val nodeaddr = IPv4(ipv4addr, 4231)
      val bin = nodeaddress.encode(nodeaddr).require
      assert(bin == hex"01 C0 A8 01 2A 10 87".toBitVector)
      val nodeaddr2 = nodeaddress.decode(bin).require.value
      assert(nodeaddr == nodeaddr2)
    }
    {
      val ipv6addr = InetAddress.getByAddress(hex"2001 0db8 0000 85a3 0000 0000 ac1f 8001".toArray).asInstanceOf[Inet6Address]
      val nodeaddr = IPv6(ipv6addr, 4231)
      val bin = nodeaddress.encode(nodeaddr).require
      assert(bin == hex"02 2001 0db8 0000 85a3 0000 0000 ac1f 8001 1087".toBitVector)
      val nodeaddr2 = nodeaddress.decode(bin).require.value
      assert(nodeaddr == nodeaddr2)
    }
    {
      val nodeaddr = Tor2("z4zif3fy7fe7bpg3", 4231)
      val bin = nodeaddress.encode(nodeaddr).require
      assert(bin == hex"03 cf3282ecb8f949f0bcdb 1087".toBitVector)
      val nodeaddr2 = nodeaddress.decode(bin).require.value
      assert(nodeaddr == nodeaddr2)
    }
    {
      val nodeaddr = Tor3("mrl2d3ilhctt2vw4qzvmz3etzjvpnc6dczliq5chrxetthgbuczuggyd", 4231)
      val bin = nodeaddress.encode(nodeaddr).require
      assert(bin == hex"04 6457a1ed0b38a73d56dc866accec93ca6af68bc316568874478dc9399cc1a0b3431b03 1087".toBitVector)
      val nodeaddr2 = nodeaddress.decode(bin).require.value
      assert(nodeaddr == nodeaddr2)
    }
    {
      val nodeaddr = DnsHostname("acinq.co", 4231)
      val bin = nodeaddress.encode(nodeaddr).require
      assert(bin === hex"05 086163696e712e636f 1087".toBitVector)
      val nodeaddr2 = nodeaddress.decode(bin).require.value
      assert(nodeaddr === nodeaddr2)
    }
  }

  test("encode/decode bytevector32") {
    val testCases = Seq(
      (hex"0000000000000000000000000000000000000000000000000000000000000000", Some(ByteVector32.Zeroes)),
      (hex"0101010101010101010101010101010101010101010101010101010101010101", Some(ByteVector32(hex"0101010101010101010101010101010101010101010101010101010101010101"))),
      (hex"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", Some(ByteVector32(hex"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))),
      // Ignore additional trailing bytes
      (hex"000000000000000000000000000000000000000000000000000000000000000000", Some(ByteVector32.Zeroes)),
      (hex"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff00", Some(ByteVector32(hex"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))),
      // Not enough bytes
      (hex"00000000000000000000000000000000000000000000000000000000000000", None),
      (hex"", None)
    )

    for ((encoded, expected_opt) <- testCases) {
      expected_opt match {
        case Some(expected) =>
          val decoded = bytes32.decode(encoded.bits).require.value
          assert(decoded == expected)
          assert(expected.bytes == bytes32.encode(decoded).require.bytes)
        case None =>
          assert(bytes32.decode(encoded.bits).isFailure)
      }
    }
  }

  test("encode/decode bytevector64") {
    val testCases = Seq(
      (hex"00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000", Some(ByteVector64.Zeroes)),
      (hex"01010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101", Some(ByteVector64(hex"01010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101"))),
      (hex"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", Some(ByteVector64(hex"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))),
      // Ignore additional trailing bytes
      (hex"0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000", Some(ByteVector64.Zeroes)),
      (hex"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff00", Some(ByteVector64(hex"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))),
      // Not enough bytes
      (hex"000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000", None),
      (hex"00000000000000000000000000000000000000000000000000000000000000", None),
      (hex"", None)
    )

    for ((encoded, expected_opt) <- testCases) {
      expected_opt match {
        case Some(expected) =>
          val decoded = bytes64.decode(encoded.bits).require.value
          assert(decoded == expected)
          assert(expected.bytes == bytes64.encode(decoded).require.bytes)
        case None =>
          assert(bytes64.decode(encoded.bits).isFailure)
      }
    }
  }

  test("encode/decode with private key codec") {
    val value = PrivateKey(randomBytes32())
    val wire = privateKey.encode(value).require
    assert(wire.length == 256)
    val value1 = privateKey.decode(wire).require.value
    assert(value1 == value)
  }

  test("encode/decode with public key codec") {
    val value = PrivateKey(randomBytes32()).publicKey
    val wire = CommonCodecs.publicKey.encode(value).require
    assert(wire.length == 33 * 8)
    val value1 = CommonCodecs.publicKey.decode(wire).require.value
    assert(value1 == value)
  }

  test("encode/decode with zeropaddedstring codec") {
    val c = zeropaddedstring(32)

    {
      val alias = "IRATEMONK"
      val bin = c.encode(alias).require
      assert(bin == BitVector(alias.getBytes("UTF-8") ++ Array.fill[Byte](32 - alias.length)(0)))
      val alias2 = c.decode(bin).require.value
      assert(alias == alias2)
    }

    {
      val alias = "this-alias-is-exactly-32-B-long."
      val bin = c.encode(alias).require
      assert(bin == BitVector(alias.getBytes("UTF-8") ++ Array.fill[Byte](32 - alias.length)(0)))
      val alias2 = c.decode(bin).require.value
      assert(alias == alias2)
    }

    {
      val alias = "this-alias-is-far-too-long-because-we-are-limited-to-32-bytes"
      assert(c.encode(alias).isFailure)
    }
  }

  test("encode/decode with prependmac codec") {
    val mac = Hmac256(ByteVector32.Zeroes)
    val testCases = Seq(
      (uint64, UInt64(561), hex"d5b500b8843e19a34d8ab54740db76a7ea597e4ff2ada3827420f87c7e60b7c6 0000000000000231"),
      (varint, UInt64(65535), hex"71e17e5b97deb6916f7ad97a53650769d4e4f0b1e580ff35ca332200d61e765c fdffff")
    )

    for ((codec, expected, bin) <- testCases) {
      val macCodec = prependmac(codec, mac)
      val decoded = macCodec.decode(bin.toBitVector).require.value
      assert(decoded == expected)

      val encoded = macCodec.encode(expected).require.toByteVector
      assert(encoded == bin)
    }
  }

  test("backward compatibility on feerate codec") {
    val value = 123456
    val feerate = FeeratePerKw(value sat)
    assert(feeratePerKw.decode(uint32.encode(value).require).require == DecodeResult(feerate, BitVector.empty))
  }

}
