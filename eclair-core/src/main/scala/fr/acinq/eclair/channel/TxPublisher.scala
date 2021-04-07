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
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, Satoshi, Script, Transaction, TxOut}
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient.FundTransactionOptions
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{CurrentBlockCount, WatchConfirmed, WatchEventConfirmed}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.wire.protocol.UpdateFulfillHtlc
import fr.acinq.eclair.{Logs, NodeParams}

import java.util.concurrent.Executors
import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}

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
  }
  /**  Publish a fully signed transaction without modifying it. */
  case class PublishRawTx(tx: Transaction) extends PublishTx
  /**
   * Publish an unsigned transaction. Once (csv and cltv) delays have been satisfied, the tx publisher will set the fees,
   * sign the transaction and broadcast it.
   */
  case class SignAndPublishTx(txInfo: TransactionWithInputInfo, commitments: Commitments) extends PublishTx {
    override def tx: Transaction = txInfo.tx
  }
  case class WrappedCurrentBlockCount(currentBlockCount: Long) extends Command
  case class ParentTxConfirmed(childTx: PublishTx, parentTxId: ByteVector32) extends Command
  private case class PublishNextBlock(p: PublishTx) extends Command
  case class SetChannelId(channelId: ByteVector32, remoteNodeId: PublicKey) extends Command
  // @formatter:on

  // NOTE: we use a single thread to publish transactions so that it preserves order.
  // CHANGING THIS WILL RESULT IN CONCURRENCY ISSUES WHILE PUBLISHING PARENT AND CHILD TXS!
  val singleThreadExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  def apply(nodeParams: NodeParams, remoteNodeId: PublicKey, watcher: akka.actor.ActorRef, client: ExtendedBitcoinClient): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))) {
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[CurrentBlockCount](cbc => WrappedCurrentBlockCount(cbc.blockCount)))
        new TxPublisher(nodeParams, watcher, client, context).run(SortedMap.empty, Map.empty)
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

private class TxPublisher(nodeParams: NodeParams, watcher: akka.actor.ActorRef, client: ExtendedBitcoinClient, context: ActorContext[TxPublisher.Command])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import TxPublisher._
  import nodeParams.onChainFeeConf.{feeEstimator, feeTargets}
  import nodeParams.{channelKeyManager => keyManager}

  private case class TxWithRelativeDelay(childTx: PublishTx, parentTxIds: Set[ByteVector32])

  val log = context.log

  val watchConfirmedResponseMapper: ActorRef[WatchEventConfirmed] = context.messageAdapter(w => w.event match {
    case BITCOIN_PARENT_TX_CONFIRMED(childTx) => ParentTxConfirmed(childTx, w.tx.txid)
  })

  /**
   * @param cltvDelayedTxs when transactions are cltv-delayed, we wait until the target blockchain height is reached.
   * @param csvDelayedTxs  when transactions are csv-delayed, we wait for all parent txs to have enough confirmations.
   */
  private def run(cltvDelayedTxs: SortedMap[Long, Seq[PublishTx]], csvDelayedTxs: Map[ByteVector32, TxWithRelativeDelay]): Behavior[Command] =
    Behaviors.receiveMessage {
      case p: PublishTx =>
        val blockCount = nodeParams.currentBlockHeight
        val cltvTimeout = Scripts.cltvTimeout(p.tx)
        val csvTimeouts = Scripts.csvTimeouts(p.tx)
        if (csvTimeouts.nonEmpty) {
          csvTimeouts.foreach {
            case (parentTxId, csvTimeout) =>
              log.info(s"txid=${p.tx.txid} has a relative timeout of $csvTimeout blocks, watching parentTxId=$parentTxId tx={}", p.tx)
              watcher ! WatchConfirmed(watchConfirmedResponseMapper.toClassic, parentTxId, minDepth = csvTimeout, BITCOIN_PARENT_TX_CONFIRMED(p))
          }
          run(cltvDelayedTxs, csvDelayedTxs + (p.tx.txid -> TxWithRelativeDelay(p, csvTimeouts.keySet)))
        } else if (cltvTimeout > blockCount) {
          log.info(s"delaying publication of txid=${p.tx.txid} until block=$cltvTimeout (current block=$blockCount)")
          val cltvDelayedTxs1 = cltvDelayedTxs + (cltvTimeout -> (cltvDelayedTxs.getOrElse(cltvTimeout, Seq.empty) :+ p))
          run(cltvDelayedTxs1, csvDelayedTxs)
        } else {
          publish(p)
          Behaviors.same
        }

      case ParentTxConfirmed(p, parentTxId) =>
        log.info(s"parent tx of txid=${p.tx.txid} has been confirmed (parent txid=$parentTxId)")
        val blockCount = nodeParams.currentBlockHeight
        csvDelayedTxs.get(p.tx.txid) match {
          case Some(TxWithRelativeDelay(_, parentTxIds)) =>
            val txWithRelativeDelay1 = TxWithRelativeDelay(p, parentTxIds - parentTxId)
            if (txWithRelativeDelay1.parentTxIds.isEmpty) {
              log.info(s"all parent txs of txid=${p.tx.txid} have been confirmed")
              val csvDelayedTx1 = csvDelayedTxs - p.tx.txid
              val cltvTimeout = Scripts.cltvTimeout(p.tx)
              if (cltvTimeout > blockCount) {
                log.info(s"delaying publication of txid=${p.tx.txid} until block=$cltvTimeout (current block=$blockCount)")
                val cltvDelayedTxs1 = cltvDelayedTxs + (cltvTimeout -> (cltvDelayedTxs.getOrElse(cltvTimeout, Seq.empty) :+ p))
                run(cltvDelayedTxs1, csvDelayedTx1)
              } else {
                publish(p)
                run(cltvDelayedTxs, csvDelayedTx1)
              }
            } else {
              log.info(s"some parent txs of txid=${p.tx.txid} are still unconfirmed (parent txids=${txWithRelativeDelay1.parentTxIds.mkString(",")})")
              run(cltvDelayedTxs, csvDelayedTxs + (p.tx.txid -> txWithRelativeDelay1))
            }
          case None =>
            log.warn(s"txid=${p.tx.txid} not found for parent txid=$parentTxId")
            Behaviors.same
        }

      case WrappedCurrentBlockCount(blockCount) =>
        val toPublish = cltvDelayedTxs.view.filterKeys(_ <= blockCount)
        toPublish.values.flatten.foreach(tx => publish(tx))
        run(cltvDelayedTxs -- toPublish.keys, csvDelayedTxs)

      case PublishNextBlock(p) =>
        val nextBlockCount = nodeParams.currentBlockHeight + 1
        val cltvDelayedTxs1 = cltvDelayedTxs + (nextBlockCount -> (cltvDelayedTxs.getOrElse(nextBlockCount, Seq.empty) :+ p))
        run(cltvDelayedTxs1, csvDelayedTxs)

      case SetChannelId(channelId, remoteNodeId) =>
        Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId), channelId_opt = Some(channelId))) {
          run(cltvDelayedTxs, csvDelayedTxs)
        }
    }

  private def publish(p: PublishTx): Future[ByteVector32] = {
    p match {
      case SignAndPublishTx(txInfo, commitments) =>
        log.info("publishing tx: input={}:{} txid={} tx={}", txInfo.input.outPoint.txid, txInfo.input.outPoint.index, p.tx.txid, p.tx)
        val publishF = txInfo match {
          case tx: ClaimLocalAnchorOutputTx => publishLocalAnchorTx(tx, commitments)
          case tx: HtlcTx => publishHtlcTx(tx, commitments)
          case _ =>
            log.error(s"ignoring unhandled transaction type ${txInfo.getClass.getSimpleName}")
            Future.successful(ByteVector32.Zeroes)
        }
        publishF.recoverWith {
          case t: Throwable if t.getMessage.contains("(code: -4)") || t.getMessage.contains("(code: -6)") =>
            log.warn("not enough funds to publish tx, will retry next block: reason={} input={}:{} txid={}", t.getMessage, txInfo.input.outPoint.txid, txInfo.input.outPoint.index, p.tx.txid)
            context.self ! PublishNextBlock(p)
            Future.failed(t)
          case t: Throwable =>
            log.error("cannot publish tx: reason={} input={}:{} txid={}", t.getMessage, txInfo.input.outPoint.txid, txInfo.input.outPoint.index, p.tx.txid)
            Future.failed(t)
        }
      case PublishRawTx(tx) =>
        log.info("publishing tx: txid={} tx={}", tx.txid, tx)
        publish(tx)
    }
  }

  /**
   * This method uses a single thread to publish transactions so that it preserves the order of publication.
   * We need that to prevent concurrency issues while publishing parent and child transactions.
   */
  private def publish(tx: Transaction): Future[ByteVector32] = {
    client.publishTransaction(tx)(singleThreadExecutionContext).recoverWith {
      case t: Throwable =>
        log.error("cannot publish tx: reason={} txid={}", t.getMessage, tx.txid)
        Future.failed(t)
    }
  }

  /**
   * Publish an anchor tx that spends from the commit tx and helps get it confirmed with CPFP (if the commit tx feerate
   * was too low).
   */
  private def publishLocalAnchorTx(txInfo: ClaimLocalAnchorOutputTx, commitments: Commitments): Future[ByteVector32] = {
    val commitFeerate = commitments.localCommit.spec.feeratePerKw
    val commitTx = commitments.localCommit.publishableTxs.commitTx.tx
    val targetFeerate = feeEstimator.getFeeratePerKw(feeTargets.commitmentBlockTarget)
    if (targetFeerate <= commitFeerate) {
      log.info(s"publishing commit tx without the anchor (current feerate=$commitFeerate): txid=${commitTx.txid}")
      Future.successful(commitTx.txid)
    } else {
      log.info(s"bumping commit tx with the anchor (target feerate=$targetFeerate): txid=${commitTx.txid}")
      addInputs(txInfo, targetFeerate, commitments).flatMap(claimAnchorTx => {
        val claimAnchorSig = keyManager.sign(claimAnchorTx, keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath), TxOwner.Local, commitments.commitmentFormat)
        val signedClaimAnchorTx = addSigs(claimAnchorTx, claimAnchorSig)
        val commitInfo = ExtendedBitcoinClient.PreviousTx(signedClaimAnchorTx.input, signedClaimAnchorTx.tx.txIn.head.witness)
        client.signTransaction(signedClaimAnchorTx.tx, Seq(commitInfo))
      }).flatMap(signTxResponse => {
        publish(signTxResponse.tx)
      })
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
          val changeOutput = fundTxResponse.tx.txOut(changePos.toInt)
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

  /**
   * Publish an htlc tx, and optionally RBF it before by adding new inputs/outputs to help get it confirmed.
   */
  private def publishHtlcTx(txInfo: HtlcTx, commitments: Commitments): Future[ByteVector32] = {
    val currentFeerate = commitments.localCommit.spec.feeratePerKw
    val targetFeerate = feeEstimator.getFeeratePerKw(feeTargets.commitmentBlockTarget)
    val channelKeyPath = keyManager.keyPath(commitments.localParams, commitments.channelVersion)
    val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, commitments.localCommit.index)
    val localHtlcBasepoint = keyManager.htlcPoint(channelKeyPath)
    HtlcTxAndWitnessData(txInfo, commitments) match {
      case Some(txWithWitnessData) =>
        if (targetFeerate <= currentFeerate) {
          val localSig = keyManager.sign(txInfo, localHtlcBasepoint, localPerCommitmentPoint, TxOwner.Local, commitments.commitmentFormat)
          val signedHtlcTx = txWithWitnessData.addSigs(localSig, commitments.commitmentFormat)
          log.info("publishing htlc tx without adding inputs: txid={}", signedHtlcTx.tx.txid)
          publish(signedHtlcTx.tx)
        } else {
          log.info("publishing htlc tx with additional inputs: commit input={}:{} target feerate={}", txInfo.input.outPoint.txid, txInfo.input.outPoint.index, targetFeerate)
          addInputs(txInfo, targetFeerate, commitments).flatMap(unsignedTx => {
            val localSig = keyManager.sign(unsignedTx, localHtlcBasepoint, localPerCommitmentPoint, TxOwner.Local, commitments.commitmentFormat)
            val signedHtlcTx = txWithWitnessData.updateTx(unsignedTx.tx).addSigs(localSig, commitments.commitmentFormat)
            val inputInfo = ExtendedBitcoinClient.PreviousTx(signedHtlcTx.input, signedHtlcTx.tx.txIn.head.witness)
            client.signTransaction(signedHtlcTx.tx, Seq(inputInfo), allowIncomplete = true).flatMap(signTxResponse => {
              // NB: bitcoind messes up the witness stack for our htlc input, so we need to restore it.
              // See https://github.com/bitcoin/bitcoin/issues/21151
              val completeTx = signedHtlcTx.tx.copy(txIn = signedHtlcTx.tx.txIn.head +: signTxResponse.tx.txIn.tail)
              log.info("publishing bumped htlc tx: commit input={}:{} txid={} tx={}", txInfo.input.outPoint.txid, txInfo.input.outPoint.index, completeTx.txid, completeTx)
              publish(completeTx)
            })
          })
        }
      case None =>
        Future.failed(new IllegalArgumentException(s"witness data not found for htlcId=${txInfo.htlcId}, skipping..."))
    }
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
      log.info(s"added ${fundTxResponse.tx.txIn.length} wallet input(s) and ${fundTxResponse.tx.txOut.length - 1} wallet output(s) to htlc tx spending commit input=${txInfo.input.outPoint.txid}:${txInfo.input.outPoint.index}")
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
