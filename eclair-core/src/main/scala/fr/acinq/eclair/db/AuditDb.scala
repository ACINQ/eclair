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

package fr.acinq.eclair.db

import java.io.Closeable

import fr.acinq.bitcoin.scala.Crypto.PublicKey
import fr.acinq.bitcoin.scala.{ByteVector32, Satoshi}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.{PaymentReceived, PaymentRelayed, PaymentSent}

trait AuditDb extends Closeable {

  def add(channelLifecycle: ChannelLifecycleEvent): Unit

  def add(paymentSent: PaymentSent): Unit

  def add(paymentReceived: PaymentReceived): Unit

  def add(paymentRelayed: PaymentRelayed): Unit

  def add(networkFeePaid: NetworkFeePaid): Unit

  def add(channelErrorOccurred: ChannelErrorOccurred): Unit

  def listSent(from: Long, to: Long): Seq[PaymentSent]

  def listReceived(from: Long, to: Long): Seq[PaymentReceived]

  def listRelayed(from: Long, to: Long): Seq[PaymentRelayed]

  def listNetworkFees(from: Long, to: Long): Seq[NetworkFee]

  def stats(from: Long, to: Long): Seq[Stats]

}

case class ChannelLifecycleEvent(channelId: ByteVector32, remoteNodeId: PublicKey, capacity: Satoshi, isFunder: Boolean, isPrivate: Boolean, event: String)

case class NetworkFee(remoteNodeId: PublicKey, channelId: ByteVector32, txId: ByteVector32, fee: Satoshi, txType: String, timestamp: Long)

case class Stats(channelId: ByteVector32, direction: String, avgPaymentAmount: Satoshi, paymentCount: Int, relayFee: Satoshi, networkFee: Satoshi)
