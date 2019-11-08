package fr.acinq.eclair.recovery

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import fr.acinq.eclair.channel.{CMD_SIGN, Channel, DATA_NORMAL, Data, INPUT_DISCONNECTED, INPUT_RECONNECTED, NORMAL, OFFLINE, State}
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import RecoveryFSM._
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.io.{NodeURI, PeerConnected}
import fr.acinq.eclair.wire.{ChannelReestablish, CommitSig, Init, RevokeAndAck}
import org.json4s.JsonAST
import org.json4s.JsonAST.{JNull, JObject, JString}
import org.mockito.scalatest.{IdiomaticMockito, MockitoSugar}
import org.scalatest.{FunSuite, FunSuiteLike, Outcome}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

class RecoveryFSMSpec extends TestkitBaseClass with StateTestsHelperMethods with IdiomaticMockito {

  type FixtureParam = SetupFixtureFSM

  case class SetupFixtureFSM(alice: TestFSMRef[State, Data, Channel],
                          bob: TestFSMRef[State, Data, Channel],
                          bob2alice: TestProbe,
                          fundingTx: Transaction)


  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    within(30 seconds) {
      val fundingTx = reachNormal(setup, test.tags)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      withFixture(test.toNoArgTest(SetupFixtureFSM(alice, bob, bob2alice, fundingTx)))
    }
  }

  test("recover our funding key and channel keypath from the remote commit tx") { f =>
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

  test("find the funding transaction id from channel id"){ f =>
    import f._

    val probe = TestProbe()
    val aliceStateData = f.alice.stateData.asInstanceOf[DATA_NORMAL]
    val fundingId = aliceStateData.commitments.commitInput.outPoint.txid
    val channelId = aliceStateData.commitments.channelId

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

    val nodeParams = TestConstants.Alice.nodeParams
    val remotePeer = TestProbe()
    val remotePeerId = TestConstants.Bob.nodeParams.nodeId

    // given the channel id the recovery FSM guesses several funding tx ids, this mock rpc client replies with the funding transaction
    // only if the query is correct, which means when the parameter txid is equal to the funding txid,
    val bitcoinRpcClient = new BitcoinJsonRPCClient {
      override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JsonAST.JValue] = method match {
        case "getrawtransaction" if params.head.asInstanceOf[String] == fundingId.toHex => Future.successful(
          JString(Transaction.write(fundingTx).toHex)
        )
        case _ => Future.successful(JNull)
      }
    }

    val recoveryFSM = TestFSMRef(new RecoveryFSM(NodeURI(remotePeerId, HostAndPort.fromHost("localhost")), nodeParams, new TestWallet, bitcoinRpcClient))
    recoveryFSM.setState(RECOVERY_WAIT_FOR_CONNECTION, DATA_WAIT_FOR_CONNECTION(remotePeerId))

    // skip peer connection
    probe.send(recoveryFSM, PeerConnected(remotePeer.ref, remotePeerId))
    awaitCond(recoveryFSM.stateName == RECOVERY_WAIT_FOR_CHANNEL)

    // send a ChannelFound event with channel_reestablish to the recoveryFSM -- the channel has been found
    probe.send(recoveryFSM, ChannelFound(channelId, bobAliceReestablish))

    // the recovery FSM replies with an error asking the remote to publish its commitment
    val sendError = remotePeer.expectMsgType[SendErrorToRemote]
    assert(sendError.error.toAscii.contains("please publish your local commitment"))

    awaitCond(recoveryFSM.stateName == RECOVERY_WAIT_FOR_COMMIT_PUBLISHED)
  }

}
