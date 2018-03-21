/*
 * Copyright 2018 ACINQ SAS
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

import fr.acinq.bitcoin.DeterministicWallet.ExtendedPrivateKey
import fr.acinq.bitcoin.{BinaryData, Block, Crypto, DeterministicWallet}
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.{PacketAndSecrets, ParsedPacket}
import fr.acinq.eclair.payment.PaymentLifecycle._
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.wire.{ChannelUpdate, LightningMessageCodecs, PerHopPayload}
import fr.acinq.eclair.{ShortChannelId, TestConstants, nodeFee, randomBytes}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import scodec.bits.BitVector

import scala.util.Success

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
    assert(packet_b.serialize.size === Sphinx.PacketLength)

    // let's peel the onion
    val Success(ParsedPacket(bin_b, packet_c, _)) = Sphinx.parsePacket(priv_b.privateKey, paymentHash, packet_b.serialize)
    val payload_b = LightningMessageCodecs.perHopPayloadCodec.decode(BitVector(bin_b.data)).require.value
    assert(packet_c.serialize.size === Sphinx.PacketLength)
    assert(payload_b.amtToForward === amount_bc)
    assert(payload_b.outgoingCltvValue === expiry_bc)

    val Success(ParsedPacket(bin_c, packet_d, _)) = Sphinx.parsePacket(priv_c.privateKey, paymentHash, packet_c.serialize)
    val payload_c = LightningMessageCodecs.perHopPayloadCodec.decode(BitVector(bin_c.data)).require.value
    assert(packet_d.serialize.size === Sphinx.PacketLength)
    assert(payload_c.amtToForward === amount_cd)
    assert(payload_c.outgoingCltvValue === expiry_cd)

    val Success(ParsedPacket(bin_d, packet_e, _)) = Sphinx.parsePacket(priv_d.privateKey, paymentHash, packet_d.serialize)
    val payload_d = LightningMessageCodecs.perHopPayloadCodec.decode(BitVector(bin_d.data)).require.value
    assert(packet_e.serialize.size === Sphinx.PacketLength)
    assert(payload_d.amtToForward === amount_de)
    assert(payload_d.outgoingCltvValue === expiry_de)

    val Success(ParsedPacket(bin_e, packet_random, _)) = Sphinx.parsePacket(priv_e.privateKey, paymentHash, packet_e.serialize)
    val payload_e = LightningMessageCodecs.perHopPayloadCodec.decode(BitVector(bin_e.data)).require.value
    assert(packet_random.serialize.size === Sphinx.PacketLength)
    assert(payload_e.amtToForward === finalAmountMsat)
    assert(payload_e.outgoingCltvValue === finalExpiry)
  }

  test("build a command including the onion") {

    val (add, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)

    assert(add.amountMsat > finalAmountMsat)
    assert(add.expiry === finalExpiry + channelUpdate_de.cltvExpiryDelta + channelUpdate_cd.cltvExpiryDelta + channelUpdate_bc.cltvExpiryDelta)
    assert(add.paymentHash === paymentHash)
    assert(add.onion.length === Sphinx.PacketLength)

    // let's peel the onion
    val Success(ParsedPacket(bin_b, packet_c, _)) = Sphinx.parsePacket(priv_b.privateKey, paymentHash, add.onion)
    val payload_b = LightningMessageCodecs.perHopPayloadCodec.decode(BitVector(bin_b.data)).require.value
    assert(packet_c.serialize.size === Sphinx.PacketLength)
    assert(payload_b.amtToForward === amount_bc)
    assert(payload_b.outgoingCltvValue === expiry_bc)

    val Success(ParsedPacket(bin_c, packet_d, _)) = Sphinx.parsePacket(priv_c.privateKey, paymentHash, packet_c.serialize)
    val payload_c = LightningMessageCodecs.perHopPayloadCodec.decode(BitVector(bin_c.data)).require.value
    assert(packet_d.serialize.size === Sphinx.PacketLength)
    assert(payload_c.amtToForward === amount_cd)
    assert(payload_c.outgoingCltvValue === expiry_cd)

    val Success(ParsedPacket(bin_d, packet_e, _)) = Sphinx.parsePacket(priv_d.privateKey, paymentHash, packet_d.serialize)
    val payload_d = LightningMessageCodecs.perHopPayloadCodec.decode(BitVector(bin_d.data)).require.value
    assert(packet_e.serialize.size === Sphinx.PacketLength)
    assert(payload_d.amtToForward === amount_de)
    assert(payload_d.outgoingCltvValue === expiry_de)

    val Success(ParsedPacket(bin_e, packet_random, _)) = Sphinx.parsePacket(priv_e.privateKey, paymentHash, packet_e.serialize)
    val payload_e = LightningMessageCodecs.perHopPayloadCodec.decode(BitVector(bin_e.data)).require.value
    assert(packet_random.serialize.size === Sphinx.PacketLength)
    assert(payload_e.amtToForward === finalAmountMsat)
    assert(payload_e.outgoingCltvValue === finalExpiry)
  }

  test("build a command with no hops") {
    val (add, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops.take(1))

    assert(add.amountMsat === finalAmountMsat)
    assert(add.expiry === finalExpiry)
    assert(add.paymentHash === paymentHash)
    assert(add.onion.size === Sphinx.PacketLength)

    // let's peel the onion
    val Success(ParsedPacket(bin_b, packet_random, _)) = Sphinx.parsePacket(priv_b.privateKey, paymentHash, add.onion)
    val payload_b = LightningMessageCodecs.perHopPayloadCodec.decode(BitVector(bin_b.data)).require.value
    assert(packet_random.serialize.size === Sphinx.PacketLength)
    assert(payload_b.amtToForward === finalAmountMsat)
    assert(payload_b.outgoingCltvValue === finalExpiry)
  }

}

object HtlcGenerationSpec {

  def randomExtendedPrivateKey: ExtendedPrivateKey = DeterministicWallet.generate(randomBytes(32))

  val (priv_a, priv_b, priv_c, priv_d, priv_e) = (TestConstants.Alice.keyManager.nodeKey, TestConstants.Bob.keyManager.nodeKey, randomExtendedPrivateKey, randomExtendedPrivateKey, randomExtendedPrivateKey)
  val (a, b, c, d, e) = (priv_a.publicKey, priv_b.publicKey, priv_c.publicKey, priv_d.publicKey, priv_e.publicKey)
  val sig = Crypto.encodeSignature(Crypto.sign(Crypto.sha256(BinaryData.empty), priv_a.privateKey)) :+ 1.toByte
  val defaultChannelUpdate = ChannelUpdate(sig, Block.RegtestGenesisBlock.hash, ShortChannelId(0), 0, "0000", 0, 42000, 0, 0)
  val channelUpdate_ab = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(1), cltvExpiryDelta = 4, feeBaseMsat = 642000, feeProportionalMillionths = 7)
  val channelUpdate_bc = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(2), cltvExpiryDelta = 5, feeBaseMsat = 153000, feeProportionalMillionths = 4)
  val channelUpdate_cd = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(3), cltvExpiryDelta = 10, feeBaseMsat = 60000, feeProportionalMillionths = 1)
  val channelUpdate_de = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(4), cltvExpiryDelta = 7, feeBaseMsat = 766000, feeProportionalMillionths = 10)

  // simple route a -> b -> c -> d -> e

  val hops =
    Hop(a, b, channelUpdate_ab) ::
      Hop(b, c, channelUpdate_bc) ::
      Hop(c, d, channelUpdate_cd) ::
      Hop(d, e, channelUpdate_de) :: Nil

  val finalAmountMsat = 42000000L
  val currentBlockCount = 420000
  val finalExpiry = currentBlockCount + Channel.MIN_CLTV_EXPIRY
  val paymentPreimage = BinaryData("42" * 32)
  val paymentHash = Crypto.sha256(paymentPreimage)

  val expiry_de = currentBlockCount + Channel.MIN_CLTV_EXPIRY
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
