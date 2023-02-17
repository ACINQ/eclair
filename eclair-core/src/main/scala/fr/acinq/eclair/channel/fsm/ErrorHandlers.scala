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

import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.actor.{ActorRef, FSM}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, SatoshiLong, Transaction}
import fr.acinq.eclair.NotificationsLogger
import fr.acinq.eclair.NotificationsLogger.NotifyNodeOperator
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchOutputSpent, WatchTxConfirmed}
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel.UnhandledExceptionStrategy
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishReplaceableTx, PublishTx}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.ClosingTx
import fr.acinq.eclair.wire.protocol.{AcceptChannel, ChannelReestablish, Error, OpenChannel}

import java.sql.SQLException

/**
 * Created by t-bast on 28/03/2022.
 */

/**
 * This trait contains handlers for error scenarios (channel closing, force-closing, unhandled, exceptions, etc).
 */
trait ErrorHandlers extends CommonHandlers {

  this: Channel =>

  def handleFastClose(c: CloseCommand, channelId: ByteVector32) = {
    val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
    replyTo ! RES_SUCCESS(c, channelId)
    goto(CLOSED)
  }

  def handleMutualClose(closingTx: ClosingTx, d: Either[DATA_NEGOTIATING, DATA_CLOSING]) = {
    log.info(s"closing tx published: closingTxId=${closingTx.tx.txid}")
    val nextData = d match {
      case Left(negotiating) => DATA_CLOSING(negotiating.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = negotiating.localShutdown.scriptPubKey, mutualCloseProposed = negotiating.closingTxProposed.flatten.map(_.unsignedTx), mutualClosePublished = closingTx :: Nil)
      case Right(closing) => closing.copy(mutualClosePublished = closing.mutualClosePublished :+ closingTx)
    }
    goto(CLOSING) using nextData storing() calling doPublish(closingTx, nextData.commitments.params.localParams.isInitiator)
  }

  def doPublish(closingTx: ClosingTx, isInitiator: Boolean): Unit = {
    // the initiator pays the fee
    val fee = if (isInitiator) closingTx.fee else 0.sat
    txPublisher ! PublishFinalTx(closingTx, fee, None)
    blockchain ! WatchTxConfirmed(self, closingTx.tx.txid, nodeParams.channelConf.minDepthBlocks)
  }

  def handleLocalError(cause: Throwable, d: ChannelData, msg: Option[Any]) = {
    cause match {
      case _: ForcedLocalCommit =>
        log.warning(s"force-closing channel at user request")
      case _ if msg.exists(_.isInstanceOf[OpenChannel]) || msg.exists(_.isInstanceOf[AcceptChannel]) =>
        // invalid remote channel parameters are logged as warning
        log.warning(s"${cause.getMessage} while processing msg=${msg.getOrElse("n/a").getClass.getSimpleName} in state=$stateName")
      case _: ChannelException =>
        log.error(s"${cause.getMessage} while processing msg=${msg.getOrElse("n/a").getClass.getSimpleName} in state=$stateName")
      case _ =>
        // unhandled error: we dump the channel data, and print the stack trace
        log.error(cause, s"msg=${msg.getOrElse("n/a")} stateData=$stateData:")
    }

    val error = Error(d.channelId, cause.getMessage)
    context.system.eventStream.publish(ChannelErrorOccurred(self, stateData.channelId, remoteNodeId, LocalError(cause), isFatal = true))

    d match {
      case negotiating@DATA_NEGOTIATING(_, _, _, _, Some(bestUnpublishedClosingTx)) =>
        log.info(s"we have a valid closing tx, publishing it instead of our commitment: closingTxId=${bestUnpublishedClosingTx.tx.txid}")
        // if we were in the process of closing and already received a closing sig from the counterparty, it's always better to use that
        handleMutualClose(bestUnpublishedClosingTx, Left(negotiating))
      case dd: PersistentChannelData =>
        // We publish our commitment even if we have nothing at stake: it's a nice thing to do because it lets our peer
        // get their funds back without delays.
        cause match {
          case _: InvalidFundingTx =>
            // invalid funding tx in the single-funding case: we just close the channel
            goto(CLOSED)
          case _: ChannelException =>
            // known channel exception: we force close using our current commitment
            spendLocalCurrent(dd) sending error
          case _ =>
            // unhandled exception: we apply the configured strategy
            nodeParams.channelConf.unhandledExceptionStrategy match {
              case UnhandledExceptionStrategy.LocalClose =>
                spendLocalCurrent(dd) sending error
              case UnhandledExceptionStrategy.Stop =>
                log.error("unhandled exception: standard procedure would be to force-close the channel, but eclair has been configured to halt instead.")
                NotificationsLogger.logFatalError(
                  s"""stopping node as configured strategy to unhandled exceptions for nodeId=$remoteNodeId channelId=${d.channelId}
                     |
                     |Eclair has been configured to shut down when an unhandled exception happens, instead of requesting a
                     |force-close from the peer. This gives the operator a chance of avoiding an unnecessary mass force-close
                     |of channels that may be caused by a bug in Eclair, or issues like running out of disk space, etc.
                     |
                     |You should get in touch with Eclair developers and provide logs of your node for analysis.
                     |""".stripMargin, cause)
                sys.exit(1)
                stop(FSM.Shutdown)
            }
        }
      case _: TransientChannelData => goto(CLOSED) sending error // when there is no commitment yet, we just send an error to our peer and go to CLOSED state
    }
  }

  def handleRemoteError(e: Error, d: ChannelData) = {
    // see BOLT 1: only print out data verbatim if is composed of printable ASCII characters
    log.error(s"peer sent error: ascii='${e.toAscii}' bin=${e.data.toHex}")
    context.system.eventStream.publish(ChannelErrorOccurred(self, stateData.channelId, remoteNodeId, RemoteError(e), isFatal = true))

    d match {
      case _: DATA_CLOSING => stay() // nothing to do, there is already a spending tx published
      case negotiating@DATA_NEGOTIATING(_, _, _, _, Some(bestUnpublishedClosingTx)) =>
        // if we were in the process of closing and already received a closing sig from the counterparty, it's always better to use that
        handleMutualClose(bestUnpublishedClosingTx, Left(negotiating))
      // NB: we publish the commitment even if we have nothing at stake (in a dataloss situation our peer will send us an error just for that)
      case hasCommitments: PersistentChannelData => spendLocalCurrent(hasCommitments)
      case _: TransientChannelData => goto(CLOSED) // when there is no commitment yet, we just go to CLOSED state in case an error occurs
    }
  }

  /**
   * This helper method will publish txs only if they haven't yet reached minDepth
   */
  private def publishIfNeeded(txs: Iterable[PublishTx], irrevocablySpent: Map[OutPoint, Transaction]): Unit = {
    val (skip, process) = txs.partition(publishTx => Closing.inputAlreadySpent(publishTx.input, irrevocablySpent))
    process.foreach { publishTx => txPublisher ! publishTx }
    skip.foreach(publishTx => log.info("no need to republish tx spending {}:{}, it has already been confirmed", publishTx.input.txid, publishTx.input.index))
  }

  /**
   * This helper method will watch txs only if they haven't yet reached minDepth
   */
  private def watchConfirmedIfNeeded(txs: Iterable[Transaction], irrevocablySpent: Map[OutPoint, Transaction]): Unit = {
    val (skip, process) = txs.partition(Closing.inputsAlreadySpent(_, irrevocablySpent))
    process.foreach(tx => blockchain ! WatchTxConfirmed(self, tx.txid, nodeParams.channelConf.minDepthBlocks))
    skip.foreach(tx => log.info(s"no need to watch txid=${tx.txid}, it has already been confirmed"))
  }

  /**
   * This helper method will watch txs only if the utxo they spend hasn't already been irrevocably spent
   *
   * @param parentTx transaction which outputs will be watched
   * @param outputs  outputs that will be watched. They must be a subset of the outputs of the `parentTx`
   */
  private def watchSpentIfNeeded(parentTx: Transaction, outputs: Iterable[OutPoint], irrevocablySpent: Map[OutPoint, Transaction]): Unit = {
    outputs.foreach { output =>
      require(output.txid == parentTx.txid && output.index < parentTx.txOut.size, s"output doesn't belong to the given parentTx: output=${output.txid}:${output.index} (expected txid=${parentTx.txid} index < ${parentTx.txOut.size})")
    }
    val (skip, process) = outputs.partition(irrevocablySpent.contains)
    process.foreach(output => blockchain ! WatchOutputSpent(self, parentTx.txid, output.index.toInt, Set.empty))
    skip.foreach(output => log.info(s"no need to watch output=${output.txid}:${output.index}, it has already been spent by txid=${irrevocablySpent.get(output).map(_.txid)}"))
  }

  def spendLocalCurrent(d: PersistentChannelData) = {
    val outdatedCommitment = d match {
      case _: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT => true
      case closing: DATA_CLOSING if closing.futureRemoteCommitPublished.isDefined => true
      case _ => false
    }
    if (outdatedCommitment) {
      log.warning("we have an outdated commitment: will not publish our local tx")
      stay()
    } else {
      val finalScriptPubKey = getOrGenerateFinalScriptPubKey(d)
      val commitTx = d.commitments.latest.fullySignedLocalCommitTx(keyManager).tx
      val localCommitPublished = Closing.LocalClose.claimCommitTxOutputs(keyManager, d.commitments.latest, commitTx, nodeParams.currentBlockHeight, nodeParams.onChainFeeConf, finalScriptPubKey)
      val nextData = d match {
        case closing: DATA_CLOSING => closing.copy(localCommitPublished = Some(localCommitPublished))
        case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, negotiating.closingTxProposed.flatten.map(_.unsignedTx), localCommitPublished = Some(localCommitPublished))
        case _ => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = Nil, localCommitPublished = Some(localCommitPublished))
      }
      goto(CLOSING) using nextData storing() calling doPublish(localCommitPublished, d.commitments.latest)
    }
  }

  def doPublish(localCommitPublished: LocalCommitPublished, commitment: FullCommitment): Unit = {
    import localCommitPublished._

    val isInitiator = commitment.localParams.isInitiator
    val publishQueue = commitment.params.commitmentFormat match {
      case Transactions.DefaultCommitmentFormat =>
        val redeemableHtlcTxs = htlcTxs.values.flatten.map(tx => PublishFinalTx(tx, tx.fee, Some(commitTx.txid)))
        List(PublishFinalTx(commitTx, commitment.commitInput.outPoint, "commit-tx", Closing.commitTxFee(commitment.commitInput, commitTx, isInitiator), None)) ++ (claimMainDelayedOutputTx.map(tx => PublishFinalTx(tx, tx.fee, None)) ++ redeemableHtlcTxs ++ claimHtlcDelayedTxs.map(tx => PublishFinalTx(tx, tx.fee, None)))
      case _: Transactions.AnchorOutputsCommitmentFormat =>
        val redeemableHtlcTxs = htlcTxs.values.flatten.map(tx => PublishReplaceableTx(tx, commitment))
        val claimLocalAnchor = claimAnchorTxs.collect { case tx: Transactions.ClaimLocalAnchorOutputTx => PublishReplaceableTx(tx, commitment) }
        List(PublishFinalTx(commitTx, commitment.commitInput.outPoint, "commit-tx", Closing.commitTxFee(commitment.commitInput, commitTx, isInitiator), None)) ++ claimLocalAnchor ++ claimMainDelayedOutputTx.map(tx => PublishFinalTx(tx, tx.fee, None)) ++ redeemableHtlcTxs ++ claimHtlcDelayedTxs.map(tx => PublishFinalTx(tx, tx.fee, None))
    }
    publishIfNeeded(publishQueue, irrevocablySpent)

    // we watch:
    // - the commitment tx itself, so that we can handle the case where we don't have any outputs
    // - 'final txs' that send funds to our wallet and that spend outputs that only us control
    val watchConfirmedQueue = List(commitTx) ++ claimMainDelayedOutputTx.map(_.tx) ++ claimHtlcDelayedTxs.map(_.tx)
    watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent)

    // we watch outputs of the commitment tx that both parties may spend
    // we also watch our local anchor: this ensures that we will correctly detect when it's confirmed and count its fees
    // in the audit DB, even if we restart before confirmation
    val watchSpentQueue = htlcTxs.keys ++ claimAnchorTxs.collect { case tx: Transactions.ClaimLocalAnchorOutputTx => tx.input.outPoint }
    watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent)
  }

  def handleRemoteSpentCurrent(commitTx: Transaction, d: PersistentChannelData) = {
    val commitments = d.commitments.latest
    log.warning(s"they published their current commit in txid=${commitTx.txid}")
    require(commitTx.txid == commitments.remoteCommit.txid, "txid mismatch")
    val finalScriptPubKey = getOrGenerateFinalScriptPubKey(d)
    context.system.eventStream.publish(TransactionPublished(d.channelId, remoteNodeId, commitTx, Closing.commitTxFee(commitments.commitInput, commitTx, d.commitments.params.localParams.isInitiator), "remote-commit"))
    val remoteCommitPublished = Closing.RemoteClose.claimCommitTxOutputs(keyManager, commitments, commitments.remoteCommit, commitTx, nodeParams.currentBlockHeight, nodeParams.onChainFeeConf, finalScriptPubKey)
    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(remoteCommitPublished = Some(remoteCommitPublished))
      case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = negotiating.closingTxProposed.flatten.map(_.unsignedTx), remoteCommitPublished = Some(remoteCommitPublished))
      case _ => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = Nil, remoteCommitPublished = Some(remoteCommitPublished))
    }
    goto(CLOSING) using nextData storing() calling doPublish(remoteCommitPublished, commitments)
  }

  def handleRemoteSpentFuture(commitTx: Transaction, d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) = {
    // It doesn't matter which commitment we use here, we'll only be able to claim our main outputs which is independent of the commitment.
    val commitments = d.commitments.latest
    log.warning(s"they published their future commit (because we asked them to) in txid=${commitTx.txid}")
    context.system.eventStream.publish(TransactionPublished(d.channelId, remoteNodeId, commitTx, Closing.commitTxFee(commitments.commitInput, commitTx, d.commitments.params.localParams.isInitiator), "future-remote-commit"))
    val finalScriptPubKey = getOrGenerateFinalScriptPubKey(d)
    val remotePerCommitmentPoint = d.remoteChannelReestablish.myCurrentPerCommitmentPoint
    val remoteCommitPublished = RemoteCommitPublished(
      commitTx = commitTx,
      claimMainOutputTx = Closing.RemoteClose.claimMainOutput(keyManager, d.commitments.params, remotePerCommitmentPoint, commitTx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets, finalScriptPubKey),
      claimHtlcTxs = Map.empty,
      claimAnchorTxs = List.empty,
      irrevocablySpent = Map.empty)
    val nextData = DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = Nil, futureRemoteCommitPublished = Some(remoteCommitPublished))
    goto(CLOSING) using nextData storing() calling doPublish(remoteCommitPublished, commitments)
  }

  def handleRemoteSpentNext(commitTx: Transaction, d: PersistentChannelData) = {
    val commitment = d.commitments.latest
    log.warning(s"they published their next commit in txid=${commitTx.txid}")
    require(commitment.nextRemoteCommit_opt.nonEmpty, "next remote commit must be defined")
    val remoteCommit = commitment.nextRemoteCommit_opt.get.commit
    require(commitTx.txid == remoteCommit.txid, "txid mismatch")

    val finalScriptPubKey = getOrGenerateFinalScriptPubKey(d)
    context.system.eventStream.publish(TransactionPublished(d.channelId, remoteNodeId, commitTx, Closing.commitTxFee(commitment.commitInput, commitTx, d.commitments.params.localParams.isInitiator), "next-remote-commit"))
    val remoteCommitPublished = Closing.RemoteClose.claimCommitTxOutputs(keyManager, commitment, remoteCommit, commitTx, nodeParams.currentBlockHeight, nodeParams.onChainFeeConf, finalScriptPubKey)
    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(nextRemoteCommitPublished = Some(remoteCommitPublished))
      case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = negotiating.closingTxProposed.flatten.map(_.unsignedTx), nextRemoteCommitPublished = Some(remoteCommitPublished))
      // NB: if there is a next commitment, we can't be in DATA_WAIT_FOR_FUNDING_CONFIRMED so we don't have the case where fundingTx is defined
      case _ => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = Nil, nextRemoteCommitPublished = Some(remoteCommitPublished))
    }
    goto(CLOSING) using nextData storing() calling doPublish(remoteCommitPublished, commitment)
  }

  def doPublish(remoteCommitPublished: RemoteCommitPublished, commitment: FullCommitment): Unit = {
    import remoteCommitPublished._

    val redeemableHtlcTxs = claimHtlcTxs.values.flatten.map(tx => PublishReplaceableTx(tx, commitment))
    val publishQueue = claimMainOutputTx.map(tx => PublishFinalTx(tx, tx.fee, None)).toSeq ++ redeemableHtlcTxs
    publishIfNeeded(publishQueue, irrevocablySpent)

    // we watch:
    // - the commitment tx itself, so that we can handle the case where we don't have any outputs
    // - 'final txs' that send funds to our wallet and that spend outputs that only us control
    val watchConfirmedQueue = List(commitTx) ++ claimMainOutputTx.map(_.tx)
    watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent)

    // we watch outputs of the commitment tx that both parties may spend
    val watchSpentQueue = claimHtlcTxs.keys
    watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent)
  }

  def handleRemoteSpentOther(tx: Transaction, d: PersistentChannelData) = {
    val commitments = d.commitments.latest
    log.warning(s"funding tx spent in txid=${tx.txid}")
    val finalScriptPubKey = getOrGenerateFinalScriptPubKey(d)
    Closing.RevokedClose.claimCommitTxOutputs(keyManager, d.commitments.params, d.commitments.remotePerCommitmentSecrets, tx, nodeParams.db.channels, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets, finalScriptPubKey) match {
      case Some(revokedCommitPublished) =>
        log.warning(s"txid=${tx.txid} was a revoked commitment, publishing the penalty tx")
        context.system.eventStream.publish(TransactionPublished(d.channelId, remoteNodeId, tx, Closing.commitTxFee(commitments.commitInput, tx, d.commitments.params.localParams.isInitiator), "revoked-commit"))
        val exc = FundingTxSpent(d.channelId, tx.txid)
        val error = Error(d.channelId, exc.getMessage)
        val nextData = d match {
          case closing: DATA_CLOSING => closing.copy(revokedCommitPublished = closing.revokedCommitPublished :+ revokedCommitPublished)
          case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = negotiating.closingTxProposed.flatten.map(_.unsignedTx), revokedCommitPublished = revokedCommitPublished :: Nil)
          // NB: if there is a revoked commitment, we can't be in DATA_WAIT_FOR_FUNDING_CONFIRMED so we don't have the case where fundingTx is defined
          case _ => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = Nil, revokedCommitPublished = revokedCommitPublished :: Nil)
        }
        goto(CLOSING) using nextData storing() calling doPublish(revokedCommitPublished) sending error
      case None =>
        // the published tx was neither their current commitment nor a revoked one
        log.error(s"couldn't identify txid=${tx.txid}, something very bad is going on!!!")
        context.system.eventStream.publish(NotifyNodeOperator(NotificationsLogger.Error, s"funding tx ${commitments.fundingTxId} of channel ${d.channelId} was spent by an unknown transaction, indicating that your DB has lost data or your node has been breached: please contact the dev team."))
        goto(ERR_INFORMATION_LEAK)
    }
  }

  def doPublish(revokedCommitPublished: RevokedCommitPublished): Unit = {
    import revokedCommitPublished._

    val publishQueue = (claimMainOutputTx ++ mainPenaltyTx ++ htlcPenaltyTxs ++ claimHtlcDelayedPenaltyTxs).map(tx => PublishFinalTx(tx, tx.fee, None))
    publishIfNeeded(publishQueue, irrevocablySpent)

    // we watch:
    // - the commitment tx itself, so that we can handle the case where we don't have any outputs
    // - 'final txs' that send funds to our wallet and that spend outputs that only us control
    val watchConfirmedQueue = List(commitTx) ++ claimMainOutputTx.map(_.tx)
    watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent)

    // we watch outputs of the commitment tx that both parties may spend
    val watchSpentQueue = (mainPenaltyTx ++ htlcPenaltyTxs).map(_.input.outPoint)
    watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent)
  }

  def handleOutdatedCommitment(channelReestablish: ChannelReestablish, d: PersistentChannelData) = {
    val exc = PleasePublishYourCommitment(d.channelId)
    val error = Error(d.channelId, exc.getMessage)
    goto(WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) using DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(d.commitments, channelReestablish) storing() sending error
  }

  /**
   * This helper function runs the state's default event handlers, and react to exceptions by unilaterally closing the channel
   */
  def handleExceptions(s: StateFunction): StateFunction = {
    case event if s.isDefinedAt(event) =>
      try {
        s(event)
      } catch {
        case t: SQLException =>
          log.error(t, "fatal database error\n")
          NotificationsLogger.logFatalError("eclair is shutting down because of a fatal database error", t)
          sys.exit(1)
        case t: Throwable => handleLocalError(t, event.stateData, None)
      }
  }

}
