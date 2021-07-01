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

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import com.google.common.net.HostAndPort
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.Features._
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.{Peer, PeerConnection}
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.eclair.router.RouteCalculation.ROUTE_MAX_LENGTH
import fr.acinq.eclair.router.Router.{MultiPartParams, RouteParams, NORMAL => _, State => _}
import fr.acinq.eclair.{CltvExpiryDelta, Kit, MilliSatoshi, MilliSatoshiLong, Setup, TestKitBaseClass}
import grizzled.slf4j.Logging
import org.json4s.{DefaultFormats, Formats}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

import java.io.File
import java.util.Properties
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * Created by PM on 15/03/2017.
 */

abstract class IntegrationSpec extends TestKitBaseClass with BitcoindService with AnyFunSuiteLike with BeforeAndAfterAll with Logging {

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
    mpp = MultiPartParams(15000000 msat, 6),
    includeLocalChannelCost = false,
  ))

  // we need to provide a value higher than every node's fulfill-safety-before-timeout
  val finalCltvExpiryDelta = CltvExpiryDelta(36)

  val commonConfig = ConfigFactory.parseMap(Map(
    "eclair.chain" -> "regtest",
    "eclair.file-backup.enabled" -> false,
    "eclair.server.public-ips.1" -> "127.0.0.1",
    "eclair.bitcoind.port" -> bitcoindPort,
    "eclair.bitcoind.rpcport" -> bitcoindRpcPort,
    "eclair.bitcoind.zmqblock" -> s"tcp://127.0.0.1:$bitcoindZmqBlockPort",
    "eclair.bitcoind.zmqtx" -> s"tcp://127.0.0.1:$bitcoindZmqTxPort",
    "eclair.bitcoind.wallet" -> defaultWallet,
    "eclair.mindepth-blocks" -> 2,
    "eclair.max-htlc-value-in-flight-msat" -> 100000000000L,
    "eclair.max-block-processing-delay" -> "2 seconds",
    "eclair.router.broadcast-interval" -> "2 seconds",
    "eclair.auto-reconnect" -> false,
    "eclair.to-remote-delay-blocks" -> 24,
    "eclair.multi-part-payment-expiry" -> "20 seconds").asJava).withFallback(ConfigFactory.load())

  val commonFeatures = ConfigFactory.parseMap(Map(
    s"eclair.features.${OptionDataLossProtect.rfcName}" -> "optional",
    s"eclair.features.${ChannelRangeQueries.rfcName}" -> "optional",
    s"eclair.features.${ChannelRangeQueriesExtended.rfcName}" -> "optional",
    s"eclair.features.${VariableLengthOnion.rfcName}" -> "mandatory",
    s"eclair.features.${PaymentSecret.rfcName}" -> "mandatory",
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

  override def beforeAll(): Unit = {
    startBitcoind()
    waitForBitcoindReady()
  }

  override def afterAll(): Unit = {
    // gracefully stopping bitcoin will make it store its state cleanly to disk, which is good for later debugging
    logger.info(s"stopping bitcoind")
    stopBitcoind()
    nodes.foreach {
      case (name, setup) =>
        logger.info(s"stopping node $name")
        TestKit.shutdownActorSystem(setup.system)
    }
    super.afterAll()
  }

  def instantiateEclairNode(name: String, config: Config): Unit = {
    val datadir = new File(INTEGRATION_TMP_DIR, s"datadir-eclair-$name")
    datadir.mkdirs()
    implicit val system: ActorSystem = ActorSystem(s"system-$name", config)
    val setup = new Setup(datadir, pluginParams = Seq.empty)
    val kit = Await.result(setup.bootstrap, 10 seconds)
    nodes = nodes + (name -> kit)
  }

  def javaProps(props: Seq[(String, String)]): Properties = {
    val properties = new Properties()
    props.foreach(p => properties.setProperty(p._1, p._2))
    properties
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
      initialRelayFees_opt = None,
      channelFlags = None,
      timeout_opt = None))
    sender.expectMsgType[ChannelOpenResponse.ChannelOpened](10 seconds)
  }

  def getBlockCount: Long = {
    // we make sure that all nodes have the same value
    awaitCond(nodes.values.map(_.nodeParams.currentBlockHeight).toSet.size == 1, max = 1 minute, interval = 1 second)
    // and we return it (NB: it could be a different value at this point)
    nodes.values.head.nodeParams.currentBlockHeight
  }

}
