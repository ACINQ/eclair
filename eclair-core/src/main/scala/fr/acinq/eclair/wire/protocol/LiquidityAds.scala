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

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto, Satoshi}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol.CommonCodecs.{blockHeight, millisatoshi32, satoshi32}
import fr.acinq.eclair.{BlockHeight, MilliSatoshi, NodeParams, ToMilliSatoshiConversion}
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs._

import java.nio.charset.StandardCharsets

/**
 * Created by t-bast on 02/01/2023.
 */

/**
 * Liquidity ads create a decentralized market for channel liquidity.
 * Nodes advertise fee rates for their available liquidity using the gossip protocol.
 * Other nodes can then pay the advertised rate to get inbound liquidity allocated towards them.
 */
object LiquidityAds {

  /**
   * @param rate      proposed lease rate.
   * @param minAmount minimum funding amount for that rate: we don't want to contribute very low amounts, because that
   *                  may lock some of our liquidity in an unconfirmed and unsafe change output.
   */
  case class LeaseRateConfig(rate: LeaseRate, minAmount: Satoshi)

  case class SellerConfig(rates: Seq[LeaseRateConfig]) {
    def offerLease(nodeKey: PrivateKey, channelId: ByteVector32, currentBlockHeight: BlockHeight, fundingScript: ByteVector, fundingFeerate: FeeratePerKw, requestFunds: ChannelTlv.RequestFunds): Either[ChannelException, WillFundLease] = {
      rates.find(_.rate.leaseDuration == requestFunds.leaseDuration) match {
        case Some(r) =>
          if (currentBlockHeight + 12 < requestFunds.leaseExpiry - requestFunds.leaseDuration) {
            // They're trying to cheat and pay for a smaller duration than what will actually be enforced.
            Left(InvalidLiquidityAdsDuration(channelId, requestFunds.leaseDuration))
          } else if (requestFunds.amount < r.minAmount) {
            Left(InvalidLiquidityAdsAmount(channelId, requestFunds.amount, r.minAmount))
          } else {
            val lease = r.rate.signLease(nodeKey, fundingScript, fundingFeerate, requestFunds)
            Right(lease)
          }
        case None =>
          Left(InvalidLiquidityAdsDuration(channelId, requestFunds.leaseDuration))
      }
    }
  }

  def offerLease_opt(nodeParams: NodeParams, channelId: ByteVector32, fundingScript: ByteVector, fundingFeerate: FeeratePerKw, requestFunds_opt: Option[ChannelTlv.RequestFunds]): Either[ChannelException, Option[WillFundLease]] = {
    (nodeParams.liquidityAdsConfig_opt, requestFunds_opt) match {
      case (Some(sellerConfig), Some(requestFunds)) => sellerConfig.offerLease(nodeParams.privateKey, channelId, nodeParams.currentBlockHeight, fundingScript, fundingFeerate, requestFunds) match {
        case Left(t) => Left(t)
        case Right(rates) => Right(Some(rates))
      }
      case _ => Right(None)
    }
  }

  case class RequestRemoteFundingParams(fundingAmount: Satoshi, leaseDuration: Int, maxFee: Satoshi) {
    def withLeaseStart(leaseStart: BlockHeight): RequestRemoteFunding = RequestRemoteFunding(fundingAmount, maxFee, leaseStart, leaseDuration)
  }

  /** Request inbound liquidity from a remote peer that supports liquidity ads. */
  case class RequestRemoteFunding(fundingAmount: Satoshi, maxFee: Satoshi, leaseStart: BlockHeight, leaseDuration: Int) {
    private val leaseExpiry: BlockHeight = leaseStart + leaseDuration
    val requestFunds: ChannelTlv.RequestFunds = ChannelTlv.RequestFunds(fundingAmount, leaseDuration, leaseExpiry)

    def validateLease(remoteNodeId: PublicKey,
                      channelId: ByteVector32,
                      fundingScript: ByteVector,
                      remoteFundingAmount: Satoshi,
                      fundingFeerate: FeeratePerKw,
                      willFund_opt: Option[ChannelTlv.WillFund]): Either[ChannelException, Lease] = {
      willFund_opt match {
        case Some(willFund) =>
          val leaseRate = willFund.leaseRate(leaseDuration)
          val witness = LeaseWitness(fundingScript, leaseDuration, leaseExpiry, leaseRate)
          val fees = leaseRate.fees(fundingFeerate, fundingAmount, remoteFundingAmount)
          if (!witness.verify(remoteNodeId, willFund.sig)) {
            Left(InvalidLiquidityAdsSig(channelId))
          } else if (remoteFundingAmount < fundingAmount) {
            Left(InvalidLiquidityAdsAmount(channelId, remoteFundingAmount, fundingAmount))
          } else if (maxFee < fees.total) {
            Left(InvalidLiquidityRates(channelId))
          } else {
            val leaseAmount = fundingAmount.min(remoteFundingAmount)
            Right(Lease(leaseAmount, fees, willFund.sig, witness))
          }
        case None =>
          // If the remote peer doesn't want to provide inbound liquidity, we immediately fail the attempt.
          // The node operator may retry this funding attempt without requesting inbound liquidity.
          Left(MissingLiquidityAds(channelId))
      }
    }
  }

  def validateLease_opt(requestRemoteFunding_opt: Option[RequestRemoteFunding],
                        remoteNodeId: PublicKey,
                        channelId: ByteVector32,
                        fundingScript: ByteVector,
                        remoteFundingAmount: Satoshi,
                        fundingFeerate: FeeratePerKw,
                        willFund_opt: Option[ChannelTlv.WillFund]): Either[ChannelException, Option[Lease]] = {
    requestRemoteFunding_opt match {
      case Some(requestRemoteFunding) => requestRemoteFunding.validateLease(remoteNodeId, channelId, fundingScript, remoteFundingAmount, fundingFeerate, willFund_opt) match {
        case Left(t) => Left(t)
        case Right(lease) => Right(Some(lease))
      }
      case None => Right(None)
    }
  }

  /** We propose adding funds to a channel for an optional fee at the given rates. */
  case class AddFunding(fundingAmount: Satoshi, leaseRate_opt: Option[LeaseRate]) {
    def signLease(nodeKey: PrivateKey, fundingScript: ByteVector, fundingFeerate: FeeratePerKw, requestFunds_opt: Option[ChannelTlv.RequestFunds]): Option[WillFundLease] = for {
      rate <- leaseRate_opt
      request <- requestFunds_opt
    } yield {
      rate.signLease(nodeKey, fundingScript, fundingFeerate, request, Some(fundingAmount))
    }
  }

  /**
   * Liquidity is leased using the following rates:
   *
   *  - the buyer pays [[leaseFeeBase]] regardless of the amount contributed by the seller
   *  - the buyer pays [[leaseFeeProportional]] (expressed in basis points) of the amount contributed by the seller
   *  - the seller will have to add inputs/outputs to the transaction and pay on-chain fees for them, but the buyer
   *    refunds on-chain fees for [[fundingWeight]] vbytes
   *
   * The seller promises that their relay fees towards the buyer will never exceed [[maxRelayFeeBase]] and [[maxRelayFeeProportional]].
   * This cannot be enforced, but if the buyer notices the seller cheating, they should blacklist them and can prove
   * that they misbehaved using the seller's signature of the [[LeaseWitness]].
   */
  case class LeaseRate(leaseDuration: Int, fundingWeight: Int, leaseFeeProportional: Int, leaseFeeBase: Satoshi, maxRelayFeeProportional: Int, maxRelayFeeBase: MilliSatoshi) {
    /**
     * Fees paid by the liquidity buyer: the resulting amount must be added to the seller's output in the corresponding
     * commitment transaction.
     */
    def fees(feerate: FeeratePerKw, requestedAmount: Satoshi, contributedAmount: Satoshi): LeaseFees = {
      val onChainFees = Transactions.weight2fee(feerate, fundingWeight)
      // If the seller adds more liquidity than requested, the buyer doesn't pay for that extra liquidity.
      val proportionalFee = requestedAmount.min(contributedAmount).toMilliSatoshi * leaseFeeProportional / 10_000
      LeaseFees(onChainFees, leaseFeeBase + proportionalFee.truncateToSatoshi)
    }

    /**
     * @param fundingAmountOverride_opt this field should be provided if we contribute a different amount than what was requested.
     */
    def signLease(nodeKey: PrivateKey,
                  fundingScript: ByteVector,
                  fundingFeerate: FeeratePerKw,
                  requestFunds: ChannelTlv.RequestFunds,
                  fundingAmountOverride_opt: Option[Satoshi] = None): WillFundLease = {
      val witness = LeaseWitness(fundingScript, requestFunds.leaseDuration, requestFunds.leaseExpiry, this)
      val sig = witness.sign(nodeKey)
      val fundingAmount = fundingAmountOverride_opt.getOrElse(requestFunds.amount)
      val leaseFees = fees(fundingFeerate, requestFunds.amount, fundingAmount)
      val leaseAmount = fundingAmount.min(requestFunds.amount)
      val willFund = ChannelTlv.WillFund(sig, fundingWeight, leaseFeeProportional, leaseFeeBase, maxRelayFeeProportional, maxRelayFeeBase)
      WillFundLease(willFund, Lease(leaseAmount, leaseFees, sig, witness))
    }
  }

  object LeaseRate {
    val codec: Codec[LeaseRate] = (
      ("lease_duration" | uint16) ::
        ("funding_weight" | uint16) ::
        ("lease_fee_basis" | uint16) ::
        ("lease_fee_base_sat" | satoshi32) ::
        ("channel_fee_basis_max" | uint16) ::
        ("channel_fee_max_base_msat" | millisatoshi32)
      ).as[LeaseRate]
  }

  /**
   * The seller signs the lease parameters: if they raise their channel routing fees higher than what they advertised,
   * the buyer can use that signature to prove that they cheated.
   */
  case class LeaseWitness(fundingScript: ByteVector, leaseDuration: Int, leaseEnd: BlockHeight, maxRelayFeeProportional: Int, maxRelayFeeBase: MilliSatoshi) {
    def sign(nodeKey: PrivateKey): ByteVector64 = {
      Crypto.sign(Crypto.sha256(LeaseWitness.codec.encode(this).require.bytes), nodeKey)
    }

    def verify(nodeId: PublicKey, sig: ByteVector64): Boolean = {
      Crypto.verifySignature(Crypto.sha256(LeaseWitness.codec.encode(this).require.bytes), sig, nodeId)
    }
  }

  object LeaseWitness {
    def apply(fundingScript: ByteVector, leaseDuration: Int, leaseEnd: BlockHeight, leaseRate: LeaseRate): LeaseWitness = {
      LeaseWitness(fundingScript, leaseDuration, leaseEnd, leaseRate.maxRelayFeeProportional, leaseRate.maxRelayFeeBase)
    }

    val codec: Codec[LeaseWitness] = (
      ("tag" | constant(ByteVector("option_will_fund".getBytes(StandardCharsets.US_ASCII)))) ::
        ("funding_script" | variableSizeBytes(uint16, bytes)) ::
        ("lease_duration" | uint16) ::
        ("lease_end" | blockHeight) ::
        ("channel_fee_max_basis" | uint16) ::
        ("channel_fee_max_base_msat" | millisatoshi32)
      ).as[LeaseWitness]
  }

  case class LeaseFees(miningFee: Satoshi, serviceFee: Satoshi) {
    val total: Satoshi = miningFee + serviceFee
  }

  /**
   * Once a liquidity ads has been paid, we should keep track of the lease, and check that our peer doesn't raise their
   * routing fees above the values they signed up for.
   */
  case class Lease(amount: Satoshi, fees: LeaseFees, sellerSig: ByteVector64, witness: LeaseWitness) {
    val start: BlockHeight = witness.leaseEnd - witness.leaseDuration
    val expiry: BlockHeight = witness.leaseEnd
    val maxRelayFees: RelayFees = RelayFees(witness.maxRelayFeeBase, witness.maxRelayFeeProportional.toLong * 100)
  }

  case class WillFundLease(willFund: ChannelTlv.WillFund, lease: Lease)

}
