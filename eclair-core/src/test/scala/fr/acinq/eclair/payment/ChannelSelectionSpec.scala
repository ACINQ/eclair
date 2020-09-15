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

import fr.acinq.bitcoin.Block
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshi, ShortChannelId, randomKey}
import org.scalatest.funsuite.AnyFunSuite

class ChannelSelectionSpec extends AnyFunSuite {

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  /**
   * This is just a simplified helper function with random values for fields we are not using here
   */
  def dummyUpdate(shortChannelId: ShortChannelId, cltvExpiryDelta: CltvExpiryDelta, htlcMinimumMsat: MilliSatoshi, feeBaseMsat: MilliSatoshi, feeProportionalMillionths: Long, htlcMaximumMsat: MilliSatoshi, enable: Boolean = true) =
    Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, randomKey, randomKey.publicKey, shortChannelId, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaximumMsat, enable)

//  test("channel selection") {
//    val onionPayload = RelayLegacyPayload(ShortChannelId(12345), 998900 msat, CltvExpiry(60))
//    val relayPayload = IncomingPacket.ChannelRelayPacket(
//      add = UpdateAddHtlc(randomBytes32, 42, 1000000 msat, randomBytes32, CltvExpiry(70), TestConstants.emptyOnionPacket),
//      payload = onionPayload,
//      nextPacket = TestConstants.emptyOnionPacket // just a placeholder
//    )
//
//    val (a, b) = (randomKey.publicKey, randomKey.publicKey)
//    val channelUpdate = dummyUpdate(ShortChannelId(12345), CltvExpiryDelta(10), 100 msat, 1000 msat, 100, 10000000 msat, true)
//
//    val channelUpdates = Map(
//      ShortChannelId(11111) -> OutgoingChannel(a, channelUpdate, makeCommitments(ByteVector32.Zeroes, 100000000 msat)),
//      ShortChannelId(12345) -> OutgoingChannel(a, channelUpdate, makeCommitments(ByteVector32.Zeroes, 20000000 msat)),
//      ShortChannelId(22222) -> OutgoingChannel(a, channelUpdate, makeCommitments(ByteVector32.Zeroes, 10000000 msat)),
//      ShortChannelId(33333) -> OutgoingChannel(a, channelUpdate, makeCommitments(ByteVector32.Zeroes, 100000 msat)),
//      ShortChannelId(44444) -> OutgoingChannel(b, channelUpdate, makeCommitments(ByteVector32.Zeroes, 1000000 msat))
//    )
//
//    val node2channels = mutable.MultiDict.empty[PublicKey, ShortChannelId]
//    node2channels.addAll(
//      (a, ShortChannelId(12345)) ::
//        (a, ShortChannelId(11111)) ::
//        (a, ShortChannelId(22222)) ::
//        (a, ShortChannelId(33333)) ::
//        (b, ShortChannelId(44444)) :: Nil)
//
//    // select the channel to the same node, with the lowest balance but still high enough to handle the payment
//    assert(selectPreferredChannel(ActorRef.noSender, relayPayload, channelUpdates, node2channels, Seq.empty) === Some(ShortChannelId(22222)))
//    // select 2nd-to-best channel
//    assert(selectPreferredChannel(ActorRef.noSender, relayPayload, channelUpdates, node2channels, Seq(ShortChannelId(22222))) === Some(ShortChannelId(12345)))
//    // select 3rd-to-best channel
//    assert(selectPreferredChannel(ActorRef.noSender, relayPayload, channelUpdates, node2channels, Seq(ShortChannelId(22222), ShortChannelId(12345))) === Some(ShortChannelId(11111)))
//    // all the suitable channels have been tried
//    assert(selectPreferredChannel(ActorRef.noSender, relayPayload, channelUpdates, node2channels, Seq(ShortChannelId(22222), ShortChannelId(12345), ShortChannelId(11111))) === None)
//    // higher amount payment (have to increased incoming htlc amount for fees to be sufficient)
//    assert(selectPreferredChannel(ActorRef.noSender, relayPayload.copy(add = relayPayload.add.copy(amountMsat = 60000000 msat), payload = onionPayload.copy(amountToForward = 50000000 msat)), channelUpdates, node2channels, Seq.empty) === Some(ShortChannelId(11111)))
//    // lower amount payment
//    assert(selectPreferredChannel(ActorRef.noSender, relayPayload.copy(payload = onionPayload.copy(amountToForward = 1000 msat)), channelUpdates, node2channels, Seq.empty) === Some(ShortChannelId(33333)))
//    // payment too high, no suitable channel found
//    assert(selectPreferredChannel(ActorRef.noSender, relayPayload.copy(payload = onionPayload.copy(amountToForward = 1000000000 msat)), channelUpdates, node2channels, Seq.empty) === Some(ShortChannelId(12345)))
//    // invalid cltv expiry, no suitable channel, we keep the requested one
//    assert(selectPreferredChannel(ActorRef.noSender, relayPayload.copy(payload = onionPayload.copy(outgoingCltv = CltvExpiry(40))), channelUpdates, node2channels, Seq.empty) === Some(ShortChannelId(12345)))
//  }

}
