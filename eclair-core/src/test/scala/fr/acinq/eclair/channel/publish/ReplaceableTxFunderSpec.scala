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

import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, OutPoint, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel.publish.ReplaceableTxFunder.AdjustPreviousTxOutputResult.{AddWalletInputs, TxOutputAdjusted}
import fr.acinq.eclair.channel.publish.ReplaceableTxFunder._
import fr.acinq.eclair.channel.publish.ReplaceableTxPrePublisher._
import fr.acinq.eclair.channel.{CommitTxAndRemoteSig, FullCommitment, LocalCommit, LocalParams}
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
            assert(updatedClaimHtlc.txInfo.tx.txIn.length == 1)
            assert(updatedClaimHtlc.txInfo.tx.txOut.length == 1)
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

    val commitment = mock[FullCommitment]
    val localParams = mock[LocalParams]
    localParams.dustLimit.returns(1000 sat)
    commitment.localParams.returns(localParams)
    val localCommit = mock[LocalCommit]
    localCommit.commitTxAndRemoteSig.returns(CommitTxAndRemoteSig(commitTx, PlaceHolderSig))
    commitment.localCommit.returns(localCommit)

    // We can handle a small feerate update by lowering the change output.
    val TxOutputAdjusted(feerateUpdate1) = adjustPreviousTxOutput(FundedTx(previousAnchorTx, 12000 sat, FeeratePerKw(2500 sat)), FeeratePerKw(5000 sat), commitment)
    assert(feerateUpdate1.txInfo.tx.txIn == previousAnchorTx.txInfo.tx.txIn)
    assert(feerateUpdate1.txInfo.tx.txOut.length == 1)
    val TxOutputAdjusted(feerateUpdate2) = adjustPreviousTxOutput(FundedTx(previousAnchorTx, 12000 sat, FeeratePerKw(2500 sat)), FeeratePerKw(6000 sat), commitment)
    assert(feerateUpdate2.txInfo.tx.txIn == previousAnchorTx.txInfo.tx.txIn)
    assert(feerateUpdate2.txInfo.tx.txOut.length == 1)
    assert(feerateUpdate2.txInfo.tx.txOut.head.amount < feerateUpdate1.txInfo.tx.txOut.head.amount)

    // But if the feerate increase is too large, we must add new wallet inputs.
    val AddWalletInputs(previousTx) = adjustPreviousTxOutput(FundedTx(previousAnchorTx, 12000 sat, FeeratePerKw(2500 sat)), FeeratePerKw(10000 sat), commitment)
    assert(previousTx == previousAnchorTx)
  }

  test("adjust previous htlc transaction outputs", Tag("fuzzy")) {
    val commitment = mock[FullCommitment]
    val localParams = mock[LocalParams]
    localParams.dustLimit.returns(600 sat)
    commitment.localParams.returns(localParams)
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
      val TxOutputAdjusted(feerateUpdate1) = adjustPreviousTxOutput(FundedTx(previousTx, 15000 sat, FeeratePerKw(2500 sat)), FeeratePerKw(5000 sat), commitment)
      assert(feerateUpdate1.txInfo.tx.txIn == previousTx.txInfo.tx.txIn)
      assert(feerateUpdate1.txInfo.tx.txOut.length == 2)
      assert(feerateUpdate1.txInfo.tx.txOut.head == previousTx.txInfo.tx.txOut.head)
      val TxOutputAdjusted(feerateUpdate2) = adjustPreviousTxOutput(FundedTx(previousTx, 15000 sat, FeeratePerKw(2500 sat)), FeeratePerKw(6000 sat), commitment)
      assert(feerateUpdate2.txInfo.tx.txIn == previousTx.txInfo.tx.txIn)
      assert(feerateUpdate2.txInfo.tx.txOut.length == 2)
      assert(feerateUpdate2.txInfo.tx.txOut.head == previousTx.txInfo.tx.txOut.head)
      assert(feerateUpdate2.txInfo.tx.txOut.last.amount < feerateUpdate1.txInfo.tx.txOut.last.amount)

      // If the previous funding attempt didn't add a change output, we must add new wallet inputs.
      val previousTxNoChange = previousTx.updateTx(previousTx.txInfo.tx.copy(txOut = Seq(previousTx.txInfo.tx.txOut.head)))
      val AddWalletInputs(tx) = adjustPreviousTxOutput(FundedTx(previousTxNoChange, 25000 sat, FeeratePerKw(2500 sat)), FeeratePerKw(5000 sat), commitment)
      assert(tx == previousTxNoChange)

      for (_ <- 1 to 100) {
        val amountIn = Random.nextInt(25_000_000).sat
        val changeAmount = Random.nextInt(amountIn.toLong.toInt).sat
        val fuzzyPreviousTx = previousTx.updateTx(previousTx.txInfo.tx.copy(txOut = Seq(
          initialHtlcTx.txInfo.tx.txOut.head,
          TxOut(changeAmount, Script.pay2wpkh(PlaceHolderPubKey))
        )))
        val targetFeerate = FeeratePerKw(2500 sat) + FeeratePerKw(Random.nextInt(20000).sat)
        adjustPreviousTxOutput(FundedTx(fuzzyPreviousTx, amountIn, FeeratePerKw(2500 sat)), targetFeerate, commitment) match {
          case AdjustPreviousTxOutputResult.Skip(_) => // nothing do check
          case AddWalletInputs(tx) => assert(tx == fuzzyPreviousTx)
          case TxOutputAdjusted(updatedTx) =>
            assert(updatedTx.txInfo.tx.txIn == fuzzyPreviousTx.txInfo.tx.txIn)
            assert(Set(1, 2).contains(updatedTx.txInfo.tx.txOut.length))
            assert(updatedTx.txInfo.tx.txOut.head == fuzzyPreviousTx.txInfo.tx.txOut.head)
            assert(updatedTx.txInfo.tx.txOut.last.amount >= 600.sat)
        }
      }
    }
  }

  test("adjust previous claim htlc transaction outputs") {
    val commitment = mock[FullCommitment]
    val localParams = mock[LocalParams]
    localParams.dustLimit.returns(500 sat)
    commitment.localParams.returns(localParams)
    val (claimHtlcSuccess, claimHtlcTimeout) = createClaimHtlcTx()
    for (claimHtlc <- Seq(claimHtlcSuccess, claimHtlcTimeout)) {
      var previousAmount = claimHtlc.txInfo.tx.txOut.head.amount
      for (i <- 1 to 100) {
        val targetFeerate = FeeratePerKw(250 * i sat)
        adjustPreviousTxOutput(FundedTx(claimHtlc, claimHtlc.txInfo.amountIn, FeeratePerKw(2500 sat)), targetFeerate, commitment) match {
          case AdjustPreviousTxOutputResult.Skip(_) => assert(targetFeerate >= FeeratePerKw(10000 sat))
          case AddWalletInputs(_) => fail("shouldn't add wallet inputs to claim-htlc-tx")
          case TxOutputAdjusted(updatedTx) =>
            assert(updatedTx.txInfo.tx.txIn == claimHtlc.txInfo.tx.txIn)
            assert(updatedTx.txInfo.tx.txOut.length == 1)
            assert(updatedTx.txInfo.tx.txOut.head.amount < previousAmount)
            previousAmount = updatedTx.txInfo.tx.txOut.head.amount
        }
      }
    }
  }

}
