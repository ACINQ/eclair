package fr.acinq.protos

import fr.acinq.bitcoin._
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class Bolt3Spec extends FunSuite {
  val (Base58.Prefix.SecretKeyTestnet, localPrivKey) = Base58Check.decode("cVuzKWCszfvjkoJyUasvsrRdECriz8hSd1BDinRNzytwnXmX7m1g")
  val (Base58.Prefix.SecretKeyTestnet, remotePrivKey) = Base58Check.decode("cRUfvpbRtMSqCFD1ADdvgPn5HfRLYuHCFYAr2noWnaRDNger2AoA")
  val localPubKey = Crypto.publicKeyFromPrivateKey(localPrivKey)
  val remotePubKey = Crypto.publicKeyFromPrivateKey(remotePrivKey)

  val (Base58.Prefix.SecretKeyTestnet, revocationPrivKey) = Base58Check.decode("cSupnaiBh6jgTcQf9QANCB5fZtXojxkJQczq5kwfSBeULjNd5Ypo")
  val revocationPubKey = Crypto.publicKeyFromPrivateKey(revocationPrivKey)

  val amount = 42000 satoshi
  val fundingTx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, Script.pay2wsh(Bolt3.fundingScript(localPubKey, remotePubKey))) :: Nil, lockTime = 0)

  val paymentPreimage = Hash.Zeroes
  val paymentHash = Crypto.hash256(paymentPreimage)

  val localDelayedKey = localPubKey
  val paymentPreimage1 = Hash.Zeroes
  val paymentPreimage2 = Hash.One

  val htlcTimeout = 10000
  val selfDelay = 15000

  // create our local commit tx, with an HTLC that we've offered and a HTLC that we've received
  val commitTx = {
    val tx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(fundingTx, 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = Seq(
        TxOut(22000 satoshi, Script.pay2wsh(Bolt3.toLocal(selfDelay, localDelayedKey))),
        TxOut(10000 satoshi, Script.pay2pkh(Bolt3.toRemote(remotePubKey))),
        TxOut(6000 satoshi, Script.pay2wsh(Bolt3.htlcOffered(localPubKey, remotePubKey, Crypto.hash160(paymentPreimage1)))),
        TxOut(4000 satoshi, Script.pay2wsh(Bolt3.htlcReceived(localPubKey, remotePubKey, Crypto.hash160(paymentPreimage2), htlcTimeout)))
      ),
      lockTime = 0)
    val redeemScript: BinaryData = Bolt3.fundingScript(localPubKey, remotePubKey)
    val localSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, fundingTx.txOut(0).amount, SigVersion.SIGVERSION_WITNESS_V0, localPrivKey)
    val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, fundingTx.txOut(0).amount, SigVersion.SIGVERSION_WITNESS_V0, remotePrivKey)
    val witness = ScriptWitness(BinaryData.empty :: localSig :: remoteSig :: redeemScript :: Nil)
    tx.updateWitness(0, witness)
  }

  // create our local HTLC timeout tx for the HTLC that we've offered
  // it is signed by both parties
  val htlcTimeoutTx = {
    val tx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(commitTx, 2), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(6000 satoshi, Script.pay2wsh(Bolt3.htlcSuccessOrTimeout(revocationPubKey, selfDelay, localDelayedKey))) :: Nil,
      lockTime = 0)
    // both parties sign the unsigned tx
    val redeemScript: BinaryData = Script.write(Bolt3.htlcOffered(localPubKey, remotePubKey, Crypto.hash160(paymentPreimage1)))
    val localSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, commitTx.txOut(2).amount, SigVersion.SIGVERSION_WITNESS_V0, localPrivKey)
    val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, commitTx.txOut(2).amount, SigVersion.SIGVERSION_WITNESS_V0, remotePrivKey)
    val witness = ScriptWitness(BinaryData("01") :: BinaryData.empty :: remoteSig :: localSig :: BinaryData.empty :: redeemScript :: Nil)
    tx.updateWitness(0, witness)
  }

  // create our local HTLC success tx for the HTLC that we've received
  // it is signed by both parties and its witness contains the HTLC payment preimage
  val htlcSuccessTx = {
    val tx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(commitTx, 3), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(4000 satoshi, Script.pay2wsh(Bolt3.htlcSuccessOrTimeout(revocationPubKey, selfDelay, localDelayedKey))) :: Nil,
      lockTime = 0)
    // both parties sign the unsigned tx
    val redeemScript: BinaryData = Script.write(Bolt3.htlcReceived(localPubKey, remotePubKey, Crypto.hash160(paymentPreimage2), htlcTimeout))
    val localSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, commitTx.txOut(3).amount, SigVersion.SIGVERSION_WITNESS_V0, localPrivKey)
    val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, commitTx.txOut(3).amount, SigVersion.SIGVERSION_WITNESS_V0, remotePrivKey)
    val witness = ScriptWitness(BinaryData("01") :: BinaryData.empty :: remoteSig :: localSig :: paymentPreimage2 :: redeemScript :: Nil)
    tx.updateWitness(0, witness)
  }

  test("commit tx spends the funding tx") {
    Transaction.correctlySpends(commitTx, fundingTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("HTLC timeout tx spends the commit tx") {
    Transaction.correctlySpends(htlcTimeoutTx, commitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("HTLC success tx spends the commit tx") {
    Transaction.correctlySpends(htlcSuccessTx, commitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("we can claim the offered HTLC after a delay") {
    val spendHtlcTimeout = {
      val tx = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(htlcTimeoutTx, 0), signatureScript = Nil, sequence = selfDelay + 1) :: Nil,
        txOut = TxOut(6000 satoshi, Script.pay2wpkh(localPubKey)) :: Nil,
        lockTime = 0)
      val redeemScript: BinaryData = Script.write(Bolt3.htlcSuccessOrTimeout(revocationPubKey, selfDelay, localDelayedKey))
      val localSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, htlcTimeoutTx.txOut(0).amount, SigVersion.SIGVERSION_WITNESS_V0, localPrivKey)
      val witness = ScriptWitness(localSig :: BinaryData.empty :: redeemScript :: Nil)
      tx.updateWitness(0, witness)
    }
    Transaction.correctlySpends(spendHtlcTimeout, htlcTimeoutTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("we can claim the received HTLC after a delay") {
    val spendHtlcSuccess = {
      val tx = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(htlcSuccessTx, 0), signatureScript = Nil, sequence = selfDelay + 1) :: Nil,
        txOut = TxOut(4000 satoshi, Script.pay2wpkh(localPubKey)) :: Nil,
        lockTime = 0)
      val redeemScript: BinaryData = Script.write(Bolt3.htlcSuccessOrTimeout(revocationPubKey, selfDelay, localDelayedKey))
      val localSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, htlcSuccessTx.txOut(0).amount, SigVersion.SIGVERSION_WITNESS_V0, localPrivKey)
      val witness = ScriptWitness(localSig :: BinaryData.empty :: redeemScript :: Nil)
      tx.updateWitness(0, witness)
    }
    Transaction.correctlySpends(spendHtlcSuccess, htlcSuccessTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("they can spend our HTLC timeout tx immediately if they know the revocation private key") {
    val penaltyTx = {
      val tx = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(htlcTimeoutTx, 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
        txOut = TxOut(6000 satoshi, Script.pay2wpkh(remotePubKey)) :: Nil,
        lockTime = 0)
      val redeemScript: BinaryData = Script.write(Bolt3.htlcSuccessOrTimeout(revocationPubKey, selfDelay, localDelayedKey))
      val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, htlcTimeoutTx.txOut(0).amount, SigVersion.SIGVERSION_WITNESS_V0, revocationPrivKey)
      val witness = ScriptWitness(remoteSig :: BinaryData("01") :: redeemScript :: Nil)
      tx.updateWitness(0, witness)
    }
    Transaction.correctlySpends(penaltyTx, htlcTimeoutTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("they can spend our HTLC success tx immediately if they know the revocation private key") {
    val penaltyTx = {
      val tx = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(htlcSuccessTx, 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
        txOut = TxOut(6000 satoshi, Script.pay2wpkh(remotePubKey)) :: Nil,
        lockTime = 0)
      val redeemScript: BinaryData = Script.write(Bolt3.htlcSuccessOrTimeout(revocationPubKey, selfDelay, localDelayedKey))
      val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, htlcSuccessTx.txOut(0).amount, SigVersion.SIGVERSION_WITNESS_V0, revocationPrivKey)
      val witness = ScriptWitness(remoteSig :: BinaryData("01") :: redeemScript :: Nil)
      tx.updateWitness(0, witness)
    }
    Transaction.correctlySpends(penaltyTx, htlcSuccessTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }
}
