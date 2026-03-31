package fr.acinq.eclair.wire.protocol

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import fr.acinq.bitcoin.scalacompat.Protocol
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.lightningMessageCodec
import scodec.bits.ByteVector

import java.nio.ByteOrder

/**
 * Fuzz tests for Lightning message codecs.
 */
class LightningMessageCodecsFuzzTest {

  /**
   * Prepend a two-byte big-endian message type to the fuzz data, then verify
   * that the codec does not throw on arbitrary input and that the canonical
   * encoding is stable.
   */
  private def codecRoundTrip(data: FuzzedDataProvider, msgType: Int): Unit = {
    val wire = Protocol.writeUInt16(msgType, ByteOrder.BIG_ENDIAN) ++ ByteVector(data.consumeRemainingAsBytes())

    val decoded = lightningMessageCodec.decode(wire.bits)
    if (decoded.isFailure) return

    val encoded1 = lightningMessageCodec.encode(decoded.require.value).require
    val encoded2 = lightningMessageCodec.encode(lightningMessageCodec.decode(encoded1).require.value).require
    assert(encoded1 == encoded2)
  }

  @FuzzTest(maxDuration = "")
  def fuzzOpenChannel(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 32)

  @FuzzTest(maxDuration = "")
  def fuzzAcceptChannel(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 33)

  @FuzzTest(maxDuration = "")
  def fuzzFundingCreated(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 34)

  @FuzzTest(maxDuration = "")
  def fuzzFundingSigned(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 35)

  @FuzzTest(maxDuration = "")
  def fuzzChannelReady(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 36)
}
