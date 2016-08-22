package fr.acinq.eclair.channel.simulator

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestActorRef, TestFSMRef, TestKit}
import fr.acinq.eclair.{TestBitcoinClient, TestConstants}
import fr.acinq.eclair.blockchain.PollingWatcher
import TestConstants.{Alice, Bob}
import fr.acinq.eclair.channel._
import org.scalatest.{BeforeAndAfterAll, Matchers, fixture}

/**
  * Created by PM on 28/04/2016.
  */
abstract class BaseChannelTestClass extends TestKit(ActorSystem("test")) with Matchers with fixture.FunSuiteLike with BeforeAndAfterAll {

  type FixtureParam = Tuple3[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], ActorRef]

  override def withFixture(test: OneArgTest) = {
    val pipe: ActorRef = system.actorOf(Props[Pipe])
    val blockchainA = TestActorRef(new PollingWatcher(new TestBitcoinClient()))
    val blockchainB = TestActorRef(new PollingWatcher(new TestBitcoinClient()))
    val paymentHandler = TestActorRef(new NoopPaymentHandler())
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(pipe, blockchainA, paymentHandler, Alice.channelParams, "B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(pipe, blockchainB, paymentHandler, Bob.channelParams, "A"))
    test((alice, bob, pipe))
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

}
