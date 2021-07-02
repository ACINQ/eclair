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

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{MakeFundingTxResponse, TestWallet}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsBase
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{TestConstants, TestKitBaseClass}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.ByteVector

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

/**
 * Created by PM on 05/07/2016.
 */

class WaitForFundingCreatedInternalStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsBase {

  case class FixtureParam(alice: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val noopWallet = new TestWallet {
      override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: FeeratePerKw): Future[MakeFundingTxResponse] = Promise[MakeFundingTxResponse].future // will never be completed
    }
    val setup = init(wallet = noopWallet)
    import setup._
    val channelVersion = ChannelVersion.STANDARD
    val aliceInit = Init(Alice.channelParams.features)
    val bobInit = Init(Bob.channelParams.features)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, None, Alice.channelParams, alice2bob.ref, bobInit, ChannelFlags.Empty, channelVersion)
      bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, Bob.channelParams, bob2alice.ref, aliceInit, channelVersion)
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
      withFixture(test.toNoArgTest(FixtureParam(alice, alice2bob, bob2alice, alice2blockchain)))
    }
  }

  test("recv Error") { f =>
    import f._
    alice ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    alice ! c
    sender.expectMsg(RES_SUCCESS(c, ByteVector32.Zeroes))
    awaitCond(alice.stateName == CLOSED)
  }

}
