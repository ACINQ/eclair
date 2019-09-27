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

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.{Block, ByteVector32, DeterministicWallet}
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.channel.ChannelVersion
import org.scalatest.FunSuite
import scodec.bits._


class LocalKeyManagerSpec extends FunSuite {
  test("generate the same node id from the same seed") {
    // if this test breaks it means that we will generate a different node id  from
    // the same seed, which could be a problem during an upgrade
    val seed = hex"17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501"
    val keyManager = new LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)
    assert(keyManager.nodeId == PublicKey(hex"02a051267759c3a149e3e72372f4e0c4054ba597ebfd0eda78a2273023667205ee"))

    val keyManager1 = new LocalKeyManager(seed, Block.LivenetGenesisBlock.hash)
    assert(keyManager1.nodeId == PublicKey(hex"03c3fd5d31c1aba11fc9f4a386bef56f8dd613909dd0fb66ee75a91eda3c25267f"))
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
    assert(keyManager1.fundingPublicKey(keyPath, ChannelVersion.STANDARD) != keyManager2.fundingPublicKey(keyPath, ChannelVersion.STANDARD))
    assert(keyManager1.commitmentPoint(keyPath, 1) != keyManager2.commitmentPoint(keyPath, 1))
  }

  test("compute channel key path from funding keys") {
    // if this test fails it means that we don't generate the same channel key path from the same funding pubkey, which
    // will break existing channels !
    val pub = PrivateKey(ByteVector32.fromValidHex("01" * 32)).publicKey
    val keyPath = KeyManager.channelKeyPath(pub)
    assert(keyPath.toString() == "m/2041577608/1982247572/689197082'/1288840885")
  }

  test("compute funding key path") {
    assert(KeyManager.fundingKeyPath(hex"a4b14e2d66d79812b7d6bd9003081b7e", isFunder = true) == KeyManager.fundingKeyPath(Seq(0xa4b14e2d, 0x66d79812, 0xb7d6bd90, 0x03081b7e), isFunder = true))
    assert(KeyManager.fundingKeyPath(hex"a4b14e2d66d79812b7d6bd9003081b7e", isFunder = false) == KeyManager.fundingKeyPath(Seq(0xa4b14e2d, 0x66d79812, 0xb7d6bd90, 0x03081b7e), isFunder = false))
    assert(KeyManager.fundingKeyPath(hex"a4b14e2d66d79812b7d6bd9003081b7e", isFunder = true) != KeyManager.fundingKeyPath(Seq(0xa4b14e2d, 0x66d79812, 0xb7d6bd90, 0x03081b7e), isFunder = false))
  }

  test("test vectors (testnet, funder)") {
    val seed = ByteVector.fromValidHex("17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501")
    val keyManager = new LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)
    val fundingKeyPath = KeyManager.fundingKeyPath(hex"a4b14e2d66d79812b7d6bd9003081b7e", isFunder = true)
    val fundingPub = keyManager.fundingPublicKey(fundingKeyPath, ChannelVersion.STANDARD)

    val localParams = TestConstants.Alice.channelParams.copy(fundingKeyPath = fundingKeyPath)
    val channelKeyPath = keyManager.channelKeyPath(localParams, ChannelVersion.STANDARD)

    assert(fundingPub.publicKey == PrivateKey(hex"700135eae537bc897c3ec5df41f2253a315e2a5f693e5da530964ab1a515b3e9").publicKey)
    assert(keyManager.revocationPoint(channelKeyPath).publicKey == PrivateKey(hex"b6e3ee63be92a323ac973584ccab86e45dfa4ccce7f2beb66e0ba881b5618bb1").publicKey)
    assert(keyManager.paymentPoint(channelKeyPath).publicKey == PrivateKey(hex"77f0139e98dcd457e90905621b34920bf7be341c192f5895fd42d91366a87f32").publicKey)
    assert(keyManager.delayedPaymentPoint(channelKeyPath).publicKey == PrivateKey(hex"13a8030b6a00de83974f610eed95822ee0016190f17facfa0797f1a4eab8f868").publicKey)
    assert(keyManager.htlcPoint(channelKeyPath).publicKey == PrivateKey(hex"af45f868e08fe146d1566a5c11ce7dda40b68f0525c9ce825544983512f45f77").publicKey)
    assert(keyManager.commitmentSecret(channelKeyPath, 0).value == ShaChain.shaChainFromSeed(ByteVector32.fromValidHex("4b92c812a00932b6b57338b4696cd57769b2e37a80cfcb3940a8ccb0e3a1b0cb"), 0xFFFFFFFFFFFFL))
  }

  test("test vectors (testnet, fundee)") {
    val seed = ByteVector.fromValidHex("aeb3e9b5642cd4523e9e09164047f60adb413633549c3c6189192921311894d501")
    val keyManager = new LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)
    val fundingKeyPath = KeyManager.fundingKeyPath(hex"b4992888dde7aa436620464f1c0306de", isFunder = false)
    val fundingPub = keyManager.fundingPublicKey(fundingKeyPath, ChannelVersion.STANDARD)

    val localParams = TestConstants.Alice.channelParams.copy(fundingKeyPath = fundingKeyPath)
    val channelKeyPath = keyManager.channelKeyPath(localParams, ChannelVersion.STANDARD)

    assert(fundingPub.publicKey == PrivateKey(hex"461d9fb0e2a19645a6c1e8bd66b0356dc4fb98136f77bfc0e87c4cf309f47639").publicKey)
    assert(keyManager.revocationPoint(channelKeyPath).publicKey == PrivateKey(hex"740de06340235c33167091a3cfdc35a62ac7536db20fc8a8768fd11597dfd47a").publicKey)
    assert(keyManager.paymentPoint(channelKeyPath).publicKey == PrivateKey(hex"f854397def747a8fca889cdd5bed799124581b6c9b1d7a8518d3bc0c47db1cc5").publicKey)
    assert(keyManager.delayedPaymentPoint(channelKeyPath).publicKey == PrivateKey(hex"9c4b704688413286614d1d2e903f286d658ea2a6f28a98d479a30936ea1e0089").publicKey)
    assert(keyManager.htlcPoint(channelKeyPath).publicKey == PrivateKey(hex"fe9d336f0a1a1c77f8d9b2aa5ab65ea5c5ca91473b18180639da8cef057885ee").publicKey)
    assert(keyManager.commitmentSecret(channelKeyPath, 0).value == ShaChain.shaChainFromSeed(ByteVector32.fromValidHex("ef9ad6112f14c3fd5452ab63d84cc827c3348f7d8b2d8faf5921da374ffb231a"), 0xFFFFFFFFFFFFL))
  }

  test("test vectors (mainnet, funder)") {
    val seed = ByteVector.fromValidHex("d8d5431487c2b19ee6486aad6c3bdfb99d10b727bade7fa848e2ab7901c15bff01")
    val keyManager = new LocalKeyManager(seed, Block.LivenetGenesisBlock.hash)
    val fundingKeyPath = KeyManager.fundingKeyPath(hex"59917d8664c053c799584cd1f57c3131", isFunder = true)
    val fundingPub = keyManager.fundingPublicKey(fundingKeyPath, ChannelVersion.STANDARD)

    val localParams = TestConstants.Alice.channelParams.copy(fundingKeyPath = fundingKeyPath)
    val channelKeyPath = keyManager.channelKeyPath(localParams, ChannelVersion.STANDARD)

    assert(fundingPub.publicKey == PrivateKey(hex"ee31ad0e72d86be1914a7e4062c3ebf9986ee85f01b0a7e30f2149976afbc5fe").publicKey)
    assert(keyManager.revocationPoint(channelKeyPath).publicKey == PrivateKey(hex"5720de202be655396e831f3004f3769e2aa5ca46b21c3bd415b1d182982d5e6f").publicKey)
    assert(keyManager.paymentPoint(channelKeyPath).publicKey == PrivateKey(hex"9c0658e7a9e88035510c1fe732e5d36848f540eff2f62a695ff9d39b19758a72").publicKey)
    assert(keyManager.delayedPaymentPoint(channelKeyPath).publicKey == PrivateKey(hex"1f598b3ba0c8d84d75494495fbb1bb69626f30f65a2a7b9cc3cc4d22f4565ac6").publicKey)
    assert(keyManager.htlcPoint(channelKeyPath).publicKey == PrivateKey(hex"b6f7ccd711dece384f5b42b2285370d18fa8d9b968d45f6ccd6b107ca733571d").publicKey)
    assert(keyManager.commitmentSecret(channelKeyPath, 0).value == ShaChain.shaChainFromSeed(ByteVector32.fromValidHex("27799a22d2e251d14e2ea7ebcce7b3812a164ecf3c31d40434c9033cf083f9cc"), 0xFFFFFFFFFFFFL))
  }

  test("test vectors (mainnet, fundee)") {
    val seed = ByteVector.fromValidHex("4b809dd593b36131c454d60c2f7bdfd49d12ec455e5b657c47a9ca0f5dfc5eef01")
    val keyManager = new LocalKeyManager(seed, Block.LivenetGenesisBlock.hash)
    val fundingKeyPath = KeyManager.fundingKeyPath(hex"91e7e7caf9fba829b17b837e2dc2fab1", isFunder = false)
    val fundingPub = keyManager.fundingPublicKey(fundingKeyPath, ChannelVersion.STANDARD)

    val localParams = TestConstants.Alice.channelParams.copy(fundingKeyPath = fundingKeyPath)
    val channelKeyPath = keyManager.channelKeyPath(localParams, ChannelVersion.STANDARD)

    assert(fundingPub.publicKey == PrivateKey(hex"bcb1f57575eb3e53d994360d4c182c5a5dad0273b44db8e3ce576b8f472aba74").publicKey)
    assert(keyManager.revocationPoint(channelKeyPath).publicKey == PrivateKey(hex"865041819358e684e671d2ce2b2af0bc8740ad110dceac8aa3af03d894a99fea").publicKey)
    assert(keyManager.paymentPoint(channelKeyPath).publicKey == PrivateKey(hex"65c0eba027d200c581dc6a72c43859d5ccff75a127467bbcc408e17b8b6be505").publicKey)
    assert(keyManager.delayedPaymentPoint(channelKeyPath).publicKey == PrivateKey(hex"e16dfa8c303ed4b43019ce387a8d3cef061188b2d88c83256a5b54700e66db12").publicKey)
    assert(keyManager.htlcPoint(channelKeyPath).publicKey == PrivateKey(hex"f488dc465063b2201841c7b21469a6ed2cfdfb94b40a39c6b580f5a769a24aec").publicKey)
    assert(keyManager.commitmentSecret(channelKeyPath, 0).value == ShaChain.shaChainFromSeed(ByteVector32.fromValidHex("9b5432706a7549d093253e72814edcd4df69419d7c7e1961e85f1d6a606735cf"), 0xFFFFFFFFFFFFL))
  }
}
