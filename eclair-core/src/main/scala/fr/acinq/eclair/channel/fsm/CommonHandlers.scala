/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.channel.fsm

import akka.actor.FSM
import fr.acinq.bitcoin.crypto.musig2.{IndividualNonce, SecretNonce}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Script}
import fr.acinq.eclair.Features
import fr.acinq.eclair.channel.Helpers.Closing.MutualClose
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.PendingCommandsDb
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.wire.protocol.{ClosingComplete, HtlcSettlementMessage, LightningMessage, Shutdown, UpdateMessage}
import scodec.bits.ByteVector

import scala.concurrent.duration.DurationInt

/**
 * Created by t-bast on 28/03/2022.
 */

/**
 * This trait contains utility functions for basic channel tasks.
 */
trait CommonHandlers {

  this: Channel =>

  def send(msg: LightningMessage): Unit = {
    peer ! Peer.OutgoingMessage(msg, activeConnection)
  }

  implicit def state2mystate(state: FSM.State[ChannelState, ChannelData]): MyState = MyState(state)

  /**
   * We wrap the FSM state to add some utility functions that can be called on state transitions.
   */
  case class MyState(state: FSM.State[ChannelState, ChannelData]) {

    def storing(unused: Unit = ()): FSM.State[ChannelState, ChannelData] = {
      state.stateData match {
        case d: PersistentChannelData =>
          log.debug("updating database record for channelId={}", d.channelId)
          nodeParams.db.channels.addOrUpdateChannel(d)
          context.system.eventStream.publish(ChannelPersisted(self, remoteNodeId, d.channelId, d))
          state
        case _: TransientChannelData =>
          log.error(s"can't store data=${state.stateData} in state=${state.stateName}")
          state
      }
    }

    def sending(msgs: Seq[LightningMessage]): FSM.State[ChannelState, ChannelData] = {
      msgs.foreach(sending)
      state
    }

    def sending(msg: LightningMessage): FSM.State[ChannelState, ChannelData] = {
      send(msg)
      state
    }

    /**
     * This method allows performing actions during the transition, e.g. after a call to [[MyState.storing]]. This is
     * particularly useful to publish transactions only after we are sure that the state has been persisted.
     */
    def calling(f: => Unit): FSM.State[ChannelState, ChannelData] = {
      f
      state
    }

    /**
     * We don't acknowledge htlc commands immediately, because we send them to the channel as soon as possible, and they
     * may not yet have been written to the database.
     *
     * @param cmd fail/fulfill command that has been processed
     */
    def acking(channelId: ByteVector32, cmd: HtlcSettlementCommand): FSM.State[ChannelState, ChannelData] = {
      log.debug("scheduling acknowledgement of cmd id={}", cmd.id)
      context.system.scheduler.scheduleOnce(10 seconds)(PendingCommandsDb.ackSettlementCommand(nodeParams.db.pendingCommands, channelId, cmd))(context.system.dispatcher)
      state
    }

    def acking(updates: List[UpdateMessage]): FSM.State[ChannelState, ChannelData] = {
      log.debug("scheduling acknowledgement of cmds ids={}", updates.collect { case s: HtlcSettlementMessage => s.id }.mkString(","))
      context.system.scheduler.scheduleOnce(10 seconds)(PendingCommandsDb.ackSettlementCommands(nodeParams.db.pendingCommands, updates))(context.system.dispatcher)
      state
    }

  }

  /** We don't regenerate the final address if we already have one. */
  def getOrGenerateFinalScriptPubKey(data: ChannelDataWithCommitments): ByteVector = data match {
    case d: DATA_NORMAL if d.localShutdown.isDefined => d.localShutdown.get.scriptPubKey
    case d: DATA_SHUTDOWN => d.localShutdown.scriptPubKey
    case d: DATA_NEGOTIATING => d.localShutdown.scriptPubKey
    case d: DATA_NEGOTIATING_SIMPLE => d.localScriptPubKey
    case d: DATA_CLOSING => d.finalScriptPubKey
    case d =>
      d.commitments.params.localParams.upfrontShutdownScript_opt match {
        case Some(upfrontShutdownScript) =>
          if (data.commitments.params.channelFeatures.hasFeature(Features.UpfrontShutdownScript)) {
            // we have a shutdown script, and the option_upfront_shutdown_script is enabled: we have to use it
            upfrontShutdownScript
          } else {
            log.info("ignoring pre-generated shutdown script, because option_upfront_shutdown_script is disabled")
            generateFinalScriptPubKey()
          }
        case None =>
          // normal case: we don't pre-generate shutdown scripts
          generateFinalScriptPubKey()
      }
  }

  private def generateFinalScriptPubKey(): ByteVector = {
    val finalPubKey = wallet.getP2wpkhPubkey()
    val finalScriptPubKey = Script.write(Script.pay2wpkh(finalPubKey))
    log.info(s"using finalScriptPubkey=$finalScriptPubKey")
    finalScriptPubKey
  }

  def startSimpleClose(commitments: Commitments, localShutdown: Shutdown, remoteShutdown: Shutdown, closingFeerates: Option[ClosingFeerates], localNonce_opt: Option[(SecretNonce, IndividualNonce)] = None): (DATA_NEGOTIATING_SIMPLE, Option[ClosingComplete]) = {
    val localScript = localShutdown.scriptPubKey
    val remoteScript = remoteShutdown.scriptPubKey
    val closingFeerate = closingFeerates.map(_.preferred).getOrElse(nodeParams.onChainFeeConf.getClosingFeerate(nodeParams.currentBitcoinCoreFeerates))
    MutualClose.makeSimpleClosingTx(nodeParams.currentBlockHeight, keyManager, commitments.latest, localScript, remoteScript, closingFeerate, localNonce_opt, remoteShutdown.shutdownNonce_opt) match {
      case Left(f) =>
        log.warning("cannot create local closing txs, waiting for remote closing_complete: {}", f.getMessage)
        val d = DATA_NEGOTIATING_SIMPLE(commitments, closingFeerate, localScript, remoteScript, Nil, Nil)
        (d, None)
      case Right((closingTxs, closingComplete)) =>
        log.debug("signing local mutual close transactions: {}", closingTxs)
        val d = DATA_NEGOTIATING_SIMPLE(commitments, closingFeerate, localScript, remoteScript, closingTxs :: Nil, Nil)
        (d, Some(closingComplete))
    }
  }

}
