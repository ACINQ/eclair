package fr.acinq.eclair.payment

import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.PaymentLifecycle._
import fr.acinq.eclair.randomKey
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.wire.{ChannelUpdate, Codecs, PerHopPayload}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import scodec.bits.BitVector

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class HtlcGenerationSpec extends FunSuite {

  test("compute fees") {
    val feeBaseMsat = 150000L
    val feeProportionalMillionth = 4L
    val htlcAmountMsat = 42000000
    // spec: fee-base-msat + htlc-amount-msat * fee-proportional-millionths / 1000000
    val ref = feeBaseMsat + htlcAmountMsat * feeProportionalMillionth / 1000000
    val fee = nodeFee(feeBaseMsat, feeProportionalMillionth, htlcAmountMsat)
    assert(ref === fee)
  }

  import HtlcGenerationSpec._

  test("compute route with fees and expiry delta") {

    val (firstAmountMsat, firstExpiry, payloads) = buildRoute(finalAmountMsat, hops.drop(1), currentBlockCount)

    assert(firstAmountMsat === amount_ab)
    assert(firstExpiry === expiry_ab)
    assert(payloads ===
      PerHopPayload(amount_bc, expiry_bc) ::
        PerHopPayload(amount_cd, expiry_cd) ::
        PerHopPayload(amount_de, expiry_de) :: Nil)
  }

  test("build onion") {

    val (_, _, payloads) = buildRoute(finalAmountMsat, hops.drop(1), currentBlockCount)
    val nodes = hops.map(_.nextNodeId)
    val packet_b = buildOnion(nodes, payloads, paymentHash)
    assert(packet_b.size === 1254)

    // let's peel the onion
    val (bin_b, address_c, packet_c) = Sphinx.parsePacket(priv_b, paymentHash, packet_b)
    val payload_b = Codecs.perHopPayloadCodec.decode(BitVector(bin_b.data)).toOption.get.value
    assert(address_c === c.hash160)
    assert(packet_c.size === 1254)
    assert(payload_b.amt_to_forward === amount_bc)
    assert(payload_b.outgoing_cltv_value === expiry_bc)

    val (bin_c, address_d, packet_d) = Sphinx.parsePacket(priv_c, paymentHash, packet_c)
    val payload_c = Codecs.perHopPayloadCodec.decode(BitVector(bin_c.data)).toOption.get.value
    assert(address_d === d.hash160)
    assert(packet_d.size === 1254)
    assert(payload_c.amt_to_forward === amount_cd)
    assert(payload_c.outgoing_cltv_value === expiry_cd)

    val (bin_d, address_e, packet_e) = Sphinx.parsePacket(priv_d, paymentHash, packet_d)
    val payload_d = Codecs.perHopPayloadCodec.decode(BitVector(bin_d.data)).toOption.get.value
    assert(address_e === e.hash160)
    assert(packet_e.size === 1254)
    assert(payload_d.amt_to_forward === amount_de)
    assert(payload_d.outgoing_cltv_value === expiry_de)

    val (bin_e, address_null, packet_random) = Sphinx.parsePacket(priv_e, paymentHash, packet_e)
    assert(bin_e === BinaryData("00" * 20))
    assert(address_null === BinaryData("00" * 20))
    assert(packet_random.size === 1254)
  }

  test("build a command including the onion") {

    val add = buildCommand(finalAmountMsat, paymentHash, hops, currentBlockCount)

    assert(add.amountMsat > finalAmountMsat)
    assert(add.expiry === currentBlockCount + defaultHtlcExpiry + channelUpdate_de.cltvExpiryDelta + channelUpdate_cd.cltvExpiryDelta + channelUpdate_bc.cltvExpiryDelta)
    assert(add.paymentHash === paymentHash)
    assert(add.onion.size === 1254)

    // let's peel the onion
    val (bin_b, address_c, packet_c) = Sphinx.parsePacket(priv_b, paymentHash, add.onion)
    val payload_b = Codecs.perHopPayloadCodec.decode(BitVector(bin_b.data)).toOption.get.value
    assert(address_c === c.hash160)
    assert(packet_c.size === 1254)
    assert(payload_b.amt_to_forward === amount_bc)
    assert(payload_b.outgoing_cltv_value === expiry_bc)

    val (bin_c, address_d, packet_d) = Sphinx.parsePacket(priv_c, paymentHash, packet_c)
    val payload_c = Codecs.perHopPayloadCodec.decode(BitVector(bin_c.data)).toOption.get.value
    assert(address_d === d.hash160)
    assert(packet_d.size === 1254)
    assert(payload_c.amt_to_forward === amount_cd)
    assert(payload_c.outgoing_cltv_value === expiry_cd)

    val (bin_d, address_e, packet_e) = Sphinx.parsePacket(priv_d, paymentHash, packet_d)
    val payload_d = Codecs.perHopPayloadCodec.decode(BitVector(bin_d.data)).toOption.get.value
    assert(address_e === e.hash160)
    assert(packet_e.size === 1254)
    assert(payload_d.amt_to_forward === amount_de)
    assert(payload_d.outgoing_cltv_value === expiry_de)

    val (bin_e, address_null, packet_random) = Sphinx.parsePacket(priv_e, paymentHash, packet_e)
    assert(bin_e === BinaryData("00" * 20))
    assert(address_null === BinaryData("00" * 20))
    assert(packet_random.size === 1254)
  }

  test("build a command with no hops") {
    val add = buildCommand(finalAmountMsat, paymentHash, hops.take(1), currentBlockCount)

    assert(add.amountMsat === finalAmountMsat)
    assert(add.expiry === currentBlockCount + defaultHtlcExpiry)
    assert(add.paymentHash === paymentHash)
    assert(add.onion.size === 1254)

    // let's peel the onion
    val (bin_b, address_null, packet_random) = Sphinx.parsePacket(priv_b, paymentHash, add.onion)
    assert(bin_b === BinaryData("00" * 20))
    assert(address_null === BinaryData("00" * 20))
    assert(packet_random.size === 1254)
  }

}

object HtlcGenerationSpec {
  val (priv_a, priv_b, priv_c, priv_d, priv_e) = (randomKey, randomKey, randomKey, randomKey, randomKey)
  val (a, b, c, d, e) = (priv_a.publicKey, priv_b.publicKey, priv_c.publicKey, priv_d.publicKey, priv_e.publicKey)

  val defaultChannelUpdate = ChannelUpdate("00" * 64, 0, 0, "0000", 0, 0, 0, 0)
  val channelUpdate_ab = defaultChannelUpdate.copy(cltvExpiryDelta = 4, feeBaseMsat = 642000, feeProportionalMillionths = 7)
  val channelUpdate_bc = defaultChannelUpdate.copy(cltvExpiryDelta = 5, feeBaseMsat = 153000, feeProportionalMillionths = 4)
  val channelUpdate_cd = defaultChannelUpdate.copy(cltvExpiryDelta = 10, feeBaseMsat = 60000, feeProportionalMillionths = 1)
  val channelUpdate_de = defaultChannelUpdate.copy(cltvExpiryDelta = 7, feeBaseMsat = 766000, feeProportionalMillionths = 10)

  // simple route a -> b -> c -> d -> e

  val hops =
    Hop(a, b, channelUpdate_ab) ::
      Hop(b, c, channelUpdate_bc) ::
      Hop(c, d, channelUpdate_cd) ::
      Hop(d, e, channelUpdate_de) :: Nil

  val finalAmountMsat = 42000000L
  val paymentPreimage = BinaryData("42" * 32)
  val paymentHash = Crypto.sha256(paymentPreimage)
  val currentBlockCount = 420000

  val expiry_de = currentBlockCount + defaultHtlcExpiry
  val amount_de = finalAmountMsat
  val fee_d = nodeFee(channelUpdate_de.feeBaseMsat, channelUpdate_de.feeProportionalMillionths, amount_de)

  val expiry_cd = expiry_de + channelUpdate_de.cltvExpiryDelta
  val amount_cd = amount_de + fee_d
  val fee_c = nodeFee(channelUpdate_cd.feeBaseMsat, channelUpdate_cd.feeProportionalMillionths, amount_cd)

  val expiry_bc = expiry_cd + channelUpdate_cd.cltvExpiryDelta
  val amount_bc = amount_cd + fee_c
  val fee_b = nodeFee(channelUpdate_bc.feeBaseMsat, channelUpdate_bc.feeProportionalMillionths, amount_bc)

  val expiry_ab = expiry_bc + channelUpdate_bc.cltvExpiryDelta
  val amount_ab = amount_bc + fee_b
}
