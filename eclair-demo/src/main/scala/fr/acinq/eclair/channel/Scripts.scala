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
    case locktime(Seconds(seconds)) => (seconds / 512) & TxIn.SEQUENCE_LOCKTIME_TYPE_FLAG
  }

  def locktime2long_cltv(in: locktime): Long = in match {
    case locktime(Blocks(blocks)) => blocks
    case locktime(Seconds(seconds)) => seconds
  }

  def isLess(a: Seq[Byte], b: Seq[Byte]): Boolean = memcmp(a.dropWhile(_ == 0).toList, b.dropWhile(_ == 0).toList) < 0

  def lessThan(output1: TxOut, output2: TxOut): Boolean = (output1, output2) match {
    case (TxOut(amount1, script1), TxOut(amount2, script2)) if amount1 == amount2 => memcmp(script1.toList, script2.toList) < 0
    case (TxOut(amount1, _), TxOut(amount2, _)) => amount1 < amount2
  }

  def permuteOutputs(tx: Transaction): Transaction = tx.copy(txOut = tx.txOut.sortWith(lessThan))

  def multiSig2of2(pubkey1: BinaryData, pubkey2: BinaryData): BinaryData = if (isLess(pubkey1, pubkey2))
    BinaryData(Script.createMultiSigMofN(2, Seq(pubkey1, pubkey2)))
  else
    BinaryData(Script.createMultiSigMofN(2, Seq(pubkey2, pubkey1)))


  def sigScript2of2(sig1: BinaryData, sig2: BinaryData, pubkey1: BinaryData, pubkey2: BinaryData): BinaryData = if (isLess(pubkey1, pubkey2))
    BinaryData(Script.write(OP_0 :: OP_PUSHDATA(sig1) :: OP_PUSHDATA(sig2) :: OP_PUSHDATA(multiSig2of2(pubkey1, pubkey2)) :: Nil))
  else
    BinaryData(Script.write(OP_0 :: OP_PUSHDATA(sig2) :: OP_PUSHDATA(sig1) :: OP_PUSHDATA(multiSig2of2(pubkey1, pubkey2)) :: Nil))

  def pay2sh(script: Seq[ScriptElt]) = OP_HASH160 :: OP_PUSHDATA(hash160(Script.write(script))) :: OP_EQUAL :: Nil

  def pay2sh(script: BinaryData) = OP_HASH160 :: OP_PUSHDATA(hash160(script)) :: OP_EQUAL :: Nil

  //TODO : this function does not handle the case where the anchor tx does not spend all previous tx output (meaning there is change)
  def makeAnchorTx(pubkey1: BinaryData, pubkey2: BinaryData, amount: Long, previousTxOutput: OutPoint, signData: SignData): (Transaction, Int) = {
    val tx = Transaction(version = 1,
      txIn = TxIn(outPoint = previousTxOutput, signatureScript = Array.emptyByteArray, sequence = 0xffffffffL) :: Nil,
      txOut = TxOut(amount, publicKeyScript = pay2sh(multiSig2of2(pubkey1, pubkey2))) :: Nil,
      lockTime = 0)
    val signedTx = Transaction.sign(tx, Seq(signData))
    // we don't permute outputs because by convention the multisig output has index = 0
    (signedTx, 0)
  }

  def anchorPubkeyScript(pubkey1: BinaryData, pubkey2: BinaryData): BinaryData = Script.write(pay2sh(multiSig2of2(pubkey1, pubkey2)))

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

  def makeCommitTx(ourFinalKey: BinaryData, theirFinalKey: BinaryData, theirDelay: locktime, anchorTxId: BinaryData, anchorOutputIndex: Int, revocationHash: BinaryData, channelState: ChannelState): Transaction =
    makeCommitTx(inputs = TxIn(OutPoint(anchorTxId, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, ourFinalKey, theirFinalKey, theirDelay, revocationHash, channelState)

  // this way it is easy to reuse the inputTx of an existing commitmentTx
  def makeCommitTx(inputs: Seq[TxIn], ourFinalKey: BinaryData, theirFinalKey: BinaryData, theirDelay: locktime, revocationHash: BinaryData, channelState: ChannelState): Transaction = {
    val redeemScript = redeemSecretOrDelay(ourFinalKey, locktime2long_csv(theirDelay), theirFinalKey, revocationHash: BinaryData)

    val tx = Transaction(
      version = 1,
      txIn = inputs,
      txOut = Seq(
        // TODO : is that the correct way to handle sub-satoshi balances ?
        TxOut(amount = channelState.us.pay_msat / 1000, publicKeyScript = pay2sh(redeemScript)),
        TxOut(amount = channelState.them.pay_msat / 1000, publicKeyScript = pay2sh(OP_PUSHDATA(theirFinalKey) :: OP_CHECKSIG :: Nil))
      ),
      lockTime = 0)

    val sendOuts = channelState.them.htlcs.map(htlc =>
      TxOut(htlc.amountMsat / 1000, pay2sh(scriptPubKeyHtlcSend(ourFinalKey, theirFinalKey, locktime2long_cltv(htlc.expiry), locktime2long_csv(theirDelay), htlc.rHash, revocationHash)))
    )
    val receiveOuts = channelState.us.htlcs.map(htlc =>
      TxOut(htlc.amountMsat / 1000, pay2sh(scriptPubKeyHtlcReceive(ourFinalKey, theirFinalKey, locktime2long_cltv(htlc.expiry), locktime2long_csv(theirDelay), htlc.rHash, revocationHash)))
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
  def makeFinalTx(inputs: Seq[TxIn], ourFinalKey: BinaryData, theirFinalKey: BinaryData, channelState: ChannelState): Transaction = {
    assert(channelState.them.htlcs.isEmpty && channelState.us.htlcs.isEmpty, s"cannot close a channel with pending htlcs (see rusty's state_types.h line 103)")

    permuteOutputs(Transaction(
      version = 1,
      txIn = inputs,
      txOut = Seq(
        TxOut(amount = channelState.them.pay_msat / 1000, publicKeyScript = pay2sh(OP_PUSHDATA(theirFinalKey) :: OP_CHECKSIG :: Nil)),
        TxOut(amount = channelState.us.pay_msat / 1000, publicKeyScript = pay2sh(OP_PUSHDATA(ourFinalKey) :: OP_CHECKSIG :: Nil))
      ),
      lockTime = 0))
  }

  def isFunder(o: open_channel): Boolean = o.anch == open_channel.anchor_offer.WILL_CREATE_ANCHOR

  def initialFunding(a: open_channel, b: open_channel, anchor: open_anchor, fee: Long): ChannelState = {
    require(isFunder(a) ^ isFunder(b))
    val (c1, c2) = ChannelOneSide(pay_msat = anchor.amount - fee, fee_msat = fee, Seq.empty[update_add_htlc]) -> ChannelOneSide(0, 0, Seq.empty[update_add_htlc])
    if (isFunder(a)) ChannelState(c1, c2) else ChannelState(c2, c1)
  }

  def findPublicKeyScriptIndex(tx: Transaction, publicKeyScript: BinaryData): Option[Int] =
    tx.txOut.zipWithIndex.find {
      case (TxOut(_, script), _) => script == publicKeyScript
    } map (_._2)
}
