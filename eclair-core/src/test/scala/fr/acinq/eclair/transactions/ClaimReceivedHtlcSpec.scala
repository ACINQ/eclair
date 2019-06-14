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

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, ripemd160}
import fr.acinq.bitcoin._
import fr.acinq.eclair.transactions.Scripts._
import org.scalatest.FunSuite
import scodec.bits.ByteVector


class ClaimReceivedHtlcSpec extends FunSuite {

  object Alice {
    val (commitKey, true) = PrivateKey.fromBase58("cVuzKWCszfvjkoJyUasvsrRdECriz8hSd1BDinRNzytwnXmX7m1g", Base58.Prefix.SecretKeyTestnet)
    val (finalKey, true) = PrivateKey.fromBase58("cRUfvpbRtMSqCFD1ADdvgPn5HfRLYuHCFYAr2noWnaRDNger2AoA", Base58.Prefix.SecretKeyTestnet)
    val commitPubKey = commitKey.publicKey
    val finalPubKey = finalKey.publicKey
    val R = Crypto.sha256(ByteVector.view("this is Alice's R".getBytes("UTF-8")))
    val Rhash = Crypto.sha256(R)
    val H = Crypto.hash160(R)
    val revokeCommit = ByteVector.view("Alice foo".getBytes("UTF-8"))
    val revokeCommitHash = Crypto.sha256(revokeCommit)
  }

  object Bob {
    val (commitKey, true) = PrivateKey.fromBase58("cSupnaiBh6jgTcQf9QANCB5fZtXojxkJQczq5kwfSBeULjNd5Ypo", Base58.Prefix.SecretKeyTestnet)
    val (finalKey, true) = PrivateKey.fromBase58("cQLk5fMydgVwJjygt9ta8GcUU4GXLumNiXJCQviibs2LE5vyMXey", Base58.Prefix.SecretKeyTestnet)
    val commitPubKey = commitKey.publicKey
    val finalPubKey = finalKey.publicKey
    val R = Crypto.sha256(ByteVector.view("this is Bob's R".getBytes("UTF-8")))
    val Rhash = Crypto.sha256(R)
    val H = Crypto.hash160(R)
    val revokeCommit = Crypto.sha256(ByteVector.view("Alice revocation R".getBytes("UTF-8")))
    val revokeCommitRHash = Crypto.sha256(revokeCommit)
    val revokeCommitH = Crypto.sha256(revokeCommit)
  }

  def scriptPubKeyHtlcReceive(ourkey: PublicKey, theirkey: PublicKey, abstimeout: Long, reltimeout: Long, rhash: ByteVector32, commit_revoke: ByteVector): Seq[ScriptElt] = {
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

  val abstimeout = 3000
  val reltimeout = 2000
  val htlcScript = scriptPubKeyHtlcReceive(Alice.finalPubKey, Bob.finalPubKey, abstimeout, reltimeout, Bob.Rhash, Bob.revokeCommitRHash)
  val redeemScript = Script.write(htlcScript)

  // this tx sends money to our HTLC
  val tx = Transaction(
    version = 2,
    txIn = TxIn(OutPoint(ByteVector32.Zeroes, 0), ByteVector.empty, 0xffffffffL) :: Nil,
    txOut = TxOut(10 satoshi, Script.pay2wsh(htlcScript)) :: Nil,
    lockTime = 0)

  // this tx tries to spend the previous tx
  val tx1 = Transaction(
    version = 2,
    txIn = TxIn(OutPoint(tx, 0), ByteVector.empty, 0xffffffff) :: Nil,
    txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.finalPubKey.value)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
    lockTime = 0)

  test("Alice can spend this HTLC after a delay if she knows the payment hash") {
    val tx2 = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(tx, 0), ByteVector.empty, reltimeout + 1) :: Nil,
      txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.finalPubKey.value)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = abstimeout + 1)

    val sig = Transaction.signInput(tx2, 0, Script.write(htlcScript), SIGHASH_ALL, tx.txOut(0).amount, 1, Alice.finalKey)
    val witness = ScriptWitness(sig :: Bob.R.bytes :: redeemScript :: Nil)
    val tx3 = tx2.updateWitness(0, witness)

    Transaction.correctlySpends(tx3, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("Blob can spend this HTLC after a delay") {
    val tx2 = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(tx, 0), ByteVector.empty, reltimeout + 1) :: Nil,
      txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Bob.finalPubKey.value)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = abstimeout + 1)

    val sig = Transaction.signInput(tx2, 0, Script.write(htlcScript), SIGHASH_ALL, tx.txOut(0).amount, 1, Bob.finalKey)
    val witness = ScriptWitness(sig :: ByteVector32.Zeroes.bytes :: redeemScript :: Nil)
    val tx3 = tx2.updateWitness(0, witness)

    Transaction.correctlySpends(tx3, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("Blob can spend this HTLC right away if he knows the revocation hash") {
    val sig = Transaction.signInput(tx1, 0, Script.write(htlcScript), SIGHASH_ALL, tx.txOut(0).amount, 1, Bob.finalKey)
    val witness = ScriptWitness(sig :: Bob.revokeCommit.bytes :: redeemScript :: Nil)
    val tx2 = tx1.updateWitness(0, witness)
    Transaction.correctlySpends(tx2, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }
}
