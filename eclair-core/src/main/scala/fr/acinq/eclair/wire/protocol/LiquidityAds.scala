/*
 * Copyright 2024 ACINQ SAS
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

import com.google.common.base.Charsets
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto, Satoshi, TxId}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.TlvCodecs.tlvField
import fr.acinq.eclair.{MilliSatoshi, ToMilliSatoshiConversion, UInt64}
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._

/**
 * Created by t-bast on 12/04/2024.
 */

/**
 * Liquidity ads create a decentralized market for channel liquidity.
 * Nodes advertise funding rates for their available liquidity using the gossip protocol.
 * Other nodes can then pay the advertised rate to get inbound liquidity allocated towards them.
 */
object LiquidityAds {

  /**
   * @param miningFee  we refund the liquidity provider for some of the fee they paid to miners for the underlying on-chain transaction.
   * @param serviceFee fee paid to the liquidity provider for the inbound liquidity.
   */
  case class Fees(miningFee: Satoshi, serviceFee: Satoshi) {
    val total: Satoshi = miningFee + serviceFee
  }

  /** Fees paid for the funding transaction that provides liquidity. */
  case class FundingFee(amount: MilliSatoshi, fundingTxId: TxId)

  /**
   * Rate at which a liquidity seller sells its liquidity.
   * Liquidity fees are computed based on multiple components.
   *
   * @param minAmount          minimum amount that can be purchased at this rate.
   * @param maxAmount          maximum amount that can be purchased at this rate.
   * @param fundingWeight      the seller will have to add inputs/outputs to the transaction and pay on-chain fees
   *                           for them. The buyer refunds those on-chain fees for the given vbytes.
   * @param feeProportional    proportional fee (expressed in basis points) based on the amount contributed by the seller.
   * @param feeBase            flat fee that must be paid regardless of the amount contributed by the seller.
   * @param channelCreationFee flat fee that must be paid when a new channel is created.
   */
  case class FundingRate(minAmount: Satoshi, maxAmount: Satoshi, fundingWeight: Int, feeProportional: Int, feeBase: Satoshi, channelCreationFee: Satoshi) {
    /** Fees paid by the liquidity buyer. */
    def fees(feerate: FeeratePerKw, requestedAmount: Satoshi, contributedAmount: Satoshi, isChannelCreation: Boolean): Fees = {
      val onChainFees = Transactions.weight2fee(feerate, fundingWeight)
      // If the seller adds more liquidity than requested, the buyer doesn't pay for that extra liquidity.
      val proportionalFee = requestedAmount.min(contributedAmount).toMilliSatoshi * feeProportional / 10_000
      val flatFee = if (isChannelCreation) channelCreationFee + feeBase else feeBase
      Fees(onChainFees, flatFee + proportionalFee.truncateToSatoshi)
    }

    /** Return true if this rate is compatible with the requested funding amount. */
    def isCompatible(requestedAmount: Satoshi): Boolean = minAmount <= requestedAmount && requestedAmount <= maxAmount

    /** When liquidity is purchased, the seller provides a signature of the funding rate and funding script. */
    def signedData(fundingScript: ByteVector): ByteVector32 = {
      // We use a tagged hash to ensure that our signature cannot be reused in a different context.
      val tag = "liquidity_ads_purchase"
      val fundingRateBin = Codecs.fundingRate.encode(this).require.bytes
      Crypto.sha256(ByteVector(tag.getBytes(Charsets.US_ASCII)) ++ fundingRateBin ++ fundingScript)
    }
  }

  /** The fees associated with a given [[FundingRate]] can be paid using various options. */
  sealed trait PaymentType {
    // @formatter:off
    def rfcName: String
    override def toString: String = rfcName
    // @formatter:on
  }

  /** Payment type used for on-the-fly funding for incoming HTLCs. */
  sealed trait OnTheFlyFundingPaymentType extends PaymentType

  object PaymentType {
    // @formatter:off
    /** Fees are transferred from the buyer's channel balance to the seller's during the interactive-tx construction. */
    case object FromChannelBalance extends PaymentType { override val rfcName: String = "from_channel_balance" }
    /** Fees will be deducted from future HTLCs that will be relayed to the buyer. */
    case object FromFutureHtlc extends OnTheFlyFundingPaymentType { override val rfcName: String = "from_future_htlc" }
    /** Fees will be deducted from future HTLCs that will be relayed to the buyer, but the preimage is revealed immediately. */
    case object FromFutureHtlcWithPreimage extends OnTheFlyFundingPaymentType { override val rfcName: String = "from_future_htlc_with_preimage" }
    /** Similar to [[FromChannelBalance]] but expects HTLCs to be relayed after funding. */
    case object FromChannelBalanceForFutureHtlc extends OnTheFlyFundingPaymentType { override val rfcName: String = "from_channel_balance_for_future_htlc" }
    /** Sellers may support unknown payment types, which we must ignore. */
    case class Unknown(bitIndex: Int) extends PaymentType { override val rfcName: String = s"unknown_$bitIndex" }
    // @formatter:on
  }

  /** When purchasing liquidity, we provide payment details matching one of the [[PaymentType]]s supported by the seller. */
  sealed trait PaymentDetails extends Tlv {
    def paymentType: PaymentType
  }

  object PaymentDetails {
    // @formatter:off
    case object FromChannelBalance extends PaymentDetails { override val paymentType: PaymentType = PaymentType.FromChannelBalance }
    case class FromFutureHtlc(paymentHashes: List[ByteVector32]) extends PaymentDetails { override val paymentType: PaymentType = PaymentType.FromFutureHtlc }
    case class FromFutureHtlcWithPreimage(preimages: List[ByteVector32]) extends PaymentDetails { override val paymentType: PaymentType = PaymentType.FromFutureHtlcWithPreimage }
    case class FromChannelBalanceForFutureHtlc(paymentHashes: List[ByteVector32]) extends PaymentDetails { override val paymentType: PaymentType = PaymentType.FromChannelBalanceForFutureHtlc }
    // @formatter:on
  }

  /** Sellers offer various rates and payment options. */
  case class WillFundRates(fundingRates: List[FundingRate], paymentTypes: Set[PaymentType]) {
    def validateRequest(nodeKey: PrivateKey, channelId: ByteVector32, fundingScript: ByteVector, fundingFeerate: FeeratePerKw, request: RequestFunding, isChannelCreation: Boolean, feeCreditUsed_opt: Option[MilliSatoshi]): Either[ChannelException, WillFundPurchase] = {
      if (!paymentTypes.contains(request.paymentDetails.paymentType)) {
        Left(InvalidLiquidityAdsPaymentType(channelId, request.paymentDetails.paymentType, paymentTypes))
      } else if (!fundingRates.contains(request.fundingRate)) {
        Left(InvalidLiquidityAdsRate(channelId))
      } else if (!request.fundingRate.isCompatible(request.requestedAmount)) {
        Left(InvalidLiquidityAdsRate(channelId))
      } else {
        val sig = Crypto.sign(request.fundingRate.signedData(fundingScript), nodeKey)
        val fees = request.fundingRate.fees(fundingFeerate, request.requestedAmount, request.requestedAmount, isChannelCreation)
        val purchase = feeCreditUsed_opt match {
          case Some(feeCreditUsed) => Purchase.WithFeeCredit(request.requestedAmount, fees, feeCreditUsed, request.paymentDetails)
          case None => Purchase.Standard(request.requestedAmount, fees, request.paymentDetails)
        }
        Right(WillFundPurchase(WillFund(request.fundingRate, fundingScript, sig), purchase))
      }
    }

    def findRate(requestedAmount: Satoshi): Option[FundingRate] = fundingRates.find(r => r.minAmount <= requestedAmount && requestedAmount <= r.maxAmount)
  }

  def validateRequest(nodeKey: PrivateKey, channelId: ByteVector32, fundingScript: ByteVector, fundingFeerate: FeeratePerKw, isChannelCreation: Boolean, request_opt: Option[RequestFunding], rates_opt: Option[WillFundRates], feeCreditUsed_opt: Option[MilliSatoshi]): Either[ChannelException, Option[WillFundPurchase]] = {
    (request_opt, rates_opt) match {
      case (Some(request), Some(rates)) => rates.validateRequest(nodeKey, channelId, fundingScript, fundingFeerate, request, isChannelCreation, feeCreditUsed_opt).map(l => Some(l))
      case _ => Right(None)
    }
  }

  /**
   * Add funds to a channel when we're not the funding initiator.
   *
   * @param fundingAmount amount to add.
   * @param rates_opt     if provided, liquidity rates applied to our [[fundingAmount]] (otherwise we fund for free).
   */
  case class AddFunding(fundingAmount: Satoshi, rates_opt: Option[WillFundRates])

  /** Provide inbound liquidity to a remote peer that wants to purchase liquidity. */
  case class WillFund(fundingRate: FundingRate, fundingScript: ByteVector, signature: ByteVector64)

  /** Request inbound liquidity from a remote peer that supports liquidity ads. */
  case class RequestFunding(requestedAmount: Satoshi, fundingRate: FundingRate, paymentDetails: PaymentDetails) {
    def fees(fundingFeerate: FeeratePerKw, isChannelCreation: Boolean): Fees = fundingRate.fees(fundingFeerate, requestedAmount, requestedAmount, isChannelCreation)

    def validateRemoteFunding(remoteNodeId: PublicKey,
                              channelId: ByteVector32,
                              fundingScript: ByteVector,
                              remoteFundingAmount: Satoshi,
                              fundingFeerate: FeeratePerKw,
                              isChannelCreation: Boolean,
                              willFund_opt: Option[WillFund]): Either[ChannelException, Purchase] = {
      willFund_opt match {
        case Some(willFund) =>
          if (!Crypto.verifySignature(fundingRate.signedData(fundingScript), willFund.signature, remoteNodeId)) {
            Left(InvalidLiquidityAdsSig(channelId))
          } else if (remoteFundingAmount < requestedAmount) {
            Left(InvalidLiquidityAdsAmount(channelId, remoteFundingAmount, requestedAmount))
          } else if (willFund.fundingRate != fundingRate) {
            Left(InvalidLiquidityAdsRate(channelId))
          } else {
            val purchasedAmount = requestedAmount.min(remoteFundingAmount)
            val fees = fundingRate.fees(fundingFeerate, requestedAmount, remoteFundingAmount, isChannelCreation)
            Right(Purchase.Standard(purchasedAmount, fees, paymentDetails))
          }
        case None =>
          // If the remote peer doesn't want to provide inbound liquidity, we immediately fail the attempt.
          // The user should retry this funding attempt without requesting inbound liquidity.
          Left(MissingLiquidityAds(channelId))
      }
    }
  }

  def validateRemoteFunding(request_opt: Option[RequestFunding],
                            remoteNodeId: PublicKey,
                            channelId: ByteVector32,
                            fundingScript: ByteVector,
                            remoteFundingAmount: Satoshi,
                            fundingFeerate: FeeratePerKw,
                            isChannelCreation: Boolean,
                            willFund_opt: Option[WillFund]): Either[ChannelException, Option[Purchase]] = {
    request_opt match {
      case Some(request) => request.validateRemoteFunding(remoteNodeId, channelId, fundingScript, remoteFundingAmount, fundingFeerate, isChannelCreation, willFund_opt) match {
        case Left(f) => Left(f)
        case Right(purchase) => Right(Some(purchase))
      }
      case None => Right(None)
    }
  }

  def requestFunding(amount: Satoshi, paymentDetails: PaymentDetails, remoteFundingRates: WillFundRates): Option[RequestFunding] = {
    remoteFundingRates.findRate(amount) match {
      case Some(fundingRate) if remoteFundingRates.paymentTypes.contains(paymentDetails.paymentType) => Some(RequestFunding(amount, fundingRate, paymentDetails))
      case _ => None
    }
  }

  /** Once a liquidity ads has been purchased, we keep track of the fees paid and the payment details. */
  sealed trait Purchase {
    // @formatter:off
    def amount: Satoshi
    def fees: Fees
    def paymentDetails: PaymentDetails
    def basicInfo(isBuyer: Boolean): PurchaseBasicInfo = PurchaseBasicInfo(isBuyer, amount, fees)
    // @formatter:on
  }

  object Purchase {
    // @formatter:off
    case class Standard(amount: Satoshi, fees: Fees, paymentDetails: PaymentDetails) extends Purchase()
    /** The liquidity purchase was paid (partially or entirely) using [[fr.acinq.eclair.Features.FundingFeeCredit]]. */
    case class WithFeeCredit(amount: Satoshi, fees: Fees, feeCreditUsed: MilliSatoshi, paymentDetails: PaymentDetails) extends Purchase()
    // @formatter:on
  }

  case class WillFundPurchase(willFund: WillFund, purchase: Purchase)

  /** Minimal information about a liquidity purchase. */
  case class PurchaseBasicInfo(isBuyer: Boolean, amount: Satoshi, fees: Fees)

  object Codecs {
    val fundingRate: Codec[FundingRate] = (
      ("minAmount" | satoshi32) ::
        ("maxAmount" | satoshi32) ::
        ("fundingWeight" | uint16) ::
        ("feeBasis" | uint16) ::
        ("feeBase" | satoshi32) ::
        ("channelCreationFee" | satoshi32)
      ).as[FundingRate]

    private val paymentDetails: Codec[PaymentDetails] = discriminated[PaymentDetails].by(varint)
      .typecase(UInt64(0), tlvField(provide(PaymentDetails.FromChannelBalance)))
      .typecase(UInt64(128), tlvField(list(bytes32).as[PaymentDetails.FromFutureHtlc]))
      .typecase(UInt64(129), tlvField(list(bytes32).as[PaymentDetails.FromFutureHtlcWithPreimage]))
      .typecase(UInt64(130), tlvField(list(bytes32).as[PaymentDetails.FromChannelBalanceForFutureHtlc]))

    val requestFunding: Codec[RequestFunding] = (
      ("requestedAmount" | satoshi) ::
        ("fundingRate" | fundingRate) ::
        ("paymentDetails" | paymentDetails)
      ).as[RequestFunding]

    val willFund: Codec[WillFund] = (
      ("fundingRate" | fundingRate) ::
        ("fundingScript" | variableSizeBytes(uint16, bytes)) ::
        ("signature" | bytes64)
      ).as[WillFund]

    private val paymentTypes: Codec[Set[PaymentType]] = bytes.xmap(
      f = { bytes =>
        bytes.bits.toIndexedSeq.reverse.zipWithIndex.collect {
          case (true, 0) => PaymentType.FromChannelBalance
          case (true, 128) => PaymentType.FromFutureHtlc
          case (true, 129) => PaymentType.FromFutureHtlcWithPreimage
          case (true, 130) => PaymentType.FromChannelBalanceForFutureHtlc
          case (true, idx) => PaymentType.Unknown(idx)
        }.toSet
      },
      g = { paymentTypes =>
        val indexes = paymentTypes.collect {
          case PaymentType.FromChannelBalance => 0
          case PaymentType.FromFutureHtlc => 128
          case PaymentType.FromFutureHtlcWithPreimage => 129
          case PaymentType.FromChannelBalanceForFutureHtlc => 130
          case PaymentType.Unknown(idx) => idx
        }
        // When converting from BitVector to ByteVector, scodec pads right instead of left, so we make sure we pad to bytes *before* setting bits.
        var buf = BitVector.fill(indexes.max + 1)(high = false).bytes.bits
        indexes.foreach { i => buf = buf.set(i) }
        buf.reverse.bytes
      }
    )

    val willFundRates: Codec[WillFundRates] = (
      ("fundingRates" | listOfN(uint16, fundingRate)) ::
        ("paymentTypes" | variableSizeBytes(uint16, paymentTypes))
      ).as[WillFundRates]
  }

}
