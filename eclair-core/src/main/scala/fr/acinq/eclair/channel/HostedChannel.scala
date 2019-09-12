package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM, Props}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.wire.{InvokeHostedChannel, LightningMessage, StateUpdate}
import fr.acinq.eclair.{FSMDiagnosticActorLogging, NodeParams}

import scala.concurrent.ExecutionContext

object HostedChannel {
  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, relayer: ActorRef) = Props(new HostedChannel(nodeParams, remoteNodeId, router, relayer))
}

class HostedChannel(val nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, relayer: ActorRef)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends FSM[State, HostedData] with FSMDiagnosticActorLogging[State, HostedData] {

  val forwarder: ActorRef = context.actorOf(Props(new Forwarder(nodeParams)), "forwarder")

  startWith(OFFLINE, HostedNothing)

  when(OFFLINE) {
    case Event(cmd: CMD_HOSTED_INPUT_RECONNECTED, HostedNothing) =>
      forwarder ! cmd.transport
      goto(WAIT_FOR_INIT_INTERNAL)

    case Event(cmd: CMD_HOSTED_INPUT_RECONNECTED, data: HOSTED_DATA_COMMITMENTS) =>
      forwarder ! cmd.transport
      data.getError match {
        case Some(error) =>
          goto(CLOSED) sending error
        case None =>
          goto(SYNCING)
      }

    case Event(cmd: CMD_REGISTER_HOSTED_SHORT_CHANNEL_ID, HostedNothing) =>
      stay

    case Event(CMD_KILL_IDLE_HOSTED_CHANNELS, _) =>
      stop(FSM.Normal)
  }

  when(WAIT_FOR_INIT_INTERNAL) {
    case Event(_: CMD_HOSTED_INPUT_DISCONNECTED, HostedNothing) =>
      goto(OFFLINE)

    case Event(msg: InvokeHostedChannel, HostedNothing) =>
      stay

    case Event(msg: StateUpdate, HostedNothing) =>
      stay

    case Event(cmd: CMD_REGISTER_HOSTED_SHORT_CHANNEL_ID, HostedNothing) =>
      stay

    case Event(cmd: CMD_INVOKE_HOSTED_CHANNEL, HostedNothing) =>
      stay

    case Event(data: HOSTED_DATA_COMMITMENTS, HostedNothing) =>
      goto(NORMAL) using data
  }

  when(SYNCING) {
    case Event(cmd: CMD_HOSTED_INPUT_DISCONNECTED, _: HOSTED_DATA_COMMITMENTS) =>
      goto(OFFLINE)
  }

  when(NORMAL) {
    case Event(cmd: CMD_HOSTED_INPUT_DISCONNECTED, _: HOSTED_DATA_COMMITMENTS) =>
      goto(OFFLINE)
  }

  implicit def state2mystate(state: FSM.State[fr.acinq.eclair.channel.State, HostedData]): MyState = MyState(state)

  case class MyState(state: FSM.State[fr.acinq.eclair.channel.State, HostedData]) {

    def storing(): FSM.State[fr.acinq.eclair.channel.State, HostedData] = {
      state.stateData match {
        case d: HOSTED_DATA_COMMITMENTS =>
          log.debug(s"updating database record for hosted channelId={}", d.channelId)
          nodeParams.db.hostedChannels.addOrUpdateChannel(d)
          state
        case _ =>
          log.error(s"can't store hosted data=${state.stateData} in state=${state.stateName}")
          state
      }
    }

    def sending(msg: LightningMessage): FSM.State[fr.acinq.eclair.channel.State, HostedData] = {
      forwarder ! msg
      state
    }

  }
}