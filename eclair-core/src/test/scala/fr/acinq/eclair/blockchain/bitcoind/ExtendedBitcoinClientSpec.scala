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

import akka.actor.ActorSystem
import akka.actor.Status.Failure
import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Base58, Base58Check, Block, Btc, ByteVector32, ByteVector64, Crypto, DeterministicWallet, OP_PUSHDATA, OutPoint, SIGHASH_ALL, Script, ScriptFlags, ScriptWitness, SigVersion, Transaction, TxIn, TxOut}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair._
import fr.acinq.eclair.LongToBtcAmount
import fr.acinq.eclair.blockchain.{UtxoStatus, ValidateResult}
import fr.acinq.eclair.wire.ChannelAnnouncement
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JString, _}
import org.json4s.DefaultFormats
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}
import scodec.bits.ByteVector

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global


class ExtendedBitcoinClientSpec extends TestKit(ActorSystem("test")) with BitcoindService with FunSuiteLike with BeforeAndAfterAll with Logging {

  val commonConfig = ConfigFactory.parseMap(Map(
    "eclair.chain" -> "regtest",
    "eclair.spv" -> false,
    "eclair.server.public-ips.1" -> "localhost",
    "eclair.bitcoind.port" -> bitcoindPort,
    "eclair.bitcoind.rpcport" -> bitcoindRpcPort,
    "eclair.router-broadcast-interval" -> "2 second",
    "eclair.auto-reconnect" -> false))
  val config = ConfigFactory.load(commonConfig).getConfig("eclair")

  implicit val formats = DefaultFormats

  override def beforeAll(): Unit = {
    startBitcoind()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  test("wait bitcoind ready") {
    waitForBitcoindReady()
  }

  test("send transaction idempotent") {
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))

    val sender = TestProbe()
    bitcoinClient.invoke("getnewaddress").pipeTo(sender.ref)
    val JString(address) = sender.expectMsgType[JString]
    bitcoinClient.invoke("createrawtransaction", Array.empty, Map(address -> 6)).pipeTo(sender.ref)
    val JString(noinputTx) = sender.expectMsgType[JString]
    bitcoinClient.invoke("fundrawtransaction", noinputTx).pipeTo(sender.ref)
    val json = sender.expectMsgType[JValue]
    val JString(unsignedtx) = json \ "hex"
    val JInt(changePos) = json \ "changepos"
    bitcoinClient.invoke("signrawtransactionwithwallet", unsignedtx).pipeTo(sender.ref)
    val JString(signedTx) = sender.expectMsgType[JValue] \ "hex"
    val tx = Transaction.read(signedTx)
    val txid = tx.txid.toString()
  }

  def nonWalletTransaction(): (Transaction, ShortChannelId) = {

    val amountToReceive = Btc(0.1)
    val probe = TestProbe()
    val master = DeterministicWallet.generate(randomBytes32)
    val (privKey, privKey1) = (DeterministicWallet.derivePrivateKey(master, 0), DeterministicWallet.derivePrivateKey(master, 1))
    val (pubKey, pubKey1) = (privKey.publicKey, privKey1.publicKey)
    val externalWalletAddress = Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, Crypto.hash160(pubKey.value))

    // send bitcoins from our wallet to an external wallet
    bitcoinrpcclient.invoke("sendtoaddress", externalWalletAddress, amountToReceive.toBigDecimal.toString()).pipeTo(probe.ref)
    val JString(spendingToExternalTxId) = probe.expectMsgType[JValue]

    bitcoinrpcclient.invoke("getrawtransaction", spendingToExternalTxId).pipeTo(probe.ref)
    val JString(rawTx) = probe.expectMsgType[JValue]
    val incomingTx = Transaction.read(rawTx)
    val outIndex = incomingTx.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2pkh(pubKey)))

    assert(outIndex >= 0)

    generateBlocks(bitcoincli, 1)

    // tx spending bitcoin from external wallet to external wallet
    val tx = Transaction(
      version = 2L,
      txIn = TxIn(OutPoint(incomingTx, outIndex), ByteVector.empty,  TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(amountToReceive.toSatoshi - 200.sat, Script.write(Script.pay2pkh(pubKey1))) :: Nil, // amount - fee
      lockTime = 0
    )

    val sig = Transaction.signInput(tx, 0, Script.pay2pkh(pubKey), SIGHASH_ALL, 2500 sat, SigVersion.SIGVERSION_BASE, privKey.privateKey)
    val tx1 = tx.updateSigScript(0, OP_PUSHDATA(sig) :: OP_PUSHDATA(pubKey.value) :: Nil)
    Transaction.correctlySpends(tx1, Seq(incomingTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    bitcoinrpcclient.invoke("sendrawtransaction", Transaction.write(tx1).toHex).pipeTo(probe.ref)
    val txId = probe.expectMsgType[JString].s
    generateBlocks(bitcoincli, 1) // bury it in a block

    bitcoinrpcclient.invoke("getbestblockhash").pipeTo(probe.ref)
    val blockHash = probe.expectMsgType[JString].s

    bitcoinrpcclient.invoke("getblock", blockHash, 0).pipeTo(probe.ref)
    val JString(rawBlock) = probe.expectMsgType[JString]
    val block = Block.read(rawBlock)
    val txIndex = block.tx.indexWhere(_.txid.toHex == txId)

    bitcoinrpcclient.invoke("getblockchaininfo").pipeTo(probe.ref)
    val height = (probe.expectMsgType[JValue] \ "blocks").extract[Int]

    (tx1, ShortChannelId(height, txIndex, 0))
  }

  test("validate short channel Ids") {
    val sender = TestProbe()
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))

    val client = new ExtendedBitcoinClient(bitcoinClient)
    val (channelTransaction, channelShortId) = nonWalletTransaction() // create a non wallet transaction

    // we won't be able to get the raw transaction if it's non-wallet
    client.getRawTransaction(channelTransaction.txid.toHex).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    // likewise we can't get the channel short id from the txId
    client.getTransactionShortId(channelTransaction.txid.toHex).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    val mockChannelAnnouncement = ChannelAnnouncement(
      nodeSignature1 = ByteVector64.Zeroes,
      nodeSignature2 = ByteVector64.Zeroes,
      bitcoinSignature1 = ByteVector64.Zeroes,
      bitcoinSignature2 = ByteVector64.Zeroes,
      features = ByteVector.empty,
      chainHash = ByteVector32.Zeroes,
      shortChannelId = channelShortId,
      nodeId1 = PrivateKey(randomBytes32).publicKey,
      nodeId2 = PrivateKey(randomBytes32).publicKey,
      bitcoinKey1 = PrivateKey(randomBytes32).publicKey,
      bitcoinKey2 = PrivateKey(randomBytes32).publicKey
    )

    // but we can validate if a short channel id is unspent
    client.validate(mockChannelAnnouncement).pipeTo(sender.ref)
    sender.expectMsg(ValidateResult(mockChannelAnnouncement, Right(channelTransaction, UtxoStatus.Unspent)))

    // if the output does not exist, validation fails
    client.validate(mockChannelAnnouncement.copy(shortChannelId = ShortChannelId(10, 10, 10))).pipeTo(sender.ref)
    val validationResult = sender.expectMsgType[ValidateResult]
    assert(validationResult.fundingTx.isLeft)
  }


}