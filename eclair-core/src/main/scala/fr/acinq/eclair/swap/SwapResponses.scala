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

import fr.acinq.eclair.payment.Bolt11Invoice
import fr.acinq.eclair.wire.protocol.{HasSwapId, OpeningTxBroadcasted, SwapAgreement, SwapRequest}

object SwapResponses {

  sealed trait Response {
    def swapId: String
  }

  sealed trait Success extends Response

  sealed trait Fail extends Response

  sealed trait Error extends Fail

  case class SwapOpened(swapId: String) extends Success {
    override def toString: String = s"swap $swapId opened successfully."
  }

  case class UserCanceled(swapId: String) extends Fail {
    override def toString: String = s"swap $swapId canceled by user."
  }

  case class PeerCanceled(swapId: String) extends Fail {
    override def toString: String = s"swap $swapId canceled by peer."
  }

  case class CreateFailed(swapId: String, reason: String) extends Fail {
    override def toString: String = s"could not create swap $swapId: $reason."
  }

  case class InvalidMessage(swapId: String, behavior: String, message: HasSwapId) extends Fail {
    override def toString: String = s"swap $swapId canceled due to invalid message during $behavior: $message."
  }

  case class LocalError(swapId: String, t: Throwable) extends Error {
    override def toString: String = s"swap $swapId local error: $t."
  }

  case class SwapError(swapId: String, reason: String) extends Error {
    override def toString: String = s"swap $swapId swap error: $reason."
  }

  case class InternalError(swapId: String, reason: String) extends Error {
    override def toString: String = s"swap $swapId internal error: $reason."
  }

  sealed trait Status extends Response

  case class SwapInStatus(swapId: String, actor: String, behavior: String, request: SwapRequest, agreement_opt: Option[SwapAgreement] = None, invoice_opt: Option[Bolt11Invoice] = None, openingTxBroadcasted_opt: Option[OpeningTxBroadcasted] = None) extends Status {
    override def toString: String = s"$actor[$behavior]: $swapId, ${request.scid}, $request, $agreement_opt, $invoice_opt, $openingTxBroadcasted_opt"
  }

}
