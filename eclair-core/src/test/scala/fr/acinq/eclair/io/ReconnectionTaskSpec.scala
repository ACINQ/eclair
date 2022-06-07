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

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair._
import fr.acinq.eclair.io.Peer.ChannelId
import fr.acinq.eclair.io.ReconnectionTask.WaitingData
import fr.acinq.eclair.tor.Socks5ProxyParams
import fr.acinq.eclair.wire.protocol.{Color, IPv4, NodeAddress, NodeAnnouncement}
import org.mockito.IdiomaticMockito.StubbingOps
import org.mockito.MockitoSugar.mock
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, ParallelTestExecution, Tag}

import java.net.Inet4Address
import scala.concurrent.duration._

class ReconnectionTaskSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ParallelTestExecution {

  private val fakeIPAddress = NodeAddress.fromParts("1.2.3.4", 42000).get
  private val channels = Map(Peer.FinalChannelId(randomBytes32()) -> system.deadLetters)

  private val PeerNothingData = Peer.Nothing
  private val PeerDisconnectedData = Peer.DisconnectedData(channels)
  private val PeerConnectedData = Peer.ConnectedData(fakeIPAddress, system.deadLetters, null, null, channels.map { case (k: ChannelId, v) => (k, v) })

  case class FixtureParam(nodeParams: NodeParams, remoteNodeId: PublicKey, reconnectionTask: TestFSMRef[ReconnectionTask.State, ReconnectionTask.Data, ReconnectionTask], monitor: TestProbe)

  case class TransitionWithData(previousState: ReconnectionTask.State, nextState: ReconnectionTask.State, previousData: ReconnectionTask.Data, nextData: ReconnectionTask.Data)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val remoteNodeId = TestConstants.Bob.nodeParams.nodeId

    import com.softwaremill.quicklens._
    val aliceParams = TestConstants.Alice.nodeParams
      .modify(_.autoReconnect).setToIf(test.tags.contains("auto_reconnect"))(true)

    if (test.tags.contains("with_node_announcements")) {
      val bobAnnouncement = NodeAnnouncement(randomBytes64(), Features.empty, 1 unixsec, remoteNodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", fakeIPAddress :: Nil)
      aliceParams.db.network.addNode(bobAnnouncement)
    }

    system.actorOf(ClientSpawner.props(aliceParams.keyPair, aliceParams.socksProxy_opt, aliceParams.peerConnectionConf, TestProbe().ref, TestProbe().ref))

    val monitor = TestProbe()
    val reconnectionTask: TestFSMRef[ReconnectionTask.State, ReconnectionTask.Data, ReconnectionTask] =
      TestFSMRef(new ReconnectionTask(aliceParams, remoteNodeId) {
        onTransition {
          case state -> nextState => monitor.ref ! TransitionWithData(state, nextState, stateData, nextStateData)
        }
      })

    withFixture(test.toNoArgTest(FixtureParam(aliceParams, remoteNodeId, reconnectionTask, monitor)))
  }

  test("stay idle at startup if auto-reconnect is disabled", Tag("with_node_announcements")) { f =>
    import f._

    val peer = TestProbe()
    peer.send(reconnectionTask, Peer.Transition(PeerNothingData, PeerDisconnectedData))
    monitor.expectNoMessage()
  }

  test("stay idle at startup if there are no channels", Tag("auto_reconnect"), Tag("with_node_announcements")) { f =>
    import f._

    val peer = TestProbe()
    peer.send(reconnectionTask, Peer.Transition(PeerNothingData, Peer.DisconnectedData(Map.empty)))
    monitor.expectNoMessage()
  }

  test("only try to connect once at startup if auto-reconnect is enabled but there are no known address", Tag("auto_reconnect")) { f =>
    import f._

    val peer = TestProbe()
    peer.send(reconnectionTask, Peer.Transition(PeerNothingData, PeerDisconnectedData))
    val TransitionWithData(ReconnectionTask.IDLE, ReconnectionTask.WAITING, _, _) = monitor.expectMsgType[TransitionWithData]
    val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.IDLE, _, _) = monitor.expectMsgType[TransitionWithData]
  }

  test("initiate reconnection at startup if auto-reconnect is enabled", Tag("auto_reconnect"), Tag("with_node_announcements")) { f =>
    import f._

    val peer = TestProbe()
    peer.send(reconnectionTask, Peer.Transition(PeerNothingData, PeerDisconnectedData))
    val TransitionWithData(ReconnectionTask.IDLE, ReconnectionTask.WAITING, _, _) = monitor.expectMsgType[TransitionWithData]
    val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.CONNECTING, _, connectingData: ReconnectionTask.ConnectingData) = monitor.expectMsgType[TransitionWithData]
    assert(connectingData.to == fakeIPAddress)
    val expectedNextReconnectionDelayInterval = (nodeParams.maxReconnectInterval.toSeconds / 2) to nodeParams.maxReconnectInterval.toSeconds
    assert(expectedNextReconnectionDelayInterval contains connectingData.nextReconnectionDelay.toSeconds) // we only reconnect once
  }

  test("reconnect with increasing delays", Tag("auto_reconnect")) { f =>
    import f._

    val probe = TestProbe()
    val peer = TestProbe()
    nodeParams.db.peers.addOrUpdatePeer(remoteNodeId, fakeIPAddress)
    peer.send(reconnectionTask, Peer.Transition(PeerNothingData, PeerDisconnectedData))
    val TransitionWithData(ReconnectionTask.IDLE, ReconnectionTask.WAITING, _, _) = monitor.expectMsgType[TransitionWithData]
    probe.send(reconnectionTask, ReconnectionTask.TickReconnect)
    val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.CONNECTING, _, _) = monitor.expectMsgType[TransitionWithData]
    peer.send(reconnectionTask, Peer.Transition(PeerDisconnectedData, PeerConnectedData))
    val TransitionWithData(ReconnectionTask.CONNECTING, ReconnectionTask.IDLE, _, _) = monitor.expectMsgType[TransitionWithData]

    // NB: we change the data to make it appear like we have been connected for a long time
    reconnectionTask.setState(stateData = reconnectionTask.stateData.asInstanceOf[ReconnectionTask.IdleData].copy(since = 0 unixms))
    val TransitionWithData(ReconnectionTask.IDLE, ReconnectionTask.IDLE, _, _) = monitor.expectMsgType[TransitionWithData]

    // disconnection
    peer.send(reconnectionTask, Peer.Transition(PeerConnectedData, PeerDisconnectedData))

    // auto reconnect
    val TransitionWithData(ReconnectionTask.IDLE, ReconnectionTask.WAITING, _, waitingData0: WaitingData) = monitor.expectMsgType[TransitionWithData]
    assert(waitingData0.nextReconnectionDelay >= (200 milliseconds))
    assert(waitingData0.nextReconnectionDelay <= (20 seconds))
    probe.send(reconnectionTask, ReconnectionTask.TickReconnect) // we send it manually in order to not have to actually wait (duplicates don't matter since we look at transitions sequentially)
    val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.CONNECTING, _, _) = monitor.expectMsgType[TransitionWithData]

    val TransitionWithData(ReconnectionTask.CONNECTING, ReconnectionTask.WAITING, _, waitingData1: WaitingData) = monitor.expectMsgType[TransitionWithData](60 seconds)
    assert(waitingData1.nextReconnectionDelay == (waitingData0.nextReconnectionDelay * 2))

    probe.send(reconnectionTask, ReconnectionTask.TickReconnect)
    val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.CONNECTING, _, _) = monitor.expectMsgType[TransitionWithData]

    val TransitionWithData(ReconnectionTask.CONNECTING, ReconnectionTask.WAITING, _, waitingData2: WaitingData) = monitor.expectMsgType[TransitionWithData](60 seconds)
    assert(waitingData2.nextReconnectionDelay == (waitingData0.nextReconnectionDelay * 4))

    probe.send(reconnectionTask, ReconnectionTask.TickReconnect)
    val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.CONNECTING, _, _) = monitor.expectMsgType[TransitionWithData]
    // connection finally succeeds
    peer.send(reconnectionTask, Peer.Transition(PeerDisconnectedData, PeerConnectedData))
    val TransitionWithData(ReconnectionTask.CONNECTING, ReconnectionTask.IDLE, _, _) = monitor.expectMsgType[TransitionWithData]

    // we are disconnected one more time
    peer.send(reconnectionTask, Peer.Transition(PeerConnectedData, PeerDisconnectedData))

    // the auto reconnect kicks off again, but this time we pick up the reconnect delay where we left it
    val TransitionWithData(ReconnectionTask.IDLE, ReconnectionTask.WAITING, _, waitingData3: WaitingData) = monitor.expectMsgType[TransitionWithData]
    assert(waitingData3.nextReconnectionDelay == (waitingData0.nextReconnectionDelay * 8))
  }

  test("all kind of connection failures should be caught by the reconnection task", Tag("auto_reconnect")) { f =>
    import f._

    val peer = TestProbe()
    nodeParams.db.peers.addOrUpdatePeer(remoteNodeId, fakeIPAddress)
    peer.send(reconnectionTask, Peer.Transition(PeerNothingData, PeerDisconnectedData))
    val TransitionWithData(ReconnectionTask.IDLE, ReconnectionTask.WAITING, _, _) = monitor.expectMsgType[TransitionWithData]
    val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.CONNECTING, _, connectingData: ReconnectionTask.ConnectingData) = monitor.expectMsgType[TransitionWithData]

    val failures = List(
      PeerConnection.ConnectionResult.ConnectionFailed(connectingData.to),
      PeerConnection.ConnectionResult.NoAddressFound,
      PeerConnection.ConnectionResult.InitializationFailed("incompatible features"),
      PeerConnection.ConnectionResult.AuthenticationFailed("authentication timeout")
    )

    failures.foreach { failure =>
      // we simulate a connection error
      reconnectionTask ! failure
      // a new reconnection task will be scheduled
      val TransitionWithData(ReconnectionTask.CONNECTING, ReconnectionTask.WAITING, _, _) = monitor.expectMsgType[TransitionWithData]
      // we send the tick manually so we don't wait
      reconnectionTask ! ReconnectionTask.TickReconnect
      // this triggers a reconnection
      val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.CONNECTING, _, _) = monitor.expectMsgType[TransitionWithData]
    }
  }

  test("concurrent incoming/outgoing reconnection", Tag("auto_reconnect")) { f =>
    import f._

    val peer = TestProbe()
    nodeParams.db.peers.addOrUpdatePeer(remoteNodeId, fakeIPAddress)
    peer.send(reconnectionTask, Peer.Transition(PeerNothingData, PeerDisconnectedData))
    val TransitionWithData(ReconnectionTask.IDLE, ReconnectionTask.WAITING, _, _) = monitor.expectMsgType[TransitionWithData]
    val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.CONNECTING, _, _: ReconnectionTask.ConnectingData) = monitor.expectMsgType[TransitionWithData]

    // at this point, we are attempting to connect to the peer
    // let's assume that an incoming connection arrives from the peer right before our outgoing connection, but we haven't
    // yet received the peer transition
    val peerConnection = TestProbe()
    reconnectionTask ! PeerConnection.ConnectionResult.AlreadyConnected(peerConnection.ref, peer.ref)
    // we will schedule a reconnection
    val TransitionWithData(ReconnectionTask.CONNECTING, ReconnectionTask.WAITING, _, _) = monitor.expectMsgType[TransitionWithData]
    // but immediately after that we finally get notified that the peer is connected
    peer.send(reconnectionTask, Peer.Transition(PeerDisconnectedData, PeerConnectedData))
    // we cancel the reconnection and go to idle state
    val TransitionWithData(ReconnectionTask.WAITING, ReconnectionTask.IDLE, _, _) = monitor.expectMsgType[TransitionWithData]

  }

  test("reconnect using the address from node_announcement") { f =>
    import f._

    // we create a dummy tcp server and update bob's announcement to point to it
    val (mockServer, serverAddress) = PeerSpec.createMockServer()
    val mockAddress = NodeAddress.fromParts(serverAddress.getHostName, serverAddress.getPort).get
    val bobAnnouncement = NodeAnnouncement(randomBytes64(), Features.empty, 1 unixsec, remoteNodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", mockAddress :: Nil)
    nodeParams.db.network.addNode(bobAnnouncement)

    val peer = TestProbe()
    // we have auto-reconnect=false so we need to manually tell the peer to reconnect
    peer.send(reconnectionTask, Peer.Connect(remoteNodeId, None, peer.ref, isPersistent = true))

    // assert our mock server got an incoming connection (the client was spawned with the address from node_announcement)
    awaitCond(mockServer.accept() != null, max = 60 seconds, interval = 1 second)
    mockServer.close()
  }

  test("select peer address for reconnection") { () =>
    val nodeParams = mock[NodeParams]
    val clearnet = NodeAddress.fromParts("1.2.3.4", 9735).get
    val tor = NodeAddress.fromParts("iq7zhmhck54vcax2vlrdcavq2m32wao7ekh6jyeglmnuuvv3js57r4id.onion", 9735).get

    // NB: we don't test randomization here, but it makes tests unnecessary more complex for little value

    {
      // tor not supported: always return clearnet addresses
      nodeParams.socksProxy_opt returns None
      assert(ReconnectionTask.selectNodeAddress(nodeParams, List(clearnet)) == Some(clearnet))
      assert(ReconnectionTask.selectNodeAddress(nodeParams, List(tor)) == None)
      assert(ReconnectionTask.selectNodeAddress(nodeParams, List(clearnet, tor)) == Some(clearnet))
    }

    {
      // tor supported but not enabled for clearnet addresses: return clearnet addresses when available
      val socksParams = mock[Socks5ProxyParams]
      socksParams.useForTor returns true
      socksParams.useForIPv4 returns false
      socksParams.useForIPv6 returns false
      nodeParams.socksProxy_opt returns Some(socksParams)
      assert(ReconnectionTask.selectNodeAddress(nodeParams, List(clearnet)) == Some(clearnet))
      assert(ReconnectionTask.selectNodeAddress(nodeParams, List(tor)) == Some(tor))
      assert(ReconnectionTask.selectNodeAddress(nodeParams, List(clearnet, tor)) == Some(clearnet))
    }

    {
      // tor supported and enabled for clearnet addresses: return tor addresses when available
      val socksParams = mock[Socks5ProxyParams]
      socksParams.useForTor returns true
      socksParams.useForIPv4 returns true
      socksParams.useForIPv6 returns true
      nodeParams.socksProxy_opt returns Some(socksParams)
      assert(ReconnectionTask.selectNodeAddress(nodeParams, List(clearnet)) == Some(clearnet))
      assert(ReconnectionTask.selectNodeAddress(nodeParams, List(tor)) == Some(tor))
      assert(ReconnectionTask.selectNodeAddress(nodeParams, List(clearnet, tor)) == Some(tor))
    }

  }

}
