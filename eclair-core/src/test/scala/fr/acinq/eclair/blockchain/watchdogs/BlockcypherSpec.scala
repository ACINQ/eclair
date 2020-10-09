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
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog.LatestHeaders
import fr.acinq.eclair.blockchain.watchdogs.Blockcypher.CheckLatestHeaders
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.HexStringSyntax

class BlockcypherSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {

  test("fetch old block header") {
    val blockcypher = testKit.spawn(Blockcypher(Block.LivenetGenesisBlock.hash, 1, 1))
    val sender = testKit.createTestProbe[LatestHeaders]()
    blockcypher ! CheckLatestHeaders(sender.ref)
    val expectedHeaders = Set((1, hex"010000006fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000982051fd1e4ba744bbbe680e1fee14677ba1a3c3540bf7b1cdb606e857233e0e61bc6649ffff001d01e36299"))
    val headers = sender.expectMessageType[LatestHeaders]
    assert(headers.currentBlockCount === 1)
    assert(headers.source === Blockcypher.Source)
    assert(headers.blockHeaders.map(h => (h.blockCount, BlockHeader.write(h.blockHeader))) === expectedHeaders)
  }

  test("fetch old block headers") {
    val blockcypher = testKit.spawn(Blockcypher(Block.LivenetGenesisBlock.hash, 1, 3))
    val sender = testKit.createTestProbe[LatestHeaders]()
    blockcypher ! CheckLatestHeaders(sender.ref)
    val expectedHeaders = Set(
      (1, hex"010000006fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000982051fd1e4ba744bbbe680e1fee14677ba1a3c3540bf7b1cdb606e857233e0e61bc6649ffff001d01e36299"),
      (2, hex"010000004860eb18bf1b1620e37e9490fc8a427514416fd75159ab86688e9a8300000000d5fdcc541e25de1c7a5addedf24858b8bb665c9f36ef744ee42c316022c90f9bb0bc6649ffff001d08d2bd61"),
      (3, hex"01000000bddd99ccfda39da1b108ce1a5d70038d0a967bacb68b6b63065f626a0000000044f672226090d85db9a9f2fbfe5f0f9609b387af7be5b7fbb7a1767c831c9e995dbe6649ffff001d05e0ed6d"),
    )
    val headers = sender.expectMessageType[LatestHeaders]
    assert(headers.currentBlockCount === 1)
    assert(headers.source === Blockcypher.Source)
    assert(headers.blockHeaders.map(h => (h.blockCount, BlockHeader.write(h.blockHeader))) === expectedHeaders)
  }

  test("fetch some block headers") {
    val blockcypher = testKit.spawn(Blockcypher(Block.LivenetGenesisBlock.hash, 630450, 13))
    val sender = testKit.createTestProbe[LatestHeaders]()
    blockcypher ! CheckLatestHeaders(sender.ref)
    val expectedHeaders = Set(
      (630450, hex"0000802069ed618b1e0f87ce804e2624a34f507c13322c8f28b3010000000000000000000e370896b5a57abf5426bc51e82a3fcb626a5af32c9eb8147fe9c513efbb1701235abe5e397a111728cf439c"),
      (630451, hex"00e0ff3f45367792598312e4cdbbf1caaeceafe38aef17eed8340d00000000000000000047e447403a779461d013581137f66425c609fac6ed426bdba4f83a35f00786f3db5cbe5e397a11173e155226"),
      (630452, hex"000000200fce94e6ace8c26d3e9bb1fc7e9b85dc9c94ad51d8b00f000000000000000000ca0a5ba5b2cdba07b8926809e4909278c392587e7c8362dd6bd2a6669affff32715ebe5e397a1117efc39f19"),
      (630453, hex"00000020db608b10a44b0b60b556f40775edcfb29d5f39eeeb7f0b000000000000000000312b2dece5824b0cb3153a8ef7a06af70ae68fc2e45a694936cbbd609c747aa56762be5e397a1117fba42eac"),
      (630454, hex"0000c0200951bd9340565493bc25101f4f7cbad1d094614ea8010e000000000000000000fd0377e4c6753830fe345657921aface6159e41a57c09be4a6658ca9e2704ff0c665be5e397a11172d2ee501"),
      (630455, hex"000000204119a86146d81a66ac2670f5f36e0508d1312385f75d0200000000000000000075871a6f838207ac1f55d201d5f4a306cb37e52b2be8006d7ac4cc3114ac6e9aba6abe5e397a11173085c79c"),
      (630456, hex"00000020b87220b7dd743fe4f1ee09d4fd4fd1d70608544ffaf0010000000000000000009de701d6bd397e1be047ccc5fe30ff92f6d67bd71c526f9f67c1413c8a4c65cc5070be5e397a111706c44dd8"),
      (630457, hex"0000002028a1a12e1de0d77e1f58e1d90de97dd52aebcd0a8b570500000000000000000093669292cc29488c77d159fd5f153b66d689c0edaa284f556a587dc77585f4422c76be5e397a11171c47271b"),
      (630458, hex"00000020ff4dc9eaac1ba97f16bd7287a2a05ecb4f2b013cfd640e0000000000000000000a8ada0e68ebb19b68be274938df178a68de89031ff5bfc1c7612e82a83830b18780be5e397a11178e24035e"),
      (630459, hex"0000002030b5d50005fb4b56c1dbe12bc70e951779ab0d07e4ad02000000000000000000a73e505c3943437b0fc732ee69bfda59b48c58709b4543c7b62af5134200684deb8fbe5e397a111745743f34"),
      (630460, hex"000040206e390ff12d1568de2700470ff3417e3ef153c2960df4030000000000000000008638741f5e6870335fb5a129fa22e7dcb295ea8e1daa06fac8a1230c75143c8eee8fbe5e397a11170d01e68e"),
      (630461, hex"0000402090c95977a62b4f32bec3ed16642dbd33512df20d986400000000000000000000a3ab107298a7191e3aa0d49168db2d6798ec173d19522bec32c393cb31762468eb92be5e397a111719708a4b"),
      (630462, hex"00e0ff2705182872bc51f1a9896a72aad2ed3c0404386bd2071c09000000000000000000a2af76bc9352b01b6510dd1520542f2898fe3af97ba7a804ee496a00aa27b38f0e93be5e397a1117eb36157a")
    )
    val headers = sender.expectMessageType[LatestHeaders]
    assert(headers.currentBlockCount === 630450)
    assert(headers.source === Blockcypher.Source)
    assert(headers.blockHeaders.map(h => (h.blockCount, BlockHeader.write(h.blockHeader))) === expectedHeaders)
  }

  test("fetch future block headers") {
    val blockcypher = testKit.spawn(Blockcypher(Block.LivenetGenesisBlock.hash, 60000000, 2))
    val sender = testKit.createTestProbe[LatestHeaders]()
    blockcypher ! CheckLatestHeaders(sender.ref)
    sender.expectMessage(LatestHeaders(60000000, Set.empty, Blockcypher.Source))
  }

}
