/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.transactions

import java.nio.ByteOrder

import fr.acinq.bitcoin.Crypto.{PrivateKey, ripemd160, sha256}
import fr.acinq.bitcoin.Script.{pay2wpkh, pay2wsh, write}
import fr.acinq.bitcoin._
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.transactions.Scripts.{htlcOffered, htlcReceived, toLocalDelayed}
import fr.acinq.eclair.transactions.Transactions.{addSigs, _}
import fr.acinq.eclair.wire.UpdateAddHtlc
import grizzled.slf4j.Logging
import org.scalatest.FunSuite

import scala.io.Source
import scala.util.{Failure, Random, Success, Try}

/**
  * Created by PM on 16/12/2016.
  */

class TransactionsSpec extends FunSuite with Logging {

  test("encode/decode sequence and locktime (one example)") {

    val txnumber = 0x11F71FB268DL

    val (sequence, locktime) = encodeTxNumber(txnumber)
    assert(sequence == 0x80011F71L)
    assert(locktime == 0x20FB268DL)

    val txnumber1 = decodeTxNumber(sequence, locktime)
    assert(txnumber == txnumber1)
  }

  test("reconstruct txnumber from sequence and locktime") {

    for (i <- 0 until 1000) {
      val txnumber = Random.nextLong() & 0xffffffffffffL
      val (sequence, locktime) = encodeTxNumber(txnumber)
      val txnumber1 = decodeTxNumber(sequence, locktime)
      assert(txnumber == txnumber1)
    }
  }

  test("compute fees") {
    // see BOLT #3 specs
    val htlcs = Set(
      DirectedHtlc(OUT, UpdateAddHtlc("00" * 32, 0, MilliSatoshi(5000000).amount, Hash.Zeroes, 552, BinaryData.empty)),
      DirectedHtlc(OUT, UpdateAddHtlc("00" * 32, 0, MilliSatoshi(1000000).amount, Hash.Zeroes, 553, BinaryData.empty)),
      DirectedHtlc(IN, UpdateAddHtlc("00" * 32, 0, MilliSatoshi(7000000).amount, Hash.Zeroes, 550, BinaryData.empty)),
      DirectedHtlc(IN, UpdateAddHtlc("00" * 32, 0, MilliSatoshi(800000).amount, Hash.Zeroes, 551, BinaryData.empty))
    )
    val spec = CommitmentSpec(htlcs, feeratePerKw = 5000, toLocalMsat = 0, toRemoteMsat = 0)
    val fee = Transactions.commitTxFee(Satoshi(546), spec)
    assert(fee == Satoshi(5340))
  }

  test("check pre-computed transaction weights") {
    val localRevocationPriv = PrivateKey(BinaryData("cc" * 32), compressed = true)
    val localPaymentPriv = PrivateKey(BinaryData("dd" * 32), compressed = true)
    val remotePaymentPriv = PrivateKey(BinaryData("ee" * 32), compressed = true)
    val localHtlcPriv = PrivateKey(BinaryData("ea" * 32), compressed = true)
    val remoteHtlcPriv = PrivateKey(BinaryData("eb" * 32), compressed = true)
    val localFinalPriv = PrivateKey(BinaryData("ff" * 32), compressed = true)
    val finalPubKeyScript = Script.write(Script.pay2wpkh(PrivateKey(BinaryData("fe" * 32), compressed = true).publicKey))
    val localDustLimit = Satoshi(546)
    val toLocalDelay = 144
    val feeratePerKw = fr.acinq.eclair.MinimumFeeratePerKw

    {
      // ClaimP2WPKHOutputTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimP2WPKHOutputTx
      val pubKeyScript = write(pay2wpkh(localPaymentPriv.publicKey))
      val commitTx = Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(20000), pubKeyScript) :: Nil, lockTime = 0)
      val claimP2WPKHOutputTx = makeClaimP2WPKHOutputTx(commitTx, localDustLimit, localPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(claimP2WPKHOutputTx, localPaymentPriv.publicKey, "bb" * 73).tx)
      assert(claimP2WPKHOutputWeight == weight)
      assert(claimP2WPKHOutputTx.fee >= claimP2WPKHOutputTx.minRelayFee)
    }

    {
      // ClaimHtlcDelayedTx
      // first we create a fake htlcSuccessOrTimeoutTx tx, containing only the output that will be spent by the ClaimDelayedOutputTx
      val pubKeyScript = write(pay2wsh(toLocalDelayed(localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey)))
      val htlcSuccessOrTimeoutTx = Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(20000), pubKeyScript) :: Nil, lockTime = 0)
      val claimHtlcDelayedTx = makeClaimDelayedOutputTx(htlcSuccessOrTimeoutTx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(claimHtlcDelayedTx, "bb" * 73).tx)
      assert(claimHtlcDelayedWeight == weight)
      assert(claimHtlcDelayedTx.fee >= claimHtlcDelayedTx.minRelayFee)
    }

    {
      // MainPenaltyTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the MainPenaltyTx
      val pubKeyScript = write(pay2wsh(toLocalDelayed(localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey)))
      val commitTx = Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(20000), pubKeyScript) :: Nil, lockTime = 0)
      val mainPenaltyTx = makeMainPenaltyTx(commitTx, localDustLimit, localRevocationPriv.publicKey, finalPubKeyScript, toLocalDelay, localPaymentPriv.publicKey, feeratePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(mainPenaltyTx, "bb" * 73).tx)
      assert(mainPenaltyWeight == weight)
      assert(mainPenaltyTx.fee >= mainPenaltyTx.minRelayFee)
    }

    {
      // HtlcPenaltyTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimHtlcSuccessTx
      val paymentPreimage = BinaryData("42" * 32)
      val htlc = UpdateAddHtlc("00" * 32, 0, Satoshi(20000).amount * 1000, sha256(paymentPreimage), cltvExpiry = 400144, BinaryData.empty)
      val redeemScript = htlcReceived(localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localRevocationPriv.publicKey, ripemd160(htlc.paymentHash), htlc.cltvExpiry)
      val pubKeyScript = write(pay2wsh(redeemScript))
      val commitTx = Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(htlc.amountMsat / 1000), pubKeyScript) :: Nil, lockTime = 0)
      val htlcPenaltyTx = makeHtlcPenaltyTx(commitTx, outputsAlreadyUsed = Set.empty, Script.write(redeemScript), localDustLimit, finalPubKeyScript, feeratePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(htlcPenaltyTx, "bb" * 73, localRevocationPriv.publicKey).tx)
      assert(htlcPenaltyWeight == weight)
      assert(htlcPenaltyTx.fee >= htlcPenaltyTx.minRelayFee)
    }

    {
      // ClaimHtlcSuccessTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimHtlcSuccessTx
      val paymentPreimage = BinaryData("42" * 32)
      val htlc = UpdateAddHtlc("00" * 32, 0, Satoshi(20000).amount * 1000, sha256(paymentPreimage), cltvExpiry = 400144, BinaryData.empty)
      val pubKeyScript = write(pay2wsh(htlcOffered(localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localRevocationPriv.publicKey, ripemd160(htlc.paymentHash))))
      val commitTx = Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(htlc.amountMsat / 1000), pubKeyScript) :: Nil, lockTime = 0)
      val claimHtlcSuccessTx = makeClaimHtlcSuccessTx(commitTx, outputsAlreadyUsed = Set.empty, localDustLimit, remoteHtlcPriv.publicKey, localHtlcPriv.publicKey, localRevocationPriv.publicKey, finalPubKeyScript, htlc, feeratePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(claimHtlcSuccessTx, "bb" * 73, paymentPreimage).tx)
      assert(claimHtlcSuccessWeight == weight)
      assert(claimHtlcSuccessTx.fee >= claimHtlcSuccessTx.minRelayFee)
    }

    {
      // ClaimHtlcTimeoutTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimHtlcSuccessTx
      val paymentPreimage = BinaryData("42" * 32)
      val htlc = UpdateAddHtlc("00" * 32, 0, Satoshi(20000).amount * 1000, sha256(paymentPreimage), cltvExpiry = 400144, BinaryData.empty)
      val pubKeyScript = write(pay2wsh(htlcReceived(localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localRevocationPriv.publicKey, ripemd160(htlc.paymentHash), htlc.cltvExpiry)))
      val commitTx = Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(htlc.amountMsat / 1000), pubKeyScript) :: Nil, lockTime = 0)
      val claimClaimHtlcTimeoutTx = makeClaimHtlcTimeoutTx(commitTx, outputsAlreadyUsed = Set.empty, localDustLimit, remoteHtlcPriv.publicKey, localHtlcPriv.publicKey, localRevocationPriv.publicKey, finalPubKeyScript, htlc, feeratePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(claimClaimHtlcTimeoutTx, "bb" * 73).tx)
      assert(claimHtlcTimeoutWeight == weight)
      assert(claimClaimHtlcTimeoutTx.fee >= claimClaimHtlcTimeoutTx.minRelayFee)
    }
  }

  test("generate valid commitment and htlc transactions") {
    val localFundingPriv = PrivateKey(BinaryData("a1" * 32) :+ 1.toByte)
    val remoteFundingPriv = PrivateKey(BinaryData("a2" * 32) :+ 1.toByte)
    val localRevocationPriv = PrivateKey(BinaryData("a3" * 32) :+ 1.toByte)
    val localPaymentPriv = PrivateKey(BinaryData("a4" * 32) :+ 1.toByte)
    val localDelayedPaymentPriv = PrivateKey(BinaryData("a5" * 32) :+ 1.toByte)
    val remotePaymentPriv = PrivateKey(BinaryData("a6" * 32) :+ 1.toByte)
    val localHtlcPriv = PrivateKey(BinaryData("a7" * 32) :+ 1.toByte)
    val remoteHtlcPriv = PrivateKey(BinaryData("a8" * 32) :+ 1.toByte)
    val finalPubKeyScript = Script.write(Script.pay2wpkh(PrivateKey(BinaryData("a9" * 32), true).publicKey))
    val commitInput = Funding.makeFundingInputInfo(BinaryData("a0" * 32), 0, Btc(1), localFundingPriv.publicKey, remoteFundingPriv.publicKey)
    val toLocalDelay = 144
    val localDustLimit = Satoshi(546)
    val feeratePerKw = 22000


    // htlc1 and htlc2 are regular IN/OUT htlcs
    val paymentPreimage1 = BinaryData("11" * 32)
    val htlc1 = UpdateAddHtlc("00" * 32, 0, millibtc2satoshi(MilliBtc(100)).amount * 1000, sha256(paymentPreimage1), 300, BinaryData.empty)
    val paymentPreimage2 = BinaryData("22" * 32)
    val htlc2 = UpdateAddHtlc("00" * 32, 1, millibtc2satoshi(MilliBtc(200)).amount * 1000, sha256(paymentPreimage2), 300, BinaryData.empty)
    // htlc3 and htlc4 are dust htlcs IN/OUT htlcs, with an amount large enough to be included in the commit tx, but too small to be claimed at 2nd stage
    val paymentPreimage3 = BinaryData("33" * 32)
    val htlc3 = UpdateAddHtlc("00" * 32, 2, (localDustLimit + weight2fee(feeratePerKw, htlcTimeoutWeight)).amount * 1000, sha256(paymentPreimage3), 300, BinaryData.empty)
    val paymentPreimage4 = BinaryData("44" * 32)
    val htlc4 = UpdateAddHtlc("00" * 32, 3, (localDustLimit + weight2fee(feeratePerKw, htlcSuccessWeight)).amount * 1000, sha256(paymentPreimage4), 300, BinaryData.empty)
    val spec = CommitmentSpec(
      htlcs = Set(
        DirectedHtlc(OUT, htlc1),
        DirectedHtlc(IN, htlc2),
        DirectedHtlc(OUT, htlc3),
        DirectedHtlc(IN, htlc4)
      ),
      feeratePerKw = feeratePerKw,
      toLocalMsat = millibtc2satoshi(MilliBtc(400)).amount * 1000,
      toRemoteMsat = millibtc2satoshi(MilliBtc(300)).amount * 1000)

    val commitTxNumber = 0x404142434445L
    val commitTx = {
      val txinfo = makeCommitTx(commitInput, commitTxNumber, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, true, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, spec)
      val localSig = Transactions.sign(txinfo, localPaymentPriv)
      val remoteSig = Transactions.sign(txinfo, remotePaymentPriv)
      Transactions.addSigs(txinfo, localFundingPriv.publicKey, remoteFundingPriv.publicKey, localSig, remoteSig)
    }

    {
      assert(getCommitTxNumber(commitTx.tx, true, localPaymentPriv.publicKey, remotePaymentPriv.publicKey) == commitTxNumber)
      val hash: Array[Byte] = Crypto.sha256(localPaymentPriv.publicKey.toBin ++ remotePaymentPriv.publicKey.toBin)
      val num = Protocol.uint64(hash.takeRight(8), ByteOrder.BIG_ENDIAN) & 0xffffffffffffL
      val check = ((commitTx.tx.txIn(0).sequence & 0xffffff) << 24) | (commitTx.tx.lockTime)
      assert((check ^ num) == commitTxNumber)
    }
    val (htlcTimeoutTxs, htlcSuccessTxs) = makeHtlcTxs(commitTx.tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, spec)

    assert(htlcTimeoutTxs.size == 2) // htlc1 and htlc3
    assert(htlcSuccessTxs.size == 2) // htlc2 and htlc4

    {
      // either party spends local->remote htlc output with htlc timeout tx
      for (htlcTimeoutTx <- htlcTimeoutTxs) {
        val localSig = sign(htlcTimeoutTx, localHtlcPriv)
        val remoteSig = sign(htlcTimeoutTx, remoteHtlcPriv)
        val signed = addSigs(htlcTimeoutTx, localSig, remoteSig)
        assert(checkSpendable(signed).isSuccess)
      }
    }

    {
      // local spends delayed output of htlc1 timeout tx
      val claimHtlcDelayed = makeClaimDelayedOutputTx(htlcTimeoutTxs(0).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val localSig = sign(claimHtlcDelayed, localDelayedPaymentPriv)
      val signedTx = addSigs(claimHtlcDelayed, localSig)
      assert(checkSpendable(signedTx).isSuccess)
      // local can't claim delayed output of htlc3 timeout tx because it is below the dust limit
      intercept[RuntimeException] {
        makeClaimDelayedOutputTx(htlcTimeoutTxs(1).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      }
    }

    {
      // remote spends local->remote htlc1/htlc3 output directly in case of success
      for ((htlc, paymentPreimage) <- (htlc1, paymentPreimage1) :: (htlc3, paymentPreimage3) :: Nil) {
        val claimHtlcSuccessTx = makeClaimHtlcSuccessTx(commitTx.tx, outputsAlreadyUsed = Set.empty, localDustLimit, remoteHtlcPriv.publicKey, localHtlcPriv.publicKey, localRevocationPriv.publicKey, finalPubKeyScript, htlc, feeratePerKw)
        val localSig = sign(claimHtlcSuccessTx, remoteHtlcPriv)
        val signed = addSigs(claimHtlcSuccessTx, localSig, paymentPreimage)
        assert(checkSpendable(signed).isSuccess)
      }
    }

    {
      // local spends remote->local htlc2/htlc4 output with htlc success tx using payment preimage
      for ((htlcSuccessTx, paymentPreimage) <- (htlcSuccessTxs(0), paymentPreimage2) :: (htlcSuccessTxs(1), paymentPreimage4) :: Nil) {
        val localSig = sign(htlcSuccessTx, localHtlcPriv)
        val remoteSig = sign(htlcSuccessTx, remoteHtlcPriv)
        val signedTx = addSigs(htlcSuccessTx, localSig, remoteSig, paymentPreimage)
        assert(checkSpendable(signedTx).isSuccess)
        // check remote sig
        assert(checkSig(htlcSuccessTx, remoteSig, remoteHtlcPriv.publicKey))
      }
    }

    {
      // local spends delayed output of htlc2 success tx
      val claimHtlcDelayed = makeClaimDelayedOutputTx(htlcSuccessTxs(0).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val localSig = sign(claimHtlcDelayed, localDelayedPaymentPriv)
      val signedTx = addSigs(claimHtlcDelayed, localSig)
      assert(checkSpendable(signedTx).isSuccess)
      // local can't claim delayed output of htlc4 timeout tx because it is below the dust limit
      intercept[RuntimeException] {
        makeClaimDelayedOutputTx(htlcSuccessTxs(1).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      }
    }

    {
      // remote spends main output
      val claimP2WPKHOutputTx = makeClaimP2WPKHOutputTx(commitTx.tx, localDustLimit, remotePaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val localSig = sign(claimP2WPKHOutputTx, remotePaymentPriv)
      val signedTx = addSigs(claimP2WPKHOutputTx, remotePaymentPriv.publicKey, localSig)
      assert(checkSpendable(signedTx).isSuccess)
    }

    {
      // remote spends remote->local htlc output directly in case of timeout
      val claimHtlcTimeoutTx = makeClaimHtlcTimeoutTx(commitTx.tx, outputsAlreadyUsed = Set.empty, localDustLimit, remoteHtlcPriv.publicKey, localHtlcPriv.publicKey, localRevocationPriv.publicKey, finalPubKeyScript, htlc2, feeratePerKw)
      val localSig = sign(claimHtlcTimeoutTx, remoteHtlcPriv)
      val signed = addSigs(claimHtlcTimeoutTx, localSig)
      assert(checkSpendable(signed).isSuccess)
    }

    {
      // remote spends offered HTLC output with revocation key
      val script = Script.write(Scripts.htlcOffered(localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localRevocationPriv.publicKey, Crypto.ripemd160(htlc1.paymentHash)))
      val htlcPenaltyTx = makeHtlcPenaltyTx(commitTx.tx, outputsAlreadyUsed = Set.empty, script, localDustLimit, finalPubKeyScript, feeratePerKw)
      val sig = sign(htlcPenaltyTx, localRevocationPriv)
      val signed = addSigs(htlcPenaltyTx, sig, localRevocationPriv.publicKey)
      assert(checkSpendable(signed).isSuccess)
    }

    {
      // remote spends received HTLC output with revocation key
      val script = Script.write(Scripts.htlcReceived(localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localRevocationPriv.publicKey, Crypto.ripemd160(htlc2.paymentHash), htlc2.cltvExpiry))
      val htlcPenaltyTx = makeHtlcPenaltyTx(commitTx.tx, outputsAlreadyUsed = Set.empty, script, localDustLimit, finalPubKeyScript, feeratePerKw)
      val sig = sign(htlcPenaltyTx, localRevocationPriv)
      val signed = addSigs(htlcPenaltyTx, sig, localRevocationPriv.publicKey)
      assert(checkSpendable(signed).isSuccess)
    }

  }

  def checkSuccessOrFailTest[T](input: Try[T]) = input match {
    case Success(_) => ()
    case Failure(t) => fail(t)
  }

  def htlc(direction: Direction, amount: Satoshi): DirectedHtlc =
    DirectedHtlc(direction, UpdateAddHtlc("00" * 32, 0, amount.amount * 1000, "00" * 32, 144, ""))

  test("BOLT 2 fee tests") {

    val bolt3 = Source
      .fromURL("https://raw.githubusercontent.com/lightningnetwork/lightning-rfc/master/03-transactions.md")
      .mkString
      .replace("    name:", "$   name:")
    // character '$' separates tests

    // this regex extract params from a given test
    val testRegex = ("""name: (.*)\n""" +
      """.*to_local_msat: ([0-9]+)\n""" +
      """.*to_remote_msat: ([0-9]+)\n""" +
      """.*feerate_per_kw: ([0-9]+)\n""" +
      """.*base commitment transaction fee = ([0-9]+)\n""" +
      """[^$]+""").r
    // this regex extracts htlc direction and amounts
    val htlcRegex =
      """.*HTLC ([a-z]+) amount ([0-9]+).*""".r

    val dustLimit = Satoshi(546)
    case class TestSetup(name: String, dustLimit: Satoshi, spec: CommitmentSpec, expectedFee: Satoshi)

    val tests = testRegex.findAllIn(bolt3).map(s => {
      val testRegex(name, to_local_msat, to_remote_msat, feerate_per_kw, fee) = s
      val htlcs = htlcRegex.findAllIn(s).map(l => {
        val htlcRegex(direction, amount) = l
        direction match {
          case "offered" => htlc(OUT, Satoshi(amount.toLong))
          case "received" => htlc(IN, Satoshi(amount.toLong))
        }
      }).toSet
      TestSetup(name, dustLimit, CommitmentSpec(htlcs = htlcs, feeratePerKw = feerate_per_kw.toLong, toLocalMsat = to_local_msat.toLong, toRemoteMsat = to_remote_msat.toLong), Satoshi(fee.toLong))
    })

    // simple non-reg test making sure we are not missing tests
    assert(tests.size === 15, "there were 15 tests at ec99f893f320e8c88f564c1c8566f3454f0f1f5f")

    tests.foreach(test => {
      logger.info(s"running BOLT 2 test: '${test.name}'")
      val fee = commitTxFee(test.dustLimit, test.spec)
      assert(fee === test.expectedFee)
    })
  }
}
