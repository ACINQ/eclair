package fr.acinq.eclair.wire.protocol

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec
import scodec.bits.ByteVector

/**
 * Fuzz tests for route blinding codecs.
 */
class RouteBlindingCodecsFuzzTest {

  @FuzzTest(maxDuration = "")
  def fuzzBlindedRouteData(data: FuzzedDataProvider): Unit = {
    val wire = ByteVector(data.consumeRemainingAsBytes())
    val decoded = blindedRouteDataCodec.decode(wire.bits)
    if (decoded.isFailure) return

    val encoded1 = blindedRouteDataCodec.encode(decoded.require.value).require
    val encoded2 = blindedRouteDataCodec.encode(blindedRouteDataCodec.decode(encoded1).require.value).require
    assert(encoded1 == encoded2)
  }
}
