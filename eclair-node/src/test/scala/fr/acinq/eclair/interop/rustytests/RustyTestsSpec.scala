package fr.acinq.eclair.interop.rustytests

import java.io.File
import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.PeerWatcher
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.NoopPaymentHandler
import fr.acinq.eclair.wire.Init
import fr.acinq.eclair.{Globals, TestBitcoinClient}
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
    val blockchainA = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient())))
    val blockchainB = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient())))
    val paymentHandler = system.actorOf(Props(new NoopPaymentHandler()))
    // we just bypass the relayer for this test
    val relayer = paymentHandler
    val router = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(Alice.nodeParams, Bob.id, blockchainA, router.ref, relayer))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(Bob.nodeParams, Alice.id, blockchainB, router.ref, relayer))
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    // alice and bob will both have 1 000 000 sat
    alice ! INPUT_INIT_FUNDER(0, 2000000, 1000000000, Alice.channelParams, pipe, bobInit)
    bob ! INPUT_INIT_FUNDEE(0, Bob.channelParams, pipe, aliceInit)
    pipe ! (alice, bob)
    within(30 seconds) {
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
    }
    pipe ! new File(getClass.getResource(s"/scenarii/${test.name}.script").getFile)
    latch.await(30, TimeUnit.SECONDS)
    val ref = Source.fromFile(getClass.getResource(s"/scenarii/${test.name}.script.expected").getFile).getLines().filterNot(_.startsWith("#")).toList
    val res = Source.fromFile(new File("result.txt")).getLines().filterNot(_.startsWith("#")).toList
    test((ref, res))
  }

  override def afterAll {
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