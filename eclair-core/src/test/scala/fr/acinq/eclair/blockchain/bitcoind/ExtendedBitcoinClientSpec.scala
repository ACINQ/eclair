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
import akka.pattern._
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Transaction}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, ExtendedBitcoinClient, JsonRPCError}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.{ImportMultiItem, UtxoStatus, ValidateResult, WatchAddressItem}
import fr.acinq.eclair.wire.ChannelAnnouncement
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JString, _}
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}
import scodec.bits.ByteVector

import scala.collection.JavaConversions._
import scala.compat.Platform
import scala.concurrent.Await
import scala.concurrent.duration._
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

  test("validate short_channel_id without using txindex") {
    val sender = TestProbe()
    val client = new ExtendedBitcoinClient(bitcoinrpcclient)
    val (receiveKey, sendKey) = (PrivateKey(randomBytes32), PrivateKey(randomBytes32))
    val channelTransaction = ExternalWalletHelper.nonWalletTransaction(receiveKey, sendKey)(system) // create a non wallet transaction
    client.publishTransaction(channelTransaction).pipeTo(sender.ref)
    sender.expectMsgType[String]

    val List(blockHash) = generateBlocks(bitcoincli, 1)

    client.getBlockCount.pipeTo(sender.ref)
    val height = sender.expectMsgType[Long]

    bitcoinrpcclient.invoke("getblock", blockHash, 0).pipeTo(sender.ref)
    val JString(rawBlock) = sender.expectMsgType[JString]
    val block = Block.read(rawBlock)
    val txIndex = block.tx.indexWhere(_.txid == channelTransaction.txid)

    val channelShortId = ShortChannelId(height.toInt, txIndex, 0)

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

  test("importmulti should import watch addresses and new block are scanned for the addresses") {
    val sender = TestProbe()
    val client = new ExtendedBitcoinClient(bitcoinrpcclient)
    val (receiveKey, sendKey) = (PrivateKey(randomBytes32), PrivateKey(randomBytes32))
    val externalTx = ExternalWalletHelper.nonWalletTransaction(receiveKey, sendKey)(system) // create a non wallet transaction
    val address1 = scriptPubKeyToAddress(externalTx.txOut.head.publicKeyScript)

    client.importMulti(Seq(ImportMultiItem(address1, "PENDING", None)), rescan = false).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    // publish tx
    client.publishTransaction(externalTx).pipeTo(sender.ref)
    sender.expectMsgType[String]

    // mine it in blocks
    generateBlocks(bitcoincli, 2)

    // lookup transaction
    client.getTransaction(externalTx.txid.toHex).pipeTo(sender.ref)
    sender.expectMsg(externalTx)
  }

}