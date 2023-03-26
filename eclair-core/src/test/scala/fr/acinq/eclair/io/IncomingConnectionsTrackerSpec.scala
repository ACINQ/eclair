/*
 * Copyright 2023 ACINQ SAS
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
import akka.actor.typed.ActorRef
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto
import fr.acinq.eclair.TestConstants.Alice.nodeParams
import fr.acinq.eclair.channel.ChannelOpened
import fr.acinq.eclair.io.Peer.Disconnect
import fr.acinq.eclair.{randomBytes32, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class IncomingConnectionsTrackerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  val connection1: Crypto.PublicKey = randomKey().publicKey
  val connection2: Crypto.PublicKey = randomKey().publicKey

  override def withFixture(test: OneArgTest): Outcome = {
    val nodeParams1 = nodeParams.copy(peerConnectionConf = nodeParams.peerConnectionConf.copy(maxNoChannels = 2))
    val switchboard = TestProbe[Disconnect]()
    val tracker = testKit.spawn(IncomingConnectionsTracker(nodeParams1, switchboard.ref))
    withFixture(test.toNoArgTest(FixtureParam(tracker, switchboard)))
  }

  case class FixtureParam(tracker: ActorRef[IncomingConnectionsTracker.Command], switchboard: TestProbe[Disconnect])

  test("accept new node connections, after limit is reached kill oldest node connection first") { f =>
    import f._

    tracker ! IncomingConnectionsTracker.TrackIncomingConnection(connection1)
    tracker ! IncomingConnectionsTracker.TrackIncomingConnection(connection2)
    tracker ! IncomingConnectionsTracker.TrackIncomingConnection(randomKey().publicKey)
    assert(switchboard.expectMessageType[Disconnect].nodeId === connection1)
    tracker ! IncomingConnectionsTracker.TrackIncomingConnection(randomKey().publicKey)
    assert(switchboard.expectMessageType[Disconnect].nodeId === connection2)
  }

  test("stop tracking a node that disconnects and free space for a new node connection") { f =>
    import f._

    // Track nodes without channels.
    val probe = TestProbe[Int]()
    tracker ! IncomingConnectionsTracker.TrackIncomingConnection(connection1)
    tracker ! IncomingConnectionsTracker.TrackIncomingConnection(connection2)
    eventually {
      tracker ! IncomingConnectionsTracker.CountIncomingConnections(probe.ref)
      probe.expectMessage(2)
    }

    // Untrack a node when it disconnects.
    system.eventStream ! EventStream.Publish(PeerDisconnected(system.deadLetters.toClassic, connection1))
    eventually {
      tracker ! IncomingConnectionsTracker.CountIncomingConnections(probe.ref)
      probe.expectMessage(1)
    }

    // Track a new node connection without disconnecting the oldest node connection.
    tracker ! IncomingConnectionsTracker.TrackIncomingConnection(randomKey().publicKey)
    switchboard.expectNoMessage(100 millis)

    // Track a new node connection and disconnect the oldest node connection.
    tracker ! IncomingConnectionsTracker.TrackIncomingConnection(randomKey().publicKey)
    assert(switchboard.expectMessageType[Disconnect].nodeId === connection2)
  }

  test("stop tracking a node that creates a channel and free space for a new node connection") { f =>
    import f._

    // Track nodes without channels.
    val probe = TestProbe[Int]()
    tracker ! IncomingConnectionsTracker.TrackIncomingConnection(connection1)
    tracker ! IncomingConnectionsTracker.TrackIncomingConnection(connection2)
    eventually {
      tracker ! IncomingConnectionsTracker.CountIncomingConnections(probe.ref)
      probe.expectMessage(2)
    }

    // Untrack a node when a channel with it is confirmed on-chain.
    system.eventStream ! EventStream.Publish(ChannelOpened(system.deadLetters.toClassic, connection1, randomBytes32()))
    eventually {
      tracker ! IncomingConnectionsTracker.CountIncomingConnections(probe.ref)
      probe.expectMessage(1)
    }

    // Track a new node connection without disconnecting the oldest node connection.
    tracker ! IncomingConnectionsTracker.TrackIncomingConnection(randomKey().publicKey)
    switchboard.expectNoMessage(100 millis)

    // Track a new node connection and disconnect the oldest node connection.
    tracker ! IncomingConnectionsTracker.TrackIncomingConnection(randomKey().publicKey)
    assert(switchboard.expectMessageType[Disconnect].nodeId === connection2)
  }

}
