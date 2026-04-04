package fr.acinq.eclair.wire.protocol

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import scodec.bits.ByteVector

/**
 * Fuzz tests for onion payload codecs.
 */
class OnionPayloadCodecsFuzzTest {

  @FuzzTest(maxDuration = "")
  def fuzzPaymentOnionPerHopPayload(data: FuzzedDataProvider): Unit = {
    val wire = ByteVector(data.consumeRemainingAsBytes())
    val decoded = PaymentOnionCodecs.perHopPayloadCodec.decode(wire.bits)
    if (decoded.isFailure) return

    val encoded1 = PaymentOnionCodecs.perHopPayloadCodec.encode(decoded.require.value).require
    val encoded2 = PaymentOnionCodecs.perHopPayloadCodec.encode(PaymentOnionCodecs.perHopPayloadCodec.decode(encoded1).require.value).require
    assert(encoded1 == encoded2)
  }

  @FuzzTest(maxDuration = "")
  def fuzzMessageOnionPerHopPayload(data: FuzzedDataProvider): Unit = {
    val wire = ByteVector(data.consumeRemainingAsBytes())
    val decoded = MessageOnionCodecs.perHopPayloadCodec.decode(wire.bits)
    if (decoded.isFailure) return

    val encoded1 = MessageOnionCodecs.perHopPayloadCodec.encode(decoded.require.value).require
    val encoded2 = MessageOnionCodecs.perHopPayloadCodec.encode(MessageOnionCodecs.perHopPayloadCodec.decode(encoded1).require.value).require
    assert(encoded1 == encoded2)
  }
}
