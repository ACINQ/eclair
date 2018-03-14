package fr.acinq.eclair.interop.rustytests

import java.io.File
import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.NoopPaymentHandler
import fr.acinq.eclair.wire.Init
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, Matchers, fixture}

import scala.concurrent.duration._
import scala.io.Source

/**
  * Created by PM on 30/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class RustyTestsSpec extends TestKit(ActorSystem("test")) with Matchers with fixture.FunSuiteLike with BeforeAndAfterAll {

  type FixtureParam = Tuple2[List[String], List[String]]

  override def withFixture(test: OneArgTest) = {
    Globals.blockCount.set(0)
    val latch = new CountDownLatch(1)
    val pipe: ActorRef = system.actorOf(Props(new SynchronizationPipe(latch)))
    val alice2blockchain = TestProbe()
    val bob2blockchain = TestProbe()
    val paymentHandler = system.actorOf(Props(new NoopPaymentHandler()))
    // we just bypass the relayer for this test
    val relayer = paymentHandler
    val router = TestProbe()
    val wallet = new TestWallet
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(Alice.nodeParams, wallet, Bob.nodeParams.nodeId, alice2blockchain.ref, router.ref, relayer))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(Bob.nodeParams, wallet, Alice.nodeParams.nodeId, bob2blockchain.ref, router.ref, relayer))
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    // alice and bob will both have 1 000 000 sat
    Globals.feeratesPerKw.set(FeeratesPerKw.single(10000))
    alice ! INPUT_INIT_FUNDER("00" * 32, 2000000, 1000000000, Globals.feeratesPerKw.get.block_1, Globals.feeratesPerKw.get.blocks_6, Alice.channelParams, pipe, bobInit, ChannelFlags.Empty)
    bob ! INPUT_INIT_FUNDEE("00" * 32, Bob.channelParams, pipe, aliceInit)
    pipe ! (alice, bob)
    within(30 seconds) {
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.expectMsgType[WatchConfirmed]
      bob2blockchain.expectMsgType[WatchSpent]
      bob2blockchain.expectMsgType[WatchConfirmed]
      alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42)
      bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42)
      alice2blockchain.expectMsgType[WatchLost]
      bob2blockchain.expectMsgType[WatchLost]
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
    }
    pipe ! new File(getClass.getResource(s"/scenarii/${test.name}.script").getFile)
    latch.await(30, TimeUnit.SECONDS)
    val ref = Source.fromFile(getClass.getResource(s"/scenarii/${test.name}.script.expected").getFile).getLines().filterNot(_.startsWith("#")).toList
    val res = Source.fromFile(new File(s"${System.getProperty("buildDirectory")}/result.tmp")).getLines().filterNot(_.startsWith("#")).toList
    test((ref, res))
  }

  override def afterAll {
    Globals.feeratesPerKw.set(FeeratesPerKw.single(0))
    TestKit.shutdownActorSystem(system)
  }

  test("01-offer1") { case (ref, res) => assert(ref === res) }
  test("02-offer2") { case (ref, res) => assert(ref === res) }
  //test("03-fulfill1") { case (ref, res) => assert(ref === res) } TODO: check
  // test("04-two-commits-onedir") { case (ref, res) => assert(ref === res) } DOES NOT PASS : we now automatically sign back when we receive a revocation and have acked changes
  // test("05-two-commits-in-flight") { case (ref, res) => assert(ref === res)} DOES NOT PASS : cannot send two commit in a row (without having first revocation)
  test("10-offers-crossover") { case (ref, res) => assert(ref === res) }
  test("11-commits-crossover") { case (ref, res) => assert(ref === res) }
  /*test("13-fee") { case (ref, res) => assert(ref === res)}
  test("14-fee-twice") { case (ref, res) => assert(ref === res)}
  test("15-fee-twice-back-to-back") { case (ref, res) => assert(ref === res)}*/

}