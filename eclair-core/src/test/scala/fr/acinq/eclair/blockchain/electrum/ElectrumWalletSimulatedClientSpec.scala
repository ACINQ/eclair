package fr.acinq.eclair.blockchain.electrum

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.{BinaryData, Block, MnemonicCode, Satoshi}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{ScriptHashSubscription, ScriptHashSubscriptionResponse}
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.{NewWalletReceiveAddress, WalletEvent, WalletParameters, WalletReady}
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, FunSuite, FunSuiteLike}
import org.scalatest.junit.JUnitRunner

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
  val wallet = system.actorOf(Props(new ElectrumWallet(mnemonics, system.actorOf(Props(new SimulatedClient())), WalletParameters(Block.RegtestGenesisBlock.hash, minimumFee = Satoshi(5000)))), "wallet")

  val genesis = ElectrumClient.Header(1,1, Block.RegtestGenesisBlock.hash, BinaryData("01" * 32), timestamp = 12346L, bits = 0, nonce = 0)
  val header1 = makeHeader(genesis, 12345L)

  def makeHeader(previousHeader: ElectrumClient.Header, timestamp: Long) : ElectrumClient.Header = ElectrumClient.Header(previousHeader.block_height + 1, 1, previousHeader.block_hash, BinaryData("01" * 32), timestamp = timestamp, bits = 0, nonce = 0)

  test("wait until wallet is ready") {
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[WalletEvent])
    sender.send(wallet, ElectrumClient.ElectrumReady)
    listener.expectMsgType[NewWalletReceiveAddress]

    sender.send(wallet, ElectrumClient.HeaderSubscriptionResponse(header1))
    assert(listener.expectMsgType[WalletReady].timestamp == header1.timestamp)
  }

  test("tell wallet is ready when a new block comes in") {
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[WalletEvent])

    val header2 = makeHeader(header1, 12346L)
    sender.send(wallet, ElectrumClient.HeaderSubscriptionResponse(header2))
    assert(listener.expectMsgType[WalletReady].timestamp == header2.timestamp)
  }

}
