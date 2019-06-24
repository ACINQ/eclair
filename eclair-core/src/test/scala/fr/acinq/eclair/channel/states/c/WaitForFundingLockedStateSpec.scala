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
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import org.scalatest.Outcome
import scodec.bits._
import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */

class WaitForFundingLockedStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  case class FixtureParam(alice: TestFSMRef[State, Data, Channel], bob: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, router: TestProbe)

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
      bob2blockchain.expectMsgType[WatchSpent]
      bob2blockchain.expectMsgType[WatchConfirmed]
      alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42)
      bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42)
      alice2blockchain.expectMsgType[WatchLost]
      bob2blockchain.expectMsgType[WatchLost]
      alice2bob.expectMsgType[FundingLocked]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_LOCKED)
      awaitCond(bob.stateName == WAIT_FOR_FUNDING_LOCKED)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, router)))
    }
  }

  test("recv FundingLocked") { f =>
    import f._
    bob2alice.expectMsgType[FundingLocked]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == NORMAL)
    bob2alice.expectNoMsg(200 millis)
  }

  // this asserts we can move WAIT_FOR_FUNDING_LOCKED -> NORMAL after upgrading from master 316ba02f
  test("recv FundingLocked using a legacy DATA_WAIT_FOR_FUNDING_LOCKED") { f =>
    import f._

    val legacyStateData = ChannelCodecs.stateDataCodec.decode(hex"00000203af0ed6052cf28d670665549bc86f4b721c9fdb309d40c58f5811f63966e005d0000419bcfa4263dd8d3687392dafc9912ee2000000000000044c0000000008f0d1800000000000002710000000000000000000900064800b000a011bd50be544b3e72531e4bdcda249a7035bfe8a800000008001cee07058e92c82f227efb7c744baad2f10cb42c20685e0e94699847cb1451ac280000000000001f47fffffffffffffff800000000000271000000000000001f40048000f011b7b3af58e62bd1afb758ec699a9de5c024bb9ffa3a13eedff8cab2f42fadf8e811c3a0116ed5f9b6e70131b4a563f14887ad2c74da13b0153b7eeb674d6cc408c01536569e85f9f905ffa5517ddc1f95e88d0350df9ccf58760fa315521c3917e0b81ebd3e53a68001ba7bbe2e387b4de858147f0f3facbc7460251438d931177e89981e15184fa6a7d0750233dbfdfdf023c5ef72ae88d67c8e64f499e020fe4d0723f800000000000000000000000000000000013880000000017d784000000000005f5e10000121b6b4371be5edfc7bc25e0c9201d867b1869a30a84928bcb30f3aee423b28f14800000000015a0210780000000001100102b64496b535cc694f07c8ca04832eabc04df4910a9707df0d1d5478d5d3862bd0023a910811b7b3af58e62bd1afb758ec699a9de5c024bb9ffa3a13eedff8cab2f42fadf8e9081fc58d19c6c0c664fb329dd212a43e043a7094202c5f4410b04e964353b1770c8a95700ac810000000000809b6b4371be5edfc7bc25e0c9201d867b1869a30a84928bcb30f3aee423b28f1480000000000cbf3dc00120068180000000000b000a1ddff3dac3bc63f50b7f548c71c3c67189fac725dc0c06000000000011001012b70e548015620a96a8b89ff50d877830f87a49edfb7baf3cba2944eb6a3c58020023982201100be011964fa2b937fc410107f1196e12fc512be2f7495f09a954f8afe53d2a0a011019210ba8ef98698a7fb84c0598757e9c38fc3d81cf919bfb19d3b6676baff85500a39822011003ebbeb478fa8233d82d86402dbf5e5e3ab92a79c5093fb3b1a3792c1e51dc78811026e1122d3fd2ed2a14d61f3496679838c57cedcdb9e35a02145f34db5da701b880a3a910811b7b3af58e62bd1afb758ec699a9de5c024bb9ffa3a13eedff8cab2f42fadf8e9081fc58d19c6c0c664fb329dd212a43e043a7094202c5f4410b04e964353b1770c8a95702ba2d90000000000000000000000000000013880000000005f5e1000000000017d7840060a5b5bbab14ce247fdbfcef08972f3c4cbecb63bb5c7832b24db0150ab37da8013fb54c36f195bef6fd3034d4105a75b04d688b8e08d035e19df55b3a06623e2000000000000000000000000000000000000000000000000000000000000040e3559e01ef0db325dae812ad6a4f85fdfc4539ae02d2ced0e06cacc8b4cbf0f800090db5a1b8df2f6fe3de12f064900ec33d8c34d185424945e59879d77211d9478a40000000000ad01083c00000000008800815b224b5a9ae634a783e46502419755e026fa48854b83ef868eaa3c6ae9c315e8011d488408dbd9d7ac7315e8d7dbac7634cd4ef2e0125dcffd1d09f76ffc65597a17d6fc74840fe2c68ce36063327d994ee909521f021d384a10162fa20858274b21a9d8bb86454ab800006dad0dc6f97b7f1ef0978324807619ec61a68c2a124a2f2cc3cebb908eca3c520c350000005400006dad0dc6f97b7f1ef0978324807619ec61a68c2a124a2f2cc3cebb908eca3c52044526ddfd95ae01708239a4c3f48a04a762a4586607066e782569afa115590a840".bits).require.value
    alice.setState(WAIT_FOR_FUNDING_LOCKED, stateData = legacyStateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED])
    bob2alice.expectMsgType[FundingLocked]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == NORMAL)
    bob2alice.expectNoMsg(200 millis)
  }

  test("recv BITCOIN_FUNDING_SPENT (remote commit)") { f =>
    import f._
    // bob publishes his commitment tx
    val tx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, tx)
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv BITCOIN_FUNDING_SPENT (other commit)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, Transaction(0, Nil, Nil, 0))
    alice2bob.expectMsgType[Error]
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
  }

  test("recv Error") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(tx))
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    sender.send(alice, CMD_CLOSE(None))
    sender.expectMsg(Failure(CommandUnavailableInThisState(channelId(alice), "close", WAIT_FOR_FUNDING_LOCKED)))
  }

  test("recv CMD_FORCECLOSE") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! CMD_FORCECLOSE
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(tx))
  }
}
