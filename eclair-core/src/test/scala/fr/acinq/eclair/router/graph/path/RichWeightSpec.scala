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

package fr.acinq.eclair.router.graph.path

import fr.acinq.bitcoin.scalacompat.{Block, ByteVector64, Crypto, SatoshiLong}
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.eclair.db.NetworkDbSpec.generatePubkeyHigherThan
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.{BlockHeight, CltvExpiryDelta, MilliSatoshi, MilliSatoshiLong, ShortChannelId, randomBytes32, randomKey}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.router.Router.PublicChannel
import fr.acinq.eclair.router.graph.path
import fr.acinq.eclair.router.graph.structure.GraphEdge
import fr.acinq.eclair.wire.protocol.ChannelUpdate
import org.scalatest.funsuite.AnyFunSuite

class RichWeightSpec extends AnyFunSuite {

  private val NO_WEIGHT_RATIOS: WeightRatios = path.WeightRatios(1, 0, 0, 0, RelayFees(0 msat, 0))

  private val HEURISTICS_CONSTANTS_TYPICAL = HeuristicsConstants(
    lockedFundsRisk = 0.0,
    failureCost = RelayFees(1000 msat, 500),
    hopCost = RelayFees(0 msat, 0),
    useLogProbability = false,
  )

  private val HEURISTICS_CONSTANTS_HIGH_FAILURE_COST = HeuristicsConstants(
    lockedFundsRisk = 0.0,
    failureCost = RelayFees(10000 msat, 1000),
    hopCost = RelayFees(0 msat, 0),
    useLogProbability = true,
  )

  private val HEURISTICS_CONSTANTS_HIGH_RISK = HeuristicsConstants(
    lockedFundsRisk = 1e-7,
    failureCost = RelayFees(0 msat, 0),
    hopCost = RelayFees(0 msat, 0),
    useLogProbability = true,
  )

  test("construct RichWeight from edge and WeightRatios") {
    val key1 = randomKey()
    val senderKey = generatePubkeyHigherThan(key1)
    val edge = createGraphEdge(senderKey)
    val prevWeight = new RichWeight(5 msat, length = 2, CltvExpiryDelta(5), successProbability = 0.99, fees = 10 msat, virtualFees = 0 msat, weight = 10)
    val currentBlockHeight = new BlockHeight(7)
    val totalAmount = 1000 msat
    val fee = 10 msat
    val totalCltv = CltvExpiryDelta(40)
    val weightRatios = NO_WEIGHT_RATIOS
    val totalFees = 100 msat
    val richWeight: RichWeight = RichWeight(senderKey.publicKey, edge, prevWeight, currentBlockHeight, totalAmount, fee, totalFees, totalCltv, weightRatios)

    assert(richWeight.toString == "RichWeight(1000 msat,3,CltvExpiryDelta(40),1.0,100 msat,0 msat,20.0)")
    assert(richWeight.weight == 20.0)
  }

  test("construct RichWeight from edge and HeuristicsConstants") {

    val key1 = randomKey()
    val senderKey = generatePubkeyHigherThan(key1)
    val edge = createGraphEdge(senderKey)
    val prevWeight = new RichWeight(5 msat, length = 2, CltvExpiryDelta(5), successProbability = 0.99, fees = 10 msat, virtualFees = 0 msat, weight = 10)
    val totalAmount = 1000 msat
    val fee = 10 msat
    val cltv = CltvExpiryDelta(2)
    val totalCltv = CltvExpiryDelta(40)
    val totalFees = 100 msat

    val richWeight1: RichWeight = RichWeight(edge, prevWeight, totalAmount, fee, totalFees, cltv, totalCltv, HEURISTICS_CONSTANTS_TYPICAL)
    val richWeight2: RichWeight = RichWeight(edge, prevWeight, totalAmount, fee, totalFees, cltv, totalCltv, HEURISTICS_CONSTANTS_HIGH_FAILURE_COST)
    val richWeight3: RichWeight = RichWeight(edge, prevWeight, totalAmount, fee, totalFees, cltv, totalCltv, HEURISTICS_CONSTANTS_HIGH_RISK)

    assert(richWeight1.toString == "RichWeight(1000 msat,3,CltvExpiryDelta(40),0.9899999505,100 msat,0 msat,1110.1010606060631)")
    assert(richWeight2.toString == "RichWeight(1000 msat,3,CltvExpiryDelta(40),0.9899999505,100 msat,0 msat,20.000500050012793)")
    assert(richWeight3.toString == "RichWeight(1000 msat,3,CltvExpiryDelta(40),0.9899999505,100 msat,0 msat,20.0002)")
  }

  private def createGraphEdge(key2: PrivateKey): GraphEdge = {
    val feeBaseMsat = 50000 msat
    val key1 = randomKey()

    val channelUpdate: ChannelUpdate = createChannelUpdate(feeBaseMsat, key1, key2)

    val transactionId = randomBytes32()
    val channelAnnouncement = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(1),
      key1.publicKey, key2.publicKey, key1.publicKey, key2.publicKey, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val publicChannel = PublicChannel(channelAnnouncement, transactionId, 100000 sat, None, None, None)

    GraphEdge(channelUpdate, publicChannel)
  }

  private def createChannelUpdate(feeBaseMsat: MilliSatoshi, key1: Crypto.PrivateKey, key2: Crypto.PrivateKey): ChannelUpdate = {
    Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, key1, key2.publicKey,
      ShortChannelId(42), CltvExpiryDelta(5), 7000000 msat, feeBaseMsat, 100, 500000000L msat)
  }
}
