/*
 * Copyright 2024 ACINQ SAS
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

package fr.acinq.eclair.db

import fr.acinq.bitcoin.scalacompat.{SatoshiLong, TxId}
import fr.acinq.eclair.TestDatabases.forAllDbs
import fr.acinq.eclair.channel.{ChannelLiquidityPurchased, LiquidityPurchase}
import fr.acinq.eclair.wire.protocol.LiquidityAds
import fr.acinq.eclair.{MilliSatoshiLong, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite

class LiquidityDbSpec extends AnyFunSuite {

  test("add/list liquidity purchases") {
    forAllDbs { dbs =>
      val db = dbs.liquidity
      val (nodeId1, nodeId2) = (randomKey().publicKey, randomKey().publicKey)
      val confirmedFundingTxId = TxId(randomBytes32())
      val unconfirmedFundingTxId = TxId(randomBytes32())
      val e1a = ChannelLiquidityPurchased(null, randomBytes32(), nodeId1, LiquidityPurchase(confirmedFundingTxId, 3, isBuyer = true, 250_000 sat, LiquidityAds.Fees(2_000 sat, 3_000 sat), 750_000 sat, 50_000 sat, 300_000 sat, 400_000_000 msat, 350_000_000 msat, 7, 11))
      val e1b = ChannelLiquidityPurchased(null, randomBytes32(), nodeId1, LiquidityPurchase(confirmedFundingTxId, 7, isBuyer = false, 50_000 sat, LiquidityAds.Fees(300 sat, 700 sat), 500_000 sat, 50_000 sat, 0 sat, 250_000_000 msat, 250_000_000 msat, 0, 0))
      val e1c = ChannelLiquidityPurchased(null, e1b.channelId, nodeId1, LiquidityPurchase(confirmedFundingTxId, 0, isBuyer = false, 150_000 sat, LiquidityAds.Fees(500 sat, 1_500 sat), 250_000 sat, 150_000 sat, -100_000 sat, 200_000_000 msat, 50_000_000 msat, 47, 45))
      val e1d = ChannelLiquidityPurchased(null, randomBytes32(), nodeId1, LiquidityPurchase(unconfirmedFundingTxId, 22, isBuyer = true, 250_000 sat, LiquidityAds.Fees(4_000 sat, 1_000 sat), 450_000 sat, -50_000 sat, 250_000 sat, 150_000_000 msat, 300_000_000 msat, 3, 3))
      val e2a = ChannelLiquidityPurchased(null, randomBytes32(), nodeId2, LiquidityPurchase(confirmedFundingTxId, 453, isBuyer = false, 200_000 sat, LiquidityAds.Fees(1_000 sat, 1_000 sat), 300_000 sat, 250_000 sat, 0 sat, 270_000_000 msat, 30_000_000 msat, 113, 0))
      val e2b = ChannelLiquidityPurchased(null, randomBytes32(), nodeId2, LiquidityPurchase(unconfirmedFundingTxId, 1, isBuyer = false, 200_000 sat, LiquidityAds.Fees(1_000 sat, 1_000 sat), 300_000 sat, 250_000 sat, -10_000 sat, 250_000_000 msat, 50_000_000 msat, 0, 113))

      db.addPurchase(e1a)
      db.addPurchase(e1b)
      db.addPurchase(e1c)
      db.addPurchase(e1d)
      db.addPurchase(e2a)
      db.addPurchase(e2b)

      // The liquidity purchase is confirmed only once the corresponding transaction confirms.
      assert(db.listPurchases(nodeId1).isEmpty)
      assert(db.listPurchases(nodeId2).isEmpty)

      db.setConfirmed(nodeId1, confirmedFundingTxId)
      db.setConfirmed(nodeId2, confirmedFundingTxId)

      assert(db.listPurchases(nodeId1).toSet == Set(e1a, e1b, e1c).map(_.purchase))
      assert(db.listPurchases(nodeId2) == Seq(e2a.purchase))
    }
  }

}
