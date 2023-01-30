/*
 * Copyright 2021 ACINQ SAS
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

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.pattern.pipe
import akka.testkit.TestProbe
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi}
import fr.acinq.eclair.TestUtils.waitEventStreamSynced
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{Watch, WatchFundingConfirmed}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel.{CMD_CLOSE, RES_SUCCESS}
import fr.acinq.eclair.io.Switchboard
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.message.OnionMessages.{IntermediateNode, Recipient, buildRoute}
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.TlvCodecs.genericTlv
import fr.acinq.eclair.wire.protocol.{GenericTlv, NodeAnnouncement}
import fr.acinq.eclair.{EclairImpl, Features, MilliSatoshi, SendOnionMessageResponse, UInt64, randomBytes, randomKey}
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class MessageIntegrationSpec extends IntegrationSpec {
  implicit val timeout: Timeout = FiniteDuration(30, SECONDS)

  test("start eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.server.port" -> 30700, "eclair.api.port" -> 30780, s"eclair.features.${Features.OnionMessages.rfcName}" -> "optional", "eclair.onion-messages.relay-policy" -> "relay-all", "eclair.onion-messages.reply-timeout" -> "1 minute").asJava).withFallback(commonConfig))
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.server.port" -> 30701, "eclair.api.port" -> 30781, s"eclair.features.${Features.OnionMessages.rfcName}" -> "optional", "eclair.onion-messages.relay-policy" -> "relay-all", "eclair.onion-messages.reply-timeout" -> "1 second").asJava).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.server.port" -> 30702, "eclair.api.port" -> 30782, s"eclair.features.${Features.OnionMessages.rfcName}" -> "optional", "eclair.onion-messages.relay-policy" -> "relay-all").asJava).withFallback(commonConfig))
    instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.node-alias" -> "D", "eclair.server.port" -> 30703, "eclair.api.port" -> 30783).asJava).withFallback(commonConfig))
    instantiateEclairNode("E", ConfigFactory.parseMap(Map("eclair.node-alias" -> "E", "eclair.server.port" -> 30704, "eclair.api.port" -> 30784, s"eclair.features.${Features.OnionMessages.rfcName}" -> "optional", "eclair.onion-messages.relay-policy" -> "channels-only").asJava).withFallback(commonConfig))
    instantiateEclairNode("F", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F", "eclair.server.port" -> 30705, "eclair.api.port" -> 30785, s"eclair.features.${Features.OnionMessages.rfcName}" -> "optional", "eclair.onion-messages.relay-policy" -> "no-relay").asJava).withFallback(commonConfig))
  }

  test("try to reach unknown node") {
    val alice = new EclairImpl(nodes("A"))
    val probe = TestProbe()
    alice.sendOnionMessage(Nil, Left(nodes("B").nodeParams.nodeId), None, ByteVector.empty).pipeTo(probe.ref)
    val result = probe.expectMsgType[SendOnionMessageResponse]
    assert(!result.sent)
  }

  test("send to connected node") {
    val alice = new EclairImpl(nodes("A"))
    connect(nodes("A"), nodes("B"))

    val probe = TestProbe()
    val eventListener = TestProbe()
    nodes("B").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    alice.sendOnionMessage(Nil, Left(nodes("B").nodeParams.nodeId), None, ByteVector.empty).pipeTo(probe.ref)
    assert(probe.expectMsgType[SendOnionMessageResponse].sent)
    eventListener.expectMsgType[OnionMessages.ReceiveMessage](max = 60 seconds)
  }

  test("send to route that starts at ourselves") {
    val alice = new EclairImpl(nodes("A"))

    val probe = TestProbe()
    val eventListener = TestProbe()
    nodes("B").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])

    val blindedRoute = buildRoute(randomKey(), Seq(IntermediateNode(nodes("A").nodeParams.nodeId), IntermediateNode(nodes("B").nodeParams.nodeId), IntermediateNode(nodes("B").nodeParams.nodeId)), Recipient(nodes("B").nodeParams.nodeId, None))
    assert(blindedRoute.introductionNodeId == nodes("A").nodeParams.nodeId)

    alice.sendOnionMessage(Nil, Right(blindedRoute), None, ByteVector.empty).pipeTo(probe.ref)
    assert(probe.expectMsgType[SendOnionMessageResponse].sent)
    eventListener.expectMsgType[OnionMessages.ReceiveMessage](max = 60 seconds)
  }

  test("expect reply") {
    val alice = new EclairImpl(nodes("A"))
    val bob = new EclairImpl(nodes("B"))
    val probe = TestProbe()
    val eventListener = TestProbe()
    nodes("B").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    alice.sendOnionMessage(Nil, Left(nodes("B").nodeParams.nodeId), Some(Seq(nodes("A").nodeParams.nodeId)), hex"3f00").pipeTo(probe.ref)

    val recv = eventListener.expectMsgType[OnionMessages.ReceiveMessage](max = 60 seconds)
    assert(recv.finalPayload.replyPath_opt.nonEmpty)
    bob.sendOnionMessage(Nil, Right(recv.finalPayload.replyPath_opt.get), None, hex"1d01ab")

    val res = probe.expectMsgType[SendOnionMessageResponse]
    assert(res.failureMessage.isEmpty)
    assert(res.response.nonEmpty)
    assert(res.response.get.unknownTlvs("29") == hex"ab")
  }

  test("reply timeout") {
    val bob = new EclairImpl(nodes("B"))
    val probe = TestProbe()
    val eventListener = TestProbe()
    nodes("A").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    bob.sendOnionMessage(Nil, Left(nodes("A").nodeParams.nodeId), Some(Seq(nodes("B").nodeParams.nodeId)), hex"3f00").pipeTo(probe.ref)

    val recv = eventListener.expectMsgType[OnionMessages.ReceiveMessage](max = 60 seconds)
    assert(recv.finalPayload.replyPath_opt.nonEmpty)

    val res = probe.expectMsgType[SendOnionMessageResponse]
    assert(res.failureMessage contains "No response")
  }

  test("send to connected node with channels-only") {
    val eve = new EclairImpl(nodes("E"))
    connect(nodes("A"), nodes("E"))

    val probe = TestProbe()
    val eventListener = TestProbe()
    nodes("A").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    eve.sendOnionMessage(Nil, Left(nodes("A").nodeParams.nodeId), None, ByteVector.empty).pipeTo(probe.ref)
    assert(probe.expectMsgType[SendOnionMessageResponse].sent)
    eventListener.expectMsgType[OnionMessages.ReceiveMessage](max = 60 seconds)
  }

  test("send to connected node with no-relay") {
    val fabrice = new EclairImpl(nodes("F"))
    connect(nodes("F"), nodes("A"))

    val probe = TestProbe()
    val eventListener = TestProbe()
    nodes("A").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    fabrice.sendOnionMessage(Nil, Left(nodes("A").nodeParams.nodeId), None, ByteVector.empty).pipeTo(probe.ref)
    assert(probe.expectMsgType[SendOnionMessageResponse].sent)
    eventListener.expectMsgType[OnionMessages.ReceiveMessage](max = 60 seconds)
  }

  test("send with hop") {
    val alice = new EclairImpl(nodes("A"))
    connect(nodes("B"), nodes("C"))

    val probe = TestProbe()
    val eventListener = TestProbe()
    nodes("C").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    alice.sendOnionMessage(nodes("B").nodeParams.nodeId :: Nil, Left(nodes("C").nodeParams.nodeId), None, hex"710301020375020102").pipeTo(probe.ref)
    assert(probe.expectMsgType[SendOnionMessageResponse].sent)

    val r = eventListener.expectMsgType[OnionMessages.ReceiveMessage](max = 60 seconds)
    assert(r.finalPayload.records.unknown == Set(GenericTlv(UInt64(113), hex"010203"), GenericTlv(UInt64(117), hex"0102")))
  }

  test("send very large message with hop") {
    // Total message size stays below 65536 bytes, the message is relayed.
    val bytes = randomBytes(65289)
    val encodedBytes = genericTlv.encode(GenericTlv(UInt64(135), bytes)).require.bytes

    val alice = new EclairImpl(nodes("A"))

    val probe = TestProbe()
    val eventListener = TestProbe()
    nodes("C").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    alice.sendOnionMessage(nodes("B").nodeParams.nodeId :: Nil, Left(nodes("C").nodeParams.nodeId), None, encodedBytes).pipeTo(probe.ref)
    assert(probe.expectMsgType[SendOnionMessageResponse].sent)

    val r = eventListener.expectMsgType[OnionMessages.ReceiveMessage](max = 60 seconds)
    assert(r.finalPayload.records.unknown == Set(GenericTlv(UInt64(135), bytes)))
  }

  test("send too large message with hop") {
    // Total message size goes above the 65536 bytes limit.
    val bytes = randomBytes(65290)
    val encodedBytes = genericTlv.encode(GenericTlv(UInt64(135), bytes)).require.bytes

    val alice = new EclairImpl(nodes("A"))

    val probe = TestProbe()
    val eventListener = TestProbe()
    nodes("C").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    alice.sendOnionMessage(nodes("B").nodeParams.nodeId :: Nil, Left(nodes("C").nodeParams.nodeId), None, encodedBytes).pipeTo(probe.ref)
    assert(!probe.expectMsgType[SendOnionMessageResponse].sent)

    eventListener.expectNoMessage()
  }

  test("relay with channels-only and missing channel") {
    val alice = new EclairImpl(nodes("A"))
    connect(nodes("E"), nodes("C"))

    val probe = TestProbe()
    val eventListener = TestProbe()
    nodes("C").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    alice.sendOnionMessage(nodes("E").nodeParams.nodeId :: Nil, Left(nodes("C").nodeParams.nodeId), None, hex"710301020375020102").pipeTo(probe.ref)
    assert(probe.expectMsgType[SendOnionMessageResponse].sent)

    eventListener.expectNoMessage()
  }

  test("relay with no-relay") {
    val alice = new EclairImpl(nodes("A"))
    connect(nodes("F"), nodes("C"))

    val probe = TestProbe()
    val eventListener = TestProbe()
    nodes("C").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    alice.sendOnionMessage(nodes("F").nodeParams.nodeId :: Nil, Left(nodes("C").nodeParams.nodeId), None, hex"710301020375020102").pipeTo(probe.ref)
    assert(probe.expectMsgType[SendOnionMessageResponse].sent)

    eventListener.expectNoMessage()
  }

  test("open channels") {
    val probe = TestProbe()

    // We connect A -> B -> C
    connect(nodes("B"), nodes("A"), Satoshi(100_000), MilliSatoshi(0))
    connect(nodes("B"), nodes("C"), Satoshi(100_000), MilliSatoshi(0))

    // We connect A -> E -> C
    connect(nodes("E"), nodes("A"), Satoshi(100_000), MilliSatoshi(0))
    connect(nodes("E"), nodes("C"), Satoshi(100_000), MilliSatoshi(0))

    // We connect A -> F -> C
    connect(nodes("F"), nodes("A"), Satoshi(100_000), MilliSatoshi(0))
    connect(nodes("F"), nodes("C"), Satoshi(100_000), MilliSatoshi(0))

    // we make sure all channels have set up their WatchConfirmed for the funding tx
    awaitCond({
      nodes("B").watcher ! ZmqWatcher.ListWatches(probe.ref)
      val watches = probe.expectMsgType[Set[Watch[_]]]
      watches.count(_.isInstanceOf[WatchFundingConfirmed]) == 2
    }, max = 20 seconds, interval = 500 millis)
    awaitCond({
      nodes("E").watcher ! ZmqWatcher.ListWatches(probe.ref)
      val watches = probe.expectMsgType[Set[Watch[_]]]
      watches.count(_.isInstanceOf[WatchFundingConfirmed]) == 2
    }, max = 20 seconds, interval = 500 millis)
    awaitCond({
      nodes("F").watcher ! ZmqWatcher.ListWatches(probe.ref)
      val watches = probe.expectMsgType[Set[Watch[_]]]
      watches.count(_.isInstanceOf[WatchFundingConfirmed]) == 2
    }, max = 20 seconds, interval = 500 millis)

    // We also connect A -> D, B -> D, C -> D
    connect(nodes("D"), nodes("A"), Satoshi(100_000), MilliSatoshi(0))
    connect(nodes("D"), nodes("B"), Satoshi(100_000), MilliSatoshi(0))
    connect(nodes("D"), nodes("C"), Satoshi(100_000), MilliSatoshi(0))

    // we make sure all channels have set up their WatchConfirmed for the funding tx
    awaitCond({
      nodes("D").watcher ! ZmqWatcher.ListWatches(probe.ref)
      val watches = probe.expectMsgType[Set[Watch[_]]]
      watches.count(_.isInstanceOf[WatchFundingConfirmed]) == 3
    }, max = 20 seconds, interval = 500 millis)

    // confirm funding txs
    generateBlocks(10)

    // We wait for A to know about B, C, D, E and F
    awaitCond({
      probe.send(nodes("A").router, Router.GetNodes)
      probe.expectMsgType[Iterable[NodeAnnouncement]].size == 6
    }, max = 60 seconds, interval = 1 second)
  }

  test("relay with channels-only") {
    val alice = new EclairImpl(nodes("A"))

    val probe = TestProbe()
    val eventListener = TestProbe()
    nodes("C").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    alice.sendOnionMessage(nodes("E").nodeParams.nodeId :: Nil, Left(nodes("C").nodeParams.nodeId), None, hex"710301020375020102").pipeTo(probe.ref)
    assert(probe.expectMsgType[SendOnionMessageResponse].sent)

    val r = eventListener.expectMsgType[OnionMessages.ReceiveMessage](max = 60 seconds)
    assert(r.finalPayload.records.unknown == Set(GenericTlv(UInt64(113), hex"010203"), GenericTlv(UInt64(117), hex"0102")))
  }

  test("channel relay with no-relay") {
    val alice = new EclairImpl(nodes("A"))

    val probe = TestProbe()
    val eventListener = TestProbe()
    nodes("C").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    alice.sendOnionMessage(nodes("F").nodeParams.nodeId :: Nil, Left(nodes("C").nodeParams.nodeId), None, hex"710301020375020102").pipeTo(probe.ref)
    assert(probe.expectMsgType[SendOnionMessageResponse].sent)

    eventListener.expectNoMessage()
  }

  test("automatically connect to node based on node_announcement address") {
    val alice = new EclairImpl(nodes("A"))
    val probe = TestProbe()
    val eventListener = TestProbe()

    nodes("C").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    alice.sendOnionMessage(Nil, Left(nodes("C").nodeParams.nodeId), None, ByteVector.empty).pipeTo(probe.ref)
    assert(probe.expectMsgType[SendOnionMessageResponse].sent)
    eventListener.expectMsgType[OnionMessages.ReceiveMessage](max = 60 seconds)

    // We disconnect A from C for future tests.
    alice.disconnect(nodes("C").nodeParams.nodeId)
  }

  test("close channels") {
    // We close the channels A -> B -> C but we keep channels with D
    // This ensures nodes still have an unrelated channel so we keep them in the network DB.
    val probe = TestProbe()
    probe.send(nodes("B").register, Symbol("channels"))
    val channelsB = probe.expectMsgType[Map[ByteVector32, ActorRef]]
    assert(channelsB.size == 3)
    probe.send(nodes("D").register, Symbol("channels"))
    val channelsD = probe.expectMsgType[Map[ByteVector32, ActorRef]]
    assert(channelsD.size == 3)
    channelsB.foreach {
      case (channelId, channel) =>
        if (!channelsD.contains(channelId)) {
          channel ! CMD_CLOSE(probe.ref, None, None)
          probe.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
        }
    }

    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    awaitCond({
      bitcoinClient.getMempool().pipeTo(probe.ref)
      probe.expectMsgType[Seq[Transaction]].size == 2
    }, max = 20 seconds, interval = 500 millis)

    // confirm closing txs
    generateBlocks(10)

    // nodes should disconnect automatically once they don't have any channels left
    awaitCond({
      probe.send(nodes("A").switchboard, Switchboard.GetPeers)
      val peersA = probe.expectMsgType[Iterable[ActorRef]]
      probe.send(nodes("B").switchboard, Switchboard.GetPeers)
      val peersB = probe.expectMsgType[Iterable[ActorRef]]
      // A and B are now only connected to D
      peersA.size == 3 && peersB.size == 1
    }, max = 20 seconds, interval = 500 millis)
  }

  test("automatically connect to known nodes") {
    val alice = new EclairImpl(nodes("A"))
    val probe = TestProbe()
    val eventListener = TestProbe()
    nodes("C").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    waitEventStreamSynced(nodes("C").system.eventStream)
    alice.sendOnionMessage(nodes("B").nodeParams.nodeId :: Nil, Left(nodes("C").nodeParams.nodeId), None, hex"7300").pipeTo(probe.ref)
    assert(probe.expectMsgType[SendOnionMessageResponse].sent)

    val r = eventListener.expectMsgType[OnionMessages.ReceiveMessage](max = 60 seconds)
    assert(r.finalPayload.pathId_opt.isEmpty)
    assert(r.finalPayload.records.unknown == Set(GenericTlv(UInt64(115), hex"")))
  }

}
