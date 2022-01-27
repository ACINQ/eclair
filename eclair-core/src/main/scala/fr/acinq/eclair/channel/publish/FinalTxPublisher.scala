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

package fr.acinq.eclair.channel.publish

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishContext
import fr.acinq.eclair.channel.publish.TxTimeLocksMonitor.CheckTx

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationLong
import scala.util.{Failure, Random, Success}

/**
 * Created by t-bast on 10/06/2021.
 */

/**
 * This actor publishes a fully signed transaction without modifying it.
 * It waits for confirmation or failure before reporting back to the requesting actor.
 */
object FinalTxPublisher {

  // @formatter:off
  sealed trait Command
  case class Publish(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishFinalTx) extends Command
  private case object TimeLocksOk extends Command
  private case object CheckParentTx extends Command
  private case object ParentTxOk extends Command
  private case object ParentTxMissing extends Command
  private case class WrappedTxResult(result: MempoolTxMonitor.TxResult) extends Command
  private case class UnknownFailure(reason: Throwable) extends Command
  case object Stop extends Command
  // @formatter:on

  def apply(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, watcher: ActorRef[ZmqWatcher.Command], txPublishContext: TxPublishContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        Behaviors.withMdc(txPublishContext.mdc()) {
          Behaviors.receiveMessagePartial {
            case Publish(replyTo, cmd) => new FinalTxPublisher(nodeParams, replyTo, cmd, bitcoinClient, watcher, context, timers, txPublishContext).checkTimeLocks()
            case Stop => Behaviors.stopped
          }
        }
      }
    }
  }

}

private class FinalTxPublisher(nodeParams: NodeParams,
                               replyTo: ActorRef[TxPublisher.PublishTxResult],
                               cmd: TxPublisher.PublishFinalTx,
                               bitcoinClient: BitcoinCoreClient,
                               watcher: ActorRef[ZmqWatcher.Command],
                               context: ActorContext[FinalTxPublisher.Command],
                               timers: TimerScheduler[FinalTxPublisher.Command],
                               txPublishContext: TxPublishContext)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import FinalTxPublisher._

  private val log = context.log

  def checkTimeLocks(): Behavior[Command] = {
    val timeLocksChecker = context.spawn(TxTimeLocksMonitor(nodeParams, watcher, txPublishContext), "time-locks-monitor")
    timeLocksChecker ! CheckTx(context.messageAdapter[TxTimeLocksMonitor.TimeLocksOk](_ => TimeLocksOk), cmd.tx, cmd.desc)
    Behaviors.receiveMessagePartial {
      case TimeLocksOk => checkParentPublished()
      case Stop => Behaviors.stopped
    }
  }

  def checkParentPublished(): Behavior[Command] = {
    cmd.parentTx_opt match {
      case Some(parentTxId) =>
        context.self ! CheckParentTx
        Behaviors.receiveMessagePartial {
          case CheckParentTx =>
            context.pipeToSelf(bitcoinClient.getTxConfirmations(parentTxId)) {
              case Success(Some(_)) => ParentTxOk
              case Success(None) => ParentTxMissing
              case Failure(reason) => UnknownFailure(reason)
            }
            Behaviors.same
          case ParentTxOk => publish()
          case ParentTxMissing =>
            log.debug("parent tx is missing, retrying after delay...")
            timers.startSingleTimer(CheckParentTx, (1 + Random.nextLong(nodeParams.channelConf.maxTxPublishRetryDelay.toMillis)).millis)
            Behaviors.same
          case UnknownFailure(reason) =>
            log.error("could not check parent tx: ", reason)
            sendResult(TxPublisher.TxRejected(txPublishContext.id, cmd, TxPublisher.TxRejectedReason.UnknownTxFailure))
          case Stop => Behaviors.stopped
        }
      case None => publish()
    }
  }

  def publish(): Behavior[Command] = {
    val txMonitor = context.spawn(MempoolTxMonitor(nodeParams, bitcoinClient, txPublishContext), "mempool-tx-monitor")
    txMonitor ! MempoolTxMonitor.Publish(context.messageAdapter[MempoolTxMonitor.TxResult](WrappedTxResult), cmd.tx, cmd.input, cmd.desc, cmd.fee)
    Behaviors.receiveMessagePartial {
      case WrappedTxResult(txResult) =>
        txResult match {
          case _: MempoolTxMonitor.IntermediateTxResult => Behaviors.same
          case MempoolTxMonitor.TxRejected(_, reason) => sendResult(TxPublisher.TxRejected(txPublishContext.id, cmd, reason))
          case MempoolTxMonitor.TxDeeplyBuried(tx) => sendResult(TxPublisher.TxConfirmed(cmd, tx))
        }
      case Stop => Behaviors.stopped
    }
  }

  def sendResult(result: TxPublisher.PublishTxResult): Behavior[Command] = {
    replyTo ! result
    Behaviors.receiveMessagePartial {
      case Stop => Behaviors.stopped
    }
  }

}



















































