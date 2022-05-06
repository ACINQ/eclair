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

import akka.actor.typed.scaladsl.adapter.{ClassicActorSystemOps, actorRefAdapter}
import akka.pattern.pipe
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong, Script, Transaction, TxOut}
import fr.acinq.eclair.blockchain.OnChainWallet.{FundTransactionResponse, SignTransactionResponse}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.{MempoolTx, Utxo}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.InteractiveTx._
import fr.acinq.eclair.channel.InteractiveTxFunder.{Fund, FundingFailed, FundingSucceeded}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol.TxSignatures
import fr.acinq.eclair.{TestKitBaseClass, randomBytes32, randomKey}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class InteractiveTxFunderSpec extends TestKitBaseClass with BitcoindService with AnyFunSuiteLike with BeforeAndAfterAll {

  import InteractiveTxSpec._

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

  test("fund transaction") {
    // Initialize wallets with a few confirmed utxos.
    val probe = TestProbe()
    val initiatorRpcClient = createWallet("basic-funding-initiator")
    val initiatorWallet = new BitcoinCoreClient(initiatorRpcClient)
    val nonInitiatorRpcClient = createWallet("basic-funding-non-initiator")
    val nonInitiatorWallet = new BitcoinCoreClient(nonInitiatorRpcClient)
    Seq(50_000 sat, 35_000 sat, 60_000 sat).foreach(amount => addUtxo(initiatorWallet, amount, probe))
    Seq(100_000 sat, 75_000 sat).foreach(amount => addUtxo(nonInitiatorWallet, amount, probe))
    generateBlocks(1)

    // Each participant funds part of the shared transaction.
    val channelId = randomBytes32()
    val sharedOutputScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val lockTime = 42
    val targetFeerate = FeeratePerKw(5000 sat)
    val initiatorParams = InteractiveTxParams(channelId, isInitiator = true, 120_000 sat, 40_000 sat, sharedOutputScript, lockTime, 660 sat, targetFeerate)
    system.spawnAnonymous(InteractiveTxFunder(randomKey().publicKey, initiatorParams, initiatorWallet)) ! Fund(probe.ref, Nil)
    val initiatorContributions = probe.expectMsgType[FundingSucceeded].contributions
    val nonInitiatorParams = InteractiveTxParams(channelId, isInitiator = false, 40_000 sat, 120_000 sat, sharedOutputScript, lockTime, 660 sat, targetFeerate)
    system.spawnAnonymous(InteractiveTxFunder(randomKey().publicKey, nonInitiatorParams, nonInitiatorWallet)) ! Fund(probe.ref, Nil)
    val nonInitiatorContributions = probe.expectMsgType[FundingSucceeded].contributions

    // The initiator is responsible for adding the shared output.
    assert(initiatorContributions.inputs.length === 3)
    assert(initiatorContributions.outputs.length === 2)
    assert(initiatorContributions.outputs.count(_.pubkeyScript == sharedOutputScript) === 1)
    assert(initiatorContributions.outputs.exists(o => o.pubkeyScript == sharedOutputScript && o.amount == 160_000.sat))
    assert(nonInitiatorContributions.inputs.length === 1)
    assert(nonInitiatorContributions.outputs.length === 1)
    assert(nonInitiatorContributions.outputs.count(_.pubkeyScript == sharedOutputScript) === 0)

    // Utxos are locked for the duration of the protocol
    val initiatorLocks = getLocks(probe, initiatorRpcClient)
    assert(initiatorLocks.size === 3)
    assert(initiatorLocks === initiatorContributions.inputs.map(toOutPoint).toSet)
    val nonInitiatorLocks = getLocks(probe, nonInitiatorRpcClient)
    assert(nonInitiatorLocks.size === 1)
    assert(nonInitiatorLocks === nonInitiatorContributions.inputs.map(toOutPoint).toSet)

    // The resulting transaction is valid and has the right feerate.
    val sharedInitiatorTx = SharedTransaction(initiatorContributions.inputs, nonInitiatorContributions.inputs.map(i => RemoteTxAddInput(i)), initiatorContributions.outputs, nonInitiatorContributions.outputs.map(o => RemoteTxAddOutput(o)), initiatorParams.lockTime)
    InteractiveTx.signTx(channelId, sharedInitiatorTx, initiatorWallet).pipeTo(probe.ref)
    val initiatorSignedTx = probe.expectMsgType[PartiallySignedSharedTransaction]
    val sharedNonInitiatorTx = SharedTransaction(nonInitiatorContributions.inputs, initiatorContributions.inputs.map(i => RemoteTxAddInput(i)), nonInitiatorContributions.outputs, initiatorContributions.outputs.map(o => RemoteTxAddOutput(o)), nonInitiatorParams.lockTime)
    InteractiveTx.signTx(channelId, sharedNonInitiatorTx, nonInitiatorWallet).pipeTo(probe.ref)
    val nonInitiatorSignedTx = probe.expectMsgType[PartiallySignedSharedTransaction]
    val Right(initiatorTx) = InteractiveTx.addRemoteSigs(initiatorParams, initiatorSignedTx, nonInitiatorSignedTx.localSigs)
    val Right(nonInitiatorTx) = InteractiveTx.addRemoteSigs(nonInitiatorParams, nonInitiatorSignedTx, initiatorSignedTx.localSigs)
    assert(initiatorTx.signedTx === nonInitiatorTx.signedTx)
    val signedTx = initiatorTx.signedTx
    assert(signedTx.lockTime === lockTime)
    initiatorWallet.publishTransaction(signedTx).pipeTo(probe.ref)
    probe.expectMsg(signedTx.txid)
    initiatorWallet.getMempoolTx(signedTx.txid).pipeTo(probe.ref)
    val mempoolTx = probe.expectMsgType[MempoolTx]
    assert(mempoolTx.fees === sharedInitiatorTx.fees)
    assert(targetFeerate <= initiatorTx.feerate && initiatorTx.feerate <= targetFeerate * 1.25, s"unexpected feerate (target=$targetFeerate actual=${initiatorTx.feerate})")
  }

  test("fund transaction without contributing (initiator)") {
    // Initialize wallet with a few confirmed utxos.
    val probe = TestProbe()
    val wallet = new BitcoinCoreClient(createWallet("non-contributing-initiator"))
    addUtxo(wallet, 100_000 sat, probe)
    generateBlocks(1)

    // Even though the initiator isn't contributing, they're paying the fees for the common parts of the transaction.
    val params = InteractiveTxParams(randomBytes32(), isInitiator = true, 0 sat, 50_000 sat, Script.write(Script.pay2wpkh(randomKey().publicKey)), 0, 330 sat, FeeratePerKw(4000 sat))
    assert(params.fundingAmount === 50_000.sat)
    system.spawnAnonymous(InteractiveTxFunder(randomKey().publicKey, params, wallet)) ! Fund(probe.ref, Nil)
    val fundingContributions = probe.expectMsgType[FundingSucceeded].contributions
    assert(fundingContributions.inputs.length === 1)
    assert(fundingContributions.outputs.length === 2)
    assert(fundingContributions.outputs.exists(o => o.pubkeyScript == params.fundingPubkeyScript && o.amount === params.fundingAmount))

    // But the initiator doesn't pay the funding amount, that will be the non-initiator's responsibility.
    val initiatorFees = computeFees(fundingContributions) + params.fundingAmount
    assert(initiatorFees > 0.sat)
    val partialTx = SharedTransaction(fundingContributions.inputs, Nil, fundingContributions.outputs, Nil, params.lockTime)
    InteractiveTx.signTx(params.channelId, partialTx, wallet).pipeTo(probe.ref)
    val partiallySignedTx = probe.expectMsgType[PartiallySignedSharedTransaction]
    val initiatorTx = FullySignedSharedTransaction(partiallySignedTx.tx, partiallySignedTx.localSigs, TxSignatures(params.channelId, partialTx.buildUnsignedTx().txid, Nil))
    val feerate = Transactions.fee2rate(initiatorFees, initiatorTx.signedTx.weight())
    assert(params.targetFeerate <= feerate && feerate <= params.targetFeerate * 1.25, s"unexpected feerate (target=${params.targetFeerate} actual=$feerate)")
  }

  test("fund transaction without contributing (non-initiator)") {
    // Initialize empty wallet.
    val probe = TestProbe()
    val wallet = new BitcoinCoreClient(createWallet("non-contributing-non-initiator"))

    // When the non-initiator isn't contributing, they don't need to do anything.
    val params = InteractiveTxParams(randomBytes32(), isInitiator = false, 0 sat, 150_000 sat, Script.write(Script.pay2wpkh(randomKey().publicKey)), 0, 330 sat, FeeratePerKw(4000 sat))
    assert(params.fundingAmount === 150_000.sat)
    system.spawnAnonymous(InteractiveTxFunder(randomKey().publicKey, params, wallet)) ! Fund(probe.ref, Nil)
    val fundingContributions = probe.expectMsgType[FundingSucceeded].contributions
    assert(fundingContributions.inputs.isEmpty)
    assert(fundingContributions.outputs.isEmpty)
  }

  test("fund transaction with previous inputs") {
    // Initialize wallet with a few confirmed utxos.
    val probe = TestProbe()
    val wallet = new BitcoinCoreClient(createWallet("funding-previous-inputs"))
    Seq(55_000 sat, 60_000 sat, 57_000 sat, 52_000 sat, 75_000 sat).foreach(amount => addUtxo(wallet, amount, probe))
    generateBlocks(1)

    // We fund the transaction a first time.
    val feerate1 = FeeratePerKw(3000 sat)
    val params1 = InteractiveTxParams(randomBytes32(), isInitiator = true, 100_000 sat, 50_000 sat, Script.write(Script.pay2wpkh(randomKey().publicKey)), 0, 330 sat, feerate1)
    system.spawnAnonymous(InteractiveTxFunder(randomKey().publicKey, params1, wallet)) ! Fund(probe.ref, Nil)
    val contributions1 = probe.expectMsgType[FundingSucceeded].contributions
    assert(contributions1.inputs.length === 2)
    assert(contributions1.outputs.length <= 2)
    assert(contributions1.outputs.exists(o => o.pubkeyScript == params1.fundingPubkeyScript && o.amount == params1.fundingAmount))
    val fee1 = computeFees(contributions1)

    // We fund if a second time, re-using the same inputs and adding new ones if necessary.
    val feerate2 = FeeratePerKw(7500 sat)
    val params2 = params1.copy(targetFeerate = feerate2)
    system.spawnAnonymous(InteractiveTxFunder(randomKey().publicKey, params2, wallet)) ! Fund(probe.ref, contributions1.inputs)
    val contributions2 = probe.expectMsgType[FundingSucceeded].contributions
    contributions1.inputs.foreach(i => assert(contributions2.inputs.contains(i)))
    assert(contributions2.outputs.length <= 2)
    assert(contributions2.outputs.exists(o => o.pubkeyScript == params1.fundingPubkeyScript && o.amount == params1.fundingAmount))
    val fee2 = computeFees(contributions2)
    assert(fee2 > fee1)
  }

  test("skip unusable utxos when funding transaction") {
    // Initialize wallet with a few confirmed utxos, including some unusable utxos.
    val probe = TestProbe()
    val walletRpcClient = createWallet("funding-unusable-utxos")
    val wallet = new BitcoinCoreClient(walletRpcClient)
    Seq(75_000 sat, 60_000 sat).foreach(amount => addUtxo(wallet, amount, probe))
    // Dual funding disallows non-segwit inputs.
    val legacyTxId = {
      val legacyAddress = getNewAddress(probe, walletRpcClient, Some("legacy"))
      sendToAddress(legacyAddress, 100_000 sat, probe).txid
    }
    // Dual funding cannot use transactions that exceed 65k bytes.
    val bigTxId = {
      wallet.getReceivePubkey().pipeTo(probe.ref)
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
    wallet.listUnspent().pipeTo(probe.ref)
    val utxos = probe.expectMsgType[Seq[Utxo]]
    assert(utxos.length === 4)
    assert(utxos.exists(_.txid == bigTxId))
    assert(utxos.exists(_.txid == legacyTxId))

    // We can't use some of our utxos, so we don't have enough to fund our channel.
    val params = InteractiveTxParams(randomBytes32(), isInitiator = true, 140_000 sat, 0 sat, Script.write(Script.pay2wpkh(randomKey().publicKey)), 0, 660 sat, FeeratePerKw(5000 sat))
    system.spawnAnonymous(InteractiveTxFunder(randomKey().publicKey, params, wallet)) ! Fund(probe.ref, Nil)
    val failure = probe.expectMsgType[FundingFailed]
    assert(failure.t.getMessage.contains("Insufficient funds"))
    // Utxos shouldn't be locked after a failure.
    awaitCond(getLocks(probe, walletRpcClient).isEmpty, max = 10 seconds, interval = 100 millis)

    // We add more usable utxos to unblock funding.
    Seq(80_000 sat, 50_000 sat).foreach(amount => addUtxo(wallet, amount, probe))
    generateBlocks(1)
    system.spawnAnonymous(InteractiveTxFunder(randomKey().publicKey, params, wallet)) ! Fund(probe.ref, Nil)
    val contributions = probe.expectMsgType[FundingSucceeded].contributions
    assert(!contributions.inputs.exists(_.previousTx.txid == legacyTxId))
    assert(!contributions.inputs.exists(_.previousTx.txid == bigTxId))
    // Only used utxos should be locked.
    awaitCond({
      val locks = getLocks(probe, walletRpcClient)
      locks === contributions.inputs.map(toOutPoint).toSet
    }, max = 10 seconds, interval = 100 millis)

    val sharedTx = SharedTransaction(contributions.inputs, Nil, contributions.outputs, Nil, params.lockTime)
    InteractiveTx.signTx(params.channelId, sharedTx, wallet).pipeTo(probe.ref)
    val partiallySignedTx = probe.expectMsgType[PartiallySignedSharedTransaction]
    val Right(initiatorTx) = InteractiveTx.addRemoteSigs(params, partiallySignedTx, TxSignatures(params.channelId, sharedTx.buildUnsignedTx().txid, Nil))
    wallet.publishTransaction(initiatorTx.signedTx).pipeTo(probe.ref)
    probe.expectMsg(initiatorTx.signedTx.txid)
    wallet.getMempoolTx(initiatorTx.signedTx.txid).pipeTo(probe.ref)
    val mempoolTx = probe.expectMsgType[MempoolTx]
    assert(mempoolTx.fees === sharedTx.fees)
    assert(params.targetFeerate <= initiatorTx.feerate && initiatorTx.feerate <= params.targetFeerate * 1.25, s"unexpected feerate (target=${params.targetFeerate} actual=${initiatorTx.feerate})")
  }

}
