package fr.acinq.eclair.blockchain.electrum

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.{BinaryData, Block, MnemonicCode, Satoshi}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{ScriptHashSubscription, ScriptHashSubscriptionResponse}
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.{NewWalletReceiveAddress, WalletEvent, WalletParameters, WalletReady}
import org.junit.runner.RunWith
import org.scalatest.FunSuiteLike
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class ElectrumWalletSimulatedClientSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {
  val sender = TestProbe()

  class SimulatedClient extends Actor {
    def receive = {
      case ScriptHashSubscription(scriptHash, replyTo) => replyTo ! ScriptHashSubscriptionResponse(scriptHash, "")
    }
  }

  val entropy = BinaryData("01" * 32)
  val mnemonics = MnemonicCode.toMnemonics(entropy)
  val seed = MnemonicCode.toSeed(mnemonics, "")

  val listener = TestProbe()
  system.eventStream.subscribe(listener.ref, classOf[WalletEvent])
  val wallet = TestFSMRef(new ElectrumWallet(seed, system.actorOf(Props(new SimulatedClient())), WalletParameters(Block.RegtestGenesisBlock.hash, minimumFee = Satoshi(5000))))

  // wallet sends a receive address notification as soon as it is created
  listener.expectMsgType[NewWalletReceiveAddress]

  val genesis = ElectrumClient.Header(1, 1, Block.RegtestGenesisBlock.hash, BinaryData("01" * 32), timestamp = 12346L, bits = 0, nonce = 0)
  val header1 = makeHeader(genesis, 12345L)
  val header2 = makeHeader(header1, 12346L)
  val header3 = makeHeader(header2, 12347L)
  val header4 = makeHeader(header3, 12348L)

  def makeHeader(previousHeader: ElectrumClient.Header, timestamp: Long): ElectrumClient.Header = ElectrumClient.Header(previousHeader.block_height + 1, 1, previousHeader.block_hash, BinaryData("01" * 32), timestamp = timestamp, bits = 0, nonce = 0)


  test("wait until wallet is ready") {
    sender.send(wallet, ElectrumClient.ElectrumReady)
    sender.send(wallet, ElectrumClient.HeaderSubscriptionResponse(header1))
    awaitCond(wallet.stateName == ElectrumWallet.RUNNING)
    assert(listener.expectMsgType[WalletReady].timestamp == header1.timestamp)
    listener.expectMsgType[NewWalletReceiveAddress]
  }

  test("tell wallet is ready when a new block comes in, even if nothing else has changed") {
    sender.send(wallet, ElectrumClient.HeaderSubscriptionResponse(header2))
    assert(listener.expectMsgType[WalletReady].timestamp == header2.timestamp)
    listener.expectMsgType[NewWalletReceiveAddress]
  }

  test("tell wallet is ready when it is reconnected, even if nothing has changed") {
    // disconnect wallet
    sender.send(wallet, ElectrumClient.ElectrumDisconnected)
    awaitCond(wallet.stateName == ElectrumWallet.DISCONNECTED)

    // reconnect wallet
    sender.send(wallet, ElectrumClient.ElectrumReady)
    sender.send(wallet, ElectrumClient.HeaderSubscriptionResponse(header3))
    awaitCond(wallet.stateName == ElectrumWallet.RUNNING)

    // listener should be notified
    assert(listener.expectMsgType[WalletReady].timestamp == header3.timestamp)
    listener.expectMsgType[NewWalletReceiveAddress]
  }

  test("don't send the same ready mnessage more then once") {
    // listener should be notified
    sender.send(wallet, ElectrumClient.HeaderSubscriptionResponse(header4))
    assert(listener.expectMsgType[WalletReady].timestamp == header4.timestamp)
    listener.expectMsgType[NewWalletReceiveAddress]

    sender.send(wallet, ElectrumClient.HeaderSubscriptionResponse(header4))
    listener.expectNoMsg(500 milliseconds)
  }
}
