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

package fr.acinq.eclair.swap

import akka.actor
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, Transaction}
import fr.acinq.eclair.blockchain.OnChainWallet
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{CMD_GET_CHANNEL_DATA, ChannelData, RES_GET_CHANNEL_DATA, Register}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentEvent}
import fr.acinq.eclair.swap.SwapCommands._
import fr.acinq.eclair.swap.SwapEvents.TransactionPublished
import fr.acinq.eclair.swap.SwapTransactions.makeSwapOpeningTxOut
import fr.acinq.eclair.transactions.Transactions.{TransactionWithInputInfo, checkSpendable}
import fr.acinq.eclair.wire.protocol.{HasSwapId, OpeningTxBroadcasted, SwapInAgreement, SwapInRequest}
import fr.acinq.eclair.{NodeParams, ShortChannelId}
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

object SwapHelpers {

  def queryChannelData(register: actor.ActorRef, channelId: ByteVector32)(implicit context: ActorContext[SwapCommand]): Unit =
    register ! Register.Forward[CMD_GET_CHANNEL_DATA](channelDataFailureAdapter(context), channelId, CMD_GET_CHANNEL_DATA(channelDataResultAdapter(context).toClassic))

  def channelDataResultAdapter(context: ActorContext[SwapCommand]): ActorRef[RES_GET_CHANNEL_DATA[ChannelData]] =
    context.messageAdapter[RES_GET_CHANNEL_DATA[ChannelData]](ChannelDataResult)

  def channelDataFailureAdapter(context: ActorContext[SwapCommand]): ActorRef[Register.ForwardFailure[CMD_GET_CHANNEL_DATA]] =
    context.messageAdapter[Register.ForwardFailure[CMD_GET_CHANNEL_DATA]](ChannelDataFailure)

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

  def watchForTxConfirmation(watcher: ActorRef[ZmqWatcher.Command])(replyTo: ActorRef[WatchTxConfirmedTriggered], txId: ByteVector32, minDepth: Long): Unit =
    watcher ! WatchTxConfirmed(replyTo, txId, minDepth)

  def watchForTxCsvConfirmation(watcher: ActorRef[ZmqWatcher.Command])(replyTo: ActorRef[WatchFundingDeeplyBuriedTriggered], txId: ByteVector32, minDepth: Long): Unit =
    watcher ! WatchFundingDeeplyBuried(replyTo, txId, minDepth)

  def watchForOutputSpent(watcher: ActorRef[ZmqWatcher.Command])(replyTo: ActorRef[WatchOutputSpentTriggered], txId: ByteVector32, outputIndex: Int): Unit =
    watcher ! WatchOutputSpent(replyTo, txId, outputIndex, Set())

  def payInvoice(nodeParams: NodeParams)(paymentInitiator: actor.ActorRef, swapId: String, invoice: Bolt11Invoice): Unit =
    paymentInitiator ! SendPaymentToNode(invoice.amount_opt.get, invoice, nodeParams.maxPaymentAttempts, Some(swapId), nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams, blockUntilComplete = true)

  def watchForPayment(watch: Boolean)(implicit context: ActorContext[SwapCommand]): Unit =
    if (watch) context.system.classicSystem.eventStream.subscribe(paymentEventAdapter(context).toClassic, classOf[PaymentEvent])
    else context.system.classicSystem.eventStream.unsubscribe(paymentEventAdapter(context).toClassic, classOf[PaymentEvent])

  def paymentEventAdapter(context: ActorContext[SwapCommand]): ActorRef[PaymentEvent] = context.messageAdapter[PaymentEvent](PaymentEventReceived)

  def sendShortId(register: actor.ActorRef, shortChannelId: ShortChannelId)(message: HasSwapId)(implicit context: ActorContext[SwapCommand]): Unit =
    register ! Register.ForwardShortId[HasSwapId](forwardShortIdAdapter(context), shortChannelId, message)

  def forwardShortIdAdapter(context: ActorContext[SwapCommand]): ActorRef[Register.ForwardShortIdFailure[HasSwapId]] =
    context.messageAdapter[Register.ForwardShortIdFailure[HasSwapId]](ForwardShortIdFailureAdapter)

  def send(register: actor.ActorRef, channelId: ByteVector32)(message: HasSwapId)(implicit context: ActorContext[SwapCommand]): Unit =
    register ! Register.Forward(forwardAdapter(context), channelId, message)

  def forwardAdapter(context: ActorContext[SwapCommand]): ActorRef[Register.ForwardFailure[HasSwapId]] =
    context.messageAdapter[Register.ForwardFailure[HasSwapId]](ForwardFailureAdapter)

  def fundOpening(wallet: OnChainWallet, feeRatePerKw: FeeratePerKw)(request: SwapInRequest, agreement: SwapInAgreement, invoice: Bolt11Invoice)(implicit context: ActorContext[SwapCommand]): Unit = {
    // setup conditions satisfied, create the opening tx
    val openingTx = makeSwapOpeningTxOut((request.amount + agreement.premium).sat, PublicKey(ByteVector.fromValidHex(request.pubkey)), PublicKey(ByteVector.fromValidHex(agreement.pubkey)), invoice.paymentHash)
    // funding successful, commit the opening tx
    context.pipeToSelf(wallet.makeFundingTx(openingTx.publicKeyScript, (request.amount + agreement.premium).sat, feeRatePerKw)) {
      case Success(r) => OpeningTxFunded(invoice, r)
      case Failure(cause) => OpeningTxFailed(s"error while funding swap open tx: $cause")
    }
  }

  def commitOpening(wallet: OnChainWallet)(swapId: String, invoice: Bolt11Invoice, fundingResponse: MakeFundingTxResponse, desc: String)(implicit context: ActorContext[SwapCommand]): Unit = {
    context.system.eventStream ! EventStream.Publish(TransactionPublished(swapId, fundingResponse.fundingTx, desc))
    context.pipeToSelf(wallet.commit(fundingResponse.fundingTx)) {
      case Success(true) => context.log.debug(s"opening tx ${fundingResponse.fundingTx.txid} published for swap $swapId")
        OpeningTxCommitted(invoice, OpeningTxBroadcasted(swapId, invoice.toString, fundingResponse.fundingTx.txid.toHex, fundingResponse.fundingTxOutputIndex, ""))
      case Success(false) => OpeningTxFailed("could not publish swap open tx", Some(fundingResponse))
      case Failure(t) => OpeningTxFailed(s"failed to commit swap open tx, exception: $t", Some(fundingResponse))
    }
  }

  def commitClaim(wallet: OnChainWallet)(swapId: String, txInfo: TransactionWithInputInfo, desc: String)(implicit context: ActorContext[SwapCommand]): Unit =
    checkSpendable(txInfo) match {
      case Success(_) =>
        // publish claim tx
        context.system.eventStream ! EventStream.Publish(TransactionPublished(swapId, txInfo.tx, desc))
        context.pipeToSelf(wallet.commit(txInfo.tx)) {
          case Success(true) => ClaimTxCommitted
          case Success(false) => context.log.error(s"swap $swapId claim tx commit did not succeed, $txInfo")
            ClaimTxFailed(s"publish did not succeed $txInfo")
          case Failure(t) => context.log.error(s"swap $swapId claim tx commit failed, $txInfo")
            ClaimTxFailed(s"failed to commit $txInfo, exception: $t")
        }
      case Failure(e) => context.log.error(s"swap $swapId claim tx is invalid: $e")
        context.self ! ClaimTxInvalid(e)
    }

  def rollback(wallet: OnChainWallet)(error: String, tx: Transaction)(implicit context: ActorContext[SwapCommand]): Unit =
    context.pipeToSelf(wallet.rollback(tx)) {
      case Success(status) => RollbackSuccess(error, status)
      case Failure(t) => RollbackFailure(error, t)
    }
}