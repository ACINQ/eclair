package fr.acinq.eclair.wire.internal.channel.version5

import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{ChannelRangeQueries, PaymentSecret, VariableLengthOnion}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.{InteractiveTxParams, PartiallySignedSharedTransaction, RequireConfirmedInputs, SharedTransaction}
import fr.acinq.eclair.channel.fund.InteractiveTxSigningSession.UnsignedLocalCommit
import fr.acinq.eclair.channel.fund.{InteractiveTxBuilder, InteractiveTxSigningSession}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat
import fr.acinq.eclair.wire.internal.channel.version5.ChannelCodecs5.Codecs.{dualFundingStatusCodec, remoteChannelParamsCodec}
import fr.acinq.eclair.wire.protocol.{LiquidityAds, TxSignatures}
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshiLong, UInt64, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

class ChannelCodecs5Spec extends AnyFunSuite {

  test("encode/decode rbf status") {
    val channelId = randomBytes32()
    val fundingTx = SharedTransaction(
      sharedInput_opt = None,
      sharedOutput = InteractiveTxBuilder.Output.Shared(UInt64(8), ByteVector.empty, 100_000_600 msat, 74_000_400 msat, 0 msat),
      localInputs = Nil, remoteInputs = Nil,
      localOutputs = Nil, remoteOutputs = Nil,
      lockTime = 0
    )
    val waitingForSigs = InteractiveTxSigningSession.WaitingForSigs(
      InteractiveTxParams(channelId, isInitiator = true, 100_000 sat, 75_000 sat, None, randomKey().publicKey, Nil, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat, 0, 330 sat, FeeratePerKw(500 sat), RequireConfirmedInputs(forLocal = false, forRemote = false)),
      fundingTxIndex = 0,
      PartiallySignedSharedTransaction(fundingTx, TxSignatures(channelId, randomTxId(), Nil)),
      CommitParams(330 sat, 1 msat, UInt64.MaxValue, 30, CltvExpiryDelta(720)),
      Left(UnsignedLocalCommit(0, CommitmentSpec(Set.empty, FeeratePerKw(1000 sat), 100_000_000 msat, 75_000_000 msat), randomTxId())),
      CommitParams(500 sat, 1000 msat, UInt64.MaxValue, 483, CltvExpiryDelta(144)),
      RemoteCommit(0, CommitmentSpec(Set.empty, FeeratePerKw(1000 sat), 75_000_000 msat, 100_000_000 msat), randomTxId(), randomKey().publicKey),
      Some(LiquidityAds.PurchaseBasicInfo(isBuyer = true, 100_000 sat, LiquidityAds.Fees(1000 sat, 500 sat))),
    )
    val testCases = Map(
      DualFundingStatus.WaitingForConfirmations -> DualFundingStatus.WaitingForConfirmations,
      DualFundingStatus.RbfRequested(CMD_BUMP_FUNDING_FEE(null, FeeratePerKw(750 sat), fundingFeeBudget = 100_000.sat, 0, None)) -> DualFundingStatus.WaitingForConfirmations,
      DualFundingStatus.RbfInProgress(None, null, None) -> DualFundingStatus.WaitingForConfirmations,
      DualFundingStatus.RbfWaitingForSigs(waitingForSigs) -> DualFundingStatus.RbfWaitingForSigs(waitingForSigs),
      DualFundingStatus.RbfWaitingForSigs(waitingForSigs.copy(liquidityPurchase_opt = None)) -> DualFundingStatus.RbfWaitingForSigs(waitingForSigs.copy(liquidityPurchase_opt = None)),
      DualFundingStatus.RbfAborted -> DualFundingStatus.WaitingForConfirmations,
    )
    testCases.foreach { case (status, expected) =>
      val encoded = dualFundingStatusCodec.encode(status).require
      val decoded = dualFundingStatusCodec.decode(encoded).require.value
      assert(decoded == expected)
    }
  }

  test("encode/decode optional shutdown script") {
    val remoteParams = RemoteChannelParams(
      randomKey().publicKey,
      Some(300_000 sat),
      randomKey().publicKey,
      randomKey().publicKey,
      randomKey().publicKey,
      randomKey().publicKey,
      Features(ChannelRangeQueries -> Optional, VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory),
      None)
    assert(remoteChannelParamsCodec.decodeValue(remoteChannelParamsCodec.encode(remoteParams).require).require == remoteParams)
    val remoteParams1 = remoteParams.copy(upfrontShutdownScript_opt = Some(ByteVector.fromValidHex("deadbeef")))
    assert(remoteChannelParamsCodec.decodeValue(remoteChannelParamsCodec.encode(remoteParams1).require).require == remoteParams1)
  }

}
