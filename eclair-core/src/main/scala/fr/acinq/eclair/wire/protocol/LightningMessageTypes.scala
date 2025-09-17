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

package fr.acinq.eclair.wire.protocol

import com.google.common.base.Charsets
import com.google.common.net.InetAddresses
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{BlockHash, ByteVector32, ByteVector64, OutPoint, Satoshi, SatoshiLong, ScriptWitness, Transaction, TxId, TxOut}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.ChannelSpendSignature.{IndividualSignature, PartialSignatureWithNonce}
import fr.acinq.eclair.channel.{ChannelFlags, ChannelSpendSignature, ChannelType}
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.transactions.Transactions.InputInfo
import fr.acinq.eclair.wire.protocol.ChannelReadyTlv.ShortChannelIdTlv
import fr.acinq.eclair.{Alias, BlockHeight, CltvExpiry, CltvExpiryDelta, Feature, Features, InitFeature, MilliSatoshi, MilliSatoshiLong, RealShortChannelId, ShortChannelId, TimestampSecond, UInt64, isAsciiPrintable}
import scodec.bits.ByteVector

import java.net.{Inet4Address, Inet6Address, InetAddress}
import java.nio.charset.StandardCharsets
import scala.util.Try

/**
 * Created by PM on 15/11/2016.
 */

// @formatter:off
sealed trait LightningMessage extends Serializable
sealed trait SetupMessage extends LightningMessage
sealed trait ChannelMessage extends LightningMessage
sealed trait InteractiveTxMessage extends LightningMessage
sealed trait InteractiveTxConstructionMessage extends InteractiveTxMessage // <- not in the spec
sealed trait HtlcMessage extends LightningMessage
sealed trait RoutingMessage extends LightningMessage
sealed trait AnnouncementMessage extends RoutingMessage // <- not in the spec
sealed trait OnTheFlyFundingMessage extends LightningMessage { def paymentHash: ByteVector32 }
sealed trait OnTheFlyFundingFailureMessage extends OnTheFlyFundingMessage { def id: ByteVector32 }
sealed trait HasTimestamp extends LightningMessage { def timestamp: TimestampSecond }
sealed trait HasTemporaryChannelId extends LightningMessage { def temporaryChannelId: ByteVector32 } // <- not in the spec
sealed trait HasChannelId extends LightningMessage { def channelId: ByteVector32 } // <- not in the spec
sealed trait HasChainHash extends LightningMessage { def chainHash: BlockHash } // <- not in the spec
sealed trait HasSerialId extends LightningMessage { def serialId: UInt64 } // <- not in the spec
sealed trait ForbiddenMessageWhenQuiescent extends LightningMessage // <- not in the spec
sealed trait UpdateMessage extends HtlcMessage with ForbiddenMessageWhenQuiescent // <- not in the spec
sealed trait HtlcSettlementMessage extends UpdateMessage { def id: Long } // <- not in the spec
sealed trait HtlcFailureMessage extends HtlcSettlementMessage // <- not in the spec
// @formatter:on

case class Init(features: Features[InitFeature], tlvStream: TlvStream[InitTlv] = TlvStream.empty) extends SetupMessage {
  val networks: Seq[BlockHash] = tlvStream.get[InitTlv.Networks].map(_.chainHashes).getOrElse(Nil)
  val remoteAddress_opt: Option[NodeAddress] = tlvStream.get[InitTlv.RemoteAddress].map(_.address)
  val fundingRates_opt: Option[LiquidityAds.WillFundRates] = tlvStream.get[InitTlv.OptionWillFund].map(_.rates)
}

case class Warning(channelId: ByteVector32, data: ByteVector, tlvStream: TlvStream[WarningTlv] = TlvStream.empty) extends SetupMessage with HasChannelId {
  def toAscii: String = if (isAsciiPrintable(data)) new String(data.toArray, StandardCharsets.US_ASCII) else "n/a"
}

object Warning {
  // @formatter:off
  def apply(channelId: ByteVector32, msg: String): Warning = Warning(channelId, ByteVector.view(msg.getBytes(Charsets.US_ASCII)))
  def apply(msg: String): Warning = Warning(ByteVector32.Zeroes, ByteVector.view(msg.getBytes(Charsets.US_ASCII)))
  // @formatter:on
}

case class Error(channelId: ByteVector32, data: ByteVector, tlvStream: TlvStream[ErrorTlv] = TlvStream.empty) extends SetupMessage with HasChannelId {
  def toAscii: String = if (isAsciiPrintable(data)) new String(data.toArray, StandardCharsets.US_ASCII) else "n/a"
}

object Error {
  def apply(channelId: ByteVector32, msg: String): Error = Error(channelId, ByteVector.view(msg.getBytes(Charsets.US_ASCII)))
}

case class Ping(pongLength: Int, data: ByteVector, tlvStream: TlvStream[PingTlv] = TlvStream.empty) extends SetupMessage

case class Pong(data: ByteVector, tlvStream: TlvStream[PongTlv] = TlvStream.empty) extends SetupMessage

case class TxAddInput(channelId: ByteVector32,
                      serialId: UInt64,
                      previousTx_opt: Option[Transaction],
                      previousTxOutput: Long,
                      sequence: Long,
                      tlvStream: TlvStream[TxAddInputTlv] = TlvStream.empty) extends InteractiveTxConstructionMessage with HasChannelId with HasSerialId {
  /** This field may replace [[previousTx_opt]] when using taproot. */
  val previousTxOut_opt: Option[InputInfo] = tlvStream.get[TxAddInputTlv.PrevTxOut].map(tlv => InputInfo(OutPoint(tlv.txId, previousTxOutput), TxOut(tlv.amount, tlv.publicKeyScript)))
  val sharedInput_opt: Option[OutPoint] = tlvStream.get[TxAddInputTlv.SharedInputTxId].map(i => OutPoint(i.txId, previousTxOutput))
}

object TxAddInput {
  def apply(channelId: ByteVector32, serialId: UInt64, sharedInput: OutPoint, sequence: Long): TxAddInput = {
    TxAddInput(channelId, serialId, None, sharedInput.index, sequence, TlvStream(TxAddInputTlv.SharedInputTxId(sharedInput.txid)))
  }
}

case class TxAddOutput(channelId: ByteVector32,
                       serialId: UInt64,
                       amount: Satoshi,
                       pubkeyScript: ByteVector,
                       tlvStream: TlvStream[TxAddOutputTlv] = TlvStream.empty) extends InteractiveTxConstructionMessage with HasChannelId with HasSerialId

case class TxRemoveInput(channelId: ByteVector32,
                         serialId: UInt64,
                         tlvStream: TlvStream[TxRemoveInputTlv] = TlvStream.empty) extends InteractiveTxConstructionMessage with HasChannelId with HasSerialId

case class TxRemoveOutput(channelId: ByteVector32,
                          serialId: UInt64,
                          tlvStream: TlvStream[TxRemoveOutputTlv] = TlvStream.empty) extends InteractiveTxConstructionMessage with HasChannelId with HasSerialId

case class TxComplete(channelId: ByteVector32,
                      tlvStream: TlvStream[TxCompleteTlv] = TlvStream.empty) extends InteractiveTxConstructionMessage with HasChannelId {
  val commitNonces_opt: Option[TxCompleteTlv.CommitNonces] = tlvStream.get[TxCompleteTlv.CommitNonces]
  val fundingNonce_opt: Option[IndividualNonce] = tlvStream.get[TxCompleteTlv.FundingInputNonce].map(_.nonce)
}

object TxComplete {
  def apply(channelId: ByteVector32, commitNonce: IndividualNonce, nextCommitNonce: IndividualNonce, fundingNonce_opt: Option[IndividualNonce]): TxComplete = {
    val tlvs = Set(
      Some(TxCompleteTlv.CommitNonces(commitNonce, nextCommitNonce)),
      fundingNonce_opt.map(TxCompleteTlv.FundingInputNonce(_)),
    ).flatten[TxCompleteTlv]
    TxComplete(channelId, TlvStream(tlvs))
  }
}

case class TxSignatures(channelId: ByteVector32,
                        txId: TxId,
                        witnesses: Seq[ScriptWitness],
                        tlvStream: TlvStream[TxSignaturesTlv] = TlvStream.empty) extends InteractiveTxMessage with HasChannelId {
  val previousFundingTxSig_opt: Option[ByteVector64] = tlvStream.get[TxSignaturesTlv.PreviousFundingTxSig].map(_.sig)
  val previousFundingTxPartialSig_opt: Option[PartialSignatureWithNonce] = tlvStream.get[TxSignaturesTlv.PreviousFundingTxPartialSig].map(_.partialSigWithNonce)
}

object TxSignatures {
  def apply(channelId: ByteVector32, tx: Transaction, witnesses: Seq[ScriptWitness], previousFundingSig_opt: Option[ChannelSpendSignature]): TxSignatures = {
    val tlvs: Set[TxSignaturesTlv] = Set(
      previousFundingSig_opt.map {
        case IndividualSignature(sig) => TxSignaturesTlv.PreviousFundingTxSig(sig)
        case partialSig: PartialSignatureWithNonce => TxSignaturesTlv.PreviousFundingTxPartialSig(partialSig)
      }
    ).flatten
    TxSignatures(channelId, tx.txid, witnesses, TlvStream(tlvs))
  }
}

case class TxInitRbf(channelId: ByteVector32,
                     lockTime: Long,
                     feerate: FeeratePerKw,
                     tlvStream: TlvStream[TxInitRbfTlv] = TlvStream.empty) extends InteractiveTxMessage with HasChannelId {
  val fundingContribution: Satoshi = tlvStream.get[TxRbfTlv.SharedOutputContributionTlv].map(_.amount).getOrElse(0 sat)
  val requireConfirmedInputs: Boolean = tlvStream.get[ChannelTlv.RequireConfirmedInputsTlv].nonEmpty
  val requestFunding_opt: Option[LiquidityAds.RequestFunding] = tlvStream.get[ChannelTlv.RequestFundingTlv].map(_.request)
}

object TxInitRbf {
  def apply(channelId: ByteVector32, lockTime: Long, feerate: FeeratePerKw, fundingContribution: Satoshi, requireConfirmedInputs: Boolean, requestFunding_opt: Option[LiquidityAds.RequestFunding]): TxInitRbf = {
    val tlvs: Set[TxInitRbfTlv] = Set(
      Some(TxRbfTlv.SharedOutputContributionTlv(fundingContribution)),
      if (requireConfirmedInputs) Some(ChannelTlv.RequireConfirmedInputsTlv()) else None,
      requestFunding_opt.map(ChannelTlv.RequestFundingTlv)
    ).flatten
    TxInitRbf(channelId, lockTime, feerate, TlvStream(tlvs))
  }
}

case class TxAckRbf(channelId: ByteVector32, tlvStream: TlvStream[TxAckRbfTlv] = TlvStream.empty) extends InteractiveTxMessage with HasChannelId {
  val fundingContribution: Satoshi = tlvStream.get[TxRbfTlv.SharedOutputContributionTlv].map(_.amount).getOrElse(0 sat)
  val requireConfirmedInputs: Boolean = tlvStream.get[ChannelTlv.RequireConfirmedInputsTlv].nonEmpty
  val willFund_opt: Option[LiquidityAds.WillFund] = tlvStream.get[ChannelTlv.ProvideFundingTlv].map(_.willFund)
}

object TxAckRbf {
  def apply(channelId: ByteVector32, fundingContribution: Satoshi, requireConfirmedInputs: Boolean, willFund_opt: Option[LiquidityAds.WillFund]): TxAckRbf = {
    val tlvs: Set[TxAckRbfTlv] = Set(
      Some(TxRbfTlv.SharedOutputContributionTlv(fundingContribution)),
      if (requireConfirmedInputs) Some(ChannelTlv.RequireConfirmedInputsTlv()) else None,
      willFund_opt.map(ChannelTlv.ProvideFundingTlv)
    ).flatten
    TxAckRbf(channelId, TlvStream(tlvs))
  }
}

case class TxAbort(channelId: ByteVector32,
                   data: ByteVector,
                   tlvStream: TlvStream[TxAbortTlv] = TlvStream.empty) extends InteractiveTxMessage with HasChannelId {
  def toAscii: String = if (isAsciiPrintable(data)) new String(data.toArray, StandardCharsets.US_ASCII) else "n/a"
}

object TxAbort {
  def apply(channelId: ByteVector32, msg: String): TxAbort = TxAbort(channelId, ByteVector.view(msg.getBytes(Charsets.US_ASCII)))
}

case class ChannelReestablish(channelId: ByteVector32,
                              nextLocalCommitmentNumber: Long,
                              nextRemoteRevocationNumber: Long,
                              yourLastPerCommitmentSecret: PrivateKey,
                              myCurrentPerCommitmentPoint: PublicKey,
                              tlvStream: TlvStream[ChannelReestablishTlv] = TlvStream.empty) extends ChannelMessage with HasChannelId {
  val nextFundingTxId_opt: Option[TxId] = tlvStream.get[ChannelReestablishTlv.NextFundingTlv].map(_.txId)
  val myCurrentFundingLocked_opt: Option[TxId] = tlvStream.get[ChannelReestablishTlv.MyCurrentFundingLockedTlv].map(_.txId)
  val yourLastFundingLocked_opt: Option[TxId] = tlvStream.get[ChannelReestablishTlv.YourLastFundingLockedTlv].map(_.txId)
  val nextCommitNonces: Map[TxId, IndividualNonce] = tlvStream.get[ChannelReestablishTlv.NextLocalNoncesTlv].map(_.nonces.toMap).getOrElse(Map.empty)
  val currentCommitNonce_opt: Option[IndividualNonce] = tlvStream.get[ChannelReestablishTlv.CurrentCommitNonceTlv].map(_.nonce)
}

case class OpenChannel(chainHash: BlockHash,
                       temporaryChannelId: ByteVector32,
                       fundingSatoshis: Satoshi,
                       pushMsat: MilliSatoshi,
                       dustLimitSatoshis: Satoshi,
                       maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                       channelReserveSatoshis: Satoshi,
                       htlcMinimumMsat: MilliSatoshi,
                       feeratePerKw: FeeratePerKw,
                       toSelfDelay: CltvExpiryDelta,
                       maxAcceptedHtlcs: Int,
                       fundingPubkey: PublicKey,
                       revocationBasepoint: PublicKey,
                       paymentBasepoint: PublicKey,
                       delayedPaymentBasepoint: PublicKey,
                       htlcBasepoint: PublicKey,
                       firstPerCommitmentPoint: PublicKey,
                       channelFlags: ChannelFlags,
                       tlvStream: TlvStream[OpenChannelTlv] = TlvStream.empty) extends ChannelMessage with HasTemporaryChannelId with HasChainHash {
  val upfrontShutdownScript_opt: Option[ByteVector] = tlvStream.get[ChannelTlv.UpfrontShutdownScriptTlv].map(_.script)
  val channelType_opt: Option[ChannelType] = tlvStream.get[ChannelTlv.ChannelTypeTlv].map(_.channelType)
  val commitNonce_opt: Option[IndividualNonce] = tlvStream.get[ChannelTlv.NextLocalNonceTlv].map(_.nonce)
}

case class AcceptChannel(temporaryChannelId: ByteVector32,
                         dustLimitSatoshis: Satoshi,
                         maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                         channelReserveSatoshis: Satoshi,
                         htlcMinimumMsat: MilliSatoshi,
                         minimumDepth: Long,
                         toSelfDelay: CltvExpiryDelta,
                         maxAcceptedHtlcs: Int,
                         fundingPubkey: PublicKey,
                         revocationBasepoint: PublicKey,
                         paymentBasepoint: PublicKey,
                         delayedPaymentBasepoint: PublicKey,
                         htlcBasepoint: PublicKey,
                         firstPerCommitmentPoint: PublicKey,
                         tlvStream: TlvStream[AcceptChannelTlv] = TlvStream.empty) extends ChannelMessage with HasTemporaryChannelId {
  val upfrontShutdownScript_opt: Option[ByteVector] = tlvStream.get[ChannelTlv.UpfrontShutdownScriptTlv].map(_.script)
  val channelType_opt: Option[ChannelType] = tlvStream.get[ChannelTlv.ChannelTypeTlv].map(_.channelType)
  val commitNonce_opt: Option[IndividualNonce] = tlvStream.get[ChannelTlv.NextLocalNonceTlv].map(_.nonce)
}

// NB: this message is named open_channel2 in the specification.
case class OpenDualFundedChannel(chainHash: BlockHash,
                                 temporaryChannelId: ByteVector32,
                                 fundingFeerate: FeeratePerKw,
                                 commitmentFeerate: FeeratePerKw,
                                 fundingAmount: Satoshi,
                                 dustLimit: Satoshi,
                                 maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                                 htlcMinimum: MilliSatoshi,
                                 toSelfDelay: CltvExpiryDelta,
                                 maxAcceptedHtlcs: Int,
                                 lockTime: Long,
                                 fundingPubkey: PublicKey,
                                 revocationBasepoint: PublicKey,
                                 paymentBasepoint: PublicKey,
                                 delayedPaymentBasepoint: PublicKey,
                                 htlcBasepoint: PublicKey,
                                 firstPerCommitmentPoint: PublicKey,
                                 secondPerCommitmentPoint: PublicKey,
                                 channelFlags: ChannelFlags,
                                 tlvStream: TlvStream[OpenDualFundedChannelTlv] = TlvStream.empty) extends ChannelMessage with HasTemporaryChannelId with HasChainHash {
  val upfrontShutdownScript_opt: Option[ByteVector] = tlvStream.get[ChannelTlv.UpfrontShutdownScriptTlv].map(_.script)
  val channelType_opt: Option[ChannelType] = tlvStream.get[ChannelTlv.ChannelTypeTlv].map(_.channelType)
  val requireConfirmedInputs: Boolean = tlvStream.get[ChannelTlv.RequireConfirmedInputsTlv].nonEmpty
  val requestFunding_opt: Option[LiquidityAds.RequestFunding] = tlvStream.get[ChannelTlv.RequestFundingTlv].map(_.request)
  val usesOnTheFlyFunding: Boolean = requestFunding_opt.exists(_.paymentDetails.paymentType.isInstanceOf[LiquidityAds.OnTheFlyFundingPaymentType])
  val useFeeCredit_opt: Option[MilliSatoshi] = tlvStream.get[ChannelTlv.UseFeeCredit].map(_.amount)
  val pushAmount: MilliSatoshi = tlvStream.get[ChannelTlv.PushAmountTlv].map(_.amount).getOrElse(0 msat)
}

// NB: this message is named accept_channel2 in the specification.
case class AcceptDualFundedChannel(temporaryChannelId: ByteVector32,
                                   fundingAmount: Satoshi,
                                   dustLimit: Satoshi,
                                   maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                                   htlcMinimum: MilliSatoshi,
                                   minimumDepth: Long,
                                   toSelfDelay: CltvExpiryDelta,
                                   maxAcceptedHtlcs: Int,
                                   fundingPubkey: PublicKey,
                                   revocationBasepoint: PublicKey,
                                   paymentBasepoint: PublicKey,
                                   delayedPaymentBasepoint: PublicKey,
                                   htlcBasepoint: PublicKey,
                                   firstPerCommitmentPoint: PublicKey,
                                   secondPerCommitmentPoint: PublicKey,
                                   tlvStream: TlvStream[AcceptDualFundedChannelTlv] = TlvStream.empty) extends ChannelMessage with HasTemporaryChannelId {
  val upfrontShutdownScript_opt: Option[ByteVector] = tlvStream.get[ChannelTlv.UpfrontShutdownScriptTlv].map(_.script)
  val channelType_opt: Option[ChannelType] = tlvStream.get[ChannelTlv.ChannelTypeTlv].map(_.channelType)
  val requireConfirmedInputs: Boolean = tlvStream.get[ChannelTlv.RequireConfirmedInputsTlv].nonEmpty
  val willFund_opt: Option[LiquidityAds.WillFund] = tlvStream.get[ChannelTlv.ProvideFundingTlv].map(_.willFund)
  val pushAmount: MilliSatoshi = tlvStream.get[ChannelTlv.PushAmountTlv].map(_.amount).getOrElse(0 msat)
}

case class FundingCreated(temporaryChannelId: ByteVector32,
                          fundingTxId: TxId,
                          fundingOutputIndex: Int,
                          signature: ByteVector64,
                          tlvStream: TlvStream[FundingCreatedTlv] = TlvStream.empty) extends ChannelMessage with HasTemporaryChannelId {
  val sigOrPartialSig: ChannelSpendSignature = tlvStream.get[ChannelTlv.PartialSignatureWithNonceTlv].map(_.partialSigWithNonce).getOrElse(IndividualSignature(signature))
}

object FundingCreated {
  def apply(temporaryChannelId: ByteVector32, fundingTxId: TxId, fundingOutputIndex: Int, sig: ChannelSpendSignature): FundingCreated = {
    val individualSig = sig match {
      case IndividualSignature(sig) => sig
      case _: PartialSignatureWithNonce => ByteVector64.Zeroes
    }
    val tlvs = sig match {
      case _: IndividualSignature => TlvStream.empty[FundingCreatedTlv]
      case psig: PartialSignatureWithNonce => TlvStream[FundingCreatedTlv](ChannelTlv.PartialSignatureWithNonceTlv(psig))
    }
    FundingCreated(temporaryChannelId, fundingTxId, fundingOutputIndex, individualSig, tlvs)
  }
}

case class FundingSigned(channelId: ByteVector32,
                         signature: ByteVector64,
                         tlvStream: TlvStream[FundingSignedTlv] = TlvStream.empty) extends ChannelMessage with HasChannelId {
  val sigOrPartialSig: ChannelSpendSignature = tlvStream.get[ChannelTlv.PartialSignatureWithNonceTlv].map(_.partialSigWithNonce).getOrElse(IndividualSignature(signature))
}

object FundingSigned {
  def apply(channelId: ByteVector32, sig: ChannelSpendSignature): FundingSigned = {
    val individualSig = sig match {
      case IndividualSignature(sig) => sig
      case _: PartialSignatureWithNonce => ByteVector64.Zeroes
    }
    val tlvs = sig match {
      case _: IndividualSignature => TlvStream.empty[FundingSignedTlv]
      case psig: PartialSignatureWithNonce => TlvStream[FundingSignedTlv](ChannelTlv.PartialSignatureWithNonceTlv(psig))
    }
    FundingSigned(channelId, individualSig, tlvs)
  }
}

case class ChannelReady(channelId: ByteVector32,
                        nextPerCommitmentPoint: PublicKey,
                        tlvStream: TlvStream[ChannelReadyTlv] = TlvStream.empty) extends ChannelMessage with HasChannelId {
  val alias_opt: Option[Alias] = tlvStream.get[ShortChannelIdTlv].map(_.alias)
  val nextCommitNonce_opt: Option[IndividualNonce] = tlvStream.get[ChannelTlv.NextLocalNonceTlv].map(_.nonce)
}

object ChannelReady {
  def apply(channelId: ByteVector32, nextPerCommitmentPoint: PublicKey, alias: Alias): ChannelReady = {
    val tlvs = TlvStream[ChannelReadyTlv](ChannelReadyTlv.ShortChannelIdTlv(alias))
    ChannelReady(channelId, nextPerCommitmentPoint, tlvs)
  }

  def apply(channelId: ByteVector32, nextPerCommitmentPoint: PublicKey, alias: Alias, nextCommitNonce: IndividualNonce): ChannelReady = {
    val tlvs = TlvStream[ChannelReadyTlv](
      ChannelReadyTlv.ShortChannelIdTlv(alias),
      ChannelTlv.NextLocalNonceTlv(nextCommitNonce),
    )
    ChannelReady(channelId, nextPerCommitmentPoint, tlvs)
  }
}

case class Stfu(channelId: ByteVector32, initiator: Boolean) extends SetupMessage with HasChannelId

case class SpliceInit(channelId: ByteVector32,
                      fundingContribution: Satoshi,
                      feerate: FeeratePerKw,
                      lockTime: Long,
                      fundingPubKey: PublicKey,
                      tlvStream: TlvStream[SpliceInitTlv] = TlvStream.empty) extends ChannelMessage with HasChannelId {
  val requireConfirmedInputs: Boolean = tlvStream.get[ChannelTlv.RequireConfirmedInputsTlv].nonEmpty
  val requestFunding_opt: Option[LiquidityAds.RequestFunding] = tlvStream.get[ChannelTlv.RequestFundingTlv].map(_.request)
  val usesOnTheFlyFunding: Boolean = requestFunding_opt.exists(_.paymentDetails.paymentType.isInstanceOf[LiquidityAds.OnTheFlyFundingPaymentType])
  val useFeeCredit_opt: Option[MilliSatoshi] = tlvStream.get[ChannelTlv.UseFeeCredit].map(_.amount)
  val pushAmount: MilliSatoshi = tlvStream.get[ChannelTlv.PushAmountTlv].map(_.amount).getOrElse(0 msat)
  val channelType_opt: Option[ChannelType] = tlvStream.get[ChannelTlv.ChannelTypeTlv].map(_.channelType)
}

object SpliceInit {
  def apply(channelId: ByteVector32, fundingContribution: Satoshi, lockTime: Long, feerate: FeeratePerKw, fundingPubKey: PublicKey, pushAmount: MilliSatoshi, requireConfirmedInputs: Boolean, requestFunding_opt: Option[LiquidityAds.RequestFunding], channelType_opt: Option[ChannelType]): SpliceInit = {
    val tlvs: Set[SpliceInitTlv] = Set(
      if (pushAmount > 0.msat) Some(ChannelTlv.PushAmountTlv(pushAmount)) else None,
      if (requireConfirmedInputs) Some(ChannelTlv.RequireConfirmedInputsTlv()) else None,
      requestFunding_opt.map(ChannelTlv.RequestFundingTlv),
      channelType_opt.map(ChannelTlv.ChannelTypeTlv),
    ).flatten
    SpliceInit(channelId, fundingContribution, feerate, lockTime, fundingPubKey, TlvStream(tlvs))
  }

  def apply(channelId: ByteVector32, fundingContribution: Satoshi, lockTime: Long, feerate: FeeratePerKw, fundingPubKey: PublicKey, pushAmount: MilliSatoshi, requireConfirmedInputs: Boolean, requestFunding_opt: Option[LiquidityAds.RequestFunding]): SpliceInit =
    apply(channelId, fundingContribution, lockTime, feerate, fundingPubKey, pushAmount, requireConfirmedInputs, requestFunding_opt, None)
}

case class SpliceAck(channelId: ByteVector32,
                     fundingContribution: Satoshi,
                     fundingPubKey: PublicKey,
                     tlvStream: TlvStream[SpliceAckTlv] = TlvStream.empty) extends ChannelMessage with HasChannelId {
  val requireConfirmedInputs: Boolean = tlvStream.get[ChannelTlv.RequireConfirmedInputsTlv].nonEmpty
  val willFund_opt: Option[LiquidityAds.WillFund] = tlvStream.get[ChannelTlv.ProvideFundingTlv].map(_.willFund)
  val pushAmount: MilliSatoshi = tlvStream.get[ChannelTlv.PushAmountTlv].map(_.amount).getOrElse(0 msat)
  val channelType_opt: Option[ChannelType] = tlvStream.get[ChannelTlv.ChannelTypeTlv].map(_.channelType)
}

object SpliceAck {
  def apply(channelId: ByteVector32, fundingContribution: Satoshi, fundingPubKey: PublicKey, pushAmount: MilliSatoshi, requireConfirmedInputs: Boolean, willFund_opt: Option[LiquidityAds.WillFund], feeCreditUsed_opt: Option[MilliSatoshi], channelType_opt: Option[ChannelType]): SpliceAck = {
    val tlvs: Set[SpliceAckTlv] = Set(
      if (pushAmount > 0.msat) Some(ChannelTlv.PushAmountTlv(pushAmount)) else None,
      if (requireConfirmedInputs) Some(ChannelTlv.RequireConfirmedInputsTlv()) else None,
      willFund_opt.map(ChannelTlv.ProvideFundingTlv),
      feeCreditUsed_opt.map(ChannelTlv.FeeCreditUsedTlv),
      channelType_opt.map(ChannelTlv.ChannelTypeTlv),
    ).flatten
    SpliceAck(channelId, fundingContribution, fundingPubKey, TlvStream(tlvs))
  }

  def apply(channelId: ByteVector32, fundingContribution: Satoshi, fundingPubKey: PublicKey, pushAmount: MilliSatoshi, requireConfirmedInputs: Boolean, willFund_opt: Option[LiquidityAds.WillFund], feeCreditUsed_opt: Option[MilliSatoshi]): SpliceAck =
    apply(channelId, fundingContribution, fundingPubKey, pushAmount, requireConfirmedInputs, willFund_opt, feeCreditUsed_opt, None)
}

case class SpliceLocked(channelId: ByteVector32,
                        fundingTxId: TxId,
                        tlvStream: TlvStream[SpliceLockedTlv] = TlvStream.empty) extends ChannelMessage with HasChannelId {
}

case class Shutdown(channelId: ByteVector32,
                    scriptPubKey: ByteVector,
                    tlvStream: TlvStream[ShutdownTlv] = TlvStream.empty) extends ChannelMessage with HasChannelId with ForbiddenMessageWhenQuiescent {
  val closeeNonce_opt: Option[IndividualNonce] = tlvStream.get[ShutdownTlv.ShutdownNonce].map(_.nonce)
}

object Shutdown {
  def apply(channelId: ByteVector32, scriptPubKey: ByteVector, closeeNonce: IndividualNonce): Shutdown = Shutdown(channelId, scriptPubKey, TlvStream[ShutdownTlv](ShutdownTlv.ShutdownNonce(closeeNonce)))
}

case class ClosingSigned(channelId: ByteVector32,
                         feeSatoshis: Satoshi,
                         signature: ByteVector64,
                         tlvStream: TlvStream[ClosingSignedTlv] = TlvStream.empty) extends ChannelMessage with HasChannelId {
  val feeRange_opt: Option[ClosingSignedTlv.FeeRange] = tlvStream.get[ClosingSignedTlv.FeeRange]
}

case class ClosingComplete(channelId: ByteVector32, closerScriptPubKey: ByteVector, closeeScriptPubKey: ByteVector, fees: Satoshi, lockTime: Long, tlvStream: TlvStream[ClosingCompleteTlv] = TlvStream.empty) extends ChannelMessage with HasChannelId {
  val closerOutputOnlySig_opt: Option[ByteVector64] = tlvStream.get[ClosingTlv.CloserOutputOnly].map(_.sig)
  val closeeOutputOnlySig_opt: Option[ByteVector64] = tlvStream.get[ClosingTlv.CloseeOutputOnly].map(_.sig)
  val closerAndCloseeOutputsSig_opt: Option[ByteVector64] = tlvStream.get[ClosingTlv.CloserAndCloseeOutputs].map(_.sig)
  val closerOutputOnlyPartialSig_opt: Option[PartialSignatureWithNonce] = tlvStream.get[ClosingCompleteTlv.CloserOutputOnlyPartialSignature].map(_.partialSignature)
  val closeeOutputOnlyPartialSig_opt: Option[PartialSignatureWithNonce] = tlvStream.get[ClosingCompleteTlv.CloseeOutputOnlyPartialSignature].map(_.partialSignature)
  val closerAndCloseeOutputsPartialSig_opt: Option[PartialSignatureWithNonce] = tlvStream.get[ClosingCompleteTlv.CloserAndCloseeOutputsPartialSignature].map(_.partialSignature)
}

case class ClosingSig(channelId: ByteVector32, closerScriptPubKey: ByteVector, closeeScriptPubKey: ByteVector, fees: Satoshi, lockTime: Long, tlvStream: TlvStream[ClosingSigTlv] = TlvStream.empty) extends ChannelMessage with HasChannelId {
  val closerOutputOnlySig_opt: Option[ByteVector64] = tlvStream.get[ClosingTlv.CloserOutputOnly].map(_.sig)
  val closeeOutputOnlySig_opt: Option[ByteVector64] = tlvStream.get[ClosingTlv.CloseeOutputOnly].map(_.sig)
  val closerAndCloseeOutputsSig_opt: Option[ByteVector64] = tlvStream.get[ClosingTlv.CloserAndCloseeOutputs].map(_.sig)
  val closerOutputOnlyPartialSig_opt: Option[ByteVector32] = tlvStream.get[ClosingSigTlv.CloserOutputOnlyPartialSignature].map(_.partialSignature)
  val closeeOutputOnlyPartialSig_opt: Option[ByteVector32] = tlvStream.get[ClosingSigTlv.CloseeOutputOnlyPartialSignature].map(_.partialSignature)
  val closerAndCloseeOutputsPartialSig_opt: Option[ByteVector32] = tlvStream.get[ClosingSigTlv.CloserAndCloseeOutputsPartialSignature].map(_.partialSignature)
  val nextCloseeNonce_opt: Option[IndividualNonce] = tlvStream.get[ClosingSigTlv.NextCloseeNonce].map(_.nonce)
}

case class UpdateAddHtlc(channelId: ByteVector32,
                         id: Long,
                         amountMsat: MilliSatoshi,
                         paymentHash: ByteVector32,
                         cltvExpiry: CltvExpiry,
                         onionRoutingPacket: OnionRoutingPacket,
                         tlvStream: TlvStream[UpdateAddHtlcTlv]) extends HtlcMessage with UpdateMessage with HasChannelId {
  val pathKey_opt: Option[PublicKey] = tlvStream.get[UpdateAddHtlcTlv.PathKey].map(_.publicKey)
  val fundingFee_opt: Option[LiquidityAds.FundingFee] = tlvStream.get[UpdateAddHtlcTlv.FundingFeeTlv].map(_.fee)

  val endorsement: Int = tlvStream.get[UpdateAddHtlcTlv.Endorsement].map(_.level).getOrElse(0)

  /** When storing in our DB, we avoid wasting storage with unknown data. */
  def removeUnknownTlvs(): UpdateAddHtlc = this.copy(tlvStream = tlvStream.copy(unknown = Set.empty))
}

object UpdateAddHtlc {
  def apply(channelId: ByteVector32,
            id: Long,
            amountMsat: MilliSatoshi,
            paymentHash: ByteVector32,
            cltvExpiry: CltvExpiry,
            onionRoutingPacket: OnionRoutingPacket,
            pathKey_opt: Option[PublicKey],
            endorsement: Int,
            fundingFee_opt: Option[LiquidityAds.FundingFee]): UpdateAddHtlc = {
    val tlvs = Set(
      pathKey_opt.map(UpdateAddHtlcTlv.PathKey),
      fundingFee_opt.map(UpdateAddHtlcTlv.FundingFeeTlv),
      Some(UpdateAddHtlcTlv.Endorsement(endorsement)),
    ).flatten[UpdateAddHtlcTlv]
    UpdateAddHtlc(channelId, id, amountMsat, paymentHash, cltvExpiry, onionRoutingPacket, TlvStream(tlvs))
  }
}

case class UpdateFulfillHtlc(channelId: ByteVector32,
                             id: Long,
                             paymentPreimage: ByteVector32,
                             tlvStream: TlvStream[UpdateFulfillHtlcTlv] = TlvStream.empty) extends HtlcMessage with UpdateMessage with HasChannelId with HtlcSettlementMessage {
  val attribution_opt: Option[ByteVector] = tlvStream.get[UpdateFulfillHtlcTlv.AttributionData].map(_.data)
}

case class UpdateFailHtlc(channelId: ByteVector32,
                          id: Long,
                          reason: ByteVector,
                          tlvStream: TlvStream[UpdateFailHtlcTlv] = TlvStream.empty) extends HtlcMessage with UpdateMessage with HasChannelId with HtlcFailureMessage {
  val attribution_opt: Option[ByteVector] = tlvStream.get[UpdateFailHtlcTlv.AttributionData].map(_.data)
}

case class UpdateFailMalformedHtlc(channelId: ByteVector32,
                                   id: Long,
                                   onionHash: ByteVector32,
                                   failureCode: Int,
                                   tlvStream: TlvStream[UpdateFailMalformedHtlcTlv] = TlvStream.empty) extends HtlcMessage with UpdateMessage with HasChannelId with HtlcFailureMessage

/**
 * [[CommitSig]] can either be sent individually or as part of a batch. When sent in a batch (which happens when there
 * are pending splice transactions), we treat the whole batch as a single lightning message and group them on the wire.
 */
sealed trait CommitSigs extends HtlcMessage with HasChannelId

object CommitSigs {
  def apply(sigs: Seq[CommitSig]): CommitSigs = if (sigs.size == 1) sigs.head else CommitSigBatch(sigs)
}

case class CommitSig(channelId: ByteVector32,
                     signature: IndividualSignature,
                     htlcSignatures: List[ByteVector64],
                     tlvStream: TlvStream[CommitSigTlv] = TlvStream.empty) extends CommitSigs {
  val partialSignature_opt: Option[PartialSignatureWithNonce] = tlvStream.get[CommitSigTlv.PartialSignatureWithNonceTlv].map(_.partialSigWithNonce)
  val sigOrPartialSig: ChannelSpendSignature = partialSignature_opt.getOrElse(signature)
}

object CommitSig {
  def apply(channelId: ByteVector32, signature: ChannelSpendSignature, htlcSignatures: List[ByteVector64], batchSize: Int): CommitSig = {
    val (individualSig, partialSig_opt) = signature match {
      case sig: IndividualSignature => (sig, None)
      case psig: PartialSignatureWithNonce => (IndividualSignature(ByteVector64.Zeroes), Some(psig))
    }
    val tlvs = Set(
      if (batchSize > 1) Some(CommitSigTlv.BatchTlv(batchSize)) else None,
      partialSig_opt.map(CommitSigTlv.PartialSignatureWithNonceTlv(_))
    ).flatten[CommitSigTlv]
    CommitSig(channelId, individualSig, htlcSignatures, TlvStream(tlvs))
  }
}

case class CommitSigBatch(messages: Seq[CommitSig]) extends CommitSigs {
  require(messages.map(_.channelId).toSet.size == 1, "commit_sig messages in a batch must be for the same channel")
  val channelId: ByteVector32 = messages.head.channelId
  val batchSize: Int = messages.size
}

case class RevokeAndAck(channelId: ByteVector32,
                        perCommitmentSecret: PrivateKey,
                        nextPerCommitmentPoint: PublicKey,
                        tlvStream: TlvStream[RevokeAndAckTlv] = TlvStream.empty) extends HtlcMessage with HasChannelId {
  val nextCommitNonces: Map[TxId, IndividualNonce] = tlvStream.get[RevokeAndAckTlv.NextLocalNoncesTlv].map(_.nonces.toMap).getOrElse(Map.empty)
}

object RevokeAndAck {
  def apply(channelId: ByteVector32, perCommitmentSecret: PrivateKey, nextPerCommitmentPoint: PublicKey, nextCommitNonces: Seq[(TxId, IndividualNonce)]): RevokeAndAck = {
    val tlvs = Set(
      if (nextCommitNonces.nonEmpty) Some(RevokeAndAckTlv.NextLocalNoncesTlv(nextCommitNonces)) else None
    ).flatten[RevokeAndAckTlv]
    RevokeAndAck(channelId, perCommitmentSecret, nextPerCommitmentPoint, TlvStream(tlvs))
  }
}

case class UpdateFee(channelId: ByteVector32,
                     feeratePerKw: FeeratePerKw,
                     tlvStream: TlvStream[UpdateFeeTlv] = TlvStream.empty) extends ChannelMessage with UpdateMessage with HasChannelId

case class AnnouncementSignatures(channelId: ByteVector32,
                                  shortChannelId: RealShortChannelId,
                                  nodeSignature: ByteVector64,
                                  bitcoinSignature: ByteVector64,
                                  tlvStream: TlvStream[AnnouncementSignaturesTlv] = TlvStream.empty) extends RoutingMessage with HasChannelId

case class ChannelAnnouncement(nodeSignature1: ByteVector64,
                               nodeSignature2: ByteVector64,
                               bitcoinSignature1: ByteVector64,
                               bitcoinSignature2: ByteVector64,
                               features: Features[Feature],
                               chainHash: BlockHash,
                               shortChannelId: RealShortChannelId,
                               nodeId1: PublicKey,
                               nodeId2: PublicKey,
                               bitcoinKey1: PublicKey,
                               bitcoinKey2: PublicKey,
                               tlvStream: TlvStream[ChannelAnnouncementTlv] = TlvStream.empty) extends RoutingMessage with AnnouncementMessage with HasChainHash

case class Color(r: Byte, g: Byte, b: Byte) {
  override def toString: String = f"#$r%02x$g%02x$b%02x" // to hexa s"#  ${r}%02x ${r & 0xFF}${g & 0xFF}${b & 0xFF}"
}

// @formatter:off
sealed trait NodeAddress { def host: String; def port: Int; override def toString: String = s"$host:$port" }
sealed trait OnionAddress extends NodeAddress
sealed trait IPAddress extends NodeAddress
// @formatter:on

object NodeAddress {
  /**
   * Creates a NodeAddress from a host and port.
   *
   * Note that only IP v4 and v6 hosts will be resolved, onion and DNS hosts names will not be resolved.
   *
   * We don't attempt to resolve onion addresses (it will be done by the tor proxy), so we just recognize them based on
   * the .onion TLD and rely on their length to separate v2/v3.
   *
   * Host names that are not Tor, IPv4 or IPv6 are assumed to be a DNS name and are not immediately resolved.
   *
   */
  def fromParts(host: String, port: Int): Try[NodeAddress] = Try {
    host match {
      case _ if host.endsWith(".onion") && host.length == 22 => Tor2(host.dropRight(6), port)
      case _ if host.endsWith(".onion") && host.length == 62 => Tor3(host.dropRight(6), port)
      case _ if InetAddresses.isInetAddress(host.filterNot(Set('[', ']'))) => IPAddress(InetAddress.getByName(host), port)
      case _ => DnsHostname(host, port)
    }
  }

  private def isPrivate(address: InetAddress): Boolean = address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress

  def isPublicIPAddress(address: NodeAddress): Boolean = {
    address match {
      case IPv4(ipv4, _) if !isPrivate(ipv4) => true
      case IPv6(ipv6, _) if !isPrivate(ipv6) => true
      case _ => false
    }
  }
}

object IPAddress {
  def apply(inetAddress: InetAddress, port: Int): IPAddress = (inetAddress: @unchecked) match {
    // we need the @unchecked annotation to suppress a "matching not exhaustive". Before JDK21, InetAddress was a regular class so scalac would not check anything (it only checks sealed patterns)
    // with JDK21 InetAddress is defined as `public sealed class InetAddress implements Serializable permits Inet4Address, Inet6Address` and scalac complains because in theory there could be
    // an InetAddress() instance, though it is not possible in practice because the constructor is package private :(
    // remove @unchecked if we upgrade to a newer JDK that does not have this pb, or if scalac pattern matching becomes more clever
    case address: Inet4Address => IPv4(address, port)
    case address: Inet6Address => IPv6(address, port)
  }
}

// @formatter:off
case class IPv4(ipv4: Inet4Address, port: Int) extends IPAddress { override def host: String = InetAddresses.toUriString(ipv4) }
case class IPv6(ipv6: Inet6Address, port: Int) extends IPAddress { override def host: String = InetAddresses.toUriString(ipv6) }
case class Tor2(tor2: String, port: Int) extends OnionAddress { override def host: String = tor2 + ".onion" }
case class Tor3(tor3: String, port: Int) extends OnionAddress { override def host: String = tor3 + ".onion" }
case class DnsHostname(dnsHostname: String, port: Int) extends IPAddress {override def host: String = dnsHostname}
// @formatter:on

case class NodeInfo(features: Features[InitFeature], address_opt: Option[NodeAddress])

case class NodeAnnouncement(signature: ByteVector64,
                            features: Features[Feature],
                            timestamp: TimestampSecond,
                            nodeId: PublicKey,
                            rgbColor: Color,
                            alias: String,
                            addresses: List[NodeAddress],
                            tlvStream: TlvStream[NodeAnnouncementTlv] = TlvStream.empty) extends RoutingMessage with AnnouncementMessage with HasTimestamp {
  val fundingRates_opt: Option[LiquidityAds.WillFundRates] = tlvStream.get[NodeAnnouncementTlv.OptionWillFund].map(_.rates)
  val validAddresses: List[NodeAddress] = {
    // if port is equal to 0, SHOULD ignore ipv6_addr OR ipv4_addr OR hostname; SHOULD ignore Tor v2 onion services.
    val validAddresses = addresses.filter(address => address.port != 0 || address.isInstanceOf[Tor3]).filterNot(address => address.isInstanceOf[Tor2])
    // if more than one type 5 address is announced, SHOULD ignore the additional data.
    validAddresses.filter(!_.isInstanceOf[DnsHostname]) ++ validAddresses.find(_.isInstanceOf[DnsHostname])
  }
  val shouldRebroadcast: Boolean = {
    // if more than one type 5 address is announced, MUST not forward the node_announcement.
    addresses.count(address => address.isInstanceOf[DnsHostname]) <= 1
  }
}

case class ChannelUpdate(signature: ByteVector64,
                         chainHash: BlockHash,
                         shortChannelId: ShortChannelId,
                         timestamp: TimestampSecond,
                         messageFlags: ChannelUpdate.MessageFlags,
                         channelFlags: ChannelUpdate.ChannelFlags,
                         cltvExpiryDelta: CltvExpiryDelta,
                         htlcMinimumMsat: MilliSatoshi,
                         feeBaseMsat: MilliSatoshi,
                         feeProportionalMillionths: Long,
                         htlcMaximumMsat: MilliSatoshi,
                         tlvStream: TlvStream[ChannelUpdateTlv] = TlvStream.empty) extends RoutingMessage with AnnouncementMessage with HasTimestamp with HasChainHash {
  val dontForward: Boolean = messageFlags.dontForward

  def toStringShort: String = s"cltvExpiryDelta=$cltvExpiryDelta,feeBase=$feeBaseMsat,feeProportionalMillionths=$feeProportionalMillionths"

  def relayFees: Relayer.RelayFees = Relayer.RelayFees(feeBase = feeBaseMsat, feeProportionalMillionths = feeProportionalMillionths)

  def blip18InboundFees_opt: Option[Relayer.InboundFees] =
    tlvStream.get[ChannelUpdateTlv.Blip18InboundFee].map(blip18 => Relayer.InboundFees(blip18.feeBase, blip18.feeProportionalMillionths))
}

object ChannelUpdate {
  case class MessageFlags(dontForward: Boolean)

  case class ChannelFlags(isEnabled: Boolean, isNode1: Boolean)

  object ChannelFlags {
    /** for tests */
    val DUMMY: ChannelFlags = ChannelFlags(isEnabled = true, isNode1 = true)
  }
}

// @formatter:off
sealed trait EncodingType
object EncodingType {
  case object UNCOMPRESSED extends EncodingType
  case object COMPRESSED_ZLIB extends EncodingType
}
// @formatter:on

case class EncodedShortChannelIds(encoding: EncodingType, array: List[RealShortChannelId]) {
  /** custom toString because it can get huge in logs */
  override def toString: String = s"EncodedShortChannelIds($encoding,${array.headOption.getOrElse("")}->${array.lastOption.getOrElse("")} size=${array.size})"
}

case class QueryShortChannelIds(chainHash: BlockHash, shortChannelIds: EncodedShortChannelIds, tlvStream: TlvStream[QueryShortChannelIdsTlv] = TlvStream.empty) extends RoutingMessage with HasChainHash {
  val queryFlags_opt: Option[QueryShortChannelIdsTlv.EncodedQueryFlags] = tlvStream.get[QueryShortChannelIdsTlv.EncodedQueryFlags]
}

case class ReplyShortChannelIdsEnd(chainHash: BlockHash, complete: Byte, tlvStream: TlvStream[ReplyShortChannelIdsEndTlv] = TlvStream.empty) extends RoutingMessage with HasChainHash

case class QueryChannelRange(chainHash: BlockHash, firstBlock: BlockHeight, numberOfBlocks: Long, tlvStream: TlvStream[QueryChannelRangeTlv] = TlvStream.empty) extends RoutingMessage with HasChainHash {
  val queryFlags_opt: Option[QueryChannelRangeTlv.QueryFlags] = tlvStream.get[QueryChannelRangeTlv.QueryFlags]
}

case class ReplyChannelRange(chainHash: BlockHash, firstBlock: BlockHeight, numberOfBlocks: Long, syncComplete: Byte, shortChannelIds: EncodedShortChannelIds, tlvStream: TlvStream[ReplyChannelRangeTlv] = TlvStream.empty) extends RoutingMessage with HasChainHash {
  val timestamps_opt: Option[ReplyChannelRangeTlv.EncodedTimestamps] = tlvStream.get[ReplyChannelRangeTlv.EncodedTimestamps]
  val checksums_opt: Option[ReplyChannelRangeTlv.EncodedChecksums] = tlvStream.get[ReplyChannelRangeTlv.EncodedChecksums]
}

object ReplyChannelRange {
  def apply(chainHash: BlockHash,
            firstBlock: BlockHeight,
            numberOfBlocks: Long,
            syncComplete: Byte,
            shortChannelIds: EncodedShortChannelIds,
            timestamps: Option[ReplyChannelRangeTlv.EncodedTimestamps],
            checksums: Option[ReplyChannelRangeTlv.EncodedChecksums]): ReplyChannelRange = {
    timestamps.foreach(ts => require(ts.timestamps.length == shortChannelIds.array.length))
    checksums.foreach(cs => require(cs.checksums.length == shortChannelIds.array.length))
    new ReplyChannelRange(chainHash, firstBlock, numberOfBlocks, syncComplete, shortChannelIds, TlvStream(Set(timestamps, checksums).flatten[ReplyChannelRangeTlv]))
  }
}

case class GossipTimestampFilter(chainHash: BlockHash, firstTimestamp: TimestampSecond, timestampRange: Long, tlvStream: TlvStream[GossipTimestampFilterTlv] = TlvStream.empty) extends RoutingMessage with HasChainHash

case class OnionMessage(pathKey: PublicKey, onionRoutingPacket: OnionRoutingPacket, tlvStream: TlvStream[OnionMessageTlv] = TlvStream.empty) extends LightningMessage

case class PeerStorageStore(blob: ByteVector, tlvStream: TlvStream[PeerStorageTlv] = TlvStream.empty) extends SetupMessage

case class PeerStorageRetrieval(blob: ByteVector, tlvStream: TlvStream[PeerStorageTlv] = TlvStream.empty) extends SetupMessage

// NB: blank lines to minimize merge conflicts

//

//

//

//

//

//

//

//

/**
 * This message informs our peers of the feerates we recommend using.
 * We may reject funding attempts that use values that are too far from our recommended feerates.
 */
case class RecommendedFeerates(chainHash: BlockHash, fundingFeerate: FeeratePerKw, commitmentFeerate: FeeratePerKw, tlvStream: TlvStream[RecommendedFeeratesTlv] = TlvStream.empty) extends SetupMessage with HasChainHash {
  val minFundingFeerate: FeeratePerKw = tlvStream.get[RecommendedFeeratesTlv.FundingFeerateRange].map(_.min).getOrElse(fundingFeerate)
  val maxFundingFeerate: FeeratePerKw = tlvStream.get[RecommendedFeeratesTlv.FundingFeerateRange].map(_.max).getOrElse(fundingFeerate)
  val minCommitmentFeerate: FeeratePerKw = tlvStream.get[RecommendedFeeratesTlv.CommitmentFeerateRange].map(_.min).getOrElse(commitmentFeerate)
  val maxCommitmentFeerate: FeeratePerKw = tlvStream.get[RecommendedFeeratesTlv.CommitmentFeerateRange].map(_.max).getOrElse(commitmentFeerate)
}

/**
 * This message is sent when an HTLC couldn't be relayed to our node because we don't have enough inbound liquidity.
 * This allows us to treat it as an incoming payment, and request on-the-fly liquidity accordingly if we wish to receive that payment.
 * If we accept the payment, we will send an [[OpenDualFundedChannel]] or [[SpliceInit]] message containing [[ChannelTlv.RequestFundingTlv]].
 * Our peer will then provide the requested funding liquidity and will relay the corresponding HTLC(s) afterwards.
 */
case class WillAddHtlc(chainHash: BlockHash,
                       id: ByteVector32,
                       amount: MilliSatoshi,
                       paymentHash: ByteVector32,
                       expiry: CltvExpiry,
                       finalPacket: OnionRoutingPacket,
                       tlvStream: TlvStream[WillAddHtlcTlv] = TlvStream.empty) extends OnTheFlyFundingMessage {
  val pathKey_opt: Option[PublicKey] = tlvStream.get[WillAddHtlcTlv.PathKey].map(_.publicKey)
}

object WillAddHtlc {
  def apply(chainHash: BlockHash,
            id: ByteVector32,
            amount: MilliSatoshi,
            paymentHash: ByteVector32,
            expiry: CltvExpiry,
            finalPacket: OnionRoutingPacket,
            pathKey_opt: Option[PublicKey]): WillAddHtlc = {
    val tlvs = pathKey_opt.map(WillAddHtlcTlv.PathKey).toSet[WillAddHtlcTlv]
    WillAddHtlc(chainHash, id, amount, paymentHash, expiry, finalPacket, TlvStream(tlvs))
  }
}

/** This message is similar to [[UpdateFailHtlc]], but for [[WillAddHtlc]]. */
case class WillFailHtlc(id: ByteVector32, paymentHash: ByteVector32, reason: ByteVector, tlvStream: TlvStream[UpdateFailHtlcTlv] = TlvStream.empty) extends OnTheFlyFundingFailureMessage {
  val attribution_opt: Option[ByteVector] = tlvStream.get[UpdateFailHtlcTlv.AttributionData].map(_.data)
}

/** This message is similar to [[UpdateFailMalformedHtlc]], but for [[WillAddHtlc]]. */
case class WillFailMalformedHtlc(id: ByteVector32, paymentHash: ByteVector32, onionHash: ByteVector32, failureCode: Int) extends OnTheFlyFundingFailureMessage

/**
 * This message is sent in response to an [[OpenDualFundedChannel]] or [[SpliceInit]] message containing an invalid [[LiquidityAds.RequestFunding]].
 * The receiver must consider the funding attempt failed when receiving this message.
 */
case class CancelOnTheFlyFunding(channelId: ByteVector32, paymentHashes: List[ByteVector32], reason: ByteVector) extends LightningMessage with HasChannelId {
  def toAscii: String = if (isAsciiPrintable(reason)) new String(reason.toArray, StandardCharsets.US_ASCII) else "n/a"
}

object CancelOnTheFlyFunding {
  def apply(channelId: ByteVector32, paymentHashes: List[ByteVector32], reason: String): CancelOnTheFlyFunding = CancelOnTheFlyFunding(channelId, paymentHashes, ByteVector.view(reason.getBytes(Charsets.US_ASCII)))
}

/**
 * This message is used to reveal the preimage of a small payment for which it isn't economical to perform an on-chain
 * transaction. The amount of the payment will be added to our fee credit, which can be used when a future on-chain
 * transaction is needed. This message requires the [[Features.FundingFeeCredit]] feature.
 */
case class AddFeeCredit(chainHash: BlockHash, preimage: ByteVector32) extends HasChainHash

/** This message contains our current fee credit: the liquidity provider is the source of truth for that value. */
case class CurrentFeeCredit(chainHash: BlockHash, amount: MilliSatoshi) extends HasChainHash

case class UnknownMessage(tag: Int, data: ByteVector) extends LightningMessage