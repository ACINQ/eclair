package fr.acinq.eclair.payment

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.payment.PaymentLifecycle._
import fr.acinq.eclair.randomKey
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.wire.{ChannelUpdate, PerHopPayload}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

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

  test("compute simple route (no fees, no expiry delta)") {

    val (a, b, c, d, e) = (BinaryData("aa" * 33), BinaryData("bb" * 33), BinaryData("cc" * 33), BinaryData("dd" * 33), BinaryData("ee" * 33))

    val defaultChannelUpdate = ChannelUpdate("00" * 64, 0, 0, "0000", 0, 0, 0, 0)

    // simple route b -> c -> d -> e

    val hops =
      Hop(b, c, defaultChannelUpdate) ::
        Hop(c, d, defaultChannelUpdate) ::
        Hop(d, e, defaultChannelUpdate) :: Nil

    val finalAmountMsat = 42000000L
    val currentBlockCount = 420000

    val (firstAmountMsat, firstExpiry, payloads) = buildRoute(finalAmountMsat, hops, currentBlockCount)
    assert(firstAmountMsat === finalAmountMsat)
    assert(firstExpiry === currentBlockCount + defaultHtlcExpiry)
    assert(payloads ===
      PerHopPayload(finalAmountMsat, currentBlockCount + defaultHtlcExpiry) ::
        PerHopPayload(finalAmountMsat, currentBlockCount + defaultHtlcExpiry) ::
        PerHopPayload(finalAmountMsat, currentBlockCount + defaultHtlcExpiry) :: Nil)
  }

  test("compute route with fees and expiry delta") {

    val (a, b, c, d, e) = (BinaryData("aa" * 33), BinaryData("bb" * 33), BinaryData("cc" * 33), BinaryData("dd" * 33), BinaryData("ee" * 33))

    val defaultChannelUpdate = ChannelUpdate("00" * 64, 0, 0, "0000", 0, 0, 0, 0)
    val channelUpdate_bc = defaultChannelUpdate.copy(cltvExpiryDelta = 5, feeBaseMsat = 153000, feeProportionalMillionths = 4)
    val channelUpdate_cd = defaultChannelUpdate.copy(cltvExpiryDelta = 10, feeBaseMsat = 60000, feeProportionalMillionths = 1)
    val channelUpdate_de = defaultChannelUpdate.copy(cltvExpiryDelta = 7, feeBaseMsat = 766000, feeProportionalMillionths = 10)

    // simple route b -> c -> d -> e

    val hops =
      Hop(b, c, channelUpdate_bc) ::
        Hop(c, d, channelUpdate_cd) ::
        Hop(d, e, channelUpdate_de) :: Nil

    val finalAmountMsat = 42000000L
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

    val (firstAmountMsat, firstExpiry, payloads) = buildRoute(finalAmountMsat, hops, currentBlockCount)
    assert(firstAmountMsat === amount_ab)
    assert(firstExpiry === expiry_ab)
    assert(payloads ===
      PerHopPayload(amount_bc, expiry_bc) ::
        PerHopPayload(amount_cd, expiry_cd) ::
        PerHopPayload(amount_de, expiry_de) :: Nil)
  }

  test("build onion") {

    def randomPubkey = randomKey.publicKey
    val (a, b, c, d, e) = (randomPubkey, randomPubkey, randomPubkey, randomPubkey, randomPubkey)

    val defaultChannelUpdate = ChannelUpdate("00" * 64, 0, 0, "0000", 0, 0, 0, 0)
    val channelUpdate_bc = defaultChannelUpdate.copy(cltvExpiryDelta = 5, feeBaseMsat = 153000, feeProportionalMillionths = 4)
    val channelUpdate_cd = defaultChannelUpdate.copy(cltvExpiryDelta = 10, feeBaseMsat = 60000, feeProportionalMillionths = 1)
    val channelUpdate_de = defaultChannelUpdate.copy(cltvExpiryDelta = 7, feeBaseMsat = 766000, feeProportionalMillionths = 10)

    // simple route b -> c -> d -> e

    val hops =
      Hop(b, c, channelUpdate_bc) ::
        Hop(c, d, channelUpdate_cd) ::
        Hop(d, e, channelUpdate_de) :: Nil

    val finalAmountMsat = 42000000L
    val currentBlockCount = 420000

    val (_, _, payloads) = buildRoute(finalAmountMsat, hops, currentBlockCount)
    val nodes = hops.map(_.nextNodeId)
    val onion = buildOnion(nodes, payloads)

    assert(onion.size === 1254)

  }

  test("build command including the onion") {

    def randomPubkey = randomKey.publicKey
    val (a, b, c, d, e) = (randomPubkey, randomPubkey, randomPubkey, randomPubkey, randomPubkey)

    val defaultChannelUpdate = ChannelUpdate("00" * 64, 0, 0, "0000", 0, 0, 0, 0)
    val channelUpdate_bc = defaultChannelUpdate.copy(cltvExpiryDelta = 5, feeBaseMsat = 153000, feeProportionalMillionths = 4)
    val channelUpdate_cd = defaultChannelUpdate.copy(cltvExpiryDelta = 10, feeBaseMsat = 60000, feeProportionalMillionths = 1)
    val channelUpdate_de = defaultChannelUpdate.copy(cltvExpiryDelta = 7, feeBaseMsat = 766000, feeProportionalMillionths = 10)

    // simple route b -> c -> d -> e

    val hops =
      Hop(b, c, channelUpdate_bc) ::
        Hop(c, d, channelUpdate_cd) ::
        Hop(d, e, channelUpdate_de) :: Nil

    val finalAmountMsat = 42000000L
    val paymentHash = BinaryData("42" * 32)
    val currentBlockCount = 420000

    val add = buildCommand(finalAmountMsat, paymentHash, hops, currentBlockCount)
    assert(add.amountMsat > finalAmountMsat)
    assert(add.expiry === currentBlockCount + defaultHtlcExpiry + channelUpdate_de.cltvExpiryDelta + channelUpdate_cd.cltvExpiryDelta + channelUpdate_bc.cltvExpiryDelta)
    assert(add.paymentHash === paymentHash)
    assert(add.onion.size === 1254)
  }


}
