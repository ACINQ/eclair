package fr.acinq.eclair.channel

import akka.actor.Status
import fr.acinq.eclair._

import scala.concurrent.duration._
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.{TestkitBaseClass, hostedChanId}
import fr.acinq.eclair.channel.states.HostedStateTestsHelperMethods
import fr.acinq.eclair.payment.{CommandBuffer, ForwardAdd}
import fr.acinq.eclair.wire.{ChannelUpdate, StateOverride, StateUpdate, UpdateAddHtlc, UpdateFulfillHtlc}
import org.scalatest.Outcome

class HostedChannelOverrideSpec extends TestkitBaseClass with HostedStateTestsHelperMethods {

  type FixtureParam = HostedSetupFixture

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  val channelId: ByteVector32 = hostedChanId(Bob.nodeParams.nodeId.value, Alice.nodeParams.nodeId.value)

  override def withFixture(test: OneArgTest): Outcome = withFixture(test.toNoArgTest(init()))

  test("Bob fulfills a wrong HTLC, then channel gets overridden") { f =>
    import f._
    val (_, cmdAdd1) = makeCmdAdd(25000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage2, cmdAdd2) = makeCmdAdd(26000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage3, cmdAdd3) = makeCmdAdd(30000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    reachNormal(f, channelId)
    bob ! cmdAdd1
    val bobUpdateAdd1 = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd1))
    alice ! cmdAdd2
    val aliceUpdateAdd2 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd2))
    bob ! CMD_SIGN
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    relayerA.expectMsgType[ForwardAdd]
    relayerB.expectMsgType[ForwardAdd]
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    bob ! CMD_FULFILL_HTLC(aliceUpdateAdd2.id, paymentPreimage2)
    val bobUpdateFulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateFulfill.copy(id = 19)))
    awaitCond(alice.stateName === CLOSED)
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[wire.Error]))
    awaitCond(bob.stateName === CLOSED)
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    alice ! CMD_HOSTED_OVERRIDE(channelId, 99900000000L msat) // restoring an old balance
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateOverride]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    awaitCond(alice.stateName === NORMAL)
    awaitCond(bob.stateName === NORMAL)
    alice2bob.expectMsgType[ChannelUpdate]
    bob2alice.expectMsgType[ChannelUpdate]
    assert(relayerA.expectMsgType[Status.Failure].cause.asInstanceOf[AddHtlcFailed].paymentHash === aliceUpdateAdd2.paymentHash)
    assert(relayerB.expectMsgType[CommandBuffer.CommandAck].htlcId === aliceUpdateAdd2.id)
    assert(relayerB.expectMsgType[Status.Failure].cause.asInstanceOf[AddHtlcFailed].paymentHash === bobUpdateAdd1.paymentHash)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 100000000.msat)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 99900000000L.msat)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.isEmpty)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.isEmpty)
    alice ! cmdAdd3
    val aliceUpdateAdd3 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd3))
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate])) // isTerminal: false, alice has no idea if Bob is sending anything
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate])) // isTerminal: true, Bob reply, he is not
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate])) // isTerminal: true, Alice reply with state update
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate])) // isTerminal: false, Bob reply with state update
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    bob ! CMD_FULFILL_HTLC(aliceUpdateAdd3.id, paymentPreimage3)
    val bobUpdateFulfill3 = bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateFulfill3))
    bob ! CMD_SIGN
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 130000000.msat)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 99870000000L.msat)
  }

  test("Bob fulfills a wrong HTLC, override saved while Bob is offline, override happens on Bob becoming online") { f =>
    import f._
    val (_, cmdAdd1) = makeCmdAdd(25000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage2, cmdAdd2) = makeCmdAdd(26000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage3, cmdAdd3) = makeCmdAdd(30000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    reachNormal(f, channelId)
    bob ! cmdAdd1
    val bobUpdateAdd1 = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd1))
    alice ! cmdAdd2
    val aliceUpdateAdd2 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd2))
    bob ! CMD_SIGN
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    relayerA.expectMsgType[ForwardAdd]
    relayerB.expectMsgType[ForwardAdd]
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    bob ! CMD_FULFILL_HTLC(aliceUpdateAdd2.id, paymentPreimage2)
    val bobUpdateFulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateFulfill.copy(id = 19)))
    awaitCond(alice.stateName === CLOSED)
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[wire.Error]))
    awaitCond(bob.stateName === CLOSED)
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    bob ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    alice ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    awaitCond(bob.stateName == OFFLINE)
    awaitCond(alice.stateName == OFFLINE)
    alice ! CMD_HOSTED_OVERRIDE(channelId, 99900000000L msat) // restoring an old balance
    alice2bob.expectMsgType[StateOverride] // do not forward to Bob because he's offline
    bob ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Alice.nodeParams.nodeId, bob2alice.ref)
    alice ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Bob.nodeParams.nodeId, alice2bob.ref)
    awaitCond(bob.stateName == CLOSED)
    awaitCond(alice.stateName == SYNCING)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[wire.Error]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateOverride]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    awaitCond(alice.stateName === NORMAL)
    awaitCond(bob.stateName === NORMAL)
    bob2alice.expectMsgType[ChannelUpdate]
    alice2bob.expectMsgType[ChannelUpdate]
    assert(relayerA.expectMsgType[Status.Failure].cause.asInstanceOf[AddHtlcFailed].paymentHash === aliceUpdateAdd2.paymentHash)
    assert(relayerB.expectMsgType[CommandBuffer.CommandAck].htlcId === aliceUpdateAdd2.id)
    assert(relayerB.expectMsgType[Status.Failure].cause.asInstanceOf[AddHtlcFailed].paymentHash === bobUpdateAdd1.paymentHash)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 100000000.msat)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 99900000000L.msat)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.isEmpty)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.isEmpty)
    alice ! cmdAdd3
    val aliceUpdateAdd3 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd3))
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate])) // isTerminal: false, alice has no idea if Bob is sending anything
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate])) // isTerminal: true, Bob reply, he is not
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate])) // isTerminal: true, Alice reply with state update
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate])) // isTerminal: false, Bob reply with state update
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    bob ! CMD_FULFILL_HTLC(aliceUpdateAdd3.id, paymentPreimage3)
    val bobUpdateFulfill3 = bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateFulfill3))
    bob ! CMD_SIGN
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 130000000.msat)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 99870000000L.msat)
  }

  test("Bob fulfills a wrong HTLC, then channel fails to get overridden") { f =>
    import f._
    val (_, cmdAdd1) = makeCmdAdd(25000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage2, cmdAdd2) = makeCmdAdd(26000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    reachNormal(f, channelId)
    bob ! cmdAdd1
    val bobUpdateAdd1 = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd1))
    alice ! cmdAdd2
    val aliceUpdateAdd2 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd2))
    bob ! CMD_SIGN
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    relayerA.expectMsgType[ForwardAdd]
    relayerB.expectMsgType[ForwardAdd]
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    bob ! CMD_FULFILL_HTLC(aliceUpdateAdd2.id, paymentPreimage2)
    val bobUpdateFulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateFulfill.copy(id = 19)))
    awaitCond(alice.stateName === CLOSED)
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[wire.Error]))
    awaitCond(bob.stateName === CLOSED)
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    alice ! CMD_HOSTED_OVERRIDE(channelId, 999900000000L msat) // more than capacity
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateOverride]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[wire.Error]))
    awaitCond(alice.stateName === CLOSED)
    awaitCond(bob.stateName === CLOSED)
  }
}
