package fr.acinq.eclair.blockchain.electrum

import java.net.InetSocketAddress

import akka.actor.Props
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Base58, OP_PUSHDATA, OutPoint, SIGHASH_ALL, Satoshi, Script, ScriptFlags, SigVersion, Transaction, TxIn, TxOut}
import org.json4s.JsonAST.{JArray, JString, JValue}

import scala.concurrent.duration._

class ElectrumWatcherSpec extends IntegrationSpec {
  test("watch for confirmed transactions") {
    val probe = TestProbe()
    val watcher = system.actorOf(Props(new ElectrumWatcher(electrumClient)))

    probe.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = probe.expectMsgType[JValue]
    println(address)

    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address :: 1.0 :: Nil))
    val JString(txid) = probe.expectMsgType[JValue](3000 seconds)

    probe.send(bitcoincli, BitcoinReq("getrawtransaction", txid :: Nil))
    val JString(hex) = probe.expectMsgType[JValue]
    val tx = Transaction.read(hex)

    val listener = TestProbe()
    probe.send(watcher, ElectrumWatcher.WatchConfirmed(tx, 4, listener.ref))
    probe.send(bitcoincli, BitcoinReq("generate", 3 :: Nil))
    listener.expectNoMsg(1 second)
    probe.send(bitcoincli, BitcoinReq("generate", 2 :: Nil))
    val confirmed = listener.expectMsgType[ElectrumWatcher.TransactionConfirmed](20 seconds)
    system.stop(watcher)
  }

  test("watch for spent transactions") {
    val probe = TestProbe()
    val watcher = system.actorOf(Props(new ElectrumWatcher(electrumClient)))

    probe.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = probe.expectMsgType[JValue]
    println(address)

    probe.send(bitcoincli, BitcoinReq("dumpprivkey", address :: Nil))
    val JString(wif) = probe.expectMsgType[JValue]
    val priv = PrivateKey.fromBase58(wif, Base58.Prefix.SecretKeyTestnet)

    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address :: 1.0 :: Nil))
    val JString(txid) = probe.expectMsgType[JValue](3000 seconds)

    probe.send(bitcoincli, BitcoinReq("getrawtransaction", txid :: Nil))
    val JString(hex) = probe.expectMsgType[JValue]
    val tx = Transaction.read(hex)

    // find the output for the address we generated and create a tx that spends it
    val pos = tx.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2pkh(priv.publicKey)))
    assert(pos != -1)
    val spendingTx = {
      val tmp = Transaction(version = 2,
        txIn = TxIn(OutPoint(tx, pos), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
        txOut = TxOut(tx.txOut(pos).amount - Satoshi(1000), publicKeyScript = Script.pay2pkh(priv.publicKey)) :: Nil,
        lockTime = 0)
      val sig = Transaction.signInput(tmp, 0, tx.txOut(pos).publicKeyScript, SIGHASH_ALL, tx.txOut(pos).amount, SigVersion.SIGVERSION_BASE, priv)
      val signedTx = tmp.updateSigScript(0, OP_PUSHDATA(sig) :: OP_PUSHDATA(priv.publicKey.toBin) :: Nil)
      Transaction.correctlySpends(signedTx, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      signedTx
    }

    val listener = TestProbe()
    probe.send(watcher, ElectrumWatcher.WatchSpent(tx, pos, listener.ref))
    listener.expectNoMsg(1 second)
    probe.send(bitcoincli, BitcoinReq("sendrawtransaction", Transaction.write(spendingTx).toString :: Nil))
    probe.expectMsgType[JValue]
    probe.send(bitcoincli, BitcoinReq("generate", 2 :: Nil))
    val blocks = probe.expectMsgType[JValue]
    val JArray(List(JString(block1), JString(block2))) = blocks
    val spent = listener.expectMsgType[ElectrumWatcher.TransactionSpent](20 seconds)
    system.stop(watcher)
  }
}
