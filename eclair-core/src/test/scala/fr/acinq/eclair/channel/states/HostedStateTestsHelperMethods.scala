package fr.acinq.eclair.channel.states

import akka.testkit.{TestFSMRef, TestKitBase, TestProbe}
import fr.acinq.eclair.{NodeParams, TestConstants}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.channel.{HostedChannel, HostedData, LocalChannelDown, LocalChannelUpdate, State}
import org.scalatest.{ParallelTestExecution, fixture}

trait HostedStateTestsHelperMethods extends TestKitBase with fixture.TestSuite with ParallelTestExecution {
  case class HostedSetupFixture(alice: TestFSMRef[State, HostedData, HostedChannel],
                          bob: TestFSMRef[State, HostedData, HostedChannel],
                          alice2bob: TestProbe,
                          bob2alice: TestProbe,
                          router: TestProbe,
                          relayerA: TestProbe,
                          relayerB: TestProbe,
                          channelUpdateListener: TestProbe) {
    def currentBlockHeight: Long = alice.underlyingActor.nodeParams.currentBlockHeight
  }

  def init(nodeParamsA: NodeParams = TestConstants.Alice.nodeParams, nodeParamsB: NodeParams = TestConstants.Bob.nodeParams): HostedSetupFixture = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val relayerA = TestProbe()
    val relayerB = TestProbe()
    val channelUpdateListener = TestProbe()
    system.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelUpdate])
    system.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelDown])
    val router = TestProbe()

    val alice: TestFSMRef[State, HostedData, HostedChannel] = TestFSMRef(new HostedChannel(nodeParamsA, Bob.nodeParams.nodeId, router.ref, relayerA.ref))
    val bob: TestFSMRef[State, HostedData, HostedChannel] = TestFSMRef(new HostedChannel(nodeParamsB, Alice.nodeParams.nodeId, router.ref, relayerB.ref))
    HostedSetupFixture(alice, bob, alice2bob, bob2alice, router, relayerA, relayerB, channelUpdateListener)
  }
}
