/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair.payment.send

import fr.acinq.eclair.Features

sealed trait PaymentError extends Throwable

object PaymentError {

  // @formatter:off
  sealed trait InvalidInvoice extends PaymentError
  /** The invoice contains a feature we don't support. */
  case class UnsupportedFeatures(features: Features) extends InvalidInvoice
  /** The invoice is missing a payment secret. */
  case object PaymentSecretMissing extends InvalidInvoice
  // @formatter:on

  // @formatter:off
  sealed trait InvalidTrampolineArguments extends PaymentError
  /** Trampoline fees or cltv expiry delta is missing. */
  case object TrampolineFeesMissing extends InvalidTrampolineArguments
  /** 0-value invoice should not be paid via trampoline-to-legacy (trampoline may steal funds). */
  case object TrampolineLegacyAmountLessInvoice extends InvalidTrampolineArguments
  /** Only a single trampoline node is currently supported. */
  case object TrampolineMultiNodeNotSupported extends InvalidTrampolineArguments
  // @formatter:on

  // @formatter:off
  /** Outbound capacity is too low. */
  case object BalanceTooLow extends PaymentError
  /** Payment attempts exhausted without success. */
  case object RetryExhausted extends PaymentError
  // @formatter:on

}
