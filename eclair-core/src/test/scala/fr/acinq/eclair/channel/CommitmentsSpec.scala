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

  test("availableForSend") { f =>
    import f._

    val c0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    val sendAmount = Satoshi(420000).toMilliSatoshi
    assert(c0.availableBalanceForSend > sendAmount)

    val cmdAdd = makeCmdAdd(sendAmount, bob.underlyingActor.nodeParams.nodeId)._2
    val Right((c1, _)) = sendAdd(c0, cmdAdd, Local(UUID.randomUUID, None))
    assert(c1.availableBalanceForSend < c0.availableBalanceForSend)

    val (c2, commit_sig) = sendCommit(c1, alice.underlyingActor.nodeParams.keyManager)
    assert(c2.availableBalanceForSend == c1.availableBalanceForSend)
  }

}
