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
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishLogContext
import fr.acinq.eclair.channel.publish.TxTimeLocksMonitor.CheckTx

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationLong
import scala.util.{Failure, Random, Success}

/**
 * Created by t-bast on 10/06/2021.
 */

/**
 * This actor publishes a raw transaction without modifying it.
 * It waits for confirmation or failure before reporting back to the requesting actor.
 */
object RawTxPublisher {

  // @formatter:off
  sealed trait Command
  case class Publish(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishRawTx) extends Command
  private case object TimeLocksOk extends Command
  private case object CheckParentTx extends Command
  private case object ParentTxOk extends Command
  private case object ParentTxMissing extends Command
  private case class WrappedTxResult(result: MempoolTxMonitor.TxResult) extends Command
  private case class UnknownFailure(reason: Throwable) extends Command
  case object Stop extends Command
  // @formatter:on

  def apply(nodeParams: NodeParams, bitcoinClient: ExtendedBitcoinClient, watcher: ActorRef[ZmqWatcher.Command], loggingInfo: TxPublishLogContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        Behaviors.withMdc(loggingInfo.mdc()) {
          new RawTxPublisher(nodeParams, bitcoinClient, watcher, context, timers, loggingInfo).start()
        }
      }
    }
  }

}

private class RawTxPublisher(nodeParams: NodeParams,
                             bitcoinClient: ExtendedBitcoinClient,
                             watcher: ActorRef[ZmqWatcher.Command],
                             context: ActorContext[RawTxPublisher.Command],
                             timers: TimerScheduler[RawTxPublisher.Command],
                             loggingInfo: TxPublishLogContext)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import RawTxPublisher._

  private val log = context.log

  def start(): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case Publish(replyTo, cmd) => checkTimeLocks(replyTo, cmd)
      case Stop => Behaviors.stopped
    }
  }

  def checkTimeLocks(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishRawTx): Behavior[Command] = {
    val timeLocksChecker = context.spawn(TxTimeLocksMonitor(nodeParams, watcher, loggingInfo), "time-locks-monitor")
    timeLocksChecker ! CheckTx(context.messageAdapter[TxTimeLocksMonitor.TimeLocksOk](_ => TimeLocksOk), cmd.tx, cmd.desc)
    Behaviors.receiveMessagePartial {
      case TimeLocksOk => checkParentPublished(replyTo, cmd)
      case Stop =>
        timeLocksChecker ! TxTimeLocksMonitor.Stop
        Behaviors.stopped
    }
  }

  def checkParentPublished(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishRawTx): Behavior[Command] = {
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
          case ParentTxOk => publish(replyTo, cmd)
          case ParentTxMissing =>
            log.debug("parent tx is missing, retrying after delay...")
            timers.startSingleTimer(CheckParentTx, (1 + Random.nextLong(nodeParams.maxTxPublishRetryDelay.toMillis)).millis)
            Behaviors.same
          case UnknownFailure(reason) =>
            log.error("could not check parent tx", reason)
            sendResult(replyTo, TxPublisher.TxRejected(loggingInfo.id, cmd, TxPublisher.TxRejectedReason.UnknownTxFailure))
          case Stop => Behaviors.stopped
        }
      case None => publish(replyTo, cmd)
    }
  }

  def publish(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishRawTx): Behavior[Command] = {
    val txMonitor = context.spawn(MempoolTxMonitor(nodeParams, bitcoinClient, loggingInfo), "mempool-tx-monitor")
    txMonitor ! MempoolTxMonitor.Publish(context.messageAdapter[MempoolTxMonitor.TxResult](WrappedTxResult), cmd.tx, cmd.input)
    Behaviors.receiveMessagePartial {
      case WrappedTxResult(MempoolTxMonitor.TxConfirmed) => sendResult(replyTo, TxPublisher.TxConfirmed(cmd, cmd.tx))
      case WrappedTxResult(MempoolTxMonitor.TxRejected(reason)) => sendResult(replyTo, TxPublisher.TxRejected(loggingInfo.id, cmd, reason))
      case Stop =>
        txMonitor ! MempoolTxMonitor.Stop
        Behaviors.stopped
    }
  }

  def sendResult(replyTo: ActorRef[TxPublisher.PublishTxResult], result: TxPublisher.PublishTxResult): Behavior[Command] = {
    replyTo ! result
    Behaviors.receiveMessagePartial {
      case Stop => Behaviors.stopped
    }
  }

}



















































