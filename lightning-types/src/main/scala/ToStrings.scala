/**
  * Created by PM on 18/02/2016.
  */

package lightning

import java.io.{ByteArrayOutputStream, OutputStream}
import java.math.BigInteger
import javax.xml.bind.DatatypeConverter

import com.google.protobuf.ByteString


object ToStrings {
  def writeUInt8(input: Long, out: OutputStream): Unit = out.write((input & 0xff).asInstanceOf[Int])

  def writeUInt64(input: Long, out: OutputStream): Unit = {
    writeUInt8((input) & 0xff, out)
    writeUInt8((input >>> 8) & 0xff, out)
    writeUInt8((input >>> 16) & 0xff, out)
    writeUInt8((input >>> 24) & 0xff, out)
    writeUInt8((input >>> 32) & 0xff, out)
    writeUInt8((input >>> 40) & 0xff, out)
    writeUInt8((input >>> 48) & 0xff, out)
    writeUInt8((input >>> 56) & 0xff, out)
  }
}

trait Sha256ToString {

  // @formatter:off
  def a: Long
  def b: Long
  def c: Long
  def d: Long
  // @formatter:on

  override def toString = {
    import ToStrings._
    val bos = new ByteArrayOutputStream()
    writeUInt64(a, bos)
    writeUInt64(b, bos)
    writeUInt64(c, bos)
    writeUInt64(d, bos)
    s"sha256_hash(${DatatypeConverter.printHexBinary(bos.toByteArray)})"
  }

}

trait RvalToString {

  // @formatter:off
  def a: Long
  def b: Long
  def c: Long
  def d: Long
  // @formatter:on

  override def toString = {
    import ToStrings._
    val bos = new ByteArrayOutputStream()
    writeUInt64(a, bos)
    writeUInt64(b, bos)
    writeUInt64(c, bos)
    writeUInt64(d, bos)
    s"rval(${DatatypeConverter.printHexBinary(bos.toByteArray)})"
  }

}

trait SignatureToString {

  // @formatter:off
  def r1: Long
  def r2: Long
  def r3: Long
  def r4: Long
  def s1: Long
  def s2: Long
  def s3: Long
  def s4: Long
  // @formatter:on

  override def toString = {
    import ToStrings._
    val rbos = new ByteArrayOutputStream()
    writeUInt64(r1, rbos)
    writeUInt64(r2, rbos)
    writeUInt64(r3, rbos)
    writeUInt64(r4, rbos)
    val r = new BigInteger(1, rbos.toByteArray.reverse)
    val sbos = new ByteArrayOutputStream()
    writeUInt64(s1, sbos)
    writeUInt64(s2, sbos)
    writeUInt64(s3, sbos)
    writeUInt64(s4, sbos)
    val s = new BigInteger(1, sbos.toByteArray.reverse)
    s"signature(r=${DatatypeConverter.printHexBinary(r.toByteArray)},s=${DatatypeConverter.printHexBinary(s.toByteArray)})"
  }
}

trait PubkeyToString {

  def key: ByteString

  override def toString = s"bitcoin_pubkey(${DatatypeConverter.printHexBinary(key.toByteArray)})"
}
