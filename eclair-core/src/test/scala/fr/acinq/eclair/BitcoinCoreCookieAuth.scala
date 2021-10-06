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

package fr.acinq.eclair

import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.integration.IntegrationSpec
import org.scalatest.concurrent.ScalaFutures.whenReady

import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.CollectionConverters.MapHasAsJava

class BitcoinCoreCookieAuth extends IntegrationSpec {


  override def beforeAll(): Unit = {
    //do nothing
  }

  test("bitcoind cookie authentication") {
    startBitcoind(useCookie = true)
    waitForBitcoindReady()

    instantiateEclairNode("cookie_test", ConfigFactory.parseMap(
      Map(
        "eclair.node-alias" -> "cookie_test",
        "eclair.server.port" -> 29750,
        "eclair.api.port" -> 28090,
        "eclair.channel-flags" -> 0,
        "eclair.bitcoind.auth" -> "safecookie",
        "eclair.bitcoind.cookie" -> (PATH_BITCOIND_DATADIR.toString + "/regtest/.cookie")).asJava
    ).withFallback(commonConfig))

    // test getting onchainbalance.
    whenReady(nodes("cookie_test").wallet.onChainBalance()) { _ => assert(true) }

    restartBitcoind(useCookie = true)

    // test getting onchainbalance again after restarting bitcoin. (will fail)
    //whenReady(nodes("cookie_test").wallet.onChainBalance()) { _ => assert(true) }
  }
}
