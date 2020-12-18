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

import akka.actor.{Actor, ActorLogging, ActorRef, Stash, Terminated}
import fr.acinq.bitcoin.{BlockHeader, ByteVector32, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.computeScriptHash
import fr.acinq.eclair.channel.{BITCOIN_FUNDING_DEPTHOK, BITCOIN_PARENT_TX_CONFIRMED}
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.{ShortChannelId, TxCoordinates}

import java.util.concurrent.atomic.AtomicLong
import scala.collection.immutable.{Queue, SortedMap}


class ElectrumWatcher(blockCount: AtomicLong, client: ActorRef) extends Actor with Stash with ActorLogging {

  client ! ElectrumClient.AddStatusListener(self)

  override def unhandled(message: Any): Unit = message match {
    case ValidateRequest(c) =>
      log.info("blindly validating channel={}", c)
      val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(c.bitcoinKey1, c.bitcoinKey2)))
      val TxCoordinates(_, _, outputIndex) = ShortChannelId.coordinates(c.shortChannelId)
      val fakeFundingTx = Transaction(
        version = 2,
        txIn = Seq.empty[TxIn],
        txOut = List.fill(outputIndex + 1)(TxOut(0 sat, pubkeyScript)), // quick and dirty way to be sure that the outputIndex'th output is of the expected format
        lockTime = 0)
      sender ! ValidateResult(c, Right((fakeFundingTx, UtxoStatus.Unspent)))

    case _ => log.warning("unhandled message {}", message)
  }

  def receive: Receive = disconnected(Set.empty, Queue.empty, SortedMap.empty, Queue.empty)

  def disconnected(watches: Set[Watch], publishQueue: Queue[PublishAsap], block2tx: SortedMap[Long, Seq[Transaction]], getTxQueue: Queue[(GetTxWithMeta, ActorRef)]): Receive = {
    case ElectrumClient.ElectrumReady(_, _, _) =>
      client ! ElectrumClient.HeaderSubscription(self)
    case ElectrumClient.HeaderSubscriptionResponse(height, header) =>
      watches.foreach(self ! _)
      publishQueue.foreach(self ! _)
      getTxQueue.foreach { case (msg, origin) => self.tell(msg, origin) }
      context become running(height, header, Set(), Map(), block2tx, Queue.empty)
    case watch: Watch => context become disconnected(watches + watch, publishQueue, block2tx, getTxQueue)
    case publish: PublishAsap => context become disconnected(watches, publishQueue :+ publish, block2tx, getTxQueue)
    case getTx: GetTxWithMeta => context become disconnected(watches, publishQueue, block2tx, getTxQueue :+ (getTx, sender))
  }

  def running(height: Int, tip: BlockHeader, watches: Set[Watch], scriptHashStatus: Map[ByteVector32, String], block2tx: SortedMap[Long, Seq[Transaction]], sent: Queue[Transaction]): Receive = {
    case ElectrumClient.HeaderSubscriptionResponse(_, newtip) if tip == newtip => ()

    case ElectrumClient.HeaderSubscriptionResponse(newheight, newtip) =>
      log.info(s"new tip: ${newtip.blockId} $height")
      watches collect {
        case watch: WatchConfirmed =>
          val scriptHash = computeScriptHash(watch.publicKeyScript)
          client ! ElectrumClient.GetScriptHashHistory(scriptHash)
      }
      val toPublish = block2tx.filterKeys(_ <= newheight)
      toPublish.values.flatten.foreach(publish)
      context become running(newheight, newtip, watches, scriptHashStatus, block2tx -- toPublish.keys, sent ++ toPublish.values.flatten)

    case watch: Watch if watches.contains(watch) => ()

    case watch@WatchSpent(_, txid, outputIndex, publicKeyScript, _) =>
      val scriptHash = computeScriptHash(publicKeyScript)
      log.info(s"added watch-spent on output=$txid:$outputIndex scriptHash=$scriptHash")
      client ! ElectrumClient.ScriptHashSubscription(scriptHash, self)
      context.watch(watch.replyTo)
      context become running(height, tip, watches + watch, scriptHashStatus, block2tx, sent)

    case watch@WatchSpentBasic(_, txid, outputIndex, publicKeyScript, _) =>
      val scriptHash = computeScriptHash(publicKeyScript)
      log.info(s"added watch-spent-basic on output=$txid:$outputIndex scriptHash=$scriptHash")
      client ! ElectrumClient.ScriptHashSubscription(scriptHash, self)
      context.watch(watch.replyTo)
      context become running(height, tip, watches + watch, scriptHashStatus, block2tx, sent)

    case watch@WatchConfirmed(_, txid, publicKeyScript, _, _) =>
      val scriptHash = computeScriptHash(publicKeyScript)
      log.info(s"added watch-confirmed on txid=$txid scriptHash=$scriptHash")
      client ! ElectrumClient.ScriptHashSubscription(scriptHash, self)
      context.watch(watch.replyTo)
      context become running(height, tip, watches + watch, scriptHashStatus, block2tx, sent)

    case Terminated(actor) =>
      val watches1 = watches.filterNot(_.replyTo == actor)
      context become running(height, tip, watches1, scriptHashStatus, block2tx, sent)

    case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status) =>
      scriptHashStatus.get(scriptHash) match {
        case Some(s) if s == status => log.debug(s"already have status=$status for scriptHash=$scriptHash")
        case _ if status.isEmpty => log.info(s"empty status for scriptHash=$scriptHash")
        case _ =>
          log.info(s"new status=$status for scriptHash=$scriptHash")
          client ! ElectrumClient.GetScriptHashHistory(scriptHash)
      }
      context become running(height, tip, watches, scriptHashStatus + (scriptHash -> status), block2tx, sent)

    case ElectrumClient.GetScriptHashHistoryResponse(_, history) =>
      // we retrieve the transaction before checking watches
      // NB: height=-1 means that the tx is unconfirmed and at least one of its inputs is also unconfirmed. we need to take them into consideration if we want to handle unconfirmed txes (which is the case for turbo channels)
      history.filter(_.height >= -1).foreach { item => client ! ElectrumClient.GetTransaction(item.tx_hash, Some(item)) }

    case ElectrumClient.GetTransactionResponse(tx, Some(item: ElectrumClient.TransactionHistoryItem)) =>
      // this is for WatchSpent/WatchSpentBasic
      val watchSpentTriggered = tx.txIn.map(_.outPoint).flatMap(outPoint => watches.collect {
        case WatchSpent(channel, txid, pos, _, event) if txid == outPoint.txid && pos == outPoint.index.toInt =>
          log.info(s"output $txid:$pos spent by transaction ${tx.txid}")
          channel ! WatchEventSpent(event, tx)
          // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx
          // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
          None
        case w@WatchSpentBasic(channel, txid, pos, _, event) if txid == outPoint.txid && pos == outPoint.index.toInt =>
          log.info(s"output $txid:$pos spent by transaction ${tx.txid}")
          channel ! WatchEventSpentBasic(event)
          Some(w)
      }).flatten

      // this is for WatchConfirmed
      val watchConfirmedTriggered = watches.collect {
        case w@WatchConfirmed(channel, txid, _, minDepth, BITCOIN_FUNDING_DEPTHOK) if txid == tx.txid && minDepth == 0 =>
          // special case for mempool watches (min depth = 0)
          val (dummyHeight, dummyTxIndex) = ElectrumWatcher.makeDummyShortChannelId(txid)
          channel ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, dummyHeight, dummyTxIndex, tx)
          Some(w)
        case WatchConfirmed(_, txid, _, minDepth, _) if txid == tx.txid && minDepth > 0 && item.height > 0 =>
          // min depth > 0 here
          val txheight = item.height
          val confirmations = height - txheight + 1
          log.info(s"txid=$txid was confirmed at height=$txheight and now has confirmations=$confirmations (currentHeight=$height)")
          if (confirmations >= minDepth) {
            // we need to get the tx position in the block
            client ! ElectrumClient.GetMerkle(txid, txheight, Some(tx))
          }
          None
      }.flatten

      context become running(height, tip, watches -- watchSpentTriggered -- watchConfirmedTriggered, scriptHashStatus, block2tx, sent)

    case ElectrumClient.GetMerkleResponse(tx_hash, _, txheight, pos, Some(tx: Transaction)) =>
      val confirmations = height - txheight + 1
      val triggered = watches.collect {
        case w@WatchConfirmed(channel, txid, _, minDepth, event) if txid == tx_hash && confirmations >= minDepth =>
          log.info(s"txid=$txid had confirmations=$confirmations in block=$txheight pos=$pos")
          channel ! WatchEventConfirmed(event, txheight.toInt, pos, tx)
          w
      }
      context become running(height, tip, watches -- triggered, scriptHashStatus, block2tx, sent)

    case GetTxWithMeta(txid) => client ! ElectrumClient.GetTransaction(txid, Some(sender))

    case ElectrumClient.GetTransactionResponse(tx, Some(origin: ActorRef)) => origin ! GetTxWithMetaResponse(tx.txid, Some(tx), tip.time)

    case ElectrumClient.ServerError(ElectrumClient.GetTransaction(txid, Some(origin: ActorRef)), _) => origin ! GetTxWithMetaResponse(txid, None, tip.time)

    case PublishAsap(tx) =>
      val blockCount = this.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeout = Scripts.csvTimeout(tx)
      if (csvTimeout > 0) {
        require(tx.txIn.size == 1, s"watcher only supports tx with 1 input, this tx has ${tx.txIn.size} inputs")
        val parentTxid = tx.txIn.head.outPoint.txid
        log.info(s"txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parenttxid=$parentTxid tx={}", tx)
        val parentPublicKeyScript = WatchConfirmed.extractPublicKeyScript(tx.txIn.head.witness)
        self ! WatchConfirmed(self, parentTxid, parentPublicKeyScript, minDepth = 1, BITCOIN_PARENT_TX_CONFIRMED(tx))
      } else if (cltvTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(cltvTimeout, block2tx.getOrElse(cltvTimeout, Seq.empty[Transaction]) :+ tx)
        context become running(height, tip, watches, scriptHashStatus, block2tx1, sent)
      } else {
        publish(tx)
        context become running(height, tip, watches, scriptHashStatus, block2tx, sent :+ tx)
      }

    case WatchEventConfirmed(BITCOIN_PARENT_TX_CONFIRMED(tx), blockHeight, _, _) =>
      log.info(s"parent tx of txid=${tx.txid} has been confirmed")
      val blockCount = this.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeout = Scripts.csvTimeout(tx)
      val absTimeout = math.max(blockHeight + csvTimeout, cltvTimeout)
      if (absTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$absTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(absTimeout, block2tx.getOrElse(absTimeout, Seq.empty[Transaction]) :+ tx)
        context become running(height, tip, watches, scriptHashStatus, block2tx1, sent)
      } else {
        publish(tx)
        context become running(height, tip, watches, scriptHashStatus, block2tx, sent :+ tx)
      }

    case ElectrumClient.BroadcastTransactionResponse(tx, error_opt) =>
      error_opt match {
        case None => log.info(s"broadcast succeeded for txid=${tx.txid} tx={}", tx)
        case Some(error) if error.message.contains("transaction already in block chain") => log.info(s"broadcast ignored for txid=${tx.txid} tx={} (tx was already in blockchain)", tx)
        case Some(error) => log.error(s"broadcast failed for txid=${tx.txid} tx=$tx with error=$error")
      }
      context become running(height, tip, watches, scriptHashStatus, block2tx, sent diff Seq(tx))

    case ElectrumClient.ElectrumDisconnected =>
      // we remember watches and keep track of tx that have not yet been published
      // we also re-send the txes that we previously sent but hadn't yet received the confirmation
      context become disconnected(watches, sent.map(PublishAsap), block2tx, Queue.empty)
  }

  def publish(tx: Transaction): Unit = {
    log.info("publishing tx={}", tx)
    client ! ElectrumClient.BroadcastTransaction(tx)
  }

}

object ElectrumWatcher {
  /**
   * @param txid funding transaction id
   * @return a (blockHeight, txIndex) tuple that is extracted from the input source
   *         This is used to create unique "dummy" short channel ids for zero-conf channels
   */
  def makeDummyShortChannelId(txid: ByteVector32): (Int, Int) = {
    // we use a height of 0
    // - to make sure that the tx will be marked as "confirmed"
    // - to easily identify scids linked to 0-conf channels
    //
    // this gives us a probability of collisions of 0.1% for 5 0-conf channels and 1% for 20
    // collisions mean that users may temporarily see incorrect numbers for their 0-conf channels (until they've been confirmed)
    // if this ever becomes a problem we could just extract some bits for our dummy height instead of just returning 0
    val height = 0
    val txIndex = txid.bits.sliceToInt(0, 16, signed = false)
    (height, txIndex)
  }
}
