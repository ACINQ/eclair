/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.channel

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Transaction}
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchParentTxConfirmed, WatchParentTxConfirmedTriggered}
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.{Logs, NodeParams}

import scala.collection.immutable.SortedMap
import scala.concurrent.ExecutionContext

/**
 * Created by t-bast on 25/03/2021.
 */

/**
 * This actor ensures its parent channel's on-chain transactions confirm in a timely manner.
 * It sets the fees, tracks confirmation progress and bumps fees if necessary.
 */
object TxPublisher {

  // @formatter:off
  sealed trait Command
  sealed trait PublishTx extends Command {
    def tx: Transaction
    def desc: String
  }
  /**  Publish a fully signed transaction without modifying it. */
  case class PublishRawTx(tx: Transaction, desc: String, parentTx_opt: Option[Transaction]) extends PublishTx
  object PublishRawTx {
    def apply(txInfo: TransactionWithInputInfo, parentTx_opt: Option[Transaction]): PublishRawTx = PublishRawTx(txInfo.tx, txInfo.desc, parentTx_opt)
  }
  /**
   * Publish an unsigned transaction. Once (csv and cltv) delays have been satisfied, the tx publisher will set the fees,
   * sign the transaction and broadcast it.
   */
  case class SignAndPublishTx(txInfo: ReplaceableTransactionWithInputInfo, commitments: Commitments) extends PublishTx {
    override def tx: Transaction = txInfo.tx
    override def desc: String = txInfo.desc
  }
  case class WrappedCurrentBlockCount(currentBlockCount: Long) extends Command
  case class ParentTxConfirmed(childTx: PublishTx, parentTxId: ByteVector32) extends Command
  case class WrappedTxPublishResult(result: TxPublish.TxPublishResult) extends Command
  case class SetChannelId(remoteNodeId: PublicKey, channelId: ByteVector32) extends Command
  // @formatter:on

  case class ChannelInfo(remoteNodeId: PublicKey, channelId_opt: Option[ByteVector32])

  def apply(nodeParams: NodeParams, remoteNodeId: PublicKey, watcher: ActorRef[ZmqWatcher.Command], client: ExtendedBitcoinClient): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))) {
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[CurrentBlockCount](cbc => WrappedCurrentBlockCount(cbc.blockCount)))
        new TxPublisher(nodeParams, watcher, client, context).run(SortedMap.empty, Map.empty, ChannelInfo(remoteNodeId, None))
      }
    }

}

private class TxPublisher(nodeParams: NodeParams, watcher: ActorRef[ZmqWatcher.Command], client: ExtendedBitcoinClient, context: ActorContext[TxPublisher.Command])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import TxPublisher._

  private case class TxWithRelativeDelay(childTx: PublishTx, parentTxIds: Set[ByteVector32])

  private val log = context.log
  private val watchConfirmedResponseMapper: ActorRef[WatchParentTxConfirmedTriggered] = context.messageAdapter(w => ParentTxConfirmed(w.childTx, w.tx.txid))
  private val publishTxResponseMapper: ActorRef[TxPublish.TxPublishResult] = context.messageAdapter(r => WrappedTxPublishResult(r))

  /**
   * @param cltvDelayedTxs when transactions are cltv-delayed, we wait until the target blockchain height is reached.
   * @param csvDelayedTxs  when transactions are csv-delayed, we wait for all parent txs to have enough confirmations.
   */
  private def run(cltvDelayedTxs: SortedMap[Long, Seq[PublishTx]], csvDelayedTxs: Map[ByteVector32, TxWithRelativeDelay], channelInfo: ChannelInfo): Behavior[Command] =
    Behaviors.receiveMessage {
      case p: PublishTx =>
        val blockCount = nodeParams.currentBlockHeight
        val cltvTimeout = Scripts.cltvTimeout(p.tx)
        val csvTimeouts = Scripts.csvTimeouts(p.tx)
        if (csvTimeouts.nonEmpty) {
          csvTimeouts.foreach {
            case (parentTxId, csvTimeout) =>
              log.info(s"${p.desc} txid=${p.tx.txid} has a relative timeout of $csvTimeout blocks, watching parentTxId=$parentTxId tx={}", p.tx)
              watcher ! WatchParentTxConfirmed(watchConfirmedResponseMapper, parentTxId, minDepth = csvTimeout, p)
          }
          run(cltvDelayedTxs, csvDelayedTxs + (p.tx.txid -> TxWithRelativeDelay(p, csvTimeouts.keySet)), channelInfo)
        } else if (cltvTimeout > blockCount) {
          log.info(s"delaying publication of ${p.desc} txid=${p.tx.txid} until block=$cltvTimeout (current block=$blockCount)")
          val cltvDelayedTxs1 = cltvDelayedTxs + (cltvTimeout -> (cltvDelayedTxs.getOrElse(cltvTimeout, Seq.empty) :+ p))
          run(cltvDelayedTxs1, csvDelayedTxs, channelInfo)
        } else {
          publish(p, channelInfo)
          Behaviors.same
        }

      case ParentTxConfirmed(p, parentTxId) =>
        log.info(s"parent tx of ${p.desc} txid=${p.tx.txid} has been confirmed (parent txid=$parentTxId)")
        val blockCount = nodeParams.currentBlockHeight
        csvDelayedTxs.get(p.tx.txid) match {
          case Some(TxWithRelativeDelay(_, parentTxIds)) =>
            val txWithRelativeDelay1 = TxWithRelativeDelay(p, parentTxIds - parentTxId)
            if (txWithRelativeDelay1.parentTxIds.isEmpty) {
              log.info(s"all parent txs of ${p.desc} txid=${p.tx.txid} have been confirmed")
              val csvDelayedTx1 = csvDelayedTxs - p.tx.txid
              val cltvTimeout = Scripts.cltvTimeout(p.tx)
              if (cltvTimeout > blockCount) {
                log.info(s"delaying publication of ${p.desc} txid=${p.tx.txid} until block=$cltvTimeout (current block=$blockCount)")
                val cltvDelayedTxs1 = cltvDelayedTxs + (cltvTimeout -> (cltvDelayedTxs.getOrElse(cltvTimeout, Seq.empty) :+ p))
                run(cltvDelayedTxs1, csvDelayedTx1, channelInfo)
              } else {
                publish(p, channelInfo)
                run(cltvDelayedTxs, csvDelayedTx1, channelInfo)
              }
            } else {
              log.info(s"some parent txs of ${p.desc} txid=${p.tx.txid} are still unconfirmed (parent txids=${txWithRelativeDelay1.parentTxIds.mkString(",")})")
              run(cltvDelayedTxs, csvDelayedTxs + (p.tx.txid -> txWithRelativeDelay1), channelInfo)
            }
          case None =>
            log.warn(s"${p.desc} txid=${p.tx.txid} not found for parent txid=$parentTxId")
            Behaviors.same
        }

      case WrappedCurrentBlockCount(blockCount) =>
        val toPublish = cltvDelayedTxs.view.filterKeys(_ <= blockCount)
        toPublish.values.flatten.foreach(tx => publish(tx, channelInfo))
        run(cltvDelayedTxs -- toPublish.keys, csvDelayedTxs, channelInfo)

      case WrappedTxPublishResult(result) =>
        result match {
          case _: TxPublish.InsufficientFunds =>
            // We retry when the next block has been found, we may have more funds available.
            val nextBlockCount = nodeParams.currentBlockHeight + 1
            val cltvDelayedTxs1 = cltvDelayedTxs + (nextBlockCount -> (cltvDelayedTxs.getOrElse(nextBlockCount, Seq.empty) :+ result.cmd))
            run(cltvDelayedTxs1, csvDelayedTxs, channelInfo)
          case _ =>
            Behaviors.same
        }

      case SetChannelId(remoteNodeId, channelId) =>
        Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId), channelId_opt = Some(channelId))) {
          run(cltvDelayedTxs, csvDelayedTxs, channelInfo.copy(remoteNodeId = remoteNodeId, channelId_opt = Some(channelId)))
        }
    }

  private def publish(tx: PublishTx, channelInfo: ChannelInfo): Unit = {
    context.spawnAnonymous(TxPublish(nodeParams, client, channelInfo)) ! TxPublish.DoPublish(publishTxResponseMapper, tx)
  }

}
