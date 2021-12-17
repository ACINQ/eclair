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
import fr.acinq.bitcoin.{BtcAmount, ByteVector32, MilliBtcDouble, OutPoint, SatoshiLong, Script, ScriptWitness, Transaction, TxIn, TxOut}
import fr.acinq.eclair.TestConstants.TestFeeEstimator
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.MempoolTx
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BitcoinCoreClient, BitcoinJsonRPCClient}
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.publish.ReplaceableTxPublisher.{Publish, Stop}
import fr.acinq.eclair.channel.publish.TxPublisher.TxRejectedReason._
import fr.acinq.eclair.channel.publish.TxPublisher._
import fr.acinq.eclair.channel.states.{ChannelStateTestsHelperMethods, ChannelStateTestsTags}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.{MilliSatoshiLong, TestConstants, TestKitBaseClass, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.{BeforeAndAfterAll, Tag}

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.util.Random

class ReplaceableTxPublisherSpec extends TestKitBaseClass with AnyFunSuiteLike with BitcoindService with ChannelStateTestsHelperMethods with BeforeAndAfterAll {

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

    def createPublisher(): ActorRef[ReplaceableTxPublisher.Command] = {
      system.spawnAnonymous(ReplaceableTxPublisher(alice.underlyingActor.nodeParams, wallet, alice2blockchain.ref, TxPublishLogContext(UUID.randomUUID(), randomKey().publicKey, None)))
    }

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

    def getMempool: Seq[Transaction] = {
      wallet.getMempool().pipeTo(probe.ref)
      probe.expectMsgType[Seq[Transaction]]
    }

    def getMempoolTxs(expectedTxCount: Int): Seq[MempoolTx] = {
      awaitCond(getMempool.size == expectedTxCount, interval = 200 milliseconds)
      getMempool.map(tx => {
        wallet.getMempoolTx(tx.txid).pipeTo(probe.ref)
        probe.expectMsgType[MempoolTx]
      })
    }

  }

  // NB: we can't use ScalaTest's fixtures, they would see uninitialized bitcoind fields because they sandbox each test.
  private def withFixture(utxos: Seq[BtcAmount], channelType: SupportedChannelType, testFun: Fixture => Any): Unit = {
    // Create a unique wallet for this test and ensure it has some btc.
    val testId = UUID.randomUUID()
    val walletRpcClient = createWallet(s"lightning-$testId")
    val walletClient = new BitcoinCoreClient(walletRpcClient)
    val probe = TestProbe()

    // Ensure our wallet has some funds.
    utxos.foreach(amount => {
      walletClient.getReceiveAddress().pipeTo(probe.ref)
      val walletAddress = probe.expectMsgType[String]
      sendToAddress(walletAddress, amount, probe)
    })
    generateBlocks(1)

    // Setup a valid channel between alice and bob.
    val blockCount = new AtomicLong()
    blockCount.set(currentBlockHeight(probe))
    val aliceNodeParams = TestConstants.Alice.nodeParams.copy(blockCount = blockCount)
    val setup = init(aliceNodeParams, TestConstants.Bob.nodeParams.copy(blockCount = blockCount), walletClient)
    val testTags = channelType match {
      case ChannelTypes.AnchorOutputsZeroFeeHtlcTx => Set(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)
      case ChannelTypes.AnchorOutputs => Set(ChannelStateTestsTags.AnchorOutputs)
      case ChannelTypes.StaticRemoteKey => Set(ChannelStateTestsTags.StaticRemoteKey)
      case _ => Set.empty[String]
    }
    reachNormal(setup, testTags)
    import setup._
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)

    // Generate blocks to ensure the funding tx is confirmed.
    generateBlocks(1)

    // Execute our test.
    val publisher = system.spawn(ReplaceableTxPublisher(aliceNodeParams, walletClient, alice2blockchain.ref, TxPublishLogContext(testId, TestConstants.Bob.nodeParams.nodeId, None)), testId.toString)
    try {
      testFun(Fixture(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, walletClient, walletRpcClient, publisher, probe))
    } finally {
      publisher ! Stop
    }
  }

  def closeChannelWithoutHtlcs(f: Fixture): (PublishFinalTx, PublishReplaceableTx) = {
    import f._

    val commitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.fullySignedLocalCommitTx(alice.underlyingActor.nodeParams.channelKeyManager)
    probe.send(alice, CMD_FORCECLOSE(probe.ref))
    probe.expectMsgType[CommandSuccess[CMD_FORCECLOSE]]

    // Forward the commit tx to the publisher.
    val publishCommitTx = alice2blockchain.expectMsg(PublishFinalTx(commitTx, commitTx.fee, None))
    // Forward the anchor tx to the publisher.
    val publishAnchor = alice2blockchain.expectMsgType[PublishReplaceableTx]
    assert(publishAnchor.txInfo.input.outPoint.txid === commitTx.tx.txid)
    assert(publishAnchor.txInfo.isInstanceOf[ClaimLocalAnchorOutputTx])

    (publishCommitTx, publishAnchor)
  }

  test("commit tx feerate high enough, not spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      val commitFeerate = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.commitTxFeerate
      setFeerate(commitFeerate)
      val (_, anchorTx) = closeChannelWithoutHtlcs(f)
      publisher ! Publish(probe.ref, anchorTx)

      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd === anchorTx)
      assert(result.reason === TxSkipped(retryNextBlock = true))
    })
  }

  test("commit tx confirmed, not spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f)
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)
      generateBlocks(1)

      setFeerate(FeeratePerKw(10_000 sat))
      publisher ! Publish(probe.ref, anchorTx)
      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd === anchorTx)
      assert(result.reason === TxSkipped(retryNextBlock = false))
    })
  }

  test("commit tx feerate high enough and commit tx confirmed, not spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      val commitFeerate = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.commitTxFeerate
      setFeerate(commitFeerate)
      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f)
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)
      generateBlocks(1)

      publisher ! Publish(probe.ref, anchorTx)
      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd === anchorTx)
      assert(result.reason === TxSkipped(retryNextBlock = false))
    })
  }

  test("remote commit tx confirmed, not spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      val remoteCommit = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager)
      val (_, anchorTx) = closeChannelWithoutHtlcs(f)
      wallet.publishTransaction(remoteCommit.tx).pipeTo(probe.ref)
      probe.expectMsg(remoteCommit.tx.txid)
      generateBlocks(1)

      setFeerate(FeeratePerKw(10_000 sat))
      publisher ! Publish(probe.ref, anchorTx)
      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd === anchorTx)
      assert(result.reason === TxSkipped(retryNextBlock = false))
    })
  }

  test("remote commit tx published, not spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      val remoteCommit = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager)
      val (_, anchorTx) = closeChannelWithoutHtlcs(f)
      wallet.publishTransaction(remoteCommit.tx).pipeTo(probe.ref)
      probe.expectMsg(remoteCommit.tx.txid)

      setFeerate(FeeratePerKw(10_000 sat))
      publisher ! Publish(probe.ref, anchorTx)
      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd === anchorTx)
      // When the remote commit tx is still unconfirmed, we want to retry in case it is evicted from the mempool and our
      // commit is then published.
      assert(result.reason === TxSkipped(retryNextBlock = true))
    })
  }

  test("remote commit tx replaces local commit tx, not spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      val remoteCommit = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.commitTxFeerate === FeeratePerKw(2500 sat))

      // We lower the feerate to make it easy to replace our commit tx by theirs in the mempool.
      val lowFeerate = FeeratePerKw(500 sat)
      updateFee(lowFeerate, alice, bob, alice2bob, bob2alice)
      val (localCommit, anchorTx) = closeChannelWithoutHtlcs(f)
      // We set a slightly higher feerate to ensure the local anchor is used.
      setFeerate(FeeratePerKw(600 sat))
      publisher ! Publish(probe.ref, anchorTx)
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(localCommit.tx.txid))

      // Our commit tx is replaced by theirs.
      wallet.publishTransaction(remoteCommit.tx).pipeTo(probe.ref)
      probe.expectMsg(remoteCommit.tx.txid)
      generateBlocks(1)
      system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))

      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd === anchorTx)
      assert(result.reason === WalletInputGone)
    })
  }

  test("not enough funds to increase commit tx feerate") {
    withFixture(Seq(10.5 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      // close channel and wait for the commit tx to be published, anchor will not be published because we don't have enough funds
      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f)
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)

      setFeerate(FeeratePerKw(25_000 sat))
      publisher ! Publish(probe.ref, anchorTx)
      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd === anchorTx)
      // When the remote commit tx is still unconfirmed, we want to retry in case it is evicted from the mempool and our
      // commit is then published.
      assert(result.reason === CouldNotFund)
    })
  }

  test("commit tx feerate too low, spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      val (commitTx, anchorTx) = {
        val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f)
        (commitTx, anchorTx.copy(deadline = alice.underlyingActor.nodeParams.currentBlockHeight + 1))
      }
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)
      assert(getMempool.length === 1)

      val targetFeerate = FeeratePerKw(3000 sat)
      setFeerate(targetFeerate, blockTarget = 1)
      publisher ! Publish(probe.ref, anchorTx)
      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(commitTx.tx.txid))

      val targetFee = Transactions.weight2fee(targetFeerate, mempoolTxs.map(_.weight).sum.toInt)
      val actualFee = mempoolTxs.map(_.fees).sum
      assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee")

      generateBlocks(5)
      system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
      val result = probe.expectMsgType[TxConfirmed]
      assert(result.cmd === anchorTx)
      assert(result.tx.txIn.map(_.outPoint.txid).contains(commitTx.tx.txid))
      assert(mempoolTxs.map(_.txid).contains(result.tx.txid))
    })
  }

  test("commit tx not published, publishing it and spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      val (commitTx, anchorTx) = {
        val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f)
        (commitTx, anchorTx.copy(deadline = alice.underlyingActor.nodeParams.currentBlockHeight + 2))
      }
      assert(getMempool.isEmpty)

      val targetFeerate = FeeratePerKw(3000 sat)
      setFeerate(targetFeerate, blockTarget = 2)
      publisher ! Publish(probe.ref, anchorTx)
      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(commitTx.tx.txid))

      val targetFee = Transactions.weight2fee(targetFeerate, mempoolTxs.map(_.weight).sum.toInt)
      val actualFee = mempoolTxs.map(_.fees).sum
      assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee")

      generateBlocks(5)
      system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
      val result = probe.expectMsgType[TxConfirmed]
      assert(result.cmd === anchorTx)
      assert(result.tx.txIn.map(_.outPoint.txid).contains(commitTx.tx.txid))
      assert(mempoolTxs.map(_.txid).contains(result.tx.txid))
    })
  }

  test("commit tx feerate too low, spending anchor outputs with multiple wallet inputs") {
    val utxos = Seq(
      // channel funding
      10 millibtc,
      // bumping utxos
      25000 sat,
      22000 sat,
      15000 sat
    )
    withFixture(utxos, ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      // NB: when we get close to the deadline, we use aggressive block target: in this case we are 6 blocks away from
      // the deadline, and we use a block target of 2 to ensure we confirm before the deadline.
      val targetFeerate = FeeratePerKw(10_000 sat)
      setFeerate(targetFeerate, blockTarget = 2)
      val (commitTx, anchorTx) = {
        val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f)
        (commitTx, anchorTx.copy(deadline = alice.underlyingActor.nodeParams.currentBlockHeight + 6))
      }
      publisher ! Publish(probe.ref, anchorTx)

      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(commitTx.tx.txid))

      val targetFee = Transactions.weight2fee(targetFeerate, mempoolTxs.map(_.weight).sum.toInt)
      val actualFee = mempoolTxs.map(_.fees).sum
      assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee")

      generateBlocks(5)
      system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
      val result = probe.expectMsgType[TxConfirmed]
      assert(result.cmd === anchorTx)
      assert(result.tx.txIn.map(_.outPoint.txid).contains(commitTx.tx.txid))
      assert(result.tx.txIn.length > 2) // we added more than 1 wallet input
      assert(mempoolTxs.map(_.txid).contains(result.tx.txid))
    })
  }

  test("unlock utxos when anchor tx cannot be published") {
    withFixture(Seq(500 millibtc, 200 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      val targetFeerate = FeeratePerKw(3000 sat)
      setFeerate(targetFeerate)
      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f)
      publisher ! Publish(probe.ref, anchorTx)

      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(commitTx.tx.txid))

      // we try to publish the anchor again (can be caused by a node restart): it will fail to replace the existing one
      // in the mempool but we must ensure we don't leave some utxos locked.
      val publisher2 = createPublisher()
      publisher2 ! Publish(probe.ref, anchorTx)
      val result = probe.expectMsgType[TxRejected]
      assert(result.reason === ConflictingTxUnconfirmed)
      getMempoolTxs(2) // the previous anchor tx and the commit tx are still in the mempool

      // our parent will stop us when receiving the TxRejected message.
      publisher2 ! Stop
      awaitCond(getLocks(probe, walletRpcClient).isEmpty)

      // the first publishing attempt succeeds
      generateBlocks(5)
      system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
      assert(probe.expectMsgType[TxConfirmed].cmd === anchorTx)
    })
  }

  test("unlock anchor utxos when stopped before completion") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      val targetFeerate = FeeratePerKw(3000 sat)
      setFeerate(targetFeerate)
      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f)
      publisher ! Publish(probe.ref, anchorTx)

      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(commitTx.tx.txid))

      // we unlock utxos before stopping
      publisher ! Stop
      awaitCond(getLocks(probe, walletRpcClient).isEmpty)
    })
  }

  test("adjust anchor tx change amount", Tag("fuzzy")) {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      val commitFeerate = f.alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.commitTxFeerate
      assert(commitFeerate < TestConstants.feeratePerKw)
      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f)
      val anchorTxInfo = anchorTx.txInfo.asInstanceOf[ClaimLocalAnchorOutputTx]
      val dustLimit = anchorTx.commitments.localParams.dustLimit
      for (_ <- 1 to 100) {
        val walletInputsCount = 1 + Random.nextInt(5)
        val walletInputs = (1 to walletInputsCount).map(_ => TxIn(OutPoint(randomBytes32(), 0), Nil, 0))
        val amountIn = dustLimit * walletInputsCount + Random.nextInt(25_000_000).sat
        val amountOut = dustLimit + Random.nextLong(amountIn.toLong).sat
        val unsignedTx = anchorTxInfo.copy(tx = anchorTxInfo.tx.copy(
          txIn = anchorTxInfo.tx.txIn ++ walletInputs,
          txOut = TxOut(amountOut, Script.pay2wpkh(randomKey().publicKey)) :: Nil,
        ))
        val (adjustedTx, fee) = ReplaceableTxPublisher.adjustAnchorOutputChange(unsignedTx, commitTx.tx, amountIn, commitFeerate, TestConstants.feeratePerKw, dustLimit)
        assert(fee === amountIn - adjustedTx.tx.txOut.map(_.amount).sum)
        assert(adjustedTx.tx.txIn.size === unsignedTx.tx.txIn.size)
        assert(adjustedTx.tx.txOut.size === 1)
        assert(adjustedTx.tx.txOut.head.amount >= dustLimit)
        if (adjustedTx.tx.txOut.head.amount > dustLimit) {
          // Simulate tx signing to check final feerate.
          val signedTx = {
            val anchorSigned = Transactions.addSigs(adjustedTx, Transactions.PlaceHolderSig)
            val signedWalletInputs = anchorSigned.tx.txIn.tail.map(txIn => txIn.copy(witness = ScriptWitness(Seq(Scripts.der(Transactions.PlaceHolderSig), Transactions.PlaceHolderPubKey.value))))
            anchorSigned.tx.copy(txIn = anchorSigned.tx.txIn.head +: signedWalletInputs)
          }
          // We want the package anchor tx + commit tx to reach our target feerate, but the commit tx already pays a (smaller) fee
          val targetFee = Transactions.weight2fee(TestConstants.feeratePerKw, signedTx.weight() + commitTx.tx.weight()) - Transactions.weight2fee(commitFeerate, commitTx.tx.weight())
          val actualFee = amountIn - signedTx.txOut.map(_.amount).sum
          assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee amountIn=$amountIn tx=$signedTx")
        }
      }
    })
  }

  test("remote commit tx confirmed, not publishing htlc tx") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
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
      val remoteCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager)
      wallet.publishTransaction(remoteCommitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(remoteCommitTx.tx.txid)
      generateBlocks(5)

      // Verify that HTLC transactions immediately fail to publish.
      setFeerate(FeeratePerKw(15_000 sat))
      val htlcSuccessPublisher = createPublisher()
      htlcSuccessPublisher ! Publish(probe.ref, htlcSuccess)
      val result1 = probe.expectMsgType[TxRejected]
      assert(result1.cmd === htlcSuccess)
      assert(result1.reason === ConflictingTxConfirmed)
      htlcSuccessPublisher ! Stop

      val htlcTimeoutPublisher = createPublisher()
      htlcTimeoutPublisher ! Publish(probe.ref, htlcTimeout)
      val result2 = probe.expectMsgType[TxRejected]
      assert(result2.cmd === htlcTimeout)
      assert(result2.reason === ConflictingTxConfirmed)
      htlcTimeoutPublisher ! Stop
    })
  }

  def closeChannelWithHtlcs(f: Fixture): (Transaction, PublishReplaceableTx, PublishReplaceableTx) = {
    import f._

    // Add htlcs in both directions and ensure that preimages are available.
    addHtlc(5_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val (r, htlc) = addHtlc(4_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    probe.send(alice, CMD_FULFILL_HTLC(htlc.id, r, replyTo_opt = Some(probe.ref)))
    probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]

    // Force-close channel and verify txs sent to watcher.
    val commitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.fullySignedLocalCommitTx(alice.underlyingActor.nodeParams.channelKeyManager)
    assert(commitTx.tx.txOut.size === 6)
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
    val htlcTimeout = alice2blockchain.expectMsgType[PublishReplaceableTx]
    assert(htlcTimeout.txInfo.isInstanceOf[HtlcTimeoutTx])

    alice2blockchain.expectMsgType[WatchTxConfirmed] // commit tx
    alice2blockchain.expectMsgType[WatchTxConfirmed] // claim main output
    alice2blockchain.expectMsgType[WatchOutputSpent] // claim-anchor tx
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc-success tx
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc-timeout tx
    alice2blockchain.expectNoMessage(100 millis)

    (commitTx.tx, htlcSuccess, htlcTimeout)
  }

  test("not enough funds to increase htlc tx feerate") {
    withFixture(Seq(10.5 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      val (commitTx, htlcSuccess) = {
        val (commitTx, htlcSuccess, _) = closeChannelWithHtlcs(f)
        (commitTx, htlcSuccess.copy(deadline = alice.underlyingActor.nodeParams.currentBlockHeight))
      }
      val htlcSuccessPublisher = createPublisher()
      setFeerate(FeeratePerKw(75_000 sat), blockTarget = 1)
      htlcSuccessPublisher ! Publish(probe.ref, htlcSuccess)
      val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe).toInt, 0, commitTx)

      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd === htlcSuccess)
      assert(result.reason === CouldNotFund)
      htlcSuccessPublisher ! Stop
    })
  }

  private def testPublishHtlcSuccess(f: Fixture, commitTx: Transaction, htlcSuccess: PublishReplaceableTx, targetFeerate: FeeratePerKw): Transaction = {
    import f._

    // The HTLC-success tx will be immediately published since the commit tx is confirmed.
    // NB: when we get close to the deadline (here, 10 blocks from it) we use an aggressive block target (in this case, 2)
    setFeerate(targetFeerate, blockTarget = 2)
    val htlcSuccessWithDeadline = htlcSuccess.copy(deadline = alice.underlyingActor.nodeParams.currentBlockHeight + 10)
    val htlcSuccessPublisher = createPublisher()
    htlcSuccessPublisher ! Publish(probe.ref, htlcSuccessWithDeadline)
    val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
    w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe).toInt, 0, commitTx)
    val htlcSuccessTx = getMempoolTxs(1).head
    val htlcSuccessTargetFee = Transactions.weight2fee(targetFeerate, htlcSuccessTx.weight.toInt)
    assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx.fees && htlcSuccessTx.fees <= htlcSuccessTargetFee * 1.4, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcSuccessTargetFee")

    generateBlocks(4)
    system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
    val htlcSuccessResult = probe.expectMsgType[TxConfirmed]
    assert(htlcSuccessResult.cmd === htlcSuccessWithDeadline)
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
    val htlcTimeoutWithDeadline = htlcTimeout.copy(deadline = alice.underlyingActor.nodeParams.currentBlockHeight)
    htlcTimeoutPublisher ! Publish(probe.ref, htlcTimeoutWithDeadline)
    alice2blockchain.expectNoMessage(100 millis)
    generateBlocks(144)
    system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
    setFeerate(targetFeerate, blockTarget = 1) // the feerate is higher than what it was when the channel force-closed
    val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
    w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe).toInt, 0, commitTx)
    val htlcTimeoutTx = getMempoolTxs(1).head
    val htlcTimeoutTargetFee = Transactions.weight2fee(targetFeerate, htlcTimeoutTx.weight.toInt)
    assert(htlcTimeoutTargetFee * 0.9 <= htlcTimeoutTx.fees && htlcTimeoutTx.fees <= htlcTimeoutTargetFee * 1.4, s"actualFee=${htlcTimeoutTx.fees} targetFee=$htlcTimeoutTargetFee")

    generateBlocks(4)
    system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
    val htlcTimeoutResult = probe.expectMsgType[TxConfirmed]
    assert(htlcTimeoutResult.cmd === htlcTimeoutWithDeadline)
    assert(htlcTimeoutResult.tx.txIn.map(_.outPoint.txid).contains(commitTx.txid))
    htlcTimeoutPublisher ! Stop
    htlcTimeoutResult.tx
  }

  test("htlc tx feerate high enough, not adding wallet inputs") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputs, f => {
      import f._

      val currentFeerate = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.commitTxFeerate
      val (commitTx, htlcSuccess, htlcTimeout) = closeChannelWithHtlcs(f)
      val htlcSuccessTx = testPublishHtlcSuccess(f, commitTx, htlcSuccess, currentFeerate)
      assert(htlcSuccess.txInfo.fee > 0.sat)
      assert(htlcSuccessTx.txIn.length === 1)
      val htlcTimeoutTx = testPublishHtlcTimeout(f, commitTx, htlcTimeout, currentFeerate)
      assert(htlcTimeout.txInfo.fee > 0.sat)
      assert(htlcTimeoutTx.txIn.length === 1)
    })
  }

  test("htlc tx feerate too low, adding wallet inputs") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputs, f => {
      val targetFeerate = FeeratePerKw(15_000 sat)
      val (commitTx, htlcSuccess, htlcTimeout) = closeChannelWithHtlcs(f)
      val htlcSuccessTx = testPublishHtlcSuccess(f, commitTx, htlcSuccess, targetFeerate)
      assert(htlcSuccessTx.txIn.length > 1)
      val htlcTimeoutTx = testPublishHtlcTimeout(f, commitTx, htlcTimeout, targetFeerate)
      assert(htlcTimeoutTx.txIn.length > 1)
    })
  }

  test("htlc tx feerate zero, adding wallet inputs") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      val targetFeerate = FeeratePerKw(15_000 sat)
      val (commitTx, htlcSuccess, htlcTimeout) = closeChannelWithHtlcs(f)
      assert(htlcSuccess.txInfo.fee === 0.sat)
      val htlcSuccessTx = testPublishHtlcSuccess(f, commitTx, htlcSuccess, targetFeerate)
      assert(htlcSuccessTx.txIn.length > 1)
      assert(htlcTimeout.txInfo.fee === 0.sat)
      val htlcTimeoutTx = testPublishHtlcTimeout(f, commitTx, htlcTimeout, targetFeerate)
      assert(htlcTimeoutTx.txIn.length > 1)
    })
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
    withFixture(utxos, ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      val targetFeerate = FeeratePerKw(8_000 sat)
      val (commitTx, htlcSuccess, htlcTimeout) = closeChannelWithHtlcs(f)
      val htlcSuccessTx = testPublishHtlcSuccess(f, commitTx, htlcSuccess, targetFeerate)
      assert(htlcSuccessTx.txIn.length > 2)
      val htlcTimeoutTx = testPublishHtlcTimeout(f, commitTx, htlcTimeout, targetFeerate)
      assert(htlcTimeoutTx.txIn.length > 2)
    })
  }

  test("unlock utxos when htlc tx cannot be published") {
    withFixture(Seq(500 millibtc, 200 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      val targetFeerate = FeeratePerKw(5_000 sat)
      setFeerate(targetFeerate)
      val (commitTx, htlcSuccess, _) = closeChannelWithHtlcs(f)
      val publisher1 = createPublisher()
      publisher1 ! Publish(probe.ref, htlcSuccess)
      val w1 = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w1.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe).toInt, 0, commitTx)
      getMempoolTxs(1)

      // we try to publish the htlc-success again (can be caused by a node restart): it will fail to replace the existing
      // one in the mempool but we must ensure we don't leave some utxos locked.
      val publisher2 = createPublisher()
      publisher2 ! Publish(probe.ref, htlcSuccess)
      val w2 = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w2.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe).toInt, 0, commitTx)
      val result = probe.expectMsgType[TxRejected]
      assert(result.reason === ConflictingTxUnconfirmed)
      getMempoolTxs(1) // the previous htlc-success tx is still in the mempool

      // our parent will stop us when receiving the TxRejected message.
      publisher2 ! Stop
      awaitCond(getLocks(probe, walletRpcClient).isEmpty)

      // the first publishing attempt succeeds
      generateBlocks(5)
      system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
      assert(probe.expectMsgType[TxConfirmed].cmd === htlcSuccess)
      publisher1 ! Stop
    })
  }

  test("unlock htlc utxos when stopped before completion") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      setFeerate(FeeratePerKw(5_000 sat))
      val (commitTx, htlcSuccess, _) = closeChannelWithHtlcs(f)
      publisher ! Publish(probe.ref, htlcSuccess)
      val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe).toInt, 0, commitTx)
      getMempoolTxs(1)

      // We unlock utxos before stopping.
      publisher ! Stop
      awaitCond(getLocks(probe, walletRpcClient).isEmpty)
    })
  }

  test("adjust htlc tx change amount", Tag("fuzzy")) {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      val (_, htlcSuccess, htlcTimeout) = closeChannelWithHtlcs(f)
      val commitments = htlcSuccess.commitments
      val dustLimit = commitments.localParams.dustLimit
      val targetFeerate = TestConstants.feeratePerKw
      for (_ <- 1 to 100) {
        val walletInputsCount = 1 + Random.nextInt(5)
        val walletInputs = (1 to walletInputsCount).map(_ => TxIn(OutPoint(randomBytes32(), 0), Nil, 0))
        val walletAmountIn = dustLimit * walletInputsCount + Random.nextInt(25_000_000).sat
        val changeOutput = TxOut(Random.nextLong(walletAmountIn.toLong).sat, Script.pay2wpkh(randomKey().publicKey))
        val unsignedHtlcSuccessTx = htlcSuccess.txInfo.asInstanceOf[HtlcSuccessTx].copy(tx = htlcSuccess.txInfo.tx.copy(
          txIn = htlcSuccess.txInfo.tx.txIn ++ walletInputs,
          txOut = htlcSuccess.txInfo.tx.txOut ++ Seq(changeOutput)
        ))
        val unsignedHtlcTimeoutTx = htlcTimeout.txInfo.asInstanceOf[HtlcTimeoutTx].copy(tx = htlcTimeout.txInfo.tx.copy(
          txIn = htlcTimeout.txInfo.tx.txIn ++ walletInputs,
          txOut = htlcTimeout.txInfo.tx.txOut ++ Seq(changeOutput)
        ))
        for (unsignedTx <- Seq(unsignedHtlcSuccessTx, unsignedHtlcTimeoutTx)) {
          val totalAmountIn = unsignedTx.input.txOut.amount + walletAmountIn
          val (adjustedTx, fee) = ReplaceableTxPublisher.adjustHtlcTxChange(unsignedTx, totalAmountIn, targetFeerate, commitments)
          assert(fee === totalAmountIn - adjustedTx.tx.txOut.map(_.amount).sum)
          assert(adjustedTx.tx.txIn.size === unsignedTx.tx.txIn.size)
          assert(adjustedTx.tx.txOut.size === 1 || adjustedTx.tx.txOut.size === 2)
          if (adjustedTx.tx.txOut.size == 2) {
            // Simulate tx signing to check final feerate.
            val signedTx = {
              val htlcSigned = adjustedTx match {
                case tx: HtlcSuccessTx => Transactions.addSigs(tx, Transactions.PlaceHolderSig, Transactions.PlaceHolderSig, ByteVector32.Zeroes, commitments.commitmentFormat)
                case tx: HtlcTimeoutTx => Transactions.addSigs(tx, Transactions.PlaceHolderSig, Transactions.PlaceHolderSig, commitments.commitmentFormat)
              }
              val signedWalletInputs = htlcSigned.tx.txIn.tail.map(txIn => txIn.copy(witness = ScriptWitness(Seq(Scripts.der(Transactions.PlaceHolderSig), Transactions.PlaceHolderPubKey.value))))
              htlcSigned.tx.copy(txIn = htlcSigned.tx.txIn.head +: signedWalletInputs)
            }
            val targetFee = Transactions.weight2fee(targetFeerate, signedTx.weight())
            val actualFee = totalAmountIn - signedTx.txOut.map(_.amount).sum
            assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee amountIn=$walletAmountIn tx=$signedTx")
          }
        }
      }
    })
  }

  test("local commit tx confirmed, not publishing claim htlc tx") {
    withFixture(Seq(11 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      // Add htlcs in both directions and ensure that preimages are available.
      addHtlc(25_000_000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      val (r, htlc) = addHtlc(20_000_000 msat, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)
      probe.send(alice, CMD_FULFILL_HTLC(htlc.id, r, replyTo_opt = Some(probe.ref)))
      probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]

      // Force-close channel.
      val localCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.fullySignedLocalCommitTx(alice.underlyingActor.nodeParams.channelKeyManager)
      val remoteCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager)
      assert(remoteCommitTx.tx.txOut.size === 6)
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
      assert(result1.cmd === claimHtlcSuccess)
      assert(result1.reason === ConflictingTxConfirmed)
      claimHtlcSuccessPublisher ! Stop

      val claimHtlcTimeoutPublisher = createPublisher()
      claimHtlcTimeoutPublisher ! Publish(probe.ref, claimHtlcTimeout)
      val result2 = probe.expectMsgType[TxRejected]
      assert(result2.cmd === claimHtlcTimeout)
      assert(result2.reason === ConflictingTxConfirmed)
      claimHtlcTimeoutPublisher ! Stop
    })
  }

  def remoteCloseChannelWithHtlcs(f: Fixture): (Transaction, PublishReplaceableTx, PublishReplaceableTx) = {
    import f._

    // Add htlcs in both directions and ensure that preimages are available.
    addHtlc(25_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val (r, htlc) = addHtlc(20_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    probe.send(alice, CMD_FULFILL_HTLC(htlc.id, r, replyTo_opt = Some(probe.ref)))
    probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]

    // Force-close channel and verify txs sent to watcher.
    val remoteCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.fullySignedLocalCommitTx(bob.underlyingActor.nodeParams.channelKeyManager)
    if (bob.stateData.asInstanceOf[DATA_NORMAL].commitments.commitmentFormat == DefaultCommitmentFormat) {
      assert(remoteCommitTx.tx.txOut.size === 4)
    } else {
      assert(remoteCommitTx.tx.txOut.size === 6)
    }
    probe.send(alice, WatchFundingSpentTriggered(remoteCommitTx.tx))

    // We make the commit tx confirm because claim-htlc txs have a relative delay when using anchor outputs.
    wallet.publishTransaction(remoteCommitTx.tx).pipeTo(probe.ref)
    probe.expectMsg(remoteCommitTx.tx.txid)
    generateBlocks(1)

    alice2blockchain.expectMsgType[PublishFinalTx] // claim main output
    val claimHtlcTimeout = alice2blockchain.expectMsgType[PublishReplaceableTx]
    assert(claimHtlcTimeout.txInfo.isInstanceOf[ClaimHtlcTimeoutTx])
    val claimHtlcSuccess = alice2blockchain.expectMsgType[PublishReplaceableTx]
    assert(claimHtlcSuccess.txInfo.isInstanceOf[ClaimHtlcSuccessTx])

    alice2blockchain.expectMsgType[WatchTxConfirmed] // commit tx
    alice2blockchain.expectMsgType[WatchTxConfirmed] // claim main output
    alice2blockchain.expectMsgType[WatchOutputSpent] // claim-htlc-success tx
    alice2blockchain.expectMsgType[WatchOutputSpent] // claim-htlc-timeout tx
    alice2blockchain.expectNoMessage(100 millis)

    (remoteCommitTx.tx, claimHtlcSuccess, claimHtlcTimeout)
  }

  private def testPublishClaimHtlcSuccess(f: Fixture, remoteCommitTx: Transaction, claimHtlcSuccess: PublishReplaceableTx, targetFeerate: FeeratePerKw): Transaction = {
    import f._

    // The Claim-HTLC-success tx will be immediately published since the commit tx is confirmed.
    setFeerate(targetFeerate, blockTarget = 2)
    val claimHtlcSuccessPublisher = createPublisher()
    val claimHtlcSuccessWithDeadline = claimHtlcSuccess.copy(deadline = alice.underlyingActor.nodeParams.currentBlockHeight + 4)
    claimHtlcSuccessPublisher ! Publish(probe.ref, claimHtlcSuccessWithDeadline)
    val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
    w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe).toInt, 0, remoteCommitTx)
    val claimHtlcSuccessTx = getMempoolTxs(1).head
    val claimHtlcSuccessTargetFee = Transactions.weight2fee(targetFeerate, claimHtlcSuccessTx.weight.toInt)
    assert(claimHtlcSuccessTargetFee * 0.9 <= claimHtlcSuccessTx.fees && claimHtlcSuccessTx.fees <= claimHtlcSuccessTargetFee * 1.1, s"actualFee=${claimHtlcSuccessTx.fees} targetFee=$claimHtlcSuccessTargetFee")

    generateBlocks(4)
    system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
    val claimHtlcSuccessResult = probe.expectMsgType[TxConfirmed]
    assert(claimHtlcSuccessResult.cmd === claimHtlcSuccessWithDeadline)
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
    val claimHtlcTimeoutWithDeadline = claimHtlcTimeout.copy(deadline = alice.underlyingActor.nodeParams.currentBlockHeight)
    claimHtlcTimeoutPublisher ! Publish(probe.ref, claimHtlcTimeoutWithDeadline)
    alice2blockchain.expectNoMessage(100 millis)
    generateBlocks(144)
    system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
    setFeerate(targetFeerate, blockTarget = 1) // the feerate is higher than what it was when the channel force-closed
    val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
    w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe).toInt, 0, remoteCommitTx)
    val claimHtlcTimeoutTx = getMempoolTxs(1).head
    val claimHtlcTimeoutTargetFee = Transactions.weight2fee(targetFeerate, claimHtlcTimeoutTx.weight.toInt)
    assert(claimHtlcTimeoutTargetFee * 0.9 <= claimHtlcTimeoutTx.fees && claimHtlcTimeoutTx.fees <= claimHtlcTimeoutTargetFee * 1.1, s"actualFee=${claimHtlcTimeoutTx.fees} targetFee=$claimHtlcTimeoutTargetFee")

    generateBlocks(4)
    system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
    val claimHtlcTimeoutResult = probe.expectMsgType[TxConfirmed]
    assert(claimHtlcTimeoutResult.cmd === claimHtlcTimeoutWithDeadline)
    assert(claimHtlcTimeoutResult.tx.txIn.map(_.outPoint.txid).contains(remoteCommitTx.txid))
    claimHtlcTimeoutPublisher ! Stop
    claimHtlcTimeoutResult.tx
  }

  test("claim htlc tx feerate high enough, not changing output amount") {
    withFixture(Seq(11 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      val currentFeerate = alice.underlyingActor.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(2)
      val (remoteCommitTx, claimHtlcSuccess, claimHtlcTimeout) = remoteCloseChannelWithHtlcs(f)
      val claimHtlcSuccessTx = testPublishClaimHtlcSuccess(f, remoteCommitTx, claimHtlcSuccess, currentFeerate)
      assert(claimHtlcSuccess.txInfo.fee > 0.sat)
      assert(claimHtlcSuccessTx.txIn.length === 1)
      val claimHtlcTimeoutTx = testPublishClaimHtlcTimeout(f, remoteCommitTx, claimHtlcTimeout, currentFeerate)
      assert(claimHtlcTimeout.txInfo.fee > 0.sat)
      assert(claimHtlcTimeoutTx.txIn.length === 1)
    })
  }

  test("claim htlc tx feerate too low, lowering output amount") {
    withFixture(Seq(11 millibtc), ChannelTypes.AnchorOutputs, f => {
      val targetFeerate = FeeratePerKw(15_000 sat)
      val (remoteCommitTx, claimHtlcSuccess, claimHtlcTimeout) = remoteCloseChannelWithHtlcs(f)
      val claimHtlcSuccessTx = testPublishClaimHtlcSuccess(f, remoteCommitTx, claimHtlcSuccess, targetFeerate)
      assert(claimHtlcSuccessTx.txIn.length === 1)
      assert(claimHtlcSuccessTx.txOut.length === 1)
      assert(claimHtlcSuccessTx.txOut.head.amount < claimHtlcSuccess.txInfo.tx.txOut.head.amount)
      val claimHtlcTimeoutTx = testPublishClaimHtlcTimeout(f, remoteCommitTx, claimHtlcTimeout, targetFeerate)
      assert(claimHtlcTimeoutTx.txIn.length === 1)
      assert(claimHtlcTimeoutTx.txOut.length === 1)
      assert(claimHtlcTimeoutTx.txOut.head.amount < claimHtlcTimeout.txInfo.tx.txOut.head.amount)
    })
  }

  test("claim htlc tx feerate too low, lowering output amount (standard commitment format)") {
    withFixture(Seq(11 millibtc), ChannelTypes.Standard, f => {
      import f._

      val targetFeerate = FeeratePerKw(15_000 sat)
      val (remoteCommitTx, claimHtlcSuccess, claimHtlcTimeout) = remoteCloseChannelWithHtlcs(f)

      // The Claim-HTLC-success tx will be immediately published.
      setFeerate(targetFeerate)
      val claimHtlcSuccessPublisher = createPublisher()
      claimHtlcSuccessPublisher ! Publish(probe.ref, claimHtlcSuccess)
      val claimHtlcSuccessTx = getMempoolTxs(1).head
      val claimHtlcSuccessTargetFee = Transactions.weight2fee(targetFeerate, claimHtlcSuccessTx.weight.toInt)
      assert(claimHtlcSuccessTargetFee * 0.9 <= claimHtlcSuccessTx.fees && claimHtlcSuccessTx.fees <= claimHtlcSuccessTargetFee * 1.1, s"actualFee=${claimHtlcSuccessTx.fees} targetFee=$claimHtlcSuccessTargetFee")
      generateBlocks(4)
      system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
      val claimHtlcSuccessResult = probe.expectMsgType[TxConfirmed]
      assert(claimHtlcSuccessResult.cmd === claimHtlcSuccess)
      assert(claimHtlcSuccessResult.tx.txIn.map(_.outPoint.txid).contains(remoteCommitTx.txid))
      claimHtlcSuccessPublisher ! Stop

      // The Claim-HTLC-timeout will be published after the timeout.
      val claimHtlcTimeoutPublisher = createPublisher()
      claimHtlcTimeoutPublisher ! Publish(probe.ref, claimHtlcTimeout)
      alice2blockchain.expectNoMessage(100 millis)
      generateBlocks(144)
      system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
      val claimHtlcTimeoutTx = getMempoolTxs(1).head
      val claimHtlcTimeoutTargetFee = Transactions.weight2fee(targetFeerate, claimHtlcTimeoutTx.weight.toInt)
      assert(claimHtlcTimeoutTargetFee * 0.9 <= claimHtlcTimeoutTx.fees && claimHtlcTimeoutTx.fees <= claimHtlcTimeoutTargetFee * 1.1, s"actualFee=${claimHtlcTimeoutTx.fees} targetFee=$claimHtlcTimeoutTargetFee")

      generateBlocks(4)
      system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
      val claimHtlcTimeoutResult = probe.expectMsgType[TxConfirmed]
      assert(claimHtlcTimeoutResult.cmd === claimHtlcTimeout)
      assert(claimHtlcTimeoutResult.tx.txIn.map(_.outPoint.txid).contains(remoteCommitTx.txid))
      claimHtlcTimeoutPublisher ! Stop
    })
  }

  test("claim htlc tx feerate way too low, skipping output") {
    withFixture(Seq(11 millibtc), ChannelTypes.AnchorOutputs, f => {
      import f._

      val (remoteCommitTx, claimHtlcSuccess, claimHtlcTimeout) = remoteCloseChannelWithHtlcs(f)

      setFeerate(FeeratePerKw(50_000 sat))
      val claimHtlcSuccessPublisher = createPublisher()
      claimHtlcSuccessPublisher ! Publish(probe.ref, claimHtlcSuccess)
      val w1 = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w1.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe).toInt, 0, remoteCommitTx)
      val result1 = probe.expectMsgType[TxRejected]
      assert(result1.cmd === claimHtlcSuccess)
      assert(result1.reason === TxSkipped(retryNextBlock = true))
      claimHtlcSuccessPublisher ! Stop

      val claimHtlcTimeoutPublisher = createPublisher()
      claimHtlcTimeoutPublisher ! Publish(probe.ref, claimHtlcTimeout)
      generateBlocks(144)
      system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
      val w2 = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w2.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe).toInt, 0, remoteCommitTx)
      val result2 = probe.expectMsgType[TxRejected]
      assert(result2.cmd === claimHtlcTimeout)
      assert(result2.reason === TxSkipped(retryNextBlock = true))
      claimHtlcTimeoutPublisher ! Stop
    })
  }

}
