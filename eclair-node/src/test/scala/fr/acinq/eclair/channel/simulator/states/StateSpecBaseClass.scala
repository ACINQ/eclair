package fr.acinq.eclair.channel.simulator.states

import akka.actor.{ActorNotFound, ActorRef, ActorSystem, PoisonPill, Terminated}
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, fixture}

import scala.concurrent.Await

/**
  * Created by PM on 06/09/2016.
  */
abstract class StateSpecBaseClass extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike with BeforeAndAfterEach with BeforeAndAfterAll {

  override def afterEach() {
    system.actorSelection(system / "*") ! PoisonPill
    intercept[ActorNotFound] {
      import scala.concurrent.duration._
      Await.result(system.actorSelection(system / "*").resolveOne(42 days), 42 days)
    }
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

}
