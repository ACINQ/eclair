package fr.acinq.eclair.blockchain.electrum

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import fr.acinq.bitcoin.{BinaryData, Crypto, Transaction}


class ElectrumWatcher(client: ActorRef) extends Actor with ActorLogging {

  import ElectrumWatcher._

  client ! ElectrumClient.AddStatusListener(self)

  override def unhandled(message: Any): Unit = {
    super.unhandled(message)
    log.warning(s"uhandled message $message")
  }

  def receive = disconnected(Set())

  def disconnected(watches: Set[Watch]): Receive = {
    case ElectrumClient.Ready =>
      client ! ElectrumClient.HeaderSubscription(self)
    case ElectrumClient.HeaderSubscriptionResponse(header) =>
      watches.map(self ! _)
      context become running(header, Set(), Map())
  }

  def running(tip: ElectrumClient.Header, watches: Set[Watch], scriptHashStatus: Map[BinaryData, String]): Receive = {
    case ElectrumClient.HeaderSubscriptionResponse(newtip) if tip == newtip => ()

    case ElectrumClient.HeaderSubscriptionResponse(newtip) =>
      log.info(s"new tip: ${newtip.block_hash} $newtip")
      watches collect {
        case WatchConfirmed(tx, minDepth, _) =>
          val scriptHash: BinaryData = Crypto.sha256(tx.txOut(0).publicKeyScript).reverse
          client ! ElectrumClient.GetScriptHashHistory(scriptHash)
        //        case WatchSpent(tx, pos, _) =>
        //          val scriptHash: BinaryData = Crypto.sha256(tx.txOut(pos).publicKeyScript).reverse
        //          client ! GetScriptHashHistory(scriptHash)
      }
      context become running(newtip, watches, scriptHashStatus)

    case watch: Watch if watches.contains(watch) => ()

    case watch@WatchSpent(tx, pos, _) =>
      val publicKeyScript = tx.txOut(pos).publicKeyScript
      val scriptHash: BinaryData = Crypto.sha256(publicKeyScript).reverse
      log.info(s"watch spent ${tx.txid} script hash is $scriptHash")
      client ! ElectrumClient.ScriptHashSubscription(scriptHash, self)
      context become running(tip, watches + watch, scriptHashStatus)

    case watch@WatchConfirmed(tx, minDepth, _) =>
      val publicKeyScript = tx.txOut(0).publicKeyScript
      val scriptHash: BinaryData = Crypto.sha256(publicKeyScript).reverse
      log.info(s"watch confirmed ${tx.txid} script hash is $scriptHash")
      client ! ElectrumClient.ScriptHashSubscription(scriptHash, self)
      client ! ElectrumClient.GetScriptHashHistory(scriptHash)
      context become running(tip, watches + watch, scriptHashStatus)

    case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status) if scriptHashStatus.get(scriptHash) == Some(status) =>
      log.debug(s"already have status $status for $scriptHash")

    case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status) =>
      log.info(s"script hash $scriptHash has a new status $status")
      client ! ElectrumClient.GetScriptHashHistory(scriptHash)
      context become running(tip, watches, scriptHashStatus + (scriptHash -> status))

    case ElectrumClient.GetScriptHashHistoryResponse(scriptHash, history) =>
      history.collect {
        case ElectrumClient.TransactionHistoryItem(height, tx_hash) if height > 0 => watches collect {
          case WatchConfirmed(tx, minDepth, actor) if tx.txid.toString() == tx_hash && (tip.block_height - height + 1) >= minDepth =>
            log.info(s"transaction ${tx.txid} was confirmed at block $height and we're at ${tip.block_height}")
            actor ! TransactionConfirmed(tx.txid, tip.block_height - height)
        }
      }
      history.filter(_.height > 0).map(item => client ! ElectrumClient.GetTransaction(item.tx_hash))

    case ElectrumClient.GetTransactionResponse(hex: String) =>
      val spendingTx = Transaction.read(hex)
      spendingTx.txIn.map(_.outPoint).map(outPoint => {
        watches.collect {
          case WatchSpent(tx, pos, actor) if tx.txid == outPoint.txid && pos == outPoint.index.toInt =>
            log.info(s"output ${tx.txid}:$pos spend by transaction ${spendingTx.txid}")
            actor ! TransactionSpent(tx, pos, spendingTx)
        }
      })

    case ElectrumClient.Disconnected => context become disconnected(watches)
  }
}

object ElectrumWatcher extends App {

  // @formatter:off
  sealed trait Event
  case class TransactionSpent(tx: Transaction, pos: Int, spendingTx: Transaction) extends Event
  case class TransactionConfirmed(txid: BinaryData, confirmations: Long) extends Event

  sealed trait Watch
  case class WatchSpent(tx: Transaction, pos: Int, actor: ActorRef) extends Watch
  case class WatchConfirmed(tx: Transaction, minDepth: Long, actor: ActorRef) extends Watch
  // @formatter:on

  val system = ActorSystem()

  class Root extends Actor with ActorLogging {
    val serverAddresses = Seq(new InetSocketAddress("localhost", 51000), new InetSocketAddress("localhost", 51001))
    val client = context.actorOf(Props(new ElectrumClient(serverAddresses)), "client")
    client ! ElectrumClient.AddStatusListener(self)

    override def unhandled(message: Any): Unit = {
      super.unhandled(message)
      log.warning(s"unhandled message $message")
    }

    def receive = {
      case ElectrumClient.Ready =>
        log.info(s"starting watcher")
        context become running(context.actorOf(Props(new ElectrumWatcher(client)), "watcher"))
    }

    def running(watcher: ActorRef): Receive = {
      case watch: Watch => watcher forward watch
    }
  }

  val root = system.actorOf(Props[Root], "root")
  val scanner = new java.util.Scanner(System.in)
  while (true) {
    val tx = scanner.nextLine()
    root ! WatchSpent(Transaction.read(tx), 0, root)
    root ! WatchConfirmed(Transaction.read(tx), 4, root)
  }
}
