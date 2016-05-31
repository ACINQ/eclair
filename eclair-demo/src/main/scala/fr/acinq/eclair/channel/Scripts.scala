package fr.acinq.eclair.channel

import fr.acinq.bitcoin.Crypto._
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import lightning.locktime.Locktime.{Seconds, Blocks}
import lightning.{locktime, update_add_htlc, open_anchor, open_channel}

/**
  * Created by PM on 21/01/2016.
  */
object Scripts {

  def locktime2long_csv(in: locktime): Long = in match {
    case locktime(Blocks(blocks)) => blocks
    case locktime(Seconds(seconds)) => TxIn.SEQUENCE_LOCKTIME_TYPE_FLAG | (seconds >> TxIn.SEQUENCE_LOCKTIME_GRANULARITY)
  }

  def locktime2long_cltv(in: locktime): Long = in match {
    case locktime(Blocks(blocks)) => blocks
    case locktime(Seconds(seconds)) => seconds
  }

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

  def pay2sh(script: Seq[ScriptElt]): Seq[ScriptElt] = pay2sh(Script.write(script))

  def pay2sh(script: BinaryData): Seq[ScriptElt] = OP_HASH160 :: OP_PUSHDATA(hash160(script)) :: OP_EQUAL :: Nil

  def pay2wsh(script: Seq[ScriptElt]): Seq[ScriptElt] = pay2wsh(Script.write(script))

  def pay2wsh(script: BinaryData): Seq[ScriptElt] = OP_0 :: OP_PUSHDATA(sha256(script)) :: Nil

  def pay2wpkh(pubKey: BinaryData): Seq[ScriptElt] = OP_0 :: OP_PUSHDATA(hash160(pubKey)) :: Nil

  /**
    *
    * @param pubkey1 public key for A
    * @param pubkey2 public key for B
    * @param amount anchor tx amount
    * @param previousTx tx that will fund the anchor; it * must * be a P2PWPK embedded in a standard P2SH tx: the p2sh
    *                   script is just the P2WPK script for the public key that matches our "key" parameter
    * @param outputIndex index of the output in the funding tx
    * @param key private key that can redeem the funding tx
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
    val hash = Transaction.hashForSigning(tx, 0, pubKeyScript, SIGHASH_ALL, tx.txOut(0).amount.toLong, signatureVersion = 1)
    val sig = Crypto.encodeSignature(Crypto.sign(hash, key.take(32), randomize = false)) :+ SIGHASH_ALL.toByte
    val witness = ScriptWitness(Seq(sig, pub))
    val script = Script.write(OP_0 :: OP_PUSHDATA(Crypto.hash160(pub)) :: Nil)
    val signedTx = tx.updateSigScript(0, OP_PUSHDATA(script) :: Nil).copy(witness = Seq(witness))

    // we don't permute outputs because by convention the multisig output has index = 0
    (signedTx, 0)
  }

  def anchorPubkeyScript(pubkey1: BinaryData, pubkey2: BinaryData): BinaryData = Script.write(pay2wsh(multiSig2of2(pubkey1, pubkey2)))

  def redeemSecretOrDelay(delayedKey: BinaryData, reltimeout: Long, keyIfSecretKnown: BinaryData, hashOfSecret: BinaryData): Seq[ScriptElt] = {
    // @formatter:off
    OP_HASH160 :: OP_PUSHDATA(ripemd160(hashOfSecret)) :: OP_EQUAL ::
    OP_IF ::
      OP_PUSHDATA(keyIfSecretKnown) ::
    OP_ELSE ::
      OP_PUSHDATA(Script.encodeNumber(reltimeout)) :: OP_CHECKSEQUENCEVERIFY :: OP_DROP :: OP_PUSHDATA(delayedKey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  def scriptPubKeyHtlcSend(ourkey: BinaryData, theirkey: BinaryData, abstimeout: Long, reltimeout: Long, rhash: BinaryData, commit_revoke: BinaryData): Seq[ScriptElt] = {
    // @formatter:off
    OP_SIZE :: OP_PUSHDATA(Script.encodeNumber(32)) :: OP_EQUALVERIFY ::
    OP_HASH160 :: OP_DUP ::
    OP_PUSHDATA(ripemd160(rhash)) :: OP_EQUAL ::
    OP_SWAP :: OP_PUSHDATA(ripemd160(commit_revoke)) :: OP_EQUAL :: OP_ADD ::
    OP_IF ::
      OP_PUSHDATA(theirkey) ::
    OP_ELSE ::
      OP_PUSHDATA(Script.encodeNumber(abstimeout)) :: OP_CHECKLOCKTIMEVERIFY :: OP_PUSHDATA(Script.encodeNumber(reltimeout)) :: OP_CHECKSEQUENCEVERIFY :: OP_2DROP :: OP_PUSHDATA(ourkey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  def scriptPubKeyHtlcReceive(ourkey: BinaryData, theirkey: BinaryData, abstimeout: Long, reltimeout: Long, rhash: BinaryData, commit_revoke: BinaryData): Seq[ScriptElt] = {
    // @formatter:off
    OP_SIZE :: OP_PUSHDATA(Script.encodeNumber(32)) :: OP_EQUALVERIFY ::
    OP_HASH160 :: OP_DUP ::
    OP_PUSHDATA(ripemd160(rhash)) :: OP_EQUAL ::
    OP_IF ::
      OP_PUSHDATA(Script.encodeNumber(reltimeout)) :: OP_CHECKSEQUENCEVERIFY :: OP_2DROP :: OP_PUSHDATA(ourkey) ::
    OP_ELSE ::
      OP_PUSHDATA(ripemd160(commit_revoke)) :: OP_EQUAL ::
      OP_NOTIF ::
        OP_PUSHDATA(Script.encodeNumber(abstimeout)) :: OP_CHECKLOCKTIMEVERIFY :: OP_DROP ::
      OP_ENDIF ::
      OP_PUSHDATA(theirkey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  def makeCommitTx(ourFinalKey: BinaryData, theirFinalKey: BinaryData, theirDelay: locktime, anchorTxId: BinaryData, anchorOutputIndex: Int, revocationHash: BinaryData, spec: CommitmentSpec): Transaction =
    makeCommitTx(inputs = TxIn(OutPoint(anchorTxId, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, ourFinalKey, theirFinalKey, theirDelay, revocationHash, spec)

  // this way it is easy to reuse the inputTx of an existing commitmentTx
//  def makeCommitTx(inputs: Seq[TxIn], ourFinalKey: BinaryData, theirFinalKey: BinaryData, theirDelay: locktime, revocationHash: BinaryData, channelState: ChannelState): Transaction = {
//    val redeemScript = redeemSecretOrDelay(ourFinalKey, locktime2long_csv(theirDelay), theirFinalKey, revocationHash: BinaryData)
//
//    val outputs = Seq(
//      // TODO : is that the correct way to handle sub-satoshi balances ?
//      TxOut(amount = Satoshi(channelState.us.pay_msat / 1000), publicKeyScript = pay2wsh(redeemScript)),
//      TxOut(amount = Satoshi(channelState.them.pay_msat / 1000), publicKeyScript = pay2wpkh(theirFinalKey))
//    ).filterNot(_.amount.toLong < 546) // do not add dust
//
//    val tx = Transaction(
//      version = 2,
//      txIn = inputs,
//      txOut = outputs,
//      lockTime = 0)
//
//    val sendOuts = channelState.them.htlcs_received.map(htlc =>
//      TxOut(Satoshi(htlc.amountMsat / 1000), pay2wsh(scriptPubKeyHtlcSend(ourFinalKey, theirFinalKey, locktime2long_cltv(htlc.expiry), locktime2long_csv(theirDelay), htlc.rHash, revocationHash)))
//    )
//    val receiveOuts = channelState.us.htlcs_received.map(htlc =>
//      TxOut(Satoshi(htlc.amountMsat / 1000), pay2wsh(scriptPubKeyHtlcReceive(ourFinalKey, theirFinalKey, locktime2long_cltv(htlc.expiry), locktime2long_csv(theirDelay), htlc.rHash, revocationHash)))
//    )
//    val tx1 = tx.copy(txOut = tx.txOut ++ sendOuts ++ receiveOuts)
//    permuteOutputs(tx1)
//  }

  /**
    *
    * A node MUST use the formula 338 + 32 bytes for every non-dust HTLC as the bytecount for calculating commitment
    * transaction fees. Note that the fee requirement is unchanged, even if the elimination of dust HTLC outputs
    * has caused a non-zero fee already.
    * The fee for a transaction MUST be calculated by multiplying this bytecount by the fee rate, dividing by 1000
    * and truncating (rounding down) the result to an even number of satoshis.
    *
    * @param feeRate       fee rate in Satoshi/Kb
    * @param numberOfHtlcs number of (non-dust) HTLCs to be included in the commit tx
    * @return the fee in Satoshis for a commit tx with 'numberOfHtlcs' HTLCs
    */
  def computeFee(feeRate: Long, numberOfHtlcs: Int) : Long = {
    Math.floorDiv((338 + 32 * numberOfHtlcs) * feeRate, 2000) * 2
  }

  def makeCommitTx(inputs: Seq[TxIn], ourFinalKey: BinaryData, theirFinalKey: BinaryData, theirDelay: locktime, revocationHash: BinaryData, commitmentSpec: CommitmentSpec): Transaction = {
    val redeemScript = redeemSecretOrDelay(ourFinalKey, locktime2long_csv(theirDelay), theirFinalKey, revocationHash: BinaryData)
    val dust_threshold = 546000
    val htlcs_in = commitmentSpec.htlcs_in.filter(_.amountMsat >= dust_threshold)
    val htlcs_out = commitmentSpec.htlcs_out.filter(_.amountMsat >= dust_threshold)
    val fee_msat = computeFee(commitmentSpec.feeRate, htlcs_in.size + htlcs_out.size) * 1000
    val (amount_us_msat: Long, amount_them_msat: Long) = (commitmentSpec.amount_us_msat, commitmentSpec.amount_them_msat) match {
      case (us, them) if us >= fee_msat/2 && them >= fee_msat / 2 => (us - fee_msat / 2, them - fee_msat / 2)
      case (us, them) if us < fee_msat/2 => (0L, Math.max(0L, them - fee_msat + us))
      case (us, them) if them < fee_msat/2 => (Math.max(us - fee_msat + them, 0L), 0L)
    }

    val outputs = Seq(
      // TODO : is that the correct way to handle sub-satoshi balances ?
      TxOut(amount = Satoshi(amount_us_msat / 1000), publicKeyScript = pay2wsh(redeemScript)),
      TxOut(amount = Satoshi(amount_them_msat / 1000), publicKeyScript = pay2wpkh(theirFinalKey))
    ).filterNot(_.amount.toLong < 546) // do not add dust

    val tx = Transaction(
      version = 2,
      txIn = inputs,
      txOut = outputs,
      lockTime = 0)

    val sendOuts = htlcs_out.map(htlc =>
      TxOut(Satoshi(htlc.amountMsat / 1000), pay2wsh(scriptPubKeyHtlcSend(ourFinalKey, theirFinalKey, locktime2long_cltv(htlc.expiry), locktime2long_csv(theirDelay), htlc.rHash, revocationHash)))
    )
    val receiveOuts = htlcs_in.map(htlc =>
      TxOut(Satoshi(htlc.amountMsat / 1000), pay2wsh(scriptPubKeyHtlcReceive(ourFinalKey, theirFinalKey, locktime2long_cltv(htlc.expiry), locktime2long_csv(theirDelay), htlc.rHash, revocationHash)))
    )
    val tx1 = tx.copy(txOut = tx.txOut ++ sendOuts ++ receiveOuts)
    permuteOutputs(tx1)
  }

  /**
    * This is a simple tx with a multisig input and two pay2sh output
    *
    * @param inputs inputs to include in the tx. In most cases, there's only one input that points to the output of
    *               the anchor tx
    * @param ourFinalKey our final public key
    * @param theirFinalKey their final public key
    * @param channelState channel state
    * @return an unsigned "final" tx
    */
//  def makeFinalTx(inputs: Seq[TxIn], ourFinalKey: BinaryData, theirFinalKey: BinaryData, channelState: ChannelState): Transaction = {
//    assert(channelState.them.htlcs_received.isEmpty && channelState.us.htlcs_received.isEmpty, s"cannot close a channel with pending htlcs (see rusty's state_types.h line 103)")
//
//    permuteOutputs(Transaction(
//      version = 2,
//      txIn = inputs,
//      txOut = Seq(
//        TxOut(amount = Satoshi(channelState.them.pay_msat / 1000), publicKeyScript = pay2wpkh(theirFinalKey)),
//        TxOut(amount = Satoshi(channelState.us.pay_msat / 1000), publicKeyScript = pay2wpkh(ourFinalKey))
//      ),
//      lockTime = 0))
//  }

  def isFunder(o: open_channel): Boolean = o.anch == open_channel.anchor_offer.WILL_CREATE_ANCHOR

  def findPublicKeyScriptIndex(tx: Transaction, publicKeyScript: BinaryData): Option[Int] =
    tx.txOut.zipWithIndex.find {
      case (TxOut(_, script), _) => script == publicKeyScript
    } map (_._2)
}
