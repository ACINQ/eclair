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
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog.{DangerousBlocksSkew, WrappedCurrentBlockCount}
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class BlockchainWatchdogSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {

  test("fetch block headers from four sources on mainnet") {
    val eventListener = TestProbe[DangerousBlocksSkew]()
    system.eventStream ! EventStream.Subscribe(eventListener.ref)
    val watchdog = testKit.spawn(BlockchainWatchdog(Block.LivenetGenesisBlock.hash, 1 second))
    watchdog ! WrappedCurrentBlockCount(630561)

    val events = Seq(
      eventListener.expectMessageType[DangerousBlocksSkew],
      eventListener.expectMessageType[DangerousBlocksSkew],
      eventListener.expectMessageType[DangerousBlocksSkew],
      eventListener.expectMessageType[DangerousBlocksSkew]
    )
    eventListener.expectNoMessage(100 millis)
    assert(events.map(_.recentHeaders.source).toSet === Set("bitcoinheaders.net", "blockcypher.com", "blockstream.info", "mempool.space"))
    testKit.stop(watchdog)
  }

  test("fetch block headers from three sources on testnet") {
    val eventListener = TestProbe[DangerousBlocksSkew]()
    system.eventStream ! EventStream.Subscribe(eventListener.ref)
    val watchdog = testKit.spawn(BlockchainWatchdog(Block.TestnetGenesisBlock.hash, 1 second))
    watchdog ! WrappedCurrentBlockCount(500000)

    val events = Seq(
      eventListener.expectMessageType[DangerousBlocksSkew],
      eventListener.expectMessageType[DangerousBlocksSkew],
      eventListener.expectMessageType[DangerousBlocksSkew]
    )
    eventListener.expectNoMessage(100 millis)
    assert(events.map(_.recentHeaders.source).toSet === Set("blockcypher.com", "blockstream.info", "mempool.space"))
    testKit.stop(watchdog)
  }

}
