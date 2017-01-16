package fr.acinq.eclair.transactions

import java.nio.{ByteBuffer, ByteOrder}

import fr.acinq.bitcoin.Crypto.{Scalar, sha256}
import fr.acinq.bitcoin.{BinaryData, Btc, Crypto, MilliBtc, MilliSatoshi, Protocol, Satoshi, Transaction, millibtc2satoshi}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.UpdateAddHtlc
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.util.Random

/**
  * Created by PM on 16/12/2016.
  */
@RunWith(classOf[JUnitRunner])
class TransactionsSpec extends FunSuite {

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

  test("generate valid commitment and htlc transactions") {
    val localFundingPriv = Scalar(BinaryData("aa" * 32) :+ 1.toByte)
    val remoteFundingPriv = Scalar(BinaryData("bb" * 32) :+ 1.toByte)
    val localRevocationPriv = Scalar(BinaryData("cc" * 32) :+ 1.toByte)
    val localPaymentPriv = Scalar(BinaryData("dd" * 32) :+ 1.toByte)
    val remotePaymentPriv = Scalar(BinaryData("ee" * 32) :+ 1.toByte)
    val commitInput = Funding.makeFundingInputInfo(BinaryData("12" * 32), 0, Btc(1), localFundingPriv.toPoint, remoteFundingPriv.toPoint)
    val toLocalDelay = 144
    val localDustLimit = Satoshi(542)

    val paymentPreimage1 = BinaryData("11" * 32)
    val htlc1 = UpdateAddHtlc(0, 0, millibtc2satoshi(MilliBtc(100)).amount * 1000, 300, sha256(paymentPreimage1), BinaryData(""))
    val paymentPreimage2 = BinaryData("22" * 32)
    val htlc2 = UpdateAddHtlc(0, 1, millibtc2satoshi(MilliBtc(200)).amount * 1000, 300, sha256(paymentPreimage2), BinaryData(""))
    val spec = CommitmentSpec(
      htlcs = Set(
        Htlc(OUT, htlc1, None),
        Htlc(IN, htlc2, None)
      ),
      feeRatePerKw = 0,
      toLocalMsat = millibtc2satoshi(MilliBtc(400)).amount * 1000,
      toRemoteMsat = millibtc2satoshi(MilliBtc(300)).amount * 1000)

    val commitTxNumber = 0x404142434445L
    val commitTx = {
      val txinfo = makeCommitTx(commitInput, commitTxNumber, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, true, localDustLimit, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, spec)
      val localSig = Transactions.sign(txinfo, localPaymentPriv)
      val remoteSig = Transactions.sign(txinfo, remotePaymentPriv)
      Transactions.addSigs(txinfo, localFundingPriv.toPoint, remoteFundingPriv.toPoint, localSig, remoteSig)
    }

    {
      assert(getCommitTxNumber(commitTx.tx, localPaymentPriv.toPoint, remotePaymentPriv.toPoint) == commitTxNumber)
      val hash: Array[Byte] = Crypto.sha256(localPaymentPriv.toPoint.toBin ++ remotePaymentPriv.toPoint.toBin)
      val num = ByteBuffer.wrap(hash.takeRight(8)).order(ByteOrder.BIG_ENDIAN).getLong & 0xffffffffffffL
      val check = ((commitTx.tx.txIn(0).sequence & 0xffffff) << 24) | (commitTx.tx.lockTime)
      assert((check ^ num) == commitTxNumber)
    }
    val (htlcTimeoutTxs, htlcSuccessTxs) = makeHtlcTxs(commitTx.tx, localDustLimit, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, spec)

    assert(htlcTimeoutTxs.size == 1)
    assert(htlcSuccessTxs.size == 1)

    {
      // either party spends local->remote htlc output with htlc timeout tx
      val htlcTimeoutTx = htlcTimeoutTxs(0)
      val localSig = sign(htlcTimeoutTx, localPaymentPriv)
      val remoteSig = sign(htlcTimeoutTx, remotePaymentPriv)
      val signed = addSigs(htlcTimeoutTx, localSig, remoteSig)
      assert(checkSpendable(signed).isSuccess)
    }

    {
      // local spends delayed output of htlc timeout tx
      val htlcTimeoutTx = htlcTimeoutTxs(0)
      val localFinalPriv = Scalar(BinaryData("ff" * 32))
      val claimHtlcDelayed = makeClaimHtlcDelayed(htlcTimeoutTx.tx, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, localFinalPriv.toPoint, htlc1)
      val localSig = sign(claimHtlcDelayed, localPaymentPriv)
      val signedTx = addSigs(claimHtlcDelayed, localSig)
      assert(checkSpendable(signedTx).isSuccess)
    }

    {
      // remote spends local->remote htlc output directly in case of success
      val remoteFinalPriv = Scalar(BinaryData("ff" * 32) :+ 1.toByte)
      val claimHtlcSuccessTx = makeClaimHtlcSuccessTx(commitTx.tx, remotePaymentPriv.toPoint, localPaymentPriv.toPoint, remoteFinalPriv.toPoint, htlc1)
      val localSig = sign(claimHtlcSuccessTx, remotePaymentPriv)
      val signed = addSigs(claimHtlcSuccessTx, localSig, paymentPreimage1)
      assert(checkSpendable(signed).isSuccess)
    }

    {
      // local spends remote->local htlc output with htlc success tx using payment preimage
      val htlcSuccessTx = htlcSuccessTxs(0)
      val localSig = sign(htlcSuccessTx, localPaymentPriv)
      val remoteSig = sign(htlcSuccessTx, remotePaymentPriv)
      val signedTx = addSigs(htlcSuccessTx, localSig, remoteSig, paymentPreimage2)
      assert(checkSpendable(signedTx).isSuccess)
      // check remote sig
      assert(checkSig(htlcSuccessTx, remoteSig, remotePaymentPriv.toPoint))
    }

    {
      // local spends delayed output of htlc success tx
      val htlcSuccessTx = htlcSuccessTxs(0)
      val localFinalPriv = Scalar(BinaryData("ff" * 32) :+ 1.toByte)
      val claimHtlcDelayed = makeClaimHtlcDelayed(htlcSuccessTx.tx, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, localFinalPriv.toPoint, htlc1)
      val localSig = sign(claimHtlcDelayed, localPaymentPriv)
      val signedTx = addSigs(claimHtlcDelayed, localSig)
      assert(checkSpendable(signedTx).isSuccess)
    }

    {
      // remote spends remote->local htlc output directly in case of timeout
      val remoteFinalPriv = Scalar(BinaryData("ff" * 32) :+ 1.toByte)
      val claimHtlcTimeoutTx = makeClaimHtlcTimeoutTx(commitTx.tx, remotePaymentPriv.toPoint, localPaymentPriv.toPoint, remoteFinalPriv.toPoint, htlc2)
      val localSig = sign(claimHtlcTimeoutTx, remotePaymentPriv)
      val signed = addSigs(claimHtlcTimeoutTx, localSig)
      assert(checkSpendable(signed).isSuccess)
    }

  }

  /*def randombytes(len: Int): BinaryData = {
    val bytes = Array.fill[Byte](len)(0)
    Random.nextBytes(bytes)
    bytes
  }*/

  test("BOLT 3 test vectors") {

    val localFundingPriv = Scalar(BinaryData("30ff4956bbdd3222d44cc5e8a1261dab1e07957bdac5ae88fe3261ef321f3749") :+ 1.toByte)
    val remoteFundingPriv = Scalar(BinaryData("1552dfba4f6cf29a62a0af13c8d6981d36d0ef8d61ba10fb0fe90da7634d7e13") :+ 1.toByte)
    val localRevocationPriv = Scalar(BinaryData("131526c63723ff1d36c28e61a8bdc86660d7893879bbda4cfeaad2022db7c109") :+ 1.toByte)
    val localPaymentPriv = Scalar(BinaryData("e937268a37a774aa948ebddff3187fedc7035e3f0a029d8d85f31bda33b02d55") :+ 1.toByte)
    val remotePaymentPriv = Scalar(BinaryData("ce65059278a571ee4f4c9b4d5d7fa07449bbe09d9c716879343d9e975df1de33") :+ 1.toByte)
    val commitInput = Funding.makeFundingInputInfo(BinaryData("42a26bb3a430a536cf9e3a8ce2cbcb0427c29ec6c7d647175cfc78328b57fba7"), 1, MilliBtc(100), localFundingPriv.toPoint, remoteFundingPriv.toPoint)
    val toLocalDelay = 144
    val localDustLimit = Satoshi(546)
    val feeRatePerKw = 15000

    println(s"    funding_tx_hash: ${commitInput.outPoint.hash}")
    println(s"    funding_output_index: ${commitInput.outPoint.index}")
    println(s"    funding_amount_satoshi: ${commitInput.txOut.amount.toLong}")
    println(s"    local_funding_key: ${localFundingPriv.toBin}")
    println(s"    remote_funding_key: ${remoteFundingPriv.toBin}")
    println(s"    local_revocation_key: ${localRevocationPriv.toBin}")
    println(s"    local_payment_key: ${localPaymentPriv.toBin}")
    println(s"    remote_payment_key: ${remotePaymentPriv.toBin}")
    println(s"    local_delay: $toLocalDelay")
    println(s"    local_dust_limit_satoshi: ${localDustLimit.amount}")
    println(s"    local_feerate_per_kw: ${feeRatePerKw}")

    def run(name: String, spec: CommitmentSpec, commitTx: CommitTx, htlcTimeoutTxs: Seq[HtlcTimeoutTx], htlcSuccessTxs: Seq[HtlcSuccessTx]): Unit = {
      println()
      println(s"    name: $name")
      println(s"    to_local_msat: ${spec.toLocalMsat}")
      println(s"    to_remote_msat: ${spec.toRemoteMsat}")
      spec.htlcs.toSeq.zipWithIndex.foreach {
        case (htlc, i) =>
          println(s"    htlc $i direction: ${if (htlc.direction == OUT) "local->remote" else "remote->local"}")
          println(s"    htlc $i amount_msat: ${htlc.add.amountMsat}")
          println(s"    htlc $i expiry: ${htlc.add.expiry}")
          println(s"    htlc $i payment_hash: ${htlc.add.paymentHash}")
      }
      println(s"    output commit_tx: ${Transaction.write(commitTx.tx)}")

      htlcTimeoutTxs.zipWithIndex.foreach {
        case (tx, i) => println(s"    output htlc_timeout_tx $i: ${Transaction.write(tx.tx)}")
      }
      htlcSuccessTxs.zipWithIndex.foreach {
        case (tx, i) => println(s"    output htlc_success_tx $i: ${Transaction.write(tx.tx)}")
      }
    }


    {
      // simple tx with two outputs
      val spec = CommitmentSpec(
        htlcs = Set.empty,
        feeRatePerKw = feeRatePerKw,
        toLocalMsat = millibtc2satoshi(MilliBtc(70)).amount * 1000,
        toRemoteMsat = millibtc2satoshi(MilliBtc(30)).amount * 1000)
      val commitTx = makeCommitTx(commitInput, 42, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, true, localDustLimit, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, spec)
      val (htlcTimeoutTxs, htlcSuccessTxs) = makeHtlcTxs(commitTx.tx, localDustLimit, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, spec)
      run("simple tx with two outputs", spec, commitTx, htlcTimeoutTxs, htlcSuccessTxs)
    }

    {
      // two outputs with one below dust limit
      val spec = CommitmentSpec(
        htlcs = Set.empty,
        feeRatePerKw = feeRatePerKw,
        toLocalMsat = (MilliBtc(100) - Satoshi(1000)).amount * 1000,
        toRemoteMsat = Satoshi(1000).amount * 1000)
      val commitTx = makeCommitTx(commitInput, 42, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, true, localDustLimit, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, spec)
      val (htlcTimeoutTxs, htlcSuccessTxs) = makeHtlcTxs(commitTx.tx, localDustLimit, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, spec)
      run("two outputs with fundee below dust limit", spec, commitTx, htlcTimeoutTxs, htlcSuccessTxs)
    }

    {
      // with htlcs, all above dust limit
      val paymentPreimage1 = BinaryData("f1a90faf44857d6c6b83dba3d27259bc0c9edb7215d21d2d5d9e7b84a9a555ab")
      val htlc1 = UpdateAddHtlc(0, 0, MilliSatoshi(10000000).amount, 443210, sha256(paymentPreimage1), BinaryData(""))
      val paymentPreimage2 = BinaryData("5bd0169de7a4a90c0b2b44a6f7c93860ac96630f60e40252fdd0e9f4758bc4ed")
      val htlc2 = UpdateAddHtlc(0, 1, MilliSatoshi(20000000).amount, 453203, sha256(paymentPreimage2), BinaryData(""))
      val spec = CommitmentSpec(
        htlcs = Set(
          Htlc(OUT, htlc1, None),
          Htlc(IN, htlc2, None)
        ),
        feeRatePerKw = feeRatePerKw,
        toLocalMsat = (MilliBtc(100) - MilliBtc(30) - MilliSatoshi(htlc1.amountMsat) - MilliSatoshi(htlc2.amountMsat)).amount * 1000,
        toRemoteMsat = millibtc2satoshi(MilliBtc(30)).amount * 1000)
      val commitTx = makeCommitTx(commitInput, 42, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, true, localDustLimit, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, spec)
      val (htlcTimeoutTxs, htlcSuccessTxs) = makeHtlcTxs(commitTx.tx, localDustLimit, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, spec)
      run("with htlcs, all above dust limit", spec, commitTx, htlcTimeoutTxs, htlcSuccessTxs)
    }

    {
      // with htlcs, some below dust limit
      val paymentPreimage1 = BinaryData("257d67d518b1f1a840096a8eaa9c6351a9296d01240f8edd694ad329a10ca019")
      val htlc1 = UpdateAddHtlc(0, 0, MilliSatoshi(10000000).amount, 443210, sha256(paymentPreimage1), BinaryData(""))
      val paymentPreimage2 = BinaryData("7f91b6f7f014a275da70959fdcc3bcc435650a6551b3ffe743e73955885c35e4")
      val htlc2 = UpdateAddHtlc(0, 1, MilliSatoshi(200000).amount, 443120, sha256(paymentPreimage2), BinaryData(""))
      val paymentPreimage3 = BinaryData("c2d651a0d96ae2cd6b53570ef1c9663eea1dca7c6d8226a330cb8c073783d654")
      val htlc3 = UpdateAddHtlc(0, 0, MilliSatoshi(20000000).amount, 445435, sha256(paymentPreimage3), BinaryData(""))
      val paymentPreimage4 = BinaryData("e65e9fdc1593a434ccd58cf24225b5ba43d7fcc8137d998159bd009cf2bf948f")
      val htlc4 = UpdateAddHtlc(0, 1, MilliSatoshi(100000).amount, 448763, sha256(paymentPreimage4), BinaryData(""))
      val paymentPreimage5 = BinaryData("bc37269e37a774aa948ebddff3187fedc70aae3f0a029d8775f31bda33b02d55")
      val htlc5 = UpdateAddHtlc(0, 2, MilliSatoshi(130000000).amount, 445678, sha256(paymentPreimage5), BinaryData(""))
      val spec = CommitmentSpec(
        htlcs = Set(
          Htlc(OUT, htlc1, None),
          Htlc(OUT, htlc2, None),
          Htlc(IN, htlc3, None),
          Htlc(IN, htlc4, None),
          Htlc(IN, htlc5, None)
        ),
        feeRatePerKw = feeRatePerKw,
        toLocalMsat = (MilliBtc(100) - MilliBtc(30) - MilliSatoshi(htlc1.amountMsat) - MilliSatoshi(htlc2.amountMsat) - MilliSatoshi(htlc3.amountMsat) - MilliSatoshi(htlc4.amountMsat) - MilliSatoshi(htlc5.amountMsat)).amount * 1000,
        toRemoteMsat = millibtc2satoshi(MilliBtc(30)).amount * 1000)
      val commitTx = makeCommitTx(commitInput, 42, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, true, localDustLimit, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, spec)
      val (htlcTimeoutTxs, htlcSuccessTxs) = makeHtlcTxs(commitTx.tx, localDustLimit, localRevocationPriv.toPoint, toLocalDelay, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, spec)
      run("with htlcs, some below dust limit", spec, commitTx, htlcTimeoutTxs, htlcSuccessTxs)
    }

  }

}
