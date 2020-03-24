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

package fr.acinq.eclair.io

import java.net.{InetAddress, ServerSocket}

import akka.actor.FSM
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.io.ReconnectionTask.WaitingData
import fr.acinq.eclair.wire.{Color, NodeAddress, NodeAnnouncement}
import fr.acinq.eclair.{TestConstants, TestkitBaseClass, _}
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector

import scala.concurrent.duration._

class ReconnectionTaskSpec extends TestkitBaseClass with StateTestsHelperMethods {

  val fakeIPAddress = NodeAddress.fromParts("1.2.3.4", 42000).get

  case class FixtureParam(nodeParams: NodeParams, remoteNodeId: PublicKey, reconnectionTask: TestFSMRef[ReconnectionTask.State, ReconnectionTask.Data, ReconnectionTask], monitor: TestProbe)

  case class TransitionWithData(previousState: ReconnectionTask.State, nextState: ReconnectionTask.State, previousData: ReconnectionTask.Data, nextData: ReconnectionTask.Data)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val remoteNodeId = TestConstants.Bob.nodeParams.nodeId

    import com.softwaremill.quicklens._
    val aliceParams = TestConstants.Alice.nodeParams
      .modify(_.autoReconnect).setToIf(test.tags.contains("auto_reconnect"))(true)

    if (test.tags.contains("with_node_announcements")) {
      val bobAnnouncement = NodeAnnouncement(randomBytes64, ByteVector.empty, 1, remoteNodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", fakeIPAddress :: Nil)
      aliceParams.db.network.addNode(bobAnnouncement)
    }

    val monitor = TestProbe()
    val reconnectionTask: TestFSMRef[ReconnectionTask.State, ReconnectionTask.Data, ReconnectionTask] =
      TestFSMRef(new ReconnectionTask(aliceParams, remoteNodeId, TestProbe().ref, TestProbe().ref) {
        onTransition {
          case state -> nextState => monitor.ref ! TransitionWithData(state, nextState, stateData, nextStateData)
        }
      })

    withFixture(test.toNoArgTest(FixtureParam(aliceParams, remoteNodeId, reconnectionTask, monitor)))
  }

  test("stay idle at startup if auto-reconnect is disabled", Tag("with_node_announcements")) { f =>
    import f._

    val peer = TestProbe()
    peer.send(reconnectionTask, FSM.Transition(peer.ref, Peer.INSTANTIATING, Peer.DISCONNECTED))
    monitor.expectNoMsg()
  }

  test("only try to connect once at startup if auto-reconnect is enabled but there are no known address", Tag("auto_reconnect")) { f =>
    import f._

    val peer = TestProbe()
    peer.send(reconnectionTask, FSM.Transition(peer.ref, Peer.INSTANTIATING, Peer.DISCONNECTED))
    val TransitionWithData(ReconnectionTask.IDLE, ReconnectionTask.WAITING, _, _) = monitor.expectMsgType[TransitionWithData]
    val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.IDLE, _, _) = monitor.expectMsgType[TransitionWithData]
    monitor.expectNoMsg()
  }

  test("initiate reconnection at startup if auto-reconnect is enabled", Tag("auto_reconnect"), Tag("with_node_announcements")) { f =>
    import f._

    val peer = TestProbe()
    peer.send(reconnectionTask, FSM.Transition(peer.ref, Peer.INSTANTIATING, Peer.DISCONNECTED))
    val TransitionWithData(ReconnectionTask.IDLE, ReconnectionTask.WAITING, _, _) = monitor.expectMsgType[TransitionWithData]
    val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.CONNECTING, _, connectingData: ReconnectionTask.ConnectingData) = monitor.expectMsgType[TransitionWithData]
    assert(connectingData.to === fakeIPAddress.socketAddress)
    val expectedNextReconnectionDelayInterval = (nodeParams.maxReconnectInterval.toSeconds / 2) to nodeParams.maxReconnectInterval.toSeconds
    assert(expectedNextReconnectionDelayInterval contains connectingData.nextReconnectionDelay.toSeconds) // we only reconnect once
  }

  test("reconnect with increasing delays", Tag("auto_reconnect")) { f =>
    import f._

    val probe = TestProbe()
    val peer = TestProbe()
    nodeParams.db.peers.addOrUpdatePeer(remoteNodeId, NodeAddress.fromParts("localhost", 42).get)
    peer.send(reconnectionTask, FSM.Transition(peer.ref, Peer.INSTANTIATING, Peer.DISCONNECTED))
    val TransitionWithData(ReconnectionTask.IDLE, ReconnectionTask.WAITING, _, _) = monitor.expectMsgType[TransitionWithData]
    probe.send(reconnectionTask, ReconnectionTask.TickReconnect)
    val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.CONNECTING, _, _) = monitor.expectMsgType[TransitionWithData]
    peer.send(reconnectionTask, FSM.Transition(peer.ref, Peer.DISCONNECTED, Peer.CONNECTED))
    val TransitionWithData(ReconnectionTask.CONNECTING, ReconnectionTask.IDLE, _, _) = monitor.expectMsgType[TransitionWithData]

    // disconnection
    peer.send(reconnectionTask, FSM.Transition(peer.ref, Peer.CONNECTED, Peer.DISCONNECTED))

    // auto reconnect
    val TransitionWithData(ReconnectionTask.IDLE, ReconnectionTask.WAITING, _, waitingData0: WaitingData) = monitor.expectMsgType[TransitionWithData]
    assert(waitingData0.nextReconnectionDelay >= (200 milliseconds))
    assert(waitingData0.nextReconnectionDelay <= (10 seconds))
    probe.send(reconnectionTask, ReconnectionTask.TickReconnect) // we send it manually in order to not have to actually wait (duplicates don' matter since we look at transitions sequentially)
    val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.CONNECTING, _, _) = monitor.expectMsgType[TransitionWithData]

    val TransitionWithData(ReconnectionTask.CONNECTING, ReconnectionTask.WAITING, _, waitingData1: WaitingData) = monitor.expectMsgType[TransitionWithData]
    assert(waitingData1.nextReconnectionDelay === (waitingData0.nextReconnectionDelay * 2))

    probe.send(reconnectionTask, ReconnectionTask.TickReconnect)
    val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.CONNECTING, _, _) = monitor.expectMsgType[TransitionWithData]

    val TransitionWithData(ReconnectionTask.CONNECTING, ReconnectionTask.WAITING, _, waitingData2: WaitingData) = monitor.expectMsgType[TransitionWithData]
    assert(waitingData2.nextReconnectionDelay === (waitingData0.nextReconnectionDelay * 4))

    probe.send(reconnectionTask, ReconnectionTask.TickReconnect)
    val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.CONNECTING, _, _) = monitor.expectMsgType[TransitionWithData]

    val TransitionWithData(ReconnectionTask.CONNECTING, ReconnectionTask.WAITING, _, waitingData3: WaitingData) = monitor.expectMsgType[TransitionWithData]
    assert(waitingData3.nextReconnectionDelay === (waitingData0.nextReconnectionDelay * 8))
  }

  test("reconnect using the address from node_announcement") { f =>
    import f._

    // we create a dummy tcp server and update bob's announcement to point to it
    val mockServer = new ServerSocket(0, 1, InetAddress.getLocalHost) // port will be assigned automatically
    val mockAddress = NodeAddress.fromParts(mockServer.getInetAddress.getHostAddress, mockServer.getLocalPort).get
    val bobAnnouncement = NodeAnnouncement(randomBytes64, ByteVector.empty, 1, remoteNodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", mockAddress :: Nil)
    nodeParams.db.network.addNode(bobAnnouncement)

    val peer = TestProbe()
    // we have auto-reconnect=false so we need to manually tell the peer to reconnect
    peer.send(reconnectionTask, Peer.Connect(remoteNodeId, None))

    // assert our mock server got an incoming connection (the client was spawned with the address from node_announcement)
    within(30 seconds) {
      mockServer.accept()
    }
    mockServer.close()
  }


}
