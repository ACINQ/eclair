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

package fr.acinq

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.KotlinUtils._
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.eclair.crypto.StrongRandom
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import scodec.Attempt
import scodec.bits.{BitVector, ByteVector}

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}

package object eclair {

  val randomGen = new StrongRandom()

  /**
   * Create an empty file that only the current user can read, replacing it if it already exists. The permissions must
   * be set when the file is created: if we set them after writing to the file, there is a window during which other
   * users can read its content.
   */
  def createSecretFile(path: Path): Unit = {
    // File attributes are ignored when the file already exists, so we start from a clean slate.
    Files.deleteIfExists(path)
    try {
      // The permissions are passed to the underlying open(2) call, so the file never exists with broader permissions.
      // This also fails if someone else concurrently created that file, instead of storing our secret in their file.
      Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } catch {
      case _: UnsupportedOperationException => Files.createFile(path) // non-POSIX file system (e.g. Windows)
    }
  }

  /** Write a secret to a file that only the current user can read. */
  def writeSecret(path: Path, secret: String): Unit = {
    createSecretFile(path)
    Files.writeString(path, secret) // the file already exists, so this doesn't change its permissions
  }

  /** Write a secret to a file that only the current user can read. */
  def writeSecret(path: Path, secret: Array[Byte]): Unit = {
    createSecretFile(path)
    Files.write(path, secret) // the file already exists, so this doesn't change its permissions
  }

  /**
   * Restrict access to a file or directory to its owner, e.g. "rw-------" for a secret file or "rwx------" for a
   * directory containing secrets. This is a no-op on file systems that don't support POSIX permissions (e.g. Windows),
   * where access control is handled differently.
   */
  def setOwnerPermissions(path: Path, permissions: String): Unit = {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
    } catch {
      case _: UnsupportedOperationException => () // non-POSIX file system (e.g. Windows)
    }
  }

  def randomBytes(length: Int): ByteVector = {
    val buffer = new Array[Byte](length)
    randomGen.nextBytes(buffer)
    ByteVector.view(buffer)
  }

  def randomBytes32(): ByteVector32 = ByteVector32(randomBytes(32))

  def randomBytes64(): ByteVector64 = ByteVector64(randomBytes(64))

  def randomKey(): PrivateKey = PrivateKey(randomBytes32())

  def randomLong(): Long = randomGen.nextLong()

  def toLongId(fundingTxId: TxId, fundingOutputIndex: Int): ByteVector32 = {
    require(fundingOutputIndex < 65536, "fundingOutputIndex must not be greater than 0xFFFF")
    val fundingTxHash = TxHash(fundingTxId).value
    val channelId = ByteVector32(fundingTxHash.take(30) :+ (fundingTxHash(30) ^ (fundingOutputIndex >> 8)).toByte :+ (fundingTxHash(31) ^ fundingOutputIndex).toByte)
    channelId
  }

  def serializationResult(attempt: Attempt[BitVector]): ByteVector = attempt match {
    case Attempt.Successful(bin) => bin.bytes
    case Attempt.Failure(cause) => throw new RuntimeException(s"serialization error: $cause")
  }

  /**
   * Tests whether the binary data is composed solely of printable ASCII characters (see BOLT 1)
   *
   * @param data to check
   */
  def isAsciiPrintable(data: ByteVector): Boolean = data.toArray.forall(ch => ch >= 32 && ch < 127)

  /**
   * @param baseFee         fixed fee
   * @param proportionalFee proportional fee (millionths)
   * @param paymentAmount   payment amount in millisatoshi
   * @return the fee that a node should be paid to forward an HTLC of 'paymentAmount' millisatoshis
   */
  def nodeFee(baseFee: MilliSatoshi, proportionalFee: Long, paymentAmount: MilliSatoshi): MilliSatoshi = baseFee + (paymentAmount * proportionalFee) / 1000000

  def nodeFee(relayFees: RelayFees, paymentAmount: MilliSatoshi): MilliSatoshi = nodeFee(relayFees.feeBase, relayFees.feeProportionalMillionths, paymentAmount)

  /**
   * @param baseFee         fixed fee
   * @param proportionalFee proportional fee (millionths)
   * @param incomingAmount  incoming payment amount
   * @return the amount that a node should forward after paying itself the base and proportional fees
   */
  def amountAfterFee(baseFee: MilliSatoshi, proportionalFee: Long, incomingAmount: MilliSatoshi): MilliSatoshi =
    ((incomingAmount - baseFee).toLong * 1_000_000 + 1_000_000 + proportionalFee - 1).msat / (1_000_000 + proportionalFee)

  def amountAfterFee(relayFees: RelayFees, incomingAmount: MilliSatoshi): MilliSatoshi = amountAfterFee(relayFees.feeBase, relayFees.feeProportionalMillionths, incomingAmount)

  implicit class MilliSatoshiLong(private val n: Long) extends AnyVal {
    def msat = MilliSatoshi(n)
  }

  implicit class TimestampSecondLong(private val n: Long) extends AnyVal {
    def unixsec = TimestampSecond(n)
  }

  implicit class TimestampMilliLong(private val n: Long) extends AnyVal {
    def unixms = TimestampMilli(n)
  }

  // We implement Numeric to take advantage of operations such as sum, sort or min/max on iterables.
  implicit object NumericMilliSatoshi extends Numeric[MilliSatoshi] {
    // @formatter:off
    override def plus(x: MilliSatoshi, y: MilliSatoshi): MilliSatoshi = x + y
    override def minus(x: MilliSatoshi, y: MilliSatoshi): MilliSatoshi = x - y
    override def times(x: MilliSatoshi, y: MilliSatoshi): MilliSatoshi = MilliSatoshi(x.toLong * y.toLong)
    override def negate(x: MilliSatoshi): MilliSatoshi = -x
    override def fromInt(x: Int): MilliSatoshi = MilliSatoshi(x)
    override def toInt(x: MilliSatoshi): Int = x.toLong.toInt
    override def toLong(x: MilliSatoshi): Long = x.toLong
    override def toFloat(x: MilliSatoshi): Float = x.toFloat
    override def toDouble(x: MilliSatoshi): Double = x.toDouble
    override def compare(x: MilliSatoshi, y: MilliSatoshi): Int = x.compare(y)
    override def parseString(str: String): Option[MilliSatoshi] = ???
    // @formatter:on
  }

  implicit class ToMilliSatoshiConversion(amount: BtcAmount) {
    // @formatter:off
    def toMilliSatoshi: MilliSatoshi = MilliSatoshi.toMilliSatoshi(amount)
    def +(other: MilliSatoshi): MilliSatoshi = amount.toMilliSatoshi + other
    def -(other: MilliSatoshi): MilliSatoshi = amount.toMilliSatoshi - other
    // @formatter:on
  }

  /**
   * Apparently .getClass.getSimpleName can crash java 8 with a "Malformed class name" error
   */
  def getSimpleClassName(o: Any): String = o.getClass.getName.split("\\$").last

}