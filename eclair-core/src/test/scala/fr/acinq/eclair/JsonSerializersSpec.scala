package fr.acinq.eclair

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{ByteVector32, DeterministicWallet, OutPoint, Satoshi, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.channel.{ChannelVersion, LocalChanges, LocalParams, RemoteParams}
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire._
import grizzled.slf4j.Logging
import org.scalatest.FunSuite
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._
import upickle.default.{read, write}

import scala.util.Random

class JsonSerializersSpec extends AnyFunSuite with Logging {
  import JsonSerializers._

  val tx1 = Transaction(version = 2,
    txIn = TxIn(OutPoint(ByteVector32.fromValidHex("11418a2d282a40461966e4f578e1fdf633ad15c1b7fb3e771d14361127233be1"), 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
    txOut = TxOut(Satoshi(1500), Script.pay2wpkh(PrivateKey(ByteVector32.fromValidHex("01" * 32)).publicKey)) :: Nil,
    lockTime = 0)
  val tx2 = Transaction(version = 2,
    txIn = TxIn(OutPoint(ByteVector32.fromValidHex("3d62bd4f71dc63798418e59efbc7532380c900b5e79db3a5521374b161dd0e33"), 1), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
    txOut = TxOut(Satoshi(1500), Script.pay2wpkh(PrivateKey(ByteVector32.fromValidHex("02" * 32)).publicKey)) :: Nil,
    lockTime = 0)

  test("deserialize Map[OutPoint, BinaryData]") {
    val output1 = OutPoint(tx1, 0)
    val output2 = OutPoint(tx2, 1)


    val map = Map(
      output1 -> ByteVector.fromValidHex("dead"),
      output2 -> ByteVector.fromValidHex("beef")
    )
    val json = write(map)
    assert(json === s"""[["${tx1.txid}:0","dead"],["${tx2.txid}:1","beef"]]""")
  }

  test("NodeAddress serialization") {
    val ipv4 = NodeAddress.fromParts("10.0.0.1", 8888).get
    val ipv6LocalHost = NodeAddress.fromParts("[0:0:0:0:0:0:0:1]", 9735).get

    assert(write(ipv4) === s""""10.0.0.1:8888"""")
    assert(write(ipv6LocalHost) === s""""[0:0:0:0:0:0:0:1]:9735"""")
  }

  test("ChannelVersion serialization") {
    assert(write(ChannelVersion.STANDARD) ===  """"00000000000000000000000000000001"""")
  }

  test("serialize LocalParams") {
    val localParams = LocalParams(
      nodeId = randomKey.publicKey,
      fundingKeyPath = DeterministicWallet.KeyPath(Seq(42L, 42L, 42L, 42L)),
      dustLimit = Satoshi(Random.nextInt(Int.MaxValue)),
      maxHtlcValueInFlightMsat = UInt64(Random.nextInt(Int.MaxValue)),
      channelReserve = Satoshi(Random.nextInt(Int.MaxValue)),
      htlcMinimum = MilliSatoshi(Random.nextInt(Int.MaxValue)),
      toSelfDelay = CltvExpiryDelta(Random.nextInt(Short.MaxValue)),
      maxAcceptedHtlcs = Random.nextInt(Short.MaxValue),
      defaultFinalScriptPubKey = randomBytes(10 + Random.nextInt(200)),
      isFunder = Random.nextBoolean(),
      features = Features(randomBytes(256)),
      staticPaymentBasepoint = None)

    logger.info(write(localParams))

  }

  test("serialize RemoteParams") {
    val remoteParams = RemoteParams(
      nodeId = randomKey.publicKey,
      dustLimit = Satoshi(Random.nextInt(Int.MaxValue)),
      maxHtlcValueInFlightMsat = UInt64(Random.nextInt(Int.MaxValue)),
      channelReserve = Satoshi(Random.nextInt(Int.MaxValue)),
      htlcMinimum = MilliSatoshi(Random.nextInt(Int.MaxValue)),
      toSelfDelay = CltvExpiryDelta(Random.nextInt(Short.MaxValue)),
      maxAcceptedHtlcs = Random.nextInt(Short.MaxValue),
      fundingPubKey = randomKey.publicKey,
      revocationBasepoint = randomKey.publicKey,
      paymentBasepoint = randomKey.publicKey,
      delayedPaymentBasepoint = randomKey.publicKey,
      htlcBasepoint = randomKey.publicKey,
      features = Features(randomBytes(256)))

    logger.info(write(remoteParams))
  }

  test("serialize CommitmentSpec") {
    val spec = CommitmentSpec(Set(IncomingHtlc(UpdateAddHtlc(randomBytes32, 421, MilliSatoshi(1245), randomBytes32, CltvExpiry(1000), OnionRoutingPacket(0, randomKey.publicKey.value, hex"0101", randomBytes32)))), feeratePerKw = 1233, toLocal = MilliSatoshi(100), toRemote = MilliSatoshi(200))
    logger.info(write(spec))
  }

  test("serialize LocalChanges") {
    val channelId = randomBytes32
    val add = UpdateAddHtlc(channelId, 421, MilliSatoshi(1245), randomBytes32, CltvExpiry(1000), OnionRoutingPacket(0, randomKey.publicKey.value, hex"0101", randomBytes32))
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
    val commitments = ChannelCodecsSpec.normal.commitments
    logger.info(write(commitments))
  }

  test("serialize DATA_NORMAL") {
    val data = ChannelCodecsSpec.normal
    logger.info(write(data))
  }
}
