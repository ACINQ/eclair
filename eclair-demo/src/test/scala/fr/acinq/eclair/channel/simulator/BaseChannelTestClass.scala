package fr.acinq.eclair.channel.simulator

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props, Terminated}
import akka.testkit.{TestActorRef, TestFSMRef, TestKit, TestProbe}
import fr.acinq.eclair.{TestBitcoinClient, TestConstants}
import fr.acinq.eclair.blockchain.PeerWatcher
import TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.peer.NewBlock
import fr.acinq.eclair.channel._
import org.scalatest.{BeforeAndAfterAll, Matchers, fixture}

import scala.concurrent.duration._

/**
  * Created by PM on 28/04/2016.
  */
abstract class BaseChannelTestClass extends TestKit(ActorSystem("test")) with Matchers with fixture.FunSuiteLike with BeforeAndAfterAll {

  type FixtureParam = Tuple3[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], ActorRef]

  override def withFixture(test: OneArgTest) = {
    val pipe: ActorRef = system.actorOf(Props[Pipe])
    val watcherA = TestActorRef(new PeerWatcher(new TestBitcoinClient(), 300))
    val watcherB = TestActorRef(new PeerWatcher(new TestBitcoinClient(), 300))
    val paymentHandler = TestActorRef(new NoopPaymentHandler())
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(pipe, watcherA, paymentHandler, Alice.channelParams, "B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(pipe, watcherB, paymentHandler, Bob.channelParams, "A"))
    val outcome = test((alice, bob, pipe))
    val shutdownProbe = TestProbe()
    for (a <- pipe :: watcherA :: watcherB :: paymentHandler :: alice :: bob :: Nil) {
      shutdownProbe.watch(a)
      a ! PoisonPill
      shutdownProbe.expectMsgType[Terminated]
    }
    outcome
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

}
