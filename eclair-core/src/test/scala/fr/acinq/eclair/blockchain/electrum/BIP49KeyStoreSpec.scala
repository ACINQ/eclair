package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Base58, Base58Check, Block, ByteVector32, DeterministicWallet, MnemonicCode}
import org.scalatest.FunSuite
import scodec.bits.ByteVector

class BIP49KeyStoreSpec extends FunSuite {
  test("compute addresses") {
    val master = DeterministicWallet.generate(ByteVector32(ByteVector.fill(32)(1)))
    val keyStore = new BIP49KeyStore(master, Block.RegtestGenesisBlock.hash)
    val priv = PrivateKey.fromBase58("cRumXueoZHjhGXrZWeFoEBkeDHu2m8dW5qtFBCqSAt4LDR2Hnd8Q", Base58.Prefix.SecretKeyTestnet)._1
    assert(Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, priv.publicKey.hash160) == "ms93boMGZZjvjciujPJgDAqeR86EKBf9MC")
    assert(keyStore.computeAddress(priv) == "2MscvqgGXMTYJNAY3owdUtgWJaxPUjH38Cx")
  }

  test("implement BIP49") {
    val mnemonics = "pizza afraid guess romance pair steel record jazz rubber prison angle hen heart engage kiss visual helmet twelve lady found between wave rapid twist".split(" ")
    val seed = MnemonicCode.toSeed(mnemonics, "")
    val master = DeterministicWallet.generate(seed)
    val keyStore = new BIP49KeyStore(master, Block.RegtestGenesisBlock.hash)
    val firstKey = keyStore.accountKey(0)
    assert(keyStore.computeAddress(firstKey) === "2MxJejujQJRRJdbfTKNQQ94YCnxJwRaE7yo")
  }
}
