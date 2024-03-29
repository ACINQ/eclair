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
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import fr.acinq.eclair.blockchain.bitcoind.rpc.{Error, JsonRPCError}
import fr.acinq.eclair.{BitcoinDefaultWalletException, BitcoinWalletDisabledException, BitcoinWalletNotLoadedException, TestUtils}
import org.json4s.JsonAST.JValue

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/**
 * Created by remyers on 16/03/2022.
 */

class StartupIntegrationSpec extends IntegrationSpec {

  private def createConfig(wallet_opt: Option[String], waitForBitcoind: Boolean = false): Config = {
    val defaultConfig = ConfigFactory.parseMap(Map("eclair.bitcoind.wait-for-bitcoind-up" -> waitForBitcoind, "eclair.server.port" -> TestUtils.availablePort).asJava).withFallback(withStaticRemoteKey).withFallback(commonConfig)
    wallet_opt match {
      case Some(wallet) => ConfigFactory.parseMap(Map("eclair.bitcoind.wallet" -> wallet).asJava).withFallback(defaultConfig)
      case None => defaultConfig.withoutPath("eclair.bitcoind.wallet")
    }
  }

  test("no bitcoind wallet configured and one wallet loaded") {
    instantiateEclairNode("A", createConfig(wallet_opt = None))
  }

  test("no bitcoind wallet configured and two wallets loaded") {
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("createwallet", "other_wallet"))
    sender.expectMsgType[Any]
    val thrown = intercept[BitcoinDefaultWalletException] {
      instantiateEclairNode("C", createConfig(wallet_opt = None))
    }
    assert(thrown == BitcoinDefaultWalletException(List(defaultWallet, "other_wallet")))
  }

  test("explicit bitcoind wallet configured and two wallets loaded") {
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("createwallet", "other_wallet"))
    sender.expectMsgType[Any]
    instantiateEclairNode("D", createConfig(wallet_opt = Some(defaultWallet)))
  }

  test("explicit bitcoind wallet configured but not loaded") {
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("createwallet", "other_wallet"))
    sender.expectMsgType[Any]
    val thrown = intercept[BitcoinWalletNotLoadedException] {
      instantiateEclairNode("E", createConfig(wallet_opt = Some("not_loaded")))
    }
    assert(thrown == BitcoinWalletNotLoadedException("not_loaded", List(defaultWallet, "other_wallet")))
  }

  test("bitcoind started with wallets disabled") {
    restartBitcoind(startupFlags = "-disablewallet", loadWallet = false)
    val thrown = intercept[BitcoinWalletDisabledException] {
      instantiateEclairNode("F", createConfig(wallet_opt = None))
    }
    assert(thrown == BitcoinWalletDisabledException(e = JsonRPCError(Error(-32601, "Method not found"))))
  }

  test("wait for bitcoind to be available") {
    import scala.concurrent.ExecutionContext.Implicits.global
    stopBitcoind()
    val sender = TestProbe()
    Future {
      startBitcoind()
      waitForBitcoindUp(sender)
      sender.send(bitcoincli, BitcoinReq("loadwallet", defaultWallet))
      sender.expectMsgType[JValue]
    }
    instantiateEclairNode("G", createConfig(wallet_opt = None, waitForBitcoind = true))
  }
}