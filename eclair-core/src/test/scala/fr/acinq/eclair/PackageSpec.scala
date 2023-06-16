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

package fr.acinq.eclair

import fr.acinq.bitcoin.scala.Crypto.PrivateKey
import fr.acinq.bitcoin.scala.{Base58, Base58Check, Bech32, Block, ByteVector32, Crypto, Satoshi, Script}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

import scala.util.Try

/**
 * Created by PM on 27/01/2017.
 */

class PackageSpec extends AnyFunSuite {

  test("compute long channel id") {
    val data = ((hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF", 0, hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF") ::
      (hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF", 1, hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFE") ::
      (hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF0000", 2, hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF0002") ::
      (hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00F0", 0x0F00, hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF0FF0") :: Nil)
      .map(x => (ByteVector32(x._1), x._2, ByteVector32(x._3)))

    data.foreach(x => assert(toLongId(ByteVector32(x._1), x._2) === x._3))
  }

  test("decode base58 addresses") {
    val priv = PrivateKey(ByteVector32(ByteVector.fill(32)(1)))
    val pub = priv.publicKey

    // p2pkh
    // valid chain
    assert(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, pub.hash160), Block.TestnetGenesisBlock.hash) == Script.pay2pkh(pub))
    assert(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, pub.hash160), Block.RegtestGenesisBlock.hash) == Script.pay2pkh(pub))
    assert(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.PubkeyAddress, pub.hash160), Block.LivenetGenesisBlock.hash) == Script.pay2pkh(pub))

    // wrong chain
    intercept[RuntimeException] {
      addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.PubkeyAddress, pub.hash160), Block.TestnetGenesisBlock.hash)
    }
    assert(Try(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.PubkeyAddress, pub.hash160), Block.TestnetGenesisBlock.hash)).isFailure)
    assert(Try(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.PubkeyAddress, pub.hash160), Block.RegtestGenesisBlock.hash)).isFailure)

    // p2sh
    val script = Script.write(Script.pay2wpkh(pub))

    // valid chain
    assert(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, Crypto.hash160(script)), Block.TestnetGenesisBlock.hash) == Script.pay2sh(script))
    assert(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, Crypto.hash160(script)), Block.RegtestGenesisBlock.hash) == Script.pay2sh(script))
    assert(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.ScriptAddress, Crypto.hash160(script)), Block.LivenetGenesisBlock.hash) == Script.pay2sh(script))

    // wrong chain
    assert(Try(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, Crypto.hash160(script)), Block.LivenetGenesisBlock.hash)).isFailure)
    assert(Try(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.ScriptAddress, Crypto.hash160(script)), Block.TestnetGenesisBlock.hash)).isFailure)
    assert(Try(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.ScriptAddress, Crypto.hash160(script)), Block.RegtestGenesisBlock.hash)).isFailure)
  }

  test("decode bech32 addresses") {
    val priv = PrivateKey(ByteVector32(ByteVector.fill(32)(1)))
    val pub = priv.publicKey

    // p2wpkh
    assert(addressToPublicKeyScript(Bech32.encodeWitnessAddress("bc", 0, pub.hash160), Block.LivenetGenesisBlock.hash) == Script.pay2wpkh(pub))
    assert(addressToPublicKeyScript(Bech32.encodeWitnessAddress("tb", 0, pub.hash160), Block.TestnetGenesisBlock.hash) == Script.pay2wpkh(pub))
    assert(addressToPublicKeyScript(Bech32.encodeWitnessAddress("bcrt", 0, pub.hash160), Block.RegtestGenesisBlock.hash) == Script.pay2wpkh(pub))

    // wrong version
    assert(Try(addressToPublicKeyScript(Bech32.encodeWitnessAddress("bc", 1, pub.hash160), Block.LivenetGenesisBlock.hash)).isFailure)
    assert(Try(addressToPublicKeyScript(Bech32.encodeWitnessAddress("tb", 1, pub.hash160), Block.TestnetGenesisBlock.hash)).isFailure)
    assert(Try(addressToPublicKeyScript(Bech32.encodeWitnessAddress("bcrt", 1, pub.hash160), Block.RegtestGenesisBlock.hash)).isFailure)

    // wrong chain
    assert(Try(addressToPublicKeyScript(Bech32.encodeWitnessAddress("bc", 0, pub.hash160), Block.TestnetGenesisBlock.hash)).isFailure)
    assert(Try(addressToPublicKeyScript(Bech32.encodeWitnessAddress("tb", 0, pub.hash160), Block.LivenetGenesisBlock.hash)).isFailure)
    assert(Try(addressToPublicKeyScript(Bech32.encodeWitnessAddress("bcrt", 0, pub.hash160), Block.LivenetGenesisBlock.hash)).isFailure)

    val script = Script.write(Script.pay2wpkh(pub))
    assert(addressToPublicKeyScript(Bech32.encodeWitnessAddress("bc", 0, Crypto.sha256(script)), Block.LivenetGenesisBlock.hash) == Script.pay2wsh(script))
    assert(addressToPublicKeyScript(Bech32.encodeWitnessAddress("tb", 0, Crypto.sha256(script)), Block.TestnetGenesisBlock.hash) == Script.pay2wsh(script))
    assert(addressToPublicKeyScript(Bech32.encodeWitnessAddress("bcrt", 0, Crypto.sha256(script)), Block.RegtestGenesisBlock.hash) == Script.pay2wsh(script))
  }

  test("fail to decode invalid addresses") {
    val e = intercept[RuntimeException] {
      addressToPublicKeyScript("1Qbbbbb", Block.LivenetGenesisBlock.hash)
    }
    assert(e.getMessage.contains("is neither a valid Base58 address") && e.getMessage.contains("nor a valid Bech32 address"))
  }

  test("convert fee rates and enforce a minimum feerate-per-kw") {
    assert(feerateByte2Kw(1) == MinimumFeeratePerKw)
    assert(feerateKB2Kw(1000) == MinimumFeeratePerKw)
  }

  test("compare short channel ids as unsigned longs") {
    assert(ShortChannelId(Long.MinValue - 1) < ShortChannelId(Long.MinValue))
    assert(ShortChannelId(Long.MinValue) < ShortChannelId(Long.MinValue + 1))
    assert(ShortChannelId(Long.MaxValue - 1) < ShortChannelId(Long.MaxValue))
    assert(ShortChannelId(Long.MaxValue) < ShortChannelId(Long.MaxValue + 1))
  }

  test("compute addresses from pubkey scripts") {
    Seq(
      ("0014d0b19277b0f76c9512f26d77573fd31a8fd15fc7", Block.TestnetGenesisBlock.hash, "tb1q6zceyaas7akf2yhjd4m4w07nr28azh78gw79kk"),
      ("00203287047df2aa7aade3f394790a9c9d6f9235943f48a012e8a9f2c3300ca4f2d1", Block.TestnetGenesisBlock.hash, "tb1qx2rsgl0j4fa2mclnj3us48yad7frt9plfzsp969f7tpnqr9y7tgsyprxej"),
      ("76a914b17deefe2feab87fef7221cf806bb8ca61f00fa188ac", Block.TestnetGenesisBlock.hash, "mwhSm2SHhRhd19KZyaQLgJyAtCLnkbzWbf"),
      ("a914d3cf9d04f4ecc36df8207b300e46bc6775fc84c087", Block.TestnetGenesisBlock.hash, "2NCZBGzKadAnLv1ijAqhrKavMuqvxqu18yY"),
      ("00145cb882efd643b7d63ae133e4d5e88e10bd5a20d7", Block.LivenetGenesisBlock.hash, "bc1qtjug9m7kgwmavwhpx0jdt6ywzz745gxhxwyn8u"),
      ("00208c2865c87ffd33fc5d698c7df9cf2d0fb39d93103c637a06dea32c848ebc3e1d", Block.LivenetGenesisBlock.hash, "bc1q3s5xtjrll5elchtf337lnnedp7eemycs833h5pk75vkgfr4u8cws3ytg02"),
      ("76a914536ffa992491508dca0354e52f32a3a7a679a53a88ac", Block.LivenetGenesisBlock.hash, "18cBEMRxXHqzWWCxZNtU91F5sbUNKhL5PX"),
      ("a91481b9ac6a59b53927da7277b5ad5460d781b365d987", Block.LivenetGenesisBlock.hash, "3DWwX7NYjnav66qygrm4mBCpiByjammaWy"),
      ("5120eceb3f3cdbad5bda395a32910ff6195a89cc5ec7fbf34705512da6f81963b156", Block.RegtestGenesisBlock.hash, "bcrt1pan4n70xm44da5w26x2gslaset2yuchk8l0e5wp239kn0sxtrk9tql9wc8z"),
      ("00144cae0f71665150b3ac0e0f2990222e99a0e470df", Block.RegtestGenesisBlock.hash, "bcrt1qfjhq7utx29gt8tqwpu5eqg3wnxswguxl57xpge")
    ).foreach { case (script, blockHash, address) =>
      assert(addressFromPublicKeyScript(blockHash, ByteVector.fromValidHex(script)) == address)
    }
  }
}
