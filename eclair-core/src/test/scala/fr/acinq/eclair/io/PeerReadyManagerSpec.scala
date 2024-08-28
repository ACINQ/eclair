/*
 * Copyright 2024 ACINQ SAS
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
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.randomKey
import org.scalatest.funsuite.AnyFunSuiteLike

class PeerReadyManagerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {

  test("watch pending notifiers") {
    val manager = testKit.spawn(PeerReadyManager())
    val remoteNodeId1 = randomKey().publicKey
    val notifier1a = TestProbe[PeerReadyManager.Registered]()
    val notifier1b = TestProbe[PeerReadyManager.Registered]()

    manager ! PeerReadyManager.Register(notifier1a.ref, remoteNodeId1)
    assert(notifier1a.expectMessageType[PeerReadyManager.Registered].otherAttempts == 0)
    manager ! PeerReadyManager.Register(notifier1b.ref, remoteNodeId1)
    assert(notifier1b.expectMessageType[PeerReadyManager.Registered].otherAttempts == 1)

    val remoteNodeId2 = randomKey().publicKey
    val notifier2a = TestProbe[PeerReadyManager.Registered]()
    val notifier2b = TestProbe[PeerReadyManager.Registered]()

    // Later attempts aren't affected by previously completed attempts.
    manager ! PeerReadyManager.Register(notifier2a.ref, remoteNodeId2)
    assert(notifier2a.expectMessageType[PeerReadyManager.Registered].otherAttempts == 0)
    notifier2a.stop()
    val probe = TestProbe[Set[PublicKey]]()
    probe.awaitAssert({
      manager ! PeerReadyManager.List(probe.ref)
      assert(probe.expectMessageType[Set[PublicKey]] == Set(remoteNodeId1))
    })
    manager ! PeerReadyManager.Register(notifier2b.ref, remoteNodeId2)
    assert(notifier2b.expectMessageType[PeerReadyManager.Registered].otherAttempts == 0)
  }

}
