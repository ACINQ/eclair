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

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.{TestKitBase, TestProbe}
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{Block, Btc, BtcAmount, MilliBtc, Satoshi, Transaction, TxOut, computeP2WpkhAddress}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.{Descriptor, PreviousTx}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinJsonRPCAuthMethod.{SafeCookie, UserPassword}
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinCoreClient, BitcoinJsonRPCAuthMethod, BitcoinJsonRPCClient}
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKB, FeeratePerKw}
import fr.acinq.eclair.crypto.keymanager.{LocalOnchainKeyManager, OnchainKeyManager}
import fr.acinq.eclair.integration.IntegrationSpec
import fr.acinq.eclair.{BlockHeight, TestUtils, addressToPublicKeyScript, randomKey}
import grizzled.slf4j.Logging
import org.json4s.JsonAST._
import scodec.bits.ByteVector
import sttp.client3.okhttp.OkHttpFutureBackend

import java.io.File
import java.nio.file.Files
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Source

trait BitcoindService extends Logging {
  self: TestKitBase =>

  def useEclairSigner: Boolean = false

  import BitcoindService._

  import scala.sys.process._

  implicit val system: ActorSystem
  implicit val sttpBackend = OkHttpFutureBackend()

  val defaultWallet: String = if (useEclairSigner) "eclair" else "miner"
  val bitcoindPort: Int = TestUtils.availablePort
  val bitcoindRpcPort: Int = TestUtils.availablePort
  val bitcoindZmqBlockPort: Int = TestUtils.availablePort
  val bitcoindZmqTxPort: Int = TestUtils.availablePort

  val INTEGRATION_TMP_DIR: File = TestUtils.newIntegrationTmpDir()
  logger.info(s"using tmp dir: $INTEGRATION_TMP_DIR")

  val PATH_BITCOIND = sys.env.get("BITCOIND_DIR") match {
    case Some(customBitcoinDir) => new File(customBitcoinDir, "bitcoind")
    case None => new File(TestUtils.BUILD_DIRECTORY, "bitcoin-23.1/bin/bitcoind")
  }
  logger.info(s"using bitcoind: $PATH_BITCOIND")
  val PATH_BITCOIND_DATADIR = new File(INTEGRATION_TMP_DIR, "datadir-bitcoin")

  var bitcoind: Process = _
  var bitcoinrpcclient: BitcoinJsonRPCClient = _
  var bitcoinrpcauthmethod: BitcoinJsonRPCAuthMethod = _
  var bitcoincli: ActorRef = _
  val onchainKeyManager = new LocalOnchainKeyManager(ByteVector.fromValidHex("01" * 32), Block.RegtestGenesisBlock.hash)
  def startBitcoind(useCookie: Boolean = false,
                    defaultAddressType_opt: Option[String] = None,
                    mempoolSize_opt: Option[Int] = None, // mempool size in MB
                    mempoolMinFeerate_opt: Option[FeeratePerByte] = None, // transactions below this feerate won't be accepted in the mempool
                    startupFlags: String = ""): Unit = {
    Files.createDirectories(PATH_BITCOIND_DATADIR.toPath)
    if (!Files.exists(new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath)) {
      val is = classOf[IntegrationSpec].getResourceAsStream("/integration/bitcoin.conf")
      val conf = {
        val defaultConf = Source.fromInputStream(is).mkString
          .replace("28333", bitcoindPort.toString)
          .replace("28332", bitcoindRpcPort.toString)
          .replace("28334", bitcoindZmqBlockPort.toString)
          .replace("28335", bitcoindZmqTxPort.toString)
          .appendedAll(defaultAddressType_opt.map(addressType => s"addresstype=$addressType\n").getOrElse(""))
          .appendedAll(defaultAddressType_opt.map(addressType => s"changetype=$addressType\n").getOrElse(""))
          .appendedAll(mempoolSize_opt.map(mempoolSize => s"maxmempool=$mempoolSize\n").getOrElse(""))
          .appendedAll(mempoolMinFeerate_opt.map(mempoolMinFeerate => s"minrelaytxfee=${FeeratePerKB(mempoolMinFeerate).feerate.toBtc.toBigDecimal}\n").getOrElse(""))
        if (useCookie) {
          defaultConf
            .replace("rpcuser=foo", "")
            .replace("rpcpassword=bar", "")
        } else {
          defaultConf
        }
      }
      Files.writeString(new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath, conf)
    }

    bitcoind = s"$PATH_BITCOIND -datadir=$PATH_BITCOIND_DATADIR $startupFlags".run()
    bitcoinrpcauthmethod = if (useCookie) {
      SafeCookie(s"$PATH_BITCOIND_DATADIR/regtest/.cookie")
    } else {
      UserPassword("foo", "bar")
    }
    bitcoinrpcclient = new BasicBitcoinJsonRPCClient(rpcAuthMethod = bitcoinrpcauthmethod, host = "localhost", port = bitcoindRpcPort, wallet = Some(defaultWallet))
    bitcoincli = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case BitcoinReq(method) => bitcoinrpcclient.invoke(method).pipeTo(sender())
        case BitcoinReq(method, params) => bitcoinrpcclient.invoke(method, params).pipeTo(sender())
        case BitcoinReq(method, param1, param2) => bitcoinrpcclient.invoke(method, param1, param2).pipeTo(sender())
      }
    }))
  }

  def makeBitcoinCoreClient: BitcoinCoreClient = new BitcoinCoreClient(bitcoinrpcclient, if (useEclairSigner) Some(onchainKeyManager) else None)

  def stopBitcoind(): Unit = {
    // gracefully stopping bitcoin will make it store its state cleanly to disk, which is good for later debugging
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("stop"))
    sender.expectMsgType[JValue]
    bitcoind.exitValue()
  }

  def restartBitcoind(sender: TestProbe = TestProbe(), useCookie: Boolean = false, startupFlags: String = "", loadWallet: Boolean = true): Unit = {
    stopBitcoind()
    startBitcoind(useCookie = useCookie, startupFlags = startupFlags)
    waitForBitcoindUp(sender)
    if (loadWallet) {
      sender.send(bitcoincli, BitcoinReq("loadwallet", defaultWallet))
      sender.expectMsgType[JValue]
    }
  }

  private def waitForBitcoindUp(sender: TestProbe): Unit = {
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
  }

  def waitForBitcoindReady(): Unit = {
    val sender = TestProbe()
    waitForBitcoindUp(sender)
    if (useEclairSigner) {
      // wallet_name, disable_private_keys, blank, passphrase, avoid_reuse, descriptors, load_on_startup, external_signer
      bitcoinrpcclient.invoke("createwallet", defaultWallet, true, false, "", false, true, true, false).pipeTo(sender.ref)
      sender.expectMsgType[JValue]

      val jsonRpcClient = new BasicBitcoinJsonRPCClient(rpcAuthMethod = bitcoinrpcauthmethod, host = "localhost", port = bitcoindRpcPort, wallet = Some(defaultWallet))
      importEclairDescriptors(jsonRpcClient, onchainKeyManager)
    } else {
      sender.send(bitcoincli, BitcoinReq("createwallet", defaultWallet))
      sender.expectMsgType[JValue]
    }
    logger.info(s"generating initial blocks to wallet=$defaultWallet...")
    generateBlocks(150)
    awaitCond(currentBlockHeight(sender) >= BlockHeight(150), max = 3 minutes, interval = 2 second)
  }


  def importEclairDescriptors(jsonRpcClient: BitcoinJsonRPCClient, keyManager: OnchainKeyManager, probe: TestProbe = TestProbe()): Unit = {
    val (main, change) = keyManager.getDescriptors(0)
    val descriptors = main.map(d => Descriptor(d)) ++ change.map(d => Descriptor(d, internal = true))
    jsonRpcClient.invoke("importdescriptors", descriptors).pipeTo(probe.ref)
    probe.expectMsgType[JValue]
  }

  def generateBlocks(blockCount: Int, address: Option[String] = None, timeout: FiniteDuration = 10 seconds)(implicit system: ActorSystem): Unit = {
    val sender = TestProbe()
    val addressToUse = address match {
      case Some(addr) => addr
      case None =>
        sender.send(bitcoincli, BitcoinReq("getnewaddress", "", "bech32"))
        val JString(address) = sender.expectMsgType[JValue](timeout)
        address
    }
    sender.send(bitcoincli, BitcoinReq("generatetoaddress", blockCount, addressToUse))
    val JArray(blocks) = sender.expectMsgType[JValue](timeout)
    assert(blocks.size == blockCount)
  }

  def currentBlockHeight(sender: TestProbe = TestProbe()): BlockHeight = {
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val JInt(blockCount) = sender.expectMsgType[JInt]
    BlockHeight(blockCount.toLong)
  }

  /** Create a new wallet and returns an RPC client to interact with it. */
  def createWallet(walletName: String, sender: TestProbe = TestProbe()): BitcoinJsonRPCClient = {
    sender.send(bitcoincli, BitcoinReq("createwallet", walletName))
    sender.expectMsgType[JValue]
    new BasicBitcoinJsonRPCClient(rpcAuthMethod = bitcoinrpcauthmethod, host = "localhost", port = bitcoindRpcPort, wallet = Some(walletName))
  }

  def getNewAddress(sender: TestProbe = TestProbe(), rpcClient: BitcoinJsonRPCClient = bitcoinrpcclient, addressType_opt: Option[String] = None): String = {
    addressType_opt match {
      case Some(addressType) => rpcClient.invoke("getnewaddress", "", addressType).pipeTo(sender.ref)
      case None => rpcClient.invoke("getnewaddress").pipeTo(sender.ref)
    }
    val JString(address) = sender.expectMsgType[JValue]
    address
  }

  def createExternalAddress(): (PrivateKey, String) = {
    val priv = randomKey()
    val address = computeP2WpkhAddress(priv.publicKey, Block.RegtestGenesisBlock.hash)
    (priv, address)
  }

  def sendToAddress(address: String, amount: BtcAmount): Transaction = {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    val amountSat = amount match {
      case amount: Satoshi => amount
      case amount: MilliBtc => amount.toSatoshi
      case amount: Btc => amount.toSatoshi
    }
    val probe = TestProbe()
    val tx = Transaction(version = 2, Nil, TxOut(amountSat, addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)) :: Nil, lockTime = 0)
    val client = makeBitcoinCoreClient
    val f = for {
      funded <- client.fundTransaction(tx, FeeratePerKw(FeeratePerByte(Satoshi(10))), true)
      signed <- client.signPsbt(new Psbt(funded.tx), funded.tx.txIn.indices, Nil)
      txid <- client.publishTransaction(signed.finalTx)
      tx <- client.getTransaction(txid)
    } yield tx
    f.pipeTo(probe.ref)
    probe.expectMsgType[Transaction]
  }

  /** Send to a given address, without generating blocks to confirm. */
  def sendToAddress(address: String, amount: BtcAmount, sender: TestProbe = TestProbe(), rpcClient: BitcoinJsonRPCClient = bitcoinrpcclient): Transaction = {
    val amountDecimal = amount match {
      case amount: Satoshi => amount.toBtc.toBigDecimal
      case amount: MilliBtc => amount.toBtc.toBigDecimal
      case amount: Btc => amount.toBigDecimal
    }
    rpcClient.invoke("sendtoaddress", address, amountDecimal).pipeTo(sender.ref)
    val JString(txid) = sender.expectMsgType[JString]
    rpcClient.invoke("getrawtransaction", txid).pipeTo(sender.ref)
    val JString(rawTx) = sender.expectMsgType[JString]
    Transaction.read(rawTx)
  }

  def signTransaction(client: BitcoinCoreClient, tx: Transaction): Future[SignTransactionResponse] = signTransaction(client, tx, Nil)

  def signTransaction(client: BitcoinCoreClient, tx: Transaction, allowIncomplete: Boolean): Future[SignTransactionResponse] = signTransaction(client, tx, Nil, allowIncomplete)

  def signTransaction(client: BitcoinCoreClient, tx: Transaction, previousTxs: Seq[PreviousTx], allowIncomplete: Boolean = false): Future[SignTransactionResponse] = {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._
    client.signPsbt(new Psbt(tx), tx.txIn.indices, Nil).map(p => SignTransactionResponse(p.extractFinalTx.getOrElse(p.extractPartiallySignedTx), p.extractFinalTx.isRight))
  }
}

object BitcoindService {

  case class BitcoinReq(method: String, params: Any*)

  final case class SignTransactionResponse(tx: Transaction, complete: Boolean)
}