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
import fr.acinq.bitcoin.SigHash._
import fr.acinq.bitcoin.TxIn.{SEQUENCE_LOCKTIME_DISABLE_FLAG, SEQUENCE_LOCKTIME_MASK, SEQUENCE_LOCKTIME_TYPE_FLAG}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.Script._
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.eclair.transactions.Transactions.{AnchorOutputsCommitmentFormat, CommitmentFormat, DefaultCommitmentFormat}
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta}
import scodec.bits.ByteVector

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
    case _: AnchorOutputsCommitmentFormat => SIGHASH_SINGLE | SIGHASH_ANYONECANPAY
  }

  def multiSig2of2(pubkey1: PublicKey, pubkey2: PublicKey): Seq[ScriptElt] =
    if (LexicographicalOrdering.isLessThan(pubkey1.value, pubkey2.value)) {
      Script.createMultiSigMofN(2, Seq(pubkey1, pubkey2))
    } else {
      Script.createMultiSigMofN(2, Seq(pubkey2, pubkey1))
    }

  /**
   * @return a script witness that matches the msig 2-of-2 pubkey script for pubkey1 and pubkey2
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
  def encodeNumber(n: Long): ScriptElt = n match {
    case 0 => OP_0
    case -1 => OP_1NEGATE
    case x if x >= 1 && x <= 16 => ScriptElt.code2elt((ScriptElt.elt2code(OP_1) + x - 1).toInt).get
    case _ => OP_PUSHDATA(Script.encodeNumber(n))
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
    }
    else {
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
  def csvTimeouts(tx: Transaction): Map[ByteVector32, Long] = {
    if (tx.version < 2) {
      Map.empty
    } else {
      tx.txIn.foldLeft(Map.empty[ByteVector32, Long]) { case (current, txIn) =>
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

  def toLocalDelayed(revocationPubkey: PublicKey, toSelfDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey): Seq[ScriptElt] = {
    // @formatter:off
    OP_IF ::
      OP_PUSHDATA(revocationPubkey) ::
    OP_ELSE ::
      encodeNumber(toSelfDelay.toInt) :: OP_CHECKSEQUENCEVERIFY :: OP_DROP ::
      OP_PUSHDATA(localDelayedPaymentPubkey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  /**
   * This witness script spends a [[toLocalDelayed]] output using a local sig after a delay
   */
  def witnessToLocalDelayedAfterDelay(localSig: ByteVector64, toLocalDelayedScript: ByteVector) =
    ScriptWitness(der(localSig) :: ByteVector.empty :: toLocalDelayedScript :: Nil)

  /**
   * This witness script spends (steals) a [[toLocalDelayed]] output using a revocation key as a punishment
   * for having published a revoked transaction
   */
  def witnessToLocalDelayedWithRevocationSig(revocationSig: ByteVector64, toLocalScript: ByteVector) =
    ScriptWitness(der(revocationSig) :: ByteVector(1) :: toLocalScript :: Nil)

  /**
   * With the anchor outputs format, the to_remote output is delayed with a CSV 1 to allow CPFP carve-out on anchors.
   */
  def toRemoteDelayed(remotePaymentPubkey: PublicKey): Seq[ScriptElt] = {
    OP_PUSHDATA(remotePaymentPubkey) :: OP_CHECKSIGVERIFY :: OP_1 :: OP_CHECKSEQUENCEVERIFY :: Nil
  }

  /**
   * If remote publishes its commit tx where there was a to_remote delayed output (anchor outputs format), then local
   * uses this script to claim its funds (consumes to_remote script from commit tx).
   */
  def witnessClaimToRemoteDelayedFromCommitTx(localSig: ByteVector64, toRemoteDelayedScript: ByteVector) =
    ScriptWitness(der(localSig) :: toRemoteDelayedScript :: Nil)

  /**
   * Each participant has its own anchor output that locks to their funding key. This allows using CPFP carve-out (see
   * https://github.com/bitcoin/bitcoin/pull/15681) to speed up confirmation of a commitment transaction.
   */
  def anchor(fundingPubkey: PublicKey): Seq[ScriptElt] = {
    // @formatter:off
    OP_PUSHDATA(fundingPubkey) :: OP_CHECKSIG :: OP_IFDUP ::
    OP_NOTIF ::
      OP_16 :: OP_CHECKSEQUENCEVERIFY ::
    OP_ENDIF :: Nil
    // @formatter:on
  }

  /**
   * This witness script spends a local [[anchor]] output using a local sig.
   */
  def witnessAnchor(localSig: ByteVector64, anchorScript: ByteVector) = ScriptWitness(der(localSig) :: anchorScript :: Nil)

  /**
   * This witness script spends either a local or remote [[anchor]] output after its CSV delay.
   */
  def witnessAnchorAfterDelay(anchorScript: ByteVector) = ScriptWitness(ByteVector.empty :: anchorScript :: Nil)

  def htlcOffered(localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey, revocationPubKey: PublicKey, paymentHash: ByteVector, commitmentFormat: CommitmentFormat): Seq[ScriptElt] = {
    val addCsvDelay = commitmentFormat match {
      case DefaultCommitmentFormat => false
      case _: AnchorOutputsCommitmentFormat => true
    }
    // @formatter:off
    // To you with revocation key
    OP_DUP :: OP_HASH160 :: OP_PUSHDATA(revocationPubKey.hash160) :: OP_EQUAL ::
    OP_IF ::
        OP_CHECKSIG ::
    OP_ELSE ::
        OP_PUSHDATA(remoteHtlcPubkey) :: OP_SWAP  :: OP_SIZE :: encodeNumber(32) :: OP_EQUAL ::
        OP_NOTIF ::
            // To me via HTLC-timeout transaction (timelocked).
            OP_DROP :: OP_2 :: OP_SWAP :: OP_PUSHDATA(localHtlcPubkey) :: OP_2 :: OP_CHECKMULTISIG ::
        OP_ELSE ::
            OP_HASH160 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
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
  def witnessHtlcSuccess(localSig: ByteVector64, remoteSig: ByteVector64, paymentPreimage: ByteVector32, htlcOfferedScript: ByteVector, commitmentFormat: CommitmentFormat) =
    ScriptWitness(ByteVector.empty :: der(remoteSig, htlcRemoteSighash(commitmentFormat)) :: der(localSig) :: paymentPreimage.bytes :: htlcOfferedScript :: Nil)

  /** Extract the payment preimage from a 2nd-stage HTLC Success transaction's witness script */
  def extractPreimageFromHtlcSuccess: PartialFunction[ScriptWitness, ByteVector32] = {
    case ScriptWitness(Seq(ByteVector.empty, _, _, paymentPreimage, _)) if paymentPreimage.size == 32 => ByteVector32(paymentPreimage)
  }

  /**
   * If remote publishes its commit tx where there was a remote->local htlc, then local uses this script to
   * claim its funds using a payment preimage (consumes htlcOffered script from commit tx)
   */
  def witnessClaimHtlcSuccessFromCommitTx(localSig: ByteVector64, paymentPreimage: ByteVector32, htlcOffered: ByteVector) =
    ScriptWitness(der(localSig) :: paymentPreimage.bytes :: htlcOffered :: Nil)

  /** Extract the payment preimage from from a fulfilled offered htlc. */
  def extractPreimageFromClaimHtlcSuccess: PartialFunction[ScriptWitness, ByteVector32] = {
    case ScriptWitness(Seq(_, paymentPreimage, _)) if paymentPreimage.size == 32 => ByteVector32(paymentPreimage)
  }

  def htlcReceived(localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey, revocationPubKey: PublicKey, paymentHash: ByteVector, lockTime: CltvExpiry, commitmentFormat: CommitmentFormat): Seq[ScriptElt] = {
    val addCsvDelay = commitmentFormat match {
      case DefaultCommitmentFormat => false
      case _: AnchorOutputsCommitmentFormat => true
    }
    // @formatter:off
    // To you with revocation key
    OP_DUP :: OP_HASH160 :: OP_PUSHDATA(revocationPubKey.hash160) :: OP_EQUAL ::
    OP_IF ::
        OP_CHECKSIG ::
    OP_ELSE ::
        OP_PUSHDATA(remoteHtlcPubkey) :: OP_SWAP :: OP_SIZE :: encodeNumber(32) :: OP_EQUAL ::
        OP_IF ::
            // To me via HTLC-success transaction.
            OP_HASH160 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
            OP_2 :: OP_SWAP :: OP_PUSHDATA(localHtlcPubkey) :: OP_2 :: OP_CHECKMULTISIG ::
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
  def witnessHtlcTimeout(localSig: ByteVector64, remoteSig: ByteVector64, htlcOfferedScript: ByteVector, commitmentFormat: CommitmentFormat) =
    ScriptWitness(ByteVector.empty :: der(remoteSig, htlcRemoteSighash(commitmentFormat)) :: der(localSig) :: ByteVector.empty :: htlcOfferedScript :: Nil)

  /** Extract the payment hash from a 2nd-stage HTLC Timeout transaction's witness script */
  def extractPaymentHashFromHtlcTimeout: PartialFunction[ScriptWitness, ByteVector] = {
    case ScriptWitness(Seq(ByteVector.empty, _, _, ByteVector.empty, htlcOfferedScript)) => htlcOfferedScript.slice(109, 109 + 20)
  }

  /**
   * If remote publishes its commit tx where there was a local->remote htlc, then local uses this script to
   * claim its funds after timeout (consumes htlcReceived script from commit tx)
   */
  def witnessClaimHtlcTimeoutFromCommitTx(localSig: ByteVector64, htlcReceivedScript: ByteVector) =
    ScriptWitness(der(localSig) :: ByteVector.empty :: htlcReceivedScript :: Nil)

  /** Extract the payment hash from a timed-out received htlc. */
  def extractPaymentHashFromClaimHtlcTimeout: PartialFunction[ScriptWitness, ByteVector] = {
    case ScriptWitness(Seq(_, ByteVector.empty, htlcReceivedScript)) => htlcReceivedScript.slice(69, 69 + 20)
  }

  /**
   * This witness script spends (steals) a [[htlcOffered]] or [[htlcReceived]] output using a revocation key as a punishment
   * for having published a revoked transaction
   */
  def witnessHtlcWithRevocationSig(revocationSig: ByteVector64, revocationPubkey: PublicKey, htlcScript: ByteVector) =
    ScriptWitness(der(revocationSig) :: revocationPubkey.value :: htlcScript :: Nil)

}