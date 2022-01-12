/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.channel.publish

import fr.acinq.bitcoin.{ByteVector32, Crypto, OutPoint, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel.publish.ReplaceableTxFunder.AdjustPreviousTxOutputResult.{AddWalletInputs, TxOutputAdjusted}
import fr.acinq.eclair.channel.publish.ReplaceableTxFunder._
import fr.acinq.eclair.channel.publish.ReplaceableTxPrePublisher._
import fr.acinq.eclair.channel.{CommitTxAndRemoteSig, Commitments, LocalCommit, LocalParams}
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.{BlockHeight, CltvExpiry, TestKitBaseClass, randomBytes32}
import org.mockito.IdiomaticMockito.StubbingOps
import org.mockito.MockitoSugar.mock
import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.ByteVector

import scala.util.Random

class ReplaceableTxFunderSpec extends TestKitBaseClass with AnyFunSuiteLike {

  private def createAnchorTx(): (CommitTx, ClaimLocalAnchorOutputTx) = {
    val anchorScript = Scripts.anchor(PlaceHolderPubKey)
    val commitInput = Funding.makeFundingInputInfo(randomBytes32(), 1, 500 sat, PlaceHolderPubKey, PlaceHolderPubKey)
    val commitTx = Transaction(
      2,
      Seq(TxIn(commitInput.outPoint, commitInput.redeemScript, 0, Scripts.witness2of2(PlaceHolderSig, PlaceHolderSig, PlaceHolderPubKey, PlaceHolderPubKey))),
      Seq(TxOut(330 sat, Script.pay2wsh(anchorScript))),
      0
    )
    val anchorTx = ClaimLocalAnchorOutputTx(
      InputInfo(OutPoint(commitTx, 0), commitTx.txOut.head, anchorScript),
      Transaction(2, Seq(TxIn(OutPoint(commitTx, 0), ByteVector.empty, 0)), Nil, 0),
      BlockHeight(0)
    )
    (CommitTx(commitInput, commitTx), anchorTx)
  }

  test("adjust anchor tx change amount", Tag("fuzzy")) {
    val (commitTx, anchorTx) = createAnchorTx()
    val dustLimit = 600 sat
    val commitFeerate = FeeratePerKw(2500 sat)
    val targetFeerate = FeeratePerKw(10000 sat)
    for (_ <- 1 to 100) {
      val walletInputsCount = 1 + Random.nextInt(5)
      val walletInputs = (1 to walletInputsCount).map(_ => TxIn(OutPoint(randomBytes32(), 0), Nil, 0))
      val amountIn = dustLimit * walletInputsCount + Random.nextInt(25_000_000).sat
      val amountOut = dustLimit + Random.nextLong(amountIn.toLong).sat
      val unsignedTx = ClaimLocalAnchorWithWitnessData(anchorTx.copy(tx = anchorTx.tx.copy(
        txIn = anchorTx.tx.txIn ++ walletInputs,
        txOut = TxOut(amountOut, Script.pay2wpkh(PlaceHolderPubKey)) :: Nil,
      )))
      val adjustedTx = adjustAnchorOutputChange(unsignedTx, commitTx.tx, amountIn, commitFeerate, targetFeerate, dustLimit)
      assert(adjustedTx.txInfo.tx.txIn.size === unsignedTx.txInfo.tx.txIn.size)
      assert(adjustedTx.txInfo.tx.txOut.size === 1)
      assert(adjustedTx.txInfo.tx.txOut.head.amount >= dustLimit)
      if (adjustedTx.txInfo.tx.txOut.head.amount > dustLimit) {
        // Simulate tx signing to check final feerate.
        val signedTx = {
          val anchorSigned = addSigs(adjustedTx.txInfo, PlaceHolderSig)
          val signedWalletInputs = anchorSigned.tx.txIn.tail.map(txIn => txIn.copy(witness = Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig)))
          anchorSigned.tx.copy(txIn = anchorSigned.tx.txIn.head +: signedWalletInputs)
        }
        // We want the package anchor tx + commit tx to reach our target feerate, but the commit tx already pays a (smaller) fee
        val targetFee = weight2fee(targetFeerate, signedTx.weight() + commitTx.tx.weight()) - weight2fee(commitFeerate, commitTx.tx.weight())
        val actualFee = amountIn - signedTx.txOut.map(_.amount).sum
        assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee amountIn=$amountIn tx=$signedTx")
      }
    }
  }

  private def createHtlcTxs(): (HtlcSuccessWithWitnessData, HtlcTimeoutWithWitnessData) = {
    val preimage = randomBytes32()
    val paymentHash = Crypto.sha256(preimage)
    val htlcSuccessScript = Scripts.htlcReceived(PlaceHolderPubKey, PlaceHolderPubKey, PlaceHolderPubKey, paymentHash, CltvExpiry(0), ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    val htlcTimeoutScript = Scripts.htlcOffered(PlaceHolderPubKey, PlaceHolderPubKey, PlaceHolderPubKey, randomBytes32(), ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    val commitTx = Transaction(
      2,
      Seq(TxIn(OutPoint(randomBytes32(), 1), Script.write(Script.pay2wpkh(PlaceHolderPubKey)), 0, Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig))),
      Seq(TxOut(5000 sat, Script.pay2wsh(htlcSuccessScript)), TxOut(4000 sat, Script.pay2wsh(htlcTimeoutScript))),
      0
    )
    val htlcSuccess = HtlcSuccessWithWitnessData(HtlcSuccessTx(
      InputInfo(OutPoint(commitTx, 0), commitTx.txOut.head, htlcSuccessScript),
      Transaction(2, Seq(TxIn(OutPoint(commitTx, 0), ByteVector.empty, 0)), Seq(TxOut(5000 sat, Script.pay2wpkh(PlaceHolderPubKey))), 0),
      paymentHash,
      17,
      BlockHeight(0)
    ), PlaceHolderSig, preimage)
    val htlcTimeout = HtlcTimeoutWithWitnessData(HtlcTimeoutTx(
      InputInfo(OutPoint(commitTx, 1), commitTx.txOut.last, htlcTimeoutScript),
      Transaction(2, Seq(TxIn(OutPoint(commitTx, 1), ByteVector.empty, 0)), Seq(TxOut(4000 sat, Script.pay2wpkh(PlaceHolderPubKey))), 0),
      12,
      BlockHeight(0)
    ), PlaceHolderSig)
    (htlcSuccess, htlcTimeout)
  }

  test("adjust htlc tx change amount", Tag("fuzzy")) {
    val dustLimit = 600 sat
    val targetFeerate = FeeratePerKw(10000 sat)
    val (htlcSuccess, htlcTimeout) = createHtlcTxs()
    for (_ <- 1 to 100) {
      val walletInputsCount = 1 + Random.nextInt(5)
      val walletInputs = (1 to walletInputsCount).map(_ => TxIn(OutPoint(randomBytes32(), 0), Nil, 0))
      val walletAmountIn = dustLimit * walletInputsCount + Random.nextInt(25_000_000).sat
      val changeOutput = TxOut(Random.nextLong(walletAmountIn.toLong).sat, Script.pay2wpkh(PlaceHolderPubKey))
      val unsignedHtlcSuccessTx = htlcSuccess.updateTx(htlcSuccess.txInfo.tx.copy(
        txIn = htlcSuccess.txInfo.tx.txIn ++ walletInputs,
        txOut = htlcSuccess.txInfo.tx.txOut ++ Seq(changeOutput)
      ))
      val unsignedHtlcTimeoutTx = htlcTimeout.updateTx(htlcTimeout.txInfo.tx.copy(
        txIn = htlcTimeout.txInfo.tx.txIn ++ walletInputs,
        txOut = htlcTimeout.txInfo.tx.txOut ++ Seq(changeOutput)
      ))
      for (unsignedTx <- Seq(unsignedHtlcSuccessTx, unsignedHtlcTimeoutTx)) {
        val totalAmountIn = unsignedTx.txInfo.input.txOut.amount + walletAmountIn
        val adjustedTx = adjustHtlcTxChange(unsignedTx, totalAmountIn, targetFeerate, dustLimit, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
        assert(adjustedTx.txInfo.tx.txIn.size === unsignedTx.txInfo.tx.txIn.size)
        assert(adjustedTx.txInfo.tx.txOut.size === 1 || adjustedTx.txInfo.tx.txOut.size === 2)
        if (adjustedTx.txInfo.tx.txOut.size == 2) {
          // Simulate tx signing to check final feerate.
          val signedTx = {
            val htlcSigned = adjustedTx.txInfo match {
              case tx: HtlcSuccessTx => addSigs(tx, PlaceHolderSig, PlaceHolderSig, ByteVector32.Zeroes, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
              case tx: HtlcTimeoutTx => addSigs(tx, PlaceHolderSig, PlaceHolderSig, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
            }
            val signedWalletInputs = htlcSigned.tx.txIn.tail.map(txIn => txIn.copy(witness = Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig)))
            htlcSigned.tx.copy(txIn = htlcSigned.tx.txIn.head +: signedWalletInputs)
          }
          val targetFee = weight2fee(targetFeerate, signedTx.weight())
          val actualFee = totalAmountIn - signedTx.txOut.map(_.amount).sum
          assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee amountIn=$walletAmountIn tx=$signedTx")
        }
      }
    }
  }

  private def createClaimHtlcTx(): (ClaimHtlcSuccessWithWitnessData, ClaimHtlcTimeoutWithWitnessData) = {
    val preimage = randomBytes32()
    val paymentHash = Crypto.sha256(preimage)
    val htlcSuccessScript = Scripts.htlcReceived(PlaceHolderPubKey, PlaceHolderPubKey, PlaceHolderPubKey, paymentHash, CltvExpiry(0), ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    val htlcTimeoutScript = Scripts.htlcOffered(PlaceHolderPubKey, PlaceHolderPubKey, PlaceHolderPubKey, randomBytes32(), ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    val claimHtlcSuccess = ClaimHtlcSuccessWithWitnessData(ClaimHtlcSuccessTx(
      InputInfo(OutPoint(ByteVector32.Zeroes, 3), TxOut(5000 sat, Script.pay2wsh(htlcSuccessScript)), htlcSuccessScript),
      Transaction(2, Seq(TxIn(OutPoint(ByteVector32.Zeroes, 3), ByteVector.empty, 0)), Seq(TxOut(5000 sat, Script.pay2wpkh(PlaceHolderPubKey))), 0),
      paymentHash,
      5,
      BlockHeight(0)
    ), preimage)
    val claimHtlcTimeout = ClaimHtlcTimeoutWithWitnessData(ClaimHtlcTimeoutTx(
      InputInfo(OutPoint(ByteVector32.Zeroes, 7), TxOut(5000 sat, Script.pay2wsh(htlcTimeoutScript)), htlcTimeoutScript),
      Transaction(2, Seq(TxIn(OutPoint(ByteVector32.Zeroes, 7), ByteVector.empty, 0)), Seq(TxOut(5000 sat, Script.pay2wpkh(PlaceHolderPubKey))), 0),
      7,
      BlockHeight(0)
    ))
    (claimHtlcSuccess, claimHtlcTimeout)
  }

  test("adjust claim htlc tx change amount") {
    val dustLimit = 750 sat
    val (claimHtlcSuccess, claimHtlcTimeout) = createClaimHtlcTx()
    for (claimHtlc <- Seq(claimHtlcSuccess, claimHtlcTimeout)) {
      var previousAmount = claimHtlc.txInfo.tx.txOut.head.amount
      for (i <- 1 to 100) {
        val targetFeerate = FeeratePerKw(250 * i sat)
        adjustClaimHtlcTxOutput(claimHtlc, targetFeerate, dustLimit) match {
          case Left(_) => assert(targetFeerate >= FeeratePerKw(7000 sat))
          case Right(updatedClaimHtlc) =>
            assert(updatedClaimHtlc.txInfo.tx.txIn.length === 1)
            assert(updatedClaimHtlc.txInfo.tx.txOut.length === 1)
            assert(updatedClaimHtlc.txInfo.tx.txOut.head.amount < previousAmount)
            previousAmount = updatedClaimHtlc.txInfo.tx.txOut.head.amount
            val signedTx = updatedClaimHtlc match {
              case ClaimHtlcSuccessWithWitnessData(txInfo, preimage) => addSigs(txInfo, PlaceHolderSig, preimage)
              case ClaimHtlcTimeoutWithWitnessData(txInfo) => addSigs(txInfo, PlaceHolderSig)
              case _: LegacyClaimHtlcSuccessWithWitnessData => fail("legacy claim htlc success not supported")
            }
            val txFeerate = fee2rate(signedTx.fee, signedTx.tx.weight())
            assert(targetFeerate * 0.9 <= txFeerate && txFeerate <= targetFeerate * 1.1, s"actualFeerate=$txFeerate targetFeerate=$targetFeerate")
        }
      }
    }
  }

  test("adjust previous anchor transaction outputs") {
    val (commitTx, initialAnchorTx) = createAnchorTx()
    val previousAnchorTx = ClaimLocalAnchorWithWitnessData(initialAnchorTx).updateTx(initialAnchorTx.tx.copy(
      txIn = Seq(
        initialAnchorTx.tx.txIn.head,
        // The previous funding attempt added two wallet inputs:
        TxIn(OutPoint(randomBytes32(), 3), ByteVector.empty, 0, Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig)),
        TxIn(OutPoint(randomBytes32(), 1), ByteVector.empty, 0, Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig))
      ),
      // And a change output:
      txOut = Seq(TxOut(5000 sat, Script.pay2wpkh(PlaceHolderPubKey)))
    ))

    val commitments = mock[Commitments]
    val localParams = mock[LocalParams]
    localParams.dustLimit.returns(1000 sat)
    commitments.localParams.returns(localParams)
    val localCommit = mock[LocalCommit]
    localCommit.commitTxAndRemoteSig.returns(CommitTxAndRemoteSig(commitTx, PlaceHolderSig))
    commitments.localCommit.returns(localCommit)

    // We can handle a small feerate update by lowering the change output.
    val TxOutputAdjusted(feerateUpdate1) = adjustPreviousTxOutput(FundedTx(previousAnchorTx, 12000 sat, FeeratePerKw(2500 sat)), FeeratePerKw(5000 sat), commitments)
    assert(feerateUpdate1.txInfo.tx.txIn === previousAnchorTx.txInfo.tx.txIn)
    assert(feerateUpdate1.txInfo.tx.txOut.length === 1)
    val TxOutputAdjusted(feerateUpdate2) = adjustPreviousTxOutput(FundedTx(previousAnchorTx, 12000 sat, FeeratePerKw(2500 sat)), FeeratePerKw(6000 sat), commitments)
    assert(feerateUpdate2.txInfo.tx.txIn === previousAnchorTx.txInfo.tx.txIn)
    assert(feerateUpdate2.txInfo.tx.txOut.length === 1)
    assert(feerateUpdate2.txInfo.tx.txOut.head.amount < feerateUpdate1.txInfo.tx.txOut.head.amount)

    // But if the feerate increase is too large, we must add new wallet inputs.
    val AddWalletInputs(previousTx) = adjustPreviousTxOutput(FundedTx(previousAnchorTx, 12000 sat, FeeratePerKw(2500 sat)), FeeratePerKw(10000 sat), commitments)
    assert(previousTx === previousAnchorTx)
  }

  test("adjust previous htlc transaction outputs", Tag("fuzzy")) {
    val commitments = mock[Commitments]
    val localParams = mock[LocalParams]
    localParams.dustLimit.returns(600 sat)
    commitments.localParams.returns(localParams)
    val (initialHtlcSuccess, initialHtlcTimeout) = createHtlcTxs()
    for (initialHtlcTx <- Seq(initialHtlcSuccess, initialHtlcTimeout)) {
      val previousTx = initialHtlcTx.updateTx(initialHtlcTx.txInfo.tx.copy(
        txIn = Seq(
          initialHtlcTx.txInfo.tx.txIn.head,
          // The previous funding attempt added three wallet inputs:
          TxIn(OutPoint(randomBytes32(), 3), ByteVector.empty, 0, Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig)),
          TxIn(OutPoint(randomBytes32(), 1), ByteVector.empty, 0, Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig)),
          TxIn(OutPoint(randomBytes32(), 5), ByteVector.empty, 0, Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig))
        ),
        txOut = Seq(
          initialHtlcTx.txInfo.tx.txOut.head,
          // And one change output:
          TxOut(5000 sat, Script.pay2wpkh(PlaceHolderPubKey))
        )
      ))

      // We can handle a small feerate update by lowering the change output.
      val TxOutputAdjusted(feerateUpdate1) = adjustPreviousTxOutput(FundedTx(previousTx, 15000 sat, FeeratePerKw(2500 sat)), FeeratePerKw(5000 sat), commitments)
      assert(feerateUpdate1.txInfo.tx.txIn === previousTx.txInfo.tx.txIn)
      assert(feerateUpdate1.txInfo.tx.txOut.length === 2)
      assert(feerateUpdate1.txInfo.tx.txOut.head === previousTx.txInfo.tx.txOut.head)
      val TxOutputAdjusted(feerateUpdate2) = adjustPreviousTxOutput(FundedTx(previousTx, 15000 sat, FeeratePerKw(2500 sat)), FeeratePerKw(6000 sat), commitments)
      assert(feerateUpdate2.txInfo.tx.txIn === previousTx.txInfo.tx.txIn)
      assert(feerateUpdate2.txInfo.tx.txOut.length === 2)
      assert(feerateUpdate2.txInfo.tx.txOut.head === previousTx.txInfo.tx.txOut.head)
      assert(feerateUpdate2.txInfo.tx.txOut.last.amount < feerateUpdate1.txInfo.tx.txOut.last.amount)

      // If the previous funding attempt didn't add a change output, we must add new wallet inputs.
      val previousTxNoChange = previousTx.updateTx(previousTx.txInfo.tx.copy(txOut = Seq(previousTx.txInfo.tx.txOut.head)))
      val AddWalletInputs(tx) = adjustPreviousTxOutput(FundedTx(previousTxNoChange, 25000 sat, FeeratePerKw(2500 sat)), FeeratePerKw(5000 sat), commitments)
      assert(tx === previousTxNoChange)

      for (_ <- 1 to 100) {
        val amountIn = Random.nextInt(25_000_000).sat
        val changeAmount = Random.nextInt(amountIn.toLong.toInt).sat
        val fuzzyPreviousTx = previousTx.updateTx(previousTx.txInfo.tx.copy(txOut = Seq(
          initialHtlcTx.txInfo.tx.txOut.head,
          TxOut(changeAmount, Script.pay2wpkh(PlaceHolderPubKey))
        )))
        val targetFeerate = FeeratePerKw(2500 sat) + FeeratePerKw(Random.nextInt(20000).sat)
        adjustPreviousTxOutput(FundedTx(fuzzyPreviousTx, amountIn, FeeratePerKw(2500 sat)), targetFeerate, commitments) match {
          case AdjustPreviousTxOutputResult.Skip(_) => // nothing do check
          case AddWalletInputs(tx) => assert(tx === fuzzyPreviousTx)
          case TxOutputAdjusted(updatedTx) =>
            assert(updatedTx.txInfo.tx.txIn === fuzzyPreviousTx.txInfo.tx.txIn)
            assert(Set(1, 2).contains(updatedTx.txInfo.tx.txOut.length))
            assert(updatedTx.txInfo.tx.txOut.head === fuzzyPreviousTx.txInfo.tx.txOut.head)
            assert(updatedTx.txInfo.tx.txOut.last.amount >= 600.sat)
        }
      }
    }
  }

  test("adjust previous claim htlc transaction outputs") {
    val commitments = mock[Commitments]
    val localParams = mock[LocalParams]
    localParams.dustLimit.returns(500 sat)
    commitments.localParams.returns(localParams)
    val (claimHtlcSuccess, claimHtlcTimeout) = createClaimHtlcTx()
    for (claimHtlc <- Seq(claimHtlcSuccess, claimHtlcTimeout)) {
      var previousAmount = claimHtlc.txInfo.tx.txOut.head.amount
      for (i <- 1 to 100) {
        val targetFeerate = FeeratePerKw(250 * i sat)
        adjustPreviousTxOutput(FundedTx(claimHtlc, claimHtlc.txInfo.amountIn, FeeratePerKw(2500 sat)), targetFeerate, commitments) match {
          case AdjustPreviousTxOutputResult.Skip(_) => assert(targetFeerate >= FeeratePerKw(10000 sat))
          case AddWalletInputs(_) => fail("shouldn't add wallet inputs to claim-htlc-tx")
          case TxOutputAdjusted(updatedTx) =>
            assert(updatedTx.txInfo.tx.txIn === claimHtlc.txInfo.tx.txIn)
            assert(updatedTx.txInfo.tx.txOut.length === 1)
            assert(updatedTx.txInfo.tx.txOut.head.amount < previousAmount)
            previousAmount = updatedTx.txInfo.tx.txOut.head.amount
        }
      }
    }
  }

}
