/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.Script.LOCKTIME_THRESHOLD
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.SigHash._
import fr.acinq.bitcoin.TxIn.{SEQUENCE_LOCKTIME_DISABLE_FLAG, SEQUENCE_LOCKTIME_MASK, SEQUENCE_LOCKTIME_TYPE_FLAG}
import fr.acinq.bitcoin.scalacompat.Crypto.{PublicKey, XonlyPublicKey}
import fr.acinq.bitcoin.scalacompat.Script._
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.eclair.crypto.keymanager.{CommitmentPublicKeys, LocalCommitmentKeys, RemoteCommitmentKeys}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta}
import scodec.bits.ByteVector

import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.util.{Success, Try}

/**
 * Created by PM on 02/12/2016.
 */
object Scripts {

  /**
   * Convert a raw ECDSA signature (r,s) to a der-encoded signature that can be used in bitcoin scripts.
   *
   * @param sig         raw ECDSA signature (r,s)
   * @param sighashType sighash flags
   */
  def der(sig: ByteVector64, sighashType: Int = SIGHASH_ALL): ByteVector = Crypto.compact2der(sig) :+ sighashType.toByte

  private def htlcRemoteSighash(commitmentFormat: CommitmentFormat): Int = commitmentFormat match {
    case DefaultCommitmentFormat => SIGHASH_ALL
    case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => SIGHASH_SINGLE | SIGHASH_ANYONECANPAY
  }

  /** Sort public keys using lexicographic ordering. */
  def sort(pubkeys: Seq[PublicKey]): Seq[PublicKey] = pubkeys.sortWith { (a, b) => LexicographicalOrdering.isLessThan(a.value, b.value) }

  def multiSig2of2(pubkey1: PublicKey, pubkey2: PublicKey): Seq[ScriptElt] = Script.createMultiSigMofN(2, sort(Seq(pubkey1, pubkey2)))

  /**
   * @return a script witness that matches the [[multiSig2of2]] pubkey script for pubkey1 and pubkey2
   */
  def witness2of2(sig1: ByteVector64, sig2: ByteVector64, pubkey1: PublicKey, pubkey2: PublicKey): ScriptWitness =
    if (LexicographicalOrdering.isLessThan(pubkey1.value, pubkey2.value)) {
      ScriptWitness(Seq(ByteVector.empty, der(sig1), der(sig2), write(multiSig2of2(pubkey1, pubkey2))))
    } else {
      ScriptWitness(Seq(ByteVector.empty, der(sig2), der(sig1), write(multiSig2of2(pubkey1, pubkey2))))
    }

  /**
   * minimal encoding of a number into a script element:
   * - OP_0 to OP_16 if 0 <= n <= 16
   * - OP_PUSHDATA(encodeNumber(n)) otherwise
   *
   * @param n input number
   * @return a script element that represents n
   */
  private def encodeNumber(n: Long): ScriptElt = n match {
    case 0 => OP_0
    case -1 => OP_1NEGATE
    case x if x >= 1 && x <= 16 => ScriptElt.code2elt((ScriptElt.elt2code(OP_1) + x - 1).toInt).get
    case _ => OP_PUSHDATA(Script.encodeNumber(n))
  }

  /** As defined in https://github.com/lightning/bolts/blob/master/03-transactions.md#dust-limits */
  def dustLimit(scriptPubKey: ByteVector): Satoshi = {
    Try(Script.parse(scriptPubKey)) match {
      case Success(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pubkeyHash, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) if pubkeyHash.size == 20 => 546.sat
      case Success(OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil) if scriptHash.size == 20 => 540.sat
      case Success(OP_0 :: OP_PUSHDATA(pubkeyHash, _) :: Nil) if pubkeyHash.size == 20 => 294.sat
      case Success(OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil) if scriptHash.size == 32 => 330.sat
      case Success((OP_1 | OP_2 | OP_3 | OP_4 | OP_5 | OP_6 | OP_7 | OP_8 | OP_9 | OP_10 | OP_11 | OP_12 | OP_13 | OP_14 | OP_15 | OP_16) :: OP_PUSHDATA(program, _) :: Nil) if 2 <= program.length && program.length <= 40 => 354.sat
      case Success(OP_RETURN :: _) => 0.sat // OP_RETURN is never dust
      case _ => 546.sat
    }
  }

  /** Checks if the given script is an OP_RETURN. */
  def isOpReturn(scriptPubKey: ByteVector): Boolean = {
    Try(Script.parse(scriptPubKey)) match {
      case Success(OP_RETURN :: _) => true
      case _ => false
    }
  }

  /**
   * This function interprets the locktime for the given transaction, and returns the block height before which this tx cannot be published.
   * By convention in bitcoin, depending of the value of locktime it might be a number of blocks or a number of seconds since epoch.
   * This function does not support the case when the locktime is a number of seconds that is not way in the past.
   * NB: We use this property in lightning to store data in this field.
   *
   * @return the block height before which this tx cannot be published.
   */
  def cltvTimeout(tx: Transaction): BlockHeight =
    if (tx.lockTime <= LOCKTIME_THRESHOLD) {
      // locktime is a number of blocks
      BlockHeight(tx.lockTime)
    } else {
      // locktime is a unix epoch timestamp
      require(tx.lockTime <= 0x20FFFFFF, "locktime should be lesser than 0x20FFFFFF")
      // since locktime is very well in the past (0x20FFFFFF is in 1987), it is equivalent to no locktime at all
      BlockHeight(0)
    }

  private def sequenceToBlockHeight(sequence: Long): Long = {
    if ((sequence & SEQUENCE_LOCKTIME_DISABLE_FLAG) != 0) {
      0
    } else {
      require((sequence & SEQUENCE_LOCKTIME_TYPE_FLAG) == 0, "CSV timeout must use block heights, not block times")
      sequence & SEQUENCE_LOCKTIME_MASK
    }
  }

  /**
   * @return the number of confirmations of each parent before which the given transaction can be published.
   */
  def csvTimeouts(tx: Transaction): Map[TxId, Long] = {
    if (tx.version < 2) {
      Map.empty
    } else {
      tx.txIn.foldLeft(Map.empty[TxId, Long]) { case (current, txIn) =>
        val csvTimeout = sequenceToBlockHeight(txIn.sequence)
        if (csvTimeout > 0) {
          val maxCsvTimeout = math.max(csvTimeout, current.getOrElse(txIn.outPoint.txid, 0L))
          current + (txIn.outPoint.txid -> maxCsvTimeout)
        } else {
          current
        }
      }
    }
  }

  def toLocalDelayed(keys: CommitmentPublicKeys, toSelfDelay: CltvExpiryDelta): Seq[ScriptElt] = {
    // @formatter:off
    OP_IF ::
      OP_PUSHDATA(keys.revocationPublicKey) ::
    OP_ELSE ::
      encodeNumber(toSelfDelay.toInt) :: OP_CHECKSEQUENCEVERIFY :: OP_DROP ::
      OP_PUSHDATA(keys.localDelayedPaymentPublicKey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  /**
   * This witness script spends a [[toLocalDelayed]] output using a local sig after a delay
   */
  def witnessToLocalDelayedAfterDelay(localSig: ByteVector64, toLocalDelayedScript: ByteVector): ScriptWitness =
    ScriptWitness(der(localSig) :: ByteVector.empty :: toLocalDelayedScript :: Nil)

  /**
   * This witness script spends (steals) a [[toLocalDelayed]] output using a revocation key as a punishment
   * for having published a revoked transaction
   */
  def witnessToLocalDelayedWithRevocationSig(revocationSig: ByteVector64, toLocalScript: ByteVector): ScriptWitness =
    ScriptWitness(der(revocationSig) :: ByteVector(1) :: toLocalScript :: Nil)

  /**
   * With the anchor outputs format, the to_remote output is delayed with a CSV 1 to allow CPFP carve-out on anchors.
   */
  def toRemoteDelayed(keys: CommitmentPublicKeys): Seq[ScriptElt] = {
    OP_PUSHDATA(keys.remotePaymentPublicKey) :: OP_CHECKSIGVERIFY :: OP_1 :: OP_CHECKSEQUENCEVERIFY :: Nil
  }

  /**
   * If remote publishes its commit tx where there was a to_remote delayed output (anchor outputs format), then local
   * uses this script to claim its funds (consumes to_remote script from commit tx).
   */
  def witnessClaimToRemoteDelayedFromCommitTx(localSig: ByteVector64, toRemoteDelayedScript: ByteVector): ScriptWitness =
    ScriptWitness(der(localSig) :: toRemoteDelayedScript :: Nil)

  /**
   * Each participant has its own anchor output that locks to their funding key. This allows using CPFP carve-out (see
   * https://github.com/bitcoin/bitcoin/pull/15681) to speed up confirmation of a commitment transaction.
   */
  def anchor(anchorKey: PublicKey): Seq[ScriptElt] = {
    // @formatter:off
    OP_PUSHDATA(anchorKey) :: OP_CHECKSIG :: OP_IFDUP ::
    OP_NOTIF ::
      OP_16 :: OP_CHECKSEQUENCEVERIFY ::
    OP_ENDIF :: Nil
    // @formatter:on
  }

  /**
   * This witness script spends a local [[anchor]] output using a local sig.
   */
  def witnessAnchor(localSig: ByteVector64, anchorScript: ByteVector): ScriptWitness = ScriptWitness(der(localSig) :: anchorScript :: Nil)

  def htlcOffered(keys: CommitmentPublicKeys, paymentHash: ByteVector32, commitmentFormat: CommitmentFormat): Seq[ScriptElt] = {
    val addCsvDelay = commitmentFormat match {
      case DefaultCommitmentFormat => false
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => true
    }
    // @formatter:off
    // To you with revocation key
    OP_DUP :: OP_HASH160 :: OP_PUSHDATA(keys.revocationPublicKey.hash160) :: OP_EQUAL ::
    OP_IF ::
        OP_CHECKSIG ::
    OP_ELSE ::
        OP_PUSHDATA(keys.remoteHtlcPublicKey) :: OP_SWAP  :: OP_SIZE :: encodeNumber(32) :: OP_EQUAL ::
        OP_NOTIF ::
            // To me via HTLC-timeout transaction (timelocked).
            OP_DROP :: OP_2 :: OP_SWAP :: OP_PUSHDATA(keys.localHtlcPublicKey) :: OP_2 :: OP_CHECKMULTISIG ::
        OP_ELSE ::
            OP_HASH160 :: OP_PUSHDATA(Crypto.ripemd160(paymentHash)) :: OP_EQUALVERIFY ::
            OP_CHECKSIG ::
        OP_ENDIF ::
    (if (addCsvDelay) {
        OP_1 :: OP_CHECKSEQUENCEVERIFY :: OP_DROP ::
    OP_ENDIF :: Nil
    } else {
    OP_ENDIF :: Nil
    })
    // @formatter:on
  }

  /**
   * This is the witness script of the 2nd-stage HTLC Success transaction (consumes htlcOffered script from commit tx)
   */
  def witnessHtlcSuccess(localSig: ByteVector64, remoteSig: ByteVector64, paymentPreimage: ByteVector32, htlcOfferedScript: ByteVector, commitmentFormat: CommitmentFormat): ScriptWitness =
    ScriptWitness(ByteVector.empty :: der(remoteSig, htlcRemoteSighash(commitmentFormat)) :: der(localSig) :: paymentPreimage.bytes :: htlcOfferedScript :: Nil)

  /** Extract the payment preimage from a 2nd-stage HTLC Success transaction's witness script */
  def extractPreimageFromHtlcSuccess: PartialFunction[ScriptWitness, ByteVector32] = {
    case ScriptWitness(Seq(ByteVector.empty, _, _, paymentPreimage, _)) if paymentPreimage.size == 32 => ByteVector32(paymentPreimage)
    case ScriptWitness(Seq(_, _, paymentPreimage, _, _)) if paymentPreimage.size == 32 => ByteVector32(paymentPreimage)
  }

  /** Extract payment preimages from a (potentially batched) 2nd-stage HTLC transaction's witnesses. */
  def extractPreimagesFromHtlcSuccess(tx: Transaction): Set[ByteVector32] = tx.txIn.map(_.witness).collect(extractPreimageFromHtlcSuccess).toSet

  /**
   * If remote publishes its commit tx where there was a remote->local htlc, then local uses this script to
   * claim its funds using a payment preimage (consumes htlcOffered script from commit tx)
   */
  def witnessClaimHtlcSuccessFromCommitTx(localSig: ByteVector64, paymentPreimage: ByteVector32, htlcOffered: ByteVector): ScriptWitness =
    ScriptWitness(der(localSig) :: paymentPreimage.bytes :: htlcOffered :: Nil)

  /** Extract the payment preimage from from a fulfilled offered htlc. */
  def extractPreimageFromClaimHtlcSuccess: PartialFunction[ScriptWitness, ByteVector32] = {
    case ScriptWitness(Seq(_, paymentPreimage, _)) if paymentPreimage.size == 32 => ByteVector32(paymentPreimage)
    case ScriptWitness(Seq(_, paymentPreimage, _, _)) if paymentPreimage.size == 32 => ByteVector32(paymentPreimage)
  }

  /** Extract payment preimages from a (potentially batched) claim HTLC transaction's witnesses. */
  def extractPreimagesFromClaimHtlcSuccess(tx: Transaction): Set[ByteVector32] = tx.txIn.map(_.witness).collect(extractPreimageFromClaimHtlcSuccess).toSet

  def htlcReceived(keys: CommitmentPublicKeys, paymentHash: ByteVector32, lockTime: CltvExpiry, commitmentFormat: CommitmentFormat): Seq[ScriptElt] = {
    val addCsvDelay = commitmentFormat match {
      case DefaultCommitmentFormat => false
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => true
    }
    // @formatter:off
    // To you with revocation key
    OP_DUP :: OP_HASH160 :: OP_PUSHDATA(keys.revocationPublicKey.hash160) :: OP_EQUAL ::
    OP_IF ::
        OP_CHECKSIG ::
    OP_ELSE ::
        OP_PUSHDATA(keys.remoteHtlcPublicKey) :: OP_SWAP :: OP_SIZE :: encodeNumber(32) :: OP_EQUAL ::
        OP_IF ::
            // To me via HTLC-success transaction.
            OP_HASH160 :: OP_PUSHDATA(Crypto.ripemd160(paymentHash)) :: OP_EQUALVERIFY ::
            OP_2 :: OP_SWAP :: OP_PUSHDATA(keys.localHtlcPublicKey) :: OP_2 :: OP_CHECKMULTISIG ::
        OP_ELSE ::
            // To you after timeout.
            OP_DROP :: encodeNumber(lockTime.toLong) :: OP_CHECKLOCKTIMEVERIFY :: OP_DROP ::
            OP_CHECKSIG ::
        OP_ENDIF ::
    (if (addCsvDelay) {
        OP_1 :: OP_CHECKSEQUENCEVERIFY :: OP_DROP ::
    OP_ENDIF :: Nil
    } else {
    OP_ENDIF :: Nil
    })
    // @formatter:on
  }

  /**
   * This is the witness script of the 2nd-stage HTLC Timeout transaction (consumes htlcOffered script from commit tx)
   */
  def witnessHtlcTimeout(localSig: ByteVector64, remoteSig: ByteVector64, htlcOfferedScript: ByteVector, commitmentFormat: CommitmentFormat): ScriptWitness =
    ScriptWitness(ByteVector.empty :: der(remoteSig, htlcRemoteSighash(commitmentFormat)) :: der(localSig) :: ByteVector.empty :: htlcOfferedScript :: Nil)

  /**
   * If remote publishes its commit tx where there was a local->remote htlc, then local uses this script to
   * claim its funds after timeout (consumes htlcReceived script from commit tx)
   */
  def witnessClaimHtlcTimeoutFromCommitTx(localSig: ByteVector64, htlcReceivedScript: ByteVector): ScriptWitness =
    ScriptWitness(der(localSig) :: ByteVector.empty :: htlcReceivedScript :: Nil)

  /**
   * This witness script spends (steals) a [[htlcOffered]] or [[htlcReceived]] output using a revocation key as a punishment
   * for having published a revoked transaction
   */
  def witnessHtlcWithRevocationSig(keys: RemoteCommitmentKeys, revocationSig: ByteVector64, htlcScript: ByteVector): ScriptWitness =
    ScriptWitness(der(revocationSig) :: keys.revocationPublicKey.value :: htlcScript :: Nil)

  /**
   * Specific scripts for taproot channels
   */
  object Taproot {

    import KotlinUtils._

    implicit def scala2kmpscript(input: Seq[fr.acinq.bitcoin.scalacompat.ScriptElt]): java.util.List[fr.acinq.bitcoin.ScriptElt] = input.map(e => scala2kmp(e)).asJava

    /**
     * Taproot signatures are usually 64 bytes, unless a non-default sighash is used, in which case it is appended.
     */
    private def encodeSig(sig: ByteVector64, sighashType: Int = SIGHASH_DEFAULT): ByteVector = sighashType match {
      case SIGHASH_DEFAULT | SIGHASH_ALL => sig
      case _ => sig :+ sighashType.toByte
    }

    /**
     * Sort and aggregate the public keys of a musig2 session.
     *
     * @param pubkey1 public key
     * @param pubkey2 public key
     * @return the aggregated public key
     * @see [[fr.acinq.bitcoin.scalacompat.Musig2.aggregateKeys()]]
     */
    def musig2Aggregate(pubkey1: PublicKey, pubkey2: PublicKey): XonlyPublicKey = Musig2.aggregateKeys(sort(Seq(pubkey1, pubkey2)))

    /**
     * "Nothing Up My Sleeve" point, for which there is no known private key.
     */
    val NUMS_POINT: PublicKey = PublicKey(ByteVector.fromValidHex("02dca094751109d0bd055d03565874e8276dd53e926b44e3bd1bb6bf4bc130a279"))

    // miniscript: older(16)
    private val anchorScript: Seq[ScriptElt] = OP_16 :: OP_CHECKSEQUENCEVERIFY :: Nil
    val anchorScriptTree = new ScriptTree.Leaf(anchorScript)

    /**
     * Script used for local or remote anchor outputs.
     * The key used matches the key for the matching node's main output.
     */
    def anchor(anchorKey: PublicKey): Seq[ScriptElt] = {
      Script.pay2tr(anchorKey.xOnly, Some(anchorScriptTree))
    }

    /**
     * Script that can be spent with the revocation key and reveals the delayed payment key to allow observers to claim
     * unused anchor outputs.
     *
     * miniscript: this is not miniscript compatible
     *
     * @return a script that will be used to add a "revocation" leaf to a script tree
     */
    private def toRevocationKey(keys: CommitmentPublicKeys): Seq[ScriptElt] = {
      OP_PUSHDATA(keys.localDelayedPaymentPublicKey.xOnly) :: OP_DROP :: OP_PUSHDATA(keys.revocationPublicKey.xOnly) :: OP_CHECKSIG :: Nil
    }

    /**
     * Script that can be spent by the owner of the commitment transaction after a delay.
     *
     * miniscript: and_v(v:pk(delayed_key),older(delay))
     *
     * @return a script that will be used to add a "to local key" leaf to a script tree
     */
    private def toLocalDelayed(keys: CommitmentPublicKeys, toSelfDelay: CltvExpiryDelta): Seq[ScriptElt] = {
      OP_PUSHDATA(keys.localDelayedPaymentPublicKey.xOnly) :: OP_CHECKSIGVERIFY :: Scripts.encodeNumber(toSelfDelay.toInt) :: OP_CHECKSEQUENCEVERIFY :: Nil
    }

    case class ToLocalScriptTree(localDelayed: ScriptTree.Leaf, revocation: ScriptTree.Leaf) {
      val scriptTree: ScriptTree.Branch = new ScriptTree.Branch(localDelayed, revocation)
    }

    /**
     * @return a script tree with two leaves (to self with delay, and to revocation key)
     */
    def toLocalScriptTree(keys: CommitmentPublicKeys, toSelfDelay: CltvExpiryDelta): ToLocalScriptTree = {
      ToLocalScriptTree(
        new ScriptTree.Leaf(toLocalDelayed(keys, toSelfDelay)),
        new ScriptTree.Leaf(toRevocationKey(keys)),
      )
    }

    /**
     * Script used for the main balance of the owner of the commitment transaction.
     */
    def toLocal(keys: CommitmentPublicKeys, toSelfDelay: CltvExpiryDelta): Seq[ScriptElt] = {
      Script.pay2tr(NUMS_POINT.xOnly, Some(toLocalScriptTree(keys, toSelfDelay).scriptTree))
    }

    /**
     * Script that can be spent by the channel counterparty after a 1-block delay.
     *
     * miniscript: and_v(v:pk(remote_key),older(1))
     *
     * @return a script that will be used to add a "to remote key" leaf to a script tree
     */
    private def toRemoteDelayed(keys: CommitmentPublicKeys): Seq[ScriptElt] = {
      OP_PUSHDATA(keys.remotePaymentPublicKey.xOnly) :: OP_CHECKSIGVERIFY :: OP_1 :: OP_CHECKSEQUENCEVERIFY :: Nil
    }

    /**
     * Script tree used for the main balance of the remote node in our commitment transaction.
     * Note that there is no need for a revocation leaf in that case.
     *
     * @return a script tree with a single leaf (to remote key, with a 1-block CSV delay)
     */
    def toRemoteScriptTree(keys: CommitmentPublicKeys): ScriptTree.Leaf = {
      new ScriptTree.Leaf(toRemoteDelayed(keys))
    }

    /**
     * Script used for the main balance of the remote node in our commitment transaction.
     */
    def toRemote(keys: CommitmentPublicKeys): Seq[ScriptElt] = {
      Script.pay2tr(NUMS_POINT.xOnly, Some(toRemoteScriptTree(keys)))
    }

    /**
     * Script that can be spent when an offered (outgoing) HTLC times out.
     * It is spent using a pre-signed HTLC transaction signed with both keys.
     *
     * miniscript: and_v(v:pk(local_htlc_key),pk(remote_htlc_key))
     *
     * @return a script used to create a "HTLC timeout" leaf in a script tree
     */
    private def offeredHtlcTimeout(keys: CommitmentPublicKeys): Seq[ScriptElt] = {
      OP_PUSHDATA(keys.localHtlcPublicKey.xOnly) :: OP_CHECKSIGVERIFY :: OP_PUSHDATA(keys.remoteHtlcPublicKey.xOnly) :: OP_CHECKSIG :: Nil
    }

    /**
     * Script that can be spent when an offered (outgoing) HTLC is fulfilled.
     * It is spent using a signature from the receiving node and the preimage, with a 1-block delay.
     *
     * miniscript: and_v(v:hash160(H),and_v(v:pk(remote_htlc_key),older(1)))
     *
     * @return a script used to create a "spend offered HTLC" leaf in a script tree
     */
    private def offeredHtlcSuccess(keys: CommitmentPublicKeys, paymentHash: ByteVector32): Seq[ScriptElt] = {
      // @formatter:off
      OP_SIZE :: encodeNumber(32) :: OP_EQUALVERIFY ::
      OP_HASH160 :: OP_PUSHDATA(Crypto.ripemd160(paymentHash)) :: OP_EQUALVERIFY ::
      OP_PUSHDATA(keys.remoteHtlcPublicKey.xOnly) :: OP_CHECKSIGVERIFY ::
      OP_1 :: OP_CHECKSEQUENCEVERIFY :: Nil
      // @formatter:on
    }

    case class OfferedHtlcScriptTree(timeout: ScriptTree.Leaf, success: ScriptTree.Leaf) {
      val scriptTree: ScriptTree.Branch = new ScriptTree.Branch(timeout, success)

      def witnessTimeout(commitKeys: LocalCommitmentKeys, localSig: ByteVector64, remoteSig: ByteVector64): ScriptWitness = {
        Script.witnessScriptPathPay2tr(commitKeys.revocationPublicKey.xOnly, timeout, ScriptWitness(Seq(Taproot.encodeSig(remoteSig, htlcRemoteSighash(ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)), localSig)), scriptTree)
      }

      def witnessSuccess(commitKeys: RemoteCommitmentKeys, localSig: ByteVector64, paymentPreimage: ByteVector32): ScriptWitness = {
        Script.witnessScriptPathPay2tr(commitKeys.revocationPublicKey.xOnly, success, ScriptWitness(Seq(localSig, paymentPreimage)), scriptTree)
      }
    }

    /**
     * Script tree used for offered HTLCs.
     */
    def offeredHtlcScriptTree(keys: CommitmentPublicKeys, paymentHash: ByteVector32): OfferedHtlcScriptTree = {
      OfferedHtlcScriptTree(
        new ScriptTree.Leaf(offeredHtlcTimeout(keys)),
        new ScriptTree.Leaf(offeredHtlcSuccess(keys, paymentHash)),
      )
    }

    /**
     * Script that can be spent when a received (incoming) HTLC times out.
     * It is spent using a signature from the receiving node after an absolute delay and a 1-block relative delay.
     *
     * miniscript: and_v(v:pk(remote_htlc_key),and_v(v:older(1),after(delay)))
     */
    private def receivedHtlcTimeout(keys: CommitmentPublicKeys, expiry: CltvExpiry): Seq[ScriptElt] = {
      // @formatter:off
      OP_PUSHDATA(keys.remoteHtlcPublicKey.xOnly) :: OP_CHECKSIGVERIFY ::
      OP_1 :: OP_CHECKSEQUENCEVERIFY :: OP_VERIFY ::
      encodeNumber(expiry.toLong) :: OP_CHECKLOCKTIMEVERIFY :: Nil
      // @formatter:on
    }

    /**
     * Script that can be spent when a received (incoming) HTLC is fulfilled.
     * It is spent using a pre-signed HTLC transaction signed with both keys and the preimage.
     *
     * miniscript: and_v(v:hash160(H),and_v(v:pk(local_key),pk(remote_key)))
     */
    private def receivedHtlcSuccess(keys: CommitmentPublicKeys, paymentHash: ByteVector32): Seq[ScriptElt] = {
      // @formatter:off
      OP_SIZE :: encodeNumber(32) :: OP_EQUALVERIFY ::
      OP_HASH160 :: OP_PUSHDATA(Crypto.ripemd160(paymentHash)) :: OP_EQUALVERIFY ::
      OP_PUSHDATA(keys.localHtlcPublicKey.xOnly) :: OP_CHECKSIGVERIFY ::
      OP_PUSHDATA(keys.remoteHtlcPublicKey.xOnly) :: OP_CHECKSIG :: Nil
      // @formatter:on
    }

    case class ReceivedHtlcScriptTree(timeout: ScriptTree.Leaf, success: ScriptTree.Leaf) {
      val scriptTree = new ScriptTree.Branch(timeout, success)

      def witnessSuccess(commitKeys: LocalCommitmentKeys, localSig: ByteVector64, remoteSig: ByteVector64, paymentPreimage: ByteVector32): ScriptWitness = {
        Script.witnessScriptPathPay2tr(commitKeys.revocationPublicKey.xOnly, success, ScriptWitness(Seq(Taproot.encodeSig(remoteSig, htlcRemoteSighash(ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)), localSig, paymentPreimage)), scriptTree)
      }

      def witnessTimeout(commitKeys: RemoteCommitmentKeys, localSig: ByteVector64): ScriptWitness = {
        Script.witnessScriptPathPay2tr(commitKeys.revocationPublicKey.xOnly, timeout, ScriptWitness(Seq(localSig)), scriptTree)
      }
    }

    /**
     * Script tree used for received HTLCs.
     */
    def receivedHtlcScriptTree(keys: CommitmentPublicKeys, paymentHash: ByteVector32, expiry: CltvExpiry): ReceivedHtlcScriptTree = {
      ReceivedHtlcScriptTree(
        new ScriptTree.Leaf(receivedHtlcTimeout(keys, expiry)),
        new ScriptTree.Leaf(receivedHtlcSuccess(keys, paymentHash)),
      )
    }

    /**
     * Script tree used for the output of pre-signed HTLC 2nd-stage transactions.
     */
    def htlcDelayedScriptTree(keys: CommitmentPublicKeys, toSelfDelay: CltvExpiryDelta): ScriptTree.Leaf = {
      new ScriptTree.Leaf(toLocalDelayed(keys, toSelfDelay))
    }

    /**
     * Script used for the output of pre-signed HTLC 2nd-stage transactions.
     */
    def htlcDelayed(keys: CommitmentPublicKeys, toSelfDelay: CltvExpiryDelta): Seq[ScriptElt] = {
      Script.pay2tr(keys.revocationPublicKey.xOnly, Some(htlcDelayedScriptTree(keys, toSelfDelay)))
    }
  }
}