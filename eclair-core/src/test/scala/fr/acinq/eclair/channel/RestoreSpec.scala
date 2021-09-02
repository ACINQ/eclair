package fr.acinq.eclair.channel

import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin._
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.WatchFundingSpentTriggered
import fr.acinq.eclair.channel.states.ChannelStateTestsBase
import fr.acinq.eclair.channel.states.ChannelStateTestsHelperMethods.FakeTxPublisherFactory
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.payment.relay.Relayer.{RelayFees, RelayParams}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions.{ClaimP2WPKHOutputTx, DefaultCommitmentFormat, InputInfo, TxOwner}
import fr.acinq.eclair.wire.protocol.{ChannelReestablish, CommitSig, Error, Init, RevokeAndAck}
import fr.acinq.eclair.{TestKitBaseClass, _}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration._

class RestoreSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = test.tags.contains("disable-offline-mismatch") match {
      case false => init()
      case true => init(nodeParamsA = Alice.nodeParams.copy(onChainFeeConf = Alice.nodeParams.onChainFeeConf.copy(closeOnOfflineMismatch = false)))
    }
    import setup._
    within(30 seconds) {
      reachNormal(setup)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      withFixture(test.toNoArgTest(setup))
    }
  }

  def aliceInit = Init(Alice.nodeParams.features)

  def bobInit = Init(Bob.nodeParams.features)

  test("use funding pubkeys from publish commitment to spend our output") { f =>
    import f._
    val sender = TestProbe()

    // we start by storing the current state
    val oldStateData = alice.stateData.asInstanceOf[HasCommitments]
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

    // we restart Alice
    val newAlice: TestFSMRef[ChannelState, ChannelData, Channel] = TestFSMRef(new Channel(Alice.nodeParams, wallet, Bob.nodeParams.nodeId, alice2blockchain.ref, relayerA.ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer.ref)
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
    assert(new String(error.data.toArray) === PleasePublishYourCommitment(channelId(newAlice)).getMessage)

    // alice now waits for bob to publish its commitment
    awaitCond(newAlice.stateName == WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)

    // bob is nice and publishes its commitment
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager).tx

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
      txIn = TxIn(OutPoint(bobCommitTx, bobCommitTx.txOut.indexOf(ourOutput)), sequence = TxIn.SEQUENCE_FINAL, signatureScript = Nil) :: Nil,
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

  test("restore channel without configuration change") { f =>
    import f._
    val sender = TestProbe()

    // we start by storing the current state
    assert(alice.stateData.isInstanceOf[DATA_NORMAL])
    val oldStateData = alice.stateData.asInstanceOf[DATA_NORMAL]

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // we restart Alice
    val newAlice: TestFSMRef[ChannelState, ChannelData, Channel] = TestFSMRef(new Channel(Alice.nodeParams, wallet, Bob.nodeParams.nodeId, alice2blockchain.ref, relayerA.ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer.ref)
    newAlice ! INPUT_RESTORED(oldStateData)

    // first we send out the original channel update
    val u1 = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(u1.previousChannelUpdate_opt.nonEmpty)
    assert(Announcements.areSameIgnoreFlags(u1.previousChannelUpdate_opt.get, u1.channelUpdate))
    assert(Announcements.areSameIgnoreFlags(u1.previousChannelUpdate_opt.get, oldStateData.channelUpdate))

    newAlice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    alice2bob.forward(bob)
    bob2alice.forward(newAlice)
    awaitCond(newAlice.stateName == NORMAL)

    // no new update
    channelUpdateListener.expectNoMessage()
  }

  test("restore channel with configuration change") { f =>
    import f._
    val sender = TestProbe()

    // we start by storing the current state
    assert(alice.stateData.isInstanceOf[DATA_NORMAL])
    val oldStateData = alice.stateData.asInstanceOf[DATA_NORMAL]

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // we restart Alice with a different configuration
    val newFees = RelayFees(765 msat, 2345)
    val newConfig = Alice.nodeParams.copy(relayParams = RelayParams(newFees, newFees, newFees))
    val newAlice: TestFSMRef[ChannelState, ChannelData, Channel] = TestFSMRef(new Channel(newConfig, wallet, Bob.nodeParams.nodeId, alice2blockchain.ref, relayerA.ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer.ref)
    newAlice ! INPUT_RESTORED(oldStateData)

    // first we send out the original channel update
    val u1 = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(u1.previousChannelUpdate_opt.nonEmpty)
    assert(Announcements.areSameIgnoreFlags(u1.previousChannelUpdate_opt.get, u1.channelUpdate))
    assert(Announcements.areSameIgnoreFlags(u1.previousChannelUpdate_opt.get, oldStateData.channelUpdate))

    newAlice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    alice2bob.forward(bob)
    bob2alice.forward(newAlice)
    awaitCond(newAlice.stateName == NORMAL)

    // then the new original channel update
    val u2 = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(u2.previousChannelUpdate_opt.nonEmpty)
    assert(!Announcements.areSameIgnoreFlags(u2.previousChannelUpdate_opt.get, u2.channelUpdate))
    assert(Announcements.areSameIgnoreFlags(u2.previousChannelUpdate_opt.get, oldStateData.channelUpdate))

    // no new update
    channelUpdateListener.expectNoMessage()
  }

}
