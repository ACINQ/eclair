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

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.whisk.docker.DockerReadyChecker
import fr.acinq.bitcoin.{Block, Btc, ByteVector32, DeterministicWallet, MnemonicCode, Satoshi, Transaction, TxOut}
import fr.acinq.eclair.blockchain.MakeFundingTxResponse
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet.FundTransactionResponse
import fr.acinq.eclair.blockchain.bitcoind.{BitcoinCoreWallet, BitcoindService}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{BroadcastTransaction, BroadcastTransactionResponse, SSL}
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool.ElectrumServerAddress
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet._
import fr.acinq.eclair.blockchain.electrum.db.sqlite.SqliteWalletDb
import fr.acinq.eclair.channel.Channel
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JArray, JDecimal, JString, JValue}
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}
import scodec.bits.ByteVector

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class ElectrumWalletSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with BitcoindService with ElectrumxService with BeforeAndAfterAll with Logging {

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
    sender.send(bitcoincli, BitcoinReq("generate", 150))
    sender.expectMsgType[JValue](30 seconds)
    DockerReadyChecker.LogLineContains("INFO:BlockProcessor:height: 151").looped(attempts = 15, delay = 1 second)
  }

  test("wait until wallet is ready") {
    electrumClient = system.actorOf(Props(new ElectrumClientPool(Set(ElectrumServerAddress(new InetSocketAddress("localhost", electrumPort), SSL.OFF)))))
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
      BitcoinCoreWallet.SignTransactionResponse(tx2, true) <- btcWallet.signTransaction(tx1)
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
    assert(tx.txid === ByteVector32.fromValidHex(txid))
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
        case TransactionConfidenceChanged(txid, 1, _) => true
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

  test("detect is a tx has been double-spent") {
    val probe = TestProbe()
    val GetBalanceResponse(confirmed, unconfirmed) = getBalance(probe)
    logger.info(s"current balance is $confirmed $unconfirmed")

    // create 2 transactions that spend the same wallet UTXO
    val tx1 = {
      probe.send(bitcoincli, BitcoinReq("getnewaddress"))
      val JString(address) = probe.expectMsgType[JValue]
      val tmp = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(1), fr.acinq.eclair.addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)) :: Nil, lockTime = 0L)
      probe.send(wallet, CompleteTransaction(tmp, 20000))
      val CompleteTransactionResponse(tx, fee1, None) = probe.expectMsgType[CompleteTransactionResponse]
      probe.send(wallet, CancelTransaction(tx))
      probe.expectMsg(CancelTransactionResponse(tx))
      tx
    }
    val tx2 = {
      probe.send(bitcoincli, BitcoinReq("getnewaddress"))
      val JString(address) = probe.expectMsgType[JValue]
      val tmp = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(1), fr.acinq.eclair.addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)) :: Nil, lockTime = 0L)
      probe.send(wallet, CompleteTransaction(tmp, 20000))
      val CompleteTransactionResponse(tx, fee1, None) = probe.expectMsgType[CompleteTransactionResponse]
      probe.send(wallet, CancelTransaction(tx))
      probe.expectMsg(CancelTransactionResponse(tx))
      tx
    }

    probe.send(wallet, IsDoubleSpent(tx1))
    probe.expectMsg(IsDoubleSpentResponse(tx1, false))
    probe.send(wallet, IsDoubleSpent(tx2))
    probe.expectMsg(IsDoubleSpentResponse(tx2, false))

    // publish and confirm tx1
    probe.send(wallet, BroadcastTransaction(tx1))
    probe.expectMsg(BroadcastTransactionResponse(tx1, None))
    probe.send(bitcoincli, BitcoinReq("generate", 1))
    probe.expectMsgType[JValue]

    awaitCond({
      val GetBalanceResponse(confirmed1, unconfirmed1) = getBalance(probe)
      logger.info(s"current balance is $confirmed $unconfirmed")
      confirmed1 < confirmed - Btc(1)
    }, max = 30 seconds, interval = 1 second)

    // tx2 is double spent
    probe.send(wallet, IsDoubleSpent(tx1))
    probe.expectMsg(IsDoubleSpentResponse(tx1, false))
    probe.send(wallet, IsDoubleSpent(tx2))
    probe.expectMsg(IsDoubleSpentResponse(tx2, true))
  }

  test("fund, sign and broadcast a transaction") {
    val probe = TestProbe()

    val GetBalanceResponse(confirmed, unconfirmed) = getBalance(probe)
    logger.info(s" balance: $confirmed $unconfirmed")

    // send money to our wallet
    val GetCurrentReceiveAddressResponse(address) = getCurrentAddress(probe)

    logger.info(s"sending 1 btc to $address")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address, 1.0))
    probe.expectMsgType[JValue]

    awaitCond({
      val GetBalanceResponse(_, unconfirmed1) = getBalance(probe)
      unconfirmed1 == unconfirmed + Satoshi(100000000L)
    }, max = 30 seconds, interval = 1 second)

    // confirm our tx
    probe.send(bitcoincli, BitcoinReq("generate", 1))
    probe.expectMsgType[JValue]

    awaitCond({
      val GetBalanceResponse(confirmed1, _) = getBalance(probe)
      confirmed1 == confirmed + Satoshi(100000000L)
    }, max = 30 seconds, interval = 1 second)

    val amountToSend = Satoshi(50000000L) // 0.5 BTC

    // create a tx that sends money to Bitcoin Core's address
    probe.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(destinationAddress) = probe.expectMsgType[JValue]

    // raw transaction that sends the funds to a hardcoded address
    val hardcodedOutput = TxOut(amountToSend, fr.acinq.eclair.addressToPublicKeyScript("2N2JczfZK7tDJ9yuH3eQ9S64fL3dMp5eNCr", Block.RegtestGenesisBlock.hash))
    val tx = Transaction(version = 2, txIn = Nil, txOut = hardcodedOutput :: Nil, lockTime = 0L)

    // this will ask the electrum wallet to attach an input (fund the tx)
    probe.send(wallet, CompleteTransaction(tx, 20000))
    val CompleteTransactionResponse(tx1, _, None) = probe.expectMsgType[CompleteTransactionResponse]

    // we strip the signatures from the tx
    val tx2 = Channel.stripSignaturesFromTx(MakeFundingTxResponse(tx1, 0, Satoshi(0))).fundingTx

    // assert there are no signatures
    assert(tx2.txIn.forall(_.signatureScript.isEmpty))
    assert(tx2.txIn.forall(!_.hasWitness))

    // update the transaction with the actual output we want
    val tx3 = tx2.copy(txOut = tx2.txOut.updated(
      tx2.txOut.indexOf(hardcodedOutput),
      TxOut(amountToSend, fr.acinq.eclair.addressToPublicKeyScript(destinationAddress, Block.RegtestGenesisBlock.hash))
    ))

    // ask the electrum wallet to sign the transaction again
    probe.send(wallet, SignTransaction(tx3))
    val ElectrumWallet.SignTransactionResponse(tx4) = probe.expectMsgType[SignTransactionResponse]

    // broadcast the transaction
    logger.info(s"sending 0.5 btc to $address with tx ${tx4.txid}")
    probe.send(wallet, BroadcastTransaction(tx4))
    val BroadcastTransactionResponse(_, None) = probe.expectMsgType[BroadcastTransactionResponse]

    // mine a block
    probe.send(bitcoincli, BitcoinReq("generate", 1))
    val JArray(List(JString(blockHash))) = probe.expectMsgType[JValue]

    // get the block
    probe.send(bitcoincli, BitcoinReq("getblock", blockHash, 0))
    val JString(serializedBlock) = probe.expectMsgType[JValue]

    // assert the block contains our transaction
    assert(Block.read(serializedBlock).tx.exists(_.txid == tx4.txid))
  }

}
