/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.channel.states.c

import akka.actor.Status.Failure
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{ByteVector32, Transaction}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.wire.{AcceptChannel, ChannelCodecs, Error, FundingCreated, FundingLocked, FundingSigned, Init, OpenChannel}
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import org.scalatest.Outcome
import scodec.bits._

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */

class WaitForFundingConfirmedStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  case class FixtureParam(alice: TestFSMRef[State, Data, Channel], bob: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, alice2bob.ref, bobInit, ChannelFlags.Empty)
      bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, Bob.channelParams, bob2alice.ref, aliceInit)
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[FundingSigned]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain)))
    }
  }

  test("recv FundingLocked") { f =>
    import f._
    // make bob send a FundingLocked msg
    bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 42000, 42)
    val msg = bob2alice.expectMsgType[FundingLocked]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].deferred.contains(msg))
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
  }

  test("recv BITCOIN_FUNDING_DEPTHOK") { f =>
    import f._
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 42000, 42)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_LOCKED)
    alice2blockchain.expectMsgType[WatchLost]
    alice2bob.expectMsgType[FundingLocked]
  }

  // this asserts we can move WAIT_FOR_FUNDING_CONFIRMED -> WAIT_FOR_FUNDING_LOCKED after upgrading from master 316ba02f
  test("recv BITCOIN_FUNDING_DEPTHOK using a legacy DATA_WAIT_FOR_FUNDING_CONFIRMED") { f =>
    import f._

    val legacyStateData = ChannelCodecs.stateDataCodec.decode(hex"00000803af0ed6052cf28d670665549bc86f4b721c9fdb309d40c58f5811f63966e005d00004087224bcb3e8633fe2886e6f3c18b703000000000000044c0000000008f0d1800000000000002710000000000000000000900064800b000a3c62de7dc8e62bd93b66cf47420e5790edef339a800000008001cee07058e92c82f227efb7c744baad2f10cb42c20685e0e94699847cb1451ac280000000000001f47fffffffffffffff800000000000271000000000000001f40048000f01ed9187296e4c54b479ae345140aa1391ccc9faf267f6c5944dfe664caa63bc87819a259d51b0799ae40ffe378da636a9295f0f1ca729fe7288e3fb726321ddacb9012a9a22a5fe0f279fd7a14a2470128271d76ef0d4f7e2e6a89d40372966b3c60d81d57eefaafa9c0d29da3771e8bfb57df89bf9222a6201a512dffb3002070c2bb101e68a0233838a37442cc5ac9367c5e33036d86c59da6504bf4b640c513dcc8f44000000000000000000000000000000000013880000000017d784000000000005f5e100001256455401b07c5bf408c59267c5c7f9ba4a3d95e328450deb5a7a3296d83ecab2800000000015a021078000000000110010646319c0c17e834063062d25ad955159489aea968e1b14d6cc58cb99be89c7838023a910813d7b72631b3de37925f4d91934a35e9f5178ff78c7deaba3b49ba5a94cff776b1081ed9187296e4c54b479ae345140aa1391ccc9faf267f6c5944dfe664caa63bc87a95700ad01000000000080d6455401b07c5bf408c59267c5c7f9ba4a3d95e328450deb5a7a3296d83ecab28000000000316633400120068180000000000b000a08f63f057773606da741e58e2f9d7a536d9d91dd5c0c0600000000001100105be873e3d5ba9979d72da3bbd5720a416b1d970bf2bc00702f1676655c18d65b82002418228110805db5dae6348bc53e64f001e567412275e64af83e5b66d71e6c0d693d833d8c3181102f27d53bd13e6e7581e9bc55cc101cb844a50f2094ef24a4b0f65fe6fbe6f67880a39822011020340f177c84953a0321b6c2cde893fa4d3629bd1f9b5164308fb5766196850d811010d91e80ba0f02a3ed8a31a84f897338f4549e6dab14b109b591d25a44da8b4000a3a910813d7b72631b3de37925f4d91934a35e9f5178ff78c7deaba3b49ba5a94cff776b1081ed9187296e4c54b479ae345140aa1391ccc9faf267f6c5944dfe664caa63bc87a9570382a890000000000000000000000000000013880000000005f5e1000000000017d784000eb072f0b13aeae10a66802ce65c477f1eb2a44be91c4f178a044479bf442060016197621911deea00ecdd7d789e91690661608651f7b5e7c6b7f30582b4dd6d2100000000000000000000000000000000000000000000000000000000000040c0c42642c86958a4e78c48f1078e03ccf00165e9483691069a1e70f59a15c1b580092b22aa00d83e2dfa0462c933e2e3fcdd251ecaf1942286f5ad3d194b6c1f655940000000000ad01083c00000000008800832318ce060bf41a031831692d6caa8aca44d754b470d8a6b662c65ccdf44e3c1c011d488409ebdb9318d9ef1bc92fa6c8c9a51af4fa8bc7fbc63ef55d1da4dd2d4a67fbbb58840f6c8c394b7262a5a3cd71a28a05509c8e664fd7933fb62ca26ff33265531de43d4ab8000159155006c1f16fd02316499f171fe6e928f6578ca11437ad69e8ca5b60fb2acb005e020000000101010101010101010101010101010101010101010101010101010101010101012a00000000ffffffff0140420f0000000000220020c8c6338182fd0680c60c5a4b5b2aa2b29135d52d1c3629ad98b197337d138f0700000000000000005d10c71400000000000000000000000000000000000000000000000000000000000000002b22aa00d83e2dfa0462c933e2e3fcdd251ecaf1942286f5ad3d194b6c1f655940003726337f1c37ac74d004a8836ac4fc05eb3dbb6f4c99301a2a4d079bc6fa9391d00e30f4a4a1aa0bb119b1528ca822195da27202992398f79c820cb0569ba4910".bits).require.value
    alice.setState(WAIT_FOR_FUNDING_CONFIRMED, stateData = legacyStateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED])
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 42000, 42)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_LOCKED)
    alice2blockchain.expectMsgType[WatchLost]
    alice2bob.expectMsgType[FundingLocked]
  }


  test("recv BITCOIN_FUNDING_PUBLISH_FAILED") { f =>
    import f._
    alice ! BITCOIN_FUNDING_PUBLISH_FAILED
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_FUNDING_TIMEOUT") { f =>
    import f._
    alice ! BITCOIN_FUNDING_TIMEOUT
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_FUNDING_SPENT (remote commit)") { f =>
    import f._
    // bob publishes his commitment tx
    val tx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, tx)
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv BITCOIN_FUNDING_SPENT (other commit)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, Transaction(0, Nil, Nil, 0))
    alice2bob.expectMsgType[Error]
    alice2blockchain.expectMsg(PublishAsap(tx))
    awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
  }

  test("recv Error") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap] // claim-main-delayed
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(tx))
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    sender.send(alice, CMD_CLOSE(None))
    sender.expectMsg(Failure(CommandUnavailableInThisState(channelId(alice), "close", WAIT_FOR_FUNDING_CONFIRMED)))
  }

  test("recv CMD_FORCECLOSE") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! CMD_FORCECLOSE
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap] // claim-main-delayed
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(tx))
  }

}
