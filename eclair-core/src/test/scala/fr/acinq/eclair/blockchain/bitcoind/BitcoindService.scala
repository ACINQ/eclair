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

package fr.acinq.eclair.blockchain.bitcoind

import java.io.File
import java.nio.file.{Files, StandardOpenOption}
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.{TestKitBase, TestProbe}
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import fr.acinq.bitcoin.{Base58, Base58Check, Block, Btc, Crypto, DeterministicWallet, OP_PUSHDATA, OutPoint, SIGHASH_ALL, Script, ScriptFlags, SigVersion, Transaction, TxIn, TxOut}
import fr.acinq.eclair.{ShortChannelId, TestUtils, randomBytes32}
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinJsonRPCClient}
import fr.acinq.eclair.integration.IntegrationSpec
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JArray, JDecimal, JInt, JString, JValue}
import scodec.bits.ByteVector
import fr.acinq.eclair.LongToBtcAmount
import org.json4s.{DefaultFormats, Formats}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.io.Source
import scala.util.Random

trait BitcoindService extends Logging {
  self: TestKitBase =>

  implicit val formats: Formats = DefaultFormats
  implicit val system: ActorSystem
  implicit val sttpBackend  = OkHttpFutureBackend()

  val bitcoindPort: Int = TestUtils.availablePort

  val bitcoindRpcPort: Int = TestUtils.availablePort

  val bitcoindZmqBlockPort: Int = TestUtils.availablePort

  val bitcoindZmqTxPort: Int = TestUtils.availablePort

  import scala.sys.process._

  val INTEGRATION_TMP_DIR = new File(TestUtils.BUILD_DIRECTORY, s"integration-${UUID.randomUUID()}")
  logger.info(s"using tmp dir: $INTEGRATION_TMP_DIR")

  val PATH_BITCOIND = new File(TestUtils.BUILD_DIRECTORY, "bitcoin-0.18.1/bin/bitcoind")
  val PATH_BITCOIND_DATADIR = new File(INTEGRATION_TMP_DIR, "datadir-bitcoin")

  var bitcoind: Process = null
  var bitcoinrpcclient: BitcoinJsonRPCClient = null
  var bitcoincli: ActorRef = null

  case class BitcoinReq(method: String, params: Any*)

  def startBitcoind(txIndexEnabled: Boolean = false): Unit = {
    Files.createDirectories(PATH_BITCOIND_DATADIR.toPath)
    val bitcoinConfFile = new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath
    if (!Files.exists(bitcoinConfFile)) {
      val is = classOf[IntegrationSpec].getResourceAsStream("/integration/bitcoin.conf")
      val conf = Source.fromInputStream(is).mkString
          .replace("28333", bitcoindPort.toString)
          .replace("28332", bitcoindRpcPort.toString)
          .replace("28334", bitcoindZmqBlockPort.toString)
          .replace("28335", bitcoindZmqTxPort.toString)
          .replace("txindex=0", if (txIndexEnabled) "txindex=1" else "txindex=0")
      Files.writeString(bitcoinConfFile, conf)
    }

    bitcoind = s"$PATH_BITCOIND -datadir=$PATH_BITCOIND_DATADIR".run()
    bitcoinrpcclient = new BasicBitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = bitcoindRpcPort)
    bitcoincli = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case BitcoinReq(method) => bitcoinrpcclient.invoke(method) pipeTo sender
        case BitcoinReq(method, params) => bitcoinrpcclient.invoke(method, params) pipeTo sender
        case BitcoinReq(method, param1, param2) => bitcoinrpcclient.invoke(method, param1, param2) pipeTo sender
      }
    }))
  }

  def stopBitcoind(): Unit = {
    // gracefully stopping bitcoin will make it store its state cleanly to disk, which is good for later debugging
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("stop"))
    sender.expectMsgType[JValue]
    bitcoind.exitValue()
  }

  def waitForBitcoindReady(): Unit = {
    val sender = TestProbe()
    logger.info(s"waiting for bitcoind to initialize...")
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("getnetworkinfo"))
      sender.expectMsgType[Any](5 second) match {
        case j: JValue => j \ "version" match {
          case JInt(_) => true
          case _ => false
        }
        case _ => false
      }
    }, max = 3 minutes, interval = 2 seconds)
    logger.info(s"generating initial blocks...")
    generateBlocks(bitcoincli, 150)
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("getbalance"))
      val JDecimal(balance) = sender.expectMsgType[JDecimal](30 seconds)
      balance > 100
    }, max = 3 minutes, interval = 2 second)
  }

  def generateBlocks(bitcoinCli: ActorRef, blockCount: Int, address: Option[String] = None, timeout: FiniteDuration = 10 seconds)(implicit system: ActorSystem): List[String] = {
    val sender = TestProbe()
    val addressToUse = address match {
      case Some(addr) => addr
      case None =>
        sender.send(bitcoinCli, BitcoinReq("getnewaddress"))
        val JString(address) = sender.expectMsgType[JValue](timeout)
        address
    }
    sender.send(bitcoinCli, BitcoinReq("generatetoaddress", blockCount, addressToUse))
    val JArray(blocks) = sender.expectMsgType[JValue](timeout)
    assert(blocks.size == blockCount)
    blocks.collect { case JString(blockId) => blockId }
  }

  object ExternalWalletHelper {
    // master key used to simulate non wallet transactions
    val nonWalletMasterKey = DeterministicWallet.generate(randomBytes32)

    def receiveKey(index: Long = 0) = DeterministicWallet.derivePrivateKey(nonWalletMasterKey, index)
    def sendKey(index: Long = 1) = DeterministicWallet.derivePrivateKey(nonWalletMasterKey, index)

    // create a signed tx that spends the transaction created with 'nonWalletTransaction' does not broadcast it
    def spendNonWalletTx(tx: Transaction, receivingKeyIndex: Long = 0)(implicit system: ActorSystem): Transaction = {
      val spendTx = Transaction(
        version = 2L,
        txIn = TxIn(OutPoint(tx, 0), ByteVector.empty,  TxIn.SEQUENCE_FINAL) :: Nil,
        txOut = TxOut(tx.txOut(0).amount - 200.sat, Script.write(Script.pay2pkh(receiveKey(receivingKeyIndex).publicKey))) :: Nil, // amount - fee
        lockTime = 0
      )

      val sig = Transaction.signInput(spendTx, 0, tx.txOut(0).publicKeyScript, SIGHASH_ALL, tx.txOut(0).amount - 200.sat, SigVersion.SIGVERSION_BASE, sendKey().privateKey)
      val tx1 = spendTx.updateSigScript(0, OP_PUSHDATA(sig) :: OP_PUSHDATA(sendKey().publicKey) :: Nil)
      Transaction.correctlySpends(tx1, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

      tx1
    }

    // sends money from out bitcoind wallet to an external one
    def nonWalletTransaction(implicit system: ActorSystem): Transaction = {
      val amountToReceive = Btc(0.1)
      val probe = TestProbe()
      val externalWalletAddress = Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, Crypto.hash160(receiveKey().publicKey.value))

      // send bitcoins from our wallet to an external wallet
      bitcoinrpcclient.invoke("sendtoaddress", externalWalletAddress, amountToReceive.toBigDecimal.toString()).pipeTo(probe.ref)
      val JString(spendingToExternalTxId) = probe.expectMsgType[JValue]

      bitcoinrpcclient.invoke("getrawtransaction", spendingToExternalTxId).pipeTo(probe.ref)
      val JString(rawTx) = probe.expectMsgType[JValue]
      val incomingTx = Transaction.read(rawTx)
      val outIndex = incomingTx.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2pkh(receiveKey().publicKey)))

      assert(outIndex >= 0)

      generateBlocks(bitcoincli, 1)

      // tx spending bitcoin from external wallet to external wallet
      val tx = Transaction(
        version = 2L,
        txIn = TxIn(OutPoint(incomingTx, outIndex), ByteVector.empty,  TxIn.SEQUENCE_FINAL) :: Nil,
        txOut = TxOut(amountToReceive.toSatoshi - 200.sat, Script.write(Script.pay2pkh(sendKey().publicKey))) :: Nil, // amount - fee
        lockTime = 0
      )

      val sig = Transaction.signInput(tx, 0, Script.pay2pkh(receiveKey().publicKey), SIGHASH_ALL, 2500 sat, SigVersion.SIGVERSION_BASE, receiveKey().privateKey)
      val tx1 = tx.updateSigScript(0, OP_PUSHDATA(sig) :: OP_PUSHDATA(receiveKey().publicKey.value) :: Nil)
      Transaction.correctlySpends(tx1, Seq(incomingTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      tx1
    }

    def swapWallet()(implicit system: ActorSystem) = {
      val probe = TestProbe()
      val newWalletName = "temp-" + Random.nextInt()

      // creates a new bitcoind wallet
      bitcoinrpcclient.invoke("createwallet", newWalletName).pipeTo(probe.ref)
      probe.expectMsgType[JValue]

      // unload the default wallet (named ""), automatically makes the new one default
      bitcoinrpcclient.invoke("unloadwallet", "").pipeTo(probe.ref)
      probe.expectMsgType[JValue]
      newWalletName
    }

  }
}