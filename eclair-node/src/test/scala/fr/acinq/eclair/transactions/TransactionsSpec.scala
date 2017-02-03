package fr.acinq.eclair.transactions

import java.nio.ByteOrder

import fr.acinq.bitcoin.Crypto.{PrivateKey, Scalar, sha256}
import fr.acinq.bitcoin.Script.{pay2wpkh, pay2wsh, write}
import fr.acinq.bitcoin.{BinaryData, Btc, Crypto, MilliBtc, MilliSatoshi, Protocol, Satoshi, Script, Transaction, TxOut, millibtc2satoshi}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel.LocalParams
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.transactions.Scripts.toLocalDelayed
import fr.acinq.eclair.transactions.Transactions.{addSigs, _}
import fr.acinq.eclair.wire.UpdateAddHtlc
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.util.{Failure, Random, Success, Try}

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

  test("check pre-computed transaction weights") {
    val localRevocationPriv = PrivateKey(BinaryData("cc" * 32), compressed = true)
    val localPaymentPriv = PrivateKey(BinaryData("dd" * 32), compressed = true)
    val remotePaymentPriv = PrivateKey(BinaryData("ee" * 32), compressed = true)
    val localFinalPriv = PrivateKey(BinaryData("ff" * 32), compressed = true)
    val finalPubKeyScript = Script.pay2wpkh(PrivateKey(BinaryData("ff" * 32), compressed = true).publicKey)
    val toLocalDelay = 144
    val feeRatePerKw = 1000

    {
      // ClaimP2WPKHOutputTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimP2WPKHOutputTx
      val pubKeyScript = write(pay2wpkh(localPaymentPriv.publicKey))
      val commitTx = Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(20000), pubKeyScript) :: Nil, lockTime = 0)
      val claimP2WPKHOutputTx = makeClaimP2WPKHOutputTx(commitTx, localPaymentPriv.publicKey, finalPubKeyScript, feeRatePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(claimP2WPKHOutputTx, localPaymentPriv.publicKey, "bb" * 71).tx)
      assert(claimP2WPKHOutputWeight == weight)
    }

    {
      // ClaimHtlcDelayedTx
      // first we create a fake htlcSuccessOrTimeoutTx tx, containing only the output that will be spent by the ClaimDelayedOutputTx
      val pubKeyScript = write(pay2wsh(toLocalDelayed(localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey)))
      val htlcSuccessOrTimeoutTx = Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(20000), pubKeyScript) :: Nil, lockTime = 0)
      val claimHtlcDelayedTx = makeClaimDelayedOutputTx(htlcSuccessOrTimeoutTx, localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey, finalPubKeyScript, feeRatePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(claimHtlcDelayedTx, "bb" * 71).tx)
      assert(claimHtlcDelayedWeight == weight)
    }

    {
      // MainPunishmentTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the MainPunishmentTx
      val pubKeyScript = write(pay2wsh(toLocalDelayed(localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey)))
      val commitTx = Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(20000), pubKeyScript) :: Nil, lockTime = 0)
      val mainPunishmentTx = makeMainPunishmentTx(commitTx, localRevocationPriv.publicKey, finalPubKeyScript, toLocalDelay, localPaymentPriv.publicKey, feeRatePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(mainPunishmentTx, "bb" * 71).tx)
      assert(mainPunishmentWeight == weight)
    }
  }

  test("generate valid commitment and htlc transactions") {
    val localFundingPriv = PrivateKey(BinaryData("aa" * 32) :+ 1.toByte)
    val remoteFundingPriv = PrivateKey(BinaryData("bb" * 32) :+ 1.toByte)
    val localRevocationPriv = PrivateKey(BinaryData("cc" * 32) :+ 1.toByte)
    val localPaymentPriv = PrivateKey(BinaryData("dd" * 32) :+ 1.toByte)
    val remotePaymentPriv = PrivateKey(BinaryData("ee" * 32) :+ 1.toByte)
    val finalPubKeyScript = Script.pay2wpkh(PrivateKey(BinaryData("ee" * 32), true).publicKey)
    val commitInput = Funding.makeFundingInputInfo(BinaryData("12" * 32), 0, Btc(1), localFundingPriv.publicKey, remoteFundingPriv.publicKey)
    val toLocalDelay = 144
    val localDustLimit = Satoshi(542)
    val feeRatePerKw = 1000

    val paymentPreimage1 = BinaryData("11" * 32)
    val htlc1 = UpdateAddHtlc(0, 0, millibtc2satoshi(MilliBtc(100)).amount * 1000, 300, sha256(paymentPreimage1), BinaryData(""))
    val paymentPreimage2 = BinaryData("22" * 32)
    val htlc2 = UpdateAddHtlc(0, 1, millibtc2satoshi(MilliBtc(200)).amount * 1000, 300, sha256(paymentPreimage2), BinaryData(""))
    val spec = CommitmentSpec(
      htlcs = Set(
        Htlc(OUT, htlc1, None),
        Htlc(IN, htlc2, None)
      ),
      feeRatePerKw = feeRatePerKw,
      toLocalMsat = millibtc2satoshi(MilliBtc(400)).amount * 1000,
      toRemoteMsat = millibtc2satoshi(MilliBtc(300)).amount * 1000)

    val commitTxNumber = 0x404142434445L
    val commitTx = {
      val txinfo = makeCommitTx(commitInput, commitTxNumber, localPaymentPriv.toPoint, remotePaymentPriv.toPoint, true, localDustLimit, localPaymentPriv.publicKey, localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey, remotePaymentPriv.publicKey, spec)
      val localSig = Transactions.sign(txinfo, localPaymentPriv)
      val remoteSig = Transactions.sign(txinfo, remotePaymentPriv)
      Transactions.addSigs(txinfo, localFundingPriv.publicKey, remoteFundingPriv.publicKey, localSig, remoteSig)
    }

    {
      assert(getCommitTxNumber(commitTx.tx, localPaymentPriv.publicKey, remotePaymentPriv.publicKey) == commitTxNumber)
      val hash: Array[Byte] = Crypto.sha256(localPaymentPriv.publicKey.toBin ++ remotePaymentPriv.publicKey.toBin)
      val num = Protocol.uint64(hash.takeRight(8), ByteOrder.BIG_ENDIAN) & 0xffffffffffffL
      val check = ((commitTx.tx.txIn(0).sequence & 0xffffff) << 24) | (commitTx.tx.lockTime)
      assert((check ^ num) == commitTxNumber)
    }
    val (htlcTimeoutTxs, htlcSuccessTxs) = makeHtlcTxs(commitTx.tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey, localPaymentPriv.publicKey, remotePaymentPriv.publicKey, spec)

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
      val claimHtlcDelayed = makeClaimDelayedOutputTx(htlcTimeoutTx.tx, localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey, finalPubKeyScript, feeRatePerKw)
      val localSig = sign(claimHtlcDelayed, localPaymentPriv)
      val signedTx = addSigs(claimHtlcDelayed, localSig)
      assert(checkSpendable(signedTx).isSuccess)
    }

    {
      // remote spends local->remote htlc output directly in case of success
      val claimHtlcSuccessTx = makeClaimHtlcSuccessTx(commitTx.tx, remotePaymentPriv.publicKey, localPaymentPriv.publicKey, finalPubKeyScript, htlc1)
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
      assert(checkSig(htlcSuccessTx, remoteSig, remotePaymentPriv.publicKey))
    }

    {
      // local spends delayed output of htlc success tx
      val htlcSuccessTx = htlcSuccessTxs(0)
      val claimHtlcDelayed = makeClaimDelayedOutputTx(htlcSuccessTx.tx, localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey, finalPubKeyScript, feeRatePerKw)
      val localSig = sign(claimHtlcDelayed, localPaymentPriv)
      val signedTx = addSigs(claimHtlcDelayed, localSig)
      assert(checkSpendable(signedTx).isSuccess)
    }

    {
      // remote spends main output
      val claimP2WPKHOutputTx = makeClaimP2WPKHOutputTx(commitTx.tx, remotePaymentPriv.publicKey, finalPubKeyScript, feeRatePerKw)
      val localSig = sign(claimP2WPKHOutputTx, remotePaymentPriv)
      val signedTx = addSigs(claimP2WPKHOutputTx, remotePaymentPriv.publicKey, localSig)
      assert(checkSpendable(signedTx).isSuccess)
    }

    {
      // remote spends remote->local htlc output directly in case of timeout
      val claimHtlcTimeoutTx = makeClaimHtlcTimeoutTx(commitTx.tx, remotePaymentPriv.publicKey, localPaymentPriv.publicKey, finalPubKeyScript, htlc2)
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

  def checkSuccessOrFailTest[T](input: Try[T]) = input match {
    case Success(_) => ()
    case Failure(t) => fail(t)
  }

  test("BOLT 3 test vectors") {

    object Local {
      val commitTxNumber = 42

      val params = LocalParams(
        dustLimitSatoshis = Satoshi(546).toLong,
        maxHtlcValueInFlightMsat = 50,
        channelReserveSatoshis = 10000,
        htlcMinimumMsat = 50000,
        feeratePerKw = 15000,
        toSelfDelay = 144,
        maxAcceptedHtlcs = 50,
        fundingPrivKey = PrivateKey(BinaryData("01" * 32) :+ 1.toByte),
        revocationSecret = Scalar(BinaryData("02" * 32)),
        paymentKey = PrivateKey(BinaryData("03" * 32) :+ 1.toByte),
        delayedPaymentKey = Scalar(BinaryData("04" * 32)),
        defaultFinalScriptPubKey = Nil,
        shaSeed = BinaryData("05" * 32),
        isFunder = true)

      val commitmentInput = Funding.makeFundingInputInfo(BinaryData("42a26bb3a430a536cf9e3a8ce2cbcb0427c29ec6c7d647175cfc78328b57fba7"), 1, MilliBtc(100), params.fundingPrivKey.publicKey, params.fundingPrivKey.publicKey)
    }

    object Remote {
      val commitTxNumber = 10

      val params = LocalParams(
        dustLimitSatoshis = Satoshi(546).toLong,
        maxHtlcValueInFlightMsat = 50,
        channelReserveSatoshis = 10000,
        htlcMinimumMsat = 50000,
        feeratePerKw = 15000,
        toSelfDelay = 144,
        maxAcceptedHtlcs = 50,
        fundingPrivKey = PrivateKey(BinaryData("06" * 32) :+ 1.toByte),
        revocationSecret = Scalar(BinaryData("07" * 32)),
        paymentKey = PrivateKey(BinaryData("08" * 32) :+ 1.toByte),
        delayedPaymentKey = Scalar(BinaryData("09" * 32)),
        defaultFinalScriptPubKey = Nil,
        shaSeed = BinaryData("0a" * 32),
        isFunder = false)

      val commitmentInput = Funding.makeFundingInputInfo(BinaryData("42a26bb3a430a536cf9e3a8ce2cbcb0427c29ec6c7d647175cfc78328b57fba7"), 1, MilliBtc(100), params.fundingPrivKey.publicKey, params.fundingPrivKey.publicKey)
    }


    def printParams(name: String, params: LocalParams): Unit = {
      println(s"$name funding private key ${params.fundingPrivKey}")
      println(s"$name funding key ${params.fundingPrivKey.publicKey}")
      println(s"$name payment key ${params.paymentKey}")
      println(s"$name delayed payment key ${params.delayedPaymentKey}")
      println(s"$name self delay ${params.toSelfDelay}")
      println(s"$name shachain seed ${params.shaSeed}")
      println(s"$name fee rate per kw ${params.feeratePerKw}")
      println(s"$name dust limit ${params.dustLimitSatoshis}")
    }

    def printSpec(name: String, spec: CommitmentSpec): Unit = {
      println(s"name: $name")
      println(s"to_local_msat: ${spec.toLocalMsat}")
      println(s"to_remote_msat: ${spec.toRemoteMsat}")
      spec.htlcs.toSeq.zipWithIndex.foreach {
        case (htlc, i) =>
          println(s"    htlc $i direction: ${if (htlc.direction == OUT) "local->remote" else "remote->local"}")
          println(s"    htlc $i amount_msat: ${htlc.add.amountMsat}")
          println(s"    htlc $i expiry: ${htlc.add.expiry}")
          println(s"    htlc $i payment_hash: ${htlc.add.paymentHash}")
      }
    }

    def printTx(name: String, commitTx: CommitTx, htlcTimeoutTxs: Seq[HtlcTimeoutTx], htlcSuccessTxs: Seq[HtlcSuccessTx]): Unit = {
      println()
      println(s"output commit_tx: ${Transaction.write(commitTx.tx)}")

      htlcTimeoutTxs.zipWithIndex.foreach {
        case (tx, i) => println(s"    output htlc_timeout_tx $i: ${Transaction.write(tx.tx)}")
      }
      htlcSuccessTxs.zipWithIndex.foreach {
        case (tx, i) => println(s"    output htlc_success_tx $i: ${Transaction.write(tx.tx)}")
      }
    }

    println("# local parameters")
    printParams("local", Local.params)

    println("# remote parameters")
    printParams("remote", Remote.params)
    println()

    def computeKeys = {
      println(s"# compute the local per commitment point")
      val perCommitmentPoint = Generators.perCommitPoint(Local.params.shaSeed, Local.commitTxNumber)
      println(s"shachain seed: ${Local.params.shaSeed}")
      println(s"commit tx number: ${Local.commitTxNumber}")
      println(s"local per commitment point: $perCommitmentPoint")

      println(s"# compute the local key from the local payment basepoint and the local per commitment point")
      val localPubkey = Generators.derivePubKey(Local.params.paymentKey.toPoint, perCommitmentPoint)
      println(s"local payment basepoint: ${Local.params.paymentKey.toPoint}")
      println(s"local per commitment point: ${perCommitmentPoint}")
      println(s"local key: ${localPubkey}")

      println(s"# compute the local delayed key from the local delayed payment basepoint and the local per commitment point")
      val localDelayedPubkey = Generators.derivePubKey(Local.params.delayedPaymentKey.toPoint, perCommitmentPoint)
      println(s"local delayed payment basepoint: ${Local.params.delayedPaymentKey.toPoint}")
      println(s"local per commitment point: ${perCommitmentPoint}")
      println(s"local delayed key: ${localDelayedPubkey}")

      println(s"# compute the remote key from the remote payement basepoint and the local per commitment point")
      val remotePubkey = Generators.derivePubKey(Remote.params.paymentKey.toPoint, perCommitmentPoint)
      println(s"remote payment basepoint: ${Remote.params.paymentKey.toPoint}")
      println(s"local per commitment point: ${perCommitmentPoint}")
      println(s"remote key: ${remotePubkey}")

      println(s"# compute the local revocation key from the remote revocation basepoint and the local per commitment point")
      val localRevocationPubkey = Generators.revocationPubKey(Remote.params.revocationSecret.toPoint, perCommitmentPoint)
      println(s"remote revocation basepoint: ${Remote.params.revocationSecret.toPoint}")
      println(s"local per commitment point: ${perCommitmentPoint}")
      println(s"local revocation key: ${localRevocationPubkey}")
      (perCommitmentPoint, localPubkey, localDelayedPubkey, remotePubkey, localRevocationPubkey)
    }


    {
      val spec = CommitmentSpec(
        htlcs = Set.empty,
        feeRatePerKw = Local.params.feeratePerKw,
        toLocalMsat = millibtc2satoshi(MilliBtc(70)).amount * 1000,
        toRemoteMsat = millibtc2satoshi(MilliBtc(30)).amount * 1000)
      printSpec("simple tx with two outputs", spec)

      val (perCommitmentPoint, localKey, localDelayedPubkey, remotePubkey, localRevocationPubkey) = computeKeys

      val commitTx = Transactions.makeCommitTx(Local.commitmentInput, Local.commitTxNumber, Local.params.paymentKey.toPoint, Remote.params.paymentKey.toPoint, Local.params.isFunder, Satoshi(Local.params.dustLimitSatoshis), localKey, localRevocationPubkey, Local.params.toSelfDelay, localDelayedPubkey, remotePubkey, spec)
      assert(getCommitTxNumber(commitTx.tx, Local.params.paymentKey.toPoint, Remote.params.paymentKey.toPoint) === Local.commitTxNumber)

      val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, Satoshi(Local.params.dustLimitSatoshis), localRevocationPubkey, Local.params.toSelfDelay, localKey, localDelayedPubkey, remotePubkey, spec)
      printTx("simple tx with two outputs", commitTx, htlcTimeoutTxs, htlcSuccessTxs)
      println
    }

    {
      val spec = CommitmentSpec(
        htlcs = Set.empty,
        feeRatePerKw = Local.params.feeratePerKw,
        toLocalMsat = (MilliBtc(100) - Satoshi(1000)).amount * 1000,
        toRemoteMsat = Satoshi(1000).amount * 1000)
      printSpec("two outputs with fundee below dust limit", spec)

      val (perCommitmentPoint, localKey, localDelayedPubkey, remotePubkey, localRevocationPubkey) = computeKeys

      val commitTx = Transactions.makeCommitTx(Local.commitmentInput, Local.commitTxNumber, Local.params.paymentKey.toPoint, Remote.params.paymentKey.toPoint, Local.params.isFunder, Satoshi(Local.params.dustLimitSatoshis), localKey, localRevocationPubkey, Local.params.toSelfDelay, localDelayedPubkey, remotePubkey, spec)
      assert(getCommitTxNumber(commitTx.tx, Local.params.paymentKey.toPoint, Remote.params.paymentKey.toPoint) === Local.commitTxNumber)

      val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, Satoshi(Local.params.dustLimitSatoshis), localRevocationPubkey, Local.params.toSelfDelay, localKey, localDelayedPubkey, remotePubkey, spec)
      printTx("two outputs with fundee below dust limit", commitTx, htlcTimeoutTxs, htlcSuccessTxs)
      println
    }

    {
      // with htlcs, all above dust limit
      val paymentPreimage1 = BinaryData("f1a90faf44857d6c6b83dba3d27259bc0c9edb7215d21d2d5d9e7b84a9a555ab")
      val htlc1 = UpdateAddHtlc(0, 0, MilliSatoshi(40000000).amount, 443210, sha256(paymentPreimage1), BinaryData(""))
      val paymentPreimage2 = BinaryData("5bd0169de7a4a90c0b2b44a6f7c93860ac96630f60e40252fdd0e9f4758bc4ed")
      val htlc2 = UpdateAddHtlc(0, 1, MilliSatoshi(20000000).amount, 453203, sha256(paymentPreimage2), BinaryData(""))
      val spec = CommitmentSpec(
        htlcs = Set(
          Htlc(OUT, htlc1, None),
          Htlc(IN, htlc2, None)
        ),
        feeRatePerKw = Local.params.feeratePerKw,
        toLocalMsat = (MilliBtc(100) - MilliBtc(30) - MilliSatoshi(htlc1.amountMsat) - MilliSatoshi(htlc2.amountMsat)).amount * 1000,
        toRemoteMsat = millibtc2satoshi(MilliBtc(30)).amount * 1000)
      printSpec("twith htlcs, all above dust limit", spec)

      val (perCommitmentPoint, localPubkey, localDelayedPubkey, remotePubkey, localRevocationPubkey) = computeKeys

      val commitTx = Transactions.makeCommitTx(Local.commitmentInput, Local.commitTxNumber, Local.params.paymentKey.toPoint, Remote.params.paymentKey.toPoint, Local.params.isFunder, Satoshi(Local.params.dustLimitSatoshis), localPubkey, localRevocationPubkey, Local.params.toSelfDelay, localDelayedPubkey, remotePubkey, spec)
      assert(getCommitTxNumber(commitTx.tx, Local.params.paymentKey.toPoint, Remote.params.paymentKey.toPoint) === Local.commitTxNumber)

      val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, Satoshi(Local.params.dustLimitSatoshis), localRevocationPubkey, Local.params.toSelfDelay, localPubkey, localDelayedPubkey, remotePubkey, spec)
      printTx("with htlcs, all above dust limit", commitTx, htlcTimeoutTxs, htlcSuccessTxs)
      println
    }


    {
      // with htlcs, some below dust limit
      val paymentPreimage1 = BinaryData("257d67d518b1f1a840096a8eaa9c6351a9296d01240f8edd694ad329a10ca019")
      val htlc1 = UpdateAddHtlc(0, 0, MilliSatoshi(40000000).amount, 443210, sha256(paymentPreimage1), BinaryData(""))
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
        feeRatePerKw = Local.params.feeratePerKw,
        toLocalMsat = (MilliBtc(100) - MilliBtc(30) - MilliSatoshi(htlc1.amountMsat) - MilliSatoshi(htlc2.amountMsat) - MilliSatoshi(htlc3.amountMsat) - MilliSatoshi(htlc4.amountMsat) - MilliSatoshi(htlc5.amountMsat)).amount * 1000,
        toRemoteMsat = millibtc2satoshi(MilliBtc(30)).amount * 1000)
      printSpec("with htlcs, some below dust limit", spec)


      val (perCommitmentPoint, localPubkey, localDelayedPubkey, remotePubkey, localRevocationPubkey) = computeKeys

      val commitTx = Transactions.makeCommitTx(Local.commitmentInput, Local.commitTxNumber, Local.params.paymentKey.toPoint, Remote.params.paymentKey.toPoint, Local.params.isFunder, Satoshi(Local.params.dustLimitSatoshis), localPubkey, localRevocationPubkey, Local.params.toSelfDelay, localDelayedPubkey, remotePubkey, spec)
      assert(getCommitTxNumber(commitTx.tx, Local.params.paymentKey.toPoint, Remote.params.paymentKey.toPoint) === Local.commitTxNumber)

      val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, Satoshi(Local.params.dustLimitSatoshis), localRevocationPubkey, Local.params.toSelfDelay, localPubkey, localDelayedPubkey, remotePubkey, spec)
      printTx("with htlcs, some below dust limit", commitTx, htlcTimeoutTxs, htlcSuccessTxs)
      println
    }
  }
}
