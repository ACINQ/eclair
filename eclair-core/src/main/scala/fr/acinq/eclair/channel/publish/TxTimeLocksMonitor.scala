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

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.{Transaction, TxId}
import fr.acinq.eclair.blockchain.{CurrentBlockHeight, OnChainChannelFunder}
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishContext
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.{BlockHeight, NodeParams}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationLong
import scala.util.{Failure, Random, Success}

/**
 * Created by t-bast on 10/06/2021.
 */

/**
 * This actor checks absolute and relative time locks for a given transaction.
 * Once all time locks have been satisfied, it notifies the requesting actor.
 */
object TxTimeLocksMonitor {

  case class TimeLocksOk()

  // @formatter:off
  sealed trait Command
  case class CheckTx(replyTo: ActorRef[TimeLocksOk], tx: Transaction, desc: String) extends Command
  final case class WrappedCurrentBlockHeight(currentBlockHeight: BlockHeight) extends Command
  private case object CheckRelativeTimeLock extends Command
  private case class ParentTxStatus(parentTxId: TxId, confirmations_opt: Option[Int]) extends Command
  private case class GetTxConfirmationsFailed(parentTxId: TxId, reason: Throwable) extends Command
  // @formatter:on

  def apply(nodeParams: NodeParams, bitcoinClient: OnChainChannelFunder, txPublishContext: TxPublishContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        Behaviors.withMdc(txPublishContext.mdc()) {
          Behaviors.receiveMessagePartial {
            case cmd: CheckTx => new TxTimeLocksMonitor(nodeParams, cmd, bitcoinClient, context, timers).checkAbsoluteTimeLock()
          }
        }
      }
    }
  }

}

private class TxTimeLocksMonitor(nodeParams: NodeParams,
                                 cmd: TxTimeLocksMonitor.CheckTx,
                                 bitcoinClient: OnChainChannelFunder,
                                 context: ActorContext[TxTimeLocksMonitor.Command],
                                 timers: TimerScheduler[TxTimeLocksMonitor.Command])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import TxTimeLocksMonitor._

  private val log = context.log

  private def checkAbsoluteTimeLock(): Behavior[Command] = {
    val cltvTimeout = Scripts.cltvTimeout(cmd.tx)
    context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[CurrentBlockHeight](cbc => WrappedCurrentBlockHeight(cbc.blockHeight)))
    if (nodeParams.currentBlockHeight < cltvTimeout) {
      log.info("delaying publication of {} until block={} (current block={})", cmd.desc, cltvTimeout, nodeParams.currentBlockHeight)
      waitForAbsoluteTimeLock(cltvTimeout)
    } else {
      checkRelativeTimeLocks()
    }
  }

  private def waitForAbsoluteTimeLock(cltvTimeout: BlockHeight): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedCurrentBlockHeight(currentBlockHeight) if cltvTimeout <= currentBlockHeight =>
        timers.startSingleTimer(CheckRelativeTimeLock, (1 + Random.nextLong(nodeParams.channelConf.maxTxPublishRetryDelay.toMillis)).millis)
        Behaviors.same
      case WrappedCurrentBlockHeight(_) => Behaviors.same
      case CheckRelativeTimeLock => checkRelativeTimeLocks()
    }
  }

  private def checkRelativeTimeLocks(): Behavior[Command] = {
    val csvTimeouts = Scripts.csvTimeouts(cmd.tx)
    if (csvTimeouts.nonEmpty) {
      val parentTxs = csvTimeouts.map {
        case (parentTxId, csvTimeout) =>
          log.info("{} has a relative timeout of {} blocks, checking confirmations for parentTxId={}", cmd.desc, csvTimeout, parentTxId)
          checkConfirmations(parentTxId)
          parentTxId -> RelativeLockStatus(csvTimeout, nodeParams.currentBlockHeight + csvTimeout)
      }
      waitForRelativeTimeLocks(parentTxs)
    } else {
      notifySender()
    }
  }

  private case class RelativeLockStatus(csvTimeout: Long, checkAfterBlock: BlockHeight)

  private def waitForRelativeTimeLocks(parentTxs: Map[TxId, RelativeLockStatus]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case ParentTxStatus(parentTxId, confirmations_opt) =>
        parentTxs.get(parentTxId) match {
          case Some(status) => confirmations_opt match {
            case Some(confirmations) if status.csvTimeout <= confirmations =>
              log.debug("parentTxId={} of {} has reached enough confirmations", parentTxId, cmd.desc)
              val remainingParentTxs = parentTxs - parentTxId
              if (remainingParentTxs.isEmpty) {
                log.info("all parent txs of {} have reached enough confirmations", cmd.desc)
                notifySender()
              } else {
                log.debug("some parent txs of {} don't have enough confirmations yet (parentTxIds={})", cmd.desc, remainingParentTxs.keySet.mkString(","))
                waitForRelativeTimeLocks(remainingParentTxs)
              }
            case Some(confirmations) =>
              log.debug("parentTxId={} doesn't have enough confirmations, retrying in {} blocks", parentTxId, status.csvTimeout - confirmations)
              val status1 = status.copy(checkAfterBlock = nodeParams.currentBlockHeight + status.csvTimeout - confirmations)
              waitForRelativeTimeLocks(parentTxs + (parentTxId -> status1))
            case None =>
              log.debug("parentTxId={} is unconfirmed, retrying in {} blocks", parentTxId, status.csvTimeout)
              val status1 = status.copy(checkAfterBlock = nodeParams.currentBlockHeight + status.csvTimeout)
              waitForRelativeTimeLocks(parentTxs + (parentTxId -> status1))
          }
          case None =>
            log.debug("ignoring duplicate parentTxId={}", parentTxId)
            Behaviors.same
        }
      case GetTxConfirmationsFailed(parentTxId, reason) =>
        log.warn("could not get tx confirmations for parentTxId={}, retrying ({})", parentTxId, reason.getMessage)
        checkConfirmations(parentTxId)
        Behaviors.same
      case WrappedCurrentBlockHeight(currentBlockHeight) =>
        log.debug("received new block (height={})", currentBlockHeight)
        parentTxs.collect {
          case (parentTxId, status) if status.checkAfterBlock <= currentBlockHeight =>
            log.debug("checking confirmations for parentTxId={} ({} <= {})", parentTxId, status.checkAfterBlock, currentBlockHeight)
            checkConfirmations(parentTxId)
        }
        Behaviors.same
    }
  }

  private def checkConfirmations(parentTxId: TxId): Unit = {
    context.pipeToSelf(bitcoinClient.getTxConfirmations(parentTxId)) {
      case Success(confirmations_opt) => ParentTxStatus(parentTxId, confirmations_opt)
      case Failure(reason) => GetTxConfirmationsFailed(parentTxId, reason)
    }
  }

  private def notifySender(): Behavior[Command] = {
    log.debug("time locks satisfied for {}", cmd.desc)
    cmd.replyTo ! TimeLocksOk()
    Behaviors.stopped
  }

}
