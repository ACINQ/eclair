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
import fr.acinq.eclair.channel.publish.ReplaceableTxFunder._
import fr.acinq.eclair.channel.publish.ReplaceableTxPrePublisher._
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.{BlockHeight, CltvExpiry, TestKitBaseClass, randomBytes32}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.ByteVector

class ReplaceableTxFunderSpec extends TestKitBaseClass with AnyFunSuiteLike {

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

}
