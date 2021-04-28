package fr.acinq.eclair.channel

import akka.actor.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import fr.acinq.eclair.channel.Channel.OutgoingMessage
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.wire.protocol.LightningMessage

object ChannelWriterSender {

  sealed trait Command
  case class ResetPeerConnection(connection: ActorRef) extends Command
  case class Store(d: HasCommitments) extends Command
  case class StoreThenSend(d: HasCommitments, m: LightningMessage) extends Command
  case class Send(m: LightningMessage) extends Command

  def apply(db: ChannelsDb, peer: ActorRef, connection: ActorRef): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case ResetPeerConnection(newConnection) =>
          apply(db, peer, newConnection)
        case Store(d) =>
          db.addOrUpdateChannel(d)
          Behaviors.same
        case StoreThenSend(d, m) =>
          db.addOrUpdateChannel(d)
          peer ! OutgoingMessage(m, connection)
          Behaviors.same
        case Send(m) =>
          peer ! OutgoingMessage(m, connection)
          Behaviors.same
      }
    }

}
