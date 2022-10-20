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
import fr.acinq.bitcoin.scalacompat.Satoshi
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchFundingDeeplyBuriedTriggered, WatchOutputSpentTriggered, WatchTxConfirmedTriggered}
import fr.acinq.eclair.channel.{CMD_GET_CHANNEL_DATA, ChannelData, RES_GET_CHANNEL_DATA, Register}
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentEvent}
import fr.acinq.eclair.plugins.peerswap.SwapResponses.{Response, Status}
import fr.acinq.eclair.plugins.peerswap.wire.protocol.{HasSwapId, OpeningTxBroadcasted, SwapInRequest, SwapOutRequest}
import fr.acinq.eclair.wire.protocol.UnknownMessage

object SwapCommands {

  sealed trait SwapCommand

  // @formatter:off
  case class StartSwapInSender(amount: Satoshi, swapId: String, shortChannelId: ShortChannelId) extends SwapCommand
  case class StartSwapOutReceiver(request: SwapOutRequest) extends SwapCommand
  case class RestoreSwap(swapData: SwapData) extends SwapCommand
  case object AbortSwap extends SwapCommand

  sealed trait CreateSwapMessages extends SwapCommand
  case object StateTimeout extends CreateSwapMessages with AwaitAgreementMessages with CreateOpeningTxMessages with ClaimSwapCsvMessages with WaitCsvMessages with AwaitFeePaymentMessages with ClaimSwapMessages with PayFeeInvoiceMessages  with SendAgreementMessages
  case class ChannelDataFailure(failure: Register.ForwardShortIdFailure[CMD_GET_CHANNEL_DATA]) extends CreateSwapMessages
  case class ChannelDataResult(channelData: RES_GET_CHANNEL_DATA[ChannelData]) extends CreateSwapMessages

  sealed trait AwaitAgreementMessages extends SwapCommand

  case class SwapMessageReceived(message: HasSwapId) extends AwaitAgreementMessages with CreateOpeningTxMessages with AwaitClaimPaymentMessages with AwaitFeePaymentMessages with AwaitOpeningTxConfirmedMessages with ValidateTxMessages with ClaimSwapMessages with PayFeeInvoiceMessages with SendAgreementMessages
  case class ForwardFailureAdapter(result: Register.ForwardFailure[UnknownMessage]) extends AwaitAgreementMessages

  sealed trait CreateOpeningTxMessages extends SwapCommand
  case class InvoiceResponse(invoice: Bolt11Invoice) extends CreateOpeningTxMessages
  case class OpeningTxFunded(invoice: Bolt11Invoice, fundingResponse: MakeFundingTxResponse) extends CreateOpeningTxMessages
  case class OpeningTxCommitted(invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted) extends CreateOpeningTxMessages
  case class OpeningTxFailed(error: String, fundingResponse_opt: Option[MakeFundingTxResponse] = None) extends CreateOpeningTxMessages
  case class RollbackSuccess(error: String, status: Boolean) extends CreateOpeningTxMessages
  case class RollbackFailure(error: String, exception: Throwable) extends CreateOpeningTxMessages

  sealed trait AwaitOpeningTxConfirmedMessages extends SwapCommand
  case class OpeningTxConfirmed(openingConfirmedTriggered: WatchTxConfirmedTriggered) extends AwaitOpeningTxConfirmedMessages with ClaimSwapCoopMessages
  case object InvoiceExpired extends AwaitOpeningTxConfirmedMessages with AwaitClaimPaymentMessages with AwaitFeePaymentMessages

  sealed trait AwaitClaimPaymentMessages extends SwapCommand
  case class CsvDelayConfirmed(csvDelayTriggered: WatchFundingDeeplyBuriedTriggered) extends SwapCommand with WaitCsvMessages
  case class PaymentEventReceived(paymentEvent: PaymentEvent) extends AwaitClaimPaymentMessages with PayClaimInvoiceMessages with AwaitFeePaymentMessages with PayFeeInvoiceMessages

  sealed trait ClaimSwapCoopMessages extends SwapCommand
  case object ClaimTxCommitted extends ClaimSwapCoopMessages with ClaimSwapCsvMessages with ClaimSwapMessages
  case class ClaimTxFailed(error: String) extends ClaimSwapCoopMessages with ClaimSwapCsvMessages with ClaimSwapMessages
  case class ClaimTxInvalid(exception: Throwable) extends ClaimSwapCoopMessages with ClaimSwapCsvMessages with ClaimSwapMessages
  case class ClaimTxConfirmed(claimByCoopConfirmedTriggered: WatchTxConfirmedTriggered) extends ClaimSwapCoopMessages with ClaimSwapCsvMessages with ClaimSwapMessages

  sealed trait WaitCsvMessages extends SwapCommand

  sealed trait ClaimSwapCsvMessages extends SwapCommand
  // @Formatter:on

  // @formatter:off
  case class StartSwapInReceiver(request: SwapInRequest) extends SwapCommand
  case class StartSwapOutSender(amount: Satoshi, swapId: String, shortChannelId: ShortChannelId) extends SwapCommand

  sealed trait SendAgreementMessages extends SwapCommand
  sealed trait AwaitFeePaymentMessages extends SwapCommand
  case class ForwardShortIdFailureAdapter(result: Register.ForwardShortIdFailure[UnknownMessage]) extends AwaitFeePaymentMessages with SendCoopCloseMessages with SendAgreementMessages

  sealed trait ValidateTxMessages extends SwapCommand
  case class ValidInvoice(invoice: Bolt11Invoice) extends ValidateTxMessages
  case class InvalidInvoice(reason: String) extends ValidateTxMessages

  sealed trait PayClaimInvoiceMessages extends SwapCommand

  sealed trait SendCoopCloseMessages extends SwapCommand
  case class OpeningTxOutputSpent(openingTxOutputSpentTriggered: WatchOutputSpentTriggered) extends SendCoopCloseMessages

  sealed trait ClaimSwapMessages extends SwapCommand

  sealed trait PayFeeInvoiceMessages extends SwapCommand

  sealed trait UserMessages extends AwaitFeePaymentMessages with AwaitAgreementMessages with CreateOpeningTxMessages with AwaitOpeningTxConfirmedMessages with ValidateTxMessages with PayClaimInvoiceMessages with AwaitClaimPaymentMessages with ClaimSwapMessages with SendCoopCloseMessages with ClaimSwapCoopMessages with WaitCsvMessages with ClaimSwapCsvMessages
  case class GetStatus(replyTo: ActorRef[Status]) extends UserMessages with PayFeeInvoiceMessages with SendAgreementMessages
  case class CancelRequested(replyTo: ActorRef[Response]) extends UserMessages with PayFeeInvoiceMessages with SendAgreementMessages
  // @Formatter:on
}
