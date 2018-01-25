package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel._
import fr.acinq.eclair.wire.{UpdateFailHtlc, UpdateFailMalformedHtlc, UpdateFulfillHtlc}

class CommandBuffer(nodeParams: NodeParams, register: ActorRef) extends Actor with ActorLogging {

  import CommandBuffer._
  import nodeParams.pendingRelayDb

  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])

  override def receive: Receive = {

    case CommandSend(channelId, htlcId, cmd) =>
      // save command in db
      register forward Register.Forward(channelId, cmd)
      // we also store the preimage in a db (note that this happens *after* forwarding the fulfill to the channel, so we don't add latency)
      pendingRelayDb.addPendingRelay(channelId, htlcId, cmd)

    case CommandAck(channelId, htlcId) =>
      //delete from db
      log.debug(s"fulfill/fail acked for channelId=$channelId htlcId=$htlcId")
      pendingRelayDb.removePendingRelay(channelId, htlcId)

    case ChannelStateChanged(channel, _, _, WAIT_FOR_INIT_INTERNAL | OFFLINE | SYNCING, nextState, d: HasCommitments) =>
      import d.channelId
      // if channel is in a state where it can have pending htlcs, we send them the fulfills we know of
      nextState match {
        case NORMAL | SHUTDOWN | CLOSING =>
          pendingRelayDb.listPendingRelay(channelId) match {
            case Nil => ()
            case msgs =>
              log.info(s"re-sending ${msgs.size} unacked fulfills/fails to channel $channelId")
              msgs.foreach(channel ! _) // they all have commit = false
              // better to sign once instead of after each fulfill
              channel ! CMD_SIGN
          }
        case _ => ()
      }

    case _: ChannelStateChanged => () // ignored

  }

}

object CommandBuffer {

  case class CommandSend(channelId: BinaryData, htlcId: Long, cmd: Command)

  case class CommandAck(channelId: BinaryData, htlcId: Long)

}
