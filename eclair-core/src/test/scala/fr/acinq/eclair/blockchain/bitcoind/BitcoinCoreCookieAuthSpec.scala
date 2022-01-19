/*
 * Copyright 2021 ACINQ SAS
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

import akka.pattern.pipe
import akka.testkit.TestProbe
import fr.acinq.eclair.{BlockHeight, TestKitBaseClass}
import fr.acinq.eclair.blockchain.OnChainWallet.OnChainBalance
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.ExecutionContext.Implicits.global

class BitcoinCoreCookieAuthSpec extends TestKitBaseClass with BitcoindService with AnyFunSuiteLike with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    startBitcoind(useCookie = true)
    waitForBitcoindReady()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  test("call bitcoind with cookie authentication") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    bitcoinClient.onChainBalance().pipeTo(sender.ref)
    sender.expectMsgType[OnChainBalance]
  }

  test("automatically fetch updated credentials after restart") {
    val sender = TestProbe()
    // We make a first call before restarting bitcoind to ensure we have a first set of valid credentials.
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    bitcoinClient.getBlockHeight().pipeTo(sender.ref)
    val blockHeight = sender.expectMsgType[BlockHeight]

    restartBitcoind(sender, useCookie = true)

    // We use the previous bitcoin client: its credentials are now obsolete since bitcoind changes the cookie credentials
    // after restarting.
    bitcoinClient.getBlockHeight().pipeTo(sender.ref)
    sender.expectMsg(blockHeight)

    // A fresh bitcoin client will automatically fetch the latest credentials.
    val postRestartBitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    postRestartBitcoinClient.getBlockHeight().pipeTo(sender.ref)
    sender.expectMsg(blockHeight)
  }

}