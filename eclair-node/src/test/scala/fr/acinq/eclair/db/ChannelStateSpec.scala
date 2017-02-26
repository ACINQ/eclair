package fr.acinq.eclair.db

import java.net.InetSocketAddress

import fr.acinq.bitcoin.Crypto.{PrivateKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi, Satoshi, Transaction}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.{Generators, ShaChain}
import fr.acinq.eclair.transactions.Transactions.CommitTx
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.{CommitSig, Init, OpenChannel, UpdateAddHtlc}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by fabrice on 07/02/17.
  */
@RunWith(classOf[JUnitRunner])
class ChannelStateSpec extends FunSuite {

  import ChannelStateSpec._

  test("basic serialization test") {
    val data = openChannel
    val bin = ChannelState.serializer.serialize(data)
    val check = ChannelState.serializer.deserialize(bin)
    assert(data == check)
  }

  test("basic serialization test (NORMAL)") {
    val data = normal
    val bin = ChannelState.serializer.serialize(data)
    val check = ChannelState.serializer.deserialize(bin)
    assert(data == check)
  }
}

object ChannelStateSpec {
  val localParams = LocalParams(
    dustLimitSatoshis = Satoshi(546).toLong,
    maxHtlcValueInFlightMsat = 50,
    channelReserveSatoshis = 10000,
    htlcMinimumMsat = 50000,
    feeratePerKw = 15000,
    toSelfDelay = 144,
    maxAcceptedHtlcs = 50,
    fundingPrivKey = PrivateKey(BinaryData("01" * 32) :+ 1.toByte),
    revocationSecret = Scalar(BinaryData("02" * 32)),
    paymentKey = PrivateKey(BinaryData("03" * 32) :+ 1.toByte),
    delayedPaymentKey = Scalar(BinaryData("04" * 32)),
    defaultFinalScriptPubKey = Nil,
    shaSeed = BinaryData("05" * 32),
    isFunder = true,
    globalFeatures = "foo".getBytes(),
    localFeatures = "bar".getBytes())

  val remoteParams = RemoteParams(
    dustLimitSatoshis = Satoshi(546).toLong,
    maxHtlcValueInFlightMsat = 50,
    channelReserveSatoshis = 10000,
    htlcMinimumMsat = 50000,
    feeratePerKw = 15000,
    toSelfDelay = 144,
    maxAcceptedHtlcs = 50,
    fundingPubKey = PrivateKey(BinaryData("01" * 32) :+ 1.toByte).publicKey,
    revocationBasepoint = Scalar(BinaryData("02" * 32)).toPoint,
    paymentBasepoint = Scalar(BinaryData("03" * 32)).toPoint,
    delayedPaymentBasepoint = Scalar(BinaryData("04" * 32)).toPoint,
    globalFeatures = "foo".getBytes(),
    localFeatures = "bar".getBytes())

  val channelParams = ChannelParams(localParams, remoteParams, 42000, 100)

  val openChannel = {
    val open = OpenChannel(temporaryChannelId = 1000,
      fundingSatoshis = 42000,
      pushMsat = 0,
      dustLimitSatoshis = localParams.dustLimitSatoshis,
      maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
      channelReserveSatoshis = localParams.channelReserveSatoshis,
      htlcMinimumMsat = localParams.htlcMinimumMsat,
      feeratePerKw = localParams.feeratePerKw,
      toSelfDelay = localParams.toSelfDelay,
      maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
      fundingPubkey = localParams.fundingPrivKey.publicKey,
      revocationBasepoint = localParams.revocationSecret.toPoint,
      paymentBasepoint = localParams.paymentKey.toPoint,
      delayedPaymentBasepoint = localParams.delayedPaymentKey.toPoint,
      firstPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, 0))

    ChannelState(
      PrivateKey(BinaryData("01" * 32) :+ 1.toByte).publicKey,
      WAIT_FOR_ACCEPT_CHANNEL,
      DATA_WAIT_FOR_ACCEPT_CHANNEL(INPUT_INIT_FUNDER(PrivateKey(BinaryData("01" * 32) :+ 1.toByte).publicKey, 42, 10000, 1000000, localParams, Init("foo".getBytes, "bar".getBytes)), open))
  }

  val paymentPreimages = Seq(
    BinaryData("0000000000000000000000000000000000000000000000000000000000000000"),
    BinaryData("0101010101010101010101010101010101010101010101010101010101010101"),
    BinaryData("0202020202020202020202020202020202020202020202020202020202020202"),
    BinaryData("0303030303030303030303030303030303030303030303030303030303030303"),
    BinaryData("0404040404040404040404040404040404040404040404040404040404040404")
  )

  val htlcs = Seq(
    Htlc(IN, UpdateAddHtlc(0, 0, MilliSatoshi(1000000).amount, 500, Crypto.sha256(paymentPreimages(0)), BinaryData("")), None),
    Htlc(IN, UpdateAddHtlc(0, 0, MilliSatoshi(2000000).amount, 501, Crypto.sha256(paymentPreimages(1)), BinaryData("")), None),
    Htlc(OUT, UpdateAddHtlc(0, 0, MilliSatoshi(2000000).amount, 502, Crypto.sha256(paymentPreimages(2)), BinaryData("")), None),
    Htlc(OUT, UpdateAddHtlc(0, 0, MilliSatoshi(3000000).amount, 503, Crypto.sha256(paymentPreimages(3)), BinaryData("")), None),
    Htlc(IN, UpdateAddHtlc(0, 0, MilliSatoshi(4000000).amount, 504, Crypto.sha256(paymentPreimages(4)), BinaryData("")), None)
  )

  val fundingTx = Transaction.read("0200000001adbb20ea41a8423ea937e76e8151636bf6093b70eaff942930d20576600521fd000000006b48304502210090587b6201e166ad6af0227d3036a9454223d49a1f11839c1a362184340ef0240220577f7cd5cca78719405cbf1de7414ac027f0239ef6e214c90fcaab0454d84b3b012103535b32d5eb0a6ed0982a0479bbadc9868d9836f6ba94dd5a63be16d875069184ffffffff028096980000000000220020c015c4a6be010e21657068fc2e6a9d02b27ebe4d490a25846f7237f104d1a3cd20256d29010000001600143ca33c2e4446f4a305f23c80df8ad1afdcf652f900000000")
  val fundingAmount = fundingTx.txOut(0).amount
  val commitmentInput = Funding.makeFundingInputInfo(fundingTx.hash, 0, fundingAmount, localParams.fundingPrivKey.publicKey, remoteParams.fundingPubKey)

  val localCommit = LocalCommit(0, CommitmentSpec(htlcs.toSet, 1500, 50000, 700000), PublishableTxs(CommitTx(commitmentInput, Transaction(2, Nil, Nil, 0)), Nil), CommitSig(42, BinaryData("01" * 64), Nil))
  val remoteCommit = RemoteCommit(0, CommitmentSpec(htlcs.toSet, 1500, 50000, 700000), BinaryData("0303030303030303030303030303030303030303030303030303030303030303"), Scalar(BinaryData("04" * 32)).toPoint)
  val commitments = Commitments(localParams, remoteParams, localCommit, remoteCommit, LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
    localNextHtlcId = 0L,
    remoteNextCommitInfo = Right(null), // TODO: we will receive their next per-commitment point in the next message, so we temporarily put an empty byte array
    remoteNextHtlcId = 0L,
    commitInput = commitmentInput, remotePerCommitmentSecrets = ShaChain.init, channelId = 0L, unackedMessages = Nil)

  val normal = ChannelState(PrivateKey(BinaryData("01" * 32) :+ 1.toByte).publicKey, NORMAL, DATA_NORMAL(ChannelParams(localParams, remoteParams, 42000, 10), commitments, None))
}
