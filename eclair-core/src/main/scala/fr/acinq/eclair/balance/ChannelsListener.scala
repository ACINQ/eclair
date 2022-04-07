package fr.acinq.eclair.balance

import akka.Done
import akka.actor.typed
import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.balance.ChannelsListener._
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.{ChannelPersisted, ChannelRestored, PersistentChannelData}

import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt

object ChannelsListener {

  // @formatter:off
  sealed trait Command
  private final case class ChannelData(channelId: ByteVector32, channel: akka.actor.ActorRef, data: PersistentChannelData) extends Command
  private final case class ChannelDied(channelId: ByteVector32) extends Command
  final case class GetChannels(replyTo: typed.ActorRef[GetChannelsResponse]) extends Command
  final case object SendDummyEvent extends Command
  final case object DummyEvent extends Command
  // @formatter:on

  case class GetChannelsResponse(channels: Map[ByteVector32, PersistentChannelData])

  def apply(ready: Promise[Done]): Behavior[Command] =
    Behaviors.setup { context =>
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelRestored](e => ChannelData(e.channelId, e.channel, e.data)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelPersisted](e => ChannelData(e.channelId, e.channel, e.data)))
      context.system.eventStream ! EventStream.Subscribe(context.self.narrow[DummyEvent.type])
      Behaviors.withTimers { timers =>
        // since subscription is asynchronous, we send a fake event so we know when we are subscribed
        val dummyTimer = "dummy-event"
        timers.startTimerAtFixedRate(dummyTimer, SendDummyEvent, 100 milliseconds)
        Behaviors.receiveMessagePartial {
          case SendDummyEvent =>
            context.system.eventStream ! EventStream.Publish(DummyEvent)
            Behaviors.same
          case DummyEvent =>
            context.log.info("channels listener is ready")
            timers.cancel(dummyTimer)
            context.system.eventStream ! EventStream.Unsubscribe(context.self.narrow[DummyEvent.type])
            ready.success(Done)
            new ChannelsListener(context).running(Map.empty)
        }
      }
    }
}

private class ChannelsListener(context: ActorContext[Command]) {

  private val log = context.log

  def running(channels: Map[ByteVector32, PersistentChannelData]): Behavior[Command] =
    Behaviors.receiveMessage {
      case ChannelData(channelId, channel, data) =>
        Closing.isClosed(data, additionalConfirmedTx_opt = None) match {
          case None =>
            context.watchWith(channel.toTyped, ChannelDied(channelId))
            running(channels + (channelId -> data))
          case _ =>
            // if channel is closed we remove early from the map because we want to minimize the window during which
            // there is potential duplication between on-chain and off-chain amounts (the channel actors stays alive
            // for a few seconds after the channel is closed)
            log.debug("remove channel={} from list (closed)", channelId)
            context.unwatch(channel.toTyped)
            running(channels - channelId)
        }
      case ChannelDied(channelId) =>
        log.debug("remove channel={} from list (died)", channelId)
        running(channels - channelId)
      case GetChannels(replyTo) =>
        replyTo ! GetChannelsResponse(channels)
        Behaviors.same
      case SendDummyEvent => Behaviors.same
      case DummyEvent => Behaviors.same
    }
}
