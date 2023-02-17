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
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, OP_1, OutPoint, Satoshi, SatoshiLong, Script, ScriptWitness, Transaction, TxOut}
import fr.acinq.eclair.blockchain.OnChainWallet.{FundTransactionResponse, SignTransactionResponse}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.{MempoolTx, Utxo}
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BitcoinCoreClient, BitcoinJsonRPCClient}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{OnChainWallet, SingleKeyOnChainWallet}
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder._
import fr.acinq.eclair.io.OpenChannelInterceptor.makeChannelParams
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions.InputInfo
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Feature, FeatureSupport, Features, InitFeature, MilliSatoshiLong, NodeParams, TestConstants, TestKitBaseClass, ToMilliSatoshiConversion, UInt64, randomBytes32, randomKey}
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
    wallet.getReceiveAddress().pipeTo(probe.ref)
    val walletAddress = probe.expectMsgType[String]
    sendToAddress(walletAddress, amount, probe)
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
                           fundingParamsB: InteractiveTxParams,
                           nodeParamsB: NodeParams,
                           channelParamsB: ChannelParams,
                           channelFeatures: ChannelFeatures) {
    val channelId: ByteVector32 = fundingParamsA.channelId
    val commitFeerate: FeeratePerKw = TestConstants.anchorOutputsFeeratePerKw

    private val firstPerCommitmentPointA = nodeParamsA.channelKeyManager.commitmentPoint(nodeParamsA.channelKeyManager.keyPath(channelParamsA.localParams, ChannelConfig.standard), 0)
    private val firstPerCommitmentPointB = nodeParamsB.channelKeyManager.commitmentPoint(nodeParamsB.channelKeyManager.keyPath(channelParamsB.localParams, ChannelConfig.standard), 0)
    val fundingPurposeA = FundingTx(commitFeerate, firstPerCommitmentPointB)
    val fundingPurposeB = FundingTx(commitFeerate, firstPerCommitmentPointA)

    def dummySharedInputB(amount: Satoshi): SharedFundingInput = {
      val inputInfo = InputInfo(OutPoint(randomBytes32(), 3), TxOut(amount, fundingParamsB.fundingPubkeyScript), Nil)
      Multisig2of2Input(inputInfo, channelParamsA.remoteParams.fundingPubKey, channelParamsB.remoteParams.fundingPubKey)
    }

    def sharedInputs(commitmentA: Commitment, commitmentB: Commitment): (SharedFundingInput, SharedFundingInput) = {
      val sharedInputA = Multisig2of2Input(nodeParamsA.channelKeyManager, channelParamsA, commitmentA)
      val sharedInputB = Multisig2of2Input(nodeParamsB.channelKeyManager, channelParamsB, commitmentB)
      (sharedInputA, sharedInputB)
    }

    def spawnTxBuilderAlice(wallet: OnChainWallet, fundingParams: InteractiveTxParams = fundingParamsA): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      nodeParamsA, fundingParams, channelParamsA,
      fundingPurposeA,
      0 msat, 0 msat,
      wallet))

    def spawnTxBuilderRbfAlice(fundingParams: InteractiveTxParams, commitment: Commitment, previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction], wallet: OnChainWallet): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      nodeParamsA, fundingParams, channelParamsA,
      PreviousTxRbf(commitment, 0 sat, 0 sat, previousTransactions),
      0 msat, 0 msat,
      wallet))

    def spawnTxBuilderSpliceAlice(fundingParams: InteractiveTxParams, commitment: Commitment, wallet: OnChainWallet): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      nodeParamsA, fundingParams, channelParamsA,
      SpliceTx(commitment),
      0 msat, 0 msat,
      wallet))

    def spawnTxBuilderSpliceRbfAlice(fundingParams: InteractiveTxParams, commitment: Commitment, previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction], wallet: OnChainWallet): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      nodeParamsA, fundingParams, channelParamsA,
      PreviousTxRbf(commitment, commitment.localCommit.spec.toLocal.truncateToSatoshi, commitment.remoteCommit.spec.toLocal.truncateToSatoshi, previousTransactions),
      0 msat, 0 msat,
      wallet))

    def spawnTxBuilderBob(wallet: OnChainWallet, fundingParams: InteractiveTxParams = fundingParamsB): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      nodeParamsB, fundingParams, channelParamsB,
      fundingPurposeB,
      0 msat, 0 msat,
      wallet))

    def spawnTxBuilderRbfBob(fundingParams: InteractiveTxParams, commitment: Commitment, previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction], wallet: OnChainWallet): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      nodeParamsB, fundingParams, channelParamsB,
      PreviousTxRbf(commitment, 0 sat, 0 sat, previousTransactions),
      0 msat, 0 msat,
      wallet))

    def spawnTxBuilderSpliceBob(fundingParams: InteractiveTxParams, commitment: Commitment, wallet: OnChainWallet): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      nodeParamsB, fundingParams, channelParamsB,
      SpliceTx(commitment),
      0 msat, 0 msat,
      wallet))

    def spawnTxBuilderSpliceRbfBob(fundingParams: InteractiveTxParams, commitment: Commitment, previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction], wallet: OnChainWallet): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      nodeParamsB, fundingParams, channelParamsB,
      PreviousTxRbf(commitment, commitment.localCommit.spec.toLocal.truncateToSatoshi, commitment.remoteCommit.spec.toLocal.truncateToSatoshi, previousTransactions),
      0 msat, 0 msat,
      wallet))

  }

  private def createFixtureParams(fundingAmountA: Satoshi, fundingAmountB: Satoshi, targetFeerate: FeeratePerKw, dustLimit: Satoshi, lockTime: Long, requireConfirmedInputs: RequireConfirmedInputs = RequireConfirmedInputs(forLocal = false, forRemote = false)): FixtureParams = {
    val channelFeatures = ChannelFeatures(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), Features[InitFeature](Features.DualFunding -> FeatureSupport.Optional), Features[InitFeature](Features.DualFunding -> FeatureSupport.Optional), announceChannel = true)
    val Seq(nodeParamsA, nodeParamsB) = Seq(TestConstants.Alice.nodeParams, TestConstants.Bob.nodeParams).map(_.copy(features = Features(channelFeatures.features.map(f => f -> FeatureSupport.Optional).toMap[Feature, FeatureSupport])))
    val localParamsA = makeChannelParams(nodeParamsA, nodeParamsA.features.initFeatures(), None, None, isInitiator = true, dualFunded = true, fundingAmountA, unlimitedMaxHtlcValueInFlight = false)
    val localParamsB = makeChannelParams(nodeParamsB, nodeParamsB.features.initFeatures(), None, None, isInitiator = false, dualFunded = true, fundingAmountB, unlimitedMaxHtlcValueInFlight = false)

    val Seq(remoteParamsA, remoteParamsB) = Seq((nodeParamsA, localParamsA), (nodeParamsB, localParamsB)).map {
      case (nodeParams, localParams) =>
        val channelKeyPath = nodeParams.channelKeyManager.keyPath(localParams, ChannelConfig.standard)
        RemoteParams(
          nodeParams.nodeId,
          localParams.dustLimit, UInt64(localParams.maxHtlcValueInFlightMsat.toLong), None, localParams.htlcMinimum, localParams.toSelfDelay, localParams.maxAcceptedHtlcs,
          nodeParams.channelKeyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey,
          nodeParams.channelKeyManager.revocationPoint(channelKeyPath).publicKey,
          nodeParams.channelKeyManager.paymentPoint(channelKeyPath).publicKey,
          nodeParams.channelKeyManager.delayedPaymentPoint(channelKeyPath).publicKey,
          nodeParams.channelKeyManager.htlcPoint(channelKeyPath).publicKey,
          localParams.initFeatures,
          None)
    }

    val channelId = randomBytes32()
    val fundingScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(remoteParamsA.fundingPubKey, remoteParamsB.fundingPubKey)))
    val fundingParamsA = InteractiveTxParams(channelId, isInitiator = true, fundingAmountA, fundingAmountB, None, fundingScript, Nil, lockTime, dustLimit, targetFeerate, Some(3), requireConfirmedInputs)
    val fundingParamsB = InteractiveTxParams(channelId, isInitiator = false, fundingAmountB, fundingAmountA, None, fundingScript, Nil, lockTime, dustLimit, targetFeerate, Some(3), requireConfirmedInputs)
    val channelParamsA = ChannelParams(channelId, ChannelConfig.standard, channelFeatures, localParamsA, remoteParamsB, ChannelFlags.Public)
    val channelParamsB = ChannelParams(channelId, ChannelConfig.standard, channelFeatures, localParamsB, remoteParamsA, ChannelFlags.Public)

    FixtureParams(fundingParamsA, nodeParamsA, channelParamsA, fundingParamsB, nodeParamsB, channelParamsB, channelFeatures)
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
        case msg: InteractiveTxConstructionMessage => r ! ReceiveTxMessage(msg)
        case msg: CommitSig => r ! ReceiveCommitSig(msg)
        case msg: TxSignatures => r ! ReceiveTxSigs(msg)
        case msg => fail(s"invalid message sent ($msg)")
      }
      msg.asInstanceOf[T]
    }
  }

  private def withFixture(fundingAmountA: Satoshi, utxosA: Seq[Satoshi], fundingAmountB: Satoshi, utxosB: Seq[Satoshi], targetFeerate: FeeratePerKw, dustLimit: Satoshi, lockTime: Long, requireConfirmedInputs: RequireConfirmedInputs)(testFun: Fixture => Any): Unit = {
    // Initialize wallets with a few confirmed utxos.
    val probe = TestProbe()
    val rpcClientA = createWallet(UUID.randomUUID().toString)
    val walletA = new BitcoinCoreClient(rpcClientA)
    utxosA.foreach(amount => addUtxo(walletA, amount, probe))
    val rpcClientB = createWallet(UUID.randomUUID().toString)
    val walletB = new BitcoinCoreClient(rpcClientB)
    utxosB.foreach(amount => addUtxo(walletB, amount, probe))
    generateBlocks(1)

    val fixtureParams = createFixtureParams(fundingAmountA, fundingAmountB, targetFeerate, dustLimit, lockTime, requireConfirmedInputs)
    val alice = fixtureParams.spawnTxBuilderAlice(walletA)
    val bob = fixtureParams.spawnTxBuilderBob(walletB)
    testFun(Fixture(alice, bob, fixtureParams, walletA, rpcClientA, walletB, rpcClientB, TestProbe(), TestProbe()))
  }

  test("initiator funds more than non-initiator") {
    val targetFeerate = FeeratePerKw(5000 sat)
    val fundingA = 120_000 sat
    val utxosA = Seq(50_000 sat, 35_000 sat, 60_000 sat)
    val fundingB = 40_000 sat
    val utxosB = Seq(100_000 sat)
    withFixture(fundingA, utxosA, fundingB, utxosB, targetFeerate, 660 sat, 42, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // Bob waits for Alice to send the first message.
      bob2alice.expectNoMessage(100 millis)
      // Alice --- tx_add_input --> Bob
      val inputA1 = fwd.forwardAlice2Bob[TxAddInput]
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
      assert(aliceParams.fundingPubkeyScript == bobParams.fundingPubkeyScript)
      assert(aliceParams.fundingAmount == 160_000.sat)
      assert(Seq(outputA1, outputA2).count(_.pubkeyScript == aliceParams.fundingPubkeyScript) == 1)
      assert(Seq(outputA1, outputA2).exists(o => o.pubkeyScript == aliceParams.fundingPubkeyScript && o.amount == aliceParams.fundingAmount))
      assert(outputB1.pubkeyScript != aliceParams.fundingPubkeyScript)

      // Bob sends signatures first as he contributed less than Alice.
      fwd.forwardBob2Alice[CommitSig]
      fwd.forwardAlice2Bob[CommitSig]
      val txB = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB.localSigs)
      val txA = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]

      // The resulting transaction is valid and has the right feerate.
      assert(txA.signedTx.txid == txB.txId)
      assert(txA.signedTx.lockTime == aliceParams.lockTime)
      assert(txA.tx.localAmountIn == utxosA.sum)
      assert(txA.tx.remoteAmountIn == utxosB.sum)
      assert(0.sat < txB.tx.localFees)
      assert(txB.tx.localFees == txA.tx.remoteFees)
      assert(txB.tx.localFees < txA.tx.localFees)
      walletA.publishTransaction(txA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA.signedTx.txid)
      walletA.getMempoolTx(txA.signedTx.txid).pipeTo(probe.ref)
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
    withFixture(fundingA, utxosA, fundingB, utxosB, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = true, forRemote = true)) { f =>
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
      assert(aliceParams.fundingPubkeyScript == bobParams.fundingPubkeyScript)
      assert(aliceParams.fundingAmount == 60_000.sat)
      assert(Seq(outputA1, outputA2).count(_.pubkeyScript == aliceParams.fundingPubkeyScript) == 1)
      assert(Seq(outputA1, outputA2).exists(o => o.pubkeyScript == aliceParams.fundingPubkeyScript && o.amount == aliceParams.fundingAmount))
      assert(outputB.pubkeyScript != aliceParams.fundingPubkeyScript)

      // Alice sends signatures first as she contributed less than Bob.
      fwd.forwardAlice2Bob[CommitSig]
      fwd.forwardBob2Alice[CommitSig]
      val txA = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      bob ! ReceiveTxSigs(txA.localSigs)
      val txB = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]

      // The resulting transaction is valid and has the right feerate.
      assert(txB.signedTx.lockTime == aliceParams.lockTime)
      assert(txB.tx.localAmountIn == utxosB.sum)
      assert(txB.tx.remoteAmountIn == utxosA.sum)
      assert(0.sat < txA.tx.localFees)
      assert(0.sat < txB.tx.localFees)
      assert(txA.tx.remoteFees == txB.tx.localFees)
      val probe = TestProbe()
      walletB.publishTransaction(txB.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txB.signedTx.txid)
      walletB.getMempoolTx(txB.signedTx.txid).pipeTo(probe.ref)
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
    withFixture(fundingA, utxosA, fundingB, utxosB, targetFeerate, 660 sat, 42, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
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
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]

      // Alice contributes more than Bob to funding output, but Bob's inputs are bigger than Alice's, so Alice must sign first.
      assert(inputA.previousTx_opt.get.txOut(inputA.previousTxOutput.toInt).amount < inputB.previousTx_opt.get.txOut(inputB.previousTxOutput.toInt).amount)
      // Alice --- tx_signatures --> Bob
      val txA = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      bob ! ReceiveTxSigs(txA.localSigs)
      // Alice <-- tx_signatures --- Bob
      val txB = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]

      // The resulting transaction is valid.
      val probe = TestProbe()
      walletA.publishTransaction(txB.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txB.signedTx.txid)
    }
  }

  test("non-initiator does not contribute") {
    val targetFeerate = FeeratePerKw(2500 sat)
    val fundingA = 150_000 sat
    val utxosA = Seq(80_000 sat, 120_000 sat)
    withFixture(fundingA, utxosA, 0 sat, Nil, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
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

      // Alice is responsible for adding the shared output.
      assert(aliceParams.fundingPubkeyScript == bobParams.fundingPubkeyScript)
      assert(aliceParams.fundingAmount == 150_000.sat)
      assert(Seq(outputA1, outputA2).count(_.pubkeyScript == aliceParams.fundingPubkeyScript) == 1)
      assert(Seq(outputA1, outputA2).exists(o => o.pubkeyScript == aliceParams.fundingPubkeyScript && o.amount == aliceParams.fundingAmount))

      // Bob sends signatures first as he did not contribute at all.
      fwd.forwardBob2Alice[CommitSig]
      fwd.forwardAlice2Bob[CommitSig]
      val txB = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      // Alice doesn't wait to receive Bob's signatures: they are empty anyway.
      val txA = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]

      // The resulting transaction is valid and has the right feerate.
      assert(txA.signedTx.txid == txB.txId)
      assert(txA.signedTx.lockTime == aliceParams.lockTime)
      assert(txA.tx.localAmountIn == utxosA.sum)
      assert(txA.tx.remoteAmountIn == 0.sat)
      assert(txB.tx.localFees == 0.sat)
      assert(txA.tx.localFees == txA.tx.fees)
      val probe = TestProbe()
      walletA.publishTransaction(txA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA.signedTx.txid)
      walletA.getMempoolTx(txA.signedTx.txid).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == txA.tx.fees)
      assert(targetFeerate <= txA.feerate && txA.feerate <= targetFeerate * 1.25, s"unexpected feerate (target=$targetFeerate actual=${txA.feerate})")
    }
  }

  test("initiator uses unconfirmed inputs") {
    withFixture(100_000 sat, Seq(250_000 sat), 0 sat, Nil, FeeratePerKw(2500 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      // Alice's inputs are all unconfirmed.
      val probe = TestProbe()
      val tx = sendToAddress(getNewAddress(probe, rpcClientA), 75_000 sat, probe, rpcClientA)
      walletA.listUnspent().pipeTo(probe.ref)
      probe.expectMsgType[Seq[Utxo]].foreach(utxo => assert(utxo.confirmations == 0))

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
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      val txB = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB.localSigs)
      val txA = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]
      txA.signedTx.txIn.foreach(txIn => assert(txIn.outPoint.txid == tx.txid))
    }
  }

  test("initiator and non-initiator splice-in") {
    val targetFeerate = FeeratePerKw(1000 sat)
    val fundingA1 = 100_000 sat
    val utxosA = Seq(150_000 sat, 85_000 sat)
    val fundingB1 = 50_000 sat
    val utxosB = Seq(90_000 sat, 80_000 sat)
    withFixture(fundingA1, utxosA, fundingB1, utxosB, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = true, forRemote = true)) { f =>
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
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB1 = bob2alice.expectMsgType[Succeeded]
      alice ! ReceiveTxSigs(successB1.sharedTx.localSigs)
      // Alice --- tx_signatures --> Bob
      val successA1 = alice2bob.expectMsgType[Succeeded]
      walletA.publishTransaction(successA1.sharedTx.signedTx_opt.get).pipeTo(probe.ref)
      probe.expectMsg(successA1.sharedTx.txId)

      // Alice and Bob decide to splice additional funds in the channel.
      val fundingA2 = fundingA1 + 30_000.sat
      val fundingB2 = fundingB1 + 25_000.sat
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(successA1.commitment, successB1.commitment)
      val fundingParamsA1 = aliceParams.copy(localAmount = fundingA2, remoteAmount = fundingB2, sharedInput_opt = Some(sharedInputA))
      val fundingParamsB1 = bobParams.copy(localAmount = fundingB2, remoteAmount = fundingA2, sharedInput_opt = Some(sharedInputB))
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, successA1.commitment, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(fundingParamsB1, successB1.commitment, walletB)
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
      // Alice <-- commit_sig --- Bob
      fwdSplice.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwdSplice.forwardAlice2Bob[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB2 = bob2alice.expectMsgType[Succeeded]
      assert(successB2.sharedTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      aliceSplice ! ReceiveTxSigs(successB2.sharedTx.localSigs)
      // Alice --- tx_signatures --> Bob
      val successA2 = alice2bob.expectMsgType[Succeeded]
      assert(successA2.sharedTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      val spliceTx = successA2.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(spliceTx.signedTx.txIn.exists(_.outPoint == successA1.commitment.commitInput.outPoint))
      assert(0.sat < spliceTx.tx.localFees)
      assert(0.sat < spliceTx.tx.remoteFees)
      assert(successB2.sharedTx.tx.localFees == spliceTx.tx.remoteFees)

      assert(successA2.commitment.localCommit.spec.toLocal == fundingA2.toMilliSatoshi)
      assert(successA2.commitment.localCommit.spec.toRemote == fundingB2.toMilliSatoshi)
      assert(successB2.commitment.localCommit.spec.toLocal == fundingB2.toMilliSatoshi)
      assert(successB2.commitment.localCommit.spec.toRemote == fundingA2.toMilliSatoshi)

      // The resulting transaction is valid and has the right feerate.
      walletA.publishTransaction(spliceTx.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTx.txId)
      walletA.getMempoolTx(spliceTx.txId).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == spliceTx.tx.fees)
      assert(targetFeerate <= spliceTx.feerate && spliceTx.feerate <= targetFeerate * 1.25, s"unexpected feerate (target=$targetFeerate actual=${spliceTx.feerate})")
    }
  }

  test("initiator and non-initiator splice-out (single)") {
    val fundingA1 = 100_000 sat
    val utxosA = Seq(150_000 sat)
    val fundingB1 = 90_000 sat
    val utxosB = Seq(130_000 sat)
    withFixture(fundingA1, utxosA, fundingB1, utxosB, FeeratePerKw(1000 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = true, forRemote = true)) { f =>
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
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB1 = bob2alice.expectMsgType[Succeeded]
      alice ! ReceiveTxSigs(successB1.sharedTx.localSigs)
      // Alice --- tx_signatures --> Bob
      val successA1 = alice2bob.expectMsgType[Succeeded]
      walletA.publishTransaction(successA1.sharedTx.signedTx_opt.get).pipeTo(probe.ref)
      probe.expectMsg(successA1.sharedTx.txId)

      // Alice and Bob decide to splice funds out of the channel, and deduce on-chain fees from their new channel contribution.
      val spliceOutputsA = List(TxOut(50_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val spliceOutputsB = List(TxOut(30_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val fundingA2 = fundingA1 - spliceOutputsA.map(_.amount).sum - 1_000.sat
      val fundingB2 = fundingB1 - spliceOutputsB.map(_.amount).sum - 500.sat
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(successA1.commitment, successB1.commitment)
      val fundingParamsA1 = aliceParams.copy(localAmount = fundingA2, remoteAmount = fundingB2, sharedInput_opt = Some(sharedInputA), localOutputs = spliceOutputsA)
      val fundingParamsB1 = bobParams.copy(localAmount = fundingB2, remoteAmount = fundingA2, sharedInput_opt = Some(sharedInputB), localOutputs = spliceOutputsB)
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, successA1.commitment, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(fundingParamsB1, successB1.commitment, walletB)
      val fwdSplice = TypeCheckedForwarder(aliceSplice, bobSplice, alice2bob, bob2alice)

      aliceSplice ! Start(alice2bob.ref)
      bobSplice ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      val sharedInput = fwdSplice.forwardAlice2Bob[TxAddInput]
      assert(sharedInput.previousTx_opt.isEmpty)
      assert(sharedInput.sharedInput_opt.contains(successA1.commitment.commitInput.outPoint))
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
      // Alice <-- commit_sig --- Bob
      fwdSplice.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwdSplice.forwardAlice2Bob[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB2 = bob2alice.expectMsgType[Succeeded]
      assert(successB2.sharedTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      aliceSplice ! ReceiveTxSigs(successB2.sharedTx.localSigs)
      // Alice --- tx_signatures --> Bob
      val successA2 = alice2bob.expectMsgType[Succeeded]
      assert(successA2.sharedTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      val spliceTx = successA2.sharedTx.signedTx_opt.get
      assert(successA2.sharedTx.tx.localFees == 1000.sat)
      assert(successB2.sharedTx.tx.localFees == 500.sat)
      assert(successB2.sharedTx.tx.localFees == successA2.sharedTx.tx.remoteFees)
      spliceOutputsA.foreach(txOut => assert(Set(outputA1, outputA2).map(o => TxOut(o.amount, o.pubkeyScript)).contains(txOut)))
      spliceOutputsB.foreach(txOut => assert(Set(outputB).map(o => TxOut(o.amount, o.pubkeyScript)).contains(txOut)))
      assert(Set(outputA1, outputA2).exists(o => o.amount == fundingA2 + fundingB2 && o.pubkeyScript == fundingParamsA1.fundingPubkeyScript))

      assert(successA2.commitment.localCommit.spec.toLocal == fundingA2.toMilliSatoshi)
      assert(successA2.commitment.localCommit.spec.toRemote == fundingB2.toMilliSatoshi)
      assert(successB2.commitment.localCommit.spec.toLocal == fundingB2.toMilliSatoshi)
      assert(successB2.commitment.localCommit.spec.toRemote == fundingA2.toMilliSatoshi)

      // The resulting transaction is valid.
      walletA.publishTransaction(spliceTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTx.txid)
    }
  }

  test("initiator and non-initiator splice-out (multiple)") {
    val fundingA1 = 150_000 sat
    val utxosA = Seq(200_000 sat)
    val fundingB1 = 100_000 sat
    val utxosB = Seq(150_000 sat)
    withFixture(fundingA1, utxosA, fundingB1, utxosB, FeeratePerKw(1000 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = true, forRemote = true)) { f =>
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
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB1 = bob2alice.expectMsgType[Succeeded]
      alice ! ReceiveTxSigs(successB1.sharedTx.localSigs)
      // Alice --- tx_signatures --> Bob
      val successA1 = alice2bob.expectMsgType[Succeeded]
      walletA.publishTransaction(successA1.sharedTx.signedTx_opt.get).pipeTo(probe.ref)
      probe.expectMsg(successA1.sharedTx.txId)

      // Alice and Bob decide to splice funds out of the channel, and deduce on-chain fees from their new channel contribution.
      val spliceOutputsA = List(20_000 sat, 15_000 sat, 15_000 sat).map(amount => TxOut(amount, Script.pay2wpkh(randomKey().publicKey)))
      val spliceOutputsB = List(25_000 sat, 15_000 sat).map(amount => TxOut(amount, Script.pay2wpkh(randomKey().publicKey)))
      val fundingA2 = fundingA1 - spliceOutputsA.map(_.amount).sum - 1_000.sat
      val fundingB2 = fundingB1 - spliceOutputsB.map(_.amount).sum - 500.sat
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(successA1.commitment, successB1.commitment)
      val fundingParamsA1 = aliceParams.copy(localAmount = fundingA2, remoteAmount = fundingB2, sharedInput_opt = Some(sharedInputA), localOutputs = spliceOutputsA)
      val fundingParamsB1 = bobParams.copy(localAmount = fundingB2, remoteAmount = fundingA2, sharedInput_opt = Some(sharedInputB), localOutputs = spliceOutputsB)
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, successA1.commitment, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(fundingParamsB1, successB1.commitment, walletB)
      val fwdSplice = TypeCheckedForwarder(aliceSplice, bobSplice, alice2bob, bob2alice)

      aliceSplice ! Start(alice2bob.ref)
      bobSplice ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      val sharedInput = fwdSplice.forwardAlice2Bob[TxAddInput]
      assert(sharedInput.previousTx_opt.isEmpty)
      assert(sharedInput.sharedInput_opt.contains(successA1.commitment.commitInput.outPoint))
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
      // Alice <-- commit_sig --- Bob
      fwdSplice.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwdSplice.forwardAlice2Bob[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB2 = bob2alice.expectMsgType[Succeeded]
      assert(successB2.sharedTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      aliceSplice ! ReceiveTxSigs(successB2.sharedTx.localSigs)
      // Alice --- tx_signatures --> Bob
      val successA2 = alice2bob.expectMsgType[Succeeded]
      assert(successA2.sharedTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      val spliceTx = successA2.sharedTx.signedTx_opt.get
      assert(successA2.sharedTx.tx.localFees == 1000.sat)
      assert(successB2.sharedTx.tx.localFees == 500.sat)
      assert(successB2.sharedTx.tx.localFees == successA2.sharedTx.tx.remoteFees)
      spliceOutputsA.foreach(txOut => assert(Set(outputA1, outputA2, outputA3, outputA4).map(o => TxOut(o.amount, o.pubkeyScript)).contains(txOut)))
      spliceOutputsB.foreach(txOut => assert(Set(outputB1, outputB2).map(o => TxOut(o.amount, o.pubkeyScript)).contains(txOut)))
      assert(Set(outputA1, outputA2, outputA3, outputA4).exists(o => o.amount == fundingA2 + fundingB2 && o.pubkeyScript == fundingParamsA1.fundingPubkeyScript))

      assert(successA2.commitment.localCommit.spec.toLocal == fundingA2.toMilliSatoshi)
      assert(successA2.commitment.localCommit.spec.toRemote == fundingB2.toMilliSatoshi)
      assert(successB2.commitment.localCommit.spec.toLocal == fundingB2.toMilliSatoshi)
      assert(successB2.commitment.localCommit.spec.toRemote == fundingA2.toMilliSatoshi)

      // The resulting transaction is valid.
      walletA.publishTransaction(spliceTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTx.txid)
    }
  }

  test("initiator and non-initiator combine splice-in and splice-out") {
    val targetFeerate = FeeratePerKw(1000 sat)
    val fundingA1 = 150_000 sat
    val utxosA = Seq(200_000 sat, 100_000 sat)
    val fundingB1 = 100_000 sat
    val utxosB = Seq(150_000 sat, 50_000 sat)
    withFixture(fundingA1, utxosA, fundingB1, utxosB, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = true, forRemote = true)) { f =>
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
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB1 = bob2alice.expectMsgType[Succeeded]
      alice ! ReceiveTxSigs(successB1.sharedTx.localSigs)
      // Alice --- tx_signatures --> Bob
      val successA1 = alice2bob.expectMsgType[Succeeded]
      walletA.publishTransaction(successA1.sharedTx.signedTx_opt.get).pipeTo(probe.ref)
      probe.expectMsg(successA1.sharedTx.txId)

      // Alice and Bob decide to splice funds out of the channel while also splicing funds in, resulting in an increase
      // of their channel balance and the creation of splice outputs.
      val fundingA2 = fundingA1 + 25_000.sat
      val fundingB2 = fundingB1 + 15_000.sat
      val spliceOutputsA = List(TxOut(30_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val spliceOutputsB = List(TxOut(10_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(successA1.commitment, successB1.commitment)
      val fundingParamsA1 = aliceParams.copy(localAmount = fundingA2, remoteAmount = fundingB2, sharedInput_opt = Some(sharedInputA), localOutputs = spliceOutputsA)
      val fundingParamsB1 = bobParams.copy(localAmount = fundingB2, remoteAmount = fundingA2, sharedInput_opt = Some(sharedInputB), localOutputs = spliceOutputsB)
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, successA1.commitment, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(fundingParamsB1, successB1.commitment, walletB)
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
      // Alice <-- commit_sig --- Bob
      fwdSplice.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwdSplice.forwardAlice2Bob[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB2 = bob2alice.expectMsgType[Succeeded]
      assert(successB2.sharedTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      aliceSplice ! ReceiveTxSigs(successB2.sharedTx.localSigs)
      // Alice --- tx_signatures --> Bob
      val successA2 = alice2bob.expectMsgType[Succeeded]
      assert(successA2.sharedTx.localSigs.previousFundingTxSig_opt.nonEmpty)
      val spliceTx = successA2.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      spliceOutputsA.foreach(txOut => assert(Set(outputA1, outputA2, outputA3).map(o => TxOut(o.amount, o.pubkeyScript)).contains(txOut)))
      spliceOutputsB.foreach(txOut => assert(Set(outputB1, outputB2).map(o => TxOut(o.amount, o.pubkeyScript)).contains(txOut)))
      assert(Set(outputA1, outputA2, outputA3).exists(o => o.amount == fundingA2 + fundingB2 && o.pubkeyScript == fundingParamsA1.fundingPubkeyScript))

      assert(successA2.commitment.localCommit.spec.toLocal == fundingA2.toMilliSatoshi)
      assert(successA2.commitment.localCommit.spec.toRemote == fundingB2.toMilliSatoshi)
      assert(successB2.commitment.localCommit.spec.toLocal == fundingB2.toMilliSatoshi)
      assert(successB2.commitment.localCommit.spec.toRemote == fundingA2.toMilliSatoshi)

      // The resulting transaction is valid and has the right feerate.
      walletA.publishTransaction(spliceTx.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTx.txId)
      walletA.getMempoolTx(spliceTx.txId).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == spliceTx.tx.fees)
      assert(targetFeerate <= spliceTx.feerate && spliceTx.feerate <= targetFeerate * 1.25, s"unexpected feerate (target=$targetFeerate actual=${spliceTx.feerate})")
    }
  }

  test("remove input/output") {
    withFixture(100_000 sat, Seq(150_000 sat), 0 sat, Nil, FeeratePerKw(2500 sat), 330 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      alice ! Start(alice2bob.ref)
      bob ! Start(bob2alice.ref)

      // In this flow we introduce dummy inputs/outputs from Bob to Alice that are then removed.
      // Alice --- tx_add_input --> Bob
      val inputA = fwd.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      bob2alice.expectMsgType[SendMessage] // we override Bob's tx_complete
      alice ! ReceiveTxMessage(TxAddInput(bobParams.channelId, UInt64(1), Some(Transaction(2, Nil, Seq(TxOut(250_000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)), 0, 0))
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      bob2alice.expectMsgType[SendMessage] // we override Bob's tx_complete
      alice ! ReceiveTxMessage(TxAddOutput(bobParams.channelId, UInt64(3), 250_000 sat, Script.write(Script.pay2wpkh(randomKey().publicKey))))
      // Alice --- tx_add_output --> Bob
      fwd.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_remove_input --- Bob
      bob2alice.expectMsgType[SendMessage] // we override Bob's tx_complete
      alice ! ReceiveTxMessage(TxRemoveInput(bobParams.channelId, UInt64(1)))
      // Alice --- tx_complete --> Bob
      fwd.forwardAlice2Bob[TxComplete]
      // Alice <-- tx_remove_output --- Bob
      alice ! ReceiveTxMessage(TxRemoveOutput(bobParams.channelId, UInt64(3)))
      // Alice --- tx_complete --> Bob
      alice2bob.expectMsgType[SendMessage]
      // Alice <-- tx_complete --- Bob
      alice ! ReceiveTxMessage(TxComplete(bobParams.channelId))
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      val txB = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB.localSigs)
      val txA = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]

      // The resulting transaction doesn't contain Bob's removed inputs and outputs.
      assert(txA.signedTx.txid == txB.txId)
      assert(txA.signedTx.lockTime == aliceParams.lockTime)
      assert(txA.signedTx.txIn.map(_.outPoint) == Seq(toOutPoint(inputA)))
      assert(txA.signedTx.txOut.length == 2)
      assert(txA.tx.remoteAmountIn == 0.sat)
    }
  }

  test("not enough funds (unconfirmed utxos not allowed)") {
    withFixture(100_000 sat, Seq(250_000 sat), 0 sat, Nil, FeeratePerKw(2500 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = true, forRemote = true)) { f =>
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
    withFixture(fundingA, utxosA, 0 sat, Nil, FeeratePerKw(5000 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      // Add some unusable utxos to Alice's wallet.
      val probe = TestProbe()
      val legacyTxId = {
        // Dual funding disallows non-segwit inputs.
        val legacyAddress = getNewAddress(probe, rpcClientA, Some("legacy"))
        sendToAddress(legacyAddress, 100_000 sat, probe).txid
      }
      val bigTxId = {
        // Dual funding cannot use transactions that exceed 65k bytes.
        walletA.getP2wpkhPubkey().pipeTo(probe.ref)
        val publicKey = probe.expectMsgType[PublicKey]
        val tx = Transaction(2, Nil, TxOut(100_000 sat, Script.pay2wpkh(publicKey)) +: (1 to 2500).map(_ => TxOut(5000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
        val minerWallet = new BitcoinCoreClient(bitcoinrpcclient)
        minerWallet.fundTransaction(tx, FeeratePerKw(500 sat), replaceable = true).pipeTo(probe.ref)
        val unsignedTx = probe.expectMsgType[FundTransactionResponse].tx
        minerWallet.signTransaction(unsignedTx).pipeTo(probe.ref)
        val signedTx = probe.expectMsgType[SignTransactionResponse].tx
        assert(Transaction.write(signedTx).length >= 65_000)
        minerWallet.publishTransaction(signedTx).pipeTo(probe.ref)
        probe.expectMsgType[ByteVector32]
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
    withFixture(fundingA, utxosA, 0 sat, Nil, FeeratePerKw(5000 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      // Add some unusable utxos to Alice's wallet.
      val probe = TestProbe()
      val legacyTxIds = {
        // Dual funding disallows non-segwit inputs.
        val legacyAddress = getNewAddress(probe, rpcClientA, Some("legacy"))
        val tx1 = sendToAddress(legacyAddress, 100_000 sat, probe).txid
        val tx2 = sendToAddress(legacyAddress, 120_000 sat, probe).txid
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
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      val txB = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB.localSigs)
      val txA = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]

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
    withFixture(fundingA, utxosA, 0 sat, Nil, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
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
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val txB1 = successB1.sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB1.localSigs)
      val successA1 = alice2bob.expectMsgType[Succeeded]
      val txA1 = successA1.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(targetFeerate * 0.9 <= txA1.feerate && txA1.feerate <= targetFeerate * 1.25)
      val probe = TestProbe()
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.signedTx.txid)

      val aliceRbf = fixtureParams.spawnTxBuilderRbfAlice(aliceParams.copy(targetFeerate = targetFeerate * 1.5), successA1.commitment, Seq(txA1), walletA)
      val bobRbf = fixtureParams.spawnTxBuilderRbfBob(bobParams.copy(targetFeerate = targetFeerate * 1.5), successB1.commitment, Nil, walletB)
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
      // Alice --- commit_sig --> Bob
      fwdRbf.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwdRbf.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val txB2 = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      aliceRbf ! ReceiveTxSigs(txB2.localSigs)
      val succeeded = alice2bob.expectMsgType[Succeeded]
      val rbfFeerate = succeeded.fundingParams.targetFeerate
      assert(targetFeerate < rbfFeerate)
      val txA2 = succeeded.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(rbfFeerate * 0.9 <= txA2.feerate && txA2.feerate <= rbfFeerate * 1.25)
      assert(inputA1 == inputA2)
      assert(txA1.signedTx.txIn.map(_.outPoint) == txA2.signedTx.txIn.map(_.outPoint))
      assert(txA1.signedTx.txid != txA2.signedTx.txid)
      assert(txA1.tx.fees < txA2.tx.fees)
      walletA.publishTransaction(txA2.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA2.signedTx.txid)
    }
  }

  test("fund transaction with previous inputs (with new inputs)") {
    val targetFeerate = FeeratePerKw(10_000 sat)
    val fundingA = 100_000 sat
    val utxosA = Seq(55_000 sat, 55_000 sat, 55_000 sat)
    withFixture(fundingA, utxosA, 0 sat, Nil, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
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
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val txB1 = successB1.sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB1.localSigs)
      val successA1 = alice2bob.expectMsgType[Succeeded]
      val txA1 = successA1.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      // Bitcoin Core didn't add a change output, which results in a bigger over-payment of the on-chain fees.
      assert(targetFeerate * 0.9 <= txA1.feerate && txA1.feerate <= targetFeerate * 1.5)
      val probe = TestProbe()
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.signedTx.txid)

      val aliceRbf = fixtureParams.spawnTxBuilderRbfAlice(aliceParams.copy(targetFeerate = targetFeerate * 1.5), successA1.commitment, Seq(txA1), walletA)
      val bobRbf = fixtureParams.spawnTxBuilderRbfBob(bobParams.copy(targetFeerate = targetFeerate * 1.5), successB1.commitment, Nil, walletB)
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
      // Alice --- commit_sig --> Bob
      fwdRbf.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwdRbf.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val txB2 = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      aliceRbf ! ReceiveTxSigs(txB2.localSigs)
      val succeeded = alice2bob.expectMsgType[Succeeded]
      val rbfFeerate = succeeded.fundingParams.targetFeerate
      assert(targetFeerate < rbfFeerate)
      val txA2 = succeeded.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(rbfFeerate * 0.9 <= txA2.feerate && txA2.feerate <= rbfFeerate * 1.25)
      val previousInputs = Set(inputA1, inputA2).map(i => toOutPoint(i))
      val newInputs = Set(inputA3, inputA4, inputA5).map(i => toOutPoint(i))
      assert(previousInputs.subsetOf(newInputs))
      assert(txA1.signedTx.txid != txA2.signedTx.txid)
      assert(txA1.signedTx.txIn.length + 1 == txA2.signedTx.txIn.length)
      assert(txA1.tx.fees < txA2.tx.fees)
      walletA.publishTransaction(txA2.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA2.signedTx.txid)
    }
  }

  test("rbf with previous contributions from the non-initiator") {
    val initialFeerate = FeeratePerKw(5_000 sat)
    val fundingA = 100_000 sat
    val utxosA = Seq(70_000 sat, 60_000 sat)
    val fundingB = 25_000 sat
    val utxosB = Seq(27_500 sat)
    withFixture(fundingA, utxosA, fundingB, utxosB, initialFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
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
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val txB1 = successB1.sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB1.localSigs)
      val successA1 = alice2bob.expectMsgType[Succeeded]
      val txA1 = successA1.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(initialFeerate * 0.9 <= txA1.feerate && txA1.feerate <= initialFeerate * 1.25)
      val probe = TestProbe()
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.signedTx.txid)

      // Bob didn't have enough funds to add a change output.
      // If we want to increase the feerate, Bob cannot contribute more than what he has already contributed.
      // However, it still makes sense for Bob to contribute whatever he's able to, the final feerate will simply be
      // slightly less than what Alice intended, but it's better than being stuck with a low feerate.
      val aliceRbf = fixtureParams.spawnTxBuilderRbfAlice(aliceParams.copy(targetFeerate = initialFeerate * 1.5), successA1.commitment, Seq(txA1), walletA)
      val bobRbf = fixtureParams.spawnTxBuilderRbfBob(bobParams.copy(targetFeerate = initialFeerate * 1.5), successB1.commitment, Seq(txB1), walletB)
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
      // Alice --- commit_sig --> Bob
      fwdRbf.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwdRbf.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val txB2 = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      aliceRbf ! ReceiveTxSigs(txB2.localSigs)
      val succeeded = alice2bob.expectMsgType[Succeeded]
      val rbfFeerate = succeeded.fundingParams.targetFeerate
      assert(rbfFeerate == FeeratePerKw(7500 sat))
      assert(inputB == inputBb)
      assert(Set(inputA1, inputA2).map(i => toOutPoint(i)) == Set(inputA1b, inputA2b).map(i => toOutPoint(i)))
      val txA2 = succeeded.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(rbfFeerate * 0.75 <= txA2.feerate && txA2.feerate <= rbfFeerate * 1.25)
      assert(txA1.signedTx.txIn.map(_.outPoint).toSet == txA2.signedTx.txIn.map(_.outPoint).toSet)
      assert(txA2.signedTx.txOut.map(_.amount).sum < txA1.signedTx.txOut.map(_.amount).sum)
      assert(txA1.tx.fees < txA2.tx.fees)
      walletA.publishTransaction(txA2.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA2.signedTx.txid)
    }
  }

  test("fund splice transaction with previous inputs (no new inputs)") {
    val targetFeerate = FeeratePerKw(2_000 sat)
    val fundingA1 = 150_000 sat
    val utxosA = Seq(200_000 sat, 75_000 sat)
    val fundingB1 = 100_000 sat
    val utxosB = Seq(150_000 sat, 50_000 sat)
    withFixture(fundingA1, utxosA, fundingB1, utxosB, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
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
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB1 = bob2alice.expectMsgType[Succeeded]
      alice ! ReceiveTxSigs(successB1.sharedTx.localSigs)
      val successA1 = alice2bob.expectMsgType[Succeeded]
      val txA1 = successA1.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(targetFeerate * 0.9 <= txA1.feerate && txA1.feerate <= targetFeerate * 1.25)
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.signedTx.txid)

      // Alice and Bob splice some funds in and out.
      val fundingA2 = fundingA1 + 15_000.sat
      val fundingB2 = fundingB1 + 5_000.sat
      val spliceOutputsA = List(TxOut(20_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val spliceOutputsB = List(TxOut(10_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(successA1.commitment, successB1.commitment)
      val fundingParamsA1 = aliceParams.copy(localAmount = fundingA2, remoteAmount = fundingB2, sharedInput_opt = Some(sharedInputA), localOutputs = spliceOutputsA)
      val fundingParamsB1 = bobParams.copy(localAmount = fundingB2, remoteAmount = fundingA2, sharedInput_opt = Some(sharedInputB), localOutputs = spliceOutputsB)
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, successA1.commitment, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(fundingParamsB1, successB1.commitment, walletB)
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
      // Alice <-- commit_sig --- Bob
      fwdSplice.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwdSplice.forwardAlice2Bob[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB2 = bob2alice.expectMsgType[Succeeded]
      aliceSplice ! ReceiveTxSigs(successB2.sharedTx.localSigs)
      // Alice --- tx_signatures --> Bob
      val successA2 = alice2bob.expectMsgType[Succeeded]
      val spliceTxA1 = successA2.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(targetFeerate * 0.9 <= spliceTxA1.feerate && spliceTxA1.feerate <= targetFeerate * 1.25)
      walletA.publishTransaction(spliceTxA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA1.txId)

      // Alice wants to increase the feerate of the splice transaction.
      val aliceRbf = fixtureParams.spawnTxBuilderSpliceRbfAlice(fundingParamsA1.copy(targetFeerate = targetFeerate * 2), successA1.commitment, Seq(successA2.sharedTx), walletA)
      val bobRbf = fixtureParams.spawnTxBuilderSpliceRbfBob(fundingParamsB1.copy(targetFeerate = targetFeerate * 2), successB1.commitment, Seq(successB2.sharedTx), walletB)
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
      // Alice <-- commit_sig --- Bob
      fwdRbf.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwdRbf.forwardAlice2Bob[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB3 = bob2alice.expectMsgType[Succeeded]
      aliceRbf ! ReceiveTxSigs(successB3.sharedTx.localSigs)
      // Alice --- tx_signatures --> Bob
      val successA3 = alice2bob.expectMsgType[Succeeded]
      val rbfFeerate = successA3.fundingParams.targetFeerate
      assert(targetFeerate < rbfFeerate)
      val spliceTxA2 = successA3.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(rbfFeerate * 0.9 <= spliceTxA2.feerate && spliceTxA2.feerate <= rbfFeerate * 1.25)
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
    val utxosA = Seq(140_000 sat, 40_000 sat, 35_000 sat)
    val fundingB1 = 80_000 sat
    val utxosB = Seq(110_000 sat, 20_000 sat, 15_000 sat)
    withFixture(fundingA1, utxosA, fundingB1, utxosB, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
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
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB1 = bob2alice.expectMsgType[Succeeded]
      alice ! ReceiveTxSigs(successB1.sharedTx.localSigs)
      val successA1 = alice2bob.expectMsgType[Succeeded]
      val txA1 = successA1.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(targetFeerate * 0.9 <= txA1.feerate && txA1.feerate <= targetFeerate * 1.25)
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.signedTx.txid)

      // Alice and Bob splice some funds in and out, which requires using an additional input for each of them.
      val fundingA2 = fundingA1 + 15_000.sat
      val fundingB2 = fundingB1 + 5_000.sat
      val spliceOutputsA = List(TxOut(20_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val spliceOutputsB = List(TxOut(10_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(successA1.commitment, successB1.commitment)
      val fundingParamsA1 = aliceParams.copy(localAmount = fundingA2, remoteAmount = fundingB2, sharedInput_opt = Some(sharedInputA), localOutputs = spliceOutputsA)
      val fundingParamsB1 = bobParams.copy(localAmount = fundingB2, remoteAmount = fundingA2, sharedInput_opt = Some(sharedInputB), localOutputs = spliceOutputsB)
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, successA1.commitment, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(fundingParamsB1, successB1.commitment, walletB)
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
      // Alice <-- commit_sig --- Bob
      fwdSplice.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwdSplice.forwardAlice2Bob[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB2 = bob2alice.expectMsgType[Succeeded]
      aliceSplice ! ReceiveTxSigs(successB2.sharedTx.localSigs)
      // Alice --- tx_signatures --> Bob
      val successA2 = alice2bob.expectMsgType[Succeeded]
      val spliceTxA1 = successA2.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(targetFeerate * 0.9 <= spliceTxA1.feerate && spliceTxA1.feerate <= targetFeerate * 1.25)
      walletA.publishTransaction(spliceTxA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA1.txId)

      // Alice wants to make a large increase to the feerate of the splice transaction, which requires additional inputs.
      val aliceRbf = fixtureParams.spawnTxBuilderSpliceRbfAlice(fundingParamsA1.copy(targetFeerate = FeeratePerKw(10_000 sat)), successA1.commitment, Seq(successA2.sharedTx), walletA)
      val bobRbf = fixtureParams.spawnTxBuilderSpliceRbfBob(fundingParamsB1.copy(targetFeerate = FeeratePerKw(10_000 sat)), successB1.commitment, Seq(successB2.sharedTx), walletB)
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
      // Alice <-- commit_sig --- Bob
      fwdRbf.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwdRbf.forwardAlice2Bob[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB3 = bob2alice.expectMsgType[Succeeded]
      aliceRbf ! ReceiveTxSigs(successB3.sharedTx.localSigs)
      // Alice --- tx_signatures --> Bob
      val successA3 = alice2bob.expectMsgType[Succeeded]
      val rbfFeerate = successA3.fundingParams.targetFeerate
      assert(targetFeerate < rbfFeerate)
      val spliceTxA2 = successA3.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(rbfFeerate * 0.9 <= spliceTxA2.feerate && spliceTxA2.feerate <= rbfFeerate * 1.25)
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

  test("funding splice transaction with previous inputs (different balance)") {
    val targetFeerate = FeeratePerKw(2_500 sat)
    val fundingA1 = 100_000 sat
    val utxosA = Seq(140_000 sat, 40_000 sat, 35_000 sat)
    val fundingB1 = 80_000 sat
    val utxosB = Seq(110_000 sat, 20_000 sat, 15_000 sat)
    withFixture(fundingA1, utxosA, fundingB1, utxosB, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
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
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB1 = bob2alice.expectMsgType[Succeeded]
      alice ! ReceiveTxSigs(successB1.sharedTx.localSigs)
      val successA1 = alice2bob.expectMsgType[Succeeded]
      val txA1 = successA1.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(targetFeerate * 0.9 <= txA1.feerate && txA1.feerate <= targetFeerate * 1.25)
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.signedTx.txid)

      // Alice splices some funds in, which requires using an additional input.
      val fundingA2 = fundingA1 + 25_000.sat
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(successA1.commitment, successB1.commitment)
      val fundingParamsA1 = aliceParams.copy(localAmount = fundingA2, remoteAmount = fundingB1, sharedInput_opt = Some(sharedInputA))
      val fundingParamsB1 = bobParams.copy(localAmount = fundingB1, remoteAmount = fundingA2, sharedInput_opt = Some(sharedInputB))
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, successA1.commitment, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(fundingParamsB1, successB1.commitment, walletB)
      val fwdSplice = TypeCheckedForwarder(aliceSplice, bobSplice, alice2bob, bob2alice)

      aliceSplice ! Start(alice2bob.ref)
      bobSplice ! Start(bob2alice.ref)

      // Alice --- tx_add_input --> Bob
      fwdSplice.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      fwdSplice.forwardBob2Alice[TxComplete]
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
      // Alice <-- commit_sig --- Bob
      fwdSplice.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwdSplice.forwardAlice2Bob[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB2 = bob2alice.expectMsgType[Succeeded]
      aliceSplice ! ReceiveTxSigs(successB2.sharedTx.localSigs)
      // Alice --- tx_signatures --> Bob
      val successA2 = alice2bob.expectMsgType[Succeeded]
      val spliceTxA1 = successA2.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(targetFeerate * 0.9 <= spliceTxA1.feerate && spliceTxA1.feerate <= targetFeerate * 1.25)
      walletA.publishTransaction(spliceTxA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA1.txId)

      // Alice wants to increase the feerate of the splice transaction and keep the same target local amount, but before
      // that she sent htlcs to Bob which decreased her balance, so she'll need to add another input.
      val initialBalanceA = successA1.commitment.localCommit.spec.toLocal
      val initialBalanceB = successA1.commitment.localCommit.spec.toRemote
      val amountPaid = 25_000 sat
      val commitmentA2 = successA1.commitment
        .modify(_.localCommit.spec.toLocal).setTo(initialBalanceA - amountPaid)
        .modify(_.localCommit.spec.toRemote).setTo(initialBalanceB + amountPaid)
        .modify(_.remoteCommit.spec.toLocal).setTo(initialBalanceB + amountPaid)
        .modify(_.remoteCommit.spec.toRemote).setTo(initialBalanceA - amountPaid)
      val commitmentB2 = successB1.commitment
        .modify(_.localCommit.spec.toLocal).setTo(initialBalanceB + amountPaid)
        .modify(_.localCommit.spec.toRemote).setTo(initialBalanceA - amountPaid)
        .modify(_.remoteCommit.spec.toLocal).setTo(initialBalanceA - amountPaid)
        .modify(_.remoteCommit.spec.toRemote).setTo(initialBalanceB + amountPaid)
      val fundingParamsA2 = fundingParamsA1.copy(targetFeerate = FeeratePerKw(5_000 sat), localAmount = fundingA2, remoteAmount = fundingB1 + amountPaid)
      val fundingParamsB2 = fundingParamsB1.copy(targetFeerate = FeeratePerKw(5_000 sat), localAmount = fundingB1 + amountPaid, remoteAmount = fundingA2)
      val aliceRbf = fixtureParams.spawnTxBuilderSpliceRbfAlice(fundingParamsA2, commitmentA2, Seq(successA2.sharedTx), walletA)
      val bobRbf = fixtureParams.spawnTxBuilderSpliceRbfBob(fundingParamsB2, commitmentB2, Seq(successB2.sharedTx), walletB)
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
      // Alice <-- commit_sig --- Bob
      fwdRbf.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwdRbf.forwardAlice2Bob[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB3 = bob2alice.expectMsgType[Succeeded]
      aliceRbf ! ReceiveTxSigs(successB3.sharedTx.localSigs)
      // Alice --- tx_signatures --> Bob
      val successA3 = alice2bob.expectMsgType[Succeeded]
      val rbfFeerate = successA3.fundingParams.targetFeerate
      assert(targetFeerate < rbfFeerate)
      val spliceTxA2 = successA3.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      walletA.publishTransaction(spliceTxA2.signedTx).pipeTo(probe.ref)
      probe.expectMsg(spliceTxA2.txId)
      walletA.getMempoolTx(spliceTxA2.txId).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == spliceTxA2.tx.fees)
      assert(rbfFeerate * 0.9 <= spliceTxA2.feerate && spliceTxA2.feerate <= rbfFeerate * 1.25)
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
    withFixture(fundingA, utxosA, 0 sat, Nil, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
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
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val txB = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB.localSigs)
      val successA = alice2bob.expectMsgType[Succeeded]
      val txA = successA.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(targetFeerate * 0.9 <= txA.feerate && txA.feerate <= targetFeerate * 1.25)

      val aliceRbf = fixtureParams.spawnTxBuilderRbfAlice(aliceParams.copy(targetFeerate = FeeratePerKw(15_000 sat)), successA.commitment, Seq(txA), walletA)
      aliceRbf ! Start(alice2bob.ref)
      assert(alice2bob.expectMsgType[LocalFailure].cause == ChannelFundingError(aliceParams.channelId))
    }
  }

  test("allow unconfirmed remote inputs") {
    withFixture(120_000 sat, Seq(150_000 sat), 50_000 sat, Seq(100_000 sat), FeeratePerKw(4000 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
      import f._

      // Bob's available utxo is unconfirmed.
      val probe = TestProbe()
      walletB.getReceiveAddress().pipeTo(probe.ref)
      walletB.sendToAddress(probe.expectMsgType[String], 75_000 sat, 1).pipeTo(probe.ref)
      probe.expectMsgType[ByteVector32]

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
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val txB1 = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB1.localSigs)
      val txA1 = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.signedTx.txid)
    }
  }

  test("reject unconfirmed remote inputs") {
    withFixture(120_000 sat, Seq(150_000 sat), 50_000 sat, Seq(100_000 sat), FeeratePerKw(4000 sat), 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = true)) { f =>
      import f._

      // Bob's available utxo is unconfirmed.
      val probe = TestProbe()
      walletB.getReceiveAddress().pipeTo(probe.ref)
      walletB.sendToAddress(probe.expectMsgType[String], 75_000 sat, 1).pipeTo(probe.ref)
      probe.expectMsgType[ByteVector32]

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
    withFixture(500_000 sat, Seq(600_000 sat), 400_000 sat, Seq(450_000 sat), FeeratePerKw(1000 sat), 330 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
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
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB = bob2alice.expectMsgType[Succeeded]
      alice ! ReceiveTxSigs(successB.sharedTx.localSigs)
      val successA = alice2bob.expectMsgType[Succeeded]
      walletA.publishTransaction(successA.sharedTx.signedTx_opt.get).pipeTo(probe.ref)
      probe.expectMsg(successA.sharedTx.txId)

      // Bob splices too much funds out, which makes him drop below the channel reserve.
      val spliceOutputsA = List(TxOut(99_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val spliceOutputsB = List(TxOut(397_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(successA.commitment, successB.commitment)
      val fundingParamsA1 = aliceParams.copy(localAmount = 400_000 sat, remoteAmount = 2000 sat, sharedInput_opt = Some(sharedInputA), localOutputs = spliceOutputsA)
      val fundingParamsB1 = bobParams.copy(localAmount = 2000 sat, remoteAmount = 400_000 sat, sharedInput_opt = Some(sharedInputB), localOutputs = spliceOutputsB)
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, successA.commitment, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(fundingParamsB1, successB.commitment, walletB)
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

  test("invalid tx_signatures (missing shared input signature)") {
    withFixture(150_000 sat, Seq(200_000 sat), 0 sat, Nil, FeeratePerKw(1000 sat), 330 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
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
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB = bob2alice.expectMsgType[Succeeded]
      alice ! ReceiveTxSigs(successB.sharedTx.localSigs)
      val successA = alice2bob.expectMsgType[Succeeded]
      walletA.publishTransaction(successA.sharedTx.signedTx_opt.get).pipeTo(probe.ref)
      probe.expectMsg(successA.sharedTx.txId)

      // Alice splices some funds out, which creates two outputs (a shared output and a splice output).
      val spliceOutputsA = List(TxOut(25_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(successA.commitment, successB.commitment)
      val fundingParamsA1 = aliceParams.copy(localAmount = 120_000 sat, remoteAmount = 0 sat, sharedInput_opt = Some(sharedInputA), localOutputs = spliceOutputsA)
      val fundingParamsB1 = bobParams.copy(localAmount = 0 sat, remoteAmount = 120_000 sat, sharedInput_opt = Some(sharedInputB), localOutputs = Nil)
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, successA.commitment, walletA)
      val bobSplice = fixtureParams.spawnTxBuilderSpliceBob(fundingParamsB1, successB.commitment, walletB)
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
      // Alice --- commit_sig --> Bob
      fwdSplice.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwdSplice.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB1 = bob2alice.expectMsgType[Succeeded]
      aliceSplice ! ReceiveTxSigs(successB1.sharedTx.localSigs.copy(tlvStream = TlvStream.empty))
      assert(alice2bob.expectMsgType[RemoteFailure].cause == InvalidFundingSignature(bobParams.channelId, Some(successB1.sharedTx.txId)))
    }
  }

  test("invalid commitment index") {
    withFixture(150_000 sat, Seq(200_000 sat), 0 sat, Nil, FeeratePerKw(1000 sat), 330 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
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
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB = bob2alice.expectMsgType[Succeeded]
      alice ! ReceiveTxSigs(successB.sharedTx.localSigs)
      val successA = alice2bob.expectMsgType[Succeeded]
      walletA.publishTransaction(successA.sharedTx.signedTx_opt.get).pipeTo(probe.ref)
      probe.expectMsg(successA.sharedTx.txId)

      // Alice splices some funds out, but she doesn't have the same commitment index than Bob.
      val spliceOutputsA = List(TxOut(25_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val (sharedInputA, sharedInputB) = fixtureParams.sharedInputs(successA.commitment, successB.commitment)
      val fundingParamsA1 = aliceParams.copy(localAmount = 120_000 sat, remoteAmount = 0 sat, sharedInput_opt = Some(sharedInputA), localOutputs = spliceOutputsA)
      val fundingParamsB1 = bobParams.copy(localAmount = 0 sat, remoteAmount = 120_000 sat, sharedInput_opt = Some(sharedInputB), localOutputs = Nil)
      val aliceSplice = fixtureParams.spawnTxBuilderSpliceAlice(fundingParamsA1, successA.commitment, walletA)
      val invalidCommitmentB = successB.commitment
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
      // Alice --- commit_sig --> Bob
      fwdSplice.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwdSplice.forwardBob2Alice[CommitSig]
      val failureA = alice2bob.expectMsgType[RemoteFailure].cause
      val failureB = bob2alice.expectMsgType[RemoteFailure].cause
      assert(failureA.isInstanceOf[InvalidCommitmentSignature])
      assert(failureB.isInstanceOf[InvalidCommitmentSignature])
      assert(failureA.asInstanceOf[InvalidCommitmentSignature].txId != failureB.asInstanceOf[InvalidCommitmentSignature].txId)
    }
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
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val testCases = Seq(
      TxAddInput(params.channelId, UInt64(0), Some(previousTx), 0, 0) -> InvalidSerialId(params.channelId, UInt64(0)),
      TxAddInput(params.channelId, UInt64(1), Some(previousTx), 0, 0) -> DuplicateSerialId(params.channelId, UInt64(1)),
      TxAddInput(params.channelId, UInt64(3), Some(previousTx), 0, 0) -> DuplicateInput(params.channelId, UInt64(3), previousTx.txid, 0),
      TxAddInput(params.channelId, UInt64(5), Some(previousTx), 3, 0) -> InputOutOfBounds(params.channelId, UInt64(5), previousTx.txid, 3),
      TxAddInput(params.channelId, UInt64(7), Some(previousTx), 1, 0) -> NonSegwitInput(params.channelId, UInt64(7), previousTx.txid, 1),
      TxAddInput(params.channelId, UInt64(9), Some(previousTx), 2, 0xfffffffeL) -> NonReplaceableInput(params.channelId, UInt64(9), previousTx.txid, 2, 0xfffffffeL),
      TxAddInput(params.channelId, UInt64(9), Some(previousTx), 2, 0xffffffffL) -> NonReplaceableInput(params.channelId, UInt64(9), previousTx.txid, 2, 0xffffffffL),
    )
    testCases.foreach {
      case (input, expected) =>
        val alice = params.spawnTxBuilderAlice(wallet)
        alice ! Start(probe.ref)
        // Alice --- tx_add_input --> Bob
        probe.expectMsgType[SendMessage]
        // Alice <-- tx_add_input --- Bob
        alice ! ReceiveTxMessage(TxAddInput(params.channelId, UInt64(1), Some(previousTx), 0, 0))
        // Alice --- tx_add_output --> Bob
        probe.expectMsgType[SendMessage]
        // Alice <-- tx_add_input --- Bob
        alice ! ReceiveTxMessage(input)
        assert(probe.expectMsgType[RemoteFailure].cause == expected)
    }
  }

  test("allow all output types") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val testCases = Seq(
      TxAddOutput(params.channelId, UInt64(1), 25_000 sat, Script.write(Script.pay2pkh(randomKey().publicKey))),
      TxAddOutput(params.channelId, UInt64(1), 25_000 sat, Script.write(Script.pay2sh(OP_1 :: Nil))),
      TxAddOutput(params.channelId, UInt64(1), 25_000 sat, Script.write(OP_1 :: Nil)),
    )
    testCases.foreach { output =>
      val alice = params.spawnTxBuilderAlice(wallet)
      alice ! Start(probe.ref)
      // Alice --- tx_add_input --> Bob
      probe.expectMsgType[SendMessage]
      // Alice <-- tx_add_output --- Bob
      alice ! ReceiveTxMessage(output)
      // Alice does not send a failure for non-segwit outputs.
      assert(probe.expectMsgType[SendMessage].msg.isInstanceOf[TxAddOutput])
    }
  }

  test("invalid output") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
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
        alice ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(1), 50_000 sat, validScript))
        // Alice --- tx_add_output --> Bob
        probe.expectMsgType[SendMessage]
        // Alice <-- tx_add_output --- Bob
        alice ! ReceiveTxMessage(output)
        assert(probe.expectMsgType[RemoteFailure].cause == expected)
    }
  }

  test("remove unknown input/output") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
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
        alice ! ReceiveTxMessage(msg)
        assert(probe.expectMsgType[RemoteFailure].cause == expected)
    }
  }

  test("too many protocol rounds") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val alice = params.spawnTxBuilderAlice(wallet)
    alice ! Start(probe.ref)
    (1 until InteractiveTxBuilder.MAX_INPUTS_OUTPUTS_RECEIVED).foreach(i => {
      // Alice --- tx_message --> Bob
      probe.expectMsgType[SendMessage]
      alice ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(2 * i + 1), 2500 sat, validScript))
    })
    // Alice --- tx_complete --> Bob
    probe.expectMsgType[SendMessage]
    alice ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(15001), 2500 sat, validScript))
    assert(probe.expectMsgType[RemoteFailure].cause == TooManyInteractiveTxRounds(params.channelId))
  }

  test("too many inputs") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val alice = params.spawnTxBuilderAlice(wallet)
    alice ! Start(probe.ref)
    (1 to 252).foreach(i => {
      // Alice --- tx_message --> Bob
      probe.expectMsgType[SendMessage]
      alice ! ReceiveTxMessage(createInput(params.channelId, UInt64(2 * i + 1), 5000 sat))
    })
    // Alice --- tx_complete --> Bob
    probe.expectMsgType[SendMessage]
    alice ! ReceiveTxMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("too many outputs") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val alice = params.spawnTxBuilderAlice(wallet)
    alice ! Start(probe.ref)
    (1 to 252).foreach(i => {
      // Alice --- tx_message --> Bob
      probe.expectMsgType[SendMessage]
      alice ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(2 * i + 1), 2500 sat, validScript))
    })
    // Alice --- tx_complete --> Bob
    probe.expectMsgType[SendMessage]
    alice ! ReceiveTxMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("missing funding output") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val bob = params.spawnTxBuilderBob(wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveTxMessage(createInput(params.channelId, UInt64(0), 150_000 sat))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(2), 125_000 sat, validScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_complete --> Bob
    bob ! ReceiveTxMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("multiple funding outputs") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val bob = params.spawnTxBuilderBob(wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveTxMessage(createInput(params.channelId, UInt64(0), 150_000 sat))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(2), 100_000 sat, params.fundingParamsB.fundingPubkeyScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(4), 100_000 sat, params.fundingParamsB.fundingPubkeyScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_complete --> Bob
    bob ! ReceiveTxMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("missing shared input") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(1000 sat), 330 sat, 0)
    val commitment = CommitmentsSpec.makeCommitments(250_000_000 msat, 150_000_000 msat).active.head
    val bob = params.spawnTxBuilderSpliceBob(params.fundingParamsB.copy(sharedInput_opt = Some(params.dummySharedInputB(50_000 sat))), commitment, wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveTxMessage(createInput(params.channelId, UInt64(0), 150_000 sat))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(2), params.fundingParamsB.fundingAmount, params.fundingParamsB.fundingPubkeyScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_complete --> Bob
    bob ! ReceiveTxMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("invalid funding amount") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val bob = params.spawnTxBuilderBob(wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveTxMessage(createInput(params.channelId, UInt64(0), 150_000 sat))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(2), 100_001 sat, params.fundingParamsB.fundingPubkeyScript))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidSharedOutputAmount(params.channelId, UInt64(2), 100_001 sat, 100_000 sat))
  }

  test("missing previous tx") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val previousCommitment = CommitmentsSpec.makeCommitments(25_000_000 msat, 50_000_000 msat).active.head
    val fundingParams = params.fundingParamsB.copy(sharedInput_opt = Some(Multisig2of2Input(previousCommitment.commitInput, randomKey().publicKey, randomKey().publicKey)))
    val bob = params.spawnTxBuilderSpliceBob(fundingParams, previousCommitment, wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    // The input doesn't include the previous transaction but is not the shared input.
    val nonSharedInput = TxAddInput(params.channelId, UInt64(0), OutPoint(randomBytes32(), 7), 0)
    bob ! ReceiveTxMessage(nonSharedInput)
    assert(probe.expectMsgType[RemoteFailure].cause == PreviousTxMissing(params.channelId, UInt64(0)))
  }

  test("invalid shared input") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val previousCommitment = CommitmentsSpec.makeCommitments(25_000_000 msat, 50_000_000 msat).active.head
    val fundingTx = Transaction(2, Nil, Seq(TxOut(50_000 sat, Script.pay2wpkh(randomKey().publicKey)), TxOut(20_000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
    val sharedInput = Multisig2of2Input(InputInfo(OutPoint(fundingTx, 0), fundingTx.txOut.head, Nil), randomKey().publicKey, randomKey().publicKey)
    val bob = params.spawnTxBuilderSpliceBob(params.fundingParamsB.copy(sharedInput_opt = Some(sharedInput)), previousCommitment, wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    // The shared input isn't allowed to include a full previous transaction, it must use the dedicated tlv.
    val invalidSharedInput = TxAddInput(params.channelId, UInt64(0), Some(fundingTx), 0, 0)
    bob ! ReceiveTxMessage(invalidSharedInput)
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidSharedInput(params.channelId, UInt64(0)))
  }

  test("total input amount too low") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val bob = params.spawnTxBuilderBob(wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveTxMessage(createInput(params.channelId, UInt64(0), 150_000 sat))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(2), 100_000 sat, params.fundingParamsB.fundingPubkeyScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(4), 51_000 sat, validScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_complete --> Bob
    bob ! ReceiveTxMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("minimum fee not met") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val bob = params.spawnTxBuilderBob(wallet)
    bob ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveTxMessage(createInput(params.channelId, UInt64(0), 150_000 sat))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(2), 100_000 sat, params.fundingParamsB.fundingPubkeyScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(4), 49_999 sat, validScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_complete --> Bob
    bob ! ReceiveTxMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("previous attempts not double-spent") {
    val targetFeerate = FeeratePerKw(7500 sat)
    val fundingA = 85_000 sat
    val utxosA = Seq(120_000 sat)
    withFixture(fundingA, utxosA, 0 sat, Nil, targetFeerate, 660 sat, 0, RequireConfirmedInputs(forLocal = false, forRemote = false)) { f =>
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
      // Alice --- commit_sig --> Bob
      fwd.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      fwd.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val successB1 = bob2alice.expectMsgType[Succeeded]
      val txB1 = successB1.sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB1.localSigs)
      val successA1 = alice2bob.expectMsgType[Succeeded]
      val txA1 = successA1.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(targetFeerate * 0.9 <= txA1.feerate && txA1.feerate <= targetFeerate * 1.25)
      val probe = TestProbe()
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.signedTx.txid)

      // we modify remote's input in previous txs, it won't be double spent
      val fakeTxB2 = txB1.modify(_.tx.remoteInputs.at(0).outPoint.hash).setTo(randomBytes32())

      val aliceRbf = fixtureParams.spawnTxBuilderRbfAlice(aliceParams.copy(targetFeerate = FeeratePerKw(10_000 sat)), successA1.commitment, Seq(txA1), walletA)
      val bobRbf = fixtureParams.spawnTxBuilderRbfBob(bobParams.copy(targetFeerate = FeeratePerKw(10_000 sat)), successB1.commitment, Seq(txB1, fakeTxB2), walletB)
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
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val alice = params.spawnTxBuilderAlice(wallet)
    alice ! Start(probe.ref)
    // Alice --- tx_add_input --> Bob
    probe.expectMsgType[SendMessage]
    alice ! ReceiveTxMessage(TxComplete(params.channelId))
    // Alice --- tx_add_output --> Bob
    probe.expectMsgType[SendMessage]
    alice ! ReceiveTxMessage(TxComplete(params.channelId))
    // Alice --- tx_add_output --> Bob
    probe.expectMsgType[SendMessage]
    alice ! ReceiveTxMessage(TxComplete(params.channelId))
    // Alice --- tx_complete --> Bob
    assert(probe.expectMsgType[SendMessage].msg.isInstanceOf[TxComplete])
    // Alice --- commit_sig --> Bob
    assert(probe.expectMsgType[SendMessage].msg.isInstanceOf[CommitSig])
    // Alice <-- commit_sig --- Bob
    alice ! ReceiveCommitSig(CommitSig(params.channelId, ByteVector64.Zeroes, Nil))
    assert(probe.expectMsgType[RemoteFailure].cause.isInstanceOf[InvalidCommitmentSignature])
  }

  test("receive tx_signatures before commit_sig") {
    val (alice2bob, bob2alice) = (TestProbe(), TestProbe())
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val alice = params.spawnTxBuilderAlice(wallet)
    val bob = params.spawnTxBuilderBob(wallet)
    alice ! Start(alice2bob.ref)
    bob ! Start(bob2alice.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveTxMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddInput])
    alice ! ReceiveTxMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete])
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    alice ! ReceiveTxMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete])
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    alice ! ReceiveTxMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete])
    // Alice --- tx_complete --> Bob
    bob ! ReceiveTxMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete])
    // Alice <-- commit_sig --> Bob
    assert(bob2alice.expectMsgType[SendMessage].msg.isInstanceOf[CommitSig]) // alice does *not* receive bob's commit_sig
    bob ! ReceiveCommitSig(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[CommitSig])
    // Alice <-- tx_signatures --- Bob
    alice ! ReceiveTxSigs(bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction].localSigs)
    assert(alice2bob.expectMsgType[RemoteFailure].cause == UnexpectedFundingSignatures(params.channelId))
  }

  test("invalid tx_signatures") {
    val (alice2bob, bob2alice) = (TestProbe(), TestProbe())
    val wallet = new SingleKeyOnChainWallet()
    val params = createFixtureParams(100_000 sat, 25_000 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val alice = params.spawnTxBuilderAlice(wallet)
    val bob = params.spawnTxBuilderBob(wallet)
    alice ! Start(alice2bob.ref)
    bob ! Start(bob2alice.ref)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveTxMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddInput])
    alice ! ReceiveTxMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxAddInput])
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    alice ! ReceiveTxMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxAddOutput])
    alice ! ReceiveTxMessage(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete])
    // Alice --- tx_complete --> Bob
    bob ! ReceiveTxMessage(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[TxComplete])
    // Alice <-- commit_sig --> Bob
    alice ! ReceiveCommitSig(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[CommitSig])
    bob ! ReceiveCommitSig(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[CommitSig])
    // Alice <-- tx_signatures --- Bob
    val bobSigs = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction].localSigs
    alice ! ReceiveTxSigs(bobSigs.copy(witnesses = Seq(Script.witnessPay2wpkh(randomKey().publicKey, ByteVector.fill(73)(0)))))
    assert(alice2bob.expectMsgType[RemoteFailure].cause.isInstanceOf[InvalidFundingSignature])
  }

  test("reference test vector") {
    val channelId = ByteVector32.Zeroes
    val parentTx = Transaction.read("02000000000101f86fd1d0db3ac5a72df968622f31e6b5e6566a09e29206d7c7a55df90e181de800000000171600141fb9623ffd0d422eacc450fd1e967efc477b83ccffffffff0580b2e60e00000000220020fd89acf65485df89797d9ba7ba7a33624ac4452f00db08107f34257d33e5b94680b2e60e0000000017a9146a235d064786b49e7043e4a042d4cc429f7eb6948780b2e60e00000000160014fbb4db9d85fba5e301f4399e3038928e44e37d3280b2e60e0000000017a9147ecd1b519326bc13b0ec716e469b58ed02b112a087f0006bee0000000017a914f856a70093da3a5b5c4302ade033d4c2171705d387024730440220696f6cee2929f1feb3fd6adf024ca0f9aa2f4920ed6d35fb9ec5b78c8408475302201641afae11242160101c6f9932aeb4fcd1f13a9c6df5d1386def000ea259a35001210381d7d5b1bc0d7600565d827242576d9cb793bfe0754334af82289ee8b65d137600000000")
    val sharedOutput = Output.Shared(UInt64(44), hex"0020297b92c238163e820b82486084634b4846b86a3c658d87b9384192e6bea98ec5", 200_000_000 sat, 200_000_000 sat)
    val initiatorTx = {
      val initiatorInput = Input.Local(UInt64(20), parentTx, 0, 4294967293L)
      val initiatorOutput = Output.Local.Change(UInt64(30), 49_999_845 sat, hex"00141ca1cca8855bad6bc1ea5436edd8cff10b7e448b")
      val nonInitiatorInput = Input.Remote(UInt64(11), OutPoint(parentTx, 2), parentTx.txOut(2), 4294967293L)
      val nonInitiatorOutput = Output.Remote(UInt64(33), 49_999_900 sat, hex"001444cb0c39f93ecc372b5851725bd29d865d333b10")
      SharedTransaction(None, sharedOutput, List(initiatorInput), List(nonInitiatorInput), List(initiatorOutput), List(nonInitiatorOutput), lockTime = 120)
    }
    assert(initiatorTx.localFees == 155.sat)
    assert(initiatorTx.remoteFees == 100.sat)

    val nonInitiatorTx = {
      val initiatorInput = Input.Remote(UInt64(20), OutPoint(parentTx, 0), parentTx.txOut(0), 4294967293L)
      val initiatorOutput = Output.Remote(UInt64(30), 49_999_845 sat, hex"00141ca1cca8855bad6bc1ea5436edd8cff10b7e448b")
      val nonInitiatorInput = Input.Local(UInt64(11), parentTx, 2, 4294967293L)
      val nonInitiatorOutput = Output.Local.Change(UInt64(33), 49_999_900 sat, hex"001444cb0c39f93ecc372b5851725bd29d865d333b10")
      SharedTransaction(None, sharedOutput, List(nonInitiatorInput), List(initiatorInput), List(nonInitiatorOutput), List(initiatorOutput), lockTime = 120)
    }
    assert(nonInitiatorTx.localFees == 100.sat)
    assert(nonInitiatorTx.remoteFees == 155.sat)

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
