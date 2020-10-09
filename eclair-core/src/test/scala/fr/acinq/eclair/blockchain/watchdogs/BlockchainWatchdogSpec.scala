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

import akka.actor.testkit.typed.scaladsl.{LogCapturing, LoggingTestKit, ScalaTestWithActorTestKit}
import akka.actor.typed.eventstream.EventStream
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.blockchain.CurrentBlockCount
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class BlockchainWatchdogSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike with LogCapturing {

  test("fetch block headers from DNS on mainnet") {
    val watchdog = testKit.spawn(BlockchainWatchdog(Block.LivenetGenesisBlock.hash, 10, 1 second))
    LoggingTestKit.warn("bitcoinheaders.net: we are 9 blocks late: we may be eclipsed from the bitcoin network").expect {
      system.eventStream ! EventStream.Publish(CurrentBlockCount(630561))
    }
    testKit.stop(watchdog)
  }

  test("fetch block headers from blockstream.info on testnet") {
    val watchdog = testKit.spawn(BlockchainWatchdog(Block.TestnetGenesisBlock.hash, 16, 1 second))
    LoggingTestKit.warn("blockstream.info: we are 15 blocks late: we may be eclipsed from the bitcoin network").expect {
      system.eventStream ! EventStream.Publish(CurrentBlockCount(500000))
    }
    testKit.stop(watchdog)
  }

}
