package fr.acinq.protos

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.crypto.Generators.Scalar
import fr.acinq.eclair.transactions.Scripts
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Try

@RunWith(classOf[JUnitRunner])
class Bolt3Spec extends FunSuite {
  val (Base58.Prefix.SecretKeyTestnet, localPrivKey) = Base58Check.decode("cVuzKWCszfvjkoJyUasvsrRdECriz8hSd1BDinRNzytwnXmX7m1g")
  val (Base58.Prefix.SecretKeyTestnet, remotePrivKey) = Base58Check.decode("cRUfvpbRtMSqCFD1ADdvgPn5HfRLYuHCFYAr2noWnaRDNger2AoA")
  val localPubKey: BinaryData = Crypto.publicKeyFromPrivateKey(localPrivKey)
  val remotePubKey: BinaryData = Crypto.publicKeyFromPrivateKey(remotePrivKey)

  val (Base58.Prefix.SecretKeyTestnet, revocationPrivKey) = Base58Check.decode("cSupnaiBh6jgTcQf9QANCB5fZtXojxkJQczq5kwfSBeULjNd5Ypo")
  val revocationPubKey = Crypto.publicKeyFromPrivateKey(revocationPrivKey)

  val amount = 40000 + 30000 + 20000 + 15000 satoshi

  val config = ConfigFactory.load()

  // run this test with -Dbolt3-test.use-bitcoind=true to generate publishable tx
  val useBitcoind = Try(config.getBoolean("bolt3-test.use-bitcoind")).getOrElse(false)
  val bitcoin: Option[ExtendedBitcoinClient] = if (useBitcoind) {
    implicit val system = ActorSystem("mySystem")
    val client = new ExtendedBitcoinClient(new BitcoinJsonRPCClient(
      user = config.getString("eclair.bitcoind.rpcuser"),
      password = config.getString("eclair.bitcoind.rpcpassword"),
      host = config.getString("eclair.bitcoind.host"),
      port = config.getInt("eclair.bitcoind.rpcport")))
    Some(client)
  } else None

  val (fundingTx, fundingPos) = bitcoin match {
    case Some(client) => Await.result(client.makeAnchorTx(localPubKey, remotePubKey, amount), 5 seconds)
    case None => (Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, Script.pay2wsh(Bolt3.fundingScript(localPubKey, remotePubKey))) :: Nil, lockTime = 0), 0)
  }

  def hex(tx: Transaction) = toHexString(Transaction.write(tx))

  println(s"funding tx (use output $fundingPos): ${hex(fundingTx)}")

  val localDelayedKey = localPubKey
  val paymentPreimage1 = Hash.Zeroes
  val paymentPreimage2 = Hash.One
  val paymentHash1 = Crypto.hash160(paymentPreimage1)
  val paymentHash2 = Crypto.hash160(paymentPreimage2)

  // this is an absolute timeout (i.e. a block height or UNIX timestamp) that will be used with OP_CLTV
  val htlcTimeout = 10000

  // this is a relative (to the parent tx) timeout, expressed in number of blocks or seconds
  val selfDelay = 20

  val fee = 5000 satoshi

  // create our local commit tx, with an HTLC that we've offered and a HTLC that we've received
  val commitTx = {
    val tx = LexicographicalOrdering.sort(
      Transaction(
        version = 2,
        txIn = TxIn(OutPoint(fundingTx, fundingPos), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
        txOut = Seq(
          TxOut(40000.satoshi - fee / 4, Script.pay2wsh(Bolt3.toLocal(revocationPubKey, selfDelay, localDelayedKey))),
          TxOut(30000.satoshi - fee / 4, Script.pay2wpkh(remotePubKey)),
          TxOut(20000.satoshi - fee / 4, Script.pay2wsh(Bolt3.htlcOffered(localPubKey, remotePubKey, paymentHash1))),
          TxOut(15000.satoshi - fee / 4, Script.pay2wsh(Bolt3.htlcReceived(localPubKey, remotePubKey, paymentHash2, htlcTimeout)))
        ),
        lockTime = 0)
    )

    val redeemScript: BinaryData = Bolt3.fundingScript(localPubKey, remotePubKey)
    println(s"size of funding tx script: ${redeemScript.length}")
    val localSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, fundingTx.txOut(fundingPos).amount, SigVersion.SIGVERSION_WITNESS_V0, localPrivKey)
    println(s"local sig size: ${localSig.length}")
    val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, fundingTx.txOut(fundingPos).amount, SigVersion.SIGVERSION_WITNESS_V0, remotePrivKey)
    println(s"remote sig size: ${remoteSig.length}")
    val witness = if (Scripts.isLess(localPubKey, remotePubKey))
      ScriptWitness(BinaryData.empty :: localSig :: remoteSig :: redeemScript :: Nil)
    else
      ScriptWitness(BinaryData.empty :: remoteSig :: localSig :: redeemScript :: Nil)
    println(s"witness size: ${ScriptWitness.write(witness).length}")
    val tx1 = tx.updateWitness(0, witness)
    println(s"signed commit tx base size: ${Bolt3.baseSize(tx1)} total size: ${Bolt3.totalSize(tx1)} weight: ${Bolt3.weight(tx1)}")
    tx1
  }
  println(s"commit tx: ${hex(commitTx)}")

  def findPubKeyScriptIndex(tx: Transaction, script: BinaryData): Int = tx.txOut.indexWhere(_.publicKeyScript == script)

  def findPubKeyScriptIndex(tx: Transaction, script: Seq[ScriptElt]): Int = findPubKeyScriptIndex(tx, Script.write(script))

  // create our local HTLC timeout tx for the HTLC that we've offered
  // it is signed by both parties
  val htlcTimeoutTx = {
    val redeemScript: BinaryData = Script.write(Bolt3.htlcOffered(localPubKey, remotePubKey, paymentHash1))
    val index = findPubKeyScriptIndex(commitTx, Script.pay2wsh(redeemScript))
    val tx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(commitTx, index), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(commitTx.txOut(index).amount - fee, Script.pay2wsh(Bolt3.htlcSuccessOrTimeout(revocationPubKey, selfDelay, localDelayedKey))) :: Nil,
      lockTime = 0)
    // both parties sign the unsigned tx
    println(s"size of htlcOffered script: ${redeemScript.length}")
    val localSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, commitTx.txOut(index).amount, SigVersion.SIGVERSION_WITNESS_V0, localPrivKey)
    println(s"local sig size: ${localSig.length}")
    val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, commitTx.txOut(index).amount, SigVersion.SIGVERSION_WITNESS_V0, remotePrivKey)
    println(s"remote sig size: ${remoteSig.length}")
    val witness = ScriptWitness(BinaryData.empty :: remoteSig :: localSig :: BinaryData.empty :: redeemScript :: Nil)
    println(s"witness size: ${ScriptWitness.write(witness).length}")
    val tx1 = tx.updateWitness(0, witness)
    println(s"signed htlcTimeoutTx tx: base size: ${Bolt3.baseSize(tx1)} total size: ${Bolt3.totalSize(tx1)} weight: ${Bolt3.weight(tx1)}")
    tx1
  }
  println(s"htlc timeout tx: ${hex(htlcTimeoutTx)}")

  // create our local HTLC success tx for the HTLC that we've received
  // it is signed by both parties and its witness contains the HTLC payment preimage
  val htlcSuccessTx = {
    val redeemScript: BinaryData = Script.write(Bolt3.htlcReceived(localPubKey, remotePubKey, Crypto.hash160(paymentPreimage2), htlcTimeout))
    val index = findPubKeyScriptIndex(commitTx, Script.pay2wsh(redeemScript))
    val tx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(commitTx, index), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(commitTx.txOut(index).amount - fee, Script.pay2wsh(Bolt3.htlcSuccessOrTimeout(revocationPubKey, selfDelay, localDelayedKey))) :: Nil,
      lockTime = 0)
    // both parties sign the unsigned tx
    println(s"size of htlcReceived script: ${redeemScript.length}")
    val localSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, commitTx.txOut(index).amount, SigVersion.SIGVERSION_WITNESS_V0, localPrivKey)
    println(s"local sig size: ${localSig.length}")
    val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, commitTx.txOut(index).amount, SigVersion.SIGVERSION_WITNESS_V0, remotePrivKey)
    println(s"remote sig size: ${remoteSig.length}")
    val witness = ScriptWitness(BinaryData.empty :: remoteSig :: localSig :: paymentPreimage2 :: redeemScript :: Nil)
    println(s"witness size: ${ScriptWitness.write(witness).length}")
    val tx1 = tx.updateWitness(0, witness)
    println(s"signed htlcSuccessTx base size: ${Bolt3.baseSize(tx1)} total size: ${Bolt3.totalSize(tx1)} weight: ${Bolt3.weight(tx1)}")
    tx1
  }
  println(s"htlc success tx: ${hex(htlcSuccessTx)}")

  test("commit tx spends the funding tx") {
    Transaction.correctlySpends(commitTx, fundingTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("HTLC timeout tx spends the commit tx") {
    Transaction.correctlySpends(htlcTimeoutTx, commitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("HTLC success tx spends the commit tx") {
    Transaction.correctlySpends(htlcSuccessTx, commitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("we can spend our commit tx output after a delay") {
    val spendOurOutput = {
      val redeemScript: BinaryData = Script.write(Bolt3.toLocal(revocationPubKey, selfDelay, localDelayedKey))
      val index = findPubKeyScriptIndex(commitTx, Script.pay2wsh(redeemScript))
      val tx = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(commitTx, index), signatureScript = Nil, sequence = selfDelay + 1) :: Nil,
        txOut = TxOut(commitTx.txOut(index).amount - fee, Script.pay2wpkh(localPubKey)) :: Nil,
        lockTime = 0)
      println(s"size of toLocal script: ${redeemScript.length}")
      val localSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, commitTx.txOut(index).amount, SigVersion.SIGVERSION_WITNESS_V0, localPrivKey)
      println(s"local sig size: ${localSig.length}")
      val witness = ScriptWitness(localSig :: BinaryData.empty :: redeemScript :: Nil)
      tx.updateWitness(0, witness)
    }
    Transaction.correctlySpends(spendOurOutput, commitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    println(s"we-spend-our-output tx: ${hex(spendOurOutput)}")
    println(s"you need to publish the commit tx and generate ${selfDelay} blocks before you can publish this tx")
  }

  test("they can spend their commit tx immediately") {
    val index = findPubKeyScriptIndex(commitTx, Script.pay2wpkh(remotePubKey))
    val spendTheirOutput = {
      val tx = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(commitTx, index), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
        txOut = TxOut(commitTx.txOut(index).amount - fee, Script.pay2wpkh(remotePubKey)) :: Nil,
        lockTime = 0)
      val redeemScript: BinaryData = Script.write(Script.pay2pkh(remotePubKey))
      val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, commitTx.txOut(index).amount, SigVersion.SIGVERSION_WITNESS_V0, remotePrivKey)
      val witness = ScriptWitness(remoteSig :: remotePubKey :: Nil)
      tx.updateWitness(0, witness)
    }
    Transaction.correctlySpends(spendTheirOutput, commitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    println(s"they-spend-their-output tx: ${hex(spendTheirOutput)}")
  }


  test("they can spend our commit tx output immediately if they have the revocation key") {
    val redeemScript: BinaryData = Script.write(Bolt3.toLocal(revocationPubKey, selfDelay, localDelayedKey))
    val index = findPubKeyScriptIndex(commitTx, Script.pay2wsh(redeemScript))
    val penaltyTx = {
      val tx = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(commitTx, index), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
        txOut = TxOut(commitTx.txOut(index).amount - fee, Script.pay2wpkh(localPubKey)) :: Nil,
        lockTime = 0)
      val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, commitTx.txOut(index).amount, SigVersion.SIGVERSION_WITNESS_V0, revocationPrivKey)
      println(s"remote sig size: ${remoteSig.length}")
      val witness = ScriptWitness(remoteSig :: BinaryData("01") :: redeemScript :: Nil)
      tx.updateWitness(0, witness)
    }
    Transaction.correctlySpends(penaltyTx, commitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    println(s"they-spend-our-output tx: ${hex(penaltyTx)}")
  }

  test("we can claim the received HTLC timeout tx after a delay") {
    val spendHtlcTimeout = {
      val tx = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(htlcTimeoutTx, 0), signatureScript = Nil, sequence = selfDelay + 1) :: Nil,
        txOut = TxOut(htlcTimeoutTx.txOut(0).amount - fee, Script.pay2wpkh(localPubKey)) :: Nil,
        lockTime = 0)
      val redeemScript: BinaryData = Script.write(Bolt3.htlcSuccessOrTimeout(revocationPubKey, selfDelay, localDelayedKey))
      println(s"size of htlcSuccessOrTimeout script: ${redeemScript.length}")
      val localSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, htlcTimeoutTx.txOut(0).amount, SigVersion.SIGVERSION_WITNESS_V0, localPrivKey)
      println(s"local sig size: ${localSig.length}")
      val witness = ScriptWitness(localSig :: BinaryData.empty :: redeemScript :: Nil)
      tx.updateWitness(0, witness)
    }
    Transaction.correctlySpends(spendHtlcTimeout, htlcTimeoutTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    println(s"we-spend-offered-htlc-timeout tx: ${hex(spendHtlcTimeout)}")
    println(s"you need to publish the htlc timeout tx and generate ${selfDelay} blocks before you can publish this tx")
  }

  test("we can claim the received HTLC success tx after a delay") {
    val spendHtlcSuccess = {
      val tx = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(htlcSuccessTx, 0), signatureScript = Nil, sequence = selfDelay + 1) :: Nil,
        txOut = TxOut(htlcSuccessTx.txOut(0).amount - fee, Script.pay2wpkh(localPubKey)) :: Nil,
        lockTime = 0)
      val redeemScript: BinaryData = Script.write(Bolt3.htlcSuccessOrTimeout(revocationPubKey, selfDelay, localDelayedKey))
      val localSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, htlcSuccessTx.txOut(0).amount, SigVersion.SIGVERSION_WITNESS_V0, localPrivKey)
      println(s"local sig size: ${localSig.length}")
      val witness = ScriptWitness(localSig :: BinaryData.empty :: redeemScript :: Nil)
      tx.updateWitness(0, witness)
    }
    Transaction.correctlySpends(spendHtlcSuccess, htlcSuccessTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    println(s"we-spend-received-htlc tx: ${hex(spendHtlcSuccess)}")
    println(s"you need to publish the htlc success tx and generate ${selfDelay} blocks before you can publish this tx")
  }

  test("they can spend the offered HTLC with the payment preimage") {
    val spendOfferedHtlc = {
      val redeemScript: BinaryData = Script.write(Bolt3.htlcOffered(localPubKey, remotePubKey, Crypto.hash160(paymentPreimage1)))
      val index = findPubKeyScriptIndex(commitTx, Script.pay2wsh(redeemScript))
      val tx = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(commitTx, index), signatureScript = Nil, sequence = selfDelay + 1) :: Nil,
        txOut = TxOut(commitTx.txOut(index).amount - fee, Script.pay2wpkh(remotePubKey)) :: Nil,
        lockTime = 0)
      val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, commitTx.txOut(index).amount, SigVersion.SIGVERSION_WITNESS_V0, remotePrivKey)
      println(s"remote sig size: ${remoteSig.length}")
      val witness = ScriptWitness(remoteSig :: paymentPreimage1 :: redeemScript :: Nil)
      tx.updateWitness(0, witness)
    }
    Transaction.correctlySpends(spendOfferedHtlc, commitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    println(s"they-spend-offered-htlc tx: ${hex(spendOfferedHtlc)}")
  }

  test("they can timeout the received HTLC after a delay") {
    val redeemScript: BinaryData = Script.write(Bolt3.htlcReceived(localPubKey, remotePubKey, Crypto.hash160(paymentPreimage2), htlcTimeout))
    val index = findPubKeyScriptIndex(commitTx, Script.pay2wsh(redeemScript))
    val timeoutReceivedHtlc = {
      val tx = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(commitTx, index), signatureScript = Nil, sequence = 0) :: Nil,
        txOut = TxOut(commitTx.txOut(index).amount - fee, Script.pay2wpkh(remotePubKey)) :: Nil,
        lockTime = htlcTimeout + 1)
      val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, commitTx.txOut(index).amount, SigVersion.SIGVERSION_WITNESS_V0, remotePrivKey)
      println(s"remote sig size: ${remoteSig.length}")
      val witness = ScriptWitness(remoteSig :: BinaryData.empty :: redeemScript :: Nil)
      tx.updateWitness(0, witness)
    }
    Transaction.correctlySpends(timeoutReceivedHtlc, commitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    println(s"they-timeout-received-htlc tx: ${hex(timeoutReceivedHtlc)}")
  }

  test("they can spend our HTLC timeout tx immediately if they know the revocation private key") {
    val penaltyTx = {
      val tx = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(htlcTimeoutTx, 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
        txOut = TxOut(htlcTimeoutTx.txOut(0).amount - fee, Script.pay2wpkh(remotePubKey)) :: Nil,
        lockTime = 0)
      val redeemScript: BinaryData = Script.write(Bolt3.htlcSuccessOrTimeout(revocationPubKey, selfDelay, localDelayedKey))
      val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, htlcTimeoutTx.txOut(0).amount, SigVersion.SIGVERSION_WITNESS_V0, revocationPrivKey)
      println(s"remote sig size: ${remoteSig.length}")
      val witness = ScriptWitness(remoteSig :: BinaryData("01") :: redeemScript :: Nil)
      tx.updateWitness(0, witness)
    }
    Transaction.correctlySpends(penaltyTx, htlcTimeoutTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    println(s"penalty for out htlc timeout tx: ${hex(penaltyTx)}")
  }

  test("they can spend our HTLC success tx immediately if they know the revocation private key") {
    val penaltyTx = {
      val tx = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(htlcSuccessTx, 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
        txOut = TxOut(htlcSuccessTx.txOut(0).amount - fee, Script.pay2wpkh(remotePubKey)) :: Nil,
        lockTime = 0)
      val redeemScript: BinaryData = Script.write(Bolt3.htlcSuccessOrTimeout(revocationPubKey, selfDelay, localDelayedKey))
      val remoteSig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, htlcSuccessTx.txOut(0).amount, SigVersion.SIGVERSION_WITNESS_V0, revocationPrivKey)
      println(s"remote sig size: ${remoteSig.length}")
      val witness = ScriptWitness(remoteSig :: BinaryData("01") :: redeemScript :: Nil)
      tx.updateWitness(0, witness)
    }
    Transaction.correctlySpends(penaltyTx, htlcSuccessTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    println(s"penalty for out htlc success tx: ${hex(penaltyTx)}")
  }

  test("derive revocation key") {
    object Local {
      val revocationSecret = Scalar(Crypto.sha256("local foo".getBytes()))
      val revocationBasePoint = revocationSecret.point
      val perCommitSecret = Scalar(Crypto.sha256("local bar".getBytes()))
    }
    object Remote {
      val revocationSecret = Scalar(Crypto.sha256("remote foo".getBytes()))
      val perCommitSecret = Scalar(Crypto.sha256("remote bar".getBytes()))
      val perCommitBasePoint = perCommitSecret.point
    }

    // I can compute their revocation pubkey
    val theirRevocationPubKey = Generators.revocationPubKey(Local.revocationBasePoint, Remote.perCommitBasePoint)

    // and if they give me their per-commit secret I can compute their revocation privkey
    val theirRevocationPrivKey = Generators.revocationPrivKey(Local.revocationSecret, Remote.perCommitSecret)

    assert(theirRevocationPrivKey.point == theirRevocationPubKey)
  }

  test("derive local/remote/delayed keys") {
    object Local {
      val secret = Scalar(Crypto.sha256("local foo".getBytes()))
      val basePoint = secret.point
      val perCommitSecret = Scalar(Crypto.sha256("local bar".getBytes()))
      val perCommitBasePoint = perCommitSecret.point
    }
    object Remote {
      val secret = Scalar(Crypto.sha256("remote foo".getBytes()))
      val perCommitSecret = Scalar(Crypto.sha256("remote bar".getBytes()))
      val perCommitBasePoint = perCommitSecret.point
    }

    val localKey = Generators.derivePrivKey(Local.secret, Local.perCommitBasePoint)
    val localPubKey = Generators.derivePubKey(Local.basePoint, Local.perCommitBasePoint)
    assert(localKey.point == localPubKey)
  }
}
