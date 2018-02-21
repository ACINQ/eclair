package fr.acinq.eclair.blockchain.electrum

import akka.actor.{ActorRef, Props}
import akka.testkit.TestProbe
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{AddStatusListener, BroadcastTransaction, BroadcastTransactionResponse}
import org.json4s.JsonAST._

import scala.concurrent.duration._
import scala.sys.process._

class ElectrumWalletSpec extends IntegrationSpec {

  import ElectrumWallet._

  val entropy = BinaryData("01" * 32)
  val mnemonics = MnemonicCode.toMnemonics(entropy)
  val seed = MnemonicCode.toSeed(mnemonics, "")
  logger.info(s"mnemonic codes for our wallet: $mnemonics")
  val master = DeterministicWallet.generate(seed)
  var wallet: ActorRef = _

  test("wait until wallet is ready") {
    wallet = system.actorOf(Props(new ElectrumWallet(seed, electrumClient, WalletParameters(Block.RegtestGenesisBlock.hash, minimumFee = Satoshi(5000)))), "wallet")
    val probe = TestProbe()
    awaitCond({
      probe.send(wallet, GetData)
      val GetDataResponse(state) = probe.expectMsgType[GetDataResponse]
      state.status.size == state.accountKeys.size + state.changeKeys.size
    }, max = 30 seconds, interval = 1 second)
    logger.info(s"wallet is ready")
  }

  ignore("receive funds") {
    val probe = TestProbe()

    probe.send(wallet, GetCurrentReceiveAddress)
    val GetCurrentReceiveAddressResponse(address) = probe.expectMsgType[GetCurrentReceiveAddressResponse]

    logger.info(s"sending 1 btc to $address")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address :: 1.0 :: Nil))
    probe.expectMsgType[JValue]

    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(confirmed, unconfirmed) = probe.expectMsgType[GetBalanceResponse]
      unconfirmed == Satoshi(100000000L)
    }, max = 30 seconds, interval = 1 second)

    probe.send(bitcoincli, BitcoinReq("generate", 1 :: Nil))
    probe.expectMsgType[JValue]

    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(confirmed, unconfirmed) = probe.expectMsgType[GetBalanceResponse]
      confirmed == Satoshi(100000000L)
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
      val GetBalanceResponse(confirmed, unconfirmed) = probe.expectMsgType[GetBalanceResponse]
      confirmed == Satoshi(250000000L)
    }, max = 30 seconds, interval = 1 second)
  }

  test("receive 'confidence changed' notification") {
    val probe = TestProbe()
    val listener = TestProbe()

    listener.send(wallet, AddStatusListener(listener.ref))

    probe.send(wallet, GetCurrentReceiveAddress)
    val GetCurrentReceiveAddressResponse(address) = probe.expectMsgType[GetCurrentReceiveAddressResponse]

    probe.send(wallet, GetBalance)
    val GetBalanceResponse(confirmed, unconfirmed) = probe.expectMsgType[GetBalanceResponse]

    logger.info(s"sending 1 btc to $address")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address :: 1.0 :: Nil))
    val JString(txid) = probe.expectMsgType[JValue]
    logger.info(s"$txid send 1 btc to us at $address")

    val TransactionReceived(tx, 0, received, sent, _) = listener.receiveOne(5 seconds)
    assert(tx.txid === BinaryData(txid))
    assert(received === Satoshi(100000000))

    probe.send(bitcoincli, BitcoinReq("generate", 1 :: Nil))
    probe.expectMsgType[JValue]

    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(confirmed1, unconfirmed1) = probe.expectMsgType[GetBalanceResponse]
      confirmed1 - confirmed == Satoshi(100000000L)
    }, max = 30 seconds, interval = 1 second)

    awaitCond({
      val msg = listener.receiveOne(5 seconds)
      msg == TransactionConfidenceChanged(BinaryData(txid), 1)
    }, max = 30 seconds, interval = 1 second)
  }

  test("send money to someone else (we broadcast)") {
    val probe = TestProbe()
    probe.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = probe.expectMsgType[JValue]
    val (Base58.Prefix.PubkeyAddressTestnet, pubKeyHash) = Base58Check.decode(address)

    probe.send(wallet, GetBalance)
    val GetBalanceResponse(confirmed, unconfirmed) = probe.expectMsgType[GetBalanceResponse]

    // create a tx that sends money to Bitcoin Core's address
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(1 btc, Script.pay2pkh(pubKeyHash)) :: Nil, lockTime = 0L)
    probe.send(wallet, CompleteTransaction(tx, 20000))
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
      val GetBalanceResponse(confirmed1, unconfirmed1) = probe.expectMsgType[GetBalanceResponse]
      logger.debug(s"current balance is $confirmed1")
      confirmed1 < confirmed - Btc(1) && confirmed1 > confirmed - Btc(1) - Satoshi(50000)
    }, max = 30 seconds, interval = 1 second)
  }

  test("send money to ourselves (we broadcast)") {
    val probe = TestProbe()
    probe.send(wallet, GetCurrentReceiveAddress)
    val GetCurrentReceiveAddressResponse(address) = probe.expectMsgType[GetCurrentReceiveAddressResponse]
    val (Base58.Prefix.ScriptAddressTestnet, scriptHash) = Base58Check.decode(address)

    probe.send(wallet, GetBalance)
    val GetBalanceResponse(confirmed, unconfirmed) = probe.expectMsgType[GetBalanceResponse]

    // create a tx that sends money to Bitcoin Core's address
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(1 btc, OP_HASH160 :: OP_PUSHDATA(scriptHash) :: OP_EQUAL :: Nil) :: Nil, lockTime = 0L)
    probe.send(wallet, CompleteTransaction(tx, 20000))
    val CompleteTransactionResponse(tx1, None) = probe.expectMsgType[CompleteTransactionResponse]

    // send it ourselves
    logger.info(s"sending 1 btc to $address with tx ${tx1.txid}")
    probe.send(wallet, BroadcastTransaction(tx1))
    val BroadcastTransactionResponse(_, None) = probe.expectMsgType[BroadcastTransactionResponse]

    probe.send(bitcoincli, BitcoinReq("generate", 1 :: Nil))
    probe.expectMsgType[JValue]

    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(confirmed1, unconfirmed1) = probe.expectMsgType[GetBalanceResponse]
      logger.debug(s"current balance is $confirmed1")
      confirmed1 < confirmed && confirmed1 > confirmed - Satoshi(50000)
    }, max = 30 seconds, interval = 1 second)
  }

  ignore("handle reorgs (pending receive)") {
    val probe = TestProbe()

    probe.send(wallet, GetBalance)
    val GetBalanceResponse(confirmed, unconfirmed) = probe.expectMsgType[GetBalanceResponse]

    probe.send(wallet, GetCurrentReceiveAddress)
    val GetCurrentReceiveAddressResponse(address) = probe.expectMsgType[GetCurrentReceiveAddressResponse]

    // send money to our receive address
    logger.info(s"sending 0.7 btc to $address")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address :: 0.7 :: Nil))
    probe.expectMsgType[JValue]

    // generate 1 block
    probe.send(bitcoincli, BitcoinReq("generate", 1 :: Nil))
    val JArray(List(JString(blockId))) = probe.expectMsgType[JValue]

    // wait until our balance has been updated
    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(confirmed1, unconfirmed1) = probe.expectMsgType[GetBalanceResponse]
      logger.debug(s"current balance is $confirmed1")
      confirmed1 == confirmed + Btc(0.7)
    }, max = 30 seconds, interval = 1 second)

    // now invalidate the last block
    probe.send(bitcoincli, BitcoinReq("invalidateblock", blockId :: Nil))
    probe.expectMsgType[JValue]

    // and restart bitcoind, which should remove pending wallet txs
    // bitcoind was started with -zapwallettxes=2
    stopBitcoind
    Thread.sleep(2000)
    startBitcoind
    Thread.sleep(2000)


    // generate 2 new blocks. the tx that sent us money is no longer there,
    // the corresponding utxo should have been removed and our balance should
    // be back to what it was before
    probe.send(bitcoincli, BitcoinReq("generate", 2 :: Nil))
    probe.expectMsgType[JValue]
    val reorg = s"$PATH_ELECTRUMX/electrumx_rpc.py reorg 2".!!

    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(confirmed1, unconfirmed1) = probe.expectMsgType[GetBalanceResponse]
      logger.debug(s"current balance is $confirmed1")
      confirmed1 == confirmed
    }, max = 30 seconds, interval = 1 second)
  }

  ignore("handle reorgs (pending send)") {
    val probe = TestProbe()
    probe.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = probe.expectMsgType[JValue]
    val (Base58.Prefix.PubkeyAddressTestnet, pubKeyHash) = Base58Check.decode(address)

    probe.send(wallet, GetBalance)
    val GetBalanceResponse(confirmed, unconfirmed) = probe.expectMsgType[GetBalanceResponse]
    logger.debug(s"we start with a balance of $confirmed")

    // create a tx that sends money to Bitcoin Core's address
    val amount = 0.5 btc
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, Script.pay2pkh(pubKeyHash)) :: Nil, lockTime = 0L)
    probe.send(wallet, CompleteTransaction(tx, 20000))
    val CompleteTransactionResponse(tx1, None) = probe.expectMsgType[CompleteTransactionResponse]

    // send it ourselves
    logger.info(s"sending $amount to $address with tx ${tx1.txid}")
    probe.send(wallet, BroadcastTransaction(tx1))
    val BroadcastTransactionResponse(_, None) = probe.expectMsgType[BroadcastTransactionResponse]

    probe.send(bitcoincli, BitcoinReq("generate", 1 :: Nil))
    val JArray(List(JString(blockId))) = probe.expectMsgType[JValue]

    awaitCond({
      probe.send(bitcoincli, BitcoinReq("getreceivedbyaddress", address :: Nil))
      val JDouble(value) = probe.expectMsgType[JValue]
      value == amount.amount.toDouble
    }, max = 30 seconds, interval = 1 second)

    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(confirmed1, unconfirmed1) = probe.expectMsgType[GetBalanceResponse]
      logger.debug(s"current balance is $confirmed1")
      confirmed1 < confirmed - amount && confirmed1 > confirmed - amount - Satoshi(50000)
    }, max = 30 seconds, interval = 1 second)

    // now invalidate the last block
    probe.send(bitcoincli, BitcoinReq("getblockcount"))
    val JInt(count) = probe.expectMsgType[JValue]
    probe.send(bitcoincli, BitcoinReq("invalidateblock", blockId :: Nil))
    val foo = probe.expectMsgType[JValue]
    probe.send(bitcoincli, BitcoinReq("getblockcount"))
    val JInt(count1) = probe.expectMsgType[JValue]

    // and restart bitcoind, which should remove pending wallet txs
    // bitcoind was started with -zapwallettxes=2
    stopBitcoind
    Thread.sleep(2000)
    startBitcoind
    Thread.sleep(2000)

    // generate 2 new blocks. the tx that sent us money is no longer there,
    // the corresponding utxo should have been removed and our balance should
    // be back to what it was before
    probe.send(bitcoincli, BitcoinReq("generate", 2 :: Nil))
    probe.expectMsgType[JValue]
    val reorg = s"$PATH_ELECTRUMX/electrumx_rpc.py reorg 2".!!

    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(confirmed1, unconfirmed1) = probe.expectMsgType[GetBalanceResponse]
      logger.debug(s"current balance is $confirmed1")
      confirmed1 == confirmed
    }, max = 30 seconds, interval = 1 second)
  }
}
