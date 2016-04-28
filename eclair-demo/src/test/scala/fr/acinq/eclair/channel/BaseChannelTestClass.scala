package fr.acinq.eclair.channel

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestFSMRef, TestKit}
import fr.acinq.eclair.blockchain.PollingWatcher
import fr.acinq.eclair.channel.TestConstants.{Alice, Bob}
import org.scalatest.{BeforeAndAfterAll, Matchers, fixture}

/**
  * Created by PM on 28/04/2016.
  */
abstract class BaseChannelTestClass extends TestKit(ActorSystem("test")) with Matchers with fixture.FunSuiteLike with BeforeAndAfterAll {

  type FixtureParam = Tuple3[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestActorRef[Pipe]]

  override def withFixture(test: OneArgTest) = {
    val pipe: TestActorRef[Pipe] = TestActorRef[Pipe]
    val blockchainA = TestActorRef(new PollingWatcher(new TestBitcoinClient()))
    val blockchainB = TestActorRef(new PollingWatcher(new TestBitcoinClient()))
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(pipe, blockchainA, Alice.channelParams, "B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(pipe, blockchainB, Bob.channelParams, "A"))
    test((alice, bob, pipe))
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

}
