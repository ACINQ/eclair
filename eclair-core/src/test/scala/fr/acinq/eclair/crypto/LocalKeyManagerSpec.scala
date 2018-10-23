/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.{BinaryData, Block}
import org.scalatest.FunSuite


class LocalKeyManagerSpec extends FunSuite {
  test("generate the same node id from the same seed") {
    // if this test breaks it means that we will generate a different node id  from
    // the same seed, which could be a problem during an upgrade
    val seed = BinaryData("17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501")
    val keyManager = new LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)
    assert(keyManager.nodeId == PublicKey("02a051267759c3a149e3e72372f4e0c4054ba597ebfd0eda78a2273023667205ee"))
  }
  test("generate different node ids from the same seed on different chains") {
    val seed = BinaryData("17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501")
    val keyManager1 = new LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)
    val keyManager2 = new LocalKeyManager(seed, Block.LivenetGenesisBlock.hash)
    assert(keyManager1.nodeId != keyManager2.nodeId)
    val keyPath = KeyPath(1L :: Nil)
    assert(keyManager1.fundingPublicKey(keyPath) != keyManager2.fundingPublicKey(keyPath))
    assert(keyManager1.commitmentPoint(keyPath, 1) != keyManager2.commitmentPoint(keyPath, 1))
  }
}
