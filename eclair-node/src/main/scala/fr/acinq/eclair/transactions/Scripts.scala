package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.Crypto.{hash160, ripemd160, sha256}
import fr.acinq.bitcoin.{BinaryData, Crypto, LockTimeThreshold, OP_0, OP_2, OP_2DROP, OP_ADD, OP_CHECKLOCKTIMEVERIFY, OP_CHECKMULTISIG, OP_CHECKSEQUENCEVERIFY, OP_CHECKSIG, OP_DROP, OP_DUP, OP_ELSE, OP_ENDIF, OP_EQUAL, OP_EQUALVERIFY, OP_HASH160, OP_IF, OP_NOTIF, OP_PUSHDATA, OP_SIZE, OP_SWAP, OutPoint, Protocol, SIGHASH_ALL, Satoshi, Script, ScriptElt, ScriptWitness, Transaction, TxIn, TxOut}
import fr.acinq.eclair.memcmp

/**
  * Created by PM on 02/12/2016.
  */
object Common {

  def toSelfDelay2csv(in: Int): Long = ???

  /*in match {
     case locktime(Blocks(blocks)) => blocks
     case locktime(Seconds(seconds)) => TxIn.SEQUENCE_LOCKTIME_TYPE_FLAG | (seconds >> TxIn.SEQUENCE_LOCKTIME_GRANULARITY)
   }*/

  def expiry2cltv(in: Long): Long = ???

  /*in match {
      case locktime(Blocks(blocks)) => blocks
      case locktime(Seconds(seconds)) => seconds
    }*/

  def isLess(a: Seq[Byte], b: Seq[Byte]): Boolean = memcmp(a.dropWhile(_ == 0).toList, b.dropWhile(_ == 0).toList) < 0

  def lessThan(output1: TxOut, output2: TxOut): Boolean = (output1, output2) match {
    case (TxOut(amount1, script1), TxOut(amount2, script2)) if amount1 == amount2 => memcmp(script1.toList, script2.toList) < 0
    case (TxOut(amount1, _), TxOut(amount2, _)) => amount1.toLong < amount2.toLong
  }

  def permuteOutputs(tx: Transaction): Transaction = tx.copy(txOut = tx.txOut.sortWith(lessThan))

  def multiSig2of2(pubkey1: BinaryData, pubkey2: BinaryData): BinaryData = if (isLess(pubkey1, pubkey2))
    Script.createMultiSigMofN(2, Seq(pubkey1, pubkey2))
  else
    Script.createMultiSigMofN(2, Seq(pubkey2, pubkey1))


  def sigScript2of2(sig1: BinaryData, sig2: BinaryData, pubkey1: BinaryData, pubkey2: BinaryData): BinaryData = if (isLess(pubkey1, pubkey2))
    Script.write(OP_0 :: OP_PUSHDATA(sig1) :: OP_PUSHDATA(sig2) :: OP_PUSHDATA(multiSig2of2(pubkey1, pubkey2)) :: Nil)
  else
    Script.write(OP_0 :: OP_PUSHDATA(sig2) :: OP_PUSHDATA(sig1) :: OP_PUSHDATA(multiSig2of2(pubkey1, pubkey2)) :: Nil)

  /**
    *
    * @param sig1
    * @param sig2
    * @param pubkey1
    * @param pubkey2
    * @return a script witness that matches the msig 2-of-2 pubkey script for pubkey1 and pubkey2
    */
  def witness2of2(sig1: BinaryData, sig2: BinaryData, pubkey1: BinaryData, pubkey2: BinaryData): ScriptWitness = {
    if (isLess(pubkey1, pubkey2))
      ScriptWitness(Seq(BinaryData.empty, sig1, sig2, multiSig2of2(pubkey1, pubkey2)))
    else
      ScriptWitness(Seq(BinaryData.empty, sig2, sig1, multiSig2of2(pubkey1, pubkey2)))

  }

  def pay2pkh(pubKey: BinaryData): Seq[ScriptElt] = OP_DUP :: OP_HASH160 :: OP_PUSHDATA(hash160(pubKey)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil

  def pay2sh(script: Seq[ScriptElt]): Seq[ScriptElt] = pay2sh(Script.write(script))

  def pay2sh(script: BinaryData): Seq[ScriptElt] = OP_HASH160 :: OP_PUSHDATA(hash160(script)) :: OP_EQUAL :: Nil

  def pay2wsh(script: Seq[ScriptElt]): Seq[ScriptElt] = pay2wsh(Script.write(script))

  def pay2wsh(script: BinaryData): Seq[ScriptElt] = OP_0 :: OP_PUSHDATA(sha256(script)) :: Nil

  def pay2wpkh(pubKey: BinaryData): Seq[ScriptElt] = OP_0 :: OP_PUSHDATA(hash160(pubKey)) :: Nil

  /**
    *
    * @param pubkey1     public key for A
    * @param pubkey2     public key for B
    * @param amount      funding tx amount
    * @param previousTx  tx that will fund the funding tx; it * must * be a P2PWPK embedded in a standard P2SH tx: the p2sh
    *                    script is just the P2WPK script for the public key that matches our "key" parameter
    * @param outputIndex index of the output in the funding tx
    * @param key         private key that can redeem the funding tx
    * @return a signed funding tx
    */
  def makeAnchorTx(pubkey1: BinaryData, pubkey2: BinaryData, amount: Long, previousTx: Transaction, outputIndex: Int, key: BinaryData): (Transaction, Int) = {
    val tx = Transaction(version = 2,
      txIn = TxIn(outPoint = OutPoint(previousTx, outputIndex), signatureScript = Array.emptyByteArray, sequence = 0xffffffffL) :: Nil,
      txOut = TxOut(Satoshi(amount), publicKeyScript = pay2wsh(multiSig2of2(pubkey1, pubkey2))) :: Nil,
      lockTime = 0)
    val pub: BinaryData = Crypto.publicKeyFromPrivateKey(key)
    val pkh = OP_0 :: OP_PUSHDATA(Crypto.hash160(pub)) :: Nil
    val p2sh: BinaryData = Script.write(pay2sh(pkh))

    require(p2sh == previousTx.txOut(outputIndex).publicKeyScript)

    val pubKeyScript = Script.write(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pub)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil)
    val hash = Transaction.hashForSigning(tx, 0, pubKeyScript, SIGHASH_ALL, tx.txOut(0).amount, signatureVersion = 1)
    val sig = Crypto.encodeSignature(Crypto.sign(hash, key.take(32))) :+ SIGHASH_ALL.toByte
    val witness = ScriptWitness(Seq(sig, pub))
    val script = Script.write(OP_0 :: OP_PUSHDATA(Crypto.hash160(pub)) :: Nil)
    val signedTx = tx.updateSigScript(0, OP_PUSHDATA(script) :: Nil).updateWitness(0, witness)

    // we don't permute outputs because by convention the multisig output has index = 0
    (signedTx, 0)
  }

  def anchorPubkeyScript(pubkey1: BinaryData, pubkey2: BinaryData): BinaryData = Script.write(pay2wsh(multiSig2of2(pubkey1, pubkey2)))

  def encodeNumber(n: Long): BinaryData = {
    // TODO: added for compatibility with lightningd => check (it's either a bug in lighningd or bitcoin-lib)
    if (n < 0xff) Protocol.writeUInt8(n.toInt) else Script.encodeNumber(n)
  }

  def redeemSecretOrDelay(delayedKey: BinaryData, reltimeout: Long, keyIfSecretKnown: BinaryData, hashOfSecret: BinaryData): Seq[ScriptElt] = {
    // @formatter:off
    OP_HASH160 :: OP_PUSHDATA(ripemd160(hashOfSecret)) :: OP_EQUAL ::
    OP_IF ::
    OP_PUSHDATA(keyIfSecretKnown) ::
    OP_ELSE ::
    OP_PUSHDATA(encodeNumber(reltimeout)) :: OP_CHECKSEQUENCEVERIFY :: OP_DROP :: OP_PUSHDATA(delayedKey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  def scriptPubKeyHtlcSend(ourkey: BinaryData, theirkey: BinaryData, abstimeout: Long, reltimeout: Long, rhash: BinaryData, commit_revoke: BinaryData): Seq[ScriptElt] = {
    // values lesser than 16 should be encoded using OP_0..OP_16 instead of OP_PUSHDATA
    assert(abstimeout > 16, s"abstimeout=$abstimeout must be greater than 16")
    // @formatter:off
    OP_SIZE :: OP_PUSHDATA(encodeNumber(32)) :: OP_EQUALVERIFY ::
    OP_HASH160 :: OP_DUP ::
    OP_PUSHDATA(ripemd160(rhash)) :: OP_EQUAL ::
    OP_SWAP :: OP_PUSHDATA(ripemd160(commit_revoke)) :: OP_EQUAL :: OP_ADD ::
    OP_IF ::
    OP_PUSHDATA(theirkey) ::
    OP_ELSE ::
    OP_PUSHDATA(encodeNumber(abstimeout)) :: OP_CHECKLOCKTIMEVERIFY :: OP_PUSHDATA(encodeNumber(reltimeout)) :: OP_CHECKSEQUENCEVERIFY :: OP_2DROP :: OP_PUSHDATA(ourkey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  def scriptPubKeyHtlcReceive(ourkey: BinaryData, theirkey: BinaryData, abstimeout: Long, reltimeout: Long, rhash: BinaryData, commit_revoke: BinaryData): Seq[ScriptElt] = {
    // values lesser than 16 should be encoded using OP_0..OP_16 instead of OP_PUSHDATA
    assert(abstimeout > 16, s"abstimeout=$abstimeout must be greater than 16")
    // @formatter:off
    OP_SIZE :: OP_PUSHDATA(encodeNumber(32)) :: OP_EQUALVERIFY ::
      OP_HASH160 :: OP_DUP ::
      OP_PUSHDATA(ripemd160(rhash)) :: OP_EQUAL ::
      OP_IF ::
      OP_PUSHDATA(encodeNumber(reltimeout)) :: OP_CHECKSEQUENCEVERIFY :: OP_2DROP :: OP_PUSHDATA(ourkey) ::
      OP_ELSE ::
      OP_PUSHDATA(ripemd160(commit_revoke)) :: OP_EQUAL ::
      OP_NOTIF ::
      OP_PUSHDATA(encodeNumber(abstimeout)) :: OP_CHECKLOCKTIMEVERIFY :: OP_DROP ::
      OP_ENDIF ::
      OP_PUSHDATA(theirkey) ::
      OP_ENDIF ::
      OP_CHECKSIG :: Nil
    // @formatter:on
  }

  def applyFees(amount_us: Satoshi, amount_them: Satoshi, fee: Satoshi) = {
    val (amount_us1: Satoshi, amount_them1: Satoshi) = (amount_us, amount_them) match {
      case (Satoshi(us), Satoshi(them)) if us >= fee.toLong / 2 && them >= fee.toLong / 2 => (Satoshi(us - fee.toLong / 2), Satoshi(them - fee.toLong / 2))
      case (Satoshi(us), Satoshi(them)) if us < fee.toLong / 2 => (Satoshi(0L), Satoshi(Math.max(0L, them - fee.toLong + us)))
      case (Satoshi(us), Satoshi(them)) if them < fee.toLong / 2 => (Satoshi(Math.max(us - fee.toLong + them, 0L)), Satoshi(0L))
    }
    (amount_us1, amount_them1)
  }

  /**
    * Create a "final" channel transaction that will be published when the channel is closed
    *
    * @param inputs            inputs to include in the tx. In most cases, there's only one input that points to the output of
    *                          the funding tx
    * @param ourPubkeyScript   our public key script
    * @param theirPubkeyScript their public key script
    * @param amount_us         pay to us
    * @param amount_them       pay to them
    * @return an unsigned "final" tx
    */
  def makeFinalTx(inputs: Seq[TxIn], ourPubkeyScript: BinaryData, theirPubkeyScript: BinaryData, amount_us: Satoshi, amount_them: Satoshi, fee: Satoshi): Transaction = {
    val (amount_us1: Satoshi, amount_them1: Satoshi) = applyFees(amount_us, amount_them, fee)

    permuteOutputs(Transaction(
      version = 2,
      txIn = inputs,
      txOut = Seq(
        TxOut(amount = amount_us1, publicKeyScript = ourPubkeyScript),
        TxOut(amount = amount_them1, publicKeyScript = theirPubkeyScript)
      ),
      lockTime = 0))
  }

  //def isFunder(o: open_channel): Boolean = o.anch == open_channel.anchor_offer.WILL_CREATE_FUNDING

  def findPublicKeyScriptIndex(tx: Transaction, publicKeyScript: BinaryData): Option[Int] =
    tx.txOut.zipWithIndex.find {
      case (TxOut(_, script), _) => script == publicKeyScript
    } map (_._2)

  /**
    *
    * @param tx
    * @return the block height before which this tx cannot be published
    */
  def cltvTimeout(tx: Transaction): Long = {
    require(tx.lockTime <= LockTimeThreshold)
    tx.lockTime
  }

  /**
    *
    * @param tx
    * @return the number of confirmations of the tx parent before which it can be published
    */
  def csvTimeout(tx: Transaction): Long = {
    def sequenceToBlockHeight(sequence: Long): Long = {
      if ((sequence & TxIn.SEQUENCE_LOCKTIME_DISABLE_FLAG) != 0) 0
      else {
        require((sequence & TxIn.SEQUENCE_LOCKTIME_TYPE_FLAG) == 0, "CSV timeout must use block heights, not block times")
        sequence & TxIn.SEQUENCE_LOCKTIME_MASK
      }
    }

    if (tx.version < 2) 0
    else tx.txIn.map(_.sequence).map(sequenceToBlockHeight).max
  }
}

object OutputScripts {

  def toLocal(revocationPubKey: BinaryData, toSelfDelay: Int, localDelayedKey: BinaryData) = {
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

  def toRemote(remoteKey: BinaryData) = remoteKey

  def htlcOffered(localKey: BinaryData, remoteKey: BinaryData, paymentHash: BinaryData) = {
    // @formatter:off
    OP_PUSHDATA(remoteKey) :: OP_SWAP ::
    OP_SIZE :: OP_PUSHDATA(Script.encodeNumber(32)) :: OP_EQUAL ::
    OP_NOTIF ::
      OP_DROP :: OP_2 :: OP_SWAP :: OP_PUSHDATA(localKey) :: OP_2 :: OP_CHECKMULTISIG ::
    OP_ELSE ::
      OP_HASH160 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
      OP_CHECKSIG ::
    OP_ENDIF :: Nil
    // @formatter:on
  }

  def htlcReceived(localKey: BinaryData, remoteKey: BinaryData, paymentHash: BinaryData, lockTime: Long) = {
    // @formatter:off
    OP_PUSHDATA(remoteKey) :: OP_SWAP ::
    OP_SIZE :: OP_PUSHDATA(Script.encodeNumber(32)) :: OP_EQUAL ::
    OP_IF ::
      OP_HASH160 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
      OP_2 :: OP_SWAP :: OP_PUSHDATA(localKey) :: OP_2 :: OP_CHECKMULTISIG ::
    OP_ELSE ::
      OP_DROP :: OP_PUSHDATA(Script.encodeNumber(lockTime)) :: OP_CHECKLOCKTIMEVERIFY :: OP_DROP :: OP_CHECKSIG ::
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

}

object InputScripts {

}
