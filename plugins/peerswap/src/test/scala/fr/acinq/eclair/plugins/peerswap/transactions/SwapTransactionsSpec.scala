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

package fr.acinq.eclair.plugins.peerswap.transactions

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter.{ClassicActorRefOps, ClassicActorSystemOps}
import akka.pattern.pipe
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, Satoshi, SatoshiLong}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.DummyOnChainWallet
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.publish.FinalTxPublisher
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishContext
import fr.acinq.eclair.plugins.peerswap.transactions.SwapTransactions._
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.checkSpendable
import grizzled.slf4j.Logging
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

class SwapTransactionsSpec extends TestKitBaseClass with AnyFunSuiteLike with BitcoindService with BeforeAndAfterAll with Logging {
  val makerRefundPriv: PrivateKey = PrivateKey(randomBytes32())
  val takerPaymentPriv: PrivateKey = PrivateKey(randomBytes32())
  val paymentPreimage: ByteVector32 = randomBytes32()
  val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage)
  val amount: Satoshi = 30000 sat
  val premium: Satoshi = 150 sat
  val openingTxId: ByteVector32 = randomBytes32()
  val openingTxOut: Int = 0
  val claimInput: Transactions.InputInfo = makeSwapOpeningInputInfo(openingTxId, openingTxOut, amount, makerRefundPriv.publicKey, takerPaymentPriv.publicKey, paymentHash)

  val csvDelay = 20
  val localDustLimit: Satoshi = Satoshi(546)
  val feeratePerKw: FeeratePerKw = FeeratePerKw(10000 sat)
  val wallet = new DummyOnChainWallet()


  override def beforeAll(): Unit = {
    startBitcoind()
    waitForBitcoindReady()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  def createFixture(): Fixture = {
    val probe = TestProbe()
    val watcher = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    val publisher = system.spawnAnonymous(FinalTxPublisher(TestConstants.Alice.nodeParams, bitcoinClient, watcher.ref.toTyped, TxPublishContext(UUID.randomUUID(), randomKey().publicKey, None)))
    Fixture(bitcoinClient, publisher, watcher, probe)
  }

  case class Fixture(bitcoinClient: BitcoinCoreClient, publisher: ActorRef[FinalTxPublisher.Command], watcher: TestProbe, probe: TestProbe)

  test("check validity of PeerSwap claim transactions") {
    val f = createFixture()
    import f._

    val swapTxOut = makeSwapOpeningTxOut(amount + premium, makerRefundPriv.publicKey, takerPaymentPriv.publicKey, paymentHash)
    wallet.makeFundingTx(swapTxOut.publicKeyScript, amount + premium, feeratePerKw).pipeTo(probe.ref)
    val response = probe.expectMsgType[MakeFundingTxResponse]
    val openingTx = response.fundingTx
    val openingTxOut = response.fundingTxOutputIndex
    val inputInfo = makeSwapOpeningInputInfo(openingTx.txid, openingTxOut, amount + premium, makerRefundPriv.publicKey, takerPaymentPriv.publicKey, paymentHash)

    val swapClaimByInvoiceTx = makeSwapClaimByInvoiceTx(amount + premium, makerRefundPriv.publicKey, takerPaymentPriv, paymentPreimage, feeratePerKw, openingTx.txid, openingTxOut)
    assert(swapClaimByInvoiceTx.txIn.head.sequence == 0)
    assert(checkSpendable(SwapClaimByInvoiceTx(inputInfo, swapClaimByInvoiceTx)).isSuccess)

    val swapClaimByCoopTx = makeSwapClaimByCoopTx(amount + premium, makerRefundPriv, takerPaymentPriv, paymentHash, feeratePerKw, openingTx.txid, openingTxOut)
    assert(swapClaimByCoopTx.txIn.head.sequence == 0)
    assert(checkSpendable(SwapClaimByCoopTx(inputInfo, swapClaimByCoopTx)).isSuccess)

    val swapClaimByCsvTx = makeSwapClaimByCsvTx(amount + premium, makerRefundPriv, takerPaymentPriv.publicKey, paymentHash, feeratePerKw, openingTx.txid, openingTxOut)
    assert(swapClaimByCsvTx.txIn.head.sequence == 1008)
    assert(checkSpendable(SwapClaimByCsvTx(inputInfo, swapClaimByCsvTx)).isSuccess)
  }
}