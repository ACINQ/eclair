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

package fr.acinq.eclair.interop.rustytests

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.actor.{ActorRef, Props}
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.TestConstants.{Alice, Bob, TestFeeEstimator}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.receive.{ForwardHandler, PaymentHandler}
import fr.acinq.eclair.wire.Init
import fr.acinq.eclair.{LongToBtcAmount, TestKitBaseClass, TestUtils}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, Outcome}

import scala.concurrent.duration._
import scala.io.Source

/**
 * Created by PM on 30/05/2016.
 */

class RustyTestsSpec extends TestKitBaseClass with Matchers with FixtureAnyFunSuiteLike with BeforeAndAfterAll {

  case class FixtureParam(ref: List[String], res: List[String])

  override def withFixture(test: OneArgTest): Outcome = {
    val blockCount = new AtomicLong(0)
    val latch = new CountDownLatch(1)
    val pipe: ActorRef = system.actorOf(Props(new SynchronizationPipe(latch)))
    val alicePeer = TestProbe()
    val bobPeer = TestProbe()
    TestUtils.forwardOutgoingToPipe(alicePeer, pipe)
    TestUtils.forwardOutgoingToPipe(bobPeer, pipe)
    val alice2blockchain = TestProbe()
    val bob2blockchain = TestProbe()
    val paymentHandler = system.actorOf(Props(new PaymentHandler(Bob.nodeParams, TestProbe().ref)))
    paymentHandler ! new ForwardHandler(TestProbe().ref)
    // we just bypass the relayer for this test
    val relayer = paymentHandler
    val wallet = new TestWallet
    val feeEstimator = new TestFeeEstimator
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(Alice.nodeParams.copy(blockCount = blockCount, onChainFeeConf = Alice.nodeParams.onChainFeeConf.copy(feeEstimator = feeEstimator)), wallet, Bob.nodeParams.nodeId, alice2blockchain.ref, relayer), alicePeer.ref)
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(Bob.nodeParams.copy(blockCount = blockCount, onChainFeeConf = Bob.nodeParams.onChainFeeConf.copy(feeEstimator = feeEstimator)), wallet, Alice.nodeParams.nodeId, bob2blockchain.ref, relayer), bobPeer.ref)
    val aliceInit = Init(Alice.channelParams.features)
    val bobInit = Init(Bob.channelParams.features)
    // alice and bob will both have 1 000 000 sat
    feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(10000 sat)))
    alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, 2000000 sat, 1000000000 msat, feeEstimator.getFeeratePerKw(target = 2), feeEstimator.getFeeratePerKw(target = 6), Alice.channelParams, pipe, bobInit, ChannelFlags.Empty, ChannelVersion.STANDARD)
    bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, Bob.channelParams, pipe, aliceInit, ChannelVersion.STANDARD)
    pipe ! (alice, bob)
    within(30 seconds) {
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.expectMsgType[WatchConfirmed]
      bob2blockchain.expectMsgType[WatchSpent]
      bob2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
      val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
      alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42, fundingTx)
      bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42, fundingTx)
      alice2blockchain.expectMsgType[WatchLost]
      bob2blockchain.expectMsgType[WatchLost]
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      pipe ! new File(getClass.getResource(s"/scenarii/${test.name}.script").getFile)
      latch.await(30, TimeUnit.SECONDS)
      val ref = Source.fromFile(getClass.getResource(s"/scenarii/${test.name}.script.expected").getFile).getLines().filterNot(_.startsWith("#")).toList
      val res = Source.fromFile(new File(TestUtils.BUILD_DIRECTORY, "result.tmp")).getLines().filterNot(_.startsWith("#")).toList
      withFixture(test.toNoArgTest(FixtureParam(ref, res)))
    }
  }

  override def afterAll() {
    TestKit.shutdownActorSystem(system)
  }

  test("01-offer1") { f => assert(f.ref === f.res) }
  test("02-offer2") { f => assert(f.ref === f.res) }
  test("03-fulfill1") { f => assert(f.ref === f.res) }
  // test("04-two-commits-onedir") { f => assert(f.ref === f.res) } DOES NOT PASS : we now automatically sign back when we receive a revocation and have acked changes
  // test("05-two-commits-in-flight") { f => assert(f.ref === f.res)} DOES NOT PASS : cannot send two commit in a row (without having first revocation)
  test("10-offers-crossover") { f => assert(f.ref === f.res) }
  test("11-commits-crossover") { f => assert(f.ref === f.res) }
  /*test("13-fee") { f => assert(f.ref === f.res)}
  test("14-fee-twice") { f => assert(f.ref === f.res)}
  test("15-fee-twice-back-to-back") { f => assert(f.ref === f.res)}*/

}