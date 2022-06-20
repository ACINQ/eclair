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

import fr.acinq.bitcoin.scalacompat.{Block, ByteVector64, Crypto, Satoshi, SatoshiLong}
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.eclair.db.NetworkDbSpec.generatePubkeyHigherThan
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.{BitcoinDefaultWalletException, BlockHeight, CltvExpiryDelta, MilliSatoshi, MilliSatoshiLong, ShortChannelId, randomBytes32, randomKey}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.router.Router.{ChannelMeta, PublicChannel}
import fr.acinq.eclair.router.graph.path
import fr.acinq.eclair.router.graph.path.Path.NegativeProbability
import fr.acinq.eclair.router.graph.structure.GraphEdge
import fr.acinq.eclair.wire.protocol.ChannelUpdate
import org.scalatest.funsuite.AnyFunSuite
import fr.acinq.eclair.router.graph.path.RichWeightSpec._

import scala.collection.Seq

class RichWeightSpec extends AnyFunSuite {

  test("construct RichWeight from edge and WeightRatios") {
    val key1 = randomKey()
    val senderKey = generatePubkeyHigherThan(key1)

    val edge = createGraphEdge(senderKey, 1000 sat)
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
    val edge = createGraphEdge(senderKey, 100000 sat)
    val prevWeight = new RichWeight(5 msat, length = 2, CltvExpiryDelta(5), successProbability = 0.99, fees = 10 msat, virtualFees = 0 msat, weight = 10)
    val totalAmount = 1000 msat
    val fee = 10 msat
    val cltv = CltvExpiryDelta(2)
    val totalCltv = CltvExpiryDelta(40)
    val totalFees = 100 msat

    val richWeight1: RichWeight = RichWeight(edge, prevWeight, totalAmount, fee, totalFees, cltv, totalCltv, HEURISTICS_CONSTANTS_TYPICAL)
    val richWeight2: RichWeight = RichWeight(edge, prevWeight, totalAmount, fee, totalFees, cltv, totalCltv, HEURISTICS_CONSTANTS_HIGH_FAILURE_COST)
    val richWeight3: RichWeight = RichWeight(edge, prevWeight, totalAmount, fee, totalFees, cltv, totalCltv, HEURISTICS_CONSTANTS_HIGH_RISK)

    assert(richWeight1.weight == 1110.1010606060631)
    assert(richWeight2.weight == 220.0005000500128)
    assert(richWeight3.weight == 20.0002)
  }

  test("calculateAgeFactor") {
    val edge = createGraphEdge(randomKey(), 1000 sat)

    assert(RichWeight.calculateAgeFactor(edge, new BlockHeight(-100)) == 0.99999) // should negative BlockHeight be allowed?
    assert(RichWeight.calculateAgeFactor(edge, new BlockHeight(0)) == 0.99999)
    assert(RichWeight.calculateAgeFactor(edge, new BlockHeight(1)) == 0.9999809741248098)
    assert(RichWeight.calculateAgeFactor(edge, new BlockHeight(10)) == 0.9998097412480974)
    assert(RichWeight.calculateAgeFactor(edge, new BlockHeight(23400)) == 0.5547945205479452)
    assert(RichWeight.calculateAgeFactor(edge, new BlockHeight(50000)) == 0.0487062404870624)
    assert(RichWeight.calculateAgeFactor(edge, new BlockHeight(100000)) == 1.0E-5)
    assert(RichWeight.calculateAgeFactor(edge, new BlockHeight(1000000000)) == 1.0E-5)
  }

  test("calculateCapacityFactor when edge does not have balance") {

    val result = Seq(
      1 sat,
      100000 sat,
      1000000 sat,
      10000000 sat,
      50000000 sat,
      99000000 sat,
      100000000 sat,
      1000000000 sat,
    ).map(sats => RichWeight.calculateCapacityFactor(createGraphEdge(randomKey(), sats)) )

    assertResult("0.99999, 0.99999, 0.990990990990991, 0.9009009009009009, 0.5005005005005005, 0.010010010010010006, 9.99999999995449E-6, 9.99999999995449E-6") {
      result.mkString(", ")
    }
  }

  test("calculateCapacityFactor when edge has a balance") {

    val prev = new RichWeight(1000 msat, length = 2, CltvExpiryDelta(5), successProbability = 0.99, fees = 10 msat, virtualFees = 0 msat, weight = 10)
    val htlcMin = 7_000_000 msat
    val htlcMax = 500_000_000 msat
    val feePropertionalMillionsth = 100
    val meta = ChannelMeta(1 msat, 0 msat)

    val edge = createGraphEdge(randomKey(), 10000 sat, CltvExpiryDelta(10), feePropertionalMillionsth, htlcMin, htlcMax,
      None, None, Some(meta))

    assertResult(0.0) {
      RichWeight.calculateCapacityFactor(edge)
    }
  }

  test("calculateCltvFactor") {

    val result = Seq(
      (10 sat, CltvExpiryDelta(10)),
      (10 sat, CltvExpiryDelta(100)),
      (10 sat, CltvExpiryDelta(1000)),

      (1000000 sat, CltvExpiryDelta(10)),
      (1000000 sat, CltvExpiryDelta(100)),
      (1000000 sat, CltvExpiryDelta(1000)),

      (1000000 sat, CltvExpiryDelta(5)),
      (1000000 sat, CltvExpiryDelta(50000))
      ).map(v => getCltvFactor(v._1, v._2))

    assertResult(
      "4.982561036372695E-4, 0.04534130543099153, 0.49377179870453414, " +
      "4.982561036372695E-4, 0.04534130543099153, 0.49377179870453414, " +
      "1.0E-5, 0.99999") {
      result.mkString(", ")
    }
  }

  private def getCltvFactor(capacity: Satoshi, cltvExpiryDelta: CltvExpiryDelta): Double = {
    val edge = createGraphEdge(randomKey(), capacity, cltvExpiryDelta)
    RichWeight.calculateCltvFactor(edge)
  }

  test("calculateSuccessProbability when edge.balance_opt.nonEmpty ") {
    val result = Seq(
      (100 sat, CltvExpiryDelta(10), 50 msat, Some(ChannelMeta(34000 msat, 42000 msat)), HEURISTICS_CONSTANTS_TYPICAL),
      (10 sat, CltvExpiryDelta(10), 500 msat, Some(ChannelMeta(1 msat, 0 msat)), HEURISTICS_CONSTANTS_TYPICAL)
    ).map(v => getSuccessProbability(v._1, v._2, v._3, v._4, v._5))

    assertResult(
      "1.0, 1.0") {
      result.mkString(", ")
    }
  }

  test("calculateSuccessProbability when success probability negative ") {

    // negatvie probability results when ratio of prevAmount / capacity > 1
    val prevAmount = 10001000 msat
    val capacity = 10000 sat

    val prev = new RichWeight(prevAmount, length = 2, CltvExpiryDelta(5), successProbability = 0.99,
                              fees = 10 msat, virtualFees = 0 msat, weight = 10)
    val htlcMin = 7_000_000 msat
    val htlcMax = 500_000_000 msat

    val edge = createGraphEdge(randomKey(), capacity, CltvExpiryDelta(10), 100, htlcMin, htlcMax,
      None, None, None)

    val thrown = intercept[NegativeProbability] {
      RichWeight.calculateSuccessProbability(edge, prev, HEURISTICS_CONSTANTS_TYPICAL)
    }
    assert(thrown == NegativeProbability(edge, prev, HEURISTICS_CONSTANTS_TYPICAL))
  }

  test("calculateSuccessProbability") {
    val result = Seq(
      (100 sat, CltvExpiryDelta(10), 50 msat, None, HEURISTICS_CONSTANTS_TYPICAL),
      (100 sat, CltvExpiryDelta(100), 50000 msat, None, HEURISTICS_CONSTANTS_TYPICAL),
      (100 sat, CltvExpiryDelta(10000), 100000 msat, None, HEURISTICS_CONSTANTS_TYPICAL),

      (10000 sat, CltvExpiryDelta(10), 500 msat, None, HEURISTICS_CONSTANTS_TYPICAL),
      (10000 sat, CltvExpiryDelta(100), 5000 msat, None,  HEURISTICS_CONSTANTS_TYPICAL),
      (10000 sat, CltvExpiryDelta(10000), 9800000 msat, None,  HEURISTICS_CONSTANTS_TYPICAL),
      (10000 sat, CltvExpiryDelta(10000), 10000000 msat, None,  HEURISTICS_CONSTANTS_TYPICAL),
    ).map(v => getSuccessProbability(v._1, v._2, v._3, v._4, v._5))

    assertResult("0.9995, 0.5, 0.0, 0.99995, 0.9995, 0.020000000000000018, 0.0") {
      result.mkString(", ")
    }
  }

  private def getSuccessProbability(capacity: Satoshi, cltvExpiryDelta: CltvExpiryDelta, prevAmount: MilliSatoshi,
                                      meta: Option[ChannelMeta], heuristicsConstants: HeuristicsConstants): Double = {
    val prev = new RichWeight(prevAmount, length = 2, CltvExpiryDelta(5), successProbability = 0.99, fees = 10 msat, virtualFees = 0 msat, weight = 10)
    val htlcMin = 7_000_000 msat
    val htlcMax = 500_000_000 msat
    val feePropertionalMillionsth = 100
    val key1 = randomKey()

    val edge = createGraphEdge(key1, capacity, cltvExpiryDelta, feePropertionalMillionsth, htlcMin, htlcMax,
      None, None, meta)

    RichWeight.calculateSuccessProbability(edge, prev, heuristicsConstants)
  }

  private def createGraphEdge(senderKey: PrivateKey, capacity: Satoshi,
                              cltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(5), feeProportionalMillionths: Long = 100,
                              htlcMin: MilliSatoshi = 7_000_000 msat, htlcMax: MilliSatoshi = 500_000_000L msat,
                              update_1: Option[ChannelUpdate] = None,
                              update_2: Option[ChannelUpdate] = None,
                              meta: Option[ChannelMeta] = None): GraphEdge = {
    val feeBaseMsat = 50000 msat
    val localKey = randomKey()

    val channelUpdate: ChannelUpdate =
      createChannelUpdate(feeBaseMsat, localKey, senderKey, cltvExpiryDelta, feeProportionalMillionths, htlcMin, htlcMax)

    val transactionId = randomBytes32()
    val channelAnnouncement = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(1),
      localKey.publicKey, senderKey.publicKey, localKey.publicKey, senderKey.publicKey, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val publicChannel = PublicChannel(channelAnnouncement, transactionId, capacity, update_1, update_2, meta)

    GraphEdge(channelUpdate, publicChannel)
  }

  private def createChannelUpdate(feeBaseMsat: MilliSatoshi, key1: Crypto.PrivateKey, key2: Crypto.PrivateKey,
                                  cltvExpiryDelta: CltvExpiryDelta, feeProportionalMillionths: Long,
                                  htlcMin: MilliSatoshi, htlcMax: MilliSatoshi): ChannelUpdate = {
    Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, key1, key2.publicKey,
      ShortChannelId(42), cltvExpiryDelta, htlcMin, feeBaseMsat, feeProportionalMillionths, htlcMax)
  }
}

object RichWeightSpec {

  private val NO_WEIGHT_RATIOS: WeightRatios = path.WeightRatios(1, 0, 0, 0, RelayFees(0 msat, 0))

  private val HEURISTICS_CONSTANTS_TYPICAL = HeuristicsConstants(
    lockedFundsRisk = 0.0,
    failureCost = RelayFees(1000 msat, 500),
    hopCost = RelayFees(0 msat, 0),
    useLogProbability = false,
  )

  private val HEURISTICS_CONSTANTS_HIGH_FAILURE_COST = HeuristicsConstants(
    lockedFundsRisk = 0.1,
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

}