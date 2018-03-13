package fr.acinq.eclair.blockchain.electrum

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Stash, Terminated}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Satoshi, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.electrum.ElectrumClient._
import fr.acinq.eclair.channel.{BITCOIN_FUNDING_DEPTHOK, BITCOIN_FUNDING_SPENT, BITCOIN_PARENT_TX_CONFIRMED}
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.{Globals, ShortChannelId, TxCoordinates}

import scala.collection.SortedMap


class ElectrumWatcher(client: ActorRef) extends Actor with Stash with ActorLogging {

  client ! ElectrumClient.AddStatusListener(self)

  override def unhandled(message: Any): Unit = message match {
    case ValidateRequest(c) =>
        log.info(s"blindly validating channel=$c")
        val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(PublicKey(c.bitcoinKey1), PublicKey(c.bitcoinKey2))))
        val TxCoordinates(_, _, outputIndex) = ShortChannelId.coordinates(c.shortChannelId)
        val fakeFundingTx = Transaction(
          version = 2,
          txIn = Seq.empty[TxIn],
          txOut = List.fill(outputIndex + 1)(TxOut(Satoshi(0), pubkeyScript)), // quick and dirty way to be sure that the outputIndex'th output is of the expected format
          lockTime = 0)
      sender ! ValidateResult(c, Some(fakeFundingTx), true, None)

    case _ => log.warning(s"unhandled message $message")
  }

  def receive = disconnected(Set.empty, Nil, SortedMap.empty)

  def disconnected(watches: Set[Watch], publishQueue: Seq[PublishAsap], block2tx: SortedMap[Long, Seq[Transaction]]): Receive = {
    case ElectrumClient.ElectrumReady =>
      client ! ElectrumClient.HeaderSubscription(self)
    case ElectrumClient.HeaderSubscriptionResponse(header) =>
      watches.map(self ! _)
      publishQueue.map(self ! _)
      context become running(header, Set(), Map(), block2tx, Nil)
    case watch: Watch => context become disconnected(watches + watch, publishQueue, block2tx)
    case publish: PublishAsap => context become disconnected(watches, publishQueue :+ publish, block2tx)
  }

  def running(tip: ElectrumClient.Header, watches: Set[Watch], scriptHashStatus: Map[BinaryData, String], block2tx: SortedMap[Long, Seq[Transaction]], sent: Seq[Transaction]): Receive = {
    case ElectrumClient.HeaderSubscriptionResponse(newtip) if tip == newtip => ()

    case ElectrumClient.HeaderSubscriptionResponse(newtip) =>
      log.info(s"new tip: ${newtip.block_hash} $newtip")
      watches collect {
        case watch: WatchConfirmed =>
          val scriptHash = computeScriptHash(watch.publicKeyScript)
          client ! ElectrumClient.GetScriptHashHistory(scriptHash)
      }
      val toPublish = block2tx.filterKeys(_ <= newtip.block_height)
      toPublish.values.flatten.foreach(tx => self ! PublishAsap(tx))
      context become running(newtip, watches, scriptHashStatus, block2tx -- toPublish.keys, sent)

    case watch: Watch if watches.contains(watch) => ()

    case watch@WatchSpent(_, txid, outputIndex, publicKeyScript, _) =>
      val scriptHash = computeScriptHash(publicKeyScript)
      log.info(s"added watch-spent on output=$txid:$outputIndex scriptHash=$scriptHash")
      client ! ElectrumClient.ScriptHashSubscription(scriptHash, self)
      context.watch(watch.channel)
      context become running(tip, watches + watch, scriptHashStatus, block2tx, sent)

    case watch@WatchSpentBasic(_, txid, outputIndex, publicKeyScript, _) =>
      val scriptHash = computeScriptHash(publicKeyScript)
      log.info(s"added watch-spent-basic on output=$txid:$outputIndex scriptHash=$scriptHash")
      client ! ElectrumClient.ScriptHashSubscription(scriptHash, self)
      context.watch(watch.channel)
      context become running(tip, watches + watch, scriptHashStatus, block2tx, sent)

    case watch@WatchConfirmed(_, txid, publicKeyScript, _, _) =>
      val scriptHash = computeScriptHash(publicKeyScript)
      log.info(s"added watch-confirmed on txid=$txid scriptHash=$scriptHash")
      client ! ElectrumClient.GetScriptHashHistory(scriptHash)
      context.watch(watch.channel)
      context become running(tip, watches + watch, scriptHashStatus, block2tx, sent)

    case Terminated(actor) =>
      val watches1 = watches.filterNot(_.channel == actor)
      context become running(tip, watches1, scriptHashStatus, block2tx, sent)

    case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status) =>
      scriptHashStatus.get(scriptHash) match {
        case Some(s) if s == status => log.debug(s"already have status=$status for scriptHash=$scriptHash")
        case _ if status.isEmpty => log.info(s"empty status for scriptHash=$scriptHash")
        case _ =>
          log.info(s"new status=$status for scriptHash=$scriptHash")
          client ! ElectrumClient.GetScriptHashHistory(scriptHash)
      }
      context become running(tip, watches, scriptHashStatus + (scriptHash -> status), block2tx, sent)

    case ElectrumClient.GetScriptHashHistoryResponse(_, history) =>
      // this is for WatchSpent/WatchSpentBasic
      history.filter(_.height >= 0).map(item => client ! ElectrumClient.GetTransaction(item.tx_hash))
      // this is for WatchConfirmed
      history.collect {
        case ElectrumClient.TransactionHistoryItem(height, tx_hash) if height > 0 => watches.collect {
          case WatchConfirmed(_, txid, _, minDepth, _) if txid == tx_hash =>
            val confirmations = tip.block_height - height + 1
            log.info(s"txid=$txid was confirmed at height=$height and now has confirmations=$confirmations (currentHeight=${tip.block_height})")
            if (confirmations >= minDepth) {
              // we need to get the tx position in the block
              client ! GetMerkle(tx_hash, height)
            }
        }
      }

    case ElectrumClient.GetMerkleResponse(tx_hash, _, height, pos) =>
      val confirmations = tip.block_height - height + 1
      val triggered = watches.collect {
        case w@WatchConfirmed(channel, txid, _, minDepth, event) if txid == tx_hash && confirmations >= minDepth =>
          log.info(s"txid=$txid had confirmations=$confirmations in block=$height pos=$pos")
          channel ! WatchEventConfirmed(event, height.toInt, pos)
          w
      }
      context become running(tip, watches -- triggered, scriptHashStatus, block2tx, sent)

    case ElectrumClient.GetTransactionResponse(spendingTx) =>
      val triggered = spendingTx.txIn.map(_.outPoint).flatMap(outPoint => watches.collect {
        case WatchSpent(channel, txid, pos, _, event) if txid == outPoint.txid && pos == outPoint.index.toInt =>
          log.info(s"output $txid:$pos spent by transaction ${spendingTx.txid}")
          channel ! WatchEventSpent(event, spendingTx)
          // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx
          // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
          None
        case w@WatchSpentBasic(channel, txid, pos, _, event) if txid == outPoint.txid && pos == outPoint.index.toInt =>
          log.info(s"output $txid:$pos spent by transaction ${spendingTx.txid}")
          channel ! WatchEventSpentBasic(event)
          Some(w)
      }).flatten
      context become running(tip, watches -- triggered, scriptHashStatus, block2tx, sent)

    case PublishAsap(tx) =>
      val blockCount = Globals.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeout = Scripts.csvTimeout(tx)
      if (csvTimeout > 0) {
        require(tx.txIn.size == 1, s"watcher only supports tx with 1 input, this tx has ${tx.txIn.size} inputs")
        val parentTxid = tx.txIn(0).outPoint.txid
        log.info(s"txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parenttxid=$parentTxid tx=$tx")
        val parentPublicKeyScript = WatchConfirmed.extractPublicKeyScript(tx.txIn.head.witness)
        self ! WatchConfirmed(self, parentTxid, parentPublicKeyScript, minDepth = 1, BITCOIN_PARENT_TX_CONFIRMED(tx))
      } else if (cltvTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(cltvTimeout, block2tx.getOrElse(cltvTimeout, Seq.empty[Transaction]) :+ tx)
        context become running(tip, watches, scriptHashStatus, block2tx1, sent)
      } else {
        log.info(s"publishing tx=$tx")
        client ! BroadcastTransaction(tx)
        context become running(tip, watches, scriptHashStatus, block2tx, sent :+ tx)
      }

    case WatchEventConfirmed(BITCOIN_PARENT_TX_CONFIRMED(tx), blockHeight, _) =>
      log.info(s"parent tx of txid=${tx.txid} has been confirmed")
      val blockCount = Globals.blockCount.get()
      val csvTimeout = Scripts.csvTimeout(tx)
      val absTimeout = blockHeight + csvTimeout
      if (absTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$absTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(absTimeout, block2tx.getOrElse(absTimeout, Seq.empty[Transaction]) :+ tx)
        context become running(tip, watches, scriptHashStatus, block2tx1, sent)
      } else {
        log.info(s"publishing tx=$tx")
        client ! BroadcastTransaction(tx)
        context become running(tip, watches, scriptHashStatus, block2tx, sent :+ tx)
      }

    case ElectrumClient.BroadcastTransactionResponse(tx, error_opt) =>
      error_opt match {
        case None => log.info(s"broadcast succeeded for txid=${tx.txid} tx=$tx")
        case Some(error) if error.message.contains("transaction already in block chain") => log.info(s"broadcast ignored for txid=${tx.txid} tx=$tx (tx was already in blockchain)")
        case Some(error) => log.error(s"broadcast failed for txid=${tx.txid} tx=$tx with error=$error")
      }
      context become running(tip, watches, scriptHashStatus, block2tx, sent diff Seq(tx))

    case ElectrumClient.ElectrumDisconnected =>
      // we remember watches and keep track of tx that have not yet been published
      // we also re-send the txes that we previously sent but hadn't yet received the confirmation
      context become disconnected(watches, sent.map(PublishAsap(_)), block2tx)
  }

}

object ElectrumWatcher extends App {

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
      case ElectrumClient.ElectrumReady =>
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
