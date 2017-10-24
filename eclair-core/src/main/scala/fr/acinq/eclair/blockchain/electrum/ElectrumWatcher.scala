package fr.acinq.eclair.blockchain.electrum

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Stash, Terminated}
import fr.acinq.bitcoin.{BinaryData, Crypto, Transaction}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.blockchain.electrum.ElectrumClient._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.{BITCOIN_FUNDING_DEPTHOK, BITCOIN_FUNDING_SPENT, BITCOIN_PARENT_TX_CONFIRMED}
import fr.acinq.eclair.transactions.Scripts

import scala.collection.SortedMap


class ElectrumWatcher(client: ActorRef) extends Actor with Stash with ActorLogging {

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
      context become running(header, Set(), Map(), SortedMap())
  }

  def running(tip: ElectrumClient.Header, watches: Set[Watch], scriptHashStatus: Map[BinaryData, String], block2tx: SortedMap[Long, Seq[Transaction]]): Receive = {
    case ElectrumClient.HeaderSubscriptionResponse(newtip) if tip == newtip => ()

    case ElectrumClient.HeaderSubscriptionResponse(newtip) =>
      log.info(s"new tip: ${newtip.block_hash} $newtip")
      watches collect {
        case watch: WatchConfirmed =>
          val scriptHash: BinaryData = Crypto.sha256(watch.publicKeyScript).reverse
          client ! ElectrumClient.GetScriptHashHistory(scriptHash)
        //        case WatchSpent(tx, pos, _) =>
        //          val scriptHash: BinaryData = Crypto.sha256(tx.txOut(pos).publicKeyScript).reverse
        //          client ! GetScriptHashHistory(scriptHash)
      }
      context become running(newtip, watches, scriptHashStatus, block2tx)

    case watch: Watch if watches.contains(watch) => ()

    case watch@WatchSpent(_, txid, outputIndex, publicKeyScript, _) =>
      val scriptHash: BinaryData = Crypto.sha256(publicKeyScript).reverse
      log.info(s"watch spent $txid:$outputIndex script hash is $scriptHash")
      client ! ElectrumClient.ScriptHashSubscription(scriptHash, self)
      context.watch(watch.channel)
      context become running(tip, watches + watch, scriptHashStatus, block2tx)

    case watch@WatchConfirmed(_, txid, publicKeyScript, minDepth, _) =>
      val scriptHash: BinaryData = Crypto.sha256(publicKeyScript).reverse
      log.info(s"watch confirmed $txid script hash is $scriptHash")
      client ! ElectrumClient.ScriptHashSubscription(scriptHash, self)
      client ! ElectrumClient.GetScriptHashHistory(scriptHash)
      context.watch(watch.channel)
      context become running(tip, watches + watch, scriptHashStatus, block2tx)

    case Terminated(actor) =>
      val watches1 = watches.filterNot(_.channel == actor)
      context become running(tip, watches1, scriptHashStatus, block2tx)

    case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status) if scriptHashStatus.get(scriptHash) == Some(status) =>
      log.debug(s"already have status $status for $scriptHash")

    case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status) =>
      log.info(s"script hash $scriptHash has a new status $status")
      client ! ElectrumClient.GetScriptHashHistory(scriptHash)
      context become running(tip, watches, scriptHashStatus + (scriptHash -> status), block2tx)

    case ElectrumClient.GetScriptHashHistoryResponse(scriptHash, history) =>
      history.collect {
        case ElectrumClient.TransactionHistoryItem(height, tx_hash) if height > 0 => watches collect {
          case WatchConfirmed(channel, txid, publicKeyScript, minDepth, event) if txid == tx_hash && (tip.block_height - height + 1) >= minDepth =>
            log.info(s"transaction ${txid} was confirmed at block $height and we're at ${tip.block_height}")
            val request = GetMerkle(tx_hash, height)
            client ! request
            context become({
              case GetMerkleResponse(txid, _, block_height, pos) =>
                channel ! WatchEventConfirmed(event, block_height.toInt, pos)
                unstashAll()
                context unbecome()
              case ServerError(r, error) if r == request =>
                log.error(s"cannot process $request: $error")
                unstashAll()
                context unbecome()
              case _ => stash()
            }, false)
        }
      }
      history.filter(_.height > 0).map(item => client ! ElectrumClient.GetTransaction(item.tx_hash))

    case ElectrumClient.GetTransactionResponse(spendingTx) =>
      spendingTx.txIn.map(_.outPoint).map(outPoint => {
        watches.collect {
          case WatchSpent(channel, txid, pos, publicKeyScript, event) if txid == outPoint.txid && pos == outPoint.index.toInt =>
            log.info(s"output ${txid}:$pos spend by transaction ${spendingTx.txid}")
            channel ! WatchEventSpent(event, spendingTx)
        }
      })

    case PublishAsap(tx, parentPublicKeyScript) =>
      val blockCount = Globals.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeout = Scripts.csvTimeout(tx)
      if (csvTimeout > 0) {
        require(tx.txIn.size == 1, s"watcher only supports tx with 1 input, this tx has ${tx.txIn.size} inputs")
        val parentTxid = tx.txIn(0).outPoint.txid
        log.info(s"txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parenttxid=$parentTxid tx=${Transaction.write(tx)}")
        self ! WatchConfirmed(self, parentTxid, parentPublicKeyScript.get, minDepth = 1, BITCOIN_PARENT_TX_CONFIRMED(tx))
      } else if (cltvTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(cltvTimeout, tx +: block2tx.getOrElse(cltvTimeout, Seq.empty[Transaction]))
        context become running(tip, watches, scriptHashStatus, block2tx1)
      } else publish(tx)

    case ElectrumClient.Disconnected => context become disconnected(watches)
  }

  def publish(transaction: Transaction): Unit = {
    log.info(s"publishing ${Transaction.write(transaction)}")
    client ! BroadcastTransaction(transaction)
  }
}

object ElectrumWatcher extends App {

//  // @formatter:off
//  sealed trait Event
//  case class TransactionSpent(tx: Transaction, pos: Int, spendingTx: Transaction) extends Event
//  case class TransactionConfirmed(txid: BinaryData, confirmations: Long) extends Event
//
//  sealed trait Watch
//  case class WatchSpent(tx: Transaction, pos: Int, actor: ActorRef) extends Watch
//  case class WatchConfirmed(tx: Transaction, minDepth: Long, actor: ActorRef) extends Watch
//  // @formatter:on

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
    val tx = Transaction.read(scanner.nextLine())
    root ! WatchSpent(root, tx.txid, 0, tx.txOut(0).publicKeyScript, BITCOIN_FUNDING_SPENT)
    root ! WatchConfirmed(root, tx.txid, tx.txOut(0).publicKeyScript, 4L, BITCOIN_FUNDING_DEPTHOK)
  }
}
