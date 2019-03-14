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
import fr.acinq.bitcoin.{DeterministicWallet, OutPoint}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.{Local, Relayed}
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.ChannelCodecs._
import fr.acinq.eclair.{UInt64, randomBytes, randomBytes32, randomKey}
import org.scalatest.FunSuite
import scodec.bits._

import scala.compat.Platform
import scala.util.Random

/**
  * Created by PM on 31/05/2016.
  */

class ChannelCodecsSpec extends FunSuite {

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
      channelId = randomBytes32,
      id = Random.nextInt(Int.MaxValue),
      amountMsat = Random.nextInt(Int.MaxValue),
      cltvExpiry = Random.nextInt(Int.MaxValue),
      paymentHash = randomBytes32,
      onionRoutingPacket = randomBytes(Sphinx.PacketLength))
    val htlc1 = DirectedHtlc(direction = IN, add = add)
    val htlc2 = DirectedHtlc(direction = OUT, add = add)
    assert(htlcCodec.decodeValue(htlcCodec.encode(htlc1).require).require === htlc1)
    assert(htlcCodec.decodeValue(htlcCodec.encode(htlc2).require).require === htlc2)
  }

  test("encode/decode commitment spec") {
    val add1 = UpdateAddHtlc(
      channelId = randomBytes32,
      id = Random.nextInt(Int.MaxValue),
      amountMsat = Random.nextInt(Int.MaxValue),
      cltvExpiry = Random.nextInt(Int.MaxValue),
      paymentHash = randomBytes32,
      onionRoutingPacket = randomBytes(Sphinx.PacketLength))
    val add2 = UpdateAddHtlc(
      channelId = randomBytes32,
      id = Random.nextInt(Int.MaxValue),
      amountMsat = Random.nextInt(Int.MaxValue),
      cltvExpiry = Random.nextInt(Int.MaxValue),
      paymentHash = randomBytes32,
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
    val relayed = Relayed(randomBytes32, 4324, 12000000L, 11000000L)
    assert(originCodec.decodeValue(originCodec.encode(relayed).require).require === relayed)
  }

  test("encode/decode map of origins") {
    val map = Map(
      1L -> Local(None),
      42L -> Relayed(randomBytes32, 4324, 12000000L, 11000000L),
      130L -> Relayed(randomBytes32, -45, 13000000L, 12000000L),
      1000L -> Relayed(randomBytes32, 10, 14000000L, 13000000L),
      -32L -> Relayed(randomBytes32, 54, 15000000L, 14000000L),
      -4L -> Local(None))
    assert(originsMapCodec.decodeValue(originsMapCodec.encode(map).require).require === map)
  }

  test("encode/decode map of spending txes") {
    val map = Map(
      OutPoint(randomBytes32, 42) -> randomBytes32,
      OutPoint(randomBytes32, 14502) -> randomBytes32,
      OutPoint(randomBytes32, 0) -> randomBytes32,
      OutPoint(randomBytes32, 454513) -> randomBytes32
    )
    assert(spentMapCodec.decodeValue(spentMapCodec.encode(map).require).require === map)
  }

  test("backwards compatibility DATA_WAIT_FOR_FUNDING_CONFIRMED") {
    // this is a DATA_WAIT_FOR_FUNDING_CONFIRMED encoded with the previous version of the codec (at commit 997aceea82942769649bf03e95c5ea549a7beb0c)
    val bin_old = hex"000001033785fe882e8682340d11df42213f182755531bd587ae305e0062f563a52d841800049b86343ecd1baa49e0304a6e32dddeb50000000000000222000000012a05f2000000000000004e2000000000000000010090001e800bd48a66494d6275d73cc4b8e167bf840c9261a471271ec380000000c501c99c425578eb58841cbf2f7f2e435e796c654697b8076d4cedc90a7e1389589a0000000000000111000000009502f900000000000000271000000000000000008048000f01419f088c6cd366cadb656148952b730fdf73ccbcf030758c008e6a900f756bfb011fa7aae5886b9c34c5f264d996a7a1def7566424c0f90db8b688794b9ca43db8017331002fd85b09961fa68a1a6c2bc995e717ecf99f10c428a79457a46ce5d47b01325bcce8670aa8e0373c279531553ef280791c283189b1acdee55c21d239cdd90196e7a6fc79329b936e87a07b2d429e7b61ff89db92a4c66ec70e2562a14664570000000140410080000000000000000000000002ee000000003b9aca000000000000000000001278970eefbc9e9c7f44d0ab40555711618404aebd87d861ceca9867ce4f7bfe5d000000000015c0420f0000000000110010326963ce09728e7b80fd51c6d6b7f64d6e124cf55c0d2e887bc862499e325da00023a91081419f088c6cd366cadb656148952b730fdf73ccbcf030758c008e6a900f756bfb1081484d9633cfc4a9ab9f4220a7b3c679e648f18e53a7dadb7154242943b587415aa957009d81000000000080f8970eefbc9e9c7f44d0ab40555711618404aebd87d861ceca9867ce4f7bfe5d00000000007b7ef94000a1400f000000000011001013dc34f3aebaa16836581958168fcc55a5719f1a0c312ea9033c110afb4c677c82002418228110806fb9c53b82a46021cf2b48a18dc49434e9d207f3bff2f09c84e5df5f9526057481100c5bab065bd059dafb7ccef1ce14a850a4bb206b132b53cb9df70faa5be8812e00a39822011021c7d70a781a0ceb7605bed811e9aacdbff8c71f2904d54a7f57a46bd735c9f901101b6fde320e8cc9388538ab65bb2f55ac7cec1218163ec0d590e13ac3fbd7d60e80a3a91081419f088c6cd366cadb656148952b730fdf73ccbcf030758c008e6a900f756bfb1081484d9633cfc4a9ab9f4220a7b3c679e648f18e53a7dadb7154242943b587415aa95770612810000000000000000000000000000002ee0000000000000000000000003b9aca0030e78713eebb77ebfb57a70dee19a85a4e54a11fdf74c9fda7fc108ceaa942db8133978aafdb8e7232a61dca8495134873c1c9ceebb926c85256f432b73b111a9f00000000000000000000000000000000000000000000000000000000000040f5a7aef68b8bc802d20427a43145bfbeded7d322c363f661569a38c58064da6700093c4b8777de4f4e3fa26855a02aab88b0c202575ec3ec30e7654c33e727bdff2e80000000000ae0210780000000000880081934b1e704b9473dc07ea8e36b5bfb26b709267aae0697443de43124cf192ed00011d48840a0cf84463669b3656db2b0a44a95b987efb9e65e78183ac60047354807bab5fd8840a426cb19e7e254d5cfa11053d9e33cf32478c729d3ed6db8aa1214a1dac3a0ad54ab80001e25c3bbef27a71fd1342ad01555c45861012baf61f61873b2a619f393deff9740237cd8e4ea0093bb773bab03a938d5e35e9ebbb5b321ffd7ee031feff96eb82f8970eefbc9e9c7f44d0ab40555711618404aebd87d861ceca9867ce4f7bfe5d000006b0aade318a54c3339808654a781d421412758912d2df1b039399ffed52d3b784f698e9230da056f09212e8ac5cebe7ab1283c08aa3cd548ed5e1a4f81ebfe10"
    // currently version=0 and discriminator type=1
    assert(bin_old.startsWith(hex"000001"))
    // let's decode the old data (this will use the old codec that provides default values for new fields)
    val data_new = stateDataCodec.decode(bin_old.toBitVector).require.value
    assert(data_new.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx === None)
    assert(Platform.currentTime / 1000 - data_new.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].waitingSince < 3600) // we just set this timestamp to current time
    // and re-encode it with the new codec
    val bin_new = ByteVector(stateDataCodec.encode(data_new).require.toByteVector.toArray)
    // data should now be encoded under the new format, with version=0 and type=8
    assert(bin_new.startsWith(hex"000008"))
    // now let's decode it again
    val data_new2 = stateDataCodec.decode(bin_new.toBitVector).require.value
    // data should match perfectly
    assert(data_new === data_new2)
  }

}
