package fr.acinq.eclair.integration.basic.fixtures.composite

import akka.actor.ActorSystem
import akka.testkit.TestKit
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.integration.basic.fixtures.{FixtureUtils, MinimalNodeFixture}


case class ThreeNodesFixture private(system: ActorSystem,
                                     alice: MinimalNodeFixture,
                                     bob: MinimalNodeFixture,
                                     carol: MinimalNodeFixture) {
  implicit val implicitSystem: ActorSystem = system

  def cleanup(): Unit = {
    TestKit.shutdownActorSystem(alice.system)
    TestKit.shutdownActorSystem(bob.system)
    TestKit.shutdownActorSystem(carol.system)
    TestKit.shutdownActorSystem(system)
  }
}

object ThreeNodesFixture {
  def apply(aliceParams: NodeParams, bobParams: NodeParams, carolParams: NodeParams, testName: String): ThreeNodesFixture = {
    ThreeNodesFixture(
      system = ActorSystem("system-test", FixtureUtils.actorSystemConfig(testName)),
      alice = MinimalNodeFixture(aliceParams, testName),
      bob = MinimalNodeFixture(bobParams, testName),
      carol = MinimalNodeFixture(carolParams, testName),
    )
  }
}