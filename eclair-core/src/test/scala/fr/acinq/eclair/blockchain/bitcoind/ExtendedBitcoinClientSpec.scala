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
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.SigVersion.SIGVERSION_WITNESS_V0
import fr.acinq.bitcoin.{Bech32, BtcDouble, ByteVector32, Crypto, OutPoint, SIGHASH_ALL, Satoshi, SatoshiLong, Script, ScriptFlags, ScriptWitness, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient._
import fr.acinq.eclair.blockchain.bitcoind.rpc.{ExtendedBitcoinClient, JsonRPCError}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.{TestConstants, TestKitBaseClass, randomKey}
import grizzled.slf4j.Logging
import org.json4s.JsonAST._
import org.json4s.{DefaultFormats, Formats}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class ExtendedBitcoinClientSpec extends TestKitBaseClass with BitcoindService with AnyFunSuiteLike with BeforeAndAfterAll with Logging {

  implicit val formats: Formats = DefaultFormats

  override def beforeAll(): Unit = {
    startBitcoind()
    waitForBitcoindReady()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  test ("fund and sign psbt") {
    val sender = TestProbe()
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    val priv1 = PrivateKey(ByteVector32.fromValidHex("01" * 32))
    val priv2 = PrivateKey(ByteVector32.fromValidHex("02" * 32))
    val script = Script.createMultiSigMofN(2, Seq(priv1.publicKey, priv2.publicKey))
    val address = Bech32.encodeWitnessAddress("bcrt", 0, Crypto.sha256(Script.write(script)))

    bitcoinClient.fundPsbt(Map(address -> 10000.sat), 0, FundTransactionOptions(TestConstants.feeratePerKw)).pipeTo(sender.ref)
    val FundPsbtResponse(psbt, _, _) = sender.expectMsgType[FundPsbtResponse]

    bitcoinClient.processPsbt(psbt).pipeTo(sender.ref)
    val ProcessPsbtResponse(psbt1, true) = sender.expectMsgType[ProcessPsbtResponse]
    assert(psbt1.extract().isSuccess)
  }

  test("fund transactions") {
    val sender = TestProbe()
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)

    val txToRemote = {
      val txNotFunded = Transaction(2, Nil, TxOut(150000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
      bitcoinClient.fundTransaction(txNotFunded, FundTransactionOptions(TestConstants.feeratePerKw)).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      assert(fundTxResponse.changePosition.nonEmpty)
      assert(fundTxResponse.amountIn > 0.sat)
      assert(fundTxResponse.fee > 0.sat)
      fundTxResponse.tx.txIn.foreach(txIn => assert(txIn.signatureScript.isEmpty && txIn.witness.isNull))
      fundTxResponse.tx.txIn.foreach(txIn => assert(txIn.sequence === TxIn.SEQUENCE_FINAL - 2))

      bitcoinClient.signTransaction(fundTxResponse.tx, Nil).pipeTo(sender.ref)
      val signTxResponse = sender.expectMsgType[SignTransactionResponse]
      assert(signTxResponse.complete)
      assert(signTxResponse.tx.txOut.size === 2)

      bitcoinClient.publishTransaction(signTxResponse.tx).pipeTo(sender.ref)
      sender.expectMsg(signTxResponse.tx.txid)
      generateBlocks(1)
      signTxResponse.tx
    }
    {
      // txs with no outputs are not supported.
      val emptyTx = Transaction(2, Nil, Nil, 0)
      bitcoinClient.fundTransaction(emptyTx, FundTransactionOptions(TestConstants.feeratePerKw)).pipeTo(sender.ref)
      sender.expectMsgType[Failure]
    }
    {
      // bitcoind requires that "all existing inputs must have their previous output transaction be in the wallet".
      val txNonWalletInputs = Transaction(2, Seq(TxIn(OutPoint(txToRemote, 0), Nil, 0), TxIn(OutPoint(txToRemote, 1), Nil, 0)), Seq(TxOut(100000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
      bitcoinClient.fundTransaction(txNonWalletInputs, FundTransactionOptions(TestConstants.feeratePerKw)).pipeTo(sender.ref)
      sender.expectMsgType[Failure]
    }
    {
      // we can increase the feerate.
      bitcoinClient.fundTransaction(Transaction(2, Nil, TxOut(250000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0), FundTransactionOptions(TestConstants.feeratePerKw)).pipeTo(sender.ref)
      val fundTxResponse1 = sender.expectMsgType[FundTransactionResponse]
      bitcoinClient.fundTransaction(fundTxResponse1.tx, FundTransactionOptions(TestConstants.feeratePerKw * 2)).pipeTo(sender.ref)
      val fundTxResponse2 = sender.expectMsgType[FundTransactionResponse]
      assert(fundTxResponse1.tx !== fundTxResponse2.tx)
      assert(fundTxResponse1.fee < fundTxResponse2.fee)
    }
    {
      // we can control where the change output is inserted and opt-out of RBF.
      val txManyOutputs = Transaction(2, Nil, TxOut(410000 sat, Script.pay2wpkh(randomKey().publicKey)) :: TxOut(230000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
      bitcoinClient.fundTransaction(txManyOutputs, FundTransactionOptions(TestConstants.feeratePerKw, replaceable = false, changePosition = Some(1))).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      assert(fundTxResponse.tx.txOut.size === 3)
      assert(fundTxResponse.changePosition === Some(1))
      assert(!Set(230000 sat, 410000 sat).contains(fundTxResponse.tx.txOut(1).amount))
      assert(Set(230000 sat, 410000 sat) === Set(fundTxResponse.tx.txOut.head.amount, fundTxResponse.tx.txOut.last.amount))
      fundTxResponse.tx.txIn.foreach(txIn => assert(txIn.sequence === TxIn.SEQUENCE_FINAL - 1))
    }
  }

  test("unlock utxos when transaction is published") {
    val sender = TestProbe()
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)

    // create a first transaction with multiple inputs
    val tx1 = {
      val fundedTxs = (1 to 3).map(_ => {
        val txNotFunded = Transaction(2, Nil, TxOut(15000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
        bitcoinClient.fundTransaction(txNotFunded, FundTransactionOptions(TestConstants.feeratePerKw, lockUtxos = true)).pipeTo(sender.ref)
        sender.expectMsgType[FundTransactionResponse].tx
      })
      val fundedTx = Transaction(2, fundedTxs.flatMap(_.txIn), fundedTxs.flatMap(_.txOut), 0)
      assert(fundedTx.txIn.length >= 3)

      // tx inputs should be locked
      val lockedUtxos = getLocks(sender)
      fundedTx.txIn.foreach(txIn => assert(lockedUtxos.contains(txIn.outPoint)))

      bitcoinClient.signTransaction(fundedTx, Nil).pipeTo(sender.ref)
      val signTxResponse = sender.expectMsgType[SignTransactionResponse]
      bitcoinClient.publishTransaction(signTxResponse.tx).pipeTo(sender.ref)
      sender.expectMsg(signTxResponse.tx.txid)
      // once the tx is published, the inputs should be automatically unlocked
      assert(getLocks(sender).isEmpty)
      signTxResponse.tx
    }

    // create a second transaction that double-spends one of the inputs of the first transaction
    val tx2 = {
      val txNotFunded = tx1.copy(txIn = tx1.txIn.take(1))
      bitcoinClient.fundTransaction(txNotFunded, FundTransactionOptions(TestConstants.feeratePerKw * 2, lockUtxos = true)).pipeTo(sender.ref)
      val fundedTx = sender.expectMsgType[FundTransactionResponse].tx
      assert(fundedTx.txIn.length >= 2) // we added at least one new input

      // newly added inputs should be locked
      val lockedUtxos = getLocks(sender)
      fundedTx.txIn.foreach(txIn => assert(lockedUtxos.contains(txIn.outPoint)))

      bitcoinClient.signTransaction(fundedTx, Nil).pipeTo(sender.ref)
      val signTxResponse = sender.expectMsgType[SignTransactionResponse]
      bitcoinClient.publishTransaction(signTxResponse.tx).pipeTo(sender.ref)
      sender.expectMsg(signTxResponse.tx.txid)
      // once the tx is published, the inputs should be automatically unlocked
      assert(getLocks(sender).isEmpty)
      signTxResponse.tx
    }

    // tx2 replaced tx1 in the mempool
    bitcoinClient.getMempool().pipeTo(sender.ref)
    val mempoolTxs = sender.expectMsgType[Seq[Transaction]]
    assert(mempoolTxs.length === 1)
    assert(mempoolTxs.head.txid === tx2.txid)
    assert(tx2.txIn.map(_.outPoint).intersect(tx1.txIn.map(_.outPoint)).length === 1)
  }

  test("sign transactions") {
    val sender = TestProbe()
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)

    val nonWalletKey = randomKey()
    val opts = FundTransactionOptions(TestConstants.feeratePerKw, changePosition = Some(1))
    bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(250000 sat, Script.pay2wpkh(nonWalletKey.publicKey))), 0), opts).pipeTo(sender.ref)
    val fundedTx = sender.expectMsgType[FundTransactionResponse].tx
    bitcoinClient.signTransaction(fundedTx, Nil).pipeTo(sender.ref)
    val txToRemote = sender.expectMsgType[SignTransactionResponse].tx
    bitcoinClient.publishTransaction(txToRemote).pipeTo(sender.ref)
    sender.expectMsg(txToRemote.txid)
    generateBlocks(1)

    {
      bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(400000 sat, Script.pay2wpkh(randomKey().publicKey))), 0), opts).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      val txWithNonWalletInput = fundTxResponse.tx.copy(txIn = TxIn(OutPoint(txToRemote, 0), ByteVector.empty, 0) +: fundTxResponse.tx.txIn)

      // bitcoind returns an error if there are unsigned non-wallet input.
      bitcoinClient.signTransaction(txWithNonWalletInput, Nil).pipeTo(sender.ref)
      val Failure(JsonRPCError(error)) = sender.expectMsgType[Failure]
      assert(error.message.contains(txToRemote.txid.toHex))

      // we can ignore that error with allowIncomplete = true, and in that case bitcoind signs the wallet inputs.
      bitcoinClient.signTransaction(txWithNonWalletInput, Nil, allowIncomplete = true).pipeTo(sender.ref)
      val signTxResponse1 = sender.expectMsgType[SignTransactionResponse]
      assert(!signTxResponse1.complete)
      signTxResponse1.tx.txIn.tail.foreach(walletTxIn => assert(walletTxIn.witness.stack.nonEmpty))

      // if the non-wallet inputs are signed, bitcoind signs the remaining wallet inputs.
      val nonWalletSig = Transaction.signInput(txWithNonWalletInput, 0, Script.pay2pkh(nonWalletKey.publicKey), SIGHASH_ALL, txToRemote.txOut.head.amount, SIGVERSION_WITNESS_V0, nonWalletKey)
      val nonWalletWitness = ScriptWitness(Seq(nonWalletSig, nonWalletKey.publicKey.value))
      val txWithSignedNonWalletInput = txWithNonWalletInput.updateWitness(0, nonWalletWitness)
      bitcoinClient.signTransaction(txWithSignedNonWalletInput, Nil).pipeTo(sender.ref)
      val signTxResponse2 = sender.expectMsgType[SignTransactionResponse]
      assert(signTxResponse2.complete)
      Transaction.correctlySpends(signTxResponse2.tx, Seq(txToRemote), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }
    {
      // bitcoind does not sign inputs that have already been confirmed.
      bitcoinClient.signTransaction(fundedTx, Nil).pipeTo(sender.ref)
      val Failure(JsonRPCError(error)) = sender.expectMsgType[Failure]
      assert(error.message.contains("not found or already spent"))
    }
    {
      // bitcoind lets us double-spend ourselves.
      bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(75000 sat, Script.pay2wpkh(randomKey().publicKey))), 0), opts).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      bitcoinClient.signTransaction(fundTxResponse.tx, Nil).pipeTo(sender.ref)
      assert(sender.expectMsgType[SignTransactionResponse].complete)
      bitcoinClient.signTransaction(fundTxResponse.tx.copy(txOut = Seq(TxOut(85000 sat, Script.pay2wpkh(randomKey().publicKey)))), Nil).pipeTo(sender.ref)
      assert(sender.expectMsgType[SignTransactionResponse].complete)
    }
    {
      // create an unconfirmed utxo to a non-wallet address.
      bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(125000 sat, Script.pay2wpkh(nonWalletKey.publicKey))), 0), opts).pipeTo(sender.ref)
      bitcoinClient.signTransaction(sender.expectMsgType[FundTransactionResponse].tx, Nil).pipeTo(sender.ref)
      val unconfirmedTx = sender.expectMsgType[SignTransactionResponse].tx
      bitcoinClient.publishTransaction(unconfirmedTx).pipeTo(sender.ref)
      sender.expectMsg(unconfirmedTx.txid)
      // bitcoind lets us use this unconfirmed non-wallet input.
      bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(350000 sat, Script.pay2wpkh(randomKey().publicKey))), 0), opts).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      val txWithUnconfirmedInput = fundTxResponse.tx.copy(txIn = TxIn(OutPoint(unconfirmedTx, 0), ByteVector.empty, 0) +: fundTxResponse.tx.txIn)
      val nonWalletSig = Transaction.signInput(txWithUnconfirmedInput, 0, Script.pay2pkh(nonWalletKey.publicKey), SIGHASH_ALL, unconfirmedTx.txOut.head.amount, SIGVERSION_WITNESS_V0, nonWalletKey)
      val nonWalletWitness = ScriptWitness(Seq(nonWalletSig, nonWalletKey.publicKey.value))
      val txWithSignedUnconfirmedInput = txWithUnconfirmedInput.updateWitness(0, nonWalletWitness)
      val previousTx = PreviousTx(Transactions.InputInfo(OutPoint(unconfirmedTx.txid, 0), unconfirmedTx.txOut.head, Script.pay2pkh(nonWalletKey.publicKey)), nonWalletWitness)
      bitcoinClient.signTransaction(txWithSignedUnconfirmedInput, Seq(previousTx)).pipeTo(sender.ref)
      assert(sender.expectMsgType[SignTransactionResponse].complete)
    }
  }

  test("publish transaction idempotent") {
    val sender = TestProbe()
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)

    val priv = dumpPrivateKey(getNewAddress(sender), sender)
    val noInputTx = Transaction(2, Nil, TxOut(6.btc.toSatoshi, Script.pay2wpkh(priv.publicKey)) :: Nil, 0)
    bitcoinClient.fundTransaction(noInputTx, FundTransactionOptions(TestConstants.feeratePerKw)).pipeTo(sender.ref)
    val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
    val changePos = fundTxResponse.changePosition.get
    bitcoinClient.signTransaction(fundTxResponse.tx, Nil).pipeTo(sender.ref)
    val tx = sender.expectMsgType[SignTransactionResponse].tx

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
      bitcoinrpcclient.invoke("createrawtransaction", Array(Map("txid" -> tx.txid.toHex, "vout" -> pos)), Map(address -> 5.999)).pipeTo(sender.ref)
      val JString(unsignedTx) = sender.expectMsgType[JValue]
      bitcoinClient.signTransaction(Transaction.read(unsignedTx), Nil).pipeTo(sender.ref)
      sender.expectMsgType[SignTransactionResponse].tx
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
    val sender = TestProbe()
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)

    // that tx has inputs that don't exist
    val txWithUnknownInputs = Transaction.read("02000000000101b9e2a3f518fd74e696d258fed3c78c43f84504e76c99212e01cf225083619acf00000000000d0199800136b34b00000000001600145464ce1e5967773922506e285780339d72423244040047304402206795df1fd93c285d9028c384aacf28b43679f1c3f40215fd7bd1abbfb816ee5a022047a25b8c128e692d4717b6dd7b805aa24ecbbd20cfd664ab37a5096577d4a15d014730440220770f44121ed0e71ec4b482dded976f2febd7500dfd084108e07f3ce1e85ec7f5022025b32dc0d551c47136ce41bfb80f5a10de95c0babb22a3ae2d38e6688b32fcb20147522102c2662ab3e4fa18a141d3be3317c6ee134aff10e6cd0a91282a25bf75c0481ebc2102e952dd98d79aa796289fa438e4fdeb06ed8589ff2a0f032b0cfcb4d7b564bc3252aea58d1120")
    bitcoinClient.publishTransaction(txWithUnknownInputs).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    // invalid txs shouldn't be found in either the mempool or the blockchain
    bitcoinClient.getTxConfirmations(txWithUnknownInputs.txid).pipeTo(sender.ref)
    sender.expectMsg(None)

    bitcoinClient.fundTransaction(Transaction(2, Nil, TxOut(100000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0), FundTransactionOptions(TestConstants.feeratePerKw)).pipeTo(sender.ref)
    val txUnsignedInputs = sender.expectMsgType[FundTransactionResponse].tx
    bitcoinClient.publishTransaction(txUnsignedInputs).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    bitcoinClient.signTransaction(txUnsignedInputs, Nil).pipeTo(sender.ref)
    val signTxResponse = sender.expectMsgType[SignTransactionResponse]
    assert(signTxResponse.complete)

    val txWithNoOutputs = signTxResponse.tx.copy(txOut = Nil)
    bitcoinClient.publishTransaction(txWithNoOutputs).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    bitcoinClient.getBlockCount.pipeTo(sender.ref)
    val blockCount = sender.expectMsgType[Long]
    val txWithFutureCltv = signTxResponse.tx.copy(lockTime = blockCount + 1)
    bitcoinClient.publishTransaction(txWithFutureCltv).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    bitcoinClient.publishTransaction(signTxResponse.tx).pipeTo(sender.ref)
    sender.expectMsg(signTxResponse.tx.txid)
  }

  test("get mempool transaction") {
    val sender = TestProbe()
    val address = getNewAddress(sender)
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)

    def spendWalletTx(tx: Transaction, fees: Satoshi): Transaction = {
      val inputs = tx.txOut.indices.map(vout => Map("txid" -> tx.txid, "vout" -> vout))
      val amount = tx.txOut.map(_.amount).sum - fees
      bitcoinrpcclient.invoke("createrawtransaction", inputs, Map(address -> amount.toBtc.toBigDecimal)).pipeTo(sender.ref)
      val JString(unsignedTx) = sender.expectMsgType[JValue]
      bitcoinClient.signTransaction(Transaction.read(unsignedTx), Nil).pipeTo(sender.ref)
      val signedTx = sender.expectMsgType[SignTransactionResponse].tx
      bitcoinClient.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      signedTx
    }

    val tx1 = sendToAddress(address, 0.5 btc, sender)
    val tx2 = spendWalletTx(tx1, 5000 sat)
    val tx3 = spendWalletTx(tx2, 7500 sat)

    bitcoinClient.getMempoolTx(tx1.txid).pipeTo(sender.ref)
    val mempoolTx1 = sender.expectMsgType[MempoolTx]
    assert(mempoolTx1.ancestorCount === 0)
    assert(mempoolTx1.descendantCount === 2)
    assert(mempoolTx1.fees === mempoolTx1.ancestorFees)
    assert(mempoolTx1.descendantFees === mempoolTx1.fees + 12500.sat)

    bitcoinClient.getMempoolTx(tx2.txid).pipeTo(sender.ref)
    val mempoolTx2 = sender.expectMsgType[MempoolTx]
    assert(mempoolTx2.ancestorCount === 1)
    assert(mempoolTx2.descendantCount === 1)
    assert(mempoolTx2.fees === 5000.sat)
    assert(mempoolTx2.descendantFees === 12500.sat)
    assert(mempoolTx2.ancestorFees === mempoolTx1.fees + 5000.sat)

    bitcoinClient.getMempoolTx(tx3.txid).pipeTo(sender.ref)
    val mempoolTx3 = sender.expectMsgType[MempoolTx]
    assert(mempoolTx3.ancestorCount === 2)
    assert(mempoolTx3.descendantCount === 0)
    assert(mempoolTx3.fees === 7500.sat)
    assert(mempoolTx3.descendantFees === mempoolTx3.fees)
    assert(mempoolTx3.ancestorFees === mempoolTx1.fees + 12500.sat)
  }

  test("detect if tx has been double-spent") {
    val sender = TestProbe()
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)

    // first let's create a tx
    val address = "n2YKngjUp139nkjKvZGnfLRN6HzzYxJsje"
    bitcoinrpcclient.invoke("createrawtransaction", Array.empty, Map(address -> 6)).pipeTo(sender.ref)
    val JString(noinputTx1) = sender.expectMsgType[JString]
    bitcoinrpcclient.invoke("fundrawtransaction", noinputTx1).pipeTo(sender.ref)
    val json = sender.expectMsgType[JValue]
    val JString(unsignedtx1) = json \ "hex"
    bitcoinrpcclient.invoke("signrawtransactionwithwallet", unsignedtx1).pipeTo(sender.ref)
    val JString(signedTx1) = sender.expectMsgType[JValue] \ "hex"
    val tx1 = Transaction.read(signedTx1)

    // let's then generate another tx that double spends the first one
    val inputs = tx1.txIn.map(txIn => Map("txid" -> txIn.outPoint.txid.toString, "vout" -> txIn.outPoint.index)).toArray
    bitcoinrpcclient.invoke("createrawtransaction", inputs, Map(address -> tx1.txOut.map(_.amount).sum.toLong * 1.0 / 1e8)).pipeTo(sender.ref)
    val JString(unsignedtx2) = sender.expectMsgType[JValue]
    bitcoinrpcclient.invoke("signrawtransactionwithwallet", unsignedtx2).pipeTo(sender.ref)
    val JString(signedTx2) = sender.expectMsgType[JValue] \ "hex"
    val tx2 = Transaction.read(signedTx2)

    // tx1/tx2 haven't been published, so tx1 isn't double spent
    bitcoinClient.doubleSpent(tx1).pipeTo(sender.ref)
    sender.expectMsg(false)
    // let's publish tx2
    bitcoinClient.publishTransaction(tx2).pipeTo(sender.ref)
    sender.expectMsg(tx2.txid)
    // tx2 hasn't been confirmed so tx1 is still not considered double-spent
    bitcoinClient.doubleSpent(tx1).pipeTo(sender.ref)
    sender.expectMsg(false)
    // let's confirm tx2
    generateBlocks(1)
    // this time tx1 has been double spent
    bitcoinClient.doubleSpent(tx1).pipeTo(sender.ref)
    sender.expectMsg(true)
  }

  test("find spending transaction of a given output") {
    val sender = TestProbe()
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)

    bitcoinClient.getBlockCount.pipeTo(sender.ref)
    val blockCount = sender.expectMsgType[Long]

    val address = getNewAddress(sender)
    val tx1 = sendToAddress(address, 5 btc, sender)

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

    // Let's confirm our transaction.
    generateBlocks(1)
    bitcoinClient.getBlockCount.pipeTo(sender.ref)
    val blockCount1 = sender.expectMsgType[Long]
    assert(blockCount1 === blockCount + 1)
    bitcoinClient.getTxConfirmations(tx1.txid).pipeTo(sender.ref)
    sender.expectMsg(Some(1))
    bitcoinClient.isTransactionOutputSpendable(tx1.txid, 0, includeMempool = false).pipeTo(sender.ref)
    sender.expectMsg(true)
    bitcoinClient.isTransactionOutputSpendable(tx1.txid, 0, includeMempool = true).pipeTo(sender.ref)
    sender.expectMsg(true)

    generateBlocks(1)
    bitcoinClient.lookForSpendingTx(None, tx1.txIn.head.outPoint.txid, tx1.txIn.head.outPoint.index.toInt).pipeTo(sender.ref)
    sender.expectMsg(tx1)
  }

}