package fr.acinq.eclair.wire.internal.channel.version4

import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, DeterministicWallet, OutPoint, Satoshi, SatoshiLong, Script, Transaction, TxId, TxIn, TxOut}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{ChannelRangeQueries, PaymentSecret, VariableLengthOnion}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.channel.LocalFundingStatus.DualFundedUnconfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.{InteractiveTxParams, PartiallySignedSharedTransaction, RequireConfirmedInputs, SharedTransaction}
import fr.acinq.eclair.channel.fund.InteractiveTxSigningSession.UnsignedLocalCommit
import fr.acinq.eclair.channel.fund.{InteractiveTxBuilder, InteractiveTxSigningSession}
import fr.acinq.eclair.transactions.Transactions.{CommitTx, InputInfo}
import fr.acinq.eclair.transactions.{CommitmentSpec, Scripts}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec.normal
import fr.acinq.eclair.wire.internal.channel.version4.ChannelCodecs4.Codecs._
import fr.acinq.eclair.wire.internal.channel.version4.ChannelCodecs4.channelDataCodec
import fr.acinq.eclair.wire.protocol.TxSignatures
import fr.acinq.eclair.{BlockHeight, CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, UInt64, randomBytes32, randomKey}
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
      assert(decodedLocalParams == localParams.copy(initialRequestedChannelReserve_opt = None))
      assert(decodedRemoteParams == remoteParams.copy(initialRequestedChannelReserve_opt = None))
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
    val fundingInput = InputInfo(OutPoint(randomTxId(), 3), TxOut(175_000 sat, Script.pay2wpkh(randomKey().publicKey)), Nil)
    val fundingTx = SharedTransaction(
      sharedInput_opt = None,
      sharedOutput = InteractiveTxBuilder.Output.Shared(UInt64(8), ByteVector.empty, 100_000_600 msat, 74_000_400 msat, 0 msat),
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
      PartiallySignedSharedTransaction(fundingTx, TxSignatures(channelId, randomTxId(), Nil)),
      Left(UnsignedLocalCommit(0, CommitmentSpec(Set.empty, FeeratePerKw(1000 sat), 100_000_000 msat, 75_000_000 msat), commitTx, Nil)),
      RemoteCommit(0, CommitmentSpec(Set.empty, FeeratePerKw(1000 sat), 75_000_000 msat, 100_000_000 msat), randomTxId(), randomKey().publicKey)
    )
    val testCases = Map(
      RbfStatus.NoRbf -> RbfStatus.NoRbf,
      RbfStatus.RbfRequested(CMD_BUMP_FUNDING_FEE(null, FeeratePerKw(750 sat), fundingFeeBudget = 100_000.sat, 0, None)) -> RbfStatus.NoRbf,
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

  test("decode unconfirmed dual funded") {
    // data encoded with the previous version of eclair, when Shared.Input did not include a pubkey script
    val raw = ByteVector.fromValidHex("0x020001ff02000000000000002a2400000000000000000000000000000000000000000000000000000000000000000000000000003039000000000000006400000000000000c8000000000000012c02000000000000002b04deadbeef000000000000006400000000000000c8000000000000012c00000000000000000000000042000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000003e80000000000000000000000000000000000000000000000000000000000000000ff000000000000006400000000000000c8ff0001240000000000000000000000000000000000000000000000000000000000000000000000002be803000000000000220020eb72e573a9513d982a01f0e6a6b53e92764db81a0c26d2be94c5fc5b69a0db7d475221024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d076621031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f52ae00000000024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f000000000000000000000000014a000002ee0000")
    val decoded = fundingTxStatusCodec.decode(raw.bits).require.value.asInstanceOf[LocalFundingStatus.DualFundedUnconfirmedFundingTx]

    // check that our codec will set the pubkeyscript using the one from the funding params
    val channelId = ByteVector32.Zeroes
    val script = Scripts.multiSig2of2(PrivateKey(ByteVector.fromValidHex("01" * 32)).publicKey, PrivateKey(ByteVector.fromValidHex("02" * 32)).publicKey)
    val dualFundedUnconfirmedFundingTx = DualFundedUnconfirmedFundingTx(
      PartiallySignedSharedTransaction(
        SharedTransaction(
          // we include the correct pubkey script here
          Some(InteractiveTxBuilder.Input.Shared(UInt64(42), OutPoint(TxId(ByteVector32.Zeroes), 0), Script.write(Script.pay2wsh(script)), 12345L, MilliSatoshi(100), MilliSatoshi(200), MilliSatoshi(300))),
          sharedOutput = InteractiveTxBuilder.Output.Shared(UInt64(43), ByteVector.fromValidHex("deadbeef"), MilliSatoshi(100), MilliSatoshi(200), MilliSatoshi(300)),
          localInputs = Nil, remoteInputs = Nil, localOutputs = Nil, remoteOutputs = Nil, lockTime = 0
        ),
        localSigs = TxSignatures(channelId, TxId(ByteVector32.Zeroes), Nil)
      ),
      createdAt = BlockHeight(1000),
      fundingParams = InteractiveTxParams(channelId = channelId, isInitiator = true, localContribution = 100.sat, remoteContribution = 200.sat,
        sharedInput_opt = Some(InteractiveTxBuilder.Multisig2of2Input(
          InputInfo(OutPoint(TxId(ByteVector32.Zeroes), 0), TxOut(1000.sat, Script.pay2wsh(script)), script),
          0,
          PrivateKey(ByteVector.fromValidHex("02" * 32)).publicKey
        )),
        remoteFundingPubKey = PrivateKey(ByteVector.fromValidHex("01" * 32)).publicKey,
        localOutputs = Nil, lockTime = 0, dustLimit = 330.sat, targetFeerate = FeeratePerKw(FeeratePerByte(3.sat)), requireConfirmedInputs = RequireConfirmedInputs(forLocal = false, forRemote = false)),
    )
    assert(decoded == dualFundedUnconfirmedFundingTx)
  }
}
