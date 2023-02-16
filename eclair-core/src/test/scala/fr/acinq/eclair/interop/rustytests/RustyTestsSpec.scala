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

import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.actor.{ActorRef, Props}
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.DummyOnChainWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.FakeTxPublisherFactory
import fr.acinq.eclair.payment.receive.{ForwardHandler, PaymentHandler}
import fr.acinq.eclair.wire.protocol.Init
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, TestFeeEstimator, TestKitBaseClass, TestUtils}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, Outcome}

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration._
import scala.io.Source

/**
 * Created by PM on 30/05/2016.
 */

class RustyTestsSpec extends TestKitBaseClass with Matchers with FixtureAnyFunSuiteLike with BeforeAndAfterAll {

  case class FixtureParam(ref: List[String], res: List[String])

  override def withFixture(test: OneArgTest): Outcome = {
    val blockHeight = new AtomicLong(0)
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
    val wallet = new DummyOnChainWallet()
    val feeEstimator = new TestFeeEstimator()
    val aliceNodeParams = Alice.nodeParams.copy(blockHeight = blockHeight, onChainFeeConf = Alice.nodeParams.onChainFeeConf.copy(feeEstimator = feeEstimator))
    val bobNodeParams = Bob.nodeParams.copy(blockHeight = blockHeight, onChainFeeConf = Bob.nodeParams.onChainFeeConf.copy(feeEstimator = feeEstimator))
    val channelConfig = ChannelConfig.standard
    val channelType = ChannelTypes.Standard()
    val alice: TestFSMRef[ChannelState, ChannelData, Channel] = TestFSMRef(new Channel(aliceNodeParams, wallet, Bob.nodeParams.nodeId, alice2blockchain.ref, relayer, FakeTxPublisherFactory(alice2blockchain)), alicePeer.ref)
    val bob: TestFSMRef[ChannelState, ChannelData, Channel] = TestFSMRef(new Channel(bobNodeParams, wallet, Alice.nodeParams.nodeId, bob2blockchain.ref, relayer, FakeTxPublisherFactory(bob2blockchain)), bobPeer.ref)
    val aliceInit = Init(Alice.channelParams.initFeatures)
    val bobInit = Init(Bob.channelParams.initFeatures)
    // alice and bob will both have 1 000 000 sat
    feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(10000 sat)))
    alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, 2000000 sat, dualFunded = false, feeEstimator.getFeeratePerKw(target = 2), feeEstimator.getFeeratePerKw(target = 6), Some(1000000000 msat), requireConfirmedInputs = false, Alice.channelParams, pipe, bobInit, ChannelFlags.Private, channelConfig, channelType)
    alice2blockchain.expectMsgType[TxPublisher.SetChannelId]
    bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, None, dualFunded = false, None, Bob.channelParams, pipe, aliceInit, channelConfig, channelType)
    bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
    pipe ! (alice, bob)
    within(30 seconds) {
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId]
      alice2blockchain.expectMsgType[WatchFundingConfirmed]
      bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
      bob2blockchain.expectMsgType[WatchFundingConfirmed]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
      val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
      alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
      bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
      alice2blockchain.expectMsgType[WatchFundingSpent]
      bob2blockchain.expectMsgType[WatchFundingSpent]
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      pipe ! new File(getClass.getResource(s"/scenarii/${test.name}.script").getFile)
      latch.await(30, TimeUnit.SECONDS)
      val ref = Source.fromFile(getClass.getResource(s"/scenarii/${test.name}.script.expected").getFile).getLines().filterNot(_.startsWith("#")).toList
      val res = Source.fromFile(new File(TestUtils.BUILD_DIRECTORY, "result.tmp")).getLines().filterNot(_.startsWith("#")).toList
      withFixture(test.toNoArgTest(FixtureParam(ref, res)))
    }
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  test("01-offer1") { f => assert(f.ref == f.res) }
  test("02-offer2") { f => assert(f.ref == f.res) }
  test("03-fulfill1") { f => assert(f.ref == f.res) }
  // test("04-two-commits-onedir") { f => assert(f.ref == f.res) } DOES NOT PASS : we now automatically sign back when we receive a revocation and have acked changes
  // test("05-two-commits-in-flight") { f => assert(f.ref == f.res)} DOES NOT PASS : cannot send two commit in a row (without having first revocation)
  test("10-offers-crossover") { f => assert(f.ref == f.res) }
  test("11-commits-crossover") { f => assert(f.ref == f.res) }
  /*test("13-fee") { f => assert(f.ref == f.res)}
  test("14-fee-twice") { f => assert(f.ref == f.res)}
  test("15-fee-twice-back-to-back") { f => assert(f.ref == f.res)}*/

}