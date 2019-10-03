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
import fr.acinq.eclair.wire.IncorrectOrUnknownPaymentDetails
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

  test("take additional HTLC fee into account") { f =>
    import f._
    val htlcOutputFee = 1720000 msat
    val a = 772760000 msat // initial balance alice
    val ac0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    val bc0 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments
    // we need to take the additional HTLC fee into account because balances are above the trim threshold.
    assert(ac0.availableBalanceForSend == a - htlcOutputFee)
    assert(bc0.availableBalanceForReceive == a - htlcOutputFee)

    val (_, cmdAdd) = makeCmdAdd(a - htlcOutputFee - 1000.msat, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight)
    val Right((ac1, add)) = sendAdd(ac0, cmdAdd, Local(UUID.randomUUID, None), currentBlockHeight)
    val bc1 = receiveAdd(bc0, add)
    val (_, commit1) = sendCommit(ac1, alice.underlyingActor.nodeParams.keyManager)
    val (bc2, _) = receiveCommit(bc1, commit1, bob.underlyingActor.nodeParams.keyManager)
    // we don't take into account the additional HTLC fee since Alice's balance is below the trim threshold.
    assert(ac1.availableBalanceForSend == 1000.msat)
    assert(bc2.availableBalanceForReceive == 1000.msat)
  }

  test("correct values for availableForSend/availableForReceive (success case)") { f =>
    import f._

    val fee = 1720000 msat // fee due to the additional htlc output
    val a = (772760000 msat) - fee // initial balance alice
    val b = 190000000 msat // initial balance bob
    val p = 42000000 msat // a->b payment

    val ac0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    val bc0 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments

    assert(ac0.availableBalanceForSend > p) // alice can afford the payment
    assert(ac0.availableBalanceForSend == a)
    assert(ac0.availableBalanceForReceive == b)
    assert(bc0.availableBalanceForSend == b)
    assert(bc0.availableBalanceForReceive == a)

    val (payment_preimage, cmdAdd) = makeCmdAdd(p, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight)
    val Right((ac1, add)) = sendAdd(ac0, cmdAdd, Local(UUID.randomUUID, None), currentBlockHeight)
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

    val cmdFulfill = CMD_FULFILL_HTLC(0, payment_preimage)
    val (bc5, fulfill) = sendFulfill(bc4, cmdFulfill)
    assert(bc5.availableBalanceForSend == b + p) // as soon as we have the fulfill, the balance increases
    assert(bc5.availableBalanceForReceive == a - p - fee)

    val Right((ac5, _, _)) = receiveFulfill(ac4, fulfill)
    assert(ac5.availableBalanceForSend == a - p - fee)
    assert(ac5.availableBalanceForReceive == b + p)

    val (bc6, commit3) = sendCommit(bc5, bob.underlyingActor.nodeParams.keyManager)
    assert(bc6.availableBalanceForSend == b + p)
    assert(bc6.availableBalanceForReceive == a - p - fee)

    val (ac6, revocation3) = receiveCommit(ac5, commit3, alice.underlyingActor.nodeParams.keyManager)
    assert(ac6.availableBalanceForSend == a - p)
    assert(ac6.availableBalanceForReceive == b + p)

    val (bc7, _) = receiveRevocation(bc6, revocation3)
    assert(bc7.availableBalanceForSend == b + p)
    assert(bc7.availableBalanceForReceive == a - p)

    val (ac7, commit4) = sendCommit(ac6, alice.underlyingActor.nodeParams.keyManager)
    assert(ac7.availableBalanceForSend == a - p)
    assert(ac7.availableBalanceForReceive == b + p)

    val (bc8, revocation4) = receiveCommit(bc7, commit4, bob.underlyingActor.nodeParams.keyManager)
    assert(bc8.availableBalanceForSend == b + p)
    assert(bc8.availableBalanceForReceive == a - p)

    val (ac8, _) = receiveRevocation(ac7, revocation4)
    assert(ac8.availableBalanceForSend == a - p)
    assert(ac8.availableBalanceForReceive == b + p)
  }

  test("correct values for availableForSend/availableForReceive (failure case)") { f =>
    import f._

    val fee = 1720000 msat // fee due to the additional htlc output
    val a = (772760000 msat) - fee // initial balance alice
    val b = 190000000 msat // initial balance bob
    val p = 42000000 msat // a->b payment

    val ac0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    val bc0 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments

    assert(ac0.availableBalanceForSend > p) // alice can afford the payment
    assert(ac0.availableBalanceForSend == a)
    assert(ac0.availableBalanceForReceive == b)
    assert(bc0.availableBalanceForSend == b)
    assert(bc0.availableBalanceForReceive == a)

    val (_, cmdAdd) = makeCmdAdd(p, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight)
    val Right((ac1, add)) = sendAdd(ac0, cmdAdd, Local(UUID.randomUUID, None), currentBlockHeight)
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

    val cmdFail = CMD_FAIL_HTLC(0, Right(IncorrectOrUnknownPaymentDetails(p, 42)))
    val (bc5, fail) = sendFail(bc4, cmdFail, bob.underlyingActor.nodeParams.privateKey)
    assert(bc5.availableBalanceForSend == b)
    assert(bc5.availableBalanceForReceive == a - p - fee) // a's balance won't return to previous before she acknowledges the fail

    val Right((ac5, _, _)) = receiveFail(ac4, fail)
    assert(ac5.availableBalanceForSend == a - p - fee)
    assert(ac5.availableBalanceForReceive == b)

    val (bc6, commit3) = sendCommit(bc5, bob.underlyingActor.nodeParams.keyManager)
    assert(bc6.availableBalanceForSend == b)
    assert(bc6.availableBalanceForReceive == a - p - fee)

    val (ac6, revocation3) = receiveCommit(ac5, commit3, alice.underlyingActor.nodeParams.keyManager)
    assert(ac6.availableBalanceForSend == a)
    assert(ac6.availableBalanceForReceive == b)

    val (bc7, _) = receiveRevocation(bc6, revocation3)
    assert(bc7.availableBalanceForSend == b)
    assert(bc7.availableBalanceForReceive == a)

    val (ac7, commit4) = sendCommit(ac6, alice.underlyingActor.nodeParams.keyManager)
    assert(ac7.availableBalanceForSend == a)
    assert(ac7.availableBalanceForReceive == b)

    val (bc8, revocation4) = receiveCommit(bc7, commit4, bob.underlyingActor.nodeParams.keyManager)
    assert(bc8.availableBalanceForSend == b)
    assert(bc8.availableBalanceForReceive == a)

    val (ac8, _) = receiveRevocation(ac7, revocation4)
    assert(ac8.availableBalanceForSend == a)
    assert(ac8.availableBalanceForReceive == b)
  }

  test("correct values for availableForSend/availableForReceive (multiple htlcs)") { f =>
    import f._

    val fee = 1720000 msat // fee due to the additional htlc output
    val a = (772760000 msat) - fee // initial balance alice
    val b = 190000000 msat // initial balance bob
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

    val (payment_preimage1, cmdAdd1) = makeCmdAdd(p1, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight)
    val Right((ac1, add1)) = sendAdd(ac0, cmdAdd1, Local(UUID.randomUUID, None), currentBlockHeight)
    assert(ac1.availableBalanceForSend == a - p1 - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
    assert(ac1.availableBalanceForReceive == b)

    val (_, cmdAdd2) = makeCmdAdd(p2, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight)
    val Right((ac2, add2)) = sendAdd(ac1, cmdAdd2, Local(UUID.randomUUID, None), currentBlockHeight)
    assert(ac2.availableBalanceForSend == a - p1 - fee - p2 - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
    assert(ac2.availableBalanceForReceive == b)

    val (payment_preimage3, cmdAdd3) = makeCmdAdd(p3, alice.underlyingActor.nodeParams.nodeId, currentBlockHeight)
    val Right((bc1, add3)) = sendAdd(bc0, cmdAdd3, Local(UUID.randomUUID, None), currentBlockHeight)
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

    val cmdFulfill1 = CMD_FULFILL_HTLC(0, payment_preimage1)
    val (bc8, fulfill1) = sendFulfill(bc7, cmdFulfill1)
    assert(bc8.availableBalanceForSend == b + p1 - p3) // as soon as we have the fulfill, the balance increases
    assert(bc8.availableBalanceForReceive == a - p1 - fee - p2 - fee - fee)

    val cmdFail2 = CMD_FAIL_HTLC(1, Right(IncorrectOrUnknownPaymentDetails(p2, 42)))
    val (bc9, fail2) = sendFail(bc8, cmdFail2, bob.underlyingActor.nodeParams.privateKey)
    assert(bc9.availableBalanceForSend == b + p1 - p3)
    assert(bc9.availableBalanceForReceive == a - p1 - fee - p2 - fee - fee) // a's balance won't return to previous before she acknowledges the fail

    val cmdFulfill3 = CMD_FULFILL_HTLC(0, payment_preimage3)
    val (ac9, fulfill3) = sendFulfill(ac8, cmdFulfill3)
    assert(ac9.availableBalanceForSend == a - p1 - fee - p2 - fee + p3) // as soon as we have the fulfill, the balance increases
    assert(ac9.availableBalanceForReceive == b - p3)

    val Right((ac10, _, _)) = receiveFulfill(ac9, fulfill1)
    assert(ac10.availableBalanceForSend == a - p1 - fee - p2 - fee + p3)
    assert(ac10.availableBalanceForReceive == b + p1 - p3)

    val Right((ac11, _, _)) = receiveFail(ac10, fail2)
    assert(ac11.availableBalanceForSend == a - p1 - fee - p2 - fee + p3)
    assert(ac11.availableBalanceForReceive == b + p1 - p3)

    val Right((bc10, _, _)) = receiveFulfill(bc9, fulfill3)
    assert(bc10.availableBalanceForSend == b + p1 - p3)
    assert(bc10.availableBalanceForReceive == a - p1 - fee - p2 - fee + p3) // the fee for p3 disappears

    val (ac12, commit4) = sendCommit(ac11, alice.underlyingActor.nodeParams.keyManager)
    assert(ac12.availableBalanceForSend == a - p1 - fee - p2 - fee + p3)
    assert(ac12.availableBalanceForReceive == b + p1 - p3)

    val (bc11, revocation4) = receiveCommit(bc10, commit4, bob.underlyingActor.nodeParams.keyManager)
    assert(bc11.availableBalanceForSend == b + p1 - p3)
    assert(bc11.availableBalanceForReceive == a - p1 - fee - p2 - fee + p3)

    val (ac13, _) = receiveRevocation(ac12, revocation4)
    assert(ac13.availableBalanceForSend == a - p1 - fee - p2 - fee + p3)
    assert(ac13.availableBalanceForReceive == b + p1 - p3)

    val (bc12, commit5) = sendCommit(bc11, bob.underlyingActor.nodeParams.keyManager)
    assert(bc12.availableBalanceForSend == b + p1 - p3)
    assert(bc12.availableBalanceForReceive == a - p1 - fee - p2 - fee + p3)

    val (ac14, revocation5) = receiveCommit(ac13, commit5, alice.underlyingActor.nodeParams.keyManager)
    assert(ac14.availableBalanceForSend == a - p1 + p3)
    assert(ac14.availableBalanceForReceive == b + p1 - p3)

    val (bc13, _) = receiveRevocation(bc12, revocation5)
    assert(bc13.availableBalanceForSend == b + p1 - p3)
    assert(bc13.availableBalanceForReceive == a - p1 + p3)

    val (ac15, commit6) = sendCommit(ac14, alice.underlyingActor.nodeParams.keyManager)
    assert(ac15.availableBalanceForSend == a - p1 + p3)
    assert(ac15.availableBalanceForReceive == b + p1 - p3)

    val (bc14, revocation6) = receiveCommit(bc13, commit6, bob.underlyingActor.nodeParams.keyManager)
    assert(bc14.availableBalanceForSend == b + p1 - p3)
    assert(bc14.availableBalanceForReceive == a - p1 + p3)

    val (ac16, _) = receiveRevocation(ac15, revocation6)
    assert(ac16.availableBalanceForSend == a - p1 + p3)
    assert(ac16.availableBalanceForReceive == b + p1 - p3)
  }

}
