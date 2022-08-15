package fr.acinq.eclair.integration.basic.fixtures.composite

import akka.actor.ActorSystem
import akka.testkit.TestKit
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture

case class TwoNodesFixture private(system: ActorSystem,
                                   alice: MinimalNodeFixture,
                                   bob: MinimalNodeFixture) {
  implicit val implicitSystem: ActorSystem = system

  def cleanup(): Unit = {
    TestKit.shutdownActorSystem(alice.system)
    TestKit.shutdownActorSystem(bob.system)
    TestKit.shutdownActorSystem(system)
  }
}

object TwoNodesFixture {
  def apply(aliceParams: NodeParams, bobParams: NodeParams): TwoNodesFixture = {
    TwoNodesFixture(
      system = ActorSystem("system-test"),
      alice = MinimalNodeFixture(aliceParams),
      bob = MinimalNodeFixture(bobParams)
    )
  }
}
