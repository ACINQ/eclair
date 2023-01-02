/*
 * Copyright 2023 ACINQ SAS
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

package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.scalacompat.Satoshi
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol.CommonCodecs.satoshi32
import fr.acinq.eclair.wire.protocol.TlvCodecs.tmillisatoshi32
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshi, ToMilliSatoshiConversion}
import scodec.Codec
import scodec.codecs._

/**
 * Created by t-bast on 02/01/2023.
 */

/**
 * Liquidity ads create a decentralized market for channel liquidity.
 * Nodes advertise fee rates for their available liquidity using the gossip protocol.
 * Other nodes can then pay the advertised rate to get inbound liquidity allocated towards them.
 */
object LiquidityAds {

  /** Liquidity leases are valid for a fixed duration, after which they must be renewed. */
  val LeaseDuration = CltvExpiryDelta(1008) // 1 week

  /**
   * Liquidity is leased using the following rates:
   *
   *  - the buyer pays [[leaseFeeBase]] regardless of the amount contributed by the seller
   *  - the buyer pays [[leaseFeeProportional]] (expressed in basis points) of the amount contributed by the seller
   *  - the buyer refunds the on-chain fees for up to [[fundingWeight]] of the utxos contributed by the seller
   *
   * The seller promises that their relay fees towards the buyer will never exceed [[maxRelayFeeBase]] and [[maxRelayFeeProportional]].
   * This cannot be enforced, but if the buyer notices the seller cheating, they should blacklist them and can prove
   * that they misbehaved.
   */
  case class LeaseRates(fundingWeight: Int, leaseFeeProportional: Int, maxRelayFeeProportional: Int, leaseFeeBase: Satoshi, maxRelayFeeBase: MilliSatoshi) {
    /**
     * Fees paid by the liquidity buyer: the resulting amount must be added to the seller's output in the corresponding
     * funding transaction.
     */
    def fees(feerate: FeeratePerKw, requestedAmount: Satoshi, contributedAmount: Satoshi): Satoshi = {
      val onChainFees = Transactions.weight2feeMsat(feerate, fundingWeight)
      // If the seller adds more liquidity than requested, the buyer doesn't pay for that extra liquidity.
      val proportionalFee = requestedAmount.min(contributedAmount).toMilliSatoshi * leaseFeeProportional / 10_000
      leaseFeeBase + (proportionalFee + onChainFees).truncateToSatoshi
    }
  }

  val leaseRatesCodec: Codec[LeaseRates] = (
    ("funding_weight" | uint16) ::
      ("lease_fee_basis" | uint16) ::
      ("channel_fee_max_proportional_thousandths" | uint16) ::
      ("lease_fee_base_sat" | satoshi32) ::
      ("channel_fee_max_base_msat" | tmillisatoshi32)
    ).as[LeaseRates]

}
