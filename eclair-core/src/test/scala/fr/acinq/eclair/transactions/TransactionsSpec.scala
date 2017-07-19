package fr.acinq.eclair.transactions

import java.nio.ByteOrder

import fr.acinq.bitcoin.Crypto.{PrivateKey, ripemd160, sha256}
import fr.acinq.bitcoin.Script.{pay2wpkh, pay2wsh, write}
import fr.acinq.bitcoin._
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.transactions.Scripts.{htlcOffered, htlcReceived, toLocalDelayed}
import fr.acinq.eclair.transactions.Transactions.{addSigs, _}
import fr.acinq.eclair.wire.UpdateAddHtlc
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.io.Source
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

  test("compute fees") {
    // see BOLT #3 specs
    val htlcs = Set(
      Htlc(OUT, UpdateAddHtlc("00" * 32, 0, MilliSatoshi(5000000).amount, Hash.Zeroes, 552, BinaryData("")), None),
      Htlc(OUT, UpdateAddHtlc("00" * 32, 0, MilliSatoshi(1000000).amount, Hash.Zeroes, 553, BinaryData("")), None),
      Htlc(IN, UpdateAddHtlc("00" * 32, 0, MilliSatoshi(7000000).amount, Hash.Zeroes, 550, BinaryData("")), None),
      Htlc(IN, UpdateAddHtlc("00" * 32, 0, MilliSatoshi(800000).amount, Hash.Zeroes, 551, BinaryData("")), None)
    )
    val spec = CommitmentSpec(htlcs, feeratePerKw = 5000, toLocalMsat = 0, toRemoteMsat = 0)
    val fee = Transactions.commitTxFee(Satoshi(546), spec)
    assert(fee == Satoshi(5340))
  }

  test("check pre-computed transaction weights") {
    val localRevocationPriv = PrivateKey(BinaryData("cc" * 32), compressed = true)
    val localPaymentPriv = PrivateKey(BinaryData("dd" * 32), compressed = true)
    val remotePaymentPriv = PrivateKey(BinaryData("ee" * 32), compressed = true)
    val localFinalPriv = PrivateKey(BinaryData("ff" * 32), compressed = true)
    val finalPubKeyScript = Script.write(Script.pay2wpkh(PrivateKey(BinaryData("fe" * 32), compressed = true).publicKey))
    val toLocalDelay = 144
    val feeratePerKw = 1000

    {
      // ClaimP2WPKHOutputTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimP2WPKHOutputTx
      val pubKeyScript = write(pay2wpkh(localPaymentPriv.publicKey))
      val commitTx = Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(20000), pubKeyScript) :: Nil, lockTime = 0)
      val claimP2WPKHOutputTx = makeClaimP2WPKHOutputTx(commitTx, localPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(claimP2WPKHOutputTx, localPaymentPriv.publicKey, "bb" * 71).tx)
      assert(claimP2WPKHOutputWeight == weight)
    }

    {
      // ClaimHtlcDelayedTx
      // first we create a fake htlcSuccessOrTimeoutTx tx, containing only the output that will be spent by the ClaimDelayedOutputTx
      val pubKeyScript = write(pay2wsh(toLocalDelayed(localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey)))
      val htlcSuccessOrTimeoutTx = Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(20000), pubKeyScript) :: Nil, lockTime = 0)
      val claimHtlcDelayedTx = makeClaimDelayedOutputTx(htlcSuccessOrTimeoutTx, localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(claimHtlcDelayedTx, "bb" * 71).tx)
      assert(claimHtlcDelayedWeight == weight)
    }

    {
      // MainPenaltyTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the MainPenaltyTx
      val pubKeyScript = write(pay2wsh(toLocalDelayed(localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey)))
      val commitTx = Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(20000), pubKeyScript) :: Nil, lockTime = 0)
      val mainPenaltyTx = makeMainPenaltyTx(commitTx, localRevocationPriv.publicKey, finalPubKeyScript, toLocalDelay, localPaymentPriv.publicKey, feeratePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(mainPenaltyTx, "bb" * 71).tx)
      assert(mainPenaltyWeight == weight)
    }

    {
      // ClaimHtlcSuccessTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimHtlcSuccessTx
      val paymentPreimage = BinaryData("42" * 32)
      val htlc = UpdateAddHtlc("00" * 32, 0, Satoshi(20000).amount * 1000, sha256(paymentPreimage), expiry = 400144, BinaryData(""))
      val pubKeyScript = write(pay2wsh(htlcOffered(localPaymentPriv.publicKey, remotePaymentPriv.publicKey, localRevocationPriv.publicKey, ripemd160(htlc.paymentHash))))
      val commitTx = Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(htlc.amountMsat / 1000), pubKeyScript) :: Nil, lockTime = 0)
      val claimHtlcSuccessTx = makeClaimHtlcSuccessTx(commitTx, remotePaymentPriv.publicKey, localPaymentPriv.publicKey, localRevocationPriv.publicKey, finalPubKeyScript, htlc, feeratePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(claimHtlcSuccessTx, "bb" * 71, paymentPreimage).tx)
      assert(claimHtlcSuccessWeight == weight)
    }

    {
      // ClaimHtlcTimeoutTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimHtlcSuccessTx
      val paymentPreimage = BinaryData("42" * 32)
      val htlc = UpdateAddHtlc("00" * 32, 0, Satoshi(20000).amount * 1000, sha256(paymentPreimage), expiry = 400144, BinaryData(""))
      val pubKeyScript = write(pay2wsh(htlcReceived(localPaymentPriv.publicKey, remotePaymentPriv.publicKey, localRevocationPriv.publicKey, ripemd160(htlc.paymentHash), htlc.expiry)))
      val commitTx = Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(htlc.amountMsat / 1000), pubKeyScript) :: Nil, lockTime = 0)
      val claimClaimHtlcTimeoutTx = makeClaimHtlcTimeoutTx(commitTx, remotePaymentPriv.publicKey, localPaymentPriv.publicKey, localRevocationPriv.publicKey, finalPubKeyScript, htlc, feeratePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(claimClaimHtlcTimeoutTx, "bb" * 71).tx)
      assert(claimHtlcTimeoutWeight == weight)
    }
  }

  test("generate valid commitment and htlc transactions") {
    val localFundingPriv = PrivateKey(BinaryData("aa" * 32) :+ 1.toByte)
    val remoteFundingPriv = PrivateKey(BinaryData("bb" * 32) :+ 1.toByte)
    val localRevocationPriv = PrivateKey(BinaryData("cc" * 32) :+ 1.toByte)
    val localPaymentPriv = PrivateKey(BinaryData("dd" * 32) :+ 1.toByte)
    val remotePaymentPriv = PrivateKey(BinaryData("ee" * 32) :+ 1.toByte)
    val finalPubKeyScript = Script.write(Script.pay2wpkh(PrivateKey(BinaryData("ee" * 32), true).publicKey))
    val commitInput = Funding.makeFundingInputInfo(BinaryData("12" * 32), 0, Btc(1), localFundingPriv.publicKey, remoteFundingPriv.publicKey)
    val toLocalDelay = 144
    val localDustLimit = Satoshi(542)
    val feeratePerKw = 1000

    val paymentPreimage1 = BinaryData("11" * 32)
    val htlc1 = UpdateAddHtlc("00" * 32, 0, millibtc2satoshi(MilliBtc(100)).amount * 1000, sha256(paymentPreimage1), 300, BinaryData(""))
    val paymentPreimage2 = BinaryData("22" * 32)
    val htlc2 = UpdateAddHtlc("00" * 32, 1, millibtc2satoshi(MilliBtc(200)).amount * 1000, sha256(paymentPreimage2), 300, BinaryData(""))
    val spec = CommitmentSpec(
      htlcs = Set(
        Htlc(OUT, htlc1, None),
        Htlc(IN, htlc2, None)
      ),
      feeratePerKw = feeratePerKw,
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
      assert(getCommitTxNumber(commitTx.tx, true, localPaymentPriv.publicKey, remotePaymentPriv.publicKey) == commitTxNumber)
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
      val foo = checkSpendable(signed)
      assert(checkSpendable(signed).isSuccess)
    }

    {
      // local spends delayed output of htlc timeout tx
      val htlcTimeoutTx = htlcTimeoutTxs(0)
      val claimHtlcDelayed = makeClaimDelayedOutputTx(htlcTimeoutTx.tx, localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val localSig = sign(claimHtlcDelayed, localPaymentPriv)
      val signedTx = addSigs(claimHtlcDelayed, localSig)
      assert(checkSpendable(signedTx).isSuccess)
    }

    {
      // remote spends local->remote htlc output directly in case of success
      val claimHtlcSuccessTx = makeClaimHtlcSuccessTx(commitTx.tx, remotePaymentPriv.publicKey, localPaymentPriv.publicKey, localRevocationPriv.publicKey, finalPubKeyScript, htlc1, feeratePerKw)
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
      val claimHtlcDelayed = makeClaimDelayedOutputTx(htlcSuccessTx.tx, localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val localSig = sign(claimHtlcDelayed, localPaymentPriv)
      val signedTx = addSigs(claimHtlcDelayed, localSig)
      assert(checkSpendable(signedTx).isSuccess)
    }

    {
      // remote spends main output
      val claimP2WPKHOutputTx = makeClaimP2WPKHOutputTx(commitTx.tx, remotePaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val localSig = sign(claimP2WPKHOutputTx, remotePaymentPriv)
      val signedTx = addSigs(claimP2WPKHOutputTx, remotePaymentPriv.publicKey, localSig)
      assert(checkSpendable(signedTx).isSuccess)
    }

    {
      // remote spends remote->local htlc output directly in case of timeout
      val claimHtlcTimeoutTx = makeClaimHtlcTimeoutTx(commitTx.tx, remotePaymentPriv.publicKey, localPaymentPriv.publicKey, localRevocationPriv.publicKey, finalPubKeyScript, htlc2, feeratePerKw)
      val localSig = sign(claimHtlcTimeoutTx, remotePaymentPriv)
      val signed = addSigs(claimHtlcTimeoutTx, localSig)
      assert(checkSpendable(signed).isSuccess)
    }

    {
      // remote spends offered HTLC output with revocation key
      val script = Scripts.htlcOffered(localPaymentPriv.publicKey, remotePaymentPriv.publicKey, localRevocationPriv.publicKey, Crypto.ripemd160(htlc1.paymentHash))
      val index = commitTx.tx.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wsh(script)))
      val tx = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(commitTx.tx, index), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
        txOut = TxOut(commitTx.tx.txOut(index).amount, Script.pay2wpkh(remotePaymentPriv.publicKey)) :: Nil,
        lockTime = 0)
      val sig = Transaction.signInput(tx, 0, script, SIGHASH_ALL, commitTx.tx.txOut(index).amount, SigVersion.SIGVERSION_WITNESS_V0, localRevocationPriv)
      val tx1 = tx.updateWitness(0, ScriptWitness(sig :: localRevocationPriv.publicKey.toBin :: Script.write(script) :: Nil))
      Transaction.correctlySpends(tx1, Seq(commitTx.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    {
      // remote spends received HTLC output with revocation key
      val script = Scripts.htlcReceived(localPaymentPriv.publicKey, remotePaymentPriv.publicKey, localRevocationPriv.publicKey, Crypto.ripemd160(htlc2.paymentHash), htlc2.expiry)
      val index = commitTx.tx.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wsh(script)))
      val tx = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(commitTx.tx, index), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
        txOut = TxOut(commitTx.tx.txOut(index).amount, Script.pay2wpkh(remotePaymentPriv.publicKey)) :: Nil,
        lockTime = 0)
      val sig = Transaction.signInput(tx, 0, script, SIGHASH_ALL, commitTx.tx.txOut(index).amount, SigVersion.SIGVERSION_WITNESS_V0, localRevocationPriv)
      val tx1 = tx.updateWitness(0, ScriptWitness(sig :: localRevocationPriv.publicKey.toBin :: Script.write(script) :: Nil))
      Transaction.correctlySpends(tx1, Seq(commitTx.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

  }

  def checkSuccessOrFailTest[T](input: Try[T]) = input match {
    case Success(_) => ()
    case Failure(t) => fail(t)
  }

  def htlc(direction: Direction, amount: Satoshi): Htlc =
    Htlc(direction, UpdateAddHtlc("00" * 32, 0, amount.amount * 1000, "00" * 32, 144, ""), None)

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
      println(s"running BOLT 2 test: '${test.name}'")
      val fee = commitTxFee(test.dustLimit, test.spec)
      assert(fee === test.expectedFee)
    })
  }
}
