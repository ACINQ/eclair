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
import java.sql.DriverManager

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.whisk.docker.DockerReadyChecker
import fr.acinq.bitcoin.{BinaryData, Block, Btc, DeterministicWallet, MnemonicCode, Satoshi, Transaction, TxOut}
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet.{FundTransactionResponse, SignTransactionResponse}
import fr.acinq.eclair.blockchain.bitcoind.{BitcoinCoreWallet, BitcoindService}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{BroadcastTransaction, BroadcastTransactionResponse, SSL}
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool.ElectrumServerAddress
import fr.acinq.eclair.blockchain.electrum.db.sqlite.SqliteWalletDb
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JDecimal, JString, JValue}
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


class ElectrumWalletSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with BitcoindService with ElectrumxService with BeforeAndAfterAll with Logging {

  import ElectrumWallet._

  val entropy = BinaryData("01" * 32)
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

  test("generate 500 blocks") {
    val sender = TestProbe()
    logger.info(s"waiting for bitcoind to initialize...")
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("getnetworkinfo"))
      sender.receiveOne(5 second).isInstanceOf[JValue]
    }, max = 30 seconds, interval = 500 millis)
    logger.info(s"generating initial blocks...")
    sender.send(bitcoincli, BitcoinReq("generate", 500))
    sender.expectMsgType[JValue](30 seconds)
    DockerReadyChecker.LogLineContains("INFO:BlockProcessor:height: 501").looped(attempts = 15, delay = 1 second)
  }

  test("wait until wallet is ready") {
    electrumClient = system.actorOf(Props(new ElectrumClientPool(Set(ElectrumServerAddress(new InetSocketAddress("localhost", 50001), SSL.OFF)))))
    wallet = system.actorOf(Props(new ElectrumWallet(seed, electrumClient, WalletParameters(Block.RegtestGenesisBlock.hash, new SqliteWalletDb(DriverManager.getConnection("jdbc:sqlite::memory:")), minimumFee = Satoshi(5000)))), "wallet")
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
      val GetBalanceResponse(confirmed1, unconfirmed1) = getBalance(probe)
      unconfirmed1 == unconfirmed + Satoshi(100000000L)
    }, max = 30 seconds, interval = 1 second)

    // confirm our tx
    probe.send(bitcoincli, BitcoinReq("generate", 1))
    probe.expectMsgType[JValue]

    awaitCond({
      val GetBalanceResponse(confirmed1, unconfirmed1) = getBalance(probe)
      confirmed1 == confirmed + Satoshi(100000000L)
    }, max = 30 seconds, interval = 1 second)

    val GetCurrentReceiveAddressResponse(address1) = getCurrentAddress(probe)

    logger.info(s"sending 1 btc to $address1")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address1, 1.0))
    probe.expectMsgType[JValue]
    logger.info(s"sending 0.5 btc to $address1")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address1, 0.5))
    probe.expectMsgType[JValue]

    probe.send(bitcoincli, BitcoinReq("generate", 1))
    probe.expectMsgType[JValue]

    awaitCond({
      val GetBalanceResponse(confirmed1, unconfirmed1) = getBalance(probe)
      confirmed1 == confirmed + Satoshi(250000000L)
    }, max = 30 seconds, interval = 1 second)
  }

  test("handle transactions with identical outputs to us") {
    val probe = TestProbe()
    val GetBalanceResponse(confirmed, unconfirmed) = getBalance(probe)
    logger.info(s"initial balance: $confirmed $unconfirmed")

    // send money to our wallet
    val amount = Satoshi(750000)
    val GetCurrentReceiveAddressResponse(address) = getCurrentAddress(probe)
    val tx = Transaction(version = 2,
      txIn = Nil,
      txOut = Seq(
        TxOut(amount, fr.acinq.eclair.addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)),
        TxOut(amount, fr.acinq.eclair.addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash))
      ), lockTime = 0L)
    val btcWallet = new BitcoinCoreWallet(bitcoinrpcclient)
    val future = for {
      FundTransactionResponse(tx1, pos, fee) <- btcWallet.fundTransaction(tx, false, 10000)
      SignTransactionResponse(tx2, true) <- btcWallet.signTransaction(tx1)
      txid <- btcWallet.publishTransaction(tx2)
    } yield txid
    val txid = Await.result(future, 10 seconds)

    awaitCond({
      val GetBalanceResponse(confirmed1, unconfirmed1) = getBalance(probe)
      unconfirmed1 == unconfirmed + amount + amount
    }, max = 30 seconds, interval = 1 second)

    probe.send(bitcoincli, BitcoinReq("generate", 1))
    probe.expectMsgType[JValue]

    awaitCond({
      val GetBalanceResponse(confirmed1, unconfirmed1) = getBalance(probe)
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
      val GetBalanceResponse(confirmed1, unconfirmed1) = getBalance(probe)
      unconfirmed1 - unconfirmed == Satoshi(100000000L)
    }, max = 30 seconds, interval = 1 second)

    val TransactionReceived(tx, 0, received, sent, _, _) = listener.receiveOne(5 seconds)
    assert(tx.txid === BinaryData(txid))
    assert(received === Satoshi(100000000))

    logger.info("generating a new block")
    probe.send(bitcoincli, BitcoinReq("generate", 1))
    probe.expectMsgType[JValue]

    awaitCond({
      val GetBalanceResponse(confirmed1, unconfirmed1) = getBalance(probe)
      confirmed1 - confirmed == Satoshi(100000000L)
    }, max = 30 seconds, interval = 1 second)

    awaitCond({
      val msg = listener.receiveOne(5 seconds)
      msg match {
        case TransactionConfidenceChanged(BinaryData(txid), 1, _) => true
        case _ => false
      }
    }, max = 30 seconds, interval = 1 second)
  }

  test("send money to someone else (we broadcast)") {
    val probe = TestProbe()
    val GetBalanceResponse(confirmed, unconfirmed) = getBalance(probe)

    // create a tx that sends money to Bitcoin Core's address
    probe.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = probe.expectMsgType[JValue]
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(1), fr.acinq.eclair.addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)) :: Nil, lockTime = 0L)
    probe.send(wallet, CompleteTransaction(tx, 20000))
    val CompleteTransactionResponse(tx1, fee1, None) = probe.expectMsgType[CompleteTransactionResponse]

    // send it ourselves
    logger.info(s"sending 1 btc to $address with tx ${tx1.txid}")
    probe.send(wallet, BroadcastTransaction(tx1))
    val BroadcastTransactionResponse(_, None) = probe.expectMsgType[BroadcastTransactionResponse]

    probe.send(bitcoincli, BitcoinReq("generate", 1))
    probe.expectMsgType[JValue]

    awaitCond({
      probe.send(bitcoincli, BitcoinReq("getreceivedbyaddress", address))
      val JDecimal(value) = probe.expectMsgType[JValue]
      value == BigDecimal(1.0)
    }, max = 30 seconds, interval = 1 second)

    awaitCond({
      val GetBalanceResponse(confirmed1, unconfirmed1) = getBalance(probe)
      logger.debug(s"current balance is $confirmed1")
      confirmed1 < confirmed - Btc(1) && confirmed1 > confirmed - Btc(1) - Satoshi(50000)
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
    probe.send(wallet, CompleteTransaction(tx, 20000))
    val CompleteTransactionResponse(tx1, fee1, None) = probe.expectMsgType[CompleteTransactionResponse]

    // send it ourselves
    logger.info(s"sending 1 btc to $address with tx ${tx1.txid}")
    probe.send(wallet, BroadcastTransaction(tx1))
    val BroadcastTransactionResponse(_, None) = probe.expectMsgType[BroadcastTransactionResponse]

    probe.send(bitcoincli, BitcoinReq("generate", 1))
    probe.expectMsgType[JValue]

    awaitCond({
      val GetBalanceResponse(confirmed1, unconfirmed1) = getBalance(probe)
      logger.info(s"current balance is $confirmed $unconfirmed")
      confirmed1 < confirmed - Btc(1) && confirmed1 > confirmed - Btc(1) - Satoshi(50000)
    }, max = 30 seconds, interval = 1 second)
  }
}
