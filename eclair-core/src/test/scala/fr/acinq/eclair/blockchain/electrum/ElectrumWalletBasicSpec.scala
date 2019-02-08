/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.blockchain.electrum

import java.sql.DriverManager

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.DeterministicWallet.{ExtendedPrivateKey, derivePrivateKey}
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.electrum.db.sqlite.SqliteWalletDb
import fr.acinq.eclair.transactions.Transactions
import grizzled.slf4j.Logging
import org.scalatest.FunSuite

import scala.util.{Failure, Random, Success, Try}


class ElectrumWalletBasicSpec extends FunSuite with Logging {

  import ElectrumWallet._
  import ElectrumWalletBasicSpec._

  val swipeRange = 10
  val dustLimit = 546 satoshi
  val feeRatePerKw = 20000
  val minimumFee = Satoshi(2000)

  val master = DeterministicWallet.generate(BinaryData("01" * 32))
  val accountMaster = accountKey(master, Block.RegtestGenesisBlock.hash)
  val accountIndex = 0

  val changeMaster = changeKey(master, Block.RegtestGenesisBlock.hash)
  val changeIndex = 0

  val firstAccountKeys = (0 until 10).map(i => derivePrivateKey(accountMaster, i)).toVector
  val firstChangeKeys = (0 until 10).map(i => derivePrivateKey(changeMaster, i)).toVector

  val params = ElectrumWallet.WalletParameters(Block.RegtestGenesisBlock.hash, new SqliteWalletDb(DriverManager.getConnection("jdbc:sqlite::memory:")))

  val state = Data(params, Blockchain.fromCheckpoints(Block.RegtestGenesisBlock.hash, CheckPoint.load(Block.RegtestGenesisBlock.hash)), firstAccountKeys, firstChangeKeys)
    .copy(status = (firstAccountKeys ++ firstChangeKeys).map(key => computeScriptHashFromPublicKey(key.publicKey) -> "").toMap)

  def addFunds(data: Data, key: ExtendedPrivateKey, amount: Satoshi): Data = {
    val tx = Transaction(version = 1, txIn = Nil, txOut = TxOut(amount, ElectrumWallet.computePublicKeyScript(key.publicKey)) :: Nil, lockTime = 0)
    val scriptHash = ElectrumWallet.computeScriptHashFromPublicKey(key.publicKey)
    val scriptHashHistory = data.history.getOrElse(scriptHash, List.empty[ElectrumClient.TransactionHistoryItem])
    data.copy(
      history = data.history.updated(scriptHash, ElectrumClient.TransactionHistoryItem(100, tx.txid) :: scriptHashHistory),
      transactions = data.transactions + (tx.txid -> tx)
    )
  }

  def addFunds(data: Data, keyamount: (ExtendedPrivateKey, Satoshi)): Data = {
    val tx = Transaction(version = 1, txIn = Nil, txOut = TxOut(keyamount._2, ElectrumWallet.computePublicKeyScript(keyamount._1.publicKey)) :: Nil, lockTime = 0)
    val scriptHash = ElectrumWallet.computeScriptHashFromPublicKey(keyamount._1.publicKey)
    val scriptHashHistory = data.history.getOrElse(scriptHash, List.empty[ElectrumClient.TransactionHistoryItem])
    data.copy(
      history = data.history.updated(scriptHash, ElectrumClient.TransactionHistoryItem(100, tx.txid) :: scriptHashHistory),
      transactions = data.transactions + (tx.txid -> tx)
    )
  }

  def addFunds(data: Data, keyamounts: Seq[(ExtendedPrivateKey, Satoshi)]): Data = keyamounts.foldLeft(data)(addFunds)


  test("compute addresses") {
    val priv = PrivateKey.fromBase58("cRumXueoZHjhGXrZWeFoEBkeDHu2m8dW5qtFBCqSAt4LDR2Hnd8Q", Base58.Prefix.SecretKeyTestnet)
    assert(Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, priv.publicKey.hash160) == "ms93boMGZZjvjciujPJgDAqeR86EKBf9MC")
    assert(segwitAddress(priv, Block.RegtestGenesisBlock.hash) == "2MscvqgGXMTYJNAY3owdUtgWJaxPUjH38Cx")
  }

  test("implement BIP49") {
    val mnemonics = "pizza afraid guess romance pair steel record jazz rubber prison angle hen heart engage kiss visual helmet twelve lady found between wave rapid twist".split(" ")
    val seed = MnemonicCode.toSeed(mnemonics, "")
    val master = DeterministicWallet.generate(seed)

    val accountMaster = accountKey(master, Block.RegtestGenesisBlock.hash)
    val firstKey = derivePrivateKey(accountMaster, 0)
    assert(segwitAddress(firstKey, Block.RegtestGenesisBlock.hash) === "2MxJejujQJRRJdbfTKNQQ94YCnxJwRaE7yo")
  }

  test("complete transactions (enough funds)") {
    val state1 = addFunds(state, state.accountKeys.head, 1 btc)
    val (confirmed1, unconfirmed1) = state1.balance

    val pub = PrivateKey(BinaryData("01" * 32), compressed = true).publicKey
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(0.5 btc, Script.pay2pkh(pub)) :: Nil, lockTime = 0)
    val (state2, tx1, fee1) = state1.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit, false)
    val Some((_, _, Some(fee))) = state2.computeTransactionDelta(tx1)
    assert(fee == fee1)
    val actualFeeRate = Transactions.fee2rate(fee, tx1.weight())

    val state3 = state2.cancelTransaction(tx1)
    assert(state3 == state1)

    val state4 = state2.commitTransaction(tx1)
    val (confirmed4, unconfirmed4) = state4.balance
    assert(confirmed4 == confirmed1)
    assert(unconfirmed1 - unconfirmed4 >= btc2satoshi(0.5 btc))
  }

  test("complete transactions (insufficient funds)") {
    val state1 = addFunds(state, state.accountKeys.head, 5 btc)
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(6 btc, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
    val e = intercept[IllegalArgumentException] {
      state1.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit, false)
    }
  }

  test("compute the effect of tx") {
    val state1 = addFunds(state, state.accountKeys.head, 1 btc)
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(0.5 btc, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
    val (state2, tx1, fee1) = state1.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit, false)

    val Some((received, sent, Some(fee))) = state1.computeTransactionDelta(tx1)
    assert(fee == fee1)
    assert(sent - received - fee == btc2satoshi(0.5 btc))
  }

  test("use actual transaction weight to compute fees") {
    val state1 = addFunds(state, (state.accountKeys(0), Satoshi(5000000)) :: (state.accountKeys(1), Satoshi(6000000)) :: (state.accountKeys(2), Satoshi(4000000)) :: Nil)

    {
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(Satoshi(5000000), Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val (state3, tx1, fee1) = state1.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit, true)
      val Some((_, _, Some(fee))) = state3.computeTransactionDelta(tx1)
      assert(fee == fee1)
      val actualFeeRate = Transactions.fee2rate(fee, tx1.weight())
      assert(isFeerateOk(actualFeeRate, feeRatePerKw))
    }
    {
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(Satoshi(5000000) - dustLimit, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val (state3, tx1, fee1) = state1.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit, true)
      val Some((_, _, Some(fee))) = state3.computeTransactionDelta(tx1)
      assert(fee == fee1)
      val actualFeeRate = Transactions.fee2rate(fee, tx1.weight())
      assert(isFeerateOk(actualFeeRate, feeRatePerKw))
    }
    {
      // with a huge fee rate that will force us to use an additional input when we complete our tx
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(Satoshi(3000000), Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val (state3, tx1, fee1) = state1.completeTransaction(tx, 100 * feeRatePerKw, minimumFee, dustLimit, true)
      val Some((_, _, Some(fee))) = state3.computeTransactionDelta(tx1)
      assert(fee == fee1)
      val actualFeeRate = Transactions.fee2rate(fee, tx1.weight())
      assert(isFeerateOk(actualFeeRate, 100 * feeRatePerKw))
    }
    {
      // with a tiny fee rate that will force us to use an additional input when we complete our tx
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(0.09), Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val (state3, tx1, fee1) = state1.completeTransaction(tx, feeRatePerKw / 10, minimumFee / 10, dustLimit, true)
      val Some((_, _, Some(fee))) = state3.computeTransactionDelta(tx1)
      assert(fee == fee1)
      val actualFeeRate = Transactions.fee2rate(fee, tx1.weight())
      assert(isFeerateOk(actualFeeRate, feeRatePerKw / 10))
    }
  }

  test("spend all our balance") {
    val state1 = addFunds(state, state.accountKeys(0), 1 btc)
    val state2 = addFunds(state1, state1.accountKeys(1), 2 btc)
    val state3 = addFunds(state2, state2.changeKeys(0), 0.5 btc)
    assert(state3.utxos.length == 3)
    assert(state3.balance == (Satoshi(350000000),Satoshi(0)))

    val (tx, fee) = state3.spendAll(Script.pay2wpkh(BinaryData("01" * 20)), feeRatePerKw)
    val Some((received, sent, Some(fee1))) = state3.computeTransactionDelta(tx)
    assert(received == Satoshi(0))
    assert(fee == fee1)
    assert(tx.txOut.map(_.amount).sum + fee == state3.balance._1 + state3.balance._2)
  }

  test("fuzzy test") {
    val random = new Random()
    (0 to 10) foreach { _ =>
      val funds = for (i <- 0 until random.nextInt(10)) yield {
        val index = random.nextInt(state.accountKeys.length)
        val amount = dustLimit + Satoshi(random.nextInt(10000000))
        (state.accountKeys(index), amount)
      }
      val state1 = addFunds(state, funds)
      (0 until 30) foreach { _ =>
        val amount = dustLimit + Satoshi(random.nextInt(10000000))
        val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
        Try(state1.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit, true)) match {
          case Success((state2, tx1, fee1)) => ()
          case Failure(cause) if cause.getMessage != null && cause.getMessage.contains("insufficient funds") => ()
          case Failure(cause) => logger.error(s"unexpected $cause")
        }
      }
    }
  }
}

object ElectrumWalletBasicSpec {
  /**
    *
    * @param actualFeeRate actual fee rate
    * @param targetFeeRate target fee rate
    * @return true if actual fee rate is within 10% of target
    */
  def isFeerateOk(actualFeeRate: Long, targetFeeRate: Long): Boolean = Math.abs(actualFeeRate - targetFeeRate) < 0.1 * (actualFeeRate + targetFeeRate)
}