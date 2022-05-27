package fr.acinq.eclair.integration.basic.fixtures

import akka.actor.ActorSystem
import akka.testkit.TestKit
import fr.acinq.eclair.NodeParams


case class ThreeNodesFixture(system: ActorSystem,
                             alice: MinimalNodeFixture,
                             bob: MinimalNodeFixture,
                             charlie: MinimalNodeFixture) {
  implicit val implicitSystem: ActorSystem = system

  def cleanup(): Unit = {
    TestKit.shutdownActorSystem(alice.system)
    TestKit.shutdownActorSystem(bob.system)
    TestKit.shutdownActorSystem(charlie.system)
    TestKit.shutdownActorSystem(system)
  }
}

object ThreeNodesFixture {
  def apply(aliceParams: NodeParams, bobParams: NodeParams, charlieParams: NodeParams): ThreeNodesFixture = {
    ThreeNodesFixture(
      system = ActorSystem("system-test"),
      alice = MinimalNodeFixture(aliceParams),
      bob = MinimalNodeFixture(bobParams),
      charlie = MinimalNodeFixture(charlieParams),
    )
  }
}