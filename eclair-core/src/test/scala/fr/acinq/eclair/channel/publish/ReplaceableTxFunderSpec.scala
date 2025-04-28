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

import fr.acinq.bitcoin.scalacompat.{Crypto, OutPoint, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.blockchain.fee.{ConfirmationTarget, FeeratePerKw}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.publish.ReplaceableTxFunder.AdjustPreviousTxOutputResult.{AddWalletInputs, TxOutputAdjusted}
import fr.acinq.eclair.channel.publish.ReplaceableTxFunder._
import fr.acinq.eclair.channel.publish.ReplaceableTxPrePublisher._
import fr.acinq.eclair.crypto.keymanager.CommitmentPublicKeys
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

  private def createAnchorTx(): (CommitTx, ClaimAnchorOutputTx) = {
    val anchorScript = Scripts.anchor(PlaceHolderPubKey)
    val commitInput = Funding.makeFundingInputInfo(randomTxId(), 1, 500 sat, PlaceHolderPubKey, PlaceHolderPubKey)
    val commitTx = Transaction(
      2,
      Seq(TxIn(commitInput.outPoint, commitInput.redeemScript, 0, Scripts.witness2of2(PlaceHolderSig, PlaceHolderSig, PlaceHolderPubKey, PlaceHolderPubKey))),
      Seq(TxOut(330 sat, Script.pay2wsh(anchorScript))),
      0
    )
    val anchorTx = ClaimAnchorOutputTx(
      InputInfo(OutPoint(commitTx, 0), commitTx.txOut.head, anchorScript),
      Transaction(2, Seq(TxIn(OutPoint(commitTx, 0), ByteVector.empty, 0)), Nil, 0),
      ConfirmationTarget.Absolute(BlockHeight(0))
    )
    (CommitTx(commitInput, commitTx), anchorTx)
  }

  private def createHtlcTxs(): (Transaction, HtlcSuccessWithWitnessData, HtlcTimeoutWithWitnessData) = {
    val preimage = randomBytes32()
    val paymentHash = Crypto.sha256(preimage)
    val expiry = CltvExpiry(850_000)
    val commitmentKeys = CommitmentPublicKeys(PlaceHolderPubKey, PlaceHolderPubKey, PlaceHolderPubKey, PlaceHolderPubKey, PlaceHolderPubKey)
    val htlcSuccessScript = Scripts.htlcReceived(commitmentKeys, paymentHash, expiry, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    val htlcTimeoutScript = Scripts.htlcOffered(commitmentKeys, randomBytes32(), ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    val commitTx = Transaction(
      2,
      Seq(TxIn(OutPoint(randomTxId(), 1), Script.write(Script.pay2wpkh(PlaceHolderPubKey)), 0, Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig))),
      Seq(TxOut(5000 sat, Script.pay2wsh(htlcSuccessScript)), TxOut(4000 sat, Script.pay2wsh(htlcTimeoutScript))),
      0
    )
    val htlcSuccess = HtlcSuccessWithWitnessData(HtlcSuccessTx(
      InputInfo(OutPoint(commitTx, 0), commitTx.txOut.head, htlcSuccessScript),
      Transaction(2, Seq(TxIn(OutPoint(commitTx, 0), ByteVector.empty, 0)), Seq(TxOut(5000 sat, Script.pay2wpkh(PlaceHolderPubKey))), 0),
      paymentHash,
      17,
      expiry,
    ), PlaceHolderSig, preimage)
    val htlcTimeout = HtlcTimeoutWithWitnessData(HtlcTimeoutTx(
      InputInfo(OutPoint(commitTx, 1), commitTx.txOut.last, htlcTimeoutScript),
      Transaction(2, Seq(TxIn(OutPoint(commitTx, 1), ByteVector.empty, 0)), Seq(TxOut(4000 sat, Script.pay2wpkh(PlaceHolderPubKey))), 0),
      paymentHash,
      12,
      expiry
    ), PlaceHolderSig)
    (commitTx, htlcSuccess, htlcTimeout)
  }

  private def createClaimHtlcTx(): (Transaction, ClaimHtlcSuccessWithWitnessData, ClaimHtlcTimeoutWithWitnessData) = {
    val preimage = randomBytes32()
    val paymentHash = Crypto.sha256(preimage)
    val expiry = CltvExpiry(850_000)
    val commitmentKeys = CommitmentPublicKeys(PlaceHolderPubKey, PlaceHolderPubKey, PlaceHolderPubKey, PlaceHolderPubKey, PlaceHolderPubKey)
    val htlcSuccessScript = Scripts.htlcReceived(commitmentKeys, paymentHash, expiry, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    val htlcTimeoutScript = Scripts.htlcOffered(commitmentKeys, randomBytes32(), ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    val commitTx = Transaction(
      2,
      Seq(TxIn(OutPoint(randomTxId(), 1), Script.write(Script.pay2wpkh(PlaceHolderPubKey)), 0, Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig))),
      Seq(TxOut(5000 sat, Script.pay2wsh(htlcSuccessScript)), TxOut(5000 sat, Script.pay2wsh(htlcTimeoutScript))),
      0
    )
    val claimHtlcSuccess = ClaimHtlcSuccessWithWitnessData(ClaimHtlcSuccessTx(
      InputInfo(OutPoint(commitTx, 0), commitTx.txOut.head, htlcSuccessScript),
      Transaction(2, Seq(TxIn(OutPoint(commitTx, 0), ByteVector.empty, 0)), Seq(TxOut(5000 sat, Script.pay2wpkh(PlaceHolderPubKey))), 0),
      paymentHash,
      5,
      expiry
    ), preimage)
    val claimHtlcTimeout = ClaimHtlcTimeoutWithWitnessData(ClaimHtlcTimeoutTx(
      InputInfo(OutPoint(commitTx, 1), commitTx.txOut.last, htlcTimeoutScript),
      Transaction(2, Seq(TxIn(OutPoint(commitTx, 1), ByteVector.empty, 0)), Seq(TxOut(5000 sat, Script.pay2wpkh(PlaceHolderPubKey))), 0),
      paymentHash,
      7,
      expiry,
    ))
    (commitTx, claimHtlcSuccess, claimHtlcTimeout)
  }

  test("adjust claim htlc tx change amount") {
    val dustLimit = 750 sat
    val (_, claimHtlcSuccess, claimHtlcTimeout) = createClaimHtlcTx()
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
              case ClaimHtlcSuccessWithWitnessData(txInfo, preimage) => txInfo.addSigs(PlaceHolderSig, preimage)
              case ClaimHtlcTimeoutWithWitnessData(txInfo) => txInfo.addSigs(PlaceHolderSig)
            }
            val txFeerate = fee2rate(signedTx.fee, signedTx.tx.weight())
            assert(targetFeerate * 0.9 <= txFeerate && txFeerate <= targetFeerate * 1.1, s"actualFeerate=$txFeerate targetFeerate=$targetFeerate")
        }
      }
    }
  }

  test("adjust previous anchor transaction outputs") {
    val (commitTx, initialAnchorTx) = createAnchorTx()
    val previousAnchorTx = ClaimAnchorWithWitnessData(initialAnchorTx).updateTx(initialAnchorTx.tx.copy(
      txIn = Seq(
        initialAnchorTx.tx.txIn.head,
        // The previous funding attempt added two wallet inputs:
        TxIn(OutPoint(randomTxId(), 3), ByteVector.empty, 0, Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig)),
        TxIn(OutPoint(randomTxId(), 1), ByteVector.empty, 0, Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig))
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
    val TxOutputAdjusted(feerateUpdate1) = adjustPreviousTxOutput(FundedTx(previousAnchorTx, 12000 sat, FeeratePerKw(2500 sat), Map.empty), FeeratePerKw(5000 sat), commitment, commitTx.tx)
    assert(feerateUpdate1.txInfo.tx.txIn == previousAnchorTx.txInfo.tx.txIn)
    assert(feerateUpdate1.txInfo.tx.txOut.length == 1)
    val TxOutputAdjusted(feerateUpdate2) = adjustPreviousTxOutput(FundedTx(previousAnchorTx, 12000 sat, FeeratePerKw(2500 sat), Map.empty), FeeratePerKw(6000 sat), commitment, commitTx.tx)
    assert(feerateUpdate2.txInfo.tx.txIn == previousAnchorTx.txInfo.tx.txIn)
    assert(feerateUpdate2.txInfo.tx.txOut.length == 1)
    assert(feerateUpdate2.txInfo.tx.txOut.head.amount < feerateUpdate1.txInfo.tx.txOut.head.amount)

    // But if the feerate increase is too large, we must add new wallet inputs.
    val AddWalletInputs(previousTx) = adjustPreviousTxOutput(FundedTx(previousAnchorTx, 12000 sat, FeeratePerKw(2500 sat), Map.empty), FeeratePerKw(10000 sat), commitment, commitTx.tx)
    assert(previousTx == previousAnchorTx)
  }

  test("adjust previous htlc transaction outputs", Tag("fuzzy")) {
    val commitment = mock[FullCommitment]
    val localParams = mock[LocalParams]
    localParams.dustLimit.returns(600 sat)
    commitment.localParams.returns(localParams)
    val (commitTx, initialHtlcSuccess, initialHtlcTimeout) = createHtlcTxs()
    for (initialHtlcTx <- Seq(initialHtlcSuccess, initialHtlcTimeout)) {
      val previousTx = initialHtlcTx.updateTx(initialHtlcTx.txInfo.tx.copy(
        txIn = Seq(
          initialHtlcTx.txInfo.tx.txIn.head,
          // The previous funding attempt added three wallet inputs:
          TxIn(OutPoint(randomTxId(), 3), ByteVector.empty, 0, Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig)),
          TxIn(OutPoint(randomTxId(), 1), ByteVector.empty, 0, Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig)),
          TxIn(OutPoint(randomTxId(), 5), ByteVector.empty, 0, Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig))
        ),
        txOut = Seq(
          initialHtlcTx.txInfo.tx.txOut.head,
          // And one change output:
          TxOut(5000 sat, Script.pay2wpkh(PlaceHolderPubKey))
        )
      ))

      // We can handle a small feerate update by lowering the change output.
      val TxOutputAdjusted(feerateUpdate1) = adjustPreviousTxOutput(FundedTx(previousTx, 15000 sat, FeeratePerKw(2500 sat), Map.empty), FeeratePerKw(5000 sat), commitment, commitTx)
      assert(feerateUpdate1.txInfo.tx.txIn == previousTx.txInfo.tx.txIn)
      assert(feerateUpdate1.txInfo.tx.txOut.length == 2)
      assert(feerateUpdate1.txInfo.tx.txOut.head == previousTx.txInfo.tx.txOut.head)
      val TxOutputAdjusted(feerateUpdate2) = adjustPreviousTxOutput(FundedTx(previousTx, 15000 sat, FeeratePerKw(2500 sat), Map.empty), FeeratePerKw(6000 sat), commitment, commitTx)
      assert(feerateUpdate2.txInfo.tx.txIn == previousTx.txInfo.tx.txIn)
      assert(feerateUpdate2.txInfo.tx.txOut.length == 2)
      assert(feerateUpdate2.txInfo.tx.txOut.head == previousTx.txInfo.tx.txOut.head)
      assert(feerateUpdate2.txInfo.tx.txOut.last.amount < feerateUpdate1.txInfo.tx.txOut.last.amount)

      // If the previous funding attempt didn't add a change output, we must add new wallet inputs.
      val previousTxNoChange = previousTx.updateTx(previousTx.txInfo.tx.copy(txOut = Seq(previousTx.txInfo.tx.txOut.head)))
      val AddWalletInputs(tx) = adjustPreviousTxOutput(FundedTx(previousTxNoChange, 25000 sat, FeeratePerKw(2500 sat), Map.empty), FeeratePerKw(5000 sat), commitment, commitTx)
      assert(tx == previousTxNoChange)

      for (_ <- 1 to 100) {
        val amountIn = Random.nextInt(25_000_000).sat
        val changeAmount = Random.nextInt(amountIn.toLong.toInt).sat
        val fuzzyPreviousTx = previousTx.updateTx(previousTx.txInfo.tx.copy(txOut = Seq(
          initialHtlcTx.txInfo.tx.txOut.head,
          TxOut(changeAmount, Script.pay2wpkh(PlaceHolderPubKey))
        )))
        val targetFeerate = FeeratePerKw(2500 sat) + FeeratePerKw(Random.nextInt(20000).sat)
        adjustPreviousTxOutput(FundedTx(fuzzyPreviousTx, amountIn, FeeratePerKw(2500 sat), Map.empty), targetFeerate, commitment, commitTx) match {
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
    val (commitTx, claimHtlcSuccess, claimHtlcTimeout) = createClaimHtlcTx()
    for (claimHtlc <- Seq(claimHtlcSuccess, claimHtlcTimeout)) {
      var previousAmount = claimHtlc.txInfo.tx.txOut.head.amount
      for (i <- 1 to 100) {
        val targetFeerate = FeeratePerKw(250 * i sat)
        adjustPreviousTxOutput(FundedTx(claimHtlc, claimHtlc.txInfo.amountIn, FeeratePerKw(2500 sat), Map.empty), targetFeerate, commitment, commitTx) match {
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
