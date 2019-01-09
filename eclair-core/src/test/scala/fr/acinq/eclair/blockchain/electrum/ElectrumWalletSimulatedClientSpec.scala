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

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props, Terminated}
import akka.testkit
import akka.testkit.{TestActor, TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.{BinaryData, Block, BlockHeader, MnemonicCode, Satoshi, Script, Transaction, TxOut}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient._
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet._
import fr.acinq.eclair.blockchain.electrum.db.sqlite.SqliteWalletDb
import org.scalatest.FunSuiteLike

import scala.concurrent.duration._


class ElectrumWalletSimulatedClientSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {
  val sender = TestProbe()

  val entropy = BinaryData("01" * 32)
  val mnemonics = MnemonicCode.toMnemonics(entropy)
  val seed = MnemonicCode.toSeed(mnemonics, "")

  val listener = TestProbe()
  system.eventStream.subscribe(listener.ref, classOf[WalletEvent])

  val client = TestProbe()
  client.ignoreMsg {
    case ElectrumClient.Ping => true
    case _: AddStatusListener => true
    case _: HeaderSubscription => true
  }
  client.setAutoPilot(new testkit.TestActor.AutoPilot {
    override def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
      case ScriptHashSubscription(scriptHash, replyTo) =>
        replyTo ! ScriptHashSubscriptionResponse(scriptHash, "")
        TestActor.KeepRunning
      case _ => TestActor.KeepRunning
    }
  })
  val wallet = TestFSMRef(new ElectrumWallet(seed, client.ref, WalletParameters(Block.RegtestGenesisBlock.hash, new SqliteWalletDb(DriverManager.getConnection("jdbc:sqlite::memory:")), minimumFee = Satoshi(5000))))

  // wallet sends a receive address notification as soon as it is created
  listener.expectMsgType[NewWalletReceiveAddress]

  val genesis = Block.RegtestGenesisBlock.header
  val header1 = makeHeader(genesis, 12345L)
  val header2 = makeHeader(header1, 12346L)
  val header3 = makeHeader(header2, 12347L)
  val header4 = makeHeader(header3, 12348L)

  val headers = Seq(genesis, header1, header2, header3, header4)

  def makeHeader(previousHeader: BlockHeader, timestamp: Long) = previousHeader.copy(hashPreviousBlock = previousHeader.hash, time = timestamp)

  test("wait until wallet is ready") {
    sender.send(wallet, ElectrumClient.ElectrumReady(1, headers(1), InetSocketAddress.createUnresolved("0.0.0.0", 9735)))
    sender.send(wallet, ElectrumClient.HeaderSubscriptionResponse(1, headers(1)))
    awaitCond(wallet.stateName == ElectrumWallet.SYNCING)

    sender.send(wallet, ElectrumClient.GetHeadersResponse(1,Seq(headers(1)), 2016))
    sender.send(wallet, ElectrumClient.GetHeadersResponse(2,Seq(), 2016))
    assert(listener.expectMsgType[WalletReady].timestamp == headers(1).time)
    listener.expectMsgType[NewWalletReceiveAddress]
    listener.send(wallet, GetXpub)
    val GetXpubResponse(xpub, path) = listener.expectMsgType[GetXpubResponse]
    assert(xpub == "tpubDCY62b4okoTERzMurvrtoCMgkswfLufejmhwfShqAKDBN2PPNUWpwx72cvyt4R8enGstorHvXNGS8StbkAsPb7XSbYFER8Wo6zPf1Z6m9w4")
    assert(path == "m/49'/1'/0'")
  }

  test("tell wallet is ready when a new block comes in, even if nothing else has changed") {
    sender.send(wallet, ElectrumClient.HeaderSubscriptionResponse(2, headers(2)))
    assert(listener.expectMsgType[WalletReady].timestamp == headers(2).time)
    val NewWalletReceiveAddress(address) = listener.expectMsgType[NewWalletReceiveAddress]
    assert(address == "2NDjBqJugL3gCtjWTToDgaWWogq9nYuYw31")
  }

  test("tell wallet is ready when it is reconnected, even if nothing has changed") {
    // disconnect wallet
    sender.send(wallet, ElectrumClient.ElectrumDisconnected)
    awaitCond(wallet.stateName == ElectrumWallet.DISCONNECTED)

    // reconnect wallet
    sender.send(wallet, ElectrumClient.ElectrumReady(2, headers(2), InetSocketAddress.createUnresolved("0.0.0.0", 9735)))
    sender.send(wallet, ElectrumClient.HeaderSubscriptionResponse(2, headers(2)))
    awaitCond(wallet.stateName == ElectrumWallet.RUNNING)

    // listener should be notified
    assert(listener.expectMsgType[WalletReady].timestamp == headers(2).time)
    listener.expectMsgType[NewWalletReceiveAddress]
  }

  test("don't send the same ready message more then once") {
    // listener should be notified
    sender.send(wallet, ElectrumClient.HeaderSubscriptionResponse(3, headers(3)))
    assert(listener.expectMsgType[WalletReady].timestamp == headers(3).time)
    listener.expectMsgType[NewWalletReceiveAddress]

    sender.send(wallet, ElectrumClient.HeaderSubscriptionResponse(3, headers(3)))
    listener.expectNoMsg(500 milliseconds)
  }

  test("disconnect if server sends a bad header") {
    val last = wallet.stateData.blockchain.bestchain.last
    val bad = makeHeader(last.header, 42L).copy(bits = Long.MaxValue)

    // here we simulate a bad client
    val sender1 = TestProbe()
    val watcher = TestProbe()
    watcher.watch(sender1.ref)
    watcher.setAutoPilot(new TestActor.AutoPilot {
      override def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
        case Terminated(actor) if actor == sender1.ref =>
          // if the client dies, we tell the wallet that it's been disconnected
          wallet ! ElectrumClient.ElectrumDisconnected
          TestActor.KeepRunning
      }
    })

    sender1.send(wallet, ElectrumClient.HeaderSubscriptionResponse(last.height + 1, bad))
    watcher.expectTerminated(sender1.ref)
    awaitCond(wallet.stateName == ElectrumWallet.DISCONNECTED)

    sender.send(wallet, ElectrumClient.ElectrumReady(last.height, last.header, InetSocketAddress.createUnresolved("0.0.0.0", 9735)))
    awaitCond(wallet.stateName == ElectrumWallet.WAITING_FOR_TIP)
    sender.send(wallet, ElectrumClient.HeaderSubscriptionResponse(last.height, last.header))
    awaitCond(wallet.stateName == ElectrumWallet.RUNNING)
  }


  test("disconnect if server sends an invalid transaction") {
    while (client.msgAvailable) {
      client.receiveOne(100 milliseconds)
    }
    val key = wallet.stateData.accountKeys(0)
    val scriptHash = computeScriptHashFromPublicKey(key.publicKey)
    wallet ! ScriptHashSubscriptionResponse(scriptHash, "01" * 32)
    client.expectMsg(GetScriptHashHistory(scriptHash))

    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(Satoshi(100000), ElectrumWallet.computePublicKeyScript(key.publicKey)) :: Nil, lockTime = 0)
    wallet ! GetScriptHashHistoryResponse(scriptHash, TransactionHistoryItem(2, tx.txid) :: Nil)

    // wallet will generate a new address and the corresponding subscription
    client.expectMsgType[ScriptHashSubscription]

    while (listener.msgAvailable) {
      listener.receiveOne(100 milliseconds)
    }

    client.expectMsg(GetTransaction(tx.txid))
    wallet ! GetTransactionResponse(tx)
    val TransactionReceived(_, _, Satoshi(100000), _, _) = listener.expectMsgType[TransactionReceived]
    // we think we have some unconfirmed funds
    val WalletReady(Satoshi(100000), _, _, _) = listener.expectMsgType[WalletReady]

    client.expectMsg(GetMerkle(tx.txid, 2))

    val sender1 = TestProbe()
    val watcher = TestProbe()
    watcher.watch(sender1.ref)
    watcher.setAutoPilot(new TestActor.AutoPilot {
      override def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
        case Terminated(actor) if actor == sender1.ref =>
          wallet ! ElectrumClient.ElectrumDisconnected
          TestActor.KeepRunning
      }
    })
    sender1.send(wallet, GetMerkleResponse(tx.txid, BinaryData("01" * 32) :: Nil, 2, 0))
    watcher.expectTerminated(sender1.ref)
    awaitCond(wallet.stateName == ElectrumWallet.DISCONNECTED)

    sender.send(wallet, ElectrumClient.ElectrumReady(wallet.stateData.blockchain.bestchain.last.height, wallet.stateData.blockchain.bestchain.last.header, InetSocketAddress.createUnresolved("0.0.0.0", 9735)))
    awaitCond(wallet.stateName == ElectrumWallet.WAITING_FOR_TIP)
    while (listener.msgAvailable) {
      listener.receiveOne(100 milliseconds)
    }
    sender.send(wallet, ElectrumClient.HeaderSubscriptionResponse(wallet.stateData.blockchain.bestchain.last.height, wallet.stateData.blockchain.bestchain.last.header))
    awaitCond(wallet.stateName == ElectrumWallet.RUNNING)
    val ready = listener.expectMsgType[WalletReady]
    assert(ready.unconfirmedBalance == Satoshi(0))
  }
}
