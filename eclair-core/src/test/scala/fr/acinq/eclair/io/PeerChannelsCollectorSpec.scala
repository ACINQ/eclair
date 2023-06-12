package fr.acinq.eclair.io

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.PeerChannelsCollector.GetChannels
import fr.acinq.eclair.{randomBytes32, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class PeerChannelsCollectorSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  case class FixtureParam(remoteNodeId: PublicKey, collector: ActorRef[PeerChannelsCollector.Command], probe: TestProbe[Peer.PeerChannels]) {
    def respond(request: CMD_GET_CHANNEL_INFO, channelState: ChannelState): Unit = {
      request.replyTo ! RES_GET_CHANNEL_INFO(request.requestId, remoteNodeId, randomBytes32(), channelState, null)
    }
  }

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
    assert(Set(request1.requestId, request2.requestId, request3.requestId).size == 3)
    respond(request1, NORMAL)
    respond(request2, WAIT_FOR_FUNDING_CONFIRMED)
    probe.expectNoMessage(100 millis) // we don't send a response back until we receive responses from all channels
    respond(request3, CLOSING)
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
    respond(request2, NORMAL)
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
    respond(request2, NORMAL)
    assert(probe.expectMessageType[Peer.PeerChannels].channels.size == 1)

  }

}
