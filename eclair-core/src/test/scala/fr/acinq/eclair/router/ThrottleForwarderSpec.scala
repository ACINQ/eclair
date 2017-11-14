package fr.acinq.eclair.router

import akka.actor.{ActorSystem, Terminated}
import akka.testkit.{TestKit, TestProbe}
import org.junit.runner.RunWith
import org.scalatest.FunSuiteLike
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Base class for router testing.
  * It is re-used in payment FSM tests
  * Created by PM on 29/08/2016.
  */
@RunWith(classOf[JUnitRunner])
class ThrottleForwarderSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  test("forward and delay messages, then dies") {

    val target = TestProbe()
    val messages = 0 until 100

    val forwarder = system.actorOf(ThrottleForwarder.props(target.ref, messages, 7, 5 millis))

    target.watch(forwarder)
    messages.foreach(target.expectMsg(_))
    target.expectMsgType[Terminated]

  }


}
