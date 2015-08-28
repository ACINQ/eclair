package fr.acinq

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.math.BigInteger
import java.security.SecureRandom

import _root_.lightning._
import _root_.lightning.locktime.Locktime.{Blocks, Seconds}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin.Crypto._
import fr.acinq.bitcoin._

import scala.annotation.tailrec

package lightning {


case class ChannelOneSide(pay: Long, fee: Long, htlcs: Seq[update_add_htlc])

case class ChannelState(us: ChannelOneSide, them: ChannelOneSide) {
  /**
   * Because each party needs to be able to compute the other party's commitment tx
   * @return the channel state as seen by the other party
   */
  def reverse: ChannelState = this.copy(them = us, us = them)

  /**
   * Update the channel
   * @param delta as seen by us, if delta > 0 we increase our balance
   * @return the update channel state
   */
  def update(delta: Long): ChannelState = this.copy(them = them.copy(pay = them.pay - delta), us = us.copy(pay = us.pay + delta))

  /**
   * Update our state when we send an htlc
   * @param htlc
   * @return
   */
  def htlc_receive(htlc: update_add_htlc): ChannelState = this.copy(them = them.copy(pay = them.pay - htlc.amount), us = us.copy(htlcs = us.htlcs :+ htlc))

  /**
   * Update our state when we receive an htlc
   * @param htlc
   * @return
   */
  def htlc_send(htlc: update_add_htlc): ChannelState = this.copy(them = them.copy(htlcs = them.htlcs :+ htlc), us = us.copy(pay = us.pay - htlc.amount))

  def htlc_complete(r: sha256_hash): ChannelState = {
    if (us.htlcs.find(_.rHash == bin2sha256(Crypto.sha256(r))).isDefined) {
      // TODO not optimized
      val htlc = us.htlcs.find(_.rHash == bin2sha256(Crypto.sha256(r))).get
      // we were the receiver of this htlc
      this.copy(us = us.copy(pay = us.pay + htlc.amount, htlcs = us.htlcs.filterNot(_ == htlc)))
    } else if (them.htlcs.find(_.rHash == bin2sha256(Crypto.sha256(r))).isDefined) {
      // TODO not optimized
      val htlc = them.htlcs.find(_.rHash == bin2sha256(Crypto.sha256(r))).get
      // we were the sender of this htlc
      this.copy(them = them.copy(pay = them.pay + htlc.amount, htlcs = them.htlcs.filterNot(_ == htlc)))
    } else throw new RuntimeException(s"could not find corresponding htlc (r=$r)")
  }

  def prettyString(): String = s"pay_us=${us.pay} htlcs_us=${us.htlcs.map(_.amount).sum} pay_them=${them.pay} htlcs_them=${them.htlcs.map(_.amount).sum} total=${us.pay + us.htlcs.map(_.amount).sum + them.pay + them.htlcs.map(_.amount).sum}"
}

}

package object lightning {

  val random = new SecureRandom()

  // TODO : should generate them in a deterministic fashion ?
  def randomsha256(): sha256_hash = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    bin2sha256(bytes)
  }

  implicit def bin2sha256(in: BinaryData): sha256_hash = {
    require(in.size == 32)
    val bis = new ByteArrayInputStream(in)
    sha256_hash(Protocol.uint64(bis), Protocol.uint64(bis), Protocol.uint64(bis), Protocol.uint64(bis))
  }

  implicit def array2sha256(in: Array[Byte]): sha256_hash = bin2sha256(in)

  implicit def sha2562bin(in: sha256_hash): BinaryData = {
    val bos = new ByteArrayOutputStream()
    Protocol.writeUInt64(in.a, bos)
    Protocol.writeUInt64(in.b, bos)
    Protocol.writeUInt64(in.c, bos)
    Protocol.writeUInt64(in.d, bos)
    bos.toByteArray
  }

  // TODO : redundant with above, needed for seamless Crypto.sha256(sha256_hash)
  implicit def sha2562seq(in: sha256_hash): Seq[Byte] = sha2562bin(in)

  implicit def bin2pubkey(in: BinaryData) = bitcoin_pubkey(ByteString.copyFrom(in))

  implicit def array2pubkey(in: Array[Byte]) = bin2pubkey(in)

  implicit def pubkey2bin(in: bitcoin_pubkey): BinaryData = in.key.toByteArray

  private def fixSize(in: Array[Byte]): Array[Byte] = in.size match {
    case 32 => in
    case s if s < 32 => Array.fill(32 - s)(0: Byte) ++ in
    case s if s > 32 => in.takeRight(32)
  }

  implicit def bin2signature(in: BinaryData): signature = {
    val (r, s) = Crypto.decodeSignature(in)
    val (ar, as) = (r.toByteArray, s.toByteArray)
    val (ar1, as1) = (fixSize(ar), fixSize(as))
    val (rbis, sbis) = (new ByteArrayInputStream(ar1), new ByteArrayInputStream(as1))
    signature(Protocol.uint64(rbis), Protocol.uint64(rbis), Protocol.uint64(rbis), Protocol.uint64(rbis), Protocol.uint64(sbis), Protocol.uint64(sbis), Protocol.uint64(sbis), Protocol.uint64(sbis))
  }

  implicit def array2signature(in: Array[Byte]): signature = bin2signature(in)

  implicit def signature2bin(in: signature): BinaryData = {
    val rbos = new ByteArrayOutputStream()
    Protocol.writeUInt64(in.r1, rbos)
    Protocol.writeUInt64(in.r2, rbos)
    Protocol.writeUInt64(in.r3, rbos)
    Protocol.writeUInt64(in.r4, rbos)
    val r = new BigInteger(1, rbos.toByteArray)
    val sbos = new ByteArrayOutputStream()
    Protocol.writeUInt64(in.s1, sbos)
    Protocol.writeUInt64(in.s2, sbos)
    Protocol.writeUInt64(in.s3, sbos)
    Protocol.writeUInt64(in.s4, sbos)
    val s = new BigInteger(1, sbos.toByteArray)
    Crypto.encodeSignature(r, s) :+ SIGHASH_ALL.toByte
  }

  implicit def locktime2long(in: locktime): Long = in match {
    case locktime(Blocks(blocks)) => blocks
    case locktime(Seconds(seconds)) => seconds
  }

  @tailrec
  def memcmp(a: List[Byte], b: List[Byte]): Int = (a, b) match {
    case (x, y) if (x.length != y.length) => x.length - y.length
    case (Nil, Nil) => 0
    case (ha :: ta, hb :: tb) if ha == hb => memcmp(ta, tb)
    case (ha :: ta, hb :: tb) => (ha & 0xff) - (hb & 0xff)
  }

  def isLess(a: Seq[Byte], b: Seq[Byte]): Boolean = memcmp(a.dropWhile(_ == 0).toList, b.dropWhile(_ == 0).toList) < 0

  def lessThan(output1: TxOut, output2: TxOut) : Boolean = (output1, output2) match {
    case (TxOut(amount1, script1), TxOut(amount2, script2)) if amount1 == amount2 => memcmp(script1.toList, script2.toList) < 0
    case (TxOut(amount1, _), TxOut(amount2, _)) => amount1 < amount2
  }

  def permuteOutputs(tx: Transaction) : Transaction = tx.copy(txOut = tx.txOut.sortWith(lessThan))

  def multiSig2of2(pubkey1: BinaryData, pubkey2: BinaryData): BinaryData = if (isLess(pubkey1, pubkey2))
    BinaryData(Script.createMultiSigMofN(2, Seq(pubkey1, pubkey2)))
  else
    BinaryData(Script.createMultiSigMofN(2, Seq(pubkey2, pubkey1)))


  def sigScript2of2(sig1: BinaryData, sig2: BinaryData, pubkey1: BinaryData, pubkey2: BinaryData): BinaryData = if (isLess(pubkey1, pubkey2))
    BinaryData(Script.write(OP_0 :: OP_PUSHDATA(sig1) :: OP_PUSHDATA(sig2) :: OP_PUSHDATA(multiSig2of2(pubkey1, pubkey2)) :: Nil))
  else
    BinaryData(Script.write(OP_0 :: OP_PUSHDATA(sig2) :: OP_PUSHDATA(sig1) :: OP_PUSHDATA(multiSig2of2(pubkey1, pubkey2)) :: Nil))

  def pay2sh(script: Seq[ScriptElt]) = OP_HASH160 :: OP_PUSHDATA(hash160(Script.write(script))) :: OP_EQUAL :: Nil

  def makeAnchorTx(pubkey1: BinaryData, pubkey2: BinaryData, amount: Long, previousTxOutput: OutPoint, signData: SignData): Transaction = {
    val scriptPubKey = if (isLess(pubkey1, pubkey2))
      Script.createMultiSigMofN(2, Seq(pubkey1, pubkey2))
    else
      Script.createMultiSigMofN(2, Seq(pubkey2, pubkey1))

    val tx = Transaction(version = 1,
      txIn = TxIn(outPoint = previousTxOutput, signatureScript = Array.emptyByteArray, sequence = 0xffffffffL) :: Nil,
      txOut = TxOut(amount, publicKeyScript = OP_HASH160 :: OP_PUSHDATA(hash160(scriptPubKey)) :: OP_EQUAL :: Nil) :: Nil,
      lockTime = 0)
    val signedTx = Transaction.sign(tx, Seq(signData))
    // we don't permute outputs because by convention the multisig output as index = 0
    signedTx
  }

  def redeemSecretOrDelay(delayedKey: BinaryData, lockTime: Long, keyIfSecretKnown: BinaryData, hashOfSecret: BinaryData): Seq[ScriptElt] = {
    // @formatter:off
    OP_HASH160 :: OP_PUSHDATA(ripemd160(hashOfSecret)) :: OP_EQUAL ::
    OP_IF ::
      OP_PUSHDATA(keyIfSecretKnown) ::
    OP_ELSE ::
      OP_PUSHDATA(Script.encodeNumber(lockTime)) :: OP_CHECKSEQUENCEVERIFY :: OP_DROP :: OP_PUSHDATA(delayedKey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  def scriptPubKeyHtlcSend(ourkey: BinaryData, theirkey: BinaryData, value: Long, htlc_abstimeout: Long, locktime: Long, commit_revoke: BinaryData, rhash: BinaryData): Seq[ScriptElt] = {
    // @formatter:off
    OP_HASH160 :: OP_DUP ::
    OP_PUSHDATA(ripemd160(rhash)) :: OP_EQUAL ::
    OP_SWAP :: OP_PUSHDATA(ripemd160(commit_revoke)) :: OP_EQUAL :: OP_ADD ::
    OP_IF ::
      OP_PUSHDATA(theirkey) ::
    OP_ELSE ::
      OP_PUSHDATA(Script.encodeNumber(htlc_abstimeout)) :: OP_CHECKLOCKTIMEVERIFY :: OP_PUSHDATA(Script.encodeNumber(locktime)) :: OP_CHECKSEQUENCEVERIFY :: OP_2DROP :: OP_PUSHDATA(ourkey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  def scriptPubKeyHtlcReceive(ourkey: BinaryData, theirkey: BinaryData, value: Long, htlc_abstimeout: Long, locktime: Long, commit_revoke: BinaryData, rhash: BinaryData): Seq[ScriptElt] = {
    // @formatter:off
    OP_HASH160 :: OP_DUP ::
    OP_PUSHDATA(ripemd160(rhash)) :: OP_EQUAL ::
    OP_IF ::
      OP_PUSHDATA(Script.encodeNumber(locktime)) :: OP_CHECKSEQUENCEVERIFY :: OP_2DROP :: OP_PUSHDATA(ourkey) ::
    OP_ELSE ::
      OP_PUSHDATA(ripemd160(commit_revoke)) :: OP_EQUAL ::
      OP_NOTIF ::
        OP_PUSHDATA(Script.encodeNumber(htlc_abstimeout)) :: OP_CHECKLOCKTIMEVERIFY :: OP_DROP ::
      OP_ENDIF ::
      OP_PUSHDATA(theirkey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  //TODO : do we really need this ?
  def makeCommitTx(ours: open_channel, theirs: open_channel, anchor: open_anchor, revocationHash: BinaryData, channelState: ChannelState): Transaction =
    makeCommitTx(ours.finalKey, theirs.finalKey, theirs.delay, anchor.txid, anchor.outputIndex, revocationHash, channelState)


  def makeCommitTx(ourFinalKey: BinaryData, theirFinalKey: BinaryData, theirDelay: Long, anchorTxId: BinaryData, anchorOutputIndex: Int, revocationHash: BinaryData, channelState: ChannelState): Transaction =
    makeCommitTx(inputs = TxIn(OutPoint(anchorTxId, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, ourFinalKey, theirFinalKey, theirDelay, revocationHash, channelState)

  // this way it is easy to reuse the inputTx of an existing commitmentTx
  def makeCommitTx(inputs: Seq[TxIn], ourFinalKey: BinaryData, theirFinalKey: BinaryData, theirDelay: Long, revocationHash: BinaryData, channelState: ChannelState): Transaction = {
    val redeemScript = redeemSecretOrDelay(ourFinalKey, theirDelay, theirFinalKey, revocationHash: BinaryData)

    val tx = Transaction(
      version = 1,
      txIn = inputs,
      txOut = Seq(
        TxOut(amount = channelState.them.pay, publicKeyScript = pay2sh(redeemScript)),
        TxOut(amount = channelState.us.pay, publicKeyScript = pay2sh(OP_PUSHDATA(theirFinalKey) :: OP_CHECKSIG :: Nil))
      ),
      lockTime = 0)

    val sendOuts = channelState.them.htlcs.map(htlc => {
      TxOut(htlc.amount, pay2sh(scriptPubKeyHtlcSend(ourFinalKey, theirFinalKey, htlc.amount, htlc.expiry, theirDelay, htlc.rHash, htlc.revocationHash)))
    })
    val receiveOuts = channelState.us.htlcs.map(htlc => {
      TxOut(htlc.amount, pay2sh(scriptPubKeyHtlcReceive(ourFinalKey, theirFinalKey, htlc.amount, htlc.expiry, theirDelay, htlc.rHash, htlc.revocationHash)))
    })
    val tx1 = tx.copy(txOut = tx.txOut ++ sendOuts ++ receiveOuts)
    permuteOutputs(tx1)
  }

  /**
   * This is a simple tx with a multisig input and two pay2pk output
   * @param inputs
   * @param ourFinalKey
   * @param theirFinalKey
   * @param channelState
   * @return
   */
  def makeFinalTx(inputs: Seq[TxIn], ourFinalKey: BinaryData, theirFinalKey: BinaryData, channelState: ChannelState): Transaction = {
    // TODO : is this the proper behaviour ?
    assert(channelState.them.htlcs.isEmpty && channelState.us.htlcs.isEmpty, s"cannot close a channel with pending htlcs (not sure this is in the specs)")
    permuteOutputs(Transaction(
      version = 1,
      txIn = inputs,
      txOut = Seq(
        TxOut(amount = channelState.them.pay, publicKeyScript = OP_DUP :: OP_HASH160 :: OP_PUSHDATA(theirFinalKey) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil),
        TxOut(amount = channelState.us.pay, publicKeyScript = OP_DUP :: OP_HASH160 :: OP_PUSHDATA(ourFinalKey) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil)),
      lockTime = 0))
  }

  def isFunder(o: open_channel): Boolean = o.anch == open_channel.anchor_offer.WILL_CREATE_ANCHOR

  def initialFunding(a: open_channel, b: open_channel, anchor: open_anchor, fee: Long): ChannelState = {
    require(isFunder(a) ^ isFunder(b))
    val (c1, c2) = ChannelOneSide(pay = anchor.amount - fee, fee = fee, Seq.empty[update_add_htlc]) -> ChannelOneSide(0, 0, Seq.empty[update_add_htlc])
    if (isFunder(a)) ChannelState(c1, c2) else ChannelState(c2, c1)
  }
}


