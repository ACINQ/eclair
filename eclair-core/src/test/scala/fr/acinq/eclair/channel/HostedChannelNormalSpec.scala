package fr.acinq.eclair.channel

import akka.actor.Status
import akka.testkit.TestProbe
import fr.acinq.eclair._

import scala.concurrent.duration._
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.{TestkitBaseClass, hostedChanId}
import fr.acinq.eclair.channel.states.HostedStateTestsHelperMethods
import fr.acinq.eclair.payment.{CommandBuffer, ForwardAdd, ForwardFulfill}
import fr.acinq.eclair.transactions.{IN, OUT}
import fr.acinq.eclair.wire.{ChannelUpdate, InvokeHostedChannel, LastCrossSignedState, PermanentChannelFailure, StateUpdate, UpdateAddHtlc, UpdateFailHtlc, UpdateFulfillHtlc}
import org.scalatest.Outcome

class HostedChannelNormalSpec extends TestkitBaseClass with HostedStateTestsHelperMethods {

  type FixtureParam = HostedSetupFixture

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  val channelId: ByteVector32 = hostedChanId(Bob.nodeParams.nodeId.value, Alice.nodeParams.nodeId.value)

  override def withFixture(test: OneArgTest): Outcome = withFixture(test.toNoArgTest(init()))

  test("Bob -> Alice send and fulfill one HTLC") { f =>
    import f._
    reachNormal(f, channelId)
    val (paymentPreimage, cmdAdd) = makeCmdAdd(25000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    bob ! cmdAdd
    bob ! CMD_SIGN
    val bobUpdateAdd = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd))
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.count(_.direction == OUT) === 0)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.count(_.direction == OUT) === 1)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    // Alice LCSS is updated
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.count(_.direction == IN) === 1)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.count(_.direction == IN) === 1)
    // Alice can now resolve a pending incoming HTLC by forwarding to to Relayer
    val aliceForward = relayerA.expectMsgType[ForwardAdd]
    assert(aliceForward.add === bobUpdateAdd)
    // Bob LCSS is updated
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.count(_.direction == OUT) === 1)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.count(_.direction == OUT) === 1)
    // Further StateUpdate exchange is halted because signature is the same
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    alice ! CMD_FULFILL_HTLC(bobUpdateAdd.id, paymentPreimage)
    alice ! CMD_SIGN
    val aliceUpdateFulfill = alice2bob.expectMsgType[UpdateFulfillHtlc]
    relayerA.expectMsgType[CommandBuffer.CommandAck]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateFulfill))
    relayerB.expectMsgType[ForwardFulfill]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    // Further StateUpdate exchange is halted because signature is the same
    relayerA.expectNoMsg(100 millis)
    relayerB.expectNoMsg(100 millis)
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    // HTLC has been resolved
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.isEmpty)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.isEmpty)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.isEmpty)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.isEmpty)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 75000000L.msat)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.toLocal === 75000000L.msat)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 99925000000L.msat)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.toLocal === 99925000000L.msat)
  }

  test("Bob -> Alice send and fulfill one HTLC externally") { f =>
    import f._
    reachNormal(f, channelId)
    val (paymentPreimage, cmdAdd) = makeCmdAdd(25000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    bob ! cmdAdd
    bob ! CMD_SIGN
    val bobUpdateAdd = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd))
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.count(_.direction == OUT) === 0)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.count(_.direction == OUT) === 1)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    // Alice LCSS is updated
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.count(_.direction == IN) === 1)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.count(_.direction == IN) === 1)
    // Alice can now resolve a pending incoming HTLC by forwarding to to Relayer
    val aliceForward = relayerA.expectMsgType[ForwardAdd]
    assert(aliceForward.add === bobUpdateAdd)
    // Bob LCSS is updated
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.count(_.direction == OUT) === 1)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.count(_.direction == OUT) === 1)
    // Further StateUpdate exchange is halted because signature is the same
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    val sender = TestProbe()
    val cmd = CMD_HOSTED_EXTERNAL_FULFILL(channelId, bobUpdateAdd.id, paymentPreimage)
    sender.send(bob, CMD_HOSTED_MESSAGE(cmd.channelId, wire.Error(cmd.channelId, "External fulfill attempt")))
    sender.send(bob, cmd.fulfillCmd)
    sender.expectMsgType[String]
    relayerB.expectMsgType[ForwardFulfill]
    awaitCond(bob.stateName === CLOSED)
  }

  test("Bob -> Alice send and fulfill a wrong HTLC") { f =>
    import f._
    reachNormal(f, channelId)
    val (paymentPreimage, cmdAdd) = makeCmdAdd(25000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    bob ! cmdAdd
    bob ! CMD_SIGN
    val bobUpdateAdd = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd))
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.count(_.direction == OUT) === 0)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.count(_.direction == OUT) === 1)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    // Alice LCSS is updated
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.count(_.direction == IN) === 1)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.count(_.direction == IN) === 1)
    // Alice can now resolve a pending incoming HTLC by forwarding to to Relayer
    val aliceForward = relayerA.expectMsgType[ForwardAdd]
    assert(aliceForward.add === bobUpdateAdd)
    // Bob LCSS is updated
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.count(_.direction == OUT) === 1)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.count(_.direction == OUT) === 1)
    // Further StateUpdate exchange is halted because signature is the same
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    alice ! CMD_FULFILL_HTLC(bobUpdateAdd.id, paymentPreimage)
    val aliceUpdateFulfill = alice2bob.expectMsgType[UpdateFulfillHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateFulfill.copy(id = 19)))
    awaitCond(bob.stateName === CLOSED)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[wire.Error]))
    awaitCond(alice.stateName === CLOSED)
    assert(ChannelErrorCodes.toAscii(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localError.get.data) === "unknown htlc id=19")
    assert(ChannelErrorCodes.toAscii(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].remoteError.get.data) === "unknown htlc id=19")
  }

  test("Bob -> Alice send and fulfill many HTLC") { f =>
    import f._
    val (paymentPreimage1, cmdAdd1) = makeCmdAdd(25000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage2, cmdAdd2) = makeCmdAdd(26000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage3, cmdAdd3) = makeCmdAdd(27000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    reachNormal(f, channelId)
    bob ! cmdAdd1
    val bobUpdateAdd1 = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd1))
    bob ! cmdAdd2
    val bobUpdateAdd2 = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd2))
    bob ! CMD_SIGN
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    bob ! cmdAdd3
    val bobUpdateAdd3 = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd3))
    bob ! CMD_SIGN
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    alice ! CMD_FULFILL_HTLC(bobUpdateAdd2.id, paymentPreimage2)
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[UpdateFulfillHtlc]))
    alice ! CMD_FULFILL_HTLC(bobUpdateAdd1.id, paymentPreimage1)
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[UpdateFulfillHtlc]))
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice ! CMD_FULFILL_HTLC(bobUpdateAdd3.id, paymentPreimage3)
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[UpdateFulfillHtlc]))
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    // HTLCs has been resolved
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.isEmpty)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.isEmpty)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.isEmpty)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.isEmpty)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 22000000.msat)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.toLocal === 22000000.msat)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 99978000000L.msat)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.toLocal === 99978000000L.msat)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextTotalLocal === 3) // 3 Fulfills from Alice
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextTotalRemote === 3) // 3 Adds from Bob
  }

  test("Bob <-> Alice send and fulfill/fail many HTLC (total mayhem)") { f =>
    import f._
    val (paymentPreimage1, cmdAdd1) = makeCmdAdd(25000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage2, cmdAdd2) = makeCmdAdd(26000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage3, cmdAdd3) = makeCmdAdd(27000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage4, cmdAdd4) = makeCmdAdd(28000000 msat, Bob.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage5, cmdAdd5) = makeCmdAdd(29000000 msat, Bob.nodeParams.nodeId, currentBlockHeight)
    val (_, cmdAdd6) = makeCmdAdd(30000000 msat, Bob.nodeParams.nodeId, currentBlockHeight)
    reachNormal(f, channelId)
    bob ! cmdAdd1
    val bobUpdateAdd1 = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd1))
    alice ! cmdAdd4
    val aliceUpdateAdd4 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd4))
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob ! cmdAdd2
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    val bobUpdateAdd2 = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd2))
    alice ! cmdAdd5
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    val aliceUpdateAdd5 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd5))
    bob ! CMD_SIGN
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice ! cmdAdd6
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    val aliceUpdateAdd6 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd6))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice ! CMD_FULFILL_HTLC(bobUpdateAdd1.id, paymentPreimage1)
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[UpdateFulfillHtlc]))
    bob ! cmdAdd3
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    val bobUpdateAdd3 = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd3))
    bob ! CMD_FULFILL_HTLC(aliceUpdateAdd4.id, paymentPreimage4)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[UpdateFulfillHtlc]))
    bob ! CMD_SIGN
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    alice ! CMD_FULFILL_HTLC(bobUpdateAdd2.id, paymentPreimage2)
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[UpdateFulfillHtlc]))
    bob ! CMD_FULFILL_HTLC(aliceUpdateAdd5.id, paymentPreimage5)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[UpdateFulfillHtlc]))
    bob ! CMD_SIGN
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice ! CMD_FULFILL_HTLC(bobUpdateAdd3.id, paymentPreimage3)
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[UpdateFulfillHtlc]))
    alice ! CMD_SIGN
    bob ! CMD_FAIL_HTLC(aliceUpdateAdd6.id, Right(PermanentChannelFailure))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[UpdateFailHtlc]))
    bob ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.isEmpty)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.isEmpty)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.isEmpty)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.isEmpty)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 79000000.msat)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 99921000000L.msat)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextTotalLocal === 6)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextTotalRemote === 6)
  }

  test("Bob <-> Alice send one HTLC, then disconnect and resend") { f =>
    import f._
    val (paymentPreimage1, cmdAdd1) = makeCmdAdd(25000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage2, cmdAdd2) = makeCmdAdd(26000000 msat, Bob.nodeParams.nodeId, currentBlockHeight)
    reachNormal(f, channelId)
    bob ! cmdAdd1
    val bobUpdateAdd1 = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd1))
    alice ! cmdAdd2
    val aliceUpdateAdd2 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd2))
    awaitCond(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextRemoteUpdates.size == 1)
    awaitCond(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextRemoteUpdates.size == 1)
    bob ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    alice ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    awaitCond(bob.stateName == OFFLINE)
    awaitCond(alice.stateName == OFFLINE)
    bob ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Alice.nodeParams.nodeId, bob2alice.ref)
    alice ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Bob.nodeParams.nodeId, alice2bob.ref)
    awaitCond(bob.stateName == SYNCING)
    awaitCond(alice.stateName == SYNCING)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[InvokeHostedChannel]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[LastCrossSignedState]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[LastCrossSignedState]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[LastCrossSignedState]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[UpdateAddHtlc]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[UpdateAddHtlc]))
    bob2alice.expectMsgType[ChannelUpdate]
    alice2bob.expectMsgType[ChannelUpdate]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    awaitCond(bob.stateName == NORMAL)
    awaitCond(alice.stateName == NORMAL)
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    bob ! CMD_FULFILL_HTLC(aliceUpdateAdd2.id, paymentPreimage2)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[UpdateFulfillHtlc]))
    bob ! CMD_SIGN
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice ! CMD_FULFILL_HTLC(bobUpdateAdd1.id, paymentPreimage1)
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[UpdateFulfillHtlc]))
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.isEmpty)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.isEmpty)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.isEmpty)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.isEmpty)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 101000000.msat)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 99899000000L.msat)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextTotalLocal === 2)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextTotalRemote === 2)
  }

  test("Bob <-> Alice send HTLC, then cross-sign, then Alice sends another HTLC and falls behind, then resolves on reconnect") { f =>
    import f._
    val (_, cmdAdd1) = makeCmdAdd(25000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage2, cmdAdd2) = makeCmdAdd(26000000 msat, Bob.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage3, cmdAdd3) = makeCmdAdd(27000000 msat, Bob.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage4, cmdAdd4) = makeCmdAdd(28000000 msat, Bob.nodeParams.nodeId, currentBlockHeight)
    reachNormal(f, channelId)
    // exchange first set of UpdateAdd
    bob ! cmdAdd1
    val bobUpdateAdd1 = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd1))
    alice ! cmdAdd2
    val aliceUpdateAdd2 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd2))
    // cross-sign and enter new state
    bob ! CMD_SIGN
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.size === 2)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.size === 2)
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    relayerA.expectMsgType[ForwardAdd] // cmdAdd1 from Bob is cross-signed
    relayerB.expectMsgType[ForwardAdd] // cmdAdd2 from Alice is cross-signed
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    // Alice sends a second UpdateAddHtlc
    alice ! cmdAdd3
    val aliceUpdateAdd3 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd3))
    // Alice sends StateUpdate, Bob receives it, has a future state
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate].copy(isTerminal = true)))
    bob2alice.expectMsgType[StateUpdate] // disconnect happens on Bob's side, neither of them notice this yet
    relayerB.expectMsgType[ForwardAdd] // cmdAdd3 from Alice is cross-signed on Bob's side
    alice ! cmdAdd4
    val aliceUpdateAdd4 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd4))
    relayerB.expectNoMsg(100 millis) // cmdAdd3 from alice is not yet cross-signed on Bob's side
    bob ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    // Alice sends another UpdateAdd, then StateUpdate
    alice ! CMD_SIGN
    alice2bob.expectMsgType[StateUpdate] // do not send to Bob because he's disconnected
    // Alice finds out about disconnect, at this point Alice is 1 LCSS ahead from base LCSS, Bob is 2 LCSS ahead
    alice ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    awaitCond(bob.stateName == OFFLINE)
    awaitCond(alice.stateName == OFFLINE)
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.size === 3) // Bob is 1 LCSS ahead of Alice
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.size === 2)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalUpdates.size === 2) // Alice has two unsigned HTLCs, one of them is actually signed on Bob's side
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.exists(_.add == alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalUpdates.head))
    bob ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Alice.nodeParams.nodeId, bob2alice.ref)
    alice ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Bob.nodeParams.nodeId, alice2bob.ref)
    awaitCond(bob.stateName == SYNCING)
    awaitCond(alice.stateName == SYNCING)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[InvokeHostedChannel]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[LastCrossSignedState]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[LastCrossSignedState]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[LastCrossSignedState])) // Bob disregards this because he's ahead already
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[UpdateAddHtlc])) // Alice re-sends one leftover HTLC
    bob2alice.expectMsgType[ChannelUpdate]
    alice2bob.expectMsgType[ChannelUpdate]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    relayerB.expectMsgType[ForwardAdd] // cmdAdd4 is now cross-signed on Bob's side
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    awaitCond(bob.stateName == NORMAL)
    awaitCond(alice.stateName == NORMAL)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.size === 4)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.size === 4)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].originChannels.size === 1)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].originChannels.size === 3)
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    bob ! CMD_FULFILL_HTLC(aliceUpdateAdd2.id, paymentPreimage2)
    bob ! CMD_FULFILL_HTLC(aliceUpdateAdd3.id, paymentPreimage3)
    bob ! CMD_FULFILL_HTLC(aliceUpdateAdd4.id, paymentPreimage4)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[UpdateFulfillHtlc]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[UpdateFulfillHtlc]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[UpdateFulfillHtlc]))
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.size === 1)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.size === 1)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].futureUpdates.isEmpty)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].futureUpdates.isEmpty)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].originChannels.size === 1)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].originChannels.size === 0)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 156000000.msat) // minus 1 unresolved in-flight
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.toLocal === 99819000000L.msat) // not yet received one from Bob
  }

  test("Bob <- Alice send many HTLC, some time out and some are resolved while online") { f =>
    import f._
    val (paymentPreimage1, cmdAdd1) = makeCmdAdd(25000000 msat, Bob.nodeParams.nodeId, currentBlockHeight)
    val (paymentPreimage2, cmdAdd2) = makeCmdAdd(26000000 msat, Bob.nodeParams.nodeId, currentBlockHeight + 10)
    val (_, cmdAdd3) = makeCmdAdd(27000000 msat, Bob.nodeParams.nodeId, currentBlockHeight + 20)
    reachNormal(f, channelId)
    alice ! cmdAdd1
    val aliceUpdateAdd1 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd1))
    alice ! cmdAdd2
    val aliceUpdateAdd2 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd2))
    alice ! cmdAdd3
    val aliceUpdateAdd3 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd3))
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    bob ! CMD_FULFILL_HTLC(aliceUpdateAdd1.id, paymentPreimage1)
    val bobFulfill1 = bob2alice.expectMsgType[UpdateFulfillHtlc] // This fulfill will be sent later
    bob ! CMD_FULFILL_HTLC(aliceUpdateAdd2.id, paymentPreimage2) // Bob fulfills 2nd HTLC right before Alice times out a first one
    alice ! CurrentBlockCount(400145) // Alice times out a first HTLC to Bob
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[UpdateFulfillHtlc]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[wire.Error]))
    awaitCond(alice.stateName == CLOSED)
    awaitCond(bob.stateName == CLOSED)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].remoteError.isDefined)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localError.isDefined)
    assert(relayerA.expectMsgType[Status.Failure].cause.asInstanceOf[AddHtlcFailed].paymentHash === cmdAdd1.paymentHash)
    assert(relayerA.expectMsgType[ForwardFulfill].htlc.paymentHash === cmdAdd2.paymentHash)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobFulfill1)) // Bob tries to fulfill a timedout HTLC, but it's too late
    relayerA.expectNoMsg(100 millis)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].failedOutgoingHtlcLeftoverIds === Set(1))
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].fulfilledOutgoingHtlcLeftoverIds === Set(2))
    alice ! CurrentBlockCount(400155) // Nothing happens to already failed/fulfilled HTLCs
    relayerA.expectNoMsg(100 millis)
    alice ! CurrentBlockCount(400165)
    assert(relayerA.expectMsgType[Status.Failure].cause.asInstanceOf[AddHtlcFailed].paymentHash === cmdAdd3.paymentHash)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].failedOutgoingHtlcLeftoverIds === Set(1, 3))
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].fulfilledOutgoingHtlcLeftoverIds === Set(2))
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextRemoteUpdates.exists(_.asInstanceOf[UpdateFulfillHtlc].id === aliceUpdateAdd2.id))
    relayerA.expectNoMsg(100 millis)
  }

  test("Bob <- Alice send many HTLC, all time out while offline, then channel removed") { f =>
    import f._
    val aliceTestProbe = TestProbe()
    aliceTestProbe watch alice
    val (_, cmdAdd1) = makeCmdAdd(25000000 msat, Bob.nodeParams.nodeId, currentBlockHeight)
    val (_, cmdAdd2) = makeCmdAdd(26000000 msat, Bob.nodeParams.nodeId, currentBlockHeight + 10)
    val (_, cmdAdd3) = makeCmdAdd(27000000 msat, Bob.nodeParams.nodeId, currentBlockHeight + 20)
    reachNormal(f, channelId)
    alice ! cmdAdd1
    val aliceUpdateAdd1 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd1))
    alice ! cmdAdd2
    val aliceUpdateAdd2 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd2))
    alice ! cmdAdd3
    val aliceUpdateAdd3 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd3))
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)
    bob ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    alice ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    awaitCond(bob.stateName == OFFLINE)
    awaitCond(alice.stateName == OFFLINE)
    alice ! CurrentBlockCount(400145)
    assert(relayerA.expectMsgType[Status.Failure].cause.asInstanceOf[AddHtlcFailed].paymentHash === cmdAdd1.paymentHash)
    alice ! CurrentBlockCount(400155)
    system.eventStream.publish(CMD_HOSTED_REMOVE_IDLE_CHANNELS) // Pending HTLCs prevent channel from being closed
    assert(relayerA.expectMsgType[Status.Failure].cause.asInstanceOf[AddHtlcFailed].paymentHash === cmdAdd2.paymentHash)
    alice ! CurrentBlockCount(400165)
    assert(relayerA.expectMsgType[Status.Failure].cause.asInstanceOf[AddHtlcFailed].paymentHash === cmdAdd3.paymentHash)
    alice ! CurrentBlockCount(400175)
    relayerA.expectNoMsg(100 millis)
    system.eventStream.publish(CMD_HOSTED_REMOVE_IDLE_CHANNELS)
    aliceTestProbe.expectTerminated(alice)
  }

  test("Bob sends an HTLC while offline") { f =>
    import f._
    val sender = TestProbe()
    val (_, cmdAdd1) = makeCmdAdd(25000000 msat, Bob.nodeParams.nodeId, currentBlockHeight)
    reachNormal(f, channelId)
    bob ! CMD_HOSTED_INPUT_DISCONNECTED(channelId)
    sender.send(bob, cmdAdd1)
    sender.expectMsgType[Status.Failure]
  }
}
