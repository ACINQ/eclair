package fr.acinq.eclair.channel

import fr.acinq.eclair._
import scala.concurrent.duration._
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.{TestkitBaseClass, hostedChanId}
import fr.acinq.eclair.channel.states.HostedStateTestsHelperMethods
import fr.acinq.eclair.payment.{CommandBuffer, ForwardAdd, ForwardFulfill}
import fr.acinq.eclair.transactions.{IN, OUT}
import fr.acinq.eclair.wire.{PermanentChannelFailure, StateUpdate, UpdateAddHtlc, UpdateFailHtlc, UpdateFulfillHtlc}
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
    val bobStateUpdate = bob2alice.expectMsgType[StateUpdate]
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.count(_.direction == OUT) === 0)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.count(_.direction == OUT) === 1)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobStateUpdate))
    // Alice LCSS is updated
    val aliceStateUpdate = alice2bob.expectMsgType[StateUpdate]
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.count(_.direction == IN) === 1)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.count(_.direction == IN) === 1)
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceStateUpdate))
    // Alice can now resolve a pending incoming HTLC by forwarding to to Relayer
    val aliceForward = relayerA.expectMsgType[ForwardAdd]
    assert(aliceForward.add === bobUpdateAdd)
    // Bob LCSS is updated
    val bobStateUpdate1 = bob2alice.expectMsgType[StateUpdate]
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.count(_.direction == OUT) === 1)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.count(_.direction == OUT) === 1)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobStateUpdate1))
    // Further StateUpdate exchange is halted because signature is the same
    alice2bob.expectNoMsg(100 millis)
    bob2alice.expectNoMsg(100 millis)

    alice ! CMD_FULFILL_HTLC(bobUpdateAdd.id, paymentPreimage)
    alice ! CMD_SIGN
    val aliceUpdateFulfill = alice2bob.expectMsgType[UpdateFulfillHtlc]
    relayerA.expectMsgType[CommandBuffer.CommandAck]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateFulfill))
    relayerB.expectMsgType[ForwardFulfill]
    val aliceStateUpdate1 = alice2bob.expectMsgType[StateUpdate]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceStateUpdate1))
    val bobStateUpdate2 = bob2alice.expectMsgType[StateUpdate]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobStateUpdate2))
    val aliceStateUpdate2 = alice2bob.expectMsgType[StateUpdate]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceStateUpdate2))
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

  test("Bob -> Alice send and fulfill a wrong HTLC") { f =>
    import f._
    reachNormal(f, channelId)
    val (paymentPreimage, cmdAdd) = makeCmdAdd(25000000 msat, Alice.nodeParams.nodeId, currentBlockHeight)
    bob ! cmdAdd
    bob ! CMD_SIGN
    val bobUpdateAdd = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd))
    val bobStateUpdate = bob2alice.expectMsgType[StateUpdate]
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.count(_.direction == OUT) === 0)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.count(_.direction == OUT) === 1)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobStateUpdate))
    // Alice LCSS is updated
    val aliceStateUpdate = alice2bob.expectMsgType[StateUpdate]
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.count(_.direction == IN) === 1)
    assert(alice.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.count(_.direction == IN) === 1)
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceStateUpdate))
    // Alice can now resolve a pending incoming HTLC by forwarding to to Relayer
    val aliceForward = relayerA.expectMsgType[ForwardAdd]
    assert(aliceForward.add === bobUpdateAdd)
    // Bob LCSS is updated
    val bobStateUpdate1 = bob2alice.expectMsgType[StateUpdate]
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].localSpec.htlcs.count(_.direction == OUT) === 1)
    assert(bob.stateData.asInstanceOf[HOSTED_DATA_COMMITMENTS].nextLocalSpec.htlcs.count(_.direction == OUT) === 1)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobStateUpdate1))
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
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    // Further StateUpdate exchange is halted because signature is the same
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
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
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
    bob2alice.expectMsgType[StateUpdate]
    val bobUpdateAdd2 = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd2))
    alice ! cmdAdd5
    val aliceUpdateAdd5 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd5))
    bob ! CMD_SIGN
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice ! cmdAdd6
    alice2bob.expectMsgType[StateUpdate]
    val aliceUpdateAdd6 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceUpdateAdd6))
    alice ! CMD_FULFILL_HTLC(bobUpdateAdd1.id, paymentPreimage1)
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[UpdateFulfillHtlc]))
    bob ! cmdAdd3
    val bobUpdateAdd3 = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobUpdateAdd3))
    bob ! CMD_FULFILL_HTLC(aliceUpdateAdd4.id, paymentPreimage4)
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[UpdateFulfillHtlc]))
    bob ! CMD_SIGN
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice ! CMD_SIGN
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.expectMsgType[StateUpdate]
    alice2bob.expectMsgType[StateUpdate]
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
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob ! CMD_FAIL_HTLC(aliceUpdateAdd6.id, Right(PermanentChannelFailure))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[UpdateFailHtlc]))
    bob ! CMD_SIGN
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, alice2bob.expectMsgType[StateUpdate]))
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bob2alice.expectMsgType[StateUpdate]))
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
}
