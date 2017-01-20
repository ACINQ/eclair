package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, ripemd160}
import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin.{BinaryData, Crypto, LexicographicalOrdering, LockTimeThreshold, OP_0, OP_2, OP_2DROP, OP_ADD, OP_CHECKLOCKTIMEVERIFY, OP_CHECKMULTISIG, OP_CHECKSEQUENCEVERIFY, OP_CHECKSIG, OP_DROP, OP_DUP, OP_ELSE, OP_ENDIF, OP_EQUAL, OP_EQUALVERIFY, OP_HASH160, OP_IF, OP_NOTIF, OP_PUSHDATA, OP_SIZE, OP_SWAP, OutPoint, Protocol, SIGHASH_ALL, Satoshi, Script, ScriptElt, ScriptWitness, Transaction, TxIn, TxOut}

/**
  * Created by PM on 02/12/2016.
  */
object Scripts {

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

  def multiSig2of2(pubkey1: PublicKey, pubkey2: PublicKey): Seq[ScriptElt] = if (LexicographicalOrdering.isLessThan(pubkey1.toBin, pubkey2.toBin))
    Script.createMultiSigMofN(2, Seq(pubkey1, pubkey2))
  else
    Script.createMultiSigMofN(2, Seq(pubkey2, pubkey1))

  /**
    *
    * @param sig1
    * @param sig2
    * @param pubkey1
    * @param pubkey2
    * @return a script witness that matches the msig 2-of-2 pubkey script for pubkey1 and pubkey2
    */
  def witness2of2(sig1: BinaryData, sig2: BinaryData, pubkey1: PublicKey, pubkey2: PublicKey): ScriptWitness = {
    if (LexicographicalOrdering.isLessThan(pubkey1.toBin, pubkey2.toBin))
      ScriptWitness(Seq(BinaryData.empty, sig1, sig2, write(multiSig2of2(pubkey1, pubkey2))))
    else
      ScriptWitness(Seq(BinaryData.empty, sig2, sig1, write(multiSig2of2(pubkey1, pubkey2))))

  }

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
  def makeFundingTx(pubkey1: PublicKey, pubkey2: PublicKey, amount: Long, previousTx: Transaction, outputIndex: Int, key: PrivateKey): (Transaction, Int) = {
    val tx = Transaction(version = 2,
      txIn = TxIn(outPoint = OutPoint(previousTx, outputIndex), signatureScript = Array.emptyByteArray, sequence = 0xffffffffL) :: Nil,
      txOut = TxOut(Satoshi(amount), publicKeyScript = pay2wsh(multiSig2of2(pubkey1, pubkey2))) :: Nil,
      lockTime = 0)
    val pub: BinaryData = key.toPoint
    val pkh = OP_0 :: OP_PUSHDATA(Crypto.hash160(pub)) :: Nil
    val p2sh: BinaryData = Script.write(pay2sh(pkh))

    require(p2sh == previousTx.txOut(outputIndex).publicKeyScript)

    val pubKeyScript = Script.write(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pub)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil)
    val hash = Transaction.hashForSigning(tx, 0, pubKeyScript, SIGHASH_ALL, tx.txOut(0).amount, signatureVersion = 1)
    val sig = Crypto.encodeSignature(Crypto.sign(hash, key)) :+ SIGHASH_ALL.toByte
    val witness = ScriptWitness(Seq(sig, pub))
    val script = Script.write(OP_0 :: OP_PUSHDATA(Crypto.hash160(pub)) :: Nil)
    val signedTx = tx.updateSigScript(0, OP_PUSHDATA(script) :: Nil).updateWitness(0, witness)

    // we don't permute outputs because by convention the multisig output has index = 0
    (signedTx, 0)
  }

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

    LexicographicalOrdering.sort(Transaction(
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
    * This function interprets the locktime for the given transaction, and returns the block height before which this tx cannot be published.
    * By convention in bitcoin, depending of the value of locktime it might be a number of blocks or a number of seconds since epoch.
    * This function does not support the case when the locktime is a number of seconds that is not way in the past.
    * NB: We use this property in lightning to store data in this field.
    * @return the block height before which this tx cannot be published.
    */
  def cltvTimeout(tx: Transaction): Long = {
    if (tx.lockTime <= LockTimeThreshold) {
      // locktime is a number of blocks
      tx.lockTime
    }
    else {
      // locktime is a unix epoch timestamp
      require(tx.lockTime <= 0x20FFFFFF, "locktime should be lesser than 0x20FFFFFF")
      // since locktime is very well in the past (0x20FFFFFF is in 1987), it is equivalent to no locktime at all
      0
    }
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

  def toLocalDelayed(revocationPubkey: BinaryData, toSelfDelay: Int, localDelayedPubkey: BinaryData) = {
    // @formatter:off
    OP_IF ::
      OP_PUSHDATA(revocationPubkey) ::
    OP_ELSE ::
      OP_PUSHDATA(Script.encodeNumber(toSelfDelay)) :: OP_CHECKSEQUENCEVERIFY :: OP_DROP ::
      OP_PUSHDATA(localDelayedPubkey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  /**
    * This witness script spends a [[toLocalDelayed]] output using a local sig after a delay
    */
  def witnessToLocalDelayedAfterDelay(localSig: BinaryData, toLocalDelayedScript: BinaryData) =
    ScriptWitness(localSig :: BinaryData.empty :: toLocalDelayedScript :: Nil)

  /**
    * This witness script spends (steals) a [[toLocalDelayed]] output using a revocation key as a punishment
    * for having published a revoked transaction
    */
  def witnessToLocalDelayedWithRevocationSig(revocationSig: BinaryData, toLocalScript: BinaryData) =
    ScriptWitness(revocationSig :: BinaryData("01") :: toLocalScript :: Nil)

  def htlcOffered(localPubkey: BinaryData, remotePubkey: BinaryData, paymentHash: BinaryData) = {
    // @formatter:off
    OP_PUSHDATA(remotePubkey) :: OP_SWAP ::
    OP_SIZE :: OP_PUSHDATA(Script.encodeNumber(32)) :: OP_EQUAL ::
    OP_NOTIF ::
      OP_DROP :: OP_2 :: OP_SWAP :: OP_PUSHDATA(localPubkey) :: OP_2 :: OP_CHECKMULTISIG ::
    OP_ELSE ::
      OP_HASH160 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
      OP_CHECKSIG ::
    OP_ENDIF :: Nil
    // @formatter:on
  }

  /**
    * This is the witness script of the 2nd-stage HTLC Success transaction (consumes htlcOffered script from commit tx)
    */
  def witnessHtlcSuccess(localSig: BinaryData, remoteSig: BinaryData, paymentPreimage: BinaryData, htlcOfferedScript: BinaryData) =
    ScriptWitness(BinaryData.empty :: remoteSig :: localSig :: paymentPreimage :: htlcOfferedScript :: Nil)

  /**
    * If local publishes its commit tx where there was a local->remote htlc, then remote uses this script to
    * claim its funds using a payment preimage (consumes htlcOffered script from commit tx)
    */
  def witnessClaimHtlcSuccessFromCommitTx(localSig: BinaryData, paymentPreimage: BinaryData, htlcOfferedScript: BinaryData) =
    ScriptWitness(localSig :: paymentPreimage :: htlcOfferedScript :: Nil)

  def htlcReceived(localKey: BinaryData, remotePubkey: BinaryData, paymentHash: BinaryData, lockTime: Long) = {
    // @formatter:off
    OP_PUSHDATA(remotePubkey) :: OP_SWAP ::
    OP_SIZE :: OP_PUSHDATA(Script.encodeNumber(32)) :: OP_EQUAL ::
    OP_IF ::
      OP_HASH160 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
      OP_2 :: OP_SWAP :: OP_PUSHDATA(localKey) :: OP_2 :: OP_CHECKMULTISIG ::
    OP_ELSE ::
      OP_DROP :: OP_PUSHDATA(Script.encodeNumber(lockTime)) :: OP_CHECKLOCKTIMEVERIFY :: OP_DROP :: OP_CHECKSIG ::
    OP_ENDIF :: Nil
    // @formatter:on
  }

  /**
    * This is the witness script of the 2nd-stage HTLC Timeout transaction (consumes htlcReceived script from commit tx)
    */
  def witnessHtlcTimeout(localSig: BinaryData, remoteSig: BinaryData, htlcReceivedScript: BinaryData) =
    ScriptWitness(BinaryData.empty :: remoteSig :: localSig :: BinaryData.empty :: htlcReceivedScript :: Nil)

  /**
    * If local publishes its commit tx where there was a remote->local htlc, then remote uses this script to
    * claim its funds after timeout (consumes htlcReceived script from commit tx)
    */
  def witnessClaimHtlcTimeoutFromCommitTx(localSig: BinaryData, htlcReceivedScript: BinaryData) =
    ScriptWitness(localSig :: BinaryData.empty :: htlcReceivedScript :: Nil)

}