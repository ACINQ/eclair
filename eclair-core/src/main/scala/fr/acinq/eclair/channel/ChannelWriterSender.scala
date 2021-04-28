package fr.acinq.eclair.channel

import akka.actor.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import fr.acinq.eclair.channel.Channel.OutgoingMessage
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.wire.protocol.LightningMessage

object ChannelWriterSender {

  // @formatter:off
  sealed trait Command
  case class ResetPeerConnection(connection: ActorRef) extends Command
  case class Store(d: HasCommitments) extends Command
  case class StoreThenSend(d: HasCommitments, msgs: Seq[LightningMessage]) extends Command
  case class Send(m: LightningMessage) extends Command
  // @formatter:on

  def apply(db: ChannelsDb, peer: ActorRef, connection: ActorRef): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case ResetPeerConnection(newConnection) =>
          apply(db, peer, newConnection)
        case Store(d) =>
          db.addOrUpdateChannel(d)
          Behaviors.same
        case StoreThenSend(d, msgs) =>
          db.addOrUpdateChannel(d)
          msgs.foreach { m =>
            peer ! OutgoingMessage(m, connection)
          }
          Behaviors.same
        case Send(m) =>
          peer ! OutgoingMessage(m, connection)
          Behaviors.same
      }
    }

}
