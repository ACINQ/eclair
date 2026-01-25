package fr.acinq.eclair.channel

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.{TestActor, TestFSMRef, TestProbe}
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.states.ChannelStateTestsBase
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.FakeTxPublisherFactory
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.protocol.{ChannelReady, ChannelReestablish, ChannelUpdate, Init}
import fr.acinq.eclair.{TestKitBaseClass, _}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration._

class RestoreSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init(tags = test.tags)
    within(30 seconds) {
      reachNormal(setup, test.tags)
      withFixture(test.toNoArgTest(setup))
    }
  }

  private val aliceInit = Init(Alice.nodeParams.features.initFeatures())
  private val bobInit = Init(Bob.nodeParams.features.initFeatures())

  /** We are only interested in channel updates from Alice, we use the channel flag to discriminate */
  def aliceChannelUpdateListener(channelUpdateListener: TestProbe): TestProbe = {
    val aliceListener = TestProbe()
    channelUpdateListener.setAutoPilot {
      (sender: ActorRef, msg: Any) =>
        msg match {
          case u: ChannelUpdateParametersChanged if u.channelUpdate.channelFlags.isNode1 == Announcements.isNode1(Alice.nodeParams.nodeId, Bob.nodeParams.nodeId) =>
            aliceListener.ref.tell(msg, sender)
            TestActor.KeepRunning
          case _ => TestActor.KeepRunning
        }
    }
    aliceListener
  }

  test("restore channel without configuration change") { f =>
    import f._
    val sender = TestProbe()
    val channelUpdateListener = {
      val listener = TestProbe()
      system.eventStream.subscribe(listener.ref, classOf[ChannelUpdateParametersChanged])
      aliceChannelUpdateListener(listener)
    }

    // we start by storing the current state
    assert(alice.stateData.isInstanceOf[DATA_NORMAL])
    val oldStateData = alice.stateData.asInstanceOf[DATA_NORMAL]

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // and we terminate Alice
    alice.stop()

    // we restart Alice
    val newAlice: TestFSMRef[ChannelState, ChannelData, Channel] = TestFSMRef(new Channel(Alice.nodeParams, Alice.channelKeys(), aliceWallet, Bob.nodeParams.nodeId, alice2blockchain.ref, alice2relayer.ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer.ref)
    newAlice ! INPUT_RESTORED(oldStateData)

    newAlice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    alice2bob.forward(bob)
    bob2alice.forward(newAlice)
    awaitCond(newAlice.stateName == NORMAL)

    channelUpdateListener.expectNoMessage()
  }

  test("restore channel with configuration change") { f =>
    import f._
    val sender = TestProbe()
    val channelUpdateListener = {
      val listener = TestProbe()
      system.eventStream.subscribe(listener.ref, classOf[ChannelUpdateParametersChanged])
      aliceChannelUpdateListener(listener)
    }

    // we start by storing the current state
    assert(alice.stateData.isInstanceOf[DATA_NORMAL])
    val oldStateData = alice.stateData.asInstanceOf[DATA_NORMAL]

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // and we terminate Alice
    alice.stop()

    // there should be no pending messages
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()

    // we restart Alice with different configurations
    Seq(
      Alice.nodeParams
        .modify(_.relayParams.privateChannelFees.feeBase).setTo(765 msat),
      Alice.nodeParams
        .modify(_.relayParams.privateChannelFees.feeProportionalMillionths).setTo(2345),
      Alice.nodeParams
        .modify(_.channelConf.expiryDelta).setTo(CltvExpiryDelta(147)),
      Alice.nodeParams
        .modify(_.relayParams.privateChannelFees.feeProportionalMillionths).setTo(2345)
        .modify(_.channelConf.expiryDelta).setTo(CltvExpiryDelta(147)),
    ) foreach { newConfig =>
      val newAlice: TestFSMRef[ChannelState, ChannelData, Channel] = TestFSMRef(new Channel(newConfig, Alice.channelKeys(), aliceWallet, Bob.nodeParams.nodeId, alice2blockchain.ref, alice2relayer.ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer.ref)
      newAlice ! INPUT_RESTORED(oldStateData)

      val u1 = channelUpdateListener.expectMsgType[ChannelUpdateParametersChanged]
      assert(!Announcements.areSameRelayParams(u1.channelUpdate, oldStateData.channelUpdate))
      assert(u1.channelUpdate.feeBaseMsat == newConfig.relayParams.privateChannelFees.feeBase)
      assert(u1.channelUpdate.feeProportionalMillionths == newConfig.relayParams.privateChannelFees.feeProportionalMillionths)
      assert(u1.channelUpdate.cltvExpiryDelta == newConfig.channelConf.expiryDelta)

      alice2bob.ignoreMsg { case _: ChannelUpdate | _: ChannelReady => true }
      bob2alice.ignoreMsg { case _: ChannelUpdate | _: ChannelReady => true }

      newAlice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
      bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
      alice2bob.expectMsgType[ChannelReestablish]
      bob2alice.expectMsgType[ChannelReestablish]
      alice2bob.forward(bob)
      bob2alice.forward(newAlice)
      alice2bob.expectNoMessage()
      bob2alice.expectNoMessage()

      awaitCond(newAlice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)

      channelUpdateListener.expectNoMessage()

      // we simulate a disconnection
      sender.send(newAlice, INPUT_DISCONNECTED)
      sender.send(bob, INPUT_DISCONNECTED)
      awaitCond(newAlice.stateName == OFFLINE)
      awaitCond(bob.stateName == OFFLINE)

      // and we terminate Alice
      newAlice.stop()
    }
  }

}
