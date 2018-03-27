/*
 * Copyright 2018 ACINQ SAS
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

import fr.acinq.bitcoin.Crypto.{PublicKey, ripemd160}
import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin.{BinaryData, LexicographicalOrdering, LockTimeThreshold, OP_0, OP_1, OP_1NEGATE, OP_2, OP_2DROP, OP_ADD, OP_CHECKLOCKTIMEVERIFY, OP_CHECKMULTISIG, OP_CHECKSEQUENCEVERIFY, OP_CHECKSIG, OP_DROP, OP_DUP, OP_ELSE, OP_ENDIF, OP_EQUAL, OP_EQUALVERIFY, OP_HASH160, OP_IF, OP_NOTIF, OP_PUSHDATA, OP_SIZE, OP_SWAP, Satoshi, Script, ScriptElt, ScriptWitness, Transaction, TxIn}

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
    case x if x >= 1 && x <= 16 => ScriptElt.code2elt((ScriptElt.elt2code(OP_1) + x - 1).toInt)
    case _ => OP_PUSHDATA(Script.encodeNumber(n))
  }

  def redeemSecretOrDelay(delayedKey: BinaryData, reltimeout: Long, keyIfSecretKnown: BinaryData, hashOfSecret: BinaryData): Seq[ScriptElt] = {
    // @formatter:off
    OP_HASH160 :: OP_PUSHDATA(ripemd160(hashOfSecret)) :: OP_EQUAL ::
    OP_IF ::
      OP_PUSHDATA(keyIfSecretKnown) ::
    OP_ELSE ::
      encodeNumber(reltimeout):: OP_CHECKSEQUENCEVERIFY :: OP_DROP :: OP_PUSHDATA(delayedKey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  def scriptPubKeyHtlcSend(ourkey: BinaryData, theirkey: BinaryData, abstimeout: Long, reltimeout: Long, rhash: BinaryData, commit_revoke: BinaryData): Seq[ScriptElt] = {
    // values lesser than 16 should be encoded using OP_0..OP_16 instead of OP_PUSHDATA
    require(abstimeout > 16, s"abstimeout=$abstimeout must be greater than 16")
    // @formatter:off
    OP_SIZE :: encodeNumber(32) :: OP_EQUALVERIFY ::
    OP_HASH160 :: OP_DUP ::
    OP_PUSHDATA(ripemd160(rhash)) :: OP_EQUAL ::
    OP_SWAP :: OP_PUSHDATA(ripemd160(commit_revoke)) :: OP_EQUAL :: OP_ADD ::
    OP_IF ::
      OP_PUSHDATA(theirkey) ::
    OP_ELSE ::
      encodeNumber(abstimeout) :: OP_CHECKLOCKTIMEVERIFY :: encodeNumber(reltimeout) :: OP_CHECKSEQUENCEVERIFY :: OP_2DROP :: OP_PUSHDATA(ourkey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  def scriptPubKeyHtlcReceive(ourkey: BinaryData, theirkey: BinaryData, abstimeout: Long, reltimeout: Long, rhash: BinaryData, commit_revoke: BinaryData): Seq[ScriptElt] = {
    // values lesser than 16 should be encoded using OP_0..OP_16 instead of OP_PUSHDATA
    require(abstimeout > 16, s"abstimeout=$abstimeout must be greater than 16")
    // @formatter:off
    OP_SIZE :: encodeNumber(32) :: OP_EQUALVERIFY ::
    OP_HASH160 :: OP_DUP ::
    OP_PUSHDATA(ripemd160(rhash)) :: OP_EQUAL ::
    OP_IF ::
      encodeNumber(reltimeout) :: OP_CHECKSEQUENCEVERIFY :: OP_2DROP :: OP_PUSHDATA(ourkey) ::
    OP_ELSE ::
      OP_PUSHDATA(ripemd160(commit_revoke)) :: OP_EQUAL ::
      OP_NOTIF ::
        encodeNumber(abstimeout) :: OP_CHECKLOCKTIMEVERIFY :: OP_DROP ::
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
    * This function interprets the locktime for the given transaction, and returns the block height before which this tx cannot be published.
    * By convention in bitcoin, depending of the value of locktime it might be a number of blocks or a number of seconds since epoch.
    * This function does not support the case when the locktime is a number of seconds that is not way in the past.
    * NB: We use this property in lightning to store data in this field.
    *
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

  def toLocalDelayed(revocationPubkey: PublicKey, toSelfDelay: Int, localDelayedPaymentPubkey: PublicKey) = {
    // @formatter:off
    OP_IF ::
      OP_PUSHDATA(revocationPubkey) ::
    OP_ELSE ::
      encodeNumber(toSelfDelay) :: OP_CHECKSEQUENCEVERIFY :: OP_DROP ::
      OP_PUSHDATA(localDelayedPaymentPubkey) ::
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

  def htlcOffered(localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey, revocationPubKey: PublicKey, paymentHash: BinaryData): Seq[ScriptElt] = {
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

  def htlcReceived(localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey, revocationPubKey: PublicKey, paymentHash: BinaryData, lockTime: Long) = {
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
            OP_DROP :: encodeNumber(lockTime) :: OP_CHECKLOCKTIMEVERIFY :: OP_DROP ::
            OP_CHECKSIG ::
        OP_ENDIF ::
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

  /**
    * This witness script spends (steals) a [[htlcOffered]] or [[htlcReceived]] output using a revocation key as a punishment
    * for having published a revoked transaction
    */
  def witnessHtlcWithRevocationSig(revocationSig: BinaryData, revocationPubkey: PublicKey, htlcScript: BinaryData) =
    ScriptWitness(revocationSig :: revocationPubkey.toBin :: htlcScript :: Nil)

}