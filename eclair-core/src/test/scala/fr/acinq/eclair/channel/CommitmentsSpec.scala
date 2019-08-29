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

import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.{TestkitBaseClass, _}
import fr.acinq.eclair.channel.Commitments._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.payment.Local
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

  test("correct values for availableForSend/availableForReceive") { f =>
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
    assert(ac6.availableBalanceForSend == a - p) // the balance increases a little, because there is no htlc output so less fees in the commitment tx
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

}
