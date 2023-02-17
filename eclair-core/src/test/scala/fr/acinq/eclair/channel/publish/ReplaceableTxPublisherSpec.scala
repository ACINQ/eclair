/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.channel.publish

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter.{ClassicActorSystemOps, actorRefAdapter}
import akka.pattern.pipe
import akka.testkit.{TestFSMRef, TestProbe}
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{BtcAmount, ByteVector32, MilliBtcDouble, OutPoint, SatoshiLong, Transaction}
import fr.acinq.eclair.NotificationsLogger.NotifyNodeOperator
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.MempoolTx
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BitcoinCoreClient, BitcoinJsonRPCClient}
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.blockchain.{CurrentBlockHeight, OnchainPubkeyCache}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.publish.ReplaceableTxPublisher.{Publish, Stop, UpdateConfirmationTarget}
import fr.acinq.eclair.channel.publish.TxPublisher.TxRejectedReason._
import fr.acinq.eclair.channel.publish.TxPublisher._
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.{CommitSig, RevokeAndAck}
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, NodeParams, NotificationsLogger, TestConstants, TestFeeEstimator, TestKitBaseClass, randomKey}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class ReplaceableTxPublisherSpec extends TestKitBaseClass with AnyFunSuiteLike with BitcoindService with ChannelStateTestsBase with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    startBitcoind()
    waitForBitcoindReady()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  case class Fixture(alice: TestFSMRef[ChannelState, ChannelData, Channel],
                     bob: TestFSMRef[ChannelState, ChannelData, Channel],
                     alice2bob: TestProbe,
                     bob2alice: TestProbe,
                     alice2blockchain: TestProbe,
                     bob2blockchain: TestProbe,
                     wallet: BitcoinCoreClient,
                     walletRpcClient: BitcoinJsonRPCClient,
                     publisher: ActorRef[ReplaceableTxPublisher.Command],
                     probe: TestProbe) {

    def createPublisher(): ActorRef[ReplaceableTxPublisher.Command] = createPublisher(alice.underlyingActor.nodeParams)

    def createPublisher(nodeParams: NodeParams): ActorRef[ReplaceableTxPublisher.Command] = {
      system.spawnAnonymous(ReplaceableTxPublisher(nodeParams, wallet, alice2blockchain.ref, TxPublishContext(UUID.randomUUID(), randomKey().publicKey, None)))
    }

    def aliceBlockHeight(): BlockHeight = alice.underlyingActor.nodeParams.currentBlockHeight

    def bobBlockHeight(): BlockHeight = bob.underlyingActor.nodeParams.currentBlockHeight

    /** Set uniform feerate for all block targets. */
    def setFeerate(feerate: FeeratePerKw): Unit = {
      alice.underlyingActor.nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator].setFeerate(FeeratesPerKw.single(feerate))
      bob.underlyingActor.nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator].setFeerate(FeeratesPerKw.single(feerate))
    }

    /** Set feerate for a specific block target. */
    def setFeerate(feerate: FeeratePerKw, blockTarget: Int): Unit = {
      alice.underlyingActor.nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator].setFeerate(blockTarget, feerate)
      bob.underlyingActor.nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator].setFeerate(blockTarget, feerate)
    }

    def getMempool(): Seq[Transaction] = {
      wallet.getMempool().pipeTo(probe.ref)
      probe.expectMsgType[Seq[Transaction]]
    }

    def getMempoolTxs(expectedTxCount: Int): Seq[MempoolTx] = {
      awaitAssert(assert(getMempool().size == expectedTxCount), interval = 200 milliseconds)
      getMempool().map(tx => {
        wallet.getMempoolTx(tx.txid).pipeTo(probe.ref)
        probe.expectMsgType[MempoolTx]
      })
    }

    def isInMempool(txid: ByteVector32): Boolean = {
      getMempool().exists(_.txid == txid)
    }

  }

  // NB: we can't use ScalaTest's fixtures, they would see uninitialized bitcoind fields because they sandbox each test.
  private def withFixture(utxos: Seq[BtcAmount], channelType: SupportedChannelType)(testFun: Fixture => Any): Unit = {
    // Create a unique wallet for this test and ensure it has some btc.
    val testId = UUID.randomUUID()
    val walletRpcClient = createWallet(s"lightning-$testId")
    val probe = TestProbe()
    val walletClient = new BitcoinCoreClient(walletRpcClient) with OnchainPubkeyCache {
      val pubkey = {
        getP2wpkhPubkey().pipeTo(probe.ref)
        probe.expectMsgType[PublicKey]
      }

      override def getP2wpkhPubkey(renew: Boolean): PublicKey = pubkey
    }

    // Ensure our wallet has some funds.
    utxos.foreach(amount => {
      walletClient.getReceiveAddress().pipeTo(probe.ref)
      val walletAddress = probe.expectMsgType[String]
      sendToAddress(walletAddress, amount, probe)
    })
    generateBlocks(1)

    // Setup a valid channel between alice and bob.
    val blockHeight = new AtomicLong()
    blockHeight.set(currentBlockHeight(probe).toLong)
    val aliceNodeParams = TestConstants.Alice.nodeParams.copy(blockHeight = blockHeight)
    val setup = init(aliceNodeParams, TestConstants.Bob.nodeParams.copy(blockHeight = blockHeight), wallet_opt = Some(walletClient))
    val testTags = channelType match {
      case _: ChannelTypes.AnchorOutputsZeroFeeHtlcTx => Set(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)
      case _: ChannelTypes.AnchorOutputs => Set(ChannelStateTestsTags.AnchorOutputs)
      case _: ChannelTypes.StaticRemoteKey => Set(ChannelStateTestsTags.StaticRemoteKey)
      case _ => Set.empty[String]
    }
    reachNormal(setup, testTags)
    import setup._
    awaitAssert(assert(alice.stateName == NORMAL))
    awaitAssert(assert(bob.stateName == NORMAL))

    // Generate blocks to ensure the funding tx is confirmed.
    generateBlocks(1)

    // Execute our test.
    val publisher = system.spawn(ReplaceableTxPublisher(aliceNodeParams, walletClient, alice2blockchain.ref, TxPublishContext(testId, TestConstants.Bob.nodeParams.nodeId, None)), testId.toString)
    testFun(Fixture(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, walletClient, walletRpcClient, publisher, probe))
  }

  def closeChannelWithoutHtlcs(f: Fixture, overrideCommitTarget: BlockHeight): (PublishFinalTx, PublishReplaceableTx) = {
    import f._

    val commitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.fullySignedLocalCommitTx(alice.underlyingActor.nodeParams.channelKeyManager)
    probe.send(alice, CMD_FORCECLOSE(probe.ref))
    probe.expectMsgType[CommandSuccess[CMD_FORCECLOSE]]

    // Forward the commit tx to the publisher.
    val publishCommitTx = alice2blockchain.expectMsg(PublishFinalTx(commitTx, commitTx.fee, None))
    // Forward the anchor tx to the publisher.
    val publishAnchor = alice2blockchain.expectMsgType[PublishReplaceableTx]
    assert(publishAnchor.txInfo.input.outPoint.txid == commitTx.tx.txid)
    assert(publishAnchor.txInfo.isInstanceOf[ClaimLocalAnchorOutputTx])
    val anchorTx = publishAnchor.txInfo.asInstanceOf[ClaimLocalAnchorOutputTx].copy(confirmBefore = overrideCommitTarget)

    (publishCommitTx, publishAnchor.copy(txInfo = anchorTx))
  }

  test("commit tx feerate high enough, not spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val commitFeerate = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.commitTxFeerate
      setFeerate(commitFeerate)
      val (_, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 24)
      publisher ! Publish(probe.ref, anchorTx)

      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd == anchorTx)
      assert(result.reason == TxSkipped(retryNextBlock = true))
    }
  }

  test("commit tx confirmed, not spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 12)
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)
      generateBlocks(1)

      setFeerate(FeeratePerKw(10_000 sat))
      publisher ! Publish(probe.ref, anchorTx)
      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd == anchorTx)
      assert(result.reason == TxSkipped(retryNextBlock = false))
    }
  }

  test("commit tx feerate high enough and commit tx confirmed, not spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val commitFeerate = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.commitTxFeerate
      setFeerate(commitFeerate)
      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 6)
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)
      generateBlocks(1)

      publisher ! Publish(probe.ref, anchorTx)
      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd == anchorTx)
      assert(result.reason == TxSkipped(retryNextBlock = false))
    }
  }

  test("remote commit tx confirmed, not spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val remoteCommit = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager)
      assert(remoteCommit.tx.txOut.length == 4) // 2 main outputs + 2 anchor outputs
      val (_, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 12)
      wallet.publishTransaction(remoteCommit.tx).pipeTo(probe.ref)
      probe.expectMsg(remoteCommit.tx.txid)
      generateBlocks(1)

      setFeerate(FeeratePerKw(10_000 sat))
      publisher ! Publish(probe.ref, anchorTx)
      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd == anchorTx)
      assert(result.reason == TxSkipped(retryNextBlock = false))
    }
  }

  test("next remote commit tx confirmed, not spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      // Add a partially signed htlc Alice -> Bob.
      addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
      probe.send(alice, CMD_SIGN(Some(probe.ref)))
      probe.expectMsgType[CommandSuccess[CMD_SIGN]]
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[RevokeAndAck]
      bob2alice.expectMsgType[CommitSig]
      assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.nextRemoteCommit_opt.nonEmpty)
      val nextRemoteCommitTxId = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.nextRemoteCommit_opt.get.commit.txid

      val nextRemoteCommit = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager)
      assert(nextRemoteCommit.tx.txid == nextRemoteCommitTxId)
      assert(nextRemoteCommit.tx.txOut.length == 5) // 2 main outputs + 2 anchor outputs + 1 htlc
      val (_, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 12)
      wallet.publishTransaction(nextRemoteCommit.tx).pipeTo(probe.ref)
      probe.expectMsg(nextRemoteCommit.tx.txid)
      generateBlocks(1)

      setFeerate(FeeratePerKw(10_000 sat))
      publisher ! Publish(probe.ref, anchorTx)
      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd == anchorTx)
      assert(result.reason == TxSkipped(retryNextBlock = false))
    }
  }

  test("remote commit tx published, not spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val remoteCommit = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager)
      val (_, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 12)
      wallet.publishTransaction(remoteCommit.tx).pipeTo(probe.ref)
      probe.expectMsg(remoteCommit.tx.txid)

      setFeerate(FeeratePerKw(10_000 sat))
      publisher ! Publish(probe.ref, anchorTx)
      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd == anchorTx)
      // When the remote commit tx is still unconfirmed, we want to retry in case it is evicted from the mempool and our
      // commit is then published.
      assert(result.reason == TxSkipped(retryNextBlock = true))
    }
  }

  test("remote commit tx replaces local commit tx, not spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val remoteCommit = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.commitTxFeerate == FeeratePerKw(2500 sat))

      // We lower the feerate to make it easy to replace our commit tx by theirs in the mempool.
      val lowFeerate = FeeratePerKw(500 sat)
      updateFee(lowFeerate, alice, bob, alice2bob, bob2alice)
      val (localCommit, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 16)
      // We set a slightly higher feerate to ensure the local anchor is used.
      setFeerate(FeeratePerKw(600 sat))
      publisher ! Publish(probe.ref, anchorTx)
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(localCommit.tx.txid))

      // Our commit tx is replaced by theirs.
      wallet.publishTransaction(remoteCommit.tx).pipeTo(probe.ref)
      probe.expectMsg(remoteCommit.tx.txid)
      generateBlocks(1)
      system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))

      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd == anchorTx)
      assert(result.reason == InputGone)

      // Since our wallet input is gone, we will retry and discover that a commit tx has been confirmed.
      val publisher2 = createPublisher()
      publisher2 ! Publish(probe.ref, anchorTx)
      val result2 = probe.expectMsgType[TxRejected]
      assert(result2.cmd == anchorTx)
      assert(result2.reason == TxSkipped(retryNextBlock = false))
    }
  }

  test("not enough funds to increase commit tx feerate") {
    withFixture(Seq(10.4 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      // close channel and wait for the commit tx to be published, anchor will not be published because we don't have enough funds
      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 6)
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)

      setFeerate(FeeratePerKw(25_000 sat))
      publisher ! Publish(probe.ref, anchorTx)
      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd == anchorTx)
      // When the remote commit tx is still unconfirmed, we want to retry in case it is evicted from the mempool and our
      // commit is then published.
      assert(result.reason == CouldNotFund)
    }
  }

  test("commit tx feerate too low, spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 30)
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)
      assert(getMempool().length == 1)

      val targetFeerate = FeeratePerKw(3000 sat)
      // NB: we try to get transactions confirmed *before* their confirmation target, so we aim for a more aggressive block target what's provided.
      setFeerate(targetFeerate, blockTarget = 12)
      publisher ! Publish(probe.ref, anchorTx)
      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(commitTx.tx.txid))

      val targetFee = Transactions.weight2fee(targetFeerate, mempoolTxs.map(_.weight).sum.toInt)
      val actualFee = mempoolTxs.map(_.fees).sum
      assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee")

      generateBlocks(5)
      system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
      val result = probe.expectMsgType[TxConfirmed]
      assert(result.cmd == anchorTx)
      assert(result.tx.txIn.map(_.outPoint.txid).contains(commitTx.tx.txid))
      assert(mempoolTxs.map(_.txid).contains(result.tx.txid))
    }
  }

  test("commit tx not published, publishing it and spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 32)
      assert(getMempool().isEmpty)

      val targetFeerate = FeeratePerKw(3000 sat)
      // NB: we try to get transactions confirmed *before* their confirmation target, so we aim for a more aggressive block target than what's provided.
      setFeerate(targetFeerate, blockTarget = 12)
      publisher ! Publish(probe.ref, anchorTx)
      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(commitTx.tx.txid))

      val targetFee = Transactions.weight2fee(targetFeerate, mempoolTxs.map(_.weight).sum.toInt)
      val actualFee = mempoolTxs.map(_.fees).sum
      assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee")

      generateBlocks(5)
      system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
      val result = probe.expectMsgType[TxConfirmed]
      assert(result.cmd == anchorTx)
      assert(result.tx.txIn.map(_.outPoint.txid).contains(commitTx.tx.txid))
      assert(mempoolTxs.map(_.txid).contains(result.tx.txid))
    }
  }

  test("commit tx feerate too low, spending anchor outputs with multiple wallet inputs") {
    val utxos = Seq(
      // channel funding
      10 millibtc,
      // bumping utxos
      15000 sat,
      12000 sat,
      10000 sat
    )
    withFixture(utxos, ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      // NB: we try to get transactions confirmed *before* their confirmation target, so we aim for a more aggressive block target than what's provided.
      val targetFeerate = FeeratePerKw(10_000 sat)
      setFeerate(targetFeerate, blockTarget = 12)
      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 32)
      publisher ! Publish(probe.ref, anchorTx)

      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(commitTx.tx.txid))

      val targetFee = Transactions.weight2fee(targetFeerate, mempoolTxs.map(_.weight).sum.toInt)
      val actualFee = mempoolTxs.map(_.fees).sum
      assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee")

      generateBlocks(5)
      system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
      val result = probe.expectMsgType[TxConfirmed]
      assert(result.cmd == anchorTx)
      assert(result.tx.txIn.map(_.outPoint.txid).contains(commitTx.tx.txid))
      assert(result.tx.txIn.length > 2) // we added more than 1 wallet input
      assert(mempoolTxs.map(_.txid).contains(result.tx.txid))
    }
  }

  test("commit tx fees not increased when confirmation target is far and feerate hasn't changed") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 30)
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)

      setFeerate(FeeratePerKw(3000 sat))
      publisher ! Publish(probe.ref, anchorTx)
      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(commitTx.tx.txid))

      // A new block is found, but we still have time and the feerate hasn't changed, so we don't bump the fees.
      // Note that we don't generate blocks, so the transactions are still unconfirmed.
      system.eventStream.publish(CurrentBlockHeight(aliceBlockHeight() + 5))
      probe.expectNoMessage(500 millis)
      val mempoolTxs2 = getMempool()
      assert(mempoolTxs.map(_.txid).toSet == mempoolTxs2.map(_.txid).toSet)
    }
  }

  test("commit tx not confirming, lowering anchor output amount") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 30)
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)

      val listener = TestProbe()
      system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

      val oldFeerate = FeeratePerKw(3000 sat)
      setFeerate(oldFeerate)
      publisher ! Publish(probe.ref, anchorTx)
      // wait for the commit tx and anchor tx to be published
      val anchorTxId1 = listener.expectMsgType[TransactionPublished].tx.txid
      val mempoolTxs1 = getMempoolTxs(2)
      assert(mempoolTxs1.map(_.txid).contains(commitTx.tx.txid))
      val mempoolAnchorTx1 = mempoolTxs1.filter(_.txid != commitTx.tx.txid).head
      assert(mempoolAnchorTx1.txid == anchorTxId1)

      // A new block is found, and the feerate has increased for our block target, so we bump the fees.
      val newFeerate = FeeratePerKw(5000 sat)
      setFeerate(newFeerate, blockTarget = 12)
      system.eventStream.publish(CurrentBlockHeight(aliceBlockHeight() + 5))
      val anchorTxId2 = listener.expectMsgType[TransactionPublished].tx.txid
      assert(!isInMempool(mempoolAnchorTx1.txid))
      val mempoolTxs2 = getMempoolTxs(2)
      val mempoolAnchorTx2 = mempoolTxs2.filter(_.txid != commitTx.tx.txid).head
      assert(mempoolAnchorTx2.txid == anchorTxId2)
      assert(mempoolAnchorTx1.fees < mempoolAnchorTx2.fees)

      val targetFee = Transactions.weight2fee(newFeerate, mempoolTxs2.map(_.weight).sum.toInt)
      val actualFee = mempoolTxs2.map(_.fees).sum
      assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee")
    }
  }

  test("commit tx not confirming, adding other wallet inputs") {
    withFixture(Seq(10.4 millibtc, 5 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 30)
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)

      val listener = TestProbe()
      system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

      // The feerate is (much) higher for higher block targets
      val targetFeerate = FeeratePerKw(75_000 sat)
      setFeerate(FeeratePerKw(3000 sat))
      setFeerate(targetFeerate, blockTarget = 6)
      publisher ! Publish(probe.ref, anchorTx)
      // wait for the commit tx and anchor tx to be published
      val anchorTxId1 = listener.expectMsgType[TransactionPublished].tx.txid
      val mempoolTxs1 = getMempoolTxs(2)
      assert(mempoolTxs1.map(_.txid).contains(commitTx.tx.txid))
      val anchorTx1 = getMempool().filter(_.txid != commitTx.tx.txid).head
      assert(anchorTx1.txid == anchorTxId1)

      // A new block is found, and the feerate has increased for our block target, so we bump the fees.
      system.eventStream.publish(CurrentBlockHeight(aliceBlockHeight() + 15))
      val anchorTxId2 = listener.expectMsgType[TransactionPublished].tx.txid
      assert(!isInMempool(anchorTx1.txid))
      val anchorTx2 = getMempool().filter(_.txid != commitTx.tx.txid).head
      assert(anchorTx2.txid == anchorTxId2)
      // We used different inputs to be able to bump to the desired feerate.
      assert(anchorTx1.txIn.map(_.outPoint).toSet != anchorTx2.txIn.map(_.outPoint).toSet)

      val mempoolTxs2 = getMempoolTxs(2)
      val targetFee = Transactions.weight2fee(targetFeerate, mempoolTxs2.map(_.weight).sum.toInt)
      val actualFee = mempoolTxs2.map(_.fees).sum
      assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee")
    }
  }

  test("commit tx not confirming, not enough funds to increase fees") {
    withFixture(Seq(10.2 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 30)
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)

      // The feerate is higher for higher block targets
      val targetFeerate = FeeratePerKw(25_000 sat)
      setFeerate(FeeratePerKw(3000 sat))
      setFeerate(targetFeerate, blockTarget = 6)
      publisher ! Publish(probe.ref, anchorTx)
      // wait for the commit tx and anchor tx to be published
      val mempoolTxs1 = getMempoolTxs(2)
      assert(mempoolTxs1.map(_.txid).contains(commitTx.tx.txid))

      // A new block is found, and the feerate has increased for our block target, but we don't have enough funds to bump the fees.
      system.eventStream.subscribe(probe.ref, classOf[NotifyNodeOperator])
      // just making sure that we have been subscribed to the event, otherwise there is a possible race condition
      awaitAssert({
        system.eventStream.publish(NotifyNodeOperator(NotificationsLogger.Info, "ping"))
        assert(probe.msgAvailable)
      }, max = 30 seconds)
      system.eventStream.publish(CurrentBlockHeight(aliceBlockHeight() + 15))
      probe.fishForMessage() {
        case nno: NotifyNodeOperator => nno.severity != NotificationsLogger.Info
        case _ => false
      }
      val mempoolTxs2 = getMempool()
      assert(mempoolTxs1.map(_.txid).toSet == mempoolTxs2.map(_.txid).toSet)
    }
  }

  test("commit tx not confirming, cannot use new unconfirmed inputs to increase fees") {
    withFixture(Seq(10.2 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 30)
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)

      // The feerate is higher for higher block targets
      val targetFeerate = FeeratePerKw(25_000 sat)
      setFeerate(FeeratePerKw(3000 sat))
      setFeerate(targetFeerate, blockTarget = 6)
      publisher ! Publish(probe.ref, anchorTx)
      // wait for the commit tx and anchor tx to be published
      val mempoolTxs1 = getMempoolTxs(2)
      assert(mempoolTxs1.map(_.txid).contains(commitTx.tx.txid))

      // Our wallet receives new unconfirmed utxos: unfortunately, BIP 125 rule #2 doesn't let us use that input...
      wallet.getReceiveAddress().pipeTo(probe.ref)
      val walletAddress = probe.expectMsgType[String]
      val walletTx = sendToAddress(walletAddress, 5 millibtc, probe)

      // A new block is found, and the feerate has increased for our block target, but we can't use our unconfirmed input.
      system.eventStream.subscribe(probe.ref, classOf[NotifyNodeOperator])
      system.eventStream.publish(CurrentBlockHeight(aliceBlockHeight() + 15))
      probe.expectMsgType[NotifyNodeOperator]
      val mempoolTxs2 = getMempool()
      assert(mempoolTxs1.map(_.txid).toSet + walletTx.txid == mempoolTxs2.map(_.txid).toSet)
    }
  }

  test("commit tx not confirming, updating confirmation target") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 30)
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)

      val listener = TestProbe()
      system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

      val feerateLow = FeeratePerKw(3000 sat)
      val feerateHigh = FeeratePerKw(5000 sat)
      setFeerate(feerateLow)
      setFeerate(feerateHigh, blockTarget = 6)
      // With the initial confirmation target, this will use the low feerate.
      publisher ! Publish(probe.ref, anchorTx)
      val anchorTxId1 = listener.expectMsgType[TransactionPublished].tx.txid
      val mempoolTxs1 = getMempoolTxs(2)
      assert(mempoolTxs1.map(_.txid).contains(commitTx.tx.txid))
      val mempoolAnchorTx1 = mempoolTxs1.filter(_.txid != commitTx.tx.txid).head
      assert(mempoolAnchorTx1.txid == anchorTxId1)
      val targetFee1 = Transactions.weight2fee(feerateLow, mempoolTxs1.map(_.weight).sum.toInt)
      val actualFee1 = mempoolTxs1.map(_.fees).sum
      assert(targetFee1 * 0.9 <= actualFee1 && actualFee1 <= targetFee1 * 1.1, s"actualFee=$actualFee1 targetFee=$targetFee1")

      // The confirmation target has changed (probably because we learnt a payment preimage).
      // We should now use the high feerate, which corresponds to that new target.
      publisher ! UpdateConfirmationTarget(aliceBlockHeight() + 15)
      system.eventStream.publish(CurrentBlockHeight(aliceBlockHeight()))
      val anchorTxId2 = listener.expectMsgType[TransactionPublished].tx.txid
      awaitAssert(assert(!isInMempool(mempoolAnchorTx1.txid)), interval = 200 millis, max = 30 seconds)
      val mempoolTxs2 = getMempoolTxs(2)
      val mempoolAnchorTx2 = mempoolTxs2.filter(_.txid != commitTx.tx.txid).head
      assert(mempoolAnchorTx2.txid == anchorTxId2)
      assert(mempoolAnchorTx1.fees < mempoolAnchorTx2.fees)

      val targetFee2 = Transactions.weight2fee(feerateHigh, mempoolTxs2.map(_.weight).sum.toInt)
      val actualFee2 = mempoolTxs2.map(_.fees).sum
      assert(targetFee2 * 0.9 <= actualFee2 && actualFee2 <= targetFee2 * 1.1, s"actualFee=$actualFee2 targetFee=$targetFee2")
    }
  }

  test("unlock utxos when anchor tx cannot be published") {
    withFixture(Seq(500 millibtc, 200 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val targetFeerate = FeeratePerKw(3000 sat)
      setFeerate(targetFeerate)
      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 36)
      publisher ! Publish(probe.ref, anchorTx)

      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(commitTx.tx.txid))

      // we try to publish the anchor again (can be caused by a node restart): it will fail to replace the existing one
      // in the mempool but we must ensure we don't leave some utxos locked.
      val publisher2 = createPublisher()
      publisher2 ! Publish(probe.ref, anchorTx)
      val result = probe.expectMsgType[TxRejected]
      assert(result.reason == ConflictingTxUnconfirmed)
      getMempoolTxs(2) // the previous anchor tx and the commit tx are still in the mempool

      // our parent will stop us when receiving the TxRejected message.
      publisher2 ! Stop
      awaitAssert({
        wallet.listLockedOutpoints().pipeTo(probe.ref)
        assert(!probe.expectMsgType[Set[OutPoint]].exists(_.txid != commitTx.tx.txid))
      })

      // the first publishing attempt succeeds
      generateBlocks(5)
      system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
      assert(probe.expectMsgType[TxConfirmed].cmd == anchorTx)
    }
  }

  test("unlock anchor utxos when stopped before completion") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val targetFeerate = FeeratePerKw(3000 sat)
      setFeerate(targetFeerate)
      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f, aliceBlockHeight() + 16)
      publisher ! Publish(probe.ref, anchorTx)

      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(commitTx.tx.txid))

      // we unlock utxos before stopping
      publisher ! Stop
      awaitAssert({
        wallet.listLockedOutpoints().pipeTo(probe.ref)
        assert(probe.expectMsgType[Set[OutPoint]].isEmpty)
      })
    }
  }

  test("remote commit tx confirmed, not publishing htlc tx") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      // Add htlcs in both directions and ensure that preimages are available.
      addHtlc(5_000_000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      val (r, htlc) = addHtlc(4_000_000 msat, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)
      probe.send(alice, CMD_FULFILL_HTLC(htlc.id, r, replyTo_opt = Some(probe.ref)))
      probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]

      // Force-close channel.
      probe.send(alice, CMD_FORCECLOSE(probe.ref))
      probe.expectMsgType[CommandSuccess[CMD_FORCECLOSE]]
      alice2blockchain.expectMsgType[PublishFinalTx]
      assert(alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.isInstanceOf[ClaimLocalAnchorOutputTx])
      alice2blockchain.expectMsgType[PublishFinalTx] // claim main output
      val htlcSuccess = alice2blockchain.expectMsgType[PublishReplaceableTx]
      assert(htlcSuccess.txInfo.isInstanceOf[HtlcSuccessTx])
      val htlcTimeout = alice2blockchain.expectMsgType[PublishReplaceableTx]
      assert(htlcTimeout.txInfo.isInstanceOf[HtlcTimeoutTx])

      // Ensure remote commit tx confirms.
      val remoteCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager)
      wallet.publishTransaction(remoteCommitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(remoteCommitTx.tx.txid)
      generateBlocks(5)

      // Verify that HTLC transactions immediately fail to publish.
      setFeerate(FeeratePerKw(15_000 sat))
      val htlcSuccessPublisher = createPublisher()
      htlcSuccessPublisher ! Publish(probe.ref, htlcSuccess)
      val result1 = probe.expectMsgType[TxRejected]
      assert(result1.cmd == htlcSuccess)
      assert(result1.reason == ConflictingTxConfirmed)
      htlcSuccessPublisher ! Stop

      val htlcTimeoutPublisher = createPublisher()
      htlcTimeoutPublisher ! Publish(probe.ref, htlcTimeout)
      val result2 = probe.expectMsgType[TxRejected]
      assert(result2.cmd == htlcTimeout)
      assert(result2.reason == ConflictingTxConfirmed)
      htlcTimeoutPublisher ! Stop
    }
  }

  test("next remote commit tx confirmed, not publishing htlc tx") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      // Add one htlc in the current commitment and one htlc in the next commitment.
      addHtlc(5_000_000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      addHtlc(4_000_000 msat, alice, bob, alice2bob, bob2alice)
      probe.send(alice, CMD_SIGN(Some(probe.ref)))
      probe.expectMsgType[CommandSuccess[CMD_SIGN]]
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[RevokeAndAck]
      bob2alice.expectMsgType[CommitSig]
      assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.nextRemoteCommit_opt.nonEmpty)
      val nextRemoteCommitTxId = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.nextRemoteCommit_opt.get.commit.txid

      // Force-close channel.
      probe.send(alice, CMD_FORCECLOSE(probe.ref))
      probe.expectMsgType[CommandSuccess[CMD_FORCECLOSE]]
      alice2blockchain.expectMsgType[PublishFinalTx]
      assert(alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.isInstanceOf[ClaimLocalAnchorOutputTx])
      alice2blockchain.expectMsgType[PublishFinalTx] // claim main output
      val htlcTimeout = alice2blockchain.expectMsgType[PublishReplaceableTx]
      assert(htlcTimeout.txInfo.isInstanceOf[HtlcTimeoutTx])

      // Ensure remote commit tx confirms.
      val nextRemoteCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager)
      assert(nextRemoteCommitTx.tx.txid == nextRemoteCommitTxId)
      assert(nextRemoteCommitTx.tx.txOut.length == 6) // 2 main outputs + 2 anchor outputs + 2 htlcs
      wallet.publishTransaction(nextRemoteCommitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(nextRemoteCommitTx.tx.txid)
      generateBlocks(5)

      // Verify that HTLC transactions immediately fail to publish.
      setFeerate(FeeratePerKw(15_000 sat))
      val htlcTimeoutPublisher = createPublisher()
      htlcTimeoutPublisher ! Publish(probe.ref, htlcTimeout)
      val result2 = probe.expectMsgType[TxRejected]
      assert(result2.cmd == htlcTimeout)
      assert(result2.reason == ConflictingTxConfirmed)
      htlcTimeoutPublisher ! Stop
    }
  }

  def closeChannelWithHtlcs(f: Fixture, overrideHtlcTarget: BlockHeight): (Transaction, PublishReplaceableTx, PublishReplaceableTx) = {
    import f._

    // Add htlcs in both directions and ensure that preimages are available.
    addHtlc(5_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val (r, htlc) = addHtlc(4_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    probe.send(alice, CMD_FULFILL_HTLC(htlc.id, r, replyTo_opt = Some(probe.ref)))
    probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]

    // Force-close channel and verify txs sent to watcher.
    val commitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.fullySignedLocalCommitTx(alice.underlyingActor.nodeParams.channelKeyManager)
    assert(commitTx.tx.txOut.size == 6)
    probe.send(alice, CMD_FORCECLOSE(probe.ref))
    probe.expectMsgType[CommandSuccess[CMD_FORCECLOSE]]

    // We make the commit tx confirm because htlc txs have a relative delay.
    alice2blockchain.expectMsg(PublishFinalTx(commitTx, commitTx.fee, None))
    wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
    probe.expectMsg(commitTx.tx.txid)
    generateBlocks(1)

    assert(alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.isInstanceOf[ClaimLocalAnchorOutputTx])
    alice2blockchain.expectMsgType[PublishFinalTx] // claim main output
    val htlcSuccess = alice2blockchain.expectMsgType[PublishReplaceableTx]
    assert(htlcSuccess.txInfo.isInstanceOf[HtlcSuccessTx])
    val htlcSuccessTx = htlcSuccess.txInfo.asInstanceOf[HtlcSuccessTx].copy(confirmBefore = overrideHtlcTarget)
    val htlcTimeout = alice2blockchain.expectMsgType[PublishReplaceableTx]
    assert(htlcTimeout.txInfo.isInstanceOf[HtlcTimeoutTx])
    val htlcTimeoutTx = htlcTimeout.txInfo.asInstanceOf[HtlcTimeoutTx].copy(confirmBefore = overrideHtlcTarget)

    alice2blockchain.expectMsgType[WatchTxConfirmed] // commit tx
    alice2blockchain.expectMsgType[WatchTxConfirmed] // claim main output
    alice2blockchain.expectMsgType[WatchOutputSpent] // claim-anchor tx
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc-success tx
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc-timeout tx
    alice2blockchain.expectNoMessage(100 millis)

    (commitTx.tx, htlcSuccess.copy(txInfo = htlcSuccessTx), htlcTimeout.copy(txInfo = htlcTimeoutTx))
  }

  test("not enough funds to increase htlc tx feerate") {
    withFixture(Seq(10.5 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val (commitTx, htlcSuccess, _) = closeChannelWithHtlcs(f, aliceBlockHeight())
      val htlcSuccessPublisher = createPublisher()
      setFeerate(FeeratePerKw(75_000 sat), blockTarget = 1)
      htlcSuccessPublisher ! Publish(probe.ref, htlcSuccess)
      val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe), 0, commitTx)

      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd == htlcSuccess)
      assert(result.reason == CouldNotFund)
      htlcSuccessPublisher ! Stop
    }
  }

  private def testPublishHtlcSuccess(f: Fixture, commitTx: Transaction, htlcSuccess: PublishReplaceableTx, targetFeerate: FeeratePerKw): Transaction = {
    import f._

    val htlcSuccessPublisher = createPublisher()
    htlcSuccessPublisher ! Publish(probe.ref, htlcSuccess)
    val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
    w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe), 0, commitTx)
    val htlcSuccessTx = getMempoolTxs(1).head
    val htlcSuccessTargetFee = Transactions.weight2fee(targetFeerate, htlcSuccessTx.weight.toInt)
    assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx.fees && htlcSuccessTx.fees <= htlcSuccessTargetFee * 1.2, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcSuccessTargetFee")

    generateBlocks(4)
    system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
    val htlcSuccessResult = probe.expectMsgType[TxConfirmed]
    assert(htlcSuccessResult.cmd == htlcSuccess)
    assert(htlcSuccessResult.tx.txIn.map(_.outPoint.txid).contains(commitTx.txid))
    htlcSuccessPublisher ! Stop
    htlcSuccessResult.tx
  }

  private def testPublishHtlcTimeout(f: Fixture, commitTx: Transaction, htlcTimeout: PublishReplaceableTx, targetFeerate: FeeratePerKw): Transaction = {
    import f._

    // We start with a low feerate, that will then rise during the CLTV period.
    // The publisher should use the feerate available when the transaction can be published (after the timeout).
    setFeerate(targetFeerate / 2)

    // The HTLC-timeout will be published after the timeout.
    val htlcTimeoutPublisher = createPublisher()
    htlcTimeoutPublisher ! Publish(probe.ref, htlcTimeout)
    alice2blockchain.expectNoMessage(100 millis)
    generateBlocks(144)
    system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
    setFeerate(targetFeerate) // the feerate is higher than what it was when the channel force-closed
    val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
    w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe), 0, commitTx)
    val htlcTimeoutTx = getMempoolTxs(1).head
    val htlcTimeoutTargetFee = Transactions.weight2fee(targetFeerate, htlcTimeoutTx.weight.toInt)
    assert(htlcTimeoutTargetFee * 0.9 <= htlcTimeoutTx.fees && htlcTimeoutTx.fees <= htlcTimeoutTargetFee * 1.2, s"actualFee=${htlcTimeoutTx.fees} targetFee=$htlcTimeoutTargetFee")

    generateBlocks(4)
    system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
    val htlcTimeoutResult = probe.expectMsgType[TxConfirmed]
    assert(htlcTimeoutResult.cmd == htlcTimeout)
    assert(htlcTimeoutResult.tx.txIn.map(_.outPoint.txid).contains(commitTx.txid))
    htlcTimeoutPublisher ! Stop
    htlcTimeoutResult.tx
  }

  test("htlc tx feerate high enough, not adding wallet inputs") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputs()) { f =>
      import f._

      val currentFeerate = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.commitTxFeerate
      val (commitTx, htlcSuccess, htlcTimeout) = closeChannelWithHtlcs(f, aliceBlockHeight() + 64)
      setFeerate(currentFeerate)
      val htlcSuccessTx = testPublishHtlcSuccess(f, commitTx, htlcSuccess, currentFeerate)
      assert(htlcSuccess.txInfo.fee > 0.sat)
      assert(htlcSuccessTx.txIn.length == 1)
      val htlcTimeoutTx = testPublishHtlcTimeout(f, commitTx, htlcTimeout, currentFeerate)
      assert(htlcTimeout.txInfo.fee > 0.sat)
      assert(htlcTimeoutTx.txIn.length == 1)
    }
  }

  test("htlc tx feerate too low, adding wallet inputs") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputs()) { f =>
      import f._

      val targetFeerate = FeeratePerKw(15_000 sat)
      val (commitTx, htlcSuccess, htlcTimeout) = closeChannelWithHtlcs(f, aliceBlockHeight() + 64)
      // NB: we try to get transactions confirmed *before* their confirmation target, so we aim for a more aggressive block target than what's provided.
      setFeerate(targetFeerate, blockTarget = 36)
      val htlcSuccessTx = testPublishHtlcSuccess(f, commitTx, htlcSuccess, targetFeerate)
      assert(htlcSuccessTx.txIn.length > 1)
      val htlcTimeoutTx = testPublishHtlcTimeout(f, commitTx, htlcTimeout, targetFeerate)
      assert(htlcTimeoutTx.txIn.length > 1)
    }
  }

  test("htlc tx feerate zero, adding wallet inputs") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val targetFeerate = FeeratePerKw(15_000 sat)
      val (commitTx, htlcSuccess, htlcTimeout) = closeChannelWithHtlcs(f, aliceBlockHeight() + 30)
      // NB: we try to get transactions confirmed *before* their confirmation target, so we aim for a more aggressive block target than what's provided.
      setFeerate(targetFeerate, blockTarget = 12)
      assert(htlcSuccess.txInfo.fee == 0.sat)
      val htlcSuccessTx = testPublishHtlcSuccess(f, commitTx, htlcSuccess, targetFeerate)
      assert(htlcSuccessTx.txIn.length > 1)
      assert(htlcTimeout.txInfo.fee == 0.sat)
      val htlcTimeoutTx = testPublishHtlcTimeout(f, commitTx, htlcTimeout, targetFeerate)
      assert(htlcTimeoutTx.txIn.length > 1)
    }
  }

  test("htlc tx feerate zero, high commit feerate, adding wallet inputs") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val commitFeerate = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.commitTxFeerate
      val targetFeerate = commitFeerate / 2
      val (commitTx, htlcSuccess, htlcTimeout) = closeChannelWithHtlcs(f, aliceBlockHeight() + 30)
      setFeerate(targetFeerate)
      assert(htlcSuccess.txInfo.fee == 0.sat)
      val htlcSuccessTx = testPublishHtlcSuccess(f, commitTx, htlcSuccess, targetFeerate)
      assert(htlcSuccessTx.txIn.length > 1)
      assert(htlcTimeout.txInfo.fee == 0.sat)
      val htlcTimeoutTx = testPublishHtlcTimeout(f, commitTx, htlcTimeout, targetFeerate)
      assert(htlcTimeoutTx.txIn.length > 1)
    }
  }

  test("htlc tx feerate too low, adding multiple wallet inputs") {
    val utxos = Seq(
      // channel funding
      10 millibtc,
      // bumping utxos
      6000 sat,
      5900 sat,
      5800 sat,
      5700 sat,
      5600 sat,
      5500 sat,
      5400 sat,
      5300 sat,
      5200 sat,
      5100 sat
    )
    withFixture(utxos, ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val targetFeerate = FeeratePerKw(8_000 sat)
      val (commitTx, htlcSuccess, htlcTimeout) = closeChannelWithHtlcs(f, aliceBlockHeight() + 30)
      // NB: we try to get transactions confirmed *before* their confirmation target, so we aim for a more aggressive block target than what's provided.
      setFeerate(targetFeerate, blockTarget = 12)
      val htlcSuccessTx = testPublishHtlcSuccess(f, commitTx, htlcSuccess, targetFeerate)
      assert(htlcSuccessTx.txIn.length > 2)
      val htlcTimeoutTx = testPublishHtlcTimeout(f, commitTx, htlcTimeout, targetFeerate)
      assert(htlcTimeoutTx.txIn.length > 2)
    }
  }

  test("htlc success tx not confirming, lowering output amount") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val initialFeerate = FeeratePerKw(15_000 sat)
      setFeerate(initialFeerate)
      val (commitTx, htlcSuccess, _) = closeChannelWithHtlcs(f, aliceBlockHeight() + 30)

      val listener = TestProbe()
      system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

      val htlcSuccessPublisher = createPublisher()
      htlcSuccessPublisher ! Publish(probe.ref, htlcSuccess)
      val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w.replyTo ! WatchParentTxConfirmedTriggered(aliceBlockHeight(), 0, commitTx)
      val htlcSuccessTxId1 = listener.expectMsgType[TransactionPublished].tx.txid
      val htlcSuccessTx1 = getMempoolTxs(1).head
      val htlcSuccessInputs1 = getMempool().head.txIn.map(_.outPoint).toSet
      assert(htlcSuccessTx1.txid == htlcSuccessTxId1)

      // New blocks are found, which makes us aim for a more aggressive block target, so we bump the fees.
      val targetFeerate = FeeratePerKw(25_000 sat)
      setFeerate(targetFeerate, blockTarget = 6)
      system.eventStream.publish(CurrentBlockHeight(aliceBlockHeight() + 15))
      val htlcSuccessTxId2 = listener.expectMsgType[TransactionPublished].tx.txid
      assert(!isInMempool(htlcSuccessTx1.txid))
      val htlcSuccessTx2 = getMempoolTxs(1).head
      val htlcSuccessInputs2 = getMempool().head.txIn.map(_.outPoint).toSet
      assert(htlcSuccessTx2.txid == htlcSuccessTxId2)
      assert(htlcSuccessTx1.fees < htlcSuccessTx2.fees)
      assert(htlcSuccessInputs1 == htlcSuccessInputs2)
      val htlcSuccessTargetFee = Transactions.weight2fee(targetFeerate, htlcSuccessTx2.weight.toInt)
      assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx2.fees && htlcSuccessTx2.fees <= htlcSuccessTargetFee * 1.1, s"actualFee=${htlcSuccessTx2.fees} targetFee=$htlcSuccessTargetFee")
    }
  }

  test("htlc success tx not confirming, adding other wallet inputs") {
    withFixture(Seq(1_010_000 sat, 10_000 sat), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val initialFeerate = FeeratePerKw(3_000 sat)
      setFeerate(initialFeerate)
      val (commitTx, htlcSuccess, _) = closeChannelWithHtlcs(f, aliceBlockHeight() + 15)

      val listener = TestProbe()
      system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

      val htlcSuccessPublisher = createPublisher()
      htlcSuccessPublisher ! Publish(probe.ref, htlcSuccess)
      val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w.replyTo ! WatchParentTxConfirmedTriggered(aliceBlockHeight(), 0, commitTx)
      val htlcSuccessTxId1 = listener.expectMsgType[TransactionPublished].tx.txid
      val htlcSuccessTx1 = getMempoolTxs(1).head
      val htlcSuccessInputs1 = getMempool().head.txIn.map(_.outPoint).toSet
      assert(htlcSuccessTx1.txid == htlcSuccessTxId1)

      // New blocks are found, which makes us aim for a more aggressive block target, so we bump the fees.
      val targetFeerate = FeeratePerKw(10_000 sat)
      setFeerate(targetFeerate, blockTarget = 2)
      system.eventStream.publish(CurrentBlockHeight(aliceBlockHeight() + 10))
      val htlcSuccessTxId2 = listener.expectMsgType[TransactionPublished].tx.txid
      awaitAssert(assert(!isInMempool(htlcSuccessTx1.txid)), interval = 200 millis, max = 30 seconds)
      val htlcSuccessTx2 = getMempoolTxs(1).head
      val htlcSuccessInputs2 = getMempool().head.txIn.map(_.outPoint).toSet
      assert(htlcSuccessTx2.txid == htlcSuccessTxId2)
      assert(htlcSuccessTx1.fees < htlcSuccessTx2.fees)
      assert(htlcSuccessInputs1 != htlcSuccessInputs2)
      val htlcSuccessTargetFee = Transactions.weight2fee(targetFeerate, htlcSuccessTx2.weight.toInt)
      assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx2.fees && htlcSuccessTx2.fees <= htlcSuccessTargetFee * 1.1, s"actualFee=${htlcSuccessTx2.fees} targetFee=$htlcSuccessTargetFee")
    }
  }

  test("htlc success tx confirmation target reached, increasing fees") {
    withFixture(Seq(50 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val initialFeerate = FeeratePerKw(10_000 sat)
      setFeerate(initialFeerate)
      val (commitTx, htlcSuccess, _) = closeChannelWithHtlcs(f, aliceBlockHeight() + 6)

      val listener = TestProbe()
      system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

      val htlcSuccessPublisher = createPublisher()
      htlcSuccessPublisher ! Publish(probe.ref, htlcSuccess)
      val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w.replyTo ! WatchParentTxConfirmedTriggered(aliceBlockHeight(), 0, commitTx)
      val htlcSuccessTxId = listener.expectMsgType[TransactionPublished].tx.txid
      var htlcSuccessTx = getMempoolTxs(1).head
      assert(htlcSuccessTx.txid == htlcSuccessTxId)

      // We are only 6 blocks away from the confirmation target, so we bump the fees at each new block.
      (1 to 3).foreach(i => {
        system.eventStream.publish(CurrentBlockHeight(aliceBlockHeight() + i))
        val bumpedHtlcSuccessTxId = listener.expectMsgType[TransactionPublished].tx.txid
        assert(!isInMempool(htlcSuccessTx.txid))
        val bumpedHtlcSuccessTx = getMempoolTxs(1).head
        assert(bumpedHtlcSuccessTx.txid == bumpedHtlcSuccessTxId)
        assert(htlcSuccessTx.fees < bumpedHtlcSuccessTx.fees)
        htlcSuccessTx = bumpedHtlcSuccessTx
      })
    }
  }

  test("htlc timeout tx not confirming, increasing fees") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val feerate = FeeratePerKw(15_000 sat)
      setFeerate(feerate)
      // The confirmation target for htlc-timeout corresponds to their CLTV: we should claim them asap once the htlc has timed out.
      val (commitTx, _, htlcTimeout) = closeChannelWithHtlcs(f, aliceBlockHeight() + 144)

      val listener = TestProbe()
      system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

      val htlcTimeoutPublisher = createPublisher()
      htlcTimeoutPublisher ! Publish(probe.ref, htlcTimeout)
      generateBlocks(144)
      system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
      val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe), 0, commitTx)
      val htlcTimeoutTxId1 = listener.expectMsgType[TransactionPublished].tx.txid
      val htlcTimeoutTx1 = getMempoolTxs(1).head
      val htlcTimeoutInputs1 = getMempool().head.txIn.map(_.outPoint).toSet
      assert(htlcTimeoutTx1.txid == htlcTimeoutTxId1)

      // A new block is found, and we've already reached the confirmation target, so we bump the fees.
      system.eventStream.publish(CurrentBlockHeight(aliceBlockHeight() + 145))
      val htlcTimeoutTxId2 = listener.expectMsgType[TransactionPublished].tx.txid
      assert(!isInMempool(htlcTimeoutTx1.txid))
      val htlcTimeoutTx2 = getMempoolTxs(1).head
      val htlcTimeoutInputs2 = getMempool().head.txIn.map(_.outPoint).toSet
      assert(htlcTimeoutTx2.txid == htlcTimeoutTxId2)
      assert(htlcTimeoutTx1.fees < htlcTimeoutTx2.fees)
      assert(htlcTimeoutInputs1 == htlcTimeoutInputs2)
      // Once the confirmation target is reach, we should raise the feerate by at least 20% at every block.
      val htlcTimeoutTargetFee = Transactions.weight2fee(feerate * 1.2, htlcTimeoutTx2.weight.toInt)
      assert(htlcTimeoutTargetFee * 0.9 <= htlcTimeoutTx2.fees && htlcTimeoutTx2.fees <= htlcTimeoutTargetFee * 1.1, s"actualFee=${htlcTimeoutTx2.fees} targetFee=$htlcTimeoutTargetFee")
    }
  }

  test("utxos count too low, setting short confirmation target") {
    withFixture(Seq(15 millibtc, 10 millibtc, 5 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val (commitTx, htlcSuccess, _) = closeChannelWithHtlcs(f, aliceBlockHeight() + 144)
      // The HTLC confirmation target is far away, but we have less safe utxos than the configured threshold.
      // We will target a 1-block confirmation to get a safe utxo back as soon as possible.
      val highSafeThresholdParams = alice.underlyingActor.nodeParams.modify(_.onChainFeeConf.feeTargets.safeUtxosThreshold).setTo(10)
      setFeerate(FeeratePerKw(2500 sat))
      val targetFeerate = FeeratePerKw(5000 sat)
      setFeerate(targetFeerate, blockTarget = 2)

      val htlcSuccessPublisher = createPublisher(highSafeThresholdParams)
      htlcSuccessPublisher ! Publish(probe.ref, htlcSuccess)
      val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe), 0, commitTx)
      val htlcSuccessTx = getMempoolTxs(1).head
      val htlcSuccessTargetFee = Transactions.weight2fee(targetFeerate, htlcSuccessTx.weight.toInt)
      assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx.fees && htlcSuccessTx.fees <= htlcSuccessTargetFee * 1.1, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcSuccessTargetFee")
    }
  }

  test("unlock utxos when htlc tx cannot be published") {
    withFixture(Seq(500 millibtc, 200 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val targetFeerate = FeeratePerKw(5_000 sat)
      setFeerate(targetFeerate)
      val (commitTx, htlcSuccess, _) = closeChannelWithHtlcs(f, aliceBlockHeight() + 18)
      val publisher1 = createPublisher()
      publisher1 ! Publish(probe.ref, htlcSuccess)
      val w1 = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w1.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe), 0, commitTx)
      getMempoolTxs(1)

      // we try to publish the htlc-success again (can be caused by a node restart): it will fail to replace the existing
      // one in the mempool but we must ensure we don't leave some utxos locked.
      val publisher2 = createPublisher()
      publisher2 ! Publish(probe.ref, htlcSuccess)
      val w2 = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w2.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe), 0, commitTx)
      val result = probe.expectMsgType[TxRejected]
      assert(result.reason == ConflictingTxUnconfirmed)
      getMempoolTxs(1) // the previous htlc-success tx is still in the mempool

      // our parent will stop us when receiving the TxRejected message.
      publisher2 ! Stop
      awaitAssert({
        wallet.listLockedOutpoints().pipeTo(probe.ref)
        assert(!probe.expectMsgType[Set[OutPoint]].exists(_.txid != commitTx.txid))
      })

      // the first publishing attempt succeeds
      generateBlocks(5)
      system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
      assert(probe.expectMsgType[TxConfirmed].cmd == htlcSuccess)
      publisher1 ! Stop
    }
  }

  test("unlock htlc utxos when stopped before completion") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      setFeerate(FeeratePerKw(5_000 sat))
      val (commitTx, htlcSuccess, _) = closeChannelWithHtlcs(f, aliceBlockHeight() + 48)
      publisher ! Publish(probe.ref, htlcSuccess)
      val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe), 0, commitTx)
      getMempoolTxs(1)

      // We unlock utxos before stopping.
      publisher ! Stop
      awaitAssert({
        wallet.listLockedOutpoints().pipeTo(probe.ref)
        assert(probe.expectMsgType[Set[OutPoint]].isEmpty)
      })
    }
  }

  test("local commit tx confirmed, not publishing claim htlc tx") {
    withFixture(Seq(11 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      // Add htlcs in both directions and ensure that preimages are available.
      addHtlc(25_000_000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      val (r, htlc) = addHtlc(20_000_000 msat, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)
      probe.send(alice, CMD_FULFILL_HTLC(htlc.id, r, replyTo_opt = Some(probe.ref)))
      probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]

      // Force-close channel.
      val localCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.fullySignedLocalCommitTx(alice.underlyingActor.nodeParams.channelKeyManager)
      val remoteCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager)
      assert(remoteCommitTx.tx.txOut.size == 6)
      probe.send(alice, WatchFundingSpentTriggered(remoteCommitTx.tx))
      alice2blockchain.expectMsgType[PublishFinalTx] // claim main output
      val claimHtlcTimeout = alice2blockchain.expectMsgType[PublishReplaceableTx]
      assert(claimHtlcTimeout.txInfo.isInstanceOf[ClaimHtlcTimeoutTx])
      val claimHtlcSuccess = alice2blockchain.expectMsgType[PublishReplaceableTx]
      assert(claimHtlcSuccess.txInfo.isInstanceOf[ClaimHtlcSuccessTx])

      // Ensure local commit tx confirms.
      wallet.publishTransaction(localCommitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(localCommitTx.tx.txid)
      generateBlocks(5)

      // Verify that Claim-HTLC transactions immediately fail to publish.
      setFeerate(FeeratePerKw(5_000 sat))
      val claimHtlcSuccessPublisher = createPublisher()
      claimHtlcSuccessPublisher ! Publish(probe.ref, claimHtlcSuccess)
      val result1 = probe.expectMsgType[TxRejected]
      assert(result1.cmd == claimHtlcSuccess)
      assert(result1.reason == ConflictingTxConfirmed)
      claimHtlcSuccessPublisher ! Stop

      val claimHtlcTimeoutPublisher = createPublisher()
      claimHtlcTimeoutPublisher ! Publish(probe.ref, claimHtlcTimeout)
      val result2 = probe.expectMsgType[TxRejected]
      assert(result2.cmd == claimHtlcTimeout)
      assert(result2.reason == ConflictingTxConfirmed)
      claimHtlcTimeoutPublisher ! Stop
    }
  }

  def remoteCloseChannelWithHtlcs(f: Fixture, overrideHtlcTarget: BlockHeight, nextCommit: Boolean): (Transaction, PublishReplaceableTx, PublishReplaceableTx) = {
    import f._

    // Add htlcs in both directions and ensure that preimages are available.
    val (r, htlc) = addHtlc(20_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    addHtlc(25_000_000 msat, alice, bob, alice2bob, bob2alice)
    if (nextCommit) {
      probe.send(alice, CMD_SIGN(Some(probe.ref)))
      probe.expectMsgType[CommandSuccess[CMD_SIGN]]
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[RevokeAndAck]
      bob2alice.expectMsgType[CommitSig]
    } else {
      crossSign(alice, bob, alice2bob, bob2alice)
    }
    probe.send(alice, CMD_FULFILL_HTLC(htlc.id, r, replyTo_opt = Some(probe.ref)))
    probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]

    // Force-close channel and verify txs sent to watcher.
    val remoteCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager)
    if (bob.stateData.asInstanceOf[DATA_NORMAL].commitments.params.commitmentFormat == DefaultCommitmentFormat) {
      assert(remoteCommitTx.tx.txOut.size == 4)
    } else {
      assert(remoteCommitTx.tx.txOut.size == 6)
    }
    probe.send(alice, WatchFundingSpentTriggered(remoteCommitTx.tx))

    // We make the commit tx confirm because claim-htlc txs have a relative delay when using anchor outputs.
    wallet.publishTransaction(remoteCommitTx.tx).pipeTo(probe.ref)
    probe.expectMsg(remoteCommitTx.tx.txid)
    generateBlocks(1)

    alice2blockchain.expectMsgType[PublishFinalTx] // claim main output
    val claimHtlcSuccess = alice2blockchain.expectMsgType[PublishReplaceableTx]
    assert(claimHtlcSuccess.txInfo.isInstanceOf[ClaimHtlcSuccessTx])
    val claimHtlcSuccessTx = claimHtlcSuccess.txInfo.asInstanceOf[ClaimHtlcSuccessTx].copy(confirmBefore = overrideHtlcTarget)
    val claimHtlcTimeout = alice2blockchain.expectMsgType[PublishReplaceableTx]
    assert(claimHtlcTimeout.txInfo.isInstanceOf[ClaimHtlcTimeoutTx])
    val claimHtlcTimeoutTx = claimHtlcTimeout.txInfo.asInstanceOf[ClaimHtlcTimeoutTx].copy(confirmBefore = overrideHtlcTarget)

    alice2blockchain.expectMsgType[WatchTxConfirmed] // commit tx
    alice2blockchain.expectMsgType[WatchTxConfirmed] // claim main output
    alice2blockchain.expectMsgType[WatchOutputSpent] // claim-htlc-success tx
    alice2blockchain.expectMsgType[WatchOutputSpent] // claim-htlc-timeout tx
    alice2blockchain.expectNoMessage(100 millis)

    (remoteCommitTx.tx, claimHtlcSuccess.copy(txInfo = claimHtlcSuccessTx), claimHtlcTimeout.copy(txInfo = claimHtlcTimeoutTx))
  }

  private def testPublishClaimHtlcSuccess(f: Fixture, remoteCommitTx: Transaction, claimHtlcSuccess: PublishReplaceableTx, targetFeerate: FeeratePerKw): Transaction = {
    import f._

    val claimHtlcSuccessPublisher = createPublisher()
    claimHtlcSuccessPublisher ! Publish(probe.ref, claimHtlcSuccess)
    val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
    w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe), 0, remoteCommitTx)
    val claimHtlcSuccessTx = getMempoolTxs(1).head
    val claimHtlcSuccessTargetFee = Transactions.weight2fee(targetFeerate, claimHtlcSuccessTx.weight.toInt)
    assert(claimHtlcSuccessTargetFee * 0.9 <= claimHtlcSuccessTx.fees && claimHtlcSuccessTx.fees <= claimHtlcSuccessTargetFee * 1.1, s"actualFee=${claimHtlcSuccessTx.fees} targetFee=$claimHtlcSuccessTargetFee")

    generateBlocks(4)
    system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
    val claimHtlcSuccessResult = probe.expectMsgType[TxConfirmed]
    assert(claimHtlcSuccessResult.cmd == claimHtlcSuccess)
    assert(claimHtlcSuccessResult.tx.txIn.map(_.outPoint.txid).contains(remoteCommitTx.txid))
    claimHtlcSuccessPublisher ! Stop
    claimHtlcSuccessResult.tx
  }

  private def testPublishClaimHtlcTimeout(f: Fixture, remoteCommitTx: Transaction, claimHtlcTimeout: PublishReplaceableTx, targetFeerate: FeeratePerKw): Transaction = {
    import f._

    // We start with a low feerate, that will then rise during the CLTV period.
    // The publisher should use the feerate available when the transaction can be published (after the timeout).
    setFeerate(targetFeerate / 2)

    // The Claim-HTLC-timeout will be published after the timeout.
    val claimHtlcTimeoutPublisher = createPublisher()
    claimHtlcTimeoutPublisher ! Publish(probe.ref, claimHtlcTimeout)
    alice2blockchain.expectNoMessage(100 millis)
    generateBlocks(144)
    system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
    setFeerate(targetFeerate) // the feerate is higher than what it was when the channel force-closed
    val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
    w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe), 0, remoteCommitTx)
    val claimHtlcTimeoutTx = getMempoolTxs(1).head
    val claimHtlcTimeoutTargetFee = Transactions.weight2fee(targetFeerate, claimHtlcTimeoutTx.weight.toInt)
    assert(claimHtlcTimeoutTargetFee * 0.9 <= claimHtlcTimeoutTx.fees && claimHtlcTimeoutTx.fees <= claimHtlcTimeoutTargetFee * 1.1, s"actualFee=${claimHtlcTimeoutTx.fees} targetFee=$claimHtlcTimeoutTargetFee")

    generateBlocks(4)
    system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
    val claimHtlcTimeoutResult = probe.expectMsgType[TxConfirmed]
    assert(claimHtlcTimeoutResult.cmd == claimHtlcTimeout)
    assert(claimHtlcTimeoutResult.tx.txIn.map(_.outPoint.txid).contains(remoteCommitTx.txid))
    claimHtlcTimeoutPublisher ! Stop
    claimHtlcTimeoutResult.tx
  }

  test("claim htlc tx feerate high enough, not changing output amount") {
    withFixture(Seq(11 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx()) { f =>
      import f._

      val currentFeerate = alice.underlyingActor.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(2)
      val (remoteCommitTx, claimHtlcSuccess, claimHtlcTimeout) = remoteCloseChannelWithHtlcs(f, aliceBlockHeight() + 50, nextCommit = false)
      val claimHtlcSuccessTx = testPublishClaimHtlcSuccess(f, remoteCommitTx, claimHtlcSuccess, currentFeerate)
      assert(claimHtlcSuccess.txInfo.fee > 0.sat)
      assert(claimHtlcSuccessTx.txIn.length == 1)
      val claimHtlcTimeoutTx = testPublishClaimHtlcTimeout(f, remoteCommitTx, claimHtlcTimeout, currentFeerate)
      assert(claimHtlcTimeout.txInfo.fee > 0.sat)
      assert(claimHtlcTimeoutTx.txIn.length == 1)
    }
  }

  def testClaimHtlcTxFeerateTooLowAnchors(nextCommit: Boolean): Unit = {
    withFixture(Seq(11 millibtc), ChannelTypes.AnchorOutputs()) { f =>
      import f._

      val targetFeerate = FeeratePerKw(15_000 sat)
      val (remoteCommitTx, claimHtlcSuccess, claimHtlcTimeout) = remoteCloseChannelWithHtlcs(f, aliceBlockHeight() + 32, nextCommit)
      // NB: we try to get transactions confirmed *before* their confirmation target, so we aim for a more aggressive block target than what's provided.
      setFeerate(targetFeerate, blockTarget = 12)
      val claimHtlcSuccessTx = testPublishClaimHtlcSuccess(f, remoteCommitTx, claimHtlcSuccess, targetFeerate)
      assert(claimHtlcSuccessTx.txIn.length == 1)
      assert(claimHtlcSuccessTx.txOut.length == 1)
      assert(claimHtlcSuccessTx.txOut.head.amount < claimHtlcSuccess.txInfo.tx.txOut.head.amount)
      val claimHtlcTimeoutTx = testPublishClaimHtlcTimeout(f, remoteCommitTx, claimHtlcTimeout, targetFeerate)
      assert(claimHtlcTimeoutTx.txIn.length == 1)
      assert(claimHtlcTimeoutTx.txOut.length == 1)
      assert(claimHtlcTimeoutTx.txOut.head.amount < claimHtlcTimeout.txInfo.tx.txOut.head.amount)
    }
  }

  test("claim htlc tx feerate too low, lowering output amount") {
    testClaimHtlcTxFeerateTooLowAnchors(nextCommit = false)
  }

  test("claim htlc tx feerate too low, lowering output amount (next remote commit)") {
    testClaimHtlcTxFeerateTooLowAnchors(nextCommit = true)
  }

  def testClaimHtlcTxFeerateTooLowStandard(nextCommit: Boolean): Unit = {
    withFixture(Seq(11 millibtc), ChannelTypes.Standard()) { f =>
      import f._

      val targetFeerate = FeeratePerKw(15_000 sat)
      val (remoteCommitTx, claimHtlcSuccess, claimHtlcTimeout) = remoteCloseChannelWithHtlcs(f, aliceBlockHeight() + 300, nextCommit)

      // The Claim-HTLC-success tx will be immediately published.
      setFeerate(targetFeerate)
      val claimHtlcSuccessPublisher = createPublisher()
      claimHtlcSuccessPublisher ! Publish(probe.ref, claimHtlcSuccess)
      val claimHtlcSuccessTx = getMempoolTxs(1).head
      val claimHtlcSuccessTargetFee = Transactions.weight2fee(targetFeerate, claimHtlcSuccessTx.weight.toInt)
      assert(claimHtlcSuccessTargetFee * 0.9 <= claimHtlcSuccessTx.fees && claimHtlcSuccessTx.fees <= claimHtlcSuccessTargetFee * 1.1, s"actualFee=${claimHtlcSuccessTx.fees} targetFee=$claimHtlcSuccessTargetFee")
      generateBlocks(4)
      system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
      val claimHtlcSuccessResult = probe.expectMsgType[TxConfirmed]
      assert(claimHtlcSuccessResult.cmd == claimHtlcSuccess)
      assert(claimHtlcSuccessResult.tx.txIn.map(_.outPoint.txid).contains(remoteCommitTx.txid))
      claimHtlcSuccessPublisher ! Stop

      // The Claim-HTLC-timeout will be published after the timeout.
      val claimHtlcTimeoutPublisher = createPublisher()
      claimHtlcTimeoutPublisher ! Publish(probe.ref, claimHtlcTimeout)
      alice2blockchain.expectNoMessage(100 millis)
      generateBlocks(144)
      system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
      val claimHtlcTimeoutTx = getMempoolTxs(1).head
      val claimHtlcTimeoutTargetFee = Transactions.weight2fee(targetFeerate, claimHtlcTimeoutTx.weight.toInt)
      assert(claimHtlcTimeoutTargetFee * 0.9 <= claimHtlcTimeoutTx.fees && claimHtlcTimeoutTx.fees <= claimHtlcTimeoutTargetFee * 1.1, s"actualFee=${claimHtlcTimeoutTx.fees} targetFee=$claimHtlcTimeoutTargetFee")

      generateBlocks(4)
      system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
      val claimHtlcTimeoutResult = probe.expectMsgType[TxConfirmed]
      assert(claimHtlcTimeoutResult.cmd == claimHtlcTimeout)
      assert(claimHtlcTimeoutResult.tx.txIn.map(_.outPoint.txid).contains(remoteCommitTx.txid))
      claimHtlcTimeoutPublisher ! Stop
    }
  }

  test("claim htlc tx feerate too low, lowering output amount (standard commitment format)") {
    testClaimHtlcTxFeerateTooLowStandard(nextCommit = false)
  }

  test("claim htlc tx feerate too low, lowering output amount (next remote commit, standard commitment format)") {
    testClaimHtlcTxFeerateTooLowStandard(nextCommit = true)
  }

  test("claim htlc tx feerate way too low, skipping output") {
    withFixture(Seq(11 millibtc), ChannelTypes.AnchorOutputs()) { f =>
      import f._

      val (remoteCommitTx, claimHtlcSuccess, claimHtlcTimeout) = remoteCloseChannelWithHtlcs(f, aliceBlockHeight() + 300, nextCommit = false)

      setFeerate(FeeratePerKw(50_000 sat))
      val claimHtlcSuccessPublisher = createPublisher()
      claimHtlcSuccessPublisher ! Publish(probe.ref, claimHtlcSuccess)
      val w1 = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w1.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe), 0, remoteCommitTx)
      val result1 = probe.expectMsgType[TxRejected]
      assert(result1.cmd == claimHtlcSuccess)
      assert(result1.reason == TxSkipped(retryNextBlock = true))
      claimHtlcSuccessPublisher ! Stop

      val claimHtlcTimeoutPublisher = createPublisher()
      claimHtlcTimeoutPublisher ! Publish(probe.ref, claimHtlcTimeout)
      generateBlocks(144)
      system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
      val w2 = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w2.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe), 0, remoteCommitTx)
      val result2 = probe.expectMsgType[TxRejected]
      assert(result2.cmd == claimHtlcTimeout)
      assert(result2.reason == TxSkipped(retryNextBlock = true))
      claimHtlcTimeoutPublisher ! Stop
    }
  }

  test("claim htlc tx not confirming, lowering output amount again (standard commitment format)") {
    withFixture(Seq(11 millibtc), ChannelTypes.Standard()) { f =>
      import f._

      val initialFeerate = FeeratePerKw(15_000 sat)
      val targetFeerate = FeeratePerKw(20_000 sat)

      val (remoteCommitTx, claimHtlcSuccess, claimHtlcTimeout) = remoteCloseChannelWithHtlcs(f, aliceBlockHeight() + 144, nextCommit = false)

      val listener = TestProbe()
      system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

      // The Claim-HTLC-success tx will be immediately published.
      setFeerate(initialFeerate)
      val claimHtlcSuccessPublisher = createPublisher()
      claimHtlcSuccessPublisher ! Publish(probe.ref, claimHtlcSuccess)
      val claimHtlcSuccessTx1 = getMempoolTxs(1).head
      assert(listener.expectMsgType[TransactionPublished].tx.txid == claimHtlcSuccessTx1.txid)

      setFeerate(targetFeerate)
      system.eventStream.publish(CurrentBlockHeight(aliceBlockHeight() + 5))
      val claimHtlcSuccessTxId2 = listener.expectMsgType[TransactionPublished].tx.txid
      assert(!isInMempool(claimHtlcSuccessTx1.txid))
      val claimHtlcSuccessTx2 = getMempoolTxs(1).head
      assert(claimHtlcSuccessTx2.txid == claimHtlcSuccessTxId2)
      assert(claimHtlcSuccessTx1.fees < claimHtlcSuccessTx2.fees)
      val targetHtlcSuccessFee = Transactions.weight2fee(targetFeerate, claimHtlcSuccessTx2.weight.toInt)
      assert(targetHtlcSuccessFee * 0.9 <= claimHtlcSuccessTx2.fees && claimHtlcSuccessTx2.fees <= targetHtlcSuccessFee * 1.1, s"actualFee=${claimHtlcSuccessTx2.fees} targetFee=$targetHtlcSuccessFee")
      val finalHtlcSuccessTx = getMempool().head
      assert(finalHtlcSuccessTx.txIn.length == 1)
      assert(finalHtlcSuccessTx.txOut.length == 1)
      assert(finalHtlcSuccessTx.txIn.head.outPoint.txid == remoteCommitTx.txid)

      // The Claim-HTLC-timeout will be published after the timeout.
      setFeerate(initialFeerate)
      val claimHtlcTimeoutPublisher = createPublisher()
      claimHtlcTimeoutPublisher ! Publish(probe.ref, claimHtlcTimeout)
      generateBlocks(144)
      system.eventStream.publish(CurrentBlockHeight(aliceBlockHeight() + 144))
      assert(probe.expectMsgType[TxConfirmed].tx.txid == finalHtlcSuccessTx.txid) // the claim-htlc-success is now confirmed
      val claimHtlcTimeoutTx1 = getMempoolTxs(1).head
      assert(listener.expectMsgType[TransactionPublished].tx.txid == claimHtlcTimeoutTx1.txid)

      setFeerate(targetFeerate)
      system.eventStream.publish(CurrentBlockHeight(aliceBlockHeight() + 145))
      val claimHtlcTimeoutTxId2 = listener.expectMsgType[TransactionPublished].tx.txid
      assert(!isInMempool(claimHtlcTimeoutTx1.txid))
      val claimHtlcTimeoutTx2 = getMempoolTxs(1).head
      assert(claimHtlcTimeoutTx2.txid == claimHtlcTimeoutTxId2)
      assert(claimHtlcTimeoutTx1.fees < claimHtlcTimeoutTx2.fees)
      val targetHtlcTimeoutFee = Transactions.weight2fee(targetFeerate, claimHtlcTimeoutTx2.weight.toInt)
      assert(targetHtlcTimeoutFee * 0.9 <= claimHtlcTimeoutTx2.fees && claimHtlcTimeoutTx2.fees <= targetHtlcTimeoutFee * 1.1, s"actualFee=${claimHtlcTimeoutTx2.fees} targetFee=$targetHtlcTimeoutFee")
      val finalHtlcTimeoutTx = getMempool().head
      assert(finalHtlcTimeoutTx.txIn.length == 1)
      assert(finalHtlcTimeoutTx.txOut.length == 1)
      assert(finalHtlcTimeoutTx.txIn.head.outPoint.txid == remoteCommitTx.txid)
    }
  }

  test("claim htlc tx not confirming, but cannot lower output amount again") {
    withFixture(Seq(11 millibtc), ChannelTypes.AnchorOutputs()) { f =>
      import f._

      val (remoteCommitTx, claimHtlcSuccess, _) = remoteCloseChannelWithHtlcs(f, aliceBlockHeight() + 300, nextCommit = false)

      val listener = TestProbe()
      system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

      setFeerate(FeeratePerKw(5_000 sat))
      val claimHtlcSuccessPublisher = createPublisher()
      claimHtlcSuccessPublisher ! Publish(probe.ref, claimHtlcSuccess)
      val w1 = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w1.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe), 0, remoteCommitTx)
      val claimHtlcSuccessTx = getMempoolTxs(1).head
      assert(listener.expectMsgType[TransactionPublished].tx.txid == claimHtlcSuccessTx.txid)

      // New blocks are found and the feerate is higher, but the htlc would become dust, so we don't bump the fees.
      setFeerate(FeeratePerKw(50_000 sat))
      system.eventStream.publish(CurrentBlockHeight(aliceBlockHeight() + 5))
      probe.expectNoMessage(500 millis)
      val mempoolTxs = getMempool()
      assert(mempoolTxs.map(_.txid).toSet == Set(claimHtlcSuccessTx.txid))
    }
  }

}
