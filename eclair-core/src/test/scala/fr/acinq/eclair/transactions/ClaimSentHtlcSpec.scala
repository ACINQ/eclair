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

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin._
import fr.acinq.eclair.transactions.Scripts._
import org.scalatest.FunSuite
import scodec.bits.ByteVector


class ClaimSentHtlcSpec extends FunSuite {

  object Alice {
    val commitKey = PrivateKey.fromBase58("cVuzKWCszfvjkoJyUasvsrRdECriz8hSd1BDinRNzytwnXmX7m1g", Base58.Prefix.SecretKeyTestnet)
    val finalKey = PrivateKey.fromBase58("cRUfvpbRtMSqCFD1ADdvgPn5HfRLYuHCFYAr2noWnaRDNger2AoA", Base58.Prefix.SecretKeyTestnet)
    val commitPubKey = commitKey.publicKey
    val finalPubKey = finalKey.publicKey
    val R = Crypto.sha256(ByteVector.view("this is Alice's R".getBytes("UTF-8")))
    val Rhash = Crypto.sha256(R)
    val H = Crypto.hash160(Rhash)
    val revokeCommit = Crypto.sha256(ByteVector.view("Alice revocation R".getBytes("UTF-8")))
    val revokeCommitRHash = Crypto.sha256(revokeCommit)
    val revokeCommitH = Crypto.sha256(revokeCommit)
  }

  object Bob {
    val commitKey = PrivateKey.fromBase58("cSupnaiBh6jgTcQf9QANCB5fZtXojxkJQczq5kwfSBeULjNd5Ypo", Base58.Prefix.SecretKeyTestnet)
    val finalKey = PrivateKey.fromBase58("cQLk5fMydgVwJjygt9ta8GcUU4GXLumNiXJCQviibs2LE5vyMXey", Base58.Prefix.SecretKeyTestnet)
    val commitPubKey = commitKey.publicKey
    val finalPubKey = finalKey.publicKey
    val R: ByteVector = Crypto.sha256(ByteVector.view("this is Bob's R".getBytes("UTF-8")))
    val Rhash: ByteVector = Crypto.sha256(R)
    val H = Crypto.hash160(Rhash)
    val revokeCommit: ByteVector = Crypto.sha256(ByteVector.view("Bob revocation R".getBytes("UTF-8")))
    val revokeCommitRHash: ByteVector = Crypto.sha256(revokeCommit)
    val revokeCommitH: ByteVector = Crypto.sha256(revokeCommit)
  }

  val abstimeout = 3000
  val reltimeout = 2000
  val htlcScript = scriptPubKeyHtlcSend(Alice.finalPubKey, Bob.finalPubKey, abstimeout, reltimeout, Alice.revokeCommitRHash, Alice.Rhash)
  val redeemScript: ByteVector = Script.write(htlcScript)

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
    txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.finalPubKey.toBin)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
    lockTime = 0)

  test("Alice can spend this HTLC after a delay") {
    val tx2 = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(tx, 0), ByteVector.empty, sequence = reltimeout + 1) :: Nil,
      txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.finalPubKey.toBin)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = abstimeout + 1)

    val sig = Transaction.signInput(tx2, 0, redeemScript, SIGHASH_ALL, tx.txOut(0).amount, 1, Alice.finalKey)
    val witness = ScriptWitness(sig :: ByteVector32.Zeroes.bytes :: redeemScript :: Nil)
    val tx3 = tx2.updateWitness(0, witness)

    Transaction.correctlySpends(tx3, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("Alice cannot spend this HTLC before its absolute timeout") {
    val tx2 = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(tx, 0), ByteVector.empty, sequence = reltimeout + 1) :: Nil,
      txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.finalPubKey.toBin)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = abstimeout - 1)

    val sig = Transaction.signInput(tx2, 0, redeemScript, SIGHASH_ALL, tx.txOut(0).amount, 1, Alice.finalKey)
    val witness = ScriptWitness(sig :: ByteVector32.Zeroes.bytes :: redeemScript :: Nil)
    val tx3 = tx2.updateWitness(0, witness)

    val e = intercept[RuntimeException] {
      Transaction.correctlySpends(tx3, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }
    assert(e.getMessage === "unsatisfied CLTV lock time")
  }

  test("Alice cannot spend this HTLC before its relative timeout") {
    val tx2 = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(tx, 0), ByteVector.empty, sequence = reltimeout - 1) :: Nil,
      txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.finalPubKey.toBin)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = abstimeout + 1)

    val sig = Transaction.signInput(tx2, 0, redeemScript, SIGHASH_ALL, tx.txOut(0).amount, 1, Alice.finalKey)
    val witness = ScriptWitness(sig :: ByteVector32.Zeroes.bytes :: redeemScript :: Nil)
    val tx3 = tx2.updateWitness(0, witness)

    val e = intercept[RuntimeException] {
      Transaction.correctlySpends(tx3, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }
    assert(e.getMessage === "unsatisfied CSV lock time")
  }

  test("Bob can spend this HTLC if he knows the payment hash") {
    val sig = Transaction.signInput(tx1, 0, redeemScript, SIGHASH_ALL, tx.txOut(0).amount, 1, Bob.finalKey)
    val witness = ScriptWitness(sig :: Alice.R.bytes :: redeemScript :: Nil)
    val tx2 = tx1.updateWitness(0, witness)
    Transaction.correctlySpends(tx2, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("Bob can spend this HTLC if he knows the revocation hash") {
    val sig = Transaction.signInput(tx1, 0, redeemScript, SIGHASH_ALL, tx.txOut(0).amount, 1, Bob.finalKey)
    val witness = ScriptWitness(sig :: Alice.revokeCommit.bytes :: redeemScript :: Nil)
    val tx2 = tx1.updateWitness(0, witness)
    Transaction.correctlySpends(tx2, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }
}
