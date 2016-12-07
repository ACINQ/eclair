package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.Crypto._
import fr.acinq.bitcoin._
import fr.acinq.eclair._

/**
  * Created by PM on 21/01/2016.
  */
object OldScripts {

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
    * @param amount      anchor tx amount
    * @param previousTx  tx that will fund the anchor; it * must * be a P2PWPK embedded in a standard P2SH tx: the p2sh
    *                    script is just the P2WPK script for the public key that matches our "key" parameter
    * @param outputIndex index of the output in the funding tx
    * @param key         private key that can redeem the funding tx
    * @return a signed anchor tx
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

  def makeCommitTx(ourFinalKey: BinaryData, theirFinalKey: BinaryData, theirDelay: Int, anchorTxId: BinaryData, anchorOutputIndex: Int, revocationHash: BinaryData, spec: CommitmentSpec): Transaction =
    makeCommitTx(inputs = TxIn(OutPoint(anchorTxId, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, ourFinalKey, theirFinalKey, theirDelay, revocationHash, spec)

  def applyFees(amount_us: Satoshi, amount_them: Satoshi, fee: Satoshi) = {
    val (amount_us1: Satoshi, amount_them1: Satoshi) = (amount_us, amount_them) match {
      case (Satoshi(us), Satoshi(them)) if us >= fee.toLong / 2 && them >= fee.toLong / 2 => (Satoshi(us - fee.toLong / 2), Satoshi(them - fee.toLong / 2))
      case (Satoshi(us), Satoshi(them)) if us < fee.toLong / 2 => (Satoshi(0L), Satoshi(Math.max(0L, them - fee.toLong + us)))
      case (Satoshi(us), Satoshi(them)) if them < fee.toLong / 2 => (Satoshi(Math.max(us - fee.toLong + them, 0L)), Satoshi(0L))
    }
    (amount_us1, amount_them1)
  }


  def makeCommitTxTemplate(inputs: Seq[TxIn], ourFinalKey: BinaryData, theirFinalKey: BinaryData, theirDelay: Int, revocationHash: BinaryData, commitmentSpec: CommitmentSpec): CommitTxTemplate = {
    val redeemScript = redeemSecretOrDelay(ourFinalKey, toSelfDelay2csv(theirDelay), theirFinalKey, revocationHash: BinaryData)
    val htlcs = commitmentSpec.htlcs.filter(_.add.amountMsat >= 546000).toSeq
    val fee_msat = computeFee(commitmentSpec.feeRate, htlcs.size) * 1000
    val (amount_us_msat: Long, amount_them_msat: Long) = (commitmentSpec.to_local_msat, commitmentSpec.to_remote_msat) match {
      case (us, them) if us >= fee_msat / 2 && them >= fee_msat / 2 => (us - fee_msat / 2, them - fee_msat / 2)
      case (us, them) if us < fee_msat / 2 => (0L, Math.max(0L, them - fee_msat + us))
      case (us, them) if them < fee_msat / 2 => (Math.max(us - fee_msat + them, 0L), 0L)
    }

    // our output is a pay2wsh output than can be claimed by them if they know the preimage, or by us after a delay
    // when * they * publish a revoked commit tx, we use the preimage that they sent us to claim it
    val ourOutput = if (amount_us_msat >= 546000) Some(P2WSHTemplate(Satoshi(amount_us_msat / 1000), redeemScript)) else None

    // their output is a simple pay2pkh output that sends money to their final key and can only be claimed by them
    // when * they * publish a revoked commit tx we don't have anything special to do about it
    val theirOutput = if (amount_them_msat >= 546000) Some(P2WPKHTemplate(Satoshi(amount_them_msat / 1000), theirFinalKey)) else None

    val sendOuts: Seq[HTLCTemplate] = htlcs.filter(_.direction == OUT).map(htlc => {
      HTLCTemplate(htlc, ourFinalKey, theirFinalKey, theirDelay, revocationHash)
    })
    val receiveOuts: Seq[HTLCTemplate] = htlcs.filter(_.direction == IN).map(htlc => {
      HTLCTemplate(htlc, ourFinalKey, theirFinalKey, theirDelay, revocationHash)
    })
    CommitTxTemplate(inputs, ourOutput, theirOutput, sendOuts, receiveOuts)
  }

  def makeCommitTx(inputs: Seq[TxIn], ourFinalKey: BinaryData, theirFinalKey: BinaryData, theirDelay: Int, revocationHash: BinaryData, commitmentSpec: CommitmentSpec): Transaction = {
    val txTemplate = makeCommitTxTemplate(inputs, ourFinalKey, theirFinalKey, theirDelay, revocationHash, commitmentSpec)
    val tx = txTemplate.makeTx
    tx
  }

  /**
    * Create a "final" channel transaction that will be published when the channel is closed
    *
    * @param inputs            inputs to include in the tx. In most cases, there's only one input that points to the output of
    *                          the anchor tx
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

  //def isFunder(o: open_channel): Boolean = o.anch == open_channel.anchor_offer.WILL_CREATE_ANCHOR

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
