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

package fr.acinq.eclair.balance

import kamon.Kamon
import kamon.metric.Metric

object Monitoring {

  object Metrics {
    val GlobalBalance: Metric.Gauge = Kamon.gauge("globalbalance", "Global Balance (BTC)")
    val GlobalBalanceNormalized: Metric.Gauge = Kamon.gauge("globalbalance.normalized", "Global Balance Normalized")
    val GlobalBalanceDiff: Metric.Gauge = Kamon.gauge("globalbalance.diff", "Global Balance Diff (Satoshi)")
    val GlobalBalanceDetailed: Metric.Gauge = Kamon.gauge("globalbalance.detailed", "Global Balance Detailed (BTC)")
    val BitcoinBalance: Metric.Gauge = Kamon.gauge("bitcoin.balance", "Bitcoin balance (mBTC)")
    val UtxoCount: Metric.Gauge = Kamon.gauge("bitcoin.utxo.count", "Number of unspent outputs available")
  }

  object Tags {
    val BalanceType = "type"
    val OffchainState = "state"
    val DiffSign = "sign"
    val UtxoStatus = "status"

    object BalanceTypes {
      val OnchainConfirmed = "onchain.confirmed"
      val OnchainUnconfirmed = "onchain.unconfirmed"
      val Offchain = "offchain"
    }

    object OffchainStates {
      val waitForFundingConfirmed = "waitForFundingConfirmed"
      val waitForChannelReady = "waitForChannelReady"
      val normal = "normal"
      val shutdown = "shutdown"
      val negotiating = "negotiating"
      val closingLocal = "closing-local"
      val closingRemote = "closing-remote"
      val closingUnknown = "closing-unknown"
      val waitForPublishFutureCommitment = "waitForPublishFutureCommitment"
    }

    /** we can't chart negative amounts in Kamon */
    object DiffSigns {
      val plus = "plus"
      val minus = "minus"
    }

    object UtxoStatuses {
      val Confirmed = "confirmed"
      val Safe = "safe"
      val Unsafe = "unsafe"
      val Unconfirmed = "unconfirmed"
    }

  }

}
