package fr.acinq.eclair

import fr.acinq.bitcoin.{ByteVector32, DeterministicWallet, OutPoint}
import fr.acinq.eclair.channel.{LocalChanges, LocalParams, RemoteParams}
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.db.ChannelStateSpec
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire._
import grizzled.slf4j.Logging
import org.scalatest.FunSuite
import scodec.bits._
import upickle.default.{read, write}

import scala.util.Random

class JsonSerializersSpec extends FunSuite with Logging {
  import JsonSerializers._

  test("deserialize Map[OutPoint, BinaryData]") {
    val output1 = OutPoint(ByteVector32.fromValidHex("11418a2d282a40461966e4f578e1fdf633ad15c1b7fb3e771d14361127233be1"), 0)
    val output2 = OutPoint(ByteVector32.fromValidHex("3d62bd4f71dc63798418e59efbc7532380c900b5e79db3a5521374b161dd0e33"), 1)


    val map = Map(
      output1 -> ByteVector.fromValidHex("dead"),
      output2 -> ByteVector.fromValidHex("beef")
    )
    val json = write(map)
    assert(json === s"""[["${output1.hash}:0","dead"],["${output2.hash}:1","beef"]]""")
  }

  test("NodeAddress serialization") {
    val ipv4 = NodeAddress.fromParts("10.0.0.1", 8888).get
    val ipv6LocalHost = NodeAddress.fromParts("[0:0:0:0:0:0:0:1]", 9735).get

    assert(write(ipv4) === s""""10.0.0.1:8888"""")
    assert(write(ipv6LocalHost) === s""""[0:0:0:0:0:0:0:1]:9735"""")
  }

  test("Direction serialization") {
    assert(write(IN) ===  """{"$type":"fr.acinq.eclair.transactions.IN"}""")
    assert(write(OUT) ===  """{"$type":"fr.acinq.eclair.transactions.OUT"}""")
  }

  test("serialize LocalParams") {
    val localParams = LocalParams(
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

    logger.info(write(localParams))

  }

  test("serialize RemoteParams") {
    val remoteParams = RemoteParams(
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

    logger.info(write(remoteParams))
  }

  test("serialize CommitmentSpec") {
    val spec = CommitmentSpec(Set(DirectedHtlc(IN, UpdateAddHtlc(randomBytes32, 421, 1245, randomBytes32, 1000, hex"010101"))), feeratePerKw = 1233, toLocalMsat = 100, toRemoteMsat = 200)
    logger.info(write(spec))
  }

  test("serialize LocalChanges") {
    val channelId = randomBytes32
    val add = UpdateAddHtlc(channelId, 421, 1245, randomBytes32, 1000, hex"010101")
    val fail = UpdateFailHtlc(channelId, 42, hex"0101")
    val failMalformed = UpdateFailMalformedHtlc(channelId, 42, randomBytes32, 1)
    val updateFee = UpdateFee(channelId, 1500)
    val fulfill = UpdateFulfillHtlc(channelId, 42, randomBytes32)
    val localChanges = LocalChanges(proposed = add :: add :: fail :: updateFee :: Nil, signed = add :: failMalformed :: Nil, acked = fail :: fail :: fulfill :: Nil)
    val json = write(localChanges)
    val check = read[LocalChanges](json)
    assert(check === localChanges)
  }

  test("serialize shaChain") {
    val seed = ByteVector32.Zeroes
    var receiver = ShaChain.empty
    for (i <- 0 until 7) {
      receiver = receiver.addHash(ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFL - i), 0xFFFFFFFFFFFFL - i)
    }
    logger.info(write(receiver))
  }

  test("serialize Commitments") {
    val commitments = ChannelStateSpec.commitments
    logger.info(write(commitments))
  }

  test("serialize DATA_NORMAL") {
    val data = ChannelStateSpec.normal
    logger.info(write(data))
  }
}
