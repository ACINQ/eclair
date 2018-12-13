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

package fr.acinq.eclair.blockchain.electrum

import java.net.InetSocketAddress

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Base58, OutPoint, SIGHASH_ALL, Satoshi, Script, ScriptFlags, ScriptWitness, SigVersion, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.SSL
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool.ElectrumServerAddress
import fr.acinq.eclair.blockchain.{WatchConfirmed, WatchEventConfirmed, WatchEventSpent, WatchSpent}
import fr.acinq.eclair.channel.{BITCOIN_FUNDING_DEPTHOK, BITCOIN_FUNDING_SPENT}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JArray, JString, JValue}
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.concurrent.duration._


class ElectrumWatcherSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with BitcoindService with ElectrumxService  with BeforeAndAfterAll with Logging {

  override def beforeAll(): Unit = {
    logger.info("starting bitcoind")
    startBitcoind()
    waitForBitcoindReady()
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    logger.info("stopping bitcoind")
    stopBitcoind()
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }

  val electrumAddress = ElectrumServerAddress(new InetSocketAddress("localhost", 50001), SSL.OFF)

  test("watch for confirmed transactions") {
    val probe = TestProbe()
    val electrumClient = system.actorOf(Props(new ElectrumClientPool(Set(electrumAddress))))
    val watcher = system.actorOf(Props(new ElectrumWatcher(electrumClient)))

    probe.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = probe.expectMsgType[JValue]

    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address, 1.0))
    val JString(txid) = probe.expectMsgType[JValue](3000 seconds)

    probe.send(bitcoincli, BitcoinReq("getrawtransaction", txid))
    val JString(hex) = probe.expectMsgType[JValue]
    val tx = Transaction.read(hex)

    val listener = TestProbe()
    probe.send(watcher, WatchConfirmed(listener.ref, tx.txid, tx.txOut(0).publicKeyScript, 4, BITCOIN_FUNDING_DEPTHOK))
    probe.send(bitcoincli, BitcoinReq("generate", 3))
    listener.expectNoMsg(1 second)
    probe.send(bitcoincli, BitcoinReq("generate", 2))
    val confirmed = listener.expectMsgType[WatchEventConfirmed](20 seconds)
    system.stop(watcher)
  }

  test("watch for spent transactions") {
    val probe = TestProbe()
    val electrumClient = system.actorOf(Props(new ElectrumClientPool(Set(electrumAddress))))
    val watcher = system.actorOf(Props(new ElectrumWatcher(electrumClient)))

    probe.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = probe.expectMsgType[JValue]

    probe.send(bitcoincli, BitcoinReq("dumpprivkey", address))
    val JString(wif) = probe.expectMsgType[JValue]
    val priv = PrivateKey.fromBase58(wif, Base58.Prefix.SecretKeyTestnet)

    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address, 1.0))
    val JString(txid) = probe.expectMsgType[JValue](30 seconds)

    probe.send(bitcoincli, BitcoinReq("getrawtransaction", txid))
    val JString(hex) = probe.expectMsgType[JValue]
    val tx = Transaction.read(hex)

    // find the output for the address we generated and create a tx that spends it
    val pos = tx.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wpkh(priv.publicKey)))
    assert(pos != -1)
    val spendingTx = {
      val tmp = Transaction(version = 2,
        txIn = TxIn(OutPoint(tx, pos), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
        txOut = TxOut(tx.txOut(pos).amount - Satoshi(1000), publicKeyScript = Script.pay2wpkh(priv.publicKey)) :: Nil,
        lockTime = 0)
      val sig = Transaction.signInput(tmp, 0, Script.pay2pkh(priv.publicKey), SIGHASH_ALL, tx.txOut(pos).amount, SigVersion.SIGVERSION_WITNESS_V0, priv)
      val signedTx = tmp.updateWitness(0, ScriptWitness(sig :: priv.publicKey.toBin :: Nil))
      Transaction.correctlySpends(signedTx, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      signedTx
    }

    val listener = TestProbe()
    probe.send(watcher, WatchSpent(listener.ref, tx.txid, pos, tx.txOut(pos).publicKeyScript, BITCOIN_FUNDING_SPENT))
    listener.expectNoMsg(1 second)
    probe.send(bitcoincli, BitcoinReq("sendrawtransaction", spendingTx.toString))
    probe.expectMsgType[JValue]
    probe.send(bitcoincli, BitcoinReq("generate", 2))
    val blocks = probe.expectMsgType[JValue]
    val JArray(List(JString(block1), JString(block2))) = blocks
    val spent = listener.expectMsgType[WatchEventSpent](20 seconds)
    system.stop(watcher)
  }
}
