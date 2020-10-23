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

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog.LatestHeaders
import fr.acinq.eclair.blockchain.watchdogs.ExplorerApi.{BlockcypherExplorer, BlockstreamExplorer, CheckLatestHeaders, MempoolSpaceExplorer}
import org.scalatest.funsuite.AnyFunSuiteLike

class ExplorerApiSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {

  val explorers = Seq(BlockcypherExplorer(), BlockstreamExplorer(), MempoolSpaceExplorer())

  test("fetch latest block headers") {
    for (explorer <- explorers) {
      val api = testKit.spawn(ExplorerApi(Block.LivenetGenesisBlock.hash, 630450, explorer))
      val sender = testKit.createTestProbe[LatestHeaders]()
      api ! CheckLatestHeaders(sender.ref)
      val latestHeaders = sender.expectMessageType[LatestHeaders]
      assert(latestHeaders.currentBlockCount === 630450)
      assert(latestHeaders.blockHeaders.nonEmpty)
      assert(latestHeaders.blockHeaders.forall(_.blockCount > 630450))
      assert(latestHeaders.source === explorer.name)
    }
  }

  test("fetch future block headers") {
    for (explorer <- explorers) {
      val api = testKit.spawn(ExplorerApi(Block.LivenetGenesisBlock.hash, 60000000, explorer))
      val sender = testKit.createTestProbe[LatestHeaders]()
      api ! CheckLatestHeaders(sender.ref)
      sender.expectMessage(LatestHeaders(60000000, Set.empty, explorer.name))
    }
  }

}
