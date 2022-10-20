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

package fr.acinq.eclair.plugins.peerswap.db

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, Satoshi, SatoshiLong}
import fr.acinq.eclair.payment.PaymentReceived.PartialPayment
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentReceived}
import fr.acinq.eclair.plugins.peerswap.SwapEvents.ClaimByInvoicePaid
import fr.acinq.eclair.plugins.peerswap.SwapRole.{Maker, SwapRole, Taker}
import fr.acinq.eclair.plugins.peerswap.db.sqlite.SqliteSwapsDb
import fr.acinq.eclair.plugins.peerswap.wire.protocol._
import fr.acinq.eclair.plugins.peerswap.{LocalSwapKeyManager, SwapData, SwapKeyManager}
import fr.acinq.eclair.{CltvExpiryDelta, NodeParams, TestConstants, ToMilliSatoshiConversion, randomBytes32}
import org.scalatest.funsuite.AnyFunSuite

import java.sql.DriverManager
import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}

class SwapsDbSpec extends AnyFunSuite {

  val protocolVersion = 2
  val noAsset = ""
  val network: String = NodeParams.chainFromHash(TestConstants.Alice.nodeParams.chainHash)
  val amount: Satoshi = 1000 sat
  val fee: Satoshi = 100 sat
  val makerKeyManager: SwapKeyManager = new LocalSwapKeyManager(TestConstants.Alice.seed, TestConstants.Alice.nodeParams.chainHash)
  val takerKeyManager: SwapKeyManager = new LocalSwapKeyManager(TestConstants.Bob.seed, TestConstants.Bob.nodeParams.chainHash)
  val makerNodeId: PublicKey = PrivateKey(randomBytes32()).publicKey
  val premium = 10
  val txid: String = ByteVector32.One.toHex
  val scriptOut: Long = 0
  val blindingKey: String = ""
  val paymentPreimage: ByteVector32 = ByteVector32.One
  val feePreimage: ByteVector32 = ByteVector32.Zeroes
  val scid = "1x1x1"

  def paymentInvoice(swapId: String): Bolt11Invoice = Bolt11Invoice(TestConstants.Alice.nodeParams.chainHash, Some(amount.toMilliSatoshi), Crypto.sha256(paymentPreimage), makerPrivkey(swapId), Left("SwapOutSender payment invoice"), CltvExpiryDelta(18))
  def feeInvoice(swapId: String): Bolt11Invoice = Bolt11Invoice(TestConstants.Alice.nodeParams.chainHash, Some(fee.toMilliSatoshi), Crypto.sha256(feePreimage), makerPrivkey(swapId), Left("SwapOutSender fee invoice"), CltvExpiryDelta(18))
  def makerPrivkey(swapId: String): PrivateKey = makerKeyManager.openingPrivateKey(SwapKeyManager.keyPath(swapId)).privateKey
  def takerPrivkey(swapId: String): PrivateKey = takerKeyManager.openingPrivateKey(SwapKeyManager.keyPath(swapId)).privateKey
  def makerPubkey(swapId: String): PublicKey = makerPrivkey(swapId).publicKey
  def takerPubkey(swapId: String): PublicKey = takerPrivkey(swapId).publicKey
  def swapInRequest(swapId: String): SwapInRequest = SwapInRequest(protocolVersion = protocolVersion, swapId = swapId, asset = noAsset, network = network, scid = scid, amount = amount.toLong, pubkey = makerPubkey(swapId).toHex)
  def swapOutRequest(swapId: String): SwapOutRequest = SwapOutRequest(protocolVersion = protocolVersion, swapId = swapId, asset = noAsset, network = network, scid = scid, amount = amount.toLong, pubkey = takerPubkey(swapId).toHex)
  def swapInAgreement(swapId: String): SwapInAgreement = SwapInAgreement(protocolVersion, swapId, takerPubkey(swapId).toHex, premium)
  def swapOutAgreement(swapId: String): SwapOutAgreement = SwapOutAgreement(protocolVersion, swapId, makerPubkey(swapId).toHex, feeInvoice(swapId).toString)
  def openingTxBroadcasted(swapId: String): OpeningTxBroadcasted = OpeningTxBroadcasted(swapId, paymentInvoice(swapId).toString, txid, scriptOut, blindingKey)
  def paymentCompleteResult(swapId: String): ClaimByInvoicePaid = ClaimByInvoicePaid(swapId, PaymentReceived(paymentInvoice(swapId).paymentHash, Seq(PartialPayment(amount.toMilliSatoshi, randomBytes32()))))
  def swapData(swapId: String, isInitiator: Boolean, swapType: SwapRole): SwapData = {
    val (request, agreement) = (isInitiator, swapType == Maker) match {
      case (true, true) => (swapInRequest(swapId), swapInAgreement(swapId))
      case (false, false) => (swapInRequest(swapId), swapInAgreement(swapId))
      case (true, false) => (swapOutRequest(swapId), swapOutAgreement(swapId))
      case (false, true) => (swapOutRequest(swapId), swapOutAgreement(swapId))
    }
    SwapData(request, agreement, paymentInvoice(swapId), openingTxBroadcasted(swapId), swapType, isInitiator)
  }

  test("init database two times in a row") {
    val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    new SqliteSwapsDb(connection)
    new SqliteSwapsDb(connection)
  }

  test("add/list/addResult/restore/remove swaps") {
    val db = new SqliteSwapsDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
    assert(db.list().isEmpty)

    val swap_1 = swapData(randomBytes32().toString(),isInitiator = true, Maker)
    val swap_2 = swapData(randomBytes32().toString(),isInitiator = false, Maker)
    val swap_3 = swapData(randomBytes32().toString(),isInitiator = true, Taker)
    val swap_4 = swapData(randomBytes32().toString(),isInitiator = false, Taker)

    assert(db.list().toSet == Set.empty)
    db.add(swap_1)
    assert(db.list().toSet == Set(swap_1))
    db.add(swap_1) // duplicate is ignored
    assert(db.list().size == 1)
    db.add(swap_2)
    db.add(swap_3)
    db.add(swap_4)
    assert(db.list().toSet == Set(swap_1, swap_2, swap_3, swap_4))
    db.addResult(paymentCompleteResult(swap_2.request.swapId))
    assert(db.restore().toSet == Set(swap_1, swap_3, swap_4))
    db.remove(swap_2.request.swapId)
    assert(db.list().toSet == Set(swap_1, swap_3, swap_4))
    assert(db.restore().toSet == Set(swap_1, swap_3, swap_4))
  }

  test("concurrent swap updates") {
    val db = new SqliteSwapsDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
    assert(db.list().isEmpty)

    implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(8))
    val futures = for (_ <- 0 until 2500) yield {
      Future(db.add(swapData(randomBytes32().toString(),isInitiator = true, Maker)))
    }
    val res = Future.sequence(futures)
    Await.result(res, 60 seconds)
  }
}