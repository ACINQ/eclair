package fr.acinq.eclair.wire.internal.channel.version4

import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.{DeterministicWallet, OutPoint, Satoshi, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{ChannelRangeQueries, PaymentSecret, VariableLengthOnion}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.{InteractiveTxParams, PartiallySignedSharedTransaction, RequireConfirmedInputs, SharedTransaction}
import fr.acinq.eclair.channel.fund.InteractiveTxSigningSession.UnsignedLocalCommit
import fr.acinq.eclair.channel.fund.{InteractiveTxBuilder, InteractiveTxSigningSession}
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions.{CommitTx, InputInfo}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec.normal
import fr.acinq.eclair.wire.internal.channel.version4.ChannelCodecs4.Codecs.{channelConfigCodec, localParamsCodec, rbfStatusCodec, remoteParamsCodec}
import fr.acinq.eclair.wire.internal.channel.version4.ChannelCodecs4.channelDataCodec
import fr.acinq.eclair.wire.protocol.TxSignatures
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, UInt64, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

import scala.util.Random

class ChannelCodecs4Spec extends AnyFunSuite {

  test("basic serialization test (NORMAL)") {
    val data = normal
    val bin = channelDataCodec.encode(data).require
    val check = channelDataCodec.decodeValue(bin).require.asInstanceOf[ChannelDataWithCommitments]
    assert(data.commitments.latest.localCommit.spec == check.commitments.latest.localCommit.spec)
    assert(data == check)
  }

  test("encode/decode channel configuration options") {
    assert(channelConfigCodec.encode(ChannelConfig(Set.empty[ChannelConfigOption])).require.bytes == hex"00")
    assert(channelConfigCodec.decode(hex"00".bits).require.value == ChannelConfig(Set.empty[ChannelConfigOption]))
    assert(channelConfigCodec.decode(hex"01f0".bits).require.value == ChannelConfig(Set.empty[ChannelConfigOption]))
    assert(channelConfigCodec.decode(hex"020000".bits).require.value == ChannelConfig(Set.empty[ChannelConfigOption]))

    assert(channelConfigCodec.encode(ChannelConfig.standard).require.bytes == hex"0101")
    assert(channelConfigCodec.encode(ChannelConfig(ChannelConfig.FundingPubKeyBasedChannelKeyPath)).require.bytes == hex"0101")
    assert(channelConfigCodec.decode(hex"0101".bits).require.value == ChannelConfig(ChannelConfig.FundingPubKeyBasedChannelKeyPath))
    assert(channelConfigCodec.decode(hex"01ff".bits).require.value == ChannelConfig(ChannelConfig.FundingPubKeyBasedChannelKeyPath))
    assert(channelConfigCodec.decode(hex"020001".bits).require.value == ChannelConfig(ChannelConfig.FundingPubKeyBasedChannelKeyPath))
  }

  test("encode/decode optional channel reserve") {
    val localParams = LocalParams(
      randomKey().publicKey,
      DeterministicWallet.KeyPath(Seq(42L)),
      Satoshi(660),
      MilliSatoshi(500000),
      Some(Satoshi(15000)),
      MilliSatoshi(1000),
      CltvExpiryDelta(36),
      50,
      Random.nextBoolean(),
      Some(hex"deadbeef"),
      None,
      Features().initFeatures())
    val remoteParams = RemoteParams(
      randomKey().publicKey,
      Satoshi(500),
      UInt64(100000),
      Some(Satoshi(30000)),
      MilliSatoshi(1500),
      CltvExpiryDelta(144),
      10,
      randomKey().publicKey,
      randomKey().publicKey,
      randomKey().publicKey,
      randomKey().publicKey,
      Features(),
      None)

    {
      val localCodec = localParamsCodec(ChannelFeatures())
      val remoteCodec = remoteParamsCodec(ChannelFeatures())
      val decodedLocalParams = localCodec.decode(localCodec.encode(localParams).require).require.value
      val decodedRemoteParams = remoteCodec.decode(remoteCodec.encode(remoteParams).require).require.value
      assert(decodedLocalParams == localParams)
      assert(decodedRemoteParams == remoteParams)
    }
    {
      val localCodec = localParamsCodec(ChannelFeatures(Features.DualFunding))
      val remoteCodec = remoteParamsCodec(ChannelFeatures(Features.DualFunding))
      val decodedLocalParams = localCodec.decode(localCodec.encode(localParams).require).require.value
      val decodedRemoteParams = remoteCodec.decode(remoteCodec.encode(remoteParams).require).require.value
      assert(decodedLocalParams == localParams.copy(requestedChannelReserve_opt = None))
      assert(decodedRemoteParams == remoteParams.copy(requestedChannelReserve_opt = None))
    }
  }

  test("encode/decode optional shutdown script") {
    val codec = remoteParamsCodec(ChannelFeatures())
    val remoteParams = RemoteParams(
      randomKey().publicKey,
      Satoshi(600),
      UInt64(123456L),
      Some(Satoshi(300)),
      MilliSatoshi(1000),
      CltvExpiryDelta(42),
      42,
      randomKey().publicKey,
      randomKey().publicKey,
      randomKey().publicKey,
      randomKey().publicKey,
      Features(ChannelRangeQueries -> Optional, VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory),
      None)
    assert(codec.decodeValue(codec.encode(remoteParams).require).require == remoteParams)
    val remoteParams1 = remoteParams.copy(upfrontShutdownScript_opt = Some(ByteVector.fromValidHex("deadbeef")))
    assert(codec.decodeValue(codec.encode(remoteParams1).require).require == remoteParams1)

    val dataWithoutRemoteShutdownScript = normal.modify(_.commitments.params.remoteParams).setTo(remoteParams)
    assert(channelDataCodec.decode(channelDataCodec.encode(dataWithoutRemoteShutdownScript).require).require.value == dataWithoutRemoteShutdownScript)

    val dataWithRemoteShutdownScript = normal.modify(_.commitments.params.remoteParams).setTo(remoteParams1)
    assert(channelDataCodec.decode(channelDataCodec.encode(dataWithRemoteShutdownScript).require).require.value == dataWithRemoteShutdownScript)
  }

  test("encode/decode rbf status") {
    val channelId = randomBytes32()
    val fundingInput = InputInfo(OutPoint(randomBytes32(), 3), TxOut(175_000 sat, Script.pay2wpkh(randomKey().publicKey)), Nil)
    val fundingTx = SharedTransaction(
      sharedInput_opt = None,
      sharedOutput = InteractiveTxBuilder.Output.Shared(UInt64(8), ByteVector.empty, 100_000_600 msat, 74_000_400 msat),
      localInputs = Nil, remoteInputs = Nil,
      localOutputs = Nil, remoteOutputs = Nil,
      lockTime = 0
    )
    val commitTx = CommitTx(
      fundingInput,
      Transaction(2, Seq(TxIn(fundingInput.outPoint, Nil, 0)), Seq(TxOut(150_000 sat, Script.pay2wpkh(randomKey().publicKey))), 0),
    )
    val waitingForSigs = InteractiveTxSigningSession.WaitingForSigs(
      InteractiveTxParams(channelId, isInitiator = true, 100_000 sat, 75_000 sat, None, randomKey().publicKey, Nil, 0, 330 sat, FeeratePerKw(500 sat), RequireConfirmedInputs(forLocal = false, forRemote = false)),
      fundingTxIndex = 0,
      PartiallySignedSharedTransaction(fundingTx, TxSignatures(channelId, randomBytes32(), Nil)),
      Left(UnsignedLocalCommit(0, CommitmentSpec(Set.empty, FeeratePerKw(1000 sat), 100_000_000 msat, 75_000_000 msat), commitTx, Nil)),
      RemoteCommit(0, CommitmentSpec(Set.empty, FeeratePerKw(1000 sat), 75_000_000 msat, 100_000_000 msat), randomBytes32(), randomKey().publicKey)
    )
    val testCases = Map(
      RbfStatus.NoRbf -> RbfStatus.NoRbf,
      RbfStatus.RbfRequested(CMD_BUMP_FUNDING_FEE(null, FeeratePerKw(750 sat), 0)) -> RbfStatus.NoRbf,
      RbfStatus.RbfInProgress(None, null, None) -> RbfStatus.NoRbf,
      RbfStatus.RbfWaitingForSigs(waitingForSigs) -> RbfStatus.RbfWaitingForSigs(waitingForSigs),
      RbfStatus.RbfAborted -> RbfStatus.NoRbf,
    )
    testCases.foreach { case (status, expected) =>
      val encoded = rbfStatusCodec.encode(status).require
      val decoded = rbfStatusCodec.decode(encoded).require.value
      assert(decoded == expected)
    }
  }

}
