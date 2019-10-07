package fr.acinq.eclair.channel.states

import akka.testkit.{TestFSMRef, TestKitBase, TestProbe}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.{NodeParams, TestConstants}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.channel.{CMD_HOSTED_INPUT_RECONNECTED, CMD_HOSTED_INVOKE_CHANNEL, CMD_HOSTED_MESSAGE, HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE, HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE, HostedChannel, HostedData, LocalChannelDown, LocalChannelUpdate, NORMAL, State, WAIT_FOR_INIT_INTERNAL}
import fr.acinq.eclair.wire.{InitHostedChannel, InvokeHostedChannel, StateUpdate}
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

  def reachNormal(setup: HostedSetupFixture, channelId: ByteVector32, tags: Set[String] = Set.empty): Unit = {
    import setup._
    bob ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Alice.nodeParams.nodeId, bob2alice.ref)
    alice ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Bob.nodeParams.nodeId, alice2bob.ref)
    awaitCond(bob.stateName == WAIT_FOR_INIT_INTERNAL)
    awaitCond(alice.stateName == WAIT_FOR_INIT_INTERNAL)
    bob ! CMD_HOSTED_INVOKE_CHANNEL(channelId, Alice.nodeParams.nodeId, Bob.channelParams.defaultFinalScriptPubKey)
    val bobInvokeHostedChannel = bob2alice.expectMsgType[InvokeHostedChannel]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobInvokeHostedChannel))
    awaitCond(alice.stateData.isInstanceOf[HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE])
    val aliceInitHostedChannel = alice2bob.expectMsgType[InitHostedChannel]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceInitHostedChannel))
    awaitCond(bob.stateData.isInstanceOf[HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE])
    val bobStateUpdate = bob2alice.expectMsgType[StateUpdate]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobStateUpdate))
    awaitCond(alice.stateName == NORMAL)
    val aliceStateUpdate = alice2bob.expectMsgType[StateUpdate]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceStateUpdate))
    awaitCond(bob.stateName == NORMAL)
  }
}
