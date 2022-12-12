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

import akka.actor.typed.ActorRef
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchFundingDeeplyBuriedTriggered, WatchTxConfirmedTriggered}
import fr.acinq.eclair.db.OutgoingPaymentStatus.Failed
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentFailed, PaymentReceived}
import fr.acinq.eclair.plugins.peerswap.SwapResponses.{Response, Status}
import fr.acinq.eclair.plugins.peerswap.wire.protocol.{HasSwapId, OpeningTxBroadcasted, SwapInRequest, SwapOutRequest}

object SwapCommands {

  sealed trait SwapCommand

  // @formatter:off
  case class StartSwapInSender(amount: Satoshi, swapId: String, shortChannelId: ShortChannelId) extends SwapCommand
  case class StartSwapOutReceiver(request: SwapOutRequest) extends SwapCommand
  case class RestoreSwap(swapData: SwapData) extends SwapCommand

  sealed trait AwaitAgreementMessages extends SwapCommand

  case class SwapMessageReceived(message: HasSwapId) extends AwaitAgreementMessages with CreateOpeningTxMessages with AwaitClaimPaymentMessages with AwaitFeePaymentMessages with AwaitOpeningTxConfirmedMessages with ClaimSwapMessages with PayFeeInvoiceMessages with SendAgreementMessages

  sealed trait CreateOpeningTxMessages extends SwapCommand
  case class InvoiceResponse(invoice: Bolt11Invoice) extends CreateOpeningTxMessages
  case class OpeningTxFunded(invoice: Bolt11Invoice, fundingResponse: MakeFundingTxResponse) extends CreateOpeningTxMessages
  case class OpeningTxCommitted(invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted) extends CreateOpeningTxMessages
  case class OpeningTxFundingFailed(cause: Throwable) extends CreateOpeningTxMessages
  case class OpeningTxCommitFailed(fundingResponse: MakeFundingTxResponse) extends CreateOpeningTxMessages
  case class RollbackSuccess(status: Boolean, fundingResponse: MakeFundingTxResponse) extends CreateOpeningTxMessages
  case class RollbackFailure(exception: Throwable, fundingResponse: MakeFundingTxResponse) extends CreateOpeningTxMessages

  sealed trait AwaitOpeningTxConfirmedMessages extends SwapCommand
  case class OpeningTxConfirmed(openingConfirmedTriggered: WatchTxConfirmedTriggered) extends AwaitOpeningTxConfirmedMessages with ClaimSwapCoopMessages
  case object InvoiceExpired extends AwaitClaimPaymentMessages with AwaitFeePaymentMessages

  sealed trait AwaitClaimPaymentMessages extends SwapCommand
  case class CsvDelayConfirmed(csvDelayTriggered: WatchFundingDeeplyBuriedTriggered) extends SwapCommand with WaitCsvMessages

  sealed trait PayClaimPaymentMessages extends SwapCommand

  sealed trait WrappedPaymentEvent extends PayFeeInvoiceMessages with PayClaimPaymentMessages {
    def paymentHash: ByteVector32
  }
  case class WrappedPaymentReceived(paymentEvent: PaymentReceived) extends AwaitFeePaymentMessages with AwaitClaimPaymentMessages {
    def paymentHash: ByteVector32 = paymentEvent.paymentHash
  }
  case class WrappedPaymentSent(paymentHash: ByteVector32, paymentPreimage: ByteVector32) extends WrappedPaymentEvent
  case class WrappedPaymentFailed(paymentHash: ByteVector32, failure: Either[PaymentFailed, Failed]) extends WrappedPaymentEvent
  case class WrappedPaymentPending(paymentHash: ByteVector32) extends WrappedPaymentEvent
  sealed trait ClaimSwapCoopMessages extends SwapCommand
  case object ClaimTxCommitted extends ClaimSwapCoopMessages with ClaimSwapCsvMessages with ClaimSwapMessages
  case object ClaimTxFailed extends ClaimSwapCoopMessages with ClaimSwapCsvMessages with ClaimSwapMessages
  case object ClaimTxInvalid extends ClaimSwapCoopMessages with ClaimSwapCsvMessages with ClaimSwapMessages
  case class ClaimTxConfirmed(claimByCoopConfirmedTriggered: WatchTxConfirmedTriggered) extends ClaimSwapCoopMessages with ClaimSwapCsvMessages with ClaimSwapMessages
  sealed trait WaitCsvMessages extends SwapCommand

  sealed trait ClaimSwapCsvMessages extends SwapCommand
  // @Formatter:on

  // @formatter:off
  case class StartSwapInReceiver(request: SwapInRequest) extends SwapCommand
  case class StartSwapOutSender(amount: Satoshi, swapId: String, shortChannelId: ShortChannelId) extends SwapCommand

  sealed trait SendAgreementMessages extends SwapCommand
  sealed trait AwaitFeePaymentMessages extends SwapCommand

  sealed trait ClaimSwapMessages extends SwapCommand

  sealed trait PayFeeInvoiceMessages extends SwapCommand

  sealed trait UserMessages extends AwaitFeePaymentMessages with AwaitAgreementMessages with CreateOpeningTxMessages with AwaitOpeningTxConfirmedMessages with AwaitClaimPaymentMessages with PayClaimPaymentMessages with ClaimSwapMessages with ClaimSwapCoopMessages with WaitCsvMessages with ClaimSwapCsvMessages
  case class GetStatus(replyTo: ActorRef[Status]) extends UserMessages with PayFeeInvoiceMessages with SendAgreementMessages
  case class CancelRequested(replyTo: ActorRef[Response]) extends UserMessages with PayFeeInvoiceMessages with SendAgreementMessages
  // @Formatter:on
}
