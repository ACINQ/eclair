package fr.acinq.eclair.io

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.PeerChannelsCollector.GetChannels
import fr.acinq.eclair.{randomBytes32, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class PeerChannelsCollectorSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  case class FixtureParam(remoteNodeId: PublicKey, collector: ActorRef[PeerChannelsCollector.Command], probe: TestProbe[Peer.PeerChannels])

  override def withFixture(test: OneArgTest): Outcome = {
    val remoteNodeId = randomKey().publicKey
    val probe = TestProbe[Peer.PeerChannels]()
    val collector = testKit.spawn(PeerChannelsCollector(remoteNodeId))
    withFixture(test.toNoArgTest(FixtureParam(remoteNodeId, collector, probe)))
  }

  test("query multiple channels") { f =>
    import f._

    val (channel1, channel2, channel3) = (TestProbe[CMD_GET_CHANNEL_INFO](), TestProbe[CMD_GET_CHANNEL_INFO](), TestProbe[CMD_GET_CHANNEL_INFO]())
    collector ! GetChannels(probe.ref, Set(channel1, channel2, channel3).map(_.ref))
    val request1 = channel1.expectMessageType[CMD_GET_CHANNEL_INFO]
    val request2 = channel2.expectMessageType[CMD_GET_CHANNEL_INFO]
    val request3 = channel3.expectMessageType[CMD_GET_CHANNEL_INFO]
    request1.replyTo ! RES_GET_CHANNEL_INFO(remoteNodeId, randomBytes32(), channel1.ref.toClassic, NORMAL, null)
    request2.replyTo ! RES_GET_CHANNEL_INFO(remoteNodeId, randomBytes32(), channel2.ref.toClassic, WAIT_FOR_FUNDING_CONFIRMED, null)
    probe.expectNoMessage(100 millis) // we don't send a response back until we receive responses from all channels
    request3.replyTo ! RES_GET_CHANNEL_INFO(remoteNodeId, randomBytes32(), channel3.ref.toClassic, CLOSING, null)
    val peerChannels = probe.expectMessageType[Peer.PeerChannels]
    assert(peerChannels.nodeId == remoteNodeId)
    assert(peerChannels.channels.map(_.state).toSet == Set(NORMAL, WAIT_FOR_FUNDING_CONFIRMED, CLOSING))
  }

  test("channel dies before request") { f =>
    import f._

    val (channel1, channel2) = (TestProbe[CMD_GET_CHANNEL_INFO](), TestProbe[CMD_GET_CHANNEL_INFO]())
    channel1.stop()
    collector ! GetChannels(probe.ref, Set(channel1, channel2).map(_.ref))
    val request2 = channel2.expectMessageType[CMD_GET_CHANNEL_INFO]
    probe.expectNoMessage(100 millis)
    request2.replyTo ! RES_GET_CHANNEL_INFO(remoteNodeId, randomBytes32(), channel2.ref.toClassic, NORMAL, null)
    assert(probe.expectMessageType[Peer.PeerChannels].channels.size == 1)
  }

  test("channel dies after request") { f =>
    import f._

    val (channel1, channel2) = (TestProbe[CMD_GET_CHANNEL_INFO](), TestProbe[CMD_GET_CHANNEL_INFO]())
    collector ! GetChannels(probe.ref, Set(channel1, channel2).map(_.ref))
    channel1.expectMessageType[CMD_GET_CHANNEL_INFO]
    channel1.stop()
    val request2 = channel2.expectMessageType[CMD_GET_CHANNEL_INFO]
    probe.expectNoMessage(100 millis)
    request2.replyTo ! RES_GET_CHANNEL_INFO(remoteNodeId, randomBytes32(), channel2.ref.toClassic, NORMAL, null)
    assert(probe.expectMessageType[Peer.PeerChannels].channels.size == 1)
  }

}
