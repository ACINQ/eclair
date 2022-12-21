/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.io

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.PeerReadyNotifier.{NotifyWhenPeerReady, PeerReady, PeerUnavailable}
import fr.acinq.eclair.{BlockHeight, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class PeerReadyNotifierSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  case class FixtureParam(remoteNodeId: PublicKey, switchboard: TestProbe[Switchboard.GetPeerInfo], channelProbes: Seq[TestProbe[CMD_GET_CHANNEL_STATE]], probe: TestProbe[PeerReadyNotifier.Result]) {
    def channels: Set[akka.actor.ActorRef] = channelProbes.map(_.ref.toClassic).toSet
  }

  override def withFixture(test: OneArgTest): Outcome = {
    val remoteNodeId = randomKey().publicKey
    val switchboard = TestProbe[Switchboard.GetPeerInfo]("switchboard")
    val channelProbes = Seq(TestProbe[CMD_GET_CHANNEL_STATE]("channel1"), TestProbe[CMD_GET_CHANNEL_STATE]("channel2"))
    val probe = TestProbe[PeerReadyNotifier.Result]()
    withFixture(test.toNoArgTest(FixtureParam(remoteNodeId, switchboard, channelProbes, probe)))
  }

  test("peer not connected (duration timeout)") { f =>
    import f._

    val notifier = testKit.spawn(PeerReadyNotifier(remoteNodeId, switchboard.ref, timeout_opt = Some(Left(10 millis))))
    notifier ! NotifyWhenPeerReady(probe.ref)
    assert(switchboard.expectMessageType[Switchboard.GetPeerInfo].remoteNodeId == remoteNodeId)
    probe.expectMessage(PeerUnavailable(remoteNodeId))
  }

  test("peer not connected (block timeout)") { f =>
    import f._

    val notifier = testKit.spawn(PeerReadyNotifier(remoteNodeId, switchboard.ref, timeout_opt = Some(Right(BlockHeight(100)))))
    notifier ! NotifyWhenPeerReady(probe.ref)
    assert(switchboard.expectMessageType[Switchboard.GetPeerInfo].remoteNodeId == remoteNodeId)

    // We haven't reached the timeout yet.
    system.eventStream ! EventStream.Publish(CurrentBlockHeight(BlockHeight(99)))
    probe.expectNoMessage(100 millis)

    // We exceed the timeout (we've missed blocks).
    system.eventStream ! EventStream.Publish(CurrentBlockHeight(BlockHeight(110)))
    probe.expectMessage(PeerUnavailable(remoteNodeId))
  }

  test("peer connected (without channels)") { f =>
    import f._

    val notifier = testKit.spawn(PeerReadyNotifier(remoteNodeId, switchboard.ref, timeout_opt = Some(Right(BlockHeight(500)))))
    notifier ! NotifyWhenPeerReady(probe.ref)
    val request = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.CONNECTED, None, Set.empty)
    probe.expectMessage(PeerReady(remoteNodeId, 0))
  }

  test("peer connected (with channels)") { f =>
    import f._

    val notifier = testKit.spawn(PeerReadyNotifier(remoteNodeId, switchboard.ref, timeout_opt = Some(Right(BlockHeight(500)))))
    notifier ! NotifyWhenPeerReady(probe.ref)
    val request = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.CONNECTED, None, channels)

    // Channels are not ready yet.
    channelProbes.foreach(_.expectMessageType[CMD_GET_CHANNEL_STATE].replyTo ! RES_GET_CHANNEL_STATE(SYNCING))
    probe.expectNoMessage(100 millis)

    // After the first retry, one of the channels is ready but not the second one.
    channelProbes.head.expectMessageType[CMD_GET_CHANNEL_STATE].replyTo ! RES_GET_CHANNEL_STATE(NORMAL)
    channelProbes.last.expectMessageType[CMD_GET_CHANNEL_STATE].replyTo ! RES_GET_CHANNEL_STATE(SYNCING)
    probe.expectNoMessage(100 millis)

    // After the second retry, both channels are ready.
    channelProbes.head.expectMessageType[CMD_GET_CHANNEL_STATE].replyTo ! RES_GET_CHANNEL_STATE(NORMAL)
    channelProbes.last.expectMessageType[CMD_GET_CHANNEL_STATE].replyTo ! RES_GET_CHANNEL_STATE(SHUTDOWN)
    probe.expectMessage(PeerReady(remoteNodeId, 2))
  }

  test("peer connects after initial request") { f =>
    import f._

    val notifier = testKit.spawn(PeerReadyNotifier(remoteNodeId, switchboard.ref, timeout_opt = Some(Right(BlockHeight(500)))))
    notifier ! NotifyWhenPeerReady(probe.ref)
    val request1 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request1.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.DISCONNECTED, None, channels)
    channelProbes.head.expectNoMessage(100 millis)

    // An unrelated peer connects.
    system.eventStream ! EventStream.Publish(PeerConnected(TestProbe().ref.toClassic, randomKey().publicKey, null))
    switchboard.expectNoMessage(100 millis)

    // The target peer connects.
    system.eventStream ! EventStream.Publish(PeerConnected(TestProbe().ref.toClassic, remoteNodeId, null))
    val request2 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request2.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.CONNECTED, None, channels)
    channelProbes.foreach(_.expectMessageType[CMD_GET_CHANNEL_STATE].replyTo ! RES_GET_CHANNEL_STATE(NEGOTIATING))
    probe.expectMessage(PeerReady(remoteNodeId, 2))
  }

  test("peer connects then disconnects") { f =>
    import f._

    val notifier = testKit.spawn(PeerReadyNotifier(remoteNodeId, switchboard.ref, timeout_opt = None))
    notifier ! NotifyWhenPeerReady(probe.ref)
    val request1 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request1.replyTo ! Peer.PeerNotFound(remoteNodeId)
    channelProbes.head.expectNoMessage(100 millis)

    // The target peer connects and instantly disconnects.
    system.eventStream ! EventStream.Publish(PeerConnected(TestProbe().ref.toClassic, remoteNodeId, null))
    val request2 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request2.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.DISCONNECTED, None, channels)
    channelProbes.head.expectNoMessage(100 millis)

    // The target peer reconnects and stays connected.
    system.eventStream ! EventStream.Publish(PeerConnected(TestProbe().ref.toClassic, remoteNodeId, null))
    val request3 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request3.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.CONNECTED, None, channels)
    channelProbes.foreach(_.expectMessageType[CMD_GET_CHANNEL_STATE].replyTo ! RES_GET_CHANNEL_STATE(NEGOTIATING))
    probe.expectMessage(PeerReady(remoteNodeId, 2))
  }

  test("peer connects then disconnects (while waiting for channel states)") { f =>
    import f._

    val notifier = testKit.spawn(PeerReadyNotifier(remoteNodeId, switchboard.ref, timeout_opt = Some(Right(BlockHeight(500)))))
    notifier ! NotifyWhenPeerReady(probe.ref)
    val request1 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request1.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.DISCONNECTED, None, channels)
    channelProbes.head.expectNoMessage(100 millis)

    // The target peer connects.
    system.eventStream ! EventStream.Publish(PeerConnected(TestProbe().ref.toClassic, remoteNodeId, null))
    val request2 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request2.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.CONNECTED, None, channels)
    channelProbes.foreach(_.expectMessageType[CMD_GET_CHANNEL_STATE])

    // The target peer disconnects, so we wait for them to connect again.
    system.eventStream ! EventStream.Publish(PeerDisconnected(TestProbe().ref.toClassic, remoteNodeId))
    val request3 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request3.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.CONNECTED, None, channels)
    channelProbes.foreach(_.expectMessageType[CMD_GET_CHANNEL_STATE].replyTo ! RES_GET_CHANNEL_STATE(NORMAL))
    probe.expectMessage(PeerReady(remoteNodeId, 2))
  }

  test("peer connected (duration timeout)") { f =>
    import f._

    val notifier = testKit.spawn(PeerReadyNotifier(remoteNodeId, switchboard.ref, timeout_opt = Some(Left(100 millis))))
    notifier ! NotifyWhenPeerReady(probe.ref)
    val request = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.CONNECTED, None, channels)
    channelProbes.foreach(_.expectMessageType[CMD_GET_CHANNEL_STATE])
    probe.expectMessage(PeerUnavailable(remoteNodeId))
  }

  test("peer connected (block timeout)") { f =>
    import f._

    val notifier = testKit.spawn(PeerReadyNotifier(remoteNodeId, switchboard.ref, timeout_opt = Some(Right(BlockHeight(100)))))
    notifier ! NotifyWhenPeerReady(probe.ref)
    val request = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.CONNECTED, None, channels)
    channelProbes.foreach(_.expectMessageType[CMD_GET_CHANNEL_STATE])
    system.eventStream ! EventStream.Publish(CurrentBlockHeight(BlockHeight(100)))
    probe.expectMessage(PeerUnavailable(remoteNodeId))
  }

}
