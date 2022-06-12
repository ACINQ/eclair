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

package fr.acinq.eclair.router.graph

import fr.acinq.eclair.{BlockHeight, CltvExpiryDelta, MilliSatoshi, MilliSatoshiLong, ToMilliSatoshiConversion, ShortChannelId, randomBytes32, randomKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector64, Crypto, SatoshiLong}
import fr.acinq.eclair.db.NetworkDbSpec.generatePubkeyHigherThan
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.router.Router.{AssistedChannel, ChannelMeta, ChannelRelayParams, PrivateChannel, PublicChannel}
import fr.acinq.eclair.router.graph.structure.GraphEdge
import fr.acinq.eclair.wire.protocol.ChannelUpdate
import org.scalatest.funsuite.AnyFunSuite

class GraphEdgeSpec extends AnyFunSuite{

  test("construct from public channel") {

    val feeBaseMsat = 50000 msat
    val feeProportionalMillionths = 100
    val key1 = randomKey()
    val key2 = generatePubkeyHigherThan(key1)
    val channelUpdate: ChannelUpdate = createChannelUpdate(feeBaseMsat, key1, key2, feeProportionalMillionths)

    val transactionId = randomBytes32()
    val channelAnnouncement = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(1),
      key1.publicKey, key2.publicKey, key1.publicKey, key2.publicKey, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val publicChannel = PublicChannel(channelAnnouncement, transactionId, 100000 sat, None, None, None)

    val graphEdge = GraphEdge(channelUpdate, publicChannel)

    assert(graphEdge.capacity == (100000 sat))
    assert(graphEdge.maxHtlcAmount(10000 msat) == (99990000 msat))
    assert(graphEdge.fee(20000 msat ) == feeBaseMsat + (2 msat))
    assert(graphEdge.balance_opt.isEmpty)
  }

  test("construct from private channel") {

    val feeBaseMsat = 50000 msat
    val feeProportionalMillionths = 100
    val key1 = randomKey()
    val key2 = generatePubkeyHigherThan(key1)
    val channelUpdate: ChannelUpdate = createChannelUpdate(feeBaseMsat, key1, key2, feeProportionalMillionths)

    val channelId_ag_private = randomBytes32()
    val scid_ag_private = ShortChannelId(BlockHeight(420000), 5, 0)
    val publicChannel = PrivateChannel(scid_ag_private, channelId_ag_private, key1.publicKey, key2.publicKey, None, None, ChannelMeta(1000 msat, 2000 msat))

    val graphEdge = GraphEdge(channelUpdate, publicChannel)

    assert(graphEdge.capacity == (3 sat))
    assert(graphEdge.maxHtlcAmount(1000 msat) == (0 msat))
    assert(graphEdge.fee(20000 msat ) == feeBaseMsat + (2 msat))
    assert(graphEdge.balance_opt.contains(1000 msat))
  }

  test("construct from assisted channel") {

    val feeBaseMsat = 50000 msat
    val key1 = randomKey()
    val key2 = generatePubkeyHigherThan(key1)
    val feeProportionalMillionths = 100
    val extraHop4 = ExtraHop(key2.publicKey, ShortChannelId(4), feeBaseMsat, feeProportionalMillionths, CltvExpiryDelta(42))
    val assistedChannel = AssistedChannel(key1.publicKey, ChannelRelayParams.FromHint(extraHop4, 100050.sat.toMilliSatoshi))

    val graphEdge = GraphEdge(assistedChannel)

    assert(graphEdge.capacity == (100051 sat))
    assert(graphEdge.maxHtlcAmount(1000 msat) == (100049000 msat))
    assert(graphEdge.fee(20000 msat ) == feeBaseMsat + (2 msat))
    assert(graphEdge.balance_opt.contains(100050000 msat))
  }

  private def createChannelUpdate(feeBaseMsat: MilliSatoshi, key1: Crypto.PrivateKey, key2: Crypto.PrivateKey, feeProportionalMillionths: Long): ChannelUpdate = {
    Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, key1, key2.publicKey,
      ShortChannelId(42), CltvExpiryDelta(5), 7000000 msat, feeBaseMsat, feeProportionalMillionths, 500000000L msat)
  }

}
