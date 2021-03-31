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

package fr.acinq.eclair.channel

import akka.actor.typed.scaladsl.adapter.{ClassicActorSystemOps, TypedActorRefOps, actorRefAdapter}
import akka.pattern.pipe
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{BtcAmount, MilliBtcDouble, OutPoint, SatoshiLong, Script, ScriptWitness, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.WatcherSpec.createSpendP2WPKH
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient.{FundTransactionResponse, MempoolTx, SignTransactionResponse}
import fr.acinq.eclair.blockchain.bitcoind.{BitcoinCoreWallet, BitcoindService}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{WatchConfirmed, WatchSpent}
import fr.acinq.eclair.channel.TxPublisher._
import fr.acinq.eclair.channel.states.{StateTestsHelperMethods, StateTestsTags}
import fr.acinq.eclair.transactions.Transactions.TransactionSigningKit.{ClaimAnchorOutputSigningKit, HtlcSuccessSigningKit, HtlcTimeoutSigningKit}
import fr.acinq.eclair.transactions.Transactions.{CommitTx, HtlcSuccessTx, HtlcTimeoutTx}
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.{MilliSatoshiLong, TestConstants, TestKitBaseClass, randomBytes32, randomKey}
import grizzled.slf4j.Logging
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.{BeforeAndAfterAll, Tag}

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.util.Random

class TxPublisherSpec extends TestKitBaseClass with AnyFunSuiteLike with BitcoindService with StateTestsHelperMethods with BeforeAndAfterAll with Logging {

  override def beforeAll(): Unit = {
    logger.info("starting bitcoind")
    startBitcoind()
    waitForBitcoindReady()
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    logger.info("stopping bitcoind")
    stopBitcoind()
    super.afterAll()
  }

  case class Fixture(alice: TestFSMRef[State, Data, Channel],
                     bob: TestFSMRef[State, Data, Channel],
                     alice2bob: TestProbe,
                     bob2alice: TestProbe,
                     alice2blockchain: TestProbe,
                     bob2blockchain: TestProbe,
                     blockCount: AtomicLong,
                     bitcoinClient: ExtendedBitcoinClient,
                     bitcoinWallet: BitcoinCoreWallet,
                     txPublisher: akka.actor.typed.ActorRef[TxPublisher.Command],
                     probe: TestProbe) {

    def createBlocks(count: Int): Unit = {
      val current = blockCount.get()
      generateBlocks(count)
      blockCount.set(current + count)
      txPublisher ! WrappedCurrentBlockCount(current + count)
    }

    def getMempool: Seq[Transaction] = {
      bitcoinClient.getMempool().pipeTo(probe.ref)
      probe.expectMsgType[Seq[Transaction]]
    }

    def getMempoolTxs(expectedTxCount: Int): Seq[MempoolTx] = {
      awaitCond(getMempool.size == expectedTxCount, interval = 250 milliseconds)
      getMempool.map(tx => {
        bitcoinClient.getMempoolTx(tx.txid).pipeTo(probe.ref)
        probe.expectMsgType[MempoolTx]
      })
    }

  }

  // NB: we can't use ScalaTest's fixtures, they would see uninitialized bitcoind fields because they sandbox each test.
  private def withFixture(utxos: Seq[BtcAmount], testFun: Fixture => Any): Unit = {
    // Create a unique wallet for this test and ensure it has some btc.
    val testId = UUID.randomUUID()
    val walletRpcClient = createWallet(s"lightning-$testId")
    val bitcoinClient = new ExtendedBitcoinClient(walletRpcClient)
    val bitcoinWallet = new BitcoinCoreWallet(walletRpcClient)
    val probe = TestProbe()
    utxos.foreach(amount => {
      bitcoinWallet.getReceiveAddress.pipeTo(probe.ref)
      val walletAddress = probe.expectMsgType[String]
      sendToAddress(walletAddress, amount, probe)
    })
    generateBlocks(1)

    val blockCount = new AtomicLong()
    val aliceNodeParams = TestConstants.Alice.nodeParams.copy(blockCount = blockCount)
    // Setup a valid channel between alice and bob.
    val setup = init(aliceNodeParams, TestConstants.Bob.nodeParams.copy(blockCount = blockCount), bitcoinWallet)
    reachNormal(setup, Set(StateTestsTags.AnchorOutputs))
    import setup._
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)

    // Generate blocks to ensure the funding tx is confirmed and set initial block count.
    generateBlocks(1)
    bitcoinClient.getBlockCount.pipeTo(probe.ref)
    blockCount.set(probe.expectMsgType[Long])

    // Execute our test.
    val txPublisher = system.spawn(TxPublisher(aliceNodeParams, alice2blockchain.ref, bitcoinClient), testId.toString)
    try {
      testFun(Fixture(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, blockCount, bitcoinClient, bitcoinWallet, txPublisher, probe))
    } finally {
      system.stop(txPublisher.ref.toClassic)
    }
  }

  test("publish transactions with relative and absolute delays") {
    withFixture(Seq(500 millibtc), f => {
      import f._

      // tx1 has an absolute delay but no relative delay
      val priv = dumpPrivateKey(getNewAddress(probe), probe)
      val tx1 = {
        bitcoinWallet.fundTransaction(Transaction(2, Nil, TxOut(150000 sat, Script.pay2wpkh(priv.publicKey)) :: Nil, blockCount.get() + 5), lockUtxos = true, FeeratePerKw(250 sat)).pipeTo(probe.ref)
        val funded = probe.expectMsgType[FundTransactionResponse].tx
        bitcoinWallet.signTransaction(funded).pipeTo(probe.ref)
        probe.expectMsgType[SignTransactionResponse].tx
      }
      txPublisher ! PublishRawTx(probe.ref, tx1)
      createBlocks(4)
      assert(!getMempool.exists(_.txid === tx1.txid)) // tx should not be broadcast yet
      createBlocks(1)
      awaitCond(getMempool.exists(_.txid === tx1.txid), max = 20 seconds, interval = 1 second)

      // tx2 has a relative delay but no absolute delay
      val tx2 = createSpendP2WPKH(tx1, priv, priv.publicKey, 10000 sat, sequence = 2, lockTime = 0)
      txPublisher ! PublishRawTx(probe.ref, tx2)
      val watchParentTx2 = alice2blockchain.expectMsgType[WatchConfirmed]
      assert(watchParentTx2.txId === tx1.txid)
      assert(watchParentTx2.minDepth === 2)
      createBlocks(2)
      txPublisher ! ParentTxConfirmed(watchParentTx2.event.asInstanceOf[BITCOIN_PARENT_TX_CONFIRMED].childTx, tx1.txid)
      awaitCond(getMempool.exists(_.txid === tx2.txid), max = 20 seconds, interval = 1 second)

      // tx3 has both relative and absolute delays
      val tx3 = createSpendP2WPKH(tx2, priv, priv.publicKey, 10000 sat, sequence = 1, lockTime = blockCount.get + 5)
      txPublisher ! PublishRawTx(probe.ref, tx3)
      val watchParentTx3 = alice2blockchain.expectMsgType[WatchConfirmed]
      assert(watchParentTx3.txId === tx2.txid)
      assert(watchParentTx3.minDepth === 1)
      // after 1 block, the relative delay is elapsed, but not the absolute delay
      createBlocks(1)
      txPublisher ! ParentTxConfirmed(watchParentTx3.event.asInstanceOf[BITCOIN_PARENT_TX_CONFIRMED].childTx, tx2.txid)
      assert(!getMempool.exists(_.txid === tx3.txid))
      // after 4 more blocks, the absolute delay is elapsed
      createBlocks(4)
      awaitCond(getMempool.exists(_.txid === tx3.txid), max = 20 seconds, interval = 1 second)
    })
  }

  def closeChannelWithoutHtlcs(f: Fixture): SignAndPublishTx = {
    import f._

    val commitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val currentFeerate = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.feeratePerKw
    probe.send(alice, CMD_FORCECLOSE(probe.ref))
    probe.expectMsgType[CommandSuccess[CMD_FORCECLOSE]]

    val publishCommitTx = alice2blockchain.expectMsgType[PublishTx]
    assert(publishCommitTx.tx.txid === commitTx.txid)
    assert(publishCommitTx.isInstanceOf[SignAndPublishTx])
    val feerateInfo = publishCommitTx.asInstanceOf[SignAndPublishTx].setFeerate
    assert(feerateInfo.currentFeerate < feerateInfo.targetFeerate)
    assert(feerateInfo.currentFeerate === currentFeerate)
    assert(feerateInfo.targetFeerate === TestConstants.feeratePerKw)
    publishCommitTx.asInstanceOf[SignAndPublishTx]
  }

  test("commit tx feerate high enough, not spending anchor output") {
    withFixture(Seq(500 millibtc), f => {
      import f._

      val publishCommitTx = closeChannelWithoutHtlcs(f)
      txPublisher ! publishCommitTx.copy(setFeerate = publishCommitTx.setFeerate.copy(targetFeerate = publishCommitTx.setFeerate.currentFeerate))

      // wait for the commit tx and anchor tx to be published
      val mempoolTx = getMempoolTxs(1).head
      assert(mempoolTx.txid === publishCommitTx.tx.txid)

      val targetFee = Transactions.weight2fee(publishCommitTx.setFeerate.currentFeerate, mempoolTx.weight.toInt)
      val actualFee = mempoolTx.fees
      assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee")
    })
  }

  test("commit tx feerate too low, not enough wallet inputs to increase feerate") {
    withFixture(Seq(10.1 millibtc), f => {
      import f._

      val publishCommitTx = closeChannelWithoutHtlcs(f)
      txPublisher ! publishCommitTx

      // wait for the commit tx to be published, anchor will not be published because we don't have enough funds
      val mempoolTx1 = getMempoolTxs(1).head
      assert(mempoolTx1.txid === publishCommitTx.tx.txid)

      // add more funds to our wallet
      bitcoinWallet.getReceiveAddress.pipeTo(probe.ref)
      val walletAddress = probe.expectMsgType[String]
      sendToAddress(walletAddress, 1 millibtc, probe)
      createBlocks(1)

      // wait for the anchor tx to be published
      val mempoolTx2 = getMempoolTxs(1).head
      bitcoinClient.getTransaction(mempoolTx2.txid).pipeTo(probe.ref)
      val anchorTx = probe.expectMsgType[Transaction]
      assert(anchorTx.txIn.exists(_.outPoint.txid == mempoolTx1.txid))
      val targetFee = Transactions.weight2fee(TestConstants.feeratePerKw, (mempoolTx1.weight + mempoolTx2.weight).toInt)
      val actualFee = mempoolTx1.fees + mempoolTx2.fees
      assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee")
    })
  }

  test("commit tx feerate too low, spending anchor output") {
    withFixture(Seq(500 millibtc), f => {
      import f._

      val publishCommitTx = closeChannelWithoutHtlcs(f)
      txPublisher ! publishCommitTx

      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(publishCommitTx.tx.txid))

      val targetFee = Transactions.weight2fee(TestConstants.feeratePerKw, mempoolTxs.map(_.weight).sum.toInt)
      val actualFee = mempoolTxs.map(_.fees).sum
      assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee")
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
    withFixture(utxos, f => {
      import f._

      val publishCommitTx = closeChannelWithoutHtlcs(f)
      txPublisher ! publishCommitTx

      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(2)
      assert(mempoolTxs.map(_.txid).contains(publishCommitTx.tx.txid))
      val claimAnchorTx = mempoolTxs.find(_.txid != publishCommitTx.tx.txid).map(tx => {
        bitcoinClient.getTransaction(tx.txid).pipeTo(probe.ref)
        probe.expectMsgType[Transaction]
      })
      assert(claimAnchorTx.nonEmpty)
      assert(claimAnchorTx.get.txIn.length > 2) // we added more than 1 wallet input

      val targetFee = Transactions.weight2fee(TestConstants.feeratePerKw, mempoolTxs.map(_.weight).sum.toInt)
      val actualFee = mempoolTxs.map(_.fees).sum
      assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee")
    })
  }

  test("adjust anchor tx change amount", Tag("fuzzy")) {
    withFixture(Seq(500 millibtc), f => {
      val SignAndPublishTx(_, commitTx: CommitTx, SetFeerate(currentFeerate, targetFeerate, dustLimit, signingKit: ClaimAnchorOutputSigningKit)) = closeChannelWithoutHtlcs(f)
      for (_ <- 1 to 100) {
        val walletInputsCount = 1 + Random.nextInt(5)
        val walletInputs = (1 to walletInputsCount).map(_ => TxIn(OutPoint(randomBytes32, 0), Nil, 0))
        val amountIn = dustLimit * walletInputsCount + Random.nextInt(25_000_000).sat
        val amountOut = dustLimit + Random.nextLong(amountIn.toLong).sat
        val unsignedTx = signingKit.txWithInput.copy(tx = signingKit.txWithInput.tx.copy(
          txIn = signingKit.txWithInput.tx.txIn ++ walletInputs,
          txOut = TxOut(amountOut, Script.pay2wpkh(randomKey.publicKey)) :: Nil,
        ))
        val adjustedTx = adjustAnchorOutputChange(unsignedTx, commitTx.tx, amountIn, currentFeerate, targetFeerate, dustLimit)
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
          val targetFee = Transactions.weight2fee(targetFeerate, signedTx.weight() + commitTx.tx.weight()) - Transactions.weight2fee(currentFeerate, commitTx.tx.weight())
          val actualFee = amountIn - signedTx.txOut.map(_.amount).sum
          assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee amountIn=$amountIn tx=$signedTx")
        }
      }
    })
  }

  def closeChannelWithHtlcs(f: Fixture): (SignAndPublishTx, SignAndPublishTx, SignAndPublishTx) = {
    import f._

    // Add htlcs in both directions and ensure that preimages are available.
    addHtlc(5_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val (r, htlc) = addHtlc(4_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    probe.send(alice, CMD_FULFILL_HTLC(htlc.id, r, replyTo_opt = Some(probe.ref)))
    probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]

    // Force-close channel and verify txs sent to watcher.
    val commitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val currentFeerate = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.feeratePerKw
    assert(commitTx.txOut.size === 6)
    probe.send(alice, CMD_FORCECLOSE(probe.ref))
    probe.expectMsgType[CommandSuccess[CMD_FORCECLOSE]]
    val publishCommitTx = alice2blockchain.expectMsgType[SignAndPublishTx]
    alice2blockchain.expectMsgType[PublishRawTx] // claim main output
    val publishHtlcSuccess = alice2blockchain.expectMsgType[SignAndPublishTx]
    val publishHtlcTimeout = alice2blockchain.expectMsgType[SignAndPublishTx]
    Seq(publishCommitTx, publishHtlcSuccess, publishHtlcTimeout).foreach(publishTx => {
      assert(publishTx.setFeerate.currentFeerate === currentFeerate)
      assert(publishTx.setFeerate.currentFeerate < publishTx.setFeerate.targetFeerate)
      assert(publishTx.setFeerate.targetFeerate === TestConstants.feeratePerKw)
    })

    (publishCommitTx, publishHtlcSuccess, publishHtlcTimeout)
  }

  test("htlc tx feerate high enough, not adding wallet inputs") {
    withFixture(Seq(500 millibtc), f => {
      import f._

      val currentFeerate = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.feeratePerKw
      val (publishCommitTx, publishHtlcSuccess, publishHtlcTimeout) = closeChannelWithHtlcs(f)
      val publishHtlcSuccess1 = publishHtlcSuccess.copy(setFeerate = publishHtlcSuccess.setFeerate.copy(targetFeerate = currentFeerate))
      val publishHtlcTimeout1 = publishHtlcTimeout.copy(setFeerate = publishHtlcTimeout.setFeerate.copy(targetFeerate = currentFeerate))

      // Publish the commit tx.
      txPublisher ! publishCommitTx
      txPublisher ! publishHtlcSuccess1
      txPublisher ! publishHtlcTimeout1
      // HTLC txs will only be published once the commit tx is confirmed (csv delay)
      getMempoolTxs(2)
      createBlocks(2)
      txPublisher ! ParentTxConfirmed(publishHtlcSuccess1, publishCommitTx.tx.txid)
      txPublisher ! ParentTxConfirmed(publishHtlcTimeout1, publishCommitTx.tx.txid)

      // The HTLC-success tx will be immediately published.
      val htlcSuccessTx = getMempoolTxs(1).head
      val htlcSuccessTargetFee = Transactions.weight2fee(currentFeerate, htlcSuccessTx.weight.toInt)
      assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx.fees && htlcSuccessTx.fees <= htlcSuccessTargetFee * 1.1, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcSuccessTargetFee")

      // The HTLC-timeout tx will be published once its absolute timeout is satisfied.
      createBlocks(144)
      txPublisher ! ParentTxConfirmed(publishHtlcTimeout, publishCommitTx.tx.txid)
      val htlcTimeoutTx = getMempoolTxs(1).head
      val htlcTimeoutTargetFee = Transactions.weight2fee(currentFeerate, htlcTimeoutTx.weight.toInt)
      assert(htlcTimeoutTargetFee * 0.9 <= htlcTimeoutTx.fees && htlcTimeoutTx.fees <= htlcTimeoutTargetFee * 1.1, s"actualFee=${htlcTimeoutTx.fees} targetFee=$htlcTimeoutTargetFee")
    })
  }

  test("htlc tx feerate too low, not enough wallet inputs to increase feerate") {
    withFixture(Seq(10.1 millibtc), f => {
      import f._

      val (publishCommitTx, publishHtlcSuccess, _) = closeChannelWithHtlcs(f)

      // Publish the commit tx without the anchor.
      txPublisher ! publishCommitTx.copy(setFeerate = publishCommitTx.setFeerate.copy(targetFeerate = publishCommitTx.setFeerate.currentFeerate))
      txPublisher ! publishHtlcSuccess
      // HTLC txs will only be published once the commit tx is confirmed (csv delay)
      getMempoolTxs(1)
      createBlocks(2)
      txPublisher ! ParentTxConfirmed(publishHtlcSuccess, publishCommitTx.tx.txid)

      // Add more funds to our wallet to allow bumping HTLC txs.
      bitcoinWallet.getReceiveAddress.pipeTo(probe.ref)
      val walletAddress = probe.expectMsgType[String]
      sendToAddress(walletAddress, 1 millibtc, probe)
      createBlocks(1)

      // The HTLC-success tx will be immediately published.
      val htlcSuccessTx = getMempoolTxs(1).head
      val htlcSuccessTargetFee = Transactions.weight2fee(TestConstants.feeratePerKw, htlcSuccessTx.weight.toInt)
      assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx.fees && htlcSuccessTx.fees <= htlcSuccessTargetFee * 1.1, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcSuccessTargetFee")
    })
  }

  test("htlc tx feerate too low, adding wallet inputs") {
    withFixture(Seq(500 millibtc), f => {
      import f._

      val (publishCommitTx, publishHtlcSuccess, publishHtlcTimeout) = closeChannelWithHtlcs(f)

      // Publish the commit tx.
      txPublisher ! publishCommitTx
      txPublisher ! publishHtlcSuccess
      txPublisher ! publishHtlcTimeout
      // HTLC txs will only be published once the commit tx is confirmed (csv delay)
      getMempoolTxs(2)
      createBlocks(2)
      txPublisher ! ParentTxConfirmed(publishHtlcSuccess, publishCommitTx.tx.txid)
      txPublisher ! ParentTxConfirmed(publishHtlcTimeout, publishCommitTx.tx.txid)

      // The HTLC-success tx will be immediately published.
      val htlcSuccessTx = getMempoolTxs(1).head
      val htlcSuccessTargetFee = Transactions.weight2fee(TestConstants.feeratePerKw, htlcSuccessTx.weight.toInt)
      assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx.fees && htlcSuccessTx.fees <= htlcSuccessTargetFee * 1.1, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcSuccessTargetFee")

      // The HTLC-timeout tx will be published once its absolute timeout is satisfied.
      createBlocks(144)
      val htlcTimeoutTx = getMempoolTxs(1).head
      val htlcTimeoutTargetFee = Transactions.weight2fee(TestConstants.feeratePerKw, htlcTimeoutTx.weight.toInt)
      assert(htlcTimeoutTargetFee * 0.9 <= htlcTimeoutTx.fees && htlcTimeoutTx.fees <= htlcTimeoutTargetFee * 1.1, s"actualFee=${htlcTimeoutTx.fees} targetFee=$htlcTimeoutTargetFee")
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
    withFixture(utxos, f => {
      import f._

      val (publishCommitTx, publishHtlcSuccess, publishHtlcTimeout) = closeChannelWithHtlcs(f)

      // Publish the commit tx without the anchor.
      txPublisher ! publishCommitTx.copy(setFeerate = publishCommitTx.setFeerate.copy(targetFeerate = publishCommitTx.setFeerate.currentFeerate))
      txPublisher ! publishHtlcSuccess
      txPublisher ! publishHtlcTimeout
      // HTLC txs will only be published once the commit tx is confirmed (csv delay)
      getMempoolTxs(1)
      createBlocks(2)
      txPublisher ! ParentTxConfirmed(publishHtlcSuccess, publishCommitTx.tx.txid)
      txPublisher ! ParentTxConfirmed(publishHtlcTimeout, publishCommitTx.tx.txid)

      // The HTLC-success tx will be immediately published.
      val htlcSuccessTx = getMempoolTxs(1).head
      bitcoinClient.getTransaction(htlcSuccessTx.txid).pipeTo(probe.ref)
      assert(probe.expectMsgType[Transaction].txIn.length > 2) // we added more than 1 wallet input
      val htlcSuccessTargetFee = Transactions.weight2fee(TestConstants.feeratePerKw, htlcSuccessTx.weight.toInt)
      assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx.fees && htlcSuccessTx.fees <= htlcSuccessTargetFee * 1.4, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcSuccessTargetFee")

      // The HTLC-timeout tx will be published once its absolute timeout is satisfied.
      createBlocks(144)
      val htlcTimeoutTx = getMempoolTxs(1).head
      bitcoinClient.getTransaction(htlcTimeoutTx.txid).pipeTo(probe.ref)
      assert(probe.expectMsgType[Transaction].txIn.length > 2) // we added more than 1 wallet input
      val htlcTimeoutTargetFee = Transactions.weight2fee(TestConstants.feeratePerKw, htlcTimeoutTx.weight.toInt)
      assert(htlcTimeoutTargetFee * 0.9 <= htlcTimeoutTx.fees && htlcTimeoutTx.fees <= htlcTimeoutTargetFee * 1.4, s"actualFee=${htlcTimeoutTx.fees} targetFee=$htlcTimeoutTargetFee")
    })
  }

  test("htlc tx sent after commit tx confirmed") {
    withFixture(Seq(500 millibtc), f => {
      import f._

      // Add incoming htlc.
      val (r, htlc) = addHtlc(5_000_000 msat, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)

      // Force-close channel and verify txs sent to watcher.
      val commitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      assert(commitTx.txOut.size === 5)
      probe.send(alice, CMD_FORCECLOSE(probe.ref))
      probe.expectMsgType[CommandSuccess[CMD_FORCECLOSE]]
      val publishCommitTx = alice2blockchain.expectMsgType[SignAndPublishTx]
      alice2blockchain.expectMsgType[PublishRawTx] // claim main output
      alice2blockchain.expectMsgType[WatchConfirmed] // commit tx
      alice2blockchain.expectMsgType[WatchConfirmed] // claim main output
      alice2blockchain.expectMsgType[WatchSpent] // alice doesn't have the preimage yet to redeem the htlc but she watches the output
      alice2blockchain.expectNoMessage(100 millis)

      // Publish and confirm the commit tx.
      txPublisher ! publishCommitTx
      getMempoolTxs(2)
      createBlocks(2)

      probe.send(alice, CMD_FULFILL_HTLC(htlc.id, r, replyTo_opt = Some(probe.ref)))
      probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]
      alice2blockchain.expectMsg(publishCommitTx)
      alice2blockchain.expectMsgType[PublishRawTx] // claim main output
      val publishHtlcSuccess = alice2blockchain.expectMsgType[SignAndPublishTx]
      alice2blockchain.expectMsgType[WatchConfirmed] // commit tx
      alice2blockchain.expectMsgType[WatchConfirmed] // claim main output
      alice2blockchain.expectMsgType[WatchSpent] // htlc output
      alice2blockchain.expectNoMessage(100 millis)

      txPublisher ! publishHtlcSuccess
      val w = alice2blockchain.expectMsgType[WatchConfirmed]
      assert(w.txId === commitTx.txid)
      assert(w.minDepth === 1)
      txPublisher ! ParentTxConfirmed(publishHtlcSuccess, publishCommitTx.tx.txid)

      // The HTLC-success tx will be immediately published.
      val htlcSuccessTx = getMempoolTxs(1).head
      val htlcSuccessTargetFee = Transactions.weight2fee(TestConstants.feeratePerKw, htlcSuccessTx.weight.toInt)
      assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx.fees && htlcSuccessTx.fees <= htlcSuccessTargetFee * 1.1, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcSuccessTargetFee")
    })
  }

  test("adjust htlc tx change amount", Tag("fuzzy")) {
    withFixture(Seq(500 millibtc), f => {
      val (_, publishHtlcSuccess, publishHtlcTimeout) = closeChannelWithHtlcs(f)
      val SignAndPublishTx(_, htlcSuccessTx: HtlcSuccessTx, SetFeerate(_, targetFeerate, dustLimit, successSigningKit: HtlcSuccessSigningKit)) = publishHtlcSuccess
      val SignAndPublishTx(_, htlcTimeoutTx: HtlcTimeoutTx, SetFeerate(_, _, _, timeoutSigningKit: HtlcTimeoutSigningKit)) = publishHtlcTimeout
      for (_ <- 1 to 100) {
        val walletInputsCount = 1 + Random.nextInt(5)
        val walletInputs = (1 to walletInputsCount).map(_ => TxIn(OutPoint(randomBytes32, 0), Nil, 0))
        val walletAmountIn = dustLimit * walletInputsCount + Random.nextInt(25_000_000).sat
        val changeOutput = TxOut(Random.nextLong(walletAmountIn.toLong).sat, Script.pay2wpkh(randomKey.publicKey))
        val unsignedHtlcSuccessTx = successSigningKit.txWithInput.copy(tx = htlcSuccessTx.tx.copy(
          txIn = htlcSuccessTx.tx.txIn ++ walletInputs,
          txOut = htlcSuccessTx.tx.txOut ++ Seq(changeOutput)
        ))
        val unsignedHtlcTimeoutTx = timeoutSigningKit.txWithInput.copy(tx = htlcTimeoutTx.tx.copy(
          txIn = htlcTimeoutTx.tx.txIn ++ walletInputs,
          txOut = htlcTimeoutTx.tx.txOut ++ Seq(changeOutput)
        ))
        for ((unsignedTx, signingKit) <- Seq((unsignedHtlcSuccessTx, successSigningKit), (unsignedHtlcTimeoutTx, timeoutSigningKit))) {
          val totalAmountIn = unsignedTx.input.txOut.amount + walletAmountIn
          val adjustedTx = adjustHtlcTxChange(unsignedTx, totalAmountIn, targetFeerate, dustLimit, signingKit)
          assert(adjustedTx.tx.txIn.size === unsignedTx.tx.txIn.size)
          assert(adjustedTx.tx.txOut.size === 1 || adjustedTx.tx.txOut.size === 2)
          if (adjustedTx.tx.txOut.size == 2) {
            // Simulate tx signing to check final feerate.
            val signedTx = {
              val htlcSigned = addHtlcTxSigs(adjustedTx, Transactions.PlaceHolderSig, signingKit)
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
