package fr.acinq.eclair.balance

import akka.Done
import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.balance.ChannelsListener.{GetChannels, GetChannelsResponse}
import fr.acinq.eclair.channel.ChannelRestored
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.{randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.Promise

class ChannelsListenerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {

  test("channels listener basic test") {
    val ready = Promise[Done]()
    val channelsListener = testKit.spawn(ChannelsListener.apply(ready))

    eventually {
      assert(ready.isCompleted)
    }

    val channel1 = TestProbe[Any]()
    system.eventStream ! EventStream.Publish(ChannelRestored(channel1.ref.toClassic, randomBytes32(), null, randomKey().publicKey, ChannelCodecsSpec.normal))
    val channel2 = TestProbe[Any]()
    system.eventStream ! EventStream.Publish(ChannelRestored(channel2.ref.toClassic, randomBytes32(), null, randomKey().publicKey, ChannelCodecsSpec.normal))

    val probe = TestProbe[GetChannelsResponse]()

    eventually {
      channelsListener ! GetChannels(probe.ref)
      assert(probe.expectMessageType[GetChannelsResponse].channels.size == 2)
    }

    channel2.stop()

    eventually {
      channelsListener ! GetChannels(probe.ref)
      assert(probe.expectMessageType[GetChannelsResponse].channels.size == 1)
    }
  }

}
