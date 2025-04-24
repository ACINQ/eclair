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

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, DeterministicWallet}
import fr.acinq.eclair.Setup.Seeds
import fr.acinq.eclair.channel.ChannelConfig
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.{NodeParams, TestUtils, randomBytes32}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

import java.io.File
import java.nio.file.Files


class LocalChannelKeyManagerSpec extends AnyFunSuite {

  test("generate the same secrets from the same seed") {
    // data was generated with eclair 0.3 
    val seed = hex"17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501"
    val nodeKeyManager = LocalNodeKeyManager(seed, Block.Testnet3GenesisBlock.hash)
    val channelKeyManager = LocalChannelKeyManager(seed, Block.Testnet3GenesisBlock.hash)
    assert(nodeKeyManager.nodeId == PublicKey(hex"02a051267759c3a149e3e72372f4e0c4054ba597ebfd0eda78a2273023667205ee"))
    val fundingKeyPath = KeyPath("m/1'/2'/3'/4'")
    val channelKeys = channelKeyManager.channelKeys(ChannelConfig(), fundingKeyPath)
    assert(channelKeys.commitmentSecret(0).value == ByteVector32.fromValidHex("fa7a8c2fc62642f7a9a19ea0bfad14d39a430f3c9899c185dcecc61c8077891e"))
    assert(channelKeys.commitmentSecret(1).value == ByteVector32.fromValidHex("3e82338d3e487c760ee10448127613d196b040e86ce90d2d437db6425bb7301c"))
    assert(channelKeys.commitmentSecret(2).value == ByteVector32.fromValidHex("102357f7a9b2d0b9147f645c98aa156d3278ddb4745caf0631773dd663e76e6f"))
    assert(channelKeys.commitmentPoint(0).value == hex"0x0237dd5a0ea26ed84ed1249d46cc715679b542939d6943b42232e043825cde3944")
    assert(channelKeys.delayedPaymentBaseSecret == PrivateKey(hex"195f8f7de612978117baaa750c0098362eb17ed287161cc84dc03f869e321317"))
    assert(channelKeys.htlcBaseSecret == PrivateKey(hex"9430df6ca38bf1a00a8ea8f1123ec870ad04c3cbce9641e38b6bf94cb910f7f3"))
    assert(channelKeys.paymentBaseSecret == PrivateKey(hex"6a2577dbac51e4ddc6fc325ff63f3eba6f37b08c3e5ac173168810d20c5632cd"))
    assert(channelKeys.revocationBaseSecret == PrivateKey(hex"0871e813ddc7b29bca128c9c9b048f6e60fbe6a53fda1558f33545951e04e1ab"))
    assert(DeterministicWallet.ExtendedPublicKey.decode("tpubDMBn7xW1g1Gsok5eThkJAKJnB3ZFqZQnvsdWv8VvM3RjZkqVPZZpjPDAAmbyDHnZPdAZY8EnFBh1ibTBtiuDqb8t9wRcAZiFihma3yYRG1f")._2.publicKey == channelKeys.delayedPaymentBasePoint)
    assert(DeterministicWallet.ExtendedPublicKey.decode("tpubDMBn7xW1g1GsqpsqaVNB1ehpjktQUX44Dycy7fJ6thp774XGzNeWFmQf5L6dVChHREgkoc8BYc2caHqwc2mZzTYCwoxsvrpchBSujsPCvGH")._2.publicKey == channelKeys.htlcBasePoint)
    assert(DeterministicWallet.ExtendedPublicKey.decode("tpubDMBn7xW1g1Gsme9jTAEJwTvizDJtJEgE3jc9vkDqQ9azuh9Es2aM6GsioFiouwdvWPJoNw2zavCkVTMta6UJN6BWR5cMZQsSHvsFyQNfGzv")._2.publicKey == channelKeys.paymentBasePoint)
    assert(DeterministicWallet.ExtendedPublicKey.decode("tpubDMBn7xW1g1GsizhaZ7M4co6sBtUDhRUKgUUPWRv3WfLTpTGYrSjATJy6ZVSoYFCKRnaBop5dFig3Ham1P145NQAKuUgPUbujLAooL7F2vy6")._2.publicKey == channelKeys.revocationBasePoint)
  }

  test("compute channel key path from funding keys") {
    // if this test fails it means that we don't generate the same channel key path from the same funding pubkey, which
    // will break existing channels !
    val pub = PrivateKey(ByteVector32.fromValidHex("01" * 32)).publicKey
    val keyPath = ChannelKeyManager.keyPathFromPublicKey(pub)
    assert(keyPath.toString() == "m/1909530642'/1080788911/847211985'/1791010671/1303008749'/34154019'/723973395/767609665")
  }

  test("deterministically derive channel keys based on channel config") {
    val channelKeyManager = LocalChannelKeyManager(randomBytes32(), Block.Testnet3GenesisBlock.hash)
    val fundingKeyPath1 = channelKeyManager.newFundingKeyPath(isChannelOpener = true)
    val fundingKeyPath2 = channelKeyManager.newFundingKeyPath(isChannelOpener = true)
    assert(fundingKeyPath1 != fundingKeyPath2)

    val channelKeys1 = channelKeyManager.channelKeys(ChannelConfig.standard, fundingKeyPath1)
    val channelKeys2 = channelKeyManager.channelKeys(ChannelConfig.standard, fundingKeyPath2)
    assert(channelKeys1 == channelKeyManager.channelKeys(ChannelConfig.standard, fundingKeyPath1))
    assert(channelKeys1 != channelKeyManager.channelKeys(ChannelConfig(), fundingKeyPath1))
    assert(channelKeys1 != channelKeys2)
  }

  def makeFundingKeyPath(entropy: ByteVector, isInitiator: Boolean): KeyPath = {
    val items = for (i <- 0 to 7) yield entropy.drop(i * 4).take(4).toInt(signed = false) & 0xFFFFFFFFL
    val last = DeterministicWallet.hardened(if (isInitiator) 1L else 0L)
    KeyPath(items :+ last)
  }

  test("test vectors (testnet, funder)") {
    val seed = ByteVector.fromValidHex("17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501")
    val channelKeyManager = LocalChannelKeyManager(seed, Block.Testnet3GenesisBlock.hash)
    val fundingKeyPath = makeFundingKeyPath(hex"be4fa97c62b9f88437a3be577b31eb48f2165c7bc252194a15ff92d995778cfb", isInitiator = true)
    val channelKeys = channelKeyManager.channelKeys(ChannelConfig.standard, fundingKeyPath)
    val fundingKey = channelKeys.fundingKey(fundingTxIndex = 0)

    assert(fundingKey.publicKey == PrivateKey(hex"216414970b4216b197a1040367419ad6922f80e8b73ced083e9afe5e6ddd8e4c").publicKey)
    assert(channelKeys.revocationBasePoint == PrivateKey(hex"a4e7ab3c54752a3487b3c474467843843f28d3bb9113e65e92056ad45d1e318e").publicKey)
    assert(channelKeys.paymentBasePoint == PrivateKey(hex"de24c43d24b8d6bc66b020ac81164206bb577c7924511d4e99431c0d60505012").publicKey)
    assert(channelKeys.delayedPaymentBasePoint == PrivateKey(hex"8aa7b8b14a7035540c331c030be0dd73e8806fb0c97a2519d63775c2f579a950").publicKey)
    assert(channelKeys.htlcBasePoint == PrivateKey(hex"94eca6eade204d6e753344c347b46bb09067c92b2fe371cf4f8362c1594c8c59").publicKey)
    assert(channelKeys.commitmentSecret(0).value == ShaChain.shaChainFromSeed(ByteVector32.fromValidHex("64e9d1e9840add3bb02c1525995edd28feea67f1df7a9ee075179e8541adc7a2"), 0xFFFFFFFFFFFFL))
  }

  test("test vectors (testnet, fundee)") {
    val seed = ByteVector.fromValidHex("aeb3e9b5642cd4523e9e09164047f60adb413633549c3c6189192921311894d501")
    val channelKeyManager = LocalChannelKeyManager(seed, Block.Testnet3GenesisBlock.hash)
    val fundingKeyPath = makeFundingKeyPath(hex"06535806c1aa73971ec4877a5e2e684fa636136c073810f190b63eefc58ca488", isInitiator = false)
    val channelKeys = channelKeyManager.channelKeys(ChannelConfig.standard, fundingKeyPath)
    val fundingKey = channelKeys.fundingKey(fundingTxIndex = 0)

    assert(fundingKey.publicKey == PrivateKey(hex"7bb8019c99fcba1c6bd0cc7f3c635c14c658d26751232d6a6350d8b6127d53c3").publicKey)
    assert(channelKeys.revocationBasePoint == PrivateKey(hex"26510db99546c9b08418fe9df2da710a92afa6cc4e5681141610dfb8019052e6").publicKey)
    assert(channelKeys.paymentBasePoint == PrivateKey(hex"0766c93fd06f69287fcc7b343916e678b83942345d4080e83f4c8a061b1a9f4b").publicKey)
    assert(channelKeys.delayedPaymentBasePoint == PrivateKey(hex"094aa052a9647228fd80e42461cae26c04f6cdd1665b816d4660df686915319a").publicKey)
    assert(channelKeys.htlcBasePoint == PrivateKey(hex"8ec62bd03b241a2e522477ae1a9861a668429ab3e443abd2aa0f2f10e2dc2206").publicKey)
    assert(channelKeys.commitmentSecret(0).value == ShaChain.shaChainFromSeed(ByteVector32.fromValidHex("c49e98202b0fee19f28fd3af60691aaacdd2c09e20896f5fa3ad1b9b70e4879f"), 0xFFFFFFFFFFFFL))
  }

  test("test vectors (mainnet, funder)") {
    val seed = ByteVector.fromValidHex("d8d5431487c2b19ee6486aad6c3bdfb99d10b727bade7fa848e2ab7901c15bff01")
    val channelKeyManager = LocalChannelKeyManager(seed, Block.LivenetGenesisBlock.hash)
    val fundingKeyPath = makeFundingKeyPath(hex"ec1c41cd6be2b6e4ef46c1107f6c51fbb2066d7e1f7720bde4715af233ae1322", isInitiator = true)
    val channelKeys = channelKeyManager.channelKeys(ChannelConfig.standard, fundingKeyPath)
    val fundingKey = channelKeys.fundingKey(fundingTxIndex = 0)

    assert(fundingKey.publicKey == PrivateKey(hex"b97c04796850e9d74a06c9d7230d85e2ecca3598b162ddf902895ece820c8f09").publicKey)
    assert(channelKeys.revocationBasePoint == PrivateKey(hex"ee13db7f2d7e672f21395111ee169af8462c6e8d1a6a78d808f7447b27155ffb").publicKey)
    assert(channelKeys.paymentBasePoint == PrivateKey(hex"7fc18e4c925bf3c5a83411eac7f234f0c5eaef9a8022b22ec6e3272ae329e17e").publicKey)
    assert(channelKeys.delayedPaymentBasePoint == PrivateKey(hex"c0d9a3e3601d79b11b948db9d672fcddafcb9a3c0873c6a738bb09087ea2bfc6").publicKey)
    assert(channelKeys.htlcBasePoint == PrivateKey(hex"bd3ba7068d131a9ab47f33202d532c5824cc5fc35a9adada3644ac2994372228").publicKey)
    assert(channelKeys.commitmentSecret(0).value == ShaChain.shaChainFromSeed(ByteVector32.fromValidHex("7799de34239f97837a12191f5b60e766e32e9704bb84b0f12b539e9bf6a0dc2a"), 0xFFFFFFFFFFFFL))
  }

  test("test vectors (mainnet, fundee)") {
    val seed = ByteVector.fromValidHex("4b809dd593b36131c454d60c2f7bdfd49d12ec455e5b657c47a9ca0f5dfc5eef01")
    val channelKeyManager = LocalChannelKeyManager(seed, Block.LivenetGenesisBlock.hash)
    val fundingKeyPath = makeFundingKeyPath(hex"2b4f045be5303d53f9d3a84a1e70c12251168dc29f300cf9cece0ec85cd8182b", isInitiator = false)
    val channelKeys = channelKeyManager.channelKeys(ChannelConfig.standard, fundingKeyPath)
    val fundingKey = channelKeys.fundingKey(fundingTxIndex = 0)

    assert(fundingKey.publicKey == PrivateKey(hex"46a4e818615a48a99ce9f6bd73eea07d5822dcfcdff18081ea781d4e5e6c036c").publicKey)
    assert(channelKeys.revocationBasePoint == PrivateKey(hex"c2cd9e2f9f8203f16b1751bd252285bb2e7fc4688857d620467b99645ebdfbe6").publicKey)
    assert(channelKeys.paymentBasePoint == PrivateKey(hex"1e4d3527788b39dc8ebc0ae6368a67e92eff55a43bea8e93054338ca850fa340").publicKey)
    assert(channelKeys.delayedPaymentBasePoint == PrivateKey(hex"6bc30b0852fbc653451662a1ff6ad530f311d58b5e5661b541eb57dba8206937").publicKey)
    assert(channelKeys.htlcBasePoint == PrivateKey(hex"b1be27b5232e3bc5d6a261949b4ee68d96fa61f481998d36342e2ad99444cf8a").publicKey)
    assert(channelKeys.commitmentSecret(0).value == ShaChain.shaChainFromSeed(ByteVector32.fromValidHex("eeb3bad6808e8bb5f1774581ccf64aa265fef38eca80a1463d6310bb801b3ba7"), 0xFFFFFFFFFFFFL))
  }

  test("derivation of local key from base key and per-commitment-point") {
    val baseKey: PrivateKey = PrivateKey(hex"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    val commitmentPoint = PublicKey(hex"025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486")
    val localprivkey = ChannelKeys.derivePerCommitmentKey(baseKey, commitmentPoint)
    assert(localprivkey.value == ByteVector32(hex"cbced912d3b21bf196a766651e436aff192362621ce317704ea2f75d87e7be0f"))
  }

  test("derivation remote public key from base point and per-commitment-point") {
    val basePoint = PublicKey(hex"036d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e2")
    val commitmentPoint = PublicKey(hex"025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486")
    val publicKey = ChannelKeys.remotePerCommitmentPublicKey(basePoint, commitmentPoint)
    assert(publicKey.value == hex"0235f2dbfaa89b57ec7b055afe29849ef7ddfeb1cefdb9ebdc43f5494984db29e5")
  }

  test("derivation of revocation key from base key and per-commitment-secret") {
    val baseKey: PrivateKey = PrivateKey(hex"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    val commitmentSecret: PrivateKey = PrivateKey(hex"1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100")
    val revocationKey = ChannelKeys.revocationKey(baseKey, commitmentSecret)
    assert(revocationKey.value == ByteVector32(hex"d09ffff62ddb2297ab000cc85bcb4283fdeb6aa052affbc9dddcf33b61078110"))
  }

  test("derivation of revocation public key from base point and per-commitment-point") {
    val basePoint = PublicKey(hex"036d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e2")
    val commitmentPoint = PublicKey(hex"025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486")
    val revocationKey = ChannelKeys.revocationPublicKey(basePoint, commitmentPoint)
    assert(revocationKey.value == hex"02916e326636d19c33f13e8c0c3a03dd157f332f3e99c317c141dd865eb01f8ff0")
  }

  test("keep the same channel seed after a migration from the old seed.dat file") {
    val seed = hex"17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501"
    val seedDatFile = TestUtils.createSeedFile("seed.dat", seed.toArray)

    val Seeds(_, _) = NodeParams.getSeeds(seedDatFile.getParentFile)

    val channelSeedDatFile = new File(seedDatFile.getParentFile, "channel_seed.dat")
    assert(channelSeedDatFile.exists())

    val channelSeedContent = ByteVector(Files.readAllBytes(channelSeedDatFile.toPath))
    assert(seed == channelSeedContent)
  }

}
