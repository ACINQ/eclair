package fr.acinq.eclair

import fr.acinq.bitcoin._
import fr.acinq.eclair.channel.Scripts._
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ClaimReceivedHtlcSpec extends FunSuite {

  object Alice {
    val (_, commitKey) = Base58Check.decode("cVuzKWCszfvjkoJyUasvsrRdECriz8hSd1BDinRNzytwnXmX7m1g")
    val (_, finalKey) = Base58Check.decode("cRUfvpbRtMSqCFD1ADdvgPn5HfRLYuHCFYAr2noWnaRDNger2AoA")
    val commitPubKey = Crypto.publicKeyFromPrivateKey(commitKey)
    val finalPubKey = Crypto.publicKeyFromPrivateKey(finalKey)
    val R = "this is Alice's R".getBytes("UTF-8")
    val Rhash = Crypto.sha256(R)
    val H = Crypto.hash160(R)
    val revokeCommit = "Alice foo".getBytes("UTF-8")
    val revokeCommitHash = Crypto.sha256(revokeCommit)
  }

  object Bob {
    val (_, commitKey) = Base58Check.decode("cSupnaiBh6jgTcQf9QANCB5fZtXojxkJQczq5kwfSBeULjNd5Ypo")
    val (_, finalKey) = Base58Check.decode("cQLk5fMydgVwJjygt9ta8GcUU4GXLumNiXJCQviibs2LE5vyMXey")
    val commitPubKey = Crypto.publicKeyFromPrivateKey(commitKey)
    val finalPubKey = Crypto.publicKeyFromPrivateKey(finalKey)
    val R = "this is Bob's R".getBytes("UTF-8")
    val Rhash = Crypto.sha256(R)
    val H = Crypto.hash160(R)
    val revokeCommit = "Bob foo".getBytes("UTF-8")
    val revokeCommitHash = Crypto.sha256(revokeCommit)
  }

  val abstimeout = 3000
  val reltimeout = 2000
  val htlcScript = scriptPubKeyHtlcReceive(Alice.finalPubKey, Bob.finalPubKey, abstimeout, reltimeout, Bob.Rhash, Bob.revokeCommitHash)

  // this tx sends money to our HTLC
  val tx = Transaction(
    version = 1,
    txIn = TxIn(OutPoint(Hash.Zeroes, 0), Array.emptyByteArray, 0xffffffffL) :: Nil,
    txOut = TxOut(10, Script.write(pay2sh(htlcScript))) :: Nil,
    lockTime = 0)

  // this tx tries to spend the previous tx
  val tx1 = Transaction(
    version = 2,
    txIn = TxIn(OutPoint(tx, 0), Array.emptyByteArray, 0xffffffff) :: Nil,
    txOut = TxOut(10, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.finalPubKey)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
    lockTime = 0)

  test("Alice can spend this HTLC after a delay if she knows the payment hash") {
    val tx2 = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(tx, 0), Array.emptyByteArray, reltimeout + 1) :: Nil,
      txOut = TxOut(10, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.finalPubKey)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = abstimeout + 1)

    val sig = Transaction.signInput(tx2, 0, Script.write(htlcScript), SIGHASH_ALL, Alice.finalKey)
    val sigScript = OP_PUSHDATA(sig) :: OP_PUSHDATA(Bob.R) :: OP_PUSHDATA(Script.write(htlcScript)) :: Nil
    val tx3 = tx2.updateSigScript(0, Script.write(sigScript))

    val runner = new Script.Runner(
      new Script.Context(tx3, 0),
      ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS | ScriptFlags.SCRIPT_VERIFY_CHECKLOCKTIMEVERIFY | ScriptFlags.SCRIPT_VERIFY_CHECKSEQUENCEVERIFY
    )
    val result = runner.verifyScripts(Script.write(sigScript), Script.write(pay2sh(htlcScript)))
    assert(result)
  }

  test("Blob can spend this HTLC after a delay") {
    val tx2 = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(tx, 0), Array.emptyByteArray, reltimeout + 1) :: Nil,
      txOut = TxOut(10, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.finalPubKey)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = abstimeout + 1)


    val sig = Transaction.signInput(tx2, 0, Script.write(htlcScript), SIGHASH_ALL, Bob.finalKey)
    val sigScript = OP_PUSHDATA(sig) :: OP_PUSHDATA(Array.emptyByteArray) :: OP_PUSHDATA(Script.write(htlcScript)) :: Nil
    val tx3 = tx2.updateSigScript(0, Script.write(sigScript))

    val runner = new Script.Runner(new Script.Context(tx3, 0), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS | ScriptFlags.SCRIPT_VERIFY_CHECKLOCKTIMEVERIFY | ScriptFlags.SCRIPT_VERIFY_CHECKSEQUENCEVERIFY)
    val result = runner.verifyScripts(Script.write(sigScript), Script.write(pay2sh(htlcScript)))
    assert(result)
  }

  test("Blob can spend this HTLC right away if he knows the revocation hash") {
    val sig = Transaction.signInput(tx1, 0, Script.write(htlcScript), SIGHASH_ALL, Bob.finalKey)
    val sigScript = OP_PUSHDATA(sig) :: OP_PUSHDATA(Bob.revokeCommit) :: OP_PUSHDATA(Script.write(htlcScript)) :: Nil
    val tx2 = tx1.updateSigScript(0, Script.write(sigScript))

    val runner = new Script.Runner(new Script.Context(tx2, 0), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS | ScriptFlags.SCRIPT_VERIFY_CHECKLOCKTIMEVERIFY | ScriptFlags.SCRIPT_VERIFY_CHECKSEQUENCEVERIFY)
    val result = runner.verifyScripts(Script.write(sigScript), Script.write(pay2sh(htlcScript)))
    assert(result)
  }
}
