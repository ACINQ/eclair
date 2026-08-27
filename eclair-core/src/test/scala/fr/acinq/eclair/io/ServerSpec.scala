/*
 * Copyright 2026 ACINQ SAS
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

import akka.Done
import akka.actor.ActorRef
import akka.testkit.TestProbe
import fr.acinq.eclair.{TestConstants, TestKitBaseClass, TestUtils, randomKey}
import org.scalatest.concurrent.Eventually
import org.scalatest.funsuite.AnyFunSuiteLike

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}

/**
 * We drive the [[Server]] through real TCP connections: this lets us verify what peers actually observe (in
 * particular whether their connection was closed), which is the behavior we care about.
 */
class ServerSpec extends TestKitBaseClass with AnyFunSuiteLike with Eventually {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 30 seconds, interval = 100 millis)

  private val nodeParams = TestConstants.Alice.nodeParams

  private def defaultConf: PeerConnection.Conf = nodeParams.peerConnectionConf.copy(
    maxPendingIncomingConnections = 2,
    pendingConnectionMinAge = 1 minute,
    pendingConnectionAcceptDelay = 10 millis,
  )

  private def withServer(conf: PeerConnection.Conf)(f: (ActorRef, Int) => Unit): Unit = {
    val port = TestUtils.availablePort
    val bound = Promise[Done]()
    val server = system.actorOf(Server.props(nodeParams.keyPair, conf, TestProbe().ref, TestProbe().ref, new InetSocketAddress("127.0.0.1", port), Some(bound)))
    Await.result(bound.future, 10 seconds)
    try {
      f(server, port)
    } finally {
      system.stop(server)
    }
  }

  /** Open a real TCP connection to our server, without sending anything: we never complete the BOLT 8 handshake. */
  private def connect(port: Int): SocketChannel = {
    val channel = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))
    channel.configureBlocking(false)
    channel
  }

  /** Whether our peer closed the connection: `read` returns -1 after a TCP FIN and throws after a TCP RST. */
  private def isDisconnected(channel: SocketChannel): Boolean = {
    try {
      channel.read(ByteBuffer.allocate(16)) < 0
    } catch {
      case _: IOException => true
    }
  }

  private def pendingConnections(server: ActorRef): Set[ActorRef] = {
    val probe = TestProbe()
    probe.send(server, Server.GetPendingConnections(probe.ref))
    probe.expectMsgType[Server.PendingConnections].peerConnections
  }

  /** Connect and wait until the server has accepted that connection. */
  private def connectAndWait(server: ActorRef, port: Int, expectedPending: Int): (SocketChannel, Set[ActorRef]) = {
    val channel = connect(port)
    eventually {
      assert(pendingConnections(server).size == expectedPending)
    }
    (channel, pendingConnections(server))
  }

  test("track incoming connections that haven't authenticated") {
    withServer(defaultConf.copy(maxPendingIncomingConnections = 3)) { (server, port) =>
      assert(pendingConnections(server).isEmpty)
      val (_, pending1) = connectAndWait(server, port, 1)
      val (_, pending2) = connectAndWait(server, port, 2)
      assert(pending1.subsetOf(pending2))
    }
  }

  test("drop the oldest pending connection when reaching the limit") {
    // We disable the grace period: pending connections can be dropped immediately.
    withServer(defaultConf.copy(maxPendingIncomingConnections = 2, pendingConnectionMinAge = 0 millis)) { (server, port) =>
      val (channel1, pending1) = connectAndWait(server, port, 1)
      val oldest = pending1.head
      val (channel2, _) = connectAndWait(server, port, 2)
      val probe = TestProbe()
      probe.watch(oldest)
      // We're at capacity: this connection evicts the oldest pending one.
      val channel3 = connect(port)
      probe.expectTerminated(oldest, 10 seconds)
      eventually {
        val pending = pendingConnections(server)
        assert(pending.size == 2)
        assert(!pending.contains(oldest))
      }
      // The peer whose connection was dropped sees it closed, the two others are still connected.
      eventually {
        assert(isDisconnected(channel1))
      }
      assert(!isDisconnected(channel2))
      assert(!isDisconnected(channel3))
    }
  }

  test("reject incoming connections when every pending connection is too recent") {
    withServer(defaultConf.copy(maxPendingIncomingConnections = 2, pendingConnectionMinAge = 1 minute)) { (server, port) =>
      val (channel1, _) = connectAndWait(server, port, 1)
      val (channel2, pending) = connectAndWait(server, port, 2)
      // We're at capacity and no pending connection is old enough to be dropped: we reject the incoming connection
      // instead, which protects the peers that are already busy authenticating.
      val channel3 = connect(port)
      eventually {
        assert(isDisconnected(channel3))
      }
      assert(pendingConnections(server) == pending)
      assert(!isDisconnected(channel1))
      assert(!isDisconnected(channel2))
    }
  }

  test("free up a slot when a connection is authenticated") {
    withServer(defaultConf.copy(maxPendingIncomingConnections = 1, pendingConnectionMinAge = 1 minute)) { (server, port) =>
      val (channel1, pending) = connectAndWait(server, port, 1)
      val probe = TestProbe()
      // Once authenticated, a connection doesn't count towards the limit anymore.
      probe.send(server, PeerConnection.Authenticated(pending.head, randomKey().publicKey, outgoing = false))
      eventually {
        assert(pendingConnections(server).isEmpty)
      }
      // We can thus accept another incoming connection, even though the previous one is still alive.
      val (channel2, _) = connectAndWait(server, port, 1)
      assert(!isDisconnected(channel1))
      assert(!isDisconnected(channel2))
    }
  }

  test("free up a slot when a pending connection dies") {
    withServer(defaultConf.copy(maxPendingIncomingConnections = 1, pendingConnectionMinAge = 1 minute)) { (server, port) =>
      val (channel1, _) = connectAndWait(server, port, 1)
      channel1.close()
      eventually {
        assert(pendingConnections(server).isEmpty)
      }
      val (channel2, _) = connectAndWait(server, port, 1)
      assert(!isDisconnected(channel2))
    }
  }

  test("accept all incoming connections when the limit is disabled") {
    withServer(defaultConf.copy(maxPendingIncomingConnections = 0)) { (server, port) =>
      val channels = (1 to 4).map(_ => connect(port))
      eventually {
        assert(pendingConnections(server).size == 4)
      }
      channels.foreach(channel => assert(!isDisconnected(channel)))
    }
  }

  test("delay accepting incoming connections after dropping one") {
    val acceptDelay = 3.seconds
    withServer(defaultConf.copy(maxPendingIncomingConnections = 1, pendingConnectionMinAge = 0 millis, pendingConnectionAcceptDelay = acceptDelay)) { (server, port) =>
      val (_, pending1) = connectAndWait(server, port, 1)
      // This connection evicts the previous one, after which we stop accepting connections for a while.
      connect(port)
      eventually {
        assert(pendingConnections(server) != pending1)
      }
      val pending2 = pendingConnections(server)
      // The next connection attempt waits in the kernel backlog instead of consuming our resources.
      connect(port)
      // NB: expectNoMessage dilates durations by the test time factor, but the server's delay isn't dilated.
      TestProbe().expectNoMessage((acceptDelay.toMillis * 2 / 3 / testKitSettings.TestTimeFactor).toLong.millis)
      assert(pendingConnections(server) == pending2)
      // But we do accept it once the delay has elapsed.
      eventually {
        assert(pendingConnections(server) != pending2)
      }
    }
  }

}
