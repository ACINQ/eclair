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
import fr.acinq.eclair.payment.PaymentSettlingOnChain

/**
  * Created by anton on 12.09.18.
  */
trait PendingPaymentDb {
  def add(paymentHash: BinaryData, peerNodeId: PublicKey, targetNodeId: PublicKey,
          peerCltvDelta: Long, added: Long, delay: Long, expiry: Long)

  def updateDelay(paymentHash: BinaryData, peerNodeId: PublicKey, delay: Long)

  def listDelays(targetNodeId: PublicKey, sinceBlockHeight: Long): Seq[Long]

  def riskInfo(targetNodeId: PublicKey, sinceBlockHeight: Long, sdTimes: Double): Option[RiskInfo]

  def listBadPeers(sinceBlockHeight: Long): Seq[PublicKey]


  def add(paymentSettlingOnChain: PaymentSettlingOnChain)

  def getSettlingOnChain(paymentHashOrTxid: BinaryData): Option[PaymentSettlingOnChain]
}

case class RiskInfo(targetNodeId: PublicKey, sinceBlockHeight: Long, total: Long, mean: Double, sdTimes: Double, delays: Seq[Long], adjusted: Seq[Long])