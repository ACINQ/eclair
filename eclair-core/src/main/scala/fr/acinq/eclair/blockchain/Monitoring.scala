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

package fr.acinq.eclair.blockchain

import kamon.Kamon

object Monitoring {

  object Metrics {
    val NewBlockCheckConfirmedDuration = Kamon.timer("bitcoin.watcher.newblock.checkconfirmed")
    val RpcBasicInvokeCount = Kamon.counter("bitcoin.rpc.basic.invoke.count")
    val RpcBasicInvokeDuration = Kamon.timer("bitcoin.rpc.basic.invoke.duration")
    val RpcBatchInvokeDuration = Kamon.timer("bitcoin.rpc.batch.invoke.duration")
    val BitcoinBalance = Kamon.gauge("bitcoin.balance", "Bitcoin balance (mBTC)")
    val MempoolMinFeeratePerKw = Kamon.gauge("bitcoin.mempool.min-feerate-per-kw", "Minimum feerate (sat/kw) for a tx to be accepted in our mempool")
    val CannotRetrieveFeeratesCount = Kamon.counter("bitcoin.rpc.feerates.error", "Number of failures to retrieve on-chain feerates")
    val UtxoCount = Kamon.gauge("bitcoin.utxo.count", "Number of unspent outputs available")
  }

  object Tags {
    val Method = "method"
    val UtxoStatus = "status"

    object UtxoStatuses {
      val Confirmed = "confirmed"
      val Safe = "safe"
      val Unsafe = "unsafe"
      val Unconfirmed = "unconfirmed"
    }

  }

}
