/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair.blockchain.watchdogs

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.eventstream.EventStream
import com.typesafe.config.ConfigFactory
import com.ashbornelabs.bitcoin.xbt.scalacompat.Block
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog.{DangerousBlocksSkew, WrappedCurrentBlockHeight}
import fr.acinq.eclair.{BlockHeight, TestConstants}
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class BlockchainWatchdogSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {

  test("external watchdog sources are disabled by default") {
    val sources = ConfigFactory.load("application").getStringList("eclair.blockchain-watchdog.sources")
    assert(sources.isEmpty)
  }

  test("ignore Bitcoin-only watchdog sources in the XBT runtime") {
    val nodeParams = TestConstants.Alice.nodeParams.copy(
      chainHash = Block.LivenetGenesisBlock.hash,
      blockchainWatchdogSources = Seq("bitcoinheaders.net", "blockcypher.com", "blockstream.info", "mempool.space")
    )
    val eventListener = TestProbe[DangerousBlocksSkew]()
    system.eventStream ! EventStream.Subscribe(eventListener.ref)
    val watchdog = testKit.spawn(BlockchainWatchdog(nodeParams, 1.second))

    watchdog ! WrappedCurrentBlockHeight(BlockHeight(630561))

    eventListener.expectNoMessage(200.millis)
    testKit.stop(watchdog)
  }
}
