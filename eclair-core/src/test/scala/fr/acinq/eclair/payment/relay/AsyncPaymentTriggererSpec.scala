package fr.acinq.eclair.payment.relay

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.channel.{CMD_GET_CHANNEL_STATE, NEGOTIATING, RES_GET_CHANNEL_STATE}
import fr.acinq.eclair.{BlockHeight, TestConstants, randomKey}
import fr.acinq.eclair.payment.relay.AsyncPaymentTriggerer._
import fr.acinq.eclair.io.{Peer, PeerConnected, Switchboard}
import fr.acinq.eclair.io.Switchboard.GetPeerInfo
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class AsyncPaymentTriggererSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  case class FixtureParam(remoteNodeId: PublicKey, switchboard: TestProbe[Switchboard.GetPeerInfo], channelProbes: Seq[TestProbe[CMD_GET_CHANNEL_STATE]], probe: TestProbe[Result], triggerer: ActorRef[Command]) {
    def channels: Set[akka.actor.ActorRef] = channelProbes.map(_.ref.toClassic).toSet
  }

  override def withFixture(test: OneArgTest): Outcome = {
    val remoteNodeId = TestConstants.Alice.nodeParams.nodeId
    val switchboard = TestProbe[Switchboard.GetPeerInfo]("switchboard")
    val channelProbes = Seq(TestProbe[CMD_GET_CHANNEL_STATE]("channel1"), TestProbe[CMD_GET_CHANNEL_STATE]("channel2"))
    val probe = TestProbe[Result]()
    val triggerer = testKit.spawn(AsyncPaymentTriggerer())
    triggerer ! Start(switchboard.ref)
    withFixture(test.toNoArgTest(FixtureParam(remoteNodeId, switchboard, channelProbes, probe, triggerer)))
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
    probe.expectNoMessage()
  }

  test("duplicate watches should emit only one trigger") { f =>
    import f._

    // create two identical watches
    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    assert(switchboard.expectMessageType[GetPeerInfo].remoteNodeId == remoteNodeId)
    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    switchboard.expectNoMessage()

    // We trigger one timeout messages when we reach the timeout
    system.eventStream ! EventStream.Publish(CurrentBlockHeight(BlockHeight(100)))
    probe.expectMessage(AsyncPaymentTimeout)
    probe.expectNoMessage(100 millis)

    // create two different watches
    val probe2 = TestProbe[Result]()
    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    assert(switchboard.expectMessageType[GetPeerInfo].remoteNodeId == remoteNodeId)
    triggerer ! Watch(probe2.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    switchboard.expectNoMessage()

    // We get two timeout messages when we reach the timeout
    system.eventStream ! EventStream.Publish(CurrentBlockHeight(BlockHeight(100)))
    probe.expectMessage(AsyncPaymentTimeout)
    probe2.expectMessage(AsyncPaymentTimeout)
  }

  test("remote node connects before timeout") { f =>
    import f._

    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    val request1 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request1.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.DISCONNECTED, None, channels)
    channelProbes.head.expectNoMessage(100 millis)

    // An unrelated peer connects.
    system.eventStream ! EventStream.Publish(PeerConnected(TestProbe().ref.toClassic, randomKey().publicKey, null))
    switchboard.expectNoMessage(100 millis)
    probe.expectNoMessage()

    // The target peer connects.
    system.eventStream ! EventStream.Publish(PeerConnected(TestProbe().ref.toClassic, remoteNodeId, null))
    val request2 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request2.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.CONNECTED, None, channels)
    channelProbes.foreach(_.expectMessageType[CMD_GET_CHANNEL_STATE].replyTo ! RES_GET_CHANNEL_STATE(NEGOTIATING))
    probe.expectMessage(AsyncPaymentTriggered)

    // Only get the trigger message once.
    system.eventStream ! EventStream.Publish(PeerConnected(TestProbe().ref.toClassic, remoteNodeId, null))
    switchboard.expectNoMessage()
    probe.expectNoMessage()
  }

  test("remote node connects after one watch timeout and before another") { f =>
    import f._

    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    val request1 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request1.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.DISCONNECTED, None, channels)
    channelProbes.head.expectNoMessage(100 millis)

    // Another async payment node relay watches the peer
    val probe2 = TestProbe[Result]()
    triggerer ! Watch(probe2.ref, remoteNodeId, paymentHash = ByteVector32.One, timeout = BlockHeight(101))

    // First watch times out
    system.eventStream ! EventStream.Publish(CurrentBlockHeight(BlockHeight(100)))
    probe.expectMessage(AsyncPaymentTimeout)

    // Second watch succeeds
    system.eventStream ! EventStream.Publish(PeerConnected(TestProbe().ref.toClassic, remoteNodeId, null))
    val request2 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request2.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.CONNECTED, None, channels)
    channelProbes.foreach(_.expectMessageType[CMD_GET_CHANNEL_STATE].replyTo ! RES_GET_CHANNEL_STATE(NEGOTIATING))
    probe.expectNoMessage()
    probe2.expectMessage(AsyncPaymentTriggered)
  }

  test("watch two nodes, one connects and the other times out") { f =>
    import f._

    // watch remote node
    triggerer ! Watch(probe.ref, remoteNodeId, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(100))
    val request1 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request1.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.DISCONNECTED, None, channels)
    channelProbes.head.expectNoMessage(100 millis)

    // watch another remote node
    val remoteNodeId2 = TestConstants.Bob.nodeParams.nodeId
    val probe2 = TestProbe[Result]()
    triggerer ! Watch(probe2.ref, remoteNodeId2, paymentHash = ByteVector32.Zeroes, timeout = BlockHeight(101))
    val request2 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request2.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId, Peer.DISCONNECTED, None, channels)
    channelProbes.head.expectNoMessage(100 millis)

    // First remote node times out
    system.eventStream ! EventStream.Publish(CurrentBlockHeight(BlockHeight(100)))
    probe.expectMessage(AsyncPaymentTimeout)

    // First remote node connects, but does not trigger expired watch
    system.eventStream ! EventStream.Publish(PeerConnected(TestProbe().ref.toClassic, remoteNodeId, null))
    switchboard.expectNoMessage()

    // Second remote node connects and triggers watch
    system.eventStream ! EventStream.Publish(PeerConnected(TestProbe().ref.toClassic, remoteNodeId2, null))
    val request3 = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    request3.replyTo ! Peer.PeerInfo(TestProbe().ref.toClassic, remoteNodeId2, Peer.CONNECTED, None, channels)
    channelProbes.foreach(_.expectMessageType[CMD_GET_CHANNEL_STATE].replyTo ! RES_GET_CHANNEL_STATE(NEGOTIATING))
    probe.expectNoMessage()
    probe2.expectMessage(AsyncPaymentTriggered)
  }
}
