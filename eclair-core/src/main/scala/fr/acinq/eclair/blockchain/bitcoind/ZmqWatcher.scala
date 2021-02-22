/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.blockchain.bitcoind

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.ClassicActorContextOps
import akka.actor.{Actor, ActorLogging, Cancellable, Props, Terminated}
import akka.pattern.pipe
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPublicKey
import fr.acinq.bitcoin._
import fr.acinq.eclair.KamonExt
import fr.acinq.eclair.blockchain.Monitoring.Metrics
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient.{FundTransactionOptions, FundTransactionResponse}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog
import fr.acinq.eclair.channel.{BITCOIN_PARENT_TX_CONFIRMED, Commitments}
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.transactions.Transactions.{HtlcSuccessTx, HtlcTimeoutTx, TransactionSigningKit, TransactionWithInputInfo, weight2fee}
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import org.json4s.JsonAST.{JArray, JBool, JDecimal, JInt, JString}
import scodec.bits.ByteVector

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * Created by PM on 21/02/2016.
 */

/**
 * A blockchain watcher that:
 *  - receives bitcoin events (new blocks and new txs) directly from the bitcoin network
 *  - also uses bitcoin-core rpc api, most notably for tx confirmation count and block count (because reorgs)
 */
class ZmqWatcher(chainHash: ByteVector32, blockCount: AtomicLong, client: ExtendedBitcoinClient)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  import ZmqWatcher._

  context.system.eventStream.subscribe(self, classOf[NewBlock])
  context.system.eventStream.subscribe(self, classOf[NewTransaction])
  context.system.eventStream.subscribe(self, classOf[CurrentBlockCount])

  private val watchdog = context.spawn(Behaviors.supervise(BlockchainWatchdog(chainHash, 150 seconds)).onFailure(SupervisorStrategy.resume), "blockchain-watchdog")

  // this is to initialize block count
  self ! TickNewBlock

  // @formatter:off
  private case class PublishNextBlock(p: PublishAsap)
  private case class TriggerEvent(w: Watch, e: WatchEvent)

  private sealed trait AddWatchResult
  private case object Keep extends AddWatchResult
  private case object Ignore extends AddWatchResult
  // @formatter:on

  def receive: Receive = watching(Set(), Map(), SortedMap(), None)

  def watching(watches: Set[Watch], watchedUtxos: Map[OutPoint, Set[Watch]], block2tx: SortedMap[Long, Seq[PublishAsap]], nextTick: Option[Cancellable]): Receive = {

    case NewTransaction(tx) =>
      log.debug("analyzing txid={} tx={}", tx.txid, tx)
      tx.txIn
        .map(_.outPoint)
        .flatMap(watchedUtxos.get)
        .flatten // List[Watch] -> Watch
        .collect {
          case w: WatchSpentBasic =>
            self ! TriggerEvent(w, WatchEventSpentBasic(w.event))
          case w: WatchSpent =>
            self ! TriggerEvent(w, WatchEventSpent(w.event, tx))
        }

    case NewBlock(block) =>
      // using a Try because in tests we generate fake blocks
      log.debug("received blockid={}", Try(block.blockId).getOrElse(ByteVector32(ByteVector.empty)))
      nextTick.map(_.cancel()) // this may fail or succeed, worse case scenario we will have two ticks in a row (no big deal)
      log.debug("scheduling a new task to check on tx confirmations")
      // we do this to avoid herd effects in testing when generating a lots of blocks in a row
      val task = context.system.scheduler.scheduleOnce(2 seconds, self, TickNewBlock)
      context become watching(watches, watchedUtxos, block2tx, Some(task))

    case TickNewBlock =>
      client.getBlockCount.map {
        count =>
          log.debug("setting blockCount={}", count)
          blockCount.set(count)
          context.system.eventStream.publish(CurrentBlockCount(count))
      }
      checkUtxos()
      // TODO: beware of the herd effect
      KamonExt.timeFuture(Metrics.NewBlockCheckConfirmedDuration.withoutTags()) {
        Future.sequence(watches.collect { case w: WatchConfirmed => checkConfirmed(w) })
      }
      context become watching(watches, watchedUtxos, block2tx, None)

    case TriggerEvent(w, e) if watches.contains(w) =>
      log.info("triggering {}", w)
      w.replyTo ! e
      w match {
        case _: WatchSpent =>
          // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx or the commit tx
          // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
          ()
        case _ =>
          context become watching(watches - w, removeWatchedUtxos(watchedUtxos, w), block2tx, nextTick)
      }

    case CurrentBlockCount(count) =>
      val toPublish = block2tx.filterKeys(_ <= count)
      toPublish.values.flatten.foreach(tx => publish(tx))
      context become watching(watches, watchedUtxos, block2tx -- toPublish.keys, nextTick)

    case w: Watch =>

      val result = w match {
        case _ if watches.contains(w) => Ignore // we ignore duplicates

        case WatchSpentBasic(_, txid, outputIndex, _, _) =>
          // NB: we assume parent tx was published, we just need to make sure this particular output has not been spent
          client.isTransactionOutputSpendable(txid, outputIndex, includeMempool = true).collect {
            case false =>
              log.info(s"output=$outputIndex of txid=$txid has already been spent")
              self ! TriggerEvent(w, WatchEventSpentBasic(w.event))
          }
          Keep

        case WatchSpent(_, txid, outputIndex, _, _, hints) =>
          // first let's see if the parent tx was published or not
          client.getTxConfirmations(txid).collect {
            case Some(_) =>
              // parent tx was published, we need to make sure this particular output has not been spent
              client.isTransactionOutputSpendable(txid, outputIndex, includeMempool = true).collect {
                case false =>
                  // the output has been spent, let's find the spending tx
                  // if we know some potential spending txs, we try to fetch them directly
                  Future.sequence(hints.map(txid => client.getTransaction(txid).map(Some(_)).recover { case _ => None }))
                    .map(_
                      .flatten // filter out errors
                      .find(tx => tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex)) match {
                      case Some(spendingTx) =>
                        // there can be only one spending tx for an utxo
                        log.info(s"$txid:$outputIndex has already been spent by a tx provided in hints: txid=${spendingTx.txid}")
                        self ! NewTransaction(spendingTx)
                      case None =>
                        // no luck, we have to do it the hard way...
                        log.info(s"$txid:$outputIndex has already been spent, looking for the spending tx in the mempool")
                        client.getMempool().map { mempoolTxs =>
                          mempoolTxs.filter(tx => tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex)) match {
                            case Nil =>
                              log.warning(s"$txid:$outputIndex has already been spent, spending tx not in the mempool, looking in the blockchain...")
                              client.lookForSpendingTx(None, txid, outputIndex).map { tx =>
                                log.warning(s"found the spending tx of $txid:$outputIndex in the blockchain: txid=${tx.txid}")
                                self ! NewTransaction(tx)
                              }
                            case txs =>
                              log.info(s"found ${txs.size} txs spending $txid:$outputIndex in the mempool: txids=${txs.map(_.txid).mkString(",")}")
                              txs.foreach(tx => self ! NewTransaction(tx))
                          }
                        }
                    })
              }
          }
          Keep

        case w: WatchConfirmed =>
          checkConfirmed(w) // maybe the tx is already confirmed, in that case the watch will be triggered and removed immediately
          Keep

        case _: WatchLost => Ignore // TODO: not implemented, we ignore it silently
      }

      result match {
        case Keep =>
          log.debug("adding watch {} for {}", w, sender)
          context.watch(w.replyTo)
          context become watching(watches + w, addWatchedUtxos(watchedUtxos, w), block2tx, nextTick)
        case Ignore => ()
      }

    case p@PublishAsap(tx, _) =>
      val blockCount = this.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeouts = Scripts.csvTimeouts(tx)
      if (csvTimeouts.nonEmpty) {
        // watcher supports txs with multiple csv-delayed inputs: we watch all delayed parents and try to publish every
        // time a parent's relative delays are satisfied, so we will eventually succeed.
        csvTimeouts.foreach { case (parentTxId, csvTimeout) =>
          log.info(s"txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parentTxId=$parentTxId tx={}", tx)
          val parentPublicKeyScript = Script.write(Script.pay2wsh(tx.txIn.find(_.outPoint.txid == parentTxId).get.witness.stack.last))
          self ! WatchConfirmed(self, parentTxId, parentPublicKeyScript, minDepth = csvTimeout, BITCOIN_PARENT_TX_CONFIRMED(p))
        }
      } else if (cltvTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(cltvTimeout, block2tx.getOrElse(cltvTimeout, Seq.empty[PublishAsap]) :+ p)
        context become watching(watches, watchedUtxos, block2tx1, nextTick)
      } else publish(p)

    case WatchEventConfirmed(BITCOIN_PARENT_TX_CONFIRMED(p@PublishAsap(tx, _)), _, _, _) =>
      log.info(s"parent tx of txid=${tx.txid} has been confirmed")
      val blockCount = this.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      if (cltvTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(cltvTimeout, block2tx.getOrElse(cltvTimeout, Seq.empty[PublishAsap]) :+ p)
        context become watching(watches, watchedUtxos, block2tx1, nextTick)
      } else publish(p)

    case PublishNextBlock(p) =>
      val nextBlockCount = this.blockCount.get() + 1
      val block2tx1 = block2tx.updated(nextBlockCount, block2tx.getOrElse(nextBlockCount, Seq.empty[PublishAsap]) :+ p)
      context become watching(watches, watchedUtxos, block2tx1, nextTick)

    case ValidateRequest(ann) => client.validate(ann).pipeTo(sender)

    case GetTxWithMeta(txid) => client.getTransactionMeta(txid).pipeTo(sender)

    case Terminated(actor) =>
      // we remove watches associated to dead actor
      val deprecatedWatches = watches.filter(_.replyTo == actor)
      val watchedUtxos1 = deprecatedWatches.foldLeft(watchedUtxos) { case (m, w) => removeWatchedUtxos(m, w) }
      context.become(watching(watches -- deprecatedWatches, watchedUtxos1, block2tx, nextTick))

    case Symbol("watches") => sender ! watches

  }

  // NOTE: we use a single thread to publish transactions so that it preserves order.
  // CHANGING THIS WILL RESULT IN CONCURRENCY ISSUES WHILE PUBLISHING PARENT AND CHILD TXS
  val singleThreadExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  def publish(p: PublishAsap): Future[ByteVector32] = {
    p.strategy match {
      case PublishStrategy.SetFeerate(currentFeerate, targetFeerate, dustLimit, signingKit) =>
        val spentOutpoint = signingKit match {
          case signingKit: TransactionSigningKit.ClaimAnchorOutputSigningKit => signingKit.txWithInput.input.outPoint
          case signingKit: TransactionSigningKit.HtlcTxSigningKit => signingKit.txWithInput.input.outPoint
        }
        log.info("publishing tx: input={}:{} txid={} tx={}", spentOutpoint.txid, spentOutpoint.index, p.tx.txid, p.tx)
        val publishF = signingKit match {
          case signingKit: TransactionSigningKit.ClaimAnchorOutputSigningKit => publishCommitWithAnchor(p.tx, currentFeerate, targetFeerate, dustLimit, signingKit)
          case signingKit: TransactionSigningKit.HtlcTxSigningKit => publishHtlcTx(currentFeerate, targetFeerate, dustLimit, signingKit)
        }
        publishF.recoverWith {
          case t: Throwable if t.getMessage.contains("(code: -4)") || t.getMessage.contains("(code: -6)") =>
            log.warning("not enough funds to publish tx, will retry next block: reason={} input={}:{} txid={}", t.getMessage, spentOutpoint.txid, spentOutpoint.index, p.tx.txid)
            self ! PublishNextBlock(p)
            Future.failed(t)
          case t: Throwable =>
            log.error("cannot publish tx: reason={} input={}:{} txid={}", t.getMessage, spentOutpoint.txid, spentOutpoint.index, p.tx.txid)
            Future.failed(t)
        }
      case PublishStrategy.JustPublish =>
        log.info("publishing tx: txid={} tx={}", p.tx.txid, p.tx)
        publish(p.tx, isRetry = false)
    }
  }

  def publish(tx: Transaction, isRetry: Boolean): Future[ByteVector32] = {
    client.publishTransaction(tx)(singleThreadExecutionContext).recoverWith {
      case t: Throwable if t.getMessage.contains("(code: -25)") && !isRetry => // we retry only once
        import akka.pattern.after
        after(3 seconds, context.system.scheduler)(Future.successful({})).flatMap(_ => publish(tx, isRetry = true))
      case t: Throwable =>
        log.error("cannot publish tx: reason={} txid={}", t.getMessage, tx.txid)
        Future.failed(t)
    }
  }

  def publishCommitWithAnchor(commitTx: Transaction, currentFeerate: FeeratePerKw, targetFeerate: FeeratePerKw, dustLimit: Satoshi, signingKit: TransactionSigningKit.ClaimAnchorOutputSigningKit): Future[ByteVector32] = {
    import signingKit._
    if (targetFeerate <= currentFeerate) {
      log.info(s"publishing commit tx without the anchor (current feerate=$currentFeerate): txid=${commitTx.txid}")
      publish(commitTx, isRetry = false)
    } else {
      log.info(s"publishing commit tx with the anchor (target feerate=$targetFeerate): txid=${commitTx.txid}")
      // We want the feerate of the package (commit tx + tx spending anchor) to equal targetFeerate.
      // Thus we have: anchorFeerate = targetFeerate + (weight-commit-tx / weight-anchor-tx) * (targetFeerate - commitTxFeerate)
      // If we use the smallest weight possible for the anchor tx, the feerate we use will thus be greater than what we want,
      // and we can adjust it afterwards by raising the change output amount.
      val anchorFeerate = targetFeerate + FeeratePerKw(targetFeerate.feerate - currentFeerate.feerate) * commitTx.weight() / Transactions.claimAnchorOutputMinWeight
      // NB: bitcoind requires txs to have at least one output, but we'll remove it later to keep a single change output.
      // In case we have the perfect set of utxo amounts and no change output is added, we need the amount to be greater
      // than the fee because we may need to deduce the fee from that output.
      val dummyChangeAmount = Transactions.weight2fee(anchorFeerate, Transactions.claimAnchorOutputMinWeight) + dustLimit
      publish(commitTx, isRetry = false).flatMap(commitTxId => {
        val txNotFunded = Transaction(2, Nil, TxOut(dummyChangeAmount, Script.pay2wpkh(Transactions.PlaceHolderPubKey)) :: Nil, 0)
        client.fundTransaction(txNotFunded, FundTransactionOptions(anchorFeerate))(singleThreadExecutionContext)
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

  def publishHtlcTx(currentFeerate: FeeratePerKw, targetFeerate: FeeratePerKw, dustLimit: Satoshi, signingKit: TransactionSigningKit.HtlcTxSigningKit): Future[ByteVector32] = {
    import signingKit._
    if (targetFeerate <= currentFeerate) {
      val localSig = keyManager.sign(txWithInput, localHtlcBasepoint, localPerCommitmentPoint, Transactions.TxOwner.Local, commitmentFormat)
      val signedHtlcTx = addHtlcTxSigs(txWithInput, localSig, signingKit)
      log.info("publishing htlc tx without adding inputs: txid={}", signedHtlcTx.tx.txid)
      client.publishTransaction(signedHtlcTx.tx)(singleThreadExecutionContext)
    } else {
      log.info("publishing htlc tx with additional inputs: commit input={}:{} target feerate={}", txWithInput.input.outPoint.txid, txWithInput.input.outPoint.index, targetFeerate)
      val txNotFunded = txWithInput.tx.copy(txIn = Nil, txOut = txWithInput.tx.txOut.head.copy(amount = dustLimit) :: Nil)
      val htlcTxWeight = signingKit match {
        case _: TransactionSigningKit.HtlcSuccessSigningKit => commitmentFormat.htlcSuccessWeight
        case _: TransactionSigningKit.HtlcTimeoutSigningKit => commitmentFormat.htlcTimeoutWeight
      }
      // NB: bitcoind will add at least one P2WPKH input.
      val weightRatio = htlcTxWeight.toDouble / (txNotFunded.weight() + Transactions.claimP2WPKHOutputWeight)
      client.fundTransaction(txNotFunded, FundTransactionOptions(targetFeerate * weightRatio, changePosition = Some(1)))(singleThreadExecutionContext).map(fundTxResponse => {
        log.info(s"added ${fundTxResponse.tx.txIn.length} wallet input(s) and ${fundTxResponse.tx.txOut.length - 1} wallet output(s) to htlc tx spending commit input=${txWithInput.input.outPoint.txid}:${txWithInput.input.outPoint.index}")
        // We add the HTLC input (from the commit tx) and restore the HTLC output.
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

  def checkConfirmed(w: WatchConfirmed): Future[Unit] = {
    log.debug("checking confirmations of txid={}", w.txId)
    // NB: this is very inefficient since internally we call `getrawtransaction` three times, but it doesn't really
    // matter because this only happens once, when the watched transaction has reached min_depth
    client.getTxConfirmations(w.txId).flatMap {
      case Some(confirmations) if confirmations >= w.minDepth =>
        client.getTransaction(w.txId).flatMap { tx =>
          client.getTransactionShortId(w.txId).map {
            case (height, index) => self ! TriggerEvent(w, WatchEventConfirmed(w.event, height, index, tx))
          }
        }
    }
  }

  // TODO: move to a separate actor that listens to CurrentBlockCount and manages utxos for RBF
  def checkUtxos(): Future[Unit] = {
    case class Utxo(txId: ByteVector32, amount: MilliBtc, confirmations: Long, safe: Boolean)

    def listUnspent(): Future[Seq[Utxo]] = client.rpcClient.invoke("listunspent", /* minconf */ 0).collect {
      case JArray(values) => values.map(utxo => {
        val JInt(confirmations) = utxo \ "confirmations"
        val JBool(safe) = utxo \ "safe"
        val JDecimal(amount) = utxo \ "amount"
        val JString(txid) = utxo \ "txid"
        Utxo(ByteVector32.fromValidHex(txid), (amount.doubleValue * 1000).millibtc, confirmations.toLong, safe)
      })
    }

    def getUnconfirmedAncestorCount(utxo: Utxo): Future[(ByteVector32, Long)] = client.rpcClient.invoke("getmempoolentry", utxo.txId).map(json => {
      val JInt(ancestorCount) = json \ "ancestorcount"
      (utxo.txId, ancestorCount.toLong)
    })

    def getUnconfirmedAncestorCountMap(utxos: Seq[Utxo]): Future[Map[ByteVector32, Long]] = Future.sequence(utxos.filter(_.confirmations == 0).map(getUnconfirmedAncestorCount)).map(_.toMap)

    def recordUtxos(utxos: Seq[Utxo], ancestorCount: Map[ByteVector32, Long]): Unit = {
      val filteredByStatus = Seq(
        (Monitoring.Tags.UtxoStatuses.Confirmed, utxos.filter(utxo => utxo.confirmations > 0)),
        // We cannot create chains of unconfirmed transactions with more than 25 elements, so we ignore such utxos.
        (Monitoring.Tags.UtxoStatuses.Unconfirmed, utxos.filter(utxo => utxo.confirmations == 0 && ancestorCount.getOrElse(utxo.txId, 1L) < 25)),
        (Monitoring.Tags.UtxoStatuses.Safe, utxos.filter(utxo => utxo.safe)),
        (Monitoring.Tags.UtxoStatuses.Unsafe, utxos.filter(utxo => !utxo.safe)),
      )
      filteredByStatus.foreach {
        case (status, filteredUtxos) =>
          val amount = filteredUtxos.map(_.amount.toDouble).sum
          log.info(s"we have ${filteredUtxos.length} $status utxos ($amount mBTC)")
          Monitoring.Metrics.UtxoCount.withTag(Monitoring.Tags.UtxoStatus, status).update(filteredUtxos.length)
          Monitoring.Metrics.BitcoinBalance.withTag(Monitoring.Tags.UtxoStatus, status).update(amount)
      }
    }

    (for {
      utxos <- listUnspent()
      ancestorCount <- getUnconfirmedAncestorCountMap(utxos)
    } yield recordUtxos(utxos, ancestorCount)).recover {
      case ex => log.warning(s"could not check utxos: $ex")
    }
  }

}

object ZmqWatcher {

  def props(chainHash: ByteVector32, blockCount: AtomicLong, client: ExtendedBitcoinClient)(implicit ec: ExecutionContext = ExecutionContext.global) = Props(new ZmqWatcher(chainHash, blockCount, client)(ec))

  case object TickNewBlock

  private def utxo(w: Watch): Option[OutPoint] =
    w match {
      case w: WatchSpent => Some(OutPoint(w.txId.reverse, w.outputIndex))
      case w: WatchSpentBasic => Some(OutPoint(w.txId.reverse, w.outputIndex))
      case _ => None
    }

  /**
   * The resulting map allows checking spent txs in constant time wrt number of watchers
   */
  def addWatchedUtxos(m: Map[OutPoint, Set[Watch]], w: Watch): Map[OutPoint, Set[Watch]] = {
    utxo(w) match {
      case Some(utxo) => m.get(utxo) match {
        case Some(watches) => m + (utxo -> (watches + w))
        case None => m + (utxo -> Set(w))
      }
      case None => m
    }
  }

  def removeWatchedUtxos(m: Map[OutPoint, Set[Watch]], w: Watch): Map[OutPoint, Set[Watch]] = {
    utxo(w) match {
      case Some(utxo) => m.get(utxo) match {
        case Some(watches) if watches - w == Set.empty => m - utxo
        case Some(watches) => m + (utxo -> (watches - w))
        case None => m
      }
      case None => m
    }
  }

  /** Adjust the amount of the change output of an anchor tx to match our target feerate. */
  def adjustAnchorOutputChange(unsignedTx: Transactions.ClaimAnchorOutputTx, commitTx: Transaction, amountIn: Satoshi, currentFeerate: FeeratePerKw, targetFeerate: FeeratePerKw, dustLimit: Satoshi): Transactions.ClaimAnchorOutputTx = {
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
      case htlcTimeout: TransactionSigningKit.HtlcTimeoutSigningKit =>
        Transactions.addSigs(unsignedHtlcTx.asInstanceOf[HtlcTimeoutTx], localSig, signingKit.remoteSig, signingKit.commitmentFormat)
    }
  }

  /** Adjust the change output of an htlc tx to match our target feerate. */
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
