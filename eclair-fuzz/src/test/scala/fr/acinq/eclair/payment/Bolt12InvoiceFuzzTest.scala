package fr.acinq.eclair.payment

import com.code_intelligence.jazzer.junit.FuzzTest
import fr.acinq.bitcoin.Bech32
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer}
import org.junit.jupiter.params.provider.MethodSource

/**
 * Fuzz tests for Bolt 12 offer, invoice request, and invoice deserialization.
 */
class Bolt12InvoiceFuzzTest {

  @MethodSource(Array("offerSeeds"))
  @FuzzTest(maxDuration = "")
  def fuzzOffer(data: Array[Byte]): Unit = {
    val offerStr = try {
      Bech32.encodeBytes(Offer.hrp, data, Bech32.Encoding.Beck32WithoutChecksum)
    } catch {
      case _: Exception => return
    }

    val offer = Offer.decode(offerStr)
    if (offer.isFailure) return

    val encoded = offer.get.encode()
    val offer2 = Offer.decode(encoded)
    assert(offer2.isSuccess)
    assert(offer.get == offer2.get)
  }

  @MethodSource(Array("invoiceRequestSeeds"))
  @FuzzTest(maxDuration = "")
  def fuzzInvoiceRequest(data: Array[Byte]): Unit = {
    val invoiceReqStr = try {
      Bech32.encodeBytes(InvoiceRequest.hrp, data, Bech32.Encoding.Beck32WithoutChecksum)
    } catch {
      case _: Exception => return
    }

    val invoiceReq = InvoiceRequest.decode(invoiceReqStr)
    if (invoiceReq.isFailure) return

    val encoded = invoiceReq.get.encode()
    val invoiceReq2 = InvoiceRequest.decode(encoded)
    assert(invoiceReq2.isSuccess)
    assert(invoiceReq.get == invoiceReq2.get)
  }

  @MethodSource(Array("bolt12InvoiceSeeds"))
  @FuzzTest(maxDuration = "")
  def fuzzBolt12Invoice(data: Array[Byte]): Unit = {
    val invoiceStr = try {
      Bech32.encodeBytes(Bolt12Invoice.hrp, data, Bech32.Encoding.Beck32WithoutChecksum)
    } catch {
      case _: Exception => return
    }

    val invoice = Bolt12Invoice.fromString(invoiceStr)
    if (invoice.isFailure) return

    val encoded = invoice.get.toString
    val invoice2 = Bolt12Invoice.fromString(encoded)
    assert(invoice2.isSuccess)
    assert(invoice.get == invoice2.get)
  }

  @FuzzTest(maxDuration = "")
  def fuzzMinimalBolt12Invoice(data: Array[Byte]): Unit = {
    val invoiceStr = try {
      Bech32.encodeBytes(MinimalBolt12Invoice.hrp, data, Bech32.Encoding.Beck32WithoutChecksum)
    } catch {
      case _: Exception => return
    }

    val invoice = MinimalBolt12Invoice.fromString(invoiceStr)
    if (invoice.isFailure) return

    val encoded = invoice.get.toString
    val invoice2 = MinimalBolt12Invoice.fromString(encoded)
    assert(invoice2.isSuccess)
    assert(invoice.get == invoice2.get)
  }
}

object Bolt12InvoiceFuzzTest {

  private val OFFER_SEED = "lno1qgsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqgqyeq5ym0venx2u3qwa5hg6pqw96kzmn5d968jys3v9kxjcm9gp3xjemndphhqtnrdak3gqqkyypsmuhrtwfzm85mht4a3vcp0yrlgua3u3m5uqpc6kf7nqjz6v70qwg"
  private val INVOICE_REQUEST_SEED = "lnr1qqp6hn00zcssxr0juddeytv7nwawhk9nq9us0arnk8j8wnsq8r2e86vzgtfneupe2gp9yzzcyypymkt4c0n6rhcdw9a7ay2ptuje2gvehscwcchlvgntump3x7e7tc0sgp9k43qeu892gfnz2hrr7akh2x8erh7zm2tv52884vyl462dm5tfcahgtuzt7j0npy7getf4trv5d4g78a9fkwu3kke6hcxdr6t2n7vz"
  private val BOLT12_INVOICE_SEED = "lni1qqx2n6mw2fh2ckwdnwylkgqzypp5jl7hlqnf2ugg7j3slkwwcwht57vhyzzwjr4dq84rxzgqqqqqqzqrq83yqzscd9h8vmmfvdjjqamfw35zqmtpdeujqenfv4kxgucvqqfq2ctvd93k293pq0zxw03kpc8tc2vv3kfdne0kntqhq8p70wtdncwq2zngaqp529mmc5pqgdyhl4lcy62hzz855v8annkr46a8n9eqsn5satgpagesjqqqqqq9yqcpufq9vqfetqssyj5djm6dz0zzr8eprw9gu762k75f3lgm96gzwn994peh48k6xalctyr5jfmdyppx7cneqvqsyqaq5qpugee7xc8qa0pf3jxe9k0976dvzuqu8eaedk0pcpg2dr5qx3gh00qzn8pc426xsh6l6ekdhr2hdpge0euhhp9frv6w04zjcqhhf6ru2wrqzqnjsxh8zmlm0gkeuq8qyxcy28uzhzljqkq22epc4mmdrx6vtm0eyyqr4agrvpkfuutftvf7f6paqewk3ysql3h8ukfz3phgmap5we4wsq3c97205a96r6f3hsd705jl29xt8yj3cu8vpm6z8lztjw3pcqqqpy5sqqqzl5q5gqqqqqqqqqqraqqqqqqqqqq7ysqqqzjqgc4qq6l2vqswzz5zq5v4r4x98jgyqd0sk2fae803crnevusngv9wq7jl8cf5e5eny56p4gpsrcjq4sfqgqqyzg74d7qxqqywkwkudz29aasp4cqtqggrc3nnudswp67znrydjtv7ta56c9cpc0nmjmv7rszs568gqdz3w770qsx3axhvq3e7npme2pwslgxa8kfcnqjqyeztg5r5wgzjpufjswx4crvd6kzlqjzukq5e707kp9ez98mj0zkckeggkm8cp6g6vgzh3j2q0lgp8ypt4ws"

  private def decode(bech32: String): Array[Byte] = Bech32.decodeBytes(bech32.toLowerCase, true).getSecond

  def offerSeeds(): Array[Array[Byte]] = Array(decode(OFFER_SEED))

  def invoiceRequestSeeds(): Array[Array[Byte]] = Array(decode(INVOICE_REQUEST_SEED))

  def bolt12InvoiceSeeds(): Array[Array[Byte]] = Array(decode(BOLT12_INVOICE_SEED))
}
