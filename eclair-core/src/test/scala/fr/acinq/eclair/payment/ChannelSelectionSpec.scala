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

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, CMD_FAIL_HTLC}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.Relayer.{OutgoingChannel, RelayPayload}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{ShortChannelId, randomBytes, randomKey}
import org.scalatest.FunSuite

import scala.collection.mutable

class ChannelSelectionSpec extends FunSuite {

  /**
    * This is just a simplified helper function with random values for fields we are not using here
    */
  def dummyUpdate(shortChannelId: ShortChannelId, cltvExpiryDelta: Int, htlcMinimumMsat: Long, feeBaseMsat: Long, feeProportionalMillionths: Long, htlcMaximumMsat: Long, enable: Boolean = true) =
    Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, randomKey, randomKey.publicKey, shortChannelId, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaximumMsat, enable)

  test("handle relay") {
    val relayPayload = RelayPayload(
      add = UpdateAddHtlc(randomBytes(32), 42, 1000000, randomBytes(32), 70, ""),
      payload = PerHopPayload(ShortChannelId(12345), amtToForward = 998900, outgoingCltvValue = 60),
      nextPacket = Sphinx.LAST_PACKET // just a placeholder
    )

    val channelUpdate = dummyUpdate(ShortChannelId(12345), 10, 100, 1000, 100, 10000000, true)

    implicit val log = akka.event.NoLogging

    // nominal case
    assert(Relayer.handleRelay(relayPayload, Some(channelUpdate)) === Right(CMD_ADD_HTLC(relayPayload.payload.amtToForward, relayPayload.add.paymentHash, relayPayload.payload.outgoingCltvValue, relayPayload.nextPacket.serialize, upstream_opt = Some(relayPayload.add), commit = true, redirected = false)))
    // redirected to preferred channel
    assert(Relayer.handleRelay(relayPayload, Some(channelUpdate.copy(shortChannelId = ShortChannelId(1111)))) === Right(CMD_ADD_HTLC(relayPayload.payload.amtToForward, relayPayload.add.paymentHash, relayPayload.payload.outgoingCltvValue, relayPayload.nextPacket.serialize, upstream_opt = Some(relayPayload.add), commit = true, redirected = true)))
    // no channel_update
    assert(Relayer.handleRelay(relayPayload, channelUpdate_opt = None) === Left(CMD_FAIL_HTLC(relayPayload.add.id, Right(UnknownNextPeer), commit = true)))
    // channel disabled
    val channelUpdate_disabled = channelUpdate.copy(channelFlags = Announcements.makeChannelFlags(true, enable = false))
    assert(Relayer.handleRelay(relayPayload, Some(channelUpdate_disabled)) === Left(CMD_FAIL_HTLC(relayPayload.add.id, Right(ChannelDisabled(channelUpdate_disabled.messageFlags, channelUpdate_disabled.channelFlags, channelUpdate_disabled)), commit = true)))
    // amount too low
    val relayPayload_toolow = relayPayload.copy(payload = relayPayload.payload.copy(amtToForward = 99))
    assert(Relayer.handleRelay(relayPayload_toolow, Some(channelUpdate)) === Left(CMD_FAIL_HTLC(relayPayload.add.id, Right(AmountBelowMinimum(relayPayload_toolow.payload.amtToForward, channelUpdate)), commit = true)))
    // incorrect cltv expiry
    val relayPayload_incorrectcltv = relayPayload.copy(payload = relayPayload.payload.copy(outgoingCltvValue = 42))
    assert(Relayer.handleRelay(relayPayload_incorrectcltv, Some(channelUpdate)) === Left(CMD_FAIL_HTLC(relayPayload.add.id, Right(IncorrectCltvExpiry(relayPayload_incorrectcltv.payload.outgoingCltvValue, channelUpdate)), commit = true)))
    // insufficient fee
    val relayPayload_insufficientfee = relayPayload.copy(payload = relayPayload.payload.copy(amtToForward = 998910))
    assert(Relayer.handleRelay(relayPayload_insufficientfee, Some(channelUpdate)) === Left(CMD_FAIL_HTLC(relayPayload.add.id, Right(FeeInsufficient(relayPayload_insufficientfee.add.amountMsat, channelUpdate)), commit = true)))
    // note that a generous fee is ok!
    val relayPayload_highfee = relayPayload.copy(payload = relayPayload.payload.copy(amtToForward = 900000))
    assert(Relayer.handleRelay(relayPayload_highfee, Some(channelUpdate)) === Right(CMD_ADD_HTLC(relayPayload_highfee.payload.amtToForward, relayPayload_highfee.add.paymentHash, relayPayload_highfee.payload.outgoingCltvValue, relayPayload_highfee.nextPacket.serialize, upstream_opt = Some(relayPayload.add), commit = true, redirected = false)))
  }

  test("relay channel selection") {

    val relayPayload = RelayPayload(
      add = UpdateAddHtlc(randomBytes(32), 42, 1000000, randomBytes(32), 70, ""),
      payload = PerHopPayload(ShortChannelId(12345), amtToForward = 998900, outgoingCltvValue = 60),
      nextPacket = Sphinx.LAST_PACKET // just a placeholder
    )

    val (a, b) = (randomKey.publicKey, randomKey.publicKey)
    val channelUpdate = dummyUpdate(ShortChannelId(12345), 10, 100, 1000, 100, 10000000, true)

    val channelUpdates = Map(
      ShortChannelId(11111) -> OutgoingChannel(a, channelUpdate, 100000000),
      ShortChannelId(12345) -> OutgoingChannel(a, channelUpdate, 20000000),
      ShortChannelId(22222) -> OutgoingChannel(a, channelUpdate, 10000000),
      ShortChannelId(33333) -> OutgoingChannel(a, channelUpdate, 100000),
      ShortChannelId(44444) -> OutgoingChannel(b, channelUpdate, 1000000)
    )

    val node2channels = new mutable.HashMap[PublicKey, mutable.Set[ShortChannelId]] with mutable.MultiMap[PublicKey, ShortChannelId]
    node2channels.put(a, mutable.Set(ShortChannelId(12345), ShortChannelId(11111), ShortChannelId(22222), ShortChannelId(33333)))
    node2channels.put(b, mutable.Set(ShortChannelId(44444)))

    implicit val log = akka.event.NoLogging

    import com.softwaremill.quicklens._

    // select the channel to the same node, with the lowest balance but still high enough to handle the payment
    assert(Relayer.selectPreferredChannel(relayPayload, channelUpdates, node2channels) === ShortChannelId(22222))
    // higher amount payment (have to increased incoming htlc amount for fees to be sufficient)
    assert(Relayer.selectPreferredChannel(relayPayload.modify(_.add.amountMsat).setTo(60000000).modify(_.payload.amtToForward).setTo(50000000), channelUpdates, node2channels) === ShortChannelId(11111))
    // lower amount payment
    assert(Relayer.selectPreferredChannel(relayPayload.modify(_.payload.amtToForward).setTo(1000), channelUpdates, node2channels) === ShortChannelId(33333))
    // payment too high, no suitable channel, we keep the requested one
    assert(Relayer.selectPreferredChannel(relayPayload.modify(_.payload.amtToForward).setTo(1000000000), channelUpdates, node2channels) === ShortChannelId(12345))
    // invalid cltv expiry, no suitable channel, we keep the requested one
    assert(Relayer.selectPreferredChannel(relayPayload.modify(_.payload.outgoingCltvValue).setTo(40), channelUpdates, node2channels) === ShortChannelId(12345))

  }

}
