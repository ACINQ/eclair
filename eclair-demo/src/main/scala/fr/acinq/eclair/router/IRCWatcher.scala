package fr.acinq.eclair.router

import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.channel.{ChannelChangedState, DATA_NORMAL, NORMAL}
import grizzled.slf4j.Logging
import org.kitteh.irc.client.library.Client
import org.kitteh.irc.client.library.event.channel.ChannelUsersUpdatedEvent
import org.kitteh.irc.client.library.event.client.ClientConnectedEvent
import org.kitteh.irc.client.library.event.helper.ChannelUserListChangeEvent
import org.kitteh.irc.client.library.event.helper.ChannelUserListChangeEvent.Change
import org.kitteh.irc.client.library.event.user.PrivateMessageEvent
import org.kitteh.irc.lib.net.engio.mbassy.listener.Handler

import scala.util.Random
import scala.collection.JavaConversions._

/**
  * Created by PM on 25/08/2016.
  */
class IRCWatcher extends Actor with ActorLogging {

  val ircChannel = "#eclair-gossip"

  context.system.eventStream.subscribe(self, classOf[ChannelChangedState])
  context.system.eventStream.subscribe(self, classOf[NetworkEvent])

  val client = Client.builder().nick(s"node-${Globals.Node.id.take(8)}").serverHost("irc.freenode.net").build()
  client.getEventManager().registerEventListener(new NodeIRCListener())
  client.addChannel(ircChannel)

  // we can't use channel id as nickname because there would be conflicts (a channel has two ends)
  val rand = new Random()

  override def receive: Receive = main(Map(), Map())

  def main(channels: Map[String, ChannelDesc], localChannels: Map[ActorRef, Client]): Receive = {
    case ChannelChangedState(channel, theirNodeId, _, NORMAL, d: DATA_NORMAL) =>
      val channelDesc = ChannelDesc(d.commitments.anchorId, Globals.Node.publicKey, theirNodeId)
      val channelClient = Client.builder().nick(f"chan-${rand.nextInt(1000000)}%06d").realName(channelDesc.id.toString()).serverHost("irc.freenode.net").build()
      channelClient.getEventManager().registerEventListener(new ChannelIRCListener(channelDesc))
      channelClient.addChannel(ircChannel)
      context become main(channels, localChannels + (channel -> channelClient))

    case ChannelChangedState(channel, theirNodeId, NORMAL, _, _) if localChannels.contains(channel) =>
      localChannels(channel).shutdown()
      context become main(channels, localChannels - channel)

    case ('add, nick: String, desc: ChannelDesc) if !channels.contains(nick) && !channels.values.map(_.id).contains(desc.id)=>
      context.system.eventStream.publish(ChannelDiscovered(desc))
      context become main(channels + (nick -> desc), localChannels)

    case ('remove, nick: String) if channels.contains(nick) =>
      context.system.eventStream.publish(ChannelLost(channels(nick)))
      context become main(channels - nick, localChannels)
  }

}

class NodeIRCListener(implicit context: ActorContext) extends Logging {
  @Handler
  def onClientConnected(event: ClientConnectedEvent) {
    logger.info(s"connected to IRC: ${event.getServerInfo}")
  }

  @Handler
  def onChannelUsersUpdated(event: ChannelUsersUpdatedEvent) {
    logger.debug(s"users updated: $event")
    event.getChannel.getUsers
      .filter(_.getNick.startsWith("chan"))
      .map(chanUser => chanUser.sendMessage("desc"))
  }

  @Handler
  def onChannelUserListChangeEvent(event: ChannelUserListChangeEvent) {
    logger.debug(s"${event.getChange} ${event.getUser}")
    event.getChange match {
      case Change.JOIN if event.getUser.getNick.startsWith("chan") => event.getUser.sendMessage("desc")
      case Change.LEAVE if event.getUser.getNick.startsWith("chan") => context.self ! ('remove, event.getUser.getNick)
      case _ => {}
    }
  }

  val r = """([0-9a-f]{64}): ([0-9a-f]{66})-([0-9a-f]{66})""".r

  @Handler
  def onPrivateMessage(event: PrivateMessageEvent) {
    logger.debug(s"got private message: ${event.getMessage}")
    event.getMessage match {
      case r(id, a, b) => context.self ! ('add, event.getActor.getNick, ChannelDesc(id, a, b))
      case _ => {}
    }
  }
}

class ChannelIRCListener(channelDesc: ChannelDesc) extends Logging {
  @Handler
  def onClientConnected(event: ClientConnectedEvent) {
    logger.info(s"channel=${channelDesc.id} connected to IRC: ${event.getServerInfo}")
  }

  @Handler
  def onPrivateMessage(event: PrivateMessageEvent) {
    logger.debug(s"got private message: ${event.getMessage}")
    event.getMessage match {
      case "desc" => event.sendReply(s"${channelDesc.id}: ${channelDesc.a}-${channelDesc.b}")
      case "kill" => event.getClient.shutdown()
      case _ => {}
    }
  }
}