package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging, ActorRef}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.wire.{Error, LightningMessage}

/**
  * Created by fabrice on 27/02/17.
  */

case class StoreAndForward(state: State, stateData: Data, outgoing: Seq[LightningMessage])

class Forwarder(nodeParams: NodeParams) extends Actor with ActorLogging {

  // caller is responsible for sending the destination before anything else
  // the general case is that destination can die anytime and it is managed by the caller
  def receive = main(context.system.deadLetters, None)

  def main(destination: ActorRef, temporaryChannelId: Option[BinaryData]): Receive = {

    case destination: ActorRef => context become main(destination, None)

    case error: Error => destination ! error

    case StoreAndForward(CLOSED, stateData: HasCommitments, _) =>
      log.debug(s"deleting database record for channelId=${stateData.channelId}")
      nodeParams.channelsDb.delete(stateData.channelId)

    case StoreAndForward(_, stateData: DATA_WAIT_FOR_FUNDING_PARENT, outgoing) =>
      log.debug(s"updating database record for channelId=${stateData.data.temporaryChannelId}")
      nodeParams.channelsDb.put(stateData.data.temporaryChannelId, stateData)
      outgoing.foreach(destination forward _)
      context become main(destination, Some(stateData.data.temporaryChannelId))

    case StoreAndForward(_, stateData: DATA_WAIT_FOR_FUNDING_CREATED, outgoing) =>
      log.debug(s"updating database record for channelId=${stateData.temporaryChannelId}")
      nodeParams.channelsDb.put(stateData.temporaryChannelId, stateData)
      outgoing.foreach(destination forward _)
      context become main(destination, Some(stateData.temporaryChannelId))

    case StoreAndForward(_, stateData: DATA_WAIT_FOR_FUNDING_SIGNED, outgoing) =>
      log.debug(s"updating database record for channelId=${stateData.channelId}")
      nodeParams.channelsDb.put(stateData.channelId, stateData)
      temporaryChannelId.map(id => nodeParams.channelsDb.delete(id))
      outgoing.foreach(destination forward _)

    case StoreAndForward(_, stateData: HasCommitments, outgoing) =>
      log.debug(s"updating database record for channelId=${stateData.channelId}")
      nodeParams.channelsDb.put(stateData.channelId, stateData)
      temporaryChannelId.map(id => nodeParams.channelsDb.delete(id))
      outgoing.foreach(destination forward _)

    case StoreAndForward(_, _, outgoing) =>
      outgoing.foreach(destination forward _)
  }
}
