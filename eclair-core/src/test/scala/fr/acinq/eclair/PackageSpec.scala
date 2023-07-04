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
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto, Script, addressToPublicKeyScript}
import fr.acinq.bitcoin.{Base58, Base58Check, Bech32}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

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
    assert(addressToPublicKeyScript(Block.TestnetGenesisBlock.hash, Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, pub.hash160)) == Right(Script.pay2pkh(pub)))
    assert(addressToPublicKeyScript(Block.TestnetGenesisBlock.hash, Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, pub.hash160)) == Right(Script.pay2pkh(pub)))
    assert(addressToPublicKeyScript(Block.TestnetGenesisBlock.hash, Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, pub.hash160)) == Right(Script.pay2pkh(pub)))
    assert(addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, Base58Check.encode(Base58.Prefix.PubkeyAddress, pub.hash160)) == Right(Script.pay2pkh(pub)))

    // wrong chain
    val Left(failure) = addressToPublicKeyScript(Block.TestnetGenesisBlock.hash, Base58Check.encode(Base58.Prefix.PubkeyAddress, pub.hash160))
    assert(failure.toString.contains("chain hash mismatch"))

    assert(addressToPublicKeyScript(Block.TestnetGenesisBlock.hash, Base58Check.encode(Base58.Prefix.PubkeyAddress, pub.hash160)).isLeft)
    assert(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, Base58Check.encode(Base58.Prefix.PubkeyAddress, pub.hash160)).isLeft)
    assert(addressToPublicKeyScript(Block.SignetGenesisBlock.hash, Base58Check.encode(Base58.Prefix.PubkeyAddress, pub.hash160)).isLeft)

    // p2sh
    val script = Script.write(Script.pay2wpkh(pub))

    // valid chain
    assert(addressToPublicKeyScript(Block.TestnetGenesisBlock.hash, Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, Crypto.hash160(script))) == Right(Script.pay2sh(script)))
    assert(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, Crypto.hash160(script))) == Right(Script.pay2sh(script)))
    assert(addressToPublicKeyScript(Block.SignetGenesisBlock.hash, Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, Crypto.hash160(script))) == Right(Script.pay2sh(script)))
    assert(addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, Base58Check.encode(Base58.Prefix.ScriptAddress, Crypto.hash160(script))) == Right(Script.pay2sh(script)))

    // wrong chain
    assert(addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, Crypto.hash160(script))).isLeft)
    assert(addressToPublicKeyScript(Block.TestnetGenesisBlock.hash, Base58Check.encode(Base58.Prefix.ScriptAddress, Crypto.hash160(script))).isLeft)
    assert(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, Base58Check.encode(Base58.Prefix.ScriptAddress, Crypto.hash160(script))).isLeft)
    assert(addressToPublicKeyScript(Block.SignetGenesisBlock.hash, Base58Check.encode(Base58.Prefix.ScriptAddress, Crypto.hash160(script))).isLeft)
  }

  test("decode bech32 addresses") {
    val priv = PrivateKey(ByteVector32(ByteVector.fill(32)(1)))
    val pub = priv.publicKey

    // p2wpkh
    assert(addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, Bech32.encodeWitnessAddress("bc", 0, pub.hash160)) == Right(Script.pay2wpkh(pub)))
    assert(addressToPublicKeyScript(Block.TestnetGenesisBlock.hash, Bech32.encodeWitnessAddress("tb", 0, pub.hash160)) == Right(Script.pay2wpkh(pub)))
    assert(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, Bech32.encodeWitnessAddress("bcrt", 0, pub.hash160)) == Right(Script.pay2wpkh(pub)))
    assert(addressToPublicKeyScript(Block.SignetGenesisBlock.hash, Bech32.encodeWitnessAddress("tb", 0, pub.hash160)) == Right(Script.pay2wpkh(pub)))

    // wrong chain
    assert(addressToPublicKeyScript(Block.TestnetGenesisBlock.hash, Bech32.encodeWitnessAddress("bc", 0, pub.hash160)).isLeft)
    assert(addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, Bech32.encodeWitnessAddress("tb", 0, pub.hash160)).isLeft)
    assert(addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, Bech32.encodeWitnessAddress("bcrt", 0, pub.hash160)).isLeft)
    assert(addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, Bech32.encodeWitnessAddress("tb", 0, pub.hash160)).isLeft)

    val script = Script.write(Script.pay2wpkh(pub))
    assert(addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, Bech32.encodeWitnessAddress("bc", 0, Crypto.sha256(script))) == Right(Script.pay2wsh(script)))
    assert(addressToPublicKeyScript(Block.TestnetGenesisBlock.hash, Bech32.encodeWitnessAddress("tb", 0, Crypto.sha256(script))) == Right(Script.pay2wsh(script)))
    assert(addressToPublicKeyScript(Block.RegtestGenesisBlock.hash, Bech32.encodeWitnessAddress("bcrt", 0, Crypto.sha256(script))) == Right(Script.pay2wsh(script)))
    assert(addressToPublicKeyScript(Block.SignetGenesisBlock.hash, Bech32.encodeWitnessAddress("tb", 0, Crypto.sha256(script))) == Right(Script.pay2wsh(script)))
  }

  test("fail to decode invalid addresses") {
    assert(addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, "1Qbbbbb").isLeft)
    //assert(e.getMessage.contains("is neither a valid Base58 address") && e.getMessage.contains("nor a valid Bech32 address"))
  }

  test("compare short channel ids as unsigned longs") {
    assert(ShortChannelId(Long.MinValue - 1) < ShortChannelId(Long.MinValue))
    assert(ShortChannelId(Long.MinValue) < ShortChannelId(Long.MinValue + 1))
    assert(ShortChannelId(Long.MaxValue - 1) < ShortChannelId(Long.MaxValue))
    assert(ShortChannelId(Long.MaxValue) < ShortChannelId(Long.MaxValue + 1))
  }

}
