package fr.acinq.eclair.channel

import akka.testkit.TestProbe
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._

import scala.concurrent.duration._
import fr.acinq.eclair.TestkitBaseClass
import fr.acinq.eclair.channel.states.HostedStateTestsHelperMethods
import fr.acinq.eclair.wire.{ChannelUpdate, InitHostedChannel, InvokeHostedChannel, LastCrossSignedState, StateUpdate}
import org.scalatest.Outcome

class HostedChannelEstablishmentSpec extends TestkitBaseClass with HostedStateTestsHelperMethods {

  type FixtureParam = HostedSetupFixture

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  val channelId: ByteVector32 = hostedChanId(Bob.nodeParams.nodeId.value, Alice.nodeParams.nodeId.value)

  override def withFixture(test: OneArgTest): Outcome = withFixture(test.toNoArgTest(init()))

  test("Successful invoke") { f =>
    import f._
    reachNormal(f, channelId)
    val bobCommits = bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS]
    val aliceCommits = alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS]
    assert(!bobCommits.isHost)
    assert(aliceCommits.isHost)
    assert(bobCommits.lastCrossSignedState.verifyRemoteSig(Alice.nodeParams.nodeId))
    assert(aliceCommits.lastCrossSignedState.verifyRemoteSig(Bob.nodeParams.nodeId))
  }

  test("Host rejects an invalid refundScriptPubKey, then successful retry") { f =>
    import f._
    bob ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Alice.nodeParams.nodeId, bob2alice.ref)
    alice ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Bob.nodeParams.nodeId, alice2bob.ref)
    awaitCond(bob.stateName == WAIT_FOR_INIT_INTERNAL)
    awaitCond(alice.stateName == WAIT_FOR_INIT_INTERNAL)
    bob ! CMD_HOSTED_INVOKE_CHANNEL(channelId, Alice.nodeParams.nodeId, ByteVector32.Zeroes)
    val bobInvokeHostedChannel = bob2alice.expectMsgType[InvokeHostedChannel]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobInvokeHostedChannel))
    val aliceError = alice2bob.expectMsgType[wire.Error]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceError))
    awaitCond(bob.stateName == CLOSED)
    bob ! CMD_HOSTED_INVOKE_CHANNEL(channelId, Alice.nodeParams.nodeId, Bob.channelParams.defaultFinalScriptPubKey)
    val bobInvokeHostedChannel1 = bob2alice.expectMsgType[InvokeHostedChannel]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobInvokeHostedChannel1))
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
    val bobCommits = bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS]
    val aliceCommits = alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS]
    alice2bob.expectMsgType[ChannelUpdate]
    assert(!bobCommits.isHost)
    assert(aliceCommits.isHost)
    assert(bobCommits.lastCrossSignedState.verifyRemoteSig(Alice.nodeParams.nodeId))
    assert(aliceCommits.lastCrossSignedState.verifyRemoteSig(Bob.nodeParams.nodeId))
  }

  test("Disconnect in a middle of establishment, then successful retry") { f =>
    import f._
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
    bob2alice.expectMsgType[StateUpdate]
    bob ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    alice ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    awaitCond(bob.stateName == OFFLINE)
    awaitCond(alice.stateName == OFFLINE)

    reachNormal(f, channelId)
    val bobCommits = bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS]
    val aliceCommits = alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS]
    assert(!bobCommits.isHost)
    assert(aliceCommits.isHost)
    assert(bobCommits.lastCrossSignedState.verifyRemoteSig(Alice.nodeParams.nodeId))
    assert(aliceCommits.lastCrossSignedState.verifyRemoteSig(Bob.nodeParams.nodeId))
  }

  test("Successful invoke, then client loses data, restores from host LCSS") { f =>
    reachNormal(f, channelId)
    val bobCommits = f.bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS]
    val aliceCommits = f.alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS]
    assert(!bobCommits.isHost)
    assert(aliceCommits.isHost)
    assert(bobCommits.lastCrossSignedState.verifyRemoteSig(Alice.nodeParams.nodeId))
    assert(aliceCommits.lastCrossSignedState.verifyRemoteSig(Bob.nodeParams.nodeId))
    f.bob ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    f.alice ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    awaitCond(f.bob.stateName == OFFLINE)
    awaitCond(f.alice.stateName == OFFLINE)

    val f1 = init()
    f1.bob ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Alice.nodeParams.nodeId, f1.bob2alice.ref)
    f.alice ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Bob.nodeParams.nodeId, f.alice2bob.ref)
    awaitCond(f1.bob.stateName == WAIT_FOR_INIT_INTERNAL)
    awaitCond(f.alice.stateName == SYNCING)
    f1.bob ! CMD_HOSTED_INVOKE_CHANNEL(channelId, Alice.nodeParams.nodeId, Bob.channelParams.defaultFinalScriptPubKey)
    val bobInvokeHostedChannel1 = f1.bob2alice.expectMsgType[InvokeHostedChannel]
    f1.bob2alice.forward(f.alice, CMD_HOSTED_MESSAGE(channelId, bobInvokeHostedChannel1))
    val aliceLastCrossSignedState = f.alice2bob.expectMsgType[LastCrossSignedState]
    f.alice2bob.forward(f1.bob, CMD_HOSTED_MESSAGE(channelId, aliceLastCrossSignedState))
    awaitCond(f.alice.stateName == SYNCING)
    val bobLastCrossSignedState = f1.bob2alice.expectMsgType[LastCrossSignedState]
    f1.bob2alice.forward(f.alice, CMD_HOSTED_MESSAGE(channelId, bobLastCrossSignedState))
    val aliceLastCrossSignedState1 = f.alice2bob.expectMsgType[LastCrossSignedState]
    f.alice2bob.forward(f1.bob, CMD_HOSTED_MESSAGE(channelId, aliceLastCrossSignedState1))
    f1.bob2alice.expectMsgType[ChannelUpdate]
    f.alice2bob.expectMsgType[ChannelUpdate]
    f1.bob2alice.expectNoMsg(100 millis)
    f.alice2bob.expectNoMsg(100 millis)
    awaitCond(f1.bob.stateName == NORMAL)
    awaitCond(f.alice.stateName == NORMAL)
  }

  test("Successful invoke, then host loses data, restores from client LCSS") { f =>
    reachNormal(f, channelId)
    val bobCommits = f.bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS]
    val aliceCommits = f.alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS]
    assert(!bobCommits.isHost)
    assert(aliceCommits.isHost)
    assert(bobCommits.lastCrossSignedState.verifyRemoteSig(Alice.nodeParams.nodeId))
    assert(aliceCommits.lastCrossSignedState.verifyRemoteSig(Bob.nodeParams.nodeId))
    f.bob ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    f.alice ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    awaitCond(f.bob.stateName == OFFLINE)
    awaitCond(f.alice.stateName == OFFLINE)

    val f1 = init()
    f.bob ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Alice.nodeParams.nodeId, f.bob2alice.ref)
    f1.alice ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Bob.nodeParams.nodeId, f1.alice2bob.ref)
    awaitCond(f.bob.stateName == SYNCING)
    awaitCond(f1.alice.stateName == WAIT_FOR_INIT_INTERNAL)
    f.bob ! CMD_HOSTED_INVOKE_CHANNEL(channelId, Alice.nodeParams.nodeId, Bob.channelParams.defaultFinalScriptPubKey)
    f.bob2alice.forward(f1.alice, CMD_HOSTED_MESSAGE(channelId, f.bob2alice.expectMsgType[InvokeHostedChannel]))
    f1.alice2bob.forward(f.bob, CMD_HOSTED_MESSAGE(channelId, f1.alice2bob.expectMsgType[InitHostedChannel]))
    f.bob2alice.forward(f1.alice, CMD_HOSTED_MESSAGE(channelId, f.bob2alice.expectMsgType[LastCrossSignedState]))
    f1.alice2bob.forward(f.bob, CMD_HOSTED_MESSAGE(channelId, f1.alice2bob.expectMsgType[LastCrossSignedState]))
    awaitCond(f.bob.stateName == NORMAL)
    awaitCond(f1.alice.stateName == NORMAL)
    f.bob2alice.expectMsgType[ChannelUpdate]
    f1.alice2bob.expectMsgType[ChannelUpdate]
    f.bob2alice.expectNoMsg(100 millis)
    f1.alice2bob.expectNoMsg(100 millis)
  }

  test("Successful invoke, then client loses data, host replies with wrong LCSS, both CLOSED on reconnect") { f =>
    reachNormal(f, channelId)
    val bobCommits = f.bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS]
    val aliceCommits = f.alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS]
    assert(!bobCommits.isHost)
    assert(aliceCommits.isHost)
    assert(bobCommits.lastCrossSignedState.verifyRemoteSig(Alice.nodeParams.nodeId))
    assert(aliceCommits.lastCrossSignedState.verifyRemoteSig(Bob.nodeParams.nodeId))
    f.bob ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    f.alice ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    awaitCond(f.bob.stateName == OFFLINE)
    awaitCond(f.alice.stateName == OFFLINE)

    val f1 = init()
    f1.bob ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Alice.nodeParams.nodeId, f1.bob2alice.ref)
    f.alice ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Bob.nodeParams.nodeId, f.alice2bob.ref)
    awaitCond(f1.bob.stateName == WAIT_FOR_INIT_INTERNAL)
    awaitCond(f.alice.stateName == SYNCING)
    f1.bob ! CMD_HOSTED_INVOKE_CHANNEL(channelId, Alice.nodeParams.nodeId, Bob.channelParams.defaultFinalScriptPubKey)
    val bobInvokeHostedChannel1 = f1.bob2alice.expectMsgType[InvokeHostedChannel]
    f1.bob2alice.forward(f.alice, CMD_HOSTED_MESSAGE(channelId, bobInvokeHostedChannel1))
    val aliceLastCrossSignedState = f.alice2bob.expectMsgType[LastCrossSignedState]
    f.alice2bob.forward(f1.bob, CMD_HOSTED_MESSAGE(channelId, aliceLastCrossSignedState.copy(blockDay = 1)))
    awaitCond(f1.bob.stateName == CLOSED)
    val bobError = f1.bob2alice.expectMsgType[wire.Error]
    assert(bobError.tag === ChannelErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG)
    assert(f1.bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localError.isDefined)
    f1.bob2alice.forward(f.alice, CMD_HOSTED_MESSAGE(channelId, bobError))
    awaitCond(f.alice.stateName == CLOSED)
    assert(f.alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].remoteError.isDefined)

    f1.bob ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    f.alice ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    awaitCond(f1.bob.stateName == OFFLINE)
    awaitCond(f.alice.stateName == OFFLINE)
    f1.bob ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Alice.nodeParams.nodeId, f1.bob2alice.ref)
    f.alice ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Bob.nodeParams.nodeId, f.alice2bob.ref)
    awaitCond(f1.bob.stateName == CLOSED)
    awaitCond(f.alice.stateName == SYNCING)
    val bobError1 = f1.bob2alice.expectMsgType[wire.Error]
    f1.bob2alice.forward(f.alice, CMD_HOSTED_MESSAGE(channelId, bobError1))
    awaitCond(f.alice.stateName == CLOSED)
    f.alice2bob.expectNoMsg(100 millis)
  }

  test("Remove stale channels without commitments") { f =>
    import f._
    val bobTestProbe = TestProbe()
    val aliceTestProbe = TestProbe()
    bobTestProbe watch bob
    aliceTestProbe watch alice
    bob ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Alice.nodeParams.nodeId, bob2alice.ref)
    alice ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Bob.nodeParams.nodeId, alice2bob.ref)
    awaitCond(bob.stateName == WAIT_FOR_INIT_INTERNAL)
    awaitCond(alice.stateName == WAIT_FOR_INIT_INTERNAL)
    bob ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    alice ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    bob ! CMD_HOSTED_REMOVE_IDLE_CHANNELS
    alice ! CMD_HOSTED_REMOVE_IDLE_CHANNELS
    bobTestProbe.expectTerminated(bob)
    aliceTestProbe.expectTerminated(alice)
  }

  test("Remove stale channels with commitments") { f =>
    import f._
    val bobTestProbe = TestProbe()
    val aliceTestProbe = TestProbe()
    bobTestProbe watch bob
    aliceTestProbe watch alice
    reachNormal(f, channelId)
    bob ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    alice ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    bob ! CMD_HOSTED_REMOVE_IDLE_CHANNELS
    alice ! CMD_HOSTED_REMOVE_IDLE_CHANNELS
    bobTestProbe.expectTerminated(bob)
    aliceTestProbe.expectTerminated(alice)
  }
}