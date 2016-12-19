package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.Crypto.{Scalar, sha256}
import fr.acinq.bitcoin.{BinaryData, Btc, MilliBtc, millibtc2satoshi}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.UpdateAddHtlc
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 16/12/2016.
  */
@RunWith(classOf[JUnitRunner])
class TransactionsSpec extends FunSuite {

  val fundingLocalPriv = Scalar(BinaryData("aa" * 32) :+ 1.toByte)
  val fundingRemotePriv = Scalar(BinaryData("bb" * 32) :+ 1.toByte)
  val commitInput = Funding.makeFundingInputInfo(BinaryData("12" * 32), 0, Btc(1), fundingLocalPriv.toPoint, fundingRemotePriv.toPoint)
  val localRevocationPriv = Scalar(BinaryData("cc" * 32) :+ 1.toByte)
  val localPaymentPriv = Scalar(BinaryData("dd" * 32) :+ 1.toByte)
  val remotePaymentPriv = Scalar(BinaryData("ee" * 32) :+ 1.toByte)
  val toLocalDelay = 144

  test("generate valid commitment and htlc transactions") {
    val paymentPreimage1 = BinaryData("11" * 32)
    val htlc1 = UpdateAddHtlc(0, 0, millibtc2satoshi(MilliBtc(100)).amount * 1000, 300, sha256(paymentPreimage1), BinaryData(""))
    val paymentPreimage2 = BinaryData("22" * 32)
    val htlc2 = UpdateAddHtlc(0, 1, millibtc2satoshi(MilliBtc(200)).amount * 1000, 300, sha256(paymentPreimage2), BinaryData(""))
    val spec = CommitmentSpec(
      htlcs = Set(
        Htlc(OUT, htlc1, None),
        Htlc(IN, htlc2, None)
      ),
      feeRate = 0,
      to_local_msat = millibtc2satoshi(MilliBtc(400)).amount * 1000,
      to_remote_msat = millibtc2satoshi(MilliBtc(300)).amount * 1000)

    val commitTx = makeCommitTx(commitInput, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, spec)

    val (htlcTimeoutTxs, htlcSuccessTxs) = makeHtlcTxs(commitTx.tx, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, spec)

    assert(htlcTimeoutTxs.size == 1)
    assert(htlcSuccessTxs.size == 1)

    {
      // either party spends local->remote htlc output with htlc timeout tx
      val htlcTimeoutTx = htlcTimeoutTxs(0)
      val localSig = sign(htlcTimeoutTx, localPaymentPriv)
      val remoteSig = sign(htlcTimeoutTx, remotePaymentPriv)
      val signed = addSigs(htlcTimeoutTx, localSig, remoteSig)
      assert(checkSig(signed).isSuccess)
    }

    {
      // local spends delayed output of htlc timeout tx
      val htlcTimeoutTx = htlcTimeoutTxs(0)
      val localFinalPriv = Scalar(BinaryData("ff" * 32))
      val claimHtlcDelayed = makeClaimHtlcDelayed(htlcTimeoutTx.tx, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, localFinalPriv.toPoint, htlc1)
      val localSig = sign(claimHtlcDelayed, localPaymentPriv)
      val signedTx = addSigs(claimHtlcDelayed, localSig)
      assert(checkSig(signedTx).isSuccess)
    }

    {
      // remote spends local->remote htlc output directly in case of success
      val remoteFinalPriv = Scalar(BinaryData("ff" * 32) :+ 1.toByte)
      val claimHtlcSuccessTx = makeClaimHtlcSuccessTx(commitTx.tx, remotePaymentPriv.toPoint, localPaymentPriv.toPoint, remoteFinalPriv.toPoint, htlc1)
      val localSig = sign(claimHtlcSuccessTx, remotePaymentPriv)
      val signed = addSigs(claimHtlcSuccessTx, localSig, paymentPreimage1)
      assert(checkSig(signed).isSuccess)
    }

    {
      // local spends remote->local htlc output with htlc success tx using payment preimage
      val htlcSuccessTx = htlcSuccessTxs(0)
      val localSig = sign(htlcSuccessTx, localPaymentPriv)
      val remoteSig = sign(htlcSuccessTx, remotePaymentPriv)
      val signedTx = addSigs(htlcSuccessTx, localSig, remoteSig, paymentPreimage2)
      assert(checkSig(signedTx).isSuccess)
    }

    {
      // local spends delayed output of htlc success tx
      val htlcSuccessTx = htlcSuccessTxs(0)
      val localFinalPriv = Scalar(BinaryData("ff" * 32) :+ 1.toByte)
      val claimHtlcDelayed = makeClaimHtlcDelayed(htlcSuccessTx.tx, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, localFinalPriv.toPoint, htlc1)
      val localSig = sign(claimHtlcDelayed, localPaymentPriv)
      val signedTx = addSigs(claimHtlcDelayed, localSig)
      assert(checkSig(signedTx).isSuccess)
    }

    {
      // remote spends remote->local htlc output directly in case of timeout
      val remoteFinalPriv = Scalar(BinaryData("ff" * 32) :+ 1.toByte)
      val claimHtlcTimeoutTx = makeClaimHtlcTimeoutTx(commitTx.tx, remotePaymentPriv.toPoint, localPaymentPriv.toPoint, remoteFinalPriv.toPoint, htlc2)
      val localSig = sign(claimHtlcTimeoutTx, remotePaymentPriv)
      val signed = addSigs(claimHtlcTimeoutTx, localSig)
      assert(checkSig(signed).isSuccess)
    }

  }

}
