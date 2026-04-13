package fr.acinq.eclair.wire.protocol

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import fr.acinq.bitcoin.scalacompat.Protocol
import fr.acinq.eclair.wire.protocol.FailureMessageCodecs.failureMessageCodec
import scodec.bits.ByteVector

import java.nio.ByteOrder

/**
 * Fuzz tests for onion failure message codecs that have structured fields.
 */
class FailureMessageCodecsFuzzTest {

  private def failureRoundTrip(data: FuzzedDataProvider, code: Int): Unit = {
    val wire = Protocol.writeUInt16(code, ByteOrder.BIG_ENDIAN) ++ ByteVector(data.consumeRemainingAsBytes())

    val decoded = failureMessageCodec.decode(wire.bits)
    if (decoded.isFailure) return

    val encoded1 = failureMessageCodec.encode(decoded.require.value).require
    val encoded2 = failureMessageCodec.encode(failureMessageCodec.decode(encoded1).require.value).require
    assert(encoded1 == encoded2)
  }

  import FailureMessageCodecs.{BADONION, PERM, UPDATE}

  @FuzzTest(maxDuration = "")
  def fuzzInvalidOnionVersion(data: FuzzedDataProvider): Unit =
    failureRoundTrip(data, BADONION | PERM | 4)

  @FuzzTest(maxDuration = "")
  def fuzzInvalidOnionHmac(data: FuzzedDataProvider): Unit =
    failureRoundTrip(data, BADONION | PERM | 5)

  @FuzzTest(maxDuration = "")
  def fuzzInvalidOnionKey(data: FuzzedDataProvider): Unit =
    failureRoundTrip(data, BADONION | PERM | 6)

  @FuzzTest(maxDuration = "")
  def fuzzTemporaryChannelFailure(data: FuzzedDataProvider): Unit =
    failureRoundTrip(data, UPDATE | 7)

  @FuzzTest(maxDuration = "")
  def fuzzAmountBelowMinimum(data: FuzzedDataProvider): Unit =
    failureRoundTrip(data, UPDATE | 11)

  @FuzzTest(maxDuration = "")
  def fuzzFeeInsufficient(data: FuzzedDataProvider): Unit =
    failureRoundTrip(data, UPDATE | 12)

  @FuzzTest(maxDuration = "")
  def fuzzIncorrectCltvExpiry(data: FuzzedDataProvider): Unit =
    failureRoundTrip(data, UPDATE | 13)

  @FuzzTest(maxDuration = "")
  def fuzzExpiryTooSoon(data: FuzzedDataProvider): Unit =
    failureRoundTrip(data, UPDATE | 14)

  @FuzzTest(maxDuration = "")
  def fuzzIncorrectOrUnknownPaymentDetails(data: FuzzedDataProvider): Unit =
    failureRoundTrip(data, PERM | 15)

  @FuzzTest(maxDuration = "")
  def fuzzFinalIncorrectCltvExpiry(data: FuzzedDataProvider): Unit =
    failureRoundTrip(data, 18)

  @FuzzTest(maxDuration = "")
  def fuzzFinalIncorrectHtlcAmount(data: FuzzedDataProvider): Unit =
    failureRoundTrip(data, 19)

  @FuzzTest(maxDuration = "")
  def fuzzChannelDisabled(data: FuzzedDataProvider): Unit =
    failureRoundTrip(data, UPDATE | 20)

  @FuzzTest(maxDuration = "")
  def fuzzInvalidOnionPayload(data: FuzzedDataProvider): Unit =
    failureRoundTrip(data, PERM | 22)

  @FuzzTest(maxDuration = "")
  def fuzzInvalidOnionBlinding(data: FuzzedDataProvider): Unit =
    failureRoundTrip(data, BADONION | PERM | 24)
}
