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

import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.testkit.TestProbe
import akka.util.Timeout
import com.google.common.net.HostAndPort
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{Watch, WatchFundingConfirmed}
import fr.acinq.eclair.channel.{ChannelStateChanged, NORMAL}
import fr.acinq.eclair.io.NodeURI
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.NodeAnnouncement
import fr.acinq.eclair.{EclairImpl, Features}
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class MessageIntegrationSpec extends IntegrationSpec {
  val duration: FiniteDuration = FiniteDuration(5, SECONDS)
  implicit val timeout: Timeout = duration

  test("start eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.expiry-delta-blocks" -> 130, "eclair.server.port" -> 29730, "eclair.api.port" -> 28080, s"eclair.features.${Features.OnionMessages.rfcName}" -> "optional").asJava).withFallback(commonFeatures).withFallback(commonConfig))
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.expiry-delta-blocks" -> 131, "eclair.server.port" -> 29731, "eclair.api.port" -> 28081, s"eclair.features.${Features.OnionMessages.rfcName}" -> "optional").asJava).withFallback(commonFeatures).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.expiry-delta-blocks" -> 132, "eclair.server.port" -> 29732, "eclair.api.port" -> 28082, s"eclair.features.${Features.OnionMessages.rfcName}" -> "optional").asJava).withFallback(commonFeatures).withFallback(commonConfig))
    instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.node-alias" -> "D", "eclair.expiry-delta-blocks" -> 133, "eclair.server.port" -> 29733, "eclair.api.port" -> 28083, s"eclair.features.${Features.OnionMessages.rfcName}" -> "optional").asJava).withFallback(commonFeatures).withFallback(commonConfig))
    instantiateEclairNode("E", ConfigFactory.parseMap(Map("eclair.node-alias" -> "E", "eclair.expiry-delta-blocks" -> 134, "eclair.server.port" -> 29734, "eclair.api.port" -> 28084, s"eclair.features.${Features.OnionMessages.rfcName}" -> "optional").asJava).withFallback(commonFeatures).withFallback(commonConfig))
  }

  test("try to reach unknown node") {
    val alice = new EclairImpl(nodes("A"))

    assert(Await.result(alice.sendOnionMessage(nodes("B").nodeParams.nodeId :: Nil, ByteVector.empty, Some(hex"000000")), duration).sent === false)
  }

  test("send to connected node") {
    val alice = new EclairImpl(nodes("A"))
    val eventListener = TestProbe()

    assert(Await.result(alice.connect(Left(NodeURI(nodes("B").nodeParams.nodeId, HostAndPort.fromString("127.0.0.1:29731")))), duration) === "connected")

    nodes("B").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    assert(Await.result(alice.sendOnionMessage(nodes("B").nodeParams.nodeId :: Nil, ByteVector.empty, Some(hex"111111")), duration).sent === true)

    val r = eventListener.expectMsgType[OnionMessages.ReceiveMessage]
    assert(r.pathId contains hex"111111")
  }

  test("send with hop") {
    val alice = new EclairImpl(nodes("A"))
    val bob = new EclairImpl(nodes("B"))
    val eventListener = TestProbe()

    assert(Await.result(bob.connect(Left(NodeURI(nodes("C").nodeParams.nodeId, HostAndPort.fromString("127.0.0.1:29732")))), duration) === "connected")

    nodes("C").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    assert(Await.result(alice.sendOnionMessage(nodes("B").nodeParams.nodeId :: nodes("C").nodeParams.nodeId :: Nil, ByteVector.empty, Some(hex"2222")), duration).sent === true)

    val r = eventListener.expectMsgType[OnionMessages.ReceiveMessage]
    assert(r.pathId contains hex"2222")
  }

  test("create channels") {
    val bob = new EclairImpl(nodes("B"))
    val sender = TestProbe()
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    Await.result(bob.open(nodes("A").nodeParams.nodeId, Satoshi(100000), None, None, None, None, None), duration)
    Await.result(bob.open(nodes("C").nodeParams.nodeId, Satoshi(100000), None, None, None, None, None), duration)
    
    // confirming the funding tx
    generateBlocks(10)

    // We wait for A to know about B and C
    awaitCond({
      sender.send(nodes("A").router, Router.GetNodes)
      sender.expectMsgType[Iterable[NodeAnnouncement]].size == 3
    }, max = 60 seconds, interval = 1 second)
  }

  test("skip hop") {
    val alice = new EclairImpl(nodes("A"))
    val eventListener = TestProbe()

    nodes("C").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])
    assert(Await.result(alice.sendOnionMessage(nodes("C").nodeParams.nodeId :: Nil, ByteVector.empty, Some(hex"33333333")), duration).sent === true)

    val r = eventListener.expectMsgType[OnionMessages.ReceiveMessage]
    assert(r.pathId contains hex"33333333")
  }
}
