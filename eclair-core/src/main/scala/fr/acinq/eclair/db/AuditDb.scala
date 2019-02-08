/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.db

import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.{PaymentReceived, PaymentRelayed, PaymentSent}

trait AuditDb {

  def add(availableBalanceChanged: AvailableBalanceChanged)

  def add(channelLifecycle: ChannelLifecycleEvent)

  def add(paymentSent: PaymentSent)

  def add(paymentReceived: PaymentReceived)

  def add(paymentRelayed: PaymentRelayed)

  def add(networkFeePaid: NetworkFeePaid)

  def listSent(from: Long, to: Long): Seq[PaymentSent]

  def listReceived(from: Long, to: Long): Seq[PaymentReceived]

  def listRelayed(from: Long, to: Long): Seq[PaymentRelayed]

  def listNetworkFees(from: Long, to: Long): Seq[NetworkFee]

  def stats: Seq[Stats]

  def close: Unit

}

case class ChannelLifecycleEvent(channelId: BinaryData, remoteNodeId: PublicKey, capacitySat: Long, isFunder: Boolean, isPrivate: Boolean, event: String)

case class NetworkFee(remoteNodeId: PublicKey, channelId: BinaryData, txId: BinaryData, feeSat: Long, txType: String, timestamp: Long)

case class Stats(channelId: BinaryData, avgPaymentAmountSatoshi: Long, paymentCount: Int, relayFeeSatoshi: Long, networkFeeSatoshi: Long)
