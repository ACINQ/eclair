package fr.acinq.eclair.db

import fr.acinq.bitcoin.Crypto.{PrivateKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, Crypto, DeterministicWallet, MilliSatoshi, Satoshi, Transaction}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.{LocalKeyManager, ShaChain, Sphinx}
import fr.acinq.eclair.payment.{Local, Relayed}
import fr.acinq.eclair.{UInt64, randomKey}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions.CommitTx
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.{ChannelCodecs, UpdateAddHtlc}
import fr.acinq.eclair.wire.{ChannelCodecs, ChannelUpdate, UpdateAddHtlc}
import fr.acinq.eclair.{ShortChannelId, UInt64, randomKey}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by fabrice on 07/02/17.
  */
@RunWith(classOf[JUnitRunner])
class ChannelStateSpec extends FunSuite {

  import ChannelStateSpec._

  test("basic serialization test (NORMAL)") {
    val data = normal
    val bin = ChannelCodecs.DATA_NORMAL_Codec.encode(data).require
    val check = ChannelCodecs.DATA_NORMAL_Codec.decodeValue(bin).require
    assert(data.commitments.localCommit.spec === check.commitments.localCommit.spec)
    assert(data === check)
  }
}

object ChannelStateSpec {
  val keyManager = new LocalKeyManager("01" * 32)
  val localParams = LocalParams(
    keyManager.nodeId,
    channelKeyPath = DeterministicWallet.KeyPath(Seq(42)),
    dustLimitSatoshis = Satoshi(546).toLong,
    maxHtlcValueInFlightMsat = UInt64(50),
    channelReserveSatoshis = 10000,
    htlcMinimumMsat = 50000,
    toSelfDelay = 144,
    maxAcceptedHtlcs = 50,
    defaultFinalScriptPubKey = Nil,
    isFunder = true,
    globalFeatures = "foo".getBytes(),
    localFeatures = "bar".getBytes())

  val remoteParams = RemoteParams(
    nodeId = randomKey.publicKey,
    dustLimitSatoshis = Satoshi(546).toLong,
    maxHtlcValueInFlightMsat = UInt64(50),
    channelReserveSatoshis = 10000,
    htlcMinimumMsat = 50000,
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
    DirectedHtlc(IN, UpdateAddHtlc("00" * 32, 0, MilliSatoshi(2000000).amount, Crypto.sha256(paymentPreimages(1)), 501, BinaryData("00" * Sphinx.PacketLength))),
    DirectedHtlc(OUT, UpdateAddHtlc("00" * 32, 0, MilliSatoshi(2000000).amount, Crypto.sha256(paymentPreimages(2)), 502, BinaryData("00" * Sphinx.PacketLength))),
    DirectedHtlc(OUT, UpdateAddHtlc("00" * 32, 0, MilliSatoshi(3000000).amount, Crypto.sha256(paymentPreimages(3)), 503, BinaryData("00" * Sphinx.PacketLength))),
    DirectedHtlc(IN, UpdateAddHtlc("00" * 32, 0, MilliSatoshi(4000000).amount, Crypto.sha256(paymentPreimages(4)), 504, BinaryData("00" * Sphinx.PacketLength)))
  )

  val fundingTx = Transaction.read("0200000001adbb20ea41a8423ea937e76e8151636bf6093b70eaff942930d20576600521fd000000006b48304502210090587b6201e166ad6af0227d3036a9454223d49a1f11839c1a362184340ef0240220577f7cd5cca78719405cbf1de7414ac027f0239ef6e214c90fcaab0454d84b3b012103535b32d5eb0a6ed0982a0479bbadc9868d9836f6ba94dd5a63be16d875069184ffffffff028096980000000000220020c015c4a6be010e21657068fc2e6a9d02b27ebe4d490a25846f7237f104d1a3cd20256d29010000001600143ca33c2e4446f4a305f23c80df8ad1afdcf652f900000000")
  val fundingAmount = fundingTx.txOut(0).amount
  val commitmentInput = Funding.makeFundingInputInfo(fundingTx.hash, 0, fundingAmount, keyManager.fundingPublicKey(localParams.channelKeyPath).publicKey, remoteParams.fundingPubKey)

  val localCommit = LocalCommit(0, CommitmentSpec(htlcs.toSet, 1500, 50000, 700000), PublishableTxs(CommitTx(commitmentInput, Transaction(2, Nil, Nil, 0)), Nil))
  val remoteCommit = RemoteCommit(0, CommitmentSpec(htlcs.toSet, 1500, 50000, 700000), BinaryData("0303030303030303030303030303030303030303030303030303030303030303"), Scalar(BinaryData("04" * 32)).toPoint)
  val commitments = Commitments(localParams, remoteParams, channelFlags = 0x01.toByte, localCommit, remoteCommit, LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
    localNextHtlcId = 0L,
    remoteNextHtlcId = 0L,
    originChannels = Map(42L -> Local(None), 15000L -> Relayed("42" * 32, 43, 11000000L, 10000000L)),
    remoteNextCommitInfo = Right(randomKey.publicKey),
    commitInput = commitmentInput, remotePerCommitmentSecrets = ShaChain.init, channelId = "00" * 32)

  val channelUpdate = Announcements.makeChannelUpdate("11" * 32, randomKey, randomKey.publicKey, ShortChannelId(142553), 42, 15, 575, 53)

  val normal = DATA_NORMAL(commitments, ShortChannelId(42), true, None, channelUpdate, None, None)
}
