package fr.acinq.eclair.blockchain.electrum

import akka.actor.{ActorRef, Props}
import akka.testkit.TestProbe
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{BroadcastTransaction, BroadcastTransactionResponse}
import org.json4s.JsonAST.{JArray, JDouble, JString, JValue}

import scala.concurrent.duration._

class ElectrumWalletSpec extends IntegrationSpec{
  import ElectrumWallet._

  val entropy = BinaryData("01" * 32)
  val mnemonics = MnemonicCode.toMnemonics(entropy)
  val seed = MnemonicCode.toSeed(mnemonics, "")
  logger.info(s"mnemonic codes for our wallet: $mnemonics")
  val master = DeterministicWallet.generate(seed)
  var wallet: ActorRef = _

  test("wait until wallet is ready") {
    wallet = system.actorOf(Props(new ElectrumWallet(master, electrumClient)))
    val probe = TestProbe()
    awaitCond({
      probe.send(wallet, GetState)
      val GetStateResponse(state) = probe.expectMsgType[GetStateResponse]
      state.status.size == state.accountKeys.size + state.changeKeys.size
    }, max = 30 seconds, interval = 1 second)
    logger.info(s"wallet is ready")
  }

  test("receive funds") {
    val probe = TestProbe()

    probe.send(wallet, GetCurrentReceiveAddress)
    val GetCurrentReceiveAddressResponse(address) = probe.expectMsgType[GetCurrentReceiveAddressResponse]

    logger.info(s"sending 1 btc to $address")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address :: 1.0 :: Nil))
    probe.expectMsgType[JValue]

    probe.send(bitcoincli, BitcoinReq("generate", 1 :: Nil))
    probe.expectMsgType[JValue]

    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(balance) = probe.expectMsgType[GetBalanceResponse]
      balance == Satoshi(100000000L)
    }, max = 30 seconds, interval = 1 second)

    probe.send(wallet, GetCurrentReceiveAddress)
    val GetCurrentReceiveAddressResponse(address1) = probe.expectMsgType[GetCurrentReceiveAddressResponse]

    logger.info(s"sending 1 btc to $address1")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address1 :: 1.0 :: Nil))
    probe.expectMsgType[JValue]
    logger.info(s"sending 0.5 btc to $address1")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address1 :: 0.5 :: Nil))
    probe.expectMsgType[JValue]

    probe.send(bitcoincli, BitcoinReq("generate", 1 :: Nil))
    probe.expectMsgType[JValue]

    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(balance) = probe.expectMsgType[GetBalanceResponse]
      balance == Satoshi(250000000L)
    }, max = 30 seconds, interval = 1 second)
  }

  test("send money to someone else (we broadcast)") {
    val probe = TestProbe()
    probe.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = probe.expectMsgType[JValue]
    val (Base58.Prefix.PubkeyAddressTestnet, pubKeyHash) = Base58Check.decode(address)

    probe.send(wallet, GetBalance)
    val GetBalanceResponse(balance) = probe.expectMsgType[GetBalanceResponse]

    // create a tx that sends money to Bitcoin Core's address
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(1 btc, Script.pay2pkh(pubKeyHash)) :: Nil, lockTime = 0L)
    probe.send(wallet, CompleteTransaction(tx))
    val CompleteTransactionResponse(tx1, None) = probe.expectMsgType[CompleteTransactionResponse]

    // send it ourselves
    logger.info(s"sending 1 btc to $address with tx ${tx1.txid}")
    probe.send(wallet, BroadcastTransaction(tx1))
    val BroadcastTransactionResponse(_, None) = probe.expectMsgType[BroadcastTransactionResponse]

    probe.send(bitcoincli, BitcoinReq("generate", 1 :: Nil))
    probe.expectMsgType[JValue]

    awaitCond({
      probe.send(bitcoincli, BitcoinReq("getreceivedbyaddress", address :: Nil))
      val JDouble(value) = probe.expectMsgType[JValue]
      value == 1.0
    }, max = 30 seconds, interval = 1 second)

    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(balance1) = probe.expectMsgType[GetBalanceResponse]
      logger.debug(s"current balance is $balance1")
      balance1 < balance - Btc(1) && balance1 > balance - Btc(1) - Satoshi(50000)
    }, max = 30 seconds, interval = 1 second)
  }

  test("send money to ourselves (we broadcast)") {
    val probe = TestProbe()
    probe.send(wallet, GetCurrentReceiveAddress)
    val GetCurrentReceiveAddressResponse(address) = probe.expectMsgType[GetCurrentReceiveAddressResponse]
    val (Base58.Prefix.ScriptAddressTestnet, scriptHash) = Base58Check.decode(address)

    probe.send(wallet, GetBalance)
    val GetBalanceResponse(balance) = probe.expectMsgType[GetBalanceResponse]

    // create a tx that sends money to Bitcoin Core's address
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(1 btc, OP_HASH160 :: OP_PUSHDATA(scriptHash) :: OP_EQUAL :: Nil) :: Nil, lockTime = 0L)
    probe.send(wallet, CompleteTransaction(tx))
    val CompleteTransactionResponse(tx1, None) = probe.expectMsgType[CompleteTransactionResponse]

    // send it ourselves
    logger.info(s"sending 1 btc to $address with tx ${tx1.txid}")
    probe.send(wallet, BroadcastTransaction(tx1))
    val BroadcastTransactionResponse(_, None) = probe.expectMsgType[BroadcastTransactionResponse]

    probe.send(bitcoincli, BitcoinReq("generate", 1 :: Nil))
    probe.expectMsgType[JValue]

    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(balance1) = probe.expectMsgType[GetBalanceResponse]
      logger.debug(s"current balance is $balance1")
      balance1 < balance && balance1 > balance - Satoshi(50000)
    }, max = 30 seconds, interval = 1 second)
  }

  test("handle reorgs (pending receive)") {
    val probe = TestProbe()

    probe.send(wallet, GetBalance)
    val GetBalanceResponse(balance) = probe.expectMsgType[GetBalanceResponse]

    probe.send(wallet, GetCurrentReceiveAddress)
    val GetCurrentReceiveAddressResponse(address) = probe.expectMsgType[GetCurrentReceiveAddressResponse]

    logger.info(s"sending 1 btc to $address")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address :: 1.0 :: Nil))
    probe.expectMsgType[JValue]

    probe.send(bitcoincli, BitcoinReq("generate", 1 :: Nil))
    val JArray(List(JString(blockId))) = probe.expectMsgType[JValue]

    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(balance1) = probe.expectMsgType[GetBalanceResponse]
      logger.debug(s"current balance is $balance1")
      balance1 == balance + Btc(1)
    }, max = 30 seconds, interval = 1 second)

    probe.send(bitcoincli, BitcoinReq("invalidateblock", blockId :: Nil))
    probe.expectMsgType[JValue]
  }
}
