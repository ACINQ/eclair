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
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Satoshi, Script, Transaction, TxOut}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient.FundTransactionOptions
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{CurrentBlockCount, WatchConfirmed, WatchEvent, WatchEventConfirmed}
import fr.acinq.eclair.transactions.Transactions.{HtlcSuccessTx, HtlcTimeoutTx, TransactionSigningKit, TransactionWithInputInfo}
import fr.acinq.eclair.transactions.{Scripts, Transactions}

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

  case class SetFeerate(currentFeerate: FeeratePerKw, targetFeerate: FeeratePerKw, dustLimit: Satoshi, signingKit: TransactionSigningKit) {
    override def toString = s"SetFeerate(target=$targetFeerate)"
  }

  // @formatter:off
  sealed trait Command
  sealed trait PublishTx extends Command {
    /** Actor that should receive a [[WatchEventConfirmed]] once the transaction has been confirmed. */
    def replyTo: ActorRef[WatchEvent]
    def tx: Transaction
  }
  /**  Publish a fully signed transaction without modifying it. */
  case class PublishRawTx(replyTo: ActorRef[WatchEvent], tx: Transaction) extends PublishTx
  /**
   * Publish an unsigned transaction. Once (csv and cltv) delays have been satisfied, the tx publisher will set the fees,
   * sign the transaction and broadcast it.
   */
  case class SignAndPublishTx(replyTo: ActorRef[WatchEvent], txInfo: TransactionWithInputInfo, setFeerate: SetFeerate) extends PublishTx {
    override def tx: Transaction = txInfo.tx
  }
  case class WrappedCurrentBlockCount(currentBlockCount: Long) extends Command
  case class ParentTxConfirmed(childTx: PublishTx, parentTxId: ByteVector32) extends Command
  private case class PublishNextBlock(p: PublishTx) extends Command
  // @formatter:on

  def apply(nodeParams: NodeParams, watcher: akka.actor.ActorRef, client: ExtendedBitcoinClient): Behavior[Command] =
    Behaviors.setup { context =>
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[CurrentBlockCount](cbc => WrappedCurrentBlockCount(cbc.blockCount)))
      new TxPublisher(nodeParams, watcher, client, context).run(SortedMap.empty, Map.empty)
    }

  /**
   * Adjust the amount of the change output of an anchor tx to match our target feerate.
   * We need this because fundrawtransaction doesn't allow us to leave non-wallet inputs, so we have to add them
   * afterwards which may bring the resulting feerate below our target.
   */
  def adjustAnchorOutputChange(unsignedTx: Transactions.ClaimLocalAnchorOutputTx, commitTx: Transaction, amountIn: Satoshi, currentFeerate: FeeratePerKw, targetFeerate: FeeratePerKw, dustLimit: Satoshi): Transactions.ClaimLocalAnchorOutputTx = {
    require(unsignedTx.tx.txOut.size == 1, "funded transaction should have a single change output")
    // We take into account witness weight and adjust the fee to match our desired feerate.
    val dummySignedClaimAnchorTx = Transactions.addSigs(unsignedTx, Transactions.PlaceHolderSig)
    // NB: we assume that our bitcoind wallet uses only P2WPKH inputs when funding txs.
    val estimatedWeight = commitTx.weight() + dummySignedClaimAnchorTx.tx.weight() + Transactions.claimP2WPKHOutputWitnessWeight * (dummySignedClaimAnchorTx.tx.txIn.size - 1)
    val targetFee = Transactions.weight2fee(targetFeerate, estimatedWeight) - Transactions.weight2fee(currentFeerate, commitTx.weight())
    val amountOut = dustLimit.max(amountIn - targetFee)
    unsignedTx.copy(tx = unsignedTx.tx.copy(txOut = unsignedTx.tx.txOut.head.copy(amount = amountOut) :: Nil))
  }

  def addHtlcTxSigs(unsignedHtlcTx: Transactions.HtlcTx, localSig: ByteVector64, signingKit: TransactionSigningKit.HtlcTxSigningKit): Transactions.HtlcTx = {
    signingKit match {
      case htlcSuccess: TransactionSigningKit.HtlcSuccessSigningKit =>
        Transactions.addSigs(unsignedHtlcTx.asInstanceOf[HtlcSuccessTx], localSig, signingKit.remoteSig, htlcSuccess.preimage, signingKit.commitmentFormat)
      case _: TransactionSigningKit.HtlcTimeoutSigningKit =>
        Transactions.addSigs(unsignedHtlcTx.asInstanceOf[HtlcTimeoutTx], localSig, signingKit.remoteSig, signingKit.commitmentFormat)
    }
  }

  /**
   * Adjust the change output of an htlc tx to match our target feerate.
   * We need this because fundrawtransaction doesn't allow us to leave non-wallet inputs, so we have to add them
   * afterwards which may bring the resulting feerate below our target.
   */
  def adjustHtlcTxChange(unsignedTx: Transactions.HtlcTx, amountIn: Satoshi, targetFeerate: FeeratePerKw, dustLimit: Satoshi, signingKit: TransactionSigningKit.HtlcTxSigningKit): Transactions.HtlcTx = {
    require(unsignedTx.tx.txOut.size <= 2, "funded transaction should have at most one change output")
    val dummySignedTx = addHtlcTxSigs(unsignedTx, Transactions.PlaceHolderSig, signingKit)
    // We adjust the change output to obtain the targeted feerate.
    val estimatedWeight = dummySignedTx.tx.weight() + Transactions.claimP2WPKHOutputWitnessWeight * (dummySignedTx.tx.txIn.size - 1)
    val targetFee = Transactions.weight2fee(targetFeerate, estimatedWeight)
    val changeAmount = amountIn - dummySignedTx.tx.txOut.head.amount - targetFee
    if (dummySignedTx.tx.txOut.length == 2 && changeAmount >= dustLimit) {
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

}

private class TxPublisher(nodeParams: NodeParams, watcher: akka.actor.ActorRef, client: ExtendedBitcoinClient, context: ActorContext[TxPublisher.Command]) {

  import TxPublisher._

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
    }

  implicit val ec: ExecutionContext = ExecutionContext.global

  // NOTE: we use a single thread to publish transactions so that it preserves order.
  // CHANGING THIS WILL RESULT IN CONCURRENCY ISSUES WHILE PUBLISHING PARENT AND CHILD TXS
  val singleThreadExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  private def publish(p: PublishTx): Future[ByteVector32] = {
    p match {
      case SignAndPublishTx(_, _, SetFeerate(currentFeerate, targetFeerate, dustLimit, signingKit)) =>
        log.info("publishing tx: input={}:{} txid={} tx={}", signingKit.spentOutpoint.txid, signingKit.spentOutpoint.index, p.tx.txid, p.tx)
        val publishF = signingKit match {
          case signingKit: TransactionSigningKit.ClaimAnchorOutputSigningKit => publishCommitWithAnchor(p.tx, currentFeerate, targetFeerate, dustLimit, signingKit)
          case signingKit: TransactionSigningKit.HtlcTxSigningKit => publishHtlcTx(currentFeerate, targetFeerate, dustLimit, signingKit)
        }
        publishF.recoverWith {
          case t: Throwable if t.getMessage.contains("(code: -4)") || t.getMessage.contains("(code: -6)") =>
            log.warn("not enough funds to publish tx, will retry next block: reason={} input={}:{} txid={}", t.getMessage, signingKit.spentOutpoint.txid, signingKit.spentOutpoint.index, p.tx.txid)
            context.self ! PublishNextBlock(p)
            Future.failed(t)
          case t: Throwable =>
            log.error("cannot publish tx: reason={} input={}:{} txid={}", t.getMessage, signingKit.spentOutpoint.txid, signingKit.spentOutpoint.index, p.tx.txid)
            Future.failed(t)
        }
      case PublishRawTx(_, tx) =>
        log.info("publishing tx: txid={} tx={}", tx.txid, tx)
        publish(tx)
    }
  }

  private def publish(tx: Transaction): Future[ByteVector32] = {
    client.publishTransaction(tx)(singleThreadExecutionContext).recoverWith {
      case t: Throwable =>
        log.error("cannot publish tx: reason={} txid={}", t.getMessage, tx.txid)
        Future.failed(t)
    }
  }

  /**
   * Publish the commit tx, and optionally an anchor tx that spends from the commit tx and helps get it confirmed with CPFP.
   */
  private def publishCommitWithAnchor(commitTx: Transaction, currentFeerate: FeeratePerKw, targetFeerate: FeeratePerKw, dustLimit: Satoshi, signingKit: TransactionSigningKit.ClaimAnchorOutputSigningKit): Future[ByteVector32] = {
    import signingKit._
    if (targetFeerate <= currentFeerate) {
      log.info(s"publishing commit tx without the anchor (current feerate=$currentFeerate): txid=${commitTx.txid}")
      publish(commitTx)
    } else {
      log.info(s"publishing commit tx with the anchor (target feerate=$targetFeerate): txid=${commitTx.txid}")
      // We want the feerate of the package (commit tx + tx spending anchor) to equal targetFeerate.
      // Thus we have: anchorFeerate = targetFeerate + (weight-commit-tx / weight-anchor-tx) * (targetFeerate - commitTxFeerate)
      // If we use the smallest weight possible for the anchor tx, the feerate we use will thus be greater than what we want,
      // and we can adjust it afterwards by raising the change output amount.
      val anchorFeerate = targetFeerate + FeeratePerKw(targetFeerate.feerate - currentFeerate.feerate) * commitTx.weight() / Transactions.claimAnchorOutputMinWeight
      // NB: fundrawtransaction requires at least one output, and may add at most one additional change output.
      // Since the purpose of this transaction is just to do a CPFP, the resulting tx should have a single change output
      // (note that bitcoind doesn't let us publish a transaction with no outputs).
      // To work around these limitations, we start with a dummy output and later merge that dummy output with the optional
      // change output added by bitcoind.
      // NB: fundrawtransaction doesn't support non-wallet inputs, so we have to remove our anchor input and re-add it later.
      // That means bitcoind will not take our anchor input's weight into account when adding inputs to set the fee.
      // That's ok, we can increase the fee later by decreasing the output amount. But we need to ensure we'll have enough
      // to cover the weight of our anchor input, which is why we set it to the following value.
      val dummyChangeAmount = Transactions.weight2fee(anchorFeerate, Transactions.claimAnchorOutputMinWeight) + dustLimit
      publish(commitTx).flatMap(_ => {
        val txNotFunded = Transaction(2, Nil, TxOut(dummyChangeAmount, Script.pay2wpkh(Transactions.PlaceHolderPubKey)) :: Nil, 0)
        client.fundTransaction(txNotFunded, FundTransactionOptions(anchorFeerate, lockUtxos = true))(singleThreadExecutionContext)
      }).flatMap(fundTxResponse => {
        // We merge the outputs if there's more than one.
        fundTxResponse.changePosition match {
          case Some(changePos) =>
            val changeOutput = fundTxResponse.tx.txOut(changePos.toInt)
            val txSingleOutput = fundTxResponse.tx.copy(txOut = Seq(changeOutput.copy(amount = changeOutput.amount + dummyChangeAmount)))
            Future.successful(fundTxResponse.copy(tx = txSingleOutput))
          case None =>
            client.getChangeAddress()(singleThreadExecutionContext).map(pubkeyHash => {
              val txSingleOutput = fundTxResponse.tx.copy(txOut = Seq(TxOut(dummyChangeAmount, Script.pay2wpkh(pubkeyHash))))
              fundTxResponse.copy(tx = txSingleOutput)
            })
        }
      }).map(fundTxResponse => {
        require(fundTxResponse.tx.txOut.size == 1, "funded transaction should have a single change output")
        // NB: we insert the anchor input in the *first* position because our signing helpers only sign input #0.
        val unsignedTx = txWithInput.copy(tx = fundTxResponse.tx.copy(txIn = txWithInput.tx.txIn.head +: fundTxResponse.tx.txIn))
        adjustAnchorOutputChange(unsignedTx, commitTx, fundTxResponse.amountIn + Transactions.AnchorOutputsCommitmentFormat.anchorAmount, currentFeerate, targetFeerate, dustLimit)
      }).flatMap(claimAnchorTx => {
        val claimAnchorSig = keyManager.sign(claimAnchorTx, localFundingPubKey, Transactions.TxOwner.Local, commitmentFormat)
        val signedClaimAnchorTx = Transactions.addSigs(claimAnchorTx, claimAnchorSig)
        val commitInfo = ExtendedBitcoinClient.PreviousTx(signedClaimAnchorTx.input, signedClaimAnchorTx.tx.txIn.head.witness)
        client.signTransaction(signedClaimAnchorTx.tx, Seq(commitInfo))(singleThreadExecutionContext)
      }).flatMap(signTxResponse => {
        client.publishTransaction(signTxResponse.tx)(singleThreadExecutionContext)
      })
    }
  }

  /**
   * Publish an htlc tx, and optionally RBF it before by adding new inputs/outputs to help get it confirmed.
   */
  private def publishHtlcTx(currentFeerate: FeeratePerKw, targetFeerate: FeeratePerKw, dustLimit: Satoshi, signingKit: TransactionSigningKit.HtlcTxSigningKit): Future[ByteVector32] = {
    import signingKit._
    if (targetFeerate <= currentFeerate) {
      val localSig = keyManager.sign(txWithInput, localHtlcBasepoint, localPerCommitmentPoint, Transactions.TxOwner.Local, commitmentFormat)
      val signedHtlcTx = addHtlcTxSigs(txWithInput, localSig, signingKit)
      log.info("publishing htlc tx without adding inputs: txid={}", signedHtlcTx.tx.txid)
      client.publishTransaction(signedHtlcTx.tx)(singleThreadExecutionContext)
    } else {
      log.info("publishing htlc tx with additional inputs: commit input={}:{} target feerate={}", txWithInput.input.outPoint.txid, txWithInput.input.outPoint.index, targetFeerate)
      // NB: fundrawtransaction doesn't support non-wallet inputs, so we clear the input and re-add it later.
      val txNotFunded = txWithInput.tx.copy(txIn = Nil, txOut = txWithInput.tx.txOut.head.copy(amount = dustLimit) :: Nil)
      val htlcTxWeight = signingKit match {
        case _: TransactionSigningKit.HtlcSuccessSigningKit => commitmentFormat.htlcSuccessWeight
        case _: TransactionSigningKit.HtlcTimeoutSigningKit => commitmentFormat.htlcTimeoutWeight
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
      val weightRatio = 1.0 + (Transactions.htlcInputMaxWeight.toDouble / (htlcTxWeight + Transactions.claimP2WPKHOutputWeight))
      client.fundTransaction(txNotFunded, FundTransactionOptions(targetFeerate * weightRatio, lockUtxos = true, changePosition = Some(1)))(singleThreadExecutionContext).map(fundTxResponse => {
        log.info(s"added ${fundTxResponse.tx.txIn.length} wallet input(s) and ${fundTxResponse.tx.txOut.length - 1} wallet output(s) to htlc tx spending commit input=${txWithInput.input.outPoint.txid}:${txWithInput.input.outPoint.index}")
        // We add the HTLC input (from the commit tx) and restore the HTLC output.
        // NB: we can't modify them because they are signed by our peer (with SIGHASH_SINGLE | SIGHASH_ANYONECANPAY).
        val txWithHtlcInput = fundTxResponse.tx.copy(
          txIn = txWithInput.tx.txIn ++ fundTxResponse.tx.txIn,
          txOut = txWithInput.tx.txOut ++ fundTxResponse.tx.txOut.tail
        )
        val unsignedTx = signingKit match {
          case htlcSuccess: TransactionSigningKit.HtlcSuccessSigningKit => htlcSuccess.txWithInput.copy(tx = txWithHtlcInput)
          case htlcTimeout: TransactionSigningKit.HtlcTimeoutSigningKit => htlcTimeout.txWithInput.copy(tx = txWithHtlcInput)
        }
        adjustHtlcTxChange(unsignedTx, fundTxResponse.amountIn + unsignedTx.input.txOut.amount, targetFeerate, dustLimit, signingKit)
      }).flatMap(unsignedTx => {
        val localSig = keyManager.sign(unsignedTx, localHtlcBasepoint, localPerCommitmentPoint, Transactions.TxOwner.Local, commitmentFormat)
        val signedHtlcTx = addHtlcTxSigs(unsignedTx, localSig, signingKit)
        val inputInfo = ExtendedBitcoinClient.PreviousTx(signedHtlcTx.input, signedHtlcTx.tx.txIn.head.witness)
        client.signTransaction(signedHtlcTx.tx, Seq(inputInfo), allowIncomplete = true)(singleThreadExecutionContext).flatMap(signTxResponse => {
          // NB: bitcoind messes up the witness stack for our htlc input, so we need to restore it.
          // See https://github.com/bitcoin/bitcoin/issues/21151
          val completeTx = signedHtlcTx.tx.copy(txIn = signedHtlcTx.tx.txIn.head +: signTxResponse.tx.txIn.tail)
          log.info("publishing bumped htlc tx: commit input={}:{} txid={} tx={}", txWithInput.input.outPoint.txid, txWithInput.input.outPoint.index, completeTx.txid, completeTx)
          client.publishTransaction(completeTx)(singleThreadExecutionContext)
        })
      })
    }
  }

}
