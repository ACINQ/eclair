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

import akka.actor.typed.ActorRef
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi}
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchFundingDeeplyBuriedTriggered, WatchOutputSpentTriggered, WatchTxConfirmedTriggered}
import fr.acinq.eclair.channel.{CMD_GET_CHANNEL_DATA, ChannelData, RES_GET_CHANNEL_DATA, Register}
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentEvent}
import fr.acinq.eclair.swap.SwapData._
import fr.acinq.eclair.swap.SwapResponses.{Response, Status}
import fr.acinq.eclair.wire.protocol.{HasSwapId, OpeningTxBroadcasted}

object SwapCommands {

  sealed trait SwapCommand

  // @formatter:off
  case class StartSwapInSender(amount: Satoshi, swapId: String, channelId: ByteVector32) extends SwapCommand
  case class RestoreSwapInSender(swapData: SwapInSenderData) extends SwapCommand
  case object AbortSwapInSender extends SwapCommand

  sealed trait CreateSwapMessages extends SwapCommand
  case object StateTimeout extends CreateSwapMessages with AwaitAgreementMessages with CreateOpeningTxMessages with ClaimSwapCsvMessages with WaitCsvMessages with SendAgreementMessages with ClaimSwapMessages
  case class ChannelDataFailure(failure: Register.ForwardFailure[CMD_GET_CHANNEL_DATA]) extends CreateSwapMessages
  case class ChannelDataResult(channelData: RES_GET_CHANNEL_DATA[ChannelData]) extends CreateSwapMessages

  sealed trait AwaitAgreementMessages extends SwapCommand

  case class SwapMessageReceived(message: HasSwapId) extends AwaitAgreementMessages with CreateOpeningTxMessages with AwaitClaimPaymentMessages with SendAgreementMessages with AwaitOpeningTxConfirmedMessages with ValidateTxMessages with ClaimSwapMessages
  case class ForwardFailureAdapter(result: Register.ForwardFailure[HasSwapId]) extends AwaitAgreementMessages

  sealed trait CreateOpeningTxMessages extends SwapCommand
  case class InvoiceResponse(invoice: Bolt11Invoice) extends CreateOpeningTxMessages
  case class OpeningTxFunded(invoice: Bolt11Invoice, fundingResponse: MakeFundingTxResponse) extends CreateOpeningTxMessages
  case class OpeningTxCommitted(invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted) extends CreateOpeningTxMessages
  case class OpeningTxFailed(error: String, fundingResponse_opt: Option[MakeFundingTxResponse] = None) extends CreateOpeningTxMessages
  case class RollbackSuccess(error: String, status: Boolean) extends CreateOpeningTxMessages
  case class RollbackFailure(error: String, exception: Throwable) extends CreateOpeningTxMessages

  sealed trait AwaitOpeningTxConfirmedMessages extends SwapCommand
  case class OpeningTxConfirmed(openingConfirmedTriggered: WatchTxConfirmedTriggered) extends AwaitOpeningTxConfirmedMessages with ClaimSwapCoopMessages
  case object InvoiceExpired extends AwaitOpeningTxConfirmedMessages with AwaitClaimPaymentMessages

  sealed trait AwaitClaimPaymentMessages extends SwapCommand
  case class CsvDelayConfirmed(csvDelayTriggered: WatchFundingDeeplyBuriedTriggered) extends SwapCommand with WaitCsvMessages
  case class PaymentEventReceived(paymentEvent: PaymentEvent) extends AwaitClaimPaymentMessages with PayClaimInvoiceMessages

  sealed trait ClaimSwapCoopMessages extends SwapCommand
  case object ClaimTxCommitted extends ClaimSwapCoopMessages with ClaimSwapCsvMessages with ClaimSwapMessages
  case class ClaimTxFailed(error: String) extends ClaimSwapCoopMessages with ClaimSwapCsvMessages with ClaimSwapMessages
  case class ClaimTxInvalid(exception: Throwable) extends ClaimSwapCoopMessages with ClaimSwapCsvMessages with ClaimSwapMessages
  case class ClaimTxConfirmed(claimByCoopConfirmedTriggered: WatchTxConfirmedTriggered) extends ClaimSwapCoopMessages with ClaimSwapCsvMessages with ClaimSwapMessages

  sealed trait WaitCsvMessages extends SwapCommand

  sealed trait ClaimSwapCsvMessages extends SwapCommand
  // @Formatter:on

  // @formatter:off
  case object StartSwapInReceiver extends SwapCommand
  case class RestoreSwapInReceiver(swapData: SwapInReceiverData) extends SwapCommand
  case object AbortSwapInReceiver extends SwapCommand

  sealed trait SendAgreementMessages extends SwapCommand
  case class ForwardShortIdFailureAdapter(result: Register.ForwardShortIdFailure[HasSwapId]) extends SendAgreementMessages with SendCoopCloseMessages

  sealed trait ValidateTxMessages extends SwapCommand
  case class ValidInvoice(invoice: Bolt11Invoice) extends ValidateTxMessages
  case class InvalidInvoice(reason: String) extends ValidateTxMessages

  sealed trait PayClaimInvoiceMessages extends SwapCommand

  sealed trait SendCoopCloseMessages extends SwapCommand
  case class OpeningTxOutputSpent(openingTxOutputSpentTriggered: WatchOutputSpentTriggered) extends SendCoopCloseMessages

  sealed trait ClaimSwapMessages extends SwapCommand

  sealed trait UserMessages extends SendAgreementMessages with AwaitAgreementMessages with CreateOpeningTxMessages with AwaitOpeningTxConfirmedMessages with ValidateTxMessages with PayClaimInvoiceMessages with AwaitClaimPaymentMessages with ClaimSwapMessages with SendCoopCloseMessages with ClaimSwapCoopMessages with WaitCsvMessages with ClaimSwapCsvMessages
  case class GetStatus(replyTo: ActorRef[Status]) extends UserMessages
  case class CancelRequested(replyTo: ActorRef[Response]) extends UserMessages
  // @Formatter:on
}
