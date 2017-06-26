package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging, ActorRef}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.wire.{Error, LightningMessage}

/**
  * Created by fabrice on 27/02/17.
  */

class Forwarder(nodeParams: NodeParams) extends Actor with ActorLogging {

  // caller is responsible for sending the destination before anything else
  // the general case is that destination can die anytime and it is managed by the caller
  def receive = main(context.system.deadLetters)

  def main(destination: ActorRef): Receive = {

    case destination: ActorRef => context become main(destination)

    case msg: LightningMessage => destination forward msg

    /*case error: Error => destination ! error

    case StoreAndForward(CLOSED, stateData: HasCommitments, _) =>
      log.debug(s"deleting database record for channelId=${stateData.channelId}")
      nodeParams.channelsDb.delete(stateData.channelId)

    case StoreAndForward(_, stateData: HasCommitments, outgoing) =>
      log.debug(s"updating database record for channelId=${stateData.channelId}")
      nodeParams.channelsDb.put(stateData.channelId, stateData)
      outgoing.foreach(destination forward _)

    case StoreAndForward(_, _, outgoing) =>
      outgoing.foreach(destination forward _)*/
  }
}
