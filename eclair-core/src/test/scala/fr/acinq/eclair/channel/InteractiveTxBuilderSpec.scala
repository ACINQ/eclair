/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.channel

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter.{ClassicActorSystemOps, actorRefAdapter}
import akka.pattern.pipe
import akka.testkit.TestProbe
import com.softwaremill.quicklens.{ModifyPimp, QuicklensAt}
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, OP_1, OutPoint, Satoshi, SatoshiLong, Script, ScriptWitness, Transaction, TxHash, TxId, TxIn, TxOut, addressToPublicKeyScript}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.blockchain.OnChainWallet.{FundTransactionResponse, ProcessPsbtResponse}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.{MempoolTx, Utxo}
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BitcoinCoreClient, BitcoinJsonRPCClient}
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.blockchain.{OnChainWallet, SingleKeyOnChainWallet}
import fr.acinq.eclair.channel.ChannelSpendSignature.{IndividualSignature, PartialSignatureWithNonce}
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder._
import fr.acinq.eclair.channel.fund.{InteractiveTxBuilder, InteractiveTxSigningSession}
import fr.acinq.eclair.crypto.keymanager.ChannelKeys
import fr.acinq.eclair.io.OpenChannelInterceptor.makeChannelParams
import fr.acinq.eclair.transactions.Transactions.{CommitmentFormat, InputInfo, PhoenixSimpleTaprootChannelCommitmentFormat, UnsafeLegacyAnchorOutputsCommitmentFormat}
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Feature, FeatureSupport, Features, MilliSatoshiLong, NodeParams, TestConstants, TestKitBaseClass, ToMilliSatoshiConversion, UInt64, randomBytes32, randomKey}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.{ByteVector, HexStringSyntax}

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

class InteractiveTxBuilderSpec extends TestKitBaseClass with AnyFunSuiteLike with BitcoindService with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    startBitcoind()
    waitForBitcoindReady()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  private def addUtxo(wallet: BitcoinCoreClient, amount: Satoshi, probe: TestProbe): Unit = {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    wallet.getReceiveAddress().pipeTo(probe.ref)
    val walletAddress = probe.expectMsgType[String]
    val tx = Transaction(version = 2, Nil, TxOut(amount, addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, walletAddress).toOption.get) :: Nil, lockTime = 0)
    val client = makeBitcoinCoreClient()
    val f = for {
      funded <- client.fundTransaction(tx, FeeratePerByte(10.sat).perKw)
      signed <- client.signPsbt(new Psbt(funded.tx), funded.tx.txIn.indices, Nil)
      txid <- client.publishTransaction(signed.finalTx_opt.toOption.get)
    } yield txid
    f.pipeTo(probe.ref)
    probe.expectMsgType[TxId]
  }

  private def createInput(channelId: ByteVector32, serialId: UInt64, amount: Satoshi): TxAddInput = {
    val changeScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val previousTx = Transaction(2, Nil, Seq(TxOut(amount, changeScript), TxOut(amount, changeScript), TxOut(amount, changeScript)), 0)
    TxAddInput(channelId, serialId, Some(previousTx), 1, 0)
  }

  private def toOutPoint(input: TxAddInput): OutPoint = input.previousTx_opt match {
    case Some(previousTx) => OutPoint(previousTx, input.previousTxOutput.toInt)
    case None => input.sharedInput_opt.get
  }

  case class FixtureParams(fundingParamsA: InteractiveTxParams,
                           nodeParamsA: NodeParams,
                           channelParamsA: ChannelParams,
                           commitParamsA: CommitParams,
                           fundingParamsB: InteractiveTxParams,
                           nodeParamsB: NodeParams,
                           channelParamsB: ChannelParams,
                           commitParamsB: CommitParams) {
    val channelId: ByteVector32 = fundingParamsA.channelId
    val commitFeerate: FeeratePerKw = TestConstants.anchorOutputsFeeratePerKw
    val channelKeysA: ChannelKeys = nodeParamsA.channelKeyManager.channelKeys(channelParamsA.channelConfig, channelParamsA.localParams.fundingKeyPath)
    val channelKeysB: ChannelKeys = nodeParamsB.channelKeyManager.channelKeys(channelParamsB.channelConfig, channelParamsB.localParams.fundingKeyPath)

    private val firstPerCommitmentPointA = channelKeysA.commitmentPoint(0)
    private val firstPerCommitmentPointB = channelKeysB.commitmentPoint(0)
    val fundingPubkeyScript: ByteVector = Transactions.makeFundingScript(fundingParamsB.remoteFundingPubKey, fundingParamsA.remoteFundingPubKey, fundingParamsA.commitmentFormat).pubkeyScript

    def sharedInputs(commitmentA: Commitment, commitmentB: Commitment): (SharedFundingInput, SharedFundingInput) = {
      val sharedInputA = SharedFundingInput(channelKeysA, commitmentA)
      val sharedInputB = SharedFundingInput(channelKeysB, commitmentB)
      (sharedInputA, sharedInputB)
    }

    def dummySharedInputB(amount: Satoshi): SharedFundingInput = {
      val inputInfo = InputInfo(OutPoint(randomTxId(), 3), TxOut(amount, fundingPubkeyScript))
      val fundingTxIndex = fundingParamsA.sharedInput_opt match {
        case Some(input) => input.fundingTxIndex + 1
        case _ => 0
      }
      SharedFundingInput(inputInfo, fundingTxIndex, fundingParamsA.remoteFundingPubKey, fundingParamsA.commitmentFormat)
    }

    def createSpliceFixtureParams(fundingTxIndex: Long, fundingAmountA: Satoshi, fundingAmountB: Satoshi, targetFeerate: FeeratePerKw, dustLimit: Satoshi, lockTime: Long, sharedInputA: SharedFundingInput, sharedInputB: SharedFundingInput, nextCommitmentFormat_opt: Option[CommitmentFormat] = None, spliceOutputsA: List[TxOut] = Nil, spliceOutputsB: List[TxOut] = Nil, requireConfirmedInputs: RequireConfirmedInputs = RequireConfirmedInputs(forLocal = false, forRemote = false)): FixtureParams = {
      val fundingPubKeyA = channelKeysA.fundingKey(fundingTxIndex).publicKey
      val fundingPubKeyB = channelKeysB.fundingKey(fundingTxIndex).publicKey
      val nextCommitmentFormat = nextCommitmentFormat_opt.getOrElse(fundingParamsA.commitmentFormat)
      val fundingParamsA1 = InteractiveTxParams(channelId, isInitiator = true, fundingAmountA, fundingAmountB, Some(sharedInputA), fundingPubKeyB, spliceOutputsA, nextCommitmentFormat, lockTime, dustLimit, targetFeerate, requireConfirmedInputs)
      val fundingParamsB1 = InteractiveTxParams(channelId, isInitiator = false, fundingAmountB, fundingAmountA, Some(sharedInputB), fundingPubKeyA, spliceOutputsB, nextCommitmentFormat, lockTime, dustLimit, targetFeerate, requireConfirmedInputs)
      copy(fundingParamsA = fundingParamsA1, fundingParamsB = fundingParamsB1)
    }

    def spawnTxBuilderAlice(wallet: OnChainWallet, fundingParams: InteractiveTxParams = fundingParamsA, liquidityPurchase_opt: Option[LiquidityAds.Purchase] = None): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      ByteVector32.Zeroes,
      nodeParamsA, fundingParams, channelParamsA, commitParamsA, commitParamsB, channelKeysA,
      FundingTx(commitFeerate, firstPerCommitmentPointB, feeBudget_opt = None),
      0 msat, 0 msat, liquidityPurchase_opt,
      wallet))

    def spawnTxBuilderRbfAlice(fundingParams: InteractiveTxParams, commitment: Commitment, previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction], wallet: OnChainWallet): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      ByteVector32.Zeroes,
      nodeParamsA, fundingParams, channelParamsA, commitParamsA, commitParamsB, channelKeysA,
      FundingTxRbf(commitment, previousTransactions, feeBudget_opt = None),
      0 msat, 0 msat, None,
      wallet))

    def spawnTxBuilderSpliceAlice(fundingParams: InteractiveTxParams, commitment: Commitment, wallet: OnChainWallet, liquidityPurchase_opt: Option[LiquidityAds.Purchase] = None): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      ByteVector32.Zeroes,
      nodeParamsA, fundingParams, channelParamsA, commitParamsA, commitParamsB, channelKeysA,
      SpliceTx(commitment, CommitmentChanges.init()),
      0 msat, 0 msat, liquidityPurchase_opt,
      wallet))

    def spawnTxBuilderSpliceRbfAlice(fundingParams: InteractiveTxParams, parentCommitment: Commitment, latestFundingTx: LocalFundingStatus.DualFundedUnconfirmedFundingTx, previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction], wallet: OnChainWallet): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      ByteVector32.Zeroes,
      nodeParamsA, fundingParams, channelParamsA, commitParamsA, commitParamsB, channelKeysA,
      SpliceTxRbf(parentCommitment, CommitmentChanges.init(), latestFundingTx, previousTransactions, feeBudget_opt = None),
      0 msat, 0 msat, None,
      wallet))

    def spawnTxBuilderBob(wallet: OnChainWallet, fundingParams: InteractiveTxParams = fundingParamsB, liquidityPurchase_opt: Option[LiquidityAds.Purchase] = None): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      ByteVector32.Zeroes,
      nodeParamsB, fundingParams, channelParamsB, commitParamsB, commitParamsA, channelKeysB,
      FundingTx(commitFeerate, firstPerCommitmentPointA, feeBudget_opt = None),
      0 msat, 0 msat, liquidityPurchase_opt,
      wallet))

    def spawnTxBuilderRbfBob(fundingParams: InteractiveTxParams, commitment: Commitment, previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction], wallet: OnChainWallet): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      ByteVector32.Zeroes,
      nodeParamsB, fundingParams, channelParamsB, commitParamsB, commitParamsA, channelKeysB,
      FundingTxRbf(commitment, previousTransactions, feeBudget_opt = None),
      0 msat, 0 msat, None,
      wallet))

    def spawnTxBuilderSpliceBob(fundingParams: InteractiveTxParams, commitment: Commitment, wallet: OnChainWallet, liquidityPurchase_opt: Option[LiquidityAds.Purchase] = None): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      ByteVector32.Zeroes,
      nodeParamsB, fundingParams, channelParamsB, commitParamsB, commitParamsA, channelKeysB,
      SpliceTx(commitment, CommitmentChanges.init()),
      0 msat, 0 msat, liquidityPurchase_opt,
      wallet))

    def spawnTxBuilderSpliceRbfBob(fundingParams: InteractiveTxParams, parentCommitment: Commitment, latestFundingTx: LocalFundingStatus.DualFundedUnconfirmedFundingTx, previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction], wallet: OnChainWallet): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      ByteVector32.Zeroes,
      nodeParamsB, fundingParams, channelParamsB, commitParamsB, commitParamsA, channelKeysB,
      SpliceTxRbf(parentCommitment, CommitmentChanges.init(), latestFundingTx, previousTransactions, feeBudget_opt = None),
      0 msat, 0 msat, None,
      wallet))

    def exchangeSigsAliceFirst(fundingParams: InteractiveTxParams, successA: InteractiveTxBuilder.Succeeded, successB: InteractiveTxBuilder.Succeeded): (FullySignedSharedTransaction, Commitment, FullySignedSharedTransaction, Commitment) = {
      implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging
      // Alice --- commit_sig --> Bob
      val Right(signingSessionB2: InteractiveTxSigningSession.WaitingForSigs) = successB.signingSession.receiveCommitSig(channelParamsB, channelKeysB, successA.commitSig, nodeParamsB.currentBlockHeight)
      // Alice <-- commit_sig --- Bob
      val Right(sigsA: InteractiveTxSigningSession.SendingSigs) = successA.signingSession.receiveCommitSig(channelParamsA, channelKeysA, successB.commitSig, nodeParamsA.currentBlockHeight)
      assert(sigsA.fundingTx.sharedTx.isInstanceOf[PartiallySignedSharedTransaction])
      // Alice --- tx_signatures --> Bob
      val Right(sigsB) = signingSessionB2.receiveTxSigs(channelKeysB, sigsA.localSigs, nodeParamsB.currentBlockHeight)
      assert(sigsB.fundingTx.sharedTx.isInstanceOf[FullySignedSharedTransaction])
      val txB = sigsB.fundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      // Alice <-- tx_signatures --- Bob
      val Right(txA) = InteractiveTxSigningSession.addRemoteSigs(channelKeysA, fundingParams, sigsA.fundingTx.sharedTx.asInstanceOf[PartiallySignedSharedTransaction], sigsB.localSigs)
      (txA, sigsA.commitment, txB, sigsB.commitment)
    }

    def exchangeSigsBobFirst(fundingParams: InteractiveTxParams, successA: InteractiveTxBuilder.Succeeded, successB: InteractiveTxBuilder.Succeeded): (FullySignedSharedTransaction, Commitment, FullySignedSharedTransaction, Commitment) = {
      implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging
      // Alice <-- commit_sig --- Bob
      val Right(signingSessionA2: InteractiveTxSigningSession.WaitingForSigs) = successA.signingSession.receiveCommitSig(channelParamsA, channelKeysA, successB.commitSig, nodeParamsA.currentBlockHeight)
      // Alice --- commit_sig --> Bob
      val Right(sigsB: InteractiveTxSigningSession.SendingSigs) = successB.signingSession.receiveCommitSig(channelParamsB, channelKeysB, successA.commitSig, nodeParamsB.currentBlockHeight)
      assert(sigsB.fundingTx.sharedTx.isInstanceOf[PartiallySignedSharedTransaction])
      // Alice <-- tx_signatures --- Bob
      val Right(sigsA) = signingSessionA2.receiveTxSigs(channelKeysA, sigsB.localSigs, nodeParamsA.currentBlockHeight)
      assert(sigsA.fundingTx.sharedTx.isInstanceOf[FullySignedSharedTransaction])
      val txA = sigsA.fundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      // Alice --- tx_signatures --> Bob
      val Right(txB) = InteractiveTxSigningSession.addRemoteSigs(channelKeysB, fundingParams, sigsB.fundingTx.sharedTx.asInstanceOf[PartiallySignedSharedTransaction], sigsA.localSigs)
      (txA, sigsA.commitment, txB, sigsB.commitment)
    }
  }

  private def createFixtureParams(channelType: SupportedChannelType, fundingAmountA: Satoshi, fundingAmountB: Satoshi, targetFeerate: FeeratePerKw, dustLimit: Satoshi, lockTime: Long, requireConfirmedInputs: RequireConfirmedInputs = RequireConfirmedInputs(forLocal = false, forRemote = false), nonInitiatorPaysCommitTxFees: Boolean = false): FixtureParams = {
    val Seq(nodeParamsA, nodeParamsB) = Seq(TestConstants.Alice.nodeParams, TestConstants.Bob.nodeParams).map(_.copy(features = Features(channelType.features.map(f => f -> FeatureSupport.Optional).toMap[Feature, FeatureSupport])))
    val localChannelParamsA = makeChannelParams(nodeParamsA, nodeParamsA.features.initFeatures(), None, None, isChannelOpener = true, paysCommitTxFees = !nonInitiatorPaysCommitTxFees, dualFunded = true, fundingAmountA)
    val commitParamsA = CommitParams(nodeParamsA.channelConf.dustLimit, nodeParamsA.channelConf.htlcMinimum, nodeParamsA.channelConf.maxHtlcValueInFlight(fundingAmountA + fundingAmountB, unlimited = false), nodeParamsA.channelConf.maxAcceptedHtlcs, nodeParamsB.channelConf.toRemoteDelay)
    val localChannelParamsB = makeChannelParams(nodeParamsB, nodeParamsB.features.initFeatures(), None, None, isChannelOpener = false, paysCommitTxFees = nonInitiatorPaysCommitTxFees, dualFunded = true, fundingAmountB)
    val commitParamsB = CommitParams(nodeParamsB.channelConf.dustLimit, nodeParamsB.channelConf.htlcMinimum, nodeParamsB.channelConf.maxHtlcValueInFlight(fundingAmountA + fundingAmountB, unlimited = false), nodeParamsB.channelConf.maxAcceptedHtlcs, nodeParamsA.channelConf.toRemoteDelay)
    val channelKeysA = nodeParamsA.channelKeyManager.channelKeys(ChannelConfig.standard, localChannelParamsA.fundingKeyPath)
    val channelKeysB = nodeParamsB.channelKeyManager.channelKeys(ChannelConfig.standard, localChannelParamsB.fundingKeyPath)

    val Seq(remoteChannelParamsA, remoteChannelParamsB) = Seq((nodeParamsA, localChannelParamsA, channelKeysA), (nodeParamsB, localChannelParamsB, channelKeysB)).map {
      case (nodeParams, localParams, channelKeys) =>
        RemoteChannelParams(
          nodeParams.nodeId,
          None,
          channelKeys.revocationBasePoint,
          localParams.walletStaticPaymentBasepoint.getOrElse(channelKeys.paymentBasePoint),
          channelKeys.delayedPaymentBasePoint,
          channelKeys.htlcBasePoint,
          localParams.initFeatures,
          None)
    }

    val channelId = randomBytes32()
    val fundingPubKeyA = channelKeysA.fundingKey(fundingTxIndex = 0).publicKey
    val fundingPubKeyB = channelKeysB.fundingKey(fundingTxIndex = 0).publicKey
    val fundingParamsA = InteractiveTxParams(channelId, isInitiator = true, fundingAmountA, fundingAmountB, None, fundingPubKeyB, Nil, channelType.commitmentFormat, lockTime, dustLimit, targetFeerate, requireConfirmedInputs)
    val fundingParamsB = InteractiveTxParams(channelId, isInitiator = false, fundingAmountB, fundingAmountA, None, fundingPubKeyA, Nil, channelType.commitmentFormat, lockTime, dustLimit, targetFeerate, requireConfirmedInputs)
    val channelParamsA = ChannelParams(channelId, ChannelConfig.standard, ChannelFeatures(Features.DualFunding), localChannelParamsA, remoteChannelParamsB, ChannelFlags(announceChannel = true))
    val channelParamsB = ChannelParams(channelId, ChannelConfig.standard, ChannelFeatures(Features.DualFunding), localChannelParamsB, remoteChannelParamsA, ChannelFlags(announceChannel = true))

    FixtureParams(fundingParamsA, nodeParamsA, channelParamsA, commitParamsA, fundingParamsB, nodeParamsB, channelParamsB, commitParamsB)
  }

  case class Fixture(alice: ActorRef[InteractiveTxBuilder.Command],
                     bob: ActorRef[InteractiveTxBuilder.Command],
                     fixtureParams: FixtureParams,
                     walletA: BitcoinCoreClient,
                     rpcClientA: BitcoinJsonRPCClient,
                     walletB: BitcoinCoreClient,
                     rpcClientB: BitcoinJsonRPCClient,
                     alice2bob: TestProbe,
                     bob2alice: TestProbe) {
    val aliceParams: InteractiveTxParams = fixtureParams.fundingParamsA
    val bobParams: InteractiveTxParams = fixtureParams.fundingParamsB
    val fwd: TypeCheckedForwarder = TypeCheckedForwarder(alice, bob, alice2bob, bob2alice)
  }

  case class TypeCheckedForwarder(alice: ActorRef[InteractiveTxBuilder.Command],
                                  bob: ActorRef[InteractiveTxBuilder.Command],
                                  alice2bob: TestProbe,
                                  bob2alice: TestProbe) {
    def forwardAlice2Bob[T <: LightningMessage](implicit t: ClassTag[T]): T = forwardMessage(alice2bob, bob)(t)

    def forwardBob2Alice[T <: LightningMessage](implicit t: ClassTag[T]): T = forwardMessage(bob2alice, alice)(t)

    private def forwardMessage[T <: LightningMessage](s2r: TestProbe, r: ActorRef[InteractiveTxBuilder.Command])(implicit t: ClassTag[T]): T = {
      val msg = s2r.expectMsgType[SendMessage].msg
      val c = t.runtimeClass.asInstanceOf[Class[T]]
      assert(c.isInstance(msg), s"expected $c, found ${msg.getClass} ($msg)")
      msg match {
        case msg: InteractiveTxConstructionMessage => r ! ReceiveMessage(msg)
        case msg => fail(s"invalid message sent ($msg)")
      }
      msg.asInstanceOf[T]
    }
  }

  private def withFixture(channelType: SupportedChannelType, fundingAmountA: Satoshi, utxosA: Seq[Satoshi], fundingAmountB: Satoshi, utxosB: Seq[Satoshi], targetFeerate: FeeratePerKw, dustLimit: Satoshi, lockTime: Long, requireConfirmedInputs: RequireConfirmedInputs, liquidityPurchase_opt: Option[LiquidityAds.Purchase] = None)(testFun: Fixture => Any): Unit = {
    // Initialize wallets with a few confirmed utxos.
    val probe = TestProbe()
    val rpcClientA = createWallet(UUID.randomUUID().toString)
    val walletA = new BitcoinCoreClient(rpcClientA)
    utxosA.foreach(amount => addUtxo(walletA, amount, probe))
    val rpcClientB = createWallet(UUID.randomUUID().toString)
    val walletB = new BitcoinCoreClient(rpcClientB)
    utxosB.foreach(amount => addUtxo(walletB, amount, probe))
    generateBlocks(1)

    val fixtureParams = createFixtureParams(channelType, fundingAmountA, fundingAmountB, targetFeerate, dustLimit, lockTime, requireConfirmedInputs, nonInitiatorPaysCommitTxFees = liquidityPurchase_opt.nonEmpty)
    val alice = fixtureParams.spawnTxBuilderAlice(walletA, liquidityPurchase_opt = liquidityPurchase_opt)
    val bob = fixtureParams.spawnTxBuilderBob(walletB, liquidityPurchase_opt = liquidityPurchase_opt)
    testFun(Fixture(alice, bob, fixtureParams, walletA, rpcClientA, walletB, rpcClientB, TestProbe(), TestProbe()))
  }

  test("initiator funds more than non-initiator") {
    val targetFeerate = FeeratePerKw(5000 sat)
    val fundingA = 120_000 sat
    val utxosA = Seq(50_000 sat, 35_000 sat, 60_000 sat)
    val fundingB = 40_000 sat
    val utxosB = Seq(100_000 sat)
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), fundingA, utxosA, fundingB, utxosB, targetFeerate, 660 sat, 42, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Bob waits for Alice to send the first message.
      bob2alice.expectNoMessage(100 millis)
      // Alice --- tx_add_input --> Bob
      val inputA1 = fwd.forwardAlice2Bob[TxAddInput]
      assert(inputA1.previousTx_opt.exists(_.txIn.forall(_.witness == ScriptWitness.empty)), "witnesses must be removed from parent txs to save space")
      // Alice <-- tx_add_input --- Bob
      val inputB1 = fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_input --> Bob
      val inputA2 = fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_output --- Bob
      val outputB1 = fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_input --> Bob
      val inputA3 = fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      val outputA1 = fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      val outputA2 = fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      // Utxos are locked for the duration of the protocol.
      val probe = TestProbe()
      walletA.listLockedOutpoints().pipeTo(probe.ref)
      val locksA = probe.expectMsgType[Set[OutPoint]]
      assert(locksA.size == 3)
      assert(locksA == Set(inputA1, inputA2, inputA3).map(toOutPoint))
      walletB.listLockedOutpoints().pipeTo(probe.ref)
      val locksB = probe.expectMsgType[Set[OutPoint]]
      assert(locksB.size == 1)
      assert(locksB == Set(toOutPoint(inputB1)))

      // Alice is responsible for adding the shared output.
      assert(aliceParams.fundingAmount == fundingA + fundingB)
      assert(Seq(outputA1, outputA2).count(_.pubkeyScript == f.fixtureParams.fundingPubkeyScript) == 1)
      assert(Seq(outputA1, outputA2).exists(o => o.pubkeyScript == f.fixtureParams.fundingPubkeyScript && o.amount == fundingA + fundingB))
      assert(outputB1.pubkeyScript != f.fixtureParams.fundingPubkeyScript)

      // Bob sends signatures first as he contributed less than Alice.
      val successA = alice2bob.expectMsgType[Succeeded]
      val successB = bob2alice.expectMsgType[Succeeded]
      val (txA, _, txB, _) = fixtureParams.exchangeSigsBobFirst(bobParams, successA, successB)
      // The resulting transaction is valid and has the right feerate.
      assert(txA.txId == txB.txId)
      assert(txA.signedTx.lockTime == aliceParams.lockTime)
      assert(txA.tx.localAmountIn == utxosA.sum.toMilliSatoshi)
      assert(txA.tx.remoteAmountIn == utxosB.sum.toMilliSatoshi)
      assert(0.msat < txB.tx.localFees)
      assert(txB.tx.localFees == txA.tx.remoteFees)
      assert(txB.tx.localFees < txA.tx.localFees)
      walletA.publishTransaction(txA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA.txId)
      walletA.getMempoolTx(txA.txId).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == txA.tx.fees)
      assert(txA.tx.fees == txB.tx.fees)
      assert(targetFeerate <= txA.feerate && txA.feerate <= targetFeerate * 1.25, s"unexpected feerate (target=$targetFeerate actual=${txA.feerate})")
    }
  }

  test("initiator funds less than non-initiator") {
    val targetFeerate = FeeratePerKw(3000 sat)
    val fundingA = 10_000 sat
    val utxosA = Seq(50_000 sat)
    val fundingB = 50_000 sat
    val utxosB = Seq(80_000 sat)
    withFixture(ChannelTypes.AnchorOutputs(), fundingA, utxosA, fundingB, utxosB, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = true, forRemote = true)) { f =>
      import f._

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Even though the initiator isn't contributing, they're paying the fees for the common parts of the transaction.
      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      val outputA1 = fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      val outputB = fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      val outputA2 = fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      // Alice is responsible for adding the shared output.
      assert(aliceParams.fundingAmount == fundingA + fundingB)
      assert(Seq(outputA1, outputA2).count(_.pubkeyScript == f.fixtureParams.fundingPubkeyScript) == 1)
      assert(Seq(outputA1, outputA2).exists(o => o.pubkeyScript == f.fixtureParams.fundingPubkeyScript && o.amount == fundingA + fundingB))
      assert(outputB.pubkeyScript != f.fixtureParams.fundingPubkeyScript)

      // Alice sends signatures first as she contributed less than Bob.
      val successA = alice2bob.expectMsgType[Succeeded]
      val successB = bob2alice.expectMsgType[Succeeded]
      val (txA, _, txB, _) = fixtureParams.exchangeSigsAliceFirst(aliceParams, successA, successB)
      // The resulting transaction is valid and has the right feerate.
      assert(txB.signedTx.lockTime == aliceParams.lockTime)
      assert(txB.tx.localAmountIn == utxosB.sum.toMilliSatoshi)
      assert(txB.tx.remoteAmountIn == utxosA.sum.toMilliSatoshi)
      assert(0.msat < txA.tx.localFees)
      assert(0.msat < txB.tx.localFees)
      assert(txA.tx.remoteFees == txB.tx.localFees)
      val probe = TestProbe()
      walletB.publishTransaction(txB.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txB.txId)
      walletB.getMempoolTx(txB.txId).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == txB.tx.fees)
      assert(txA.tx.fees == txB.tx.fees)
      assert(targetFeerate <= txB.feerate && txB.feerate <= targetFeerate * 1.25, s"unexpected feerate (target=$targetFeerate actual=${txB.feerate})")
    }
  }

  test("initiator funds more than non-initiator but contributes less") {
    val targetFeerate = FeeratePerKw(5000 sat)
    val fundingA = 100_000 sat
    val utxosA = Seq(150_000 sat)
    val fundingB = 50_000 sat
    val utxosB = Seq(200_000 sat)
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), fundingA, utxosA, fundingB, utxosB, targetFeerate, 660 sat, 42, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Bob waits for Alice to send the first message.
      bob2alice.expectNoMessage(100 millis)
      // Alice --- tx_add_input --> Bob
      val inputA = fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      val inputB = fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      // Alice contributes more than Bob to funding output, but Bob's inputs are bigger than Alice's, so Alice must sign first.
      assert(inputA.previousTx_opt.get.txOut(inputA.previousTxOutput.toInt).amount < inputB.previousTx_opt.get.txOut(inputB.previousTxOutput.toInt).amount)
      val successA = alice2bob.expectMsgType[Succeeded]
      val successB = bob2alice.expectMsgType[Succeeded]
      val (_, _, txB, _) = fixtureParams.exchangeSigsAliceFirst(aliceParams, successA, successB)
      // The resulting transaction is valid.
      val probe = TestProbe()
      walletA.publishTransaction(txB.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txB.txId)
    }
  }

  test("non-initiator does not contribute") {
    val targetFeerate = FeeratePerKw(2500 sat)
    val fundingA = 150_000 sat
    val utxosA = Seq(80_000 sat, 120_000 sat)
    withFixture(ChannelTypes.SimpleTaprootChannelsStaging(), fundingA, utxosA, 0 sat, Nil, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      val txCompleteB1 = fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      val outputA1 = fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      val txCompleteB2 = fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      val outputA2 = fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      val txCompleteB3 = fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      val txCompleteA = fwd.forwardAlice2Bob[TxComplete]
      assert(txCompleteA.commitNonces_opt.nonEmpty)
      assert(txCompleteA.fundingNonce_opt.isEmpty)
      Seq(txCompleteB1, txCompleteB2, txCompleteB3).foreach(txCompleteB => {
        assert(txCompleteB.commitNonces_opt.nonEmpty)
        assert(txCompleteB.fundingNonce_opt.isEmpty)
      })
      // Nonces change every time the shared transaction changes.
      assert(Seq(txCompleteB1, txCompleteB2, txCompleteB3).flatMap(_.commitNonces_opt).flatMap(n => Seq(n.commitNonce, n.nextCommitNonce)).toSet.size == 6)

      // Alice is responsible for adding the shared output.
      assert(aliceParams.fundingAmount == fundingA)
      assert(Seq(outputA1, outputA2).count(_.pubkeyScript == f.fixtureParams.fundingPubkeyScript) == 1)
      assert(Seq(outputA1, outputA2).exists(o => o.pubkeyScript == f.fixtureParams.fundingPubkeyScript && o.amount == fundingA))

      // Bob sends signatures first as he did not contribute at all.
      val successA = alice2bob.expectMsgType[Succeeded]
      assert(successA.commitSig.sigOrPartialSig.isInstanceOf[PartialSignatureWithNonce])
      val successB = bob2alice.expectMsgType[Succeeded]
      assert(successB.commitSig.sigOrPartialSig.isInstanceOf[PartialSignatureWithNonce])
      val (txA, _, txB, _) = fixtureParams.exchangeSigsBobFirst(bobParams, successA, successB)
      assert(successA.nextRemoteCommitNonce_opt.contains((txA.txId, txCompleteB3.commitNonces_opt.get.nextCommitNonce)))
      assert(successB.nextRemoteCommitNonce_opt.contains((txB.txId, txCompleteA.commitNonces_opt.get.nextCommitNonce)))
      // The resulting transaction is valid and has the right feerate.
      assert(txA.txId == txB.txId)
      assert(txA.signedTx.lockTime == aliceParams.lockTime)
      assert(txA.tx.localAmountIn == utxosA.sum.toMilliSatoshi)
      assert(txA.tx.remoteAmountIn == 0.msat)
      assert(txB.tx.localFees == 0.msat)
      assert(txA.tx.localFees == txA.tx.fees.toMilliSatoshi)
      val probe = TestProbe()
      walletA.publishTransaction(txA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA.txId)
      walletA.getMempoolTx(txA.txId).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == txA.tx.fees)
      assert(targetFeerate <= txA.feerate && txA.feerate <= targetFeerate * 1.25, s"unexpected feerate (target=$targetFeerate actual=${txA.feerate})")
    }
  }

  test("initiator uses unconfirmed inputs") {
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, Seq(170_000 sat), 0 sat, Nil, FeeratePerKw(2500 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      // Alice's inputs are all unconfirmed: we spent her only confirmed input to create two unconfirmed outputs.
      val probe = TestProbe()
      val tx = sendToAddress(getNewAddress(probe, rpcClientA), 75_000 sat, probe, rpcClientA)
      walletA.listUnspent().pipeTo(probe.ref)
      val utxosA = probe.expectMsgType[Seq[Utxo]]
      assert(utxosA.size == 2)
      utxosA.foreach(utxo => assert(utxo.confirmations == 0))
      utxosA.foreach(utxo => assert(utxo.amount < 100_000.sat)) // Alice will need both inputs to fund the channel.

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA = alice2bob.expectMsgType[Succeeded]
      val successB = bob2alice.expectMsgType[Succeeded]
      val (txA, _, _, _) = fixtureParams.exchangeSigsBobFirst(bobParams, successA, successB)
      txA.signedTx.txIn.foreach(txIn => assert(txIn.outPoint.txid == tx.txid))
    }
  }

  test("initiator does not contribute -- on-the-fly funding") {
    val targetFeerate = FeeratePerKw(5000 sat)
    val fundingB = 150_000.sat
    val utxosB = Seq(200_000 sat)
    // When on-the-fly funding is used, the initiator may not contribute to the funding transaction.
    // It will receive HTLCs later that use the purchased inbound liquidity, and liquidity fees will be deduced from those HTLCs.
    val purchase = LiquidityAds.Purchase.Standard(fundingB, LiquidityAds.Fees(2500 sat, 7500 sat), LiquidityAds.PaymentDetails.FromFutureHtlc(Nil))
    withFixture(ChannelTypes.AnchorOutputs(), 0 sat, Nil, fundingB, utxosB, targetFeerate, 330 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false), Some(purchase)) { f =>
      import f._

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]
      // Alice <-- tx_add_output --- Bob
      fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]

      // Alice is responsible for adding the shared output, but Bob is paying for everything.
      assert(aliceParams.fundingAmount == fundingB)

      // Alice sends signatures first as she did not contribute at all.
      val successA = alice2bob.expectMsgType[Succeeded]
      val successB = bob2alice.expectMsgType[Succeeded]
      val (txA, _, txB, commitmentB) = fixtureParams.exchangeSigsAliceFirst(aliceParams, successA, successB)
      // Alice doesn't pay any fees to Bob during the interactive-tx, fees will be paid from future HTLCs.
      assert(commitmentB.localCommit.spec.toLocal == fundingB.toMilliSatoshi)

      // The resulting transaction is valid but has a lower feerate than expected.
      assert(txA.txId == txB.txId)
      assert(txA.tx.localAmountIn == 0.msat)
      assert(txA.tx.localFees == 0.msat)
      assert(txB.tx.remoteAmountIn == 0.msat)
      assert(txB.tx.remoteFees == 0.msat)
      assert(txB.tx.localFees > 0.msat)
      val probe = TestProbe()
      walletA.publishTransaction(txA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA.txId)
      walletA.getMempoolTx(txA.txId).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == txA.tx.fees)
      assert(targetFeerate * 0.5 <= txA.feerate && txA.feerate < targetFeerate, s"unexpected feerate (target=$targetFeerate actual=${txA.feerate})")
    }
  }

  test("initiator does not contribute -- on-the-fly funding with fee credit") {
    val targetFeerate = FeeratePerKw(5000 sat)
    val fundingA = 2_500.sat
    val utxosA = Seq(5_000 sat)
    val fundingB = 150_000.sat
    val utxosB = Seq(200_000 sat)
    // The initiator contributes a small amount, and pays the remaining liquidity fees from its fee credit.
    val purchase = LiquidityAds.Purchase.WithFeeCredit(fundingB, LiquidityAds.Fees(2500 sat, 7500 sat), 7_500_000 msat, LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc(Nil))
    withFixture(ChannelTypes.AnchorOutputs(), fundingA, utxosA, fundingB, utxosB, targetFeerate, 330 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false), Some(purchase)) { f =>
      import f._

      // Alice has enough fee credit.
      fixtureParams.nodeParamsB.db.liquidity.addFeeCredit(fixtureParams.nodeParamsA.nodeId, 7_500_000 msat)

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]

      // Alice sends signatures first as she contributed less.
      val successA = alice2bob.expectMsgType[Succeeded]
      val successB = bob2alice.expectMsgType[Succeeded]
      val (txA, _, txB, commitmentB) = fixtureParams.exchangeSigsAliceFirst(aliceParams, successA, successB)
      // Alice partially paid fees to Bob during the interactive-tx using her channel balance, the rest was paid from fee credit.
      assert(commitmentB.localCommit.spec.toLocal == (fundingA + fundingB).toMilliSatoshi)
      assert(commitmentB.localCommit.spec.toRemote == 0.msat)

      // The resulting transaction is valid.
      assert(txA.txId == txB.txId)
      assert(txA.tx.localFees == 2_500_000.msat)
      assert(txB.tx.remoteFees == 2_500_000.msat)
      assert(txB.tx.localFees > 0.msat)
      val probe = TestProbe()
      walletA.publishTransaction(txA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA.txId)
      walletA.getMempoolTx(txA.txId).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == txA.tx.fees)
      assert(targetFeerate * 0.9 <= txA.feerate && txA.feerate < targetFeerate * 1.25, s"unexpected feerate (target=$targetFeerate actual=${txA.feerate})")
    }
  }

  test("initiator does not contribute -- on-the-fly funding without enough fee credit") {
    val targetFeerate = FeeratePerKw(5000 sat)
    val fundingB = 150_000.sat
    val utxosB = Seq(200_000 sat)
    // The initiator wants to pay the liquidity fees from their fee credit, but they don't have enough of it.
    val purchase = LiquidityAds.Purchase.WithFeeCredit(fundingB, LiquidityAds.Fees(2500 sat, 7500 sat), 10_000_000 msat, LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc(Nil))
    withFixture(ChannelTypes.AnchorOutputs(), 0 sat, Nil, fundingB, utxosB, targetFeerate, 330 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false), Some(purchase)) { f =>
      import f._

      // Alice doesn't have enough fee credit.
      fixtureParams.nodeParamsB.db.liquidity.addFeeCredit(fixtureParams.nodeParamsA.nodeId, 9_000_000 msat)

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]
      // Alice <-- tx_add_output --- Bob
      fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Bob rejects the funding attempt because Alice doesn't have enough fee credit.
      assert(bob2alice.expectMsgType[RemoteFailure].cause.isInstanceOf[InvalidCompleteInteractiveTx])
    }
  }

  test("initiator and non-initiator splice-in") {
    val targetFeerate = FeeratePerKw(1000 sat)
    // We chose those amounts to ensure that Bob always signs first:
    //  - funding tx: Alice has one 380 000 sat input and Bob has one 350 000 sat input
    //  - splice tx: Alice has the shared input (150 000 sat) and one 380 000 sat input, Bob has one 350 000 sat input
    // It verifies that we don't split the shared input amount: if we did, Alice would sign first.
    val fundingA1 = 50_000 sat
    val utxosA = Seq(380_000 sat, 380_000 sat)
    val fundingB1 = 100_000 sat
    val utxosB = Seq(350_000 sat, 350_000 sat)
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), fundingA1, utxosA, fundingB1, utxosB, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = true, forRemote = true)) { f =>
      import f._

      val probe = TestProbe()
      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA1 = alice2bob.expectMsgType[Succeeded]
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val (txA1, commitmentA1, _, commitmentB1) = fixtureParams.exchangeSigsBobFirst(bobParams, successA1, successB1)
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.txId)

      // Alice and Bob decide to splice additional funds in the channel.
      val additionalFundingA2 = 30_000.sat
      val additionalFundingB2 = 25_000.sat
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(commitmentA1, commitmentB1)
      val spliceFixtureParams = fixtureParams.createSpliceFixtureParams(fundingTxIndex = 1, fundingAmountA = additionalFundingA2, fundingAmountB = additionalFundingB2, aliceParams.targetFeerate, aliceParams.dustLimit, aliceParams.lockTime, sharedInputA = sharedInputA, sharedInputB = sharedInputB, requireConfirmedInputs = aliceParams.requireConfirmedInputs)
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(spliceFixtureParams.fundingParamsA, commitmentA1, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(spliceFixtureParams.fundingParamsB, commitmentB1, walletB)
      val fwdSplice = TypeCheckedForwarder(aliceSplice, bobSplice, alice2bob, bob2alice)

      aliceSplice ! Start(alice2bob.ref)
      bobSplice ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwdSplice.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwdSplice.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_input --> Bob
      fwdSplice.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_output --- Bob
      fwdSplice.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdSplice.forwardAlice2Bob[TxComplete]

      val successA2 = alice2bob.expectMsgType[Succeeded]
      assert(successA2.signingSession.fundingTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      val successB2 = bob2alice.expectMsgType[Succeeded]
      assert(successB2.signingSession.fundingTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      val (spliceTxA, commitmentA2, spliceTxB, commitmentB2) = fixtureParams.exchangeSigsBobFirst(spliceFixtureParams.fundingParamsB, successA2, successB2)
      // Bob has more balance than Alice in the shared input, so its total contribution is greater than Alice.
      // But Bob still signs first, because we don't split the shared input's balance when deciding who signs first.
      assert(spliceTxA.tx.localAmountIn < spliceTxA.tx.remoteAmountIn)
      assert(spliceTxA.signedTx.txIn.exists(_.outPoint == commitmentA1.fundingInput))
      assert(0.msat < spliceTxA.tx.localFees)
      assert(0.msat < spliceTxA.tx.remoteFees)
      assert(spliceTxB.tx.localFees == spliceTxA.tx.remoteFees)
      assert(spliceTxA.tx.sharedOutput.amount == fundingA1 + fundingB1 + additionalFundingA2 + additionalFundingB2)

      assert(commitmentA2.localCommit.spec.toLocal == (fundingA1 + additionalFundingA2).toMilliSatoshi)
      assert(commitmentA2.localCommit.spec.toRemote == (fundingB1 + additionalFundingB2).toMilliSatoshi)
      assert(commitmentB2.localCommit.spec.toLocal == (fundingB1 + additionalFundingB2).toMilliSatoshi)
      assert(commitmentB2.localCommit.spec.toRemote == (fundingA1 + additionalFundingA2).toMilliSatoshi)

      // The resulting transaction is valid and has the right feerate.
      walletA.publishTransaction(spliceTxA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA.txId)
      walletA.getMempoolTx(spliceTxA.txId).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == spliceTxA.tx.fees)
      assert(targetFeerate <= spliceTxA.feerate && spliceTxA.feerate <= targetFeerate * 1.25, s"unexpected feerate (target=$targetFeerate actual=${spliceTxA.feerate})")
    }
  }

  test("initiator and non-initiator splice-out (single)") {
    val fundingA1 = 100_000 sat
    val utxosA = Seq(150_000 sat)
    val fundingB1 = 90_000 sat
    val utxosB = Seq(130_000 sat)
    withFixture(ChannelTypes.SimpleTaprootChannelsPhoenix(), fundingA1, utxosA, fundingB1, utxosB, FeeratePerKw(1000 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = true, forRemote = true)) { f =>
      import f._

      val probe = TestProbe()
      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA1 = alice2bob.expectMsgType[Succeeded]
      assert(successA1.nextRemoteCommitNonce_opt.nonEmpty)
      val successB1 = bob2alice.expectMsgType[Succeeded]
      assert(successB1.nextRemoteCommitNonce_opt.nonEmpty)
      val (txA1, commitmentA1, _, commitmentB1) = fixtureParams.exchangeSigsBobFirst(bobParams, successA1, successB1)
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.txId)

      // Alice and Bob decide to splice funds out of the channel, and deduce on-chain fees from their new channel contribution.
      val spliceOutputsA = List(TxOut(50_000 sat, Script.pay2tr(randomKey().xOnlyPublicKey())))
      val spliceOutputsB = List(TxOut(30_000 sat, Script.pay2tr(randomKey().xOnlyPublicKey())))
      val subtractedFundingA = spliceOutputsA.map(_.amount).sum + 1_000.sat
      val subtractedFundingB = spliceOutputsB.map(_.amount).sum + 500.sat
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(commitmentA1, commitmentB1)
      assert(sharedInputA.commitmentFormat == PhoenixSimpleTaprootChannelCommitmentFormat)
      assert(sharedInputB.commitmentFormat == PhoenixSimpleTaprootChannelCommitmentFormat)
      val spliceFixtureParams = fixtureParams.createSpliceFixtureParams(fundingTxIndex = 1, fundingAmountA = -subtractedFundingA, fundingAmountB = -subtractedFundingB, aliceParams.targetFeerate, aliceParams.dustLimit, aliceParams.lockTime, sharedInputA = sharedInputA, sharedInputB = sharedInputB, spliceOutputsA = spliceOutputsA, spliceOutputsB = spliceOutputsB, requireConfirmedInputs = aliceParams.requireConfirmedInputs)

      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(spliceFixtureParams.fundingParamsA, commitmentA1, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(spliceFixtureParams.fundingParamsB, commitmentB1, walletB)
      val fwdSplice = TypeCheckedForwarder(aliceSplice, bobSplice, alice2bob, bob2alice)

      aliceSplice ! Start(alice2bob.ref)
      bobSplice ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      val sharedInput = fwdSplice.forwardAlice2Bob[TxAddInput]
      assert(sharedInput.previousTx_opt.isEmpty)
      assert(sharedInput.sharedInput_opt.contains(commitmentA1.fundingInput))
      // Alice <-- tx_add_output --- Bob
      val outputB = fwdSplice.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      val outputA1 = fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      val outputA2 = fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdSplice.forwardAlice2Bob[TxComplete]

      val successA2 = alice2bob.expectMsgType[Succeeded]
      assert(successA2.signingSession.fundingTx.localSigs.previousFundingTxSig_opt.isEmpty)
      assert(successA2.signingSession.fundingTx.localSigs.previousFundingTxPartialSig_opt.nonEmpty)
      val successB2 = bob2alice.expectMsgType[Succeeded]
      assert(successB2.signingSession.fundingTx.localSigs.previousFundingTxSig_opt.isEmpty)
      assert(successB2.signingSession.fundingTx.localSigs.previousFundingTxPartialSig_opt.nonEmpty)
      val (spliceTxA, commitmentA2, spliceTxB, commitmentB2) = fixtureParams.exchangeSigsBobFirst(spliceFixtureParams.fundingParamsB, successA2, successB2)
      assert(spliceTxA.tx.localFees == 1_000_000.msat)
      assert(spliceTxB.tx.localFees == 500_000.msat)
      assert(spliceTxB.tx.localFees == spliceTxA.tx.remoteFees)
      spliceOutputsA.foreach(txOut => assert(Set(outputA1, outputA2).map(o => TxOut(o.amount, o.pubkeyScript)).contains(txOut)))
      spliceOutputsB.foreach(txOut => assert(Set(outputB).map(o => TxOut(o.amount, o.pubkeyScript)).contains(txOut)))
      assert(Set(outputA1, outputA2).exists(o => o.amount == fundingA1 + fundingB1 - subtractedFundingA - subtractedFundingB && o.pubkeyScript == spliceFixtureParams.fundingPubkeyScript))

      assert(commitmentA2.localCommit.spec.toLocal == (fundingA1 - subtractedFundingA).toMilliSatoshi)
      assert(commitmentA2.localCommit.spec.toRemote == (fundingB1 - subtractedFundingB).toMilliSatoshi)
      assert(commitmentB2.localCommit.spec.toLocal == (fundingB1 - subtractedFundingB).toMilliSatoshi)
      assert(commitmentB2.localCommit.spec.toRemote == (fundingA1 - subtractedFundingA).toMilliSatoshi)

      // The resulting transaction is valid.
      walletA.publishTransaction(spliceTxA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA.txId)
    }
  }

  test("initiator and non-initiator splice-out (multiple)") {
    val fundingA1 = 150_000 sat
    val utxosA = Seq(200_000 sat)
    val fundingB1 = 100_000 sat
    val utxosB = Seq(150_000 sat)
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), fundingA1, utxosA, fundingB1, utxosB, FeeratePerKw(1000 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = true, forRemote = true)) { f =>
      import f._

      val probe = TestProbe()
      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA1 = alice2bob.expectMsgType[Succeeded]
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val (txA1, commitmentA1, _, commitmentB1) = fixtureParams.exchangeSigsBobFirst(bobParams, successA1, successB1)
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.txId)

      // Alice and Bob decide to splice funds out of the channel, and deduce on-chain fees from their new channel contribution.
      val spliceOutputsA = List(20_000 sat, 15_000 sat, 15_000 sat).map(amount => TxOut(amount, Script.pay2wpkh(randomKey().publicKey)))
      val spliceOutputsB = List(25_000 sat, 15_000 sat).map(amount => TxOut(amount, Script.pay2wpkh(randomKey().publicKey)))
      val subtractedFundingA = spliceOutputsA.map(_.amount).sum + 1_000.sat
      val subtractedFundingB = spliceOutputsB.map(_.amount).sum + 500.sat
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(commitmentA1, commitmentB1)
      val spliceFixtureParams = fixtureParams.createSpliceFixtureParams(fundingTxIndex = 1, fundingAmountA = -subtractedFundingA, fundingAmountB = -subtractedFundingB, aliceParams.targetFeerate, aliceParams.dustLimit, aliceParams.lockTime, sharedInputA = sharedInputA, sharedInputB = sharedInputB, spliceOutputsA = spliceOutputsA, spliceOutputsB = spliceOutputsB, requireConfirmedInputs = aliceParams.requireConfirmedInputs)
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(spliceFixtureParams.fundingParamsA, commitmentA1, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(spliceFixtureParams.fundingParamsB, commitmentB1, walletB)
      val fwdSplice = TypeCheckedForwarder(aliceSplice, bobSplice, alice2bob, bob2alice)

      aliceSplice ! Start(alice2bob.ref)
      bobSplice ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      val sharedInput = fwdSplice.forwardAlice2Bob[TxAddInput]
      assert(sharedInput.previousTx_opt.isEmpty)
      assert(sharedInput.sharedInput_opt.contains(commitmentA1.fundingInput))
      // Alice <-- tx_add_output --- Bob
      val outputB1 = fwdSplice.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      val outputA1 = fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      val outputB2 = fwdSplice.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      val outputA2 = fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      val outputA3 = fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      val outputA4 = fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdSplice.forwardAlice2Bob[TxComplete]

      val successA2 = alice2bob.expectMsgType[Succeeded]
      assert(successA2.signingSession.fundingTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      val successB2 = bob2alice.expectMsgType[Succeeded]
      assert(successB2.signingSession.fundingTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      val (spliceTxA, commitmentA2, spliceTxB, commitmentB2) = fixtureParams.exchangeSigsBobFirst(spliceFixtureParams.fundingParamsB, successA2, successB2)
      assert(spliceTxA.tx.localFees == 1_000_000.msat)
      assert(spliceTxB.tx.localFees == 500_000.msat)
      assert(spliceTxB.tx.localFees == spliceTxA.tx.remoteFees)
      spliceOutputsA.foreach(txOut => assert(Set(outputA1, outputA2, outputA3, outputA4).map(o => TxOut(o.amount, o.pubkeyScript)).contains(txOut)))
      spliceOutputsB.foreach(txOut => assert(Set(outputB1, outputB2).map(o => TxOut(o.amount, o.pubkeyScript)).contains(txOut)))
      assert(Set(outputA1, outputA2, outputA3, outputA4).exists(o => o.amount == fundingA1 + fundingB1 - subtractedFundingA - subtractedFundingB && o.pubkeyScript == spliceFixtureParams.fundingPubkeyScript))

      assert(commitmentA2.localCommit.spec.toLocal == (fundingA1 - subtractedFundingA).toMilliSatoshi)
      assert(commitmentA2.localCommit.spec.toRemote == (fundingB1 - subtractedFundingB).toMilliSatoshi)
      assert(commitmentB2.localCommit.spec.toLocal == (fundingB1 - subtractedFundingB).toMilliSatoshi)
      assert(commitmentB2.localCommit.spec.toRemote == (fundingA1 - subtractedFundingA).toMilliSatoshi)

      // The resulting transaction is valid.
      walletA.publishTransaction(spliceTxA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA.txId)
    }
  }

  test("initiator and non-initiator combine splice-in and splice-out") {
    val targetFeerate = FeeratePerKw(1000 sat)
    val fundingA1 = 150_000 sat
    val utxosA = Seq(480_000 sat, 130_000 sat)
    val fundingB1 = 100_000 sat
    val utxosB = Seq(340_000 sat, 70_000 sat)
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), fundingA1, utxosA, fundingB1, utxosB, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = true, forRemote = true)) { f =>
      import f._

      val probe = TestProbe()
      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA1 = alice2bob.expectMsgType[Succeeded]
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val (txA1, commitmentA1, _, commitmentB1) = fixtureParams.exchangeSigsBobFirst(bobParams, successA1, successB1)
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.txId)

      // Alice and Bob decide to splice funds out of the channel while also splicing funds in, resulting in an increase
      // of their channel balance and the creation of splice outputs.
      val additionalFundingA = 25_000.sat
      val additionalFundingB = 15_000.sat
      val spliceOutputsA = List(TxOut(30_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val spliceOutputsB = List(TxOut(10_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(commitmentA1, commitmentB1)
      val spliceFixtureParams = fixtureParams.createSpliceFixtureParams(fundingTxIndex = 1, fundingAmountA = additionalFundingA, fundingAmountB = additionalFundingB, aliceParams.targetFeerate, aliceParams.dustLimit, aliceParams.lockTime, sharedInputA = sharedInputA, sharedInputB = sharedInputB, spliceOutputsA = spliceOutputsA, spliceOutputsB = spliceOutputsB, requireConfirmedInputs = aliceParams.requireConfirmedInputs)
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(spliceFixtureParams.fundingParamsA, commitmentA1, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(spliceFixtureParams.fundingParamsB, commitmentB1, walletB)
      val fwdSplice = TypeCheckedForwarder(aliceSplice, bobSplice, alice2bob, bob2alice)

      aliceSplice ! Start(alice2bob.ref)
      bobSplice ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwdSplice.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwdSplice.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_input --> Bob
      fwdSplice.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_output --- Bob
      val outputB1 = fwdSplice.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      val outputA1 = fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      val outputB2 = fwdSplice.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      val outputA2 = fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      val outputA3 = fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdSplice.forwardAlice2Bob[TxComplete]

      val successA2 = alice2bob.expectMsgType[Succeeded]
      assert(successA2.signingSession.fundingTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      val successB2 = bob2alice.expectMsgType[Succeeded]
      assert(successB2.signingSession.fundingTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      val (spliceTxA, commitmentA2, _, commitmentB2) = fixtureParams.exchangeSigsBobFirst(spliceFixtureParams.fundingParamsB, successA2, successB2)
      spliceOutputsA.foreach(txOut => assert(Set(outputA1, outputA2, outputA3).map(o => TxOut(o.amount, o.pubkeyScript)).contains(txOut)))
      spliceOutputsB.foreach(txOut => assert(Set(outputB1, outputB2).map(o => TxOut(o.amount, o.pubkeyScript)).contains(txOut)))
      assert(Set(outputA1, outputA2, outputA3).exists(o => o.amount == fundingA1 + fundingB1 + additionalFundingA + additionalFundingB && o.pubkeyScript == spliceFixtureParams.fundingPubkeyScript))

      assert(commitmentA2.localCommit.spec.toLocal == (fundingA1 + additionalFundingA).toMilliSatoshi)
      assert(commitmentA2.localCommit.spec.toRemote == (fundingB1 + additionalFundingB).toMilliSatoshi)
      assert(commitmentB2.localCommit.spec.toLocal == (fundingB1 + additionalFundingB).toMilliSatoshi)
      assert(commitmentB2.localCommit.spec.toRemote == (fundingA1 + additionalFundingA).toMilliSatoshi)

      // The resulting transaction is valid and has the right feerate.
      walletA.publishTransaction(spliceTxA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA.txId)
      walletA.getMempoolTx(spliceTxA.txId).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == spliceTxA.tx.fees)
      assert(targetFeerate <= spliceTxA.feerate && spliceTxA.feerate <= targetFeerate * 1.25, s"unexpected feerate (target=$targetFeerate actual=${spliceTxA.feerate})")
    }
  }

  test("initiator upgrades to taproot while splicing-in") {
    val targetFeerate = FeeratePerKw(2000 sat)
    val fundingA1 = 150_000 sat
    val utxosA = Seq(480_000 sat, 130_000 sat)
    val fundingB1 = 0 sat
    val utxosB = Seq(70_000 sat)
    withFixture(ChannelTypes.AnchorOutputs(), fundingA1, utxosA, fundingB1, utxosB, targetFeerate, 750 sat, 0, RequireConfirmedInputs(forLocal = true, forRemote = true)) { f =>
      import f._

      val probe = TestProbe()
      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA1 = alice2bob.expectMsgType[Succeeded]
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val (txA1, commitmentA1, _, commitmentB1) = fixtureParams.exchangeSigsBobFirst(bobParams, successA1, successB1)
      assert(commitmentA1.commitmentFormat == UnsafeLegacyAnchorOutputsCommitmentFormat)
      assert(commitmentB1.commitmentFormat == UnsafeLegacyAnchorOutputsCommitmentFormat)
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.txId)

      // Alice decides to splice funds in the channel and upgrade to taproot.
      // Bob uses this opportunity to also splice some funds in the channel.
      val additionalFundingA2 = 80_000.sat
      val additionalFundingB2 = 55_000.sat
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(commitmentA1, commitmentB1)
      assert(sharedInputA.commitmentFormat == UnsafeLegacyAnchorOutputsCommitmentFormat)
      assert(sharedInputB.commitmentFormat == UnsafeLegacyAnchorOutputsCommitmentFormat)
      val spliceFixtureParams = fixtureParams.createSpliceFixtureParams(fundingTxIndex = 1, fundingAmountA = additionalFundingA2, fundingAmountB = additionalFundingB2, aliceParams.targetFeerate, aliceParams.dustLimit, aliceParams.lockTime, sharedInputA = sharedInputA, sharedInputB = sharedInputB, nextCommitmentFormat_opt = Some(PhoenixSimpleTaprootChannelCommitmentFormat), requireConfirmedInputs = aliceParams.requireConfirmedInputs)
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(spliceFixtureParams.fundingParamsA, commitmentA1, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(spliceFixtureParams.fundingParamsB, commitmentB1, walletB)
      val fwdSplice = TypeCheckedForwarder(aliceSplice, bobSplice, alice2bob, bob2alice)

      aliceSplice ! Start(alice2bob.ref)
      bobSplice ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwdSplice.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwdSplice.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_input --> Bob
      fwdSplice.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_output --- Bob
      fwdSplice.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      val txCompleteB = fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      val txCompleteA = fwdSplice.forwardAlice2Bob[TxComplete]
      Seq(txCompleteA, txCompleteB).foreach(txComplete => {
        assert(txComplete.commitNonces_opt.nonEmpty)
        assert(txComplete.fundingNonce_opt.isEmpty) // the previous commitment didn't use taproot
        assert(txComplete.commitNonces_opt.map(n => Seq(n.commitNonce, n.nextCommitNonce)).get.size == 2)
      })

      val successA2 = alice2bob.expectMsgType[Succeeded]
      assert(successA2.signingSession.fundingTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      assert(successA2.signingSession.fundingTx.localSigs.previousFundingTxPartialSig_opt.isEmpty)
      assert(successA2.commitSig.sigOrPartialSig.isInstanceOf[PartialSignatureWithNonce])
      val successB2 = bob2alice.expectMsgType[Succeeded]
      assert(successB2.signingSession.fundingTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      assert(successB2.signingSession.fundingTx.localSigs.previousFundingTxPartialSig_opt.isEmpty)
      assert(successB2.commitSig.sigOrPartialSig.isInstanceOf[PartialSignatureWithNonce])
      val (spliceTxA, commitmentA2, spliceTxB, commitmentB2) = fixtureParams.exchangeSigsBobFirst(spliceFixtureParams.fundingParamsB, successA2, successB2)
      assert(successA2.nextRemoteCommitNonce_opt.contains((spliceTxA.txId, txCompleteB.commitNonces_opt.get.nextCommitNonce)))
      assert(successB2.nextRemoteCommitNonce_opt.contains((spliceTxB.txId, txCompleteA.commitNonces_opt.get.nextCommitNonce)))
      assert(spliceTxA.tx.localAmountIn > spliceTxA.tx.remoteAmountIn)
      assert(spliceTxA.signedTx.txIn.exists(_.outPoint == commitmentA1.fundingInput))
      assert(0.msat < spliceTxA.tx.localFees)
      assert(0.msat < spliceTxA.tx.remoteFees)
      assert(spliceTxB.tx.localFees == spliceTxA.tx.remoteFees)
      assert(spliceTxA.tx.sharedOutput.amount == fundingA1 + fundingB1 + additionalFundingA2 + additionalFundingB2)

      assert(commitmentA2.localCommit.spec.toLocal == (fundingA1 + additionalFundingA2).toMilliSatoshi)
      assert(commitmentA2.localCommit.spec.toRemote == (fundingB1 + additionalFundingB2).toMilliSatoshi)
      assert(commitmentB2.localCommit.spec.toLocal == (fundingB1 + additionalFundingB2).toMilliSatoshi)
      assert(commitmentB2.localCommit.spec.toRemote == (fundingA1 + additionalFundingA2).toMilliSatoshi)

      // The resulting transaction is valid and has the right feerate.
      walletA.publishTransaction(spliceTxA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA.txId)
      walletA.getMempoolTx(spliceTxA.txId).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == spliceTxA.tx.fees)
      assert(targetFeerate <= spliceTxA.feerate && spliceTxA.feerate <= targetFeerate * 1.25, s"unexpected feerate (target=$targetFeerate actual=${spliceTxA.feerate})")
    }
  }

  test("remove input/output") {
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, Seq(150_000 sat), 0 sat, Nil, FeeratePerKw(2500 sat), 330 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // In this flow we introduce dummy inputs/outputs from Bob to Alice that are then removed.
      // Alice --- tx_add_input --> Bob
      val inputA = fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      bob2alice.expectMsgType[SendMessage] // we override Bob's tx_complete
      alice ! ReceiveMessage(TxAddInput(bobParams.channelId, UInt64(1), Some(Transaction(2, Nil, Seq(TxOut(250_000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)), 0, 0))
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      bob2alice.expectMsgType[SendMessage] // we override Bob's tx_complete
      alice ! ReceiveMessage(TxAddOutput(bobParams.channelId, UInt64(3), 250_000 sat, Script.write(Script.pay2wpkh(randomKey().publicKey))))
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_remove_input --- Bob
      bob2alice.expectMsgType[SendMessage] // we override Bob's tx_complete
      alice ! ReceiveMessage(TxRemoveInput(bobParams.channelId, UInt64(1)))
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]
      // Alice <-- tx_remove_output --- Bob
      alice ! ReceiveMessage(TxRemoveOutput(bobParams.channelId, UInt64(3)))
      // Alice --- tx_complete --> Bob
      alice2bob.expectMsgType[SendMessage]
      // Alice <-- tx_complete --- Bob
      alice ! ReceiveMessage(TxComplete(bobParams.channelId))

      val successA = alice2bob.expectMsgType[Succeeded]
      val successB = bob2alice.expectMsgType[Succeeded]
      val (txA, _, txB, _) = fixtureParams.exchangeSigsBobFirst(bobParams, successA, successB)
      // The resulting transaction doesn't contain Bob's removed inputs and outputs.
      assert(txA.txId == txB.txId)
      assert(txA.signedTx.lockTime == aliceParams.lockTime)
      assert(txA.signedTx.txIn.map(_.outPoint) == Seq(toOutPoint(inputA)))
      assert(txA.signedTx.txOut.length == 2)
      assert(txA.tx.remoteAmountIn == 0.msat)
    }
  }

  test("not enough funds (unconfirmed utxos not allowed)") {
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, Seq(250_000 sat), 0 sat, Nil, FeeratePerKw(2500 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = true, forRemote = true)) { f =>
      import f._

      // Alice's inputs are all unconfirmed.
      val probe = TestProbe()
      val tx = sendToAddress(getNewAddress(probe, rpcClientA), 75_000 sat, probe, rpcClientA)
      walletA.listUnspent().pipeTo(probe.ref)
      val utxos = probe.expectMsgType[Seq[Utxo]]
      assert(utxos.length == 2)
      utxos.foreach(utxo => assert(utxo.txid == tx.txid))
      utxos.foreach(utxo => assert(utxo.confirmations == 0))

      // Alice doesn't have enough to fund the channel since Bob requires confirmed inputs.
      alice ! Start(alice2bob.ref)
      assert(alice2bob.expectMsgType[LocalFailure].cause == ChannelFundingError(aliceParams.channelId))
      // Alice's utxos shouldn't be locked after the failed funding attempt.
      awaitAssert({
        walletA.listLockedOutpoints().pipeTo(probe.ref)
        assert(probe.expectMsgType[Set[OutPoint]].isEmpty)
      }, max = 10 seconds, interval = 100 millis)
    }
  }

  test("not enough funds (unusable utxos)") {
    val fundingA = 140_000 sat
    val utxosA = Seq(75_000 sat, 60_000 sat)
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), fundingA, utxosA, 0 sat, Nil, FeeratePerKw(5000 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._
      import fr.acinq.bitcoin.scalacompat.KotlinUtils._

      // Add some unusable utxos to Alice's wallet.
      val probe = TestProbe()
      val legacyTxId = {
        // Dual funding disallows non-segwit inputs.
        val legacyAddress = getNewAddress(probe, rpcClientA, Some("legacy"))
        sendToAddress(legacyAddress, 100_000 sat).txid
      }
      val bigTxId = {
        // Dual funding cannot use transactions that exceed 65k bytes.
        walletA.getP2wpkhPubkey().pipeTo(probe.ref)
        val publicKey = probe.expectMsgType[PublicKey]
        val tx = Transaction(2, Nil, TxOut(100_000 sat, Script.pay2wpkh(publicKey)) +: (1 to 2500).map(_ => TxOut(5000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
        val minerWallet = makeBitcoinCoreClient()
        minerWallet.fundTransaction(tx, FeeratePerKw(500 sat)).pipeTo(probe.ref)
        val unsignedTx = probe.expectMsgType[FundTransactionResponse].tx
        minerWallet.signPsbt(new Psbt(unsignedTx), unsignedTx.txIn.indices, Nil).pipeTo(probe.ref)
        val Right(signedTx) = probe.expectMsgType[ProcessPsbtResponse].finalTx_opt
        assert(Transaction.write(signedTx).length >= 65_000)
        minerWallet.publishTransaction(signedTx).pipeTo(probe.ref)
        probe.expectMsgType[TxId]
      }
      generateBlocks(1)

      // We verify that all utxos are correctly included in our wallet.
      walletA.listUnspent().pipeTo(probe.ref)
      val utxos = probe.expectMsgType[Seq[Utxo]]
      assert(utxos.length == 4)
      assert(utxos.exists(_.txid == bigTxId))
      assert(utxos.exists(_.txid == legacyTxId))

      // We can't use some of our utxos, so we don't have enough to fund our channel.
      alice ! Start(alice2bob.ref)
      assert(alice2bob.expectMsgType[LocalFailure].cause == ChannelFundingError(aliceParams.channelId))
      // Utxos shouldn't be locked after a failure.
      awaitAssert({
        walletA.listLockedOutpoints().pipeTo(probe.ref)
        assert(probe.expectMsgType[Set[OutPoint]].isEmpty)
      }, max = 10 seconds, interval = 100 millis)
    }
  }

  test("skip unusable utxos") {
    val fundingA = 140_000 sat
    val utxosA = Seq(55_000 sat, 65_000 sat, 50_000 sat)
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), fundingA, utxosA, 0 sat, Nil, FeeratePerKw(5000 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      // Add some unusable utxos to Alice's wallet.
      val probe = TestProbe()
      val legacyTxIds = {
        // Dual funding disallows non-segwit inputs.
        val legacyAddress = getNewAddress(probe, rpcClientA, Some("legacy"))
        val tx1 = sendToAddress(legacyAddress, 100_000 sat).txid
        val tx2 = sendToAddress(legacyAddress, 120_000 sat).txid
        Seq(tx1, tx2)
      }
      generateBlocks(1)

      // We verify that all utxos are correctly included in our wallet.
      walletA.listUnspent().pipeTo(probe.ref)
      val utxos = probe.expectMsgType[Seq[Utxo]]
      assert(utxos.length == 5)
      legacyTxIds.foreach(txid => assert(utxos.exists(_.txid == txid)))

      // If we ignore the unusable utxos, we have enough to fund the channel.
      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA = alice2bob.expectMsgType[Succeeded]
      val successB = bob2alice.expectMsgType[Succeeded]
      val (txA, _, _, _) = fixtureParams.exchangeSigsBobFirst(bobParams, successA, successB)
      // Unusable utxos should be skipped.
      legacyTxIds.foreach(txid => assert(!txA.signedTx.txIn.exists(_.outPoint.txid == txid)))
      // Only used utxos should be locked.
      awaitAssert({
        walletA.listLockedOutpoints().pipeTo(probe.ref)
        val locks = probe.expectMsgType[Set[OutPoint]]
        assert(locks == txA.signedTx.txIn.map(_.outPoint).toSet)
      }, max = 10 seconds, interval = 100 millis)
    }
  }

  test("fund transaction with previous inputs (no new input)") {
    val targetFeerate = FeeratePerKw(7500 sat)
    val fundingA = 85_000 sat
    val utxosA = Seq(120_000 sat)
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), fundingA, utxosA, 0 sat, Nil, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      val inputA1 = fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA1 = alice2bob.expectMsgType[Succeeded]
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val (txA1, commitmentA1, txB1, commitmentB1) = fixtureParams.exchangeSigsBobFirst(bobParams, successA1, successB1)
      assert(targetFeerate * 0.9 <= txA1.feerate && txA1.feerate <= targetFeerate * 1.25)
      val probe = TestProbe()
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.txId)

      val rbfFeerate = targetFeerate * 1.5
      val aliceRbf = fixtureParams.spawnTxBuilderRbfAlice(aliceParams.copy(targetFeerate = rbfFeerate), commitmentA1, Seq(txA1), walletA)
      val bobRbf = fixtureParams.spawnTxBuilderRbfBob(bobParams.copy(targetFeerate = rbfFeerate), commitmentB1, Seq(txB1), walletB)
      val fwdRbf = TypeCheckedForwarder(aliceRbf, bobRbf, alice2bob, bob2alice)

      aliceRbf ! Start(alice2bob.ref)
      bobRbf ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      val inputA2 = fwdRbf.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdRbf.forwardAlice2Bob[TxComplete]

      val successA2 = alice2bob.expectMsgType[Succeeded]
      val successB2 = bob2alice.expectMsgType[Succeeded]
      val (txA2, _, _, _) = fixtureParams.exchangeSigsBobFirst(bobParams.copy(targetFeerate = rbfFeerate), successA2, successB2)
      assert(rbfFeerate * 0.9 <= txA2.feerate && txA2.feerate <= rbfFeerate * 1.25)
      assert(inputA1 == inputA2)
      assert(txA1.signedTx.txIn.map(_.outPoint) == txA2.signedTx.txIn.map(_.outPoint))
      assert(txA1.txId != txA2.txId)
      assert(txA1.tx.fees < txA2.tx.fees)
      walletA.publishTransaction(txA2.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA2.txId)
    }
  }

  test("fund transaction with previous inputs (with new inputs)") {
    val targetFeerate = FeeratePerKw(10_000 sat)
    val fundingA = 100_000 sat
    val utxosA = Seq(55_000 sat, 55_000 sat, 55_000 sat)
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), fundingA, utxosA, 0 sat, Nil, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      val inputA1 = fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      val inputA2 = fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA1 = alice2bob.expectMsgType[Succeeded]
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val (txA1, commitmentA1, txB1, commitmentB1) = fixtureParams.exchangeSigsBobFirst(bobParams, successA1, successB1)
      // Bitcoin Core didn't add a change output, which results in a bigger over-payment of the on-chain fees.
      assert(targetFeerate * 0.9 <= txA1.feerate && txA1.feerate <= targetFeerate * 1.5)
      val probe = TestProbe()
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.txId)

      val rbfFeerate = targetFeerate * 1.5
      val aliceRbf = fixtureParams.spawnTxBuilderRbfAlice(aliceParams.copy(targetFeerate = rbfFeerate), commitmentA1, Seq(txA1), walletA)
      val bobRbf = fixtureParams.spawnTxBuilderRbfBob(bobParams.copy(targetFeerate = rbfFeerate), commitmentB1, Seq(txB1), walletB)
      val fwdRbf = TypeCheckedForwarder(aliceRbf, bobRbf, alice2bob, bob2alice)

      aliceRbf ! Start(alice2bob.ref)
      bobRbf ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      val inputA3 = fwdRbf.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      val inputA4 = fwdRbf.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      val inputA5 = fwdRbf.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdRbf.forwardAlice2Bob[TxComplete]

      val successA2 = alice2bob.expectMsgType[Succeeded]
      val successB2 = bob2alice.expectMsgType[Succeeded]
      val (txA2, _, _, _) = fixtureParams.exchangeSigsBobFirst(bobParams.copy(targetFeerate = rbfFeerate), successA2, successB2)
      assert(rbfFeerate * 0.9 <= txA2.feerate && txA2.feerate <= rbfFeerate * 1.25)
      val previousInputs = Set(inputA1, inputA2).map(i => toOutPoint(i))
      val newInputs = Set(inputA3, inputA4, inputA5).map(i => toOutPoint(i))
      assert(previousInputs.subsetOf(newInputs))
      assert(txA1.txId != txA2.txId)
      assert(txA1.signedTx.txIn.length + 1 == txA2.signedTx.txIn.length)
      assert(txA1.tx.fees < txA2.tx.fees)
      walletA.publishTransaction(txA2.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA2.txId)
    }
  }

  test("rbf with previous contributions from the non-initiator") {
    val initialFeerate = FeeratePerKw(5_000 sat)
    val fundingA = 100_000 sat
    val utxosA = Seq(70_000 sat, 60_000 sat)
    val fundingB = 25_000 sat
    val utxosB = Seq(27_500 sat)
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), fundingA, utxosA, fundingB, utxosB, initialFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      val inputA1 = fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      val inputB = fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_input --> Bob
      val inputA2 = fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA1 = alice2bob.expectMsgType[Succeeded]
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val (txA1, commitmentA1, txB1, commitmentB1) = fixtureParams.exchangeSigsBobFirst(bobParams, successA1, successB1)
      assert(initialFeerate * 0.9 <= txA1.feerate && txA1.feerate <= initialFeerate * 1.25)
      val probe = TestProbe()
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.txId)

      // Bob didn't have enough funds to add a change output.
      // If we want to increase the feerate, Bob cannot contribute more than what he has already contributed.
      // However, it still makes sense for Bob to contribute whatever he's able to, the final feerate will simply be
      // slightly less than what Alice intended, but it's better than being stuck with a low feerate.
      val rbfFeerate = initialFeerate * 1.5
      val aliceRbf = fixtureParams.spawnTxBuilderRbfAlice(aliceParams.copy(targetFeerate = rbfFeerate), commitmentA1, Seq(txA1), walletA)
      val bobRbf = fixtureParams.spawnTxBuilderRbfBob(bobParams.copy(targetFeerate = rbfFeerate), commitmentB1, Seq(txB1), walletB)
      val fwdRbf = TypeCheckedForwarder(aliceRbf, bobRbf, alice2bob, bob2alice)

      aliceRbf ! Start(alice2bob.ref)
      bobRbf ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      val inputA1b = fwdRbf.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      val inputBb = fwdRbf.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_input --> Bob
      val inputA2b = fwdRbf.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdRbf.forwardAlice2Bob[TxComplete]

      val successA2 = alice2bob.expectMsgType[Succeeded]
      val successB2 = bob2alice.expectMsgType[Succeeded]
      val (txA2, _, _, _) = fixtureParams.exchangeSigsBobFirst(bobParams.copy(targetFeerate = rbfFeerate), successA2, successB2)
      assert(inputB == inputBb)
      assert(Set(inputA1, inputA2).map(i => toOutPoint(i)) == Set(inputA1b, inputA2b).map(i => toOutPoint(i)))
      assert(rbfFeerate * 0.75 <= txA2.feerate && txA2.feerate <= rbfFeerate * 1.25)
      assert(txA1.signedTx.txIn.map(_.outPoint).toSet == txA2.signedTx.txIn.map(_.outPoint).toSet)
      assert(txA2.signedTx.txOut.map(_.amount).sum < txA1.signedTx.txOut.map(_.amount).sum)
      assert(txA1.tx.fees < txA2.tx.fees)
      walletA.publishTransaction(txA2.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA2.txId)
    }
  }

  test("fund splice transaction with previous inputs (no new inputs)") {
    val targetFeerate = FeeratePerKw(2_000 sat)
    val fundingA1 = 150_000 sat
    val utxosA = Seq(480_000 sat, 75_000 sat)
    val fundingB1 = 100_000 sat
    val utxosB = Seq(325_000 sat, 60_000 sat)
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), fundingA1, utxosA, fundingB1, utxosB, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      val probe = TestProbe()
      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA1 = alice2bob.expectMsgType[Succeeded]
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val (txA1, commitmentA1, _, commitmentB1) = fixtureParams.exchangeSigsBobFirst(bobParams, successA1, successB1)
      assert(targetFeerate * 0.9 <= txA1.feerate && txA1.feerate <= targetFeerate * 1.25)
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.txId)

      // Alice and Bob splice some funds in and out.
      val additionalFundingA = 15_000.sat
      val additionalFundingB = 5_000.sat
      val spliceOutputsA = List(TxOut(20_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val spliceOutputsB = List(TxOut(10_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(commitmentA1, commitmentB1)
      val spliceFixtureParams = fixtureParams.createSpliceFixtureParams(fundingTxIndex = 1, fundingAmountA = additionalFundingA, fundingAmountB = additionalFundingB, aliceParams.targetFeerate, aliceParams.dustLimit, aliceParams.lockTime, sharedInputA = sharedInputA, sharedInputB = sharedInputB, spliceOutputsA = spliceOutputsA, spliceOutputsB = spliceOutputsB, requireConfirmedInputs = aliceParams.requireConfirmedInputs)
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(spliceFixtureParams.fundingParamsA, commitmentA1, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(spliceFixtureParams.fundingParamsB, commitmentB1, walletB)
      val fwdSplice = TypeCheckedForwarder(aliceSplice, bobSplice, alice2bob, bob2alice)

      aliceSplice ! Start(alice2bob.ref)
      bobSplice ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwdSplice.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwdSplice.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_input --> Bob
      fwdSplice.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_output --- Bob
      fwdSplice.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwdSplice.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdSplice.forwardAlice2Bob[TxComplete]

      val successA2 = alice2bob.expectMsgType[Succeeded]
      val successB2 = bob2alice.expectMsgType[Succeeded]
      val (spliceTxA1, commitmentA2, spliceTxB1, commitmentB2) = fixtureParams.exchangeSigsBobFirst(spliceFixtureParams.fundingParamsB, successA2, successB2)
      assert(targetFeerate * 0.9 <= spliceTxA1.feerate && spliceTxA1.feerate <= targetFeerate * 1.25)
      walletA.publishTransaction(spliceTxA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA1.txId)

      // Alice wants to increase the feerate of the splice transaction.
      val fundingParamsA2 = spliceFixtureParams.fundingParamsA.copy(targetFeerate = targetFeerate * 2)
      val fundingParamsB2 = spliceFixtureParams.fundingParamsB.copy(targetFeerate = targetFeerate * 2)
      val aliceRbf = fixtureParams.spawnTxBuilderSpliceRbfAlice(fundingParamsA2, parentCommitment = commitmentA1, commitmentA2.localFundingStatus.asInstanceOf[LocalFundingStatus.DualFundedUnconfirmedFundingTx], Seq(spliceTxA1), walletA)
      val bobRbf = fixtureParams.spawnTxBuilderSpliceRbfBob(fundingParamsB2, parentCommitment = commitmentB1, commitmentB2.localFundingStatus.asInstanceOf[LocalFundingStatus.DualFundedUnconfirmedFundingTx], Seq(spliceTxB1), walletB)
      val fwdRbf = TypeCheckedForwarder(aliceRbf, bobRbf, alice2bob, bob2alice)

      aliceRbf ! Start(alice2bob.ref)
      bobRbf ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwdRbf.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwdRbf.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_input --> Bob
      fwdRbf.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_output --- Bob
      fwdRbf.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwdRbf.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdRbf.forwardAlice2Bob[TxComplete]

      val successA3 = alice2bob.expectMsgType[Succeeded]
      val successB3 = bob2alice.expectMsgType[Succeeded]
      val (spliceTxA2, _, _, _) = fixtureParams.exchangeSigsBobFirst(fundingParamsB2, successA3, successB3)
      // The funding transaction isn't confirmed: the splice transaction CPFPs it to the latest feerate.
      val packageFeerate = Transactions.fee2rate(txA1.tx.fees + spliceTxA2.tx.fees, txA1.signedTx.weight() + spliceTxA2.signedTx.weight())
      assert(fundingParamsB2.targetFeerate * 0.9 <= packageFeerate && packageFeerate <= fundingParamsB2.targetFeerate * 1.25)
      assert(spliceTxA1.signedTx.txIn.map(_.outPoint).toSet == spliceTxA2.signedTx.txIn.map(_.outPoint).toSet)
      (spliceOutputsA ++ spliceOutputsB).foreach(txOut => assert(spliceTxA2.signedTx.txOut.contains(txOut)))
      assert(spliceTxA1.txId != spliceTxA2.txId)
      assert(spliceTxA1.tx.fees < spliceTxA2.tx.fees)
      walletA.publishTransaction(spliceTxA2.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA2.txId)
    }
  }

  test("fund splice transaction with previous inputs (with new inputs)") {
    val targetFeerate = FeeratePerKw(2_500 sat)
    val fundingA1 = 100_000 sat
    val utxosA = Seq(340_000 sat, 40_000 sat, 35_000 sat)
    val fundingB1 = 80_000 sat
    val utxosB = Seq(280_000 sat, 20_000 sat, 15_000 sat)
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), fundingA1, utxosA, fundingB1, utxosB, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      val probe = TestProbe()
      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA1 = alice2bob.expectMsgType[Succeeded]
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val (txA1, commitmentA1, _, commitmentB1) = fixtureParams.exchangeSigsBobFirst(bobParams, successA1, successB1)
      assert(targetFeerate * 0.9 <= txA1.feerate && txA1.feerate <= targetFeerate * 1.25)
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.txId)

      // Alice and Bob splice some funds in and out, which requires using an additional input for each of them.
      val additionalFundingA = 15_000.sat
      val additionalFundingB = 5_000.sat
      val spliceOutputsA = List(TxOut(20_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val spliceOutputsB = List(TxOut(10_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(commitmentA1, commitmentB1)
      val spliceFixtureParams = fixtureParams.createSpliceFixtureParams(fundingTxIndex = 1, fundingAmountA = additionalFundingA, fundingAmountB = additionalFundingB, aliceParams.targetFeerate, aliceParams.dustLimit, aliceParams.lockTime, sharedInputA = sharedInputA, sharedInputB = sharedInputB, spliceOutputsA = spliceOutputsA, spliceOutputsB = spliceOutputsB, requireConfirmedInputs = aliceParams.requireConfirmedInputs)
      val fundingParamsA1 = spliceFixtureParams.fundingParamsA
      val fundingParamsB1 = spliceFixtureParams.fundingParamsB
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, commitmentA1, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(fundingParamsB1, commitmentB1, walletB)
      val fwdSplice = TypeCheckedForwarder(aliceSplice, bobSplice, alice2bob, bob2alice)

      aliceSplice ! Start(alice2bob.ref)
      bobSplice ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwdSplice.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwdSplice.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_input --> Bob
      fwdSplice.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_output --- Bob
      fwdSplice.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwdSplice.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdSplice.forwardAlice2Bob[TxComplete]

      val successA2 = alice2bob.expectMsgType[Succeeded]
      val successB2 = bob2alice.expectMsgType[Succeeded]
      val (spliceTxA1, commitmentA2, spliceTxB1, commitmentB2) = fixtureParams.exchangeSigsBobFirst(fundingParamsB1, successA2, successB2)
      assert(targetFeerate * 0.9 <= spliceTxA1.feerate && spliceTxA1.feerate <= targetFeerate * 1.25)
      walletA.publishTransaction(spliceTxA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA1.txId)

      // Alice wants to make a large increase to the feerate of the splice transaction, which requires additional inputs.
      val fundingParamsA2 = fundingParamsA1.copy(targetFeerate = FeeratePerKw(5_000 sat))
      val fundingParamsB2 = fundingParamsB1.copy(targetFeerate = FeeratePerKw(5_000 sat))
      val aliceRbf = fixtureParams.spawnTxBuilderSpliceRbfAlice(fundingParamsA2, parentCommitment = commitmentA1, commitmentA2.localFundingStatus.asInstanceOf[LocalFundingStatus.DualFundedUnconfirmedFundingTx], Seq(spliceTxA1), walletA)
      val bobRbf = fixtureParams.spawnTxBuilderSpliceRbfBob(fundingParamsB2, parentCommitment = commitmentB1, commitmentB2.localFundingStatus.asInstanceOf[LocalFundingStatus.DualFundedUnconfirmedFundingTx], Seq(spliceTxB1), walletB)
      val fwdRbf = TypeCheckedForwarder(aliceRbf, bobRbf, alice2bob, bob2alice)

      aliceRbf ! Start(alice2bob.ref)
      bobRbf ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwdRbf.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwdRbf.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_input --> Bob
      fwdRbf.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwdRbf.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_input --> Bob
      fwdRbf.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_output --- Bob
      fwdRbf.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwdRbf.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdRbf.forwardAlice2Bob[TxComplete]

      val successA3 = alice2bob.expectMsgType[Succeeded]
      val successB3 = bob2alice.expectMsgType[Succeeded]
      val (spliceTxA2, _, _, _) = fixtureParams.exchangeSigsBobFirst(fundingParamsB2, successA3, successB3)
      // The funding transaction isn't confirmed: the splice transaction CPFPs it to the latest feerate.
      val packageFeerate = Transactions.fee2rate(txA1.tx.fees + spliceTxA2.tx.fees, txA1.signedTx.weight() + spliceTxA2.signedTx.weight())
      assert(fundingParamsB2.targetFeerate * 0.9 <= packageFeerate && packageFeerate <= fundingParamsB2.targetFeerate * 1.25)
      // Alice and Bob both added a new input to fund the feerate increase.
      assert(spliceTxA2.signedTx.txIn.length == spliceTxA1.signedTx.txIn.length + 2)
      assert(spliceTxA1.signedTx.txIn.map(_.outPoint).toSet.subsetOf(spliceTxA2.signedTx.txIn.map(_.outPoint).toSet))
      (spliceOutputsA ++ spliceOutputsB).foreach(txOut => assert(spliceTxA2.signedTx.txOut.contains(txOut)))
      assert(spliceTxA1.txId != spliceTxA2.txId)
      assert(spliceTxA1.tx.fees < spliceTxA2.tx.fees)
      walletA.publishTransaction(spliceTxA2.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA2.txId)
    }
  }

  test("fund splice transaction from non-initiator without change output") {
    val targetFeerate = FeeratePerKw(10_000 sat)
    val fundingA = 100_000 sat
    val utxosA = Seq(150_000 sat)
    val fundingB = 92_000 sat
    val utxosB = Seq(50_000 sat, 50_000 sat, 50_000 sat, 50_000 sat)
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), fundingA, utxosA, fundingB, utxosB, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      val probe = TestProbe()
      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA1 = alice2bob.expectMsgType[Succeeded]
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val (txA1, commitmentA1, _, commitmentB1) = fixtureParams.exchangeSigsBobFirst(bobParams, successA1, successB1)
      assert(targetFeerate * 0.9 <= txA1.feerate && txA1.feerate <= targetFeerate * 1.25)
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.txId)

      val eventListener = TestProbe()
      system.eventStream.subscribe(eventListener.ref, classOf[ChannelLiquidityPurchased])

      // Alice initiates a splice that is only funded by Bob, because she is purchasing liquidity.
      val purchase = LiquidityAds.Purchase.Standard(50_000 sat, LiquidityAds.Fees(1000 sat, 1500 sat), LiquidityAds.PaymentDetails.FromChannelBalance)
      // Alice pays fees for the common fields of the transaction, by decreasing her balance in the shared output.
      val spliceFeeA = {
        val dummyWitness = Scripts.witness2of2(Transactions.PlaceHolderSig, Transactions.PlaceHolderSig, randomKey().publicKey, randomKey().publicKey)
        val dummySpliceTx = Transaction(
          version = 2,
          txIn = Seq(TxIn(commitmentA1.fundingInput, ByteVector.empty, 0, dummyWitness)),
          txOut = Seq(commitmentA1.commitInput(fixtureParams.channelKeysA).txOut),
          lockTime = 0
        )
        Transactions.weight2fee(targetFeerate, dummySpliceTx.weight())
      }
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(commitmentA1, commitmentB1)
      val spliceFixtureParams = fixtureParams.createSpliceFixtureParams(fundingTxIndex = 1, fundingAmountA = -spliceFeeA, fundingAmountB = fundingB, targetFeerate, aliceParams.dustLimit, aliceParams.lockTime, sharedInputA = sharedInputA, sharedInputB = sharedInputB, spliceOutputsA = Nil, spliceOutputsB = Nil, requireConfirmedInputs = aliceParams.requireConfirmedInputs)
      val fundingParamsA1 = spliceFixtureParams.fundingParamsA
      val fundingParamsB1 = spliceFixtureParams.fundingParamsB
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, commitmentA1, walletA, liquidityPurchase_opt = Some(purchase))
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(fundingParamsB1, commitmentB1, walletB, liquidityPurchase_opt = Some(purchase))
      val fwdSplice = TypeCheckedForwarder(aliceSplice, bobSplice, alice2bob, bob2alice)

      aliceSplice ! Start(alice2bob.ref)
      bobSplice ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwdSplice.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwdSplice.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_input --- Bob
      fwdSplice.forwardBob2Alice[TxAddInput]
      // Alice --- tx_complete --> Bob
      fwdSplice.forwardAlice2Bob[TxComplete]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]

      val successA2 = alice2bob.expectMsgType[Succeeded]
      assert(successA2.signingSession.liquidityPurchase_opt.contains(purchase.basicInfo(isBuyer = true)))
      val successB2 = bob2alice.expectMsgType[Succeeded]
      assert(successB2.signingSession.liquidityPurchase_opt.contains(purchase.basicInfo(isBuyer = false)))
      val (spliceTxA1, commitmentA2, _, commitmentB2) = fixtureParams.exchangeSigsBobFirst(fundingParamsB1, successA2, successB2)
      assert(commitmentA2.localCommit.spec.toLocal == commitmentA1.localCommit.spec.toLocal - spliceFeeA - purchase.fees.total)
      assert(commitmentB2.localCommit.spec.toLocal == commitmentB1.localCommit.spec.toLocal + fundingB + purchase.fees.total)
      assert(targetFeerate * 0.9 <= spliceTxA1.feerate && spliceTxA1.feerate <= targetFeerate * 1.25)
      walletA.publishTransaction(spliceTxA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA1.txId)

      val event = eventListener.expectMsgType[ChannelLiquidityPurchased]
      assert(event.purchase.fees == purchase.fees)
      assert(event.purchase.fundingTxIndex == 1)
      assert(event.purchase.fundingTxId == spliceTxA1.txId)
    }
  }

  private def replacePrevTxWithPrevTxOut(input: TxAddInput): TxAddInput = {
    input.previousTx_opt match {
      case None => input
      case Some(tx) =>
        val txOut = tx.txOut(input.previousTxOutput.toInt)
        input.copy(previousTx_opt = None, tlvStream = TlvStream(TxAddInputTlv.PrevTxOut(tx.txid, txOut.amount, txOut.publicKeyScript)))
    }
  }

  test("fund splice transaction with previous inputs (different balance)") {
    val targetFeerate = FeeratePerKw(2_500 sat)
    val fundingA1 = 100_000 sat
    val utxosA = Seq(340_000 sat, 40_000 sat, 35_000 sat)
    val fundingB1 = 80_000 sat
    val utxosB = Seq(290_000 sat, 20_000 sat, 15_000 sat)
    withFixture(ChannelTypes.SimpleTaprootChannelsStaging(), fundingA1, utxosA, fundingB1, utxosB, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      val probe = TestProbe()
      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA1 = alice2bob.expectMsgType[Succeeded]
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val (txA1, commitmentA1, _, commitmentB1) = fixtureParams.exchangeSigsBobFirst(bobParams, successA1, successB1)
      assert(targetFeerate * 0.9 <= txA1.feerate && txA1.feerate <= targetFeerate * 1.25)
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.txId)

      // Alice splices some funds in, which requires using an additional input.
      val additionalFundingA1 = 25_000.sat
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(commitmentA1, commitmentB1)
      val spliceFixtureParams = fixtureParams.createSpliceFixtureParams(fundingTxIndex = 1, fundingAmountA = additionalFundingA1, fundingAmountB = 0 sat, aliceParams.targetFeerate, aliceParams.dustLimit, aliceParams.lockTime, sharedInputA = sharedInputA, sharedInputB = sharedInputB, requireConfirmedInputs = aliceParams.requireConfirmedInputs)
      val fundingParamsA1 = spliceFixtureParams.fundingParamsA
      val fundingParamsB1 = spliceFixtureParams.fundingParamsB
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, commitmentA1, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(fundingParamsB1, commitmentB1, walletB)
      val fwdSplice = TypeCheckedForwarder(aliceSplice, bobSplice, alice2bob, bob2alice)

      aliceSplice ! Start(alice2bob.ref)
      bobSplice ! Start(bob2alice.ref)

      // Since we're splicing a taproot channel, we can replace the entire previous transaction by only its txOut.
      // Alice --- tx_add_input --> Bob
      val input1 = alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddInput]
      bobSplice ! ReceiveMessage(replacePrevTxWithPrevTxOut(input1))
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      val input2 = alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddInput]
      bobSplice ! ReceiveMessage(replacePrevTxWithPrevTxOut(input2))
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdSplice.forwardAlice2Bob[TxComplete]

      val successA2 = alice2bob.expectMsgType[Succeeded]
      val successB2 = bob2alice.expectMsgType[Succeeded]
      val (spliceTxA1, commitmentA2, spliceTxB1, commitmentB2) = fixtureParams.exchangeSigsBobFirst(fundingParamsB1, successA2, successB2)
      assert(targetFeerate * 0.9 <= spliceTxA1.feerate && spliceTxA1.feerate <= targetFeerate * 1.25)
      walletA.publishTransaction(spliceTxA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA1.txId)

      // Alice wants to:
      //  - increase the feerate of the splice transaction
      //  - splice more additional funds
      // Before that, she sent htlcs to Bob which decreased her balance in all active commitments.
      val amountPaid = 25_000_400 msat
      val commitmentA1bis = commitmentA1
        .modify(_.localCommit.spec.toLocal).using(balance => balance - amountPaid)
        .modify(_.localCommit.spec.toRemote).using(balance => balance + amountPaid)
        .modify(_.remoteCommit.spec.toLocal).using(balance => balance + amountPaid)
        .modify(_.remoteCommit.spec.toRemote).using(balance => balance - amountPaid)
      val commitmentA2bis = commitmentA2
        .modify(_.localCommit.spec.toLocal).using(balance => balance - amountPaid)
        .modify(_.localCommit.spec.toRemote).using(balance => balance + amountPaid)
        .modify(_.remoteCommit.spec.toLocal).using(balance => balance + amountPaid)
        .modify(_.remoteCommit.spec.toRemote).using(balance => balance - amountPaid)
      val commitmentB1bis = commitmentB1
        .modify(_.localCommit.spec.toLocal).using(balance => balance + amountPaid)
        .modify(_.localCommit.spec.toRemote).using(balance => balance - amountPaid)
        .modify(_.remoteCommit.spec.toLocal).using(balance => balance - amountPaid)
        .modify(_.remoteCommit.spec.toRemote).using(balance => balance + amountPaid)
      val commitmentB2bis = commitmentB2
        .modify(_.localCommit.spec.toLocal).using(balance => balance + amountPaid)
        .modify(_.localCommit.spec.toRemote).using(balance => balance - amountPaid)
        .modify(_.remoteCommit.spec.toLocal).using(balance => balance - amountPaid)
        .modify(_.remoteCommit.spec.toRemote).using(balance => balance + amountPaid)
      val additionalFundingA2 = 50_000 sat
      val fundingParamsA2 = fundingParamsA1.copy(targetFeerate = FeeratePerKw(5_000 sat), localContribution = additionalFundingA2, remoteContribution = 0 sat)
      val fundingParamsB2 = fundingParamsB1.copy(targetFeerate = FeeratePerKw(5_000 sat), localContribution = 0 sat, remoteContribution = additionalFundingA2)
      val aliceRbf = fixtureParams.spawnTxBuilderSpliceRbfAlice(fundingParamsA2, parentCommitment = commitmentA1bis, commitmentA2bis.localFundingStatus.asInstanceOf[LocalFundingStatus.DualFundedUnconfirmedFundingTx], Seq(spliceTxA1), walletA)
      val bobRbf = fixtureParams.spawnTxBuilderSpliceRbfBob(fundingParamsB2, parentCommitment = commitmentB1bis, commitmentB2bis.localFundingStatus.asInstanceOf[LocalFundingStatus.DualFundedUnconfirmedFundingTx], Seq(spliceTxB1), walletB)
      val fwdRbf = TypeCheckedForwarder(aliceRbf, bobRbf, alice2bob, bob2alice)

      aliceRbf ! Start(alice2bob.ref)
      bobRbf ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwdRbf.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      fwdRbf.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      fwdRbf.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdRbf.forwardAlice2Bob[TxComplete]

      val successA3 = alice2bob.expectMsgType[Succeeded]
      val successB3 = bob2alice.expectMsgType[Succeeded]
      val (spliceTxA2, commitmentA3, _, commitmentB3) = fixtureParams.exchangeSigsBobFirst(fundingParamsB2, successA3, successB3)
      assert(commitmentA3.localCommit.spec.toLocal == commitmentA1bis.localCommit.spec.toLocal + additionalFundingA2)
      assert(commitmentA3.localCommit.spec.toRemote == commitmentA1bis.localCommit.spec.toRemote)
      assert(commitmentB3.localCommit.spec.toLocal == commitmentB1bis.localCommit.spec.toLocal)
      assert(commitmentB3.localCommit.spec.toRemote == commitmentB1bis.localCommit.spec.toRemote + additionalFundingA2)

      walletA.publishTransaction(spliceTxA2.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA2.txId)
      walletA.getMempoolTx(spliceTxA2.txId).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == spliceTxA2.tx.fees)
      // The funding transaction isn't confirmed: the splice transaction CPFPs it to the latest feerate.
      val packageFeerate = Transactions.fee2rate(txA1.tx.fees + spliceTxA2.tx.fees, txA1.signedTx.weight() + spliceTxA2.signedTx.weight())
      assert(fundingParamsB2.targetFeerate * 0.9 <= packageFeerate && packageFeerate <= fundingParamsB2.targetFeerate * 1.25)
      assert(spliceTxA1.signedTx.txIn.map(_.outPoint).toSet.subsetOf(spliceTxA2.signedTx.txIn.map(_.outPoint).toSet))
      assert(spliceTxA1.txId != spliceTxA2.txId)
      assert(spliceTxA1.tx.fees < spliceTxA2.tx.fees)
      walletA.publishTransaction(spliceTxA2.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA2.txId)
    }
  }

  test("not enough funds for rbf attempt") {
    val targetFeerate = FeeratePerKw(10_000 sat)
    val fundingA = 80_000 sat
    val utxosA = Seq(85_000 sat)
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), fundingA, utxosA, 0 sat, Nil, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA = alice2bob.expectMsgType[Succeeded]
      val successB = bob2alice.expectMsgType[Succeeded]
      val (txA, commitmentA, _, _) = fixtureParams.exchangeSigsBobFirst(bobParams, successA, successB)
      assert(targetFeerate * 0.9 <= txA.feerate && txA.feerate <= targetFeerate * 1.25)

      val aliceRbf = fixtureParams.spawnTxBuilderRbfAlice(aliceParams.copy(targetFeerate = FeeratePerKw(15_000 sat)), commitmentA, Seq(txA), walletA)
      aliceRbf ! Start(alice2bob.ref)
      assert(alice2bob.expectMsgType[LocalFailure].cause == ChannelFundingError(aliceParams.channelId))
    }
  }

  test("allow unconfirmed remote inputs") {
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 120_000 sat, Seq(150_000 sat), 50_000 sat, Seq(100_000 sat), FeeratePerKw(4000 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      // Bob's available utxo is unconfirmed.
      val probe = TestProbe()
      walletB.getP2wpkhPubkey().pipeTo(probe.ref)
      walletB.sendToPubkeyScript(Script.write(Script.pay2wpkh(probe.expectMsgType[PublicKey])), 75_000 sat, FeeratePerByte(1.sat).perKw).pipeTo(probe.ref)
      probe.expectMsgType[TxId]

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA = alice2bob.expectMsgType[Succeeded]
      val successB = bob2alice.expectMsgType[Succeeded]
      val (txA, _, _, _) = fixtureParams.exchangeSigsBobFirst(bobParams, successA, successB)
      walletA.publishTransaction(txA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA.txId)
    }
  }

  test("reject unconfirmed remote inputs") {
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 120_000 sat, Seq(150_000 sat), 50_000 sat, Seq(100_000 sat), FeeratePerKw(4000 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = true)) { f =>
      import f._

      // Bob's available utxo is unconfirmed.
      val probe = TestProbe()
      walletB.getReceiveAddress().pipeTo(probe.ref)
      walletB.sendToAddress(probe.expectMsgType[String], 75_000 sat, 1).pipeTo(probe.ref)
      probe.expectMsgType[TxId]

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]
      // Alice detects that Bob's inputs are unconfirmed and aborts.
      assert(alice2bob.expectMsgType[RemoteFailure].cause == UnconfirmedInteractiveTxInputs(aliceParams.channelId))
    }
  }

  test("funding amount drops below reserve") {
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 500_000 sat, Seq(600_000 sat), 400_000 sat, Seq(450_000 sat), FeeratePerKw(1000 sat), 330 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      val probe = TestProbe()
      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      fwd.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      fwd.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA = alice2bob.expectMsgType[Succeeded]
      val successB = bob2alice.expectMsgType[Succeeded]
      val (txA, commitmentA, _, commitmentB) = fixtureParams.exchangeSigsBobFirst(bobParams, successA, successB)
      walletA.publishTransaction(txA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA.txId)

      // Bob splices too much funds out, which makes him drop below the channel reserve.
      val subtractedFundingA = 100_000 sat
      val subtractedFundingB = 398_000 sat
      val spliceOutputsA = List(TxOut(99_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val spliceOutputsB = List(TxOut(397_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(commitmentA, commitmentB)
      val fundingParamsA1 = aliceParams.copy(localContribution = -subtractedFundingA, remoteContribution = -subtractedFundingB, sharedInput_opt = Some(sharedInputA), localOutputs = spliceOutputsA)
      val fundingParamsB1 = bobParams.copy(localContribution = -subtractedFundingB, remoteContribution = -subtractedFundingA, sharedInput_opt = Some(sharedInputB), localOutputs = spliceOutputsB)
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, commitmentA, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(fundingParamsB1, commitmentB, walletB)
      val fwdSplice = TypeCheckedForwarder(aliceSplice, bobSplice, alice2bob, bob2alice)

      aliceSplice ! Start(alice2bob.ref)
      bobSplice ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwdSplice.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_output --- Bob
      fwdSplice.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdSplice.forwardAlice2Bob[TxComplete]
      // Alice detects that Bob will drop below the channel reserve and fails.
      assert(alice2bob.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(bobParams.channelId))
    }
  }

  private def testTxSignaturesMissingSharedInputSigs(channelType: SupportedChannelType): Unit = {
    withFixture(channelType, 150_000 sat, Seq(200_000 sat), 0 sat, Nil, FeeratePerKw(1000 sat), 330 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      val probe = TestProbe()
      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA1 = alice2bob.expectMsgType[Succeeded]
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val (txA, commitmentA, _, commitmentB) = fixtureParams.exchangeSigsBobFirst(bobParams, successA1, successB1)
      walletA.publishTransaction(txA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA.txId)

      // Alice splices some funds out, which creates two outputs (a shared output and a splice output).
      val subtractedFundingA = 30_000 sat
      val spliceOutputsA = List(TxOut(25_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(commitmentA, commitmentB)
      val spliceFixtureParams = fixtureParams.createSpliceFixtureParams(fundingTxIndex = 1, fundingAmountA = -subtractedFundingA, fundingAmountB = 0 sat, aliceParams.targetFeerate, aliceParams.dustLimit, aliceParams.lockTime, sharedInputA = sharedInputA, sharedInputB = sharedInputB, spliceOutputsA = spliceOutputsA, spliceOutputsB = Nil, requireConfirmedInputs = aliceParams.requireConfirmedInputs)
      val fundingParamsA1 = spliceFixtureParams.fundingParamsA
      val fundingParamsB1 = spliceFixtureParams.fundingParamsB
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, commitmentA, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(fundingParamsB1, commitmentB, walletB)
      val fwdSplice = TypeCheckedForwarder(aliceSplice, bobSplice, alice2bob, bob2alice)

      aliceSplice ! Start(alice2bob.ref)
      bobSplice ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwdSplice.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdSplice.forwardAlice2Bob[TxComplete]
      val successA2 = alice2bob.expectMsgType[Succeeded]
      val successB2 = bob2alice.expectMsgType[Succeeded]
      // Alice <-- commit_sig --- Bob
      val Right(signingA3: InteractiveTxSigningSession.WaitingForSigs) = successA2.signingSession.receiveCommitSig(fixtureParams.channelParamsA, fixtureParams.channelKeysA, successB2.commitSig, fixtureParams.nodeParamsA.currentBlockHeight)(akka.event.NoLogging)
      // Alice <-- tx_signatures --- Bob
      val Left(error) = signingA3.receiveTxSigs(fixtureParams.channelKeysA, successB2.signingSession.fundingTx.localSigs.copy(tlvStream = TlvStream.empty), fixtureParams.nodeParamsA.currentBlockHeight)(akka.event.NoLogging)
      assert(error == InvalidFundingSignature(bobParams.channelId, Some(successA2.signingSession.fundingTx.txId)))
    }
  }

  test("invalid tx_signatures (missing shared input signature)") {
    testTxSignaturesMissingSharedInputSigs(ChannelTypes.AnchorOutputsZeroFeeHtlcTx())
  }

  test("invalid tx_signatures (missing shared input signature, taproot)") {
    testTxSignaturesMissingSharedInputSigs(ChannelTypes.SimpleTaprootChannelsStaging())
  }

  test("invalid commitment index") {
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 150_000 sat, Seq(200_000 sat), 0 sat, Nil, FeeratePerKw(1000 sat), 330 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      val probe = TestProbe()
      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA1 = alice2bob.expectMsgType[Succeeded]
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val (txA, commitmentA, _, commitmentB) = fixtureParams.exchangeSigsBobFirst(bobParams, successA1, successB1)
      walletA.publishTransaction(txA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA.txId)

      // Alice splices some funds out, but she doesn't have the same commitment index than Bob.
      val subtractedFundingA = 30_000 sat
      val spliceOutputsA = List(TxOut(25_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(commitmentA, commitmentB)
      val spliceFixtureParams = fixtureParams.createSpliceFixtureParams(fundingTxIndex = 1, fundingAmountA = -subtractedFundingA, fundingAmountB = 0 sat, aliceParams.targetFeerate, aliceParams.dustLimit, aliceParams.lockTime, sharedInputA = sharedInputA, sharedInputB = sharedInputB, spliceOutputsA = spliceOutputsA, spliceOutputsB = Nil, requireConfirmedInputs = aliceParams.requireConfirmedInputs)
      val fundingParamsA1 = spliceFixtureParams.fundingParamsA
      val fundingParamsB1 = spliceFixtureParams.fundingParamsB
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, commitmentA, walletA)
      val invalidCommitmentB = commitmentB
        .modify(_.localCommit.index).setTo(6)
        .modify(_.remoteCommit.index).setTo(6)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(fundingParamsB1, invalidCommitmentB, walletB)
      val fwdSplice = TypeCheckedForwarder(aliceSplice, bobSplice, alice2bob, bob2alice)

      aliceSplice ! Start(alice2bob.ref)
      bobSplice ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwdSplice.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdSplice.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdSplice.forwardAlice2Bob[TxComplete]

      val successA2 = alice2bob.expectMsgType[Succeeded]
      val successB2 = bob2alice.expectMsgType[Succeeded]
      // Alice <-- commit_sig --- Bob
      val Left(failureA) = successA2.signingSession.receiveCommitSig(fixtureParams.channelParamsA, fixtureParams.channelKeysA, successB2.commitSig, fixtureParams.nodeParamsA.currentBlockHeight)(akka.event.NoLogging)
      // Alice --- commit_sig --> Bob
      val Left(failureB) = successB2.signingSession.receiveCommitSig(fixtureParams.channelParamsB, fixtureParams.channelKeysB, successA2.commitSig, fixtureParams.nodeParamsB.currentBlockHeight)(akka.event.NoLogging)
      assert(failureA.isInstanceOf[InvalidCommitmentSignature])
      assert(failureB.isInstanceOf[InvalidCommitmentSignature])
      assert(failureA.asInstanceOf[InvalidCommitmentSignature].fundingTxId == failureB.asInstanceOf[InvalidCommitmentSignature].fundingTxId)
      assert(failureA.asInstanceOf[InvalidCommitmentSignature].unsignedCommitTx.txid != failureB.asInstanceOf[InvalidCommitmentSignature].unsignedCommitTx.txid)
    }
  }

  test("invalid funding contributions") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 75_000 sat, 25_000 sat, FeeratePerKw(5000 sat), 500 sat, 0)
    val previousCommitment = CommitmentsSpec.makeCommitments(25_000_000 msat, 75_000_000 msat).active.head
    val sharedInput = params.dummySharedInputB(100_000 sat)
    val testCases = Seq(
      params.fundingParamsB.copy(localContribution = -24_750 sat, remoteContribution = -74_751 sat, sharedInput_opt = Some(sharedInput)) -> FundingAmountTooLow(params.channelId, 499 sat, 500 sat),
      params.fundingParamsB.copy(localContribution = 50_000 sat, remoteContribution = -75_001 sat, sharedInput_opt = Some(sharedInput)) -> InvalidFundingBalances(params.channelId, 74_999 sat, 75_000_000 msat, -1000 msat),
      params.fundingParamsB.copy(localContribution = -25_001 sat, remoteContribution = 0 sat, sharedInput_opt = Some(sharedInput)) -> InvalidFundingBalances(params.channelId, 74_999 sat, -1000 msat, 75_000_000 msat),
    )
    testCases.foreach {
      case (fundingParams, expected) =>
        val bob = params.spawnTxBuilderSpliceBob(fundingParams, previousCommitment, wallet)
        bob ! Start(probe.ref)
        assert(probe.expectMsgType[LocalFailure].cause == expected)
    }
  }

  test("invalid funding contributions for liquidity purchase") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val purchase = LiquidityAds.Purchase.Standard(500_000 sat, LiquidityAds.Fees(5000 sat, 20_000 sat), LiquidityAds.PaymentDetails.FromChannelBalance)
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 24_000 sat, 500_000 sat, FeeratePerKw(5000 sat), 500 sat, 0)
    // Bob will reject Alice's proposal, since she doesn't have enough funds to pay the liquidity fees.
    val bob = params.spawnTxBuilderBob(wallet, params.fundingParamsB, Some(purchase))
    bob ! Start(probe.ref)
    assert(probe.expectMsgType[LocalFailure].cause == InvalidFundingBalances(params.channelId, 524_000 sat, 525_000_000 msat, -1_000_000 msat))
    // Bob rejects a splice proposed by Alice where she doesn't have enough funds to pay the liquidity fees.
    val previousCommitment = CommitmentsSpec.makeCommitments(450_000_000 msat, 50_000_000 msat).active.head
    val sharedInput = params.dummySharedInputB(500_000 sat)
    val spliceParams = params.fundingParamsB.copy(localContribution = 150_000 sat, remoteContribution = -30_000 sat, sharedInput_opt = Some(sharedInput))
    val bobSplice = params.spawnTxBuilderSpliceBob(spliceParams, previousCommitment, wallet, Some(purchase))
    bobSplice ! Start(probe.ref)
    assert(probe.expectMsgType[LocalFailure].cause == InvalidFundingBalances(params.channelId, 620_000 sat, 625_000_000 msat, -5_000_000 msat))
    // If Alice is using fee credit to pay the liquidity fees, the funding attempt is valid.
    val bobFeeCredit = params.spawnTxBuilderBob(wallet, params.fundingParamsB, Some(LiquidityAds.Purchase.WithFeeCredit(500_000 sat, LiquidityAds.Fees(5000 sat, 20_000 sat), 25_000_000 msat, LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc(Nil))))
    bobFeeCredit ! Start(probe.ref)
    probe.expectNoMessage(100 millis)
    // If we use a payment type where fees are paid outside of the interactive-tx session, the funding attempt is valid.
    val bobFutureHtlc = params.spawnTxBuilderBob(wallet, params.fundingParamsB, Some(purchase.copy(paymentDetails = LiquidityAds.PaymentDetails.FromFutureHtlc(Nil))))
    bobFutureHtlc ! Start(probe.ref)
    probe.expectNoMessage(100 millis)
    // Bob rejects a splice proposed by Alice where she has enough funds to pay the liquidity fees, but wants to pay
    // them outside of the interactive-tx session, which requires some trust.
    val bobFutureHtlcWithBalance = params.spawnTxBuilderSpliceBob(spliceParams, previousCommitment, wallet, Some(purchase.copy(fees = LiquidityAds.Fees(1000 sat, 4000 sat), paymentDetails = LiquidityAds.PaymentDetails.FromFutureHtlc(Nil))))
    bobFutureHtlcWithBalance ! Start(probe.ref)
    assert(probe.expectMsgType[LocalFailure].cause == InvalidLiquidityAdsPaymentType(params.channelId, LiquidityAds.PaymentType.FromFutureHtlc, Set(LiquidityAds.PaymentType.FromChannelBalance, LiquidityAds.PaymentType.FromChannelBalanceForFutureHtlc)))
  }

  test("invalid input") {
    val probe = TestProbe()
    // Create a transaction with a mix of segwit and non-segwit inputs.
    val previousOutputs = Seq(
      TxOut(2500 sat, Script.pay2wpkh(randomKey().publicKey)),
      TxOut(2500 sat, Script.pay2pkh(randomKey().publicKey)),
      TxOut(2500 sat, Script.pay2wpkh(randomKey().publicKey)),
    )
    val previousTx = Transaction(2, Nil, previousOutputs, 0)
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val testCases = Seq(
      TxAddInput(params.channelId, UInt64(0), Some(previousTx), 0, 0) -> InvalidSerialId(params.channelId, UInt64(0)),
      TxAddInput(params.channelId, UInt64(1), Some(previousTx), 0, 0) -> DuplicateSerialId(params.channelId, UInt64(1)),
      TxAddInput(params.channelId, UInt64(3), Some(previousTx), 0, 0) -> DuplicateInput(params.channelId, UInt64(3), previousTx.txid, 0),
      TxAddInput(params.channelId, UInt64(5), Some(previousTx), 3, 0) -> InputOutOfBounds(params.channelId, UInt64(5), previousTx.txid, 3),
      TxAddInput(params.channelId, UInt64(7), Some(previousTx), 1, 0) -> NonSegwitInput(params.channelId, UInt64(7), previousTx.txid, 1),
      TxAddInput(params.channelId, UInt64(9), Some(previousTx), 2, 0xfffffffeL) -> NonReplaceableInput(params.channelId, UInt64(9), previousTx.txid, 2, 0xfffffffeL),
      TxAddInput(params.channelId, UInt64(9), Some(previousTx), 2, 0xffffffffL) -> NonReplaceableInput(params.channelId, UInt64(9), previousTx.txid, 2, 0xffffffffL),
      // Replacing the previousTx field with previousTxOut is only allowed for splices on taproot channels.
      TxAddInput(params.channelId, UInt64(5), None, 0, 0, TlvStream(TxAddInputTlv.PrevTxOut(previousTx.txid, previousOutputs(0).amount, previousOutputs(0).publicKeyScript))) -> PreviousTxMissing(params.channelId, UInt64(5))
    )
    testCases.foreach {
      case (input, expected) =>
        val alice = params.spawnTxBuilderAlice(wallet)
        alice ! Start(probe.ref)
        // Alice --- tx_add_input --> Bob
        probe.expectMsgType[SendMessage]
        // Alice <-- tx_add_input --- Bob
        alice ! ReceiveMessage(TxAddInput(params.channelId, UInt64(1), Some(previousTx), 0, 0))
        // Alice --- tx_add_output --> Bob
        probe.expectMsgType[SendMessage]
        // Alice <-- tx_add_input --- Bob
        alice ! ReceiveMessage(input)
        assert(probe.expectMsgType[RemoteFailure].cause == expected)
    }
  }

  test("allow standard output types") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val testCases = Seq(
      TxAddOutput(params.channelId, UInt64(1), 25_000 sat, Script.write(Script.pay2pkh(randomKey().publicKey))),
      TxAddOutput(params.channelId, UInt64(1), 25_000 sat, Script.write(Script.pay2sh(OP_1 :: Nil))),
      TxAddOutput(params.channelId, UInt64(1), 25_000 sat, Script.write(Script.pay2wpkh(randomKey().publicKey))),
      TxAddOutput(params.channelId, UInt64(1), 25_000 sat, Script.write(Script.pay2wsh(OP_1 :: Nil))),
      TxAddOutput(params.channelId, UInt64(1), 25_000 sat, Script.write(Script.pay2tr(randomKey().xOnlyPublicKey()))),
    )
    testCases.foreach { output =>
      val alice = params.spawnTxBuilderAlice(wallet)
      alice ! Start(probe.ref)
      // Alice --- tx_add_input --> Bob
      probe.expectMsgType[SendMessage]
      // Alice <-- tx_add_output --- Bob
      alice ! ReceiveMessage(output)
      // Alice does not send a failure for non-segwit outputs.
      assert(probe.expectMsgType[SendMessage].msg.isInstanceOf[TxAddOutput])
    }
  }

  test("invalid output") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val testCases = Seq(
      TxAddOutput(params.channelId, UInt64(0), 25_000 sat, validScript) -> InvalidSerialId(params.channelId, UInt64(0)),
      TxAddOutput(params.channelId, UInt64(1), 45_000 sat, validScript) -> DuplicateSerialId(params.channelId, UInt64(1)),
      TxAddOutput(params.channelId, UInt64(3), 329 sat, validScript) -> OutputBelowDust(params.channelId, UInt64(3), 329 sat, 330 sat),
    )
    testCases.foreach {
      case (output, expected) =>
        val alice = params.spawnTxBuilderAlice(wallet)
        alice ! Start(probe.ref)
        // Alice --- tx_add_input --> Bob
        probe.expectMsgType[SendMessage]
        // Alice <-- tx_add_output --- Bob
        alice ! ReceiveMessage(TxAddOutput(params.channelId, UInt64(1), 50_000 sat, validScript))
        // Alice --- tx_add_output --> Bob
        probe.expectMsgType[SendMessage]
        // Alice <-- tx_add_output --- Bob
        alice ! ReceiveMessage(output)
        assert(probe.expectMsgType[RemoteFailure].cause == expected)
    }
  }

  test("remove unknown input/output") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val testCases = Seq(
      TxRemoveOutput(params.channelId, UInt64(53)) -> UnknownSerialId(params.channelId, UInt64(53)),
      TxRemoveInput(params.channelId, UInt64(57)) -> UnknownSerialId(params.channelId, UInt64(57)),
    )
    testCases.foreach {
      case (msg, expected) =>
        val alice = params.spawnTxBuilderAlice(wallet)
        alice ! Start(probe.ref)
        // Alice --- tx_add_input --> Bob
        probe.expectMsgType[SendMessage]
        // Alice <-- tx_remove_(in|out)put --- Bob
        alice ! ReceiveMessage(msg)
        assert(probe.expectMsgType[RemoteFailure].cause == expected)
    }
  }

  test("too many protocol rounds") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val alice = params.spawnTxBuilderAlice(wallet)
    alice ! Start(probe.ref)
    (1 until InteractiveTxBuilder.MAX_INPUTS_OUTPUTS_RECEIVED).foreach(i => {
      // Alice --- tx_message --> Bob
      probe.expectMsgType[SendMessage]
      alice ! ReceiveMessage(TxAddOutput(params.channelId, UInt64(2 * i + 1), 2500 sat, validScript))
    })
    // Alice --- tx_complete --> Bob
    probe.expectMsgType[SendMessage]
    alice ! ReceiveMessage(TxAddOutput(params.channelId, UInt64(15001), 2500 sat, validScript))
    assert(probe.expectMsgType[RemoteFailure].cause == TooManyInteractiveTxRounds(params.channelId))
  }

  test("too many inputs") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val alice = params.spawnTxBuilderAlice(wallet)
    alice ! Start(probe.ref)
    (1 to 252).foreach(i => {
      // Alice --- tx_message --> Bob
      probe.expectMsgType[SendMessage]
      alice ! ReceiveMessage(createInput(params.channelId, UInt64(2 * i + 1), 5000 sat))
    })
    // Alice --- tx_complete --> Bob
    probe.expectMsgType[SendMessage]
    alice ! ReceiveMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("too many outputs") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val alice = params.spawnTxBuilderAlice(wallet)
    alice ! Start(probe.ref)
    (1 to 252).foreach(i => {
      // Alice --- tx_message --> Bob
      probe.expectMsgType[SendMessage]
      alice ! ReceiveMessage(TxAddOutput(params.channelId, UInt64(2 * i + 1), 2500 sat, validScript))
    })
    // Alice --- tx_complete --> Bob
    probe.expectMsgType[SendMessage]
    alice ! ReceiveMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("missing funding output") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val bob = params.spawnTxBuilderBob(wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveMessage(createInput(params.channelId, UInt64(0), 150_000 sat))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(TxAddOutput(params.channelId, UInt64(2), 125_000 sat, validScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_complete --> Bob
    bob ! ReceiveMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("multiple funding outputs") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val bob = params.spawnTxBuilderBob(wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveMessage(createInput(params.channelId, UInt64(0), 150_000 sat))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(TxAddOutput(params.channelId, UInt64(2), 100_000 sat, params.fundingPubkeyScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(TxAddOutput(params.channelId, UInt64(4), 100_000 sat, params.fundingPubkeyScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_complete --> Bob
    bob ! ReceiveMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("missing shared input") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(1000 sat), 330 sat, 0)
    val commitment = CommitmentsSpec.makeCommitments(250_000_000 msat, 150_000_000 msat).active.head
    val fundingParamsB = params.fundingParamsB.copy(sharedInput_opt = Some(params.dummySharedInputB(commitment.capacity)))
    val bob = params.spawnTxBuilderSpliceBob(fundingParamsB, commitment, wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveMessage(createInput(params.channelId, UInt64(0), 150_000 sat))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(TxAddOutput(params.channelId, UInt64(2), fundingParamsB.fundingAmount, params.fundingPubkeyScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_complete --> Bob
    bob ! ReceiveMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("invalid funding amount") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val bob = params.spawnTxBuilderBob(wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveMessage(createInput(params.channelId, UInt64(0), 150_000 sat))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(TxAddOutput(params.channelId, UInt64(2), 100_001 sat, params.fundingPubkeyScript))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidSharedOutputAmount(params.channelId, UInt64(2), 100_001 sat, 100_000 sat))
  }

  test("missing previous tx") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val previousCommitment = CommitmentsSpec.makeCommitments(25_000_000 msat, 50_000_000 msat).active.head
    val fundingParams = params.fundingParamsB.copy(sharedInput_opt = Some(SharedFundingInput(previousCommitment.commitInput(params.channelKeysB), 0, randomKey().publicKey, previousCommitment.commitmentFormat)))
    val bob = params.spawnTxBuilderSpliceBob(fundingParams, previousCommitment, wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    // The input doesn't include the previous transaction but is not the shared input.
    val nonSharedInput = TxAddInput(params.channelId, UInt64(0), OutPoint(randomTxId(), 7), 0)
    bob ! ReceiveMessage(nonSharedInput)
    assert(probe.expectMsgType[RemoteFailure].cause == PreviousTxMissing(params.channelId, UInt64(0)))
  }

  test("previous txOut not allowed for non-taproot channels") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val previousCommitment = CommitmentsSpec.makeCommitments(25_000_000 msat, 50_000_000 msat).active.head
    val fundingParams = params.fundingParamsB.copy(sharedInput_opt = Some(SharedFundingInput(previousCommitment.commitInput(params.channelKeysB), 0, randomKey().publicKey, previousCommitment.commitmentFormat)))
    val bob = params.spawnTxBuilderSpliceBob(fundingParams, previousCommitment, wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    // The input only includes the previous txOut which is only allowed for taproot channels.
    bob ! ReceiveMessage(TxAddInput(params.channelId, UInt64(0), None, 0, 0, TlvStream(TxAddInputTlv.PrevTxOut(randomTxId(), 100_000 sat, Script.write(Script.pay2tr(randomKey().xOnlyPublicKey()))))))
    assert(probe.expectMsgType[RemoteFailure].cause == PreviousTxMissing(params.channelId, UInt64(0)))
  }

  test("invalid shared input") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val previousCommitment = CommitmentsSpec.makeCommitments(25_000_000 msat, 50_000_000 msat).active.head
    val fundingTx = Transaction(2, Nil, Seq(TxOut(50_000 sat, Script.pay2wpkh(randomKey().publicKey)), TxOut(20_000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
    val sharedInput = SharedFundingInput(InputInfo(OutPoint(fundingTx, 0), fundingTx.txOut.head), 0, randomKey().publicKey, previousCommitment.commitmentFormat)
    val bob = params.spawnTxBuilderSpliceBob(params.fundingParamsB.copy(sharedInput_opt = Some(sharedInput)), previousCommitment, wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    // The shared input isn't allowed to include a full previous transaction, it must use the dedicated tlv.
    val invalidSharedInput = TxAddInput(params.channelId, UInt64(0), Some(fundingTx), 0, 0)
    bob ! ReceiveMessage(invalidSharedInput)
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidSharedInput(params.channelId, UInt64(0)))
  }

  test("total input amount too low") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val bob = params.spawnTxBuilderBob(wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveMessage(createInput(params.channelId, UInt64(0), 150_000 sat))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(TxAddOutput(params.channelId, UInt64(2), 100_000 sat, params.fundingPubkeyScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(TxAddOutput(params.channelId, UInt64(4), 51_000 sat, validScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_complete --> Bob
    bob ! ReceiveMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("minimum fee not met") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val bob = params.spawnTxBuilderBob(wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveMessage(createInput(params.channelId, UInt64(0), 150_000 sat))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(TxAddOutput(params.channelId, UInt64(2), 100_000 sat, params.fundingPubkeyScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(TxAddOutput(params.channelId, UInt64(4), 49_999 sat, validScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_complete --> Bob
    bob ! ReceiveMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause.isInstanceOf[InvalidSpliceFeerate])
  }

  test("previous attempts not double-spent") {
    val targetFeerate = FeeratePerKw(7500 sat)
    val fundingA = 85_000 sat
    val utxosA = Seq(120_000 sat)
    withFixture(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), fundingA, utxosA, 0 sat, Nil, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwd.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]

      val successA1 = alice2bob.expectMsgType[Succeeded]
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val (txA1, commitmentA1, txB1, commitmentB1) = fixtureParams.exchangeSigsBobFirst(bobParams, successA1, successB1)
      assert(targetFeerate * 0.9 <= txA1.feerate && txA1.feerate <= targetFeerate * 1.25)
      val probe = TestProbe()
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.txId)

      // we modify remote's input in previous txs, it won't be double spent
      val fakeTxB2 = txB1.modify(_.tx.remoteInputs.at(0).outPoint.hash).setTo(TxHash(randomBytes32()))

      val aliceRbf = fixtureParams.spawnTxBuilderRbfAlice(aliceParams.copy(targetFeerate = FeeratePerKw(10_000 sat)), commitmentA1, Seq(txA1), walletA)
      val bobRbf = fixtureParams.spawnTxBuilderRbfBob(bobParams.copy(targetFeerate = FeeratePerKw(10_000 sat)), commitmentB1, Seq(txB1, fakeTxB2), walletB)
      val fwdRbf = TypeCheckedForwarder(aliceRbf, bobRbf, alice2bob, bob2alice)

      aliceRbf ! Start(alice2bob.ref)
      bobRbf ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwdRbf.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      fwdRbf.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      fwdRbf.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      fwdRbf.forwardAlice2Bob[TxComplete]
      // Alice <-- error --- Bob
      assert(bob2alice.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(fixtureParams.channelId))
    }
  }

  test("invalid commit_sig") {
    val (alice2bob, bob2alice) = (TestProbe(), TestProbe())
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 25_000 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val alice = params.spawnTxBuilderAlice(wallet)
    val bob = params.spawnTxBuilderBob(wallet)
    alice ! Start(alice2bob.ref)
    bob ! Start(bob2alice.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddInput])
    alice ! ReceiveMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxAddInput])
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    alice ! ReceiveMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    alice ! ReceiveMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete])
    // Alice --- tx_complete --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete])
    // Alice <-- commit_sig --- Bob
    val successA1 = alice2bob.expectMsgType[Succeeded]
    val invalidCommitSig = CommitSig(params.channelId, IndividualSignature(ByteVector64.Zeroes), Nil)
    val Left(error) = successA1.signingSession.receiveCommitSig(params.channelParamsA, params.channelKeysA, invalidCommitSig, params.nodeParamsA.currentBlockHeight)(akka.event.NoLogging)
    assert(error.isInstanceOf[InvalidCommitmentSignature])
  }

  test("invalid commit_sig (taproot)") {
    val (alice2bob, bob2alice) = (TestProbe(), TestProbe())
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.SimpleTaprootChannelsPhoenix(), 100_000 sat, 25_000 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val alice = params.spawnTxBuilderAlice(wallet)
    val bob = params.spawnTxBuilderBob(wallet)
    alice ! Start(alice2bob.ref)
    bob ! Start(bob2alice.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddInput])
    alice ! ReceiveMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxAddInput])
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    alice ! ReceiveMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    val txCompleteBob = bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete]
    assert(txCompleteBob.commitNonces_opt.nonEmpty)
    alice ! ReceiveMessage(txCompleteBob)
    // Alice --- tx_complete --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete])
    // Alice <-- commit_sig --- Bob
    val successA1 = alice2bob.expectMsgType[Succeeded]
    val invalidCommitSig = CommitSig(params.channelId, PartialSignatureWithNonce(randomBytes32(), txCompleteBob.commitNonces_opt.get.commitNonce), Nil, batchSize = 1)
    val Left(error) = successA1.signingSession.receiveCommitSig(params.channelParamsA, params.channelKeysA, invalidCommitSig, params.nodeParamsA.currentBlockHeight)(akka.event.NoLogging)
    assert(error.isInstanceOf[InvalidCommitmentSignature])
  }

  test("receive tx_signatures before commit_sig") {
    val (alice2bob, bob2alice) = (TestProbe(), TestProbe())
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val alice = params.spawnTxBuilderAlice(wallet)
    val bob = params.spawnTxBuilderBob(wallet)
    alice ! Start(alice2bob.ref)
    bob ! Start(bob2alice.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddInput])
    alice ! ReceiveMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete])
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    alice ! ReceiveMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete])
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    alice ! ReceiveMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete])
    // Alice --- tx_complete --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete])
    // Alice <-- tx_signatures --- Bob
    val signingA = alice2bob.expectMsgType[Succeeded].signingSession
    val signingB = bob2alice.expectMsgType[Succeeded].signingSession
    val Left(error) = signingA.receiveTxSigs(params.channelKeysA, signingB.fundingTx.localSigs, params.nodeParamsA.currentBlockHeight)(akka.event.NoLogging)
    assert(error == UnexpectedFundingSignatures(params.channelId))
  }

  test("invalid tx_signatures") {
    val (alice2bob, bob2alice) = (TestProbe(), TestProbe())
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100_000 sat, 25_000 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val alice = params.spawnTxBuilderAlice(wallet)
    val bob = params.spawnTxBuilderBob(wallet)
    alice ! Start(alice2bob.ref)
    bob ! Start(bob2alice.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddInput])
    alice ! ReceiveMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxAddInput])
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    alice ! ReceiveMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    alice ! ReceiveMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete])
    // Alice --- tx_complete --> Bob
    bob ! ReceiveMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete])
    // Alice <-- commit_sig --- Bob
    val successA1 = alice2bob.expectMsgType[Succeeded]
    val successB1 = bob2alice.expectMsgType[Succeeded]
    val Right(signingA2: InteractiveTxSigningSession.WaitingForSigs) = successA1.signingSession.receiveCommitSig(params.channelParamsA, params.channelKeysA, successB1.commitSig, params.nodeParamsA.currentBlockHeight)(akka.event.NoLogging)
    // Alice <-- tx_signatures --- Bob
    val Left(error) = signingA2.receiveTxSigs(params.channelKeysA, successB1.signingSession.fundingTx.localSigs.copy(witnesses = Seq(Script.witnessPay2wpkh(randomKey().publicKey, ByteVector.fill(73)(0)))), params.nodeParamsA.currentBlockHeight)(akka.event.NoLogging)
    assert(error.isInstanceOf[InvalidFundingSignature])
  }

  test("reference test vector") {
    val channelId = ByteVector32.Zeroes
    val parentTx = Transaction.read("02000000000101f86fd1d0db3ac5a72df968622f31e6b5e6566a09e29206d7c7a55df90e181de800000000171600141fb9623ffd0d422eacc450fd1e967efc477b83ccffffffff0580b2e60e00000000220020fd89acf65485df89797d9ba7ba7a33624ac4452f00db08107f34257d33e5b94680b2e60e0000000017a9146a235d064786b49e7043e4a042d4cc429f7eb6948780b2e60e00000000160014fbb4db9d85fba5e301f4399e3038928e44e37d3280b2e60e0000000017a9147ecd1b519326bc13b0ec716e469b58ed02b112a087f0006bee0000000017a914f856a70093da3a5b5c4302ade033d4c2171705d387024730440220696f6cee2929f1feb3fd6adf024ca0f9aa2f4920ed6d35fb9ec5b78c8408475302201641afae11242160101c6f9932aeb4fcd1f13a9c6df5d1386def000ea259a35001210381d7d5b1bc0d7600565d827242576d9cb793bfe0754334af82289ee8b65d137600000000")
    val sharedOutput = Output.Shared(UInt64(44), hex"0020297b92c238163e820b82486084634b4846b86a3c658d87b9384192e6bea98ec5", 200_000_000_000L msat, 200_000_000_000L msat, 0 msat)
    val initiatorTx = {
      val initiatorInput = Input.Local(UInt64(20), parentTx, 0, 4294967293L)
      val initiatorOutput = Output.Local.Change(UInt64(30), 49_999_845 sat, hex"00141ca1cca8855bad6bc1ea5436edd8cff10b7e448b")
      val nonInitiatorInput = Input.Remote(UInt64(11), OutPoint(parentTx, 2), parentTx.txOut(2), 4294967293L)
      val nonInitiatorOutput = Output.Remote(UInt64(33), 49_999_900 sat, hex"001444cb0c39f93ecc372b5851725bd29d865d333b10")
      SharedTransaction(None, sharedOutput, List(initiatorInput), List(nonInitiatorInput), List(initiatorOutput), List(nonInitiatorOutput), lockTime = 120)
    }
    assert(initiatorTx.localFees == 155_000.msat)
    assert(initiatorTx.remoteFees == 100_000.msat)
    assert(initiatorTx.fees == 255.sat)

    val nonInitiatorTx = {
      val initiatorInput = Input.Remote(UInt64(20), OutPoint(parentTx, 0), parentTx.txOut(0), 4294967293L)
      val initiatorOutput = Output.Remote(UInt64(30), 49_999_845 sat, hex"00141ca1cca8855bad6bc1ea5436edd8cff10b7e448b")
      val nonInitiatorInput = Input.Local(UInt64(11), parentTx, 2, 4294967293L)
      val nonInitiatorOutput = Output.Local.Change(UInt64(33), 49_999_900 sat, hex"001444cb0c39f93ecc372b5851725bd29d865d333b10")
      SharedTransaction(None, sharedOutput, List(nonInitiatorInput), List(initiatorInput), List(nonInitiatorOutput), List(initiatorOutput), lockTime = 120)
    }
    assert(nonInitiatorTx.localFees == 100_000.msat)
    assert(nonInitiatorTx.remoteFees == 155_000.msat)
    assert(nonInitiatorTx.fees == 255.sat)

    val unsignedTx = Transaction.read("0200000002b932b0669cd0394d0d5bcc27e01ab8c511f1662a6799925b346c0cf18fca03430200000000fdffffffb932b0669cd0394d0d5bcc27e01ab8c511f1662a6799925b346c0cf18fca03430000000000fdffffff03e5effa02000000001600141ca1cca8855bad6bc1ea5436edd8cff10b7e448b1cf0fa020000000016001444cb0c39f93ecc372b5851725bd29d865d333b100084d71700000000220020297b92c238163e820b82486084634b4846b86a3c658d87b9384192e6bea98ec578000000")
    assert(initiatorTx.buildUnsignedTx().txid == unsignedTx.txid)
    assert(nonInitiatorTx.buildUnsignedTx().txid == unsignedTx.txid)

    val initiatorSigs = TxSignatures(channelId, unsignedTx, Seq(ScriptWitness(Seq(hex"68656c6c6f2074686572652c2074686973206973206120626974636f6e212121", hex"82012088a820add57dfe5277079d069ca4ad4893c96de91f88ffb981fdc6a2a34d5336c66aff87"))), None)
    val nonInitiatorSigs = TxSignatures(channelId, unsignedTx, Seq(ScriptWitness(Seq(hex"304402207de9ba56bb9f641372e805782575ee840a899e61021c8b1572b3ec1d5b5950e9022069e9ba998915dae193d3c25cb89b5e64370e6a3a7755e7f31cf6d7cbc2a49f6d01", hex"034695f5b7864c580bf11f9f8cb1a94eb336f2ce9ef872d2ae1a90ee276c772484"))), None)
    val initiatorSignedTx = FullySignedSharedTransaction(initiatorTx, initiatorSigs, nonInitiatorSigs, None)
    assert(initiatorSignedTx.feerate == FeeratePerKw(262 sat))
    val nonInitiatorSignedTx = FullySignedSharedTransaction(nonInitiatorTx, nonInitiatorSigs, initiatorSigs, None)
    assert(nonInitiatorSignedTx.feerate == FeeratePerKw(262 sat))
    val signedTx = Transaction.read("02000000000102b932b0669cd0394d0d5bcc27e01ab8c511f1662a6799925b346c0cf18fca03430200000000fdffffffb932b0669cd0394d0d5bcc27e01ab8c511f1662a6799925b346c0cf18fca03430000000000fdffffff03e5effa02000000001600141ca1cca8855bad6bc1ea5436edd8cff10b7e448b1cf0fa020000000016001444cb0c39f93ecc372b5851725bd29d865d333b100084d71700000000220020297b92c238163e820b82486084634b4846b86a3c658d87b9384192e6bea98ec50247304402207de9ba56bb9f641372e805782575ee840a899e61021c8b1572b3ec1d5b5950e9022069e9ba998915dae193d3c25cb89b5e64370e6a3a7755e7f31cf6d7cbc2a49f6d0121034695f5b7864c580bf11f9f8cb1a94eb336f2ce9ef872d2ae1a90ee276c772484022068656c6c6f2074686572652c2074686973206973206120626974636f6e2121212782012088a820add57dfe5277079d069ca4ad4893c96de91f88ffb981fdc6a2a34d5336c66aff8778000000")
    assert(initiatorSignedTx.signedTx == signedTx)
    assert(initiatorSignedTx.signedTx == nonInitiatorSignedTx.signedTx)
  }

}

class InteractiveTxBuilderWithEclairSignerSpec extends InteractiveTxBuilderSpec {
  override def useEclairSigner = true
}
