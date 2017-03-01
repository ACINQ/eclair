package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging, ActorRef}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.db.Dbs
import fr.acinq.eclair.wire.{LightningMessage, Error}

/**
  * Created by fabrice on 27/02/17.
  */

case class StoreAndForward(previousState: State, nextState: State, previousData: Data, currentData: Data)

class Forwarder(nodeParams: NodeParams) extends Actor with ActorLogging {

  def receive = {
    case destination: ActorRef => context become main(destination)
  }

  def main(destination: ActorRef): Receive = {

    case destination: ActorRef => context become main(destination)

    case error: Error => destination ! error

    case StoreAndForward(previousState, nextState, previousData, nextData) =>
      val outgoing = Forwarder.extractOutgoingMessages(previousState, nextState, previousData, nextData)
      if (outgoing.size > 0) {
        log.debug(s"sending ${outgoing.map(_.getClass.getSimpleName).mkString(" ")}")
        outgoing.foreach(destination forward _)
      }
      val (previousId, nextId) = (Helpers.getChannelId(previousData), Helpers.getChannelId(nextData))
      nodeParams.channelsDb.put(nextId, nextData)
      if (previousId != nextId) {
        nodeParams.channelsDb.delete(previousId)
      }
  }
}

object Forwarder {

  def extractOutgoingMessages(previousState: State, nextState: State, previousData: Data, currentData: Data): Seq[LightningMessage] = {
    previousState match {
      case OFFLINE =>
        (previousData, currentData) match {
          case (_, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => d.lastSent.right.toSeq // NB: if we re-send the message and the other party didn't receive it they will return an error (see #120)
          case (_, d: DATA_WAIT_FOR_FUNDING_LOCKED) => d.lastSent :: Nil
          case (_, d: DATA_WAIT_FOR_ANN_SIGNATURES) => d.lastSent :: Nil
          case (_: HasCommitments, d2: HasCommitments)=> d2.commitments.unackedMessages
          case _ => Nil
        }
      case _ =>
        (previousData, currentData) match {
          case (_, Nothing) => Nil
          case (_, d: DATA_WAIT_FOR_OPEN_CHANNEL) => Nil
          case (_, d: DATA_WAIT_FOR_ACCEPT_CHANNEL) => d.lastSent :: Nil
          case (_, d: DATA_WAIT_FOR_FUNDING_INTERNAL) => Nil
          case (_, d: DATA_WAIT_FOR_FUNDING_CREATED) => d.lastSent :: Nil
          case (_, d: DATA_WAIT_FOR_FUNDING_SIGNED) => d.lastSent :: Nil
          case (_, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => d.lastSent.right.toOption.map(_ :: Nil).getOrElse(Nil)
          case (_, d: DATA_WAIT_FOR_FUNDING_LOCKED) => d.lastSent :: Nil
          case (_, d: DATA_WAIT_FOR_ANN_SIGNATURES) => d.lastSent :: Nil
          case (_, d: DATA_CLOSING) => Nil
          case (d1: HasCommitments, d2: HasCommitments) => d2.commitments.unackedMessages diff d1.commitments.unackedMessages
          case (_, _: HasCommitments) => ??? // eg: goto(CLOSING)
        }
    }
  }

}
