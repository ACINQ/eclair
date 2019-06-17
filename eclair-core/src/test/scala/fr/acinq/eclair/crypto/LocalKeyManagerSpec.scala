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
    * TESTNET funder funding public key from extended private key: 025677d1885ec346adadfb788739c455fec6d789702fedd578744a580d49224e1c
    * TESTNET funder payment point from extended private key: 035af8e011c0ca6fe95585802cbdb563211033434f14738e99f9196894fded8306
    * TESTNET funder revocation point from extended private key: 029d6adad640a74826a9a683439cfdf76845dc52bb9bd418416bf486239f90ba4f
    * TESTNET funder delayed payment point from extended private key: 02cf755cd01d7f48e0e373fbcd8d78eb8efe142ff2c2c108950bceaf88147945a6
    * TESTNET funder htlc point from extended private key: 03648c46cde1e8bbfe73cec3890cfcbcc0bfd5a08011b1e7a9d940ee11ae49f2a6
    * TESTNET funder shachain point from extended private key:
    *
    * MAINNET funder funding public key from extended private key: 029984abba4283bfc6f1b8fddf792fe8bf7592309cd01a5a99211cdaab61f6f701
    * MAINNET funder payment point from extended private key: 039c668597b0c0b547aefd315abf8abb3e5eb6fc905b97b65653b0fb86a9755a3a
    *
    * TESTNET fundee funding public key from extended private key #34273 #0: 02e4c59d2e4736da12c5692ec7f540da33defb5709af7122e53e48ae27ce1be386
    * TESTNET fundee htlc public point from extended private key #34273 #0: 0390a904e032ec3b1970acdd0f00565b08b9e062e61985468fc7d0718c2ec5699f
    * TESTNET fundee payment point from extended private key #34273 #0: 024d46d97bbd563aaa9f834632864cc2ef0c418d62ec6d4169fdbb3cd10c6e379c
    *
    * MAINNET fundee funding public key from extended private key #34273 #0: 034b39ac3dc425783453413875aaa9dc55f6d832867897f1e46727a07256766b92
    * MAINNET fundee htlc public point from extended private key #34273 #0: 03dd09a0fb2907b2e107e73686edde8e59dabcdae763aaec37c2bf60a3b04380c3
    *
    * MAINNET fundee revocation point from extended private key #34273 #500: 028f8416ff67a17e9b3d21269027fc50c08df65e690a2eb2c455630f4a6542ea2d
    * MAINNET fundee payment point from extended private key #34273 #500: 037c01af4e8af469f4810a70497f4e4f61ce31ff8a7bed2fb1d9a6cc90d4c57c3e
    * MAINNET fundee delayed payment point from extended private key #34273 #500: 0368d295e7e74c7de9a0972c56272130e7dbb47b25d44b1f127b9c29636c0bf2a0
    * MAINNET fundee htlc point from extended private key #34273 #500: 038c9ea3b9263f9ad85f751c5a2a1deb0864892327fe00133b55de21f7759a9c70
    * MAINNET fundee shachain public point from extended private key #34273 #500: 03885c5995154134b5570aa8c71814c0c5c5401b413459fb684b83817e9499aba7
    *
    */

  test("test vectors derivation paths (funder TESTNET)") {

    val inputOutpoint = hex"1d12dcab62f3d509db16b8dcb69782ea6358a7060b579675561c4fc2e3294f41"
    val seed = hex"0101010102020202AABBCCDD030303030404040405050505060606060707070701"
    val keyManager = new LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)

    val funderChannelKeyPath = LocalKeyManager.makeChannelKeyPathFunder(inputOutpoint)

    // TESTNET funder funding public key
    assert(keyManager.fundingPublicKey(funderChannelKeyPath).publicKey.value === hex"025677d1885ec346adadfb788739c455fec6d789702fedd578744a580d49224e1c")
    // TESTNET funder payment point
    assert(keyManager.paymentPoint(funderChannelKeyPath).publicKey.value === hex"035af8e011c0ca6fe95585802cbdb563211033434f14738e99f9196894fded8306")
    // TESTNET funder revocation point
    assert(keyManager.revocationPoint(funderChannelKeyPath).publicKey.value === hex"029d6adad640a74826a9a683439cfdf76845dc52bb9bd418416bf486239f90ba4f")
    // TESTNET funder delayed payment point
    assert(keyManager.delayedPaymentPoint(funderChannelKeyPath).publicKey.value === hex"02cf755cd01d7f48e0e373fbcd8d78eb8efe142ff2c2c108950bceaf88147945a6")
    // TESTNET funder htlc point
    assert(keyManager.htlcPoint(funderChannelKeyPath).publicKey.value === hex"03648c46cde1e8bbfe73cec3890cfcbcc0bfd5a08011b1e7a9d940ee11ae49f2a6")
  }

  test("test vectors derivation paths (funder MAINNET)") {

    val inputOutpoint = hex"1d12dcab62f3d509db16b8dcb69782ea6358a7060b579675561c4fc2e3294f41"
    val seed = hex"0101010102020202AABBCCDD030303030404040405050505060606060707070701"
    val keyManager = new LocalKeyManager(seed, Block.LivenetGenesisBlock.hash)

    val funderChannelKeyPath = LocalKeyManager.makeChannelKeyPathFunder(inputOutpoint)

    // MAINNET funder funding public key from extended private key
    assert(keyManager.fundingPublicKey(funderChannelKeyPath).publicKey.value === hex"029984abba4283bfc6f1b8fddf792fe8bf7592309cd01a5a99211cdaab61f6f701")
    // MAINNET funder payment point from extended private key
    assert(keyManager.paymentPoint(funderChannelKeyPath).publicKey.value === hex"039c668597b0c0b547aefd315abf8abb3e5eb6fc905b97b65653b0fb86a9755a3a")
  }

  test("test vectors derivation paths (fundee TESTNET)") {

    val remoteNodePubkey = PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f")
    val seed = hex"0101010102020202AABBCCDD030303030404040405050505060606060707070701"
    val keyManager = new LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)

    val fundeePubkeyKeyPath = LocalKeyManager.makeChannelKeyPathFundeePubkey(34273, 0)
    // TESTNET fundee funding public key from extended private key
    assert(keyManager.fundingPublicKey(fundeePubkeyKeyPath).publicKey.value === hex"02e4c59d2e4736da12c5692ec7f540da33defb5709af7122e53e48ae27ce1be386")

    val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(keyManager.fundingPublicKey(fundeePubkeyKeyPath).publicKey, remoteNodePubkey)))
    val fundeeChannelKeyPath = LocalKeyManager.makeChannelKeyPathFundee(fundingPubkeyScript)

    // TESTNET fundee htlc public point from extended private key
    assert(keyManager.htlcPoint(fundeeChannelKeyPath).publicKey.value === hex"0390a904e032ec3b1970acdd0f00565b08b9e062e61985468fc7d0718c2ec5699f")
    // TESTNET fundee htlc public point from extended private key
    assert(keyManager.paymentPoint(fundeeChannelKeyPath).publicKey.value === hex"024d46d97bbd563aaa9f834632864cc2ef0c418d62ec6d4169fdbb3cd10c6e379c")
  }

  test("test vectors derivation paths (fundee MAINNET)") {

    val remoteNodePubkey = PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f")
    val seed = hex"0101010102020202AABBCCDD030303030404040405050505060606060707070701"
    val keyManager = new LocalKeyManager(seed, Block.LivenetGenesisBlock.hash)

    val fundeePubkeyKeyPath = LocalKeyManager.makeChannelKeyPathFundeePubkey(34273, 0)
    // MAINNET fundee funding public key from extended private key
    assert(keyManager.fundingPublicKey(fundeePubkeyKeyPath).publicKey.value === hex"034b39ac3dc425783453413875aaa9dc55f6d832867897f1e46727a07256766b92")

    val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(keyManager.fundingPublicKey(fundeePubkeyKeyPath).publicKey, remoteNodePubkey)))
    val fundeeChannelKeyPath = LocalKeyManager.makeChannelKeyPathFundee(fundingPubkeyScript)

    // MAINNET fundee htlc public point from extended private key
    assert(keyManager.htlcPoint(fundeeChannelKeyPath).publicKey.value === hex"03dd09a0fb2907b2e107e73686edde8e59dabcdae763aaec37c2bf60a3b04380c3")

    // with different counter
    val fundeePubkeyKeyPath1 = LocalKeyManager.makeChannelKeyPathFundeePubkey(34273, 500)
    val fundingPubkeyScript1 = Script.write(Script.pay2wsh(Scripts.multiSig2of2(keyManager.fundingPublicKey(fundeePubkeyKeyPath1).publicKey, remoteNodePubkey)))
    val fundeeChannelKeyPath1 = LocalKeyManager.makeChannelKeyPathFundee(fundingPubkeyScript1)

    // MAINNET fundee revocation point
    assert(keyManager.revocationPoint(fundeeChannelKeyPath1).publicKey.value === hex"028f8416ff67a17e9b3d21269027fc50c08df65e690a2eb2c455630f4a6542ea2d")
    // MAINNET fundee payment point
    assert(keyManager.paymentPoint(fundeeChannelKeyPath1).publicKey.value === hex"037c01af4e8af469f4810a70497f4e4f61ce31ff8a7bed2fb1d9a6cc90d4c57c3e")
    // MAINNET fundee delayed payment point
    assert(keyManager.delayedPaymentPoint(fundeeChannelKeyPath1).publicKey.value === hex"0368d295e7e74c7de9a0972c56272130e7dbb47b25d44b1f127b9c29636c0bf2a0")
    // MAINNET fundee htlc point
    assert(keyManager.htlcPoint(fundeeChannelKeyPath1).publicKey.value === hex"038c9ea3b9263f9ad85f751c5a2a1deb0864892327fe00133b55de21f7759a9c70")
    // MAINNED fundee shaSeed point
    assert(keyManager.shaSeedPub(fundeeChannelKeyPath1).publicKey.value === hex"03885c5995154134b5570aa8c71814c0c5c5401b413459fb684b83817e9499aba7")
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