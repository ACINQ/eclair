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
import fr.acinq.eclair.payment.{PaymentLostOnChain, PaymentSettlingOnChain}

/**
  * Created by anton on 12.09.18.
  */
trait PendingPaymentDb {
  def addPendingPayment(paymentHash: BinaryData, peerNodeId: PublicKey, targetNodeId: PublicKey,
                        peerCltvDelta: Long, added: Long, delay: Long, expiry: Long)

  def updateDelay(paymentHash: BinaryData, peerNodeId: PublicKey, delay: Long)

  def listDelays(targetNodeId: PublicKey, sinceBlockHeight: Long): Seq[Long]

  def riskInfo(targetNodeId: PublicKey, sinceBlockHeight: Long, sdTimes: Double): Option[RiskInfo]

  def listBadPeers(sinceBlockHeight: Long): Seq[PublicKey]


  def addSettlingOnChain(paymentSettlingOnChain: PaymentSettlingOnChain)

  def addLostOnChain(paymentLostOnChain: PaymentLostOnChain)

  def getSettlingOnChain(paymentHashOrTxid: BinaryData): Option[PaymentSettlingOnChain]

  def getLostOnChain(paymentHash: BinaryData): Option[PaymentLostOnChain]

}

/**
  * RiskInfo returns an object with the following fields:
  * - `total`: total number of outgoing payments since a given block height
  * - `mean`: average block delay for all outgoing payments since a given block height
  * - `sdTimes`: standard deviation * provided_multiplier for all outgoing payments since a given block height
  * - `delays`: a list of delayed payments for a given payee since a given block height
  * - `adjusted`: a list of delayed payments for a given payee since a given block height which are beyond `mean` + `sdTimes` value

  * If a `total` value is small we can just look at `delays` to decide if payee is risky.
  * If `total` value is large we better look at `adjusted` since it catches outliers which have a long payment delays compared to all other delays.
  */
case class RiskInfo(targetNodeId: PublicKey, sinceBlockHeight: Long, total: Long, mean: Double, sdTimes: Double, delays: Seq[Long], adjusted: Seq[Long])