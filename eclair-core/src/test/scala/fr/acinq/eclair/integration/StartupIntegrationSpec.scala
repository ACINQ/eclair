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

package fr.acinq.eclair.integration

import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import fr.acinq.eclair.blockchain.bitcoind.rpc.{Error, JsonRPCError}
import fr.acinq.eclair.{BitcoinDefaultWalletException, BitcoinWalletDisabledException, BitcoinWalletNotLoadedException, TestUtils}

import scala.jdk.CollectionConverters._

/**
 * Created by remyers on 16/03/2022.
 */

class StartupIntegrationSpec extends IntegrationSpec {

  test("no bitcoind wallet configured and one wallet loaded") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.bitcoind.wallet" -> "", "eclair.server.port" -> TestUtils.availablePort).asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
  }

  test("no bitcoind wallet configured and two wallets loaded") {
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("createwallet", ""))
    sender.expectMsgType[Any]
    val thrown = intercept[BitcoinDefaultWalletException] {
      instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.bitcoind.wallet" -> "", "eclair.server.port" -> TestUtils.availablePort).asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    }
    assert(thrown == BitcoinDefaultWalletException(List(defaultWallet, "")))
  }

  test("explicit bitcoind wallet configured and two wallets loaded") {
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("createwallet", ""))
    sender.expectMsgType[Any]
    instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.server.port" -> TestUtils.availablePort).asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
  }

  test("explicit bitcoind wallet configured but not loaded") {
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("createwallet", ""))
    sender.expectMsgType[Any]
    val thrown = intercept[BitcoinWalletNotLoadedException] {
      instantiateEclairNode("E", ConfigFactory.parseMap(Map("eclair.bitcoind.wallet" -> "notloaded", "eclair.server.port" -> TestUtils.availablePort).asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    }
    assert(thrown == BitcoinWalletNotLoadedException("notloaded", List(defaultWallet, "")))
  }

  test("bitcoind started with wallets disabled") {
    restartBitcoind(startupFlags = "-disablewallet", loadWallet = false)
    val thrown = intercept[BitcoinWalletDisabledException] {
      instantiateEclairNode("F", ConfigFactory.load().getConfig("eclair").withFallback(withDefaultCommitment).withFallback(commonConfig))
    }
    assert(thrown == BitcoinWalletDisabledException(e = JsonRPCError(Error(-32601, "Method not found"))))
  }
}