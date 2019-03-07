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

package fr.acinq.eclair.db

import fr.acinq.bitcoin.Crypto.{PrivateKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, Block, Crypto, DeterministicWallet, MilliSatoshi, Satoshi, Transaction}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.{LocalKeyManager, ShaChain, Sphinx}
import fr.acinq.eclair.payment.{Local, Relayed}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions.CommitTx
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.{ChannelCodecs, UpdateAddHtlc}
import fr.acinq.eclair.{ShortChannelId, UInt64, randomKey}
import org.scalatest.FunSuite
import scodec.bits.BitVector

/**
  * Created by fabrice on 07/02/17.
  */

class ChannelStateSpec extends FunSuite {

  import ChannelStateSpec._

  test("basic serialization test (NORMAL - CommitmentV1)") {
    val data = normal
    val bin = ChannelCodecs.genericStateDataCodec.encode(data).require
    val check = ChannelCodecs.genericStateDataCodec.decodeValue(bin).require

    assert(bin.take(8).toByte(signed = false) == ChannelCodecs.COMMITMENTv1_VERSION_BYTE)
    assert(data.commitments.localCommit.spec === check.commitments.localCommit.spec)
    assert(data === check)
  }

  test("basic serialization test (NORMAL - SimplifiedCommitment)") {
    val data = normalSimplified
    val bin = ChannelCodecs.genericStateDataCodec.encode(data).require
    val check = ChannelCodecs.genericStateDataCodec.decodeValue(bin).require

    assert(bin.take(8).toByte(signed = false) == ChannelCodecs.COMMITMENT_SIMPLIFIED_VERSION_BYTE)
    assert(data.commitments.localCommit.spec === check.commitments.localCommit.spec)
    assert(data === check)
  }

  test("backward compatible with previously stored commitments") {
    val state = ChannelCodecs.genericStateDataCodec.decodeValue(BitVector(rawCommitment.data)).require

    assert(state.commitments.localParams.nodeId.raw == BinaryData("036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96"))
    assert(state.commitments.version === VersionCommitmentV1)

    val bin = ChannelCodecs.genericStateDataCodec.encode(state).require
    val state1 = ChannelCodecs.genericStateDataCodec.decodeValue(bin).require

    assert(state === state1)
    assert(bin.size === 0)                      // 15329
    assert(BitVector(rawCommitment.data) === 0) // 15336
  }

}

object ChannelStateSpec {
  val keyManager = new LocalKeyManager("01" * 32, Block.RegtestGenesisBlock.hash)
  val localParams = LocalParams(
    keyManager.nodeId,
    channelKeyPath = DeterministicWallet.KeyPath(Seq(42L)),
    dustLimitSatoshis = Satoshi(546).toLong,
    maxHtlcValueInFlightMsat = UInt64(50000000),
    channelReserveSatoshis = 10000,
    htlcMinimumMsat = 10000,
    toSelfDelay = 144,
    maxAcceptedHtlcs = 50,
    defaultFinalScriptPubKey = Nil,
    isFunder = true,
    globalFeatures = "foo".getBytes(),
    localFeatures = "bar".getBytes())

  val remoteParams = RemoteParams(
    nodeId = randomKey.publicKey,
    dustLimitSatoshis = Satoshi(546).toLong,
    maxHtlcValueInFlightMsat = UInt64(5000000),
    channelReserveSatoshis = 10000,
    htlcMinimumMsat = 5000,
    toSelfDelay = 144,
    maxAcceptedHtlcs = 50,
    fundingPubKey = PrivateKey(BinaryData("01" * 32) :+ 1.toByte).publicKey,
    revocationBasepoint = Scalar(BinaryData("02" * 32)).toPoint,
    paymentBasepoint = Scalar(BinaryData("03" * 32)).toPoint,
    delayedPaymentBasepoint = Scalar(BinaryData("04" * 32)).toPoint,
    htlcBasepoint = Scalar(BinaryData("06" * 32)).toPoint,
    globalFeatures = "foo".getBytes(),
    localFeatures = "bar".getBytes())

  val paymentPreimages = Seq(
    BinaryData("0000000000000000000000000000000000000000000000000000000000000000"),
    BinaryData("0101010101010101010101010101010101010101010101010101010101010101"),
    BinaryData("0202020202020202020202020202020202020202020202020202020202020202"),
    BinaryData("0303030303030303030303030303030303030303030303030303030303030303"),
    BinaryData("0404040404040404040404040404040404040404040404040404040404040404")
  )

  val htlcs = Seq(
    DirectedHtlc(IN, UpdateAddHtlc("00" * 32, 0, MilliSatoshi(1000000).amount, Crypto.sha256(paymentPreimages(0)), 500, BinaryData("00" * Sphinx.PacketLength))),
    DirectedHtlc(IN, UpdateAddHtlc("00" * 32, 1, MilliSatoshi(2000000).amount, Crypto.sha256(paymentPreimages(1)), 501, BinaryData("00" * Sphinx.PacketLength))),
    DirectedHtlc(OUT, UpdateAddHtlc("00" * 32, 30, MilliSatoshi(2000000).amount, Crypto.sha256(paymentPreimages(2)), 502, BinaryData("00" * Sphinx.PacketLength))),
    DirectedHtlc(OUT, UpdateAddHtlc("00" * 32, 31, MilliSatoshi(3000000).amount, Crypto.sha256(paymentPreimages(3)), 503, BinaryData("00" * Sphinx.PacketLength))),
    DirectedHtlc(IN, UpdateAddHtlc("00" * 32, 2, MilliSatoshi(4000000).amount, Crypto.sha256(paymentPreimages(4)), 504, BinaryData("00" * Sphinx.PacketLength)))
  )

  val fundingTx = Transaction.read("0200000001adbb20ea41a8423ea937e76e8151636bf6093b70eaff942930d20576600521fd000000006b48304502210090587b6201e166ad6af0227d3036a9454223d49a1f11839c1a362184340ef0240220577f7cd5cca78719405cbf1de7414ac027f0239ef6e214c90fcaab0454d84b3b012103535b32d5eb0a6ed0982a0479bbadc9868d9836f6ba94dd5a63be16d875069184ffffffff028096980000000000220020c015c4a6be010e21657068fc2e6a9d02b27ebe4d490a25846f7237f104d1a3cd20256d29010000001600143ca33c2e4446f4a305f23c80df8ad1afdcf652f900000000")
  val fundingAmount = fundingTx.txOut(0).amount
  val commitmentInput = Funding.makeFundingInputInfo(fundingTx.hash, 0, fundingAmount, keyManager.fundingPublicKey(localParams.channelKeyPath).publicKey, remoteParams.fundingPubKey)

  val localCommit = LocalCommit(0, CommitmentSpec(htlcs.toSet, 1500, 50000000, 70000000), PublishableTxs(CommitTx(commitmentInput, Transaction(2, Nil, Nil, 0)), Nil))
  val remoteCommit = RemoteCommit(0, CommitmentSpec(htlcs.map(htlc => htlc.copy(direction = htlc.direction.opposite)).toSet, 1500, 50000, 700000), BinaryData("0303030303030303030303030303030303030303030303030303030303030303"), Scalar(BinaryData("04" * 32)).toPoint)
  val commitmentsV1 = Commitments(localParams, remoteParams, channelFlags = 0x01.toByte, localCommit, remoteCommit, LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
    localNextHtlcId = 32L,
    remoteNextHtlcId = 4L,
    originChannels = Map(42L -> Local(None), 15000L -> Relayed("42" * 32, 43, 11000000L, 10000000L)),
    remoteNextCommitInfo = Right(randomKey.publicKey),
    commitInput = commitmentInput, remotePerCommitmentSecrets = ShaChain.init, channelId = "00" * 32, version = VersionCommitmentV1)

  val simplifiedCommitment = Commitments(localParams, remoteParams, channelFlags = 0x01.toByte, localCommit, remoteCommit, LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
    localNextHtlcId = 32L,
    remoteNextHtlcId = 4L,
    originChannels = Map(42L -> Local(None), 15000L -> Relayed("42" * 32, 43, 11000000L, 10000000L)),
    remoteNextCommitInfo = Right(randomKey.publicKey),
    commitInput = commitmentInput, remotePerCommitmentSecrets = ShaChain.init, channelId = "00" * 32, version = VersionSimplifiedCommitment)

  val channelUpdate = Announcements.makeChannelUpdate("11" * 32, randomKey, randomKey.publicKey, ShortChannelId(142553), 42, 15, 575, 53, Channel.MAX_FUNDING_SATOSHIS * 1000L)

  val normal = DATA_NORMAL(commitmentsV1, ShortChannelId(42), true, None, channelUpdate, None, None)

  val normalSimplified = DATA_NORMAL(simplifiedCommitment, ShortChannelId(42), true, None, channelUpdate, None, None)

  // This is a serialized commitment (CommitmemtV1) taken from a mainnet DB and generated with code (commit: f9ead30) that did not account for other commitments type
  val rawCommitment = BinaryData("000003036D65409C41AB7380A43448F257809E7496B52BF92057C09C4F300CBD61C50D96000429A0E801FFB3E3B20083A3F8C548677F0000000000000222000000012A05F20000000000000008FC00000000000000010090001E800BD48A44296C8BE17C66F9F5675400AE1ADFF2BF4C776F4380000000C501C3277812FEF47DAC3ECC48C36735250C344AF72254935FE1B87161B32CBD1FC78000000000000111000000009502F900000000000000047E00000000000000008048000F0180884C8F3711CB0FA6009FD387BB18BDE3AE79C6F99FFBDA245A8868E3D84E4A0152D19626E31E85DCC547D6452BFECEA4A58D6421DC3ED9C31EBB5BE25F5EC81301826DC6CF05233C470A78CD05F10719F58CC6E3F3297A86F29F41AD3EC17CD07B81D2E0F42ECE10F90F47068AD225E39205BE9F62234D8217239254D1B149BC91A881BDDB713BD0D5A69215373CB4DD6C082ACCCCA37973FC3ED56486184B4060A08200000001C0404100800000000000000A000000000A5B0000000006CAC68C00000000000FFC3400122B6BEB76D026C009C138624B84F8F56DAD5A49CF2830984E66D66DCC6731BE87000000000015B8410180000000001100102BC29224A48EBA98852295B2000EE07CBE322503CDAC9E423DFABF5A0D307C790023A9108180884C8F3711CB0FA6009FD387BB18BDE3AE79C6F99FFBDA245A8868E3D84E4A1081B23E89D184700908064F254A89032F9618247C780B06332CA9547A783BBCC52EA95700AD01000000000080AB6BEB76D026C009C138624B84F8F56DAD5A49CF2830984E66D66DCC6731BE870000000000752B8CC00117840000000000000B000A2B55A3F061D2BD8FF8D65643AE2ABBE481B612B32135818000000000110010596BBCBEEA49205A1E44895DBE34810E998C5E3041AF6F154F174B877E08AED0820023982201102713B619608C4602CDCB4820CA2E2D1F18F632E14AD1AFBAA2530B195DB7E4B3811037E26CE7057B4B90C7CC88E0AAFCCA551824239C51DACAEAD6938B56E218264880A418228110804CBDAB73C3F569F5A7D6141639C3A94F923C6D15109F3E7C7019472671261B4201103E15DCB850126C070EF6E513AA9CF6D1B624692F4610DFFC6BBF519754B99DE880A3A9108180884C8F3711CB0FA6009FD387BB18BDE3AE79C6F99FFBDA245A8868E3D84E4A1081B23E89D184700908064F254A89032F9618247C780B06332CA9547A783BBCC52EA95745930A100000000000000000000A000000000A5B00000000000FFC340000000006CAC68C1882C81E7E9B4BAD22DECC021DED07B2A04CF401246F21260608E6D0362BB89981E6F8F85DBFDA1385E5274CBE5B0D21DD45329DE7FB534805C11187456282EE6E000000000000000000000000000000000000000500000000000000000000409D4457D14079D3398F705B263163D4841D8DFB50EF8E8FFFD7D72208E512D884800915B5F5BB68136004E09C3125C27C7AB6D6AD24E794184C27336B36E63398DF4380000000000ADC2080C00000000008800815E1491252475D4C42914AD90007703E5F191281E6D64F211EFD5FAD06983E3C8011D48840C04426479B88E587D3004FE9C3DD8C5EF1D73CE37CCFFDED122D443471EC27250840D91F44E8C2380484032792A5448197CB0C123E3C0583199654AA3D3C1DDE629754AB8000800F00003FFFFFFFFFFC008259C04D32D731B196AED4DC91753BC131B113C3BF619A9D7EB7A3222B3F376B9000F80003FFFFFFFFFFB0020703B9CA4B1B9FB5BA2809C0CF5FB8330E29FB47C27EC40C671E9798BADAAD29580007FFFFFFFFFF62B6BEB76D026C009C138624B84F8F56DAD5A49CF2830984E66D66DCC6731BE8704510980054B800070B821EA2784D9FC32BFEA579D3C6136DF87E6A232947BCC9DF84BC5337794370FF9740C589C29EB6931134B44414343132B149099E5DCA288BFD526B3C4BC888F22EA7DBA63472300DFBF40588AAC14A954A91FA3A7B1E760B9408841FC33B9C2137CC172868DBF6A5B3EA430FF121D3E09DEE03446599B79B33A980BB57721744E873A8774D20F9A26B466939C91BB0C9EA31367A8526F223FC505F5FAE0796C28711C7C0D4FE45184A8A5D284BA86AF7A06C7FBD30717DAAEAE237EBD1E9FD91D93A03FDAE3B23B8A2C360D692276F38C518BA33A18899D9E8C74CFA7F814AA37B0C12942FBD3BC19F24B56C5AB1E72645A06C79737C154F451FA67410A9460000DFC518156DE366E5834D448D5CC7EE9F263D06CBC2B41138D1AC32000000000011442600152E000006DACA81388356E701486891E4AF013CE92D6A57F240AF81389E60197AC38A1B2C070C9DE04BFBD1F6B0FB31230D9CD49430D12BDC89524D7F86E1C586CCB2F47F1E06C8FA274611C02420193C952A240CBE586091F1E02C18CCB2A551E9E0EEF314BA060221323CDC472C3E98027F4E1EEC62F78EB9E71BE67FEF68916A21A38F613929E7E8606E1BCCB0FEBDE1C2692621783F368F36FCB3B4840C7F47FF0EFE011EDCE3F34E089065806CC7E04039166B7EEB7B6B2116CE357A36B7A1DCCD7793B242DFC518156DE366E5834D448D5CC7EE9F263D06CBC2B41138D1AC32000000000011442600152E0000B8FD31EE020001200000000000000002000007D0000000C8000000001B6B0B0000")
}
