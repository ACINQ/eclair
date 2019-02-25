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

package fr.acinq.eclair.integration

import java.io.{File, PrintWriter}
import java.util.Properties
import collection.JavaConversions._
import akka.actor.{ActorRef, ActorSystem, Terminated}
import akka.testkit.{TestKit, TestProbe}
import com.google.common.net.HostAndPort
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Base58, Base58Check, Bech32, BinaryData, Block, Crypto, MilliSatoshi, OP_0, OP_CHECKSIG, OP_DUP, OP_EQUAL, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, Satoshi, Script, ScriptFlags, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.{Watch, WatchConfirmed}
import fr.acinq.eclair.channel.Channel.TickRefreshChannelUpdate
import fr.acinq.eclair.channel.Register.{Forward, ForwardShortId}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx.ErrorPacket
import fr.acinq.eclair.io.Peer.{Disconnect, PeerRoutingMessage}
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.payment.PaymentLifecycle.{State => _, _}
import fr.acinq.eclair.payment.{LocalPaymentHandler, PaymentRequest}
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.eclair.router.Router.ROUTE_MAX_LENGTH
import fr.acinq.eclair.router.{Announcements, AnnouncementsBatchValidationSpec, ChannelDesc, RouteParams}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.{HtlcSuccessTx, HtlcTimeoutTx}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Globals, Kit, Setup}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JValue
import org.json4s.{DefaultFormats, JString}
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

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
    maxFeeBaseMsat = Long.MaxValue,
    maxFeePct = Double.MaxValue,
    routeMaxCltv = Int.MaxValue,
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
    "eclair.bitcoind.port" -> 28333,
    "eclair.bitcoind.rpcport" -> 28332,
    "eclair.bitcoind.zmqblock" -> "tcp://127.0.0.1:28334",
    "eclair.bitcoind.zmqtx" -> "tcp://127.0.0.1:28335",
    "eclair.mindepth-blocks" -> 2,
    "eclair.max-htlc-value-in-flight-msat" -> 100000000000L,
    "eclair.router.broadcast-interval" -> "2 second",
    "eclair.auto-reconnect" -> false,
    "eclair.to-remote-delay-blocks" -> 144))

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
    sender.send(bitcoincli, BitcoinReq("generate", 500))
    sender.expectMsgType[JValue](30 seconds)
  }

  def instantiateEclairNode(name: String, config: Config) = {
    val datadir = new File(INTEGRATION_TMP_DIR, s"datadir-eclair-$name")
    datadir.mkdirs()
    new PrintWriter(new File(datadir, "eclair.conf")) {
      write(config.root().render())
      close
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
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.expiry-delta-blocks" -> 130, "eclair.server.port" -> 29730, "eclair.api.port" -> 28080, "eclair.channel-flags" -> 0)).withFallback(commonConfig)) // A's channels are private
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.expiry-delta-blocks" -> 131, "eclair.server.port" -> 29731, "eclair.api.port" -> 28081)).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.expiry-delta-blocks" -> 132, "eclair.server.port" -> 29732, "eclair.api.port" -> 28082, "eclair.payment-handler" -> "noop")).withFallback(commonConfig))
    instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.node-alias" -> "D", "eclair.expiry-delta-blocks" -> 133, "eclair.server.port" -> 29733, "eclair.api.port" -> 28083)).withFallback(commonConfig))
    instantiateEclairNode("E", ConfigFactory.parseMap(Map("eclair.node-alias" -> "E", "eclair.expiry-delta-blocks" -> 134, "eclair.server.port" -> 29734, "eclair.api.port" -> 28084)).withFallback(commonConfig))
    instantiateEclairNode("F1", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F1", "eclair.expiry-delta-blocks" -> 135, "eclair.server.port" -> 29735, "eclair.api.port" -> 28085, "eclair.payment-handler" -> "noop")).withFallback(commonConfig)) // NB: eclair.payment-handler = noop allows us to manually fulfill htlcs
    instantiateEclairNode("F2", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F2", "eclair.expiry-delta-blocks" -> 136, "eclair.server.port" -> 29736, "eclair.api.port" -> 28086, "eclair.payment-handler" -> "noop")).withFallback(commonConfig))
    instantiateEclairNode("F3", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F3", "eclair.expiry-delta-blocks" -> 137, "eclair.server.port" -> 29737, "eclair.api.port" -> 28087, "eclair.payment-handler" -> "noop")).withFallback(commonConfig))
    instantiateEclairNode("F4", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F4", "eclair.expiry-delta-blocks" -> 138, "eclair.server.port" -> 29738, "eclair.api.port" -> 28088, "eclair.payment-handler" -> "noop")).withFallback(commonConfig))
    instantiateEclairNode("F5", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F5", "eclair.expiry-delta-blocks" -> 139, "eclair.server.port" -> 29739, "eclair.api.port" -> 28089, "eclair.payment-handler" -> "noop")).withFallback(commonConfig))

    // by default C has a normal payment handler, but this can be overriden in tests
    val paymentHandlerC = nodes("C").system.actorOf(LocalPaymentHandler.props(nodes("C").nodeParams))
    nodes("C").paymentHandler ! paymentHandlerC
  }

  def connect(node1: Kit, node2: Kit, fundingSatoshis: Long, pushMsat: Long) = {
    val sender = TestProbe()
    val address = node2.nodeParams.publicAddresses.head
    sender.send(node1.switchboard, Peer.Connect(NodeURI(
      nodeId = node2.nodeParams.nodeId,
      address = HostAndPort.fromParts(address.socketAddress.getHostString, address.socketAddress.getPort))))
    sender.expectMsgAnyOf(10 seconds, "connected", "already connected")
    sender.send(node1.switchboard, Peer.OpenChannel(
      remoteNodeId = node2.nodeParams.nodeId,
      fundingSatoshis = Satoshi(fundingSatoshis),
      pushMsat = MilliSatoshi(pushMsat),
      fundingTxFeeratePerKw_opt = None,
      channelFlags = None))
    assert(sender.expectMsgType[String](10 seconds).startsWith("created channel"))
  }

  test("connect nodes") {
    //       ,--G--,     // G is being added later in a test
    //      /       \
    // A---B ------- C ==== D
    //      \       / \
    //       '--E--'   F{1,2,3,4,5}

    val sender = TestProbe()
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    connect(nodes("A"), nodes("B"), 10000000, 0)
    connect(nodes("B"), nodes("C"), 2000000, 0)
    connect(nodes("C"), nodes("D"), 5000000, 0)
    connect(nodes("C"), nodes("D"), 5000000, 0)
    connect(nodes("B"), nodes("E"), 10000000, 0)
    connect(nodes("E"), nodes("C"), 10000000, 0)
    connect(nodes("C"), nodes("F1"), 5000000, 0)
    connect(nodes("C"), nodes("F2"), 5000000, 0)
    connect(nodes("C"), nodes("F3"), 5000000, 0)
    connect(nodes("C"), nodes("F4"), 5000000, 0)
    connect(nodes("C"), nodes("F5"), 5000000, 0)

    val numberOfChannels = 11
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
    sender.send(bitcoincli, BitcoinReq("generate", 2))
    sender.expectMsgType[JValue](10 seconds)

    within(60 seconds) {
      var count = 0
      while (count < channelEndpointsCount) {
        if (eventListener.expectMsgType[ChannelStateChanged](30 seconds).currentState == NORMAL) count = count + 1
      }
    }
  }

  def awaitAnnouncements(subset: Map[String, Kit], nodes: Int, channels: Int, updates: Int) = {
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
    val sender = TestProbe()
    // generating more blocks so that all funding txes are buried under at least 6 blocks
    sender.send(bitcoincli, BitcoinReq("generate", 4))
    sender.expectMsgType[JValue]
    // A requires private channels, as a consequence:
    // - only A and B know about channel A-B
    // - A is not announced
    awaitAnnouncements(nodes.filterKeys(key => List("A", "B").contains(key)), 9, 10, 22)
    awaitAnnouncements(nodes.filterKeys(key => !List("A", "B").contains(key)), 9, 10, 20)
  }

  test("send an HTLC A->D") {
    val sender = TestProbe()
    val amountMsat = MilliSatoshi(4200000)
    // first we retrieve a payment hash from D
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator,
      SendPayment(amountMsat.amount, pr.paymentHash, nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams))
    sender.expectMsgType[PaymentSucceeded]
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
    val channelUpdateBC = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, nodes("B").nodeParams.privateKey, nodes("C").nodeParams.nodeId, shortIdBC, nodes("B").nodeParams.expiryDeltaBlocks + 1, nodes("C").nodeParams.htlcMinimumMsat, nodes("B").nodeParams.feeBaseMsat, nodes("B").nodeParams.feeProportionalMillionth, 500000000L)
    // ...and notify B's relayer
    sender.send(nodes("B").relayer, LocalChannelUpdate(system.deadLetters, commitmentBC.channelId, shortIdBC, commitmentBC.remoteParams.nodeId, None, channelUpdateBC, commitmentBC))
    // we retrieve a payment hash from D
    val amountMsat = MilliSatoshi(4200000)
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the actual payment, do not randomize the route to make sure we route through node B
    val sendReq = SendPayment(amountMsat.amount, pr.paymentHash, nodes("D").nodeParams.nodeId, randomize = Some(false), routeParams = integrationTestRouteParams)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // A will receive an error from B that include the updated channel update, then will retry the payment
    sender.expectMsgType[PaymentSucceeded](5 seconds)

    awaitCond({
      // in the meantime, the router will have updated its state
      sender.send(nodes("A").router, 'updatesMap)
      // we then put everything back like before by asking B to refresh its channel update (this will override the one we created)
      val update = sender.expectMsgType[Map[ChannelDesc, ChannelUpdate]](10 seconds).apply(ChannelDesc(channelUpdateBC.shortChannelId, nodes("B").nodeParams.nodeId, nodes("C").nodeParams.nodeId))
      update == channelUpdateBC
    }, max = 30 seconds, interval = 1 seconds)

    // first let's wait 3 seconds to make sure the timestamp of the new channel_update will be strictly greater than the former
    sender.expectNoMsg(3 seconds)
    sender.send(nodes("B").register, ForwardShortId(shortIdBC, TickRefreshChannelUpdate))
    sender.send(nodes("B").register, ForwardShortId(shortIdBC, CMD_GETINFO))
    val channelUpdateBC_new = sender.expectMsgType[RES_GETINFO].data.asInstanceOf[DATA_NORMAL].channelUpdate
    logger.info(s"channelUpdateBC=$channelUpdateBC")
    logger.info(s"channelUpdateBC_new=$channelUpdateBC_new")
    assert(channelUpdateBC_new.timestamp > channelUpdateBC.timestamp)
    assert(channelUpdateBC_new.cltvExpiryDelta == nodes("B").nodeParams.expiryDeltaBlocks)
    awaitCond({
      sender.send(nodes("A").router, 'updatesMap)
      val u = sender.expectMsgType[Map[ChannelDesc, ChannelUpdate]].apply(ChannelDesc(channelUpdateBC.shortChannelId, nodes("B").nodeParams.nodeId, nodes("C").nodeParams.nodeId))
      u.cltvExpiryDelta == nodes("B").nodeParams.expiryDeltaBlocks
    }, max = 30 seconds, interval = 1 second)
  }

  test("send an HTLC A->D with an amount greater than capacity of B-C") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D
    val amountMsat = MilliSatoshi(300000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the payment (B-C has a smaller capacity than A-B and C-D)
    val sendReq = SendPayment(amountMsat.amount, pr.paymentHash, nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // A will first receive an error from C, then retry and route around C: A->B->E->C->D
    sender.expectMsgType[PaymentSucceeded](5 seconds)
  }

  test("send an HTLC A->D with an unknown payment hash") {
    val sender = TestProbe()
    val pr = SendPayment(100000000L, "42" * 32, nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams)
    sender.send(nodes("A").paymentInitiator, pr)

    // A will receive an error from D and won't retry
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("D").nodeParams.nodeId, UnknownPaymentHash))
  }

  test("send an HTLC A->D with a lower amount than requested") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = MilliSatoshi(200000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of only 1 mBTC
    val sendReq = SendPayment(100000000L, pr.paymentHash, nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams)
    sender.send(nodes("A").paymentInitiator, sendReq)

    // A will first receive an IncorrectPaymentAmount error from D
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("D").nodeParams.nodeId, IncorrectPaymentAmount))
  }

  test("send an HTLC A->D with too much overpayment") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = MilliSatoshi(200000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of 6 mBTC
    val sendReq = SendPayment(600000000L, pr.paymentHash, nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams)
    sender.send(nodes("A").paymentInitiator, sendReq)

    // A will first receive an IncorrectPaymentAmount error from D
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("D").nodeParams.nodeId, IncorrectPaymentAmount))
  }

  test("send an HTLC A->D with a reasonable overpayment") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = MilliSatoshi(200000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of 3 mBTC, more than asked but it should still be accepted
    val sendReq = SendPayment(300000000L, pr.paymentHash, nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams)
    sender.send(nodes("A").paymentInitiator, sendReq)
    sender.expectMsgType[PaymentSucceeded]
  }

  test("send multiple HTLCs A->D with a failover when a channel gets exhausted") {
    val sender = TestProbe()
    // there are two C-D channels with 5000000 sat, so we should be able to make 7 payments worth 1000000 sat each
    for (_ <- 0 until 7) {
      val amountMsat = MilliSatoshi(1000000000L)
      sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 payment"))
      val pr = sender.expectMsgType[PaymentRequest]

      val sendReq = SendPayment(amountMsat.amount, pr.paymentHash, nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams)
      sender.send(nodes("A").paymentInitiator, sendReq)
      sender.expectMsgType[PaymentSucceeded]
    }
  }

  test("send an HTLC A->B->G->C using heuristics to select the route") {
    val sender = TestProbe()

    // G has very large channels but slightly more expensive than the others
    instantiateEclairNode("G", ConfigFactory.parseMap(Map("eclair.node-alias" -> "G", "eclair.expiry-delta-blocks" -> 140, "eclair.server.port" -> 29740, "eclair.api.port" -> 28090, "eclair.fee-base-msat" -> 1010, "eclair.fee-proportional-millionths" -> 102)).withFallback(commonConfig))
    connect(nodes("B"), nodes("G"), 16000000, 0)
    connect(nodes("G"), nodes("C"), 16000000, 0)

    sender.send(bitcoincli, BitcoinReq("generate", 10))
    sender.expectMsgType[JValue](10 seconds)

    awaitCond({
      sender.send(nodes("A").router, 'channels)
      sender.expectMsgType[Iterable[ChannelAnnouncement]](5 seconds).exists(chanAnn => chanAnn.nodeId1 == nodes("G").nodeParams.nodeId || chanAnn.nodeId2 == nodes("G").nodeParams.nodeId)
    }, max = 60 seconds, interval = 3 seconds)

    val amountMsat = MilliSatoshi(2000)
    // first we retrieve a payment hash from C
    sender.send(nodes("C").paymentHandler, ReceivePayment(Some(amountMsat), "Change from coffee"))
    val pr = sender.expectMsgType[PaymentRequest](30 seconds)

    // the payment is requesting to use a capacity-optimized route which will select node G even though it's a bit more expensive
    sender.send(nodes("A").paymentInitiator,
      SendPayment(amountMsat.amount, pr.paymentHash, nodes("C").nodeParams.nodeId, randomize = Some(false), routeParams = integrationTestRouteParams.map(_.copy(ratios = Some(WeightRatios(0, 0, 1))))))

    awaitCond({
      val route = sender.expectMsgType[PaymentSucceeded].route
      route.exists(_.nodeId == nodes("G").nodeParams.nodeId) // assert the used route is actually going through G
    }, max = 30 seconds, interval = 3 seconds)
  }


  /**
    * We currently use p2pkh script Helpers.getFinalScriptPubKey
    *
    * @param scriptPubKey
    * @return
    */
  def scriptPubKeyToAddress(scriptPubKey: BinaryData) = Script.parse(scriptPubKey) match {
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
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // NB: F has a no-op payment handler, allowing us to manually fulfill htlcs
    val htlcReceiver = TestProbe()
    // we register this probe as the final payment handler
    nodes("F1").paymentHandler ! htlcReceiver.ref
    val preimage: BinaryData = "42" * 32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPayment(100000000L, paymentHash, nodes("F1").nodeParams.nodeId, maxAttempts = 1, routeParams = integrationTestRouteParams)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[UpdateAddHtlc]
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
    peers.head ! Disconnect
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
    sender.send(bitcoincli, BitcoinReq("generate", 1))
    sender.expectMsgType[JValue](10 seconds)
    // C will extract the preimage from the blockchain and fulfill the payment upstream
    paymentSender.expectMsgType[PaymentSucceeded](30 seconds)
    // at this point F should have 1 recv transactions: the redeemed htlc
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      res.filter(_ \ "address" == JString(finalAddressF)).flatMap(_ \ "txids" \\ classOf[JString]).size == 1
    }, max = 30 seconds, interval = 1 second)
    // we then generate enough blocks so that C gets its main delayed output
    sender.send(bitcoincli, BitcoinReq("generate", 145))
    sender.expectMsgType[JValue](10 seconds)
    // and C will have its main output
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 1
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make tx confirm
    sender.send(bitcoincli, BitcoinReq("generate", 2))
    sender.expectMsgType[JValue](10 seconds)
    // and we wait for C'channel to close
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSED, max = 30 seconds)
    awaitAnnouncements(nodes.filter(_._1 == "A"), 9, 11, 24)
  }

  test("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (remote commit)") {
    val sender = TestProbe()
    // we subscribe to C's channel state transitions
    val stateListener = TestProbe()
    nodes("C").system.eventStream.subscribe(stateListener.ref, classOf[ChannelStateChanged])
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // NB: F has a no-op payment handler, allowing us to manually fulfill htlcs
    val htlcReceiver = TestProbe()
    // we register this probe as the final payment handler
    nodes("F2").paymentHandler ! htlcReceiver.ref
    val preimage: BinaryData = "42" * 32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPayment(100000000L, paymentHash, nodes("F2").nodeParams.nodeId, maxAttempts = 1, routeParams = integrationTestRouteParams)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[UpdateAddHtlc]
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
    peers.head ! Disconnect
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
    sender.send(bitcoincli, BitcoinReq("generate", 1))
    sender.expectMsgType[JValue](10 seconds)
    // C will extract the preimage from the blockchain and fulfill the payment upstream
    paymentSender.expectMsgType[PaymentSucceeded](30 seconds)
    // at this point F should have 1 recv transactions: the redeemed htlc
    // we then generate enough blocks so that F gets its htlc-success delayed output
    sender.send(bitcoincli, BitcoinReq("generate", 145))
    sender.expectMsgType[JValue](10 seconds)
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
    sender.send(bitcoincli, BitcoinReq("generate", 2))
    sender.expectMsgType[JValue](10 seconds)
    // and we wait for C'channel to close
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSED, max = 30 seconds)
    awaitAnnouncements(nodes.filter(_._1 == "A"), 8, 10, 22)
  }

  test("propagate a failure upstream when a downstream htlc times out (local commit)") {
    val sender = TestProbe()
    // we subscribe to C's channel state transitions
    val stateListener = TestProbe()
    nodes("C").system.eventStream.subscribe(stateListener.ref, classOf[ChannelStateChanged])
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // NB: F has a no-op payment handler, allowing us to manually fulfill htlcs
    val htlcReceiver = TestProbe()
    // we register this probe as the final payment handler
    nodes("F3").paymentHandler ! htlcReceiver.ref
    val preimage: BinaryData = "42" * 32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPayment(100000000L, paymentHash, nodes("F3").nodeParams.nodeId, maxAttempts = 1, routeParams = integrationTestRouteParams)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[UpdateAddHtlc]
    // now that we have the channel id, we retrieve channels default final addresses
    sender.send(nodes("C").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalAddressC = scriptPubKeyToAddress(sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
    // we also retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    val previouslyReceivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
    // we then generate enough blocks to make the htlc timeout
    sender.send(bitcoincli, BitcoinReq("generate", 11))
    sender.expectMsgType[JValue](10 seconds)
    // we generate more blocks for the htlc-timeout to reach enough confirmations
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("generate", 1))
      sender.expectMsgType[JValue](10 seconds)
      paymentSender.msgAvailable
    }, max = 30 seconds, interval = 1 second)
    // this will fail the htlc
    val failed = paymentSender.expectMsgType[PaymentFailed](30 seconds)
    assert(failed.paymentHash === paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("C").nodeParams.nodeId, PermanentChannelFailure))
    // we then generate enough blocks to confirm all delayed transactions
    sender.send(bitcoincli, BitcoinReq("generate", 150))
    sender.expectMsgType[JValue](10 seconds)
    // at this point C should have 2 recv transactions: its main output and the htlc timeout
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 2
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make tx confirm
    sender.send(bitcoincli, BitcoinReq("generate", 2))
    sender.expectMsgType[JValue](10 seconds)
    // and we wait for C'channel to close
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSED, max = 30 seconds)
    awaitAnnouncements(nodes.filter(_._1 == "A"), 7, 9, 20)
  }

  test("propagate a failure upstream when a downstream htlc times out (remote commit)") {
    val sender = TestProbe()
    // we subscribe to C's channel state transitions
    val stateListener = TestProbe()
    nodes("C").system.eventStream.subscribe(stateListener.ref, classOf[ChannelStateChanged])
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // NB: F has a no-op payment handler, allowing us to manually fulfill htlcs
    val htlcReceiver = TestProbe()
    // we register this probe as the final payment handler
    nodes("F4").paymentHandler ! htlcReceiver.ref
    val preimage: BinaryData = "42" * 32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPayment(100000000L, paymentHash, nodes("F4").nodeParams.nodeId, maxAttempts = 1, routeParams = integrationTestRouteParams)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[UpdateAddHtlc]
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
    sender.send(bitcoincli, BitcoinReq("generate", 11))
    sender.expectMsgType[JValue](10 seconds)
    // we generate more blocks for the claim-htlc-timeout to reach enough confirmations
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("generate", 1))
      sender.expectMsgType[JValue](10 seconds)
      paymentSender.msgAvailable
    }, max = 30 seconds, interval = 1 second)
    // this will fail the htlc
    val failed = paymentSender.expectMsgType[PaymentFailed](30 seconds)
    assert(failed.paymentHash === paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("C").nodeParams.nodeId, PermanentChannelFailure))
    // we then generate enough blocks to confirm all delayed transactions
    sender.send(bitcoincli, BitcoinReq("generate", 145))
    sender.expectMsgType[JValue](10 seconds)
    // at this point C should have 2 recv transactions: its main output and the htlc timeout
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 2
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make tx confirm
    sender.send(bitcoincli, BitcoinReq("generate", 2))
    sender.expectMsgType[JValue](10 seconds)
    // and we wait for C'channel to close
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSED, max = 30 seconds)
    awaitAnnouncements(nodes.filter(_._1 == "A"), 6, 8, 18)
  }

  test("punish a node that has published a revoked commit tx") {
    val sender = TestProbe()
    // we subscribe to C's channel state transitions
    val stateListener = TestProbe()
    nodes("C").system.eventStream.subscribe(stateListener.ref, classOf[ChannelStateChanged])
    // we use this to get commitments
    val sigListener = TestProbe()
    nodes("F5").system.eventStream.subscribe(sigListener.ref, classOf[ChannelSignatureReceived])
    // we use this to control when to fulfill htlcs, setup is as follow : noop-handler ---> forward-handler ---> payment-handler
    val forwardHandlerC = TestProbe()
    nodes("C").paymentHandler ! forwardHandlerC.ref
    val forwardHandlerF = TestProbe()
    nodes("F5").paymentHandler ! forwardHandlerF.ref
    // this is the actual payment handler that we will forward requests to
    val paymentHandlerC = nodes("C").system.actorOf(LocalPaymentHandler.props(nodes("C").nodeParams))
    val paymentHandlerF = nodes("F5").system.actorOf(LocalPaymentHandler.props(nodes("F5").nodeParams))
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // first we send 3 mBTC to F so that it has a balance
    val amountMsat = MilliSatoshi(300000000L)
    sender.send(paymentHandlerF, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    val sendReq = SendPayment(300000000L, pr.paymentHash, pr.nodeId, routeParams = integrationTestRouteParams)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // we forward the htlc to the payment handler
    forwardHandlerF.expectMsgType[UpdateAddHtlc]
    forwardHandlerF.forward(paymentHandlerF)
    sigListener.expectMsgType[ChannelSignatureReceived]
    sigListener.expectMsgType[ChannelSignatureReceived]
    sender.expectMsgType[PaymentSucceeded]

    // we now send a few htlcs C->F and F->C in order to obtain a commitments with multiple htlcs
    def send(amountMsat: Long, paymentHandler: ActorRef, paymentInitiator: ActorRef) = {
      sender.send(paymentHandler, ReceivePayment(Some(MilliSatoshi(amountMsat)), "1 coffee"))
      val pr = sender.expectMsgType[PaymentRequest]
      val sendReq = SendPayment(amountMsat, pr.paymentHash, pr.nodeId, routeParams = integrationTestRouteParams)
      sender.send(paymentInitiator, sendReq)
      sender.expectNoMsg()
    }

    val buffer = TestProbe()
    send(100000000, paymentHandlerF, nodes("C").paymentInitiator) // will be left pending
    forwardHandlerF.expectMsgType[UpdateAddHtlc]
    forwardHandlerF.forward(buffer.ref)
    sigListener.expectMsgType[ChannelSignatureReceived]
    send(110000000, paymentHandlerF, nodes("C").paymentInitiator) // will be left pending
    forwardHandlerF.expectMsgType[UpdateAddHtlc]
    forwardHandlerF.forward(buffer.ref)
    sigListener.expectMsgType[ChannelSignatureReceived]
    send(120000000, paymentHandlerC, nodes("F5").paymentInitiator)
    forwardHandlerC.expectMsgType[UpdateAddHtlc]
    forwardHandlerC.forward(buffer.ref)
    sigListener.expectMsgType[ChannelSignatureReceived]
    send(130000000, paymentHandlerC, nodes("F5").paymentInitiator)
    forwardHandlerC.expectMsgType[UpdateAddHtlc]
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
    buffer.expectMsgType[UpdateAddHtlc]
    buffer.forward(paymentHandlerF)
    sigListener.expectMsgType[ChannelSignatureReceived]
    val preimage1 = sender.expectMsgType[PaymentSucceeded].paymentPreimage
    buffer.expectMsgType[UpdateAddHtlc]
    buffer.forward(paymentHandlerF)
    sigListener.expectMsgType[ChannelSignatureReceived]
    sender.expectMsgType[PaymentSucceeded].paymentPreimage
    buffer.expectMsgType[UpdateAddHtlc]
    buffer.forward(paymentHandlerC)
    sigListener.expectMsgType[ChannelSignatureReceived]
    sender.expectMsgType[PaymentSucceeded].paymentPreimage
    buffer.expectMsgType[UpdateAddHtlc]
    buffer.forward(paymentHandlerC)
    sigListener.expectMsgType[ChannelSignatureReceived]
    sender.expectMsgType[PaymentSucceeded].paymentPreimage
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
    sender.send(bitcoincli, BitcoinReq("generate", 20))
    sender.expectMsgType[JValue](10 seconds)
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
    sender.send(bitcoincli, BitcoinReq("generate", 2))
    sender.expectMsgType[JValue](10 seconds)
    // and we wait for C'channel to close
    awaitCond(stateListener.expectMsgType[ChannelStateChanged].currentState == CLOSED, max = 30 seconds)
    // this will remove the channel
    awaitAnnouncements(nodes.filter(_._1 == "A"), 5, 7, 16)
  }

  test("generate and validate lots of channels") {
    implicit val extendedClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    // we simulate fake channels by publishing a funding tx and sending announcement messages to a node at random
    logger.info(s"generating fake channels")
    val sender = TestProbe()
    val channels = for (i <- 0 until 242) yield {
      // let's generate a block every 10 txs so that we can compute short ids
      if (i % 10 == 0) {
        sender.send(bitcoincli, BitcoinReq("generate", 1))
        sender.expectMsgType[JValue](10 seconds)
      }
      AnnouncementsBatchValidationSpec.simulateChannel
    }
    sender.send(bitcoincli, BitcoinReq("generate", 1))
    sender.expectMsgType[JValue](10 seconds)
    logger.info(s"simulated ${channels.size} channels")

    val remoteNodeId = PrivateKey(BinaryData("01" * 32), true).publicKey

    // then we make the announcements
    val announcements = channels.map(c => AnnouncementsBatchValidationSpec.makeChannelAnnouncement(c))
    announcements.foreach(ann => nodes("A").router ! PeerRoutingMessage(sender.ref, remoteNodeId, ann))
    awaitCond({
      sender.send(nodes("D").router, 'channels)
      sender.expectMsgType[Iterable[ChannelAnnouncement]](5 seconds).size == channels.size + 7 // 7 remaining channels because  D->F{1-5} have disappeared
    }, max = 120 seconds, interval = 1 second)
  }

}
