package fr.acinq.eclair.integration

import akka.actor.ActorRef
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, randomBytes32}
import fr.acinq.eclair.TestUtils
import fr.acinq.eclair.channel.ChannelTypes.SimpleTaprootChannel
import fr.acinq.eclair.channel.{CMD_CLOSE, ChannelFlags, ChannelStateChanged, NEGOTIATING_SIMPLE, NORMAL, RES_SUCCESS, Register, SupportedChannelType}
import fr.acinq.eclair.io.Peer.OpenChannelResponse
import fr.acinq.eclair.io.{Peer, PeerConnection}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentSent}
import fr.acinq.eclair.wire.protocol.IPAddress
import org.json4s.jackson.JsonMethods
import scodec.bits.ByteVector

import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.UUID
import scala.concurrent.duration._
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.Try

class TaprootChannelIntegrationSpec extends ChannelIntegrationSpec {

  import scala.sys.process._

  override def channelType: SupportedChannelType = SimpleTaprootChannel()

  var lnd: Process = _
  var lndNodeId: PublicKey = _
  val lndPort: Int = TestUtils.availablePort
  val lndRpcPort: Int = TestUtils.availablePort
  val PATH_LND_DATADIR = new File(INTEGRATION_TMP_DIR, "datadir-lnd")

  def startLnd(): Unit = {
    Files.createDirectories(PATH_LND_DATADIR.toPath)

    val is = classOf[IntegrationSpec].getResourceAsStream("/integration/lnd.conf")
    val conf = Source.fromInputStream(is).mkString
      .replace("29737", lndPort.toString)
      .replace("10009", lndRpcPort.toString)
      .replace("18443", bitcoindRpcPort.toString)
      .replace("29022", bitcoindZmqRawBlockPort.toString)
      .replace("29021", bitcoindZmqTxPort.toString)
    Files.writeString(PATH_LND_DATADIR.toPath.resolve("lnd.conf"), conf)
    val user = sys.env("USER")
    lnd = s"/home/$user/go/bin/lnd --lnddir=$PATH_LND_DATADIR".run()
  }

  def stopLnd() = {
    awaitCond(lncli("stop").isSuccess, max = 15 seconds, interval = 2 seconds)
  }

  def lncli(args: String) = Try {
    s"/home/fabrice/go/bin/lncli --rpcserver=localhost:$lndRpcPort --no-macaroons --lnddir=$PATH_LND_DATADIR $args" !!
  } map { s =>
    JsonMethods.parse(s, useBigDecimalForDouble = false, useBigIntForLong = true)
  }

  def syncedToChain(): Boolean = lncli("getinfo").map(json => (json \ "synced_to_chain").extract[Boolean]).getOrElse(false)

  def nodeId(): PublicKey = lncli("getinfo").map(json => (json \ "identity_pubkey").extract[String]).map(s => PublicKey(ByteVector.fromValidHex(s))).get

  def createInvoice(amount: MilliSatoshi): Bolt11Invoice = lncli(s"addinvoice --amt_msat=${amount.toLong}")
    .map(json => (json \ "payment_request").extract[String])
    .flatMap(Bolt11Invoice.fromString)
    .get

  test("start lnd") {
    startLnd()
    awaitCond(syncedToChain(), max = 15 seconds, interval = 2 seconds)
    lndNodeId = nodeId()
    println(s"started $lndNodeId")
  }

  test("start eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.channel.expiry-delta-blocks" -> 40, "eclair.channel.fulfill-safety-before-timeout-blocks" -> 12, "eclair.server.port" -> 29740, "eclair.api.port" -> 28090).asJava)
      .withFallback(withTaprootChannels)
      .withFallback(withAnchorOutputsZeroFeeHtlcTxs)
      .withFallback(commonConfig))
  }

  test("open a channel") {
    val eventListener = TestProbe()
    nodes("A").system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged])

    val sender = TestProbe()
    sender.send(nodes("A").switchboard, Peer.Connect(
      nodeId = lndNodeId,
      address_opt = Some(IPAddress(InetAddress.getLoopbackAddress, lndPort)),
      sender.ref,
      isPersistent = true
    ))
    sender.expectMsgType[PeerConnection.ConnectionResult.HasConnection](10 seconds)

    sender.send(nodes("A").switchboard, Peer.OpenChannel(
      remoteNodeId = lndNodeId,
      fundingAmount = 100000 sat,
      channelType_opt = Some(channelType),
      pushAmount_opt = Some(50000000 msat),
      fundingTxFeerate_opt = None,
      fundingTxFeeBudget_opt = None,
      requestFunding_opt = None,
      channelFlags_opt = Some(ChannelFlags(nonInitiatorPaysCommitFees = false, announceChannel = false)),
      timeout_opt = None))
    sender.expectMsgType[OpenChannelResponse.Created](10 seconds)

    // confirm the funding tx
    generateBlocks(8)
    within(60 seconds) {
      var count = 0
      while (count < 1) {
        val stateEvent = eventListener.expectMsgType[ChannelStateChanged](max = 60 seconds)
        if (stateEvent.currentState == NORMAL) {
          assert(stateEvent.commitments_opt.nonEmpty)
          assert(stateEvent.commitments_opt.get.latest.commitmentFormat == channelType.commitmentFormat)
          count = count + 1
        }
      }
    }
  }

  test("send payment") {
    val sender = TestProbe()
    val amountMsat = 5000 msat
    val invoice = createInvoice(amountMsat)
    sender.send(nodes("A").paymentInitiator, SendPaymentToNode(sender.ref, amountMsat, invoice, Nil, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)
    assert(Crypto.sha256(ps.paymentPreimage) == invoice.paymentHash)
  }

  test("close channel") {
    val eventListener = TestProbe()
    nodes("A").system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged])
    val probe = TestProbe()
    probe.send(nodes("A").register, Register.GetChannels)
    val channels = probe.expectMsgType[Map[ByteVector32, ActorRef]]
    assert(channels.size == 1)
    channels.values.foreach { channel =>
      channel ! CMD_CLOSE(probe.ref, None, None)
      probe.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    }
    within(30 seconds) {
      val stateEvent = eventListener.expectMsgType[ChannelStateChanged](max = 60 seconds)
      stateEvent.currentState == NEGOTIATING_SIMPLE
    }

  }

  test("stop lnd") {
    stopLnd()
  }

}
