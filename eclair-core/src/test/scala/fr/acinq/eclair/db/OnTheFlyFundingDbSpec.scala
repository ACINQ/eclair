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

import fr.acinq.bitcoin.scalacompat.{Crypto, TxId}
import fr.acinq.eclair.channel.Upstream
import fr.acinq.eclair.payment.relay.OnTheFlyFunding
import fr.acinq.eclair.payment.relay.OnTheFlyFundingSpec._
import fr.acinq.eclair.wire.protocol.UpdateAddHtlc
import fr.acinq.eclair.{CltvExpiry, MilliSatoshiLong, TimestampMilli, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite

import java.util.UUID

class OnTheFlyFundingDbSpec extends AnyFunSuite {

  import fr.acinq.eclair.TestDatabases.forAllDbs

  test("add/get preimages") {
    forAllDbs { dbs =>
      val db = dbs.onTheFlyFunding

      val preimage1 = randomBytes32()
      val preimage2 = randomBytes32()

      db.addPreimage(preimage1)
      db.addPreimage(preimage1) // no-op
      db.addPreimage(preimage2)

      assert(db.getPreimage(Crypto.sha256(preimage1)).contains(preimage1))
      assert(db.getPreimage(Crypto.sha256(preimage2)).contains(preimage2))
      assert(db.getPreimage(randomBytes32()).isEmpty)
    }
  }

  test("add/list/remove pending proposals") {
    forAllDbs { dbs =>
      val db = dbs.onTheFlyFunding

      val alice = randomKey().publicKey
      val bob = randomKey().publicKey
      val paymentHash1 = randomBytes32()
      val paymentHash2 = randomBytes32()
      val upstream = Seq(
        Upstream.Hot.Channel(UpdateAddHtlc(randomBytes32(), 7, 25_000_000 msat, paymentHash1, CltvExpiry(750_000), randomOnion(), None, None), TimestampMilli(0)),
        Upstream.Hot.Channel(UpdateAddHtlc(randomBytes32(), 0, 1 msat, paymentHash1, CltvExpiry(750_000), randomOnion(), Some(randomKey().publicKey), None), TimestampMilli.now()),
        Upstream.Hot.Channel(UpdateAddHtlc(randomBytes32(), 561, 100_000_000 msat, paymentHash2, CltvExpiry(799_999), randomOnion(), None, None), TimestampMilli.now()),
        Upstream.Hot.Channel(UpdateAddHtlc(randomBytes32(), 1105, 100_000_000 msat, paymentHash2, CltvExpiry(799_999), randomOnion(), None, None), TimestampMilli.now()),
      )
      val pendingAlice = Seq(
        OnTheFlyFunding.Pending(
          proposed = Seq(
            OnTheFlyFunding.Proposal(createWillAdd(20_000 msat, paymentHash1, CltvExpiry(500)), upstream(0)),
            OnTheFlyFunding.Proposal(createWillAdd(1 msat, paymentHash1, CltvExpiry(750), Some(randomKey().publicKey)), upstream(1)),
          ),
          status = OnTheFlyFunding.Status.Funded(randomBytes32(), TxId(randomBytes32()), 7, 500 msat)
        ),
        OnTheFlyFunding.Pending(
          proposed = Seq(
            OnTheFlyFunding.Proposal(createWillAdd(195_000_000 msat, paymentHash2, CltvExpiry(1000)), Upstream.Hot.Trampoline(upstream(2) :: upstream(3) :: Nil)),
          ),
          status = OnTheFlyFunding.Status.Funded(randomBytes32(), TxId(randomBytes32()), 3, 0 msat)
        )
      )
      val pendingBob = Seq(
        OnTheFlyFunding.Pending(
          proposed = Seq(
            OnTheFlyFunding.Proposal(createWillAdd(20_000 msat, paymentHash1, CltvExpiry(42)), upstream(0)),
          ),
          status = OnTheFlyFunding.Status.Funded(randomBytes32(), TxId(randomBytes32()), 11, 3_500 msat)
        ),
        OnTheFlyFunding.Pending(
          proposed = Seq(
            OnTheFlyFunding.Proposal(createWillAdd(24_000_000 msat, paymentHash2, CltvExpiry(800_000), Some(randomKey().publicKey)), Upstream.Local(UUID.randomUUID())),
          ),
          status = OnTheFlyFunding.Status.Funded(randomBytes32(), TxId(randomBytes32()), 0, 10_000 msat)
        )
      )

      assert(db.listPendingPayments().isEmpty)
      assert(db.listPending(alice).isEmpty)
      db.removePending(alice, paymentHash1) // no-op

      // Add pending proposals for Alice.
      db.addPending(alice, pendingAlice(0))
      assert(db.listPending(alice) == Map(paymentHash1 -> pendingAlice(0)))
      db.addPending(alice, pendingAlice(1).copy(status = OnTheFlyFunding.Status.Proposed(null)))
      assert(db.listPending(alice) == Map(paymentHash1 -> pendingAlice(0)))
      db.addPending(alice, pendingAlice(1))
      assert(db.listPending(alice) == Map(paymentHash1 -> pendingAlice(0), paymentHash2 -> pendingAlice(1)))
      assert(db.listPendingPayments() == Map(alice -> Set(paymentHash1, paymentHash2)))

      // Add pending proposals for Bob.
      assert(db.listPending(bob).isEmpty)
      db.addPending(bob, pendingBob(0))
      db.addPending(bob, pendingBob(1))
      assert(db.listPending(alice) == Map(paymentHash1 -> pendingAlice(0), paymentHash2 -> pendingAlice(1)))
      assert(db.listPending(bob) == Map(paymentHash1 -> pendingBob(0), paymentHash2 -> pendingBob(1)))
      assert(db.listPendingPayments() == Map(alice -> Set(paymentHash1, paymentHash2), bob -> Set(paymentHash1, paymentHash2)))

      // Remove pending proposals that are completed.
      db.removePending(alice, paymentHash1)
      assert(db.listPending(alice) == Map(paymentHash2 -> pendingAlice(1)))
      assert(db.listPending(bob) == Map(paymentHash1 -> pendingBob(0), paymentHash2 -> pendingBob(1)))
      assert(db.listPendingPayments() == Map(alice -> Set(paymentHash2), bob -> Set(paymentHash1, paymentHash2)))
      db.removePending(alice, paymentHash1) // no-op
      db.removePending(bob, randomBytes32()) // no-op
      assert(db.listPending(alice) == Map(paymentHash2 -> pendingAlice(1)))
      assert(db.listPending(bob) == Map(paymentHash1 -> pendingBob(0), paymentHash2 -> pendingBob(1)))
      assert(db.listPendingPayments() == Map(alice -> Set(paymentHash2), bob -> Set(paymentHash1, paymentHash2)))
      db.removePending(alice, paymentHash2)
      assert(db.listPending(alice).isEmpty)
      assert(db.listPending(bob) == Map(paymentHash1 -> pendingBob(0), paymentHash2 -> pendingBob(1)))
      assert(db.listPendingPayments() == Map(bob -> Set(paymentHash1, paymentHash2)))
      db.removePending(bob, paymentHash2)
      assert(db.listPending(bob) == Map(paymentHash1 -> pendingBob(0)))
      assert(db.listPendingPayments() == Map(bob -> Set(paymentHash1)))
      db.removePending(bob, paymentHash1)
      assert(db.listPending(bob).isEmpty)
      assert(db.listPendingPayments().isEmpty)
    }
  }

}
