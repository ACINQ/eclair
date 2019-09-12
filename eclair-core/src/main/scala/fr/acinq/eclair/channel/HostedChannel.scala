package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM, Props}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.wire.{InvokeHostedChannel, StateUpdate}
import fr.acinq.eclair.{FSMDiagnosticActorLogging, NodeParams}

import scala.concurrent.ExecutionContext

object HostedChannel {
  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, relayer: ActorRef) = Props(new HostedChannel(nodeParams, remoteNodeId, router, relayer))
}

class HostedChannel(val nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, relayer: ActorRef)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends FSM[State, HostedData] with FSMDiagnosticActorLogging[State, HostedData] {

  val forwarder: ActorRef = context.actorOf(Props(new Forwarder(nodeParams)), "forwarder")

  startWith(WAIT_FOR_INIT_INTERNAL, HostedNothing)

  when(WAIT_FOR_INIT_INTERNAL) {
    case Event(CMD_KILL_IDLE_HOSTED_CHANNELS, HostedNothing) =>
      stop(FSM.Normal)

    case Event(msg: InvokeHostedChannel, HostedNothing) =>
      stay

    case Event(msg: StateUpdate, HostedNothing) =>
      stay

    case Event(cmd: CMD_INVOKE_HOSTED_CHANNEL, HostedNothing) =>
      stay
  }
}