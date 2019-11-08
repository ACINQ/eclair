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
import fr.acinq.eclair.payment.send.PaymentLifecycle._
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.wire.Onion.{FinalLegacyPayload, FinalTlvPayload, PerHopPayload, RelayLegacyPayload}
import fr.acinq.eclair.wire.OnionTlv.{AmountToForward, OutgoingCltv}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, LongToBtcAmount, MilliSatoshi, ShortChannelId, TestConstants, nodeFee, randomBytes32}
import org.scalatest.{BeforeAndAfterAll, FunSuite}
import scodec.bits.ByteVector

/**
 * Created by PM on 31/05/2016.
 */

class HtlcGenerationSpec extends FunSuite with BeforeAndAfterAll {

  test("compute fees") {
    val feeBaseMsat = 150000 msat
    val feeProportionalMillionth = 4L
    val htlcAmountMsat = 42000000 msat
    // spec: fee-base-msat + htlc-amount-msat * fee-proportional-millionths / 1000000
    val ref = feeBaseMsat + htlcAmountMsat * feeProportionalMillionth / 1000000
    val fee = nodeFee(feeBaseMsat, feeProportionalMillionth, htlcAmountMsat)
    assert(ref === fee)
  }

  import HtlcGenerationSpec._

  test("compute payloads with fees and expiry delta") {
    val (firstAmountMsat, firstExpiry, payloads) = buildPayloads(hops.drop(1), FinalLegacyPayload(finalAmountMsat, finalExpiry))
    val expectedPayloads = Seq[PerHopPayload](
      RelayLegacyPayload(channelUpdate_bc.shortChannelId, amount_bc, expiry_bc),
      RelayLegacyPayload(channelUpdate_cd.shortChannelId, amount_cd, expiry_cd),
      RelayLegacyPayload(channelUpdate_de.shortChannelId, amount_de, expiry_de),
      FinalLegacyPayload(finalAmountMsat, finalExpiry))

    assert(firstAmountMsat === amount_ab)
    assert(firstExpiry === expiry_ab)
    assert(payloads === expectedPayloads)
  }

  def testBuildOnion(legacy: Boolean): Unit = {
    val finalPayload = if (legacy) {
      FinalLegacyPayload(finalAmountMsat, finalExpiry)
    } else {
      FinalTlvPayload(TlvStream[OnionTlv](AmountToForward(finalAmountMsat), OutgoingCltv(finalExpiry)))
    }
    val (_, _, payloads) = buildPayloads(hops.drop(1), finalPayload)
    val nodes = hops.map(_.nextNodeId)
    val PacketAndSecrets(packet_b, _) = buildOnion(nodes, payloads, paymentHash)
    assert(packet_b.payload.length === Sphinx.PaymentPacket.PayloadLength)

    // let's peel the onion
    testPeelOnion(packet_b)
  }

  def testPeelOnion(packet_b: OnionRoutingPacket): Unit = {
    val Right(DecryptedPacket(bin_b, packet_c, _)) = Sphinx.PaymentPacket.peel(priv_b.privateKey, paymentHash, packet_b)
    val payload_b = OnionCodecs.relayPerHopPayloadCodec.decode(bin_b.toBitVector).require.value
    assert(packet_c.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_b.amountToForward === amount_bc)
    assert(payload_b.outgoingCltv === expiry_bc)

    val Right(DecryptedPacket(bin_c, packet_d, _)) = Sphinx.PaymentPacket.peel(priv_c.privateKey, paymentHash, packet_c)
    val payload_c = OnionCodecs.relayPerHopPayloadCodec.decode(bin_c.toBitVector).require.value
    assert(packet_d.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_c.amountToForward === amount_cd)
    assert(payload_c.outgoingCltv === expiry_cd)

    val Right(DecryptedPacket(bin_d, packet_e, _)) = Sphinx.PaymentPacket.peel(priv_d.privateKey, paymentHash, packet_d)
    val payload_d = OnionCodecs.relayPerHopPayloadCodec.decode(bin_d.toBitVector).require.value
    assert(packet_e.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_d.amountToForward === amount_de)
    assert(payload_d.outgoingCltv === expiry_de)

    val Right(DecryptedPacket(bin_e, packet_random, _)) = Sphinx.PaymentPacket.peel(priv_e.privateKey, paymentHash, packet_e)
    val payload_e = OnionCodecs.finalPerHopPayloadCodec.decode(bin_e.toBitVector).require.value
    assert(packet_random.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_e.amount === finalAmountMsat)
    assert(payload_e.expiry === finalExpiry)
  }

  test("build onion with final legacy payload") {
    testBuildOnion(legacy = true)
  }

  test("build onion with final tlv payload") {
    testBuildOnion(legacy = false)
  }

  test("build a command including the onion") {
    val (add, _) = buildCommand(UUID.randomUUID, paymentHash, hops, FinalLegacyPayload(finalAmountMsat, finalExpiry))
    assert(add.amount > finalAmountMsat)
    assert(add.cltvExpiry === finalExpiry + channelUpdate_de.cltvExpiryDelta + channelUpdate_cd.cltvExpiryDelta + channelUpdate_bc.cltvExpiryDelta)
    assert(add.paymentHash === paymentHash)
    assert(add.onion.payload.length === Sphinx.PaymentPacket.PayloadLength)

    // let's peel the onion
    testPeelOnion(add.onion)
  }

  test("build a command with no hops") {
    val (add, _) = buildCommand(UUID.randomUUID(), paymentHash, hops.take(1), FinalLegacyPayload(finalAmountMsat, finalExpiry))
    assert(add.amount === finalAmountMsat)
    assert(add.cltvExpiry === finalExpiry)
    assert(add.paymentHash === paymentHash)
    assert(add.onion.payload.length === Sphinx.PaymentPacket.PayloadLength)

    // let's peel the onion
    val Right(DecryptedPacket(bin_b, packet_random, _)) = Sphinx.PaymentPacket.peel(priv_b.privateKey, paymentHash, add.onion)
    val payload_b = OnionCodecs.relayPerHopPayloadCodec.decode(bin_b.toBitVector).require.value
    assert(packet_random.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_b.amountToForward === finalAmountMsat)
    assert(payload_b.outgoingCltv === finalExpiry)
  }

}

object HtlcGenerationSpec {

  def makeCommitments(channelId: ByteVector32, testAvailableBalanceForSend: MilliSatoshi = 50000000 msat, testAvailableBalanceForReceive: MilliSatoshi = 50000000 msat) =
    new Commitments(ChannelVersion.STANDARD, null, null, 0.toByte, null, null, null, null, 0, 0, Map.empty, null, null, null, channelId) {
      override lazy val availableBalanceForSend: MilliSatoshi = testAvailableBalanceForSend.max(0 msat)
      override lazy val availableBalanceForReceive: MilliSatoshi = testAvailableBalanceForReceive.max(0 msat)
    }

  def randomExtendedPrivateKey: ExtendedPrivateKey = DeterministicWallet.generate(randomBytes32)

  val (priv_a, priv_b, priv_c, priv_d, priv_e) = (TestConstants.Alice.keyManager.nodeKey, TestConstants.Bob.keyManager.nodeKey, randomExtendedPrivateKey, randomExtendedPrivateKey, randomExtendedPrivateKey)
  val (a, b, c, d, e) = (priv_a.publicKey, priv_b.publicKey, priv_c.publicKey, priv_d.publicKey, priv_e.publicKey)
  val sig = Crypto.sign(Crypto.sha256(ByteVector.empty), priv_a.privateKey)
  val defaultChannelUpdate = ChannelUpdate(sig, Block.RegtestGenesisBlock.hash, ShortChannelId(0), 0, 1, 0, CltvExpiryDelta(0), 42000 msat, 0 msat, 0, Some(500000000 msat))
  val channelUpdate_ab = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(1), cltvExpiryDelta = CltvExpiryDelta(4), feeBaseMsat = 642000 msat, feeProportionalMillionths = 7)
  val channelUpdate_bc = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(2), cltvExpiryDelta = CltvExpiryDelta(5), feeBaseMsat = 153000 msat, feeProportionalMillionths = 4)
  val channelUpdate_cd = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(3), cltvExpiryDelta = CltvExpiryDelta(10), feeBaseMsat = 60000 msat, feeProportionalMillionths = 1)
  val channelUpdate_de = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(4), cltvExpiryDelta = CltvExpiryDelta(7), feeBaseMsat = 766000 msat, feeProportionalMillionths = 10)

  // simple route a -> b -> c -> d -> e

  val hops =
    Hop(a, b, channelUpdate_ab) ::
      Hop(b, c, channelUpdate_bc) ::
      Hop(c, d, channelUpdate_cd) ::
      Hop(d, e, channelUpdate_de) :: Nil

  val finalAmountMsat = 42000000 msat
  val currentBlockCount = 400000
  val finalExpiry = CltvExpiry(currentBlockCount) + Channel.MIN_CLTV_EXPIRY_DELTA
  val paymentPreimage = randomBytes32
  val paymentHash = Crypto.sha256(paymentPreimage)

  val expiry_de = finalExpiry
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
