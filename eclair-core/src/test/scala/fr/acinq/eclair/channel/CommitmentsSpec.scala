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

package fr.acinq.eclair.channel

import java.util.UUID

import fr.acinq.eclair.channel.Commitments._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.payment.Local
import fr.acinq.eclair.wire.IncorrectPaymentAmount
import fr.acinq.eclair.{TestkitBaseClass, _}
import org.scalatest.Outcome

import scala.concurrent.duration._

class CommitmentsSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = SetupFixture

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    within(30 seconds) {
      reachNormal(setup, test.tags)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      withFixture(test.toNoArgTest(setup))
    }
  }

  test("correct values for availableForSend/availableForReceive (success case)") { f =>
    import f._

    val a = 772760000 msat // initial balance alice
    val b = 190000000 msat // initial balance bob
    val fee = 1720000 msat // fee due to the additional htlc output
    val p = 42000000 msat // a->b payment

    val ac0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    val bc0 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments

    assert(ac0.availableBalanceForSend > p) // alice can afford the payment
    assert(ac0.availableBalanceForSend == a)
    assert(ac0.availableBalanceForReceive == b)
    assert(bc0.availableBalanceForSend == b)
    assert(bc0.availableBalanceForReceive == a)

    val (payment_preimage, cmdAdd) = makeCmdAdd(p, bob.underlyingActor.nodeParams.nodeId)
    val Right((ac1, add)) = sendAdd(ac0, cmdAdd, Local(UUID.randomUUID, None))
    assert(ac1.availableBalanceForSend == a - p - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
    assert(ac1.availableBalanceForReceive == b)

    val bc1 = receiveAdd(bc0, add)
    assert(bc1.availableBalanceForSend == b)
    assert(bc1.availableBalanceForReceive == a - p - fee)

    val (ac2, commit1) = sendCommit(ac1, alice.underlyingActor.nodeParams.keyManager)
    assert(ac2.availableBalanceForSend == a - p - fee)
    assert(ac2.availableBalanceForReceive == b)

    val (bc2, revocation1) = receiveCommit(bc1, commit1, bob.underlyingActor.nodeParams.keyManager)
    assert(bc2.availableBalanceForSend == b)
    assert(bc2.availableBalanceForReceive == a - p - fee)

    val (ac3, _) = receiveRevocation(ac2, revocation1)
    assert(ac3.availableBalanceForSend == a - p - fee)
    assert(ac3.availableBalanceForReceive == b)

    val (bc3, commit2) = sendCommit(bc2, bob.underlyingActor.nodeParams.keyManager)
    assert(bc3.availableBalanceForSend == b)
    assert(bc3.availableBalanceForReceive == a - p - fee)

    val (ac4, revocation2) = receiveCommit(ac3, commit2, alice.underlyingActor.nodeParams.keyManager)
    assert(ac4.availableBalanceForSend == a - p - fee)
    assert(ac4.availableBalanceForReceive == b)

    val (bc4, _) = receiveRevocation(bc3, revocation2)
    assert(bc4.availableBalanceForSend == b)
    assert(bc4.availableBalanceForReceive == a - p - fee)

    fulfillFunderHtlc(f, CMD_FULFILL_HTLC(0, payment_preimage), ac4, bc4, p, fee)
  }

  test("correct values for availableForSend/availableForReceive (failure case)") { f =>
    import f._

    val a = 772760000 msat // initial balance alice
    val b = 190000000 msat // initial balance bob
    val fee = 1720000 msat // fee due to the additional htlc output
    val p = 42000000 msat // a->b payment

    val ac0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    val bc0 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments

    assert(ac0.availableBalanceForSend > p) // alice can afford the payment
    assert(ac0.availableBalanceForSend == a)
    assert(ac0.availableBalanceForReceive == b)
    assert(bc0.availableBalanceForSend == b)
    assert(bc0.availableBalanceForReceive == a)

    val (_, cmdAdd) = makeCmdAdd(p, bob.underlyingActor.nodeParams.nodeId)
    val Right((ac1, add)) = sendAdd(ac0, cmdAdd, Local(UUID.randomUUID, None))
    assert(ac1.availableBalanceForSend == a - p - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
    assert(ac1.availableBalanceForReceive == b)

    val bc1 = receiveAdd(bc0, add)
    assert(bc1.availableBalanceForSend == b)
    assert(bc1.availableBalanceForReceive == a - p - fee)

    val (ac2, commit1) = sendCommit(ac1, alice.underlyingActor.nodeParams.keyManager)
    assert(ac2.availableBalanceForSend == a - p - fee)
    assert(ac2.availableBalanceForReceive == b)

    val (bc2, revocation1) = receiveCommit(bc1, commit1, bob.underlyingActor.nodeParams.keyManager)
    assert(bc2.availableBalanceForSend == b)
    assert(bc2.availableBalanceForReceive == a - p - fee)

    val (ac3, _) = receiveRevocation(ac2, revocation1)
    assert(ac3.availableBalanceForSend == a - p - fee)
    assert(ac3.availableBalanceForReceive == b)

    val (bc3, commit2) = sendCommit(bc2, bob.underlyingActor.nodeParams.keyManager)
    assert(bc3.availableBalanceForSend == b)
    assert(bc3.availableBalanceForReceive == a - p - fee)

    val (ac4, revocation2) = receiveCommit(ac3, commit2, alice.underlyingActor.nodeParams.keyManager)
    assert(ac4.availableBalanceForSend == a - p - fee)
    assert(ac4.availableBalanceForReceive == b)

    val (bc4, _) = receiveRevocation(bc3, revocation2)
    assert(bc4.availableBalanceForSend == b)
    assert(bc4.availableBalanceForReceive == a - p - fee)

    failFunderHtlc(f, CMD_FAIL_HTLC(0, Right(IncorrectPaymentAmount)), ac4, bc4, p, fee)
  }

  test("correct values for availableForSend/availableForReceive (multiple htlcs)") { f =>
    import f._

    val a = 772760000 msat // initial balance alice
    val b = 190000000 msat // initial balance bob
    val fee = 1720000 msat // fee due to the additional htlc output
    val p1 = 10000000 msat // a->b payment
    val p2 = 20000000 msat // a->b payment
    val p3 = 40000000 msat // b->a payment

    val ac0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    val bc0 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments

    assert(ac0.availableBalanceForSend > (p1 + p2)) // alice can afford the payments
    assert(bc0.availableBalanceForSend > p3) // bob can afford the payment
    assert(ac0.availableBalanceForSend == a)
    assert(ac0.availableBalanceForReceive == b)
    assert(bc0.availableBalanceForSend == b)
    assert(bc0.availableBalanceForReceive == a)

    val (preimage1, cmdAdd1) = makeCmdAdd(p1, bob.underlyingActor.nodeParams.nodeId)
    val Right((ac1, add1)) = sendAdd(ac0, cmdAdd1, Local(UUID.randomUUID, None))
    assert(ac1.availableBalanceForSend == a - p1 - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
    assert(ac1.availableBalanceForReceive == b)

    val (preimage2, cmdAdd2) = makeCmdAdd(p2, bob.underlyingActor.nodeParams.nodeId)
    val Right((ac2, add2)) = sendAdd(ac1, cmdAdd2, Local(UUID.randomUUID, None))
    assert(ac2.availableBalanceForSend == a - p1 - fee - p2 - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
    assert(ac2.availableBalanceForReceive == b)

    val (preimage3, cmdAdd3) = makeCmdAdd(p3, alice.underlyingActor.nodeParams.nodeId)
    val Right((bc1, add3)) = sendAdd(bc0, cmdAdd3, Local(UUID.randomUUID, None))
    assert(bc1.availableBalanceForSend == b - p3) // bob doesn't pay the fee
    assert(bc1.availableBalanceForReceive == a)

    val bc2 = receiveAdd(bc1, add1)
    assert(bc2.availableBalanceForSend == b - p3)
    assert(bc2.availableBalanceForReceive == a - p1 - fee)

    val bc3 = receiveAdd(bc2, add2)
    assert(bc3.availableBalanceForSend == b - p3)
    assert(bc3.availableBalanceForReceive == a - p1 - fee - p2 - fee)

    val ac3 = receiveAdd(ac2, add3)
    assert(ac3.availableBalanceForSend == a - p1 - fee - p2 - fee)
    assert(ac3.availableBalanceForReceive == b - p3)

    val (ac4, commit1) = sendCommit(ac3, alice.underlyingActor.nodeParams.keyManager)
    assert(ac4.availableBalanceForSend == a - p1 - fee - p2 - fee)
    assert(ac4.availableBalanceForReceive == b - p3)

    val (bc4, revocation1) = receiveCommit(bc3, commit1, bob.underlyingActor.nodeParams.keyManager)
    assert(bc4.availableBalanceForSend == b - p3)
    assert(bc4.availableBalanceForReceive == a - p1 - fee - p2 - fee)

    val (ac5, _) = receiveRevocation(ac4, revocation1)
    assert(ac5.availableBalanceForSend == a - p1 - fee - p2 - fee)
    assert(ac5.availableBalanceForReceive == b - p3)

    val (bc5, commit2) = sendCommit(bc4, bob.underlyingActor.nodeParams.keyManager)
    assert(bc5.availableBalanceForSend == b - p3)
    assert(bc5.availableBalanceForReceive == a - p1 - fee - p2 - fee)

    val (ac6, revocation2) = receiveCommit(ac5, commit2, alice.underlyingActor.nodeParams.keyManager)
    assert(ac6.availableBalanceForSend == a - p1 - fee - p2 - fee - fee) // alice has acknowledged b's hltc so it needs to pay the fee for it
    assert(ac6.availableBalanceForReceive == b - p3)

    val (bc6, _) = receiveRevocation(bc5, revocation2)
    assert(bc6.availableBalanceForSend == b - p3)
    assert(bc6.availableBalanceForReceive == a - p1 - fee - p2 - fee - fee)

    val (ac7, commit3) = sendCommit(ac6, alice.underlyingActor.nodeParams.keyManager)
    assert(ac7.availableBalanceForSend == a - p1 - fee - p2 - fee - fee)
    assert(ac7.availableBalanceForReceive == b - p3)

    val (bc7, revocation3) = receiveCommit(bc6, commit3, bob.underlyingActor.nodeParams.keyManager)
    assert(bc7.availableBalanceForSend == b - p3)
    assert(bc7.availableBalanceForReceive == a - p1 - fee - p2 - fee - fee)

    val (ac8, _) = receiveRevocation(ac7, revocation3)
    assert(ac8.availableBalanceForSend == a - p1 - fee - p2 - fee - fee)
    assert(ac8.availableBalanceForReceive == b - p3)

    val (ac9, bc8) = fulfillFunderHtlc(f, CMD_FULFILL_HTLC(1, preimage2), ac8, bc7, p2, fee)
    val (ac10, bc9) = fulfillFundeeHtlc(f, CMD_FULFILL_HTLC(0, preimage3), ac9, bc8, p3, fee)
    val (ac11, bc10) = fulfillFunderHtlc(f, CMD_FULFILL_HTLC(0, preimage1), ac10, bc9, p1, fee)

    assert(ac11.availableBalanceForSend == a - p1 - p2 + p3)
    assert(ac11.availableBalanceForReceive == b - p3 + p1 + p2)
    assert(bc10.availableBalanceForSend == b - p3 + p1 + p2)
    assert(bc10.availableBalanceForReceive == a - p1 - p2 + p3)
  }

  def fulfillFunderHtlc(f: FixtureParam, cmdFulfill: CMD_FULFILL_HTLC, ac: Commitments, bc: Commitments, htlcAmount: MilliSatoshi, htlcFee: MilliSatoshi): (Commitments, Commitments) = {
    import f._

    val (bc1, fulfill) = sendFulfill(bc, cmdFulfill)
    assert(bc1.availableBalanceForSend == bc.availableBalanceForSend + htlcAmount) // as soon as we have the fulfill, the balance increases
    assert(bc1.availableBalanceForReceive == bc.availableBalanceForReceive)

    val Right((ac1, _, _)) = receiveFulfill(ac, fulfill)
    assert(ac1.availableBalanceForSend == ac.availableBalanceForSend)
    assert(ac1.availableBalanceForReceive == ac.availableBalanceForReceive + htlcAmount)

    val (bc2, commit1) = sendCommit(bc1, bob.underlyingActor.nodeParams.keyManager)
    assert(bc2.availableBalanceForSend == bc.availableBalanceForSend + htlcAmount)
    assert(bc2.availableBalanceForReceive == bc.availableBalanceForReceive)

    val (ac2, revocation1) = receiveCommit(ac1, commit1, alice.underlyingActor.nodeParams.keyManager)
    assert(ac2.availableBalanceForSend == ac.availableBalanceForSend + htlcFee) // the fee is removed because the htlc has been removed from the commit tx
    assert(ac2.availableBalanceForReceive == ac.availableBalanceForReceive + htlcAmount)

    val (bc3, _) = receiveRevocation(bc2, revocation1)
    assert(bc3.availableBalanceForSend == bc.availableBalanceForSend + htlcAmount)
    assert(bc3.availableBalanceForReceive == bc.availableBalanceForReceive + htlcFee) // the fee is removed because alice revoked the commit tx that had the htlc

    val (ac3, commit2) = sendCommit(ac2, alice.underlyingActor.nodeParams.keyManager)
    assert(ac3.availableBalanceForSend == ac.availableBalanceForSend + htlcFee)
    assert(ac3.availableBalanceForReceive == ac.availableBalanceForReceive + htlcAmount)

    val (bc4, revocation2) = receiveCommit(bc3, commit2, bob.underlyingActor.nodeParams.keyManager)
    assert(bc4.availableBalanceForSend == bc.availableBalanceForSend + htlcAmount)
    assert(bc4.availableBalanceForReceive == bc.availableBalanceForReceive + htlcFee)

    val (ac4, _) = receiveRevocation(ac3, revocation2)
    assert(ac4.availableBalanceForSend == ac.availableBalanceForSend + htlcFee)
    assert(ac4.availableBalanceForReceive == ac.availableBalanceForReceive + htlcAmount)

    (ac4, bc4)
  }

  def failFunderHtlc(f: FixtureParam, cmdFail: CMD_FAIL_HTLC, ac: Commitments, bc: Commitments, htlcAmount: MilliSatoshi, htlcFee: MilliSatoshi): (Commitments, Commitments) = {
    import f._

    val (bc1, fail) = sendFail(bc, cmdFail, bob.underlyingActor.nodeParams.privateKey)
    assert(bc1.availableBalanceForSend == bc.availableBalanceForSend)
    assert(bc1.availableBalanceForReceive == bc.availableBalanceForReceive) // a's balance won't return to previous before she acknowledges the fail

    val Right((ac1, _, _)) = receiveFail(ac, fail)
    assert(ac1.availableBalanceForSend == ac.availableBalanceForSend)
    assert(ac1.availableBalanceForReceive == ac.availableBalanceForReceive)

    val (bc2, commit1) = sendCommit(bc1, bob.underlyingActor.nodeParams.keyManager)
    assert(bc2.availableBalanceForSend == bc.availableBalanceForSend)
    assert(bc2.availableBalanceForReceive == bc.availableBalanceForReceive)

    val (ac2, revocation1) = receiveCommit(ac1, commit1, alice.underlyingActor.nodeParams.keyManager)
    assert(ac2.availableBalanceForSend == ac.availableBalanceForSend + htlcAmount + htlcFee) // a's balance now returns to before the htlc was sent
    assert(ac2.availableBalanceForReceive == ac.availableBalanceForReceive)

    val (bc3, _) = receiveRevocation(bc2, revocation1)
    assert(bc3.availableBalanceForSend == bc.availableBalanceForSend)
    assert(bc3.availableBalanceForReceive == bc.availableBalanceForReceive + htlcAmount + htlcFee)

    val (ac3, commit2) = sendCommit(ac2, alice.underlyingActor.nodeParams.keyManager)
    assert(ac3.availableBalanceForSend == ac.availableBalanceForSend + htlcAmount + htlcFee)
    assert(ac3.availableBalanceForReceive == ac.availableBalanceForReceive)

    val (bc4, revocation2) = receiveCommit(bc3, commit2, bob.underlyingActor.nodeParams.keyManager)
    assert(bc4.availableBalanceForSend == bc.availableBalanceForSend)
    assert(bc4.availableBalanceForReceive == bc.availableBalanceForReceive + htlcAmount + htlcFee)

    val (ac4, _) = receiveRevocation(ac3, revocation2)
    assert(ac4.availableBalanceForSend == ac.availableBalanceForSend + htlcAmount + htlcFee)
    assert(ac4.availableBalanceForReceive == ac.availableBalanceForReceive)

    (ac4, bc4)
  }

  def fulfillFundeeHtlc(f: FixtureParam, cmdFulfill: CMD_FULFILL_HTLC, ac: Commitments, bc: Commitments, htlcAmount: MilliSatoshi, htlcFee: MilliSatoshi): (Commitments, Commitments) = {
    import f._

    val (ac1, fulfill) = sendFulfill(ac, cmdFulfill)
    assert(ac1.availableBalanceForSend == ac.availableBalanceForSend + htlcAmount + htlcFee) // as soon as we have the fulfill, the balance increases
    assert(ac1.availableBalanceForReceive == ac.availableBalanceForReceive)

    val Right((bc1, _, _)) = receiveFulfill(bc, fulfill)
    assert(bc1.availableBalanceForSend == bc.availableBalanceForSend)
    assert(bc1.availableBalanceForReceive == bc.availableBalanceForReceive + htlcAmount + htlcFee)

    val (ac2, commit1) = sendCommit(ac1, alice.underlyingActor.nodeParams.keyManager)
    assert(ac2.availableBalanceForSend == ac.availableBalanceForSend + htlcAmount + htlcFee)
    assert(ac2.availableBalanceForReceive == ac.availableBalanceForReceive)

    val (bc2, revocation1) = receiveCommit(bc1, commit1, bob.underlyingActor.nodeParams.keyManager)
    assert(bc2.availableBalanceForSend == bc.availableBalanceForSend)
    assert(bc2.availableBalanceForReceive == bc.availableBalanceForReceive + htlcAmount + htlcFee)

    val (ac3, _) = receiveRevocation(ac2, revocation1)
    assert(ac3.availableBalanceForSend == ac.availableBalanceForSend + htlcAmount + htlcFee)
    assert(ac3.availableBalanceForReceive == ac.availableBalanceForReceive)

    val (bc3, commit2) = sendCommit(bc2, bob.underlyingActor.nodeParams.keyManager)
    assert(bc3.availableBalanceForSend == bc.availableBalanceForSend)
    assert(bc3.availableBalanceForReceive == bc.availableBalanceForReceive + htlcAmount + htlcFee)

    val (ac4, revocation2) = receiveCommit(ac3, commit2, alice.underlyingActor.nodeParams.keyManager)
    assert(ac4.availableBalanceForSend == ac.availableBalanceForSend + htlcAmount + htlcFee)
    assert(ac4.availableBalanceForReceive == ac.availableBalanceForReceive)

    val (bc4, _) = receiveRevocation(bc3, revocation2)
    assert(bc4.availableBalanceForSend == bc.availableBalanceForSend)
    assert(bc4.availableBalanceForReceive == bc.availableBalanceForReceive + htlcAmount + htlcFee)

    (ac4, bc4)
  }

}