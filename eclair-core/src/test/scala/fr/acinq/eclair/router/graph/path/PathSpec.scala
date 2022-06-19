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

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector64, Crypto, SatoshiLong}
import fr.acinq.eclair.db.NetworkDbSpec.generatePubkeyHigherThan
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.router.Router.PublicChannel
import fr.acinq.eclair.router.graph.path
import fr.acinq.eclair.router.graph.structure.GraphEdge
import fr.acinq.eclair.wire.protocol.ChannelUpdate
import fr.acinq.eclair.{BlockHeight, CltvExpiryDelta, MilliSatoshi, MilliSatoshiLong, ShortChannelId, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.Seq

class PathSpec extends AnyFunSuite {

  private val WEIGHT_RATIOS: WeightRatios = path.WeightRatios(1, 0, 0, 0, RelayFees(10 msat, 1))

  test("addEdgeWeight with  channelCost") {

    assertResult("RichWeight(5 msat,3,CltvExpiryDelta(5),1.0,10 msat,0 msat,10.0)") {
      createEdgeWeight(5 msat, 10 msat, 0 msat, 50000 msat, 10, includeLocalChannelCost = false).toString
    }
    assertResult("RichWeight(50 msat,3,CltvExpiryDelta(5),1.0,10 msat,0 msat,10.0)") {
      createEdgeWeight(50 msat, 10 msat, 0 msat, 50000 msat, 10, includeLocalChannelCost = false).toString
    }
    assertResult("RichWeight(50 msat,3,CltvExpiryDelta(5),1.0,20 msat,0 msat,10.0)") {
      createEdgeWeight(50 msat, 20 msat, 0 msat, 50000 msat, 10, includeLocalChannelCost = false).toString
    }
    assertResult("RichWeight(50 msat,3,CltvExpiryDelta(5),1.0,20 msat,0 msat,10.0)") {
      createEdgeWeight(50 msat, 20 msat, 10 msat, 50000 msat, 10, includeLocalChannelCost = false).toString
    }
    assertResult("RichWeight(50 msat,3,CltvExpiryDelta(5),1.0,20 msat,0 msat,10.0)"){
      createEdgeWeight(50 msat, 20 msat, 10 msat, 10000 msat, 10, includeLocalChannelCost = false).toString
    }
    assertResult("RichWeight(50 msat,3,CltvExpiryDelta(5),1.0,20 msat,0 msat,100.0)") {
      createEdgeWeight(50 msat, 20 msat, 10 msat, 10000 msat, 100, includeLocalChannelCost = false).toString
    }
  }

  test("addEdgeWeight without channelCost") {

    assertResult("RichWeight(40005 msat,3,CltvExpiryDelta(10),1.0,40010 msat,0 msat,40010.0)") {
      createEdgeWeight(5 msat, 10 msat, 0 msat, 40000 msat, 10, includeLocalChannelCost = true).toString
    }
    assertResult("RichWeight(10000 msat,3,CltvExpiryDelta(10),1.0,5010 msat,0 msat,5010.0)"){
      createEdgeWeight(5000 msat, 10 msat, 0 msat, 5000 msat, 10, includeLocalChannelCost = true).toString
    }
    assertResult("RichWeight(50050 msat,3,CltvExpiryDelta(10),1.0,50100 msat,0 msat,50010.0)"){
      createEdgeWeight(50 msat, 100 msat, 0 msat, 50000 msat, 10, includeLocalChannelCost = true).toString
    }
    assertResult("RichWeight(50050 msat,3,CltvExpiryDelta(10),1.0,50100 msat,0 msat,50010.0)"){
      createEdgeWeight(50 msat, 100 msat, 10 msat, 50000 msat, 10, includeLocalChannelCost = true).toString
    }
    assertResult("RichWeight(10050 msat,3,CltvExpiryDelta(10),1.0,10100 msat,0 msat,10010.0)"){
      createEdgeWeight(50 msat, 100 msat, 10 msat, 10000 msat, 10, includeLocalChannelCost = true).toString
    }
    assertResult("RichWeight(10050 msat,3,CltvExpiryDelta(10),1.0,10100 msat,0 msat,10020.0)"){
      createEdgeWeight(50 msat, 100 msat, 10 msat, 10000 msat, 20, includeLocalChannelCost = true).toString
    }
  }

  private def createEdgeWeight(prevAmount: MilliSatoshi, fees: MilliSatoshi, virtualFees: MilliSatoshi, feeBase: MilliSatoshi, weight: Double, includeLocalChannelCost: Boolean): RichWeight = {
    val key1 = randomKey()
    val senderKey = generatePubkeyHigherThan(key1)
    val edge = createGraphEdge(senderKey, feeBase)
    val prevWeight = new RichWeight(prevAmount, length = 2, CltvExpiryDelta(5), successProbability = 0.99, fees, virtualFees, weight)
    val currentBlockHeight = new BlockHeight(7)

    Path.addEdgeWeight(senderKey.publicKey, edge, prevWeight, currentBlockHeight, Left(WEIGHT_RATIOS), includeLocalChannelCost)
  }


  private def createGraphEdge(key2: PrivateKey, feeBase: MilliSatoshi): GraphEdge = {
    val key1 = randomKey()

    val channelUpdate: ChannelUpdate = createChannelUpdate(feeBase, key2, key1)

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
