package fr.acinq

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.math.BigInteger

import _root_.lightning._
import _root_.lightning.locktime.Locktime.{Blocks, Seconds}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin.Crypto._
import fr.acinq.bitcoin._
import fr.acinq.eclair.channel.{ChannelState, ChannelOneSide}

import scala.annotation.tailrec

package object eclair {

  implicit def bin2sha256(in: BinaryData): sha256_hash = {
    require(in.size == 32)
    val bis = new ByteArrayInputStream(in)
    sha256_hash(Protocol.uint64(bis), Protocol.uint64(bis), Protocol.uint64(bis), Protocol.uint64(bis))
  }

  implicit def array2sha256(in: Array[Byte]): sha256_hash = bin2sha256(in)

  implicit def sha2562bin(in: sha256_hash): BinaryData = {
    val bos = new ByteArrayOutputStream()
    Protocol.writeUInt64(in.a, bos)
    Protocol.writeUInt64(in.b, bos)
    Protocol.writeUInt64(in.c, bos)
    Protocol.writeUInt64(in.d, bos)
    bos.toByteArray
  }

  // TODO : redundant with above, needed for seamless Crypto.sha256(sha256_hash)
  implicit def sha2562seq(in: sha256_hash): Seq[Byte] = sha2562bin(in)

  implicit def bin2pubkey(in: BinaryData) = bitcoin_pubkey(ByteString.copyFrom(in))

  implicit def array2pubkey(in: Array[Byte]) = bin2pubkey(in)

  implicit def pubkey2bin(in: bitcoin_pubkey): BinaryData = in.key.toByteArray

  private def fixSize(in: Array[Byte]): Array[Byte] = in.size match {
    case 32 => in
    case s if s < 32 => Array.fill(32 - s)(0: Byte) ++ in
    case s if s > 32 => in.takeRight(32)
  }

  implicit def bin2signature(in: BinaryData): signature = {
    val (r, s) = Crypto.decodeSignature(in)
    val (ar, as) = (r.toByteArray, s.toByteArray)
    val (ar1, as1) = (fixSize(ar).reverse, fixSize(as).reverse)
    val (rbis, sbis) = (new ByteArrayInputStream(ar1), new ByteArrayInputStream(as1))
    signature(Protocol.uint64(rbis), Protocol.uint64(rbis), Protocol.uint64(rbis), Protocol.uint64(rbis), Protocol.uint64(sbis), Protocol.uint64(sbis), Protocol.uint64(sbis), Protocol.uint64(sbis))
  }

  implicit def array2signature(in: Array[Byte]): signature = bin2signature(in)

  implicit def signature2bin(in: signature): BinaryData = {
    val rbos = new ByteArrayOutputStream()
    Protocol.writeUInt64(in.r1, rbos)
    Protocol.writeUInt64(in.r2, rbos)
    Protocol.writeUInt64(in.r3, rbos)
    Protocol.writeUInt64(in.r4, rbos)
    val r = new BigInteger(1, rbos.toByteArray.reverse)
    val sbos = new ByteArrayOutputStream()
    Protocol.writeUInt64(in.s1, sbos)
    Protocol.writeUInt64(in.s2, sbos)
    Protocol.writeUInt64(in.s3, sbos)
    Protocol.writeUInt64(in.s4, sbos)
    val s = new BigInteger(1, sbos.toByteArray.reverse)
    Crypto.encodeSignature(r, s) :+ SIGHASH_ALL.toByte
  }

  implicit def locktime2long(in: locktime): Long = in match {
    case locktime(Blocks(blocks)) => blocks
    // FIXME : we adopt Element's alpha convention while BIP68 is not enabled
    case locktime(Seconds(seconds)) => 500000000 + seconds
  }

  @tailrec
  def memcmp(a: List[Byte], b: List[Byte]): Int = (a, b) match {
    case (x, y) if (x.length != y.length) => x.length - y.length
    case (Nil, Nil) => 0
    case (ha :: ta, hb :: tb) if ha == hb => memcmp(ta, tb)
    case (ha :: ta, hb :: tb) => (ha & 0xff) - (hb & 0xff)
  }

}