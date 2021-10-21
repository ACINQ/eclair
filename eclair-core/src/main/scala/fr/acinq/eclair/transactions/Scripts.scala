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

import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin._
import SigHash._
import fr.acinq.eclair.transactions.Transactions.{AnchorOutputsCommitmentFormat, CommitmentFormat, DefaultCommitmentFormat}
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta}
import scodec.bits.ByteVector
import fr.acinq.eclair.KotlinUtils._

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
      new ScriptWitness((Seq(ByteVector.empty, der(sig1), der(sig2), ByteVector.view(write(multiSig2of2(pubkey1, pubkey2)))).map(scodecbytevector2acinqbytevector)))
    } else {
      new ScriptWitness((Seq(ByteVector.empty, der(sig2), der(sig1), ByteVector.view(write(multiSig2of2(pubkey1, pubkey2)))).map(scodecbytevector2acinqbytevector)))
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
    case 0 => OP_0.INSTANCE
    case -1 => OP_1NEGATE.INSTANCE
    case x if x >= 1 && x <= 16 => ScriptEltMapping.code2elt.get((ScriptEltMapping.elt2code.get(OP_1.INSTANCE) + x - 1).toInt)
    case _ => new OP_PUSHDATA(Script.encodeNumber(n))
  }

  /**
   * This function interprets the locktime for the given transaction, and returns the block height before which this tx cannot be published.
   * By convention in bitcoin, depending of the value of locktime it might be a number of blocks or a number of seconds since epoch.
   * This function does not support the case when the locktime is a number of seconds that is not way in the past.
   * NB: We use this property in lightning to store data in this field.
   *
   * @return the block height before which this tx cannot be published.
   */
  def cltvTimeout(tx: Transaction): Long =
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

  private def sequenceToBlockHeight(sequence: Long): Long = {
    if ((sequence & TxIn.SEQUENCE_LOCKTIME_DISABLE_FLAG) != 0) {
      0
    } else {
      require((sequence & TxIn.SEQUENCE_LOCKTIME_TYPE_FLAG) == 0, "CSV timeout must use block heights, not block times")
      sequence & TxIn.SEQUENCE_LOCKTIME_MASK
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
    OP_IF.INSTANCE ::
      new OP_PUSHDATA(revocationPubkey) ::
    OP_ELSE.INSTANCE ::
      encodeNumber(toSelfDelay.toInt) :: OP_CHECKSEQUENCEVERIFY.INSTANCE :: OP_DROP.INSTANCE ::
      new OP_PUSHDATA(localDelayedPaymentPubkey) ::
    OP_ENDIF.INSTANCE ::
    OP_CHECKSIG.INSTANCE :: Nil
    // @formatter:on
  }

  /**
   * This witness script spends a [[toLocalDelayed]] output using a local sig after a delay
   */
  def witnessToLocalDelayedAfterDelay(localSig: ByteVector64, toLocalDelayedScript: ByteVector) =
    new ScriptWitness((der(localSig) :: ByteVector.empty :: toLocalDelayedScript :: Nil).map(scodecbytevector2acinqbytevector))

  /**
   * This witness script spends (steals) a [[toLocalDelayed]] output using a revocation key as a punishment
   * for having published a revoked transaction
   */
  def witnessToLocalDelayedWithRevocationSig(revocationSig: ByteVector64, toLocalScript: ByteVector) =
    new ScriptWitness((der(revocationSig) :: ByteVector(1) :: toLocalScript :: Nil).map(scodecbytevector2acinqbytevector))

  /**
   * With the anchor outputs format, the to_remote output is delayed with a CSV 1 to allow CPFP carve-out on anchors.
   */
  def toRemoteDelayed(remotePaymentPubkey: PublicKey): Seq[ScriptElt] = {
    new OP_PUSHDATA(remotePaymentPubkey) :: OP_CHECKSIGVERIFY.INSTANCE :: OP_1.INSTANCE :: OP_CHECKSEQUENCEVERIFY.INSTANCE :: Nil
  }

  /**
   * If remote publishes its commit tx where there was a to_remote delayed output (anchor outputs format), then local
   * uses this script to claim its funds (consumes to_remote script from commit tx).
   */
  def witnessClaimToRemoteDelayedFromCommitTx(localSig: ByteVector64, toRemoteDelayedScript: ByteVector) =
    new ScriptWitness((der(localSig) :: toRemoteDelayedScript :: Nil).map(scodecbytevector2acinqbytevector))

  /**
   * Each participant has its own anchor output that locks to their funding key. This allows using CPFP carve-out (see
   * https://github.com/bitcoin/bitcoin/pull/15681) to speed up confirmation of a commitment transaction.
   */
  def anchor(fundingPubkey: PublicKey): Seq[ScriptElt] = {
    // @formatter:off
    new OP_PUSHDATA(fundingPubkey) :: OP_CHECKSIG.INSTANCE :: OP_IFDUP.INSTANCE ::
    OP_NOTIF.INSTANCE ::
      OP_16 .INSTANCE:: OP_CHECKSEQUENCEVERIFY.INSTANCE ::
    OP_ENDIF.INSTANCE :: Nil
    // @formatter:on
  }

  /**
   * This witness script spends a local [[anchor]] output using a local sig.
   */
  def witnessAnchor(localSig: ByteVector64, anchorScript: ByteVector) = new ScriptWitness((der(localSig) :: anchorScript :: Nil).map(scodecbytevector2acinqbytevector))

  /**
   * This witness script spends either a local or remote [[anchor]] output after its CSV delay.
   */
  def witnessAnchorAfterDelay(anchorScript: ByteVector) = new ScriptWitness((ByteVector.empty :: anchorScript :: Nil).map(scodecbytevector2acinqbytevector))

  def htlcOffered(localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey, revocationPubKey: PublicKey, paymentHash: Array[Byte], commitmentFormat: CommitmentFormat): Seq[ScriptElt] = {
    val addCsvDelay = commitmentFormat match {
      case DefaultCommitmentFormat => false
      case _: AnchorOutputsCommitmentFormat => true
    }
    // @formatter:off
    // To you with revocation key
    OP_DUP.INSTANCE :: OP_HASH160.INSTANCE :: new OP_PUSHDATA(revocationPubKey.hash160) :: OP_EQUAL.INSTANCE ::
    OP_IF.INSTANCE ::
        OP_CHECKSIG.INSTANCE ::
    OP_ELSE.INSTANCE ::
        new OP_PUSHDATA(remoteHtlcPubkey) :: OP_SWAP.INSTANCE  :: OP_SIZE.INSTANCE :: encodeNumber(32) :: OP_EQUAL.INSTANCE ::
        OP_NOTIF.INSTANCE ::
            // To me via HTLC-timeout transaction (timelocked).
            OP_DROP.INSTANCE :: OP_2.INSTANCE :: OP_SWAP.INSTANCE :: new OP_PUSHDATA(localHtlcPubkey) :: OP_2.INSTANCE :: OP_CHECKMULTISIG.INSTANCE ::
        OP_ELSE.INSTANCE ::
            OP_HASH160.INSTANCE :: new OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY.INSTANCE ::
            OP_CHECKSIG.INSTANCE ::
        OP_ENDIF.INSTANCE ::
    (if (addCsvDelay) {
        OP_1.INSTANCE :: OP_CHECKSEQUENCEVERIFY.INSTANCE :: OP_DROP.INSTANCE ::
    OP_ENDIF.INSTANCE :: Nil
    } else {
    OP_ENDIF.INSTANCE :: Nil
    })
    // @formatter:on
  }

  /**
   * This is the witness script of the 2nd-stage HTLC Success transaction (consumes htlcOffered script from commit tx)
   */
  def witnessHtlcSuccess(localSig: ByteVector64, remoteSig: ByteVector64, paymentPreimage: ByteVector32, htlcOfferedScript: ByteVector, commitmentFormat: CommitmentFormat) =
    new ScriptWitness((ByteVector.empty :: der(remoteSig, htlcRemoteSighash(commitmentFormat)) :: der(localSig) :: ByteVector.view(paymentPreimage.toByteArray) :: htlcOfferedScript :: Nil).map(scodecbytevector2acinqbytevector))

  /** Extract the payment preimage from a 2nd-stage HTLC Success transaction's witness script */
  def extractPreimageFromHtlcSuccess: PartialFunction[ScriptWitness, ByteVector32] = {
    case witness if witness.stack.size() == 5 && witness.stack.get(0).isEmpty && witness.stack.get(3).size() == 32 => new ByteVector32(witness.stack.get(3))
//    case ScriptWitness(Seq(ByteVector.empty, _, _, paymentPreimage, _)) if paymentPreimage.size == 32 => ByteVector32(paymentPreimage)
  }

  /**
   * If remote publishes its commit tx where there was a remote->local htlc, then local uses this script to
   * claim its funds using a payment preimage (consumes htlcOffered script from commit tx)
   */
  def witnessClaimHtlcSuccessFromCommitTx(localSig: ByteVector64, paymentPreimage: ByteVector32, htlcOffered: ByteVector) =
    new ScriptWitness((der(localSig) :: ByteVector.view(paymentPreimage.toByteArray) :: htlcOffered :: Nil).map(scodecbytevector2acinqbytevector))

  /** Extract the payment preimage from from a fulfilled offered htlc. */
  def extractPreimageFromClaimHtlcSuccess: PartialFunction[ScriptWitness, ByteVector32] = {
    case witness if witness.stack.size() == 3 && witness.stack.get(1).size() == 32 => new ByteVector32(witness.stack.get(1))
    //case ScriptWitness(Seq(_, paymentPreimage, _)) if paymentPreimage.size == 32 => ByteVector32(paymentPreimage)
  }

  def htlcReceived(localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey, revocationPubKey: PublicKey, paymentHash: Array[Byte], lockTime: CltvExpiry, commitmentFormat: CommitmentFormat): Seq[ScriptElt] = {
    val addCsvDelay = commitmentFormat match {
      case DefaultCommitmentFormat => false
      case _: AnchorOutputsCommitmentFormat => true
    }
    // @formatter:off
    // To you with revocation key
    OP_DUP.INSTANCE :: OP_HASH160.INSTANCE :: new OP_PUSHDATA(revocationPubKey.hash160) :: OP_EQUAL.INSTANCE ::
    OP_IF.INSTANCE  ::
        OP_CHECKSIG.INSTANCE ::
    OP_ELSE.INSTANCE ::
      new OP_PUSHDATA(remoteHtlcPubkey) :: OP_SWAP.INSTANCE :: OP_SIZE.INSTANCE :: encodeNumber(32) :: OP_EQUAL.INSTANCE ::
        OP_IF.INSTANCE  ::
            // To me via HTLC-success transaction.
            OP_HASH160.INSTANCE :: new OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY.INSTANCE ::
            OP_2.INSTANCE :: OP_SWAP.INSTANCE :: new OP_PUSHDATA(localHtlcPubkey) :: OP_2.INSTANCE :: OP_CHECKMULTISIG.INSTANCE ::
        OP_ELSE.INSTANCE ::
            // To you after timeout.
            OP_DROP.INSTANCE :: encodeNumber(lockTime.toLong) :: OP_CHECKLOCKTIMEVERIFY.INSTANCE :: OP_DROP.INSTANCE ::
            OP_CHECKSIG.INSTANCE ::
        OP_ENDIF.INSTANCE ::
    (if (addCsvDelay) {
        OP_1.INSTANCE :: OP_CHECKSEQUENCEVERIFY.INSTANCE :: OP_DROP.INSTANCE ::
    OP_ENDIF.INSTANCE :: Nil
    } else {
    OP_ENDIF.INSTANCE :: Nil
    })
    // @formatter:on
  }

    /**
   * This is the witness script of the 2nd-stage HTLC Timeout transaction (consumes htlcOffered script from commit tx)
   */
  def witnessHtlcTimeout(localSig: ByteVector64, remoteSig: ByteVector64, htlcOfferedScript: ByteVector, commitmentFormat: CommitmentFormat) =
    new ScriptWitness((ByteVector.empty :: der(remoteSig, htlcRemoteSighash(commitmentFormat)) :: der(localSig) :: ByteVector.empty :: htlcOfferedScript :: Nil).map(scodecbytevector2acinqbytevector))

  /** Extract the payment hash from a 2nd-stage HTLC Timeout transaction's witness script */
  def extractPaymentHashFromHtlcTimeout: PartialFunction[ScriptWitness, ByteVector] = {
    case witness if witness.stack.size() == 5 && witness.stack.get(0).isEmpty && witness.stack.get(3).isEmpty => witness.stack.get(4).slice(109, 109 + 20)
    //case ScriptWitness(Seq(ByteVector.empty, _, _, ByteVector.empty, htlcOfferedScript)) => htlcOfferedScript.slice(109, 109 + 20)
  }

  /**
   * If remote publishes its commit tx where there was a local->remote htlc, then local uses this script to
   * claim its funds after timeout (consumes htlcReceived script from commit tx)
   */
  def witnessClaimHtlcTimeoutFromCommitTx(localSig: ByteVector64, htlcReceivedScript: ByteVector) =
    new ScriptWitness((der(localSig) :: ByteVector.empty :: htlcReceivedScript :: Nil).map(scodecbytevector2acinqbytevector))

  /** Extract the payment hash from a timed-out received htlc. */
  def extractPaymentHashFromClaimHtlcTimeout: PartialFunction[ScriptWitness, ByteVector] = {
    case witness if witness.stack.size() == 3 && witness.stack.get(1).isEmpty => witness.stack.get(2).slice(69, 69 + 20)
    //case ScriptWitness(Seq(_, ByteVector.empty, htlcReceivedScript)) => htlcReceivedScript.slice(69, 69 + 20)
  }

  /**
   * This witness script spends (steals) a [[htlcOffered]] or [[htlcReceived]] output using a revocation key as a punishment
   * for having published a revoked transaction
   */
  def witnessHtlcWithRevocationSig(revocationSig: ByteVector64, revocationPubkey: PublicKey, htlcScript: ByteVector) =
    new ScriptWitness((der(revocationSig) :: ByteVector.view(revocationPubkey.value.toByteArray) :: htlcScript :: Nil).map(scodecbytevector2acinqbytevector))

}