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

package fr.acinq.eclair.payment

import java.util.UUID

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.MilliSatoshi

import scala.compat.Platform

/**
  * Created by PM on 01/02/2017.
  */
sealed trait PaymentEvent {
  val paymentHash: ByteVector32
}

case class PaymentSent(id: UUID, amount: MilliSatoshi, feesPaid: MilliSatoshi, paymentHash: ByteVector32, paymentPreimage: ByteVector32, toChannelId: ByteVector32, timestamp: Long = Platform.currentTime) extends PaymentEvent

case class PaymentRelayed(amountIn: MilliSatoshi, amountOut: MilliSatoshi, paymentHash: ByteVector32, fromChannelId: ByteVector32, toChannelId: ByteVector32, timestamp: Long = Platform.currentTime) extends PaymentEvent

case class PaymentReceived(amount: MilliSatoshi, paymentHash: ByteVector32, fromChannelId: ByteVector32, timestamp: Long = Platform.currentTime) extends PaymentEvent

case class PaymentSettlingOnChain(id: UUID, amount: MilliSatoshi, paymentHash: ByteVector32, timestamp: Long = Platform.currentTime) extends PaymentEvent
