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

import fr.acinq.bitcoin.scalacompat.{Crypto, SatoshiLong, TxId}
import fr.acinq.eclair.TestDatabases.forAllDbs
import fr.acinq.eclair.channel.{ChannelLiquidityPurchased, LiquidityPurchase, Upstream}
import fr.acinq.eclair.payment.relay.OnTheFlyFunding
import fr.acinq.eclair.payment.relay.OnTheFlyFundingSpec.{createWillAdd, randomOnion}
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.wire.protocol.{LiquidityAds, UpdateAddHtlc}
import fr.acinq.eclair.{CltvExpiry, MilliSatoshiLong, TimestampMilli, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite

import java.util.UUID

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

  test("add/list/remove pending on-the-fly funding proposals") {
    forAllDbs { dbs =>
      val db = dbs.liquidity

      val alice = randomKey().publicKey
      val bob = randomKey().publicKey
      val paymentHash1 = randomBytes32()
      val paymentHash2 = randomBytes32()
      val upstream = Seq(
        Upstream.Hot.Channel(UpdateAddHtlc(randomBytes32(), 7, 25_000_000 msat, paymentHash1, CltvExpiry(750_000), randomOnion(), None, Reputation.maxEndorsement, None), TimestampMilli(0), randomKey().publicKey, 0.1),
        Upstream.Hot.Channel(UpdateAddHtlc(randomBytes32(), 0, 1 msat, paymentHash1, CltvExpiry(750_000), randomOnion(), Some(randomKey().publicKey), Reputation.maxEndorsement, None), TimestampMilli.now(), randomKey().publicKey, 0.1),
        Upstream.Hot.Channel(UpdateAddHtlc(randomBytes32(), 561, 100_000_000 msat, paymentHash2, CltvExpiry(799_999), randomOnion(), None, Reputation.maxEndorsement, None), TimestampMilli.now(), randomKey().publicKey, 0.1),
        Upstream.Hot.Channel(UpdateAddHtlc(randomBytes32(), 1105, 100_000_000 msat, paymentHash2, CltvExpiry(799_999), randomOnion(), None, Reputation.maxEndorsement, None), TimestampMilli.now(), randomKey().publicKey, 0.1),
      )
      val pendingAlice = Seq(
        OnTheFlyFunding.Pending(
          proposed = Seq(
            OnTheFlyFunding.Proposal(createWillAdd(20_000 msat, paymentHash1, CltvExpiry(500)), upstream(0), Nil),
            OnTheFlyFunding.Proposal(createWillAdd(1 msat, paymentHash1, CltvExpiry(750), Some(randomKey().publicKey)), upstream(1), Nil),
          ),
          status = OnTheFlyFunding.Status.Funded(randomBytes32(), TxId(randomBytes32()), 7, 500 msat)
        ),
        OnTheFlyFunding.Pending(
          proposed = Seq(
            OnTheFlyFunding.Proposal(createWillAdd(195_000_000 msat, paymentHash2, CltvExpiry(1000)), Upstream.Hot.Trampoline(upstream(2) :: upstream(3) :: Nil), Nil),
          ),
          status = OnTheFlyFunding.Status.Funded(randomBytes32(), TxId(randomBytes32()), 3, 0 msat)
        )
      )
      val pendingBob = Seq(
        OnTheFlyFunding.Pending(
          proposed = Seq(
            OnTheFlyFunding.Proposal(createWillAdd(20_000 msat, paymentHash1, CltvExpiry(42)), upstream(0), Nil),
          ),
          status = OnTheFlyFunding.Status.Funded(randomBytes32(), TxId(randomBytes32()), 11, 3_500 msat)
        ),
        OnTheFlyFunding.Pending(
          proposed = Seq(
            OnTheFlyFunding.Proposal(createWillAdd(24_000_000 msat, paymentHash2, CltvExpiry(800_000), Some(randomKey().publicKey)), Upstream.Local(UUID.randomUUID()), Nil),
          ),
          status = OnTheFlyFunding.Status.Funded(randomBytes32(), TxId(randomBytes32()), 0, 10_000 msat)
        )
      )

      assert(db.listPendingOnTheFlyPayments().isEmpty)
      assert(db.listPendingOnTheFlyFunding(alice).isEmpty)
      assert(db.listPendingOnTheFlyFunding().isEmpty)
      db.removePendingOnTheFlyFunding(alice, paymentHash1) // no-op

      // Add pending proposals for Alice.
      db.addPendingOnTheFlyFunding(alice, pendingAlice(0))
      assert(db.listPendingOnTheFlyFunding(alice) == Map(paymentHash1 -> pendingAlice(0)))
      assert(db.listPendingOnTheFlyFunding() == Map(alice -> Map(paymentHash1 -> pendingAlice(0))))
      db.addPendingOnTheFlyFunding(alice, pendingAlice(1).copy(status = OnTheFlyFunding.Status.Proposed(null)))
      assert(db.listPendingOnTheFlyFunding(alice) == Map(paymentHash1 -> pendingAlice(0)))
      assert(db.listPendingOnTheFlyFunding() == Map(alice -> Map(paymentHash1 -> pendingAlice(0))))
      db.addPendingOnTheFlyFunding(alice, pendingAlice(1))
      assert(db.listPendingOnTheFlyFunding(alice) == Map(paymentHash1 -> pendingAlice(0), paymentHash2 -> pendingAlice(1)))
      assert(db.listPendingOnTheFlyFunding() == Map(alice -> Map(paymentHash1 -> pendingAlice(0), paymentHash2 -> pendingAlice(1))))
      assert(db.listPendingOnTheFlyPayments() == Map(alice -> Set(paymentHash1, paymentHash2)))

      // Add pending proposals for Bob.
      assert(db.listPendingOnTheFlyFunding(bob).isEmpty)
      db.addPendingOnTheFlyFunding(bob, pendingBob(0))
      db.addPendingOnTheFlyFunding(bob, pendingBob(1))
      assert(db.listPendingOnTheFlyFunding(alice) == Map(paymentHash1 -> pendingAlice(0), paymentHash2 -> pendingAlice(1)))
      assert(db.listPendingOnTheFlyFunding(bob) == Map(paymentHash1 -> pendingBob(0), paymentHash2 -> pendingBob(1)))
      assert(db.listPendingOnTheFlyFunding() == Map(
        alice -> Map(paymentHash1 -> pendingAlice(0), paymentHash2 -> pendingAlice(1)),
        bob -> Map(paymentHash1 -> pendingBob(0), paymentHash2 -> pendingBob(1))
      ))
      assert(db.listPendingOnTheFlyPayments() == Map(alice -> Set(paymentHash1, paymentHash2), bob -> Set(paymentHash1, paymentHash2)))

      // Remove pending proposals that are completed.
      db.removePendingOnTheFlyFunding(alice, paymentHash1)
      assert(db.listPendingOnTheFlyFunding(alice) == Map(paymentHash2 -> pendingAlice(1)))
      assert(db.listPendingOnTheFlyFunding(bob) == Map(paymentHash1 -> pendingBob(0), paymentHash2 -> pendingBob(1)))
      assert(db.listPendingOnTheFlyFunding() == Map(
        alice -> Map(paymentHash2 -> pendingAlice(1)),
        bob -> Map(paymentHash1 -> pendingBob(0), paymentHash2 -> pendingBob(1))
      ))
      assert(db.listPendingOnTheFlyPayments() == Map(alice -> Set(paymentHash2), bob -> Set(paymentHash1, paymentHash2)))
      db.removePendingOnTheFlyFunding(alice, paymentHash1) // no-op
      db.removePendingOnTheFlyFunding(bob, randomBytes32()) // no-op
      assert(db.listPendingOnTheFlyFunding(alice) == Map(paymentHash2 -> pendingAlice(1)))
      assert(db.listPendingOnTheFlyFunding(bob) == Map(paymentHash1 -> pendingBob(0), paymentHash2 -> pendingBob(1)))
      assert(db.listPendingOnTheFlyFunding() == Map(
        alice -> Map(paymentHash2 -> pendingAlice(1)),
        bob -> Map(paymentHash1 -> pendingBob(0), paymentHash2 -> pendingBob(1))
      ))
      assert(db.listPendingOnTheFlyPayments() == Map(alice -> Set(paymentHash2), bob -> Set(paymentHash1, paymentHash2)))
      db.removePendingOnTheFlyFunding(alice, paymentHash2)
      assert(db.listPendingOnTheFlyFunding(alice).isEmpty)
      assert(db.listPendingOnTheFlyFunding(bob) == Map(paymentHash1 -> pendingBob(0), paymentHash2 -> pendingBob(1)))
      assert(db.listPendingOnTheFlyFunding() == Map(bob -> Map(paymentHash1 -> pendingBob(0), paymentHash2 -> pendingBob(1))))
      assert(db.listPendingOnTheFlyPayments() == Map(bob -> Set(paymentHash1, paymentHash2)))
      db.removePendingOnTheFlyFunding(bob, paymentHash2)
      assert(db.listPendingOnTheFlyFunding(bob) == Map(paymentHash1 -> pendingBob(0)))
      assert(db.listPendingOnTheFlyFunding() == Map(bob -> Map(paymentHash1 -> pendingBob(0))))
      assert(db.listPendingOnTheFlyPayments() == Map(bob -> Set(paymentHash1)))
      db.removePendingOnTheFlyFunding(bob, paymentHash1)
      assert(db.listPendingOnTheFlyFunding(bob).isEmpty)
      assert(db.listPendingOnTheFlyFunding().isEmpty)
      assert(db.listPendingOnTheFlyPayments().isEmpty)
    }
  }

  test("add/get on-the-fly-funding preimages") {
    forAllDbs { dbs =>
      val db = dbs.liquidity

      val preimage1 = randomBytes32()
      val preimage2 = randomBytes32()

      db.addOnTheFlyFundingPreimage(preimage1)
      db.addOnTheFlyFundingPreimage(preimage1) // no-op
      db.addOnTheFlyFundingPreimage(preimage2)

      assert(db.getOnTheFlyFundingPreimage(Crypto.sha256(preimage1)).contains(preimage1))
      assert(db.getOnTheFlyFundingPreimage(Crypto.sha256(preimage2)).contains(preimage2))
      assert(db.getOnTheFlyFundingPreimage(randomBytes32()).isEmpty)
    }
  }

  test("add/get/remove fee credit") {
    forAllDbs { dbs =>
      val db = dbs.liquidity
      val nodeId = randomKey().publicKey

      // Initially, the DB is empty.
      assert(db.getFeeCredit(nodeId) == 0.msat)
      assert(db.removeFeeCredit(nodeId, 0 msat) == 0.msat)

      // We owe some fee credit to our peer.
      assert(db.addFeeCredit(nodeId, 211_754 msat, receivedAt = TimestampMilli(50_000)) == 211_754.msat)
      assert(db.getFeeCredit(nodeId) == 211_754.msat)
      assert(db.addFeeCredit(nodeId, 245 msat, receivedAt = TimestampMilli(55_000)) == 211_999.msat)
      assert(db.getFeeCredit(nodeId) == 211_999.msat)

      // We consume some of the fee credit.
      assert(db.removeFeeCredit(nodeId, 11_999 msat) == 200_000.msat)
      assert(db.getFeeCredit(nodeId) == 200_000.msat)
      assert(db.removeFeeCredit(nodeId, 250_000 msat) == 0.msat)
      assert(db.getFeeCredit(nodeId) == 0.msat)
    }
  }

}
