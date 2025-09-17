/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.crypto.keymanager

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto}
import fr.acinq.eclair.Setup.Seeds
import fr.acinq.eclair.channel.ChannelConfig
import fr.acinq.eclair.{NodeParams, TestUtils}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

import java.io.File
import java.nio.file.Files


class LocalNodeKeyManagerSpec extends AnyFunSuite {
  test("generate the same node id from the same seed") {
    // if this test breaks it means that we will generate a different node id  from
    // the same seed, which could be a problem during an upgrade
    val seed = hex"17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501"
    val nodeKeyManager = new LocalNodeKeyManager(seed, Block.Testnet3GenesisBlock.hash)
    assert(nodeKeyManager.nodeId == PublicKey(hex"02a051267759c3a149e3e72372f4e0c4054ba597ebfd0eda78a2273023667205ee"))
  }

  test("generate different node ids from the same seed on different chains") {
    val seed = hex"17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501"
    val nodeKeyManager1 = LocalNodeKeyManager(seed, Block.Testnet3GenesisBlock.hash)
    val nodeKeyManager2 = LocalNodeKeyManager(seed, Block.LivenetGenesisBlock.hash)
    assert(nodeKeyManager1.nodeId != nodeKeyManager2.nodeId)
    val channelKeyManager = LocalChannelKeyManager(seed, Block.Testnet3GenesisBlock.hash)
    val channelKeys1 = channelKeyManager.channelKeys(ChannelConfig.standard, channelKeyManager.newFundingKeyPath(isChannelOpener = true))
    val channelKeys2 = channelKeyManager.channelKeys(ChannelConfig.standard, channelKeyManager.newFundingKeyPath(isChannelOpener = true))
    assert(channelKeys1.fundingKey(fundingTxIndex = 0) != channelKeys2.fundingKey(fundingTxIndex = 0))
    assert(channelKeys1.fundingKey(fundingTxIndex = 42) != channelKeys2.fundingKey(fundingTxIndex = 42))
    assert(channelKeys1.commitmentPoint(1) != channelKeys2.commitmentPoint(1))
  }

  test("keep the same node seed after a migration from the old seed.dat file") {
    val seed = hex"17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501"
    val seedDatFile = TestUtils.createSeedFile("seed.dat", seed.toArray)

    val Seeds(_, _) = NodeParams.getSeeds(seedDatFile.getParentFile)

    val nodeSeedDatFile = new File(seedDatFile.getParentFile, "node_seed.dat")
    assert(nodeSeedDatFile.exists())

    val nodeSeedContent = ByteVector(Files.readAllBytes(nodeSeedDatFile.toPath))
    assert(seed == nodeSeedContent)
  }

  test("generate a signature from a digest") {
    val seed = hex"deadbeef"
    val testKeyManager = new LocalNodeKeyManager(seed, Block.RegtestGenesisBlock.hash)
    val digest = ByteVector32(hex"d7914fe546b684688bb95f4f888a92dfc680603a75f23eb823658031fff766d9") // sha256(sha256("hello"))

    val (signature, recid) = testKeyManager.signDigest(digest)
    val recoveredPubkey = Crypto.recoverPublicKey(signature, digest, recid)
    assert(recoveredPubkey == testKeyManager.nodeId)
    assert(Crypto.verifySignature(digest, signature, testKeyManager.nodeId))
  }
}
