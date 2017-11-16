package fr.acinq.eclair

import akka.actor.{ActorNotFound, ActorSystem, PoisonPill}
import akka.testkit.TestKit
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, fixture}

import scala.concurrent.Await

/**
  * This base class kills all actor between each tests.
  * Created by PM on 06/09/2016.
  */
abstract class TestkitBaseClass extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike with BeforeAndAfterEach with BeforeAndAfterAll {

  override def beforeAll {
    Globals.blockCount.set(400000)
    Globals.feeratesPerKw.set(FeeratesPerKw.single(TestConstants.feeratePerKw))
  }

  override def afterEach() {
    system.actorSelection(system / "*") ! PoisonPill
    intercept[ActorNotFound] {
      import scala.concurrent.duration._
      Await.result(system.actorSelection(system / "*").resolveOne(42 days), 42 days)
    }
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    Globals.feeratesPerKw.set(FeeratesPerKw.single(0))
  }

}
