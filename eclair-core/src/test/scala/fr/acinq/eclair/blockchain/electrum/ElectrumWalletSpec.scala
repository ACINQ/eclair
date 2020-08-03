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

package fr.acinq.eclair.blockchain.electrum

import java.net.InetSocketAddress
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicLong

import akka.actor.{ActorRef, Props}
import akka.testkit.{TestKit, TestProbe}
import com.whisk.docker.DockerReadyChecker
import fr.acinq.bitcoin.{Block, Btc, ByteVector32, DeterministicWallet, MnemonicCode, OutPoint, Satoshi, Script, ScriptFlags, ScriptWitness, SigVersion, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet.{FundTransactionResponse, SignTransactionResponse}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.bitcoind.{BitcoinCoreWallet, BitcoindService}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{BroadcastTransaction, BroadcastTransactionResponse, SSL}
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool.ElectrumServerAddress
import fr.acinq.eclair.blockchain.electrum.db.sqlite.SqliteWalletDb
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.{LongToBtcAmount, TestKitBaseClass}
import fr.acinq.{bitcoin, eclair}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JDecimal, JString, JValue}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.ByteVector

import scala.concurrent.Await
import scala.concurrent.duration._

class ElectrumWalletSpec extends TestKitBaseClass with AnyFunSuiteLike with BitcoindService with ElectrumxService with BeforeAndAfterAll with Logging {

  import ElectrumWallet._

  val entropy = ByteVector32(ByteVector.fill(32)(1))
  val mnemonics = MnemonicCode.toMnemonics(entropy)
  val seed = MnemonicCode.toSeed(mnemonics, "")
  logger.info(s"mnemonic codes for our wallet: $mnemonics")
  val master = DeterministicWallet.generate(seed)
  var wallet: ActorRef = _
  var electrumClient: ActorRef = _

  override def beforeAll(): Unit = {
    logger.info("starting bitcoind")
    startBitcoind()
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    logger.info("stopping bitcoind")
    stopBitcoind()
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }

  def getCurrentAddress(probe: TestProbe) = {
    probe.send(wallet, GetCurrentReceiveAddress)
    probe.expectMsgType[GetCurrentReceiveAddressResponse]
  }

  def getBalance(probe: TestProbe) = {
    probe.send(wallet, GetBalance)
    probe.expectMsgType[GetBalanceResponse]
  }

  test("generate 150 blocks") {
    val sender = TestProbe()
    logger.info(s"waiting for bitcoind to initialize...")
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("getnetworkinfo"))
      sender.receiveOne(5 second).isInstanceOf[JValue]
    }, max = 30 seconds, interval = 500 millis)
    logger.info(s"generating initial blocks...")
    generateBlocks(bitcoincli, 150, timeout = 30 seconds)
    DockerReadyChecker.LogLineContains("INFO:BlockProcessor:height: 151").looped(attempts = 15, delay = 1 second)
  }

  test("wait until wallet is ready") {
    electrumClient = system.actorOf(Props(new ElectrumClientPool(new AtomicLong(), Set(ElectrumServerAddress(new InetSocketAddress("localhost", electrumPort), SSL.OFF)))))
    wallet = system.actorOf(Props(new ElectrumWallet(seed, electrumClient, WalletParameters(Block.RegtestGenesisBlock.hash, new SqliteWalletDb(DriverManager.getConnection("jdbc:sqlite::memory:")), minimumFee = 5000 sat))), "wallet")
    val probe = TestProbe()
    awaitCond({
      probe.send(wallet, GetData)
      val GetDataResponse(state) = probe.expectMsgType[GetDataResponse]
      state.status.size == state.accountKeys.size + state.changeKeys.size
    }, max = 30 seconds, interval = 1 second)
    logger.info(s"wallet is ready")
  }

  test("receive funds") {
    val probe = TestProbe()
    val GetBalanceResponse(confirmed, unconfirmed) = getBalance(probe)
    logger.info(s"initial balance: $confirmed $unconfirmed")

    // send money to our wallet
    val GetCurrentReceiveAddressResponse(address) = getCurrentAddress(probe)

    logger.info(s"sending 1 btc to $address")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address, 1.0))
    probe.expectMsgType[JValue]

    awaitCond({
      val GetBalanceResponse(_, unconfirmed1) = getBalance(probe)
      unconfirmed1 == unconfirmed + 100000000.sat
    }, max = 30 seconds, interval = 1 second)

    // confirm our tx
    generateBlocks(bitcoincli, 1)
    awaitCond({
      val GetBalanceResponse(confirmed1, _) = getBalance(probe)
      confirmed1 == confirmed + 100000000.sat
    }, max = 30 seconds, interval = 1 second)

    val GetCurrentReceiveAddressResponse(address1) = getCurrentAddress(probe)

    logger.info(s"sending 1 btc to $address1")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address1, 1.0))
    probe.expectMsgType[JValue]
    logger.info(s"sending 0.5 btc to $address1")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address1, 0.5))
    probe.expectMsgType[JValue]
    generateBlocks(bitcoincli, 1)

    awaitCond({
      val GetBalanceResponse(confirmed1, _) = getBalance(probe)
      confirmed1 == confirmed + 250000000.sat
    }, max = 30 seconds, interval = 1 second)
  }

  test("handle transactions with identical outputs to us") {
    val probe = TestProbe()
    val GetBalanceResponse(confirmed, unconfirmed) = getBalance(probe)
    logger.info(s"initial balance: $confirmed $unconfirmed")

    // send money to our wallet
    val amount = 750000 sat
    val GetCurrentReceiveAddressResponse(address) = getCurrentAddress(probe)
    val tx = Transaction(version = 2,
      txIn = Nil,
      txOut = Seq(
        TxOut(amount, fr.acinq.eclair.addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)),
        TxOut(amount, fr.acinq.eclair.addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash))
      ), lockTime = 0L)
    val btcWallet = new BitcoinCoreWallet(bitcoinrpcclient)
    val btcClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    val future = for {
      FundTransactionResponse(tx1, _, _) <- btcWallet.fundTransaction(tx, lockUnspents = false, FeeratePerKw(10000 sat))
      SignTransactionResponse(tx2, true) <- btcWallet.signTransaction(tx1)
      txid <- btcClient.publishTransaction(tx2)
    } yield txid
    Await.result(future, 10 seconds)

    awaitCond({
      val GetBalanceResponse(_, unconfirmed1) = getBalance(probe)
      unconfirmed1 == unconfirmed + amount + amount
    }, max = 30 seconds, interval = 1 second)

    generateBlocks(bitcoincli, 1)
    awaitCond({
      val GetBalanceResponse(confirmed1, _) = getBalance(probe)
      confirmed1 == confirmed + amount + amount
    }, max = 30 seconds, interval = 1 second)
  }

  test("receive 'confidence changed' notification") {
    val probe = TestProbe()
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[WalletEvent])

    val GetCurrentReceiveAddressResponse(address) = getCurrentAddress(probe)
    val GetBalanceResponse(confirmed, unconfirmed) = getBalance(probe)
    logger.info(s"initial balance $confirmed $unconfirmed")

    logger.info(s"sending 1 btc to $address")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address, 1.0))
    val JString(txid) = probe.expectMsgType[JValue]
    logger.info(s"$txid sent 1 btc to us at $address")
    awaitCond({
      val GetBalanceResponse(_, unconfirmed1) = getBalance(probe)
      unconfirmed1 - unconfirmed === 100000000L.sat
    }, max = 30 seconds, interval = 1 second)

    val TransactionReceived(tx, 0, received, _, _, _) = listener.receiveOne(5 seconds)
    assert(tx.txid === ByteVector32.fromValidHex(txid))
    assert(received === 100000000.sat)

    logger.info("generating a new block")
    generateBlocks(bitcoincli, 1)
    awaitCond({
      val GetBalanceResponse(confirmed1, _) = getBalance(probe)
      confirmed1 - confirmed === 100000000.sat
    }, max = 30 seconds, interval = 1 second)

    awaitCond({
      val msg = listener.receiveOne(5 seconds)
      msg match {
        case TransactionConfidenceChanged(_, 1, _) => true
        case _ => false
      }
    }, max = 30 seconds, interval = 1 second)
  }

  test("send money to someone else (we broadcast)") {
    val probe = TestProbe()
    val GetBalanceResponse(confirmed, _) = getBalance(probe)

    // create a tx that sends money to Bitcoin Core's address
    probe.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = probe.expectMsgType[JValue]
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(1), fr.acinq.eclair.addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)) :: Nil, lockTime = 0L)
    probe.send(wallet, CompleteTransaction(tx, FeeratePerKw(20000 sat)))
    val CompleteTransactionResponse(tx1, _, None) = probe.expectMsgType[CompleteTransactionResponse]

    // send it ourselves
    logger.info(s"sending 1 btc to $address with tx ${tx1.txid}")
    probe.send(wallet, BroadcastTransaction(tx1))
    val BroadcastTransactionResponse(_, None) = probe.expectMsgType[BroadcastTransactionResponse]

    generateBlocks(bitcoincli, 1)

    awaitCond({
      probe.send(bitcoincli, BitcoinReq("getreceivedbyaddress", address))
      val JDecimal(value) = probe.expectMsgType[JValue]
      value == BigDecimal(1.0)
    }, max = 30 seconds, interval = 1 second)

    awaitCond({
      val GetBalanceResponse(confirmed1, _) = getBalance(probe)
      logger.debug(s"current balance is $confirmed1")
      confirmed1 < confirmed - 1.btc && confirmed1 > confirmed - 1.btc - 50000.sat
    }, max = 30 seconds, interval = 1 second)
  }

  test("send money to ourselves (we broadcast)") {
    val probe = TestProbe()
    val GetBalanceResponse(confirmed, unconfirmed) = getBalance(probe)
    logger.info(s"current balance is $confirmed $unconfirmed")

    // create a tx that sends money to Bitcoin Core's address
    probe.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = probe.expectMsgType[JValue]
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(1), fr.acinq.eclair.addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)) :: Nil, lockTime = 0L)
    probe.send(wallet, CompleteTransaction(tx, FeeratePerKw(20000 sat)))
    val CompleteTransactionResponse(tx1, _, None) = probe.expectMsgType[CompleteTransactionResponse]

    // send it ourselves
    logger.info(s"sending 1 btc to $address with tx ${tx1.txid}")
    probe.send(wallet, BroadcastTransaction(tx1))
    val BroadcastTransactionResponse(_, None) = probe.expectMsgType[BroadcastTransactionResponse]

    generateBlocks(bitcoincli, 1)

    awaitCond({
      val GetBalanceResponse(confirmed1, _) = getBalance(probe)
      logger.info(s"current balance is $confirmed $unconfirmed")
      confirmed1 < confirmed - 1.btc && confirmed1 > confirmed - 1.btc - 50000.sat
    }, max = 30 seconds, interval = 1 second)
  }

  test("detect is a tx has been double-spent") {
    val probe = TestProbe()
    val GetBalanceResponse(confirmed, unconfirmed) = getBalance(probe)
    logger.info(s"current balance is $confirmed $unconfirmed")

    // create 2 transactions that spend the same wallet UTXO
    val tx1 = {
      probe.send(bitcoincli, BitcoinReq("getnewaddress"))
      val JString(address) = probe.expectMsgType[JValue]
      val tmp = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(1), fr.acinq.eclair.addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)) :: Nil, lockTime = 0L)
      probe.send(wallet, CompleteTransaction(tmp, FeeratePerKw(20000 sat)))
      val CompleteTransactionResponse(tx, _, None) = probe.expectMsgType[CompleteTransactionResponse]
      probe.send(wallet, CancelTransaction(tx))
      probe.expectMsg(CancelTransactionResponse(tx))
      tx
    }
    val tx2 = {
      probe.send(bitcoincli, BitcoinReq("getnewaddress"))
      val JString(address) = probe.expectMsgType[JValue]
      val tmp = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(1), fr.acinq.eclair.addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)) :: Nil, lockTime = 0L)
      probe.send(wallet, CompleteTransaction(tmp, FeeratePerKw(20000 sat)))
      val CompleteTransactionResponse(tx, _, None) = probe.expectMsgType[CompleteTransactionResponse]
      probe.send(wallet, CancelTransaction(tx))
      probe.expectMsg(CancelTransactionResponse(tx))
      tx
    }

    probe.send(wallet, IsDoubleSpent(tx1))
    probe.expectMsg(IsDoubleSpentResponse(tx1, isDoubleSpent = false))
    probe.send(wallet, IsDoubleSpent(tx2))
    probe.expectMsg(IsDoubleSpentResponse(tx2, isDoubleSpent = false))

    // publish tx1
    probe.send(wallet, BroadcastTransaction(tx1))
    probe.expectMsg(BroadcastTransactionResponse(tx1, None))

    awaitCond({
      probe.send(wallet, GetData)
      val data = probe.expectMsgType[GetDataResponse].state
      data.heights.contains(tx1.txid) && data.transactions.contains(tx1.txid)
    }, max = 30 seconds, interval = 1 second)

    // as long as tx1 is unconfirmed tx2 won't be considered double-spent
    probe.send(wallet, IsDoubleSpent(tx1))
    probe.expectMsg(IsDoubleSpentResponse(tx1, isDoubleSpent = false))
    probe.send(wallet, IsDoubleSpent(tx2))
    probe.expectMsg(IsDoubleSpentResponse(tx2, isDoubleSpent = false))

    generateBlocks(bitcoincli, 2)

    awaitCond({
      probe.send(wallet, GetData)
      val data = probe.expectMsgType[GetDataResponse].state
      data.heights.exists { case (txid, height) => txid == tx1.txid && data.transactions.contains(txid) && ElectrumWallet.computeDepth(data.blockchain.height, height) > 1 }
    }, max = 30 seconds, interval = 1 second)

    // tx2 is double-spent
    probe.send(wallet, IsDoubleSpent(tx1))
    probe.expectMsg(IsDoubleSpentResponse(tx1, isDoubleSpent = false))
    probe.send(wallet, IsDoubleSpent(tx2))
    probe.expectMsg(IsDoubleSpentResponse(tx2, isDoubleSpent = true))
  }

  test("use all available balance") {
    val probe = TestProbe()

    // send all our funds to ourself, so we have only one utxo which is the worse case here
    val GetCurrentReceiveAddressResponse(address) = getCurrentAddress(probe)
    probe.send(wallet, SendAll(Script.write(eclair.addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)), FeeratePerKw(750 sat)))
    val SendAllResponse(tx, _) = probe.expectMsgType[SendAllResponse]
    probe.send(wallet, BroadcastTransaction(tx))
    val BroadcastTransactionResponse(`tx`, None) = probe.expectMsgType[BroadcastTransactionResponse]

    generateBlocks(bitcoincli, 1)

    awaitCond({
      probe.send(wallet, GetData)
      val data = probe.expectMsgType[GetDataResponse].state
      data.utxos.length == 1 && data.utxos(0).outPoint.txid == tx.txid
    }, max = 30 seconds, interval = 1 second)


    // send everything to a multisig 2-of-2, with the smallest possible fee rate
    val priv = eclair.randomKey
    val script = Script.pay2wsh(Scripts.multiSig2of2(priv.publicKey, priv.publicKey))
    probe.send(wallet, SendAll(Script.write(script), FeeratePerKw.MinimumFeeratePerKw))
    val SendAllResponse(tx1, _) = probe.expectMsgType[SendAllResponse]
    probe.send(wallet, BroadcastTransaction(tx1))
    val BroadcastTransactionResponse(`tx1`, None) = probe.expectMsgType[BroadcastTransactionResponse]

    generateBlocks(bitcoincli, 1)

    awaitCond({
      probe.send(wallet, GetData)
      val data = probe.expectMsgType[GetDataResponse].state
      data.utxos.isEmpty
    }, max = 30 seconds, interval = 1 second)

    // send everything back to ourselves again
    val tx2 = Transaction(version = 2,
      txIn = TxIn(OutPoint(tx1, 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(Satoshi(0), eclair.addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)) :: Nil,
      lockTime = 0)

    val sig = Transaction.signInput(tx2, 0, Scripts.multiSig2of2(priv.publicKey, priv.publicKey), bitcoin.SIGHASH_ALL, tx1.txOut(0).amount, SigVersion.SIGVERSION_WITNESS_V0, priv)
    val tx3 = tx2.updateWitness(0, ScriptWitness(Seq(ByteVector.empty, sig, sig, Script.write(Scripts.multiSig2of2(priv.publicKey, priv.publicKey)))))
    Transaction.correctlySpends(tx3, Seq(tx1), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    val fee = Transactions.weight2fee(FeeratePerKw.MinimumFeeratePerKw, tx3.weight())
    val tx4 = tx3.copy(txOut = tx3.txOut(0).copy(amount = tx1.txOut(0).amount - fee) :: Nil)
    val sig1 = Transaction.signInput(tx4, 0, Scripts.multiSig2of2(priv.publicKey, priv.publicKey), bitcoin.SIGHASH_ALL, tx1.txOut(0).amount, SigVersion.SIGVERSION_WITNESS_V0, priv)
    val tx5 = tx4.updateWitness(0, ScriptWitness(Seq(ByteVector.empty, sig1, sig1, Script.write(Scripts.multiSig2of2(priv.publicKey, priv.publicKey)))))

    probe.send(wallet, BroadcastTransaction(tx5))
    val BroadcastTransactionResponse(_, None) = probe.expectMsgType[BroadcastTransactionResponse]

    awaitCond({
      probe.send(wallet, GetData)
      val data = probe.expectMsgType[GetDataResponse].state
      data.utxos.length == 1 && data.utxos(0).outPoint.txid == tx5.txid
    }, max = 30 seconds, interval = 1 second)
  }
}
