package fr.acinq.eclair

import java.net.{InetAddress, InetSocketAddress}

import fr.acinq.bitcoin.{BinaryData, DeterministicWallet, Hash, OutPoint}
import fr.acinq.eclair.channel.{LocalChanges, LocalParams, RemoteParams}
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.db.ChannelStateSpec
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.{NodeAddress, UpdateAddHtlc, UpdateFailHtlc}
import grizzled.slf4j.Logging
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import upickle.default.write

import scala.util.Random

class JsonSerializersSpec extends FunSuite with Logging {
  import JsonSerializers._

  test("deserialize Map[OutPoint, BinaryData]") {
    val output1 = OutPoint("11418a2d282a40461966e4f578e1fdf633ad15c1b7fb3e771d14361127233be1", 0)
    val output2 = OutPoint("3d62bd4f71dc63798418e59efbc7532380c900b5e79db3a5521374b161dd0e33", 1)


    val map = Map(
      output1 -> BinaryData("dead"),
      output2 -> BinaryData("beef")
    )
    val json = write(map)
    assert(json === s"""[["${output1.hash}:0","dead"],["${output2.hash}:1","beef"]]""")
  }

  test("NodeAddress serialization") {
    val ipv4 = NodeAddress(new InetSocketAddress(InetAddress.getByAddress(Array(10, 0, 0, 1)), 8888))
    val ipv6LocalHost = NodeAddress(new InetSocketAddress(InetAddress.getByAddress(Array(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)), 9735))

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
    val spec = CommitmentSpec(Set(DirectedHtlc(IN, UpdateAddHtlc(randomKey.publicKey.value.toBin(true), 421, 1245, randomBytes(32), 1000, BinaryData("010101")))), feeratePerKw = 1233, toLocalMsat = 100, toRemoteMsat = 200)
    logger.info(write(spec))
  }

  test("serialize LocalChanges") {
    val channelId = randomKey.publicKey.value.toBin(true)
    val add = UpdateAddHtlc(channelId, 421, 1245, randomBytes(32), 1000, BinaryData("010101"))
    val fail = UpdateFailHtlc(channelId, 42, BinaryData("0101"))
    val localChanges = LocalChanges(proposed = add :: add :: fail :: Nil, signed = add :: Nil, acked = fail :: fail :: Nil)
    logger.info(write(localChanges))
  }

  test("serialize shaChain") {
    val seed = Hash.Zeroes
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
