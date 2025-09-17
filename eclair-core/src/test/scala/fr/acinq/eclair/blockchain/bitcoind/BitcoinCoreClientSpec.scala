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

import akka.actor.Status.Failure
import akka.pattern.pipe
import akka.testkit.TestProbe
import fr.acinq.bitcoin
import fr.acinq.bitcoin.psbt.{Psbt, UpdateFailure}
import fr.acinq.bitcoin.scalacompat.Crypto.{PublicKey, der2compact}
import fr.acinq.bitcoin.scalacompat.{Block, BlockHash, BlockId, Btc, BtcDouble, Crypto, DeterministicWallet, KotlinUtils, MilliBtcDouble, MnemonicCode, OP_DROP, OP_PUSHDATA, OutPoint, Satoshi, SatoshiLong, Script, ScriptWitness, Transaction, TxId, TxIn, TxOut, addressToPublicKeyScript, computeBIP84Address, computeP2WpkhAddress}
import fr.acinq.bitcoin.{Bech32, SigHash, SigVersion}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.balance.CheckBalance
import fr.acinq.eclair.blockchain.OnChainWallet.{FundTransactionResponse, MakeFundingTxResponse, OnChainBalance, ProcessPsbtResponse}
import fr.acinq.eclair.blockchain.WatcherSpec.{createSpendManyP2WPKH, createSpendP2WPKH}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.{BitcoinReq, SignTransactionResponse}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.AddressType.{P2tr, P2wpkh}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient._
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinJsonRPCAuthMethod.UserPassword
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinCoreClient, BitcoinJsonRPCClient, JsonRPCError}
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.crypto.keymanager.LocalOnChainKeyManager
import fr.acinq.eclair.transactions.Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.{BlockHeight, TestConstants, TestKitBaseClass, TimestampSecond, randomBytes32, randomKey}
import grizzled.slf4j.Logging
import org.json4s.JsonAST._
import org.json4s.{DefaultFormats, Formats}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

class BitcoinCoreClientSpec extends TestKitBaseClass with BitcoindService with AnyFunSuiteLike with BeforeAndAfterAll with Logging {

  implicit val formats: Formats = DefaultFormats

  val defaultAddressType_opt: Option[String] = Some("bech32")

  override def beforeAll(): Unit = {
    // Note that we don't specify a default change address type, allowing bitcoind to choose between p2wpkh and p2tr.
    startBitcoind(defaultAddressType_opt = defaultAddressType_opt, changeAddressType_opt = None, mempoolSize_opt = Some(5 /* MB */), mempoolMinFeerate_opt = Some(FeeratePerByte(2 sat)))
    waitForBitcoindReady()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  test("encrypt wallet") {
    assume(!useEclairSigner)

    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()
    val walletPassword = Random.alphanumeric.take(8).mkString
    sender.send(bitcoincli, BitcoinReq("encryptwallet", walletPassword))
    sender.expectMsgType[JString](60 seconds)
    restartBitcoind(sender)

    val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey().publicKey, randomKey().publicKey)))
    bitcoinClient.makeFundingTx(pubkeyScript, 50 millibtc, FeeratePerKw(10000 sat)).pipeTo(sender.ref)

    val error = sender.expectMsgType[Failure].cause.asInstanceOf[JsonRPCError].error
    assert(error.message.contains("Please enter the wallet passphrase with walletpassphrase first"))

    sender.send(bitcoincli, BitcoinReq("walletpassphrase", walletPassword, 3600)) // wallet stay unlocked for 3600s
    sender.expectMsgType[JValue]
  }

  test("get receive addresses") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    // wallet is configured with address_type=bech32
    bitcoinClient.getReceiveAddress(None).pipeTo(sender.ref)
    val address = sender.expectMsgType[String]
    assert(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, address).map(Script.isPay2wpkh).contains(true))

    bitcoinClient.getReceiveAddress(Some(AddressType.P2wpkh)).pipeTo(sender.ref)
    val address1 = sender.expectMsgType[String]
    assert(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, address1).map(Script.isPay2wpkh).contains(true))

    bitcoinClient.getReceiveAddress(Some(AddressType.P2tr)).pipeTo(sender.ref)
    val address2 = sender.expectMsgType[String]
    assert(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, address2).map(Script.isPay2tr).contains(true))
  }

  test("get change addresses") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    // wallet is configured with address_type=bech32
    bitcoinClient.getChangeAddress(None).pipeTo(sender.ref)
    val address = sender.expectMsgType[String]
    assert(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, address).map(Script.isPay2wpkh).contains(true))

    bitcoinClient.getChangeAddress(Some(AddressType.P2wpkh)).pipeTo(sender.ref)
    val address1 = sender.expectMsgType[String]
    assert(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, address1).map(Script.isPay2wpkh).contains(true))

    bitcoinClient.getChangeAddress(Some(AddressType.P2tr)).pipeTo(sender.ref)
    val address2 = sender.expectMsgType[String]
    assert(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, address2).map(Script.isPay2tr).contains(true))
  }

  test("fund transactions") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    val txToRemote = {
      val txNotFunded = Transaction(2, Nil, TxOut(150000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
      bitcoinClient.fundTransaction(txNotFunded, TestConstants.feeratePerKw).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      assert(fundTxResponse.changePosition.nonEmpty)
      assert(fundTxResponse.fee > 0.sat)
      val amountIn = fundTxResponse.tx.txIn.map(txIn => {
        bitcoinClient.getTransaction(txIn.outPoint.txid).pipeTo(sender.ref)
        sender.expectMsgType[Transaction].txOut(txIn.outPoint.index.toInt).amount
      }).sum
      assert(amountIn == fundTxResponse.amountIn)
      fundTxResponse.tx.txIn.foreach(txIn => assert(txIn.signatureScript.isEmpty && txIn.witness.isNull))
      fundTxResponse.tx.txIn.foreach(txIn => assert(txIn.sequence == bitcoin.TxIn.SEQUENCE_FINAL - 2))

      import fr.acinq.bitcoin.scalacompat.KotlinUtils._
      bitcoinClient.signPsbt(new Psbt(fundTxResponse.tx), fundTxResponse.tx.txIn.indices, Nil).pipeTo(sender.ref)
      val ProcessPsbtResponse(signedPsbt, _) = sender.expectMsgType[ProcessPsbtResponse]
      val finalTx: Transaction = signedPsbt.extract().getRight
      assert(finalTx.txOut.size == 2)

      bitcoinClient.publishTransaction(finalTx).pipeTo(sender.ref)
      sender.expectMsg(finalTx.txid)
      generateBlocks(1)
      finalTx
    }
    {
      // txs with no outputs are not supported.
      val emptyTx = Transaction(2, Nil, Nil, 0)
      bitcoinClient.fundTransaction(emptyTx, TestConstants.feeratePerKw).pipeTo(sender.ref)
      sender.expectMsgType[Failure]
    }
    {
      // bitcoind requires that "all existing inputs must have their previous output transaction be in the wallet".
      val txNonWalletInputs = Transaction(2, Seq(TxIn(OutPoint(txToRemote, 0), Nil, 0), TxIn(OutPoint(txToRemote, 1), Nil, 0)), Seq(TxOut(100000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
      bitcoinClient.fundTransaction(txNonWalletInputs, TestConstants.feeratePerKw).pipeTo(sender.ref)
      sender.expectMsgType[Failure]
    }
    {
      // mining fee must be below budget
      val txNotFunded = Transaction(2, Nil, TxOut(150000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
      bitcoinClient.fundTransaction(txNotFunded, TestConstants.feeratePerKw, feeBudget_opt = Some(100.sat)).pipeTo(sender.ref)
      sender.expectMsgType[Failure]
    }
    {
      // we can increase the feerate.
      bitcoinClient.fundTransaction(Transaction(2, Nil, TxOut(250000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0), TestConstants.feeratePerKw).pipeTo(sender.ref)
      val fundTxResponse1 = sender.expectMsgType[FundTransactionResponse]
      bitcoinClient.rollback(fundTxResponse1.tx).pipeTo(sender.ref)
      sender.expectMsg(true)
      bitcoinClient.fundTransaction(fundTxResponse1.tx, TestConstants.feeratePerKw * 2).pipeTo(sender.ref)
      val fundTxResponse2 = sender.expectMsgType[FundTransactionResponse]
      assert(fundTxResponse1.tx != fundTxResponse2.tx)
      assert(fundTxResponse1.fee < fundTxResponse2.fee)
      bitcoinClient.rollback(fundTxResponse2.tx).pipeTo(sender.ref)
      sender.expectMsg(true)
    }
    {
      // we can control where the change output is inserted and opt-out of RBF.
      val txManyOutputs = Transaction(2, Nil, TxOut(410000 sat, Script.pay2wpkh(randomKey().publicKey)) :: TxOut(230000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
      bitcoinClient.fundTransaction(txManyOutputs, TestConstants.feeratePerKw, replaceable = false, changePosition = Some(1)).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      assert(fundTxResponse.tx.txOut.size == 3)
      assert(fundTxResponse.changePosition.contains(1))
      assert(!Set(230000 sat, 410000 sat).contains(fundTxResponse.tx.txOut(1).amount))
      assert(Set(230000 sat, 410000 sat) == Set(fundTxResponse.tx.txOut.head.amount, fundTxResponse.tx.txOut.last.amount))
      fundTxResponse.tx.txIn.foreach(txIn => assert(txIn.sequence == bitcoin.TxIn.SEQUENCE_FINAL - 1))
      bitcoinClient.rollback(fundTxResponse.tx).pipeTo(sender.ref)
      sender.expectMsg(true)
    }
    {
      // we check that bitcoin core is not malicious and trying to steal funds.
      val txNotFunded = Transaction(2, Nil, TxOut(150000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)

      def makeEvilBitcoinClient(changePosMod: Int => Int, txMod: Transaction => Transaction): BitcoinCoreClient = {
        val badRpcClient = new BitcoinJsonRPCClient {
          override def chainHash: BlockHash = bitcoinClient.rpcClient.chainHash

          override def wallet: Option[String] = if (useEclairSigner) Some("eclair") else None

          override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] = method match {
            case "fundrawtransaction" => bitcoinClient.rpcClient.invoke(method, params: _*)(ec).map(json => json.mapField {
              case ("changepos", JInt(pos)) => ("changepos", JInt(changePosMod(pos.toInt)))
              case ("hex", JString(hex)) => ("hex", JString(txMod(Transaction.read(hex)).toString()))
              case x => x
            })(ec)
            case _ => bitcoinClient.rpcClient.invoke(method, params: _*)(ec)
          }
        }
        new BitcoinCoreClient(badRpcClient, onChainKeyManager_opt = if (useEclairSigner) Some(onChainKeyManager) else None)
      }

      {
        // bitcoin core doesn't specify change position.
        val evilBitcoinClient = makeEvilBitcoinClient(_ => -1, tx => tx)
        evilBitcoinClient.fundTransaction(txNotFunded, TestConstants.feeratePerKw).pipeTo(sender.ref)
        sender.expectMsgType[Failure]
      }
      {
        // bitcoin core tries to send twice the amount we wanted by duplicating the output.
        val evilBitcoinClient = makeEvilBitcoinClient(pos => pos, tx => tx.copy(txOut = tx.txOut ++ txNotFunded.txOut))
        evilBitcoinClient.fundTransaction(txNotFunded, TestConstants.feeratePerKw).pipeTo(sender.ref)
        sender.expectMsgType[Failure]
      }
      {
        // bitcoin core ignores our specified change position.
        val evilBitcoinClient = makeEvilBitcoinClient(_ => 1, tx => tx.copy(txOut = tx.txOut.reverse))
        evilBitcoinClient.fundTransaction(txNotFunded, TestConstants.feeratePerKw, changePosition = Some(0)).pipeTo(sender.ref)
        sender.expectMsgType[Failure]
      }
    }
  }

  test("fund transactions with confirmed inputs") {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    val sender = TestProbe()
    val miner = makeBitcoinCoreClient()
    val wallet = new BitcoinCoreClient(createWallet("funding_confirmed_inputs", sender))
    wallet.getReceiveAddress().pipeTo(sender.ref)
    val address = sender.expectMsgType[String]
    val pubkeyScript = Script.write(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, address).toOption.get)

    // We first receive some confirmed funds.
    miner.sendToPubkeyScript(pubkeyScript, 150_000 sat, FeeratePerByte(5 sat).perKw).pipeTo(sender.ref)
    val externalTxId = sender.expectMsgType[TxId]
    generateBlocks(1)

    // Our utxo has 1 confirmation: we can spend it if we allow this confirmation count.
    val tx1 = {
      val txNotFunded = Transaction(2, Nil, Seq(TxOut(125_000 sat, pubkeyScript)), 0)
      wallet.fundTransaction(txNotFunded, FeeratePerKw(1_000 sat), minInputConfirmations_opt = Some(2)).pipeTo(sender.ref)
      assert(sender.expectMsgType[Failure].cause.getMessage.contains("Insufficient funds"))
      wallet.fundTransaction(txNotFunded, FeeratePerKw(1_000 sat), minInputConfirmations_opt = Some(1)).pipeTo(sender.ref)
      val unsignedTx = sender.expectMsgType[FundTransactionResponse].tx
      wallet.signPsbt(new Psbt(unsignedTx), unsignedTx.txIn.indices, Nil).pipeTo(sender.ref)
      val signedTx = sender.expectMsgType[ProcessPsbtResponse].finalTx_opt.toOption.get
      wallet.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      signedTx
    }
    assert(tx1.txIn.map(_.outPoint.txid).toSet == Set(externalTxId))

    // We now have an unconfirmed utxo, which we can spend if we allow spending unconfirmed transactions.
    val tx2 = {
      val txNotFunded = Transaction(2, Nil, Seq(TxOut(100_000 sat, pubkeyScript)), 0)
      wallet.fundTransaction(txNotFunded, FeeratePerKw(1_000 sat), minInputConfirmations_opt = Some(1)).pipeTo(sender.ref)
      assert(sender.expectMsgType[Failure].cause.getMessage.contains("Insufficient funds"))
      wallet.fundTransaction(txNotFunded, FeeratePerKw(1_000 sat), minInputConfirmations_opt = None).pipeTo(sender.ref)
      val unsignedTx = sender.expectMsgType[FundTransactionResponse].tx
      wallet.signPsbt(new Psbt(unsignedTx), unsignedTx.txIn.indices, Nil).pipeTo(sender.ref)
      val signedTx = sender.expectMsgType[ProcessPsbtResponse].finalTx_opt.toOption.get
      wallet.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      signedTx
    }
    assert(tx2.txIn.map(_.outPoint.txid).toSet == Set(tx1.txid))
  }

  test("fund transactions with external inputs") {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    val sender = TestProbe()
    val defaultWallet = makeBitcoinCoreClient()
    val walletExternalFunds = new BitcoinCoreClient(createWallet("external_inputs", sender))
    // We receive some funds on an address that belongs to our wallet.
    Seq(25 millibtc, 15 millibtc, 20 millibtc).foreach(amount => {
      walletExternalFunds.getReceiveAddress().pipeTo(sender.ref)
      val walletAddress = sender.expectMsgType[String]
      defaultWallet.sendToPubkeyScript(Script.write(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, walletAddress).toOption.get), amount, FeeratePerByte(3.sat).perKw).pipeTo(sender.ref)
      sender.expectMsgType[TxId]
    })

    // We receive more funds on an address that does not belong to our wallet.
    val externalInputWeight = 310
    val (alicePriv, bobPriv, carolPriv) = (randomKey(), randomKey(), randomKey())
    val (outpoint1, inputScript1, txOut1) = {
      val script = Script.createMultiSigMofN(1, Seq(alicePriv.publicKey, bobPriv.publicKey))
      val txNotFunded = Transaction(2, Nil, Seq(TxOut(250_000 sat, Script.pay2wsh(script))), 0)
      defaultWallet.fundTransaction(txNotFunded, FeeratePerKw(2500 sat), changePosition = Some(1)).pipeTo(sender.ref)
      val fundedTx = sender.expectMsgType[FundTransactionResponse].tx
      defaultWallet.signPsbt(new Psbt(fundedTx), fundedTx.txIn.indices, Nil).pipeTo(sender.ref)
      val signedTx = sender.expectMsgType[ProcessPsbtResponse].finalTx_opt.toOption.get
      defaultWallet.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      (OutPoint(signedTx, 0), script, signedTx.txOut(0))
    }

    // We make sure these utxos are confirmed.
    generateBlocks(1)

    // We're able to spend those funds and ask bitcoind to fund the corresponding transaction.
    val (tx2, inputScript2) = {
      val targetFeerate = FeeratePerKw(5000 sat)
      val outputScript = Script.createMultiSigMofN(1, Seq(alicePriv.publicKey, carolPriv.publicKey))
      val txNotFunded = Transaction(2, Seq(TxIn(outpoint1, Nil, 0)), Seq(TxOut(300_000 sat, Script.pay2wsh(outputScript))), 0)
      val smallExternalInputWeight = 200
      assert(smallExternalInputWeight < externalInputWeight)
      walletExternalFunds.fundTransaction(txNotFunded, targetFeerate, externalInputsWeight = Map(outpoint1 -> smallExternalInputWeight), changePosition = Some(1)).pipeTo(sender.ref)
      val fundedTx1 = sender.expectMsgType[FundTransactionResponse]
      assert(fundedTx1.tx.txIn.length >= 2)
      val amountIn1 = fundedTx1.tx.txIn.map(txIn => {
        defaultWallet.getTransaction(txIn.outPoint.txid).pipeTo(sender.ref)
        sender.expectMsgType[Transaction].txOut(txIn.outPoint.index.toInt).amount
      }).sum
      assert(amountIn1 == fundedTx1.amountIn)
      // If we specify a bigger weight, bitcoind uses a bigger fee.
      walletExternalFunds.fundTransaction(txNotFunded, targetFeerate, externalInputsWeight = Map(outpoint1 -> externalInputWeight), changePosition = Some(1)).pipeTo(sender.ref)
      val fundedTx2 = sender.expectMsgType[FundTransactionResponse]
      assert(fundedTx2.tx.txIn.length >= 2)
      assert(fundedTx1.fee < fundedTx2.fee)
      val amountIn2 = fundedTx2.tx.txIn.map(txIn => {
        defaultWallet.getTransaction(txIn.outPoint.txid).pipeTo(sender.ref)
        sender.expectMsgType[Transaction].txOut(txIn.outPoint.index.toInt).amount
      }).sum
      assert(amountIn2 == fundedTx2.amountIn)
      // We sign our external input.
      val externalSig = Transaction.signInput(fundedTx2.tx, 0, inputScript1, SigHash.SIGHASH_ALL, 250_000 sat, SigVersion.SIGVERSION_WITNESS_V0, alicePriv)
      val psbt = new Psbt(fundedTx2.tx)
        .updateWitnessInput(outpoint1, txOut1, null, null, null, java.util.Map.of(), null, null, java.util.Map.of()).getRight
        .finalizeWitnessInput(0, Script.witnessMultiSigMofN(Seq(alicePriv, bobPriv).map(_.publicKey), Seq(externalSig))).getRight
      // And let bitcoind sign the wallet input.
      walletExternalFunds.signPsbt(psbt, fundedTx2.tx.txIn.indices, Nil).pipeTo(sender.ref)
      val signedTx: Transaction = sender.expectMsgType[ProcessPsbtResponse].psbt.extract().getRight

      walletExternalFunds.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      // The weight of our external input matches our estimation and the resulting feerate is correct.
      val actualExternalInputWeight = signedTx.weight() - signedTx.copy(txIn = signedTx.txIn.tail).weight()
      assert(actualExternalInputWeight * 0.9 <= externalInputWeight && externalInputWeight <= actualExternalInputWeight * 1.1)
      walletExternalFunds.getMempoolTx(signedTx.txid).pipeTo(sender.ref)
      val mempoolTx = sender.expectMsgType[MempoolTx]
      val expectedFee = Transactions.weight2fee(targetFeerate, signedTx.weight())
      val actualFee = mempoolTx.fees
      assert(expectedFee * 0.9 <= actualFee && actualFee <= expectedFee * 1.1, s"expected fee=$expectedFee actual fee=$actualFee")
      (signedTx, outputScript)
    }

    // We're also able to spend unconfirmed external funds and ask bitcoind to fund the corresponding transaction.
    val tx3 = {
      val targetFeerate = FeeratePerKw(10_000 sat)
      val externalOutpoint = OutPoint(tx2, 0)
      val txNotFunded = Transaction(2, Seq(TxIn(externalOutpoint, Nil, 0)), Seq(TxOut(300_000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
      walletExternalFunds.fundTransaction(txNotFunded, targetFeerate, externalInputsWeight = Map(externalOutpoint -> externalInputWeight), changePosition = Some(1)).pipeTo(sender.ref)
      val fundedTx = sender.expectMsgType[FundTransactionResponse]
      assert(fundedTx.tx.txIn.length >= 2)
      // We sign our external input.
      val externalSig = Transaction.signInput(fundedTx.tx, 0, inputScript2, SigHash.SIGHASH_ALL, 300_000 sat, SigVersion.SIGVERSION_WITNESS_V0, alicePriv)
      val psbt = new Psbt(fundedTx.tx)
        .updateWitnessInput(externalOutpoint, tx2.txOut(0), null, null, null, java.util.Map.of(), null, null, java.util.Map.of()).getRight
        .finalizeWitnessInput(0, Script.witnessMultiSigMofN(Seq(alicePriv, carolPriv).map(_.publicKey), Seq(externalSig))).getRight
      // bitcoind signs the wallet input.
      walletExternalFunds.signPsbt(psbt, fundedTx.tx.txIn.indices, Nil).pipeTo(sender.ref)
      val signedTx: Transaction = sender.expectMsgType[ProcessPsbtResponse].psbt.extract().getRight

      walletExternalFunds.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      // The resulting feerate takes into account our unconfirmed parent as well.
      walletExternalFunds.getMempoolTx(signedTx.txid).pipeTo(sender.ref)
      val mempoolTx = sender.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == fundedTx.fee)
      assert(mempoolTx.fees < mempoolTx.ancestorFees)
      val actualFee = mempoolTx.ancestorFees
      val expectedFee = Transactions.weight2fee(targetFeerate, signedTx.weight() + tx2.weight())
      assert(expectedFee * 0.9 <= actualFee && actualFee <= expectedFee * 1.1, s"expected fee=$expectedFee actual fee=$actualFee")
      signedTx
    }

    // We can RBF our unconfirmed transaction by asking bitcoind to fund it again.
    {
      val targetFeerate = FeeratePerKw(15_000 sat)
      // We simply remove the change output, but keep the rest of the transaction unchanged.
      val txNotFunded = tx3.copy(txOut = tx3.txOut.take(1))
      val inputWeights = txNotFunded.txIn.map(txIn => {
        val weight = txNotFunded.weight() - txNotFunded.copy(txIn = txNotFunded.txIn.filterNot(_.outPoint == txIn.outPoint)).weight()
        txIn.outPoint -> weight.toLong
      }).toMap
      walletExternalFunds.fundTransaction(txNotFunded, targetFeerate, externalInputsWeight = inputWeights, changePosition = Some(1)).pipeTo(sender.ref)
      val fundedTx = sender.expectMsgType[FundTransactionResponse]
      assert(fundedTx.tx.txIn.length >= 2)
      assert(fundedTx.tx.txOut.length == 2)

      // We sign our external input.
      val externalSig = Transaction.signInput(fundedTx.tx, 0, inputScript2, SigHash.SIGHASH_ALL, 300_000 sat, SigVersion.SIGVERSION_WITNESS_V0, alicePriv)
      val psbt = new Psbt(fundedTx.tx)
        .updateWitnessInput(OutPoint(tx2, 0), tx2.txOut(0), null, null, null, java.util.Map.of(), null, null, java.util.Map.of()).getRight
        .finalizeWitnessInput(0, Script.witnessMultiSigMofN(Seq(alicePriv, carolPriv).map(_.publicKey), Seq(externalSig))).getRight
      // bitcoind signs the wallet input.
      walletExternalFunds.signPsbt(psbt, fundedTx.tx.txIn.indices, Nil).pipeTo(sender.ref)
      val signedTx: Transaction = sender.expectMsgType[ProcessPsbtResponse].psbt.extract().getRight

      walletExternalFunds.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      // We have replaced the previous transaction.
      walletExternalFunds.getMempoolTx(tx3.txid).pipeTo(sender.ref)
      assert(sender.expectMsgType[Failure].cause.getMessage.contains("Transaction not in mempool"))
      // The resulting feerate takes into account our unconfirmed parent as well.
      walletExternalFunds.getMempoolTx(signedTx.txid).pipeTo(sender.ref)
      val mempoolTx = sender.expectMsgType[MempoolTx]
      assert(mempoolTx.fees == fundedTx.fee)
      assert(mempoolTx.fees < mempoolTx.ancestorFees)
      val actualFee = mempoolTx.ancestorFees
      val expectedFee = Transactions.weight2fee(targetFeerate, signedTx.weight() + tx2.weight())
      assert(expectedFee * 0.9 <= actualFee && actualFee <= expectedFee * 1.1, s"expected fee=$expectedFee actual fee=$actualFee")
    }
  }

  test("generate change outputs that match the transaction being funded") {
    import KotlinUtils._
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()
    val pubKey = randomKey().publicKey

    {
      // When sending to a p2wpkh, bitcoin core should add a p2wpkh change output.
      val pubkeyScript = Script.pay2wpkh(pubKey)
      val unsignedTx = Transaction(version = 2, Nil, Seq(TxOut(150_000 sat, pubkeyScript)), lockTime = 0)
      bitcoinClient.fundTransaction(unsignedTx, feeRate = FeeratePerByte(3 sat).perKw, changePosition = Some(1)).pipeTo(sender.ref)
      val tx = sender.expectMsgType[FundTransactionResponse].tx
      // We have a change output.
      assert(tx.txOut.length == 2)
      assert(tx.txOut.count(txOut => txOut.publicKeyScript == Script.write(pubkeyScript)) == 1)
      assert(tx.txOut.count(txOut => Script.isPay2wpkh(Script.parse(txOut.publicKeyScript))) == 2)
      val psbt = new Psbt(tx)
      bitcoinClient.signPsbt(psbt, tx.txIn.indices, Seq(1)).pipeTo(sender.ref)
      val response = sender.expectMsgType[ProcessPsbtResponse]
      assert(response.complete && response.finalTx_opt.isRight)
      bitcoinClient.publishTransaction(response.finalTx_opt.toOption.get).pipeTo(sender.ref)
      sender.expectMsgType[TxId]
      generateBlocks(1)
    }
    {
      // When sending to a p2tr, bitcoin core should add a p2tr change output.
      val pubkeyScript = Script.pay2tr(pubKey.xOnly)
      val unsignedTx = Transaction(version = 2, Nil, Seq(TxOut(150_000 sat, pubkeyScript)), lockTime = 0)
      bitcoinClient.fundTransaction(unsignedTx, feeRate = FeeratePerByte(3 sat).perKw, changePosition = Some(1)).pipeTo(sender.ref)
      val tx = sender.expectMsgType[FundTransactionResponse].tx
      // We have a change output.
      assert(tx.txOut.length == 2)
      assert(tx.txOut.count(txOut => txOut.publicKeyScript == Script.write(pubkeyScript)) == 1)
      assert(tx.txOut.count(txOut => Script.isPay2tr(Script.parse(txOut.publicKeyScript))) == 2)
      val psbt = new Psbt(tx)
      bitcoinClient.signPsbt(psbt, tx.txIn.indices, Seq(1)).pipeTo(sender.ref)
      val response = sender.expectMsgType[ProcessPsbtResponse]
      assert(response.complete && response.finalTx_opt.isRight)
      bitcoinClient.publishTransaction(response.finalTx_opt.toOption.get).pipeTo(sender.ref)
      sender.expectMsgType[TxId]
      generateBlocks(1)
    }
  }

  test("absence of rounding") {
    val hexOut = "02000000013361e994f6bd5cbe9dc9e8cb3acdc12bc1510a3596469d9fc03cfddd71b223720000000000feffffff02c821354a00000000160014b6aa25d6f2a692517f2cf1ad55f243a5ba672cac404b4c0000000000220020822eb4234126c5fc84910e51a161a9b7af94eb67a2344f7031db247e0ecc2f9200000000"
    val fundedTx = Transaction.read(hexOut)
    val txIn = fundedTx.copy(txIn = Nil, txOut = fundedTx.txOut(0) :: Nil)

    (0 to 9).foreach { satoshi =>
      val apiAmount = JDecimal(BigDecimal(s"0.0000000$satoshi"))
      val rpcClient = new BasicBitcoinJsonRPCClient(Block.RegtestGenesisBlock.hash, rpcAuthMethod = UserPassword("foo", "bar"), host = "localhost", port = 0) {
        override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] = method match {
          case "getbalances" => Future(JObject("mine" -> JObject("trusted" -> apiAmount, "untrusted_pending" -> apiAmount)))(ec)
          case "getmempoolinfo" => Future(JObject("mempoolminfee" -> JDecimal(0.0002)))(ec)
          case "fundrawtransaction" => Future(JObject(List("hex" -> JString(hexOut), "changepos" -> JInt(1), "fee" -> apiAmount)))(ec)
          case _ => Future.failed(new RuntimeException(s"Test BasicBitcoinJsonRPCClient: method $method is not supported"))
        }
      }

      val sender = TestProbe()
      val bitcoinClient = new BitcoinCoreClient(rpcClient)
      bitcoinClient.onChainBalance().pipeTo(sender.ref)
      assert(sender.expectMsgType[OnChainBalance] == OnChainBalance(Satoshi(satoshi), Satoshi(satoshi)))

      bitcoinClient.fundTransaction(txIn, FeeratePerKw(250 sat)).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      assert(fundTxResponse.fee == Satoshi(satoshi))
    }
  }

  test("create/commit/rollback funding txs") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    bitcoinClient.onChainBalance().pipeTo(sender.ref)
    assert(sender.expectMsgType[OnChainBalance].confirmed > 0.sat)

    bitcoinClient.getReceiveAddress().pipeTo(sender.ref)
    val address = sender.expectMsgType[String]
    assert(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, address).isRight)

    val fundingTxs = for (_ <- 0 to 3) yield {
      val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey().publicKey, randomKey().publicKey)))
      bitcoinClient.makeFundingTx(pubkeyScript, Satoshi(500), FeeratePerKw(250 sat)).pipeTo(sender.ref)
      val fundingTx = sender.expectMsgType[MakeFundingTxResponse].fundingTx
      bitcoinClient.publishTransaction(fundingTx.copy(txIn = Nil)).pipeTo(sender.ref) // try publishing an invalid version of the tx
      sender.expectMsgType[Failure]
      bitcoinClient.rollback(fundingTx).pipeTo(sender.ref) // rollback the locked outputs
      sender.expectMsg(true)

      // now fund a tx with correct feerate
      bitcoinClient.makeFundingTx(pubkeyScript, 50 millibtc, FeeratePerKw(250 sat)).pipeTo(sender.ref)
      sender.expectMsgType[MakeFundingTxResponse].fundingTx
    }

    bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
    assert(sender.expectMsgType[Set[OutPoint]].size >= 4)

    bitcoinClient.commit(fundingTxs(0)).pipeTo(sender.ref)
    sender.expectMsg(true)

    bitcoinClient.rollback(fundingTxs(1)).pipeTo(sender.ref)
    sender.expectMsg(true)

    bitcoinClient.commit(fundingTxs(2)).pipeTo(sender.ref)
    sender.expectMsg(true)

    bitcoinClient.rollback(fundingTxs(3)).pipeTo(sender.ref)
    sender.expectMsg(true)

    bitcoinClient.getTransaction(fundingTxs(0).txid).pipeTo(sender.ref)
    sender.expectMsg(fundingTxs(0))

    bitcoinClient.getTransaction(fundingTxs(2).txid).pipeTo(sender.ref)
    sender.expectMsg(fundingTxs(2))

    // NB: from 0.17.0 on bitcoin core will clear locks when a tx is published
    bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
    sender.expectMsg(Set.empty[OutPoint])
  }

  test("ensure feerate is always above min-relay-fee") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey().publicKey, randomKey().publicKey)))
    // 200 sat/kw is below the min-relay-fee
    bitcoinClient.makeFundingTx(pubkeyScript, 5 millibtc, FeeratePerKw(200 sat)).pipeTo(sender.ref)
    val MakeFundingTxResponse(fundingTx, _, _) = sender.expectMsgType[MakeFundingTxResponse]

    bitcoinClient.commit(fundingTx).pipeTo(sender.ref)
    sender.expectMsg(true)
  }

  test("unlock utxos when transaction is published") {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()
    generateBlocks(1) // generate a block to ensure we start with an empty mempool

    // create a first transaction with multiple inputs
    val tx1 = {
      val fundedTxs = (1 to 3).map(_ => {
        val txNotFunded = Transaction(2, Nil, TxOut(15000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
        bitcoinClient.fundTransaction(txNotFunded, TestConstants.feeratePerKw).pipeTo(sender.ref)
        sender.expectMsgType[FundTransactionResponse].tx
      })
      val fundedTx = Transaction(2, fundedTxs.flatMap(_.txIn), fundedTxs.flatMap(_.txOut), 0)
      assert(fundedTx.txIn.length >= 3)

      // tx inputs should be locked
      bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
      sender.expectMsg(fundedTx.txIn.map(_.outPoint).toSet)

      bitcoinClient.signPsbt(new Psbt(fundedTx), fundedTx.txIn.indices, Nil).pipeTo(sender.ref)
      val signedTx = sender.expectMsgType[ProcessPsbtResponse].finalTx_opt.toOption.get
      bitcoinClient.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      // once the tx is published, the inputs should be automatically unlocked
      bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
      sender.expectMsg(Set.empty[OutPoint])
      signedTx
    }

    // create a second transaction that double-spends one of the inputs of the first transaction
    val tx2 = {
      val txNotFunded = tx1.copy(txIn = tx1.txIn.take(1))
      bitcoinClient.fundTransaction(txNotFunded, TestConstants.feeratePerKw * 2).pipeTo(sender.ref)
      val fundedTx = sender.expectMsgType[FundTransactionResponse].tx
      assert(fundedTx.txIn.length >= 2) // we added at least one new input

      // newly added inputs should be locked
      bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
      sender.expectMsg(fundedTx.txIn.map(_.outPoint).toSet)

      bitcoinClient.signPsbt(new Psbt(fundedTx), fundedTx.txIn.indices, Nil).pipeTo(sender.ref)
      val signedTx = sender.expectMsgType[ProcessPsbtResponse].finalTx_opt.toOption.get
      bitcoinClient.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      // once the tx is published, the inputs should be automatically unlocked
      bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
      sender.expectMsg(Set.empty[OutPoint])
      signedTx
    }

    // tx2 replaced tx1 in the mempool
    bitcoinClient.getMempool().pipeTo(sender.ref)
    val mempoolTxs = sender.expectMsgType[Seq[Transaction]]
    assert(mempoolTxs.length == 1)
    assert(mempoolTxs.head.txid == tx2.txid)
    assert(tx2.txIn.map(_.outPoint).intersect(tx1.txIn.map(_.outPoint)).length == 1)
  }

  test("unlock transaction inputs if commit fails (double-spent)") {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    val sender = TestProbe()
    val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey().publicKey, randomKey().publicKey)))
    val bitcoinClient = makeBitcoinCoreClient()

    // Create a huge tx so we make sure it has > 2 inputs without publishing it.
    bitcoinClient.makeFundingTx(pubkeyScript, 250 btc, FeeratePerKw(1000 sat)).pipeTo(sender.ref)
    val fundingTx = sender.expectMsgType[MakeFundingTxResponse].fundingTx
    assert(fundingTx.txIn.length > 2)

    // Double-spend the first 2 inputs.
    val amountIn = fundingTx.txIn.take(2).map(txIn => {
      bitcoinClient.getTransaction(txIn.outPoint.txid).pipeTo(sender.ref)
      sender.expectMsgType[Transaction].txOut(txIn.outPoint.index.toInt).amount
    }).sum
    val tx1 = fundingTx.copy(
      txIn = fundingTx.txIn.take(2),
      txOut = Seq(TxOut(amountIn - 15_000.sat, Script.pay2wpkh(randomKey().publicKey)))
    )
    bitcoinClient.signPsbt(new Psbt(tx1), tx1.txIn.indices, Nil).pipeTo(sender.ref)
    val tx2 = sender.expectMsgType[ProcessPsbtResponse].finalTx_opt.toOption.get
    bitcoinClient.commit(tx2).pipeTo(sender.ref)
    sender.expectMsg(true)

    // The inputs of the first transaction are still locked except for the first 2 that were just spent.
    val expectedLocks = fundingTx.txIn.drop(2).map(_.outPoint).toSet
    assert(expectedLocks.nonEmpty)
    awaitAssert({
      bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
      sender.expectMsg(expectedLocks)
    }, max = 10 seconds, interval = 1 second)

    // Publishing the first transaction will fail as its first 2 inputs are already spent by the second transaction.
    bitcoinClient.commit(fundingTx).pipeTo(sender.ref)
    sender.expectMsg(false)

    // And all locked inputs should now be unlocked.
    awaitAssert({
      bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
      sender.expectMsg(Set.empty[OutPoint])
    }, max = 10 seconds, interval = 1 second)
  }

  test("unlock transaction inputs when double-spent by another wallet transaction") {
    assume(!useEclairSigner)

    val sender = TestProbe()
    val priv = randomKey()
    val miner = makeBitcoinCoreClient()
    // We initialize our wallet with two inputs:
    val wallet = new BitcoinCoreClient(createWallet("mempool_double_spend", sender))
    wallet.getReceiveAddress().pipeTo(sender.ref)
    val address = sender.expectMsgType[String]
    Seq(200_000 sat, 200_000 sat).foreach { amount =>
      miner.sendToAddress(address, amount, 1).pipeTo(sender.ref)
      sender.expectMsgType[TxId]
    }
    generateBlocks(1)

    // We create the following transactions:
    //
    //                          +------------+
    //    wallet input 1 ------>|            |
    //                   +----->| anchor tx1 |
    // +-----------+     |      +------------+
    // | commit tx |-----+
    // +-----------+     |      +------------+
    //                   +----->|            |
    //    wallet input 2 ------>| anchor tx2 |
    //                          +------------+
    val commitTx = {
      val txNotFunded = Transaction(2, Nil, Seq(TxOut(100_000 sat, Script.pay2wpkh(priv.publicKey))), 0)
      miner.fundTransaction(txNotFunded, FeeratePerKw(1000 sat), replaceable = true).pipeTo(sender.ref)
      signTransaction(miner, sender.expectMsgType[FundTransactionResponse].tx).pipeTo(sender.ref)
      val signedTx = sender.expectMsgType[SignTransactionResponse].tx
      miner.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      signedTx
    }
    val commitOutpoint = OutPoint(commitTx, commitTx.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wpkh(priv.publicKey))))
    val Seq(anchorTx1, anchorTx2) = Seq(FeeratePerKw(1000 sat), FeeratePerKw(2000 sat)).map { feerate =>
      val externalInput = Map(commitOutpoint -> (Transactions.p2wpkhInputWeight + Transactions.p2wpkhOutputWeight).toLong)
      val txNotFunded = Transaction(2, Seq(TxIn(commitOutpoint, Nil, 0)), Seq(TxOut(200_000 sat, Script.pay2wpkh(priv.publicKey))), 0)
      wallet.fundTransaction(txNotFunded, feerate, externalInputsWeight = externalInput).pipeTo(sender.ref)
      signTransaction(wallet, sender.expectMsgType[FundTransactionResponse].tx).pipeTo(sender.ref)
      val partiallySignedTx = sender.expectMsgType[SignTransactionResponse].tx
      assert(partiallySignedTx.txIn.size == 2) // a single wallet input should have been added
      val commitInputIndex = partiallySignedTx.txIn.indexWhere(_.outPoint == commitOutpoint)
      val sig = Transaction.signInput(partiallySignedTx, commitInputIndex, Script.pay2pkh(priv.publicKey), SigHash.SIGHASH_ALL, 100_000 sat, SigVersion.SIGVERSION_WITNESS_V0, priv)
      val signedTx = partiallySignedTx.updateWitness(commitInputIndex, Script.witnessPay2wpkh(priv.publicKey, sig))
      wallet.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      signedTx
    }
    val Some(walletInput1) = anchorTx1.txIn.collectFirst { case i if i.outPoint != commitOutpoint => i.outPoint }
    val Some(walletInput2) = anchorTx2.txIn.collectFirst { case i if i.outPoint != commitOutpoint => i.outPoint }
    assert(walletInput1 != walletInput2)

    // The second anchor transaction replaced the first one.
    wallet.getMempoolTx(anchorTx1.txid).pipeTo(sender.ref)
    assert(sender.expectMsgType[Failure].cause.getMessage.contains("Transaction not in mempool"))
    wallet.getMempoolTx(anchorTx2.txid).pipeTo(sender.ref)
    sender.expectMsgType[MempoolTx]

    // Bitcoin Core automatically detects that the wallet input of the first anchor transaction is available again.
    wallet.listUnspent().pipeTo(sender.ref)
    val walletUtxos = sender.expectMsgType[Seq[Utxo]]
    assert(walletUtxos.exists(_.txid == walletInput1.txid))
    val txNotFunded = Transaction(2, Nil, Seq(TxOut(150_000 sat, Script.pay2wpkh(priv.publicKey))), 0)
    wallet.fundTransaction(txNotFunded, FeeratePerKw(1000 sat), replaceable = true).pipeTo(sender.ref)
    sender.expectMsgType[FundTransactionResponse]
  }

  test("unlock transaction inputs when double-spent by an external transaction") {
    assume(!useEclairSigner)

    val sender = TestProbe()
    val priv = randomKey()
    val miner = makeBitcoinCoreClient()
    // We initialize two separate wallets:
    val wallet1 = new BitcoinCoreClient(createWallet("external_double_spend_1", sender))
    val wallet2 = new BitcoinCoreClient(createWallet("external_double_spend_2", sender))
    Seq(wallet1, wallet2).foreach { wallet =>
      wallet.getReceiveAddress().pipeTo(sender.ref)
      val address = sender.expectMsgType[String]
      miner.sendToAddress(address, 200_000 sat, 1).pipeTo(sender.ref)
      sender.expectMsgType[TxId]
    }
    generateBlocks(1)

    // We create the following transactions:
    //
    //                          +--------------+
    //          wallet 1 ------>|              |
    //                   +----->| htlc success |
    // +-----------+     |      +--------------+
    // | commit tx |-----+
    // +-----------+     |      +--------------+
    //                   +----->|              |
    //          wallet 2 ------>| htlc timeout |
    //                          +--------------+
    val commitTx = {
      val txNotFunded = Transaction(2, Nil, Seq(TxOut(100_000 sat, Script.pay2wpkh(priv.publicKey))), 0)
      miner.fundTransaction(txNotFunded, FeeratePerKw(1000 sat), replaceable = true).pipeTo(sender.ref)
      signTransaction(miner, sender.expectMsgType[FundTransactionResponse].tx).pipeTo(sender.ref)
      val signedTx = sender.expectMsgType[SignTransactionResponse].tx
      miner.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      signedTx
    }
    val commitOutpoint = OutPoint(commitTx, commitTx.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wpkh(priv.publicKey))))
    val Seq(htlcSuccessTx, htlcTimeoutTx) = Seq((wallet1, FeeratePerKw(1000 sat)), (wallet2, FeeratePerKw(2000 sat))).map { case (wallet, feerate) =>
      val externalInput = Map(commitOutpoint -> (Transactions.p2wpkhInputWeight + Transactions.p2wpkhOutputWeight).toLong)
      val txNotFunded = Transaction(2, Seq(TxIn(commitOutpoint, Nil, 0)), Seq(TxOut(200_000 sat, Script.pay2wpkh(priv.publicKey))), 0)
      wallet.fundTransaction(txNotFunded, feerate, externalInputsWeight = externalInput).pipeTo(sender.ref)
      signTransaction(wallet, sender.expectMsgType[FundTransactionResponse].tx).pipeTo(sender.ref)
      val partiallySignedTx = sender.expectMsgType[SignTransactionResponse].tx
      assert(partiallySignedTx.txIn.size == 2) // a single wallet input should have been added
      val commitInputIndex = partiallySignedTx.txIn.indexWhere(_.outPoint == commitOutpoint)
      val sig = Transaction.signInput(partiallySignedTx, commitInputIndex, Script.pay2pkh(priv.publicKey), SigHash.SIGHASH_ALL, 100_000 sat, SigVersion.SIGVERSION_WITNESS_V0, priv)
      val signedTx = partiallySignedTx.updateWitness(commitInputIndex, Script.witnessPay2wpkh(priv.publicKey, sig))
      wallet.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      signedTx
    }
    val Some(walletInput1) = htlcSuccessTx.txIn.collectFirst { case i if i.outPoint != commitOutpoint => i.outPoint }
    val Some(walletInput2) = htlcTimeoutTx.txIn.collectFirst { case i if i.outPoint != commitOutpoint => i.outPoint }
    assert(walletInput1 != walletInput2)

    // The second htlc transaction replaced the first one.
    miner.getMempoolTx(htlcSuccessTx.txid).pipeTo(sender.ref)
    assert(sender.expectMsgType[Failure].cause.getMessage.contains("Transaction not in mempool"))
    miner.getMempoolTx(htlcTimeoutTx.txid).pipeTo(sender.ref)
    sender.expectMsgType[MempoolTx]

    // Bitcoin Core automatically detects that the wallet input of the first HTLC transaction is available again.
    wallet1.listUnspent().pipeTo(sender.ref)
    val walletUtxos = sender.expectMsgType[Seq[Utxo]]
    assert(walletUtxos.exists(_.txid == walletInput1.txid))
    val txNotFunded = Transaction(2, Nil, Seq(TxOut(150_000 sat, Script.pay2wpkh(priv.publicKey))), 0)
    wallet1.fundTransaction(txNotFunded, FeeratePerKw(1000 sat), replaceable = true).pipeTo(sender.ref)
    sender.expectMsgType[FundTransactionResponse]
  }

  test("keep transaction inputs locked if below mempool min fee") {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    val txNotFunded = Transaction(2, Nil, Seq(TxOut(200_000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
    bitcoinClient.fundTransaction(txNotFunded, FeeratePerByte(1 sat).perKw, replaceable = true).pipeTo(sender.ref)
    val txFunded1 = sender.expectMsgType[FundTransactionResponse].tx
    assert(txFunded1.txIn.nonEmpty)
    bitcoinClient.signPsbt(new Psbt(txFunded1), txFunded1.txIn.indices, Nil).pipeTo(sender.ref)
    val signedTx1 = sender.expectMsgType[ProcessPsbtResponse].finalTx_opt.toOption.get
    bitcoinClient.publishTransaction(signedTx1).pipeTo(sender.ref)
    assert(sender.expectMsgType[Failure].cause.getMessage.contains("min relay fee not met"))

    // the inputs are still locked, because the transaction should be successfully published when the mempool clears
    bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
    sender.expectMsg(txFunded1.txIn.map(_.outPoint).toSet)

    // we double-spend the inputs, which unlocks them
    bitcoinClient.fundTransaction(txFunded1, FeeratePerByte(5 sat).perKw, replaceable = true).pipeTo(sender.ref)
    val txFunded2 = sender.expectMsgType[FundTransactionResponse].tx
    assert(txFunded2.txid != txFunded1.txid)
    txFunded1.txIn.foreach(txIn => assert(txFunded2.txIn.map(_.outPoint).contains(txIn.outPoint)))
    bitcoinClient.signPsbt(new Psbt(txFunded2), txFunded2.txIn.indices, Nil).pipeTo(sender.ref)
    val signedTx2 = sender.expectMsgType[ProcessPsbtResponse].finalTx_opt.toOption.get
    bitcoinClient.publishTransaction(signedTx2).pipeTo(sender.ref)
    sender.expectMsg(signedTx2.txid)
    awaitAssert({
      bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
      sender.expectMsg(Set.empty[OutPoint])
    }, max = 10 seconds, interval = 100 millis)
  }

  test("unlock outpoints correctly") {
    assume(!useEclairSigner)

    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()
    val nonWalletScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey().publicKey, randomKey().publicKey)))
    val nonWalletUtxo = {
      bitcoinClient.sendToPubkeyScript(nonWalletScript, 150_000 sat, FeeratePerKw(1000 sat)).pipeTo(sender.ref)
      val txId = sender.expectMsgType[TxId]
      bitcoinClient.getTransaction(txId).pipeTo(sender.ref)
      val tx = sender.expectMsgType[Transaction]
      OutPoint(tx, tx.txOut.indexWhere(_.publicKeyScript == nonWalletScript))
    }

    {
      // test #1: unlock wallet outpoints that are actually locked
      // create a huge tx so we make sure it has > 1 inputs
      bitcoinClient.makeFundingTx(nonWalletScript, 250 btc, FeeratePerKw(1000 sat)).pipeTo(sender.ref)
      val MakeFundingTxResponse(fundingTx, _, _) = sender.expectMsgType[MakeFundingTxResponse]
      assert(fundingTx.txIn.size > 2)
      bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
      sender.expectMsg(fundingTx.txIn.map(_.outPoint).toSet)
      bitcoinClient.rollback(fundingTx).pipeTo(sender.ref)
      sender.expectMsg(true)
    }
    {
      // test #2: some outpoints are locked, some are unlocked
      bitcoinClient.makeFundingTx(nonWalletScript, 250 btc, FeeratePerKw(1000 sat)).pipeTo(sender.ref)
      val MakeFundingTxResponse(fundingTx, _, _) = sender.expectMsgType[MakeFundingTxResponse]
      assert(fundingTx.txIn.size > 2)
      bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
      sender.expectMsg(fundingTx.txIn.map(_.outPoint).toSet)

      // unlock the first 2 outpoints
      val tx1 = fundingTx.copy(txIn = fundingTx.txIn.take(2))
      bitcoinClient.rollback(tx1).pipeTo(sender.ref)
      sender.expectMsg(true)
      bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
      sender.expectMsg(fundingTx.txIn.drop(2).map(_.outPoint).toSet)

      // and try to unlock all outpoints: it should work too
      bitcoinClient.rollback(fundingTx).pipeTo(sender.ref)
      sender.expectMsg(true)
      bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
      sender.expectMsg(Set.empty[OutPoint])
    }
    {
      // test #3: lock and unlock non-wallet inputs
      val txNotFunded = Transaction(2, Seq(TxIn(nonWalletUtxo, Nil, 0)), Seq(TxOut(250_000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
      bitcoinClient.fundTransaction(txNotFunded, FeeratePerKw(1000 sat), replaceable = true, externalInputsWeight = Map(nonWalletUtxo -> 400L)).pipeTo(sender.ref)
      val fundedTx = sender.expectMsgType[FundTransactionResponse].tx
      assert(fundedTx.txIn.size > 1)

      // the external input is also considered locked
      bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
      sender.expectMsg(fundedTx.txIn.map(_.outPoint).toSet)

      // unlocking works for both wallet and non-wallet utxos
      bitcoinClient.unlockOutpoints(fundedTx.txIn.map(_.outPoint)).pipeTo(sender.ref)
      sender.expectMsg(true)
      bitcoinClient.listLockedOutpoints().pipeTo(sender.ref)
      sender.expectMsg(Set.empty[OutPoint])
    }
  }

  test("sign transactions") {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    val nonWalletKey = randomKey()
    bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(250000 sat, Script.pay2wpkh(nonWalletKey.publicKey))), 0), TestConstants.feeratePerKw, changePosition = Some(1)).pipeTo(sender.ref)
    val fundedTx = sender.expectMsgType[FundTransactionResponse].tx
    bitcoinClient.signPsbt(new Psbt(fundedTx), fundedTx.txIn.indices, Nil).pipeTo(sender.ref)
    val txToRemote = sender.expectMsgType[ProcessPsbtResponse].finalTx_opt.toOption.get
    bitcoinClient.publishTransaction(txToRemote).pipeTo(sender.ref)
    sender.expectMsg(txToRemote.txid)
    generateBlocks(1)

    {
      bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(400000 sat, Script.pay2wpkh(randomKey().publicKey))), 0), TestConstants.feeratePerKw, changePosition = Some(1)).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      val txWithNonWalletInput = fundTxResponse.tx.copy(txIn = TxIn(OutPoint(txToRemote, 0), ByteVector.empty, 0) +: fundTxResponse.tx.txIn)
      val walletInputTxs = txWithNonWalletInput.txIn.tail.map(txIn => {
        bitcoinClient.getTransaction(txIn.outPoint.txid).pipeTo(sender.ref)
        sender.expectMsgType[Transaction]
      })

      // we can ignore that error with allowIncomplete = true, and in that case bitcoind signs the wallet inputs.
      bitcoinClient.signPsbt(new Psbt(txWithNonWalletInput), txWithNonWalletInput.txIn.indices.tail, Nil).pipeTo(sender.ref)
      val signTxResponse1 = sender.expectMsgType[ProcessPsbtResponse]
      assert(!signTxResponse1.complete)
      signTxResponse1.partiallySignedTx.txIn.tail.foreach(walletTxIn => assert(walletTxIn.witness.stack.nonEmpty))

      // if the non-wallet inputs are signed, bitcoind signs the remaining wallet inputs.
      val nonWalletSig = Transaction.signInput(txWithNonWalletInput, 0, Script.pay2pkh(nonWalletKey.publicKey), bitcoin.SigHash.SIGHASH_ALL, txToRemote.txOut.head.amount, bitcoin.SigVersion.SIGVERSION_WITNESS_V0, nonWalletKey)
      val nonWalletWitness = ScriptWitness(Seq(nonWalletSig, nonWalletKey.publicKey.value))
      val txWithSignedNonWalletInput = txWithNonWalletInput.updateWitness(0, nonWalletWitness)
      val psbt = new Psbt(txWithSignedNonWalletInput)
      val updated: Either[UpdateFailure, Psbt] = psbt.updateWitnessInput(psbt.global.tx.txIn.get(0).outPoint, txToRemote.txOut(0), null, fr.acinq.bitcoin.Script.pay2pkh(nonWalletKey.publicKey), SigHash.SIGHASH_ALL, psbt.getInput(0).getDerivationPaths, null, null, java.util.Map.of())
      val Right(psbt1) = updated.flatMap(_.finalizeWitnessInput(0, nonWalletWitness))
      bitcoinClient.signPsbt(psbt1, txWithSignedNonWalletInput.txIn.indices.tail, Nil).pipeTo(sender.ref)
      val signTxResponse2 = sender.expectMsgType[ProcessPsbtResponse]
      assert(signTxResponse2.complete)
      Transaction.correctlySpends(signTxResponse2.finalTx_opt.toOption.get, txToRemote +: walletInputTxs, bitcoin.ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }
    {
      // bitcoind lets us double-spend ourselves.
      bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(75000 sat, Script.pay2wpkh(randomKey().publicKey))), 0), TestConstants.feeratePerKw, changePosition = Some(1)).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      bitcoinClient.signPsbt(new Psbt(fundTxResponse.tx), fundTxResponse.tx.txIn.indices, Nil).pipeTo(sender.ref)
      assert(sender.expectMsgType[ProcessPsbtResponse].complete)
      bitcoinClient.signPsbt(new Psbt(fundTxResponse.tx.copy(txOut = Seq(TxOut(85000 sat, Script.pay2wpkh(randomKey().publicKey))))), fundTxResponse.tx.txIn.indices, Nil).pipeTo(sender.ref)
      assert(sender.expectMsgType[ProcessPsbtResponse].complete)
    }
    {
      // create an unconfirmed utxo to a non-wallet address.
      bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(125000 sat, Script.pay2wpkh(nonWalletKey.publicKey))), 0), TestConstants.feeratePerKw, changePosition = Some(1)).pipeTo(sender.ref)
      val fundedTx = sender.expectMsgType[FundTransactionResponse].tx
      bitcoinClient.signPsbt(new Psbt(fundedTx), fundedTx.txIn.indices, Nil).pipeTo(sender.ref)
      val unconfirmedTx = sender.expectMsgType[ProcessPsbtResponse].finalTx_opt.toOption.get
      bitcoinClient.publishTransaction(unconfirmedTx).pipeTo(sender.ref)
      sender.expectMsg(unconfirmedTx.txid)
      // bitcoind lets us use this unconfirmed non-wallet input.
      bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(350000 sat, Script.pay2wpkh(randomKey().publicKey))), 0), TestConstants.feeratePerKw, changePosition = Some(1)).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      val txWithUnconfirmedInput = fundTxResponse.tx.copy(txIn = TxIn(OutPoint(unconfirmedTx, 0), ByteVector.empty, 0) +: fundTxResponse.tx.txIn)
      val nonWalletSig = Transaction.signInput(txWithUnconfirmedInput, 0, Script.pay2pkh(nonWalletKey.publicKey), bitcoin.SigHash.SIGHASH_ALL, unconfirmedTx.txOut.head.amount, bitcoin.SigVersion.SIGVERSION_WITNESS_V0, nonWalletKey)
      val nonWalletWitness = ScriptWitness(Seq(nonWalletSig, nonWalletKey.publicKey.value))
      val txWithSignedUnconfirmedInput = txWithUnconfirmedInput.updateWitness(0, nonWalletWitness)
      val psbt = new Psbt(txWithSignedUnconfirmedInput)
      val Right(psbt1) = psbt.updateWitnessInput(psbt.global.tx.txIn.get(0).outPoint, unconfirmedTx.txOut(0), null, fr.acinq.bitcoin.Script.pay2pkh(nonWalletKey.publicKey), SigHash.SIGHASH_ALL, psbt.getInput(0).getDerivationPaths, null, null, java.util.Map.of())
        .flatMap(_.finalizeWitnessInput(0, nonWalletWitness))
      bitcoinClient.signPsbt(psbt1, txWithSignedUnconfirmedInput.txIn.indices.tail, Nil).pipeTo(sender.ref)
      assert(sender.expectMsgType[ProcessPsbtResponse].complete)
    }
  }

  test("publish transaction idempotent") {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    val priv = randomKey()
    val noInputTx = Transaction(2, Nil, TxOut(6.btc.toSatoshi, Script.pay2wpkh(priv.publicKey)) :: Nil, 0)
    bitcoinClient.fundTransaction(noInputTx, TestConstants.feeratePerKw).pipeTo(sender.ref)
    val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
    val changePos = fundTxResponse.changePosition.get
    bitcoinClient.signPsbt(new Psbt(fundTxResponse.tx), fundTxResponse.tx.txIn.indices, Nil).pipeTo(sender.ref)
    val tx = sender.expectMsgType[ProcessPsbtResponse].finalTx_opt.toOption.get

    // we publish the tx a first time
    bitcoinClient.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(tx.txid)
    // we publish the tx a second time to test idempotence
    bitcoinClient.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(tx.txid)
    // let's confirm the tx
    generateBlocks(1)
    // and publish the tx a third time to test idempotence
    bitcoinClient.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(tx.txid)

    // now let's spend the output of the tx
    val spendingTx = {
      val address = getNewAddress(sender)
      val pos = if (changePos == 0) 1 else 0
      bitcoinrpcclient.invoke("createrawtransaction", Array(Map("txid" -> tx.txid.value.toHex, "vout" -> pos)), Map(address -> 5.999)).pipeTo(sender.ref)
      val JString(unsignedTxStr) = sender.expectMsgType[JValue]
      val unsignedTx = Transaction.read(unsignedTxStr)
      val sig = Transaction.signInput(unsignedTx, 0, Script.pay2pkh(priv.publicKey), bitcoin.SigHash.SIGHASH_ALL, 6.btc.toSatoshi, bitcoin.SigVersion.SIGVERSION_WITNESS_V0, priv)
      unsignedTx.updateWitness(0, Script.witnessPay2wpkh(priv.publicKey, sig))
    }
    bitcoinClient.publishTransaction(spendingTx).pipeTo(sender.ref)
    sender.expectMsg(spendingTx.txid)

    // and publish the tx a fourth time to test idempotence
    bitcoinClient.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(tx.txid)
    // let's confirm the tx
    generateBlocks(1)
    // and publish the tx a fifth time to test idempotence
    bitcoinClient.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(tx.txid)
  }

  test("publish invalid transactions") {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    // that tx has inputs that don't exist
    val txWithUnknownInputs = Transaction.read("02000000000101b9e2a3f518fd74e696d258fed3c78c43f84504e76c99212e01cf225083619acf00000000000d0199800136b34b00000000001600145464ce1e5967773922506e285780339d72423244040047304402206795df1fd93c285d9028c384aacf28b43679f1c3f40215fd7bd1abbfb816ee5a022047a25b8c128e692d4717b6dd7b805aa24ecbbd20cfd664ab37a5096577d4a15d014730440220770f44121ed0e71ec4b482dded976f2febd7500dfd084108e07f3ce1e85ec7f5022025b32dc0d551c47136ce41bfb80f5a10de95c0babb22a3ae2d38e6688b32fcb20147522102c2662ab3e4fa18a141d3be3317c6ee134aff10e6cd0a91282a25bf75c0481ebc2102e952dd98d79aa796289fa438e4fdeb06ed8589ff2a0f032b0cfcb4d7b564bc3252aea58d1120")
    bitcoinClient.publishTransaction(txWithUnknownInputs).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    // invalid txs shouldn't be found in either the mempool or the blockchain
    bitcoinClient.getTxConfirmations(txWithUnknownInputs.txid).pipeTo(sender.ref)
    sender.expectMsg(None)

    bitcoinClient.fundTransaction(Transaction(2, Nil, TxOut(100000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0), TestConstants.feeratePerKw).pipeTo(sender.ref)
    val txUnsignedInputs = sender.expectMsgType[FundTransactionResponse].tx
    bitcoinClient.publishTransaction(txUnsignedInputs).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    bitcoinClient.signPsbt(new Psbt(txUnsignedInputs), txUnsignedInputs.txIn.indices, Nil).pipeTo(sender.ref)
    val signTxResponse = sender.expectMsgType[ProcessPsbtResponse]
    assert(signTxResponse.complete)

    val txWithNoOutputs = signTxResponse.finalTx_opt.toOption.get.copy(txOut = Nil)
    bitcoinClient.publishTransaction(txWithNoOutputs).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    bitcoinClient.getBlockHeight().pipeTo(sender.ref)
    val blockHeight = sender.expectMsgType[BlockHeight]
    val txWithFutureCltv = signTxResponse.finalTx_opt.toOption.get.copy(lockTime = blockHeight.toLong + 1)
    bitcoinClient.publishTransaction(txWithFutureCltv).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    bitcoinClient.publishTransaction(signTxResponse.finalTx_opt.toOption.get).pipeTo(sender.ref)
    sender.expectMsg(signTxResponse.finalTx_opt.toOption.get.txid)
    generateBlocks(1)
  }

  test("publish transaction package") {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    // The mempool contains a first parent transaction.
    val priv1 = randomKey()
    val parentTx1 = {
      bitcoinClient.fundTransaction(Transaction(2, Nil, TxOut(300_000 sat, Script.pay2wpkh(priv1.publicKey)) :: Nil, 0), FeeratePerKw(1000 sat), changePosition = Some(1)).pipeTo(sender.ref)
      val unsignedTx = sender.expectMsgType[FundTransactionResponse].tx
      bitcoinClient.signPsbt(new Psbt(unsignedTx), unsignedTx.txIn.indices, Nil).pipeTo(sender.ref)
      val signedTx = sender.expectMsgType[ProcessPsbtResponse].finalTx_opt.toOption.get
      bitcoinClient.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      signedTx
    }

    // We create a conflicting transaction that pays the same feerate.
    val priv2 = randomKey()
    val parentTx2 = {
      val unsignedTx = parentTx1.copy(txOut = TxOut(300_000 sat, Script.pay2wpkh(priv2.publicKey)) +: parentTx1.txOut.tail)
      bitcoinClient.signPsbt(new Psbt(unsignedTx), unsignedTx.txIn.indices, Nil).pipeTo(sender.ref)
      sender.expectMsgType[ProcessPsbtResponse].finalTx_opt.toOption.get
    }

    // We cannot publish this transaction on its own, but we can publish a package using CPFP.
    bitcoinClient.publishTransaction(parentTx2).pipeTo(sender.ref)
    sender.expectMsgType[Failure]
    val childTx2a = {
      val unsignedTx = Transaction(2, Seq(TxIn(OutPoint(parentTx2, 0), Nil, 0)), Seq(TxOut(280_000 sat, Script.pay2wpkh(priv2.publicKey))), 0)
      val sig = Transaction.signInput(unsignedTx, 0, Script.pay2pkh(priv2.publicKey), SigHash.SIGHASH_ALL, 300_000 sat, SigVersion.SIGVERSION_WITNESS_V0, priv2)
      unsignedTx.updateWitness(0, Script.witnessPay2wpkh(priv2.publicKey, sig))
    }
    bitcoinClient.publishTransaction(childTx2a).pipeTo(sender.ref)
    sender.expectMsgType[Failure]
    bitcoinClient.publishPackage(parentTx2, childTx2a).pipeTo(sender.ref)
    sender.expectMsg(childTx2a.txid)
    // The initial parent tx has been replaced.
    bitcoinClient.getMempool().map(txs => txs.map(_.txid).toSet).pipeTo(sender.ref)
    sender.expectMsg(Set(parentTx2.txid, childTx2a.txid))

    // We can replace the child transaction to increase the package feerate (sibling eviction).
    val childTx2b = {
      val unsignedTx = Transaction(2, Seq(TxIn(OutPoint(parentTx2, 0), Nil, 0)), Seq(TxOut(275_000 sat, Script.pay2wpkh(priv2.publicKey))), 0)
      val sig = Transaction.signInput(unsignedTx, 0, Script.pay2pkh(priv2.publicKey), SigHash.SIGHASH_ALL, 300_000 sat, SigVersion.SIGVERSION_WITNESS_V0, priv2)
      unsignedTx.updateWitness(0, Script.witnessPay2wpkh(priv2.publicKey, sig))
    }
    bitcoinClient.publishPackage(parentTx2, childTx2b).pipeTo(sender.ref)
    sender.expectMsg(childTx2b.txid)
    bitcoinClient.getMempool().map(txs => txs.map(_.txid).toSet).pipeTo(sender.ref)
    sender.expectMsg(Set(parentTx2.txid, childTx2b.txid))

    // We cannot replace the child transaction with the previous one that pays less fees.
    bitcoinClient.publishPackage(parentTx2, childTx2a).pipeTo(sender.ref)
    assert(sender.expectMsgType[Failure].cause.getMessage.contains("insufficient fee"))
    bitcoinClient.getMempool().map(txs => txs.map(_.txid).toSet).pipeTo(sender.ref)
    sender.expectMsg(Set(parentTx2.txid, childTx2b.txid))

    // We can replace the whole package by a different package paying more fees.
    val childTx1 = {
      val unsignedTx = Transaction(2, Seq(TxIn(OutPoint(parentTx1, 0), Nil, 0)), Seq(TxOut(270_000 sat, Script.pay2wpkh(priv1.publicKey))), 0)
      val sig = Transaction.signInput(unsignedTx, 0, Script.pay2pkh(priv1.publicKey), SigHash.SIGHASH_ALL, 300_000 sat, SigVersion.SIGVERSION_WITNESS_V0, priv1)
      unsignedTx.updateWitness(0, Script.witnessPay2wpkh(priv1.publicKey, sig))
    }
    bitcoinClient.publishPackage(parentTx1, childTx1).pipeTo(sender.ref)
    sender.expectMsg(childTx1.txid)
    bitcoinClient.getMempool().map(txs => txs.map(_.txid).toSet).pipeTo(sender.ref)
    sender.expectMsg(Set(parentTx1.txid, childTx1.txid))
    generateBlocks(1)
  }

  test("send and list transactions") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    bitcoinClient.onChainBalance().pipeTo(sender.ref)
    val initialBalance = sender.expectMsgType[OnChainBalance]
    assert(initialBalance.unconfirmed == 0.sat)
    assert(initialBalance.confirmed > 50.btc.toSatoshi)

    val address = "n2YKngjUp139nkjKvZGnfLRN6HzzYxJsje"
    val amount = 150.millibtc.toSatoshi
    val txid = sendToAddress(address, amount).txid

    bitcoinClient.listTransactions(25, 0).pipeTo(sender.ref)
    val Some(tx1) = sender.expectMsgType[List[WalletTx]].collectFirst { case tx if tx.txid == txid => tx }
    assert(tx1.address == address)
    assert(tx1.amount == -amount)
    assert(tx1.fees < 0.sat)
    assert(tx1.confirmations == 0)

    bitcoinClient.onChainBalance().pipeTo(sender.ref)
    // NB: we use + because these amounts are already negative
    sender.expectMsg(initialBalance.copy(confirmed = initialBalance.confirmed + tx1.amount + tx1.fees))

    generateBlocks(1)
    bitcoinClient.listTransactions(25, 0).pipeTo(sender.ref)
    val Some(tx2) = sender.expectMsgType[List[WalletTx]].collectFirst { case tx if tx.txid == txid => tx }
    assert(tx2.address == address)
    assert(tx2.amount == -amount)
    assert(tx2.fees < 0.sat)
    assert(tx2.confirmations == 1)
  }

  test("compute detailed on-chain balance") {
    assume(!useEclairSigner)

    val sender = TestProbe()
    val miner = makeBitcoinCoreClient()
    val wallet = new BitcoinCoreClient(createWallet("detailed_on_chain_balance", sender))
    wallet.getReceiveAddress().pipeTo(sender.ref)
    val address = sender.expectMsgType[String]

    // We receive an unconfirmed transaction.
    miner.sendToAddress(address, 200_000 sat, 1).pipeTo(sender.ref)
    val txId1 = sender.expectMsgType[TxId]
    awaitAssert({
      CheckBalance.computeOnChainBalance(wallet, minDepth = 2).pipeTo(sender.ref)
      val balance = sender.expectMsgType[CheckBalance.DetailedOnChainBalance]
      assert(balance.deeplyConfirmed.isEmpty && balance.recentlyConfirmed.isEmpty)
      assert(balance.unconfirmed.keySet.map(_.txid) == Set(txId1))
      assert(balance.totalUnconfirmed.toSatoshi == 200_000.sat)
      assert(!balance.recentlySpentInputs.map(_.txid).contains(txId1))
    })

    // Our received transaction confirms.
    generateBlocks(1)
    awaitAssert({
      CheckBalance.computeOnChainBalance(wallet, minDepth = 2).pipeTo(sender.ref)
      val balance = sender.expectMsgType[CheckBalance.DetailedOnChainBalance]
      assert(balance.deeplyConfirmed.isEmpty && balance.unconfirmed.isEmpty)
      assert(balance.recentlyConfirmed.keySet.map(_.txid) == Set(txId1))
      assert(balance.totalRecentlyConfirmed.toSatoshi == 200_000.sat)
      assert(!balance.recentlySpentInputs.map(_.txid).contains(txId1))
    })

    // We spend our received transaction before it deeply confirms.
    wallet.sendToAddress(address, 150_000 sat, 1).pipeTo(sender.ref)
    val txId2 = sender.expectMsgType[TxId]
    awaitAssert({
      CheckBalance.computeOnChainBalance(wallet, minDepth = 2).pipeTo(sender.ref)
      val balance = sender.expectMsgType[CheckBalance.DetailedOnChainBalance]
      assert(balance.deeplyConfirmed.isEmpty && balance.recentlyConfirmed.isEmpty)
      assert(balance.unconfirmed.keySet.map(_.txid) == Set(txId2))
      assert(190_000.sat < balance.totalUnconfirmed.toSatoshi && balance.totalUnconfirmed.toSatoshi < 200_000.sat)
      assert(balance.recentlySpentInputs.map(_.txid).contains(txId1))
    })

    // Our transaction deeply confirms.
    generateBlocks(2)
    awaitAssert({
      CheckBalance.computeOnChainBalance(wallet, minDepth = 2).pipeTo(sender.ref)
      val balance = sender.expectMsgType[CheckBalance.DetailedOnChainBalance]
      assert(balance.recentlyConfirmed.isEmpty && balance.unconfirmed.isEmpty)
      assert(balance.deeplyConfirmed.keySet.map(_.txid) == Set(txId2))
      assert(190_000.sat < balance.totalDeeplyConfirmed.toSatoshi && balance.totalDeeplyConfirmed.toSatoshi < 200_000.sat)
      assert(balance.recentlySpentInputs.map(_.txid).contains(txId1))
    })

    // With more confirmations, the input isn't included in our recently spent inputs, but stays in our balance.
    generateBlocks(3)
    awaitAssert({
      CheckBalance.computeOnChainBalance(wallet, minDepth = 2).pipeTo(sender.ref)
      val balance = sender.expectMsgType[CheckBalance.DetailedOnChainBalance]
      assert(balance.recentlyConfirmed.isEmpty && balance.unconfirmed.isEmpty)
      assert(balance.deeplyConfirmed.keySet.map(_.txid) == Set(txId2))
      assert(190_000.sat < balance.totalDeeplyConfirmed.toSatoshi && balance.totalDeeplyConfirmed.toSatoshi < 200_000.sat)
      assert(!balance.recentlySpentInputs.map(_.txid).contains(txId1))
    })
  }

  test("get mempool transaction") {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    val sender = TestProbe()
    val address = getNewAddress(sender)
    val bitcoinClient = makeBitcoinCoreClient()

    def spendWalletTx(tx: Transaction, fees: Satoshi): Transaction = {
      val amount = tx.txOut.map(_.amount).sum - fees
      val unsignedTx = Transaction(version = 2,
        txIn = tx.txOut.indices.map(i => TxIn(OutPoint(tx, i), Nil, 0)),
        txOut = TxOut(amount, addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, address).toOption.get) :: Nil,
        lockTime = 0)
      bitcoinClient.signPsbt(new Psbt(unsignedTx), unsignedTx.txIn.indices, Nil).pipeTo(sender.ref)
      val signedTx = sender.expectMsgType[ProcessPsbtResponse].finalTx_opt.toOption.get
      bitcoinClient.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      signedTx
    }

    val tx1 = sendToAddress(address, 0.5 btc)
    val tx2 = spendWalletTx(tx1, 5000 sat)
    val tx3 = spendWalletTx(tx2, 7500 sat)

    bitcoinClient.getMempoolTx(tx1.txid).pipeTo(sender.ref)
    val mempoolTx1 = sender.expectMsgType[MempoolTx]
    assert(mempoolTx1.ancestorCount == 0)
    assert(mempoolTx1.descendantCount == 2)
    assert(mempoolTx1.fees == mempoolTx1.ancestorFees)
    assert(mempoolTx1.descendantFees == mempoolTx1.fees + 12500.sat)

    bitcoinClient.getMempoolTx(tx2.txid).pipeTo(sender.ref)
    val mempoolTx2 = sender.expectMsgType[MempoolTx]
    assert(mempoolTx2.ancestorCount == 1)
    assert(mempoolTx2.descendantCount == 1)
    assert(mempoolTx2.fees == 5000.sat)
    assert(mempoolTx2.descendantFees == 12500.sat)
    assert(mempoolTx2.ancestorFees == mempoolTx1.fees + 5000.sat)

    bitcoinClient.getMempoolTx(tx3.txid).pipeTo(sender.ref)
    val mempoolTx3 = sender.expectMsgType[MempoolTx]
    assert(mempoolTx3.ancestorCount == 2)
    assert(mempoolTx3.descendantCount == 0)
    assert(mempoolTx3.fees == 7500.sat)
    assert(mempoolTx3.descendantFees == mempoolTx3.fees)
    assert(mempoolTx3.ancestorFees == mempoolTx1.fees + 12500.sat)
  }

  test("get blocks") {
    val sender = TestProbe()
    val address = getNewAddress(sender)
    val bitcoinClient = makeBitcoinCoreClient()

    val tx1 = sendToAddress(address, 200_000 sat)
    generateBlocks(1)
    val tx2 = sendToAddress(address, 150_000 sat)
    generateBlocks(1)

    val currentHeight = currentBlockHeight(sender)
    bitcoinClient.getBlockId(currentHeight.toInt).pipeTo(sender.ref)
    val lastBlockId = sender.expectMsgType[BlockId]
    bitcoinClient.getBlock(lastBlockId).pipeTo(sender.ref)
    val lastBlock = sender.expectMsgType[fr.acinq.bitcoin.Block]
    assert(lastBlock.tx.contains(KotlinUtils.scala2kmp(tx2)))

    val previousBlockId = BlockId(KotlinUtils.kmp2scala(lastBlock.header.hashPreviousBlock))
    bitcoinClient.getBlock(previousBlockId).pipeTo(sender.ref)
    val previousBlock = sender.expectMsgType[fr.acinq.bitcoin.Block]
    assert(previousBlock.tx.contains(KotlinUtils.scala2kmp(tx1)))
  }

  test("abandon transaction") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    // Broadcast a wallet transaction.
    bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(250000 sat, Script.pay2wpkh(randomKey().publicKey))), 0), TestConstants.feeratePerKw, changePosition = Some(1)).pipeTo(sender.ref)
    val fundedTx1 = sender.expectMsgType[FundTransactionResponse].tx
    signTransaction(bitcoinClient, fundedTx1).pipeTo(sender.ref)
    val signedTx1 = sender.expectMsgType[SignTransactionResponse].tx
    bitcoinClient.publishTransaction(signedTx1).pipeTo(sender.ref)
    sender.expectMsg(signedTx1.txid)

    // Double-spend that transaction.
    val fundedTx2 = fundedTx1.copy(txOut = TxOut(200000 sat, Script.pay2wpkh(randomKey().publicKey)) +: fundedTx1.txOut.tail)
    signTransaction(bitcoinClient, fundedTx2).pipeTo(sender.ref)
    val signedTx2 = sender.expectMsgType[SignTransactionResponse].tx
    assert(signedTx2.txid != signedTx1.txid)
    bitcoinClient.publishTransaction(signedTx2).pipeTo(sender.ref)
    sender.expectMsg(signedTx2.txid)

    // Abandon the first wallet transaction.
    bitcoinClient.abandon(signedTx1.txid).pipeTo(sender.ref)
    sender.expectMsg(true)

    // Abandoning an already-abandoned transaction is a no-op.
    bitcoinClient.abandon(signedTx1.txid).pipeTo(sender.ref)
    sender.expectMsg(true)

    // We can't abandon the second transaction (it's in the mempool).
    bitcoinClient.abandon(signedTx2.txid).pipeTo(sender.ref)
    sender.expectMsg(false)

    // We can't abandon a confirmed transaction.
    bitcoinClient.abandon(signedTx2.txIn.head.outPoint.txid).pipeTo(sender.ref)
    sender.expectMsg(false)
  }

  test("bump transaction fees with child-pays-for-parent (single tx)") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()
    val tx = sendToAddress(getNewAddress(sender), 150_000 sat)
    assert(tx.txOut.length == 2) // there must be a change output
    val changeOutput = if (tx.txOut.head.amount == 150_000.sat) 1 else 0

    bitcoinClient.getMempoolTx(tx.txid).pipeTo(sender.ref)
    val mempoolTx = sender.expectMsgType[MempoolTx]
    val currentFeerate = Transactions.fee2rate(mempoolTx.fees, tx.weight())

    bitcoinClient.getMempoolPackage(Set(tx.txid)).pipeTo(sender.ref)
    sender.expectMsg(Map(tx.txid -> mempoolTx))

    val targetFeerate = currentFeerate * 1.5
    bitcoinClient.cpfp(Set(OutPoint(tx, changeOutput)), targetFeerate).pipeTo(sender.ref)
    val cpfpTx = sender.expectMsgType[Transaction]
    bitcoinClient.getMempoolTx(cpfpTx.txid).pipeTo(sender.ref)
    val mempoolCpfpTx = sender.expectMsgType[MempoolTx]
    assert(mempoolCpfpTx.ancestorFees == mempoolCpfpTx.fees + mempoolTx.fees)
    val expectedFees = Transactions.weight2fee(targetFeerate, tx.weight() + cpfpTx.weight())
    assert(expectedFees * 0.95 <= mempoolCpfpTx.ancestorFees && mempoolCpfpTx.ancestorFees <= expectedFees * 1.05)
    assert(mempoolCpfpTx.replaceable)

    generateBlocks(1)
  }

  test("bump transaction fees with child-pays-for-parent (small package)") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    val fundingFeerate = FeeratePerKw(1000 sat)
    val remoteFundingPrivKey = randomKey()
    val walletFundingPrivKey = randomKey()
    val fundingScript = Scripts.multiSig2of2(remoteFundingPrivKey.publicKey, walletFundingPrivKey.publicKey)
    val fundingTx = {
      val txNotFunded = Transaction(2, Nil, TxOut(250_000 sat, Script.pay2wsh(fundingScript)) :: Nil, 0)
      bitcoinClient.fundTransaction(txNotFunded, fundingFeerate, changePosition = Some(1)).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      assert(fundTxResponse.changePosition.contains(1))
      signTransaction(bitcoinClient, fundTxResponse.tx).pipeTo(sender.ref)
      val signTxResponse = sender.expectMsgType[SignTransactionResponse]
      assert(signTxResponse.complete)
      bitcoinClient.publishTransaction(signTxResponse.tx).pipeTo(sender.ref)
      sender.expectMsg(signTxResponse.tx.txid)
      signTxResponse.tx
    }

    val mutualCloseTx = {
      bitcoinClient.getP2wpkhPubkey().pipeTo(sender.ref)
      val walletClosePubKey = sender.expectMsgType[PublicKey]
      val remoteClosePubKey = randomKey().publicKey
      // NB: the output amounts are chosen so that the feerate is ~750 sat/kw
      val unsignedTx = Transaction(2, TxIn(OutPoint(fundingTx, 0), Seq.empty, 0) :: Nil, TxOut(130_000 sat, Script.pay2wpkh(walletClosePubKey)) :: TxOut(119_500 sat, Script.pay2wpkh(remoteClosePubKey)) :: Nil, 0)
      val walletSig = Transaction.signInput(unsignedTx, 0, fundingScript, SigHash.SIGHASH_ALL, fundingTx.txOut.head.amount, SigVersion.SIGVERSION_WITNESS_V0, walletFundingPrivKey)
      val remoteSig = Transaction.signInput(unsignedTx, 0, fundingScript, SigHash.SIGHASH_ALL, fundingTx.txOut.head.amount, SigVersion.SIGVERSION_WITNESS_V0, remoteFundingPrivKey)
      val witness = Scripts.witness2of2(Crypto.der2compact(walletSig), Crypto.der2compact(remoteSig), walletFundingPrivKey.publicKey, remoteFundingPrivKey.publicKey)
      val signedTx = unsignedTx.updateWitness(0, witness)
      bitcoinClient.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      signedTx
    }

    bitcoinClient.getMempoolTx(fundingTx.txid).pipeTo(sender.ref)
    val mempoolFundingTx = sender.expectMsgType[MempoolTx]
    bitcoinClient.getMempoolTx(mutualCloseTx.txid).pipeTo(sender.ref)
    val mempoolMutualCloseTx = sender.expectMsgType[MempoolTx]
    bitcoinClient.getMempoolPackage(Set(fundingTx.txid, mutualCloseTx.txid)).pipeTo(sender.ref)
    sender.expectMsg(Map(fundingTx.txid -> mempoolFundingTx, mutualCloseTx.txid -> mempoolMutualCloseTx))

    val targetFeerate = FeeratePerKw(5000 sat)
    bitcoinClient.cpfp(Set(OutPoint(fundingTx, 1), OutPoint(mutualCloseTx, 0)), targetFeerate).pipeTo(sender.ref)
    val cpfpTx = sender.expectMsgType[Transaction]
    bitcoinClient.getMempoolTx(cpfpTx.txid).pipeTo(sender.ref)
    val mempoolCpfpTx = sender.expectMsgType[MempoolTx]
    val packageWeight = fundingTx.weight() + mutualCloseTx.weight() + cpfpTx.weight()
    val expectedFees = Transactions.weight2fee(targetFeerate, packageWeight)
    assert(expectedFees * 0.95 <= mempoolCpfpTx.ancestorFees && mempoolCpfpTx.ancestorFees <= expectedFees * 1.05)
    assert(mempoolCpfpTx.replaceable)

    generateBlocks(1)
  }

  test("bump transaction fees with child-pays-for-parent (complex package)") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()
    val currentFeerate = FeeratePerKw(500 sat)

    // We create two separate trees of transactions that will be bumped together:
    //           TxA1              TxB1
    //           /  \                \
    //          /    \                \
    //       TxA2*   TxA5             TxB2
    //        /        \                \
    //       /          \                \
    //    TxA3          TxA6*            TxB3*
    //     /              \                \
    //    /                \                \
    // TxA4*               TxA7             TxB4

    def createTx(dest: Seq[(PublicKey, Satoshi)], walletInput_opt: Option[OutPoint]): Transaction = {
      val txIn = walletInput_opt match {
        case Some(walletInput) => TxIn(walletInput, Seq.empty, 0) :: Nil
        case None => Nil
      }
      val txOut = dest.map { case (pubKey, amount) => TxOut(amount, Script.pay2wpkh(pubKey)) }
      val txNotFunded = Transaction(2, txIn, txOut, 0)
      bitcoinClient.fundTransaction(txNotFunded, currentFeerate, changePosition = Some(txOut.length)).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      signTransaction(bitcoinClient, fundTxResponse.tx).pipeTo(sender.ref)
      val signTxResponse = sender.expectMsgType[SignTransactionResponse]
      assert(signTxResponse.complete)
      bitcoinClient.publishTransaction(signTxResponse.tx).pipeTo(sender.ref)
      sender.expectMsg(signTxResponse.tx.txid)
      signTxResponse.tx
    }

    def getMempoolTx(txid: TxId): MempoolTx = {
      val probe = TestProbe()
      bitcoinClient.getMempoolTx(txid).pipeTo(probe.ref)
      probe.expectMsgType[MempoolTx]
    }

    def getWalletPubKey(sender: TestProbe): PublicKey = {
      bitcoinClient.getP2wpkhPubkey().pipeTo(sender.ref)
      sender.expectMsgType[PublicKey]
    }

    val pubKeyA2 = getWalletPubKey(sender)
    val pubKeyA3 = getWalletPubKey(sender)
    val pubKeyA4 = getWalletPubKey(sender)
    val pubKeyA5 = getWalletPubKey(sender)
    val pubKeyA6 = getWalletPubKey(sender)
    val pubKeyA7 = getWalletPubKey(sender)
    val pubKeyB2 = getWalletPubKey(sender)
    val pubKeyB3 = getWalletPubKey(sender)
    val pubKeyB4 = getWalletPubKey(sender)

    // NB: we use the same amount to ensure that an additional input will be added and there will be a change output.
    val txB1 = createTx(Seq((pubKeyB2, 100_000 sat)), None)
    val txB2 = createTx(Seq((pubKeyB3, 100_000 sat)), Some(OutPoint(txB1, 0)))
    val txB3 = createTx(Seq((pubKeyB4, 100_000 sat)), Some(OutPoint(txB2, 0)))
    val txB4 = createTx(Seq((randomKey().publicKey, 100_000 sat)), Some(OutPoint(txB3, 0)))
    val txA1 = createTx(Seq((pubKeyA2, 75_000 sat), (pubKeyA5, 50_000 sat)), None)
    val txA2 = createTx(Seq((pubKeyA3, 75_000 sat)), Some(OutPoint(txA1, 0)))
    val txA3 = createTx(Seq((pubKeyA4, 75_000 sat)), Some(OutPoint(txA2, 0)))
    val txA4 = createTx(Seq((randomKey().publicKey, 75_000 sat)), Some(OutPoint(txA3, 0)))
    val txA5 = createTx(Seq((pubKeyA6, 50_000 sat)), Some(OutPoint(txA1, 1)))
    val txA6 = createTx(Seq((pubKeyA7, 50_000 sat)), Some(OutPoint(txA5, 0)))
    val txA7 = createTx(Seq((randomKey().publicKey, 50_000 sat)), Some(OutPoint(txA6, 0)))

    bitcoinClient.getMempoolPackage(Set(txA3.txid)).pipeTo(sender.ref)
    sender.expectMsg(Set(txA1, txA2, txA3).map(tx => tx.txid -> getMempoolTx(tx.txid)).toMap)

    bitcoinClient.getMempoolPackage(Set(txA2.txid, txA5.txid)).pipeTo(sender.ref)
    sender.expectMsg(Set(txA1, txA2, txA5).map(tx => tx.txid -> getMempoolTx(tx.txid)).toMap)

    bitcoinClient.getMempoolPackage(Set(txA2.txid, txA4.txid, txA6.txid, txB3.txid)).pipeTo(sender.ref)
    sender.expectMsg(Set(txA1, txA2, txA3, txA4, txA5, txA6, txB1, txB2, txB3).map(tx => tx.txid -> getMempoolTx(tx.txid)).toMap)

    // We bump a subset of the mempool: TxA1 -> TxA6 and TxB1 -> TxB3
    val targetFeerate = FeeratePerKw(5000 sat)
    val bumpedOutpoints = Set(OutPoint(txA2, 1), OutPoint(txA4, 1), OutPoint(txA6, 1), OutPoint(txB3, 1))
    bitcoinClient.cpfp(bumpedOutpoints, targetFeerate).pipeTo(sender.ref)
    val cpfpTx = sender.expectMsgType[Transaction]
    assert(!cpfpTx.txIn.exists(_.outPoint.txid == txB4.txid))
    assert(!cpfpTx.txIn.exists(_.outPoint.txid == txA7.txid))

    bitcoinClient.getMempoolTx(cpfpTx.txid).pipeTo(sender.ref)
    val mempoolCpfpTx = sender.expectMsgType[MempoolTx]
    val packageWeight = txA1.weight() + txA2.weight() + txA3.weight() + txA4.weight() + txA5.weight() + txA6.weight() + txB1.weight() + txB2.weight() + txB3.weight() + cpfpTx.weight()
    val expectedFees = Transactions.weight2fee(targetFeerate, packageWeight)
    assert(expectedFees * 0.95 <= mempoolCpfpTx.ancestorFees && mempoolCpfpTx.ancestorFees <= expectedFees * 1.05)
    assert(mempoolCpfpTx.replaceable)

    generateBlocks(1)
  }

  test("cannot bump transaction fees (unknown transaction)") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()
    bitcoinClient.cpfp(Set(OutPoint(randomTxId(), 0), OutPoint(randomTxId(), 3)), FeeratePerKw(1500 sat)).pipeTo(sender.ref)
    val failure = sender.expectMsgType[Failure]
    assert(failure.cause.getMessage.contains("some transactions could not be found"))
  }

  test("cannot bump transaction fees (invalid outpoint index)") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()
    val tx = sendToAddress(getNewAddress(sender), 150_000 sat)
    assert(tx.txOut.length == 2) // there must be a change output
    bitcoinClient.getMempoolTx(tx.txid).pipeTo(sender.ref)
    val mempoolTx = sender.expectMsgType[MempoolTx]
    val currentFeerate = Transactions.fee2rate(mempoolTx.fees, tx.weight())

    val targetFeerate = currentFeerate * 1.5
    bitcoinClient.cpfp(Set(OutPoint(tx, 3)), targetFeerate).pipeTo(sender.ref)
    val failure = sender.expectMsgType[Failure]
    assert(failure.cause.getMessage.contains("some outpoints are invalid or cannot be resolved"))
  }

  test("cannot bump transaction fees (transaction already confirmed)") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()
    val tx = sendToAddress(getNewAddress(sender), 45_000 sat)
    generateBlocks(1)

    bitcoinClient.cpfp(Set(OutPoint(tx, 0)), FeeratePerKw(2500 sat)).pipeTo(sender.ref)
    val failure = sender.expectMsgType[Failure]
    assert(failure.cause.getMessage.contains("some transactions could not be found"))
  }

  test("cannot bump transaction fees (non-wallet input)") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()
    val txNotFunded = Transaction(2, Nil, TxOut(50_000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
    bitcoinClient.fundTransaction(txNotFunded, FeeratePerKw(1000 sat), changePosition = Some(1)).pipeTo(sender.ref)
    val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
    signTransaction(bitcoinClient, fundTxResponse.tx).pipeTo(sender.ref)
    val signTxResponse = sender.expectMsgType[SignTransactionResponse]
    bitcoinClient.publishTransaction(signTxResponse.tx).pipeTo(sender.ref)
    sender.expectMsg(signTxResponse.tx.txid)

    bitcoinClient.cpfp(Set(OutPoint(signTxResponse.tx, 0)), FeeratePerKw(1500 sat)).pipeTo(sender.ref)
    val failure = sender.expectMsgType[Failure]
    assert(failure.cause.getMessage.contains("tx signing failed"))
  }

  test("cannot bump transaction fees (amount too low)") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()
    val tx = sendToAddress(getNewAddress(sender), 2500 sat)
    val outputIndex = if (tx.txOut.head.amount == 2500.sat) 0 else 1
    bitcoinClient.cpfp(Set(OutPoint(tx, outputIndex)), FeeratePerKw(50_000 sat)).pipeTo(sender.ref)
    val failure = sender.expectMsgType[Failure]
    assert(failure.cause.getMessage.contains("input amount is not sufficient to cover the target feerate"))
  }

  test("detect if tx has been double-spent") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    // first let's create a tx
    val noInputTx1 = Transaction(2, Nil, Seq(TxOut(500_000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
    bitcoinClient.fundTransaction(noInputTx1, FeeratePerKw(2500 sat)).pipeTo(sender.ref)
    val unsignedTx1 = sender.expectMsgType[FundTransactionResponse].tx
    signTransaction(bitcoinClient, unsignedTx1).pipeTo(sender.ref)
    val tx1 = sender.expectMsgType[SignTransactionResponse].tx

    // let's then generate another tx that double spends the first one
    val unsignedTx2 = tx1.copy(txOut = Seq(TxOut(tx1.txOut.map(_.amount).sum, Script.pay2wpkh(randomKey().publicKey))))
    signTransaction(bitcoinClient, unsignedTx2).pipeTo(sender.ref)
    val tx2 = sender.expectMsgType[SignTransactionResponse].tx

    // tx1/tx2 haven't been published, so tx1 isn't double-spent
    bitcoinClient.doubleSpent(tx1).pipeTo(sender.ref)
    sender.expectMsg(false)
    // let's publish tx2
    bitcoinClient.publishTransaction(tx2).pipeTo(sender.ref)
    sender.expectMsg(tx2.txid)
    // tx2 hasn't been confirmed so tx1 is still not considered double-spent
    bitcoinClient.doubleSpent(tx1).pipeTo(sender.ref)
    sender.expectMsg(false)
    // tx2 isn't considered double-spent either
    bitcoinClient.doubleSpent(tx2).pipeTo(sender.ref)
    sender.expectMsg(false)
    // let's confirm tx2
    generateBlocks(1)
    // this time tx1 has been double-spent
    bitcoinClient.doubleSpent(tx1).pipeTo(sender.ref)
    sender.expectMsg(true)
    // and tx2 isn't considered double-spent since it's confirmed
    bitcoinClient.doubleSpent(tx2).pipeTo(sender.ref)
    sender.expectMsg(false)
  }

  test("detect if tx has been double-spent (with unconfirmed inputs)") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()
    val priv = randomKey()

    // Let's create one confirmed and one unconfirmed utxo.
    val (confirmedParentTx, unconfirmedParentTx) = {
      val txs = Seq(400_000 sat, 500_000 sat).map(amount => {
        val noInputTx = Transaction(2, Nil, Seq(TxOut(amount, Script.pay2wpkh(priv.publicKey))), 0)
        bitcoinClient.fundTransaction(noInputTx, FeeratePerKw(2500 sat)).pipeTo(sender.ref)
        val unsignedTx = sender.expectMsgType[FundTransactionResponse].tx
        signTransaction(bitcoinClient, unsignedTx).pipeTo(sender.ref)
        sender.expectMsgType[SignTransactionResponse].tx
      })
      bitcoinClient.publishTransaction(txs.head).pipeTo(sender.ref)
      sender.expectMsg(txs.head.txid)
      generateBlocks(1)
      bitcoinClient.publishTransaction(txs.last).pipeTo(sender.ref)
      sender.expectMsg(txs.last.txid)
      (txs.head, txs.last)
    }

    // Let's spend those unconfirmed utxos.
    val childTx = createSpendManyP2WPKH(Seq(confirmedParentTx, unconfirmedParentTx), priv, priv.publicKey, 500 sat, 0, 0)
    // The tx hasn't been published, so it isn't double-spent.
    bitcoinClient.doubleSpent(childTx).pipeTo(sender.ref)
    sender.expectMsg(false)
    // We publish the tx and verify it isn't double-spent.
    bitcoinClient.publishTransaction(childTx).pipeTo(sender.ref)
    sender.expectMsg(childTx.txid)
    bitcoinClient.doubleSpent(childTx).pipeTo(sender.ref)
    sender.expectMsg(false)

    // We double-spend the unconfirmed parent, which evicts our child transaction.
    {
      val previousAmountOut = unconfirmedParentTx.txOut.map(_.amount).sum
      val unsignedTx = unconfirmedParentTx.copy(txOut = Seq(TxOut(previousAmountOut - 50_000.sat, Script.pay2wpkh(randomKey().publicKey))))
      signTransaction(bitcoinClient, unsignedTx).pipeTo(sender.ref)
      val signedTx = sender.expectMsgType[SignTransactionResponse].tx
      bitcoinClient.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
    }

    // We can't know whether the child transaction is double-spent or not, as its unconfirmed input is now unknown: it's
    // not in the blockchain nor in the mempool. This unknown input may reappear in the future and the tx could then be
    // published again.
    bitcoinClient.doubleSpent(childTx).pipeTo(sender.ref)
    sender.expectMsg(false)

    // We double-spend the confirmed input.
    val spendingTx = createSpendP2WPKH(confirmedParentTx, priv, priv.publicKey, 600 sat, 0, 0)
    bitcoinClient.publishTransaction(spendingTx).pipeTo(sender.ref)
    sender.expectMsg(spendingTx.txid)
    // While the spending transaction is unconfirmed, we don't consider our transaction double-spent.
    bitcoinClient.doubleSpent(childTx).pipeTo(sender.ref)
    sender.expectMsg(false)
    // Once the spending transaction confirms, we know that our transaction is double-spent.
    generateBlocks(1)
    bitcoinClient.doubleSpent(childTx).pipeTo(sender.ref)
    sender.expectMsg(true)
  }

  test("find spending transaction of a given output") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    bitcoinClient.getBlockHeight().pipeTo(sender.ref)
    val blockHeight = sender.expectMsgType[BlockHeight]

    val address = getNewAddress(sender)
    val tx1 = sendToAddress(address, 5 btc)

    // Transaction is still in the mempool at that point
    bitcoinClient.getTxConfirmations(tx1.txid).pipeTo(sender.ref)
    sender.expectMsg(Some(0))
    // If we omit the mempool, tx1's input is still considered unspent.
    bitcoinClient.isTransactionOutputSpendable(tx1.txIn.head.outPoint.txid, tx1.txIn.head.outPoint.index.toInt, includeMempool = false).pipeTo(sender.ref)
    sender.expectMsg(true)
    // If we include the mempool, we see that tx1's input is now spent.
    bitcoinClient.isTransactionOutputSpendable(tx1.txIn.head.outPoint.txid, tx1.txIn.head.outPoint.index.toInt, includeMempool = true).pipeTo(sender.ref)
    sender.expectMsg(false)
    // If we omit the mempool, tx1's output is not considered spendable because we can't even find that output.
    bitcoinClient.isTransactionOutputSpendable(tx1.txid, 0, includeMempool = false).pipeTo(sender.ref)
    sender.expectMsg(false)
    // If we include the mempool, we see that tx1 produces an output that is still unspent.
    bitcoinClient.isTransactionOutputSpendable(tx1.txid, 0, includeMempool = true).pipeTo(sender.ref)
    sender.expectMsg(true)
    // We're able to find the spending transaction in the mempool.
    bitcoinClient.lookForMempoolSpendingTx(tx1.txIn.head.outPoint.txid, tx1.txIn.head.outPoint.index.toInt).pipeTo(sender.ref)
    sender.expectMsg(tx1)

    // Let's confirm our transaction.
    generateBlocks(1)
    bitcoinClient.getBlockHeight().pipeTo(sender.ref)
    val blockHeight1 = sender.expectMsgType[BlockHeight]
    assert(blockHeight1 == blockHeight + 1)
    bitcoinClient.getTxConfirmations(tx1.txid).pipeTo(sender.ref)
    sender.expectMsg(Some(1))
    bitcoinClient.isTransactionOutputSpendable(tx1.txid, 0, includeMempool = false).pipeTo(sender.ref)
    sender.expectMsg(true)
    bitcoinClient.isTransactionOutputSpendable(tx1.txid, 0, includeMempool = true).pipeTo(sender.ref)
    sender.expectMsg(true)

    generateBlocks(10)
    bitcoinClient.lookForMempoolSpendingTx(tx1.txIn.head.outPoint.txid, tx1.txIn.head.outPoint.index.toInt).pipeTo(sender.ref)
    sender.expectMsgType[Failure]
    bitcoinClient.lookForSpendingTx(None, tx1.txIn.head.outPoint.txid, tx1.txIn.head.outPoint.index.toInt, limit = 5).pipeTo(sender.ref)
    sender.expectMsgType[Failure]
    bitcoinClient.lookForSpendingTx(None, tx1.txIn.head.outPoint.txid, tx1.txIn.head.outPoint.index.toInt, limit = 15).pipeTo(sender.ref)
    sender.expectMsg(tx1)
  }

  test("get pubkey for p2wpkh receive address") {
    val sender = TestProbe()
    val bitcoinClient = makeBitcoinCoreClient()

    // eclair on-chain key manager does not yet support taproot descriptors
    bitcoinClient.getReceiveAddress().pipeTo(sender.ref)
    val defaultAddress = sender.expectMsgType[String]
    val decoded = Bech32.decodeWitnessAddress(defaultAddress)
    assert(decoded.getSecond == 0)

    // But we can explicitly use segwit v0 addresses.
    bitcoinClient.getP2wpkhPubkey().pipeTo(sender.ref)
    val amount = 50 millibtc
    val receiveKey = sender.expectMsgType[PublicKey]
    val address = computeP2WpkhAddress(receiveKey, Block.RegtestGenesisBlock.hash)
    sendToAddress(address, amount)
    generateBlocks(1)

    bitcoinrpcclient.invoke("getreceivedbyaddress", address).pipeTo(sender.ref)
    val receivedAmount = sender.expectMsgType[JDecimal]
    assert(Btc(receivedAmount.values).toMilliBtc == amount)
  }

  test("does not double-spend inputs of evicted transactions") {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    assume(!useEclairSigner)

    val sender = TestProbe()
    val miner = makeBitcoinCoreClient()

    // We create a bunch of confirmed utxos that use a large spending script.
    // This will let us fill the mempool by creating large transactions spending those utxos.
    val bigInputScript = Script.write(Seq.fill(200)(Seq(OP_PUSHDATA(ByteVector.fill(15)(42)), OP_DROP)).flatten)
    val largeInputsCount = 110
    val parentTxs = (1 to 15).map { _ =>
      val outputs = Seq.fill(largeInputsCount)(TxOut(50_000 sat, Script.pay2wsh(bigInputScript)))
      val txNotFunded = Transaction(2, Nil, outputs, 0)
      miner.fundTransaction(txNotFunded, FeeratePerKw(500 sat), changePosition = Some(outputs.length)).pipeTo(sender.ref)
      val fundedTx = sender.expectMsgType[FundTransactionResponse].tx
      signTransaction(miner, fundedTx).pipeTo(sender.ref)
      val signedTx = sender.expectMsgType[SignTransactionResponse].tx
      miner.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      signedTx
    }
    generateBlocks(1)

    /** Spend all outputs of the given parent transaction, creating a large mempool transaction. */
    def publishLargeTx(parentTx: Transaction, fromInput: Int, toInput: Int, feerate: FeeratePerKw): Unit = {
      val outputAmount = (parentTx.txOut.slice(fromInput, toInput).map(_.amount).sum - Transactions.weight2fee(feerate, 400_000)).max(500 sat)
      val txIn = (fromInput until toInput).map(i => TxIn(OutPoint(parentTx, i), ByteVector.empty, 0, ScriptWitness(Seq(ByteVector(1), bigInputScript))))
      val txOut = Seq(TxOut(outputAmount, Script.pay2wpkh(randomKey().publicKey)))
      var psbt = new Psbt(Transaction(2, txIn, txOut, 0))
      (fromInput until toInput).foreach { i =>
        psbt = psbt.updateWitnessInput(OutPoint(parentTx, i), parentTx.txOut(i), null, null, null, psbt.getInput(i - fromInput).getDerivationPaths, null, null, java.util.Map.of()).getRight
        psbt = psbt.finalizeWitnessInput(i - fromInput, ScriptWitness(Seq(ByteVector(1), bigInputScript))).getRight
      }
      val signedTx: Transaction = psbt.extract().getRight
      assert(signedTx.weight() <= 400_000) // standard transactions cannot exceed 400 000 WU
      miner.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
    }

    // We create two transactions that look like channel funding transactions, one for each test scenario.
    val (priv1, priv2) = (randomKey(), randomKey())
    val fundingRedeemScript = Scripts.multiSig2of2(priv1.publicKey, priv2.publicKey)
    val fundingScript = Script.pay2wsh(fundingRedeemScript)
    val fundingUtxos = (1 to 2).map { _ =>
      val txNotFunded = Transaction(2, Nil, Seq(TxOut(500_000 sat, fundingScript)), 0)
      miner.fundTransaction(txNotFunded, FeeratePerKw(2500 sat), replaceable = true).pipeTo(sender.ref)
      val fundedTx = sender.expectMsgType[FundTransactionResponse].tx
      signTransaction(miner, fundedTx).pipeTo(sender.ref)
      val signedTx = sender.expectMsgType[SignTransactionResponse].tx
      miner.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      OutPoint(signedTx.txid, signedTx.txOut.indexWhere(_.amount == 500_000.sat))
    }

    /** Spend the given utxo using the 2-of-2 funding script specified above and send to the same script. */
    def spendFundingScript(fundingUtxo: OutPoint, previousAmount: Satoshi, nextAmount: Satoshi): Transaction = {
      val unsignedTx = Transaction(2, Seq(TxIn(fundingUtxo, Nil, 0)), Seq(TxOut(nextAmount, fundingScript)), 0)
      val sig1 = Transaction.signInput(unsignedTx, 0, fundingRedeemScript, SigHash.SIGHASH_ALL, previousAmount, SigVersion.SIGVERSION_WITNESS_V0, priv1)
      val sig2 = Transaction.signInput(unsignedTx, 0, fundingRedeemScript, SigHash.SIGHASH_ALL, previousAmount, SigVersion.SIGVERSION_WITNESS_V0, priv2)
      val signedTx = unsignedTx.updateWitness(0, Scripts.witness2of2(der2compact(sig1), der2compact(sig2), priv1.publicKey, priv2.publicKey))
      miner.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      signedTx
    }

    // We fund our wallet with two confirmed utxos, one for each test scenario.
    val wallet = new BitcoinCoreClient(createWallet("mempool_eviction", sender))
    wallet.getReceiveAddress().pipeTo(sender.ref)
    val address = sender.expectMsgType[String]
    (1 to 2).foreach { _ =>
      miner.sendToAddress(address, 500_000 sat, 1).pipeTo(sender.ref)
      sender.expectMsgType[TxId]
    }
    generateBlocks(1)

    wallet.listUnspent().pipeTo(sender.ref)
    val walletUtxos = sender.expectMsgType[Seq[Utxo]]
    assert(walletUtxos.length == 2)
    walletUtxos.foreach(utxo => assert(utxo.safe))

    // We create two test scenarios in the mempool:
    //  - chain of remote splice transactions ending with a local splice transaction
    //  - local commitment transaction CPFP-ed using its anchor output
    val spliceTx = {
      val remoteSpliceTx1 = spendFundingScript(fundingUtxos(0), 500_000 sat, 490_000 sat)
      val remoteSpliceTx2 = spendFundingScript(OutPoint(remoteSpliceTx1, 0), 490_000 sat, 480_000 sat)
      val localSpliceTx = {
        val txNotFunded = Transaction(2, Seq(TxIn(OutPoint(remoteSpliceTx2, 0), Nil, 0)), Seq(TxOut(900_000 sat, fundingScript)), 0)
        val externalWeight = Map(txNotFunded.txIn.head.outPoint -> 390L)
        wallet.fundTransaction(txNotFunded, FeeratePerKw(2500 sat), externalInputsWeight = externalWeight).pipeTo(sender.ref)
        signTransaction(wallet, sender.expectMsgType[FundTransactionResponse].tx).pipeTo(sender.ref)
        val partiallySignedTx = sender.expectMsgType[SignTransactionResponse].tx
        val fundingIndex = partiallySignedTx.txIn.indexWhere(_.outPoint == OutPoint(remoteSpliceTx2, 0))
        val sig1 = Transaction.signInput(partiallySignedTx, fundingIndex, fundingRedeemScript, SigHash.SIGHASH_ALL, 480_000 sat, SigVersion.SIGVERSION_WITNESS_V0, priv1)
        val sig2 = Transaction.signInput(partiallySignedTx, fundingIndex, fundingRedeemScript, SigHash.SIGHASH_ALL, 480_000 sat, SigVersion.SIGVERSION_WITNESS_V0, priv2)
        val signedTx = partiallySignedTx.updateWitness(fundingIndex, Scripts.witness2of2(der2compact(sig1), der2compact(sig2), priv1.publicKey, priv2.publicKey))
        assert(signedTx.txIn.length == 2) // only one wallet input should have been added
        wallet.publishTransaction(signedTx).pipeTo(sender.ref)
        sender.expectMsg(signedTx.txid)
        signedTx
      }
      localSpliceTx
    }
    val anchorTx = {
      val localCommitTx = {
        val txOut = Seq(
          TxOut(490_000 sat, fundingScript),
          TxOut(330 sat, Script.pay2wsh(Scripts.anchor(priv1.publicKey)))
        )
        val unsignedTx = Transaction(2, Seq(TxIn(fundingUtxos(1), Nil, 0)), txOut, 0)
        val sig1 = Transaction.signInput(unsignedTx, 0, fundingRedeemScript, SigHash.SIGHASH_ALL, 500_000 sat, SigVersion.SIGVERSION_WITNESS_V0, priv1)
        val sig2 = Transaction.signInput(unsignedTx, 0, fundingRedeemScript, SigHash.SIGHASH_ALL, 500_000 sat, SigVersion.SIGVERSION_WITNESS_V0, priv2)
        val signedTx = unsignedTx.updateWitness(0, Scripts.witness2of2(der2compact(sig1), der2compact(sig2), priv1.publicKey, priv2.publicKey))
        wallet.publishTransaction(signedTx).pipeTo(sender.ref)
        sender.expectMsg(signedTx.txid)
        signedTx
      }
      val localAnchorTx = {
        val txNotFunded = Transaction(2, Seq(TxIn(OutPoint(localCommitTx, 1), Nil, 0)), Seq(TxOut(300_000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
        val externalWeight = Map(txNotFunded.txIn.head.outPoint -> ZeroFeeHtlcTxAnchorOutputsCommitmentFormat.anchorInputWeight.toLong)
        wallet.fundTransaction(txNotFunded, FeeratePerKw(2500 sat), externalInputsWeight = externalWeight).pipeTo(sender.ref)
        signTransaction(wallet, sender.expectMsgType[FundTransactionResponse].tx).pipeTo(sender.ref)
        val partiallySignedTx = sender.expectMsgType[SignTransactionResponse].tx
        val anchorIndex = partiallySignedTx.txIn.indexWhere(_.outPoint == OutPoint(localCommitTx, 1))
        val sig = Transaction.signInput(partiallySignedTx, anchorIndex, Scripts.anchor(priv1.publicKey), SigHash.SIGHASH_ALL, 330 sat, SigVersion.SIGVERSION_WITNESS_V0, priv1)
        val signedTx = partiallySignedTx.updateWitness(anchorIndex, Scripts.witnessAnchor(der2compact(sig), Script.write(Scripts.anchor(priv1.publicKey))))
        assert(signedTx.txIn.length == 2) // only one wallet input should have been added
        wallet.publishTransaction(signedTx).pipeTo(sender.ref)
        sender.expectMsg(signedTx.txid)
        signedTx
      }
      localAnchorTx
    }
    // Both wallet utxos are now spent.
    walletUtxos.foreach(utxo => assert(spliceTx.txIn.exists(_.outPoint.txid == utxo.txid) || anchorTx.txIn.exists(_.outPoint.txid == utxo.txid)))

    // At that point, the mempool contains the following transactions:
    //                   +------------------+     +------------------+     +-----------+
    // funding_tx_1 ---->| remote_splice_tx |---->| remote_splice_tx |---->|           |
    //                   +------------------+     +------------------+     | splice_tx |
    //                                                  wallet_utxo_1 ---->|           |
    //                                                                     +-----------+
    //                   +-----------------+     +-----------+
    // funding_tx_2 ---->| local_commit_tx |---->|           |
    //                   +-----------------+     | anchor_tx |
    //                        wallet_utxo_2 ---->|           |
    //                                           +-----------+

    // We now fill the mempool with large transactions to evict splice_tx and anchor_tx.
    // We start with very large transactions and then use smaller transaction to completely fill the mempool.
    parentTxs.take(12).foreach(tx => publishLargeTx(tx, 0, largeInputsCount, FeeratePerKw(5_000 sat)))
    (0 until largeInputsCount).foreach(i => publishLargeTx(parentTxs.last, i, i + 1, FeeratePerKw(5_000 sat)))
    Seq(spliceTx, anchorTx).foreach { walletTx =>
      wallet.getMempoolTx(walletTx.txid).pipeTo(sender.ref)
      assert(sender.expectMsgType[Failure].cause.getMessage.contains("Transaction not in mempool"))
    }

    // The wallet transactions have been evicted, but they must be kept inside the wallet and not be double-spent.
    val txNotFunded = Transaction(2, Nil, Seq(TxOut(50_000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
    wallet.fundTransaction(txNotFunded, FeeratePerKw(5000 sat), replaceable = true).pipeTo(sender.ref)
    assert(sender.expectMsgType[Failure].cause.getMessage.contains("Insufficient funds"))
    Seq(spliceTx, anchorTx).foreach { walletTx =>
      wallet.rpcClient.invoke("gettransaction", walletTx.txid).pipeTo(sender.ref)
      val json = sender.expectMsgType[JValue]
      assert(!(json \ "trusted").extract[Boolean])
      assert(!(json \ "details" \\ "abandoned").extract[Boolean])
      assert(Transaction.read((json \ "hex").extract[String]).txid == walletTx.txid)
    }

    // We now double-spend parent transactions, which permanently invalidates our wallet transactions:
    //                   +------------------+     +------------------+     +-----------+
    // funding_tx_1 +--->| remote_splice_tx |---->| remote_splice_tx |---->|           |
    //              |    +------------------+     +------------------+     | splice_tx |
    //              |    +--------------------+         wallet_utxo_1 ---->|           |
    //              +--->| remote_commit_tx_1 |                            +-----------+
    //                   +--------------------+
    //
    //                   +-----------------+     +-----------+
    // funding_tx_2 +--->| local_commit_tx |---->|           |
    //              |    +-----------------+     | anchor_tx |
    //              |         wallet_utxo_2 ---->|           |
    //              |                            +-----------+
    //              |    +--------------------+
    //              +--->| remote_commit_tx_2 |
    //                   +--------------------+
    val remoteCommitTx1 = spendFundingScript(fundingUtxos(0), 500_000 sat, 400_000 sat)
    val remoteCommitTx2 = spendFundingScript(fundingUtxos(1), 500_000 sat, 400_000 sat)
    generateBlocks(1)

    // Both funding outputs have been spent by transactions that are external to our wallet.
    fundingUtxos.foreach { utxo =>
      wallet.isTransactionOutputSpendable(utxo.txid, utxo.index.toInt, includeMempool = false).pipeTo(sender.ref)
      sender.expectMsg(false)
    }
    Seq(remoteCommitTx1, remoteCommitTx2).foreach { tx =>
      wallet.getTxConfirmations(tx.txid).pipeTo(sender.ref)
      sender.expectMsg(Some(1))
    }

    // Wallet transaction are still kept inside the wallet and cannot be double-spent, even though they have been
    // permanently double-spent at their ancestors' level.
    wallet.fundTransaction(txNotFunded, FeeratePerKw(5000 sat), replaceable = true).pipeTo(sender.ref)
    assert(sender.expectMsgType[Failure].cause.getMessage.contains("Insufficient funds"))
    Seq(spliceTx, anchorTx).foreach { walletTx =>
      wallet.rpcClient.invoke("gettransaction", walletTx.txid).map(json => Transaction.read((json \ "hex").extract[String])).pipeTo(sender.ref)
      assert(sender.expectMsgType[Transaction].txid == walletTx.txid)
    }

    // We must call abandontransaction to unlock the corresponding wallet inputs.
    Seq(spliceTx, anchorTx).foreach { walletTx =>
      wallet.abandon(walletTx.txid).pipeTo(sender.ref)
      sender.expectMsg(true)
    }
    wallet.fundTransaction(txNotFunded, FeeratePerKw(5000 sat), replaceable = true).pipeTo(sender.ref)
    sender.expectMsgType[FundTransactionResponse]
  }

}

class BitcoinCoreClientWithEclairSignerSpec extends BitcoinCoreClientSpec {
  override def useEclairSigner = true

  private def createWallet(seed: ByteVector): (BitcoinCoreClient, LocalOnChainKeyManager) = {
    val name = s"eclair_${seed.toHex.take(16)}"
    val onChainKeyManager = new LocalOnChainKeyManager(name, seed, TimestampSecond.now(), Block.RegtestGenesisBlock.hash)
    val jsonRpcClient = new BasicBitcoinJsonRPCClient(Block.RegtestGenesisBlock.hash, rpcAuthMethod = bitcoinrpcauthmethod, host = "localhost", port = bitcoindRpcPort, wallet = Some(name))
    (new BitcoinCoreClient(jsonRpcClient, onChainKeyManager_opt = Some(onChainKeyManager)), onChainKeyManager)
  }

  private def getBip32Path(wallet: BitcoinCoreClient, address: String, sender: TestProbe): DeterministicWallet.KeyPath = {
    wallet.rpcClient.invoke("getaddressinfo", address).pipeTo(sender.ref)
    val JString(bip32path) = sender.expectMsgType[JValue] \ "hdkeypath"
    DeterministicWallet.KeyPath(bip32path)
  }

  test("wallets managed by eclair implement BIP84") {
    val sender = TestProbe()
    val entropy = randomBytes32()
    val seed = MnemonicCode.toSeed(MnemonicCode.toMnemonics(entropy), "")
    val master = DeterministicWallet.generate(seed)
    val (wallet, keyManager) = createWallet(seed)
    createEclairBackedWallet(wallet.rpcClient, keyManager)

    // this account xpub can be used to create a watch-only wallet
    val accountXPub = master.derivePrivateKey("m/84'/1'/0'").extendedPublicKey.encode(DeterministicWallet.vpub)
    assert(wallet.onChainKeyManager_opt.get.masterPubKey(0, AddressType.P2wpkh) == accountXPub)

    (0 to 10).foreach { _ =>
      wallet.getReceiveAddress().pipeTo(sender.ref)
      val address = sender.expectMsgType[String]
      val bip32path = getBip32Path(wallet, address, sender)
      assert(bip32path.path.length == 5 && bip32path.toString().startsWith("m/84'/1'/0'/0"))
      assert(computeBIP84Address(master.derivePrivateKey(bip32path).publicKey, Block.RegtestGenesisBlock.hash) == address)

      wallet.getChangeAddress().pipeTo(sender.ref)
      val changeAddress = sender.expectMsgType[String]
      val bip32ChangePath = getBip32Path(wallet, changeAddress, sender)
      assert(bip32ChangePath.path.length == 5 && bip32ChangePath.toString().startsWith("m/84'/1'/0'/1"))
      assert(computeBIP84Address(master.derivePrivateKey(bip32ChangePath).publicKey, Block.RegtestGenesisBlock.hash) == changeAddress)
    }
  }

  test("wallets managed by eclair implement BIP86") {
    import KotlinUtils._
    import fr.acinq.bitcoin.Bitcoin.computeBIP86Address

    val sender = TestProbe()
    val entropy = randomBytes32()
    val seed = MnemonicCode.toSeed(MnemonicCode.toMnemonics(entropy), "")
    val master = DeterministicWallet.generate(seed)
    val (wallet, keyManager) = createWallet(seed)
    createEclairBackedWallet(wallet.rpcClient, keyManager)

    // this account xpub can be used to create a watch-only wallet
    val accountXPub = master.derivePrivateKey("m/86'/1'/0'").extendedPublicKey.encode(DeterministicWallet.tpub)
    assert(wallet.onChainKeyManager_opt.get.masterPubKey(0, AddressType.P2tr) == accountXPub)

    (0 to 10).foreach { _ =>
      wallet.getReceiveAddress(Some(AddressType.P2tr)).pipeTo(sender.ref)
      val address = sender.expectMsgType[String]
      val bip32path = getBip32Path(wallet, address, sender)
      assert(bip32path.path.length == 5 && bip32path.toString().startsWith("m/86'/1'/0'/0"))
      assert(computeBIP86Address(master.derivePrivateKey(bip32path).publicKey, Block.RegtestGenesisBlock.hash) == address)

      wallet.getChangeAddress(Some(AddressType.P2tr)).pipeTo(sender.ref)
      val changeAddress = sender.expectMsgType[String]
      val bip32ChangePath = getBip32Path(wallet, changeAddress, sender)
      assert(bip32ChangePath.path.length == 5 && bip32ChangePath.toString().startsWith("m/86'/1'/0'/1"))
      assert(computeBIP86Address(master.derivePrivateKey(bip32ChangePath).publicKey, Block.RegtestGenesisBlock.hash) == changeAddress)
    }
  }

  test("use eclair to manage on-chain keys") {
    val sender = TestProbe()

    (1 to 10).foreach { _ =>
      val (wallet, keyManager) = createWallet(randomBytes32())
      createEclairBackedWallet(wallet.rpcClient, keyManager)
      val addressType = if (Random.nextBoolean()) AddressType.P2tr else AddressType.P2wpkh
      wallet.getReceiveAddress(Some(addressType)).pipeTo(sender.ref)
      val address = sender.expectMsgType[String]

      // we can send to an on-chain address if eclair signs the transactions
      sendToAddress(address, 100_0000.sat)
      generateBlocks(1)

      // but bitcoin core's sendtoaddress RPC call will fail because wallets uses an external signer
      wallet.sendToAddress(address, 50_000.sat, 3).pipeTo(sender.ref)
      val error = sender.expectMsgType[Failure]
      assert(error.cause.getMessage.contains("Private keys are disabled for this wallet"))

      wallet.sendToPubkeyScript(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, address).toOption.get, 50_000.sat, FeeratePerByte(5.sat).perKw).pipeTo(sender.ref)
      sender.expectMsgType[TxId]
    }
  }

  test("sign mixed p2wpkh/p2tr inputs") {
    import KotlinUtils._

    val sender = TestProbe()
    val entropy = randomBytes32()
    val seed = MnemonicCode.toSeed(MnemonicCode.toMnemonics(entropy), "")
    val (wallet, keyManager) = createWallet(seed)
    createEclairBackedWallet(wallet.rpcClient, keyManager)

    // create a P2WPKH UTXO
    val utxo1 = {
      wallet.getReceiveAddress(Some(P2wpkh)).pipeTo(sender.ref)
      val address = sender.expectMsgType[String]
      val tx = sendToAddress(address, 100_000.sat)
      val outputIndex = tx.txOut.indexWhere(_.publicKeyScript == Script.write(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, address).toOption.get))
      OutPoint(tx, outputIndex)
    }
    // create a P2TR UTXO
    val utxo2 = {
      wallet.getReceiveAddress(Some(P2tr)).pipeTo(sender.ref)
      val address = sender.expectMsgType[String]
      val tx = sendToAddress(address, 100_000.sat)
      val outputIndex = tx.txOut.indexWhere(_.publicKeyScript == Script.write(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, address).toOption.get))
      OutPoint(tx, outputIndex)
    }
    generateBlocks(1)
    wallet.getReceiveAddress(Some(P2tr)).pipeTo(sender.ref)
    val address = sender.expectMsgType[String]

    val tx = Transaction(version = 2,
      txIn = TxIn(utxo1, Nil, TxIn.SEQUENCE_FINAL) :: TxIn(utxo2, Nil, TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(199_000.sat, addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, address).toOption.get) :: Nil,
      lockTime = 0)
    val psbt = new Psbt(tx)
    wallet.signPsbt(psbt, tx.txIn.indices, tx.txOut.indices).pipeTo(sender.ref)
    val ProcessPsbtResponse(signedPsbt, true) = sender.expectMsgType[ProcessPsbtResponse]
    val signedTx: Transaction = signedPsbt.extract().getRight
    wallet.publishTransaction(signedTx).pipeTo(sender.ref)
    sender.expectMsg(signedTx.txid)
  }
}
