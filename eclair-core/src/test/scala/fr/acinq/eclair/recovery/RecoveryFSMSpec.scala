package fr.acinq.eclair.recovery

import akka.testkit.TestProbe
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import fr.acinq.eclair.channel.{CMD_SIGN, DATA_NORMAL, INPUT_DISCONNECTED, INPUT_RECONNECTED, NORMAL, OFFLINE}
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import RecoveryFSM._
import fr.acinq.eclair.wire.{ChannelReestablish, CommitSig, Init, RevokeAndAck}
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

  test("recover out funding key and channel keypath from the remote commit tx") { f =>
    import f._

    val probe = TestProbe()

    val aliceFundingKey = TestConstants.Alice.nodeParams.keyManager.fundingPublicKey(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localParams.fundingKeyPath).publicKey
    val remotePublishedCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx

    // disconnect the peers to obtain a channel_reestablish on reconnection
    probe.send(alice, INPUT_DISCONNECTED)
    probe.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    val aliceInit = Init(TestConstants.Alice.nodeParams.globalFeatures, TestConstants.Alice.nodeParams.localFeatures)
    val bobInit = Init(TestConstants.Bob.nodeParams.globalFeatures, TestConstants.Bob.nodeParams.localFeatures)

    // reconnect the input in bob's channel, bob will send to alice a channel_reestablish
    probe.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))
    val bobAliceReestablish = bob2alice.expectMsgType[ChannelReestablish]

    val (key1, key2) = extractKeysFromWitness(remotePublishedCommitTx.txIn.head.witness, bobAliceReestablish)
    assert(aliceFundingKey == key1 || aliceFundingKey == key2)
    assert(isOurFundingKey(TestConstants.Alice.nodeParams.keyManager, remotePublishedCommitTx, aliceFundingKey, bobAliceReestablish))

    val recoveredFundingKey = recoverFundingKeyFromCommitment(TestConstants.Alice.nodeParams, remotePublishedCommitTx, bobAliceReestablish)
    assert(recoveredFundingKey == aliceFundingKey)
  }

}
