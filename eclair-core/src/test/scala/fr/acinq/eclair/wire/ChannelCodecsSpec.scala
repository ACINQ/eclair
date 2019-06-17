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

package fr.acinq.eclair.wire

import java.util.UUID

import akka.actor.ActorSystem
import fr.acinq.bitcoin.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.{Crypto, DeterministicWallet, OutPoint}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.{Local, Relayed}
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.ChannelCodecs._
import fr.acinq.eclair.{UInt64, randomBytes, randomBytes32, randomKey}
import org.scalatest.FunSuite
import scodec.bits._

import scala.compat.Platform
import scala.concurrent.duration._
import scala.util.Random
import scala.concurrent.duration._

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
    val localParamFundee = LocalParams(
      nodeId = randomKey.publicKey,
      channelKeyPath = Right(KeyPathFundee(KeyPath(Seq(4, 3, 2, 1L)), KeyPath(Seq(1, 2, 3, 4L)))),
      dustLimitSatoshis = Random.nextInt(Int.MaxValue),
      maxHtlcValueInFlightMsat = UInt64(Random.nextInt(Int.MaxValue)),
      channelReserveSatoshis = Random.nextInt(Int.MaxValue),
      htlcMinimumMsat = Random.nextInt(Int.MaxValue),
      toSelfDelay = Random.nextInt(Short.MaxValue),
      maxAcceptedHtlcs = Random.nextInt(Short.MaxValue),
      defaultFinalScriptPubKey = randomBytes(10 + Random.nextInt(200)),
      globalFeatures = randomBytes(256),
      localFeatures = randomBytes(256))

    val encoded = localParamsCodec(CommitmentV1).encode(localParamFundee).require
    val decoded = localParamsCodec(CommitmentV1).decode(encoded).require
    assert(localParamFundee === decoded.value)

    val localParamFunder = LocalParams(
      nodeId = randomKey.publicKey,
      channelKeyPath = Left(KeyPath(Seq(4, 3, 2, 1L))),
      dustLimitSatoshis = Random.nextInt(Int.MaxValue),
      maxHtlcValueInFlightMsat = UInt64(Random.nextInt(Int.MaxValue)),
      channelReserveSatoshis = Random.nextInt(Int.MaxValue),
      htlcMinimumMsat = Random.nextInt(Int.MaxValue),
      toSelfDelay = Random.nextInt(Short.MaxValue),
      maxAcceptedHtlcs = Random.nextInt(Short.MaxValue),
      defaultFinalScriptPubKey = randomBytes(10 + Random.nextInt(200)),
      globalFeatures = randomBytes(256),
      localFeatures = randomBytes(256))

    val encoded1 = localParamsCodec(CommitmentV1).encode(localParamFunder).require
    val decoded1 = localParamsCodec(CommitmentV1).decode(encoded1).require
    assert(localParamFunder === decoded1.value)
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
      revocationBasepoint = randomKey.publicKey,
      paymentBasepoint = randomKey.publicKey,
      delayedPaymentBasepoint = randomKey.publicKey,
      htlcBasepoint = randomKey.publicKey,
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
    val id = UUID.randomUUID()
    assert(originCodec.decodeValue(originCodec.encode(Local(id, Some(ActorSystem("system").deadLetters))).require).require === Local(id, None))
    // TODO: add backward compatibility check
    val relayed = Relayed(randomBytes32, 4324, 12000000L, 11000000L)
    assert(originCodec.decodeValue(originCodec.encode(relayed).require).require === relayed)
  }

  test("encode/decode map of origins") {
    val map = Map(
      1L -> Local(UUID.randomUUID(), None),
      42L -> Relayed(randomBytes32, 4324, 12000000L, 11000000L),
      130L -> Relayed(randomBytes32, -45, 13000000L, 12000000L),
      1000L -> Relayed(randomBytes32, 10, 14000000L, 13000000L),
      -32L -> Relayed(randomBytes32, 54, 15000000L, 14000000L),
      -4L -> Local(UUID.randomUUID(), None))
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
    assert(Platform.currentTime.milliseconds.toSeconds - data_new.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].waitingSince < 3600) // we just set this timestamp to current time
    // and re-encode it with the new codec
    val bin_new = ByteVector(stateDataCodec.encode(data_new).require.toByteVector.toArray)
    // data should now be encoded under the new format, with version=1 and type=8
    assert(bin_new.startsWith(hex"010008"))
    // now let's decode it again
    val data_new2 = stateDataCodec.decode(bin_new.toBitVector).require.value
    // data should match perfectly
    assert(data_new === data_new2)
  }

  test("backwards compatibility DATA_NORMAL for fundee scenario") {
    // this is a DATA_NORMAL encoded with the previous version of the codec (at commit 849b6bd22be6a7550c7e915e3824201ba530743d)
    val bin_old = hex"00000302A9D7232139779A93C72EDA7D9519957F7BC6C802D8618CA5A75C0B87EBFBC76F0004AA5FAE018E7726D9074CAB0245C7FB470000000000000222000000012A05F20000000000000186A0000000000000000102D0001E000B000A3A1B8E03A82FE05383331ED4E197CC58AB1710F680000000C101BC107808C08DE5B23D000090742B42EE4B837857EAB0A6B9043694B822007B1F8000000000000111000000009502F900000000000000C35000000000000000008168000F015A8ACA042BCAB0F9CD055A0FE603764A6A416D3329A70818FC239C243F3E619481596A18360D46F3CEEF9AE150F2C0569561CFDC2A758800C1D209FD4D8B89B11001DB791A4573BD56E416F0BD4EA61D670C8F3CB417414B261117806D3C28857421819FAD914FF84A7FDAF242B3D6FBBA0B87B9283A7B132BDFE0FEDA5D3D773B73E1814CAEC057CB90CA0E5B83011DB0951BC2235C942D4BA32881FE785DAC4684692900000000C10080000000000000000000000057E40000000000000000000000012A05F2000012111CACBC8C568791785028DDFB2FCA4E9CA05245563BABF3757CB8080C0122BA800000000015C04B4C00000000001100104FDEC3872BA5D67B7380F99ADA446FAB7248F007A575338A1CB14DA5B807E20E0023A910811F9D8A833659E851E19BA22B9B6CCE3EEF65CD999424658E6852B6921B2DE36990815A8ACA042BCAB0F9CD055A0FE603764A6A416D3329A70818FC239C243F3E6194A957009801000000000080911CACBC8C568791785028DDFB2FCA4E9CA05245563BABF3757CB8080C0122BA80000000000E3359C0009E0BCC00000000000B000A17856E8874244BFF977A80228A098DF977384EEE02002418228110804A81F0F66B3D1E90E7410F900E3B767690E64CAB3C4312BB00EAB69DF32EEFD1011016A5F92AFD49F8E92AE5B1533A453AE4F0495EBA595416DF47F5A25A327E4CBE00A4182281108057167EFC9876DD5ACB23966777EAE4909345FB0B385FEC48A92ABCAD720594A6011035DED61D19D80AEE2405DA6A1AFC449D5F92B05F34A861EBDB8C42AACB38C7C480A3A910811F9D8A833659E851E19BA22B9B6CCE3EEF65CD999424658E6852B6921B2DE36990815A8ACA042BCAB0F9CD055A0FE603764A6A416D3329A70818FC239C243F3E6194A95762CFE410000000000000000000000000000057E4000000012A05F20000000000000000004A0C0E9BB8C3F6CCABC0974A7EFF83215DBF7F95C03E4F4294E8BC3BA119037101DB4160554EC9F3F65EB6747990098A3B6E31037823E935FDA668BBB7BDBDF511000000000000000000000000000000000000000000000000000000000000408D6E453F3F302236BF1543948883FDAD07BE54339851A7EF79081322BA11C4118009088E565E462B43C8BC28146EFD97E5274E502922AB1DD5F9BABE5C040600915D40000000000AE025A6000000000008800827EF61C395D2EB3DB9C07CCD6D2237D5B9247803D2BA99C50E58A6D2DC03F1070011D488408FCEC5419B2CF428F0CDD115CDB6671F77B2E6CCCA1232C734295B490D96F1B4C840AD45650215E5587CE682AD07F301BB253520B69994D3840C7E11CE121F9F30CA54AB800004472B2F2315A1E45E140A377ECBF293A7281491558EEAFCDD5F2E02030048AEA0011CC00000200019D45EF1751408B9C2BA601951D8445B9BA0A205FCFE911C73CAC093080FE315B0451A5C6DA9B9C0280CEEE6BF34392FB2F9AE7450CB38D5851BAEFA6FB1AB68BA477A4F0318E6BBEF8BA89828DCDC585CC50D6EB848D1D3F29E944AE854272E5ACC602FFCA39A291F1CD1CA9D9BC0EB1D7EB087BE6B15BBD5C4081602C513399836201BEB92FC7912229F53FB7B671052E4EC9654D02CA41D6D86A094F1A83B0BD0FB34400443CDF9D88DA54247B9B2F0E9B107F12C4EC040EE0B92BC2C31761EE735ADBC0E763B21B4DA08870533F2C0058404FD248EA3306CE4F6AB3B6ABA320C077D223A1B165527612C2F97400DE578842A112E2220ED4C5CF299BD23ECB800003113723088D05ACE557893021F5ADDF9461A79D2F19950FE3D95B9E78C4488780047300000080000154EB91909CBBCD49E3976D3ECA8CCABFBDE364016C30C652D3AE05C3F5FDE3B781BC107808C08DE5B23D000090742B42EE4B837857EAB0A6B9043694B822007B1F811F9D8A833659E851E19BA22B9B6CCE3EEF65CD999424658E6852B6921B2DE369815A8ACA042BCAB0F9CD055A0FE603764A6A416D3329A70818FC239C243F3E6194D1F27730DEB0C49576CDDDBC06C61139AEF86B7AAEAC797EE3619F3DC74690DF01588F42CDF60665152590AC94EC01F3C9E2248C7CE0E8BF12CFBF4B7BAF1B8103113723088D05ACE557893021F5ADDF9461A79D2F19950FE3D95B9E78C4488780047300000080002E81CB30808100480000000000000000800001F400000032000000012A05F2000"
    // currently version=0 and discriminator type=3
     assert(bin_old.startsWith(hex"000003"))
    // let's decode the old data (this will use the old codec that provides default values for new fields)
    val data_new = stateDataCodec.decode(bin_old.toBitVector).require.value
    assert(data_new.asInstanceOf[DATA_NORMAL].commitments.localParams.isFunder == false)
    // and re-encode it with the new codec
    val bin_new = ByteVector(stateDataCodec.encode(data_new).require.toByteVector.toArray)
    // data should now be encoded under the new format, with version=1 and type=3
    assert(bin_new.startsWith(hex"010003"))
    // now let's decode it again
    val data_new2 = stateDataCodec.decode(bin_new.toBitVector).require.value
    // data should match perfectly
    assert(data_new === data_new2)
  }


}
