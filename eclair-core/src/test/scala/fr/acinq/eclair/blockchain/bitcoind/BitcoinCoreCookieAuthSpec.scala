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

import akka.testkit.TestProbe
import fr.acinq.eclair.TestKitBaseClass
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import org.json4s.JsonAST.JDecimal
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

class BitcoinCoreCookieAuthSpec extends TestKitBaseClass with BitcoindService with AnyFunSuiteLike with BeforeAndAfterAll {


  override def beforeAll(): Unit = {
    //do nothing
  }

  test("bitcoind cookie authentication") {
    startBitcoind(useCookie = true)
    waitForBitcoindReady()

    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("getbalance"))
    sender.expectMsgType[JDecimal]

    restartBitcoind(useCookie = true)

    sender.send(bitcoincli, BitcoinReq("getbalance"))
    sender.expectMsgType[JDecimal]
  }
}
