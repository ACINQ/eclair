package fr.acinq.eclair.payment.relay

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.{ActorRef, Behavior}
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.channel.{NEGOTIATING, NEGOTIATING_SIMPLE}
import fr.acinq.eclair.io.Switchboard.GetPeerInfo
import fr.acinq.eclair.io.{Peer, PeerConnected, PeerReadyManager, Switchboard}
import fr.acinq.eclair.payment.relay.AsyncPaymentTriggerer._
import fr.acinq.eclair.{BlockHeight, TestConstants, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class AsyncPaymentTriggererSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  case class FixtureParam(remoteNodeId: PublicKey, switchboard: TestProbe[Switchboard.GetPeerInfo], peer: TestProbe[Peer.GetPeerChannels], probe: TestProbe[Result], triggerer: ActorRef[Command])

  object DummyPeerReadyManager {
    def apply(): Behavior[PeerReadyManager.Command] = {
      Behaviors.receiveMessagePartial {
        case PeerReadyManager.Register(replyTo, remoteNodeId) =>
          replyTo ! PeerReadyManager.Registered(remoteNodeId, otherAttempts = 0)
          Behaviors.same
      }
    }
  }

  override def withFixture(test: OneArgTest): Outcome = {
    val remoteNodeId = TestConstants.Alice.nodeParams.nodeId
    val peerReadyManager = testKit.spawn(DummyPeerReadyManager())
    system.receptionist ! Receptionist.Register(PeerReadyManager.PeerReadyManagerServiceKey, peerReadyManager)
    val switchboard = TestProbe[Switchboard.GetPeerInfo]("switchboard")
    system.receptionist ! Receptionist.Register(Switchboard.SwitchboardServiceKey, switchboard.ref)
    val peer = TestProbe[Peer.GetPeerChannels]("peer")
    val probe = TestProbe[Result]()
    val triggerer = testKit.spawn(AsyncPaymentTriggerer())
    try {
      withFixture(test.toNoArgTest(FixtureParam(remoteNodeId, switchboard, peer, probe, triggerer)))
    } finally {
      system.receptionist ! Receptionist.Deregister(PeerReadyManager.PeerReadyManagerServiceKey, peerReadyManager)
      system.receptionist ! Receptionist.Deregister(Switchboard.SwitchboardServiceKey, switchboard.ref)
    }
  }

  test("remote node does not connect before timeout") { f =>
    import f._

    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    assert(switchboard.expectMessageType[GetPeerInfo].remoteNodeId == remoteNodeId)

    // We haven't reached the timeout yet.
    system.eventStream ! EventStream.Publish(CurrentBlockHeight(BlockHeight(99)))
    probe.expectNoMessage(100 millis)

    // We exceed the timeout (we've missed blocks).
    system.eventStream ! EventStream.Publish(CurrentBlockHeight(BlockHeight(110)))
    probe.expectMessage(AsyncPaymentTimeout)

    // Only get the timeout message once.
    system.eventStream ! EventStream.Publish(CurrentBlockHeight(BlockHeight(111)))
    probe.expectNoMessage(100 millis)
  }

  test("remote node does not connect before sender cancels") { f =>
    import f._

    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    assert(switchboard.expectMessageType[GetPeerInfo].remoteNodeId == remoteNodeId)

    // cancel of an unwatched payment does nothing
    triggerer ! Cancel(ByteVector32.One)
    probe.expectNoMessage(100 millis)

    triggerer ! Cancel(ByteVector32.Zeroes)
    probe.expectMessage(AsyncPaymentCanceled)
  }

  test("duplicate watches should emit only one trigger") { f =>
    import f._

    // create two identical watches
    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    assert(switchboard.expectMessageType[GetPeerInfo].remoteNodeId == remoteNodeId)
    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))

    // We trigger one timeout messages when we reach the timeout
    system.eventStream ! EventStream.Publish(CurrentBlockHeight(BlockHeight(100)))
    probe.expectMessage(AsyncPaymentTimeout)
    probe.expectNoMessage(100 millis)

    // create two different watches
    val probe2 = TestProbe[Result]()
    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    assert(switchboard.expectMessageType[GetPeerInfo].remoteNodeId == remoteNodeId)
    triggerer ! Watch(probe2.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))

    // We get two timeout messages when we reach the timeout
    system.eventStream ! EventStream.Publish(CurrentBlockHeight(BlockHeight(100)))
    probe.expectMessage(AsyncPaymentTimeout)
    probe2.expectMessage(AsyncPaymentTimeout)
  }

  test("all payments with the same payment hash should be canceled") { f =>
    import f._

    // create watches for two payments with the same payment hash
    val probe2 = TestProbe[Result]()
    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    assert(switchboard.expectMessageType[GetPeerInfo].remoteNodeId == remoteNodeId)
    triggerer ! Watch(probe2.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))

    // each payment gets a cancel message when we cancel the payment hash
    triggerer ! Cancel(paymentHash = ByteVector32.Zeroes)
    probe.expectMessage(AsyncPaymentCanceled)
    probe2.expectMessage(AsyncPaymentCanceled)
  }

  test("remote node connects before timeout") { f =>
    import f._

    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    val request1 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request1.replyTo ! Peer.PeerInfo(peer.ref.toClassic, remoteNodeId, Peer.DISCONNECTED, None, None, Set(TestProbe().ref.toClassic))

    // An unrelated peer connects.
    system.eventStream ! EventStream.Publish(PeerConnected(peer.ref.toClassic, randomKey().publicKey, null))
    probe.expectNoMessage(100 millis)

    // The target peer connects.
    system.eventStream ! EventStream.Publish(PeerConnected(peer.ref.toClassic, remoteNodeId, null))
    val request2 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request2.replyTo ! Peer.PeerInfo(peer.ref.toClassic, remoteNodeId, Peer.CONNECTED, None, None, Set(TestProbe().ref.toClassic))
    peer.expectMessageType[Peer.GetPeerChannels].replyTo ! Peer.PeerChannels(remoteNodeId, Seq(Peer.ChannelInfo(null, NEGOTIATING, null)))
    probe.expectMessage(AsyncPaymentTriggered)

    // Only get the trigger message once.
    system.eventStream ! EventStream.Publish(PeerConnected(peer.ref.toClassic, remoteNodeId, null))
    probe.expectNoMessage(100 millis)
  }

  test("remote node connects after one watch timeout and before another") { f =>
    import f._

    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    val request1 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request1.replyTo ! Peer.PeerInfo(peer.ref.toClassic, remoteNodeId, Peer.DISCONNECTED, None, None, Set(TestProbe().ref.toClassic))

    // Another async payment node relay watches the peer
    val probe2 = TestProbe[Result]()
    triggerer ! Watch(probe2.ref, remoteNodeId, paymentHash = ByteVector32.One, timeout = BlockHeight(101))

    // First watch times out
    system.eventStream ! EventStream.Publish(CurrentBlockHeight(BlockHeight(100)))
    probe.expectMessage(AsyncPaymentTimeout)

    // Second watch succeeds
    system.eventStream ! EventStream.Publish(PeerConnected(peer.ref.toClassic, remoteNodeId, null))
    val request2 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request2.replyTo ! Peer.PeerInfo(peer.ref.toClassic, remoteNodeId, Peer.CONNECTED, None, None, Set(TestProbe().ref.toClassic))
    peer.expectMessageType[Peer.GetPeerChannels].replyTo ! Peer.PeerChannels(remoteNodeId, Seq(Peer.ChannelInfo(null, NEGOTIATING_SIMPLE, null)))
    probe.expectNoMessage(100 millis)
    probe2.expectMessage(AsyncPaymentTriggered)
  }

  test("watch two nodes, one connects and the other times out") { f =>
    import f._

    // watch remote node
    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    val request1 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request1.replyTo ! Peer.PeerInfo(peer.ref.toClassic, remoteNodeId, Peer.DISCONNECTED, None, None, Set(TestProbe().ref.toClassic))

    // watch another remote node
    val remoteNodeId2 = TestConstants.Bob.nodeParams.nodeId
    val probe2 = TestProbe[Result]()
    triggerer ! Watch(probe2.ref, remoteNodeId2, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(101))
    val request2 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request2.replyTo ! Peer.PeerInfo(peer.ref.toClassic, remoteNodeId2, Peer.DISCONNECTED, None, None, Set(TestProbe().ref.toClassic))

    // First remote node times out
    system.eventStream ! EventStream.Publish(CurrentBlockHeight(BlockHeight(100)))
    probe.expectMessage(AsyncPaymentTimeout)

    // Second remote node connects and triggers watch
    system.eventStream ! EventStream.Publish(PeerConnected(peer.ref.toClassic, remoteNodeId2, null))
    val request3 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    assert(request3.remoteNodeId == remoteNodeId2)
    request3.replyTo ! Peer.PeerInfo(peer.ref.toClassic, remoteNodeId2, Peer.CONNECTED, None, None, Set(TestProbe().ref.toClassic))
    peer.expectMessageType[Peer.GetPeerChannels].replyTo ! Peer.PeerChannels(remoteNodeId, Seq(Peer.ChannelInfo(null, NEGOTIATING, null)))
    probe.expectNoMessage(100 millis)
    probe2.expectMessage(AsyncPaymentTriggered)

    // First remote node connects, but does not trigger expired watch
    system.eventStream ! EventStream.Publish(PeerConnected(peer.ref.toClassic, remoteNodeId, null))
    switchboard.expectNoMessage(100 millis)
  }

  test("triggerer treats an unexpected stop of the notifier as a cancel") { f =>
    import f._

    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    assert(switchboard.expectMessageType[GetPeerInfo].remoteNodeId == remoteNodeId)

    triggerer ! NotifierStopped(remoteNodeId)
    probe.expectMessage(AsyncPaymentCanceled)
  }
}
