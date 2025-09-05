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
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel.UnhandledExceptionStrategy
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishReplaceableTx, PublishTx}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.{AcceptChannel, ChannelReestablish, Error, OpenChannel, Warning}

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
    goto(CLOSING) using nextData storing() calling doPublish(closingTx, nextData.commitments.localChannelParams.paysClosingFees)
  }

  def doPublish(closingTx: ClosingTx, localPaysClosingFees: Boolean): Unit = {
    val fee = if (localPaysClosingFees) closingTx.fee else 0.sat
    txPublisher ! PublishFinalTx(closingTx.tx, closingTx.input.outPoint, closingTx.desc, fee, None)
    blockchain ! WatchTxConfirmed(self, closingTx.tx.txid, nodeParams.channelConf.minDepth)
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
      case negotiating: DATA_NEGOTIATING_SIMPLE if negotiating.publishedClosingTxs.nonEmpty =>
        // We have published at least one mutual close transaction, it's better to use it instead of our local commit.
        val closing = DATA_CLOSING(negotiating.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = negotiating.localScriptPubKey, mutualCloseProposed = negotiating.proposedClosingTxs.flatMap(_.all), mutualClosePublished = negotiating.publishedClosingTxs)
        goto(CLOSING) using closing storing()
      case dd: ChannelDataWithCommitments =>
        val maxClosingFeerate_opt = msg match {
          case Some(cmd: CMD_FORCECLOSE) => cmd.maxClosingFeerate_opt
          case _ => None
        }
        // We publish our commitment even if we have nothing at stake: it's a nice thing to do because it lets our peer
        // get their funds back without delays.
        cause match {
          case _: InvalidFundingTx =>
            // invalid funding tx in the single-funding case: we just close the channel
            goto(CLOSED)
          case _: ChannelException =>
            // known channel exception: we force close using our current commitment
            spendLocalCurrent(dd, maxClosingFeerate_opt) sending error
          case _ =>
            // unhandled exception: we apply the configured strategy
            nodeParams.channelConf.unhandledExceptionStrategy match {
              case UnhandledExceptionStrategy.LocalClose =>
                spendLocalCurrent(dd, maxClosingFeerate_opt) sending error
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
      // When there is no commitment yet, we just send an error to our peer and go to CLOSED state.
      case _: ChannelDataWithoutCommitments => goto(CLOSED) sending error
      case _: TransientChannelData => goto(CLOSED) sending error
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
      case negotiating: DATA_NEGOTIATING_SIMPLE if negotiating.publishedClosingTxs.nonEmpty =>
        // We have published at least one mutual close transaction, it's better to use it instead of our local commit.
        val closing = DATA_CLOSING(negotiating.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = negotiating.localScriptPubKey, mutualCloseProposed = negotiating.proposedClosingTxs.flatMap(_.all), mutualClosePublished = negotiating.publishedClosingTxs)
        goto(CLOSING) using closing storing()
      // NB: we publish the commitment even if we have nothing at stake (in a dataloss situation our peer will send us an error just for that)
      case hasCommitments: ChannelDataWithCommitments =>
        if (e.toAscii == "internal error") {
          // It seems like lnd sends this error whenever something wrong happens on their side, regardless of whether
          // the channel actually needs to be closed. We ignore it to avoid paying the cost of a channel force-close,
          // it's up to them to broadcast their commitment if they wish.
          log.warning("ignoring remote 'internal error', probably coming from lnd")
          stay() sending Warning(d.channelId, "ignoring your 'internal error' to avoid an unnecessary force-close")
        } else if (e.toAscii == "link failed to shutdown") {
          // When trying to close a channel with LND older than version 0.18.0,
          // LND will send an 'link failed to shutdown' error if there are HTLCs on the channel.
          // Ignoring this error will prevent a force-close.
          // The channel closing is retried on every reconnect of the channel, until it succeeds.
          log.warning("ignoring remote 'link failed to shutdown', probably coming from lnd")
          stay() sending Warning(d.channelId, "ignoring your 'link failed to shutdown' to avoid an unnecessary force-close")
        } else {
          spendLocalCurrent(hasCommitments, maxClosingFeerateOverride_opt = None)
        }
      // When there is no commitment yet, we just go to CLOSED state in case an error occurs.
      case waitForDualFundingSigned: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED =>
        rollbackFundingAttempt(waitForDualFundingSigned.signingSession.fundingTx.tx, Nil)
        goto(CLOSED)
      case _: TransientChannelData => goto(CLOSED)
    }
  }

  /**
   * This helper method will publish txs only if they haven't yet reached minDepth
   */
  private def publishIfNeeded(txs: Iterable[PublishTx], irrevocablySpent: Map[OutPoint, Transaction]): Unit = {
    val (skip, process) = txs.partition(publishTx => irrevocablySpent.contains(publishTx.input))
    process.foreach { publishTx => txPublisher ! publishTx }
    skip.foreach(publishTx => log.debug("no need to republish tx spending {}:{}, it has already been confirmed", publishTx.input.txid, publishTx.input.index))
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
    process.foreach(output => blockchain ! WatchOutputSpent(self, parentTx.txid, output.index.toInt, parentTx.txOut(output.index.toInt).amount, Set.empty))
    skip.foreach(output => log.debug(s"no need to watch output=${output.txid}:${output.index}, it has already been spent by txid=${irrevocablySpent.get(output).map(_.txid)}"))
  }

  /** This helper method will watch the given output only if it hasn't already been irrevocably spent. */
  private def watchSpentIfNeeded(input: InputInfo, irrevocablySpent: Map[OutPoint, Transaction]): Unit = {
    if (!irrevocablySpent.contains(input.outPoint)) {
      blockchain ! WatchOutputSpent(self, input.outPoint.txid, input.outPoint.index.toInt, input.txOut.amount, Set.empty)
    }
  }

  def spendLocalCurrent(d: ChannelDataWithCommitments, maxClosingFeerateOverride_opt: Option[FeeratePerKw]): FSM.State[ChannelState, ChannelData] = {
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
      val commitment = d.commitments.latest
      log.error(s"force-closing with fundingIndex=${commitment.fundingTxIndex}")
      context.system.eventStream.publish(NotifyNodeOperator(NotificationsLogger.Error, s"force-closing channel ${d.channelId} with fundingIndex=${commitment.fundingTxIndex}"))
      val commitTx = commitment.fullySignedLocalCommitTx(channelKeys)
      val closingFeerate = nodeParams.onChainFeeConf.getClosingFeerate(nodeParams.currentBitcoinCoreFeerates, maxClosingFeerateOverride_opt)
      val (localCommitPublished, closingTxs) = Closing.LocalClose.claimCommitTxOutputs(channelKeys, commitment, commitTx, closingFeerate, finalScriptPubKey, nodeParams.onChainFeeConf.spendAnchorWithoutHtlcs)
      val nextData = d match {
        case closing: DATA_CLOSING => closing.copy(localCommitPublished = Some(localCommitPublished), maxClosingFeerate_opt = maxClosingFeerateOverride_opt.orElse(closing.maxClosingFeerate_opt))
        case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, negotiating.closingTxProposed.flatten.map(_.unsignedTx), localCommitPublished = Some(localCommitPublished))
        case negotiating: DATA_NEGOTIATING_SIMPLE => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = negotiating.proposedClosingTxs.flatMap(_.all), mutualClosePublished = negotiating.publishedClosingTxs, localCommitPublished = Some(localCommitPublished))
        case _ => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = Nil, localCommitPublished = Some(localCommitPublished), maxClosingFeerate_opt = maxClosingFeerateOverride_opt)
      }
      goto(CLOSING) using nextData storing() calling doPublish(localCommitPublished, closingTxs, commitment)
    }
  }

  /** Publish 2nd-stage transactions for our local commitment. */
  def doPublish(lcp: LocalCommitPublished, txs: Closing.LocalClose.SecondStageTransactions, commitment: FullCommitment): Unit = {
    val publishCommitTx = PublishFinalTx(lcp.commitTx, commitment.fundingInput, "commit-tx", Closing.commitTxFee(commitment.commitInput(channelKeys), lcp.commitTx, commitment.localChannelParams.paysCommitTxFees), None)
    val publishAnchorTx_opt = txs.anchorTx_opt match {
      case Some(anchorTx) if !lcp.isConfirmed =>
        val confirmationTarget = Closing.confirmationTarget(commitment.localCommit, commitment.localCommitParams.dustLimit, commitment.commitmentFormat, nodeParams.onChainFeeConf)
        Some(PublishReplaceableTx(anchorTx, lcp.commitTx, commitment, confirmationTarget))
      case _ => None
    }
    val publishMainDelayedTx_opt = txs.mainDelayedTx_opt.map(tx => PublishFinalTx(tx, None))
    val publishHtlcTxs = txs.htlcTxs.map(htlcTx => commitment.commitmentFormat match {
      case DefaultCommitmentFormat => PublishFinalTx(htlcTx, Some(lcp.commitTx.txid))
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => PublishReplaceableTx(htlcTx, lcp.commitTx, commitment, Closing.confirmationTarget(htlcTx))
    })
    val publishQueue = Seq(publishCommitTx) ++ publishAnchorTx_opt ++ publishMainDelayedTx_opt ++ publishHtlcTxs
    publishIfNeeded(publishQueue, lcp.irrevocablySpent)

    if (!lcp.isConfirmed) {
      // We watch the commitment transaction: once confirmed, it invalidates other types of force-close.
      blockchain ! WatchTxConfirmed(self, lcp.commitTx.txid, nodeParams.channelConf.minDepth)
    }

    // We watch outputs of the commitment transaction that we may spend: every time we detect a spending transaction,
    // we will watch for its confirmation. This ensures that we detect double-spends that could come from:
    //  - our own RBF attempts
    //  - remote transactions for outputs that both parties may spend (e.g. HTLCs)
    val watchSpentQueue = lcp.localOutput_opt ++ lcp.anchorOutput_opt ++ lcp.htlcOutputs.toSeq
    watchSpentIfNeeded(lcp.commitTx, watchSpentQueue, lcp.irrevocablySpent)
  }

  /** Publish 3rd-stage transactions for our local commitment. */
  def doPublish(lcp: LocalCommitPublished, txs: Closing.LocalClose.ThirdStageTransactions): Unit = {
    val publishHtlcDelayedTxs = txs.htlcDelayedTxs.map(tx => PublishFinalTx(tx, None))
    publishIfNeeded(publishHtlcDelayedTxs, lcp.irrevocablySpent)
    // We watch the spent outputs to detect our RBF attempts.
    txs.htlcDelayedTxs.foreach(tx => watchSpentIfNeeded(tx.input, lcp.irrevocablySpent))
  }

  def handleRemoteSpentCurrent(commitTx: Transaction, d: ChannelDataWithCommitments) = {
    val commitments = d.commitments.latest
    log.warning(s"they published their current commit in txid=${commitTx.txid}")
    require(commitTx.txid == commitments.remoteCommit.txId, "txid mismatch")
    val finalScriptPubKey = getOrGenerateFinalScriptPubKey(d)
    val closingFeerate = d match {
      case closing: DATA_CLOSING => nodeParams.onChainFeeConf.getClosingFeerate(nodeParams.currentBitcoinCoreFeerates, closing.maxClosingFeerate_opt)
      case _ => nodeParams.onChainFeeConf.getClosingFeerate(nodeParams.currentBitcoinCoreFeerates, maxClosingFeerateOverride_opt = None)
    }
    context.system.eventStream.publish(TransactionPublished(d.channelId, remoteNodeId, commitTx, Closing.commitTxFee(commitments.commitInput(channelKeys), commitTx, d.commitments.localChannelParams.paysCommitTxFees), "remote-commit"))
    val (remoteCommitPublished, closingTxs) = Closing.RemoteClose.claimCommitTxOutputs(channelKeys, commitments, commitments.remoteCommit, commitTx, closingFeerate, finalScriptPubKey, nodeParams.onChainFeeConf.spendAnchorWithoutHtlcs)
    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(remoteCommitPublished = Some(remoteCommitPublished))
      case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = negotiating.closingTxProposed.flatten.map(_.unsignedTx), remoteCommitPublished = Some(remoteCommitPublished))
      case negotiating: DATA_NEGOTIATING_SIMPLE => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = negotiating.proposedClosingTxs.flatMap(_.all), mutualClosePublished = negotiating.publishedClosingTxs, remoteCommitPublished = Some(remoteCommitPublished))
      case _ => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = Nil, remoteCommitPublished = Some(remoteCommitPublished))
    }
    goto(CLOSING) using nextData storing() calling doPublish(remoteCommitPublished, closingTxs, commitments)
  }

  def handleRemoteSpentNext(commitTx: Transaction, d: ChannelDataWithCommitments) = {
    val commitment = d.commitments.latest
    log.warning(s"they published their next commit in txid=${commitTx.txid}")
    require(commitment.nextRemoteCommit_opt.nonEmpty, "next remote commit must be defined")
    val remoteCommit = commitment.nextRemoteCommit_opt.get.commit
    require(commitTx.txid == remoteCommit.txId, "txid mismatch")
    val finalScriptPubKey = getOrGenerateFinalScriptPubKey(d)
    val closingFeerate = d match {
      case closing: DATA_CLOSING => nodeParams.onChainFeeConf.getClosingFeerate(nodeParams.currentBitcoinCoreFeerates, closing.maxClosingFeerate_opt)
      case _ => nodeParams.onChainFeeConf.getClosingFeerate(nodeParams.currentBitcoinCoreFeerates, maxClosingFeerateOverride_opt = None)
    }
    context.system.eventStream.publish(TransactionPublished(d.channelId, remoteNodeId, commitTx, Closing.commitTxFee(commitment.commitInput(channelKeys), commitTx, d.commitments.localChannelParams.paysCommitTxFees), "next-remote-commit"))
    val (remoteCommitPublished, closingTxs) = Closing.RemoteClose.claimCommitTxOutputs(channelKeys, commitment, remoteCommit, commitTx, closingFeerate, finalScriptPubKey, nodeParams.onChainFeeConf.spendAnchorWithoutHtlcs)
    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(nextRemoteCommitPublished = Some(remoteCommitPublished))
      case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = negotiating.closingTxProposed.flatten.map(_.unsignedTx), nextRemoteCommitPublished = Some(remoteCommitPublished))
      case negotiating: DATA_NEGOTIATING_SIMPLE => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = negotiating.proposedClosingTxs.flatMap(_.all), mutualClosePublished = negotiating.publishedClosingTxs, remoteCommitPublished = Some(remoteCommitPublished))
      // NB: if there is a next commitment, we can't be in DATA_WAIT_FOR_FUNDING_CONFIRMED so we don't have the case where fundingTx is defined
      case _ => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = Nil, nextRemoteCommitPublished = Some(remoteCommitPublished))
    }
    goto(CLOSING) using nextData storing() calling doPublish(remoteCommitPublished, closingTxs, commitment)
  }

  /** Publish 2nd-stage transactions for the remote commitment (no need for 3rd-stage transactions in that case). */
  def doPublish(rcp: RemoteCommitPublished, txs: Closing.RemoteClose.SecondStageTransactions, commitment: FullCommitment): Unit = {
    val remoteCommit = commitment.nextRemoteCommit_opt match {
      case Some(c) if rcp.commitTx.txid == c.commit.txId => c.commit
      case _ => commitment.remoteCommit
    }
    val publishAnchorTx_opt = txs.anchorTx_opt match {
      case Some(anchorTx) if !rcp.isConfirmed =>
        val confirmationTarget = Closing.confirmationTarget(remoteCommit, commitment.remoteCommitParams.dustLimit, commitment.commitmentFormat, nodeParams.onChainFeeConf)
        Some(PublishReplaceableTx(anchorTx, rcp.commitTx, commitment, confirmationTarget))
      case _ => None
    }
    val publishMainTx_opt = txs.mainTx_opt.map(tx => PublishFinalTx(tx, None))
    val publishHtlcTxs = txs.htlcTxs.map(htlcTx => PublishReplaceableTx(htlcTx, rcp.commitTx, commitment, Closing.confirmationTarget(htlcTx)))
    val publishQueue = publishAnchorTx_opt ++ publishMainTx_opt ++ publishHtlcTxs
    publishIfNeeded(publishQueue, rcp.irrevocablySpent)

    if (!rcp.isConfirmed) {
      // We watch the commitment transaction: once confirmed, it invalidates other types of force-close.
      blockchain ! WatchTxConfirmed(self, rcp.commitTx.txid, nodeParams.channelConf.minDepth)
    }

    // We watch outputs of the commitment transaction that we may spend: every time we detect a spending transaction,
    // we will watch for its confirmation. This ensures that we detect double-spends that could come from:
    //  - our own RBF attempts
    //  - remote transactions for outputs that both parties may spend (e.g. HTLCs)
    val watchSpentQueue = rcp.localOutput_opt ++ rcp.anchorOutput_opt ++ rcp.htlcOutputs.toSeq
    watchSpentIfNeeded(rcp.commitTx, watchSpentQueue, rcp.irrevocablySpent)
  }

  def handleRemoteSpentOther(tx: Transaction, d: ChannelDataWithCommitments) = {
    val commitment = d.commitments.latest
    log.warning("funding tx spent by txid={}", tx.txid)
    val finalScriptPubKey = getOrGenerateFinalScriptPubKey(d)
    Closing.RevokedClose.getRemotePerCommitmentSecret(d.commitments.channelParams, channelKeys, d.commitments.remotePerCommitmentSecrets, tx) match {
      case Some((commitmentNumber, remotePerCommitmentSecret)) =>
        // TODO: once we allow changing the commitment format or to_self_delay during a splice, those values may be incorrect.
        val toSelfDelay = commitment.remoteCommitParams.toSelfDelay
        val commitmentFormat = commitment.commitmentFormat
        val dustLimit = commitment.localCommitParams.dustLimit
        val (revokedCommitPublished, closingTxs) = Closing.RevokedClose.claimCommitTxOutputs(d.commitments.channelParams, channelKeys, tx, commitmentNumber, remotePerCommitmentSecret, toSelfDelay, commitmentFormat, nodeParams.db.channels, dustLimit, nodeParams.currentBitcoinCoreFeerates, nodeParams.onChainFeeConf, finalScriptPubKey)
        log.warning("txid={} was a revoked commitment, publishing the penalty tx", tx.txid)
        context.system.eventStream.publish(TransactionPublished(d.channelId, remoteNodeId, tx, Closing.commitTxFee(commitment.commitInput(channelKeys), tx, d.commitments.localChannelParams.paysCommitTxFees), "revoked-commit"))
        val exc = FundingTxSpent(d.channelId, tx.txid)
        val error = Error(d.channelId, exc.getMessage)
        val nextData = d match {
          case closing: DATA_CLOSING => closing.copy(revokedCommitPublished = closing.revokedCommitPublished :+ revokedCommitPublished)
          case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = negotiating.closingTxProposed.flatten.map(_.unsignedTx), revokedCommitPublished = revokedCommitPublished :: Nil)
          case negotiating: DATA_NEGOTIATING_SIMPLE => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = negotiating.proposedClosingTxs.flatMap(_.all), mutualClosePublished = negotiating.publishedClosingTxs, revokedCommitPublished = revokedCommitPublished :: Nil)
          // NB: if there is a revoked commitment, we can't be in DATA_WAIT_FOR_FUNDING_CONFIRMED so we don't have the case where fundingTx is defined
          case _ => DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = Nil, revokedCommitPublished = revokedCommitPublished :: Nil)
        }
        goto(CLOSING) using nextData storing() calling doPublish(revokedCommitPublished, closingTxs) sending error
      case None => d match {
        case d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT =>
          log.warning("they published a future commit (because we asked them to) in txid={}", tx.txid)
          context.system.eventStream.publish(TransactionPublished(d.channelId, remoteNodeId, tx, Closing.commitTxFee(d.commitments.latest.commitInput(channelKeys), tx, d.commitments.localChannelParams.paysCommitTxFees), "future-remote-commit"))
          val remotePerCommitmentPoint = d.remoteChannelReestablish.myCurrentPerCommitmentPoint
          val commitKeys = d.commitments.latest.remoteKeys(channelKeys, remotePerCommitmentPoint)
          val closingFeerate = nodeParams.onChainFeeConf.getClosingFeerate(nodeParams.currentBitcoinCoreFeerates, maxClosingFeerateOverride_opt = None)
          val mainTx_opt = Closing.RemoteClose.claimMainOutput(commitKeys, tx, d.commitments.latest.localCommitParams.dustLimit, d.commitments.latest.commitmentFormat, closingFeerate, finalScriptPubKey)
          mainTx_opt.foreach(tx => log.warning("publishing our recovery transaction: tx={}", tx.toString))
          val remoteCommitPublished = RemoteCommitPublished(
            commitTx = tx,
            localOutput_opt = mainTx_opt.map(_.input.outPoint),
            anchorOutput_opt = None,
            incomingHtlcs = Map.empty,
            outgoingHtlcs = Map.empty,
            irrevocablySpent = Map.empty)
          val closingTxs = Closing.RemoteClose.SecondStageTransactions(mainTx_opt, anchorTx_opt = None, htlcTxs = Nil)
          val nextData = DATA_CLOSING(d.commitments, waitingSince = nodeParams.currentBlockHeight, finalScriptPubKey = finalScriptPubKey, mutualCloseProposed = Nil, futureRemoteCommitPublished = Some(remoteCommitPublished))
          goto(CLOSING) using nextData storing() calling doPublish(remoteCommitPublished, closingTxs, d.commitments.latest)
        case _ =>
          // the published tx doesn't seem to be a valid commitment transaction
          log.error(s"couldn't identify txid=${tx.txid}, something very bad is going on!!!")
          context.system.eventStream.publish(NotifyNodeOperator(NotificationsLogger.Error, s"funding tx ${commitment.fundingTxId} of channel ${d.channelId} was spent by an unknown transaction, indicating that your DB has lost data or your node has been breached: please contact the dev team."))
          goto(ERR_INFORMATION_LEAK)
      }
    }
  }

  /** Publish 2nd-stage transactions for a revoked remote commitment. */
  def doPublish(rvk: RevokedCommitPublished, txs: Closing.RevokedClose.SecondStageTransactions): Unit = {
    val publishQueue = (txs.mainTx_opt ++ txs.mainPenaltyTx_opt ++ txs.htlcPenaltyTxs).map(tx => PublishFinalTx(tx, None))
    publishIfNeeded(publishQueue, rvk.irrevocablySpent)

    if (!rvk.isConfirmed) {
      // We watch the commitment transaction: once confirmed, it invalidates other types of force-close.
      blockchain ! WatchTxConfirmed(self, rvk.commitTx.txid, nodeParams.channelConf.minDepth)
    }

    // We watch outputs of the commitment tx that both parties may spend, or that we may RBF.
    val watchSpentQueue = rvk.localOutput_opt ++ rvk.remoteOutput_opt ++ rvk.htlcOutputs.toSeq
    watchSpentIfNeeded(rvk.commitTx, watchSpentQueue, rvk.irrevocablySpent)
  }

  /** Publish 3rd-stage transactions for a revoked remote commitment. */
  def doPublish(rvk: RevokedCommitPublished, txs: Closing.RevokedClose.ThirdStageTransactions): Unit = {
    val publishQueue = txs.htlcDelayedPenaltyTxs.map(tx => PublishFinalTx(tx, None))
    publishIfNeeded(publishQueue, rvk.irrevocablySpent)
    // We watch the spent outputs to detect our own RBF attempts.
    txs.htlcDelayedPenaltyTxs.foreach(tx => watchSpentIfNeeded(tx.input, rvk.irrevocablySpent))
  }

  def handleOutdatedCommitment(channelReestablish: ChannelReestablish, d: ChannelDataWithCommitments) = {
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
