package fr.acinq.eclair.channel.simulator.states.a

import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.channel._
import lightning.{error, open_channel}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, fixture}

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class OpenWaitForOpenNoAnchorStateSpec extends TestKit(ActorSystem("test"))  with fixture.FunSuiteLike with BeforeAndAfterAll {

  type FixtureParam = Tuple4[TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val bob2blockchain = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, Alice.channelParams, "B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, Bob.channelParams, "A"))
    within(30 seconds) {
      bob2alice.expectMsgType[open_channel]
      awaitCond(bob.stateName == OPEN_WAIT_FOR_OPEN_NOANCHOR)
    }
    test((bob, alice2bob, bob2alice, bob2blockchain))
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  test("recv open_channel") { case (bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      alice2bob.expectMsgType[open_channel]
      alice2bob.forward(bob)
      awaitCond(bob.stateName == OPEN_WAIT_FOR_ANCHOR)
    }
  }

  test("recv error") { case (bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      bob ! error(Some("oops"))
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv CMD_CLOSE") { case (bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      bob ! CMD_CLOSE(None)
      awaitCond(bob.stateName == CLOSED)
    }
  }

}
