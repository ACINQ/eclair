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
import fr.acinq.bitcoin.{Block, BtcAmount, ByteVector32, MilliBtcDouble, OutPoint, SatoshiLong, Script, ScriptWitness, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchOutputSpent, WatchParentTxConfirmed, WatchParentTxConfirmedTriggered, WatchTxConfirmed}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.MempoolTx
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BitcoinCoreClient, BitcoinJsonRPCClient}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.publish.ReplaceableTxPublisher.{Publish, Stop}
import fr.acinq.eclair.channel.publish.TxPublisher.TxRejectedReason.{ConflictingTxUnconfirmed, CouldNotFund, TxSkipped, WalletInputGone}
import fr.acinq.eclair.channel.publish.TxPublisher._
import fr.acinq.eclair.channel.states.{ChannelStateTestsHelperMethods, ChannelStateTestsTags}
import fr.acinq.eclair.transactions.Transactions.{ClaimLocalAnchorOutputTx, HtlcSuccessTx, HtlcTimeoutTx}
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
    val walletClient = new BitcoinCoreClient(Block.RegtestGenesisBlock.hash, walletRpcClient)
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
      case _ => fail("channel type must be a form of anchor outputs")
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

  def closeChannelWithoutHtlcs(f: Fixture): (PublishRawTx, PublishReplaceableTx) = {
    import f._

    val commitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.fullySignedLocalCommitTx(alice.underlyingActor.nodeParams.channelKeyManager)
    probe.send(alice, CMD_FORCECLOSE(probe.ref))
    probe.expectMsgType[CommandSuccess[CMD_FORCECLOSE]]

    // Forward the commit tx to the publisher.
    val publishCommitTx = alice2blockchain.expectMsg(PublishRawTx(commitTx, None))
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
      val (_, anchorTx) = closeChannelWithoutHtlcs(f)
      publisher ! Publish(probe.ref, anchorTx, commitFeerate)

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

      publisher ! Publish(probe.ref, anchorTx, FeeratePerKw(10_000 sat))
      val result = probe.expectMsgType[TxRejected]
      assert(result.cmd === anchorTx)
      assert(result.reason === TxSkipped(retryNextBlock = false))
    })
  }

  test("commit tx feerate high enough and commit tx confirmed, not spending anchor output") {
    withFixture(Seq(500 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      val commitFeerate = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.commitTxFeerate
      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f)
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)
      generateBlocks(1)

      publisher ! Publish(probe.ref, anchorTx, commitFeerate)
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

      publisher ! Publish(probe.ref, anchorTx, FeeratePerKw(10_000 sat))
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

      publisher ! Publish(probe.ref, anchorTx, FeeratePerKw(10_000 sat))
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
      publisher ! Publish(probe.ref, anchorTx, FeeratePerKw(600 sat))
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

      publisher ! Publish(probe.ref, anchorTx, FeeratePerKw(25_000 sat))
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

      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f)
      wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
      probe.expectMsg(commitTx.tx.txid)
      assert(getMempool.length === 1)

      val targetFeerate = FeeratePerKw(3000 sat)
      publisher ! Publish(probe.ref, anchorTx, targetFeerate)
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

      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f)
      assert(getMempool.isEmpty)

      val targetFeerate = FeeratePerKw(3000 sat)
      publisher ! Publish(probe.ref, anchorTx, targetFeerate)
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

      val targetFeerate = FeeratePerKw(10_000 sat)
      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f)
      publisher ! Publish(probe.ref, anchorTx, targetFeerate)

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
      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f)
      publisher ! Publish(probe.ref, anchorTx, targetFeerate)

      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(commitTx.tx.txid))

      // we try to publish the anchor again (can be caused by a node restart): it will fail to replace the existing one
      // in the mempool but we must ensure we don't leave some utxos locked.
      val publisher2 = createPublisher()
      publisher2 ! Publish(probe.ref, anchorTx, targetFeerate)
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
      val (commitTx, anchorTx) = closeChannelWithoutHtlcs(f)
      publisher ! Publish(probe.ref, anchorTx, targetFeerate)

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
        val adjustedTx = ReplaceableTxPublisher.adjustAnchorOutputChange(unsignedTx, commitTx.tx, amountIn, commitFeerate, TestConstants.feeratePerKw, dustLimit)
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
    alice2blockchain.expectMsg(PublishRawTx(commitTx, None))
    wallet.publishTransaction(commitTx.tx).pipeTo(probe.ref)
    probe.expectMsg(commitTx.tx.txid)
    generateBlocks(1)

    assert(alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.isInstanceOf[ClaimLocalAnchorOutputTx])
    alice2blockchain.expectMsgType[PublishRawTx] // claim main output
    val htlcSuccess = alice2blockchain.expectMsgType[PublishReplaceableTx]
    assert(htlcSuccess.txInfo.isInstanceOf[HtlcSuccessTx])
    val htlcTimeout = alice2blockchain.expectMsgType[PublishReplaceableTx]
    assert(htlcTimeout.txInfo.isInstanceOf[HtlcTimeoutTx])

    alice2blockchain.expectMsgType[WatchTxConfirmed] // commit tx
    alice2blockchain.expectMsgType[WatchTxConfirmed] // claim main output
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc-success tx
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc-timeout tx
    alice2blockchain.expectNoMessage(100 millis)

    (commitTx.tx, htlcSuccess, htlcTimeout)
  }

  test("not enough funds to increase htlc tx feerate") {
    withFixture(Seq(10.5 millibtc), ChannelTypes.AnchorOutputsZeroFeeHtlcTx, f => {
      import f._

      val (commitTx, htlcSuccess, _) = closeChannelWithHtlcs(f)
      val htlcSuccessPublisher = createPublisher()
      htlcSuccessPublisher ! Publish(probe.ref, htlcSuccess, FeeratePerKw(75_000 sat))
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
    val htlcSuccessPublisher = createPublisher()
    htlcSuccessPublisher ! Publish(probe.ref, htlcSuccess, targetFeerate)
    val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
    w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe).toInt, 0, commitTx)
    val htlcSuccessTx = getMempoolTxs(1).head
    val htlcSuccessTargetFee = Transactions.weight2fee(targetFeerate, htlcSuccessTx.weight.toInt)
    assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx.fees && htlcSuccessTx.fees <= htlcSuccessTargetFee * 1.4, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcSuccessTargetFee")

    generateBlocks(4)
    system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
    val htlcSuccessResult = probe.expectMsgType[TxConfirmed]
    assert(htlcSuccessResult.cmd === htlcSuccess)
    assert(htlcSuccessResult.tx.txIn.map(_.outPoint.txid).contains(commitTx.txid))
    htlcSuccessPublisher ! Stop
    htlcSuccessResult.tx
  }

  private def testPublishHtlcTimeout(f: Fixture, commitTx: Transaction, htlcTimeout: PublishReplaceableTx, targetFeerate: FeeratePerKw): Transaction = {
    import f._

    // The HTLC-timeout will be published after the timeout.
    val htlcTimeoutPublisher = createPublisher()
    htlcTimeoutPublisher ! Publish(probe.ref, htlcTimeout, targetFeerate)
    alice2blockchain.expectNoMessage(100 millis)
    generateBlocks(144)
    system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
    val w = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
    w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe).toInt, 0, commitTx)
    val htlcTimeoutTx = getMempoolTxs(1).head
    val htlcTimeoutTargetFee = Transactions.weight2fee(targetFeerate, htlcTimeoutTx.weight.toInt)
    assert(htlcTimeoutTargetFee * 0.9 <= htlcTimeoutTx.fees && htlcTimeoutTx.fees <= htlcTimeoutTargetFee * 1.4, s"actualFee=${htlcTimeoutTx.fees} targetFee=$htlcTimeoutTargetFee")

    generateBlocks(4)
    system.eventStream.publish(CurrentBlockCount(currentBlockHeight(probe)))
    val htlcTimeoutResult = probe.expectMsgType[TxConfirmed]
    assert(htlcTimeoutResult.cmd === htlcTimeout)
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
      val (commitTx, htlcSuccess, _) = closeChannelWithHtlcs(f)
      val publisher1 = createPublisher()
      publisher1 ! Publish(probe.ref, htlcSuccess, targetFeerate)
      val w1 = alice2blockchain.expectMsgType[WatchParentTxConfirmed]
      w1.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe).toInt, 0, commitTx)
      getMempoolTxs(1)

      // we try to publish the htlc-success again (can be caused by a node restart): it will fail to replace the existing
      // one in the mempool but we must ensure we don't leave some utxos locked.
      val publisher2 = createPublisher()
      publisher2 ! Publish(probe.ref, htlcSuccess, targetFeerate)
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

      val targetFeerate = FeeratePerKw(5_000 sat)
      val (commitTx, htlcSuccess, _) = closeChannelWithHtlcs(f)
      publisher ! Publish(probe.ref, htlcSuccess, targetFeerate)
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
          val adjustedTx = ReplaceableTxPublisher.adjustHtlcTxChange(unsignedTx, totalAmountIn, targetFeerate, commitments)
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

}
