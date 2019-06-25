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
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto, DeterministicWallet, MilliSatoshi, OutPoint, Satoshi, Transaction}
import fr.acinq.eclair._
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.{LocalKeyManager, ShaChain, Sphinx}
import fr.acinq.eclair.payment.{Local, Relayed}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions.CommitTx
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.ChannelCodecs._
import org.scalatest.FunSuite
import scodec.bits._
import scodec.{Attempt, DecodeResult}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.io.Source
import scala.util.Random

/**
  * Created by PM on 31/05/2016.
  */

class ChannelCodecsSpec extends FunSuite {

  import ChannelCodecsSpec._

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

  test("basic serialization test (NORMAL)") {
    val data = normal
    val bin = ChannelCodecs.DATA_NORMAL_Codec.encode(data).require
    val check = ChannelCodecs.DATA_NORMAL_Codec.decodeValue(bin).require
    assert(data.commitments.localCommit.spec === check.commitments.localCommit.spec)
    assert(data === check)
  }

  test("nonreg for der/bin64 signatures") {
    val bin = ByteVector.fromValidHex(Source.fromInputStream(getClass.getResourceAsStream("/normal_data_htlcs.bin")).mkString)
    val c = ChannelCodecs.stateDataCodec.decode(bin.toBitVector).require.value

    val ref = Seq(
      (hex"30440220104aed8d52fe50e2313a9a456607838a0cdac75fdc37afe581c415e7a20da944022034d1ac69c64f34571251be8cc6b50116f26453813267fb9afa3e318a79f4f32401", hex"304502210097fcda40b22916b5d61badedf6126658c2b5927d5002cc2c3e5f88a78ba5f45b02204a74bcf8827d894cab153fc051f39d8e2aeb660162a6a05797f7140587a6133301"),
      (hex"30450221009e0e57886b81fd4159f672728891d799203b23c351e93134aba445b288443d3502207b77faa4227126b7d087144c75f4e5c8af9db705b37806dbd2d3f4339666d32201", hex"3045022100aa403fa23e82379a16ba16b446dbdee5a4f879ba690ad3f4f10dc445df2832ba022000a51fdbdb69dcbd5518274303fcece60d719cb6d593e882fdb0190253bbaaab01"),
      (hex"3045022100fb44e66fc294d9ece2c33465398221edcfd857208d32a36dedab9906d00b356d022008c5fcfa7b41f8616d57ed009a8614aca636edae1479b6114e03407ba6fceea701", hex"3045022100a9ad65dada5e5500897173bca610135a13008895ce445fbc90d440d5406bd6150220644d75e5ca774ef6b559ffaf1083a9a5da250c3c87666d96daf35b480ef0c65701"),
      (hex"3044022009e4f39656dc8712863bffe2acdfa4d2245f65f38a230dd034b226dc2e5fd7ce022049c0108e44c399d1a1b67ab6f60566f491d8682406ac4a03a914f9a21196d6ba01", hex"3044022063a6839c031fd5534d7807a8fff8ca0f9a72d8aa9d78ee104d6ece2b417ac5ce0220193d9b521a44011d31d2bb85be5043119c42a7aee3d9ef68b7388c3c9c3a780501"),
      (hex"304402207eaf435948b9e04cb6551f97ee5d85ac879e20d3fae3f5c9a0880ef452d32ac902206e9c5c9098c3e3bef010d3142578823c7fb43b43fe0a0036d481f18a0168b20f01", hex"304402205dda44c9d8aaf37a6f5f6c99713d2a001682f2593a960ccaf5c23059cd20016b02200991b09bccdfc87918852650a4bfa7b4ac9028101362631b5ec376427084138e01"),
      (hex"304402200232dbb9d46dabc6569f3f65f4f2a4b7e5acf7be85687b9897141e9784cb9d370220087b2c1dda444d7351976135b56f2f2ca22d8c03d5aa40acbce8c4241daf541501", hex"3045022100eddaa4f767bc70fd672bee983b1644dbff9479def0efc7cca79f0daa1bad370d02204c810238968ae9e86b99d348464e9ac7a06e40225022ae4203ae36fad928c22401"),
      (hex"3045022100daa604934db542aa5a9bcbd48eb666fac8acdee92ccd8d42228f52377c51184a022069f855477b27cec39b15fb9e666c09b6c4860c8b80cd1315d2498d97d9cf024601", hex"3044022020e6d43dee03f54574d8245edf2e312d0a492dd2350b7f8df68390b8876de5640220555d46cd545ff0ecc280e6bc82e976ff494bab5f2b128807626753ffb9e5796e01"),
      (hex"3044022046c3cf88f9e8639c954c584725482dd6e6403deda3824c37ae95db9bf99d341602206432f76c5ca3d61951155c1b223fd35dd4227f83e1ff9a93437b63515567d23f01", hex"3045022100812a360a6ddc44179f80e5b4252bca74bb5dbe1da25230c9e8afcd388a2fd64702202e45a658123f0263ca1157ef9a9995ede1625d1ecba532957185f1d8044aa1d301"),
      (hex"30440220482df018e51b4f684271682bc3c81c481d288d61000a77df2126afe042c3471d02204772720ff1ea323a271259022a0458ae4d228e5f613ade63fca38eb5d443756a01", hex"3044022076a338d225b8954412198ce5936aaa6433da1f51dd9bcbe69d95a1e0960c169802207db267517fc73e358e09f4c89313ae17ed4d5f6d8432faec9ec1e784a2a7da7c01"),
      (hex"3045022100916255b5758d66cd46f653f8a5a71b1c857bfae1a7cf85195b5d78476c4138c502200101e3ec9874aa2644691662bf8945a182af1237bb61c459e9dbff495b9097d001", hex"304402201d099a464a7696b22a8e58b65c52e9a519a06a5c49e944155d4e5fbd14d3f5b902203c091c0ec5b840a80be739d29b5fc2c75cb94928e5ea83133f84d226f28cd4b701"),
      (hex"3045022100d8eaa436faec6b38f155065893f1212ce43615fbec49b4af72f16119774b5472022033aa303992f4a8cfe1c77e6a0d2baa73baad0305a88da16d26122e536867431101", hex"304402203af7b7ea16cc018fdb414f52cd38ed548dc257cbb06c812c9dc1d60500b21485022072cd74b7e49bfd813e09bae778da903b44b7b0ae22b87af4c34cf8bb77dfdef201"),
      (hex"304402204f5dd042bfb449c522012a2d461e5a94c9ea3be629c0ab091b0e1f5569eb119c022021411ff8affabab12cd39f0eaa64f1b08fa72ada6f37d1d46c6bde4483d869fb01", hex"3044022043573edb37be815d1b97b90803f601dfc91c25279ccda606ad6515fee721fe57022030ac2883408a2075a47337443eb539062a8ac6b5453befb2b9863d697e35dd8201"),
      (hex"3044022030ff3d4d42ef1c3d742164a30ff7b021215e881d9277a52a1720514a4473289502204b090f6b412e8caacb5bcbf295babb075d9d5490e3f7678c289206780f6f0bc901", hex"304502210093fd7dfa3ef6cdf5b94cfadf83022be98062d53cd7097a73947453b210a481eb0220622e63a21b787ea7bb55f01ab6fe503fcb8ef4cb65adce7a264ae014403646fe01")
    )

    val sigs = c.commitments
      .localCommit
      .publishableTxs
      .htlcTxsAndSigs
      .map(data => (Scripts.der(data.localSig), Scripts.der(data.remoteSig)))

    assert(ref === sigs)
  }

  test("backward compatibility DATA_WAIT_FOR_FUNDING_CONFIRMED") {
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
    // data should now be encoded under the new format, with version=0 and type=8
    assert(bin_new.startsWith(hex"000008"))
    // now let's decode it again
    val data_new2 = stateDataCodec.decode(bin_new.toBitVector).require.value
    // data should match perfectly
    assert(data_new === data_new2)
  }

  test("tell channel_update length") {
    // with option_channel_htlc_max (136 bytes)
    val u1 = hex"81D6DE1B150A0EAD83B214C879BD55FB3EE82E54F3F38DF601B692B481DFFBBF66582C018B271FBEBF17B0F72FAD1E1B78EF19232621AA2D8055B51D94C5A39543497FD7F826957108F4A30FD9CEC3AEBA79972084E90EAD01EA33090000000013AB9500006E00005D117A190100009000000000000003E8000003E80000000100000003E8000000".bits
    // without option_channel_htlc_max (128 bytes)
    val u2 = hex"A94A853FCDE515F89259E03D10368B1A600B3BF78F6BD5C968469C0816F45EFF7878714DF26B580D5A304334E46816D5AC37B098EBC46C1CE47E37504D052DD643497FD7F826957108F4A30FD9CEC3AEBA79972084E90EAD01EA33090000000013AB9500006E00005D1149290001009000000000000003E8000003E800000001".bits

    // check that we decode correct length, and that we just take a peek without actually consuming data
    assert(noUnknownFieldsChannelUpdateSizeCodec.decode(u1) == Attempt.successful(DecodeResult(136, u1)))
    assert(noUnknownFieldsChannelUpdateSizeCodec.decode(u2) == Attempt.successful(DecodeResult(128, u2)))
  }

}

object ChannelCodecsSpec {
  val keyManager = new LocalKeyManager(ByteVector32(ByteVector.fill(32)(1)), Block.RegtestGenesisBlock.hash)
  val localParams = LocalParams(
    keyManager.nodeId,
    channelKeyPath = DeterministicWallet.KeyPath(Seq(42L)),
    dustLimitSatoshis = Satoshi(546).toLong,
    maxHtlcValueInFlightMsat = UInt64(50000000),
    channelReserveSatoshis = 10000,
    htlcMinimumMsat = 10000,
    toSelfDelay = 144,
    maxAcceptedHtlcs = 50,
    defaultFinalScriptPubKey = ByteVector.empty,
    isFunder = true,
    globalFeatures = hex"dead",
    localFeatures = hex"beef")

  val remoteParams = RemoteParams(
    nodeId = randomKey.publicKey,
    dustLimitSatoshis = Satoshi(546).toLong,
    maxHtlcValueInFlightMsat = UInt64(5000000),
    channelReserveSatoshis = 10000,
    htlcMinimumMsat = 5000,
    toSelfDelay = 144,
    maxAcceptedHtlcs = 50,
    fundingPubKey = PrivateKey(ByteVector32(ByteVector.fill(32)(1)) :+ 1.toByte).publicKey,
    revocationBasepoint = PrivateKey(ByteVector.fill(32)(2)).publicKey,
    paymentBasepoint = PrivateKey(ByteVector.fill(32)(3)).publicKey,
    delayedPaymentBasepoint = PrivateKey(ByteVector.fill(32)(4)).publicKey,
    htlcBasepoint = PrivateKey(ByteVector.fill(32)(6)).publicKey,
    globalFeatures = hex"dead",
    localFeatures = hex"beef")

  val paymentPreimages = Seq(
    ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"),
    ByteVector32(hex"0101010101010101010101010101010101010101010101010101010101010101"),
    ByteVector32(hex"0202020202020202020202020202020202020202020202020202020202020202"),
    ByteVector32(hex"0303030303030303030303030303030303030303030303030303030303030303"),
    ByteVector32(hex"0404040404040404040404040404040404040404040404040404040404040404")
  )

  val htlcs = Seq(
    DirectedHtlc(IN, UpdateAddHtlc(ByteVector32.Zeroes, 0, MilliSatoshi(1000000).amount, Crypto.sha256(paymentPreimages(0)), 500, ByteVector.fill(Sphinx.PacketLength)(0))),
    DirectedHtlc(IN, UpdateAddHtlc(ByteVector32.Zeroes, 1, MilliSatoshi(2000000).amount, Crypto.sha256(paymentPreimages(1)), 501, ByteVector.fill(Sphinx.PacketLength)(0))),
    DirectedHtlc(OUT, UpdateAddHtlc(ByteVector32.Zeroes, 30, MilliSatoshi(2000000).amount, Crypto.sha256(paymentPreimages(2)), 502, ByteVector.fill(Sphinx.PacketLength)(0))),
    DirectedHtlc(OUT, UpdateAddHtlc(ByteVector32.Zeroes, 31, MilliSatoshi(3000000).amount, Crypto.sha256(paymentPreimages(3)), 503, ByteVector.fill(Sphinx.PacketLength)(0))),
    DirectedHtlc(IN, UpdateAddHtlc(ByteVector32.Zeroes, 2, MilliSatoshi(4000000).amount, Crypto.sha256(paymentPreimages(4)), 504, ByteVector.fill(Sphinx.PacketLength)(0)))
  )

  val fundingTx = Transaction.read("0200000001adbb20ea41a8423ea937e76e8151636bf6093b70eaff942930d20576600521fd000000006b48304502210090587b6201e166ad6af0227d3036a9454223d49a1f11839c1a362184340ef0240220577f7cd5cca78719405cbf1de7414ac027f0239ef6e214c90fcaab0454d84b3b012103535b32d5eb0a6ed0982a0479bbadc9868d9836f6ba94dd5a63be16d875069184ffffffff028096980000000000220020c015c4a6be010e21657068fc2e6a9d02b27ebe4d490a25846f7237f104d1a3cd20256d29010000001600143ca33c2e4446f4a305f23c80df8ad1afdcf652f900000000")
  val fundingAmount = fundingTx.txOut(0).amount
  val commitmentInput = Funding.makeFundingInputInfo(fundingTx.hash, 0, fundingAmount, keyManager.fundingPublicKey(localParams.channelKeyPath).publicKey, remoteParams.fundingPubKey)

  val localCommit = LocalCommit(0, CommitmentSpec(htlcs.toSet, 1500, 50000000, 70000000), PublishableTxs(CommitTx(commitmentInput, Transaction(2, Nil, Nil, 0)), Nil))
  val remoteCommit = RemoteCommit(0, CommitmentSpec(htlcs.map(htlc => htlc.copy(direction = htlc.direction.opposite)).toSet, 1500, 50000, 700000), ByteVector32(hex"0303030303030303030303030303030303030303030303030303030303030303"), PrivateKey(ByteVector.fill(32)(4)).publicKey)
  val commitments = Commitments(localParams, remoteParams, channelFlags = 0x01.toByte, localCommit, remoteCommit, LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
    localNextHtlcId = 32L,
    remoteNextHtlcId = 4L,
    originChannels = Map(42L -> Local(UUID.randomUUID, None), 15000L -> Relayed(ByteVector32(ByteVector.fill(32)(42)), 43, 11000000L, 10000000L)),
    remoteNextCommitInfo = Right(randomKey.publicKey),
    commitInput = commitmentInput, remotePerCommitmentSecrets = ShaChain.init, channelId = ByteVector32.Zeroes)

  val channelUpdate = Announcements.makeChannelUpdate(ByteVector32(ByteVector.fill(32)(1)), randomKey, randomKey.publicKey, ShortChannelId(142553), 42, 15, 575, 53, Channel.MAX_FUNDING_SATOSHIS * 1000L)

  val normal = DATA_NORMAL(commitments, ShortChannelId(42), true, None, channelUpdate, None, None)
}
