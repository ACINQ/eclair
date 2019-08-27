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

package fr.acinq.eclair.payment

import java.util.UUID

import fr.acinq.bitcoin.DeterministicWallet.ExtendedPrivateKey
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto, DeterministicWallet}
import fr.acinq.eclair.channel.{Channel, ChannelVersion, Commitments}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.{DecryptedPacket, PacketAndSecrets}
import fr.acinq.eclair.payment.PaymentLifecycle._
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.wire.{ChannelUpdate, OnionCodecs, PerHopPayload}
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, MilliSatoshi, ShortChannelId, TestConstants, maxOf, nodeFee, randomBytes32}
import org.scalatest.FunSuite
import scodec.bits.ByteVector

/**
 * Created by PM on 31/05/2016.
 */

class HtlcGenerationSpec extends FunSuite {

  test("compute fees") {
    val feeBaseMsat = MilliSatoshi(150000L)
    val feeProportionalMillionth = 4L
    val htlcAmountMsat = MilliSatoshi(42000000)
    // spec: fee-base-msat + htlc-amount-msat * fee-proportional-millionths / 1000000
    val ref = feeBaseMsat + htlcAmountMsat * feeProportionalMillionth / 1000000
    val fee = nodeFee(feeBaseMsat, feeProportionalMillionth, htlcAmountMsat)
    assert(ref === fee)
  }

  import HtlcGenerationSpec._

  test("compute payloads with fees and expiry delta") {

    val (firstAmountMsat, firstExpiry, payloads) = buildPayloads(finalAmountMsat, finalExpiry, hops.drop(1))

    assert(firstAmountMsat === amount_ab)
    assert(firstExpiry === expiry_ab)
    assert(payloads ===
      PerHopPayload(channelUpdate_bc.shortChannelId, amount_bc, expiry_bc) ::
        PerHopPayload(channelUpdate_cd.shortChannelId, amount_cd, expiry_cd) ::
        PerHopPayload(channelUpdate_de.shortChannelId, amount_de, expiry_de) ::
        PerHopPayload(ShortChannelId(0L), finalAmountMsat, finalExpiry) :: Nil)
  }

  test("build onion") {

    val (_, _, payloads) = buildPayloads(finalAmountMsat, finalExpiry, hops.drop(1))
    val nodes = hops.map(_.nextNodeId)
    val PacketAndSecrets(packet_b, _) = buildOnion(nodes, payloads, paymentHash)
    assert(packet_b.payload.length === Sphinx.PaymentPacket.PayloadLength)

    // let's peel the onion
    val Right(DecryptedPacket(bin_b, packet_c, _)) = Sphinx.PaymentPacket.peel(priv_b.privateKey, paymentHash, packet_b)
    val payload_b = OnionCodecs.perHopPayloadCodec.decode(bin_b.toBitVector).require.value
    assert(packet_c.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_b.amtToForward === amount_bc)
    assert(payload_b.outgoingCltvValue === expiry_bc)

    val Right(DecryptedPacket(bin_c, packet_d, _)) = Sphinx.PaymentPacket.peel(priv_c.privateKey, paymentHash, packet_c)
    val payload_c = OnionCodecs.perHopPayloadCodec.decode(bin_c.toBitVector).require.value
    assert(packet_d.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_c.amtToForward === amount_cd)
    assert(payload_c.outgoingCltvValue === expiry_cd)

    val Right(DecryptedPacket(bin_d, packet_e, _)) = Sphinx.PaymentPacket.peel(priv_d.privateKey, paymentHash, packet_d)
    val payload_d = OnionCodecs.perHopPayloadCodec.decode(bin_d.toBitVector).require.value
    assert(packet_e.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_d.amtToForward === amount_de)
    assert(payload_d.outgoingCltvValue === expiry_de)

    val Right(DecryptedPacket(bin_e, packet_random, _)) = Sphinx.PaymentPacket.peel(priv_e.privateKey, paymentHash, packet_e)
    val payload_e = OnionCodecs.perHopPayloadCodec.decode(bin_e.toBitVector).require.value
    assert(packet_random.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_e.amtToForward === finalAmountMsat)
    assert(payload_e.outgoingCltvValue === finalExpiry)
  }

  test("build a command including the onion") {

    val (add, _) = buildCommand(UUID.randomUUID, finalAmountMsat, finalExpiry, paymentHash, hops)

    assert(add.amount > finalAmountMsat)
    assert(add.cltvExpiry === finalExpiry + channelUpdate_de.cltvExpiryDelta + channelUpdate_cd.cltvExpiryDelta + channelUpdate_bc.cltvExpiryDelta)
    assert(add.paymentHash === paymentHash)
    assert(add.onion.payload.length === Sphinx.PaymentPacket.PayloadLength)

    // let's peel the onion
    val Right(DecryptedPacket(bin_b, packet_c, _)) = Sphinx.PaymentPacket.peel(priv_b.privateKey, paymentHash, add.onion)
    val payload_b = OnionCodecs.perHopPayloadCodec.decode(bin_b.toBitVector).require.value
    assert(packet_c.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_b.amtToForward === amount_bc)
    assert(payload_b.outgoingCltvValue === expiry_bc)

    val Right(DecryptedPacket(bin_c, packet_d, _)) = Sphinx.PaymentPacket.peel(priv_c.privateKey, paymentHash, packet_c)
    val payload_c = OnionCodecs.perHopPayloadCodec.decode(bin_c.toBitVector).require.value
    assert(packet_d.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_c.amtToForward === amount_cd)
    assert(payload_c.outgoingCltvValue === expiry_cd)

    val Right(DecryptedPacket(bin_d, packet_e, _)) = Sphinx.PaymentPacket.peel(priv_d.privateKey, paymentHash, packet_d)
    val payload_d = OnionCodecs.perHopPayloadCodec.decode(bin_d.toBitVector).require.value
    assert(packet_e.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_d.amtToForward === amount_de)
    assert(payload_d.outgoingCltvValue === expiry_de)

    val Right(DecryptedPacket(bin_e, packet_random, _)) = Sphinx.PaymentPacket.peel(priv_e.privateKey, paymentHash, packet_e)
    val payload_e = OnionCodecs.perHopPayloadCodec.decode(bin_e.toBitVector).require.value
    assert(packet_random.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_e.amtToForward === finalAmountMsat)
    assert(payload_e.outgoingCltvValue === finalExpiry)
  }

  test("build a command with no hops") {
    val (add, _) = buildCommand(UUID.randomUUID(), finalAmountMsat, finalExpiry, paymentHash, hops.take(1))

    assert(add.amount === finalAmountMsat)
    assert(add.cltvExpiry === finalExpiry)
    assert(add.paymentHash === paymentHash)
    assert(add.onion.payload.length === Sphinx.PaymentPacket.PayloadLength)

    // let's peel the onion
    val Right(DecryptedPacket(bin_b, packet_random, _)) = Sphinx.PaymentPacket.peel(priv_b.privateKey, paymentHash, add.onion)
    val payload_b = OnionCodecs.perHopPayloadCodec.decode(bin_b.toBitVector).require.value
    assert(packet_random.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_b.amtToForward === finalAmountMsat)
    assert(payload_b.outgoingCltvValue === finalExpiry)
  }

}

object HtlcGenerationSpec {

  def makeCommitments(channelId: ByteVector32, testAvailableBalanceForSend: MilliSatoshi = MilliSatoshi(50000000L), testAvailableBalanceForReceive: MilliSatoshi = MilliSatoshi(50000000L)) =
    new Commitments(ChannelVersion.STANDARD, null, null, 0.toByte, null, null, null, null, 0, 0, Map.empty, null, null, null, channelId) {
      override lazy val availableBalanceForSend: MilliSatoshi = maxOf(testAvailableBalanceForSend, MilliSatoshi(0))
      override lazy val availableBalanceForReceive: MilliSatoshi = maxOf(testAvailableBalanceForReceive, MilliSatoshi(0))
    }

  def randomExtendedPrivateKey: ExtendedPrivateKey = DeterministicWallet.generate(randomBytes32)

  val (priv_a, priv_b, priv_c, priv_d, priv_e) = (TestConstants.Alice.keyManager.nodeKey, TestConstants.Bob.keyManager.nodeKey, randomExtendedPrivateKey, randomExtendedPrivateKey, randomExtendedPrivateKey)
  val (a, b, c, d, e) = (priv_a.publicKey, priv_b.publicKey, priv_c.publicKey, priv_d.publicKey, priv_e.publicKey)
  val sig = Crypto.sign(Crypto.sha256(ByteVector.empty), priv_a.privateKey)
  val defaultChannelUpdate = ChannelUpdate(sig, Block.RegtestGenesisBlock.hash, ShortChannelId(0), 0, 1, 0, CltvExpiryDelta(0), MilliSatoshi(42000), MilliSatoshi(0), 0, Some(MilliSatoshi(500000000L)))
  val channelUpdate_ab = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(1), cltvExpiryDelta = CltvExpiryDelta(4), feeBaseMsat = MilliSatoshi(642000), feeProportionalMillionths = 7)
  val channelUpdate_bc = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(2), cltvExpiryDelta = CltvExpiryDelta(5), feeBaseMsat = MilliSatoshi(153000), feeProportionalMillionths = 4)
  val channelUpdate_cd = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(3), cltvExpiryDelta = CltvExpiryDelta(10), feeBaseMsat = MilliSatoshi(60000), feeProportionalMillionths = 1)
  val channelUpdate_de = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(4), cltvExpiryDelta = CltvExpiryDelta(7), feeBaseMsat = MilliSatoshi(766000), feeProportionalMillionths = 10)

  // simple route a -> b -> c -> d -> e

  val hops =
    Hop(a, b, channelUpdate_ab) ::
      Hop(b, c, channelUpdate_bc) ::
      Hop(c, d, channelUpdate_cd) ::
      Hop(d, e, channelUpdate_de) :: Nil

  val finalAmountMsat = MilliSatoshi(42000000L)
  val currentBlockCount = 420000
  val finalExpiry = CltvExpiry(currentBlockCount) + Channel.MIN_CLTV_EXPIRY_DELTA
  val paymentPreimage = randomBytes32
  val paymentHash = Crypto.sha256(paymentPreimage)

  val expiry_de = CltvExpiry(currentBlockCount) + Channel.MIN_CLTV_EXPIRY_DELTA
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
