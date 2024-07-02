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
import fr.acinq.eclair.wire.protocol.TlvCodecs.{genericTlv, tlvField, tsatoshi32}
import fr.acinq.eclair.{MilliSatoshi, ToMilliSatoshiConversion, UInt64}
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._

/**
 * Created by t-bast on 12/04/2024.
 */

/**
 * Liquidity ads create a decentralized market for channel liquidity.
 * Nodes advertise fee rates for their available liquidity using the gossip protocol.
 * Other nodes can then pay the advertised rate to get inbound liquidity allocated towards them.
 */
object LiquidityAds {

  /**
   * @param miningFee  we refund the liquidity provider for some of the fee they paid to miners for the underlying on-chain transaction.
   * @param serviceFee fee paid to the liquidity provider for the inbound liquidity.
   */
  case class LeaseFees(miningFee: Satoshi, serviceFee: Satoshi) {
    val total: Satoshi = miningFee + serviceFee
  }

  /** Fees paid for the funding transaction that provides liquidity. */
  case class FundingFee(amount: MilliSatoshi, fundingTxId: TxId)

  sealed trait FundingLease extends Tlv {
    /** Fees paid by the liquidity buyer. */
    def fees(feerate: FeeratePerKw, requestedAmount: Satoshi, contributedAmount: Satoshi): LeaseFees

    /** Return true if this lease is compatible with the requested funding amount. */
    def isCompatible(requestedAmount: Satoshi): Boolean
  }

  object FundingLease {
    /**
     * Liquidity fees are computed based on multiple components.
     *
     * @param minAmount            minimum amount that can be purchased at this [rate].
     * @param maxAmount            maximum amount that can be purchased at this [rate].
     * @param fundingWeight        the seller will have to add inputs/outputs to the transaction and pay on-chain fees
     *                             for them. The buyer refunds those on-chain fees for the given vbytes.
     * @param leaseFeeProportional proportional fee (expressed in basis points) based on the amount contributed by the seller.
     * @param leaseFeeBase         flat fee that must be paid regardless of the amount contributed by the seller.
     */
    case class Basic(minAmount: Satoshi, maxAmount: Satoshi, fundingWeight: Int, leaseFeeProportional: Int, leaseFeeBase: Satoshi) extends FundingLease {
      override def fees(feerate: FeeratePerKw, requestedAmount: Satoshi, contributedAmount: Satoshi): LeaseFees = {
        val onChainFees = Transactions.weight2fee(feerate, fundingWeight)
        // If the seller adds more liquidity than requested, the buyer doesn't pay for that extra liquidity.
        val proportionalFee = requestedAmount.min(contributedAmount).toMilliSatoshi * leaseFeeProportional / 10_000
        LeaseFees(onChainFees, leaseFeeBase + proportionalFee.truncateToSatoshi)
      }

      override def isCompatible(requestedAmount: Satoshi): Boolean = minAmount <= requestedAmount && requestedAmount <= maxAmount
    }
  }

  sealed trait FundingLeaseWitness extends Tlv {
    def fundingScript: ByteVector

    def signedData(): ByteVector32 = {
      val tag = this match {
        case _: FundingLeaseWitness.Basic => "basic_funding_lease"
      }
      Crypto.sha256(ByteVector(tag.getBytes(Charsets.US_ASCII)) ++ Codecs.fundingLeaseWitness.encode(this).require.bytes)
    }
  }

  object FundingLeaseWitness {
    case class Basic(fundingScript: ByteVector) extends FundingLeaseWitness
  }

  /** The fees associated with a given [[FundingLease]] can be paid using various options. */
  sealed trait PaymentType {
    // @formatter:off
    def rfcName: String
    override def toString: String = rfcName
    // @formatter:on
  }

  object PaymentType {
    // @formatter:off
    /** Fees are transferred from the buyer's channel balance to the seller's during the interactive-tx construction. */
    case object FromChannelBalance extends PaymentType { override val rfcName: String = "from_channel_balance" }
    /** Fees will be deducted from future HTLCs that will be relayed to the buyer. */
    case object FromFutureHtlc extends PaymentType { override val rfcName: String = "from_future_htlc" }
    /** Fees will be deducted from future HTLCs that will be relayed to the buyer, but the preimage is revealed immediately. */
    case object FromFutureHtlcWithPreimage extends PaymentType { override val rfcName: String = "from_future_htlc_with_preimage" }
    /** Similar to [[FromChannelBalance]] but expects HTLCs to be relayed after funding. */
    case object FromChannelBalanceForFutureHtlc extends PaymentType { override val rfcName: String = "from_channel_balance_for_future_htlc" }
    /** Sellers may support unknown payment types, which we must ignore. */
    case class Unknown(bitIndex: Int) extends PaymentType { override val rfcName: String = s"unknown_$bitIndex" }
    // @formatter:on
  }

  /** When purchasing a [[FundingLease]], we provide payment details matching one of the [[PaymentType]] supported by the seller. */
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
  case class WillFundRates(fundingRates: List[FundingLease], paymentTypes: Set[PaymentType]) {
    def validateRequest(nodeKey: PrivateKey, channelId: ByteVector32, fundingScript: ByteVector, fundingFeerate: FeeratePerKw, request: RequestFunding): Either[ChannelException, WillFundLease] = {
      if (!paymentTypes.contains(request.paymentDetails.paymentType)) {
        Left(InvalidLiquidityAdsPaymentType(channelId, request.paymentDetails.paymentType, paymentTypes))
      } else if (!fundingRates.contains(request.fundingLease)) {
        Left(InvalidLiquidityAdsLease(channelId))
      } else if (!request.fundingLease.isCompatible(request.requestedAmount)) {
        Left(InvalidLiquidityAdsLease(channelId))
      } else {
        val witness = request.fundingLease match {
          case _: FundingLease.Basic => FundingLeaseWitness.Basic(fundingScript)
        }
        val sig = Crypto.sign(witness.signedData(), nodeKey)
        val lease = Lease(request.requestedAmount, request.fundingLease.fees(fundingFeerate, request.requestedAmount, request.requestedAmount), request.paymentDetails, sig, witness)
        Right(WillFundLease(WillFund(witness, sig), lease))
      }
    }

    def findLease(requestedAmount: Satoshi): Option[FundingLease] = {
      fundingRates.find {
        case l: FundingLease.Basic => l.minAmount <= requestedAmount && requestedAmount <= l.maxAmount
      }
    }
  }

  def validateRequest(nodeKey: PrivateKey, channelId: ByteVector32, fundingScript: ByteVector, fundingFeerate: FeeratePerKw, request_opt: Option[RequestFunding], rates_opt: Option[WillFundRates]): Either[ChannelException, Option[WillFundLease]] = {
    (request_opt, rates_opt) match {
      case (Some(request), Some(rates)) => rates.validateRequest(nodeKey, channelId, fundingScript, fundingFeerate, request).map(l => Some(l))
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

  /** Provide inbound liquidity to a remote peer that wants to purchase a liquidity ads. */
  case class WillFund(leaseWitness: FundingLeaseWitness, signature: ByteVector64)

  /** Request inbound liquidity from a remote peer that supports liquidity ads. */
  case class RequestFunding(requestedAmount: Satoshi, fundingLease: FundingLease, paymentDetails: PaymentDetails) {
    def fees(fundingFeerate: FeeratePerKw): LeaseFees = fundingLease.fees(fundingFeerate, requestedAmount, requestedAmount)

    def validateLease(remoteNodeId: PublicKey,
                      channelId: ByteVector32,
                      fundingScript: ByteVector,
                      remoteFundingAmount: Satoshi,
                      fundingFeerate: FeeratePerKw,
                      willFund_opt: Option[WillFund]): Either[ChannelException, Lease] = {
      willFund_opt match {
        case Some(willFund) =>
          if (willFund.leaseWitness.fundingScript != fundingScript) {
            Left(InvalidLiquidityAdsSig(channelId))
          } else if (!Crypto.verifySignature(willFund.leaseWitness.signedData(), willFund.signature, remoteNodeId)) {
            Left(InvalidLiquidityAdsSig(channelId))
          } else if (remoteFundingAmount < requestedAmount) {
            Left(InvalidLiquidityAdsAmount(channelId, remoteFundingAmount, requestedAmount))
          } else {
            val leaseAmount = requestedAmount.min(remoteFundingAmount)
            val leaseFees = fundingLease.fees(fundingFeerate, requestedAmount, remoteFundingAmount)
            Right(Lease(leaseAmount, leaseFees, paymentDetails, willFund.signature, willFund.leaseWitness))
          }
        case None =>
          // If the remote peer doesn't want to provide inbound liquidity, we immediately fail the attempt.
          // The user should retry this funding attempt without requesting inbound liquidity.
          Left(MissingLiquidityAds(channelId))
      }
    }
  }

  def validateLease(request_opt: Option[RequestFunding],
                    remoteNodeId: PublicKey,
                    channelId: ByteVector32,
                    fundingScript: ByteVector,
                    remoteFundingAmount: Satoshi,
                    fundingFeerate: FeeratePerKw,
                    willFund_opt: Option[WillFund]): Either[ChannelException, Option[Lease]] = {
    request_opt match {
      case Some(request) => request.validateLease(remoteNodeId, channelId, fundingScript, remoteFundingAmount, fundingFeerate, willFund_opt) match {
        case Left(f) => Left(f)
        case Right(lease) => Right(Some(lease))
      }
      case None => Right(None)
    }
  }

  def requestFunding(amount: Satoshi, paymentDetails: PaymentDetails, remoteFundingRates: WillFundRates): Option[RequestFunding] = {
    remoteFundingRates.findLease(amount) match {
      case Some(lease) if remoteFundingRates.paymentTypes.contains(paymentDetails.paymentType) => Some(RequestFunding(amount, lease, paymentDetails))
      case _ => None
    }
  }

  /** Once a liquidity ads has been paid, we keep track of the fees paid and the seller signature. */
  case class Lease(amount: Satoshi, fees: LeaseFees, paymentDetails: PaymentDetails, sellerSig: ByteVector64, witness: FundingLeaseWitness)

  case class WillFundLease(willFund: WillFund, lease: Lease)

  object Codecs {
    private val basicFundingLease: Codec[FundingLease.Basic] = (
      ("minLeaseAmount" | satoshi32) ::
        ("maxLeaseAmount" | satoshi32) ::
        ("fundingWeight" | uint16) ::
        ("leaseFeeBasis" | uint16) ::
        ("leaseFeeBase" | tsatoshi32)
      ).as[FundingLease.Basic]

    private val fundingLease: Codec[FundingLease] = discriminated[FundingLease].by(varint)
      .typecase(UInt64(0), variableSizeBytesLong(varintoverflow, basicFundingLease.complete))

    private val basicFundingLeaseWitness: Codec[FundingLeaseWitness.Basic] = ("fundingScript" | bytes).as[FundingLeaseWitness.Basic]

    val fundingLeaseWitness: Codec[FundingLeaseWitness] = discriminated[FundingLeaseWitness].by(varint)
      .typecase(UInt64(0), tlvField(basicFundingLeaseWitness))

    private val paymentDetails: Codec[PaymentDetails] = discriminated[PaymentDetails].by(varint)
      .typecase(UInt64(0), tlvField(provide(PaymentDetails.FromChannelBalance)))
      .typecase(UInt64(128), tlvField(list(bytes32).as[PaymentDetails.FromFutureHtlc]))
      .typecase(UInt64(129), tlvField(list(bytes32).as[PaymentDetails.FromFutureHtlcWithPreimage]))
      .typecase(UInt64(130), tlvField(list(bytes32).as[PaymentDetails.FromChannelBalanceForFutureHtlc]))

    val requestFunding: Codec[RequestFunding] = (
      ("requestedAmount" | satoshi) ::
        ("fundingLease" | fundingLease) ::
        ("paymentDetails" | paymentDetails)
      ).as[RequestFunding]

    val willFund: Codec[WillFund] = (
      ("leaseWitness" | fundingLeaseWitness) ::
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

    // We filter and ignore unknown lease types.
    private val supportedFundingLeases: Codec[List[FundingLease]] = listOfN(uint16, discriminatorFallback(genericTlv, fundingLease)).xmap(
      _.collect { case Right(lease) => lease },
      _.map(lease => Right(lease)),
    )

    val willFundRates: Codec[WillFundRates] = (
      ("fundingRates" | supportedFundingLeases) ::
        ("paymentTypes" | variableSizeBytes(uint16, paymentTypes))
      ).as[WillFundRates]
  }

}
