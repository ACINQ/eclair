package fr.acinq.protos

import fr.acinq.bitcoin._

/**
  * Created by fabrice on 21/11/16.
  */
object Bolt3 extends App {
  // Alice = local, Bob = remote
  val (_, localPrivKey) = Base58Check.decode("cVuzKWCszfvjkoJyUasvsrRdECriz8hSd1BDinRNzytwnXmX7m1g")
  val (_, remotePrivKey) = Base58Check.decode("cRUfvpbRtMSqCFD1ADdvgPn5HfRLYuHCFYAr2noWnaRDNger2AoA")
  val localPubKey = Crypto.publicKeyFromPrivateKey(localPrivKey)
  val remotePubKey = Crypto.publicKeyFromPrivateKey(remotePrivKey)

  def fundingScript(pubKey1: BinaryData, pubKey2: BinaryData) = Script.pay2wsh(Script.createMultiSigMofN(2, pubKey1.toArray :: pubKey2.toArray :: Nil))

  def toLocal(delay: Long, localDelayedKey: BinaryData) = Script.pay2wsh(OP_PUSHDATA(Script.encodeNumber(delay)) :: OP_CHECKSEQUENCEVERIFY :: OP_DROP :: OP_PUSHDATA(localDelayedKey) :: OP_CHECKSIG :: Nil)

  def toRemote(remoteKey: BinaryData) = Script.pay2pkh(remoteKey)

  def htlcOffered(localKey: BinaryData, remoteKey: BinaryData, paymentHash: BinaryData) = {
    // @formatter:off
    OP_PUSHDATA(remoteKey) :: OP_SWAP ::
    OP_SIZE :: OP_PUSHDATA(Script.encodeNumber(32)) :: OP_EQUAL ::
    OP_NOTIF ::
      OP_DROP :: OP_2 :: OP_SWAP :: OP_PUSHDATA(localKey) :: OP_2 :: OP_CHECKMULTISIGVERIFY ::
    OP_ELSE ::
      OP_HASH160 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
      OP_CHECKSIGVERIFY ::
    OP_ENDIF :: Nil
    // @formatter:on
  }

  def htlcReceived(localKey: BinaryData, remoteKey: BinaryData, paymentHash: BinaryData, lockTime: Long) = {
    // @formatter:off
    OP_PUSHDATA(remoteKey) :: OP_SWAP ::
    OP_SIZE :: OP_PUSHDATA(Script.encodeNumber(32)) :: OP_EQUAL ::
    OP_IF ::
      OP_HASH160 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
      OP_2 :: OP_SWAP :: OP_PUSHDATA(localKey) :: OP_2 :: OP_CHECKMULTISIGVERIFY ::
    OP_ELSE ::
      OP_DROP :: OP_PUSHDATA(Script.encodeNumber(lockTime)) :: OP_CHECKLOCKTIMEVERIFY :: OP_DROP :: OP_CHECKSIGVERIFY ::
    OP_ENDIF :: Nil
    // @formatter:on
  }

  def htlcSuccessOrTimeout(revocationPubKey: BinaryData, toSelfDelay: Long, localDelayedKey: BinaryData) = {
    // @formatter:off
    OP_IF ::
      OP_PUSHDATA(revocationPubKey) ::
    OP_ELSE ::
      OP_PUSHDATA(Script.encodeNumber(toSelfDelay)) :: OP_CHECKSEQUENCEVERIFY :: OP_DROP ::
      OP_PUSHDATA(localDelayedKey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  val amount = 42000 satoshi
  val fundingTx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, fundingScript(localPubKey, remotePubKey)) :: Nil, lockTime = 0)

  val paymentPreimage = Hash.Zeroes
  val paymentHash = Crypto.hash256(paymentPreimage)

  val localDelayedKey = localPubKey
  val paymentPreimage1 = Hash.Zeroes
  val paymentPreimage2 = Hash.One

  val htlcTimeout = 10000 // we use the same t/o for offered and received htlcs

  val commitTx = Transaction(
    version = 2,
    txIn = TxIn(OutPoint(fundingTx, 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
    txOut = Seq(
      TxOut(22000 satoshi, toLocal(15000, localDelayedKey)),
      TxOut(10000 satoshi, toRemote(remotePubKey)),
      TxOut(6000 satoshi, Script.pay2wsh(htlcOffered(localPubKey, remotePubKey, Crypto.hash160(paymentPreimage1)))),
      TxOut(4000 satoshi, Script.pay2wsh(htlcReceived(localPubKey, remotePubKey, Crypto.hash160(paymentPreimage2), htlcTimeout)))
    ),
    lockTime = 0)

  // Both sides sign the commit tx
  val signedCommitTx = {
    val redeemScript: BinaryData = Script.createMultiSigMofN(2, localPubKey :: remotePubKey :: Nil)

    val localSig: BinaryData = Transaction.signInput(commitTx, 0, redeemScript, SIGHASH_ALL, fundingTx.txOut(0).amount, SigVersion.SIGVERSION_WITNESS_V0, localPrivKey)
    val remoteSig: BinaryData = Transaction.signInput(commitTx, 0, redeemScript, SIGHASH_ALL, fundingTx.txOut(0).amount, SigVersion.SIGVERSION_WITNESS_V0, remotePrivKey)
    val witness = ScriptWitness(BinaryData.empty :: localSig :: remoteSig :: redeemScript :: Nil)
    commitTx.updateWitness(0, witness)
  }
  Transaction.correctlySpends(signedCommitTx, fundingTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)


  val revocationPubKey = remotePubKey

  // offered HTLC
  val htlcTimeoutTx = {
    val tx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(signedCommitTx, 3), signatureScript = Nil, sequence = 0) :: Nil,
      txOut = TxOut(4000 satoshi, htlcSuccessOrTimeout(remotePubKey, htlcTimeout, localDelayedKey)) :: Nil,
      lockTime = htlcTimeout + 1)
    val redeemScript: BinaryData = Script.write(htlcReceived(localPubKey, remotePubKey, Crypto.hash160(paymentPreimage2), htlcTimeout))
    val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, signedCommitTx.txOut(3).amount, SigVersion.SIGVERSION_WITNESS_V0, remotePrivKey)
    val witness = ScriptWitness(BinaryData("01") :: remoteSig :: BinaryData.empty :: redeemScript :: Nil)
    tx.updateWitness(0, witness)
  }

  // Alice (local) can claim the offered HTLC after a delay
  Transaction.correctlySpends(htlcTimeoutTx, signedCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

  // received HTLC
  val htlcSuccessTx = {
    val tx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(signedCommitTx, 2), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(6000 satoshi, htlcSuccessOrTimeout(remotePubKey, htlcTimeout, localDelayedKey)) :: Nil,
      lockTime = 0)
    val redeemScript: BinaryData = Script.write(htlcReceived(localPubKey, remotePubKey, Crypto.hash160(paymentPreimage2), htlcTimeout))
    val localSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, signedCommitTx.txOut(2).amount, SigVersion.SIGVERSION_WITNESS_V0, localPrivKey)
    val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, signedCommitTx.txOut(2).amount, SigVersion.SIGVERSION_WITNESS_V0, remotePrivKey)
    val witness = ScriptWitness(BinaryData("01") :: remoteSig :: BinaryData.empty :: redeemScript :: Nil)
    tx.updateWitness(0, witness)
  }
}
