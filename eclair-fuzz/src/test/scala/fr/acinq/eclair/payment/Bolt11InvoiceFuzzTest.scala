package fr.acinq.eclair.payment

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import fr.acinq.bitcoin.Bech32

/**
 * Fuzz tests for Bolt 11 invoice deserialization.
 */
class Bolt11InvoiceFuzzTest {

  @FuzzTest(maxDuration = "")
  def fuzzBolt11Invoice(data: FuzzedDataProvider): Unit = {
    val hrp = data.consumeAsciiString(data.consumeInt(1, 83))
    val int5s: Array[java.lang.Byte] = data.consumeRemainingAsBytes().map(b => (Math.floorMod(b, 32).toByte): java.lang.Byte)

    val invoiceStr = try {
      Bech32.encode(hrp, int5s, Bech32.Encoding.Bech32)
    } catch {
      case _: Exception => return
    }

    val invoice = Bolt11Invoice.fromString(invoiceStr)
    if (invoice.isFailure) return

    val encoded = invoice.get.toString
    val invoice2 = Bolt11Invoice.fromString(encoded)
    assert(invoice2.isSuccess)
    assert(encoded == invoice2.get.toString)
  }
}
