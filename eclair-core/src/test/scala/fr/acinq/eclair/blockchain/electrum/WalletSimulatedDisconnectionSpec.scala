package fr.acinq.eclair.blockchain.electrum

import java.net.InetSocketAddress
import java.sql.DriverManager

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit
import akka.testkit.{TestActor, TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Block, BlockHeader, ByteVector32, Crypto, MilliBtc, MnemonicCode, OutPoint, Satoshi, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.bitcoind.rpc.Error
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{AddStatusListener, GetHeaders, GetHeadersResponse, GetScriptHashHistory, GetScriptHashHistoryResponse, GetTransaction, GetTransactionResponse, HeaderSubscription, HeaderSubscriptionResponse, ScriptHashSubscription, ScriptHashSubscriptionResponse, ServerError, TransactionHistoryItem}
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.{GetXpub, GetXpubResponse, NewWalletReceiveAddress, WalletEvent, WalletParameters, WalletReady, extractPubKeySpentFrom}
import fr.acinq.eclair.blockchain.electrum.db.sqlite.SqliteWalletDb
import org.scalatest.FunSuiteLike
import scodec.bits.ByteVector

import scala.annotation.tailrec

class WalletSimulatedDisconnectionSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {
  import WalletSimulatedDisconnectionSpec._

  val entropy = ByteVector32(ByteVector.fill(32)(1))
  val mnemonics = MnemonicCode.toMnemonics(entropy)
  val seed = MnemonicCode.toSeed(mnemonics, "")

  val listener = TestProbe()
  system.eventStream.subscribe(listener.ref, classOf[WalletEvent])

  val genesis = Block.RegtestGenesisBlock.header
  // initial headers that we will sync when we connect to our mock server
  val headers = makeHeaders(genesis, 2016 + 2000)

  val client = TestProbe()
  client.ignoreMsg {
    case ElectrumClient.Ping => true
    case _: AddStatusListener => true
    case _: HeaderSubscription => true
  }
  var data: ElectrumWallet.Data = _

  var counter = 0
  var disconnectAfter = 10 // disconnect when counter % disconnectAfter == 0

  client.setAutoPilot(new testkit.TestActor.AutoPilot {
    override def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
      counter = counter + 1
      msg match {
        case ScriptHashSubscription(scriptHash, replyTo) =>
          // we skip over these otherwise we would never converge (there are at least 20 such messages sent when we're
          // reconnected, one for each account/change key)
          replyTo ! ScriptHashSubscriptionResponse(scriptHash, data.status.getOrElse(scriptHash, ""))
          TestActor.KeepRunning
        case _ if counter % disconnectAfter == 0 =>
          // disconnect
          sender ! ElectrumClient.ElectrumDisconnected
          // and reconnect
          sender ! ElectrumClient.ElectrumReady(headers.length, headers.last, InetSocketAddress.createUnresolved("0.0.0.0", 9735))
          sender ! ElectrumClient.HeaderSubscriptionResponse(headers.length, headers.last)
          TestActor.KeepRunning
         case request@GetTransaction(txid) =>
          data.transactions.get(txid) match {
            case Some(tx) => sender ! GetTransactionResponse(tx)
            case None => sender ! ServerError(request, Error(0, s"unknwown tx $txid"))
          }
          TestActor.KeepRunning
        case GetScriptHashHistory(scriptHash) =>
          sender ! GetScriptHashHistoryResponse(scriptHash, data.history.getOrElse(scriptHash, List()))
          TestActor.KeepRunning
        case GetHeaders(start, count, _) =>
          sender ! GetHeadersResponse(start, headers.drop(start - 1).take(count), 2016)
          TestActor.KeepRunning
        case HeaderSubscription(actor) => actor ! HeaderSubscriptionResponse(headers.length, headers.last)
          TestActor.KeepRunning
        case _ =>
          TestActor.KeepRunning
      }
    }
  })


  val db = new SqliteWalletDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
  val wallet = TestFSMRef(new ElectrumWallet(seed, client.ref, WalletParameters(Block.RegtestGenesisBlock.hash, db, minimumFee = Satoshi(5000))))

  // wallet sends a receive address notification as soon as it is created
  listener.expectMsgType[NewWalletReceiveAddress]


  val amount1 = Satoshi(1000000)
  val amount2 = Satoshi(1500000)

  data = wallet.stateData
  val wallettxs = Seq(
    addOutputs(emptyTx, amount1, data.accountKeys(0).publicKey),
    addOutputs(emptyTx, amount2, data.accountKeys(1).publicKey)
  )
  val data1 = wallettxs.foldLeft(data)(addTransaction)

  val tx1 = {
    val tx = Transaction(version = 2,
      txIn = TxIn(OutPoint(wallettxs(0), 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = walletOutput(wallettxs(0).txOut(0).amount - Satoshi(50000), data.accountKeys(2).publicKey) :: walletOutput(Satoshi(50000), data.changeKeys(0).publicKey) :: Nil,
      lockTime = 0)
    data1.signTransaction(tx)
  }
  val data2 = addTransaction(data1, tx1)
  println(data2)

  test("wait until wallet is ready") {
    val sender = TestProbe()
    sender.send(wallet, ElectrumClient.ElectrumReady(2016, headers(2015), InetSocketAddress.createUnresolved("0.0.0.0", 9735)))
    sender.send(wallet, ElectrumClient.HeaderSubscriptionResponse(2016, headers(2015)))
    val ready = listener.expectMsgType[WalletReady]
    assert(ready.timestamp == headers.last.time)
    listener.expectMsgType[NewWalletReceiveAddress]
    listener.send(wallet, GetXpub)
    val GetXpubResponse(xpub, path) = listener.expectMsgType[GetXpubResponse]
    assert(xpub == "upub5DffbMENbUsLcJbhufWvy1jourQfXfC6SoYyxhy2gPKeTSGzYHB3wKTnKH2LYCDemSzZwqzNcHNjnQZJCDn7Jy2LvvQeysQ6hrcK5ogp11B")
    assert(path == "m/49'/1'/0'")
  }

  test("ask for transactions") {
    val sender = TestProbe()
    data = data2
    disconnectAfter = 3
    wallet ! ElectrumClient.ElectrumDisconnected
    wallet ! ElectrumClient.ElectrumReady(headers.length, headers.last, InetSocketAddress.createUnresolved("0.0.0.0", 9735))
    wallet ! ElectrumClient.HeaderSubscriptionResponse(headers.length, headers.last)

    data.status.foreach { case (scriptHash, status) => sender.send(wallet, ScriptHashSubscriptionResponse(scriptHash, status)) }
    awaitCond(wallet.stateData.transactions.size == 3)
  }
 }

object WalletSimulatedDisconnectionSpec {
  val emptyTx = Transaction(version = 2, txIn = Nil, txOut = Nil, lockTime = 0)

  def walletOutput(amount: Satoshi, key: PublicKey) = TxOut(amount, ElectrumWallet.computePublicKeyScript(key))

  def addOutputs(tx: Transaction, amount: Satoshi,  keys: PublicKey*): Transaction = keys.foldLeft(tx) { case (t, k) => t.copy(txOut = t.txOut :+ walletOutput(amount, k)) }

  def makeHeader(previousHeader: BlockHeader, timestamp: Long): BlockHeader = {
    var template = previousHeader.copy(hashPreviousBlock = previousHeader.hash, time = timestamp, nonce = 0)
    while (!BlockHeader.checkProofOfWork(template)) {
      template = template.copy(nonce = template.nonce + 1)
    }
    template
  }

  def makeHeader(previousHeader: BlockHeader): BlockHeader = makeHeader(previousHeader, previousHeader.time + 1)

  def makeHeaders(previousHeader: BlockHeader, count: Int): Vector[BlockHeader] = {
    @tailrec
    def loop(acc: Vector[BlockHeader]): Vector[BlockHeader] = if (acc.length == count) acc else loop(acc :+ makeHeader(acc.last))

    loop(Vector(makeHeader(previousHeader)))
  }

  def addToHistory(history: Map[ByteVector32, List[ElectrumClient.TransactionHistoryItem]], scriptHash: ByteVector32, item : TransactionHistoryItem): Map[ByteVector32, List[ElectrumClient.TransactionHistoryItem]] = {
    history.get(scriptHash) match {
      case None => history + (scriptHash -> List(item))
      case Some(items) if items.contains(item) => history
      case _ => history.updated(scriptHash, history(scriptHash) :+ item)
    }
  }

  def updateStatus(data: ElectrumWallet.Data) : ElectrumWallet.Data = {
    val status1 = data.history.mapValues(items => {
      val status = items.map(i => s"${i.tx_hash}:${i.height}:").mkString("")
      Crypto.sha256(ByteVector.view(status.getBytes())).toString()
    })
    data.copy(status = status1)
  }

  def addTransaction(data: ElectrumWallet.Data, tx: Transaction) : ElectrumWallet.Data = {
    data.transactions.get(tx.txid) match {
      case Some(_) => data
      case None =>
        val history1 = tx.txOut.filter(o => data.isMine(o)).foldLeft(data.history) { case (a, b) =>
          addToHistory(a, Crypto.sha256(b.publicKeyScript).reverse, TransactionHistoryItem(100000, tx.txid))
        }
        val data1 = data.copy(history = history1, transactions = data.transactions + (tx.txid -> tx))
        val history2 = tx.txIn.filter(i => data1.isMine(i)).foldLeft(data1.history) { case (a, b) =>
          addToHistory(a, ElectrumWallet.computeScriptHashFromPublicKey(extractPubKeySpentFrom(b).get), TransactionHistoryItem(100000, tx.txid))
        }
        val data2 = data1.copy(history = history2)
        updateStatus(data2)
    }
  }
}
