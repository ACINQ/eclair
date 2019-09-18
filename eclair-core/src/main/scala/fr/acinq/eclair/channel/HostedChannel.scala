package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM, Props}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Satoshi}
import fr.acinq.eclair.wire.{ChannelUpdate, Error, InitHostedChannel, InvokeHostedChannel, LastCrossSignedState, LightningMessage, StateUpdate, UpdateMessage}
import fr.acinq.eclair._
import fr.acinq.eclair.payment.Origin
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IN, OUT}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Try}

object HostedChannel {
  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, relayer: ActorRef) = Props(new HostedChannel(nodeParams, remoteNodeId, router, relayer))
}

class HostedChannel(val nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, relayer: ActorRef)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends FSM[State, HostedData] with FSMDiagnosticActorLogging[State, HostedData] {

  val forwarder: ActorRef = context.actorOf(Props(new Forwarder(nodeParams)), "forwarder")

  startWith(OFFLINE, HostedNothing)

  when(OFFLINE) {
    case Event(data: HOSTED_DATA_COMMITMENTS, HostedNothing) =>
      stay using data

    case Event(cmd: CMD_HOSTED_INPUT_RECONNECTED, HostedNothing) =>
      forwarder ! cmd.transport
      goto(WAIT_FOR_INIT_INTERNAL)

    case Event(cmd: CMD_HOSTED_INPUT_RECONNECTED, data: HOSTED_DATA_COMMITMENTS) =>
      forwarder ! cmd.transport
      data.getError match {
        case Some(error) =>
          goto(CLOSED) sending error
        case None =>
          if (!data.isHost) {
            forwarder ! InvokeHostedChannel(nodeParams.chainHash, data.lastCrossSignedState.refundScriptPubKey)
          }
          goto(SYNCING)
      }
  }

  when(WAIT_FOR_INIT_INTERNAL) {
    case Event(_: CMD_HOSTED_INPUT_DISCONNECTED, _) =>
      goto(OFFLINE)

    // Host flow

    case Event(CMD_HOSTED_MESSAGE(channelId, msg: InvokeHostedChannel), HostedNothing) =>
      if (nodeParams.chainHash != msg.chainHash) {
        val message = InvalidChainHash(channelId, local = nodeParams.chainHash, remote = msg.chainHash).getMessage
        stay sending Error(channelId, message)
      } else if (!Helpers.Closing.isValidFinalScriptPubkey(msg.refundScriptPubKey)) {
        val message = InvalidFinalScript(channelId).getMessage
        stay sending Error(channelId, message)
      } else {
        val init = InitHostedChannel(maxHtlcValueInFlightMsat = nodeParams.maxHtlcValueInFlightMsat,
          htlcMinimumMsat = nodeParams.htlcMinimum, maxAcceptedHtlcs = nodeParams.maxAcceptedHtlcs,
          channelCapacityMsat = 100000000000L msat, liabilityDeadlineBlockdays = 1000,
          minimalOnchainRefundAmountSatoshis = 1000000 sat,
          initialClientBalanceMsat = 50000000L msat)

        stay using HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE(init, msg.refundScriptPubKey) sending init
      }

    case Event(CMD_HOSTED_MESSAGE(channelId, remoteSU: StateUpdate), data: HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) if !data.waitingForShortId =>
      val localLCSS = LastCrossSignedState(data.refundScriptPubKey, initHostedChannel = data.init, blockDay = remoteSU.blockDay,
        localBalanceMsat = data.init.channelCapacityMsat - data.init.initialClientBalanceMsat, remoteBalanceMsat = data.init.initialClientBalanceMsat,
        localUpdates = 0L, remoteUpdates = 0L, incomingHtlcs = Nil, outgoingHtlcs = Nil, remoteSignature = remoteSU.localSigOfRemoteLCSS)

      if (math.abs(remoteSU.blockDay - nodeParams.currentBlockDay) > 1) {
        val message = InvalidBlockDay(channelId, nodeParams.currentBlockDay, remoteSU.blockDay).getMessage
        stay using HostedNothing sending Error(channelId, message)
      } else if (!localLCSS.verifyRemoteSignature(remoteNodeId)) {
        val message = InvalidRemoteStateSignature(channelId, localLCSS.hostedSigHash, remoteSU.localSigOfRemoteLCSS).getMessage
        stay using HostedNothing sending Error(channelId, message)
      } else {
        context.parent ! CMD_REGISTER_HOSTED_SHORT_CHANNEL_ID(channelId, remoteNodeId, localLCSS)
        stay using data.copy(waitingForShortId = true)
      }

    case Event(commits: HOSTED_DATA_COMMITMENTS, data: HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) if data.waitingForShortId =>
      val localSU = commits.lastCrossSignedState.reverse.makeStateUpdate(nodeParams.privateKey)
      goto(NORMAL) using commits sending localSU

    // Client flow

    case Event(cmd: CMD_INVOKE_HOSTED_CHANNEL, HostedNothing) =>
      forwarder ! InvokeHostedChannel(nodeParams.chainHash, cmd.refundScriptPubKey)
      stay using HOSTED_DATA_CLIENT_WAIT_HOST_REPLY(cmd.refundScriptPubKey)

    case Event(CMD_HOSTED_MESSAGE(channelId, msg: InitHostedChannel), data: HOSTED_DATA_CLIENT_WAIT_HOST_REPLY) =>
      Try {
        if (msg.liabilityDeadlineBlockdays < minHostedLiabilityBlockdays) throw new ChannelException(channelId, "Their liability deadline is too low")
        if (msg.initialClientBalanceMsat > msg.channelCapacityMsat) throw new ChannelException(channelId, "Their init balance for us is larger than capacity")
        if (msg.minimalOnchainRefundAmountSatoshis > minHostedOnChainRefund) throw new ChannelException(channelId, "Their minimal on-chain refund is too high")
        if (msg.channelCapacityMsat < minHostedOnChainRefund * 2) throw new ChannelException(channelId, "Their proposed channel capacity is too low")
      } match {
        case Failure(reason) =>
          log.debug(s"unacceptable remote parameters while establishing a hosted channel, reason=${reason.getMessage}")
          goto(CLOSED)

        case _ =>
          val localLCSS = LastCrossSignedState(data.refundScriptPubKey, msg, blockDay = nodeParams.currentBlockDay,
            msg.initialClientBalanceMsat, msg.channelCapacityMsat - msg.initialClientBalanceMsat, localUpdates = 0L,
            remoteUpdates = 0L, incomingHtlcs = Nil, outgoingHtlcs = Nil, remoteSignature = ByteVector64.Zeroes)

          ???
      }
      stay
  }

  when(SYNCING) {
    case Event(_: CMD_HOSTED_INPUT_DISCONNECTED, _: HOSTED_DATA_COMMITMENTS) =>
      goto(OFFLINE)

    case Event(msg: InvokeHostedChannel, data: HOSTED_DATA_COMMITMENTS) =>
      stay

    case Event(remoteSU: StateUpdate, HostedNothing) =>
      stay

    case Event(remoteLCSS: LastCrossSignedState, HostedNothing) =>
      stay
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

  def restoreCommits(localLCSS: LastCrossSignedState, channelId: ByteVector32, isHost: Boolean): HOSTED_DATA_COMMITMENTS = ???
}