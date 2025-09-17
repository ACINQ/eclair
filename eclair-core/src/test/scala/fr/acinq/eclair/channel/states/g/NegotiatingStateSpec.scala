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

package fr.acinq.eclair.channel.states.g

import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Satoshi, SatoshiLong, Script, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishTx, SetChannelId}
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.PimpTestFSM
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.testutils.PimpTestProbe._
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.ClosingSignedTlv.FeeRange
import fr.acinq.eclair.wire.protocol.{AnnouncementSignatures, ChannelUpdate, ClosingComplete, ClosingCompleteTlv, ClosingSig, ClosingSigTlv, ClosingSigned, ClosingTlv, Error, Shutdown, TlvStream, Warning}
import fr.acinq.eclair.{BlockHeight, CltvExpiry, Features, MilliSatoshiLong, TestConstants, TestKitBaseClass, randomBytes32, randomKey}
import org.scalatest.Inside.inside
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class NegotiatingStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    within(30 seconds) {
      reachNormal(setup, test.tags)
      withFixture(test.toNoArgTest(setup))
    }
  }

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  def aliceClose(f: FixtureParam, feerates: Option[ClosingFeerates] = None): Unit = {
    import f._
    val sender = TestProbe()
    alice ! CMD_CLOSE(sender.ref, None, feerates)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    val aliceShutdown = alice2bob.expectMsgType[Shutdown]
    if (alice.commitments.latest.commitmentFormat.isInstanceOf[TaprootCommitmentFormat]) assert(aliceShutdown.closeeNonce_opt.nonEmpty)
    alice2bob.forward(bob, aliceShutdown)
    val bobShutdown = bob2alice.expectMsgType[Shutdown]
    if (bob.commitments.latest.commitmentFormat.isInstanceOf[TaprootCommitmentFormat]) assert(bobShutdown.closeeNonce_opt.nonEmpty)
    bob2alice.forward(alice, bobShutdown)
    if (alice.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.localChannelParams.initFeatures.hasFeature(Features.SimpleClose)) {
      awaitCond(alice.stateName == NEGOTIATING_SIMPLE)
      awaitCond(bob.stateName == NEGOTIATING_SIMPLE)
    } else {
      awaitCond(alice.stateName == NEGOTIATING)
      assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localChannelParams.upfrontShutdownScript_opt.forall(_ == aliceShutdown.scriptPubKey))
      awaitCond(bob.stateName == NEGOTIATING)
      assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localChannelParams.upfrontShutdownScript_opt.forall(_ == bobShutdown.scriptPubKey))
    }
  }

  def bobClose(f: FixtureParam, feerates: Option[ClosingFeerates] = None): Unit = {
    import f._
    val sender = TestProbe()
    bob ! CMD_CLOSE(sender.ref, None, feerates)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    val bobShutdown = bob2alice.expectMsgType[Shutdown]
    if (bob.commitments.latest.commitmentFormat.isInstanceOf[TaprootCommitmentFormat]) assert(bobShutdown.closeeNonce_opt.nonEmpty)
    bob2alice.forward(alice, bobShutdown)
    val aliceShutdown = alice2bob.expectMsgType[Shutdown]
    if (alice.commitments.latest.commitmentFormat.isInstanceOf[TaprootCommitmentFormat]) assert(aliceShutdown.closeeNonce_opt.nonEmpty)
    alice2bob.forward(bob, aliceShutdown)
    if (bob.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.localChannelParams.initFeatures.hasFeature(Features.SimpleClose)) {
      awaitCond(alice.stateName == NEGOTIATING_SIMPLE)
      awaitCond(bob.stateName == NEGOTIATING_SIMPLE)
    } else {
      awaitCond(alice.stateName == NEGOTIATING)
      assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localChannelParams.upfrontShutdownScript_opt.forall(_ == aliceShutdown.scriptPubKey))
      awaitCond(bob.stateName == NEGOTIATING)
      assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localChannelParams.upfrontShutdownScript_opt.forall(_ == bobShutdown.scriptPubKey))
    }
  }

  def buildFeerates(feerate: FeeratePerKw, minFeerate: FeeratePerKw = FeeratePerKw(250 sat)): FeeratesPerKw =
    FeeratesPerKw.single(feerate).copy(minimum = minFeerate, slow = minFeerate)

  test("emit disabled channel update", Tag(ChannelStateTestsTags.ChannelsPublic), Tag(ChannelStateTestsTags.DoNotInterceptGossip)) { f =>
    import f._

    val aliceListener = TestProbe()
    systemA.eventStream.subscribe(aliceListener.ref, classOf[LocalChannelUpdate])
    val bobListener = TestProbe()
    systemB.eventStream.subscribe(bobListener.ref, classOf[LocalChannelUpdate])

    alice2bob.expectMsgType[AnnouncementSignatures]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[ChannelUpdate]
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[ChannelUpdate]
    assert(aliceListener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags.isEnabled)
    assert(bobListener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags.isEnabled)

    alice ! CMD_CLOSE(TestProbe().ref, None, None)
    alice2bob.expectMsgType[Shutdown]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[Shutdown]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == NEGOTIATING)
    awaitCond(bob.stateName == NEGOTIATING)

    assert(!aliceListener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags.isEnabled)
    assert(!bobListener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags.isEnabled)
  }

  test("recv CMD_ADD_HTLC") { f =>
    import f._
    aliceClose(f)
    alice2bob.expectMsgType[ClosingSigned]
    val sender = TestProbe()
    val add = CMD_ADD_HTLC(sender.ref, 5000000000L msat, randomBytes32(), CltvExpiry(300000), TestConstants.emptyOnionPacket, None, Reputation.Score.max, None, localOrigin(sender.ref))
    alice ! add
    val error = ChannelUnavailable(channelId(alice))
    sender.expectMsg(RES_ADD_FAILED(add, error, None))
    alice2bob.expectNoMessage(200 millis)
  }

  private def testClosingSignedDifferentFees(f: FixtureParam, bobInitiates: Boolean = false): Unit = {
    import f._

    // alice and bob see different on-chain feerates
    alice.setBitcoinCoreFeerates(FeeratesPerKw(minimum = FeeratePerKw(250 sat), fastest = FeeratePerKw(10_000 sat), fast = FeeratePerKw(8000 sat), medium = FeeratePerKw(5000 sat), slow = FeeratePerKw(2000 sat)))
    bob.setBitcoinCoreFeerates(FeeratesPerKw(minimum = FeeratePerKw(250 sat), fastest = FeeratePerKw(15_000 sat), fast = FeeratePerKw(12_500 sat), medium = FeeratePerKw(7500 sat), slow = FeeratePerKw(3000 sat)))

    if (bobInitiates) {
      bobClose(f)
    } else {
      aliceClose(f)
    }

    // alice is funder so she initiates the negotiation
    val aliceCloseSig1 = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceCloseSig1.feeSatoshis == 3370.sat) // matches a feerate of 5000 sat/kw
    assert(aliceCloseSig1.feeRange_opt.nonEmpty)
    assert(aliceCloseSig1.feeRange_opt.get.min < aliceCloseSig1.feeSatoshis)
    assert(aliceCloseSig1.feeSatoshis < aliceCloseSig1.feeRange_opt.get.max)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.length == 1)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length == 1)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.isEmpty)
    if (alice.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.channelParams.channelFeatures.hasFeature(Features.UpfrontShutdownScript)) {
      // check that the closing tx uses Alice and Bob's default closing scripts
      val closingTx = alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.head.unsignedTx.tx
      val expectedLocalScript = alice.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localChannelParams.upfrontShutdownScript_opt.get
      val expectedRemoteScript = bob.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localChannelParams.upfrontShutdownScript_opt.get
      assert(closingTx.txOut.map(_.publicKeyScript).toSet == Set(expectedLocalScript, expectedRemoteScript))
    }
    alice2bob.forward(bob)
    // bob answers with a counter proposition in alice's fee range
    val bobCloseSig1 = bob2alice.expectMsgType[ClosingSigned]
    assert(aliceCloseSig1.feeRange_opt.get.min < bobCloseSig1.feeSatoshis)
    assert(bobCloseSig1.feeSatoshis < aliceCloseSig1.feeRange_opt.get.max)
    assert(bobCloseSig1.feeRange_opt.nonEmpty)
    assert(aliceCloseSig1.feeSatoshis < bobCloseSig1.feeSatoshis)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.nonEmpty)
    bob2alice.forward(alice)
    // alice accepts this proposition
    val aliceCloseSig2 = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceCloseSig2.feeSatoshis == bobCloseSig1.feeSatoshis)
    alice2bob.forward(bob)
    assert(alice.stateName == CLOSING)
    assert(bob.stateName == CLOSING)

    val mutualCloseTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx == mutualCloseTx)
    assert(mutualCloseTx.txOut.length == 2) // NB: in the anchor outputs case, anchors are removed from the closing tx
    assert(aliceCloseSig2.feeSatoshis > Transactions.weight2fee(TestConstants.anchorOutputsFeeratePerKw, mutualCloseTx.weight())) // NB: closing fee is allowed to be higher than commit tx fee when using anchor outputs
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == mutualCloseTx.txid)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == mutualCloseTx.txid)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.map(_.tx) == List(mutualCloseTx))
    assert(bob.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.map(_.tx) == List(mutualCloseTx))
  }

  test("recv ClosingSigned (theirCloseFee != ourCloseFee)") { f =>
    testClosingSignedDifferentFees(f)
  }

  test("recv ClosingSigned (theirCloseFee != ourCloseFee, bob starts closing)") { f =>
    testClosingSignedDifferentFees(f, bobInitiates = true)
  }

  test("recv ClosingSigned (theirCloseFee != ourCloseFee, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testClosingSignedDifferentFees(f)
  }

  test("recv ClosingSigned (theirCloseFee != ourCloseFee, anchor outputs, upfront shutdown scripts)", Tag(ChannelStateTestsTags.AnchorOutputs), Tag(ChannelStateTestsTags.UpfrontShutdownScript)) { f =>
    testClosingSignedDifferentFees(f)
  }

  test("recv ClosingSigned (theirMinCloseFee > ourCloseFee)") { f =>
    import f._
    alice.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(10_000 sat)))
    bob.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(2500 sat)))
    aliceClose(f)
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    alice2bob.forward(bob)
    val bobCloseSig = bob2alice.expectMsgType[ClosingSigned]
    assert(bobCloseSig.feeSatoshis == aliceCloseSig.feeSatoshis)
  }

  test("recv ClosingSigned (theirMaxCloseFee < ourCloseFee)") { f =>
    import f._
    alice.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(5_000 sat)))
    bob.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(20_000 sat)))
    aliceClose(f)
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    alice2bob.forward(bob)
    val bobCloseSig = bob2alice.expectMsgType[ClosingSigned]
    assert(bobCloseSig.feeSatoshis == aliceCloseSig.feeRange_opt.get.max)
  }

  private def testClosingSignedSameFees(f: FixtureParam, bobInitiates: Boolean = false): Unit = {
    import f._

    // alice and bob see the same on-chain feerates
    alice.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(5000 sat)))
    bob.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(5000 sat)))

    if (bobInitiates) {
      bobClose(f)
    } else {
      aliceClose(f)
    }

    // alice is funder so she initiates the negotiation
    val aliceCloseSig1 = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceCloseSig1.feeSatoshis == 3370.sat) // matches a feerate of 5 000 sat/kw
    assert(aliceCloseSig1.feeRange_opt.nonEmpty)
    alice2bob.forward(bob)
    // bob agrees with that proposal
    val bobCloseSig1 = bob2alice.expectMsgType[ClosingSigned]
    assert(bobCloseSig1.feeSatoshis == aliceCloseSig1.feeSatoshis)
    val mutualCloseTx = bob2blockchain.expectMsgType[PublishFinalTx].tx
    assert(mutualCloseTx.txOut.length == 2) // NB: in the anchor outputs case, anchors are removed from the closing tx
    bob2alice.forward(alice)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == mutualCloseTx)
    assert(alice.stateName == CLOSING)
    assert(bob.stateName == CLOSING)
  }

  test("recv ClosingSigned (theirCloseFee == ourCloseFee)") { f =>
    testClosingSignedSameFees(f)
  }

  test("recv ClosingSigned (theirCloseFee == ourCloseFee, bob starts closing)") { f =>
    testClosingSignedSameFees(f, bobInitiates = true)
  }

  test("recv ClosingSigned (theirCloseFee == ourCloseFee, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testClosingSignedSameFees(f)
  }

  test("recv ClosingSigned (theirCloseFee == ourCloseFee, upfront shutdown script)", Tag(ChannelStateTestsTags.UpfrontShutdownScript)) { f =>
    testClosingSignedSameFees(f)
  }

  test("override on-chain fee estimator (funder)") { f =>
    import f._
    alice.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(10_000 sat)))
    bob.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(10_000 sat)))
    aliceClose(f, Some(ClosingFeerates(FeeratePerKw(2500 sat), FeeratePerKw(2000 sat), FeeratePerKw(3000 sat))))
    // alice initiates the negotiation with a very low feerate
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceCloseSig.feeSatoshis == 1685.sat)
    assert(aliceCloseSig.feeRange_opt.contains(FeeRange(1348 sat, 2022 sat)))
    alice2bob.forward(bob)
    // bob chooses alice's highest fee
    val bobCloseSig = bob2alice.expectMsgType[ClosingSigned]
    assert(bobCloseSig.feeSatoshis == 2022.sat)
    bob2alice.forward(alice)
    // alice accepts this proposition
    assert(alice2bob.expectMsgType[ClosingSigned].feeSatoshis == 2022.sat)
    alice2bob.forward(bob)
    val mutualCloseTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx == mutualCloseTx)
    awaitCond(alice.stateName == CLOSING)
    awaitCond(bob.stateName == CLOSING)
  }

  test("override on-chain fee estimator (fundee)") { f =>
    import f._
    alice.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(10_000 sat)))
    bob.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(10_000 sat)))
    bobClose(f, Some(ClosingFeerates(FeeratePerKw(2500 sat), FeeratePerKw(2000 sat), FeeratePerKw(3000 sat))))
    // alice is funder, so bob's override will simply be ignored
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceCloseSig.feeSatoshis == 6740.sat) // matches a feerate of 10000 sat/kw
    alice2bob.forward(bob)
    // bob directly agrees because their fee estimator matches
    val bobCloseSig = bob2alice.expectMsgType[ClosingSigned]
    assert(aliceCloseSig.feeSatoshis == bobCloseSig.feeSatoshis)
    bob2alice.forward(alice)
    val mutualCloseTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx == mutualCloseTx)
    awaitCond(alice.stateName == CLOSING)
    awaitCond(bob.stateName == CLOSING)
  }

  test("recv ClosingSigned (nothing at stake)", Tag(ChannelStateTestsTags.NoPushAmount)) { f =>
    import f._
    alice.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(5_000 sat)))
    bob.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(10_000 sat)))
    bobClose(f)
    val aliceCloseFee = alice2bob.expectMsgType[ClosingSigned].feeSatoshis
    alice2bob.forward(bob)
    val bobCloseFee = bob2alice.expectMsgType[ClosingSigned].feeSatoshis
    assert(aliceCloseFee == bobCloseFee)
    bob2blockchain.expectMsgType[PublishTx]
    awaitCond(bob.stateName == CLOSING)
  }

  private def makeLegacyClosingSigned(f: FixtureParam, closingFee: Satoshi): (ClosingSigned, ClosingSigned) = {
    import f._
    val aliceState = alice.stateData.asInstanceOf[DATA_NEGOTIATING]
    val aliceKeys = alice.underlyingActor.channelKeys
    val aliceScript = aliceState.localShutdown.scriptPubKey
    val bobState = bob.stateData.asInstanceOf[DATA_NEGOTIATING]
    val bobKeys = bob.underlyingActor.channelKeys
    val bobScript = bobState.localShutdown.scriptPubKey
    val (_, aliceClosingSigned) = Closing.MutualClose.makeClosingTx(aliceKeys, aliceState.commitments.latest, aliceScript, bobScript, ClosingFees(closingFee, closingFee, closingFee))
    val (_, bobClosingSigned) = Closing.MutualClose.makeClosingTx(bobKeys, bobState.commitments.latest, bobScript, aliceScript, ClosingFees(closingFee, closingFee, closingFee))
    (aliceClosingSigned.copy(tlvStream = TlvStream.empty), bobClosingSigned.copy(tlvStream = TlvStream.empty))
  }

  test("recv ClosingSigned (other side ignores our fee range, funder)") { f =>
    import f._
    alice.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(1000 sat)))
    aliceClose(f)
    val aliceClosing1 = alice2bob.expectMsgType[ClosingSigned]
    val Some(FeeRange(_, maxFee)) = aliceClosing1.feeRange_opt
    assert(aliceClosing1.feeSatoshis == 674.sat)
    assert(maxFee == 1348.sat)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length == 1)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.isEmpty)
    // bob makes a proposal outside our fee range
    val (_, bobClosing1) = makeLegacyClosingSigned(f, 2500 sat)
    bob2alice.send(alice, bobClosing1)
    val aliceClosing2 = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceClosing1.feeSatoshis < aliceClosing2.feeSatoshis)
    assert(aliceClosing2.feeSatoshis < 1600.sat)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length == 2)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.nonEmpty)
    val (_, bobClosing2) = makeLegacyClosingSigned(f, 2000 sat)
    bob2alice.send(alice, bobClosing2)
    val aliceClosing3 = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceClosing2.feeSatoshis < aliceClosing3.feeSatoshis)
    assert(aliceClosing3.feeSatoshis < 1800.sat)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length == 3)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.nonEmpty)
    val (_, bobClosing3) = makeLegacyClosingSigned(f, 1800 sat)
    bob2alice.send(alice, bobClosing3)
    val aliceClosing4 = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceClosing3.feeSatoshis < aliceClosing4.feeSatoshis)
    assert(aliceClosing4.feeSatoshis < 1800.sat)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length == 4)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.nonEmpty)
    val (_, bobClosing4) = makeLegacyClosingSigned(f, aliceClosing4.feeSatoshis)
    bob2alice.send(alice, bobClosing4)
    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.length == 1)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.head.tx)
  }

  test("recv ClosingSigned (other side ignores our fee range, fundee)") { f =>
    import f._
    bob.setBitcoinCoreFeerate(FeeratePerKw(10_000 sat))
    bobClose(f)
    // alice starts with a very low proposal
    val (aliceClosing1, _) = makeLegacyClosingSigned(f, 500 sat)
    alice2bob.send(bob, aliceClosing1)
    val bobClosing1 = bob2alice.expectMsgType[ClosingSigned]
    assert(3000.sat < bobClosing1.feeSatoshis)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length == 1)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.nonEmpty)
    val (aliceClosing2, _) = makeLegacyClosingSigned(f, 750 sat)
    alice2bob.send(bob, aliceClosing2)
    val bobClosing2 = bob2alice.expectMsgType[ClosingSigned]
    assert(bobClosing2.feeSatoshis < bobClosing1.feeSatoshis)
    assert(2000.sat < bobClosing2.feeSatoshis)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length == 2)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.nonEmpty)
    val (aliceClosing3, _) = makeLegacyClosingSigned(f, 1000 sat)
    alice2bob.send(bob, aliceClosing3)
    val bobClosing3 = bob2alice.expectMsgType[ClosingSigned]
    assert(bobClosing3.feeSatoshis < bobClosing2.feeSatoshis)
    assert(1500.sat < bobClosing3.feeSatoshis)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length == 3)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.nonEmpty)
    val (aliceClosing4, _) = makeLegacyClosingSigned(f, 1300 sat)
    alice2bob.send(bob, aliceClosing4)
    val bobClosing4 = bob2alice.expectMsgType[ClosingSigned]
    assert(bobClosing4.feeSatoshis < bobClosing3.feeSatoshis)
    assert(1300.sat < bobClosing4.feeSatoshis)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length == 4)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.nonEmpty)
    val (aliceClosing5, _) = makeLegacyClosingSigned(f, bobClosing4.feeSatoshis)
    alice2bob.send(bob, aliceClosing5)
    awaitCond(bob.stateName == CLOSING)
    assert(bob.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.length == 1)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx == bob.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.head.tx)
  }

  test("recv ClosingSigned (other side ignores our fee range, max iterations reached)") { f =>
    import f._
    alice.setBitcoinCoreFeerate(FeeratePerKw(1000 sat))
    aliceClose(f)
    for (_ <- 1 to Channel.MAX_NEGOTIATION_ITERATIONS) {
      val aliceClosing = alice2bob.expectMsgType[ClosingSigned]
      val Some(FeeRange(_, aliceMaxFee)) = aliceClosing.feeRange_opt
      val bobNextFee = (aliceClosing.feeSatoshis + 500.sat).max(aliceMaxFee + 1.sat)
      val (_, bobClosing) = makeLegacyClosingSigned(f, bobNextFee)
      bob2alice.send(alice, bobClosing)
    }
    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.length == 1)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.head.tx)
  }

  test("recv ClosingSigned (fee too low, fundee)") { f =>
    import f._
    alice.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(250 sat)))
    bob.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(10_000 sat), minFeerate = FeeratePerKw(750 sat)))
    bobClose(f)
    val aliceClosing = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceClosing.feeRange_opt.get.max < 500.sat)
    alice2bob.send(bob, aliceClosing)
    // Bob refuses to sign with that fee range
    bob2alice.expectMsgType[Warning]
    bob2alice.expectNoMessage(100 millis)
  }

  test("recv ClosingSigned (fee higher than commit tx fee)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val commitment = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest
    val commitFee = Transactions.commitTxFeeMsat(commitment.localCommitParams.dustLimit, commitment.localCommit.spec, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    aliceClose(f)
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceCloseSig.feeSatoshis > commitFee.truncateToSatoshi)
    alice2bob.forward(bob, aliceCloseSig)
    val bobCloseSig = bob2alice.expectMsgType[ClosingSigned]
    assert(bobCloseSig.feeSatoshis == aliceCloseSig.feeSatoshis)
    awaitCond(bob.stateName == CLOSING)
    val closingTx = bob.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.head.tx
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == closingTx.txid)
    bob2alice.forward(alice, bobCloseSig)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == closingTx.txid)
  }

  test("recv ClosingSigned (invalid sig)") { f =>
    import f._
    aliceClose(f)
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    val tx = bob.signCommitTx()
    bob ! aliceCloseSig.copy(signature = ByteVector64.Zeroes)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray).startsWith("invalid close signature"))
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  def testReceiveClosingCompleteBothOutputs(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._

    aliceClose(f)
    val aliceClosingComplete = alice2bob.expectMsgType[ClosingComplete]
    assert(aliceClosingComplete.fees > 0.sat)
    commitmentFormat match {
      case _: SegwitV0CommitmentFormat =>
        assert(aliceClosingComplete.closerAndCloseeOutputsSig_opt.nonEmpty)
        assert(aliceClosingComplete.closerOutputOnlySig_opt.nonEmpty)
      case _: TaprootCommitmentFormat =>
        assert(aliceClosingComplete.closerAndCloseeOutputsPartialSig_opt.nonEmpty)
        assert(aliceClosingComplete.closerOutputOnlyPartialSig_opt.nonEmpty)
    }
    assert(aliceClosingComplete.closeeOutputOnlySig_opt.orElse(aliceClosingComplete.closeeOutputOnlyPartialSig_opt).isEmpty)
    val bobClosingComplete = bob2alice.expectMsgType[ClosingComplete]
    assert(bobClosingComplete.fees > 0.sat)
    commitmentFormat match {
      case _: SegwitV0CommitmentFormat =>
        assert(bobClosingComplete.closerAndCloseeOutputsSig_opt.nonEmpty)
        assert(bobClosingComplete.closerOutputOnlySig_opt.nonEmpty)
      case _: TaprootCommitmentFormat =>
        assert(bobClosingComplete.closerAndCloseeOutputsPartialSig_opt.nonEmpty)
        assert(bobClosingComplete.closerOutputOnlyPartialSig_opt.nonEmpty)
    }
    assert(bobClosingComplete.closeeOutputOnlySig_opt.orElse(bobClosingComplete.closeeOutputOnlyPartialSig_opt).isEmpty)

    alice2bob.forward(bob, aliceClosingComplete)
    val bobClosingSig = bob2alice.expectMsgType[ClosingSig]
    assert(bobClosingSig.fees == aliceClosingComplete.fees)
    assert(bobClosingSig.lockTime == aliceClosingComplete.lockTime)
    commitmentFormat match {
      case _: SegwitV0CommitmentFormat => assert(bobClosingSig.closerAndCloseeOutputsSig_opt.nonEmpty)
      case _: TaprootCommitmentFormat => assert(bobClosingSig.closerAndCloseeOutputsPartialSig_opt.nonEmpty)
    }
    bob2alice.forward(alice, bobClosingSig)
    val aliceTx = alice2blockchain.expectMsgType[PublishFinalTx]
    assert(aliceTx.desc == "closing-tx")
    assert(aliceTx.fee > 0.sat)
    alice2blockchain.expectWatchTxConfirmed(aliceTx.tx.txid)
    inside(bob2blockchain.expectMsgType[PublishFinalTx]) { p =>
      assert(p.tx.txid == aliceTx.tx.txid)
      assert(p.fee == 0.sat)
    }
    bob2blockchain.expectWatchTxConfirmed(aliceTx.tx.txid)
    assert(alice.stateName == NEGOTIATING_SIMPLE)

    bob2alice.forward(alice, bobClosingComplete)
    val aliceClosingSig = alice2bob.expectMsgType[ClosingSig]
    assert(aliceClosingSig.fees == bobClosingComplete.fees)
    assert(aliceClosingSig.lockTime == bobClosingComplete.lockTime)
    commitmentFormat match {
      case _: SegwitV0CommitmentFormat => assert(aliceClosingSig.closerAndCloseeOutputsSig_opt.nonEmpty)
      case _: TaprootCommitmentFormat => assert(aliceClosingSig.closerAndCloseeOutputsPartialSig_opt.nonEmpty)
    }
    alice2bob.forward(bob, aliceClosingSig)
    val bobTx = bob2blockchain.expectMsgType[PublishFinalTx]
    assert(bobTx.desc == "closing-tx")
    assert(bobTx.fee > 0.sat)
    bob2blockchain.expectWatchTxConfirmed(bobTx.tx.txid)
    inside(alice2blockchain.expectMsgType[PublishFinalTx]) { p =>
      assert(p.tx.txid == bobTx.tx.txid)
      assert(p.fee == 0.sat)
    }
    assert(aliceTx.tx.txid != bobTx.tx.txid)
    alice2blockchain.expectWatchTxConfirmed(bobTx.tx.txid)
    assert(bob.stateName == NEGOTIATING_SIMPLE)
  }

  test("recv ClosingComplete (both outputs)", Tag(ChannelStateTestsTags.SimpleClose), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testReceiveClosingCompleteBothOutputs(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv ClosingComplete (both outputs, simple taproot channels)", Tag(ChannelStateTestsTags.SimpleClose), Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    testReceiveClosingCompleteBothOutputs(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  def testReceiveClosingCompleteSingleOutput(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._
    aliceClose(f)
    val closingComplete = alice2bob.expectMsgType[ClosingComplete]
    commitmentFormat match {
      case _: SegwitV0CommitmentFormat => assert(closingComplete.closerOutputOnlySig_opt.nonEmpty)
      case _: TaprootCommitmentFormat => assert(closingComplete.closerOutputOnlyPartialSig_opt.nonEmpty)
    }
    assert(closingComplete.closerAndCloseeOutputsSig_opt.isEmpty)
    assert(closingComplete.closerAndCloseeOutputsPartialSig_opt.isEmpty)
    assert(closingComplete.closeeOutputOnlySig_opt.isEmpty)
    assert(closingComplete.closeeOutputOnlyPartialSig_opt.isEmpty)
    // Bob has nothing at stake.
    bob2alice.expectNoMessage(100 millis)

    alice2bob.forward(bob, closingComplete)
    val closingSig = bob2alice.expectMsgType[ClosingSig]
    commitmentFormat match {
      case _: SegwitV0CommitmentFormat => assert(closingSig.closerOutputOnlySig_opt.nonEmpty)
      case _: TaprootCommitmentFormat => assert(closingSig.closerOutputOnlyPartialSig_opt.nonEmpty)
    }
    bob2alice.forward(alice, closingSig)
    val closingTx = alice2blockchain.expectMsgType[PublishFinalTx]
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == closingTx.tx.txid)
    alice2blockchain.expectWatchTxConfirmed(closingTx.tx.txid)
    bob2blockchain.expectWatchTxConfirmed(closingTx.tx.txid)
    assert(alice.stateName == NEGOTIATING_SIMPLE)
    assert(bob.stateName == NEGOTIATING_SIMPLE)
  }

  test("recv ClosingComplete (single output)", Tag(ChannelStateTestsTags.SimpleClose), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.NoPushAmount)) { f =>
    testReceiveClosingCompleteSingleOutput(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv ClosingComplete (single output, taproot)", Tag(ChannelStateTestsTags.SimpleClose), Tag(ChannelStateTestsTags.OptionSimpleTaproot), Tag(ChannelStateTestsTags.NoPushAmount)) { f =>
    testReceiveClosingCompleteSingleOutput(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  test("recv ClosingComplete (single output, trimmed)", Tag(ChannelStateTestsTags.SimpleClose), Tag(ChannelStateTestsTags.NoPushAmount)) { f =>
    import f._
    val (r, htlc) = addHtlc(250_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(htlc.id, r, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)

    aliceClose(f)
    val aliceClosingComplete = alice2bob.expectMsgType[ClosingComplete]
    assert(aliceClosingComplete.closerAndCloseeOutputsSig_opt.isEmpty)
    assert(aliceClosingComplete.closerOutputOnlySig_opt.nonEmpty)
    assert(aliceClosingComplete.closeeOutputOnlySig_opt.isEmpty)
    val bobClosingComplete = bob2alice.expectMsgType[ClosingComplete]
    assert(bobClosingComplete.closerAndCloseeOutputsSig_opt.isEmpty)
    assert(bobClosingComplete.closerOutputOnlySig_opt.isEmpty)
    assert(bobClosingComplete.closeeOutputOnlySig_opt.nonEmpty)

    bob2alice.forward(alice, bobClosingComplete)
    val aliceClosingSig = alice2bob.expectMsgType[ClosingSig]
    alice2bob.forward(bob, aliceClosingSig)
    val bobTx = bob2blockchain.expectMsgType[PublishFinalTx]
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == bobTx.tx.txid)
    bob2blockchain.expectWatchTxConfirmed(bobTx.tx.txid)
    alice2blockchain.expectWatchTxConfirmed(bobTx.tx.txid)
    assert(alice.stateName == NEGOTIATING_SIMPLE)
    assert(bob.stateName == NEGOTIATING_SIMPLE)
  }

  def testReceiveClosingCompleteMissingCloseeOutput(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._
    aliceClose(f)
    val aliceClosingComplete = alice2bob.expectMsgType[ClosingComplete]
    val bobClosingComplete = bob2alice.expectMsgType[ClosingComplete]
    val aliceClosingComplete1 = commitmentFormat match {
      case _: SegwitV0CommitmentFormat => aliceClosingComplete.copy(tlvStream = TlvStream(ClosingTlv.CloserOutputOnly(aliceClosingComplete.closerOutputOnlySig_opt.get)))
      case _: TaprootCommitmentFormat => aliceClosingComplete.copy(tlvStream = TlvStream(ClosingCompleteTlv.CloserOutputOnlyPartialSignature(aliceClosingComplete.closerOutputOnlyPartialSig_opt.get)))
    }
    alice2bob.forward(bob, aliceClosingComplete1)
    // Bob expects to receive a signature for a closing transaction containing his output, so he ignores Alice's
    // closing_complete instead of sending back his closing_sig.
    bob2alice.expectMsgType[Warning]
    bob2alice.expectNoMessage(100 millis)
    bob2alice.forward(alice, bobClosingComplete)
    val aliceClosingSig = alice2bob.expectMsgType[ClosingSig]
    val aliceClosingSig1 = commitmentFormat match {
      case _: SegwitV0CommitmentFormat => aliceClosingSig.copy(tlvStream = TlvStream(ClosingTlv.CloseeOutputOnly(aliceClosingSig.closerAndCloseeOutputsSig_opt.get)))
      case _: TaprootCommitmentFormat => aliceClosingSig.copy(tlvStream = TlvStream(ClosingSigTlv.CloseeOutputOnlyPartialSignature(aliceClosingSig.closerAndCloseeOutputsPartialSig_opt.get)))
    }
    alice2bob.forward(bob, aliceClosingSig1)
    bob2alice.expectMsgType[Warning]
    bob2alice.expectNoMessage(100 millis)
    bob2blockchain.expectNoMessage(100 millis)
  }

  test("recv ClosingComplete (missing closee output)", Tag(ChannelStateTestsTags.SimpleClose), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testReceiveClosingCompleteMissingCloseeOutput(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv ClosingComplete (missing closee output, taproot)", Tag(ChannelStateTestsTags.SimpleClose), Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    testReceiveClosingCompleteMissingCloseeOutput(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  test("recv ClosingComplete (with concurrent script update)", Tag(ChannelStateTestsTags.SimpleClose)) { f =>
    import f._
    aliceClose(f)
    alice2bob.expectMsgType[ClosingComplete]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[ClosingComplete]
    bob2alice.forward(alice)
    val aliceTx1 = bob2blockchain.expectMsgType[PublishFinalTx]
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceTx1.tx.txid)
    val bobTx1 = alice2blockchain.expectMsgType[PublishFinalTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobTx1.tx.txid)
    alice2bob.expectMsgType[ClosingSig]
    alice2bob.forward(bob)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == bobTx1.tx.txid)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == bobTx1.tx.txid)
    bob2alice.expectMsgType[ClosingSig]
    bob2alice.forward(alice)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == aliceTx1.tx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceTx1.tx.txid)
    val aliceScript1 = alice.stateData.asInstanceOf[DATA_NEGOTIATING_SIMPLE].localScriptPubKey
    val bobScript1 = bob.stateData.asInstanceOf[DATA_NEGOTIATING_SIMPLE].localScriptPubKey

    // Alice sends another closing_complete, updating her script and the fees.
    val probe = TestProbe()
    val aliceScript2 = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val aliceFeerate2 = alice.stateData.asInstanceOf[DATA_NEGOTIATING_SIMPLE].lastClosingFeerate * 1.25
    alice ! CMD_CLOSE(probe.ref, Some(aliceScript2), Some(ClosingFeerates(aliceFeerate2, aliceFeerate2, aliceFeerate2)))
    probe.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    inside(alice2bob.expectMsgType[ClosingComplete]) { msg =>
      assert(msg.fees > aliceTx1.fee)
      assert(msg.closerScriptPubKey == aliceScript2)
      assert(msg.closeeScriptPubKey == bobScript1)
    }
    // Bob also sends closing_complete concurrently, updating his script and the fees.
    val bobScript2 = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val bobFeerate2 = bob.stateData.asInstanceOf[DATA_NEGOTIATING_SIMPLE].lastClosingFeerate * 1.25
    bob ! CMD_CLOSE(probe.ref, Some(bobScript2), Some(ClosingFeerates(bobFeerate2, bobFeerate2, bobFeerate2)))
    probe.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    inside(bob2alice.expectMsgType[ClosingComplete]) { msg =>
      assert(msg.fees > bobTx1.fee)
      assert(msg.closerScriptPubKey == bobScript2)
      assert(msg.closeeScriptPubKey == aliceScript1)
    }
    // Those messages are ignored because they don't match the latest version of each participant's scripts.
    alice2bob.forward(bob)
    bob2alice.forward(alice)
    alice2bob.expectMsgType[Warning]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectMsgType[Warning]
    bob2alice.expectNoMessage(100 millis)

    // Alice retries with a higher fee, now that she received Bob's latest script.
    val aliceFeerate3 = aliceFeerate2 * 1.25
    alice ! CMD_CLOSE(probe.ref, Some(aliceScript2), Some(ClosingFeerates(aliceFeerate3, aliceFeerate3, aliceFeerate3)))
    probe.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    inside(alice2bob.expectMsgType[ClosingComplete]) { msg =>
      assert(msg.closerScriptPubKey == aliceScript2)
      assert(msg.closeeScriptPubKey == bobScript2)
    }
    alice2bob.forward(bob)
    val bobClosingSig3 = bob2alice.expectMsgType[ClosingSig]
    assert(bobClosingSig3.closerScriptPubKey == aliceScript2)
    assert(bobClosingSig3.closeeScriptPubKey == bobScript2)
    // Before receiving Bob's closing_sig, Alice updates her script again.
    val aliceFeerate4 = aliceFeerate3 * 1.25
    val aliceScript4 = Script.write(Script.pay2wpkh(randomKey().publicKey))
    alice ! CMD_CLOSE(probe.ref, Some(aliceScript4), Some(ClosingFeerates(aliceFeerate4, aliceFeerate4, aliceFeerate4)))
    probe.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    inside(alice2bob.expectMsgType[ClosingComplete]) { msg =>
      assert(msg.closerScriptPubKey == aliceScript4)
      assert(msg.closeeScriptPubKey == bobScript2)
    }
    alice2bob.forward(bob)
    val bobClosingSig4 = bob2alice.expectMsgType[ClosingSig]
    assert(bobClosingSig4.closerScriptPubKey == aliceScript4)
    assert(bobClosingSig4.closeeScriptPubKey == bobScript2)

    // The first closing_sig is ignored because it's not using Alice's latest script.
    bob2alice.forward(alice, bobClosingSig3)
    alice2bob.expectMsgType[Warning]
    alice2blockchain.expectNoMessage(100 millis)
    // The second closing_sig lets Alice broadcast a new version of her closing transaction.
    bob2alice.forward(alice, bobClosingSig4)
    val aliceTx4 = alice2blockchain.expectMsgType[PublishFinalTx]
    assert(aliceTx4.fee > aliceTx1.fee)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceTx4.tx.txid)
    alice2blockchain.expectNoMessage(100 millis)
    alice2bob.expectNoMessage(100 millis)
  }

  test("recv WatchFundingSpentTriggered (counterparty's mutual close)") { f =>
    import f._
    aliceClose(f)
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    alice2bob.forward(bob, aliceCloseSig)
    // at this point alice and bob agree on closing fees, but alice has not yet received the final signature whereas bob has
    // bob publishes the mutual close and alice is notified that the funding tx has been spent
    assert(alice.stateName == NEGOTIATING)
    val mutualCloseTx = bob2blockchain.expectMsgType[PublishFinalTx].tx
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == mutualCloseTx.txid)
    alice ! WatchFundingSpentTriggered(mutualCloseTx)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == mutualCloseTx)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == mutualCloseTx.txid)
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (an older mutual close)") { f =>
    import f._
    alice.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(1000 sat)))
    bob.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(10_000 sat)))
    aliceClose(f)
    val aliceClosing1 = alice2bob.expectMsgType[ClosingSigned]
    alice2bob.forward(bob, aliceClosing1)
    bob2alice.expectMsgType[ClosingSigned]
    val Some(firstMutualCloseTx) = bob.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt
    val (_, bobClosing1) = makeLegacyClosingSigned(f, 3000 sat)
    assert(bobClosing1.feeSatoshis !== aliceClosing1.feeSatoshis)
    bob2alice.send(alice, bobClosing1)
    val aliceClosing2 = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceClosing2.feeSatoshis !== bobClosing1.feeSatoshis)
    val Some(latestMutualCloseTx) = alice.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt
    assert(firstMutualCloseTx.tx.txid !== latestMutualCloseTx.tx.txid)
    // at this point bob will receive a new signature, but he decides instead to publish the first mutual close
    alice ! WatchFundingSpentTriggered(firstMutualCloseTx.tx)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == firstMutualCloseTx.tx)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == firstMutualCloseTx.tx.txid)
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (self mutual close)") { f =>
    import f._
    bob.setBitcoinCoreFeerate(FeeratePerKw(10_000 sat))
    bobClose(f)
    // alice starts with a very low proposal
    val (aliceClosing1, _) = makeLegacyClosingSigned(f, 500 sat)
    alice2bob.send(bob, aliceClosing1)
    bob2alice.expectMsgType[ClosingSigned]
    // at this point bob has received a mutual close signature from alice, but doesn't yet agree on the fee
    // bob's mutual close is published from the outside of the actor
    assert(bob.stateName == NEGOTIATING)
    val mutualCloseTx = bob.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.get.tx
    bob ! WatchFundingSpentTriggered(mutualCloseTx)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx == mutualCloseTx)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == mutualCloseTx.txid)
    bob2blockchain.expectNoMessage(100 millis)
    assert(bob.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (signed closing tx)", Tag(ChannelStateTestsTags.SimpleClose)) { f =>
    import f._
    bobClose(f)
    // Alice and Bob publish a first closing tx.
    val aliceClosingComplete1 = alice2bob.expectMsgType[ClosingComplete]
    alice2bob.forward(bob, aliceClosingComplete1)
    val bobClosingComplete1 = bob2alice.expectMsgType[ClosingComplete]
    bob2alice.forward(alice, bobClosingComplete1)
    val aliceClosingSig1 = alice2bob.expectMsgType[ClosingSig]
    val bobTx1 = alice2blockchain.expectMsgType[PublishFinalTx].tx
    alice2blockchain.expectWatchTxConfirmed(bobTx1.txid)
    val bobClosingSig1 = bob2alice.expectMsgType[ClosingSig]
    val aliceTx1 = bob2blockchain.expectMsgType[PublishFinalTx].tx
    bob2blockchain.expectWatchTxConfirmed(aliceTx1.txid)
    alice2bob.forward(bob, aliceClosingSig1)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == bobTx1.txid)
    bob2blockchain.expectWatchTxConfirmed(bobTx1.txid)
    bob2alice.forward(alice, bobClosingSig1)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == aliceTx1.txid)
    alice2blockchain.expectWatchTxConfirmed(aliceTx1.txid)

    // Alice updates her closing script.
    alice ! CMD_CLOSE(TestProbe().ref, Some(Script.write(Script.pay2wpkh(randomKey().publicKey))), None)
    alice2bob.expectMsgType[ClosingComplete]
    alice2bob.forward(bob)
    val bobClosingSig = bob2alice.expectMsgType[ClosingSig]
    bob2alice.forward(alice, bobClosingSig)
    val aliceTx2 = alice2blockchain.expectMsgType[PublishFinalTx].tx
    alice2blockchain.expectWatchTxConfirmed(aliceTx2.txid)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == aliceTx2.txid)
    bob2blockchain.expectWatchTxConfirmed(aliceTx2.txid)

    // They first receive a watch event for the older transaction, then the new one.
    alice ! WatchFundingSpentTriggered(aliceTx1)
    alice2blockchain.expectWatchTxConfirmed(aliceTx1.txid)
    alice ! WatchFundingSpentTriggered(bobTx1)
    alice2blockchain.expectWatchTxConfirmed(bobTx1.txid)
    alice ! WatchFundingSpentTriggered(aliceTx2)
    alice2blockchain.expectWatchTxConfirmed(aliceTx2.txid)
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == NEGOTIATING_SIMPLE)
    bob ! WatchFundingSpentTriggered(aliceTx1)
    bob2blockchain.expectWatchTxConfirmed(aliceTx1.txid)
    bob ! WatchFundingSpentTriggered(bobTx1)
    bob2blockchain.expectWatchTxConfirmed(bobTx1.txid)
    bob ! WatchFundingSpentTriggered(aliceTx2)
    bob2blockchain.expectWatchTxConfirmed(aliceTx2.txid)
    bob2blockchain.expectNoMessage(100 millis)
    assert(bob.stateName == NEGOTIATING_SIMPLE)
  }

  test("recv WatchFundingSpentTriggered (unsigned closing tx)", Tag(ChannelStateTestsTags.SimpleClose)) { f =>
    import f._
    bobClose(f)
    val aliceClosingComplete = alice2bob.expectMsgType[ClosingComplete]
    alice2bob.forward(bob, aliceClosingComplete)
    val bobClosingComplete = bob2alice.expectMsgType[ClosingComplete]
    bob2alice.forward(alice, bobClosingComplete)
    alice2bob.expectMsgType[ClosingSig]
    val bobTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
    alice2blockchain.expectWatchTxConfirmed(bobTx.txid)
    bob2alice.expectMsgType[ClosingSig]
    val aliceTx = bob2blockchain.expectMsgType[PublishFinalTx].tx
    bob2blockchain.expectWatchTxConfirmed(aliceTx.txid)

    alice ! WatchFundingSpentTriggered(aliceTx)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == aliceTx.txid)
    alice2blockchain.expectWatchTxConfirmed(aliceTx.txid)
    alice2blockchain.expectNoMessage(100 millis)

    bob ! WatchFundingSpentTriggered(bobTx)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == bobTx.txid)
    bob2blockchain.expectWatchTxConfirmed(bobTx.txid)
    bob2blockchain.expectNoMessage(100 millis)
  }

  test("recv WatchFundingSpentTriggered (unrecognized commit)") { f =>
    import f._
    bobClose(f)
    alice ! WatchFundingSpentTriggered(Transaction(0, Nil, Nil, 0))
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == NEGOTIATING)
  }

  test("recv WatchFundingSpentTriggered (unrecognized commit, option_simple_close)", Tag(ChannelStateTestsTags.SimpleClose)) { f =>
    import f._
    bobClose(f)
    alice ! WatchFundingSpentTriggered(Transaction(0, Nil, Nil, 0))
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == NEGOTIATING_SIMPLE)
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    bobClose(f)
    alice2bob.expectMsgType[ClosingSigned]
    val sender = TestProbe()
    alice ! CMD_CLOSE(sender.ref, None, None)
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, ClosingAlreadyInProgress]]
  }

  test("recv CMD_CLOSE with updated feerates") { f =>
    import f._
    alice.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(250 sat)))
    bob.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(10_000 sat), minFeerate = FeeratePerKw(750 sat)))
    bobClose(f)
    val aliceClosing1 = alice2bob.expectMsgType[ClosingSigned]
    alice2bob.send(bob, aliceClosing1)
    // Bob refuses to sign with that fee range
    bob2alice.expectMsgType[Warning]
    bob2alice.expectNoMessage(100 millis)
    // Alice offered a fee range that was too low: she notices the warning sent by Bob and retries with higher fees.
    val sender = TestProbe()
    val closingScript = alice.stateData.asInstanceOf[DATA_NEGOTIATING].localShutdown.scriptPubKey
    val closingFeerates = ClosingFeerates(FeeratePerKw(1000 sat), FeeratePerKw(500 sat), FeeratePerKw(2000 sat))
    alice ! CMD_CLOSE(sender.ref, Some(closingScript.reverse), Some(closingFeerates))
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, ClosingAlreadyInProgress]]
    alice ! CMD_CLOSE(sender.ref, Some(closingScript), Some(closingFeerates))
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    val aliceClosing2 = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceClosing2.feeSatoshis > aliceClosing1.feeSatoshis)
    alice2bob.send(bob, aliceClosing2)
    val bobClosing = bob2alice.expectMsgType[ClosingSigned] // Bob accepts this new fee range
    bob2alice.forward(alice, bobClosing)
    val aliceClosing3 = alice2bob.expectMsgType[ClosingSigned]
    alice2bob.forward(bob, aliceClosing3)
    val mutualCloseTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx == mutualCloseTx)
    awaitCond(alice.stateName == CLOSING)
    awaitCond(bob.stateName == CLOSING)
  }

  test("recv CMD_CLOSE with RBF feerates (taproot)", Tag(ChannelStateTestsTags.SimpleClose), Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    import f._
    // Alice creates a first closing transaction.
    aliceClose(f)
    alice2bob.expectMsgType[ClosingComplete]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[ClosingComplete] // ignored
    val aliceTx1 = bob2blockchain.expectMsgType[PublishFinalTx]
    bob2blockchain.expectWatchTxConfirmed(aliceTx1.tx.txid)
    val closingSig1 = bob2alice.expectMsgType[ClosingSig]
    assert(closingSig1.nextCloseeNonce_opt.nonEmpty)
    bob2alice.forward(alice, closingSig1)
    alice2blockchain.expectFinalTxPublished(aliceTx1.tx.txid)
    alice2blockchain.expectWatchTxConfirmed(aliceTx1.tx.txid)

    // Alice sends another closing_complete, updating her fees.
    val probe = TestProbe()
    val aliceFeerate2 = alice.stateData.asInstanceOf[DATA_NEGOTIATING_SIMPLE].lastClosingFeerate * 1.25
    alice ! CMD_CLOSE(probe.ref, None, Some(ClosingFeerates(aliceFeerate2, aliceFeerate2, aliceFeerate2)))
    probe.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    assert(alice2bob.expectMsgType[ClosingComplete].fees > aliceTx1.fee)
    alice2bob.forward(bob)
    val aliceTx2 = bob2blockchain.expectMsgType[PublishFinalTx]
    bob2blockchain.expectWatchTxConfirmed(aliceTx2.tx.txid)
    val closingSig2 = bob2alice.expectMsgType[ClosingSig]
    assert(closingSig2.nextCloseeNonce_opt.nonEmpty)
    assert(closingSig2.nextCloseeNonce_opt != closingSig1.nextCloseeNonce_opt)
    bob2alice.forward(alice, closingSig2)
    alice2blockchain.expectFinalTxPublished(aliceTx2.tx.txid)
    alice2blockchain.expectWatchTxConfirmed(aliceTx2.tx.txid)
  }

  test("recv CMD_CLOSE with RBF feerate too low", Tag(ChannelStateTestsTags.SimpleClose)) { f =>
    import f._

    alice.setBitcoinCoreFeerates(buildFeerates(FeeratePerKw(500 sat)))
    aliceClose(f)
    alice2bob.expectMsgType[ClosingComplete]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[ClosingComplete] // ignored
    val bobClosingSig = bob2alice.expectMsgType[ClosingSig]
    bob2alice.forward(alice, bobClosingSig)

    val probe = TestProbe()
    alice ! CMD_CLOSE(probe.ref, None, Some(ClosingFeerates(FeeratePerKw(450 sat), FeeratePerKw(450 sat), FeeratePerKw(450 sat))))
    probe.expectMsgType[RES_FAILURE[CMD_CLOSE, InvalidRbfFeerate]]
    alice ! CMD_CLOSE(probe.ref, None, Some(ClosingFeerates(FeeratePerKw(500 sat), FeeratePerKw(500 sat), FeeratePerKw(500 sat))))
    probe.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
  }

  test("receive INPUT_RESTORED", Tag(ChannelStateTestsTags.SimpleClose)) { f =>
    import f._
    aliceClose(f)
    alice2bob.expectMsgType[ClosingComplete]
    alice2bob.forward(bob)
    val aliceTx = bob2blockchain.expectMsgType[PublishFinalTx].tx
    bob2blockchain.expectWatchTxConfirmed(aliceTx.txid)
    bob2alice.expectMsgType[ClosingComplete]
    bob2alice.forward(alice)
    val bobTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
    alice2blockchain.expectWatchTxConfirmed(bobTx.txid)
    alice2bob.expectMsgType[ClosingSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[ClosingSig] // Alice doesn't receive Bob's closing_sig
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == bobTx.txid)
    bob2blockchain.expectWatchTxConfirmed(bobTx.txid)
    val aliceData = alice.underlyingActor.nodeParams.db.channels.getChannel(channelId(alice)).get
    val bobData = bob.underlyingActor.nodeParams.db.channels.getChannel(channelId(bob)).get

    // Alice restarts before receiving Bob's closing_sig: she cannot publish her own closing transaction, but will
    // detect it when receiving it in her mempool (or in the blockchain).
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(aliceData)
    alice2blockchain.expectMsgType[SetChannelId]
    alice2blockchain.expectMsgType[WatchFundingSpent]
    awaitCond(alice.stateName == OFFLINE)

    // Alice's transaction (published by Bob) confirms.
    alice ! WatchFundingSpentTriggered(aliceTx)
    inside(alice2blockchain.expectMsgType[PublishFinalTx]) { p =>
      assert(p.tx.txid == aliceTx.txid)
      assert(p.fee > 0.sat)
    }
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceTx.txid)
    alice ! WatchTxConfirmedTriggered(BlockHeight(100), 3, aliceTx)
    awaitCond(alice.stateName == CLOSED)

    // Bob restarts and detects that Alice's closing transaction is confirmed.
    bob.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    bob ! INPUT_RESTORED(bobData)
    bob2blockchain.expectMsgType[SetChannelId]
    bob2blockchain.expectMsgType[WatchFundingSpent]
    awaitCond(bob.stateName == OFFLINE)
    bob ! WatchFundingSpentTriggered(aliceTx)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceTx.txid)
    bob ! WatchTxConfirmedTriggered(BlockHeight(100), 3, aliceTx)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv Error") { f =>
    import f._
    bobClose(f)
    alice2bob.expectMsgType[ClosingSigned]
    val tx = alice.signCommitTx()
    alice ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
  }

  test("recv Error (option_simple_close)", Tag(ChannelStateTestsTags.SimpleClose)) { f =>
    import f._
    aliceClose(f)
    val closingComplete = alice2bob.expectMsgType[ClosingComplete]
    alice2bob.forward(bob, closingComplete)
    bob2alice.expectMsgType[ClosingComplete]
    val closingSig = bob2alice.expectMsgType[ClosingSig]
    bob2alice.forward(alice, closingSig)
    val closingTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
    alice2blockchain.expectWatchTxConfirmed(closingTx.txid)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == closingTx.txid)
    bob2blockchain.expectWatchTxConfirmed(closingTx.txid)

    alice ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.nonEmpty)
    alice2blockchain.expectNoMessage(100 millis) // we have a mutual close transaction, so we don't publish the commit tx

    bob ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(bob.stateName == CLOSING)
    assert(bob.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.nonEmpty)
    bob2blockchain.expectNoMessage(100 millis) // we have a mutual close transaction, so we don't publish the commit tx
  }

}
