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

package fr.acinq.eclair.wire

import java.net.{Inet4Address, Inet6Address, InetAddress}

import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.{ShortChannelId, UInt64}
import org.apache.commons.codec.binary.Base32
import scodec.{Attempt, Codec, DecodeResult, Err}
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._

import scala.util.Try

/**
  * Created by t-bast on 20/06/2019.
  */

object CommonCodecs {

  // this codec can be safely used for values < 2^63 and will fail otherwise
  // (for something smarter see https://github.com/yzernik/bitcoin-scodec/blob/master/src/main/scala/io/github/yzernik/bitcoinscodec/structures/UInt64.scala)
  val uint64: Codec[Long] = int64.narrow(l => if (l >= 0) Attempt.Successful(l) else Attempt.failure(Err(s"overflow for value $l")), l => l)

  val uint64L: Codec[Long] = int64L.narrow(l => if (l >= 0) Attempt.Successful(l) else Attempt.failure(Err(s"overflow for value $l")), l => l)

  val uint64ex: Codec[UInt64] = bytes(8).xmap(b => UInt64(b), a => a.toByteVector.padLeft(8))

  /**
    * We impose a minimal encoding on varint values to ensure that signed hashes can be reproduced easily.
    * If a value could be encoded with less bytes, it's considered invalid and results in a failed decoding attempt.
    *
    * @param min     the minimal value that should be encoded.
    * @param attempt the decoding attempt.
    */
  def verifyMinimalEncoding(min: Long, attempt: Attempt[DecodeResult[Long]]): Attempt[DecodeResult[Long]] = {
    attempt match {
      case Attempt.Successful(res) if res.value < min => Attempt.Failure(scodec.Err("varint was not minimally encoded"))
      case Attempt.Successful(res) => Attempt.Successful(res)
      case Attempt.Failure(err) => Attempt.Failure(err)
    }
  }

  // Bitcoin-style varint codec (CompactSize)
  val varInt = Codec[Long](
    (n: Long) =>
      n match {
        case i if i < 0xfd =>
          uint8L.encode(i.toInt)
        case i if i < 0xffff =>
          for {
            a <- uint8L.encode(0xfd)
            b <- uint16L.encode(i.toInt)
          } yield a ++ b
        case i if i < 0xffffffffL =>
          for {
            a <- uint8L.encode(0xfe)
            b <- uint32L.encode(i)
          } yield a ++ b
        case i =>
          for {
            a <- uint8L.encode(0xff)
            b <- uint64L.encode(i)
          } yield a ++ b
      },
    (buf: BitVector) => {
      uint8L.decode(buf) match {
        case Attempt.Successful(b) =>
          b.value match {
            case 0xff => verifyMinimalEncoding(0x100000000L, uint64L.decode(b.remainder))
            case 0xfe => verifyMinimalEncoding(0x10000L, uint32L.decode(b.remainder))
            case 0xfd => verifyMinimalEncoding(0xfdL, uint16L.decode(b.remainder).map(b => b.map(_.toLong)))
            case _ => Attempt.Successful(DecodeResult(b.value.toLong, b.remainder))
          }
        case Attempt.Failure(err) => Attempt.Failure(err)
      }
    })

  val bytes32: Codec[ByteVector32] = limitedSizeBytes(32, bytesStrict(32).xmap(d => ByteVector32(d), d => d.bytes))

  val bytes64: Codec[ByteVector64] = limitedSizeBytes(64, bytesStrict(64).xmap(d => ByteVector64(d), d => d.bytes))

  val sha256: Codec[ByteVector32] = bytes32

  val varsizebinarydata: Codec[ByteVector] = variableSizeBytes(uint16, bytes)

  val listofsignatures: Codec[List[ByteVector64]] = listOfN(uint16, bytes64)

  val ipv4address: Codec[Inet4Address] = bytes(4).xmap(b => InetAddress.getByAddress(b.toArray).asInstanceOf[Inet4Address], a => ByteVector(a.getAddress))

  val ipv6address: Codec[Inet6Address] = bytes(16).exmap(b => Attempt.fromTry(Try(Inet6Address.getByAddress(null, b.toArray, null))), a => Attempt.fromTry(Try(ByteVector(a.getAddress))))

  def base32(size: Int): Codec[String] = bytes(size).xmap(b => new Base32().encodeAsString(b.toArray).toLowerCase, a => ByteVector(new Base32().decode(a.toUpperCase())))

  val nodeaddress: Codec[NodeAddress] =
    discriminated[NodeAddress].by(uint8)
      .typecase(1, (ipv4address :: uint16).as[IPv4])
      .typecase(2, (ipv6address :: uint16).as[IPv6])
      .typecase(3, (base32(10) :: uint16).as[Tor2])
      .typecase(4, (base32(35) :: uint16).as[Tor3])

  // this one is a bit different from most other codecs: the first 'len' element is *not* the number of items
  // in the list but rather the  number of bytes of the encoded list. The rationale is once we've read this
  // number of bytes we can just skip to the next field
  val listofnodeaddresses: Codec[List[NodeAddress]] = variableSizeBytes(uint16, list(nodeaddress))

  val shortchannelid: Codec[ShortChannelId] = int64.xmap(l => ShortChannelId(l), s => s.toLong)

  val privateKey: Codec[PrivateKey] = Codec[PrivateKey](
    (priv: PrivateKey) => bytes(32).encode(priv.value),
    (wire: BitVector) => bytes(32).decode(wire).map(_.map(b => PrivateKey(b)))
  )

  val publicKey: Codec[PublicKey] = Codec[PublicKey](
    (pub: PublicKey) => bytes(33).encode(pub.value),
    (wire: BitVector) => bytes(33).decode(wire).map(_.map(b => PublicKey(b)))
  )

  val rgb: Codec[Color] = bytes(3).xmap(buf => Color(buf(0), buf(1), buf(2)), t => ByteVector(t.r, t.g, t.b))

  def zeropaddedstring(size: Int): Codec[String] = fixedSizeBytes(32, utf8).xmap(s => s.takeWhile(_ != '\u0000'), s => s)

}
