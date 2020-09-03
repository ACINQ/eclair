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

package fr.acinq.eclair.integration

import java.io.File
import java.util.{Properties, UUID}

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import com.google.common.net.HostAndPort
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Base58, Base58Check, Bech32, Block, ByteVector32, Crypto, OP_0, OP_CHECKSIG, OP_DUP, OP_EQUAL, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, Satoshi, Script, ScriptFlags, Transaction}
import fr.acinq.eclair.Features._
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.{Watch, WatchConfirmed}
import fr.acinq.eclair.channel.Channel.{BroadcastChannelUpdate, PeriodicRefresh}
import fr.acinq.eclair.channel.ChannelOpenResponse.ChannelOpened
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx.DecryptedFailurePacket
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.db._
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.io.{Peer, PeerConnection}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceivePayment
import fr.acinq.eclair.payment.receive.{ForwardHandler, PaymentHandler}
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.PreimageReceived
import fr.acinq.eclair.payment.send.PaymentInitiator.{SendPaymentRequest, SendTrampolinePaymentRequest}
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.eclair.router.RouteCalculation.ROUTE_MAX_LENGTH
import fr.acinq.eclair.router.Router.{GossipDecision, MultiPartParams, PublicChannel, RouteParams, NORMAL => _, State => _}
import fr.acinq.eclair.router.{Announcements, AnnouncementsBatchValidationSpec, Router}
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiryDelta, Kit, LongToBtcAmount, MilliSatoshi, Setup, ShortChannelId, TestKitBaseClass, channel, randomBytes32}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JString, JValue}
import org.json4s.{DefaultFormats, Formats}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.ByteVector

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * Created by PM on 15/03/2017.
 */

class IntegrationSpec extends TestKitBaseClass with BitcoindService with AnyFunSuiteLike with BeforeAndAfterAll with Logging {

  var nodes: Map[String, Kit] = Map()

  // we override the default because these test were designed to use cost-optimized routes
  val integrationTestRouteParams = Some(RouteParams(
    randomize = false,
    maxFeeBase = 21000 msat,
    maxFeePct = 0.03,
    routeMaxCltv = CltvExpiryDelta(Int.MaxValue),
    routeMaxLength = ROUTE_MAX_LENGTH,
    ratios = Some(WeightRatios(
      cltvDeltaFactor = 0.1,
      ageFactor = 0,
      capacityFactor = 0
    )),
    mpp = MultiPartParams(15000000 msat, 6)
  ))

  // we need to provide a value higher than every node's fulfill-safety-before-timeout
  val finalCltvExpiryDelta = CltvExpiryDelta(36)

  val commonConfig = ConfigFactory.parseMap(Map(
    "eclair.chain" -> "regtest",
    "eclair.enable-db-backup" -> false,
    "eclair.server.public-ips.1" -> "127.0.0.1",
    "eclair.bitcoind.port" -> bitcoindPort,
    "eclair.bitcoind.rpcport" -> bitcoindRpcPort,
    "eclair.bitcoind.zmqblock" -> s"tcp://127.0.0.1:$bitcoindZmqBlockPort",
    "eclair.bitcoind.zmqtx" -> s"tcp://127.0.0.1:$bitcoindZmqTxPort",
    "eclair.mindepth-blocks" -> 2,
    "eclair.max-htlc-value-in-flight-msat" -> 100000000000L,
    "eclair.router.broadcast-interval" -> "2 second",
    "eclair.auto-reconnect" -> false,
    "eclair.to-remote-delay-blocks" -> 144,
    "eclair.multi-part-payment-expiry" -> "20 seconds").asJava).withFallback(ConfigFactory.load())

  val commonFeatures = ConfigFactory.parseMap(Map(
    s"eclair.features.${OptionDataLossProtect.rfcName}" -> "optional",
    s"eclair.features.${InitialRoutingSync.rfcName}" -> "optional",
    s"eclair.features.${ChannelRangeQueries.rfcName}" -> "optional",
    s"eclair.features.${ChannelRangeQueriesExtended.rfcName}" -> "optional",
    s"eclair.features.${VariableLengthOnion.rfcName}" -> "optional",
    s"eclair.features.${PaymentSecret.rfcName}" -> "optional",
    s"eclair.features.${BasicMultiPartPayment.rfcName}" -> "optional"
  ).asJava)

  val withWumbo = commonFeatures.withFallback(ConfigFactory.parseMap(Map(
    s"eclair.features.${Wumbo.rfcName}" -> "optional",
    "eclair.max-funding-satoshis" -> 500000000
  ).asJava))

  val withStaticRemoteKey = commonFeatures.withFallback(ConfigFactory.parseMap(Map(
    s"eclair.features.${StaticRemoteKey.rfcName}" -> "optional"
  ).asJava))

  val withAnchorOutputs = withStaticRemoteKey.withFallback(ConfigFactory.parseMap(Map(
    s"eclair.features.${AnchorOutputs.rfcName}" -> "optional"
  ).asJava))

  implicit val formats: Formats = DefaultFormats

  override def beforeAll: Unit = {
    startBitcoind()
  }

  override def afterAll: Unit = {
    // gracefully stopping bitcoin will make it store its state cleanly to disk, which is good for later debugging
    logger.info(s"stopping bitcoind")
    stopBitcoind()
    nodes.foreach {
      case (name, setup) =>
        logger.info(s"stopping node $name")
        TestKit.shutdownActorSystem(setup.system)
    }
  }

  test("wait bitcoind ready") {
    val sender = TestProbe()
    logger.info(s"waiting for bitcoind to initialize...")
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("getnetworkinfo"))
      sender.receiveOne(5 second).isInstanceOf[JValue]
    }, max = 30 seconds, interval = 500 millis)
    logger.info(s"generating initial blocks...")
    generateBlocks(bitcoincli, 150, timeout = 30 seconds)
  }

  def instantiateEclairNode(name: String, config: Config): Unit = {
    val datadir = new File(INTEGRATION_TMP_DIR, s"datadir-eclair-$name")
    datadir.mkdirs()
    implicit val system: ActorSystem = ActorSystem(s"system-$name", config)
    val setup = new Setup(datadir)
    val kit = Await.result(setup.bootstrap, 10 seconds)
    nodes = nodes + (name -> kit)
  }

  def javaProps(props: Seq[(String, String)]): Properties = {
    val properties = new Properties()
    props.foreach(p => properties.setProperty(p._1, p._2))
    properties
  }

  test("starting eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.expiry-delta-blocks" -> 130, "eclair.server.port" -> 29730, "eclair.api.port" -> 28080, "eclair.channel-flags" -> 0).asJava).withFallback(commonFeatures).withFallback(commonConfig)) // A's channels are private
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.expiry-delta-blocks" -> 131, "eclair.server.port" -> 29731, "eclair.api.port" -> 28081, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(commonFeatures).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.expiry-delta-blocks" -> 132, "eclair.server.port" -> 29732, "eclair.api.port" -> 28082, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(withAnchorOutputs).withFallback(withWumbo).withFallback(commonConfig))
    instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.node-alias" -> "D", "eclair.expiry-delta-blocks" -> 133, "eclair.server.port" -> 29733, "eclair.api.port" -> 28083, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(commonFeatures).withFallback(commonConfig))
    instantiateEclairNode("E", ConfigFactory.parseMap(Map("eclair.node-alias" -> "E", "eclair.expiry-delta-blocks" -> 134, "eclair.server.port" -> 29734, "eclair.api.port" -> 28084).asJava).withFallback(withAnchorOutputs).withFallback(commonConfig))
    instantiateEclairNode("F1", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F1", "eclair.expiry-delta-blocks" -> 135, "eclair.server.port" -> 29735, "eclair.api.port" -> 28085, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(withWumbo).withFallback(commonConfig))
    instantiateEclairNode("F2", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F2", "eclair.expiry-delta-blocks" -> 136, "eclair.server.port" -> 29736, "eclair.api.port" -> 28086).asJava).withFallback(withAnchorOutputs).withFallback(commonConfig))
    instantiateEclairNode("G", ConfigFactory.parseMap(Map("eclair.node-alias" -> "G", "eclair.expiry-delta-blocks" -> 137, "eclair.server.port" -> 29737, "eclair.api.port" -> 28087, "eclair.fee-base-msat" -> 1010, "eclair.fee-proportional-millionths" -> 102, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(commonConfig))
  }

  def connect(node1: Kit, node2: Kit, fundingSatoshis: Satoshi, pushMsat: MilliSatoshi): ChannelOpenResponse.ChannelOpened = {
    val sender = TestProbe()
    val address = node2.nodeParams.publicAddresses.head
    sender.send(node1.switchboard, Peer.Connect(
      nodeId = node2.nodeParams.nodeId,
      address_opt = Some(HostAndPort.fromParts(address.socketAddress.getHostString, address.socketAddress.getPort))
    ))
    sender.expectMsgAnyOf(10 seconds, PeerConnection.ConnectionResult.Connected, PeerConnection.ConnectionResult.AlreadyConnected)
    sender.send(node1.switchboard, Peer.OpenChannel(
      remoteNodeId = node2.nodeParams.nodeId,
      fundingSatoshis = fundingSatoshis,
      pushMsat = pushMsat,
      fundingTxFeeratePerKw_opt = None,
      channelFlags = None,
      timeout_opt = None))
    sender.expectMsgType[ChannelOpenResponse.ChannelOpened](10 seconds)
  }

  test("connect nodes") {
    //       ,--G--,
    //      /       \
    // A---B ------- C ==== D
    //      \       /
    //       '--E--'

    val sender = TestProbe()
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    connect(nodes("A"), nodes("B"), 11000000 sat, 0 msat)
    connect(nodes("B"), nodes("C"), 2000000 sat, 0 msat)
    connect(nodes("C"), nodes("D"), 5000000 sat, 0 msat)
    connect(nodes("C"), nodes("D"), 5000000 sat, 0 msat)
    connect(nodes("B"), nodes("E"), 10000000 sat, 0 msat)
    connect(nodes("E"), nodes("C"), 10000000 sat, 0 msat)
    connect(nodes("B"), nodes("G"), 16000000 sat, 0 msat)
    connect(nodes("G"), nodes("C"), 16000000 sat, 0 msat)

    val numberOfChannels = 8
    val channelEndpointsCount = 2 * numberOfChannels

    // we make sure all channels have set up their WatchConfirmed for the funding tx
    awaitCond({
      val watches = nodes.values.foldLeft(Set.empty[Watch]) {
        case (watches, setup) =>
          sender.send(setup.watcher, Symbol("watches"))
          watches ++ sender.expectMsgType[Set[Watch]]
      }
      watches.count(_.isInstanceOf[WatchConfirmed]) == channelEndpointsCount
    }, max = 20 seconds, interval = 1 second)

    // confirming the funding tx
    generateBlocks(bitcoincli, 2)

    within(60 seconds) {
      var count = 0
      while (count < channelEndpointsCount) {
        if (eventListener.expectMsgType[ChannelStateChanged](30 seconds).currentState == NORMAL) count = count + 1
      }
    }
  }

  def awaitAnnouncements(subset: Map[String, Kit], nodes: Int, channels: Int, updates: Int): Unit = {
    val sender = TestProbe()
    subset.foreach {
      case (_, setup) =>
        awaitCond({
          sender.send(setup.router, Symbol("nodes"))
          sender.expectMsgType[Iterable[NodeAnnouncement]](20 seconds).size == nodes
        }, max = 60 seconds, interval = 1 second)
        awaitCond({
          sender.send(setup.router, Symbol("channels"))
          sender.expectMsgType[Iterable[ChannelAnnouncement]](20 seconds).size == channels
        }, max = 60 seconds, interval = 1 second)
        awaitCond({
          sender.send(setup.router, Symbol("updates"))
          sender.expectMsgType[Iterable[ChannelUpdate]](20 seconds).size == updates
        }, max = 60 seconds, interval = 1 second)
    }
  }

  test("wait for network announcements") {
    // generating more blocks so that all funding txes are buried under at least 6 blocks
    generateBlocks(bitcoincli, 4)
    // A requires private channels, as a consequence:
    // - only A and B know about channel A-B (and there is no channel_announcement)
    // - A is not announced (no node_announcement)
    awaitAnnouncements(nodes.filterKeys(key => List("A", "B").contains(key)).toMap, 5, 7, 16)
    awaitAnnouncements(nodes.filterKeys(key => List("C", "D", "E", "G").contains(key)).toMap, 5, 7, 14)
  }

  test("wait for channels balance") {
    // Channels balance should now be available in the router
    val sender = TestProbe()
    val nodeId = nodes("C").nodeParams.nodeId
    sender.send(nodes("C").router, Router.GetRoutingState)
    val routingState = sender.expectMsgType[Router.RoutingState]
    val publicChannels = routingState.channels.filter(pc => Set(pc.ann.nodeId1, pc.ann.nodeId2).contains(nodeId))
    assert(publicChannels.nonEmpty)
    publicChannels.foreach(pc => assert(pc.meta_opt.map(m => m.balance1 > 0.msat || m.balance2 > 0.msat) === Some(true), pc))
  }

  test("open a wumbo channel C <-> F1 and wait for longer than the default min_depth") {
    // we open a 5BTC channel and check that we scale `min_depth` up to 13 confirmations
    val funder = nodes("C")
    val fundee = nodes("F1")
    val tempChannelId = connect(funder, fundee, 5 btc, 100000000000L msat).channelId

    val sender = TestProbe()
    // mine the funding tx
    generateBlocks(bitcoincli, 2)
    // get the channelId
    sender.send(fundee.register, Symbol("channels"))
    val Some((_, fundeeChannel)) = sender.expectMsgType[Map[ByteVector32, ActorRef]].find(_._1 == tempChannelId)

    sender.send(fundeeChannel, CMD_GETSTATEDATA)
    val channelId = sender.expectMsgType[RES_GETSTATEDATA[HasCommitments]].data.channelId
    awaitCond({
      funder.register ! Register.Forward(sender.ref, channelId, CMD_GETSTATE)
      sender.expectMsgType[RES_GETSTATE[_]].state == WAIT_FOR_FUNDING_LOCKED
    })

    generateBlocks(bitcoincli, 6)

    // after 8 blocks the fundee is still waiting for more confirmations
    fundee.register ! Register.Forward(sender.ref, channelId, CMD_GETSTATE)
    assert(sender.expectMsgType[RES_GETSTATE[_]].state == WAIT_FOR_FUNDING_CONFIRMED)

    // after 8 blocks the funder is still waiting for funding_locked from the fundee
    funder.register ! Register.Forward(sender.ref, channelId, CMD_GETSTATE)
    assert(sender.expectMsgType[RES_GETSTATE[_]].state == WAIT_FOR_FUNDING_LOCKED)

    // simulate a disconnection
    sender.send(funder.switchboard, Peer.Disconnect(fundee.nodeParams.nodeId))
    assert(sender.expectMsgType[String] == "disconnecting")

    awaitCond({
      fundee.register ! Register.Forward(sender.ref, channelId, CMD_GETSTATE)
      val fundeeState = sender.expectMsgType[RES_GETSTATE[_]].state
      funder.register ! Register.Forward(sender.ref, channelId, CMD_GETSTATE)
      val funderState = sender.expectMsgType[RES_GETSTATE[_]].state
      fundeeState == OFFLINE && funderState == OFFLINE
    })

    // reconnect and check the fundee is waiting for more conf, funder is waiting for fundee to send funding_locked
    awaitCond({
      // reconnection
      sender.send(fundee.switchboard, Peer.Connect(
        nodeId = funder.nodeParams.nodeId,
        address_opt = Some(HostAndPort.fromParts(funder.nodeParams.publicAddresses.head.socketAddress.getHostString, funder.nodeParams.publicAddresses.head.socketAddress.getPort))
      ))
      sender.expectMsgAnyOf(10 seconds, PeerConnection.ConnectionResult.Connected, PeerConnection.ConnectionResult.AlreadyConnected)

      fundee.register ! Register.Forward(sender.ref, channelId, CMD_GETSTATE)
      val fundeeState = sender.expectMsgType[RES_GETSTATE[State]](max = 30 seconds).state
      funder.register ! Register.Forward(sender.ref, channelId, CMD_GETSTATE)
      val funderState = sender.expectMsgType[RES_GETSTATE[State]](max = 30 seconds).state
      fundeeState == WAIT_FOR_FUNDING_CONFIRMED && funderState == WAIT_FOR_FUNDING_LOCKED
    }, max = 30 seconds, interval = 10 seconds)

    // 5 extra blocks make it 13, just the amount of confirmations needed
    generateBlocks(bitcoincli, 5)

    awaitCond({
      fundee.register ! Register.Forward(sender.ref, channelId, CMD_GETSTATE)
      val fundeeState = sender.expectMsgType[RES_GETSTATE[State]].state
      funder.register ! Register.Forward(sender.ref, channelId, CMD_GETSTATE)
      val funderState = sender.expectMsgType[RES_GETSTATE[State]].state
      fundeeState == NORMAL && funderState == NORMAL
    })

    awaitAnnouncements(nodes.filterKeys(_ == "A").toMap, 6, 8, 18)
  }

  test("send an HTLC A->D") {
    val sender = TestProbe()
    val amountMsat = 4200000.msat
    // first we retrieve a payment hash from D
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, SendPaymentRequest(amountMsat, pr.paymentHash, nodes("D").nodeParams.nodeId, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID](5 seconds)
    val ps = sender.expectMsgType[PaymentSent](5 seconds)
    assert(ps.id == paymentId)
  }

  test("send an HTLC A->D with an invalid expiry delta for B") {
    val sender = TestProbe()
    // to simulate this, we will update B's relay params
    // first we find out the short channel id for channel B-C
    sender.send(nodes("B").router, Symbol("channels"))
    val shortIdBC = sender.expectMsgType[Iterable[ChannelAnnouncement]].find(c => Set(c.nodeId1, c.nodeId2) == Set(nodes("B").nodeParams.nodeId, nodes("C").nodeParams.nodeId)).get.shortChannelId
    // we also need the full commitment
    nodes("B").register ! Register.ForwardShortId(sender.ref, shortIdBC, CMD_GETINFO)
    val commitmentBC = sender.expectMsgType[RES_GETINFO].data.asInstanceOf[DATA_NORMAL].commitments
    // we then forge a new channel_update for B-C...
    val channelUpdateBC = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, nodes("B").nodeParams.privateKey, nodes("C").nodeParams.nodeId, shortIdBC, nodes("B").nodeParams.expiryDelta + 1, nodes("C").nodeParams.htlcMinimum, nodes("B").nodeParams.feeBase, nodes("B").nodeParams.feeProportionalMillionth, 500000000 msat)
    // ...and notify B's relayer
    sender.send(nodes("B").relayer, LocalChannelUpdate(system.deadLetters, commitmentBC.channelId, shortIdBC, commitmentBC.remoteParams.nodeId, None, channelUpdateBC, commitmentBC))
    // we retrieve a payment hash from D
    val amountMsat = 4200000.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the actual payment, do not randomize the route to make sure we route through node B
    val sendReq = SendPaymentRequest(amountMsat, pr.paymentHash, nodes("D").nodeParams.nodeId, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // A will receive an error from B that include the updated channel update, then will retry the payment
    val paymentId = sender.expectMsgType[UUID](5 seconds)
    val ps = sender.expectMsgType[PaymentSent](5 seconds)
    assert(ps.id == paymentId)

    def updateFor(n: PublicKey, pc: PublicChannel): Option[ChannelUpdate] = if (n == pc.ann.nodeId1) pc.update_1_opt else if (n == pc.ann.nodeId2) pc.update_2_opt else throw new IllegalArgumentException("this node is unrelated to this channel")

    awaitCond({
      // in the meantime, the router will have updated its state
      sender.send(nodes("A").router, Symbol("channelsMap"))
      // we then put everything back like before by asking B to refresh its channel update (this will override the one we created)
      val u_opt = updateFor(nodes("B").nodeParams.nodeId, sender.expectMsgType[Map[ShortChannelId, PublicChannel]](10 seconds).apply(channelUpdateBC.shortChannelId))
      u_opt.contains(channelUpdateBC)
    }, max = 30 seconds, interval = 1 seconds)

    // first let's wait 3 seconds to make sure the timestamp of the new channel_update will be strictly greater than the former
    sender.expectNoMsg(3 seconds)
    nodes("B").register ! Register.ForwardShortId(sender.ref, shortIdBC, BroadcastChannelUpdate(PeriodicRefresh))
    nodes("B").register ! Register.ForwardShortId(sender.ref, shortIdBC, CMD_GETINFO)
    val channelUpdateBC_new = sender.expectMsgType[RES_GETINFO].data.asInstanceOf[DATA_NORMAL].channelUpdate
    logger.info(s"channelUpdateBC=$channelUpdateBC")
    logger.info(s"channelUpdateBC_new=$channelUpdateBC_new")
    assert(channelUpdateBC_new.timestamp > channelUpdateBC.timestamp)
    assert(channelUpdateBC_new.cltvExpiryDelta == nodes("B").nodeParams.expiryDelta)
    awaitCond({
      sender.send(nodes("A").router, Symbol("channelsMap"))
      val u = updateFor(nodes("B").nodeParams.nodeId, sender.expectMsgType[Map[ShortChannelId, PublicChannel]](10 seconds).apply(channelUpdateBC.shortChannelId)).get
      u.cltvExpiryDelta == nodes("B").nodeParams.expiryDelta
    }, max = 30 seconds, interval = 1 second)
  }

  test("send an HTLC A->D with an amount greater than capacity of B-C") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D
    val amountMsat = 300000000.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the payment (B-C has a smaller capacity than A-B and C-D)
    val sendReq = SendPaymentRequest(amountMsat, pr.paymentHash, nodes("D").nodeParams.nodeId, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // A will first receive an error from C, then retry and route around C: A->B->E->C->D
    sender.expectMsgType[UUID](5 seconds)
    sender.expectMsgType[PaymentSent] // the payment FSM will also reply to the sender after the payment is completed
  }

  test("send an HTLC A->D with an unknown payment hash") {
    val sender = TestProbe()
    val pr = SendPaymentRequest(100000000 msat, randomBytes32, nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, pr)

    // A will receive an error from D and won't retry
    val paymentId = sender.expectMsgType[UUID]
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.id == paymentId)
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === DecryptedFailurePacket(nodes("D").nodeParams.nodeId, IncorrectOrUnknownPaymentDetails(100000000 msat, getBlockCount)))
  }

  test("send an HTLC A->D with a lower amount than requested") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = 200000000.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of only 1 mBTC
    val sendReq = SendPaymentRequest(100000000 msat, pr.paymentHash, nodes("D").nodeParams.nodeId, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)

    // A will first receive an IncorrectPaymentAmount error from D
    val paymentId = sender.expectMsgType[UUID]
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.id == paymentId)
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === DecryptedFailurePacket(nodes("D").nodeParams.nodeId, IncorrectOrUnknownPaymentDetails(100000000 msat, getBlockCount)))
  }

  test("send an HTLC A->D with too much overpayment") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = 200000000.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of 6 mBTC
    val sendReq = SendPaymentRequest(600000000 msat, pr.paymentHash, nodes("D").nodeParams.nodeId, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)

    // A will first receive an IncorrectPaymentAmount error from D
    val paymentId = sender.expectMsgType[UUID]
    val failed = sender.expectMsgType[PaymentFailed]
    assert(paymentId == failed.id)
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === DecryptedFailurePacket(nodes("D").nodeParams.nodeId, IncorrectOrUnknownPaymentDetails(600000000 msat, getBlockCount)))
  }

  test("send an HTLC A->D with a reasonable overpayment") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = 200000000.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of 3 mBTC, more than asked but it should still be accepted
    val sendReq = SendPaymentRequest(300000000 msat, pr.paymentHash, nodes("D").nodeParams.nodeId, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)
    sender.expectMsgType[UUID]
  }

  test("send multiple HTLCs A->D with a failover when a channel gets exhausted") {
    val sender = TestProbe()
    // there are two C-D channels with 5000000 sat, so we should be able to make 7 payments worth 1000000 sat each
    for (_ <- 0 until 7) {
      val amountMsat = 1000000000.msat
      sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 payment"))
      val pr = sender.expectMsgType[PaymentRequest]

      val sendReq = SendPaymentRequest(amountMsat, pr.paymentHash, nodes("D").nodeParams.nodeId, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 5)
      sender.send(nodes("A").paymentInitiator, sendReq)
      sender.expectMsgType[UUID]
      sender.expectMsgType[PaymentSent] // the payment FSM will also reply to the sender after the payment is completed
    }
  }

  test("send an HTLC A->B->G->C using heuristics to select the route") {
    val sender = TestProbe()
    // first we retrieve a payment hash from C
    val amountMsat = 2000.msat
    sender.send(nodes("C").paymentHandler, ReceivePayment(Some(amountMsat), "Change from coffee"))
    val pr = sender.expectMsgType[PaymentRequest](30 seconds)

    // the payment is requesting to use a capacity-optimized route which will select node G even though it's a bit more expensive
    sender.send(nodes("A").paymentInitiator, SendPaymentRequest(amountMsat, pr.paymentHash, nodes("C").nodeParams.nodeId, maxAttempts = 1, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams.map(_.copy(ratios = Some(WeightRatios(0, 0, 1))))))

    sender.expectMsgType[UUID](max = 60 seconds)
    awaitCond({
      sender.expectMsgType[PaymentEvent](10 seconds) match {
        case PaymentFailed(_, _, failures, _) => failures == Seq.empty // if something went wrong fail with a hint
        case PaymentSent(_, _, _, _, _, part :: Nil) => part.route.getOrElse(Nil).exists(_.nodeId == nodes("G").nodeParams.nodeId)
        case _ => false
      }
    }, max = 30 seconds, interval = 10 seconds)
  }

  test("send a multi-part payment B->D") {
    val start = System.currentTimeMillis
    val sender = TestProbe()
    val amount = 1000000000L.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amount), "split the restaurant bill"))
    val pr = sender.expectMsgType[PaymentRequest](15 seconds)
    assert(pr.features.allowMultiPart)

    sender.send(nodes("B").paymentInitiator, SendPaymentRequest(amount, pr.paymentHash, nodes("D").nodeParams.nodeId, 5, paymentRequest = Some(pr)))
    val paymentId = sender.expectMsgType[UUID](30 seconds)
    assert(sender.expectMsgType[PreimageReceived].paymentHash === pr.paymentHash)
    val paymentSent = sender.expectMsgType[PaymentSent](30 seconds)
    assert(paymentSent.id === paymentId, paymentSent)
    assert(paymentSent.paymentHash === pr.paymentHash, paymentSent)
    assert(paymentSent.parts.length > 1, paymentSent)
    assert(paymentSent.recipientNodeId === nodes("D").nodeParams.nodeId, paymentSent)
    assert(paymentSent.recipientAmount === amount, paymentSent)
    assert(paymentSent.feesPaid > 0.msat, paymentSent)
    assert(paymentSent.parts.forall(p => p.id != paymentSent.id), paymentSent)
    assert(paymentSent.parts.forall(p => p.route.isDefined), paymentSent)

    val paymentParts = nodes("B").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    assert(paymentParts.length == paymentSent.parts.length, paymentParts)
    assert(paymentParts.map(_.amount).sum === amount, paymentParts)
    assert(paymentParts.forall(p => p.parentId == paymentId), paymentParts)
    assert(paymentParts.forall(p => p.parentId != p.id), paymentParts)
    assert(paymentParts.forall(p => p.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].feesPaid > 0.msat), paymentParts)

    awaitCond(nodes("B").nodeParams.db.audit.listSent(start, System.currentTimeMillis).nonEmpty)
    val sent = nodes("B").nodeParams.db.audit.listSent(start, System.currentTimeMillis)
    assert(sent.length === 1, sent)
    assert(sent.head.copy(parts = sent.head.parts.sortBy(_.timestamp)) === paymentSent.copy(parts = paymentSent.parts.map(_.copy(route = None)).sortBy(_.timestamp)), sent)

    awaitCond(nodes("D").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("D").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(receivedAmount === amount)
  }

  // NB: this test may take 20 seconds to complete (multi-part payment timeout).
  test("send a multi-part payment B->D greater than balance C->D (temporary remote failure)") {
    val sender = TestProbe()
    // There is enough channel capacity to route this payment, but D doesn't have enough incoming capacity to receive it
    // (the link C->D has too much capacity on D's side).
    val amount = 2000000000L.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amount), "well that's an expensive restaurant bill"))
    val pr = sender.expectMsgType[PaymentRequest](15 seconds)
    assert(pr.features.allowMultiPart)

    sender.send(nodes("B").relayer, Relayer.GetOutgoingChannels())
    val canSend = sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    assert(canSend > amount)

    sender.send(nodes("B").paymentInitiator, SendPaymentRequest(amount, pr.paymentHash, nodes("D").nodeParams.nodeId, 1, paymentRequest = Some(pr)))
    val paymentId = sender.expectMsgType[UUID](30 seconds)
    val paymentFailed = sender.expectMsgType[PaymentFailed](45 seconds)
    assert(paymentFailed.id === paymentId, paymentFailed)
    assert(paymentFailed.paymentHash === pr.paymentHash, paymentFailed)
    assert(paymentFailed.failures.length > 1, paymentFailed)

    assert(nodes("D").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)

    sender.send(nodes("B").relayer, Relayer.GetOutgoingChannels())
    val canSend2 = sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    // Fee updates may impact balances, but it shouldn't have changed much.
    assert(math.abs((canSend - canSend2).toLong) < 50000000)
  }

  test("send a multi-part payment D->C (local channels only)") {
    val sender = TestProbe()
    // This amount is greater than any channel capacity between D and C, so it should be split.
    val amount = 5100000000L.msat
    sender.send(nodes("C").paymentHandler, ReceivePayment(Some(amount), "lemme borrow some money"))
    val pr = sender.expectMsgType[PaymentRequest](15 seconds)
    assert(pr.features.allowMultiPart)

    sender.send(nodes("D").paymentInitiator, SendPaymentRequest(amount, pr.paymentHash, nodes("C").nodeParams.nodeId, 3, paymentRequest = Some(pr)))
    val paymentId = sender.expectMsgType[UUID](30 seconds)
    assert(sender.expectMsgType[PreimageReceived].paymentHash === pr.paymentHash)
    val paymentSent = sender.expectMsgType[PaymentSent](30 seconds)
    assert(paymentSent.id === paymentId, paymentSent)
    assert(paymentSent.paymentHash === pr.paymentHash, paymentSent)
    assert(paymentSent.parts.length > 1, paymentSent)
    assert(paymentSent.recipientAmount === amount, paymentSent)
    assert(paymentSent.feesPaid === 0.msat, paymentSent) // no fees when using direct channels

    val paymentParts = nodes("D").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    assert(paymentParts.map(_.amount).sum === amount, paymentParts)
    assert(paymentParts.forall(p => p.parentId == paymentId), paymentParts)
    assert(paymentParts.forall(p => p.parentId != p.id), paymentParts)
    assert(paymentParts.forall(p => p.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].feesPaid == 0.msat), paymentParts)

    awaitCond(nodes("C").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("C").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(receivedAmount === amount)
  }

  test("send a multi-part payment D->C greater than balance D->C (temporary local failure)") {
    val sender = TestProbe()
    // This amount is greater than the current capacity between D and C.
    val amount = 10000000000L.msat
    sender.send(nodes("C").paymentHandler, ReceivePayment(Some(amount), "lemme borrow more money"))
    val pr = sender.expectMsgType[PaymentRequest](15 seconds)
    assert(pr.features.allowMultiPart)

    sender.send(nodes("D").relayer, Relayer.GetOutgoingChannels())
    val canSend = sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    assert(canSend < amount)

    sender.send(nodes("D").paymentInitiator, SendPaymentRequest(amount, pr.paymentHash, nodes("C").nodeParams.nodeId, 1, paymentRequest = Some(pr)))
    val paymentId = sender.expectMsgType[UUID](30 seconds)
    val paymentFailed = sender.expectMsgType[PaymentFailed](30 seconds)
    assert(paymentFailed.id === paymentId, paymentFailed)
    assert(paymentFailed.paymentHash === pr.paymentHash, paymentFailed)

    val incoming = nodes("C").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(incoming.get.status === IncomingPaymentStatus.Pending, incoming)

    sender.send(nodes("D").relayer, Relayer.GetOutgoingChannels())
    val canSend2 = sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    // Fee updates may impact balances, but it shouldn't have changed much.
    assert(math.abs((canSend - canSend2).toLong) < 50000000)
  }

  test("send a trampoline payment B->F1 with retry (via trampoline G)") {
    val start = System.currentTimeMillis
    val sender = TestProbe()
    val amount = 4000000000L.msat
    sender.send(nodes("F1").paymentHandler, ReceivePayment(Some(amount), "like trampoline much?"))
    val pr = sender.expectMsgType[PaymentRequest](15 seconds)
    assert(pr.features.allowMultiPart)
    assert(pr.features.allowTrampoline)

    // The first attempt should fail, but the second one should succeed.
    val attempts = (1000 msat, CltvExpiryDelta(42)) :: (1000000 msat, CltvExpiryDelta(288)) :: Nil
    val payment = SendTrampolinePaymentRequest(amount, pr, nodes("G").nodeParams.nodeId, attempts)
    sender.send(nodes("B").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID](30 seconds)
    val paymentSent = sender.expectMsgType[PaymentSent](30 seconds)
    assert(paymentSent.id === paymentId, paymentSent)
    assert(paymentSent.paymentHash === pr.paymentHash, paymentSent)
    assert(paymentSent.recipientNodeId === nodes("F1").nodeParams.nodeId, paymentSent)
    assert(paymentSent.recipientAmount === amount, paymentSent)
    assert(paymentSent.feesPaid === 1000000.msat, paymentSent)
    assert(paymentSent.nonTrampolineFees === 0.msat, paymentSent)

    awaitCond(nodes("F1").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("F1").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(receivedAmount === amount)

    awaitCond({
      val relayed = nodes("G").nodeParams.db.audit.listRelayed(start, System.currentTimeMillis).filter(_.paymentHash == pr.paymentHash)
      relayed.nonEmpty && relayed.head.amountOut >= amount
    })
    val relayed = nodes("G").nodeParams.db.audit.listRelayed(start, System.currentTimeMillis).filter(_.paymentHash == pr.paymentHash).head
    assert(relayed.amountIn - relayed.amountOut > 0.msat, relayed)
    assert(relayed.amountIn - relayed.amountOut < 1000000.msat, relayed)

    val outgoingSuccess = nodes("B").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(p => p.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    outgoingSuccess.collect { case p@OutgoingPayment(_, _, _, _, _, _, _, recipientNodeId, _, _, OutgoingPaymentStatus.Succeeded(_, _, route, _)) =>
      assert(recipientNodeId === nodes("F1").nodeParams.nodeId, p)
      assert(route.lastOption === Some(HopSummary(nodes("G").nodeParams.nodeId, nodes("F1").nodeParams.nodeId)), p)
    }
    assert(outgoingSuccess.map(_.amount).sum === amount + 1000000.msat, outgoingSuccess)
  }

  test("send a trampoline payment D->B (via trampoline C)") {
    val start = System.currentTimeMillis
    val sender = TestProbe()
    val amount = 2500000000L.msat
    sender.send(nodes("B").paymentHandler, ReceivePayment(Some(amount), "trampoline-MPP is so #reckless"))
    val pr = sender.expectMsgType[PaymentRequest](15 seconds)
    assert(pr.features.allowMultiPart)
    assert(pr.features.allowTrampoline)

    val payment = SendTrampolinePaymentRequest(amount, pr, nodes("C").nodeParams.nodeId, Seq((350000 msat, CltvExpiryDelta(288))))
    sender.send(nodes("D").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID](30 seconds)
    val paymentSent = sender.expectMsgType[PaymentSent](30 seconds)
    assert(paymentSent.id === paymentId, paymentSent)
    assert(paymentSent.paymentHash === pr.paymentHash, paymentSent)
    assert(paymentSent.recipientAmount === amount, paymentSent)
    assert(paymentSent.feesPaid === 350000.msat, paymentSent)
    assert(paymentSent.nonTrampolineFees === 0.msat, paymentSent)

    awaitCond(nodes("B").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("B").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(receivedAmount === amount)

    awaitCond({
      val relayed = nodes("C").nodeParams.db.audit.listRelayed(start, System.currentTimeMillis).filter(_.paymentHash == pr.paymentHash)
      relayed.nonEmpty && relayed.head.amountOut >= amount
    })
    val relayed = nodes("C").nodeParams.db.audit.listRelayed(start, System.currentTimeMillis).filter(_.paymentHash == pr.paymentHash).head
    assert(relayed.amountIn - relayed.amountOut > 0.msat, relayed)
    assert(relayed.amountIn - relayed.amountOut < 350000.msat, relayed)

    val outgoingSuccess = nodes("D").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(p => p.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    outgoingSuccess.collect { case p@OutgoingPayment(_, _, _, _, _, _, _, recipientNodeId, _, _, OutgoingPaymentStatus.Succeeded(_, _, route, _)) =>
      assert(recipientNodeId === nodes("B").nodeParams.nodeId, p)
      assert(route.lastOption === Some(HopSummary(nodes("C").nodeParams.nodeId, nodes("B").nodeParams.nodeId)), p)
    }
    assert(outgoingSuccess.map(_.amount).sum === amount + 350000.msat, outgoingSuccess)

    awaitCond(nodes("D").nodeParams.db.audit.listSent(start, System.currentTimeMillis).nonEmpty)
    val sent = nodes("D").nodeParams.db.audit.listSent(start, System.currentTimeMillis)
    assert(sent.length === 1, sent)
    assert(sent.head.copy(parts = sent.head.parts.sortBy(_.timestamp)) === paymentSent.copy(parts = paymentSent.parts.map(_.copy(route = None)).sortBy(_.timestamp)), sent)
  }

  test("send a trampoline payment F1->A (via trampoline C, non-trampoline recipient)") {
    // The A -> B channel is not announced.
    val start = System.currentTimeMillis
    val sender = TestProbe()
    sender.send(nodes("B").relayer, Relayer.GetOutgoingChannels())
    val channelUpdate_ba = sender.expectMsgType[Relayer.OutgoingChannels].channels.filter(c => c.nextNodeId == nodes("A").nodeParams.nodeId).head.channelUpdate
    val routingHints = List(List(ExtraHop(nodes("B").nodeParams.nodeId, channelUpdate_ba.shortChannelId, channelUpdate_ba.feeBaseMsat, channelUpdate_ba.feeProportionalMillionths, channelUpdate_ba.cltvExpiryDelta)))

    val amount = 3000000000L.msat
    sender.send(nodes("A").paymentHandler, ReceivePayment(Some(amount), "trampoline to non-trampoline is so #vintage", extraHops = routingHints))
    val pr = sender.expectMsgType[PaymentRequest](15 seconds)
    assert(pr.features.allowMultiPart)
    assert(!pr.features.allowTrampoline)

    val payment = SendTrampolinePaymentRequest(amount, pr, nodes("C").nodeParams.nodeId, Seq((1000000 msat, CltvExpiryDelta(432))))
    sender.send(nodes("F1").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID](30 seconds)
    val paymentSent = sender.expectMsgType[PaymentSent](30 seconds)
    assert(paymentSent.id === paymentId, paymentSent)
    assert(paymentSent.paymentHash === pr.paymentHash, paymentSent)
    assert(paymentSent.recipientAmount === amount, paymentSent)
    assert(paymentSent.trampolineFees === 1000000.msat, paymentSent)

    awaitCond(nodes("A").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("A").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(receivedAmount === amount)

    awaitCond({
      val relayed = nodes("C").nodeParams.db.audit.listRelayed(start, System.currentTimeMillis).filter(_.paymentHash == pr.paymentHash)
      relayed.nonEmpty && relayed.head.amountOut >= amount
    })
    val relayed = nodes("C").nodeParams.db.audit.listRelayed(start, System.currentTimeMillis).filter(_.paymentHash == pr.paymentHash).head
    assert(relayed.amountIn - relayed.amountOut > 0.msat, relayed)
    assert(relayed.amountIn - relayed.amountOut < 1000000.msat, relayed)

    val outgoingSuccess = nodes("F1").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(p => p.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    outgoingSuccess.collect { case p@OutgoingPayment(_, _, _, _, _, _, _, recipientNodeId, _, _, OutgoingPaymentStatus.Succeeded(_, _, route, _)) =>
      assert(recipientNodeId === nodes("A").nodeParams.nodeId, p)
      assert(route.lastOption === Some(HopSummary(nodes("C").nodeParams.nodeId, nodes("A").nodeParams.nodeId)), p)
    }
    assert(outgoingSuccess.map(_.amount).sum === amount + 1000000.msat, outgoingSuccess)
  }

  test("send a trampoline payment B->D (temporary local failure at trampoline)") {
    val sender = TestProbe()

    // We put most of the capacity C <-> D on D's side.
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(8000000000L msat), "plz send everything"))
    val pr1 = sender.expectMsgType[PaymentRequest](15 seconds)
    sender.send(nodes("C").paymentInitiator, SendPaymentRequest(8000000000L msat, pr1.paymentHash, nodes("D").nodeParams.nodeId, 3, paymentRequest = Some(pr1)))
    sender.expectMsgType[UUID](30 seconds)
    sender.expectMsgType[PreimageReceived](30 seconds)
    sender.expectMsgType[PaymentSent](30 seconds)

    // Now we try to send more than C's outgoing capacity to D.
    val amount = 2000000000L.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amount), "I iz Satoshi"))
    val pr = sender.expectMsgType[PaymentRequest](15 seconds)
    assert(pr.features.allowMultiPart)
    assert(pr.features.allowTrampoline)

    val payment = SendTrampolinePaymentRequest(amount, pr, nodes("C").nodeParams.nodeId, Seq((250000 msat, CltvExpiryDelta(288))))
    sender.send(nodes("B").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID](30 seconds)
    val paymentFailed = sender.expectMsgType[PaymentFailed](30 seconds)
    assert(paymentFailed.id === paymentId, paymentFailed)
    assert(paymentFailed.paymentHash === pr.paymentHash, paymentFailed)

    assert(nodes("D").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
    val outgoingPayments = nodes("B").nodeParams.db.payments.listOutgoingPayments(paymentId)
    assert(outgoingPayments.nonEmpty, outgoingPayments)
    assert(outgoingPayments.forall(p => p.status.isInstanceOf[OutgoingPaymentStatus.Failed]), outgoingPayments)
  }

  test("send a trampoline payment A->D (temporary remote failure at trampoline)") {
    val sender = TestProbe()
    val amount = 2000000000L.msat // B can forward to C, but C doesn't have that much outgoing capacity to D
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amount), "I iz not Satoshi"))
    val pr = sender.expectMsgType[PaymentRequest](15 seconds)
    assert(pr.features.allowMultiPart)
    assert(pr.features.allowTrampoline)

    val payment = SendTrampolinePaymentRequest(amount, pr, nodes("B").nodeParams.nodeId, Seq((450000 msat, CltvExpiryDelta(288))))
    sender.send(nodes("A").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID](30 seconds)
    val paymentFailed = sender.expectMsgType[PaymentFailed](30 seconds)
    assert(paymentFailed.id === paymentId, paymentFailed)
    assert(paymentFailed.paymentHash === pr.paymentHash, paymentFailed)

    assert(nodes("D").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
    val outgoingPayments = nodes("A").nodeParams.db.payments.listOutgoingPayments(paymentId)
    assert(outgoingPayments.nonEmpty, outgoingPayments)
    assert(outgoingPayments.forall(p => p.status.isInstanceOf[OutgoingPaymentStatus.Failed]), outgoingPayments)
  }

  test("close channel C <-> F1") {
    val sender = TestProbe()
    sender.send(nodes("F1").register, Symbol("channelsTo"))
    val channels = sender.expectMsgType[Map[ByteVector32, PublicKey]]
    assert(channels.size === 1)
    assert(channels.head._2 === nodes("C").nodeParams.nodeId)
    val channelId = channels.head._1

    sender.send(nodes("C").register, Register.Forward(sender.ref, channelId, CMD_GETSTATEDATA))
    val commitmentsC = sender.expectMsgType[RES_GETSTATEDATA[DATA_NORMAL]].data.commitments
    val finalPubKeyScriptC = commitmentsC.localParams.defaultFinalScriptPubKey
    val fundingOutpoint = commitmentsC.commitInput.outPoint
    sender.send(nodes("F1").register, Register.Forward(sender.ref, channelId, CMD_GETSTATEDATA))
    val finalPubKeyScriptF = sender.expectMsgType[RES_GETSTATEDATA[DATA_NORMAL]].data.commitments.localParams.defaultFinalScriptPubKey

    sender.send(nodes("F1").register, Register.Forward(sender.ref, channelId, CMD_CLOSE(Some(finalPubKeyScriptF))))
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    // we then wait for C and F to negotiate the closing fee
    awaitCond({
      sender.send(nodes("C").register, Register.Forward(sender.ref, channelId, CMD_GETSTATE))
      sender.expectMsgType[RES_GETSTATE[State]].state == CLOSING
    }, max = 20 seconds, interval = 1 second)

    generateBlocks(bitcoincli, 2)
    awaitCond({
      sender.send(nodes("C").register, Register.Forward(sender.ref, channelId, CMD_GETSTATE))
      sender.expectMsgType[RES_GETSTATE[State]].state == CLOSED
    }, max = 20 seconds, interval = 1 second)

    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    bitcoinClient.lookForSpendingTx(None, fundingOutpoint.txid, fundingOutpoint.index.toInt).pipeTo(sender.ref)
    val closingTx = sender.expectMsgType[Transaction]
    assert(closingTx.txOut.map(_.publicKeyScript).toSet === Set(finalPubKeyScriptC, finalPubKeyScriptF))

    awaitAnnouncements(nodes.filterKeys(_ == "A").toMap, 5, 7, 16)
  }

  test("open channel C <-> F2, send payments and close (option_anchor_outputs, option_static_remotekey)") {
    connect(nodes("C"), nodes("F2"), 5000000 sat, 0 msat)
    generateBlocks(bitcoincli, 6)
    awaitAnnouncements(nodes.filterKeys(_ == "A").toMap, 6, 8, 18)

    // initially all the balance is on C side and F2 doesn't have an output
    val sender = TestProbe()
    sender.send(nodes("F2").register, Symbol("channelsTo"))
    // retrieve the channelId of C <--> F2
    val Some(channelId) = sender.expectMsgType[Map[ByteVector32, PublicKey]].find(_._2 == nodes("C").nodeParams.nodeId).map(_._1)

    sender.send(nodes("F2").register, Register.Forward(sender.ref, channelId, CMD_GETSTATEDATA))
    val initialStateDataF2 = sender.expectMsgType[RES_GETSTATEDATA[DATA_NORMAL]].data
    val initialCommitmentIndex = initialStateDataF2.commitments.localCommit.index

    // the 'to remote' address is a simple script spending to the remote payment basepoint with a 1-block CSV delay
    val toRemoteAddress = Script.pay2wsh(Scripts.toRemoteDelayed(initialStateDataF2.commitments.remoteParams.paymentBasepoint))

    // toRemote output of C as seen by F2
    val Some(toRemoteOutC) = initialStateDataF2.commitments.localCommit.publishableTxs.commitTx.tx.txOut.find(_.publicKeyScript == Script.write(toRemoteAddress))

    // let's make a payment to advance the commit index
    val amountMsat = 4200000.msat
    sender.send(nodes("F2").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // then we make the actual payment
    sender.send(nodes("C").paymentInitiator, SendPaymentRequest(amountMsat, pr.paymentHash, nodes("F2").nodeParams.nodeId, maxAttempts = 1, fallbackFinalExpiryDelta = finalCltvExpiryDelta))
    val paymentId = sender.expectMsgType[UUID](5 seconds)
    val ps = sender.expectMsgType[PaymentSent](5 seconds)
    assert(ps.id == paymentId)

    sender.send(nodes("F2").register, Register.Forward(sender.ref, channelId, CMD_GETSTATEDATA))
    val stateDataF2 = sender.expectMsgType[RES_GETSTATEDATA[DATA_NORMAL]].data
    val commitmentIndex = stateDataF2.commitments.localCommit.index
    val commitTx = stateDataF2.commitments.localCommit.publishableTxs.commitTx.tx
    val Some(toRemoteOutCNew) = commitTx.txOut.find(_.publicKeyScript == Script.write(toRemoteAddress))

    // there is a new commitment index in the channel state
    assert(commitmentIndex == initialCommitmentIndex + 1)

    // script pubkeys of toRemote output remained the same across commitments
    assert(toRemoteOutCNew.publicKeyScript == toRemoteOutC.publicKeyScript)
    assert(toRemoteOutCNew.amount < toRemoteOutC.amount)

    // now let's force close the channel and check the toRemote is what we had at the beginning
    sender.send(nodes("F2").register, Register.Forward(sender.ref, channelId, CMD_FORCECLOSE))
    sender.expectMsgType[RES_SUCCESS[CMD_FORCECLOSE.type]]
    // we then wait for C to detect the unilateral close and go to CLOSING state
    awaitCond({
      nodes("C").register ! Register.Forward(sender.ref, channelId, CMD_GETSTATE)
      sender.expectMsgType[RES_GETSTATE[State]].state == CLOSING
    }, max = 20 seconds, interval = 1 second)

    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    bitcoinClient.getTransaction(commitTx.txid).pipeTo(sender.ref)
    val tx = sender.expectMsgType[Transaction](10 seconds)

    // the unilateral close contains the static toRemote output
    assert(tx.txOut.exists(_.publicKeyScript == toRemoteOutC.publicKeyScript))

    // bury the unilateral close in a block, since there are no outputs to claim the channel can go to CLOSED state
    generateBlocks(bitcoincli, 2)
    awaitCond({
      nodes("C").register ! Register.Forward(sender.ref, channelId, CMD_GETSTATE)
      sender.expectMsgType[RES_GETSTATE[State]].state == CLOSED
    }, max = 20 seconds, interval = 1 second)
    awaitAnnouncements(nodes.filterKeys(_ == "A").toMap, 5, 7, 16)
  }

  /**
   * We currently use p2pkh script Helpers.getFinalScriptPubKey
   */
  def scriptPubKeyToAddress(scriptPubKey: ByteVector): String = Script.parse(scriptPubKey) match {
    case OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pubKeyHash, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil =>
      Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, pubKeyHash)
    case OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil =>
      Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, scriptHash)
    case OP_0 :: OP_PUSHDATA(pubKeyHash, _) :: Nil if pubKeyHash.length == 20 => Bech32.encodeWitnessAddress("bcrt", 0, pubKeyHash)
    case OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil if scriptHash.length == 32 => Bech32.encodeWitnessAddress("bcrt", 0, scriptHash)
    case _ => ???
  }

  def listReceivedByAddress(address: String, sender: TestProbe = TestProbe()): Seq[ByteVector32] = {
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    res.filter(_ \ "address" == JString(address)).flatMap(_ \ "txids" \\ classOf[JString]).map(ByteVector32.fromValidHex)
  }

  def getBlockCount: Long = {
    // we make sure that all nodes have the same value
    awaitCond(nodes.values.map(_.nodeParams.currentBlockHeight).toSet.size == 1, max = 1 minute, interval = 1 second)
    // and we return it (NB: it could be a different value at this point)
    nodes.values.head.nodeParams.currentBlockHeight
  }

  /** Wait for the given transaction to be either in the mempool or confirmed. */
  def waitForTxBroadcastOrConfirmed(txid: ByteVector32, bitcoinClient: ExtendedBitcoinClient, sender: TestProbe): Unit = {
    awaitCond({
      bitcoinClient.getMempool().pipeTo(sender.ref)
      val inMempool = sender.expectMsgType[Seq[Transaction]].exists(_.txid == txid)
      bitcoinClient.getTxConfirmations(txid).pipeTo(sender.ref)
      val confirmed = sender.expectMsgType[Option[Int]].nonEmpty
      inMempool || confirmed
    }, max = 30 seconds, interval = 1 second)
  }

  /** Disconnect node C from a given F node. */
  def disconnectCF(nodeF: String, channelId: ByteVector32, sender: TestProbe = TestProbe()): Unit = {
    sender.send(nodes(nodeF).switchboard, Symbol("peers"))
    val peers = sender.expectMsgType[Iterable[ActorRef]]
    // F's only node is C
    peers.head ! Peer.Disconnect(nodes("C").nodeParams.nodeId)
    // we then wait for F to be in disconnected state
    awaitCond({
      sender.send(nodes(nodeF).register, Register.Forward(sender.ref, channelId, CMD_GETSTATE))
      sender.expectMsgType[RES_GETSTATE[State]].state == OFFLINE
    }, max = 20 seconds, interval = 1 second)
  }

  case class ForceCloseFixture(sender: TestProbe, paymentSender: TestProbe, stateListener: TestProbe, paymentId: UUID, htlc: UpdateAddHtlc, preimage: ByteVector32, minerAddress: String, finalAddressC: String, finalAddressF: String)

  /** Prepare a C <-> F channel for a force-close test by adding an HTLC that will be hodl-ed at F. */
  def prepareForceCloseCF(nodeF: String, commitmentFormat: Transactions.CommitmentFormat): ForceCloseFixture = {
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(minerAddress) = sender.expectMsgType[JValue]
    // we create and announce a channel between C and F; we use push_msat to ensure both nodes have an output in the commit tx
    connect(nodes("C"), nodes(nodeF), 5000000 sat, 500000000 msat)
    generateBlocks(bitcoincli, 6, Some(minerAddress))
    awaitAnnouncements(nodes.filterKeys(_ == "A").toMap, 6, 8, 18)
    // we subscribe to C's channel state transitions
    val stateListener = TestProbe()
    nodes("C").system.eventStream.subscribe(stateListener.ref, classOf[ChannelStateChanged])
    // first we make sure we are in sync with current blockchain height
    val currentBlockCount = getBlockCount
    awaitCond(getBlockCount == currentBlockCount, max = 20 seconds, interval = 1 second)
    // we use this to control when to fulfill htlcs
    val htlcReceiver = TestProbe()
    nodes(nodeF).paymentHandler ! new ForwardHandler(htlcReceiver.ref)
    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPaymentRequest(100000000 msat, paymentHash, nodes(nodeF).nodeParams.nodeId, maxAttempts = 1, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    val paymentId = paymentSender.expectMsgType[UUID](30 seconds)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[IncomingPacket.FinalPacket].add
    // now that we have the channel id, we retrieve channels default final addresses
    sender.send(nodes("C").register, Register.Forward(sender.ref, htlc.channelId, CMD_GETSTATEDATA))
    val dataC = sender.expectMsgType[RES_GETSTATEDATA[DATA_NORMAL]].data
    assert(dataC.commitments.commitmentFormat === commitmentFormat)
    val finalAddressC = scriptPubKeyToAddress(dataC.commitments.localParams.defaultFinalScriptPubKey)
    sender.send(nodes(nodeF).register, Register.Forward(sender.ref, htlc.channelId, CMD_GETSTATEDATA))
    val dataF = sender.expectMsgType[RES_GETSTATEDATA[DATA_NORMAL]].data
    assert(dataF.commitments.commitmentFormat === commitmentFormat)
    val finalAddressF = scriptPubKeyToAddress(dataF.commitments.localParams.defaultFinalScriptPubKey)
    ForceCloseFixture(sender, paymentSender, stateListener, paymentId, htlc, preimage, minerAddress, finalAddressC, finalAddressF)
  }

  def testDownstreamFulfillLocalCommit(nodeF: String, commitmentFormat: Transactions.CommitmentFormat): Unit = {
    val forceCloseFixture = prepareForceCloseCF(nodeF, commitmentFormat)
    import forceCloseFixture._

    // we retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    val previouslyReceivedByC = listReceivedByAddress(finalAddressC, sender)
    // we then kill the connection between C and F
    disconnectCF(nodeF, htlc.channelId, sender)
    // we then have C unilaterally close the channel (which will make F redeem the htlc onchain)
    sender.send(nodes("C").register, Register.Forward(sender.ref, htlc.channelId, CMD_FORCECLOSE))
    sender.expectMsgType[RES_SUCCESS[CMD_FORCECLOSE.type]]
    // we then wait for F to detect the unilateral close and go to CLOSING state
    awaitCond({
      sender.send(nodes(nodeF).register, Register.Forward(sender.ref, htlc.channelId, CMD_GETSTATE))
      sender.expectMsgType[RES_GETSTATE[State]].state == CLOSING
    }, max = 20 seconds, interval = 1 second)
    // we generate a few blocks to get the commit tx confirmed
    generateBlocks(bitcoincli, 3, Some(minerAddress))
    // we then fulfill the htlc, which will make F redeem it on-chain
    sender.send(nodes(nodeF).register, Register.Forward(sender.ref, htlc.channelId, CMD_FULFILL_HTLC(htlc.id, preimage)))
    // we don't need to generate blocks to confirm the htlc-success; C should extract the preimage as soon as it enters
    // the mempool and fulfill the payment upstream.
    paymentSender.expectMsgType[PaymentSent](30 seconds)
    // we then generate enough blocks so that nodes get their main delayed output
    generateBlocks(bitcoincli, 145, Some(minerAddress))
    // F should have 2 recv transactions: the redeemed htlc and its main output
    // C should have 1 recv transaction: its main output
    awaitCond({
      val receivedByC = listReceivedByAddress(finalAddressC, sender)
      val receivedByF = listReceivedByAddress(finalAddressF)
      receivedByF.size == 2 && (receivedByC diff previouslyReceivedByC).size == 1
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make txs confirm
    generateBlocks(bitcoincli, 2, Some(minerAddress))
    // and we wait for the channel to close
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSED, max = 30 seconds)
    awaitCond({
      sender.send(nodes(nodeF).register, Register.Forward(sender.ref, htlc.channelId, CMD_GETSTATE))
      sender.expectMsgType[RES_GETSTATE[State]].state == CLOSED
    }, max = 20 seconds, interval = 1 second)
    awaitAnnouncements(nodes.filterKeys(_ == "A").toMap, 5, 7, 16)
  }

  test("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (local commit)") {
    testDownstreamFulfillLocalCommit("F1", Transactions.DefaultCommitmentFormat)
  }

  def testDownstreamFulfillRemoteCommit(nodeF: String, commitmentFormat: Transactions.CommitmentFormat): Unit = {
    val forceCloseFixture = prepareForceCloseCF(nodeF, commitmentFormat)
    import forceCloseFixture._

    // we retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    val previouslyReceivedByC = listReceivedByAddress(finalAddressC, sender)
    // we then kill the connection between C and F
    disconnectCF(nodeF, htlc.channelId, sender)
    // then we have F unilaterally close the channel
    sender.send(nodes(nodeF).register, Register.Forward(sender.ref, htlc.channelId, CMD_FORCECLOSE))
    sender.expectMsgType[RES_SUCCESS[CMD_FORCECLOSE.type]]
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSING, max = 30 seconds)
    // we generate a few blocks to get the commit tx confirmed
    generateBlocks(bitcoincli, 3, Some(minerAddress))
    // we then fulfill the htlc (it won't be sent to C, and will be used to pull funds on-chain)
    sender.send(nodes(nodeF).register, Register.Forward(sender.ref, htlc.channelId, CMD_FULFILL_HTLC(htlc.id, preimage)))
    // we don't need to generate blocks to confirm the htlc-success; C should extract the preimage as soon as it enters
    // the mempool and fulfill the payment upstream.
    paymentSender.expectMsgType[PaymentSent](30 seconds)
    // we then generate enough blocks so that F gets its htlc-success delayed output
    generateBlocks(bitcoincli, 145, Some(minerAddress))
    // F should have 2 recv transactions: the redeemed htlc and its main output
    // C should have 1 recv transaction: its main output
    awaitCond({
      val receivedByC = listReceivedByAddress(finalAddressC, sender)
      val receivedByF = listReceivedByAddress(finalAddressF, sender)
      receivedByF.size == 2 && (receivedByC diff previouslyReceivedByC).size == 1
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make txs confirm
    generateBlocks(bitcoincli, 2, Some(minerAddress))
    // and we wait for the channel to close
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSED, max = 30 seconds)
    awaitCond({
      sender.send(nodes(nodeF).register, Register.Forward(sender.ref, htlc.channelId, CMD_GETSTATE))
      sender.expectMsgType[RES_GETSTATE[State]].state == CLOSED
    }, max = 20 seconds, interval = 1 second)
    awaitAnnouncements(nodes.filterKeys(_ == "A").toMap, 5, 7, 16)
  }

  test("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (remote commit)") {
    testDownstreamFulfillRemoteCommit("F1", Transactions.DefaultCommitmentFormat)
  }

  def testDownstreamTimeoutLocalCommit(nodeF: String, commitmentFormat: Transactions.CommitmentFormat): Unit = {
    val forceCloseFixture = prepareForceCloseCF(nodeF, commitmentFormat)
    import forceCloseFixture._

    // we retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    val previouslyReceivedByC = listReceivedByAddress(finalAddressC, sender)
    // we then kill the connection between C and F; otherwise F would send an error message to C when it detects the htlc
    // timeout. When that happens C would broadcast his commit tx, and if it gets to the mempool before F's commit tx we
    // won't be testing the right scenario.
    disconnectCF(nodeF, htlc.channelId, sender)
    // we generate enough blocks to reach the htlc timeout
    generateBlocks(bitcoincli, (htlc.cltvExpiry.toLong - getBlockCount).toInt, Some(minerAddress))
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSING, max = 30 seconds)
    sender.send(nodes("C").register, Register.Forward(sender.ref, htlc.channelId, CMD_GETSTATEDATA))
    val Some(localCommit) = sender.expectMsgType[RES_GETSTATEDATA[DATA_CLOSING]].data.localCommitPublished
    // we wait until the commit tx has been broadcast
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    waitForTxBroadcastOrConfirmed(localCommit.commitTx.txid, bitcoinClient, sender)
    // we generate a few blocks to get the commit tx confirmed
    generateBlocks(bitcoincli, 3, Some(minerAddress))
    // we wait until the htlc-timeout has been broadcast
    waitForTxBroadcastOrConfirmed(localCommit.htlcTimeoutTxs.head.txid, bitcoinClient, sender)
    // we generate more blocks for the htlc-timeout to reach enough confirmations
    generateBlocks(bitcoincli, 3, Some(minerAddress))
    // this will fail the htlc
    val failed = paymentSender.expectMsgType[PaymentFailed](30 seconds)
    assert(failed.id == paymentId)
    assert(failed.paymentHash === htlc.paymentHash)
    assert(failed.failures.nonEmpty)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === DecryptedFailurePacket(nodes("C").nodeParams.nodeId, PermanentChannelFailure))
    // we then generate enough blocks to confirm all delayed transactions
    generateBlocks(bitcoincli, 145, Some(minerAddress))
    // C should have 2 recv transactions: its main output and the htlc timeout
    // F should have 1 recv transaction: its main output
    awaitCond({
      val receivedByC = listReceivedByAddress(finalAddressC, sender)
      val receivedByF = listReceivedByAddress(finalAddressF, sender)
      receivedByF.size == 1 && (receivedByC diff previouslyReceivedByC).size == 2
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make txs confirm
    generateBlocks(bitcoincli, 2, Some(minerAddress))
    // and we wait for the channel to close
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSED, max = 30 seconds)
    awaitCond({
      sender.send(nodes(nodeF).register, Register.Forward(sender.ref, htlc.channelId, CMD_GETSTATE))
      sender.expectMsgType[RES_GETSTATE[State]].state == CLOSED
    }, max = 20 seconds, interval = 1 second)
    awaitAnnouncements(nodes.filterKeys(_ == "A").toMap, 5, 7, 16)
  }

  test("propagate a failure upstream when a downstream htlc times out (local commit)") {
    testDownstreamTimeoutLocalCommit("F1", Transactions.DefaultCommitmentFormat)
  }

  def testDownstreamTimeoutRemoteCommit(nodeF: String, commitmentFormat: Transactions.CommitmentFormat): Unit = {
    val forceCloseFixture = prepareForceCloseCF(nodeF, commitmentFormat)
    import forceCloseFixture._

    // we retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    val previouslyReceivedByC = listReceivedByAddress(finalAddressC, sender)
    // we then kill the connection between C and F to ensure the close can only be detected on-chain
    disconnectCF(nodeF, htlc.channelId, sender)
    // we ask F to unilaterally close the channel
    sender.send(nodes(nodeF).register, Register.Forward(sender.ref, htlc.channelId, CMD_FORCECLOSE))
    sender.expectMsgType[RES_SUCCESS[CMD_FORCECLOSE.type]]
    // we wait for C to detect the unilateral close
    awaitCond({
      sender.send(nodes("C").register, Register.Forward(sender.ref, htlc.channelId, CMD_GETSTATEDATA))
      sender.expectMsgType[RES_GETSTATEDATA[Data]].data match {
        case d: DATA_CLOSING if d.remoteCommitPublished.nonEmpty => true
        case _ => false
      }
    }, max = 30 seconds, interval = 1 second)
    sender.send(nodes("C").register, Register.Forward(sender.ref, htlc.channelId, CMD_GETSTATEDATA))
    val Some(remoteCommit) = sender.expectMsgType[RES_GETSTATEDATA[DATA_CLOSING]].data.remoteCommitPublished
    // we generate enough blocks to make the htlc timeout
    generateBlocks(bitcoincli, (htlc.cltvExpiry.toLong - getBlockCount).toInt, Some(minerAddress))
    // we wait until the claim-htlc-timeout has been broadcast
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    waitForTxBroadcastOrConfirmed(remoteCommit.claimHtlcTimeoutTxs.head.txid, bitcoinClient, sender)
    // and we generate blocks for the claim-htlc-timeout to reach enough confirmations
    generateBlocks(bitcoincli, 3, Some(minerAddress))
    // this will fail the htlc
    val failed = paymentSender.expectMsgType[PaymentFailed](30 seconds)
    assert(failed.id == paymentId)
    assert(failed.paymentHash === htlc.paymentHash)
    assert(failed.failures.nonEmpty)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === DecryptedFailurePacket(nodes("C").nodeParams.nodeId, PermanentChannelFailure))
    // we then generate enough blocks to confirm all delayed transactions
    generateBlocks(bitcoincli, 145, Some(minerAddress))
    // C should have 2 recv transactions: its main output and the htlc timeout
    // F should have 1 recv transaction: its main output
    awaitCond({
      val receivedByC = listReceivedByAddress(finalAddressC, sender)
      val receivedByF = listReceivedByAddress(finalAddressF, sender)
      receivedByF.size == 1 && (receivedByC diff previouslyReceivedByC).size == 2
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make tx confirm
    generateBlocks(bitcoincli, 2, Some(minerAddress))
    // and we wait for the channel to close
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSED, max = 30 seconds)
    awaitCond({
      sender.send(nodes(nodeF).register, Register.Forward(sender.ref, htlc.channelId, CMD_GETSTATE))
      sender.expectMsgType[RES_GETSTATE[State]].state == CLOSED
    }, max = 20 seconds, interval = 1 second)
    awaitAnnouncements(nodes.filterKeys(_ == "A").toMap, 5, 7, 16)
  }

  test("propagate a failure upstream when a downstream htlc times out (remote commit)") {
    testDownstreamTimeoutRemoteCommit("F1", Transactions.DefaultCommitmentFormat)
  }

  case class RevokedCommitFixture(sender: TestProbe, stateListenerC: TestProbe, revokedCommitTx: Transaction, htlcSuccess: Seq[Transaction], htlcTimeout: Seq[Transaction], finalAddressC: String)

  def testRevokedCommit(nodeF: String, commitmentFormat: Transactions.CommitmentFormat): RevokedCommitFixture = {
    val sender = TestProbe()
    // we create and announce a channel between C and F; we use push_msat to ensure F has a balance
    connect(nodes("C"), nodes(nodeF), 5000000 sat, 300000000 msat)
    generateBlocks(bitcoincli, 6)
    awaitAnnouncements(nodes.filterKeys(_ == "A").toMap, 6, 8, 18)
    // we subscribe to C's channel state transitions
    val stateListenerC = TestProbe()
    nodes("C").system.eventStream.subscribe(stateListenerC.ref, classOf[ChannelStateChanged])
    // we use this to get commitments
    val sigListener = TestProbe()
    nodes(nodeF).system.eventStream.subscribe(sigListener.ref, classOf[ChannelSignatureReceived])
    // we use this to control when to fulfill htlcs
    val forwardHandlerC = TestProbe()
    nodes("C").paymentHandler ! new ForwardHandler(forwardHandlerC.ref)
    val forwardHandlerF = TestProbe()
    nodes(nodeF).paymentHandler ! new ForwardHandler(forwardHandlerF.ref)
    // this is the actual payment handler that we will forward requests to
    val paymentHandlerC = nodes("C").system.actorOf(PaymentHandler.props(nodes("C").nodeParams, nodes("C").register))
    val paymentHandlerF = nodes(nodeF).system.actorOf(PaymentHandler.props(nodes(nodeF).nodeParams, nodes(nodeF).register))
    // first we make sure nodes are in sync with current blockchain height
    val currentBlockCount = getBlockCount
    awaitCond(getBlockCount == currentBlockCount, max = 20 seconds, interval = 1 second)

    // we now send a few htlcs C->F and F->C in order to obtain a commitments with multiple htlcs
    def send(amountMsat: MilliSatoshi, paymentHandler: ActorRef, paymentInitiator: ActorRef): UUID = {
      sender.send(paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
      val pr = sender.expectMsgType[PaymentRequest]
      val sendReq = SendPaymentRequest(amountMsat, pr.paymentHash, pr.nodeId, maxAttempts = 1, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams)
      sender.send(paymentInitiator, sendReq)
      sender.expectMsgType[UUID]
    }

    val buffer = TestProbe()
    send(100000000 msat, paymentHandlerF, nodes("C").paymentInitiator)
    forwardHandlerF.expectMsgType[IncomingPacket.FinalPacket]
    forwardHandlerF.forward(buffer.ref)
    sigListener.expectMsgType[ChannelSignatureReceived]
    send(110000000 msat, paymentHandlerF, nodes("C").paymentInitiator)
    forwardHandlerF.expectMsgType[IncomingPacket.FinalPacket]
    forwardHandlerF.forward(buffer.ref)
    sigListener.expectMsgType[ChannelSignatureReceived]
    send(120000000 msat, paymentHandlerC, nodes(nodeF).paymentInitiator)
    forwardHandlerC.expectMsgType[IncomingPacket.FinalPacket]
    forwardHandlerC.forward(buffer.ref)
    sigListener.expectMsgType[ChannelSignatureReceived]
    send(130000000 msat, paymentHandlerC, nodes(nodeF).paymentInitiator)
    forwardHandlerC.expectMsgType[IncomingPacket.FinalPacket]
    forwardHandlerC.forward(buffer.ref)
    val commitmentsF = sigListener.expectMsgType[ChannelSignatureReceived].commitments
    sigListener.expectNoMsg(1 second)
    assert(commitmentsF.commitmentFormat === commitmentFormat)
    // in this commitment, both parties should have a main output, and there are four pending htlcs
    val localCommitF = commitmentsF.localCommit.publishableTxs
    assert(localCommitF.commitTx.tx.txOut.size === 6)
    val htlcTimeoutTxs = localCommitF.htlcTxsAndSigs.collect { case h@HtlcTxAndSigs(_: Transactions.HtlcTimeoutTx, _, _) => h }
    val htlcSuccessTxs = localCommitF.htlcTxsAndSigs.collect { case h@HtlcTxAndSigs(_: Transactions.HtlcSuccessTx, _, _) => h }
    assert(htlcTimeoutTxs.size === 2)
    assert(htlcSuccessTxs.size === 2)
    // we fulfill htlcs to get the preimages
    buffer.expectMsgType[IncomingPacket.FinalPacket]
    buffer.forward(paymentHandlerF)
    sigListener.expectMsgType[ChannelSignatureReceived]
    val preimage1 = sender.expectMsgType[PaymentSent].paymentPreimage
    buffer.expectMsgType[IncomingPacket.FinalPacket]
    buffer.forward(paymentHandlerF)
    sigListener.expectMsgType[ChannelSignatureReceived]
    val preimage2 = sender.expectMsgType[PaymentSent].paymentPreimage
    buffer.expectMsgType[IncomingPacket.FinalPacket]
    buffer.forward(paymentHandlerC)
    sigListener.expectMsgType[ChannelSignatureReceived]
    sender.expectMsgType[PaymentSent]
    buffer.expectMsgType[IncomingPacket.FinalPacket]
    buffer.forward(paymentHandlerC)
    sigListener.expectMsgType[ChannelSignatureReceived]
    sender.expectMsgType[PaymentSent]
    // we then generate blocks to make htlcs timeout (nothing will happen in the channel because all of them have already been fulfilled)
    generateBlocks(bitcoincli, 40)
    // we retrieve C's default final address
    sender.send(nodes("C").register, Register.Forward(sender.ref, commitmentsF.channelId, CMD_GETSTATEDATA))
    val finalAddressC = scriptPubKeyToAddress(sender.expectMsgType[RES_GETSTATEDATA[DATA_NORMAL]].data.commitments.localParams.defaultFinalScriptPubKey)
    // we prepare the revoked transactions F will publish
    val revokedCommitTx = localCommitF.commitTx.tx
    val htlcSuccess = htlcSuccessTxs.zip(Seq(preimage1, preimage2)).map { case (htlcTxAndSigs, preimage) => Transactions.addSigs(htlcTxAndSigs.txinfo.asInstanceOf[Transactions.HtlcSuccessTx], htlcTxAndSigs.localSig, htlcTxAndSigs.remoteSig, preimage, commitmentsF.commitmentFormat).tx }
    val htlcTimeout = htlcTimeoutTxs.map(htlcTxAndSigs => Transactions.addSigs(htlcTxAndSigs.txinfo.asInstanceOf[Transactions.HtlcTimeoutTx], htlcTxAndSigs.localSig, htlcTxAndSigs.remoteSig, commitmentsF.commitmentFormat).tx)
    htlcSuccess.foreach(tx => Transaction.correctlySpends(tx, Seq(revokedCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    htlcTimeout.foreach(tx => Transaction.correctlySpends(tx, Seq(revokedCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    RevokedCommitFixture(sender, stateListenerC, revokedCommitTx, htlcSuccess, htlcTimeout, finalAddressC)
  }

  test("punish a node that has published a revoked commit tx") {
    val revokedCommitFixture = testRevokedCommit("F1", Transactions.DefaultCommitmentFormat)
    import revokedCommitFixture._

    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    // we retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    val previouslyReceivedByC = listReceivedByAddress(finalAddressC, sender)
    // F publishes the revoked commitment, one HTLC-success, one HTLC-timeout and leaves the other HTLC outputs unclaimed
    bitcoinClient.publishTransaction(revokedCommitTx).pipeTo(sender.ref)
    sender.expectMsg(revokedCommitTx.txid)
    bitcoinClient.publishTransaction(htlcSuccess.head).pipeTo(sender.ref)
    sender.expectMsg(htlcSuccess.head.txid)
    bitcoinClient.publishTransaction(htlcTimeout.head).pipeTo(sender.ref)
    sender.expectMsg(htlcTimeout.head.txid)
    // at this point C should have 6 recv transactions: its previous main output, F's main output and all htlc outputs (taken as punishment)
    awaitCond({
      val receivedByC = listReceivedByAddress(finalAddressC, sender)
      (receivedByC diff previouslyReceivedByC).size == 6
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make txs confirm
    generateBlocks(bitcoincli, 2)
    // and we wait for C's channel to close
    awaitCond(stateListenerC.expectMsgType[ChannelStateChanged].currentState == CLOSED, max = 30 seconds)
    awaitAnnouncements(nodes.filterKeys(_ == "A").toMap, 5, 7, 16)
  }

  test("generate and validate lots of channels") {
    implicit val bitcoinClient: ExtendedBitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    // we simulate fake channels by publishing a funding tx and sending announcement messages to a node at random
    logger.info(s"generating fake channels")
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = sender.expectMsgType[JValue]
    val channels = for (i <- 0 until 242) yield {
      // let's generate a block every 10 txs so that we can compute short ids
      if (i % 10 == 0) {
        generateBlocks(bitcoincli, 1, Some(address))
      }
      AnnouncementsBatchValidationSpec.simulateChannel
    }
    generateBlocks(bitcoincli, 1, Some(address))
    logger.info(s"simulated ${channels.size} channels")

    val remoteNodeId = PrivateKey(ByteVector32(ByteVector.fill(32)(1))).publicKey

    // then we make the announcements
    val announcements = channels.map(c => AnnouncementsBatchValidationSpec.makeChannelAnnouncement(c))
    announcements.foreach { ann =>
      nodes("A").router ! PeerRoutingMessage(sender.ref, remoteNodeId, ann)
      sender.expectMsg(TransportHandler.ReadAck(ann))
      sender.expectMsg(GossipDecision.Accepted(ann))
    }
    awaitCond({
      sender.send(nodes("D").router, Symbol("channels"))
      sender.expectMsgType[Iterable[ChannelAnnouncement]](5 seconds).size == channels.size + 7 // 7 original channels (A -> B is private)
    }, max = 120 seconds, interval = 1 second)
  }

  /** Handy way to check what the channel balances are before adding new tests. */
  def debugChannelBalances(): Unit = {
    val sender = TestProbe()
    sender.send(nodes("B").relayer, Relayer.GetOutgoingChannels())
    sender.send(nodes("C").relayer, Relayer.GetOutgoingChannels())

    logger.info(s"A -> ${nodes("A").nodeParams.nodeId}")
    logger.info(s"B -> ${nodes("B").nodeParams.nodeId}")
    logger.info(s"C -> ${nodes("C").nodeParams.nodeId}")
    logger.info(s"D -> ${nodes("D").nodeParams.nodeId}")
    logger.info(s"E -> ${nodes("E").nodeParams.nodeId}")
    logger.info(s"F1 -> ${nodes("F1").nodeParams.nodeId}")
    logger.info(s"F2 -> ${nodes("F2").nodeParams.nodeId}")
    logger.info(s"G -> ${nodes("G").nodeParams.nodeId}")

    val channels1 = sender.expectMsgType[Relayer.OutgoingChannels]
    val channels2 = sender.expectMsgType[Relayer.OutgoingChannels]

    logger.info(channels1.channels.map(_.toUsableBalance))
    logger.info(channels2.channels.map(_.toUsableBalance))
  }

}
