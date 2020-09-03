package fr.acinq.eclair.channel

import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin._
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.blockchain.WatchEventSpent
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.crypto.{Generators, KeyManager}
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions.{ClaimP2WPKHOutputTx, DefaultCommitmentFormat, InputInfo, TxOwner}
import fr.acinq.eclair.wire.{ChannelReestablish, CommitSig, Error, Init, RevokeAndAck}
import fr.acinq.eclair.{TestConstants, TestKitBaseClass, _}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration._

class RecoverySpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsHelperMethods {

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

  def aliceInit = Init(TestConstants.Alice.nodeParams.features)

  def bobInit = Init(TestConstants.Bob.nodeParams.features)

  test("use funding pubkeys from publish commitment to spend our output") { f =>
    import f._
    val sender = TestProbe()

    // we start by storing the current state
    val oldStateData = alice.stateData
    // then we add an htlc and sign it
    addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_SIGN)
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN.type]]
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

    // then we manually replace alice's state with an older one
    alice.setState(OFFLINE, oldStateData)

    // then we reconnect them
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    val ce = bob2alice.expectMsgType[ChannelReestablish]

    // alice then realizes it has an old state...
    bob2alice.forward(alice)
    // ... and ask bob to publish its current commitment
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data.toArray) === PleasePublishYourCommitment(channelId(alice)).getMessage)

    // alice now waits for bob to publish its commitment
    awaitCond(alice.stateName == WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)

    // bob is nice and publishes its commitment
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx

    // actual tests starts here: let's see what we can do with Bob's commit tx
    sender.send(alice, WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx))

    // from Bob's commit tx we can extract both funding public keys
    val OP_2 :: OP_PUSHDATA(pub1, _) :: OP_PUSHDATA(pub2, _) :: OP_2 :: OP_CHECKMULTISIG :: Nil = Script.parse(bobCommitTx.txIn(0).witness.stack.last)
    // from Bob's commit tx we can also extract our p2wpkh output
    val ourOutput = bobCommitTx.txOut.find(_.publicKeyScript.length == 22).get

    val OP_0 :: OP_PUSHDATA(pubKeyHash, _) :: Nil = Script.parse(ourOutput.publicKeyScript)

    val keyManager = TestConstants.Alice.nodeParams.keyManager

    // find our funding pub key
    val fundingPubKey = Seq(PublicKey(pub1), PublicKey(pub2)).find {
      pub =>
        val channelKeyPath = KeyManager.channelKeyPath(pub)
        val localPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, ce.myCurrentPerCommitmentPoint)
        localPubkey.hash160 == pubKeyHash
    } get

    // compute our to-remote pubkey
    val channelKeyPath = KeyManager.channelKeyPath(fundingPubKey)
    val ourToRemotePubKey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, ce.myCurrentPerCommitmentPoint)

    // spend our output
    val tx = Transaction(version = 2,
      txIn = TxIn(OutPoint(bobCommitTx, bobCommitTx.txOut.indexOf(ourOutput)), sequence = TxIn.SEQUENCE_FINAL, signatureScript = Nil) :: Nil,
      txOut = TxOut(Satoshi(1000), Script.pay2pkh(fr.acinq.eclair.randomKey.publicKey)) :: Nil,
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
}
