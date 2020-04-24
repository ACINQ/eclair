package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Base58, Block, ByteVector32, DeterministicWallet, MnemonicCode}
import org.scalatest.{FunSuite, Tag}
import scodec.bits.{ByteVector, HexStringSyntax}

class BIP84KeyStoreSpec extends FunSuite {
  test("derive bip84 keys", Tag("bech32")) {
    val seed = MnemonicCode.toSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", passphrase = "")
    val master = DeterministicWallet.generate(seed)
    val keyStore = new BIP84KeyStore(master, Block.LivenetGenesisBlock.hash)
    val (accountZpub, _) = keyStore.computeXPub
    assert(accountZpub == "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs") // m/84'/0'/0'

    val firstReceivingKey = keyStore.accountKey(0)
    assert(firstReceivingKey.publicKey.value == hex"0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c")
  }

  test("compute bech32 addresses", Tag("bech32")) {
    val seed = MnemonicCode.toSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", passphrase = "")
    val master = DeterministicWallet.generate(seed)
    val livenetKeyStore = new BIP84KeyStore(master, Block.LivenetGenesisBlock.hash)
    val firstReceivingKey = livenetKeyStore.accountKey(0)
    val secondReceivingKey = livenetKeyStore.accountKey(1)
    val firstChangeKey = livenetKeyStore.changeKey(0)

    assert(livenetKeyStore.computeAddress(firstReceivingKey) == "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu")
    assert(livenetKeyStore.computeAddress(secondReceivingKey) == "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g")
    assert(livenetKeyStore.computeAddress(firstChangeKey) == "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el")
  }

  // from https://github.com/spesmilo/electrum/blob/master/electrum/tests/test_wallet.py#L218
  test("electrum bech32 compatibility") {
    val (privKey, _) = PrivateKey.fromBase58("L4jkdiXszG26SUYvwwJhzGwg37H2nLhrbip7u6crmgNeJysv5FHL", Base58.Prefix.SecretKey)
    val livenetKeyStore = new BIP84KeyStore(DeterministicWallet.generate(ByteVector32(ByteVector.fill(32)(1))), Block.LivenetGenesisBlock.hash)
    assert(livenetKeyStore.computeAddress(privKey.publicKey) == "bc1q2ccr34wzep58d4239tl3x3734ttle92a8srmuw")
  }

}
