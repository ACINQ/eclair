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

package fr.acinq.eclair.db


import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, Satoshi, SatoshiLong}
import fr.acinq.eclair.TestDatabases.{TestPgDatabases, TestSqliteDatabases}
import fr.acinq.eclair.db.pg.PgSwapsDb
import fr.acinq.eclair.db.sqlite.SqliteSwapsDb
import fr.acinq.eclair.payment.PaymentReceived.PartialPayment
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentReceived}
import fr.acinq.eclair.swap.SwapEvents.ClaimByInvoicePaid
import fr.acinq.eclair.swap.SwapRole.{Maker, SwapRole, Taker}
import fr.acinq.eclair.swap.{SwapData, SwapKeyManager}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiryDelta, TestConstants, ToMilliSatoshiConversion, randomBytes32}
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}

class SwapsDbSpec extends AnyFunSuite {

  import fr.acinq.eclair.TestDatabases.forAllDbs

  val protocolVersion = 2
  val noAsset = ""
  val network: String = TestConstants.Alice.nodeParams.chainHash.toString()
  val amount: Satoshi = 1000 sat
  val fee: Satoshi = 100 sat
  val makerKeyManager: SwapKeyManager = TestConstants.Alice.nodeParams.swapKeyManager
  val takerKeyManager: SwapKeyManager = TestConstants.Bob.nodeParams.swapKeyManager
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
    forAllDbs {
      case sqlite: TestSqliteDatabases =>
        new SqliteSwapsDb(sqlite.connection)
        new SqliteSwapsDb(sqlite.connection)
      case pg: TestPgDatabases =>
        new PgSwapsDb()(pg.datasource)
        new PgSwapsDb()(pg.datasource)
    }
  }

  test("add/list/addResult/restore/remove swaps") {
    forAllDbs { dbs =>
      val db = dbs.swaps

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
  }

  test("concurrent swap updates") {
    forAllDbs { dbs =>
      val db = dbs.swaps
      implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(8))
      val futures = for (_ <- 0 until 2500) yield {
        Future(db.add(swapData(randomBytes32().toString(),isInitiator = true, Maker)))
      }
      val res = Future.sequence(futures)
      Await.result(res, 60 seconds)
    }
  }

}