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
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Satoshi, SatoshiLong}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishRawTx, PublishTx}
import fr.acinq.eclair.channel.states.{StateTestsBase, StateTestsTags}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol.ClosingSignedTlv.FeeRange
import fr.acinq.eclair.wire.protocol.{ClosingSigned, Error, Shutdown, TlvStream, Warning}
import fr.acinq.eclair.{CltvExpiry, MilliSatoshiLong, TestConstants, TestKitBaseClass, randomBytes32}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class NegotiatingStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsBase {

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
    alice2bob.expectMsgType[Shutdown]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[Shutdown]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == NEGOTIATING)
    awaitCond(bob.stateName == NEGOTIATING)
  }

  def bobClose(f: FixtureParam, feerates: Option[ClosingFeerates] = None): Unit = {
    import f._
    val sender = TestProbe()
    bob ! CMD_CLOSE(sender.ref, None, feerates)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    bob2alice.expectMsgType[Shutdown]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[Shutdown]
    alice2bob.forward(bob)
    awaitCond(alice.stateName == NEGOTIATING)
    awaitCond(bob.stateName == NEGOTIATING)
  }

  def setFeerate(feeEstimator: TestConstants.TestFeeEstimator, feerate: FeeratePerKw, minFeerate: FeeratePerKw = FeeratePerKw(250 sat)): Unit = {
    feeEstimator.setFeerate(FeeratesPerKw.single(feerate).copy(mempoolMinFee = minFeerate, blocks_1008 = minFeerate))
  }

  test("recv CMD_ADD_HTLC") { f =>
    import f._
    aliceClose(f)
    alice2bob.expectMsgType[ClosingSigned]
    val sender = TestProbe()
    val add = CMD_ADD_HTLC(sender.ref, 5000000000L msat, randomBytes32(), CltvExpiry(300000), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    val error = ChannelUnavailable(channelId(alice))
    sender.expectMsg(RES_ADD_FAILED(add, error, None))
    alice2bob.expectNoMessage(200 millis)
  }

  private def testClosingSignedDifferentFees(f: FixtureParam, bobInitiates: Boolean = false): Unit = {
    import f._

    // alice and bob see different on-chain feerates
    alice.feeEstimator.setFeerate(FeeratesPerKw(FeeratePerKw(250 sat), FeeratePerKw(10000 sat), FeeratePerKw(5000 sat), FeeratePerKw(2000 sat), FeeratePerKw(2000 sat), FeeratePerKw(2000 sat), FeeratePerKw(2000 sat), FeeratePerKw(2000 sat), FeeratePerKw(2000 sat)))
    bob.feeEstimator.setFeerate(FeeratesPerKw(FeeratePerKw(250 sat), FeeratePerKw(15000 sat), FeeratePerKw(7500 sat), FeeratePerKw(3000 sat), FeeratePerKw(3000 sat), FeeratePerKw(3000 sat), FeeratePerKw(3000 sat), FeeratePerKw(3000 sat), FeeratePerKw(3000 sat)))
    assert(alice.feeTargets.mutualCloseBlockTarget == 2)
    assert(bob.feeTargets.mutualCloseBlockTarget == 2)

    if (bobInitiates) {
      bobClose(f)
    } else {
      aliceClose(f)
    }

    // alice is funder so she initiates the negotiation
    val aliceCloseSig1 = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceCloseSig1.feeSatoshis === 3370.sat) // matches a feerate of 5000 sat/kw
    assert(aliceCloseSig1.feeRange_opt.nonEmpty)
    assert(aliceCloseSig1.feeRange_opt.get.min < aliceCloseSig1.feeSatoshis)
    assert(aliceCloseSig1.feeSatoshis < aliceCloseSig1.feeRange_opt.get.max)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.length === 1)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length === 1)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.isEmpty)
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
    assert(aliceCloseSig2.feeSatoshis === bobCloseSig1.feeSatoshis)
    alice2bob.forward(bob)
    assert(alice.stateName == CLOSING)
    assert(bob.stateName == CLOSING)

    val mutualCloseTx = alice2blockchain.expectMsgType[PublishRawTx].tx
    assert(bob2blockchain.expectMsgType[PublishRawTx].tx === mutualCloseTx)
    assert(mutualCloseTx.txOut.length === 2) // NB: in the anchor outputs case, anchors are removed from the closing tx
    assert(aliceCloseSig2.feeSatoshis > Transactions.weight2fee(TestConstants.anchorOutputsFeeratePerKw, mutualCloseTx.weight())) // NB: closing fee is allowed to be higher than commit tx fee when using anchor outputs
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === mutualCloseTx.txid)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId === mutualCloseTx.txid)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.map(_.tx) === List(mutualCloseTx))
    assert(bob.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.map(_.tx) === List(mutualCloseTx))
  }

  test("recv ClosingSigned (theirCloseFee != ourCloseFee)") { f =>
    testClosingSignedDifferentFees(f)
  }

  test("recv ClosingSigned (theirCloseFee != ourCloseFee, bob starts closing)") { f =>
    testClosingSignedDifferentFees(f, bobInitiates = true)
  }

  test("recv ClosingSigned (theirCloseFee != ourCloseFee, anchor outputs)", Tag(StateTestsTags.AnchorOutputs)) { f =>
    testClosingSignedDifferentFees(f)
  }

  test("recv ClosingSigned (theirMinCloseFee > ourCloseFee)") { f =>
    import f._
    setFeerate(alice.feeEstimator, FeeratePerKw(10000 sat))
    setFeerate(bob.feeEstimator, FeeratePerKw(2500 sat))
    aliceClose(f)
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    alice2bob.forward(bob)
    val bobCloseSig = bob2alice.expectMsgType[ClosingSigned]
    assert(bobCloseSig.feeSatoshis === aliceCloseSig.feeSatoshis)
  }

  test("recv ClosingSigned (theirMaxCloseFee < ourCloseFee)") { f =>
    import f._
    setFeerate(alice.feeEstimator, FeeratePerKw(5000 sat))
    setFeerate(bob.feeEstimator, FeeratePerKw(20000 sat))
    aliceClose(f)
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    alice2bob.forward(bob)
    val bobCloseSig = bob2alice.expectMsgType[ClosingSigned]
    assert(bobCloseSig.feeSatoshis === aliceCloseSig.feeRange_opt.get.max)
  }

  private def testClosingSignedSameFees(f: FixtureParam, bobInitiates: Boolean = false): Unit = {
    import f._

    // alice and bob see the same on-chain feerates
    setFeerate(alice.feeEstimator, FeeratePerKw(5000 sat))
    setFeerate(bob.feeEstimator, FeeratePerKw(5000 sat))

    if (bobInitiates) {
      bobClose(f)
    } else {
      aliceClose(f)
    }

    // alice is funder so she initiates the negotiation
    val aliceCloseSig1 = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceCloseSig1.feeSatoshis === 3370.sat) // matches a feerate of 5 000 sat/kw
    assert(aliceCloseSig1.feeRange_opt.nonEmpty)
    alice2bob.forward(bob)
    // bob agrees with that proposal
    val bobCloseSig1 = bob2alice.expectMsgType[ClosingSigned]
    assert(bobCloseSig1.feeSatoshis === aliceCloseSig1.feeSatoshis)
    val mutualCloseTx = bob2blockchain.expectMsgType[PublishRawTx].tx
    assert(mutualCloseTx.txOut.length === 2) // NB: in the anchor outputs case, anchors are removed from the closing tx
    bob2alice.forward(alice)
    assert(alice2blockchain.expectMsgType[PublishRawTx].tx === mutualCloseTx)
    assert(alice.stateName == CLOSING)
    assert(bob.stateName == CLOSING)
  }

  test("recv ClosingSigned (theirCloseFee == ourCloseFee)") { f =>
    testClosingSignedSameFees(f)
  }

  test("recv ClosingSigned (theirCloseFee == ourCloseFee, bob starts closing)") { f =>
    testClosingSignedSameFees(f, bobInitiates = true)
  }

  test("recv ClosingSigned (theirCloseFee == ourCloseFee, anchor outputs)", Tag(StateTestsTags.AnchorOutputs)) { f =>
    testClosingSignedSameFees(f)
  }

  test("override on-chain fee estimator (funder)") { f =>
    import f._
    setFeerate(alice.feeEstimator, FeeratePerKw(10000 sat))
    setFeerate(bob.feeEstimator, FeeratePerKw(10000 sat))
    aliceClose(f, Some(ClosingFeerates(FeeratePerKw(2500 sat), FeeratePerKw(2000 sat), FeeratePerKw(3000 sat))))
    // alice initiates the negotiation with a very low feerate
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceCloseSig.feeSatoshis === 1685.sat)
    assert(aliceCloseSig.feeRange_opt === Some(FeeRange(1348 sat, 2022 sat)))
    alice2bob.forward(bob)
    // bob chooses alice's highest fee
    val bobCloseSig = bob2alice.expectMsgType[ClosingSigned]
    assert(bobCloseSig.feeSatoshis === 2022.sat)
    bob2alice.forward(alice)
    // alice accepts this proposition
    assert(alice2bob.expectMsgType[ClosingSigned].feeSatoshis === 2022.sat)
    alice2bob.forward(bob)
    val mutualCloseTx = alice2blockchain.expectMsgType[PublishRawTx].tx
    assert(bob2blockchain.expectMsgType[PublishRawTx].tx === mutualCloseTx)
    awaitCond(alice.stateName === CLOSING)
    awaitCond(bob.stateName === CLOSING)
  }

  test("override on-chain fee estimator (fundee)") { f =>
    import f._
    setFeerate(alice.feeEstimator, FeeratePerKw(10000 sat))
    setFeerate(bob.feeEstimator, FeeratePerKw(10000 sat))
    bobClose(f, Some(ClosingFeerates(FeeratePerKw(2500 sat), FeeratePerKw(2000 sat), FeeratePerKw(3000 sat))))
    // alice is funder, so bob's override will simply be ignored
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceCloseSig.feeSatoshis === 6740.sat) // matches a feerate of 10000 sat/kw
    alice2bob.forward(bob)
    // bob directly agrees because their fee estimator matches
    val bobCloseSig = bob2alice.expectMsgType[ClosingSigned]
    assert(aliceCloseSig.feeSatoshis === bobCloseSig.feeSatoshis)
    bob2alice.forward(alice)
    val mutualCloseTx = alice2blockchain.expectMsgType[PublishRawTx].tx
    assert(bob2blockchain.expectMsgType[PublishRawTx].tx === mutualCloseTx)
    awaitCond(alice.stateName === CLOSING)
    awaitCond(bob.stateName === CLOSING)
  }

  test("recv ClosingSigned (nothing at stake)", Tag(StateTestsTags.NoPushMsat)) { f =>
    import f._
    setFeerate(alice.feeEstimator, FeeratePerKw(5000 sat))
    setFeerate(bob.feeEstimator, FeeratePerKw(10000 sat))
    bobClose(f)
    val aliceCloseFee = alice2bob.expectMsgType[ClosingSigned].feeSatoshis
    alice2bob.forward(bob)
    val bobCloseFee = bob2alice.expectMsgType[ClosingSigned].feeSatoshis
    assert(aliceCloseFee === bobCloseFee)
    bob2blockchain.expectMsgType[PublishTx]
    awaitCond(bob.stateName === CLOSING)
  }

  private def makeLegacyClosingSigned(f: FixtureParam, closingFee: Satoshi): (ClosingSigned, ClosingSigned) = {
    import f._
    val aliceState = alice.stateData.asInstanceOf[DATA_NEGOTIATING]
    val aliceKeyManager = alice.underlyingActor.nodeParams.channelKeyManager
    val aliceScript = aliceState.localShutdown.scriptPubKey
    val bobState = bob.stateData.asInstanceOf[DATA_NEGOTIATING]
    val bobKeyManager = bob.underlyingActor.nodeParams.channelKeyManager
    val bobScript = bobState.localShutdown.scriptPubKey
    val (_, aliceClosingSigned) = Closing.makeClosingTx(aliceKeyManager, aliceState.commitments, aliceScript, bobScript, ClosingFees(closingFee, closingFee, closingFee))
    val (_, bobClosingSigned) = Closing.makeClosingTx(bobKeyManager, bobState.commitments, bobScript, aliceScript, ClosingFees(closingFee, closingFee, closingFee))
    (aliceClosingSigned.copy(tlvStream = TlvStream.empty), bobClosingSigned.copy(tlvStream = TlvStream.empty))
  }

  test("recv ClosingSigned (other side ignores our fee range, funder)") { f =>
    import f._
    setFeerate(alice.feeEstimator, FeeratePerKw(1000 sat))
    aliceClose(f)
    val aliceClosing1 = alice2bob.expectMsgType[ClosingSigned]
    val Some(FeeRange(_, maxFee)) = aliceClosing1.feeRange_opt
    assert(aliceClosing1.feeSatoshis === 674.sat)
    assert(maxFee === 1348.sat)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length === 1)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.isEmpty)
    // bob makes a proposal outside our fee range
    val (_, bobClosing1) = makeLegacyClosingSigned(f, 2500 sat)
    bob2alice.send(alice, bobClosing1)
    val aliceClosing2 = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceClosing1.feeSatoshis < aliceClosing2.feeSatoshis)
    assert(aliceClosing2.feeSatoshis < 1600.sat)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length === 2)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.nonEmpty)
    val (_, bobClosing2) = makeLegacyClosingSigned(f, 2000 sat)
    bob2alice.send(alice, bobClosing2)
    val aliceClosing3 = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceClosing2.feeSatoshis < aliceClosing3.feeSatoshis)
    assert(aliceClosing3.feeSatoshis < 1800.sat)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length === 3)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.nonEmpty)
    val (_, bobClosing3) = makeLegacyClosingSigned(f, 1800 sat)
    bob2alice.send(alice, bobClosing3)
    val aliceClosing4 = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceClosing3.feeSatoshis < aliceClosing4.feeSatoshis)
    assert(aliceClosing4.feeSatoshis < 1800.sat)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length === 4)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.nonEmpty)
    val (_, bobClosing4) = makeLegacyClosingSigned(f, aliceClosing4.feeSatoshis)
    bob2alice.send(alice, bobClosing4)
    awaitCond(alice.stateName === CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.length === 1)
    assert(alice2blockchain.expectMsgType[PublishRawTx].tx === alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.head.tx)
  }

  test("recv ClosingSigned (other side ignores our fee range, fundee)") { f =>
    import f._
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(10000 sat)))
    bobClose(f)
    // alice starts with a very low proposal
    val (aliceClosing1, _) = makeLegacyClosingSigned(f, 500 sat)
    alice2bob.send(bob, aliceClosing1)
    val bobClosing1 = bob2alice.expectMsgType[ClosingSigned]
    assert(3000.sat < bobClosing1.feeSatoshis)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length === 1)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.nonEmpty)
    val (aliceClosing2, _) = makeLegacyClosingSigned(f, 750 sat)
    alice2bob.send(bob, aliceClosing2)
    val bobClosing2 = bob2alice.expectMsgType[ClosingSigned]
    assert(bobClosing2.feeSatoshis < bobClosing1.feeSatoshis)
    assert(2000.sat < bobClosing2.feeSatoshis)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length === 2)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.nonEmpty)
    val (aliceClosing3, _) = makeLegacyClosingSigned(f, 1000 sat)
    alice2bob.send(bob, aliceClosing3)
    val bobClosing3 = bob2alice.expectMsgType[ClosingSigned]
    assert(bobClosing3.feeSatoshis < bobClosing2.feeSatoshis)
    assert(1500.sat < bobClosing3.feeSatoshis)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length === 3)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.nonEmpty)
    val (aliceClosing4, _) = makeLegacyClosingSigned(f, 1300 sat)
    alice2bob.send(bob, aliceClosing4)
    val bobClosing4 = bob2alice.expectMsgType[ClosingSigned]
    assert(bobClosing4.feeSatoshis < bobClosing3.feeSatoshis)
    assert(1300.sat < bobClosing4.feeSatoshis)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.length === 4)
    assert(bob.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt.nonEmpty)
    val (aliceClosing5, _) = makeLegacyClosingSigned(f, bobClosing4.feeSatoshis)
    alice2bob.send(bob, aliceClosing5)
    awaitCond(bob.stateName === CLOSING)
    assert(bob.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.length === 1)
    assert(bob2blockchain.expectMsgType[PublishRawTx].tx === bob.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.head.tx)
  }

  test("recv ClosingSigned (other side ignores our fee range, max iterations reached)") { f =>
    import f._
    alice.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(1000 sat)))
    aliceClose(f)
    for (_ <- 1 to Channel.MAX_NEGOTIATION_ITERATIONS) {
      val aliceClosing = alice2bob.expectMsgType[ClosingSigned]
      val Some(FeeRange(_, aliceMaxFee)) = aliceClosing.feeRange_opt
      val bobNextFee = (aliceClosing.feeSatoshis + 500.sat).max(aliceMaxFee + 1.sat)
      val (_, bobClosing) = makeLegacyClosingSigned(f, bobNextFee)
      bob2alice.send(alice, bobClosing)
    }
    awaitCond(alice.stateName === CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.length === 1)
    assert(alice2blockchain.expectMsgType[PublishRawTx].tx === alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.head.tx)
  }

  test("recv ClosingSigned (fee too low, fundee)") { f =>
    import f._
    setFeerate(alice.feeEstimator, FeeratePerKw(250 sat))
    setFeerate(bob.feeEstimator, FeeratePerKw(10000 sat), minFeerate = FeeratePerKw(750 sat))
    bobClose(f)
    val aliceClosing = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceClosing.feeRange_opt.get.max < 500.sat)
    alice2bob.send(bob, aliceClosing)
    // Bob refuses to sign with that fee range
    bob2alice.expectMsgType[Warning]
    bob2alice.expectNoMessage(100 millis)
  }

  test("recv ClosingSigned (fee too high)") { f =>
    import f._
    bobClose(f)
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    val tx = bob.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.publishableTxs.commitTx.tx
    alice2bob.forward(bob, aliceCloseSig.copy(feeSatoshis = 99000 sat)) // sig doesn't matter, it is checked later
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray).startsWith("invalid close fee: fee_satoshis=99000 sat"))
    assert(bob2blockchain.expectMsgType[PublishRawTx].tx === tx)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv ClosingSigned (invalid sig)") { f =>
    import f._
    aliceClose(f)
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    val tx = bob.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.publishableTxs.commitTx.tx
    bob ! aliceCloseSig.copy(signature = ByteVector64.Zeroes)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray).startsWith("invalid close signature"))
    assert(bob2blockchain.expectMsgType[PublishRawTx].tx === tx)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv WatchFundingSpentTriggered (counterparty's mutual close)") { f =>
    import f._
    aliceClose(f)
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    alice2bob.forward(bob, aliceCloseSig)
    // at this point alice and bob agree on closing fees, but alice has not yet received the final signature whereas bob has
    // bob publishes the mutual close and alice is notified that the funding tx has been spent
    assert(alice.stateName === NEGOTIATING)
    val mutualCloseTx = bob2blockchain.expectMsgType[PublishRawTx].tx
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId === mutualCloseTx.txid)
    alice ! WatchFundingSpentTriggered(mutualCloseTx)
    assert(alice2blockchain.expectMsgType[PublishRawTx].tx === mutualCloseTx)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === mutualCloseTx.txid)
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (an older mutual close)") { f =>
    import f._
    setFeerate(alice.feeEstimator, FeeratePerKw(1000 sat))
    setFeerate(bob.feeEstimator, FeeratePerKw(10000 sat))
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
    assert(alice2blockchain.expectMsgType[PublishRawTx].tx === firstMutualCloseTx.tx)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === firstMutualCloseTx.tx.txid)
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName === CLOSING)
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
    setFeerate(alice.feeEstimator, FeeratePerKw(250 sat))
    setFeerate(bob.feeEstimator, FeeratePerKw(10000 sat), minFeerate = FeeratePerKw(750 sat))
    bobClose(f)
    val aliceClosing1 = alice2bob.expectMsgType[ClosingSigned]
    alice2bob.send(bob, aliceClosing1)
    // Bob refuses to sign with that fee range
    bob2alice.expectMsgType[Warning]
    bob2alice.expectNoMessage(100 millis)
    // Alice offered a fee range that was too low: she notices the warning sent by Bob and retries with higher fees.
    val sender = TestProbe()
    alice ! CMD_CLOSE(sender.ref, None, Some(ClosingFeerates(FeeratePerKw(1000 sat), FeeratePerKw(500 sat), FeeratePerKw(2000 sat))))
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    val aliceClosing2 = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceClosing2.feeSatoshis > aliceClosing1.feeSatoshis)
    alice2bob.send(bob, aliceClosing2)
    val bobClosing = bob2alice.expectMsgType[ClosingSigned] // Bob accepts this new fee range
    bob2alice.forward(alice, bobClosing)
    val aliceClosing3 = alice2bob.expectMsgType[ClosingSigned]
    alice2bob.forward(bob, aliceClosing3)
    val mutualCloseTx = alice2blockchain.expectMsgType[PublishRawTx].tx
    assert(bob2blockchain.expectMsgType[PublishRawTx].tx === mutualCloseTx)
    awaitCond(alice.stateName === CLOSING)
    awaitCond(bob.stateName === CLOSING)
  }

  test("recv Error") { f =>
    import f._
    bobClose(f)
    alice2bob.expectMsgType[ClosingSigned]
    val tx = alice.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[PublishRawTx].tx === tx)
    alice2blockchain.expectMsgType[PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === tx.txid)
  }

}
