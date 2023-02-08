package fr.acinq.eclair.channel

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.{TestActor, TestFSMRef, TestProbe}
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.WatchFundingSpentTriggered
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.states.ChannelStateTestsBase
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.FakeTxPublisherFactory
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions.{ClaimP2WPKHOutputTx, DefaultCommitmentFormat, InputInfo, TxOwner}
import fr.acinq.eclair.wire.protocol.{ChannelReady, ChannelReestablish, ChannelUpdate, CommitSig, Error, Init, RevokeAndAck}
import fr.acinq.eclair.{TestKitBaseClass, _}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration._

class RestoreSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    within(30 seconds) {
      reachNormal(setup)
      withFixture(test.toNoArgTest(setup))
    }
  }

  private def aliceInit = Init(Alice.nodeParams.features.initFeatures())

  private def bobInit = Init(Bob.nodeParams.features.initFeatures())

  test("use funding pubkeys from publish commitment to spend our output") { f =>
    import f._
    val sender = TestProbe()

    // we start by storing the current state
    val oldStateData = alice.stateData.asInstanceOf[PersistentChannelData]
    // then we add an htlc and sign it
    addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_SIGN())
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // alice will receive neither the revocation nor the commit sig
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.expectMsgType[CommitSig]

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // and we terminate Alice
    alice.stop()

    // we restart Alice
    val newAlice: TestFSMRef[ChannelState, ChannelData, Channel] = TestFSMRef(new Channel(Alice.nodeParams, wallet, Bob.nodeParams.nodeId, alice2blockchain.ref, alice2relayer.ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer.ref)
    newAlice ! INPUT_RESTORED(oldStateData)

    // then we reconnect them
    sender.send(newAlice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    val ce = bob2alice.expectMsgType[ChannelReestablish]

    // alice then realizes it has an old state...
    bob2alice.forward(newAlice)
    // ... and ask bob to publish its current commitment
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data.toArray) == PleasePublishYourCommitment(channelId(newAlice)).getMessage)

    // alice now waits for bob to publish its commitment
    awaitCond(newAlice.stateName == WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)

    // bob is nice and publishes its commitment
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager).tx

    // actual tests starts here: let's see what we can do with Bob's commit tx
    sender.send(newAlice, WatchFundingSpentTriggered(bobCommitTx))

    // from Bob's commit tx we can extract both funding public keys
    val OP_2 :: OP_PUSHDATA(pub1, _) :: OP_PUSHDATA(pub2, _) :: OP_2 :: OP_CHECKMULTISIG :: Nil = Script.parse(bobCommitTx.txIn(0).witness.stack.last)
    // from Bob's commit tx we can also extract our p2wpkh output
    val ourOutput = bobCommitTx.txOut.find(_.publicKeyScript.length == 22).get

    val OP_0 :: OP_PUSHDATA(pubKeyHash, _) :: Nil = Script.parse(ourOutput.publicKeyScript)

    val keyManager = Alice.nodeParams.channelKeyManager

    // find our funding pub key
    val fundingPubKey = Seq(PublicKey(pub1), PublicKey(pub2)).find {
      pub =>
        val channelKeyPath = ChannelKeyManager.keyPath(pub)
        val localPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, ce.myCurrentPerCommitmentPoint)
        localPubkey.hash160 == pubKeyHash
    } get

    // compute our to-remote pubkey
    val channelKeyPath = ChannelKeyManager.keyPath(fundingPubKey)
    val ourToRemotePubKey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, ce.myCurrentPerCommitmentPoint)

    // spend our output
    val tx = Transaction(version = 2,
      txIn = TxIn(OutPoint(bobCommitTx, bobCommitTx.txOut.indexOf(ourOutput)), sequence = bitcoin.TxIn.SEQUENCE_FINAL, signatureScript = Nil) :: Nil,
      txOut = TxOut(Satoshi(1000), Script.pay2pkh(fr.acinq.eclair.randomKey().publicKey)) :: Nil,
      lockTime = 0)

    val sig = keyManager.sign(
      ClaimP2WPKHOutputTx(InputInfo(OutPoint(bobCommitTx, bobCommitTx.txOut.indexOf(ourOutput)), ourOutput, Script.pay2pkh(ourToRemotePubKey)), tx),
      keyManager.paymentPoint(channelKeyPath),
      ce.myCurrentPerCommitmentPoint,
      TxOwner.Local,
      DefaultCommitmentFormat)
    val tx1 = tx.updateWitness(0, ScriptWitness(Scripts.der(sig) :: ourToRemotePubKey.value :: Nil))
    Transaction.correctlySpends(tx1, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

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
    val newAlice: TestFSMRef[ChannelState, ChannelData, Channel] = TestFSMRef(new Channel(Alice.nodeParams, wallet, Bob.nodeParams.nodeId, alice2blockchain.ref, alice2relayer.ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer.ref)
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

    // there should ne no pending messages
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

      val newAlice: TestFSMRef[ChannelState, ChannelData, Channel] = TestFSMRef(new Channel(newConfig, wallet, Bob.nodeParams.nodeId, alice2blockchain.ref, alice2relayer.ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer.ref)
      newAlice ! INPUT_RESTORED(oldStateData)

      val u1 = channelUpdateListener.expectMsgType[ChannelUpdateParametersChanged]
      assert(!Announcements.areSameIgnoreFlags(u1.channelUpdate, oldStateData.channelUpdate))
      assert(u1.channelUpdate.feeBaseMsat == newConfig.relayParams.privateChannelFees.feeBase)
      assert(u1.channelUpdate.feeProportionalMillionths == newConfig.relayParams.privateChannelFees.feeProportionalMillionths)
      assert(u1.channelUpdate.cltvExpiryDelta == newConfig.channelConf.expiryDelta)

      newAlice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
      bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
      alice2bob.expectMsgType[ChannelReestablish]
      bob2alice.expectMsgType[ChannelReestablish]
      alice2bob.forward(bob)
      bob2alice.forward(newAlice)
      alice2bob.expectMsgType[ChannelReady]
      bob2alice.expectMsgType[ChannelReady]
      alice2bob.forward(bob)
      bob2alice.forward(newAlice)
      alice2bob.expectMsgType[ChannelUpdate]
      bob2alice.expectMsgType[ChannelUpdate]
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
