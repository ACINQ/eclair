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
import fr.acinq.bitcoin.scalacompat.Block
import fr.acinq.bitcoin.BlockHeader
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog.{BlockHeaderAt, LatestHeaders}
import fr.acinq.eclair.blockchain.watchdogs.HeadersOverDns.CheckLatestHeaders
import fr.acinq.eclair.{BlockHeight, TestTags}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.HexStringSyntax

import scala.concurrent.duration.DurationInt

class HeadersOverDnsSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {

  test("fetch latest block headers", TestTags.ExternalApi) {
    val headersOverDns = testKit.spawn(HeadersOverDns(Block.LivenetGenesisBlock.hash, BlockHeight(630450)))
    val sender = testKit.createTestProbe[LatestHeaders]()
    headersOverDns ! CheckLatestHeaders(sender.ref)
    val expectedHeaders = Set(
      BlockHeaderAt(BlockHeight(630450), BlockHeader.read(hex"0000802069ed618b1e0f87ce804e2624a34f507c13322c8f28b3010000000000000000000e370896b5a57abf5426bc51e82a3fcb626a5af32c9eb8147fe9c513efbb1701235abe5e397a111728cf439c".toArray)),
      BlockHeaderAt(BlockHeight(630451), BlockHeader.read(hex"00e0ff3f45367792598312e4cdbbf1caaeceafe38aef17eed8340d00000000000000000047e447403a779461d013581137f66425c609fac6ed426bdba4f83a35f00786f3db5cbe5e397a11173e155226".toArray)),
      BlockHeaderAt(BlockHeight(630452), BlockHeader.read(hex"000000200fce94e6ace8c26d3e9bb1fc7e9b85dc9c94ad51d8b00f000000000000000000ca0a5ba5b2cdba07b8926809e4909278c392587e7c8362dd6bd2a6669affff32715ebe5e397a1117efc39f19".toArray)),
      BlockHeaderAt(BlockHeight(630453), BlockHeader.read(hex"00000020db608b10a44b0b60b556f40775edcfb29d5f39eeeb7f0b000000000000000000312b2dece5824b0cb3153a8ef7a06af70ae68fc2e45a694936cbbd609c747aa56762be5e397a1117fba42eac".toArray)),
      BlockHeaderAt(BlockHeight(630454), BlockHeader.read(hex"0000c0200951bd9340565493bc25101f4f7cbad1d094614ea8010e000000000000000000fd0377e4c6753830fe345657921aface6159e41a57c09be4a6658ca9e2704ff0c665be5e397a11172d2ee501".toArray)),
      BlockHeaderAt(BlockHeight(630455), BlockHeader.read(hex"000000204119a86146d81a66ac2670f5f36e0508d1312385f75d0200000000000000000075871a6f838207ac1f55d201d5f4a306cb37e52b2be8006d7ac4cc3114ac6e9aba6abe5e397a11173085c79c".toArray)),
      BlockHeaderAt(BlockHeight(630456), BlockHeader.read(hex"00000020b87220b7dd743fe4f1ee09d4fd4fd1d70608544ffaf0010000000000000000009de701d6bd397e1be047ccc5fe30ff92f6d67bd71c526f9f67c1413c8a4c65cc5070be5e397a111706c44dd8".toArray)),
      BlockHeaderAt(BlockHeight(630457), BlockHeader.read(hex"0000002028a1a12e1de0d77e1f58e1d90de97dd52aebcd0a8b570500000000000000000093669292cc29488c77d159fd5f153b66d689c0edaa284f556a587dc77585f4422c76be5e397a11171c47271b".toArray)),
      BlockHeaderAt(BlockHeight(630458), BlockHeader.read(hex"00000020ff4dc9eaac1ba97f16bd7287a2a05ecb4f2b013cfd640e0000000000000000000a8ada0e68ebb19b68be274938df178a68de89031ff5bfc1c7612e82a83830b18780be5e397a11178e24035e".toArray)),
      BlockHeaderAt(BlockHeight(630459), BlockHeader.read(hex"0000002030b5d50005fb4b56c1dbe12bc70e951779ab0d07e4ad02000000000000000000a73e505c3943437b0fc732ee69bfda59b48c58709b4543c7b62af5134200684deb8fbe5e397a111745743f34".toArray)),
    )
    sender.expectMessage(LatestHeaders(BlockHeight(630450), expectedHeaders, HeadersOverDns.Source))
  }

  test("fetch future block headers", TestTags.ExternalApi) {
    val headersOverDns = testKit.spawn(HeadersOverDns(Block.LivenetGenesisBlock.hash, BlockHeight(60000000)))
    val sender = testKit.createTestProbe[LatestHeaders]()
    headersOverDns ! CheckLatestHeaders(sender.ref)
    sender.expectMessage(LatestHeaders(BlockHeight(60000000), Set.empty, HeadersOverDns.Source))
  }

  test("ignore testnet requests", TestTags.ExternalApi) {
    val headersOverDns = testKit.spawn(HeadersOverDns(Block.TestnetGenesisBlock.hash, BlockHeight(500000)))
    val sender = testKit.createTestProbe[LatestHeaders]()
    headersOverDns ! CheckLatestHeaders(sender.ref)
    sender.expectNoMessage(1 second)
  }

}
