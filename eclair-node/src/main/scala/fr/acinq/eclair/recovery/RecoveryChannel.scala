package fr.acinq.eclair.recovery

import akka.actor.{ActorRef, FSM}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.recovery.RecoveryChannel._

import scala.concurrent.ExecutionContext

class RecoveryChannel(val nodeParams: NodeParams, switchboard: ActorRef, val wallet: EclairWallet, blockchain: ActorRef, relayer: ActorRef, origin_opt: Option[ActorRef] = None)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends FSM[State, Data] {

  startWith(WAIT_FOR_CONNECTION, Empty)

  when(WAIT_FOR_CONNECTION){
    case Event(Connect(nodeURI: NodeURI), Empty) =>
      switchboard ! Peer.Connect(nodeURI.nodeId, Some(nodeURI.address))
      stay
  }

}

object RecoveryChannel {

  sealed trait State
  case object WAIT_FOR_CONNECTION extends State

  sealed trait Data
  case object Empty extends Data

  sealed trait Event
  case class Connect(remote: NodeURI)
}