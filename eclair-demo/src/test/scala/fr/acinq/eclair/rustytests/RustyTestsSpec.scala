package fr.acinq.eclair.rustytests

import java.io.File

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestFSMRef, TestKit}
import fr.acinq.protos.{NewChannel, NewData, NewState, TestPipe}
import org.scalatest.{BeforeAndAfterAll, Matchers, fixture}

import scala.concurrent.duration._
import scala.io.Source

/**
  * Created by PM on 18/05/2016.
  */
class RustyTestsSpec extends TestKit(ActorSystem("test")) with Matchers with fixture.FunSuiteLike with BeforeAndAfterAll {

  type FixtureParam = Tuple2[List[String], List[String]]

  override def withFixture(test: OneArgTest) = {
    val pipe: TestActorRef[TestPipe] = TestActorRef[TestPipe]
    val alice: TestFSMRef[NewState, NewData, NewChannel] = TestFSMRef(new NewChannel(pipe))
    val bob: TestFSMRef[NewState, NewData, NewChannel] = TestFSMRef(new NewChannel(pipe))
    pipe !(alice, bob, new File(s"rusty-scripts/${test.name}.script"))
    awaitCond(pipe.underlying.isTerminated, 30 seconds)
    val ref = Source.fromFile(new File(s"rusty-scripts/${test.name}.script.expected")).getLines().filterNot(_.startsWith("#")).toList
    val res = Source.fromFile(new File("result.txt")).getLines().filterNot(_.startsWith("#")).toList
    test((ref, res))
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  test("01-offer1") { case (ref, res) => assert(ref === res)}
  test("02-offer2") { case (ref, res) => assert(ref === res)}
  test("03-fulfill1") { case (ref, res) => assert(ref === res)}
  test("04-two-commits-onedir") { case (ref, res) => assert(ref === res)}
  test("05-two-commits-in-flight") { case (ref, res) => assert(ref === res)}
  test("10-offers-crossover") { case (ref, res) => assert(ref === res)}
  test("11-commits-crossover") { case (ref, res) => assert(ref === res)}
  test("13-fee") { case (ref, res) => assert(ref === res)}
  test("14-fee-twice") { case (ref, res) => assert(ref === res)}
  test("15-fee-twice-back-to-back") { case (ref, res) => assert(ref === res)}

}
