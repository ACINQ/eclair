/**
  * Created by PM on 18/02/2016.
  */

package lightning

import java.io.OutputStream
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

trait PubkeyToString {

  def key: ByteString

  override def toString = s"bitcoin_pubkey(${DatatypeConverter.printHexBinary(key.toByteArray)})"
}
