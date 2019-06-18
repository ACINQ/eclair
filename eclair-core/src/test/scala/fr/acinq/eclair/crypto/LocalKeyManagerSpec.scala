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

package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.{Block, ByteVector32, DeterministicWallet}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.{Block, Script}
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.channel.Channel.{fundingKeyPath, keyPath}
import fr.acinq.eclair.channel.LocalParams
import fr.acinq.eclair.transactions.Scripts
import org.scalatest.FunSuite
import scodec.bits._

class LocalKeyManagerSpec extends FunSuite {
  test("generate the same node id from the same seed") {
    // if this test breaks it means that we will generate a different node id  from
    // the same seed, which could be a problem during an upgrade
    val seed = hex"17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501"
    val keyManager = new LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)
    assert(keyManager.nodeId == PublicKey(hex"02a051267759c3a149e3e72372f4e0c4054ba597ebfd0eda78a2273023667205ee"))
  }

  test("generate the same secrets from the same seed") {
    // data was generated with eclair 0.3
    val seed = hex"17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501"
    val keyManager = new LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)
    assert(keyManager.nodeId == PublicKey(hex"02a051267759c3a149e3e72372f4e0c4054ba597ebfd0eda78a2273023667205ee"))
    val keyPath = KeyPath("m/1'/2'/3'/4'")
    assert(keyManager.commitmentSecret(keyPath, 0L).value == ByteVector32.fromValidHex("fa7a8c2fc62642f7a9a19ea0bfad14d39a430f3c9899c185dcecc61c8077891e"))
    assert(keyManager.commitmentSecret(keyPath, 1L).value == ByteVector32.fromValidHex("3e82338d3e487c760ee10448127613d196b040e86ce90d2d437db6425bb7301c"))
    assert(keyManager.commitmentSecret(keyPath, 2L).value == ByteVector32.fromValidHex("102357f7a9b2d0b9147f645c98aa156d3278ddb4745caf0631773dd663e76e6f"))
    assert(keyManager.commitmentPoint(keyPath, 0L).value == hex"0x0237dd5a0ea26ed84ed1249d46cc715679b542939d6943b42232e043825cde3944")
    assert(DeterministicWallet.encode(keyManager.delayedPaymentPoint(keyPath), DeterministicWallet.tpub) == "tpubDMBn7xW1g1Gsok5eThkJAKJnB3ZFqZQnvsdWv8VvM3RjZkqVPZZpjPDAAmbyDHnZPdAZY8EnFBh1ibTBtiuDqb8t9wRcAZiFihma3yYRG1f")
    assert(DeterministicWallet.encode(keyManager.htlcPoint(keyPath), DeterministicWallet.tpub) == "tpubDMBn7xW1g1GsqpsqaVNB1ehpjktQUX44Dycy7fJ6thp774XGzNeWFmQf5L6dVChHREgkoc8BYc2caHqwc2mZzTYCwoxsvrpchBSujsPCvGH")
    assert(DeterministicWallet.encode(keyManager.paymentPoint(keyPath), DeterministicWallet.tpub) == "tpubDMBn7xW1g1Gsme9jTAEJwTvizDJtJEgE3jc9vkDqQ9azuh9Es2aM6GsioFiouwdvWPJoNw2zavCkVTMta6UJN6BWR5cMZQsSHvsFyQNfGzv")
    assert(DeterministicWallet.encode(keyManager.revocationPoint(keyPath), DeterministicWallet.tpub) == "tpubDMBn7xW1g1GsizhaZ7M4co6sBtUDhRUKgUUPWRv3WfLTpTGYrSjATJy6ZVSoYFCKRnaBop5dFig3Ham1P145NQAKuUgPUbujLAooL7F2vy6")
  }

  test("generate different node ids from the same seed on different chains") {
    val seed = hex"17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501"
    val keyManager1 = new LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)
    val keyManager2 = new LocalKeyManager(seed, Block.LivenetGenesisBlock.hash)
    assert(keyManager1.nodeId != keyManager2.nodeId)
    val keyPath = KeyPath(1L :: Nil)
    assert(keyManager1.fundingPublicKey(keyPath) != keyManager2.fundingPublicKey(keyPath))
    assert(keyManager1.commitmentPoint(keyPath, 1) != keyManager2.commitmentPoint(keyPath, 1))
  }

  /**
    * TESTNET funder funding public key from extended private key: 039abec2238484d9a0680a8ffce6ad920930285fea1cbc30b8f53fad862f6e9157
    * TESTNET funder payment point from extended private key: 03edbfb2cd187e5738f3d4bb5b4d14dad16ee50e3deb1043155d6832d7816aa5f7
    * TESTNET funder revocation point from extended private key: 023c73ac122df051f8cabbefd9d1a06845aab01f0e4fed85475530ff99b73a4bfd
    * TESTNET funder delayed payment point from extended private key: 02168b4c51ba573a7cf2158da5338c6e852f5a62960ae2561dcc6f9e5ed6932ba5
    * TESTNET funder htlc point from extended private key: 03d82c30596db587d9e32ebcc68e451727c841ed27ffed441c7eb05e00bb16305d
    *
    * MAINNET funder funding public key from extended private key: 0353b7dfdb0cbae349146795bd6406ff6ac5cd93bfb31bbdfc5df01b0d4da171b4
    * MAINNET funder payment point from extended private key: 0254036505ad9aa8d8e03ed3727825eaa72ce49f5ff9f2fd286e6d20a2b88caa12
    *
    * TESTNET fundee funding public key from extended private key #34273 #0: 02bfe2d6e9ec07b68f588c1bcbf6afdb1e1e068ab11e82b3648eb5032007e9bb48
    * TESTNET fundee htlc public point from extended private key #34273 #0: 037223be02f02a117c9ad3ee243c1664d5763a57911317257536e6f355acb32418
    * TESTNET fundee payment point from extended private key #34273 #0: 02ce4468352b0b722e7e066a3603ed9081dcc6a93137b45e1d9acbe35caad08c2e
    *
    * MAINNET fundee funding public key from extended private key #34273 #0: 03b8c35b3171dcec2d1e4a2b08244b4b4ce284bd8a7dbef6da467cb6db5abdc30d
    * MAINNET fundee htlc public point from extended private key #34273 #0: 0258173e7db0f833691e72b259a9bf5349d9548dd9dcfccf4556a238d9d76193bf
    *
    * MAINNET fundee revocation point from extended private key #34273 #500: 0213718574b4169ef093286fbc6459c81c7a672cfae62391e405aa04f82fa318e2
    * MAINNET fundee payment point from extended private key #34273 #500: 026a5d2792fb7fec674e75e34a981c74bb07b7439aca8db72d800a5b7f106648be
    * MAINNET fundee delayed payment point from extended private key #34273 #500: 03b289f71d00e6b363adc08cdd7eec113441ab1c6c8208621d71e70789874f48d0
    * MAINNET fundee htlc point from extended private key #34273 #500: 039fd510225bd7c70a5f8f1661247c19cab9942088d7c868bad1c47bf0fad9b73d
    * MAINNET fundee shachain public point from extended private key #34273 #500: 0200742b2176552d4e29d01455f7de2c16094c77ba644310d5d58385306c09ddc9
    *
    */

  test("test vectors derivation paths (funder TESTNET)") {

    val inputOutpoint = hex"1d12dcab62f3d509db16b8dcb69782ea6358a7060b579675561c4fc2e3294f41"
    val seed = hex"0101010102020202AABBCCDD030303030404040405050505060606060707070701"
    val keyManager = new LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)

    val funderChannelKeyPath = LocalKeyManager.makeChannelKeyPathFunder(inputOutpoint)

    // TESTNET funder funding public key
    assert(keyManager.fundingPublicKey(funderChannelKeyPath).publicKey.value === hex"039abec2238484d9a0680a8ffce6ad920930285fea1cbc30b8f53fad862f6e9157")
    // TESTNET funder payment point
    assert(keyManager.paymentPoint(funderChannelKeyPath).publicKey.value === hex"03edbfb2cd187e5738f3d4bb5b4d14dad16ee50e3deb1043155d6832d7816aa5f7")
    // TESTNET funder revocation point
    assert(keyManager.revocationPoint(funderChannelKeyPath).publicKey.value === hex"023c73ac122df051f8cabbefd9d1a06845aab01f0e4fed85475530ff99b73a4bfd")
    // TESTNET funder delayed payment point
    assert(keyManager.delayedPaymentPoint(funderChannelKeyPath).publicKey.value === hex"02168b4c51ba573a7cf2158da5338c6e852f5a62960ae2561dcc6f9e5ed6932ba5")
    // TESTNET funder htlc point
    assert(keyManager.htlcPoint(funderChannelKeyPath).publicKey.value === hex"03d82c30596db587d9e32ebcc68e451727c841ed27ffed441c7eb05e00bb16305d")
  }

  test("test vectors derivation paths (funder MAINNET)") {

    val inputOutpoint = hex"1d12dcab62f3d509db16b8dcb69782ea6358a7060b579675561c4fc2e3294f41"
    val seed = hex"0101010102020202AABBCCDD030303030404040405050505060606060707070701"
    val keyManager = new LocalKeyManager(seed, Block.LivenetGenesisBlock.hash)

    val funderChannelKeyPath = LocalKeyManager.makeChannelKeyPathFunder(inputOutpoint)

    // MAINNET funder funding public key from extended private key
    assert(keyManager.fundingPublicKey(funderChannelKeyPath).publicKey.value === hex"0353b7dfdb0cbae349146795bd6406ff6ac5cd93bfb31bbdfc5df01b0d4da171b4")
    // MAINNET funder payment point from extended private key
    assert(keyManager.paymentPoint(funderChannelKeyPath).publicKey.value === hex"0254036505ad9aa8d8e03ed3727825eaa72ce49f5ff9f2fd286e6d20a2b88caa12")
  }

  test("test vectors derivation paths (fundee TESTNET)") {

    val remoteNodePubkey = PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f")
    val seed = hex"0101010102020202AABBCCDD030303030404040405050505060606060707070701"
    val keyManager = new LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)

    val fundeePubkeyKeyPath = LocalKeyManager.makeChannelKeyPathFundeePubkey(34273, 0)
    // TESTNET fundee funding public key from extended private key
    assert(keyManager.fundingPublicKey(fundeePubkeyKeyPath).publicKey.value === hex"02bfe2d6e9ec07b68f588c1bcbf6afdb1e1e068ab11e82b3648eb5032007e9bb48")

    val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(keyManager.fundingPublicKey(fundeePubkeyKeyPath).publicKey, remoteNodePubkey)))
    val fundeeChannelKeyPath = LocalKeyManager.makeChannelKeyPathFundee(fundingPubkeyScript)

    // TESTNET fundee htlc public point from extended private key
    assert(keyManager.htlcPoint(fundeeChannelKeyPath).publicKey.value === hex"037223be02f02a117c9ad3ee243c1664d5763a57911317257536e6f355acb32418")
    // TESTNET fundee htlc public point from extended private key
    assert(keyManager.paymentPoint(fundeeChannelKeyPath).publicKey.value === hex"02ce4468352b0b722e7e066a3603ed9081dcc6a93137b45e1d9acbe35caad08c2e")
  }

  test("test vectors derivation paths (fundee MAINNET)") {

    val remoteNodePubkey = PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f")
    val seed = hex"0101010102020202AABBCCDD030303030404040405050505060606060707070701"
    val keyManager = new LocalKeyManager(seed, Block.LivenetGenesisBlock.hash)

    val fundeePubkeyKeyPath = LocalKeyManager.makeChannelKeyPathFundeePubkey(34273, 0)
    // MAINNET fundee funding public key from extended private key
    assert(keyManager.fundingPublicKey(fundeePubkeyKeyPath).publicKey.value === hex"03b8c35b3171dcec2d1e4a2b08244b4b4ce284bd8a7dbef6da467cb6db5abdc30d")

    val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(keyManager.fundingPublicKey(fundeePubkeyKeyPath).publicKey, remoteNodePubkey)))
    val fundeeChannelKeyPath = LocalKeyManager.makeChannelKeyPathFundee(fundingPubkeyScript)

    // MAINNET fundee htlc public point from extended private key
    assert(keyManager.htlcPoint(fundeeChannelKeyPath).publicKey.value === hex"0258173e7db0f833691e72b259a9bf5349d9548dd9dcfccf4556a238d9d76193bf")

    // with different counter
    val fundeePubkeyKeyPath1 = LocalKeyManager.makeChannelKeyPathFundeePubkey(34273, 500)
    val fundingPubkeyScript1 = Script.write(Script.pay2wsh(Scripts.multiSig2of2(keyManager.fundingPublicKey(fundeePubkeyKeyPath1).publicKey, remoteNodePubkey)))
    val fundeeChannelKeyPath1 = LocalKeyManager.makeChannelKeyPathFundee(fundingPubkeyScript1)

    // MAINNET fundee revocation point
    assert(keyManager.revocationPoint(fundeeChannelKeyPath1).publicKey.value === hex"0213718574b4169ef093286fbc6459c81c7a672cfae62391e405aa04f82fa318e2")
    // MAINNET fundee payment point
    assert(keyManager.paymentPoint(fundeeChannelKeyPath1).publicKey.value === hex"026a5d2792fb7fec674e75e34a981c74bb07b7439aca8db72d800a5b7f106648be")
    // MAINNET fundee delayed payment point
    assert(keyManager.delayedPaymentPoint(fundeeChannelKeyPath1).publicKey.value === hex"03b289f71d00e6b363adc08cdd7eec113441ab1c6c8208621d71e70789874f48d0")
    // MAINNET fundee htlc point
    assert(keyManager.htlcPoint(fundeeChannelKeyPath1).publicKey.value === hex"039fd510225bd7c70a5f8f1661247c19cab9942088d7c868bad1c47bf0fad9b73d")
    // MAINNED fundee shaSeed point
    assert(keyManager.shaSeedPub(fundeeChannelKeyPath1).publicKey.value === hex"0200742b2176552d4e29d01455f7de2c16094c77ba644310d5d58385306c09ddc9")
  }

  test("use correct keypath to compute keys") {
    val seed = hex"0101010102020202AABBCCDD030303030404040405050505060606060707070701"
    val keyManager = new LocalKeyManager(seed, Block.LivenetGenesisBlock.hash)

    // FUNDER
    val funderParams = TestConstants.Alice.channelParams
    val funderKeyPath = funderParams.channelKeyPath.left.get

    assert(keyManager.fundingPublicKey(fundingKeyPath(funderParams)).publicKey == keyManager.fundingPublicKey(funderKeyPath).publicKey)
    assert(keyManager.revocationPoint(keyPath(funderParams)) == keyManager.revocationPoint(funderKeyPath))
    assert(keyManager.paymentPoint(keyPath(funderParams)).publicKey == keyManager.paymentPoint(funderKeyPath).publicKey)
    assert(keyManager.delayedPaymentPoint(keyPath(funderParams)).publicKey == keyManager.delayedPaymentPoint(funderKeyPath).publicKey)
    assert(keyManager.htlcPoint(keyPath(funderParams)).publicKey == keyManager.htlcPoint(funderKeyPath).publicKey)
    assert(keyManager.commitmentPoint(keyPath(funderParams), 0) == keyManager.commitmentPoint(funderKeyPath, 0))

    // FUNDEE
    val fundeeParams = TestConstants.Bob.channelParams
    val fundeeFundingKeyPath = fundeeParams.channelKeyPath.right.get.fundingKeyPath
    val fundeeKeyPath = fundeeParams.channelKeyPath.right.get.pointsKeyPath

    assert(keyManager.fundingPublicKey(fundingKeyPath(fundeeParams)).publicKey == keyManager.fundingPublicKey(fundeeFundingKeyPath).publicKey)
    assert(keyManager.revocationPoint(keyPath(fundeeParams)) == keyManager.revocationPoint(fundeeKeyPath))
    assert(keyManager.paymentPoint(keyPath(fundeeParams)).publicKey == keyManager.paymentPoint(fundeeKeyPath).publicKey)
    assert(keyManager.delayedPaymentPoint(keyPath(fundeeParams)).publicKey == keyManager.delayedPaymentPoint(fundeeKeyPath).publicKey)
    assert(keyManager.htlcPoint(keyPath(fundeeParams)).publicKey == keyManager.htlcPoint(fundeeKeyPath).publicKey)
    assert(keyManager.commitmentPoint(keyPath(fundeeParams), 0) == keyManager.commitmentPoint(fundeeKeyPath, 0))
  }

}