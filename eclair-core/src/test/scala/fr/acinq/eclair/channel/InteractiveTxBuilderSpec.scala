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
import akka.util.BoxedType
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Satoshi, SatoshiLong, Script, Transaction, TxOut}
import fr.acinq.eclair.blockchain.OnChainWallet.{FundTransactionResponse, SignTransactionResponse}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.{MempoolTx, Utxo}
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BitcoinCoreClient, BitcoinJsonRPCClient}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{OnChainWallet, SingleKeyOnChainWallet}
import fr.acinq.eclair.channel.InteractiveTxBuilder._
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Feature, FeatureSupport, Features, InitFeature, NodeParams, TestConstants, TestKitBaseClass, UInt64, randomBytes32, randomKey}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.ByteVector

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
    TxAddInput(channelId, serialId, previousTx, 1, 0)
  }

  case class ChannelParams(fundingParamsA: InteractiveTxParams,
                           nodeParamsA: NodeParams,
                           localParamsA: LocalParams,
                           remoteParamsA: RemoteParams,
                           firstPerCommitmentPointA: PublicKey,
                           fundingParamsB: InteractiveTxParams,
                           nodeParamsB: NodeParams,
                           localParamsB: LocalParams,
                           remoteParamsB: RemoteParams,
                           firstPerCommitmentPointB: PublicKey,
                           channelFeatures: ChannelFeatures) {
    val channelId = fundingParamsA.channelId

    def spawnTxBuilderAlice(fundingParams: InteractiveTxParams, commitFeerate: FeeratePerKw, wallet: OnChainWallet): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      nodeParamsA.nodeId,
      fundingParams, nodeParamsA.channelKeyManager,
      localParamsA, remoteParamsB,
      commitFeerate, firstPerCommitmentPointB,
      ChannelFlags.Public, ChannelConfig.standard, channelFeatures, wallet))

    def spawnTxBuilderBob(fundingParams: InteractiveTxParams, commitFeerate: FeeratePerKw, wallet: OnChainWallet): ActorRef[InteractiveTxBuilder.Command] = system.spawnAnonymous(InteractiveTxBuilder(
      nodeParamsB.nodeId,
      fundingParams, nodeParamsB.channelKeyManager,
      localParamsB, remoteParamsA,
      commitFeerate, firstPerCommitmentPointA,
      ChannelFlags.Public, ChannelConfig.standard, channelFeatures, wallet))
  }

  private def createChannelParams(fundingAmountA: Satoshi, fundingAmountB: Satoshi, targetFeerate: FeeratePerKw, dustLimit: Satoshi, lockTime: Long): ChannelParams = {
    val channelFeatures = ChannelFeatures(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = false, zeroConf = false), Features[InitFeature](Features.DualFunding -> FeatureSupport.Optional), Features[InitFeature](Features.DualFunding -> FeatureSupport.Optional), announceChannel = true)
    val Seq(nodeParamsA, nodeParamsB) = Seq(TestConstants.Alice.nodeParams, TestConstants.Bob.nodeParams).map(_.copy(features = Features(channelFeatures.features.map(f => f -> FeatureSupport.Optional).toMap[Feature, FeatureSupport])))
    val localParamsA = Peer.makeChannelParams(nodeParamsA, nodeParamsA.features.initFeatures(), ByteVector.empty, None, isInitiator = true, fundingAmountA)
    val localParamsB = Peer.makeChannelParams(nodeParamsB, nodeParamsB.features.initFeatures(), ByteVector.empty, None, isInitiator = false, fundingAmountB)

    val Seq(remoteParamsA, remoteParamsB) = Seq((nodeParamsA, localParamsA), (nodeParamsB, localParamsB)).map {
      case (nodeParams, localParams) =>
        val channelKeyPath = nodeParams.channelKeyManager.keyPath(localParams, ChannelConfig.standard)
        RemoteParams(
          nodeParams.nodeId,
          localParams.dustLimit, localParams.maxHtlcValueInFlightMsat, None, localParams.htlcMinimum, localParams.toSelfDelay, localParams.maxAcceptedHtlcs,
          nodeParams.channelKeyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey,
          nodeParams.channelKeyManager.revocationPoint(channelKeyPath).publicKey,
          nodeParams.channelKeyManager.paymentPoint(channelKeyPath).publicKey,
          nodeParams.channelKeyManager.delayedPaymentPoint(channelKeyPath).publicKey,
          nodeParams.channelKeyManager.htlcPoint(channelKeyPath).publicKey,
          localParams.initFeatures,
          None)
    }

    val firstPerCommitmentPointA = nodeParamsA.channelKeyManager.commitmentPoint(nodeParamsA.channelKeyManager.keyPath(localParamsA, ChannelConfig.standard), 0)
    val firstPerCommitmentPointB = nodeParamsB.channelKeyManager.commitmentPoint(nodeParamsB.channelKeyManager.keyPath(localParamsB, ChannelConfig.standard), 0)

    val channelId = randomBytes32()
    val fundingScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(remoteParamsA.fundingPubKey, remoteParamsB.fundingPubKey)))
    val fundingParamsA = InteractiveTxParams(channelId, isInitiator = true, fundingAmountA, fundingAmountB, fundingScript, lockTime, dustLimit, targetFeerate)
    val fundingParamsB = InteractiveTxParams(channelId, isInitiator = false, fundingAmountB, fundingAmountA, fundingScript, lockTime, dustLimit, targetFeerate)
    ChannelParams(fundingParamsA, nodeParamsA, localParamsA, remoteParamsA, firstPerCommitmentPointA, fundingParamsB, nodeParamsB, localParamsB, remoteParamsB, firstPerCommitmentPointB, channelFeatures)
  }

  case class Fixture(alice: ActorRef[InteractiveTxBuilder.Command],
                     bob: ActorRef[InteractiveTxBuilder.Command],
                     aliceRbf: ActorRef[InteractiveTxBuilder.Command],
                     bobRbf: ActorRef[InteractiveTxBuilder.Command],
                     aliceParams: InteractiveTxParams,
                     bobParams: InteractiveTxParams,
                     walletA: OnChainWallet,
                     rpcClientA: BitcoinJsonRPCClient,
                     walletB: OnChainWallet,
                     rpcClientB: BitcoinJsonRPCClient,
                     alice2bob: TestProbe,
                     bob2alice: TestProbe) {
    def forwardAlice2Bob[T <: LightningMessage](implicit t: ClassTag[T]): T = forwardMessage(alice2bob, bob)

    def forwardRbfAlice2Bob[T <: LightningMessage](implicit t: ClassTag[T]): T = forwardMessage(alice2bob, bobRbf)

    def forwardBob2Alice[T <: LightningMessage](implicit t: ClassTag[T]): T = forwardMessage(bob2alice, alice)

    def forwardRbfBob2Alice[T <: LightningMessage](implicit t: ClassTag[T]): T = forwardMessage(bob2alice, aliceRbf)

    private def forwardMessage[T <: LightningMessage](s2r: TestProbe, r: ActorRef[InteractiveTxBuilder.Command])(implicit t: ClassTag[T]): T = {
      val msg = s2r.expectMsgType[SendMessage].msg
      val c = t.runtimeClass.asInstanceOf[Class[T]]
      assert(BoxedType(c).isInstance(msg), s"expected $c, found ${msg.getClass} ($msg)")
      msg match {
        case msg: InteractiveTxConstructionMessage => r ! ReceiveTxMessage(msg)
        case msg: CommitSig => r ! ReceiveCommitSig(msg)
        case msg: TxSignatures => r ! ReceiveTxSigs(msg)
        case msg => fail(s"invalid message sent ($msg)")
      }
      msg.asInstanceOf[T]
    }
  }

  private def withFixture(fundingAmountA: Satoshi, utxosA: Seq[Satoshi], fundingAmountB: Satoshi, utxosB: Seq[Satoshi], targetFeerate: FeeratePerKw, dustLimit: Satoshi, lockTime: Long)(testFun: Fixture => Any): Unit = {
    // Initialize wallets with a few confirmed utxos.
    val probe = TestProbe()
    val rpcClientA = createWallet(UUID.randomUUID().toString)
    val walletA = new BitcoinCoreClient(rpcClientA)
    utxosA.foreach(amount => addUtxo(walletA, amount, probe))
    val rpcClientB = createWallet(UUID.randomUUID().toString)
    val walletB = new BitcoinCoreClient(rpcClientB)
    utxosB.foreach(amount => addUtxo(walletB, amount, probe))
    generateBlocks(1)

    val channelParams = createChannelParams(fundingAmountA, fundingAmountB, targetFeerate, dustLimit, lockTime)
    val commitFeerate = TestConstants.anchorOutputsFeeratePerKw
    val alice = channelParams.spawnTxBuilderAlice(channelParams.fundingParamsA, commitFeerate, walletA)
    val aliceRbf = channelParams.spawnTxBuilderAlice(channelParams.fundingParamsA.copy(targetFeerate = targetFeerate * 1.5), commitFeerate, walletA)
    val bob = channelParams.spawnTxBuilderBob(channelParams.fundingParamsB, commitFeerate, walletB)
    val bobRbf = channelParams.spawnTxBuilderBob(channelParams.fundingParamsB.copy(targetFeerate = targetFeerate * 1.5), commitFeerate, walletB)
    testFun(Fixture(alice, bob, aliceRbf, bobRbf, channelParams.fundingParamsA, channelParams.fundingParamsB, walletA, rpcClientA, walletB, rpcClientB, TestProbe(), TestProbe()))
  }

  test("initiator contributes more than non-initiator") {
    val targetFeerate = FeeratePerKw(5000 sat)
    val fundingA = 120_000 sat
    val utxosA = Seq(50_000 sat, 35_000 sat, 60_000 sat)
    val fundingB = 40_000 sat
    val utxosB = Seq(100_000 sat)
    withFixture(fundingA, utxosA, fundingB, utxosB, targetFeerate, 660 sat, 42) { f =>
      import f._

      alice ! Start(alice2bob.ref, Nil)
      bob ! Start(bob2alice.ref, Nil)

      // Bob waits for Alice to send the first message.
      bob2alice.expectNoMessage(100 millis)
      // Alice --- tx_add_input --> Bob
      val inputA1 = f.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      val inputB1 = f.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_input --> Bob
      val inputA2 = f.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_output --- Bob
      val outputB1 = f.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_input --> Bob
      val inputA3 = f.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      val outputA1 = f.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      val outputA2 = f.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      f.forwardAlice2Bob[TxComplete]

      // Utxos are locked for the duration of the protocol.
      val probe = TestProbe()
      val locksA = getLocks(probe, rpcClientA)
      assert(locksA.size == 3)
      assert(locksA == Set(inputA1, inputA2, inputA3).map(toOutPoint))
      val locksB = getLocks(probe, rpcClientB)
      assert(locksB.size == 1)
      assert(locksB == Set(toOutPoint(inputB1)))

      // Alice is responsible for adding the shared output.
      assert(aliceParams.fundingPubkeyScript == bobParams.fundingPubkeyScript)
      assert(aliceParams.fundingAmount == 160_000.sat)
      assert(Seq(outputA1, outputA2).count(_.pubkeyScript == aliceParams.fundingPubkeyScript) == 1)
      assert(Seq(outputA1, outputA2).exists(o => o.pubkeyScript == aliceParams.fundingPubkeyScript && o.amount == aliceParams.fundingAmount))
      assert(outputB1.pubkeyScript != aliceParams.fundingPubkeyScript)

      // Bob sends signatures first as he contributed less than Alice.
      f.forwardBob2Alice[CommitSig]
      f.forwardAlice2Bob[CommitSig]
      val txB = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB.localSigs)
      val txA = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]

      // The resulting transaction is valid and has the right feerate.
      assert(txA.signedTx.txid == txB.tx.buildUnsignedTx().txid)
      assert(txA.signedTx.lockTime == aliceParams.lockTime)
      assert(txA.tx.localAmountIn == utxosA.sum)
      assert(txA.tx.remoteAmountIn == utxosB.sum)
      assert(0.sat < txB.tx.localFees(bobParams))
      assert(txB.tx.localFees(bobParams) < txA.tx.localFees(aliceParams))
      walletA.publishTransaction(txA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA.signedTx.txid)
      new BitcoinCoreClient(rpcClientA).getMempoolTx(txA.signedTx.txid).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == txA.tx.fees)
      assert(txA.tx.fees == txB.tx.fees)
      assert(targetFeerate <= txA.feerate && txA.feerate <= targetFeerate * 1.25, s"unexpected feerate (target=$targetFeerate actual=${txA.feerate})")
    }
  }

  test("initiator contributes less than non-initiator") {
    val targetFeerate = FeeratePerKw(3000 sat)
    val fundingA = 10_000 sat
    val utxosA = Seq(50_000 sat)
    val fundingB = 50_000 sat
    val utxosB = Seq(80_000 sat)
    withFixture(fundingA, utxosA, fundingB, utxosB, targetFeerate, 660 sat, 0) { f =>
      import f._

      alice ! Start(alice2bob.ref, Nil)
      bob ! Start(bob2alice.ref, Nil)

      // Even though the initiator isn't contributing, they're paying the fees for the common parts of the transaction.
      // Alice --- tx_add_input --> Bob
      f.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      f.forwardBob2Alice[TxAddInput]
      // Alice --- tx_add_output --> Bob
      val outputA1 = f.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      val outputB = f.forwardBob2Alice[TxAddOutput]
      // Alice --- tx_add_output --> Bob
      val outputA2 = f.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      f.forwardAlice2Bob[TxComplete]

      // Alice is responsible for adding the shared output.
      assert(aliceParams.fundingPubkeyScript == bobParams.fundingPubkeyScript)
      assert(aliceParams.fundingAmount == 60_000.sat)
      assert(Seq(outputA1, outputA2).count(_.pubkeyScript == aliceParams.fundingPubkeyScript) == 1)
      assert(Seq(outputA1, outputA2).exists(o => o.pubkeyScript == aliceParams.fundingPubkeyScript && o.amount == aliceParams.fundingAmount))
      assert(outputB.pubkeyScript != aliceParams.fundingPubkeyScript)

      // Alice sends signatures first as she contributed less than Bob.
      f.forwardAlice2Bob[CommitSig]
      f.forwardBob2Alice[CommitSig]
      val txA = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      bob ! ReceiveTxSigs(txA.localSigs)
      val txB = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]

      // The resulting transaction is valid and has the right feerate.
      assert(txB.signedTx.lockTime == aliceParams.lockTime)
      assert(txB.tx.localAmountIn == utxosB.sum)
      assert(txB.tx.remoteAmountIn == utxosA.sum)
      assert(0.sat < txA.tx.localFees(aliceParams))
      assert(0.sat < txB.tx.localFees(bobParams))
      val probe = TestProbe()
      walletB.publishTransaction(txB.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txB.signedTx.txid)
      new BitcoinCoreClient(rpcClientB).getMempoolTx(txB.signedTx.txid).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == txB.tx.fees)
      assert(txA.tx.fees == txB.tx.fees)
      assert(targetFeerate <= txB.feerate && txB.feerate <= targetFeerate * 1.25, s"unexpected feerate (target=$targetFeerate actual=${txB.feerate})")
    }
  }

  test("non-initiator does not contribute") {
    val targetFeerate = FeeratePerKw(2500 sat)
    val fundingA = 150_000 sat
    val utxosA = Seq(80_000 sat, 120_000 sat)
    withFixture(fundingA, utxosA, 0 sat, Nil, targetFeerate, 660 sat, 0) { f =>
      import f._

      alice ! Start(alice2bob.ref, Nil)
      bob ! Start(bob2alice.ref, Nil)

      // Alice --- tx_add_input --> Bob
      f.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      f.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      val outputA1 = f.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      val outputA2 = f.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      f.forwardAlice2Bob[TxComplete]

      // Alice is responsible for adding the shared output.
      assert(aliceParams.fundingPubkeyScript == bobParams.fundingPubkeyScript)
      assert(aliceParams.fundingAmount == 150_000.sat)
      assert(Seq(outputA1, outputA2).count(_.pubkeyScript == aliceParams.fundingPubkeyScript) == 1)
      assert(Seq(outputA1, outputA2).exists(o => o.pubkeyScript == aliceParams.fundingPubkeyScript && o.amount == aliceParams.fundingAmount))

      // Bob sends signatures first as he did not contribute at all.
      f.forwardBob2Alice[CommitSig]
      f.forwardAlice2Bob[CommitSig]
      val txB = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB.localSigs)
      val txA = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]

      // The resulting transaction is valid and has the right feerate.
      assert(txA.signedTx.txid == txB.tx.buildUnsignedTx().txid)
      assert(txA.signedTx.lockTime == aliceParams.lockTime)
      assert(txA.tx.localAmountIn == utxosA.sum)
      assert(txA.tx.remoteAmountIn == 0.sat)
      assert(txB.tx.localFees(bobParams) == 0.sat)
      assert(txA.tx.localFees(aliceParams) == txA.tx.fees)
      val probe = TestProbe()
      walletA.publishTransaction(txA.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA.signedTx.txid)
      new BitcoinCoreClient(rpcClientA).getMempoolTx(txA.signedTx.txid).pipeTo(probe.ref)
      val mempoolTx = probe.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == txA.tx.fees)
      assert(targetFeerate <= txA.feerate && txA.feerate <= targetFeerate * 1.25, s"unexpected feerate (target=$targetFeerate actual=${txA.feerate})")
    }
  }

  test("remove input/output") {
    withFixture(100_000 sat, Seq(150_000 sat), 0 sat, Nil, FeeratePerKw(2500 sat), 330 sat, 0) { f =>
      import f._

      alice ! Start(alice2bob.ref, Nil)
      bob ! Start(bob2alice.ref, Nil)

      // In this flow we introduce dummy inputs/outputs from Bob to Alice that are then removed.
      // Alice --- tx_add_input --> Bob
      val inputA = f.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_add_input --- Bob
      bob2alice.expectMsgType[SendMessage] // we override Bob's tx_complete
      alice ! ReceiveTxMessage(TxAddInput(bobParams.channelId, UInt64(1), Transaction(2, Nil, Seq(TxOut(250_000 sat, Script.pay2wpkh(randomKey().publicKey))), 0), 0, 0))
      // Alice --- tx_add_output --> Bob
      f.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_add_output --- Bob
      bob2alice.expectMsgType[SendMessage] // we override Bob's tx_complete
      alice ! ReceiveTxMessage(TxAddOutput(bobParams.channelId, UInt64(3), 250_000 sat, Script.write(Script.pay2wpkh(randomKey().publicKey))))
      // Alice --- tx_add_output --> Bob
      f.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_remove_input --- Bob
      bob2alice.expectMsgType[SendMessage] // we override Bob's tx_complete
      alice ! ReceiveTxMessage(TxRemoveInput(bobParams.channelId, UInt64(1)))
      // Alice --- tx_complete --> Bob
      f.forwardAlice2Bob[TxComplete]
      // Alice <-- tx_remove_output --- Bob
      alice ! ReceiveTxMessage(TxRemoveOutput(bobParams.channelId, UInt64(3)))
      // Alice --- tx_complete --> Bob
      alice2bob.expectMsgType[SendMessage]
      // Alice <-- tx_complete --- Bob
      alice ! ReceiveTxMessage(TxComplete(bobParams.channelId))
      // Alice <-- commit_sig --- Bob
      f.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      f.forwardAlice2Bob[CommitSig]
      val txB = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB.localSigs)
      val txA = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]

      // The resulting transaction doesn't contain Bob's removed inputs and outputs.
      assert(txA.signedTx.txid == txB.tx.buildUnsignedTx().txid)
      assert(txA.signedTx.lockTime == aliceParams.lockTime)
      assert(txA.signedTx.txIn.map(_.outPoint) == Seq(toOutPoint(inputA)))
      assert(txA.signedTx.txOut.length == 2)
      assert(txA.tx.remoteAmountIn == 0.sat)
    }
  }

  test("not enough funds (unusable utxos)") {
    val fundingA = 140_000 sat
    val utxosA = Seq(75_000 sat, 60_000 sat)
    withFixture(fundingA, utxosA, 0 sat, Nil, FeeratePerKw(5000 sat), 660 sat, 0) { f =>
      import f._

      // Add some unusable utxos to Alice's wallet.
      val probe = TestProbe()
      val bitcoinClient = new BitcoinCoreClient(rpcClientA)
      val legacyTxId = {
        // Dual funding disallows non-segwit inputs.
        val legacyAddress = getNewAddress(probe, rpcClientA, Some("legacy"))
        sendToAddress(legacyAddress, 100_000 sat, probe).txid
      }
      val bigTxId = {
        // Dual funding cannot use transactions that exceed 65k bytes.
        walletA.getReceivePubkey().pipeTo(probe.ref)
        val publicKey = probe.expectMsgType[PublicKey]
        val tx = Transaction(2, Nil, TxOut(100_000 sat, Script.pay2wpkh(publicKey)) +: (1 to 2500).map(_ => TxOut(5000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
        val minerWallet = new BitcoinCoreClient(bitcoinrpcclient)
        minerWallet.fundTransaction(tx, FeeratePerKw(500 sat), replaceable = true, lockUtxos = false).pipeTo(probe.ref)
        val unsignedTx = probe.expectMsgType[FundTransactionResponse].tx
        minerWallet.signTransaction(unsignedTx).pipeTo(probe.ref)
        val signedTx = probe.expectMsgType[SignTransactionResponse].tx
        assert(Transaction.write(signedTx).length >= 65_000)
        minerWallet.publishTransaction(signedTx).pipeTo(probe.ref)
        probe.expectMsgType[ByteVector32]
      }
      generateBlocks(1)

      // We verify that all utxos are correctly included in our wallet.
      bitcoinClient.listUnspent().pipeTo(probe.ref)
      val utxos = probe.expectMsgType[Seq[Utxo]]
      assert(utxos.length == 4)
      assert(utxos.exists(_.txid == bigTxId))
      assert(utxos.exists(_.txid == legacyTxId))

      // We can't use some of our utxos, so we don't have enough to fund our channel.
      alice ! Start(alice2bob.ref, Nil)
      assert(alice2bob.expectMsgType[LocalFailure].cause == ChannelFundingError(aliceParams.channelId))
      // Utxos shouldn't be locked after a failure.
      awaitCond(getLocks(probe, rpcClientA).isEmpty, max = 10 seconds, interval = 100 millis)
    }
  }

  test("skip unusable utxos") {
    val fundingA = 140_000 sat
    val utxosA = Seq(55_000 sat, 65_000 sat, 50_000 sat)
    withFixture(fundingA, utxosA, 0 sat, Nil, FeeratePerKw(5000 sat), 660 sat, 0) { f =>
      import f._

      // Add some unusable utxos to Alice's wallet.
      val probe = TestProbe()
      val bitcoinClient = new BitcoinCoreClient(rpcClientA)
      val legacyTxIds = {
        // Dual funding disallows non-segwit inputs.
        val legacyAddress = getNewAddress(probe, rpcClientA, Some("legacy"))
        val tx1 = sendToAddress(legacyAddress, 100_000 sat, probe).txid
        val tx2 = sendToAddress(legacyAddress, 120_000 sat, probe).txid
        Seq(tx1, tx2)
      }
      generateBlocks(1)

      // We verify that all utxos are correctly included in our wallet.
      bitcoinClient.listUnspent().pipeTo(probe.ref)
      val utxos = probe.expectMsgType[Seq[Utxo]]
      assert(utxos.length == 5)
      legacyTxIds.foreach(txid => assert(utxos.exists(_.txid == txid)))

      // If we ignore the unusable utxos, we have enough to fund the channel.
      alice ! Start(alice2bob.ref, Nil)
      bob ! Start(bob2alice.ref, Nil)

      // Alice --- tx_add_input --> Bob
      f.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      f.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      f.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      f.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      f.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      f.forwardAlice2Bob[TxComplete]
      // Alice <-- commit_sig --- Bob
      f.forwardBob2Alice[CommitSig]
      // Alice --- commit_sig --> Bob
      f.forwardAlice2Bob[CommitSig]
      val txB = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB.localSigs)
      val txA = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]

      // Unusable utxos should be skipped.
      legacyTxIds.foreach(txid => assert(!txA.signedTx.txIn.exists(_.outPoint.txid == txid)))
      // Only used utxos should be locked.
      awaitCond({
        val locks = getLocks(probe, rpcClientA)
        locks == txA.signedTx.txIn.map(_.outPoint).toSet
      }, max = 10 seconds, interval = 100 millis)
    }
  }

  test("fund transaction with previous inputs (no new input)") {
    val targetFeerate = FeeratePerKw(7500 sat)
    val fundingA = 85_000 sat
    val utxosA = Seq(120_000 sat)
    withFixture(fundingA, utxosA, 0 sat, Nil, targetFeerate, 660 sat, 0) { f =>
      import f._

      alice ! Start(alice2bob.ref, Nil)
      bob ! Start(bob2alice.ref, Nil)

      // Alice --- tx_add_input --> Bob
      val inputA1 = f.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      f.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      f.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      f.forwardAlice2Bob[TxComplete]
      // Alice --- commit_sig --> Bob
      f.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      f.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val txB1 = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB1.localSigs)
      val txA1 = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(targetFeerate * 0.9 <= txA1.feerate && txA1.feerate <= targetFeerate * 1.25)
      val probe = TestProbe()
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.signedTx.txid)

      aliceRbf ! Start(alice2bob.ref, Seq(txA1))
      bobRbf ! Start(bob2alice.ref, Nil)

      // Alice --- tx_add_input --> Bob
      val inputA2 = f.forwardRbfAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      f.forwardRbfBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      f.forwardRbfAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardRbfBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      f.forwardRbfAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardRbfBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      f.forwardRbfAlice2Bob[TxComplete]
      // Alice --- commit_sig --> Bob
      f.forwardRbfAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      f.forwardRbfBob2Alice[CommitSig]
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
    withFixture(fundingA, utxosA, 0 sat, Nil, targetFeerate, 660 sat, 0) { f =>
      import f._

      alice ! Start(alice2bob.ref, Nil)
      bob ! Start(bob2alice.ref, Nil)

      // Alice --- tx_add_input --> Bob
      val inputA1 = f.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      val inputA2 = f.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      f.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      f.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      f.forwardAlice2Bob[TxComplete]
      // Alice --- commit_sig --> Bob
      f.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      f.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val txB1 = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB1.localSigs)
      val txA1 = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(targetFeerate * 0.9 <= txA1.feerate && txA1.feerate <= targetFeerate * 1.25)
      val probe = TestProbe()
      walletA.publishTransaction(txA1.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA1.signedTx.txid)

      aliceRbf ! Start(alice2bob.ref, Seq(txA1))
      bobRbf ! Start(bob2alice.ref, Nil)

      // Alice --- tx_add_input --> Bob
      val inputA3 = f.forwardRbfAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      f.forwardRbfBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      val inputA4 = f.forwardRbfAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      f.forwardRbfBob2Alice[TxComplete]
      // Alice --- tx_add_input --> Bob
      val inputA5 = f.forwardRbfAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      f.forwardRbfBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      f.forwardRbfAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardRbfBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      f.forwardRbfAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardRbfBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      f.forwardRbfAlice2Bob[TxComplete]
      // Alice --- commit_sig --> Bob
      f.forwardRbfAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      f.forwardRbfBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val txB2 = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      aliceRbf ! ReceiveTxSigs(txB2.localSigs)
      val succeeded = alice2bob.expectMsgType[Succeeded]
      val rbfFeerate = succeeded.fundingParams.targetFeerate
      assert(targetFeerate < rbfFeerate)
      val txA2 = succeeded.sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(rbfFeerate * 0.9 <= txA2.feerate && txA2.feerate <= rbfFeerate * 1.25)
      Seq(inputA1, inputA2).foreach(i => assert(Set(inputA3, inputA4, inputA5).contains(i)))
      assert(txA1.signedTx.txid != txA2.signedTx.txid)
      assert(txA1.signedTx.txIn.length + 1 == txA2.signedTx.txIn.length)
      assert(txA1.tx.fees < txA2.tx.fees)
      walletA.publishTransaction(txA2.signedTx).pipeTo(probe.ref)
      probe.expectMsg(txA2.signedTx.txid)
    }
  }

  test("not enough funds for rbf attempt") {
    val targetFeerate = FeeratePerKw(10_000 sat)
    val fundingA = 80_000 sat
    val utxosA = Seq(85_000 sat)
    withFixture(fundingA, utxosA, 0 sat, Nil, targetFeerate, 660 sat, 0) { f =>
      import f._

      alice ! Start(alice2bob.ref, Nil)
      bob ! Start(bob2alice.ref, Nil)

      // Alice --- tx_add_input --> Bob
      f.forwardAlice2Bob[TxAddInput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_add_output --> Bob
      f.forwardAlice2Bob[TxAddOutput]
      // Alice <-- tx_complete --- Bob
      f.forwardBob2Alice[TxComplete]
      // Alice --- tx_complete --> Bob
      f.forwardAlice2Bob[TxComplete]
      // Alice --- commit_sig --> Bob
      f.forwardAlice2Bob[CommitSig]
      // Alice <-- commit_sig --- Bob
      f.forwardBob2Alice[CommitSig]
      // Alice <-- tx_signatures --- Bob
      val txB = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction]
      alice ! ReceiveTxSigs(txB.localSigs)
      val txA = alice2bob.expectMsgType[Succeeded].sharedTx.asInstanceOf[FullySignedSharedTransaction]
      assert(targetFeerate * 0.9 <= txA.feerate && txA.feerate <= targetFeerate * 1.25)

      aliceRbf ! Start(alice2bob.ref, Seq(txA))
      assert(alice2bob.expectMsgType[LocalFailure].cause == ChannelFundingError(aliceParams.channelId))
    }
  }

  test("invalid input") {
    val probe = TestProbe()
    // Create a transaction with a mix of segwit and non-segwit inputs.
    val previousOutputs = Seq(
      TxOut(2500 sat, Script.pay2wpkh(randomKey().publicKey)),
      TxOut(2500 sat, Script.pay2pkh(randomKey().publicKey)),
    )
    val previousTx = Transaction(2, Nil, previousOutputs, 0)
    val wallet = new SingleKeyOnChainWallet()
    val params = createChannelParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val testCases = Seq(
      TxAddInput(params.channelId, UInt64(0), previousTx, 0, 0) -> InvalidSerialId(params.channelId, UInt64(0)),
      TxAddInput(params.channelId, UInt64(1), previousTx, 0, 0) -> DuplicateSerialId(params.channelId, UInt64(1)),
      TxAddInput(params.channelId, UInt64(3), previousTx, 0, 0) -> DuplicateInput(params.channelId, UInt64(3), previousTx.txid, 0),
      TxAddInput(params.channelId, UInt64(5), previousTx, 2, 0) -> InputOutOfBounds(params.channelId, UInt64(5), previousTx.txid, 2),
      TxAddInput(params.channelId, UInt64(7), previousTx, 1, 0) -> NonSegwitInput(params.channelId, UInt64(7), previousTx.txid, 1),
    )
    testCases.foreach {
      case (input, expected) =>
        val alice = params.spawnTxBuilderAlice(params.fundingParamsA, TestConstants.anchorOutputsFeeratePerKw, wallet)
        alice ! Start(probe.ref, Nil)
        // Alice --- tx_add_input --> Bob
        probe.expectMsgType[SendMessage]
        // Alice <-- tx_add_input --- Bob
        alice ! ReceiveTxMessage(TxAddInput(params.channelId, UInt64(1), previousTx, 0, 0))
        // Alice --- tx_add_output --> Bob
        probe.expectMsgType[SendMessage]
        // Alice <-- tx_add_input --- Bob
        alice ! ReceiveTxMessage(input)
        assert(probe.expectMsgType[RemoteFailure].cause == expected)
    }
  }

  test("invalid output") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createChannelParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val testCases = Seq(
      TxAddOutput(params.channelId, UInt64(0), 25_000 sat, validScript) -> InvalidSerialId(params.channelId, UInt64(0)),
      TxAddOutput(params.channelId, UInt64(1), 45_000 sat, validScript) -> DuplicateSerialId(params.channelId, UInt64(1)),
      TxAddOutput(params.channelId, UInt64(3), 329 sat, validScript) -> OutputBelowDust(params.channelId, UInt64(3), 329 sat, 330 sat),
      TxAddOutput(params.channelId, UInt64(5), 45_000 sat, Script.write(Script.pay2pkh(randomKey().publicKey))) -> NonSegwitOutput(params.channelId, UInt64(5)),
    )
    testCases.foreach {
      case (output, expected) =>
        val alice = params.spawnTxBuilderAlice(params.fundingParamsA, TestConstants.anchorOutputsFeeratePerKw, wallet)
        alice ! Start(probe.ref, Nil)
        // Alice --- tx_add_input --> Bob
        probe.expectMsgType[SendMessage]
        // Alice <-- tx_add_output --- Bob
        alice ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(1), 50_000 sat, validScript))
        // Alice --- tx_add_output --> Bob
        probe.expectMsgType[SendMessage]
        // Alice <-- tx_add_input --- Bob
        alice ! ReceiveTxMessage(output)
        assert(probe.expectMsgType[RemoteFailure].cause == expected)
    }
  }

  test("remove unknown input/output") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createChannelParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val testCases = Seq(
      TxRemoveOutput(params.channelId, UInt64(53)) -> UnknownSerialId(params.channelId, UInt64(53)),
      TxRemoveInput(params.channelId, UInt64(57)) -> UnknownSerialId(params.channelId, UInt64(57)),
    )
    testCases.foreach {
      case (msg, expected) =>
        val alice = params.spawnTxBuilderAlice(params.fundingParamsA, TestConstants.anchorOutputsFeeratePerKw, wallet)
        alice ! Start(probe.ref, Nil)
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
    val params = createChannelParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val alice = params.spawnTxBuilderAlice(params.fundingParamsA, TestConstants.anchorOutputsFeeratePerKw, wallet)
    alice ! Start(probe.ref, Nil)
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
    val params = createChannelParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val alice = params.spawnTxBuilderAlice(params.fundingParamsA, TestConstants.anchorOutputsFeeratePerKw, wallet)
    alice ! Start(probe.ref, Nil)
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
    val params = createChannelParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val alice = params.spawnTxBuilderAlice(params.fundingParamsA, TestConstants.anchorOutputsFeeratePerKw, wallet)
    alice ! Start(probe.ref, Nil)
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
    val params = createChannelParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val bob = params.spawnTxBuilderBob(params.fundingParamsB, TestConstants.anchorOutputsFeeratePerKw, wallet)
    bob ! Start(probe.ref, Nil)
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
    val params = createChannelParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val bob = params.spawnTxBuilderBob(params.fundingParamsB, TestConstants.anchorOutputsFeeratePerKw, wallet)
    bob ! Start(probe.ref, Nil)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveTxMessage(createInput(params.channelId, UInt64(0), 150_000 sat))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(2), 100_000 sat, params.fundingParamsB.fundingPubkeyScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(4), 25_000 sat, params.fundingParamsB.fundingPubkeyScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_complete --> Bob
    bob ! ReceiveTxMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("invalid funding amount") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createChannelParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val bob = params.spawnTxBuilderBob(params.fundingParamsB, TestConstants.anchorOutputsFeeratePerKw, wallet)
    bob ! Start(probe.ref, Nil)
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveTxMessage(createInput(params.channelId, UInt64(0), 150_000 sat))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(2), 100_001 sat, params.fundingParamsB.fundingPubkeyScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_complete --> Bob
    bob ! ReceiveTxMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("total input amount too low") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createChannelParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val bob = params.spawnTxBuilderBob(params.fundingParamsB, TestConstants.anchorOutputsFeeratePerKw, wallet)
    bob ! Start(probe.ref, Nil)
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
    val params = createChannelParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val bob = params.spawnTxBuilderBob(params.fundingParamsB, TestConstants.anchorOutputsFeeratePerKw, wallet)
    bob ! Start(probe.ref, Nil)
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
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createChannelParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val firstAttempt = PartiallySignedSharedTransaction(SharedTransaction(Seq(createInput(params.channelId, UInt64(2), 125_000 sat)), Nil, Nil, Nil, 0), null)
    val secondAttempt = PartiallySignedSharedTransaction(SharedTransaction(firstAttempt.tx.localInputs :+ createInput(params.channelId, UInt64(4), 150_000 sat), Nil, Nil, Nil, 0), null)
    val bob = params.spawnTxBuilderBob(params.fundingParamsB, TestConstants.anchorOutputsFeeratePerKw, wallet)
    bob ! Start(probe.ref, Seq(firstAttempt, secondAttempt))
    // Alice --- tx_add_input --> Bob
    bob ! ReceiveTxMessage(secondAttempt.tx.localInputs.last)
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(10), 100_000 sat, params.fundingParamsB.fundingPubkeyScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_add_output --> Bob
    bob ! ReceiveTxMessage(TxAddOutput(params.channelId, UInt64(12), 25_000 sat, validScript))
    // Alice <-- tx_complete --- Bob
    probe.expectMsgType[SendMessage]
    // Alice --- tx_complete --> Bob
    bob ! ReceiveTxMessage(TxComplete(params.channelId))
    assert(probe.expectMsgType[RemoteFailure].cause == InvalidCompleteInteractiveTx(params.channelId))
  }

  test("invalid commit_sig") {
    val probe = TestProbe()
    val wallet = new SingleKeyOnChainWallet()
    val params = createChannelParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val alice = params.spawnTxBuilderAlice(params.fundingParamsA, TestConstants.anchorOutputsFeeratePerKw, wallet)
    alice ! Start(probe.ref, Nil)
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
    val params = createChannelParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val alice = params.spawnTxBuilderAlice(params.fundingParamsA, TestConstants.anchorOutputsFeeratePerKw, wallet)
    val bob = params.spawnTxBuilderBob(params.fundingParamsB, TestConstants.anchorOutputsFeeratePerKw, wallet)
    alice ! Start(alice2bob.ref, Nil)
    bob ! Start(bob2alice.ref, Nil)
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
    val params = createChannelParams(100_000 sat, 0 sat, FeeratePerKw(5000 sat), 330 sat, 0)
    val alice = params.spawnTxBuilderAlice(params.fundingParamsA, TestConstants.anchorOutputsFeeratePerKw, wallet)
    val bob = params.spawnTxBuilderBob(params.fundingParamsB, TestConstants.anchorOutputsFeeratePerKw, wallet)
    alice ! Start(alice2bob.ref, Nil)
    bob ! Start(bob2alice.ref, Nil)
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
    alice ! ReceiveCommitSig(bob2alice.expectMsgType[SendMessage].msg.asInstanceOf[CommitSig])
    bob ! ReceiveCommitSig(alice2bob.expectMsgType[SendMessage].msg.asInstanceOf[CommitSig])
    // Alice <-- tx_signatures --- Bob
    val bobSigs = bob2alice.expectMsgType[Succeeded].sharedTx.asInstanceOf[PartiallySignedSharedTransaction].localSigs
    alice ! ReceiveTxSigs(bobSigs.copy(witnesses = Seq(Script.witnessPay2wpkh(randomKey().publicKey, ByteVector.fill(73)(0)))))
    assert(alice2bob.expectMsgType[RemoteFailure].cause.isInstanceOf[InvalidFundingSignature])
  }

}
