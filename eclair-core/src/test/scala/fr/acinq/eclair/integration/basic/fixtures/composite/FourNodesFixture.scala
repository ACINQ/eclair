package fr.acinq.eclair.integration.basic.fixtures.composite

import akka.actor.ActorSystem
import akka.testkit.TestKit
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.integration.basic.fixtures.{FixtureUtils, MinimalNodeFixture}

case class FourNodesFixture private(system: ActorSystem,
                                    alice: MinimalNodeFixture,
                                    bob: MinimalNodeFixture,
                                    carol: MinimalNodeFixture,
                                    dave: MinimalNodeFixture) {
  implicit val implicitSystem: ActorSystem = system

  def cleanup(): Unit = {
    TestKit.shutdownActorSystem(alice.system)
    TestKit.shutdownActorSystem(bob.system)
    TestKit.shutdownActorSystem(carol.system)
    TestKit.shutdownActorSystem(dave.system)
    TestKit.shutdownActorSystem(system)
  }
}

object FourNodesFixture {
  def apply(aliceParams: NodeParams, bobParams: NodeParams, carolParams: NodeParams, daveParams: NodeParams, testName: String): FourNodesFixture = {
    FourNodesFixture(
      system = ActorSystem("system-test", FixtureUtils.actorSystemConfig(testName)),
      alice = MinimalNodeFixture(aliceParams, testName),
      bob = MinimalNodeFixture(bobParams, testName),
      carol = MinimalNodeFixture(carolParams, testName),
      dave = MinimalNodeFixture(daveParams, testName),
    )
  }
}
