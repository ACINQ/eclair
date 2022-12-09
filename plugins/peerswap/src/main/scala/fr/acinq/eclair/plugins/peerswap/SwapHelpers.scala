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

package fr.acinq.eclair.plugins.peerswap

import akka.actor
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, Satoshi, Transaction}
import fr.acinq.eclair.MilliSatoshi.toMilliSatoshi
import fr.acinq.eclair.blockchain.OnChainWallet
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.db.PaymentType
import fr.acinq.eclair.io.Switchboard.ForwardUnknownMessage
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentEvent}
import fr.acinq.eclair.plugins.peerswap.SwapCommands._
import fr.acinq.eclair.plugins.peerswap.SwapEvents.TransactionPublished
import fr.acinq.eclair.plugins.peerswap.transactions.SwapTransactions.{SwapTransactionWithInputInfo, makeSwapOpeningTxOut}
import fr.acinq.eclair.plugins.peerswap.wire.protocol.PeerSwapMessageCodecs.peerSwapMessageCodecWithFallback
import fr.acinq.eclair.plugins.peerswap.wire.protocol.{HasSwapId, OpeningTxBroadcasted}
import fr.acinq.eclair.wire.protocol.UnknownMessage
import fr.acinq.eclair.{NodeParams, TimestampSecond, randomBytes32}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

private object SwapHelpers {

  def receiveSwapMessage[B <: SwapCommand : ClassTag](context: ActorContext[SwapCommand], stateName: String)(f: B => Behavior[SwapCommand]): Behavior[SwapCommand] = {
    context.log.debug(s"$stateName: waiting for messages, context: ${context.self.toString}")
    Behaviors.receiveMessage {
      case m: B => context.log.debug(s"$stateName: processing message $m")
        f(m)
      case m => context.log.error(s"$stateName: received unhandled message $m")
        Behaviors.same
    }
  }

  def swapInvoiceExpiredTimer(swapId: String): String = "swap-invoice-expired-timer-" + swapId

  def swapFeeExpiredTimer(swapId: String): String = "swap-fee-expired-timer-" + swapId

  def watchForTxConfirmation(watcher: ActorRef[ZmqWatcher.Command])(replyTo: ActorRef[WatchTxConfirmedTriggered], txId: ByteVector32, minDepth: Long): Unit =
    watcher ! WatchTxConfirmed(replyTo, txId, minDepth)

  def watchForTxCsvConfirmation(watcher: ActorRef[ZmqWatcher.Command])(replyTo: ActorRef[WatchFundingDeeplyBuriedTriggered], txId: ByteVector32, minDepth: Long): Unit =
    watcher ! WatchFundingDeeplyBuried(replyTo, txId, minDepth)

  def payInvoice(nodeParams: NodeParams)(paymentInitiator: actor.ActorRef, swapId: String, invoice: Bolt11Invoice): Unit =
    paymentInitiator ! SendPaymentToNode(invoice.amount_opt.get, invoice, nodeParams.maxPaymentAttempts, Some(swapId), nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams, blockUntilComplete = true)

  def watchForPayment(watch: Boolean)(implicit context: ActorContext[SwapCommand]): Unit =
    if (watch) context.system.classicSystem.eventStream.subscribe(paymentEventAdapter(context).toClassic, classOf[PaymentEvent])
    else context.system.classicSystem.eventStream.unsubscribe(paymentEventAdapter(context).toClassic, classOf[PaymentEvent])

  private def paymentEventAdapter(context: ActorContext[SwapCommand]): ActorRef[PaymentEvent] = context.messageAdapter[PaymentEvent](PaymentEventReceived)

  def makeUnknownMessage(message: HasSwapId): UnknownMessage = {
    val encoded = peerSwapMessageCodecWithFallback.encode(message).require
    UnknownMessage(encoded.sliceToInt(0, 16, signed = false), encoded.drop(16).toByteVector)
  }

  def send(switchboard: actor.ActorRef, remoteNodeId: PublicKey)(message: HasSwapId): Unit =
    switchboard ! ForwardUnknownMessage(remoteNodeId, makeUnknownMessage(message))

  def fundOpening(wallet: OnChainWallet, feeRatePerKw: FeeratePerKw)(amount: Satoshi, makerPubkey: PublicKey, takerPubkey: PublicKey, invoice: Bolt11Invoice)(implicit context: ActorContext[SwapCommand]): Unit = {
    // setup conditions satisfied, create the opening tx
    val openingTx = makeSwapOpeningTxOut(amount, makerPubkey, takerPubkey, invoice.paymentHash)
    // funding successful, commit the opening tx
    context.pipeToSelf(wallet.makeFundingTx(openingTx.publicKeyScript, amount, feeRatePerKw)) {
      case Success(r) => OpeningTxFunded(invoice, r)
      case Failure(cause) => OpeningTxFundingFailed(cause)
    }
  }

  def commitOpening(wallet: OnChainWallet)(swapId: String, invoice: Bolt11Invoice, fundingResponse: MakeFundingTxResponse, desc: String)(implicit context: ActorContext[SwapCommand]): Unit = {
    context.system.eventStream ! EventStream.Publish(TransactionPublished(swapId, fundingResponse.fundingTx, desc))
    context.pipeToSelf(wallet.commit(fundingResponse.fundingTx)) {
      case Success(true) =>
        context.log.debug(s"opening tx ${fundingResponse.fundingTx.txid} published for swap $swapId")
        OpeningTxCommitted(invoice, OpeningTxBroadcasted(swapId, invoice.toString, fundingResponse.fundingTx.txid.toHex, fundingResponse.fundingTxOutputIndex, ""))
      case Success(false) => OpeningTxCommitFailed(fundingResponse)
      case Failure(t) =>
        context.log.debug(s"opening tx ${fundingResponse.fundingTx.txid} *possibly* published for swap $swapId, exception: $t ")
        OpeningTxCommitted(invoice, OpeningTxBroadcasted(swapId, invoice.toString, fundingResponse.fundingTx.txid.toHex, fundingResponse.fundingTxOutputIndex, ""))
    }
  }

  def checkSpendable(txinfo: SwapTransactionWithInputInfo): Try[Unit] = {
    // NB: we don't verify the other inputs as they should only be wallet inputs used to RBF the transaction
    Try(Transaction.correctlySpends(txinfo.tx, Map(txinfo.input.outPoint -> txinfo.input.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
  }

  def commitClaim(wallet: OnChainWallet)(swapId: String, txInfo: SwapTransactionWithInputInfo, desc: String)(implicit context: ActorContext[SwapCommand]): Unit =
    checkSpendable(txInfo) match {
      case Success(_) =>
        // publish claim tx
        context.system.eventStream ! EventStream.Publish(TransactionPublished(swapId, txInfo.tx, desc))
        context.pipeToSelf(wallet.commit(txInfo.tx)) {
          case Success(true) => ClaimTxCommitted
          case Success(false) => context.log.error(s"swap $swapId claim tx commit did not succeed with $txInfo")
            ClaimTxFailed
          case Failure(t) => context.log.error(s"swap $swapId claim tx commit *possibly* failed with $txInfo, exception: $t")
            ClaimTxFailed
        }
      case Failure(e) => context.log.error(s"swap $swapId claim tx is invalid: $e")
        context.self ! ClaimTxInvalid
    }

  def rollback(wallet: OnChainWallet)(fundingResponse: MakeFundingTxResponse)(implicit context: ActorContext[SwapCommand]): Unit =
    context.pipeToSelf(wallet.rollback(fundingResponse.fundingTx)) {
      case Success(status) => RollbackSuccess(status, fundingResponse)
      case Failure(t) => RollbackFailure(t, fundingResponse)
    }

  def createInvoice(nodeParams: NodeParams, amount: Satoshi, description: String)(implicit context: ActorContext[SwapCommand]): Try[Bolt11Invoice] =
    Try {
      val paymentPreimage = randomBytes32()
      val invoice: Bolt11Invoice = Bolt11Invoice(nodeParams.chainHash, Some(toMilliSatoshi(amount)), Crypto.sha256(paymentPreimage), nodeParams.privateKey, Left(description),
        nodeParams.channelConf.minFinalExpiryDelta, fallbackAddress = None, expirySeconds = Some(nodeParams.invoiceExpiry.toSeconds),
        extraHops = Nil, timestamp = TimestampSecond.now(), paymentSecret = paymentPreimage, paymentMetadata = None, features = nodeParams.features.invoiceFeatures())
      context.log.debug("generated invoice={} from amount={} sat, description={}", invoice.toString, amount, description)
      nodeParams.db.payments.addIncomingPayment(invoice, paymentPreimage, PaymentType.Standard)
      invoice
    }
}