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

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, Satoshi, Script, Transaction, TxOut}
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient.FundTransactionOptions
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.UpdateFulfillHtlc
import fr.acinq.eclair.{Logs, NodeParams}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Created by t-bast on 27/05/2021.
 */

/**
 * This actor publishes a given transaction, adding inputs if necessary.
 */
object TxPublish {

  // @formatter:off
  sealed trait Command
  case class DoPublish(replyTo: ActorRef[TxPublishResult], tx: TxPublisher.PublishTx) extends Command
  private case class SignFundedTx(fundedTx: ReplaceableTransactionWithInputInfo) extends Command
  private case class PublishSignedTx(signedTx: Transaction) extends Command
  private case object Stop extends Command

  sealed trait TxPublishResult extends Command {
    def cmd: TxPublisher.PublishTx
  }
  case class TxPublished(cmd: TxPublisher.PublishTx, txId: ByteVector32) extends TxPublishResult
  sealed trait TxPublishFailed extends TxPublishResult { def message: String }
  case class InsufficientFunds(cmd: TxPublisher.SignAndPublishTx, reason: Throwable) extends TxPublishFailed { override def message: String = s"insufficient funds: ${reason.getMessage}" }
  case class TxSkipped(cmd: TxPublisher.SignAndPublishTx) extends TxPublishFailed { override def message: String = s"tx skipped: ${cmd.txInfo.getClass.getSimpleName}" }
  case class UnknownFailure(cmd: TxPublisher.PublishTx, reason: Throwable) extends TxPublishFailed { override def message: String = s"unknown failure: ${reason.getMessage}" }
  // @formatter:on

  def apply(nodeParams: NodeParams, client: ExtendedBitcoinClient, channelInfo: TxPublisher.ChannelInfo): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(channelInfo.remoteNodeId), channelId_opt = channelInfo.channelId_opt)) {
        new TxPublish(nodeParams, client, context).start()
      }
    }

  /**
   * Adjust the amount of the change output of an anchor tx to match our target feerate.
   * We need this because fundrawtransaction doesn't allow us to leave non-wallet inputs, so we have to add them
   * afterwards which may bring the resulting feerate below our target.
   */
  def adjustAnchorOutputChange(unsignedTx: ClaimLocalAnchorOutputTx, commitTx: Transaction, amountIn: Satoshi, currentFeerate: FeeratePerKw, targetFeerate: FeeratePerKw, dustLimit: Satoshi): ClaimLocalAnchorOutputTx = {
    require(unsignedTx.tx.txOut.size == 1, "funded transaction should have a single change output")
    // We take into account witness weight and adjust the fee to match our desired feerate.
    val dummySignedClaimAnchorTx = addSigs(unsignedTx, PlaceHolderSig)
    // NB: we assume that our bitcoind wallet uses only P2WPKH inputs when funding txs.
    val estimatedWeight = commitTx.weight() + dummySignedClaimAnchorTx.tx.weight() + claimP2WPKHOutputWitnessWeight * (dummySignedClaimAnchorTx.tx.txIn.size - 1)
    val targetFee = weight2fee(targetFeerate, estimatedWeight) - weight2fee(currentFeerate, commitTx.weight())
    val amountOut = dustLimit.max(amountIn - targetFee)
    unsignedTx.copy(tx = unsignedTx.tx.copy(txOut = unsignedTx.tx.txOut.head.copy(amount = amountOut) :: Nil))
  }

  /**
   * Adjust the change output of an htlc tx to match our target feerate.
   * We need this because fundrawtransaction doesn't allow us to leave non-wallet inputs, so we have to add them
   * afterwards which may bring the resulting feerate below our target.
   */
  def adjustHtlcTxChange(unsignedTx: HtlcTx, amountIn: Satoshi, targetFeerate: FeeratePerKw, commitments: Commitments): HtlcTx = {
    require(unsignedTx.tx.txOut.size <= 2, "funded transaction should have at most one change output")
    val dummySignedTx = unsignedTx match {
      case tx: HtlcSuccessTx => addSigs(tx, PlaceHolderSig, PlaceHolderSig, ByteVector32.Zeroes, commitments.commitmentFormat)
      case tx: HtlcTimeoutTx => addSigs(tx, PlaceHolderSig, PlaceHolderSig, commitments.commitmentFormat)
    }
    // We adjust the change output to obtain the targeted feerate.
    val estimatedWeight = dummySignedTx.tx.weight() + claimP2WPKHOutputWitnessWeight * (dummySignedTx.tx.txIn.size - 1)
    val targetFee = weight2fee(targetFeerate, estimatedWeight)
    val changeAmount = amountIn - dummySignedTx.tx.txOut.head.amount - targetFee
    if (dummySignedTx.tx.txOut.length == 2 && changeAmount >= commitments.localParams.dustLimit) {
      unsignedTx match {
        case htlcSuccess: HtlcSuccessTx => htlcSuccess.copy(tx = htlcSuccess.tx.copy(txOut = Seq(htlcSuccess.tx.txOut.head, htlcSuccess.tx.txOut(1).copy(amount = changeAmount))))
        case htlcTimeout: HtlcTimeoutTx => htlcTimeout.copy(tx = htlcTimeout.tx.copy(txOut = Seq(htlcTimeout.tx.txOut.head, htlcTimeout.tx.txOut(1).copy(amount = changeAmount))))
      }
    } else {
      unsignedTx match {
        case htlcSuccess: HtlcSuccessTx => htlcSuccess.copy(tx = htlcSuccess.tx.copy(txOut = Seq(htlcSuccess.tx.txOut.head)))
        case htlcTimeout: HtlcTimeoutTx => htlcTimeout.copy(tx = htlcTimeout.tx.copy(txOut = Seq(htlcTimeout.tx.txOut.head)))
      }
    }
  }

  sealed trait HtlcTxAndWitnessData {
    // @formatter:off
    def txInfo: HtlcTx
    def updateTx(tx: Transaction): HtlcTxAndWitnessData
    def addSigs(localSig: ByteVector64, commitmentFormat: CommitmentFormat): HtlcTx
    // @formatter:on
  }

  object HtlcTxAndWitnessData {

    case class HtlcSuccess(txInfo: HtlcSuccessTx, remoteSig: ByteVector64, preimage: ByteVector32) extends HtlcTxAndWitnessData {
      // @formatter:off
      override def updateTx(tx: Transaction): HtlcTxAndWitnessData = copy(txInfo = txInfo.copy(tx = tx))
      override def addSigs(localSig: ByteVector64, commitmentFormat: CommitmentFormat): HtlcTx = Transactions.addSigs(txInfo, localSig, remoteSig, preimage, commitmentFormat)
      // @formatter:on
    }

    case class HtlcTimeout(txInfo: HtlcTimeoutTx, remoteSig: ByteVector64) extends HtlcTxAndWitnessData {
      // @formatter:off
      override def updateTx(tx: Transaction): HtlcTxAndWitnessData = copy(txInfo = txInfo.copy(tx = tx))
      override def addSigs(localSig: ByteVector64, commitmentFormat: CommitmentFormat): HtlcTx = Transactions.addSigs(txInfo, localSig, remoteSig, commitmentFormat)
      // @formatter:on
    }

    def apply(txInfo: HtlcTx, commitments: Commitments): Option[HtlcTxAndWitnessData] = {
      txInfo match {
        case tx: HtlcSuccessTx =>
          commitments.localChanges.all.collectFirst {
            case u: UpdateFulfillHtlc if Crypto.sha256(u.paymentPreimage) == tx.paymentHash => u.paymentPreimage
          }.flatMap(preimage => {
            commitments.localCommit.publishableTxs.htlcTxsAndSigs.collectFirst {
              case HtlcTxAndSigs(HtlcSuccessTx(input, _, _, _), _, remoteSig) if input.outPoint == tx.input.outPoint => HtlcSuccess(tx, remoteSig, preimage)
            }
          })
        case tx: HtlcTimeoutTx =>
          commitments.localCommit.publishableTxs.htlcTxsAndSigs.collectFirst {
            case HtlcTxAndSigs(HtlcTimeoutTx(input, _, _), _, remoteSig) if input.outPoint == tx.input.outPoint => HtlcTimeout(tx, remoteSig)
          }
      }
    }

  }

}

private class TxPublish(nodeParams: NodeParams, client: ExtendedBitcoinClient, context: ActorContext[TxPublish.Command])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import TxPublish._
  import nodeParams.onChainFeeConf.{feeEstimator, feeTargets}
  import nodeParams.{channelKeyManager => keyManager}

  private val log = context.log

  private def start(): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case cmd: DoPublish =>
        cmd.tx match {
          case p: TxPublisher.PublishRawTx =>
            publishing(cmd, p.tx)
          case p: TxPublisher.SignAndPublishTx =>
            val commitTx = p.commitments.localCommit.publishableTxs.commitTx.tx
            val commitFeerate = p.commitments.localCommit.spec.feeratePerKw
            val targetFeerate = feeEstimator.getFeeratePerKw(feeTargets.commitmentBlockTarget)
            p.txInfo match {
              case _: ClaimLocalAnchorOutputTx =>
                if (targetFeerate <= commitFeerate) {
                  log.info("publishing commit-tx without the anchor (current feerate={}): txid={}", commitFeerate, commitTx.txid)
                  stopping(cmd, TxSkipped(p), None)
                } else {
                  funding(cmd, p, targetFeerate)
                }
              case txInfo: HtlcTx =>
                HtlcTxAndWitnessData(txInfo, p.commitments) match {
                  case Some(txWithWitnessData) =>
                    if (targetFeerate <= commitFeerate) {
                      val channelKeyPath = keyManager.keyPath(p.commitments.localParams, p.commitments.channelVersion)
                      val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, p.commitments.localCommit.index)
                      val localHtlcBasepoint = keyManager.htlcPoint(channelKeyPath)
                      val localSig = keyManager.sign(txInfo, localHtlcBasepoint, localPerCommitmentPoint, TxOwner.Local, p.commitments.commitmentFormat)
                      val signedHtlcTx = txWithWitnessData.addSigs(localSig, p.commitments.commitmentFormat)
                      log.info("publishing {} without adding inputs: txid={}", txInfo.desc, signedHtlcTx.tx.txid)
                      publishing(cmd, signedHtlcTx.tx)
                    } else {
                      funding(cmd, p, targetFeerate)
                    }
                  case None =>
                    log.error("witness data not found for htlcId={}, skipping...", txInfo.htlcId)
                    stopping(cmd, TxSkipped(p), None)
                }
            }
        }
    }
  }

  case object CommitTxAlreadyConfirmed extends RuntimeException

  private def funding(cmd: DoPublish, p: TxPublisher.SignAndPublishTx, targetFeerate: FeeratePerKw): Behavior[Command] = {
    log.info("adding more inputs to {} (input={}:{}, target feerate={})", p.txInfo.desc, p.txInfo.input.outPoint.txid, p.txInfo.input.outPoint.index, targetFeerate)
    p.txInfo match {
      case txInfo: ClaimLocalAnchorOutputTx =>
        // We don't claim our anchor if one of the commit txs has already been confirmed.
        val fundingOutpoint = p.commitments.commitInput.outPoint
        context.pipeToSelf(client.isTransactionOutputSpendable(fundingOutpoint.txid, fundingOutpoint.index.toInt, includeMempool = false).flatMap {
          case false => Future.failed(CommitTxAlreadyConfirmed)
          case true =>
            // We must ensure our local commit tx is in the mempool before publishing the anchor.
            // If it's already published, this call will be a no-op.
            client.publishTransaction(p.commitments.localCommit.publishableTxs.commitTx.tx).flatMap(_ => addInputs(txInfo, targetFeerate, p.commitments))
        }) {
          case Success(fundedTx) => SignFundedTx(fundedTx)
          case Failure(CommitTxAlreadyConfirmed) => TxSkipped(p)
          case Failure(ex) => InsufficientFunds(p, ex)
        }
      case txInfo: HtlcTx =>
        context.pipeToSelf(addInputs(txInfo, targetFeerate, p.commitments)) {
          case Success(fundedTx) => SignFundedTx(fundedTx)
          case Failure(ex) => InsufficientFunds(p, ex)
        }
    }
    Behaviors.receiveMessagePartial {
      case SignFundedTx(fundedTx) =>
        log.info(s"added ${fundedTx.tx.txIn.length - 1} wallet input(s) and ${fundedTx.tx.txOut.length - 1} wallet output(s) to ${fundedTx.desc} spending commit input=${fundedTx.input.outPoint.txid}:${fundedTx.input.outPoint.index}")
        signing(cmd, p, fundedTx)
      case failure: TxPublishFailed =>
        failure match {
          case TxSkipped(_) => log.info("skipping {} (input={}:{}): commit tx is already confirmed", p.txInfo.desc, p.txInfo.input.outPoint.txid, p.txInfo.input.outPoint.index)
          case _ => log.warn("cannot add inputs to {}: reason={} txid={}", p.txInfo.desc, failure.message, p.tx.txid)
        }
        stopping(cmd, failure, None)
    }
  }

  private def signing(cmd: DoPublish, p: TxPublisher.SignAndPublishTx, fundedTx: ReplaceableTransactionWithInputInfo): Behavior[Command] = {
    fundedTx match {
      case claimAnchorTx: ClaimLocalAnchorOutputTx =>
        val claimAnchorSig = keyManager.sign(claimAnchorTx, keyManager.fundingPublicKey(p.commitments.localParams.fundingKeyPath), TxOwner.Local, p.commitments.commitmentFormat)
        val signedClaimAnchorTx = addSigs(claimAnchorTx, claimAnchorSig)
        val commitInfo = ExtendedBitcoinClient.PreviousTx(signedClaimAnchorTx.input, signedClaimAnchorTx.tx.txIn.head.witness)
        context.pipeToSelf(client.signTransaction(signedClaimAnchorTx.tx, Seq(commitInfo))) {
          case Success(signedTx) => PublishSignedTx(signedTx.tx)
          case Failure(ex) => UnknownFailure(p, ex)
        }
      case htlcTx: HtlcTx =>
        HtlcTxAndWitnessData(htlcTx, p.commitments) match {
          case Some(txWithWitnessData) =>
            val channelKeyPath = keyManager.keyPath(p.commitments.localParams, p.commitments.channelVersion)
            val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, p.commitments.localCommit.index)
            val localHtlcBasepoint = keyManager.htlcPoint(channelKeyPath)
            val localSig = keyManager.sign(htlcTx, localHtlcBasepoint, localPerCommitmentPoint, TxOwner.Local, p.commitments.commitmentFormat)
            val signedHtlcTx = txWithWitnessData.addSigs(localSig, p.commitments.commitmentFormat)
            val inputInfo = ExtendedBitcoinClient.PreviousTx(signedHtlcTx.input, signedHtlcTx.tx.txIn.head.witness)
            context.pipeToSelf(client.signTransaction(signedHtlcTx.tx, Seq(inputInfo), allowIncomplete = true).map(signTxResponse => {
              // NB: bitcoind messes up the witness stack for our htlc input, so we need to restore it.
              // See https://github.com/bitcoin/bitcoin/issues/21151
              signedHtlcTx.tx.copy(txIn = signedHtlcTx.tx.txIn.head +: signTxResponse.tx.txIn.tail)
            })) {
              case Success(signedTx) => PublishSignedTx(signedTx)
              case Failure(ex) => UnknownFailure(p, ex)
            }
          case None => context.self ! TxSkipped(p)
        }
    }
    Behaviors.receiveMessagePartial {
      case PublishSignedTx(signedTx) =>
        publishing(cmd, signedTx)
      case failure: TxPublishFailed =>
        log.warn("cannot sign {}: reason={} txid={}", p.txInfo.desc, failure.message, p.tx.txid)
        stopping(cmd, failure, Some(fundedTx.tx))
    }
  }

  private def publishing(cmd: DoPublish, tx: Transaction): Behavior[Command] = {
    val parentTx_opt = cmd.tx match {
      case TxPublisher.PublishRawTx(_, _, parentTx_opt) =>
        log.info("publishing {}: txid={} tx={}", cmd.tx.desc, tx.txid, tx)
        parentTx_opt
      case TxPublisher.SignAndPublishTx(txInfo, _) =>
        log.info("publishing {}: input={}:{} txid={} tx={}", txInfo.desc, txInfo.input.outPoint.txid, txInfo.input.outPoint.index, tx.txid, tx)
        None
    }
    context.pipeToSelf(client.publishTransaction(tx).recoverWith {
      case ex if parentTx_opt.nonEmpty && ex.getMessage.contains("bad-txns-inputs-missingorspent") =>
        // We optimistically published the transaction in parallel with its parent, so we retry after ensuring the parent
        // transaction has been published.
        client.publishTransaction(parentTx_opt.get).flatMap(_ => client.publishTransaction(tx))
    }) {
      case Success(txId) => TxPublished(cmd.tx, txId)
      case Failure(ex) => UnknownFailure(cmd.tx, ex)
    }
    Behaviors.receiveMessagePartial {
      case result: TxPublishResult =>
        result match {
          case f: TxPublishFailed => log.error("cannot publish {}: reason={} txid={}", cmd.tx.desc, f.message, tx.txid)
          case _ => // nothing to do
        }
        stopping(cmd, result, Some(tx))
    }
  }

  private def stopping(cmd: DoPublish, result: TxPublishResult, finalTx_opt: Option[Transaction]): Behavior[Command] = {
    cmd.replyTo ! result
    // We unlock utxos in some failure cases.
    (finalTx_opt, result) match {
      case (Some(finalTx), _: TxPublishFailed) =>
        val toUnlock = finalTx.txIn.map(_.outPoint).diff(cmd.tx.tx.txIn.map(_.outPoint))
        log.debug("unlocking utxos={}", toUnlock.mkString(", "))
        context.pipeToSelf(client.unlockOutpoints(toUnlock))(_ => Stop)
      case _ =>
        context.self ! Stop
    }
    Behaviors.receiveMessagePartial {
      case Stop => Behaviors.stopped
    }
  }

  private def addInputs(txInfo: ClaimLocalAnchorOutputTx, targetFeerate: FeeratePerKw, commitments: Commitments): Future[ClaimLocalAnchorOutputTx] = {
    val dustLimit = commitments.localParams.dustLimit
    val commitFeerate = commitments.localCommit.spec.feeratePerKw
    val commitTx = commitments.localCommit.publishableTxs.commitTx.tx
    // We want the feerate of the package (commit tx + tx spending anchor) to equal targetFeerate.
    // Thus we have: anchorFeerate = targetFeerate + (weight-commit-tx / weight-anchor-tx) * (targetFeerate - commitTxFeerate)
    // If we use the smallest weight possible for the anchor tx, the feerate we use will thus be greater than what we want,
    // and we can adjust it afterwards by raising the change output amount.
    val anchorFeerate = targetFeerate + FeeratePerKw(targetFeerate.feerate - commitFeerate.feerate) * commitTx.weight() / claimAnchorOutputMinWeight
    // NB: fundrawtransaction requires at least one output, and may add at most one additional change output.
    // Since the purpose of this transaction is just to do a CPFP, the resulting tx should have a single change output
    // (note that bitcoind doesn't let us publish a transaction with no outputs).
    // To work around these limitations, we start with a dummy output and later merge that dummy output with the optional
    // change output added by bitcoind.
    // NB: fundrawtransaction doesn't support non-wallet inputs, so we have to remove our anchor input and re-add it later.
    // That means bitcoind will not take our anchor input's weight into account when adding inputs to set the fee.
    // That's ok, we can increase the fee later by decreasing the output amount. But we need to ensure we'll have enough
    // to cover the weight of our anchor input, which is why we set it to the following value.
    val dummyChangeAmount = weight2fee(anchorFeerate, claimAnchorOutputMinWeight) + dustLimit
    val txNotFunded = Transaction(2, Nil, TxOut(dummyChangeAmount, Script.pay2wpkh(PlaceHolderPubKey)) :: Nil, 0)
    client.fundTransaction(txNotFunded, FundTransactionOptions(anchorFeerate, lockUtxos = true)).flatMap(fundTxResponse => {
      // We merge the outputs if there's more than one.
      fundTxResponse.changePosition match {
        case Some(changePos) =>
          val changeOutput = fundTxResponse.tx.txOut(changePos)
          val txSingleOutput = fundTxResponse.tx.copy(txOut = Seq(changeOutput.copy(amount = changeOutput.amount + dummyChangeAmount)))
          Future.successful(fundTxResponse.copy(tx = txSingleOutput))
        case None =>
          client.getChangeAddress().map(pubkeyHash => {
            val txSingleOutput = fundTxResponse.tx.copy(txOut = Seq(TxOut(dummyChangeAmount, Script.pay2wpkh(pubkeyHash))))
            fundTxResponse.copy(tx = txSingleOutput)
          })
      }
    }).map(fundTxResponse => {
      require(fundTxResponse.tx.txOut.size == 1, "funded transaction should have a single change output")
      // NB: we insert the anchor input in the *first* position because our signing helpers only sign input #0.
      val unsignedTx = txInfo.copy(tx = fundTxResponse.tx.copy(txIn = txInfo.tx.txIn.head +: fundTxResponse.tx.txIn))
      adjustAnchorOutputChange(unsignedTx, commitTx, fundTxResponse.amountIn + AnchorOutputsCommitmentFormat.anchorAmount, commitFeerate, targetFeerate, dustLimit)
    })
  }

  private def addInputs(txInfo: HtlcTx, targetFeerate: FeeratePerKw, commitments: Commitments): Future[HtlcTx] = {
    // NB: fundrawtransaction doesn't support non-wallet inputs, so we clear the input and re-add it later.
    val txNotFunded = txInfo.tx.copy(txIn = Nil, txOut = txInfo.tx.txOut.head.copy(amount = commitments.localParams.dustLimit) :: Nil)
    val htlcTxWeight = txInfo match {
      case _: HtlcSuccessTx => commitments.commitmentFormat.htlcSuccessWeight
      case _: HtlcTimeoutTx => commitments.commitmentFormat.htlcTimeoutWeight
    }
    // We want the feerate of our final HTLC tx to equal targetFeerate. However, we removed the HTLC input from what we
    // send to fundrawtransaction, so bitcoind will not know the total weight of the final tx. In order to make up for
    // this difference, we need to tell bitcoind to target a higher feerate that takes into account the weight of the
    // input we removed.
    // That feerate will satisfy the following equality:
    // feerate * weight_seen_by_bitcoind = target_feerate * (weight_seen_by_bitcoind + htlc_input_weight)
    // So: feerate = target_feerate * (1 + htlc_input_weight / weight_seen_by_bitcoind)
    // Because bitcoind will add at least one P2WPKH input, weight_seen_by_bitcoind >= htlc_tx_weight + p2wpkh_weight
    // Thus: feerate <= target_feerate * (1 + htlc_input_weight / (htlc_tx_weight + p2wpkh_weight))
    // NB: we don't take into account the fee paid by our HTLC input: we will take it into account when we adjust the
    // change output amount (unless bitcoind didn't add any change output, in that case we will overpay the fee slightly).
    val weightRatio = 1.0 + (htlcInputMaxWeight.toDouble / (htlcTxWeight + claimP2WPKHOutputWeight))
    client.fundTransaction(txNotFunded, FundTransactionOptions(targetFeerate * weightRatio, lockUtxos = true, changePosition = Some(1))).map(fundTxResponse => {
      // We add the HTLC input (from the commit tx) and restore the HTLC output.
      // NB: we can't modify them because they are signed by our peer (with SIGHASH_SINGLE | SIGHASH_ANYONECANPAY).
      val txWithHtlcInput = fundTxResponse.tx.copy(
        txIn = txInfo.tx.txIn ++ fundTxResponse.tx.txIn,
        txOut = txInfo.tx.txOut ++ fundTxResponse.tx.txOut.tail
      )
      val unsignedTx = txInfo match {
        case htlcSuccess: HtlcSuccessTx => htlcSuccess.copy(tx = txWithHtlcInput)
        case htlcTimeout: HtlcTimeoutTx => htlcTimeout.copy(tx = txWithHtlcInput)
      }
      adjustHtlcTxChange(unsignedTx, fundTxResponse.amountIn + unsignedTx.input.txOut.amount, targetFeerate, commitments)
    })
  }

}
