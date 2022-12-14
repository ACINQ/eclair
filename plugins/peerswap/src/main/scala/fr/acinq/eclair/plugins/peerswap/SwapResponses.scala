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

import fr.acinq.bitcoin.scalacompat.{Satoshi, Transaction}
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId}
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.db.OutgoingPaymentStatus.Failed
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentEvent}
import fr.acinq.eclair.plugins.peerswap.wire.protocol.{HasSwapId, OpeningTxBroadcasted, SwapAgreement, SwapRequest}

object SwapResponses {

  sealed trait Response {
    def swapId: String
  }

  sealed trait Success extends Response

  sealed trait Fail extends Response

  sealed trait Error extends Fail

  sealed trait Status extends Response

  case class SwapOpened(swapId: String) extends Success {
    override def toString: String = s"swap $swapId opened successfully."
  }

  case class SwapExistsForChannel(shortChannelId: String) extends Fail {
    override def swapId: String = ""
    override def toString: String = s"swap already exists for channel $shortChannelId"
  }

  case class SwapNotFound(swapId: String) extends Fail {
    override def toString: String = s"swap $swapId not found."
  }

  case class UserCanceled(swapId: String) extends Error {
    override def toString: String = s"swap $swapId canceled by user."
  }

  case class PeerCanceled(swapId: String, reason: String) extends Error {
    override def toString: String = s"swap $swapId canceled by peer, reason: $reason."
  }

  case class CreateFailed(swapId: String, reason: String) extends Fail {
    override def toString: String = s"could not create swap: $reason."
  }

  case class CreateInvoiceFailed(swapId: String, exception: Throwable) extends Error {
    override def toString: String = s"swap $swapId canceled, could not create invoice: ${exception.getMessage}"
  }

  case class InvalidMessage(swapId: String, behavior: String, message: HasSwapId) extends Error {
    override def toString: String = s"swap $swapId canceled due to invalid message during $behavior: $message."
  }

  case class CancelAfterOpeningCommit(swapId: String) extends Error {
    override def toString: String = "Can not cancel swap after opening tx is committed."
  }

  case class CancelAfterClaimCommit(swapId: String) extends Error {
    override def toString: String = "Can not cancel swap after claim tx is committed."
  }

  case class IncompatibleRequest(swapId: String, request: SwapRequest) extends Error {
    override def toString: String = s"incompatible request: $request"
  }

  case class FeePaymentInvoiceExpired(swapId: String) extends Error {
    override def toString: String = s"fee payment invoice expired"
  }

  case class WrongVersion(swapId: String, version: Int) extends Error {
    override def toString: String = s"protocol version must be $version"
  }

  case class PremiumRejected(swapId: String) extends Error {
    override def toString: String = s"unacceptable premium requested."
  }

  case class OpeningFundingFailed(swapId: String, cause: Throwable) extends Error {
    override def toString: String = s"failed to fund swap open tx, cause: ${cause.getMessage}"
  }

  case class OpeningCommitFailed(swapId: String, rollback: Boolean, fundingResponse: MakeFundingTxResponse) extends Error {
    override def toString: String = s"failed to commit swap open tx: ${fundingResponse.fundingTx}, rollback=$rollback"
  }

  case class OpeningRollbackFailed(swapId: String, fundingResponse: MakeFundingTxResponse, exception: Throwable) extends Error {
    override def toString: String = s"failed to commit swap open tx: ${fundingResponse.fundingTx}, rollback exception: ${exception.getMessage}"
  }

  case class InvalidFeeInvoiceAmount(swapId: String, amount: Option[MilliSatoshi], maxOpeningFee: Satoshi) extends Error {
    override def toString: String = amount match {
      case Some(a) => s"fee invoice amount $a > estimated opening tx fee: $maxOpeningFee"
      case None => s"fee invoice amount is missing or invalid"
    }
  }

  case class InvalidSwapInvoiceAmount(swapId: String, amount: Option[MilliSatoshi], requestedAmount: Satoshi) extends Error {
    override def toString: String = amount match {
      case Some(a) => s"swap invoice amount ${a.truncateToSatoshi} > requested amount: $requestedAmount"
      case None => s"swap invoice amount is missing or invalid"
    }
  }

  case class InvalidSwapInvoiceExpiryDelta(swapId: String) extends Error {
    override def toString: String = s"Invoice min-final-cltv-expiry delta too long."
  }

  case class InvalidInvoiceChannel(swapId: String, shortChannelId: ShortChannelId, routingInfo: Seq[Seq[ExtraHop]], desc: String) extends Error {
    override def toString: String = s"$desc invoice contains channel other than $shortChannelId in invoice hints $routingInfo"
  }

  case class SwapPaymentInvoiceExpired(swapId: String) extends Error {
    override def toString: String = s"swap payment invoice expired"
  }

  case class FeeInvoiceInvalid(swapId: String, exception: Throwable) extends Error {
    override def toString: String = s"could not parse fee invoice payreq: ${exception.getMessage}"
  }

  case class SwapInvoiceInvalid(swapId: String, exception: Throwable) extends Error {
    override def toString: String = s"could not parse swap invoice payreq: ${exception.getMessage}"
  }

  case class OpeningTxInvalid(swapId: String, openingTx: Transaction) extends Error {
    override def toString: String = s"opening tx invalid: $openingTx"
  }

  case class LightningPaymentFailed(swapId: String, payment: Either[PaymentEvent, Failed], desc: String) extends Error {
    override def toString: String = s"$desc lightning payment failed: $payment"
  }

  case class UserRequestedCancel(swapId: String) extends Error {
    override def toString: String = s"cancel requested by user"
  }

  sealed trait SwapStatus extends Status
  case class AwaitingAgreement(swapId: String) extends SwapStatus {
    override def toString: String = s"awaiting agreement"
  }

  case class AwaitClaimPayment(swapId: String) extends SwapStatus {
    override def toString: String = s"awaiting claim payment"
  }

  case class AwaitOpeningTxConfirmation(swapId: String) extends SwapStatus {
    override def toString: String = s"awaiting confirmation of the opening transaction"
  }

  case class AwaitCsv(swapId: String) extends SwapStatus {
    override def toString: String = s"awaiting CSV expiry"
  }

  case class AwaitClaimByInvoiceTxConfirmation(swapId: String) extends SwapStatus {
    override def toString: String = s"waiting for claim-by-invoice transaction to confirm"
  }

  case class AwaitClaimByCoopTxConfirmation(swapId: String) extends SwapStatus {
    override def toString: String = s"waiting for claim-by-coop transaction to confirm"
  }

  case class AwaitClaimByCsvTxConfirmation(swapId: String) extends SwapStatus {
    override def toString: String = s"waiting for claim-by-csv transaction to confirm"
  }

}
