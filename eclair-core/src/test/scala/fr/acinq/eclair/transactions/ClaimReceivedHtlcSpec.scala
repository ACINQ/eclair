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


class ClaimReceivedHtlcSpec extends FunSuite {

  object Alice {
    val commitKey = PrivateKey.fromBase58("cVuzKWCszfvjkoJyUasvsrRdECriz8hSd1BDinRNzytwnXmX7m1g", Base58.Prefix.SecretKeyTestnet)
    val finalKey = PrivateKey.fromBase58("cRUfvpbRtMSqCFD1ADdvgPn5HfRLYuHCFYAr2noWnaRDNger2AoA", Base58.Prefix.SecretKeyTestnet)
    val commitPubKey = commitKey.publicKey
    val finalPubKey = finalKey.publicKey
    val R = Crypto.sha256("this is Alice's R".getBytes("UTF-8"))
    val Rhash = Crypto.sha256(R)
    val H = Crypto.hash160(R)
    val revokeCommit = "Alice foo".getBytes("UTF-8")
    val revokeCommitHash = Crypto.sha256(revokeCommit)
  }

  object Bob {
    val commitKey = PrivateKey.fromBase58("cSupnaiBh6jgTcQf9QANCB5fZtXojxkJQczq5kwfSBeULjNd5Ypo", Base58.Prefix.SecretKeyTestnet)
    val finalKey = PrivateKey.fromBase58("cQLk5fMydgVwJjygt9ta8GcUU4GXLumNiXJCQviibs2LE5vyMXey", Base58.Prefix.SecretKeyTestnet)
    val commitPubKey = commitKey.publicKey
    val finalPubKey = finalKey.publicKey
    val R: BinaryData = Crypto.sha256("this is Bob's R".getBytes("UTF-8"))
    val Rhash: BinaryData = Crypto.sha256(R)
    val H: BinaryData = Crypto.hash160(R)
    val revokeCommit: BinaryData = Crypto.sha256("Alice revocation R".getBytes("UTF-8"))
    val revokeCommitRHash: BinaryData = Crypto.sha256(revokeCommit)
    val revokeCommitH: BinaryData = Crypto.sha256(revokeCommit)
  }

  val abstimeout = 3000
  val reltimeout = 2000
  val htlcScript = scriptPubKeyHtlcReceive(Alice.finalPubKey, Bob.finalPubKey, abstimeout, reltimeout, Bob.Rhash, Bob.revokeCommitRHash)
  val redeemScript: BinaryData = Script.write(htlcScript)

  // this tx sends money to our HTLC
  val tx = Transaction(
    version = 2,
    txIn = TxIn(OutPoint(Hash.Zeroes, 0), Array.emptyByteArray, 0xffffffffL) :: Nil,
    txOut = TxOut(10 satoshi, Script.pay2wsh(htlcScript)) :: Nil,
    lockTime = 0)

  // this tx tries to spend the previous tx
  val tx1 = Transaction(
    version = 2,
    txIn = TxIn(OutPoint(tx, 0), Array.emptyByteArray, 0xffffffff) :: Nil,
    txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.finalPubKey.toBin)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
    lockTime = 0)

  test("Alice can spend this HTLC after a delay if she knows the payment hash") {
    val tx2 = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(tx, 0), Array.emptyByteArray, reltimeout + 1) :: Nil,
      txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.finalPubKey.toBin)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = abstimeout + 1)

    val sig = Transaction.signInput(tx2, 0, Script.write(htlcScript), SIGHASH_ALL, tx.txOut(0).amount, 1, Alice.finalKey)
    val witness = ScriptWitness(sig :: Bob.R :: redeemScript :: Nil)
    val tx3 = tx2.updateWitness(0, witness)

    Transaction.correctlySpends(tx3, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("Blob can spend this HTLC after a delay") {
    val tx2 = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(tx, 0), Array.emptyByteArray, reltimeout + 1) :: Nil,
      txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Bob.finalPubKey.toBin)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = abstimeout + 1)

    val sig = Transaction.signInput(tx2, 0, Script.write(htlcScript), SIGHASH_ALL, tx.txOut(0).amount, 1, Bob.finalKey)
    val witness = ScriptWitness(sig :: Hash.Zeroes :: redeemScript :: Nil)
    val tx3 = tx2.updateWitness(0, witness)

    Transaction.correctlySpends(tx3, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("Blob can spend this HTLC right away if he knows the revocation hash") {
    val sig = Transaction.signInput(tx1, 0, Script.write(htlcScript), SIGHASH_ALL, tx.txOut(0).amount, 1, Bob.finalKey)
    val witness = ScriptWitness(sig :: Bob.revokeCommit :: redeemScript :: Nil)
    val tx2 = tx1.updateWitness(0, witness)
    Transaction.correctlySpends(tx2, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }
}
