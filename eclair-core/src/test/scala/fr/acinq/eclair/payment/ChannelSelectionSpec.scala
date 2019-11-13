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

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, ByteVector32}
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, CMD_FAIL_HTLC, Upstream}
import fr.acinq.eclair.payment.PaymentPacketSpec.makeCommitments
import fr.acinq.eclair.payment.Relayer.{OutgoingChannel, RelayFailure, RelaySuccess}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.Onion.RelayLegacyPayload
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, LongToBtcAmount, MilliSatoshi, ShortChannelId, TestConstants, randomBytes32, randomKey}
import org.scalatest.FunSuite

import scala.collection.mutable

class ChannelSelectionSpec extends FunSuite {

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  /**
   * This is just a simplified helper function with random values for fields we are not using here
   */
  def dummyUpdate(shortChannelId: ShortChannelId, cltvExpiryDelta: CltvExpiryDelta, htlcMinimumMsat: MilliSatoshi, feeBaseMsat: MilliSatoshi, feeProportionalMillionths: Long, htlcMaximumMsat: MilliSatoshi, enable: Boolean = true) =
    Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, randomKey, randomKey.publicKey, shortChannelId, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaximumMsat, enable)

  test("convert to CMD_FAIL_HTLC/CMD_ADD_HTLC") {
    val onionPayload = RelayLegacyPayload(ShortChannelId(12345), 998900 msat, CltvExpiry(60))
    val relayPayload = IncomingPacket.ChannelRelayPacket(
      add = UpdateAddHtlc(randomBytes32, 42, 1000000 msat, randomBytes32, CltvExpiry(70), TestConstants.emptyOnionPacket),
      payload = onionPayload,
      nextPacket = TestConstants.emptyOnionPacket // just a placeholder
    )

    val channelUpdate = dummyUpdate(ShortChannelId(12345), CltvExpiryDelta(10), 100 msat, 1000 msat, 100, 10000000 msat, true)

    // nominal case
    assert(Relayer.relayOrFail(relayPayload, Some(channelUpdate)) === RelaySuccess(ShortChannelId(12345), CMD_ADD_HTLC(relayPayload.payload.amountToForward, relayPayload.add.paymentHash, relayPayload.payload.outgoingCltv, relayPayload.nextPacket, Upstream.Relayed(relayPayload.add), commit = true)))
    // no channel_update
    assert(Relayer.relayOrFail(relayPayload, channelUpdate_opt = None) === RelayFailure(CMD_FAIL_HTLC(relayPayload.add.id, Right(UnknownNextPeer), commit = true)))
    // channel disabled
    val channelUpdate_disabled = channelUpdate.copy(channelFlags = Announcements.makeChannelFlags(isNode1 = true, enable = false))
    assert(Relayer.relayOrFail(relayPayload, Some(channelUpdate_disabled)) === RelayFailure(CMD_FAIL_HTLC(relayPayload.add.id, Right(ChannelDisabled(channelUpdate_disabled.messageFlags, channelUpdate_disabled.channelFlags, channelUpdate_disabled)), commit = true)))
    // amount too low
    val relayPayload_toolow = relayPayload.copy(payload = onionPayload.copy(amountToForward = 99 msat))
    assert(Relayer.relayOrFail(relayPayload_toolow, Some(channelUpdate)) === RelayFailure(CMD_FAIL_HTLC(relayPayload.add.id, Right(AmountBelowMinimum(relayPayload_toolow.payload.amountToForward, channelUpdate)), commit = true)))
    // incorrect cltv expiry
    val relayPayload_incorrectcltv = relayPayload.copy(payload = onionPayload.copy(outgoingCltv = CltvExpiry(42)))
    assert(Relayer.relayOrFail(relayPayload_incorrectcltv, Some(channelUpdate)) === RelayFailure(CMD_FAIL_HTLC(relayPayload.add.id, Right(IncorrectCltvExpiry(relayPayload_incorrectcltv.payload.outgoingCltv, channelUpdate)), commit = true)))
    // insufficient fee
    val relayPayload_insufficientfee = relayPayload.copy(payload = onionPayload.copy(amountToForward = 998910 msat))
    assert(Relayer.relayOrFail(relayPayload_insufficientfee, Some(channelUpdate)) === RelayFailure(CMD_FAIL_HTLC(relayPayload.add.id, Right(FeeInsufficient(relayPayload_insufficientfee.add.amountMsat, channelUpdate)), commit = true)))
    // note that a generous fee is ok!
    val relayPayload_highfee = relayPayload.copy(payload = onionPayload.copy(amountToForward = 900000 msat))
    assert(Relayer.relayOrFail(relayPayload_highfee, Some(channelUpdate)) === RelaySuccess(ShortChannelId(12345), CMD_ADD_HTLC(relayPayload_highfee.payload.amountToForward, relayPayload_highfee.add.paymentHash, relayPayload_highfee.payload.outgoingCltv, relayPayload_highfee.nextPacket, Upstream.Relayed(relayPayload.add), commit = true)))
  }

  test("channel selection") {
    val onionPayload = RelayLegacyPayload(ShortChannelId(12345), 998900 msat, CltvExpiry(60))
    val relayPayload = IncomingPacket.ChannelRelayPacket(
      add = UpdateAddHtlc(randomBytes32, 42, 1000000 msat, randomBytes32, CltvExpiry(70), TestConstants.emptyOnionPacket),
      payload = onionPayload,
      nextPacket = TestConstants.emptyOnionPacket // just a placeholder
    )

    val (a, b) = (randomKey.publicKey, randomKey.publicKey)
    val channelUpdate = dummyUpdate(ShortChannelId(12345), CltvExpiryDelta(10), 100 msat, 1000 msat, 100, 10000000 msat, true)

    val channelUpdates = Map(
      ShortChannelId(11111) -> OutgoingChannel(a, channelUpdate, makeCommitments(ByteVector32.Zeroes, 100000000 msat)),
      ShortChannelId(12345) -> OutgoingChannel(a, channelUpdate, makeCommitments(ByteVector32.Zeroes, 20000000 msat)),
      ShortChannelId(22222) -> OutgoingChannel(a, channelUpdate, makeCommitments(ByteVector32.Zeroes, 10000000 msat)),
      ShortChannelId(33333) -> OutgoingChannel(a, channelUpdate, makeCommitments(ByteVector32.Zeroes, 100000 msat)),
      ShortChannelId(44444) -> OutgoingChannel(b, channelUpdate, makeCommitments(ByteVector32.Zeroes, 1000000 msat))
    )

    val node2channels = new mutable.HashMap[PublicKey, mutable.Set[ShortChannelId]] with mutable.MultiMap[PublicKey, ShortChannelId]
    node2channels.put(a, mutable.Set(ShortChannelId(12345), ShortChannelId(11111), ShortChannelId(22222), ShortChannelId(33333)))
    node2channels.put(b, mutable.Set(ShortChannelId(44444)))

    // select the channel to the same node, with the lowest balance but still high enough to handle the payment
    assert(Relayer.selectPreferredChannel(relayPayload, channelUpdates, node2channels, Seq.empty) === Some(ShortChannelId(22222)))
    // select 2nd-to-best channel
    assert(Relayer.selectPreferredChannel(relayPayload, channelUpdates, node2channels, Seq(ShortChannelId(22222))) === Some(ShortChannelId(12345)))
    // select 3rd-to-best channel
    assert(Relayer.selectPreferredChannel(relayPayload, channelUpdates, node2channels, Seq(ShortChannelId(22222), ShortChannelId(12345))) === Some(ShortChannelId(11111)))
    // all the suitable channels have been tried
    assert(Relayer.selectPreferredChannel(relayPayload, channelUpdates, node2channels, Seq(ShortChannelId(22222), ShortChannelId(12345), ShortChannelId(11111))) === None)
    // higher amount payment (have to increased incoming htlc amount for fees to be sufficient)
    assert(Relayer.selectPreferredChannel(relayPayload.copy(add = relayPayload.add.copy(amountMsat = 60000000 msat), payload = onionPayload.copy(amountToForward = 50000000 msat)), channelUpdates, node2channels, Seq.empty) === Some(ShortChannelId(11111)))
    // lower amount payment
    assert(Relayer.selectPreferredChannel(relayPayload.copy(payload = onionPayload.copy(amountToForward = 1000 msat)), channelUpdates, node2channels, Seq.empty) === Some(ShortChannelId(33333)))
    // payment too high, no suitable channel found
    assert(Relayer.selectPreferredChannel(relayPayload.copy(payload = onionPayload.copy(amountToForward = 1000000000 msat)), channelUpdates, node2channels, Seq.empty) === Some(ShortChannelId(12345)))
    // invalid cltv expiry, no suitable channel, we keep the requested one
    assert(Relayer.selectPreferredChannel(relayPayload.copy(payload = onionPayload.copy(outgoingCltv = CltvExpiry(40))), channelUpdates, node2channels, Seq.empty) === Some(ShortChannelId(12345)))
  }

}
