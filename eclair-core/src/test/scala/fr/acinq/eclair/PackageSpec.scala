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

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto, Script}
import fr.acinq.bitcoin.{Base58, Base58Check, Bech32}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

import scala.util.Try

/**
 * Created by PM on 27/01/2017.
 */

class PackageSpec extends AnyFunSuite {
  implicit def byteVector2array(input: ByteVector): Array[Byte] = input.toArray

  implicit def byteVector322array(input: ByteVector32): Array[Byte] = input.toArray

  test("compute long channel id") {
    val data = ((hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF", 0, hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF") ::
      (hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF", 1, hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFE") ::
      (hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF0000", 2, hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF0002") ::
      (hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00F0", 0x0F00, hex"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF0FF0") :: Nil)
      .map(x => (ByteVector32(x._1), x._2, ByteVector32(x._3)))

    data.foreach(x => assert(toLongId(ByteVector32(x._1), x._2) == x._3))
  }

  test("decode base58 addresses") {
    val priv = PrivateKey(ByteVector32(ByteVector.fill(32)(1)))
    val pub = priv.publicKey

    // p2pkh
    // valid chain
    assert(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, pub.hash160), Block.TestnetGenesisBlock.hash) == Script.pay2pkh(pub))
    assert(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, pub.hash160), Block.RegtestGenesisBlock.hash) == Script.pay2pkh(pub))
    assert(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, pub.hash160), Block.SignetGenesisBlock.hash) == Script.pay2pkh(pub))
    assert(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.PubkeyAddress, pub.hash160), Block.LivenetGenesisBlock.hash) == Script.pay2pkh(pub))

    // wrong chain
    intercept[RuntimeException] {
      addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.PubkeyAddress, pub.hash160), Block.TestnetGenesisBlock.hash)
    }
    assert(Try(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.PubkeyAddress, pub.hash160), Block.TestnetGenesisBlock.hash)).isFailure)
    assert(Try(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.PubkeyAddress, pub.hash160), Block.RegtestGenesisBlock.hash)).isFailure)
    assert(Try(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.PubkeyAddress, pub.hash160), Block.SignetGenesisBlock.hash)).isFailure)

    // p2sh
    val script = Script.write(Script.pay2wpkh(pub))

    // valid chain
    assert(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, Crypto.hash160(script)), Block.TestnetGenesisBlock.hash) == Script.pay2sh(script))
    assert(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, Crypto.hash160(script)), Block.RegtestGenesisBlock.hash) == Script.pay2sh(script))
    assert(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, Crypto.hash160(script)), Block.SignetGenesisBlock.hash) == Script.pay2sh(script))
    assert(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.ScriptAddress, Crypto.hash160(script)), Block.LivenetGenesisBlock.hash) == Script.pay2sh(script))

    // wrong chain
    assert(Try(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, Crypto.hash160(script)), Block.LivenetGenesisBlock.hash)).isFailure)
    assert(Try(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.ScriptAddress, Crypto.hash160(script)), Block.TestnetGenesisBlock.hash)).isFailure)
    assert(Try(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.ScriptAddress, Crypto.hash160(script)), Block.RegtestGenesisBlock.hash)).isFailure)
    assert(Try(addressToPublicKeyScript(Base58Check.encode(Base58.Prefix.ScriptAddress, Crypto.hash160(script)), Block.SignetGenesisBlock.hash)).isFailure)
  }

  test("decode bech32 addresses") {
    val priv = PrivateKey(ByteVector32(ByteVector.fill(32)(1)))
    val pub = priv.publicKey

    // p2wpkh
    assert(addressToPublicKeyScript(Bech32.encodeWitnessAddress("bc", 0, pub.hash160), Block.LivenetGenesisBlock.hash) == Script.pay2wpkh(pub))
    assert(addressToPublicKeyScript(Bech32.encodeWitnessAddress("tb", 0, pub.hash160), Block.TestnetGenesisBlock.hash) == Script.pay2wpkh(pub))
    assert(addressToPublicKeyScript(Bech32.encodeWitnessAddress("bcrt", 0, pub.hash160), Block.RegtestGenesisBlock.hash) == Script.pay2wpkh(pub))
    assert(addressToPublicKeyScript(Bech32.encodeWitnessAddress("tb", 0, pub.hash160), Block.SignetGenesisBlock.hash) == Script.pay2wpkh(pub))

    // wrong chain
    assert(Try(addressToPublicKeyScript(Bech32.encodeWitnessAddress("bc", 0, pub.hash160), Block.TestnetGenesisBlock.hash)).isFailure)
    assert(Try(addressToPublicKeyScript(Bech32.encodeWitnessAddress("tb", 0, pub.hash160), Block.LivenetGenesisBlock.hash)).isFailure)
    assert(Try(addressToPublicKeyScript(Bech32.encodeWitnessAddress("bcrt", 0, pub.hash160), Block.LivenetGenesisBlock.hash)).isFailure)
    assert(Try(addressToPublicKeyScript(Bech32.encodeWitnessAddress("tb", 0, pub.hash160), Block.LivenetGenesisBlock.hash)).isFailure)

    val script = Script.write(Script.pay2wpkh(pub))
    assert(addressToPublicKeyScript(Bech32.encodeWitnessAddress("bc", 0, Crypto.sha256(script)), Block.LivenetGenesisBlock.hash) == Script.pay2wsh(script))
    assert(addressToPublicKeyScript(Bech32.encodeWitnessAddress("tb", 0, Crypto.sha256(script)), Block.TestnetGenesisBlock.hash) == Script.pay2wsh(script))
    assert(addressToPublicKeyScript(Bech32.encodeWitnessAddress("bcrt", 0, Crypto.sha256(script)), Block.RegtestGenesisBlock.hash) == Script.pay2wsh(script))
    assert(addressToPublicKeyScript(Bech32.encodeWitnessAddress("tb", 0, Crypto.sha256(script)), Block.SignetGenesisBlock.hash) == Script.pay2wsh(script))
  }

  test("fail to decode invalid addresses") {
    val e = intercept[RuntimeException] {
      addressToPublicKeyScript("1Qbbbbb", Block.LivenetGenesisBlock.hash)
    }
    assert(e.getMessage.contains("is neither a valid Base58 address") && e.getMessage.contains("nor a valid Bech32 address"))
  }

  test("compare short channel ids as unsigned longs") {
    assert(ShortChannelId(Long.MinValue - 1) < ShortChannelId(Long.MinValue))
    assert(ShortChannelId(Long.MinValue) < ShortChannelId(Long.MinValue + 1))
    assert(ShortChannelId(Long.MaxValue - 1) < ShortChannelId(Long.MaxValue))
    assert(ShortChannelId(Long.MaxValue) < ShortChannelId(Long.MaxValue + 1))
  }

}
