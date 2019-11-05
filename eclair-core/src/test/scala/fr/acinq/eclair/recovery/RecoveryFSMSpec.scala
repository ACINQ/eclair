package fr.acinq.eclair.recovery

import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{OP_2, OP_CHECKMULTISIG, OP_PUSHDATA, Script, ScriptWitness, Transaction}
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import fr.acinq.eclair.channel.{DATA_NORMAL, INPUT_DISCONNECTED, INPUT_RECONNECTED, NORMAL, OFFLINE}
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.wire.{ChannelReestablish, Init}
import org.scalatest.{FunSuite, FunSuiteLike, Outcome}

import scala.concurrent.duration._

class RecoveryFSMSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    within(30 seconds) {
      reachNormal(setup, test.tags)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      withFixture(test.toNoArgTest(setup))
    }
  }

  test("extract funding keys from witness") { f =>
    import f._

    val probe = TestProbe()

    val aliceStateData = alice.stateData.asInstanceOf[DATA_NORMAL]
    val commitTx = aliceStateData.commitments.localCommit.publishableTxs.commitTx
    val aliceCommitNumber = aliceStateData.commitments.localCommit.index
    val fundingKeyPath = aliceStateData.commitments.localParams.fundingKeyPath

    // disconnect the peers to obtain a channel_reestablish on reconnection
    probe.send(alice, INPUT_DISCONNECTED)
    probe.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    val aliceInit = Init(TestConstants.Alice.nodeParams.globalFeatures, TestConstants.Alice.nodeParams.localFeatures)
    val bobInit = Init(TestConstants.Bob.nodeParams.globalFeatures, TestConstants.Bob.nodeParams.localFeatures)

    // reconnect the input in alice's channel
    probe.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    val aliceBobReestablish = alice2bob.expectMsgType[ChannelReestablish]

    val aliceFundingKey = TestConstants.Alice.nodeParams.keyManager.fundingPublicKey(fundingKeyPath).publicKey
    val (key1, key2) = RecoveryFSM.extractKeysFromWitness(commitTx.tx.txIn.head.witness, aliceBobReestablish, aliceCommitNumber)
    assert(aliceFundingKey == key1 || aliceFundingKey == key2)
  }

}
