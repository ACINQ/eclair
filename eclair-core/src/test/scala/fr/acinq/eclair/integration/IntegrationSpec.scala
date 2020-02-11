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

import java.io.{File, PrintWriter}
import java.util.{Properties, UUID}

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestKit, TestProbe}
import com.google.common.net.HostAndPort
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Base58, Base58Check, Bech32, Block, ByteVector32, Crypto, OP_0, OP_CHECKSIG, OP_DUP, OP_EQUAL, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, Satoshi, Script, ScriptFlags, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.{Watch, WatchConfirmed}
import fr.acinq.eclair.channel.Channel.{BroadcastChannelUpdate, PeriodicRefresh}
import fr.acinq.eclair.channel.Register.{Forward, ForwardShortId}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx.DecryptedFailurePacket
import fr.acinq.eclair.db._
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.io.Peer.{Disconnect, PeerRoutingMessage}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceivePayment
import fr.acinq.eclair.payment.receive.{ForwardHandler, PaymentHandler}
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.payment.relay.Relayer.{GetOutgoingChannels, OutgoingChannels}
import fr.acinq.eclair.payment.send.PaymentInitiator.{SendPaymentRequest, SendTrampolinePaymentRequest}
import fr.acinq.eclair.payment.send.PaymentLifecycle.{State => _}
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.eclair.router.Router.ROUTE_MAX_LENGTH
import fr.acinq.eclair.router.{Announcements, AnnouncementsBatchValidationSpec, PublicChannel, RouteParams}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.{HtlcSuccessTx, HtlcTimeoutTx}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiryDelta, Kit, LongToBtcAmount, MilliSatoshi, Setup, ShortChannelId, randomBytes32}
import grizzled.slf4j.Logging
import org.json4s.DefaultFormats
import org.json4s.JsonAST.{JString, JValue}
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}
import scodec.bits.ByteVector

import scala.collection.JavaConversions._
import scala.compat.Platform
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
 * Created by PM on 15/03/2017.
 */

class IntegrationSpec extends TestKit(ActorSystem("test")) with BitcoindService with FunSuiteLike with BeforeAndAfterAll with Logging {

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
    ))
  ))

  val commonConfig = ConfigFactory.parseMap(Map(
    "eclair.chain" -> "regtest",
    "eclair.server.public-ips.1" -> "127.0.0.1",
    "eclair.bitcoind.port" -> bitcoindPort,
    "eclair.bitcoind.rpcport" -> bitcoindRpcPort,
    "eclair.mindepth-blocks" -> 2,
    "eclair.max-htlc-value-in-flight-msat" -> 100000000000L,
    "eclair.router.broadcast-interval" -> "2 second",
    "eclair.auto-reconnect" -> false,
    "eclair.to-remote-delay-blocks" -> 144,
    "eclair.multi-part-payment-expiry" -> "20 seconds"))

  implicit val formats = DefaultFormats

  override def beforeAll(): Unit = {
    startBitcoind()
  }

  override def afterAll(): Unit = {
    // gracefully stopping bitcoin will make it store its state cleanly to disk, which is good for later debugging
    logger.info(s"stopping bitcoind")
    stopBitcoind()
    nodes.foreach {
      case (name, setup) =>
        logger.info(s"stopping node $name")
        setup.system.terminate()
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
    new PrintWriter(new File(datadir, "eclair.conf")) {
      write(config.root().render())
      close()
    }
    implicit val system = ActorSystem(s"system-$name")
    val setup = new Setup(datadir)
    val kit = Await.result(setup.bootstrap, 10 seconds)
    nodes = nodes + (name -> kit)
  }

  def javaProps(props: Seq[(String, String)]) = {
    val properties = new Properties()
    props.foreach(p => properties.setProperty(p._1, p._2))
    properties
  }

  test("starting eclair nodes") {
    import collection.JavaConversions._
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.expiry-delta-blocks" -> 130, "eclair.server.port" -> 29730, "eclair.api.port" -> 28080, "eclair.features" -> "028a8a", "eclair.channel-flags" -> 0)).withFallback(commonConfig)) // A's channels are private
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.expiry-delta-blocks" -> 131, "eclair.server.port" -> 29731, "eclair.api.port" -> 28081, "eclair.features" -> "028a8a", "eclair.trampoline-payments-enable" -> true)).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.expiry-delta-blocks" -> 132, "eclair.server.port" -> 29732, "eclair.api.port" -> 28082, "eclair.features" -> "028a8a", "eclair.trampoline-payments-enable" -> true, "eclair.max-payment-attempts" -> 15)).withFallback(commonConfig))
    instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.node-alias" -> "D", "eclair.expiry-delta-blocks" -> 133, "eclair.server.port" -> 29733, "eclair.api.port" -> 28083, "eclair.features" -> "028a8a", "eclair.trampoline-payments-enable" -> true)).withFallback(commonConfig))
    instantiateEclairNode("E", ConfigFactory.parseMap(Map("eclair.node-alias" -> "E", "eclair.expiry-delta-blocks" -> 134, "eclair.server.port" -> 29734, "eclair.api.port" -> 28084)).withFallback(commonConfig))
    instantiateEclairNode("F1", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F1", "eclair.expiry-delta-blocks" -> 135, "eclair.server.port" -> 29735, "eclair.api.port" -> 28085)).withFallback(commonConfig))
    instantiateEclairNode("F2", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F2", "eclair.expiry-delta-blocks" -> 136, "eclair.server.port" -> 29736, "eclair.api.port" -> 28086)).withFallback(commonConfig))
    instantiateEclairNode("F3", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F3", "eclair.expiry-delta-blocks" -> 137, "eclair.server.port" -> 29737, "eclair.api.port" -> 28087, "eclair.features" -> "028a8a", "eclair.trampoline-payments-enable" -> true)).withFallback(commonConfig))
    instantiateEclairNode("F4", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F4", "eclair.expiry-delta-blocks" -> 138, "eclair.server.port" -> 29738, "eclair.api.port" -> 28088)).withFallback(commonConfig))
    instantiateEclairNode("F5", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F5", "eclair.expiry-delta-blocks" -> 139, "eclair.server.port" -> 29739, "eclair.api.port" -> 28089)).withFallback(commonConfig))
    instantiateEclairNode("G", ConfigFactory.parseMap(Map("eclair.node-alias" -> "G", "eclair.expiry-delta-blocks" -> 140, "eclair.server.port" -> 29740, "eclair.api.port" -> 28090, "eclair.fee-base-msat" -> 1010, "eclair.fee-proportional-millionths" -> 102, "eclair.trampoline-payments-enable" -> true)).withFallback(commonConfig))

    // by default C has a normal payment handler, but this can be overriden in tests
    val paymentHandlerC = nodes("C").system.actorOf(PaymentHandler.props(nodes("C").nodeParams, nodes("C").commandBuffer))
    nodes("C").paymentHandler ! paymentHandlerC
  }

  def connect(node1: Kit, node2: Kit, fundingSatoshis: Satoshi, pushMsat: MilliSatoshi) = {
    val sender = TestProbe()
    val address = node2.nodeParams.publicAddresses.head
    sender.send(node1.switchboard, Peer.Connect(
      nodeId = node2.nodeParams.nodeId,
      address_opt = Some(HostAndPort.fromParts(address.socketAddress.getHostString, address.socketAddress.getPort))
    ))
    sender.expectMsgAnyOf(10 seconds, "connected", "already connected")
    sender.send(node1.switchboard, Peer.OpenChannel(
      remoteNodeId = node2.nodeParams.nodeId,
      fundingSatoshis = fundingSatoshis,
      pushMsat = pushMsat,
      fundingTxFeeratePerKw_opt = None,
      channelFlags = None,
      timeout_opt = None))
    assert(sender.expectMsgType[String](10 seconds).startsWith("created channel"))
  }

  test("connect nodes") {
    //       ,--G--,
    //      /       \
    // A---B ------- C ==== D
    //      \       / \
    //       '--E--'   F{1,2,3,4,5}

    val sender = TestProbe()
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    connect(nodes("A"), nodes("B"), 11000000 sat, 0 msat)
    connect(nodes("B"), nodes("C"), 2000000 sat, 0 msat)
    connect(nodes("C"), nodes("D"), 5000000 sat, 0 msat)
    connect(nodes("C"), nodes("D"), 5000000 sat, 0 msat)
    connect(nodes("B"), nodes("E"), 10000000 sat, 0 msat)
    connect(nodes("E"), nodes("C"), 10000000 sat, 0 msat)
    connect(nodes("C"), nodes("F1"), 5000000 sat, 0 msat)
    connect(nodes("C"), nodes("F2"), 5000000 sat, 0 msat)
    connect(nodes("C"), nodes("F3"), 5000000 sat, 0 msat)
    connect(nodes("C"), nodes("F4"), 5000000 sat, 0 msat)
    connect(nodes("C"), nodes("F5"), 5000000 sat, 0 msat)
    connect(nodes("B"), nodes("G"), 16000000 sat, 0 msat)
    connect(nodes("G"), nodes("C"), 16000000 sat, 0 msat)

    val numberOfChannels = 13
    val channelEndpointsCount = 2 * numberOfChannels

    // we make sure all channels have set up their WatchConfirmed for the funding tx
    awaitCond({
      val watches = nodes.values.foldLeft(Set.empty[Watch]) {
        case (watches, setup) =>
          sender.send(setup.watcher, 'watches)
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
          sender.send(setup.router, 'nodes)
          sender.expectMsgType[Iterable[NodeAnnouncement]](20 seconds).size == nodes
        }, max = 60 seconds, interval = 1 second)
        awaitCond({
          sender.send(setup.router, 'channels)
          sender.expectMsgType[Iterable[ChannelAnnouncement]](20 seconds).size == channels
        }, max = 60 seconds, interval = 1 second)
        awaitCond({
          sender.send(setup.router, 'updates)
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
    awaitAnnouncements(nodes.filterKeys(key => List("A", "B").contains(key)), 10, 12, 26)
    awaitAnnouncements(nodes.filterKeys(key => !List("A", "B").contains(key)), 10, 12, 24)
  }

  test("send an HTLC A->D") {
    val sender = TestProbe()
    val amountMsat = 4200000.msat
    // first we retrieve a payment hash from D
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, SendPaymentRequest(amountMsat, pr.paymentHash, nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID](5 seconds)
    val ps = sender.expectMsgType[PaymentSent](5 seconds)
    assert(ps.id == paymentId)
  }

  test("send an HTLC A->D with an invalid expiry delta for B") {
    val sender = TestProbe()
    // to simulate this, we will update B's relay params
    // first we find out the short channel id for channel B-C
    sender.send(nodes("B").router, 'channels)
    val shortIdBC = sender.expectMsgType[Iterable[ChannelAnnouncement]].find(c => Set(c.nodeId1, c.nodeId2) == Set(nodes("B").nodeParams.nodeId, nodes("C").nodeParams.nodeId)).get.shortChannelId
    // we also need the full commitment
    sender.send(nodes("B").register, ForwardShortId(shortIdBC, CMD_GETINFO))
    val commitmentBC = sender.expectMsgType[RES_GETINFO].data.asInstanceOf[DATA_NORMAL].commitments
    // we then forge a new channel_update for B-C...
    val channelUpdateBC = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, nodes("B").nodeParams.privateKey, nodes("C").nodeParams.nodeId, shortIdBC, nodes("B").nodeParams.expiryDeltaBlocks + 1, nodes("C").nodeParams.htlcMinimum, nodes("B").nodeParams.feeBase, nodes("B").nodeParams.feeProportionalMillionth, 500000000 msat)
    // ...and notify B's relayer
    sender.send(nodes("B").relayer, LocalChannelUpdate(system.deadLetters, commitmentBC.channelId, shortIdBC, commitmentBC.remoteParams.nodeId, None, channelUpdateBC, commitmentBC))
    // we retrieve a payment hash from D
    val amountMsat = 4200000.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the actual payment, do not randomize the route to make sure we route through node B
    val sendReq = SendPaymentRequest(amountMsat, pr.paymentHash, nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // A will receive an error from B that include the updated channel update, then will retry the payment
    val paymentId = sender.expectMsgType[UUID](5 seconds)
    val ps = sender.expectMsgType[PaymentSent](5 seconds)
    assert(ps.id == paymentId)

    def updateFor(n: PublicKey, pc: PublicChannel): Option[ChannelUpdate] = if (n == pc.ann.nodeId1) pc.update_1_opt else if (n == pc.ann.nodeId2) pc.update_2_opt else throw new IllegalArgumentException("this node is unrelated to this channel")

    awaitCond({
      // in the meantime, the router will have updated its state
      sender.send(nodes("A").router, 'channelsMap)
      // we then put everything back like before by asking B to refresh its channel update (this will override the one we created)
      val u_opt = updateFor(nodes("B").nodeParams.nodeId, sender.expectMsgType[Map[ShortChannelId, PublicChannel]](10 seconds).apply(channelUpdateBC.shortChannelId))
      u_opt.contains(channelUpdateBC)
    }, max = 30 seconds, interval = 1 seconds)

    // first let's wait 3 seconds to make sure the timestamp of the new channel_update will be strictly greater than the former
    sender.expectNoMsg(3 seconds)
    sender.send(nodes("B").register, ForwardShortId(shortIdBC, BroadcastChannelUpdate(PeriodicRefresh)))
    sender.send(nodes("B").register, ForwardShortId(shortIdBC, CMD_GETINFO))
    val channelUpdateBC_new = sender.expectMsgType[RES_GETINFO].data.asInstanceOf[DATA_NORMAL].channelUpdate
    logger.info(s"channelUpdateBC=$channelUpdateBC")
    logger.info(s"channelUpdateBC_new=$channelUpdateBC_new")
    assert(channelUpdateBC_new.timestamp > channelUpdateBC.timestamp)
    assert(channelUpdateBC_new.cltvExpiryDelta == nodes("B").nodeParams.expiryDeltaBlocks)
    awaitCond({
      sender.send(nodes("A").router, 'channelsMap)
      val u = updateFor(nodes("B").nodeParams.nodeId, sender.expectMsgType[Map[ShortChannelId, PublicChannel]](10 seconds).apply(channelUpdateBC.shortChannelId)).get
      u.cltvExpiryDelta == nodes("B").nodeParams.expiryDeltaBlocks
    }, max = 30 seconds, interval = 1 second)
  }

  test("send an HTLC A->D with an amount greater than capacity of B-C") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D
    val amountMsat = 300000000.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the payment (B-C has a smaller capacity than A-B and C-D)
    val sendReq = SendPaymentRequest(amountMsat, pr.paymentHash, nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams, maxAttempts = 5)
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
    val sendReq = SendPaymentRequest(100000000 msat, pr.paymentHash, nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams, maxAttempts = 5)
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
    val sendReq = SendPaymentRequest(600000000 msat, pr.paymentHash, nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams, maxAttempts = 5)
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
    val sendReq = SendPaymentRequest(300000000 msat, pr.paymentHash, nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams, maxAttempts = 5)
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

      val sendReq = SendPaymentRequest(amountMsat, pr.paymentHash, nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams, maxAttempts = 5)
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
    sender.send(nodes("A").paymentInitiator, SendPaymentRequest(amountMsat, pr.paymentHash, nodes("C").nodeParams.nodeId, maxAttempts = 1, routeParams = integrationTestRouteParams.map(_.copy(ratios = Some(WeightRatios(0, 0, 1))))))

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
    val start = Platform.currentTime
    val sender = TestProbe()
    val amount = 1000000000L.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amount), "split the restaurant bill"))
    val pr = sender.expectMsgType[PaymentRequest](15 seconds)
    assert(pr.features.allowMultiPart)

    sender.send(nodes("B").paymentInitiator, SendPaymentRequest(amount, pr.paymentHash, nodes("D").nodeParams.nodeId, 5, paymentRequest = Some(pr)))
    val paymentId = sender.expectMsgType[UUID](30 seconds)
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

    awaitCond(nodes("B").nodeParams.db.audit.listSent(start, Platform.currentTime).nonEmpty)
    val sent = nodes("B").nodeParams.db.audit.listSent(start, Platform.currentTime)
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

    sender.send(nodes("B").relayer, GetOutgoingChannels())
    val canSend = sender.expectMsgType[OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    assert(canSend > amount)

    sender.send(nodes("B").paymentInitiator, SendPaymentRequest(amount, pr.paymentHash, nodes("D").nodeParams.nodeId, 1, paymentRequest = Some(pr)))
    val paymentId = sender.expectMsgType[UUID](30 seconds)
    val paymentFailed = sender.expectMsgType[PaymentFailed](45 seconds)
    assert(paymentFailed.id === paymentId, paymentFailed)
    assert(paymentFailed.paymentHash === pr.paymentHash, paymentFailed)
    assert(paymentFailed.failures.length > 1, paymentFailed)

    assert(nodes("D").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)

    sender.send(nodes("B").relayer, GetOutgoingChannels())
    val canSend2 = sender.expectMsgType[OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
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

    sender.send(nodes("D").relayer, GetOutgoingChannels())
    val canSend = sender.expectMsgType[OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    assert(canSend < amount)

    sender.send(nodes("D").paymentInitiator, SendPaymentRequest(amount, pr.paymentHash, nodes("C").nodeParams.nodeId, 1, paymentRequest = Some(pr)))
    val paymentId = sender.expectMsgType[UUID](30 seconds)
    val paymentFailed = sender.expectMsgType[PaymentFailed](30 seconds)
    assert(paymentFailed.id === paymentId, paymentFailed)
    assert(paymentFailed.paymentHash === pr.paymentHash, paymentFailed)

    val incoming = nodes("C").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(incoming.get.status === IncomingPaymentStatus.Pending, incoming)

    sender.send(nodes("D").relayer, GetOutgoingChannels())
    val canSend2 = sender.expectMsgType[OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    // Fee updates may impact balances, but it shouldn't have changed much.
    assert(math.abs((canSend - canSend2).toLong) < 50000000)
  }

  test("send a trampoline payment B->F3 with retry (via trampoline G)") {
    val start = Platform.currentTime
    val sender = TestProbe()
    val amount = 4000000000L.msat
    sender.send(nodes("F3").paymentHandler, ReceivePayment(Some(amount), "like trampoline much?"))
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
    assert(paymentSent.recipientNodeId === nodes("F3").nodeParams.nodeId, paymentSent)
    assert(paymentSent.recipientAmount === amount, paymentSent)
    assert(paymentSent.feesPaid === 1000000.msat, paymentSent)
    assert(paymentSent.nonTrampolineFees === 0.msat, paymentSent)

    awaitCond(nodes("F3").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("F3").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(receivedAmount === amount)

    awaitCond(nodes("G").nodeParams.db.audit.listRelayed(start, Platform.currentTime).exists(_.paymentHash == pr.paymentHash))
    val relayed = nodes("G").nodeParams.db.audit.listRelayed(start, Platform.currentTime).filter(_.paymentHash == pr.paymentHash).head
    assert(relayed.amountIn - relayed.amountOut > 0.msat, relayed)
    assert(relayed.amountIn - relayed.amountOut < 1000000.msat, relayed)

    val outgoingSuccess = nodes("B").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(p => p.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    outgoingSuccess.foreach { case p@OutgoingPayment(_, _, _, _, _, _, _, recipientNodeId, _, _, OutgoingPaymentStatus.Succeeded(_, _, route, _)) =>
      assert(recipientNodeId === nodes("F3").nodeParams.nodeId, p)
      assert(route.lastOption === Some(HopSummary(nodes("G").nodeParams.nodeId, nodes("F3").nodeParams.nodeId)), p)
    }
    assert(outgoingSuccess.map(_.amount).sum === amount + 1000000.msat, outgoingSuccess)
  }

  test("send a trampoline payment D->B (via trampoline C)") {
    val start = Platform.currentTime
    val sender = TestProbe()
    val amount = 2500000000L.msat
    sender.send(nodes("B").paymentHandler, ReceivePayment(Some(amount), "trampoline-MPP is so #reckless"))
    val pr = sender.expectMsgType[PaymentRequest](15 seconds)
    assert(pr.features.allowMultiPart)
    assert(pr.features.allowTrampoline)

    val payment = SendTrampolinePaymentRequest(amount, pr, nodes("C").nodeParams.nodeId, Seq((300000 msat, CltvExpiryDelta(144))))
    sender.send(nodes("D").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID](30 seconds)
    val paymentSent = sender.expectMsgType[PaymentSent](30 seconds)
    assert(paymentSent.id === paymentId, paymentSent)
    assert(paymentSent.paymentHash === pr.paymentHash, paymentSent)
    assert(paymentSent.recipientAmount === amount, paymentSent)
    assert(paymentSent.feesPaid === 300000.msat, paymentSent)
    assert(paymentSent.nonTrampolineFees === 0.msat, paymentSent)

    awaitCond(nodes("B").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("B").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(receivedAmount === amount)

    awaitCond(nodes("C").nodeParams.db.audit.listRelayed(start, Platform.currentTime).exists(_.paymentHash == pr.paymentHash))
    val relayed = nodes("C").nodeParams.db.audit.listRelayed(start, Platform.currentTime).filter(_.paymentHash == pr.paymentHash).head
    assert(relayed.amountIn - relayed.amountOut > 0.msat, relayed)
    assert(relayed.amountIn - relayed.amountOut < 300000.msat, relayed)

    val outgoingSuccess = nodes("D").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(p => p.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    outgoingSuccess.foreach { case p@OutgoingPayment(_, _, _, _, _, _, _, recipientNodeId, _, _, OutgoingPaymentStatus.Succeeded(_, _, route, _)) =>
      assert(recipientNodeId === nodes("B").nodeParams.nodeId, p)
      assert(route.lastOption === Some(HopSummary(nodes("C").nodeParams.nodeId, nodes("B").nodeParams.nodeId)), p)
    }
    assert(outgoingSuccess.map(_.amount).sum === amount + 300000.msat, outgoingSuccess)

    awaitCond(nodes("D").nodeParams.db.audit.listSent(start, Platform.currentTime).nonEmpty)
    val sent = nodes("D").nodeParams.db.audit.listSent(start, Platform.currentTime)
    assert(sent.length === 1, sent)
    assert(sent.head.copy(parts = sent.head.parts.sortBy(_.timestamp)) === paymentSent.copy(parts = paymentSent.parts.map(_.copy(route = None)).sortBy(_.timestamp)), sent)
  }

  test("send a trampoline payment F3->A (via trampoline C, non-trampoline recipient)") {
    // The A -> B channel is not announced.
    val start = Platform.currentTime
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
    sender.send(nodes("F3").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID](30 seconds)
    val paymentSent = sender.expectMsgType[PaymentSent](30 seconds)
    assert(paymentSent.id === paymentId, paymentSent)
    assert(paymentSent.paymentHash === pr.paymentHash, paymentSent)
    assert(paymentSent.recipientAmount === amount, paymentSent)
    assert(paymentSent.trampolineFees === 1000000.msat, paymentSent)

    awaitCond(nodes("A").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("A").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(receivedAmount === amount)

    awaitCond(nodes("C").nodeParams.db.audit.listRelayed(start, Platform.currentTime).exists(_.paymentHash == pr.paymentHash))
    val relayed = nodes("C").nodeParams.db.audit.listRelayed(start, Platform.currentTime).filter(_.paymentHash == pr.paymentHash).head
    assert(relayed.amountIn - relayed.amountOut > 0.msat, relayed)
    assert(relayed.amountIn - relayed.amountOut < 1000000.msat, relayed)

    val outgoingSuccess = nodes("F3").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(p => p.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    outgoingSuccess.foreach { case p@OutgoingPayment(_, _, _, _, _, _, _, recipientNodeId, _, _, OutgoingPaymentStatus.Succeeded(_, _, route, _)) =>
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
    sender.expectMsgType[PaymentSent](30 seconds)

    // Now we try to send more than C's outgoing capacity to D.
    val amount = 2000000000L.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amount), "I iz Satoshi"))
    val pr = sender.expectMsgType[PaymentRequest](15 seconds)
    assert(pr.features.allowMultiPart)
    assert(pr.features.allowTrampoline)

    val payment = SendTrampolinePaymentRequest(amount, pr, nodes("C").nodeParams.nodeId, Seq((250000 msat, CltvExpiryDelta(144))))
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

  /**
   * We currently use p2pkh script Helpers.getFinalScriptPubKey
   */
  def scriptPubKeyToAddress(scriptPubKey: ByteVector) = Script.parse(scriptPubKey) match {
    case OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pubKeyHash, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil =>
      Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, pubKeyHash)
    case OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil =>
      Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, scriptHash)
    case OP_0 :: OP_PUSHDATA(pubKeyHash, _) :: Nil if pubKeyHash.length == 20 => Bech32.encodeWitnessAddress("bcrt", 0, pubKeyHash)
    case OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil if scriptHash.length == 32 => Bech32.encodeWitnessAddress("bcrt", 0, scriptHash)
    case _ => ???
  }

  test("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (local commit)") {
    val sender = TestProbe()
    // we subscribe to C's channel state transitions
    val stateListener = TestProbe()
    nodes("C").system.eventStream.subscribe(stateListener.ref, classOf[ChannelStateChanged])
    // first we make sure we are in sync with current blockchain height
    val currentBlockCount = getBlockCount
    awaitCond(getBlockCount == currentBlockCount, max = 20 seconds, interval = 1 second)
    // we use this to control when to fulfill htlcs
    val htlcReceiver = TestProbe()
    nodes("F1").paymentHandler ! new ForwardHandler(htlcReceiver.ref)
    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPaymentRequest(100000000 msat, paymentHash, nodes("F1").nodeParams.nodeId, maxAttempts = 3, routeParams = integrationTestRouteParams)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    paymentSender.expectMsgType[UUID](30 seconds)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[IncomingPacket.FinalPacket].add
    // now that we have the channel id, we retrieve channels default final addresses
    sender.send(nodes("C").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalAddressC = scriptPubKeyToAddress(sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
    sender.send(nodes("F1").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalAddressF = scriptPubKeyToAddress(sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
    // we also retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    val previouslyReceivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
    // we then kill the connection between C and F
    sender.send(nodes("F1").switchboard, 'peers)
    val peers = sender.expectMsgType[Iterable[ActorRef]]
    // F's only node is C
    peers.head ! Peer.Disconnect(nodes("C").nodeParams.nodeId)
    // we then wait for F to be in disconnected state
    awaitCond({
      sender.send(nodes("F1").register, Forward(htlc.channelId, CMD_GETSTATE))
      sender.expectMsgType[State] == OFFLINE
    }, max = 20 seconds, interval = 1 second)
    // we then have C unilateral close the channel (which will make F redeem the htlc onchain)
    sender.send(nodes("C").register, Forward(htlc.channelId, CMD_FORCECLOSE))
    sender.expectMsg("ok")
    // we then wait for F to detect the unilateral close and go to CLOSING state
    awaitCond({
      sender.send(nodes("F1").register, Forward(htlc.channelId, CMD_GETSTATE))
      sender.expectMsgType[State] == CLOSING
    }, max = 20 seconds, interval = 1 second)
    // we then fulfill the htlc, which will make F redeem it on-chain
    sender.send(nodes("F1").register, Forward(htlc.channelId, CMD_FULFILL_HTLC(htlc.id, preimage)))
    // we then generate one block so that the htlc success tx gets written to the blockchain
    generateBlocks(bitcoincli, 1)
    // C will extract the preimage from the blockchain and fulfill the payment upstream
    paymentSender.expectMsgType[PaymentSent](30 seconds)
    // at this point F should have 1 recv transactions: the redeemed htlc
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      res.filter(_ \ "address" == JString(finalAddressF)).flatMap(_ \ "txids" \\ classOf[JString]).size == 1
    }, max = 30 seconds, interval = 1 second)
    // we then generate enough blocks so that C gets its main delayed output
    generateBlocks(bitcoincli, 145)
    // and C will have its main output
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 1
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make tx confirm
    generateBlocks(bitcoincli, 2)
    // and we wait for C'channel to close
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSED, max = 30 seconds)
    awaitAnnouncements(nodes.filterKeys(_ == "A"), 9, 11, 24)
  }

  def getBlockCount: Long = {
    // we make sure that all nodes have the same value
    awaitCond(nodes.values.map(_.nodeParams.currentBlockHeight).toSet.size == 1, max = 1 minute, interval = 1 second)
    // and we return it (NB: it could be a different value at this point
    nodes.values.head.nodeParams.currentBlockHeight
  }

  test("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (remote commit)") {
    val sender = TestProbe()
    // we subscribe to C's channel state transitions
    val stateListener = TestProbe()
    nodes("C").system.eventStream.subscribe(stateListener.ref, classOf[ChannelStateChanged])
    // first we make sure we are in sync with current blockchain height
    val currentBlockCount = getBlockCount
    awaitCond(getBlockCount == currentBlockCount, max = 20 seconds, interval = 1 second)
    // we use this to control when to fulfill htlcs
    val htlcReceiver = TestProbe()
    nodes("F2").paymentHandler ! new ForwardHandler(htlcReceiver.ref)
    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPaymentRequest(100000000 msat, paymentHash, nodes("F2").nodeParams.nodeId, maxAttempts = 3, routeParams = integrationTestRouteParams)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    paymentSender.expectMsgType[UUID](30 seconds)

    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[IncomingPacket.FinalPacket].add
    // now that we have the channel id, we retrieve channels default final addresses
    sender.send(nodes("C").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalAddressC = scriptPubKeyToAddress(sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
    sender.send(nodes("F2").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalAddressF = scriptPubKeyToAddress(sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
    // we also retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    val previouslyReceivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
    // we then kill the connection between C and F
    sender.send(nodes("F2").switchboard, 'peers)
    val peers = sender.expectMsgType[Iterable[ActorRef]]
    // F's only node is C
    peers.head ! Disconnect(nodes("C").nodeParams.nodeId)
    // we then wait for F to be in disconnected state
    awaitCond({
      sender.send(nodes("F2").register, Forward(htlc.channelId, CMD_GETSTATE))
      sender.expectMsgType[State] == OFFLINE
    }, max = 20 seconds, interval = 1 second)
    // then we have F unilateral close the channel
    sender.send(nodes("F2").register, Forward(htlc.channelId, CMD_FORCECLOSE))
    sender.expectMsg("ok")
    // we then fulfill the htlc (it won't be sent to C, and will be used to pull funds on-chain)
    sender.send(nodes("F2").register, Forward(htlc.channelId, CMD_FULFILL_HTLC(htlc.id, preimage)))
    // we then generate one block so that the htlc success tx gets written to the blockchain
    sender.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = sender.expectMsgType[JValue]
    generateBlocks(bitcoincli, 1, Some(address))
    // C will extract the preimage from the blockchain and fulfill the payment upstream
    paymentSender.expectMsgType[PaymentSent](30 seconds)
    // at this point F should have 1 recv transactions: the redeemed htlc
    // we then generate enough blocks so that F gets its htlc-success delayed output
    generateBlocks(bitcoincli, 145, Some(address))
    // at this point F should have 1 recv transactions: the redeemed htlc
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      res.filter(_ \ "address" == JString(finalAddressF)).flatMap(_ \ "txids" \\ classOf[JString]).size == 1
    }, max = 30 seconds, interval = 1 second)
    // and C will have its main output
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 1
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make tx confirm
    generateBlocks(bitcoincli, 2, Some(address))
    // and we wait for C'channel to close
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSED, max = 30 seconds)
    awaitAnnouncements(nodes.filterKeys(_ == "A"), 8, 10, 22)
  }

  test("propagate a failure upstream when a downstream htlc times out (local commit)") {
    val sender = TestProbe()
    // we subscribe to C's channel state transitions
    val stateListener = TestProbe()
    nodes("C").system.eventStream.subscribe(stateListener.ref, classOf[ChannelStateChanged])
    // first we make sure we are in sync with current blockchain height
    val currentBlockCount = getBlockCount
    awaitCond(getBlockCount == currentBlockCount, max = 20 seconds, interval = 1 second)
    // we use this to control when to fulfill htlcs
    val htlcReceiver = TestProbe()
    nodes("F3").paymentHandler ! new ForwardHandler(htlcReceiver.ref)
    val preimage: ByteVector = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPaymentRequest(100000000 msat, paymentHash, nodes("F3").nodeParams.nodeId, maxAttempts = 3, routeParams = integrationTestRouteParams)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    val paymentId = paymentSender.expectMsgType[UUID]
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[IncomingPacket.FinalPacket].add
    // now that we have the channel id, we retrieve channels default final addresses
    sender.send(nodes("C").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalAddressC = scriptPubKeyToAddress(sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
    // we also retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    val previouslyReceivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
    // we then generate enough blocks to make the htlc timeout
    sender.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = sender.expectMsgType[JValue]
    generateBlocks(bitcoincli, 11, Some(address))
    // we generate more blocks for the htlc-timeout to reach enough confirmations
    awaitCond({
      generateBlocks(bitcoincli, 1, Some(address))
      paymentSender.msgAvailable
    }, max = 30 seconds, interval = 1 second)
    // this will fail the htlc
    val failed = paymentSender.expectMsgType[PaymentFailed](30 seconds)
    assert(failed.id == paymentId)
    assert(failed.paymentHash === paymentHash)
    assert(failed.failures.size > 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === DecryptedFailurePacket(nodes("C").nodeParams.nodeId, PermanentChannelFailure))
    // we then generate enough blocks to confirm all delayed transactions
    generateBlocks(bitcoincli, 150, Some(address))
    // at this point C should have 2 recv transactions: its main output and the htlc timeout
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 2
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make tx confirm
    generateBlocks(bitcoincli, 2, Some(address))
    // and we wait for C'channel to close
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSED, max = 30 seconds)
    awaitAnnouncements(nodes.filterKeys(_ == "A"), 7, 9, 20)
  }

  test("propagate a failure upstream when a downstream htlc times out (remote commit)") {
    val sender = TestProbe()
    // we subscribe to C's channel state transitions
    val stateListener = TestProbe()
    nodes("C").system.eventStream.subscribe(stateListener.ref, classOf[ChannelStateChanged])
    // first we make sure we are in sync with current blockchain height
    val currentBlockCount = getBlockCount
    awaitCond(getBlockCount == currentBlockCount, max = 20 seconds, interval = 1 second)
    // we use this to control when to fulfill htlcs
    val htlcReceiver = TestProbe()
    nodes("F4").paymentHandler ! new ForwardHandler(htlcReceiver.ref)
    val preimage: ByteVector = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPaymentRequest(100000000 msat, paymentHash, nodes("F4").nodeParams.nodeId, maxAttempts = 3, routeParams = integrationTestRouteParams)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    val paymentId = paymentSender.expectMsgType[UUID](30 seconds)

    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[IncomingPacket.FinalPacket].add
    // now that we have the channel id, we retrieve channels default final addresses
    sender.send(nodes("C").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalAddressC = scriptPubKeyToAddress(sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
    // we also retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    val previouslyReceivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
    // then we ask F to unilaterally close the channel
    sender.send(nodes("F4").register, Forward(htlc.channelId, CMD_FORCECLOSE))
    sender.expectMsg("ok")
    // we then generate enough blocks to make the htlc timeout
    sender.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = sender.expectMsgType[JValue]
    generateBlocks(bitcoincli, 11, Some(address))
    // we generate more blocks for the claim-htlc-timeout to reach enough confirmations
    awaitCond({
      generateBlocks(bitcoincli, 1, Some(address))
      paymentSender.msgAvailable
    }, max = 30 seconds, interval = 1 second)
    // this will fail the htlc
    val failed = paymentSender.expectMsgType[PaymentFailed](30 seconds)
    assert(failed.id == paymentId)
    assert(failed.paymentHash === paymentHash)
    assert(failed.failures.size > 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === DecryptedFailurePacket(nodes("C").nodeParams.nodeId, PermanentChannelFailure))
    // we then generate enough blocks to confirm all delayed transactions
    generateBlocks(bitcoincli, 145, Some(address))
    // at this point C should have 2 recv transactions: its main output and the htlc timeout
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 2
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make tx confirm
    generateBlocks(bitcoincli, 2, Some(address))
    // and we wait for C'channel to close
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSED, max = 30 seconds)
    awaitAnnouncements(nodes.filterKeys(_ == "A"), 6, 8, 18)
  }

  test("punish a node that has published a revoked commit tx") {
    val sender = TestProbe()
    // we subscribe to C's channel state transitions
    val stateListener = TestProbe()
    nodes("C").system.eventStream.subscribe(stateListener.ref, classOf[ChannelStateChanged])
    // we use this to get commitments
    val sigListener = TestProbe()
    nodes("F5").system.eventStream.subscribe(sigListener.ref, classOf[ChannelSignatureReceived])
    // we use this to control when to fulfill htlcs
    val forwardHandlerC = TestProbe()
    nodes("C").paymentHandler ! new ForwardHandler(forwardHandlerC.ref)
    val forwardHandlerF = TestProbe()
    nodes("F5").paymentHandler ! new ForwardHandler(forwardHandlerF.ref)
    // this is the actual payment handler that we will forward requests to
    val paymentHandlerC = nodes("C").system.actorOf(PaymentHandler.props(nodes("C").nodeParams, nodes("C").commandBuffer))
    val paymentHandlerF = nodes("F5").system.actorOf(PaymentHandler.props(nodes("F5").nodeParams, nodes("F5").commandBuffer))
    // first we make sure we are in sync with current blockchain height
    val currentBlockCount = getBlockCount
    awaitCond(getBlockCount == currentBlockCount, max = 20 seconds, interval = 1 second)
    // first we send 3 mBTC to F so that it has a balance
    val amountMsat = 300000000.msat
    sender.send(paymentHandlerF, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    val sendReq = SendPaymentRequest(300000000 msat, pr.paymentHash, pr.nodeId, routeParams = integrationTestRouteParams, maxAttempts = 3)
    sender.send(nodes("A").paymentInitiator, sendReq)
    val paymentId = sender.expectMsgType[UUID]
    // we forward the htlc to the payment handler
    forwardHandlerF.expectMsgType[IncomingPacket.FinalPacket]
    forwardHandlerF.forward(paymentHandlerF)
    sigListener.expectMsgType[ChannelSignatureReceived]
    sigListener.expectMsgType[ChannelSignatureReceived]
    sender.expectMsgType[PaymentSent].id === paymentId

    // we now send a few htlcs C->F and F->C in order to obtain a commitments with multiple htlcs
    def send(amountMsat: MilliSatoshi, paymentHandler: ActorRef, paymentInitiator: ActorRef) = {
      sender.send(paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
      val pr = sender.expectMsgType[PaymentRequest]
      val sendReq = SendPaymentRequest(amountMsat, pr.paymentHash, pr.nodeId, routeParams = integrationTestRouteParams, maxAttempts = 1)
      sender.send(paymentInitiator, sendReq)
      sender.expectMsgType[UUID]
    }

    val buffer = TestProbe()
    send(100000000 msat, paymentHandlerF, nodes("C").paymentInitiator) // will be left pending
    forwardHandlerF.expectMsgType[IncomingPacket.FinalPacket]
    forwardHandlerF.forward(buffer.ref)
    sigListener.expectMsgType[ChannelSignatureReceived]
    send(110000000 msat, paymentHandlerF, nodes("C").paymentInitiator) // will be left pending
    forwardHandlerF.expectMsgType[IncomingPacket.FinalPacket]
    forwardHandlerF.forward(buffer.ref)
    sigListener.expectMsgType[ChannelSignatureReceived]
    send(120000000 msat, paymentHandlerC, nodes("F5").paymentInitiator)
    forwardHandlerC.expectMsgType[IncomingPacket.FinalPacket]
    forwardHandlerC.forward(buffer.ref)
    sigListener.expectMsgType[ChannelSignatureReceived]
    send(130000000 msat, paymentHandlerC, nodes("F5").paymentInitiator)
    forwardHandlerC.expectMsgType[IncomingPacket.FinalPacket]
    forwardHandlerC.forward(buffer.ref)
    val commitmentsF = sigListener.expectMsgType[ChannelSignatureReceived].commitments
    sigListener.expectNoMsg(1 second)
    // in this commitment, both parties should have a main output, and there are four pending htlcs
    val localCommitF = commitmentsF.localCommit.publishableTxs
    assert(localCommitF.commitTx.tx.txOut.size === 6)
    val htlcTimeoutTxs = localCommitF.htlcTxsAndSigs.collect { case h@HtlcTxAndSigs(_: HtlcTimeoutTx, _, _) => h }
    val htlcSuccessTxs = localCommitF.htlcTxsAndSigs.collect { case h@HtlcTxAndSigs(_: HtlcSuccessTx, _, _) => h }
    assert(htlcTimeoutTxs.size === 2)
    assert(htlcSuccessTxs.size === 2)
    // we fulfill htlcs to get the preimagse
    buffer.expectMsgType[IncomingPacket.FinalPacket]
    buffer.forward(paymentHandlerF)
    sigListener.expectMsgType[ChannelSignatureReceived]
    val preimage1 = sender.expectMsgType[PaymentSent].paymentPreimage
    buffer.expectMsgType[IncomingPacket.FinalPacket]
    buffer.forward(paymentHandlerF)
    sigListener.expectMsgType[ChannelSignatureReceived]
    sender.expectMsgType[PaymentSent].paymentPreimage
    buffer.expectMsgType[IncomingPacket.FinalPacket]
    buffer.forward(paymentHandlerC)
    sigListener.expectMsgType[ChannelSignatureReceived]
    sender.expectMsgType[PaymentSent].paymentPreimage
    buffer.expectMsgType[IncomingPacket.FinalPacket]
    buffer.forward(paymentHandlerC)
    sigListener.expectMsgType[ChannelSignatureReceived]
    sender.expectMsgType[PaymentSent].paymentPreimage
    // this also allows us to get the channel id
    val channelId = commitmentsF.channelId
    // we also retrieve C's default final address
    sender.send(nodes("C").register, Forward(channelId, CMD_GETSTATEDATA))
    val finalAddressC = scriptPubKeyToAddress(sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
    // and we retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    val previouslyReceivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
    // F will publish the commitment above, which is now revoked
    val revokedCommitTx = localCommitF.commitTx.tx
    val htlcSuccess = Transactions.addSigs(htlcSuccessTxs.head.txinfo.asInstanceOf[HtlcSuccessTx], htlcSuccessTxs.head.localSig, htlcSuccessTxs.head.remoteSig, preimage1).tx
    val htlcTimeout = Transactions.addSigs(htlcTimeoutTxs.head.txinfo.asInstanceOf[HtlcTimeoutTx], htlcTimeoutTxs.head.localSig, htlcTimeoutTxs.head.remoteSig).tx
    Transaction.correctlySpends(htlcSuccess, Seq(revokedCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(htlcTimeout, Seq(revokedCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    // we then generate blocks to make the htlc timeout (nothing will happen in the channel because all of them have already been fulfilled)
    generateBlocks(bitcoincli, 20)
    // then we publish F's revoked transactions
    sender.send(bitcoincli, BitcoinReq("sendrawtransaction", revokedCommitTx.toString()))
    sender.expectMsgType[JValue](10000 seconds)
    sender.send(bitcoincli, BitcoinReq("sendrawtransaction", htlcSuccess.toString()))
    sender.expectMsgType[JValue](10000 seconds)
    sender.send(bitcoincli, BitcoinReq("sendrawtransaction", htlcTimeout.toString()))
    sender.expectMsgType[JValue](10000 seconds)
    // at this point C should have 3 recv transactions: its previous main output, and F's main and htlc output (taken as punishment)
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 6
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make tx confirm
    generateBlocks(bitcoincli, 2)
    // and we wait for C'channel to close
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSED, max = 30 seconds)
    // this will remove the channel
    awaitAnnouncements(nodes.filterKeys(_ == "A"), 5, 7, 16)
  }

  test("generate and validate lots of channels") {
    implicit val extendedClient = new ExtendedBitcoinClient(bitcoinrpcclient)
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
    announcements.foreach(ann => nodes("A").router ! PeerRoutingMessage(sender.ref, remoteNodeId, ann))
    awaitCond({
      sender.send(nodes("D").router, 'channels)
      sender.expectMsgType[Iterable[ChannelAnnouncement]](5 seconds).size == channels.size + 7 // 7 remaining channels because  D->F{1-5} have disappeared
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
    logger.info(s"F3 -> ${nodes("F3").nodeParams.nodeId}")
    logger.info(s"F4 -> ${nodes("F4").nodeParams.nodeId}")
    logger.info(s"F5 -> ${nodes("F5").nodeParams.nodeId}")
    logger.info(s"G -> ${nodes("G").nodeParams.nodeId}")

    val channels1 = sender.expectMsgType[Relayer.OutgoingChannels]
    val channels2 = sender.expectMsgType[Relayer.OutgoingChannels]

    logger.info(channels1.channels.map(_.toUsableBalance))
    logger.info(channels2.channels.map(_.toUsableBalance))
  }

}
