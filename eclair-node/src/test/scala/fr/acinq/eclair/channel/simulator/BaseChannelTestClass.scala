package fr.acinq.eclair.channel.simulator

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props, Terminated}
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.eclair.TestBitcoinClient
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.PeerWatcher
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.NoopPaymentHandler
import org.scalatest.{BeforeAndAfterAll, Matchers, fixture}

/**
  * Created by PM on 28/04/2016.
  */
abstract class BaseChannelTestClass extends TestKit(ActorSystem("test")) with Matchers with fixture.FunSuiteLike with BeforeAndAfterAll {

  type FixtureParam = Tuple3[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], ActorRef]

  override def withFixture(test: OneArgTest) = {
    val pipe: ActorRef = system.actorOf(Props[Pipe])
    val watcherA = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient(), 300)))
    val watcherB = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient(), 300)))
    val paymentHandler = system.actorOf(Props(new NoopPaymentHandler()))
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
