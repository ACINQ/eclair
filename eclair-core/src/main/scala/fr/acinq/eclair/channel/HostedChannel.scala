package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM, Props}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.wire.{Error, InitHostedChannel, InvokeHostedChannel, LastCrossSignedState, LightningMessage, StateUpdate}
import fr.acinq.eclair._
import fr.acinq.eclair.payment.ForwardAdd
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IN, OUT}
import scodec.bits.ByteVector

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

    case Event(cmd: CMD_HOSTED_INPUT_RECONNECTED, data: HOSTED_DATA_COMMITMENTS) if !data.isHost =>
      forwarder ! cmd.transport
      data.getError match {
        case Some(error) =>
          goto(CLOSED) sending error
        case None =>
          goto(SYNCING) sending InvokeHostedChannel(nodeParams.chainHash, data.lastCrossSignedState.refundScriptPubKey)
      }
  }

  when(WAIT_FOR_INIT_INTERNAL) {
    case Event(_: CMD_HOSTED_INPUT_DISCONNECTED, _) =>
      goto(OFFLINE)

    // HOST FLOW

    case Event(CMD_HOSTED_MESSAGE(channelId, msg: InvokeHostedChannel), HostedNothing) =>
      if (nodeParams.chainHash != msg.chainHash) {
        val message = InvalidChainHash(channelId, local = nodeParams.chainHash, remote = msg.chainHash).getMessage
        stay sending Error(channelId, message)
      } else if (!Helpers.Closing.isValidFinalScriptPubkey(msg.refundScriptPubKey)) {
        val message = InvalidFinalScript(channelId).getMessage
        stay sending Error(channelId, message)
      } else {
        val init = InitHostedChannel(maxHtlcValueInFlightMsat = nodeParams.maxHtlcValueInFlightMsat,
          htlcMinimumMsat = nodeParams.htlcMinimum, maxAcceptedHtlcs = nodeParams.maxAcceptedHtlcs, channelCapacityMsat = 100000000000L msat,
          liabilityDeadlineBlockdays = 1000, minimalOnchainRefundAmountSatoshis = 1000000 sat, initialClientBalanceMsat = 50000000L msat)

        stay using HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE(init, msg.refundScriptPubKey) sending init
      }

    case Event(CMD_HOSTED_MESSAGE(channelId, remoteSU: StateUpdate), data: HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) if !data.waitingForShortId =>
      val localLCSS = LastCrossSignedState(data.refundScriptPubKey, initHostedChannel = data.init, blockDay = remoteSU.blockDay,
        localBalanceMsat = data.init.channelCapacityMsat - data.init.initialClientBalanceMsat, remoteBalanceMsat = data.init.initialClientBalanceMsat,
        localUpdates = 0L, remoteUpdates = 0L, incomingHtlcs = Nil, outgoingHtlcs = Nil, remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS,
        localSigOfRemote = ByteVector64.Zeroes).withLocalSigOfRemote(nodeParams.privateKey)

      if (math.abs(remoteSU.blockDay - nodeParams.currentBlockDay) > 1) {
        val message = InvalidBlockDay(channelId, nodeParams.currentBlockDay, remoteSU.blockDay).getMessage
        stay using HostedNothing sending Error(channelId, message)
      } else if (!localLCSS.verifyRemoteSig(remoteNodeId)) {
        val message = InvalidRemoteStateSignature(channelId, localLCSS.hostedSigHash, remoteSU.localSigOfRemoteLCSS).getMessage
        stay using HostedNothing sending Error(channelId, message)
      } else {
        val hostedCommits = restoreCommits(localLCSS, channelId, isHost = true)
        context.parent ! CMD_HOSTED_REGISTER_SHORT_CHANNEL_ID(channelId, remoteNodeId, hostedCommits)
        stay using data.copy(waitingForShortId = true)
      }

    case Event(commits: HOSTED_DATA_COMMITMENTS, data: HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) if data.waitingForShortId =>
      goto(NORMAL) using commits sending commits.lastCrossSignedState.stateUpdate

    // CLIENT FLOW

    case Event(cmd: CMD_HOSTED_INVOKE_CHANNEL, HostedNothing) =>
      forwarder ! InvokeHostedChannel(nodeParams.chainHash, cmd.refundScriptPubKey)
      stay using HOSTED_DATA_CLIENT_WAIT_HOST_INIT(cmd.refundScriptPubKey)

    case Event(CMD_HOSTED_MESSAGE(channelId, msg: InitHostedChannel), data: HOSTED_DATA_CLIENT_WAIT_HOST_INIT) =>
      sanityCheck {
        if (msg.liabilityDeadlineBlockdays < minHostedLiabilityBlockdays) throw new ChannelException(channelId, "Their liability deadline is too low")
        if (msg.initialClientBalanceMsat > msg.channelCapacityMsat) throw new ChannelException(channelId, "Their init balance for us is larger than capacity")
        if (msg.minimalOnchainRefundAmountSatoshis > minHostedOnChainRefund) throw new ChannelException(channelId, "Their minimal on-chain refund is too high")
        if (msg.channelCapacityMsat < minHostedOnChainRefund * 2) throw new ChannelException(channelId, "Their proposed channel capacity is too low")
      } {
        val hostedCommits = restoreCommits(LastCrossSignedState(data.refundScriptPubKey, msg, nodeParams.currentBlockDay, msg.initialClientBalanceMsat,
          msg.channelCapacityMsat - msg.initialClientBalanceMsat, localUpdates = 0L, remoteUpdates = 0L, incomingHtlcs = Nil, outgoingHtlcs = Nil,
          localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes).withLocalSigOfRemote(nodeParams.privateKey), channelId, isHost = false)

        stay using HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE(hostedCommits) sending hostedCommits.lastCrossSignedState.stateUpdate
      }

    case Event(CMD_HOSTED_MESSAGE(channelId, remoteSU: StateUpdate), data: HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE) =>
      val localCompleteLCSS = data.hostedDataCommits.lastCrossSignedState.copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)

      sanityCheck {
        val isRightRemoteUpdateNumber = data.hostedDataCommits.lastCrossSignedState.remoteUpdates == remoteSU.localUpdates
        val isRightLocalUpdateNumber = data.hostedDataCommits.lastCrossSignedState.localUpdates == remoteSU.remoteUpdates
        val isBlockdayAcceptable = math.abs(remoteSU.blockDay - nodeParams.currentBlockDay) <= 1
        val isRemoteSigOk = localCompleteLCSS.verifyRemoteSig(remoteNodeId)

        if (!isBlockdayAcceptable) throw new ChannelException(channelId, "Their blockday is wrong")
        if (!isRightRemoteUpdateNumber) throw new ChannelException(channelId, "Their remote update number is wrong")
        if (!isRightLocalUpdateNumber) throw new ChannelException(channelId, "Their local update number is wrong")
        if (!isRemoteSigOk) throw new ChannelException(channelId, "Their signature is wrong")
      } {
        val hostedCommits1 = data.hostedDataCommits.copy(lastCrossSignedState = localCompleteLCSS)
        goto(NORMAL) using hostedCommits1 storing()
      }

    case Event(CMD_HOSTED_MESSAGE(channelId, remoteLCSS: LastCrossSignedState), _: HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE) =>
      // We have expected InitHostedChannel but got LastCrossSignedState so this channel exists already on their side
      // make sure signatures match and if so then become OPEN using remotely supplied state data

      val restoredHostedCommits = restoreCommits(remoteLCSS.reverse, channelId, isHost = false)
      val (localHtlcs, foreignHtlcs) = restoredHostedCommits.localAndForeignIncomingHtlcs(nodeParams.privateKey)
      val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(nodeParams.privateKey.publicKey)
      val isLocalSigOk = remoteLCSS.verifyRemoteSig(remoteNodeId)

      if (!isLocalSigOk) {
        localSuspend(restoredHostedCommits, ChannelErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG)
      } else if (!isRemoteSigOk) {
        localSuspend(restoredHostedCommits, ChannelErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
      } else if (foreignHtlcs.isEmpty || foreignHtlcs.map(_.id).toSet == nodeParams.db.pendingRelay.listPendingRelay(channelId).map(_.id).toSet) {
        // Either there are no incoming HTLCs at all, or all of them are local and can be failed/fulfilled, or all relayed HTLCs are failed or fulfilled
        for (add <- localHtlcs) relayer ! ForwardAdd(add)
        goto(NORMAL) using restoredHostedCommits storing() sending restoredHostedCommits.lastCrossSignedState.stateUpdate
      } else {
        localSuspend(restoredHostedCommits, ChannelErrorCodes.ERR_HOSTED_RELAYED_HTLC_WHILE_RESTORING)
      }
  }

  when(SYNCING) {
    case Event(_: CMD_HOSTED_INPUT_DISCONNECTED, _: HOSTED_DATA_COMMITMENTS) =>
      goto(OFFLINE)

    case Event(_: InvokeHostedChannel, _: HOSTED_DATA_COMMITMENTS) =>
      stay

    case Event(_: StateUpdate, HostedNothing) =>
      stay

    case Event(_: LastCrossSignedState, HostedNothing) =>
      stay
  }

  when(NORMAL) {
    case Event(_: CMD_HOSTED_INPUT_DISCONNECTED, _: HOSTED_DATA_COMMITMENTS) =>
      goto(OFFLINE)
  }

  type HostedFsmState = FSM.State[fr.acinq.eclair.channel.State, HostedData]

  implicit def state2mystate(state: HostedFsmState): MyState = MyState(state)

  case class MyState(state: HostedFsmState) {

    def storing(): HostedFsmState = {
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

    def sending(msg: LightningMessage): HostedFsmState = {
      forwarder ! msg
      state
    }

  }

  def restoreCommits(localLCSS: LastCrossSignedState, channelId: ByteVector32, isHost: Boolean): HOSTED_DATA_COMMITMENTS = {
    val inHtlcs = for (add <- localLCSS.incomingHtlcs) yield DirectedHtlc(IN, add)
    val outHtlcs = for (add <- localLCSS.outgoingHtlcs) yield DirectedHtlc(OUT, add)

    val spec = CommitmentSpec(htlcs = (inHtlcs ++ outHtlcs).toSet, feeratePerKw = 0L, localLCSS.localBalanceMsat, localLCSS.remoteBalanceMsat)

    HOSTED_DATA_COMMITMENTS(ChannelVersion.STANDARD, lastCrossSignedState = localLCSS, allLocalUpdates = localLCSS.localUpdates, allRemoteUpdates = localLCSS.remoteUpdates,
      localUpdates = Nil, remoteUpdates = Nil, localSpec = spec, channelId, isHost, channelUpdateOpt = None, localError = None, remoteError = None)
  }

  def sanityCheck(check: => Unit)(whenPassed: => HostedFsmState): HostedFsmState = {
    Try(check) match {
      case Failure(reason) =>
        log.debug(s"sanity check failed, reason=${reason.getMessage}")
        goto(CLOSED)
      case _ =>
        whenPassed
    }
  }

  def localSuspend(hostedCommits: HOSTED_DATA_COMMITMENTS, errCode: ByteVector): HostedFsmState = {
    val localError = Error(channelId = hostedCommits.channelId, data = errCode)
    val hostedCommits1 = hostedCommits.copy(localError = Some(localError))
    goto(CLOSED) using hostedCommits1 storing() sending localError
  }
}