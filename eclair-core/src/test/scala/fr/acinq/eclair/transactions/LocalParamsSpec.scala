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
package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.{Block, ByteVector32, DeterministicWallet}
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.wire.ChannelCodecs
import org.scalatest.FunSuite
import scodec.bits.{BitVector, ByteVector}

class LocalParamsSpec extends FunSuite {
  test("read legacy data correctly") {
    val seed = ByteVector32(ByteVector.fill(32)(2))
    val keyManager = new LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)
    // read version 0 local params from data produced with an older version of eclair
    val localParams = ChannelCodecs.localParamsCodec.decode(BitVector.fromValidHex("0x03af0ed6052cf28d670665549bc86f4b721c9fdb309d40c58f5811f63966e005d00004e3fbd1afb75d37604f7de0755c7e3e01000000000000044c0000000008f0d18000000000000027100000000000000000009000648000000000008000")).require.value
    val channelKeyPath = localParams.channelKeyPath(keyManager)
    val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
    assert(channelKeyPath == DeterministicWallet.KeyPath("m/1677447599'/928855904'/1333649525/1551777281"))
    assert(fundingPubKey.toString == "ExtendedPublicKey(ByteVector(33 bytes, 0x02f8eea36e21551d30ce9329538c8652b5ecc1f8dc5b84ab52a4eb5e95759cdbc8),a1e0ccf8ac45652752ebfd578c3b7a6adcbdb693fbc0ff3f1d1797db4af9f8bd,7,m/46'/1'/1677447599'/928855904'/1333649525/1551777281/0',1132704962)")
  }
}
