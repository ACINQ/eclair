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

package fr.acinq.eclair.channel.states.b

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}

import scala.concurrent.duration._
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{Block, ByteVector32, Script, Transaction}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.wire.{AcceptChannel, Error, Init, OpenChannel}
import org.scalatest.Outcome

import scala.concurrent.{Await, ExecutionContext, Future, Promise}

class WaitForFundingSignedInternalStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  case class FixtureParam(alice: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {

    val nonReturningWallet = new TestWallet {
      override def signTransactionComplete(tx: Transaction): Future[Transaction] = Promise[Transaction]().future
    }

    val setup = init(wallet = nonReturningWallet)
    import setup._
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    within(30 seconds) {
      val monitor = TestProbe()
      alice ! SubscribeTransitionCallBack(monitor.ref)
      val CurrentState(_, WAIT_FOR_INIT_INTERNAL) = monitor.expectMsgClass(classOf[CurrentState[_]])
      alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, alice2bob.ref, bobInit, ChannelFlags.Empty)
      bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, Bob.channelParams, bob2alice.ref, aliceInit)
      val Transition(_, WAIT_FOR_INIT_INTERNAL, WAIT_FOR_FUNDING_INTERNAL_CREATED) = monitor.expectMsgClass(classOf[Transition[_]])
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      val Transition(_, WAIT_FOR_FUNDING_INTERNAL_CREATED, WAIT_FOR_ACCEPT_CHANNEL) = monitor.expectMsgClass(classOf[Transition[_]])
      val Transition(_, WAIT_FOR_ACCEPT_CHANNEL, WAIT_FOR_FUNDING_INTERNAL_SIGNED) = monitor.expectMsgClass(classOf[Transition[_]])
      withFixture(test.toNoArgTest(FixtureParam(alice, alice2bob, bob2alice, alice2blockchain)))
    }
  }

  test("recv Error") { f =>
    import f._

    assert(alice.stateName == WAIT_FOR_FUNDING_INTERNAL_SIGNED)
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL_SIGNED].unsignedFundingTx
    assert(alice.underlyingActor.wallet.asInstanceOf[TestWallet].rolledback.isEmpty)

    alice ! Error(ByteVector32.Zeroes, "oops")

    awaitCond({
      val rolledBackTx = alice.underlyingActor.wallet.asInstanceOf[TestWallet].rolledback.head
      rolledBackTx.txOut == fundingTx.txOut && rolledBackTx.txIn.map(_.outPoint) == fundingTx.txIn.map(_.outPoint)
    })
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv CMD_CLOSE") { f =>
    import f._

    assert(alice.stateName == WAIT_FOR_FUNDING_INTERNAL_SIGNED)
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL_SIGNED].unsignedFundingTx
    assert(alice.underlyingActor.wallet.asInstanceOf[TestWallet].rolledback.isEmpty)

    alice ! CMD_CLOSE(None)

    awaitCond({
      val rolledBackTx = alice.underlyingActor.wallet.asInstanceOf[TestWallet].rolledback.head
      rolledBackTx.txOut == fundingTx.txOut && rolledBackTx.txIn.map(_.outPoint) == fundingTx.txIn.map(_.outPoint)
    })
    awaitCond(alice.stateName == CLOSED)
  }

}
