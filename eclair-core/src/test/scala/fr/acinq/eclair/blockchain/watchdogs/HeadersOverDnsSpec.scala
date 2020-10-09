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
import fr.acinq.bitcoin.{Block, BlockHeader}
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog.{BlockHeaderAt, LatestHeaders}
import fr.acinq.eclair.blockchain.watchdogs.HeadersOverDns.CheckLatestHeaders
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.HexStringSyntax

import scala.concurrent.duration.DurationInt

class HeadersOverDnsSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {

  test("fetch genesis block header") {
    val headersOverDns = testKit.spawn(HeadersOverDns(Block.LivenetGenesisBlock.hash, 0, 1))
    val sender = testKit.createTestProbe[LatestHeaders]()
    headersOverDns ! CheckLatestHeaders(sender.ref)
    sender.expectMessage(LatestHeaders(0, Set(BlockHeaderAt(0, Block.LivenetGenesisBlock.header)), HeadersOverDns.Source))
  }

  test("fetch first 3 block headers") {
    val headersOverDns = testKit.spawn(HeadersOverDns(Block.LivenetGenesisBlock.hash, 0, 3))
    val sender = testKit.createTestProbe[LatestHeaders]()
    headersOverDns ! CheckLatestHeaders(sender.ref)
    val expectedHeaders = Set(
      BlockHeaderAt(0, Block.LivenetGenesisBlock.header),
      BlockHeaderAt(1, BlockHeader.read(hex"010000006fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000982051fd1e4ba744bbbe680e1fee14677ba1a3c3540bf7b1cdb606e857233e0e61bc6649ffff001d01e36299".toArray)),
      BlockHeaderAt(2, BlockHeader.read(hex"010000004860eb18bf1b1620e37e9490fc8a427514416fd75159ab86688e9a8300000000d5fdcc541e25de1c7a5addedf24858b8bb665c9f36ef744ee42c316022c90f9bb0bc6649ffff001d08d2bd61".toArray)),
    )
    sender.expectMessage(LatestHeaders(0, expectedHeaders, HeadersOverDns.Source))
  }

  test("fetch some block headers") {
    val headersOverDns = testKit.spawn(HeadersOverDns(Block.LivenetGenesisBlock.hash, 630450, 5))
    val sender = testKit.createTestProbe[LatestHeaders]()
    headersOverDns ! CheckLatestHeaders(sender.ref)
    val expectedHeaders = Set(
      BlockHeaderAt(630450, BlockHeader.read(hex"0000802069ed618b1e0f87ce804e2624a34f507c13322c8f28b3010000000000000000000e370896b5a57abf5426bc51e82a3fcb626a5af32c9eb8147fe9c513efbb1701235abe5e397a111728cf439c".toArray)),
      BlockHeaderAt(630451, BlockHeader.read(hex"00e0ff3f45367792598312e4cdbbf1caaeceafe38aef17eed8340d00000000000000000047e447403a779461d013581137f66425c609fac6ed426bdba4f83a35f00786f3db5cbe5e397a11173e155226".toArray)),
      BlockHeaderAt(630452, BlockHeader.read(hex"000000200fce94e6ace8c26d3e9bb1fc7e9b85dc9c94ad51d8b00f000000000000000000ca0a5ba5b2cdba07b8926809e4909278c392587e7c8362dd6bd2a6669affff32715ebe5e397a1117efc39f19".toArray)),
      BlockHeaderAt(630453, BlockHeader.read(hex"00000020db608b10a44b0b60b556f40775edcfb29d5f39eeeb7f0b000000000000000000312b2dece5824b0cb3153a8ef7a06af70ae68fc2e45a694936cbbd609c747aa56762be5e397a1117fba42eac".toArray)),
      BlockHeaderAt(630454, BlockHeader.read(hex"0000c0200951bd9340565493bc25101f4f7cbad1d094614ea8010e000000000000000000fd0377e4c6753830fe345657921aface6159e41a57c09be4a6658ca9e2704ff0c665be5e397a11172d2ee501".toArray)),
    )
    sender.expectMessage(LatestHeaders(630450, expectedHeaders, HeadersOverDns.Source))
  }

  test("fetch future block headers") {
    val headersOverDns = testKit.spawn(HeadersOverDns(Block.LivenetGenesisBlock.hash, 60000000, 2))
    val sender = testKit.createTestProbe[LatestHeaders]()
    headersOverDns ! CheckLatestHeaders(sender.ref)
    sender.expectMessage(LatestHeaders(60000000, Set.empty, HeadersOverDns.Source))
  }

  test("ignore testnet requests") {
    val headersOverDns = testKit.spawn(HeadersOverDns(Block.TestnetGenesisBlock.hash, 0, 1))
    val sender = testKit.createTestProbe[LatestHeaders]()
    headersOverDns ! CheckLatestHeaders(sender.ref)
    sender.expectNoMessage(1 second)
  }

}
