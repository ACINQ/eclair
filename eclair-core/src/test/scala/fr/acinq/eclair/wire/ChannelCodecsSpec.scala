/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.wire

import fr.acinq.bitcoin.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.Script.write
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet, OutPoint, Transaction}
import fr.acinq.eclair.channel.{HtlcTxAndSigs, LocalParams, PublishableTxs, RemoteParams}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.{Local, Relayed}
import fr.acinq.eclair.transactions.Transactions.{CommitTx, HtlcTimeoutTx, HtlcTimeoutTxLegacy, InputInfo}
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.ChannelCodecs._
import fr.acinq.eclair.{UInt64, randomKey}
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import scodec.bits.BitVector
import scala.util.Random

/**
  * Created by PM on 31/05/2016.
  */

class ChannelCodecsSpec extends FunSuite {

  def randomBytes(size: Int): BinaryData = {
    val bin = new Array[Byte](size)
    Random.nextBytes(bin)
    bin
  }

  test("encode/decode key paths (all 0s)") {
    val keyPath = KeyPath(Seq(0L, 0L, 0L, 0L))
    val encoded = keyPathCodec.encode(keyPath).require
    val decoded = keyPathCodec.decode(encoded).require
    assert(keyPath === decoded.value)
  }

  test("encode/decode key paths (all 1s)") {
    val keyPath = KeyPath(Seq(0xffffffffL, 0xffffffffL, 0xffffffffL, 0xffffffffL))
    val encoded = keyPathCodec.encode(keyPath).require
    val decoded = keyPathCodec.decode(encoded).require
    assert(keyPath === decoded.value)
  }

  test("encode/decode localparams") {
    val o = LocalParams(
      nodeId = randomKey.publicKey,
      channelKeyPath = DeterministicWallet.KeyPath(Seq(42L)),
      dustLimitSatoshis = Random.nextInt(Int.MaxValue),
      maxHtlcValueInFlightMsat = UInt64(Random.nextInt(Int.MaxValue)),
      channelReserveSatoshis = Random.nextInt(Int.MaxValue),
      htlcMinimumMsat = Random.nextInt(Int.MaxValue),
      toSelfDelay = Random.nextInt(Short.MaxValue),
      maxAcceptedHtlcs = Random.nextInt(Short.MaxValue),
      defaultFinalScriptPubKey = randomBytes(10 + Random.nextInt(200)),
      isFunder = Random.nextBoolean(),
      globalFeatures = randomBytes(256),
      localFeatures = randomBytes(256))
    val encoded = localParamsCodec.encode(o).require
    val decoded = localParamsCodec.decode(encoded).require
    assert(o === decoded.value)
  }

  test("encode/decode remoteparams") {
    val o = RemoteParams(
      nodeId = randomKey.publicKey,
      dustLimitSatoshis = Random.nextInt(Int.MaxValue),
      maxHtlcValueInFlightMsat = UInt64(Random.nextInt(Int.MaxValue)),
      channelReserveSatoshis = Random.nextInt(Int.MaxValue),
      htlcMinimumMsat = Random.nextInt(Int.MaxValue),
      toSelfDelay = Random.nextInt(Short.MaxValue),
      maxAcceptedHtlcs = Random.nextInt(Short.MaxValue),
      fundingPubKey = randomKey.publicKey,
      revocationBasepoint = randomKey.publicKey.value,
      paymentBasepoint = randomKey.publicKey.value,
      delayedPaymentBasepoint = randomKey.publicKey.value,
      htlcBasepoint = randomKey.publicKey.value,
      globalFeatures = randomBytes(256),
      localFeatures = randomBytes(256))
    val encoded = remoteParamsCodec.encode(o).require
    val decoded = remoteParamsCodec.decodeValue(encoded).require
    assert(o === decoded)
  }

  test("encode/decode direction") {
    assert(directionCodec.decodeValue(directionCodec.encode(IN).require).require === IN)
    assert(directionCodec.decodeValue(directionCodec.encode(OUT).require).require === OUT)
  }

  test("encode/decode htlc") {
    val add = UpdateAddHtlc(
      channelId = randomBytes(32),
      id = Random.nextInt(Int.MaxValue),
      amountMsat = Random.nextInt(Int.MaxValue),
      expiry = Random.nextInt(Int.MaxValue),
      paymentHash = randomBytes(32),
      onionRoutingPacket = randomBytes(Sphinx.PacketLength))
    val htlc1 = DirectedHtlc(direction = IN, add = add)
    val htlc2 = DirectedHtlc(direction = OUT, add = add)
    assert(htlcCodec.decodeValue(htlcCodec.encode(htlc1).require).require === htlc1)
    assert(htlcCodec.decodeValue(htlcCodec.encode(htlc2).require).require === htlc2)
  }

  test("encode/decode commitment spec") {
    val add1 = UpdateAddHtlc(
      channelId = randomBytes(32),
      id = Random.nextInt(Int.MaxValue),
      amountMsat = Random.nextInt(Int.MaxValue),
      expiry = Random.nextInt(Int.MaxValue),
      paymentHash = randomBytes(32),
      onionRoutingPacket = randomBytes(Sphinx.PacketLength))
    val add2 = UpdateAddHtlc(
      channelId = randomBytes(32),
      id = Random.nextInt(Int.MaxValue),
      amountMsat = Random.nextInt(Int.MaxValue),
      expiry = Random.nextInt(Int.MaxValue),
      paymentHash = randomBytes(32),
      onionRoutingPacket = randomBytes(Sphinx.PacketLength))
    val htlc1 = DirectedHtlc(direction = IN, add = add1)
    val htlc2 = DirectedHtlc(direction = OUT, add = add2)
    val htlcs = Set(htlc1, htlc2)
    assert(setCodec(htlcCodec).decodeValue(setCodec(htlcCodec).encode(htlcs).require).require === htlcs)
    val o = CommitmentSpec(
      htlcs = Set(htlc1, htlc2),
      feeratePerKw = Random.nextInt(Int.MaxValue),
      toLocalMsat = Random.nextInt(Int.MaxValue),
      toRemoteMsat = Random.nextInt(Int.MaxValue)
    )
    val encoded = commitmentSpecCodec.encode(o).require
    val decoded = commitmentSpecCodec.decode(encoded).require
    assert(o === decoded.value)
  }

  test("encode/decode origin") {
    assert(originCodec.decodeValue(originCodec.encode(Local(None)).require).require === Local(None))
    val relayed = Relayed(randomBytes(32), 4324, 12000000L, 11000000L)
    assert(originCodec.decodeValue(originCodec.encode(relayed).require).require === relayed)
  }

  test("encode/decode map of origins") {
    val map = Map(
      1L -> Local(None),
      42L -> Relayed(randomBytes(32), 4324, 12000000L, 11000000L),
      130L -> Relayed(randomBytes(32), -45, 13000000L, 12000000L),
      1000L -> Relayed(randomBytes(32), 10, 14000000L, 13000000L),
      -32L -> Relayed(randomBytes(32), 54, 15000000L, 14000000L),
      -4L -> Local(None))
    assert(originsMapCodec.decodeValue(originsMapCodec.encode(map).require).require === map)
  }

  test("encode/decode map of spending txes") {
    val map = Map(
      OutPoint(randomBytes(32), 42) -> randomBytes(32),
      OutPoint(randomBytes(32), 14502) -> randomBytes(32),
      OutPoint(randomBytes(32), 0) -> randomBytes(32),
      OutPoint(randomBytes(32), 454513) -> randomBytes(32)
      )
    assert(spentMapCodec.decodeValue(spentMapCodec.encode(map).require).require === map)
  }

  test("HtlcTimeoutTx compatibility") {
    val tx1 = Transaction.read("0200000001adbb20ea41a8423ea937e76e8151636bf6093b70eaff942930d20576600521fd000000006b48304502210090587b6201e166ad6af0227d3036a9454223d49a1f11839c1a362184340ef0240220577f7cd5cca78719405cbf1de7414ac027f0239ef6e214c90fcaab0454d84b3b012103535b32d5eb0a6ed0982a0479bbadc9868d9836f6ba94dd5a63be16d875069184ffffffff028096980000000000220020c015c4a6be010e21657068fc2e6a9d02b27ebe4d490a25846f7237f104d1a3cd20256d29010000001600143ca33c2e4446f4a305f23c80df8ad1afdcf652f900000000")
    val tx2 = Transaction.read("020000000001012537488e9d066a8f3550cc9adc141a11668425e046e69e07f53bb831f3296cbf00000000000000000001bf8401000000000017a9143f398d81d3c42367b779ea869c7dd3b6826fbb7487024730440220477b961f6360ef6cb62a76898dcecbb130627c7e6a452646e3be601f04627c1f02202572313d0c0afecbfb0c7d0e47ba689427a54f3debaded6d406daa1f5da4918c01210291ed78158810ad867465377f5920036ea865a29b3a39a1b1808d0c3c351a4b4100000000")
    val input1 = InputInfo(OutPoint(tx1, 0), tx1.txOut.head, randomBytes(32))
    val input2 = InputInfo(OutPoint(tx2, 0), tx2.txOut.head, randomBytes(32))

    val htlcTimeoutTx1 = HtlcTimeoutTx(input1, tx1, randomBytes(32))
    val htlcTimeoutTx2 =
      // This is an old `HtlcTimeoutTx` which should now be `HtlcTimeoutTxLegacy`
      txWithInputInfoCodec.decodeValue(BitVector.fromHex("00030024d52085191bef26a0cc7fbf286c2bef9b85e9776155b94060120bc5d61c31b2b1000000000020bf840100000" +
      "0000017a9143f398d81d3c42367b779ea869c7dd3b6826fbb74870020111111111111111111111111111111111111111111111111111111111111111100c00200000" +
      "00001012537488e9d066a8f3550cc9adc141a11668425e046e69e07f53bb831f3296cbf00000000000000000001bf8401000000000017a9143f398d81d3c42367b77" +
      "9ea869c7dd3b6826fbb7487024730440220477b961f6360ef6cb62a76898dcecbb130627c7e6a452646e3be601f04627c1f02202572313d0c0afecbfb0c7d0e47ba6" +
      "89427a54f3debaded6d406daa1f5da4918c01210291ed78158810ad867465377f5920036ea865a29b3a39a1b1808d0c3c351a4b4100000000").get).require

    val htlcTxAndSigs1 = HtlcTxAndSigs(htlcTimeoutTx1, randomBytes(64), randomBytes(64))
    val htlcTxAndSigs2 = HtlcTxAndSigs(htlcTimeoutTx2, randomBytes(64), randomBytes(64))
    val publishableTxs = PublishableTxs(CommitTx(input2, tx1), List(htlcTxAndSigs1, htlcTxAndSigs2))

    val decoded = publishableTxsCodec.decodeValue(publishableTxsCodec.encode(publishableTxs).require).require
    assert(decoded === publishableTxs)
    assert(decoded.htlcTxsAndSigs(1).txinfo.isInstanceOf[HtlcTimeoutTxLegacy])
  }
}
