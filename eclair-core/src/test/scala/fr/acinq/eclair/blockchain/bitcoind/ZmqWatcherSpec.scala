/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.blockchain.bitcoind

import akka.Done
import akka.actor.{ActorRef, Props}
import akka.pattern.pipe
import akka.testkit.{TestActorRef, TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{Block, Btc, BtcAmount, MilliBtcDouble, OutPoint, SatoshiLong, Script, ScriptWitness, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.WatcherSpec._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient.{FundTransactionResponse, MempoolTx, SignTransactionResponse}
import fr.acinq.eclair.blockchain.bitcoind.zmq.ZMQActor
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.{StateTestsHelperMethods, StateTestsTags}
import fr.acinq.eclair.transactions.Transactions.TransactionSigningKit.{ClaimAnchorOutputSigningKit, HtlcSuccessSigningKit, HtlcTimeoutSigningKit}
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.{MilliSatoshiLong, TestConstants, TestKitBaseClass, randomBytes32, randomKey}
import grizzled.slf4j.Logging
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.{BeforeAndAfterAll, Tag}

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Random

class ZmqWatcherSpec extends TestKitBaseClass with AnyFunSuiteLike with BitcoindService with StateTestsHelperMethods with BeforeAndAfterAll with Logging {

  var zmqBlock: ActorRef = _
  var zmqTx: ActorRef = _

  override def beforeAll(): Unit = {
    logger.info("starting bitcoind")
    startBitcoind()
    waitForBitcoindReady()
    logger.info("starting zmq actors")
    val (zmqBlockConnected, zmqTxConnected) = (Promise[Done](), Promise[Done]())
    zmqBlock = system.actorOf(Props(new ZMQActor(s"tcp://127.0.0.1:$bitcoindZmqBlockPort", Some(zmqBlockConnected))))
    zmqTx = system.actorOf(Props(new ZMQActor(s"tcp://127.0.0.1:$bitcoindZmqTxPort", Some(zmqTxConnected))))
    awaitCond(zmqBlockConnected.isCompleted && zmqTxConnected.isCompleted)
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    logger.info("stopping zmq actors")
    system.stop(zmqBlock)
    system.stop(zmqTx)
    logger.info("stopping bitcoind")
    stopBitcoind()
    super.afterAll()
  }

  case class Fixture(alice: TestFSMRef[State, Data, Channel],
                     bob: TestFSMRef[State, Data, Channel],
                     alice2bob: TestProbe,
                     bob2alice: TestProbe,
                     alice2watcher: TestProbe,
                     bob2watcher: TestProbe,
                     blockCount: AtomicLong,
                     bitcoinClient: ExtendedBitcoinClient,
                     bitcoinWallet: BitcoinCoreWallet,
                     watcher: TestActorRef[ZmqWatcher],
                     probe: TestProbe)

  // NB: we can't use ScalaTest's fixtures, they would see uninitialized bitcoind fields because they sandbox each test.
  private def withWatcher(utxos: Seq[BtcAmount], testFun: Fixture => Any): Unit = {
    val probe = TestProbe()

    // Create a unique wallet for this test and ensure it has some btc.
    val walletRpcClient = createWallet(s"lightning-${UUID.randomUUID()}")
    val bitcoinClient = new ExtendedBitcoinClient(walletRpcClient)
    val bitcoinWallet = new BitcoinCoreWallet(walletRpcClient)
    utxos.foreach(amount => {
      bitcoinWallet.getReceiveAddress.pipeTo(probe.ref)
      val walletAddress = probe.expectMsgType[String]
      sendToAddress(walletAddress, amount, probe)
    })
    generateBlocks(1)

    val blockCount = new AtomicLong()
    val watcher = TestActorRef[ZmqWatcher](ZmqWatcher.props(Block.RegtestGenesisBlock.hash, blockCount, bitcoinClient))
    // Setup a valid channel between alice and bob.
    val setup = init(TestConstants.Alice.nodeParams.copy(blockCount = blockCount), TestConstants.Bob.nodeParams.copy(blockCount = blockCount), bitcoinWallet)
    reachNormal(setup, Set(StateTestsTags.AnchorOutputs))
    import setup._
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    // Generate blocks to ensure the funding tx is confirmed.
    generateBlocks(1)
    // Execute our test.
    try {
      testFun(Fixture(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, blockCount, bitcoinClient, bitcoinWallet, watcher, probe))
    } finally {
      system.stop(watcher)
    }
  }

  test("add/remove watches from/to utxo map") {
    val m0 = Map.empty[OutPoint, Set[Watch]]
    val txid = randomBytes32
    val outputIndex = 42
    val utxo = OutPoint(txid.reverse, outputIndex)

    val w1 = WatchSpent(TestProbe().ref, txid, outputIndex, BITCOIN_FUNDING_SPENT, hints = Set.empty)
    val w2 = WatchSpent(TestProbe().ref, txid, outputIndex, BITCOIN_FUNDING_SPENT, hints = Set.empty)
    val w3 = WatchSpentBasic(TestProbe().ref, txid, outputIndex, BITCOIN_FUNDING_SPENT)
    val w4 = WatchSpentBasic(TestProbe().ref, randomBytes32, 5, BITCOIN_FUNDING_SPENT)
    val w5 = WatchConfirmed(TestProbe().ref, txid, 3, BITCOIN_FUNDING_SPENT)

    // we test as if the collection was immutable
    val m1 = addWatchedUtxos(m0, w1)
    assert(m1.keySet == Set(utxo) && m1.size == 1)
    val m2 = addWatchedUtxos(m1, w2)
    assert(m2.keySet == Set(utxo) && m2(utxo).size == 2)
    val m3 = addWatchedUtxos(m2, w3)
    assert(m3.keySet == Set(utxo) && m3(utxo).size == 3)
    val m4 = addWatchedUtxos(m3, w4)
    assert(m4.keySet == Set(utxo, OutPoint(w4.txId.reverse, w4.outputIndex)) && m3(utxo).size == 3)
    val m5 = addWatchedUtxos(m4, w5)
    assert(m5.keySet == Set(utxo, OutPoint(w4.txId.reverse, w4.outputIndex)) && m5(utxo).size == 3)
    val m6 = removeWatchedUtxos(m5, w3)
    assert(m6.keySet == Set(utxo, OutPoint(w4.txId.reverse, w4.outputIndex)) && m6(utxo).size == 2)
    val m7 = removeWatchedUtxos(m6, w3)
    assert(m7.keySet == Set(utxo, OutPoint(w4.txId.reverse, w4.outputIndex)) && m7(utxo).size == 2)
    val m8 = removeWatchedUtxos(m7, w2)
    assert(m8.keySet == Set(utxo, OutPoint(w4.txId.reverse, w4.outputIndex)) && m8(utxo).size == 1)
    val m9 = removeWatchedUtxos(m8, w1)
    assert(m9.keySet == Set(OutPoint(w4.txId.reverse, w4.outputIndex)))
    val m10 = removeWatchedUtxos(m9, w4)
    assert(m10.isEmpty)
  }

  test("watch for confirmed transactions") {
    withWatcher(Seq(500 millibtc), f => {
      import f._

      val address = getNewAddress(probe)
      val tx = sendToAddress(address, Btc(1), probe)

      val listener = TestProbe()
      probe.send(watcher, WatchConfirmed(listener.ref, tx.txid, 4, BITCOIN_FUNDING_DEPTHOK))
      probe.send(watcher, WatchConfirmed(listener.ref, tx.txid, 4, BITCOIN_FUNDING_DEPTHOK)) // setting the watch multiple times should be a no-op
      generateBlocks(5)
      assert(listener.expectMsgType[WatchEventConfirmed].tx.txid === tx.txid)
      listener.expectNoMsg(1 second)

      // If we try to watch a transaction that has already been confirmed, we should immediately receive a WatchEventConfirmed.
      probe.send(watcher, WatchConfirmed(listener.ref, tx.txid, 4, BITCOIN_FUNDING_DEPTHOK))
      assert(listener.expectMsgType[WatchEventConfirmed].tx.txid === tx.txid)
      listener.expectNoMsg(1 second)
    })
  }

  test("watch for spent transactions") {
    withWatcher(Seq(500 millibtc), f => {
      import f._

      val address = getNewAddress(probe)
      val priv = dumpPrivateKey(address, probe)
      val tx = sendToAddress(address, Btc(1), probe)
      val outputIndex = tx.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wpkh(priv.publicKey)))
      val (tx1, tx2) = createUnspentTxChain(tx, priv)

      val listener = TestProbe()
      probe.send(watcher, WatchSpentBasic(listener.ref, tx.txid, outputIndex, BITCOIN_FUNDING_SPENT))
      probe.send(watcher, WatchSpent(listener.ref, tx.txid, outputIndex, BITCOIN_FUNDING_SPENT, hints = Set.empty))
      listener.expectNoMsg(1 second)
      bitcoinClient.publishTransaction(tx1).pipeTo(probe.ref)
      probe.expectMsg(tx1.txid)
      // tx and tx1 aren't confirmed yet, but we trigger the WatchEventSpent when we see tx1 in the mempool.
      listener.expectMsgAllOf(
        WatchEventSpentBasic(BITCOIN_FUNDING_SPENT),
        WatchEventSpent(BITCOIN_FUNDING_SPENT, tx1)
      )
      // Let's confirm tx and tx1: seeing tx1 in a block should trigger WatchEventSpent again, but not WatchEventSpentBasic
      // (which only triggers once).
      generateBlocks(2)
      listener.expectMsg(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx1))

      // Let's submit tx2, and set a watch after it has been confirmed this time.
      bitcoinClient.publishTransaction(tx2).pipeTo(probe.ref)
      probe.expectMsg(tx2.txid)
      listener.expectNoMsg(1 second)
      generateBlocks(1)
      probe.send(watcher, WatchSpentBasic(listener.ref, tx1.txid, 0, BITCOIN_FUNDING_SPENT))
      probe.send(watcher, WatchSpent(listener.ref, tx1.txid, 0, BITCOIN_FUNDING_SPENT, hints = Set.empty))
      listener.expectMsgAllOf(
        WatchEventSpentBasic(BITCOIN_FUNDING_SPENT),
        WatchEventSpent(BITCOIN_FUNDING_SPENT, tx2)
      )

      // We use hints and see if we can find tx2
      probe.send(watcher, WatchSpent(listener.ref, tx1.txid, 0, BITCOIN_FUNDING_SPENT, hints = Set(tx2.txid)))
      listener.expectMsg(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx2))

      // We should still find tx2 if the provided hint is wrong
      probe.send(watcher, WatchSpent(listener.ref, tx1.txid, 0, BITCOIN_FUNDING_SPENT, hints = Set(randomBytes32)))
      listener.expectMsg(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx2))
    })
  }

  test("watch for unknown spent transactions") {
    withWatcher(Seq(500 millibtc), f => {
      import f._

      // create a chain of transactions that we don't broadcast yet
      val priv = dumpPrivateKey(getNewAddress(probe), probe)
      val tx1 = {
        bitcoinWallet.fundTransaction(Transaction(2, Nil, TxOut(150000 sat, Script.pay2wpkh(priv.publicKey)) :: Nil, 0), lockUtxos = true, FeeratePerKw(250 sat)).pipeTo(probe.ref)
        val funded = probe.expectMsgType[FundTransactionResponse].tx
        bitcoinWallet.signTransaction(funded).pipeTo(probe.ref)
        probe.expectMsgType[SignTransactionResponse].tx
      }
      val outputIndex = tx1.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wpkh(priv.publicKey)))
      val tx2 = createSpendP2WPKH(tx1, priv, priv.publicKey, 10000 sat, 1, 0)

      // setup watches before we publish transactions
      probe.send(watcher, WatchSpent(probe.ref, tx1.txid, outputIndex, BITCOIN_FUNDING_SPENT, hints = Set.empty))
      probe.send(watcher, WatchConfirmed(probe.ref, tx1.txid, 3, BITCOIN_FUNDING_SPENT))
      bitcoinClient.publishTransaction(tx1).pipeTo(probe.ref)
      probe.expectMsg(tx1.txid)
      generateBlocks(1)
      probe.expectNoMsg(1 second)
      bitcoinClient.publishTransaction(tx2).pipeTo(probe.ref)
      probe.expectMsgAllOf(tx2.txid, WatchEventSpent(BITCOIN_FUNDING_SPENT, tx2))
      probe.expectNoMsg(1 second)
      generateBlocks(1)
      probe.expectMsg(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx2)) // tx2 is confirmed which triggers WatchEventSpent again
      generateBlocks(1)
      assert(probe.expectMsgType[WatchEventConfirmed].tx === tx1) // tx1 now has 3 confirmations
    })
  }

  test("publish transactions with relative and absolute delays") {
    withWatcher(Seq(500 millibtc), f => {
      import f._

      // Ensure watcher is synchronized with the latest block height.
      bitcoinClient.getBlockCount.pipeTo(probe.ref)
      val initialBlockCount = probe.expectMsgType[Long]
      awaitCond(blockCount.get === initialBlockCount)

      // tx1 has an absolute delay but no relative delay
      val priv = dumpPrivateKey(getNewAddress(probe), probe)
      val tx1 = {
        bitcoinWallet.fundTransaction(Transaction(2, Nil, TxOut(150000 sat, Script.pay2wpkh(priv.publicKey)) :: Nil, initialBlockCount + 5), lockUtxos = true, FeeratePerKw(250 sat)).pipeTo(probe.ref)
        val funded = probe.expectMsgType[FundTransactionResponse].tx
        bitcoinWallet.signTransaction(funded).pipeTo(probe.ref)
        probe.expectMsgType[SignTransactionResponse].tx
      }
      probe.send(watcher, PublishAsap(tx1, PublishStrategy.JustPublish))
      generateBlocks(4)
      awaitCond(blockCount.get === initialBlockCount + 4)
      bitcoinClient.getMempool().pipeTo(probe.ref)
      assert(!probe.expectMsgType[Seq[Transaction]].exists(_.txid === tx1.txid)) // tx should not be broadcast yet
      generateBlocks(1)
      awaitCond({
        bitcoinClient.getMempool().pipeTo(probe.ref)
        probe.expectMsgType[Seq[Transaction]].exists(_.txid === tx1.txid)
      }, max = 20 seconds, interval = 1 second)

      // tx2 has a relative delay but no absolute delay
      val tx2 = createSpendP2WPKH(tx1, priv, priv.publicKey, 10000 sat, sequence = 2, lockTime = 0)
      probe.send(watcher, WatchConfirmed(probe.ref, tx1.txid, 1, BITCOIN_FUNDING_DEPTHOK))
      probe.send(watcher, PublishAsap(tx2, PublishStrategy.JustPublish))
      generateBlocks(1)
      assert(probe.expectMsgType[WatchEventConfirmed].tx === tx1)
      generateBlocks(2)
      awaitCond({
        bitcoinClient.getMempool().pipeTo(probe.ref)
        probe.expectMsgType[Seq[Transaction]].exists(_.txid === tx2.txid)
      }, max = 20 seconds, interval = 1 second)

      // tx3 has both relative and absolute delays
      val tx3 = createSpendP2WPKH(tx2, priv, priv.publicKey, 10000 sat, sequence = 1, lockTime = blockCount.get + 5)
      probe.send(watcher, WatchConfirmed(probe.ref, tx2.txid, 1, BITCOIN_FUNDING_DEPTHOK))
      probe.send(watcher, WatchSpent(probe.ref, tx2.txid, 0, BITCOIN_FUNDING_SPENT, hints = Set.empty))
      probe.send(watcher, PublishAsap(tx3, PublishStrategy.JustPublish))
      generateBlocks(1)
      assert(probe.expectMsgType[WatchEventConfirmed].tx === tx2)
      val currentBlockCount = blockCount.get
      // after 1 block, the relative delay is elapsed, but not the absolute delay
      generateBlocks(1)
      awaitCond(blockCount.get == currentBlockCount + 1)
      probe.expectNoMsg(1 second)
      generateBlocks(3)
      probe.expectMsg(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx3))
      bitcoinClient.getMempool().pipeTo(probe.ref)
      probe.expectMsgType[Seq[Transaction]].exists(_.txid === tx3.txid)
    })
  }

  private def getMempoolTxs(bitcoinClient: ExtendedBitcoinClient, expectedTxCount: Int, probe: TestProbe = TestProbe()): Seq[MempoolTx] = {
    awaitCond({
      bitcoinClient.getMempool().pipeTo(probe.ref)
      probe.expectMsgType[Seq[Transaction]].size == expectedTxCount
    }, interval = 250 milliseconds)

    bitcoinClient.getMempool().pipeTo(probe.ref)
    probe.expectMsgType[Seq[Transaction]].map(tx => {
      bitcoinClient.getMempoolTx(tx.txid).pipeTo(probe.ref)
      probe.expectMsgType[MempoolTx]
    })
  }

  def closeChannelWithoutHtlcs(f: Fixture): PublishAsap = {
    import f._

    val commitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val currentFeerate = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.feeratePerKw
    probe.send(alice, CMD_FORCECLOSE(probe.ref))
    probe.expectMsgType[CommandSuccess[CMD_FORCECLOSE]]

    val publishCommitTx = alice2watcher.expectMsgType[PublishAsap]
    assert(publishCommitTx.tx.txid === commitTx.txid)
    assert(publishCommitTx.strategy.isInstanceOf[PublishStrategy.SetFeerate])
    val publishStrategy = publishCommitTx.strategy.asInstanceOf[PublishStrategy.SetFeerate]
    assert(publishStrategy.currentFeerate < publishStrategy.targetFeerate)
    assert(publishStrategy.currentFeerate === currentFeerate)
    assert(publishStrategy.targetFeerate === TestConstants.feeratePerKw)
    publishCommitTx
  }

  test("commit tx feerate high enough, not spending anchor output") {
    withWatcher(Seq(500 millibtc), f => {
      import f._

      val publishCommitTx = closeChannelWithoutHtlcs(f)
      val publishStrategy = publishCommitTx.strategy.asInstanceOf[PublishStrategy.SetFeerate]
      alice2watcher.forward(watcher, publishCommitTx.copy(strategy = publishStrategy.copy(targetFeerate = publishStrategy.currentFeerate)))

      // wait for the commit tx and anchor tx to be published
      val mempoolTx = getMempoolTxs(bitcoinClient, 1, probe).head
      assert(mempoolTx.txid === publishCommitTx.tx.txid)

      val targetFee = Transactions.weight2fee(publishStrategy.currentFeerate, mempoolTx.weight.toInt)
      val actualFee = mempoolTx.fees
      assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee")
    })
  }

  test("commit tx feerate too low, not enough wallet inputs to increase feerate") {
    withWatcher(Seq(10.1 millibtc), f => {
      import f._

      val publishCommitTx = closeChannelWithoutHtlcs(f)
      alice2watcher.forward(watcher, publishCommitTx)

      // wait for the commit tx to be published, anchor will not be published because we don't have enough funds
      val mempoolTx1 = getMempoolTxs(bitcoinClient, 1, probe).head
      assert(mempoolTx1.txid === publishCommitTx.tx.txid)

      // add more funds to our wallet
      bitcoinWallet.getReceiveAddress.pipeTo(probe.ref)
      val walletAddress = probe.expectMsgType[String]
      sendToAddress(walletAddress, 1 millibtc, probe)
      generateBlocks(1)

      // wait for the anchor tx to be published
      val mempoolTx2 = getMempoolTxs(bitcoinClient, 1, probe).head
      bitcoinClient.getTransaction(mempoolTx2.txid).pipeTo(probe.ref)
      val anchorTx = probe.expectMsgType[Transaction]
      assert(anchorTx.txIn.exists(_.outPoint.txid == mempoolTx1.txid))
      val targetFee = Transactions.weight2fee(TestConstants.feeratePerKw, (mempoolTx1.weight + mempoolTx2.weight).toInt)
      val actualFee = mempoolTx1.fees + mempoolTx2.fees
      assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee")
    })
  }

  test("commit tx feerate too low, spending anchor output") {
    withWatcher(Seq(500 millibtc), f => {
      import f._

      val publishCommitTx = closeChannelWithoutHtlcs(f)
      alice2watcher.forward(watcher, publishCommitTx)

      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(bitcoinClient, 2, probe)
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
    withWatcher(utxos, f => {
      import f._

      val publishCommitTx = closeChannelWithoutHtlcs(f)
      alice2watcher.forward(watcher, publishCommitTx)

      // wait for the commit tx and anchor tx to be published
      val mempoolTxs = getMempoolTxs(bitcoinClient, 2, probe)
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
    withWatcher(Seq(500 millibtc), f => {
      val PublishAsap(commitTx, PublishStrategy.SetFeerate(currentFeerate, targetFeerate, dustLimit, signingKit: ClaimAnchorOutputSigningKit)) = closeChannelWithoutHtlcs(f)
      for (_ <- 1 to 100) {
        val walletInputsCount = 1 + Random.nextInt(5)
        val walletInputs = (1 to walletInputsCount).map(_ => TxIn(OutPoint(randomBytes32, 0), Nil, 0))
        val amountIn = dustLimit * walletInputsCount + Random.nextInt(25_000_000).sat
        val amountOut = dustLimit + Random.nextLong(amountIn.toLong).sat
        val unsignedTx = signingKit.txWithInput.copy(tx = signingKit.txWithInput.tx.copy(
          txIn = signingKit.txWithInput.tx.txIn ++ walletInputs,
          txOut = TxOut(amountOut, Script.pay2wpkh(randomKey.publicKey)) :: Nil,
        ))
        val adjustedTx = adjustAnchorOutputChange(unsignedTx, commitTx, amountIn, currentFeerate, targetFeerate, dustLimit)
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
          val targetFee = Transactions.weight2fee(targetFeerate, signedTx.weight() + commitTx.weight()) - Transactions.weight2fee(currentFeerate, commitTx.weight())
          val actualFee = amountIn - signedTx.txOut.map(_.amount).sum
          assert(targetFee * 0.9 <= actualFee && actualFee <= targetFee * 1.1, s"actualFee=$actualFee targetFee=$targetFee amountIn=$amountIn tx=$signedTx")
        }
      }
    })
  }

  def closeChannelWithHtlcs(f: Fixture): (PublishAsap, PublishAsap, PublishAsap) = {
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
    val publishCommitTx = alice2watcher.expectMsgType[PublishAsap]
    assert(alice2watcher.expectMsgType[PublishAsap].strategy === PublishStrategy.JustPublish) // claim main output
    val publishHtlcSuccess = alice2watcher.expectMsgType[PublishAsap]
    val publishHtlcTimeout = alice2watcher.expectMsgType[PublishAsap]
    Seq(publishCommitTx, publishHtlcSuccess, publishHtlcTimeout).foreach(publishTx => {
      assert(publishTx.strategy.isInstanceOf[PublishStrategy.SetFeerate])
      val publishStrategy = publishTx.strategy.asInstanceOf[PublishStrategy.SetFeerate]
      assert(publishStrategy.currentFeerate === currentFeerate)
      assert(publishStrategy.currentFeerate < publishStrategy.targetFeerate)
      assert(publishStrategy.targetFeerate === TestConstants.feeratePerKw)
    })

    (publishCommitTx, publishHtlcSuccess, publishHtlcTimeout)
  }

  test("htlc tx feerate high enough, not adding wallet inputs") {
    withWatcher(Seq(500 millibtc), f => {
      import f._

      val currentFeerate = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.feeratePerKw
      val (publishCommitTx, publishHtlcSuccess, publishHtlcTimeout) = closeChannelWithHtlcs(f)

      // Publish the commit tx.
      alice2watcher.forward(watcher, publishCommitTx)
      alice2watcher.forward(watcher, publishHtlcSuccess.copy(strategy = publishHtlcSuccess.strategy.asInstanceOf[PublishStrategy.SetFeerate].copy(targetFeerate = currentFeerate)))
      alice2watcher.forward(watcher, publishHtlcTimeout.copy(strategy = publishHtlcTimeout.strategy.asInstanceOf[PublishStrategy.SetFeerate].copy(targetFeerate = currentFeerate)))
      // HTLC txs will only be published once the commit tx is confirmed (csv delay)
      getMempoolTxs(bitcoinClient, 2, probe)
      generateBlocks(2)

      // The HTLC-success tx will be immediately published.
      val htlcSuccessTx = getMempoolTxs(bitcoinClient, 1, probe).head
      val htlcSuccessTargetFee = Transactions.weight2fee(currentFeerate, htlcSuccessTx.weight.toInt)
      assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx.fees && htlcSuccessTx.fees <= htlcSuccessTargetFee * 1.1, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcSuccessTargetFee")

      // The HTLC-timeout tx will be published once its absolute timeout is satisfied.
      generateBlocks(144)
      val htlcTimeoutTx = getMempoolTxs(bitcoinClient, 1, probe).head
      val htlcTimeoutTargetFee = Transactions.weight2fee(currentFeerate, htlcTimeoutTx.weight.toInt)
      assert(htlcTimeoutTargetFee * 0.9 <= htlcTimeoutTx.fees && htlcTimeoutTx.fees <= htlcTimeoutTargetFee * 1.1, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcTimeoutTargetFee")
    })
  }

  test("htlc tx feerate too low, not enough wallet inputs to increase feerate") {
    withWatcher(Seq(10.1 millibtc), f => {
      import f._

      val initialBlockCount = blockCount.get()
      val (publishCommitTx, publishHtlcSuccess, _) = closeChannelWithHtlcs(f)
      val publishCommitStrategy = publishCommitTx.strategy.asInstanceOf[PublishStrategy.SetFeerate]

      // Publish the commit tx without the anchor.
      alice2watcher.forward(watcher, publishCommitTx.copy(strategy = publishCommitStrategy.copy(targetFeerate = publishCommitStrategy.currentFeerate)))
      alice2watcher.forward(watcher, publishHtlcSuccess)
      // HTLC txs will only be published once the commit tx is confirmed (csv delay)
      getMempoolTxs(bitcoinClient, 1, probe)
      generateBlocks(2)
      awaitCond(blockCount.get() > initialBlockCount)

      // Add more funds to our wallet to allow bumping HTLC txs.
      bitcoinWallet.getReceiveAddress.pipeTo(probe.ref)
      val walletAddress = probe.expectMsgType[String]
      sendToAddress(walletAddress, 1 millibtc, probe)
      generateBlocks(1)

      // The HTLC-success tx will be immediately published.
      val htlcSuccessTx = getMempoolTxs(bitcoinClient, 1, probe).head
      val htlcSuccessTargetFee = Transactions.weight2fee(TestConstants.feeratePerKw, htlcSuccessTx.weight.toInt)
      assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx.fees && htlcSuccessTx.fees <= htlcSuccessTargetFee * 1.1, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcSuccessTargetFee")
    })
  }

  test("htlc tx feerate too low, adding wallet inputs") {
    withWatcher(Seq(500 millibtc), f => {
      import f._

      val (publishCommitTx, publishHtlcSuccess, publishHtlcTimeout) = closeChannelWithHtlcs(f)

      // Publish the commit tx.
      alice2watcher.forward(watcher, publishCommitTx)
      alice2watcher.forward(watcher, publishHtlcSuccess)
      alice2watcher.forward(watcher, publishHtlcTimeout)
      // HTLC txs will only be published once the commit tx is confirmed (csv delay)
      getMempoolTxs(bitcoinClient, 2, probe)
      generateBlocks(2)

      // The HTLC-success tx will be immediately published.
      val htlcSuccessTx = getMempoolTxs(bitcoinClient, 1, probe).head
      val htlcSuccessTargetFee = Transactions.weight2fee(TestConstants.feeratePerKw, htlcSuccessTx.weight.toInt)
      assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx.fees && htlcSuccessTx.fees <= htlcSuccessTargetFee * 1.1, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcSuccessTargetFee")

      // The HTLC-timeout tx will be published once its absolute timeout is satisfied.
      generateBlocks(144)
      val htlcTimeoutTx = getMempoolTxs(bitcoinClient, 1, probe).head
      val htlcTimeoutTargetFee = Transactions.weight2fee(TestConstants.feeratePerKw, htlcTimeoutTx.weight.toInt)
      assert(htlcTimeoutTargetFee * 0.9 <= htlcTimeoutTx.fees && htlcTimeoutTx.fees <= htlcTimeoutTargetFee * 1.1, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcTimeoutTargetFee")
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
    withWatcher(utxos, f => {
      import f._

      val (publishCommitTx, publishHtlcSuccess, publishHtlcTimeout) = closeChannelWithHtlcs(f)
      val publishCommitStrategy = publishCommitTx.strategy.asInstanceOf[PublishStrategy.SetFeerate]

      // Publish the commit tx without the anchor.
      alice2watcher.forward(watcher, publishCommitTx.copy(strategy = publishCommitStrategy.copy(targetFeerate = publishCommitStrategy.currentFeerate)))
      alice2watcher.forward(watcher, publishHtlcSuccess)
      alice2watcher.forward(watcher, publishHtlcTimeout)
      // HTLC txs will only be published once the commit tx is confirmed (csv delay)
      getMempoolTxs(bitcoinClient, 1, probe)
      generateBlocks(2)

      // The HTLC-success tx will be immediately published.
      val htlcSuccessTx = getMempoolTxs(bitcoinClient, 1, probe).head
      bitcoinClient.getTransaction(htlcSuccessTx.txid).pipeTo(probe.ref)
      assert(probe.expectMsgType[Transaction].txIn.length > 2) // we added more than 1 wallet input
      val htlcSuccessTargetFee = Transactions.weight2fee(TestConstants.feeratePerKw, htlcSuccessTx.weight.toInt)
      assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx.fees && htlcSuccessTx.fees <= htlcSuccessTargetFee * 1.4, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcSuccessTargetFee")

      // The HTLC-timeout tx will be published once its absolute timeout is satisfied.
      generateBlocks(144)
      val htlcTimeoutTx = getMempoolTxs(bitcoinClient, 1, probe).head
      bitcoinClient.getTransaction(htlcTimeoutTx.txid).pipeTo(probe.ref)
      assert(probe.expectMsgType[Transaction].txIn.length > 2) // we added more than 1 wallet input
      val htlcTimeoutTargetFee = Transactions.weight2fee(TestConstants.feeratePerKw, htlcTimeoutTx.weight.toInt)
      assert(htlcTimeoutTargetFee * 0.9 <= htlcTimeoutTx.fees && htlcTimeoutTx.fees <= htlcTimeoutTargetFee * 1.4, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcTimeoutTargetFee")
    })
  }

  test("htlc tx sent after commit tx confirmed") {
    withWatcher(Seq(500 millibtc), f => {
      import f._

      // Add incoming htlc.
      val (r, htlc) = addHtlc(5_000_000 msat, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)

      // Force-close channel and verify txs sent to watcher.
      val commitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      assert(commitTx.txOut.size === 5)
      probe.send(alice, CMD_FORCECLOSE(probe.ref))
      probe.expectMsgType[CommandSuccess[CMD_FORCECLOSE]]
      val publishCommitTx = alice2watcher.expectMsgType[PublishAsap]
      assert(alice2watcher.expectMsgType[PublishAsap].strategy === PublishStrategy.JustPublish) // claim main output
      alice2watcher.expectMsgType[WatchConfirmed] // commit tx
      alice2watcher.expectMsgType[WatchConfirmed] // claim main output
      alice2watcher.expectMsgType[WatchSpent] // alice doesn't have the preimage yet to redeem the htlc but she watches the output
      alice2watcher.expectNoMsg(100 millis)

      // Publish and confirm the commit tx.
      alice2watcher.forward(watcher, publishCommitTx)
      getMempoolTxs(bitcoinClient, 2, probe)
      generateBlocks(2)

      probe.send(alice, CMD_FULFILL_HTLC(htlc.id, r, replyTo_opt = Some(probe.ref)))
      probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]
      alice2watcher.expectMsg(publishCommitTx)
      assert(alice2watcher.expectMsgType[PublishAsap].strategy === PublishStrategy.JustPublish) // claim main output
      val publishHtlcSuccess = alice2watcher.expectMsgType[PublishAsap]
      alice2watcher.forward(watcher, publishHtlcSuccess)

      // The HTLC-success tx will be immediately published.
      val htlcSuccessTx = getMempoolTxs(bitcoinClient, 1, probe).head
      val htlcSuccessTargetFee = Transactions.weight2fee(TestConstants.feeratePerKw, htlcSuccessTx.weight.toInt)
      assert(htlcSuccessTargetFee * 0.9 <= htlcSuccessTx.fees && htlcSuccessTx.fees <= htlcSuccessTargetFee * 1.1, s"actualFee=${htlcSuccessTx.fees} targetFee=$htlcSuccessTargetFee")
    })
  }

  test("adjust htlc tx change amount", Tag("fuzzy")) {
    withWatcher(Seq(500 millibtc), f => {
      val (_, publishHtlcSuccess, publishHtlcTimeout) = closeChannelWithHtlcs(f)
      val PublishAsap(htlcSuccessTx, PublishStrategy.SetFeerate(_, targetFeerate, dustLimit, successSigningKit: HtlcSuccessSigningKit)) = publishHtlcSuccess
      val PublishAsap(htlcTimeoutTx, PublishStrategy.SetFeerate(_, _, _, timeoutSigningKit: HtlcTimeoutSigningKit)) = publishHtlcTimeout
      for (_ <- 1 to 100) {
        val walletInputsCount = 1 + Random.nextInt(5)
        val walletInputs = (1 to walletInputsCount).map(_ => TxIn(OutPoint(randomBytes32, 0), Nil, 0))
        val walletAmountIn = dustLimit * walletInputsCount + Random.nextInt(25_000_000).sat
        val changeOutput = TxOut(Random.nextLong(walletAmountIn.toLong).sat, Script.pay2wpkh(randomKey.publicKey))
        val unsignedHtlcSuccessTx = successSigningKit.txWithInput.copy(tx = htlcSuccessTx.copy(
          txIn = htlcSuccessTx.txIn ++ walletInputs,
          txOut = htlcSuccessTx.txOut ++ Seq(changeOutput)
        ))
        val unsignedHtlcTimeoutTx = timeoutSigningKit.txWithInput.copy(tx = htlcTimeoutTx.copy(
          txIn = htlcTimeoutTx.txIn ++ walletInputs,
          txOut = htlcTimeoutTx.txOut ++ Seq(changeOutput)
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