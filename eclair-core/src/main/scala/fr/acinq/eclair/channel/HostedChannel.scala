package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM, Props, Status}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.wire._
import fr.acinq.eclair._
import fr.acinq.eclair.payment.{CommandBuffer, ForwardFulfill, Local, Origin, Relayed}
import fr.acinq.eclair.transactions.CommitmentSpec
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

object HostedChannel {
  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, relayer: ActorRef) = Props(new HostedChannel(nodeParams, remoteNodeId, router, relayer))
}

class HostedChannel(val nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, relayer: ActorRef)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends FSM[State, HostedData] with FSMDiagnosticActorLogging[State, HostedData] {

  val forwarder: ActorRef = context.actorOf(Props(new Forwarder(nodeParams)), "forwarder")

  startWith(OFFLINE, HostedNothing)

  when(OFFLINE) {
    case Event(data: HOSTED_DATA_COMMITMENTS, HostedNothing) => stay using data

    case Event(cmd: CMD_HOSTED_INPUT_RECONNECTED, HostedNothing) =>
      forwarder ! cmd.transport
      goto(WAIT_FOR_INIT_INTERNAL)

    case Event(cmd: CMD_HOSTED_INPUT_RECONNECTED, data: HOSTED_DATA_COMMITMENTS) =>
      context.system.eventStream.publish(ChannelRestored(self, context.parent, remoteNodeId, isFunder = true, data.channelId, data))
      forwarder ! cmd.transport
      if (data.isHost) {
        goto(SYNCING)
      } else if (data.getError.isDefined) {
        goto(CLOSED) sending data.getError.get
      } else {
        goto(SYNCING) sending InvokeHostedChannel(nodeParams.chainHash, data.lastCrossSignedState.refundScriptPubKey)
      }

    case Event(commits: HOSTED_DATA_COMMITMENTS, data: HOSTED_DATA_COMMITMENTS) if data.isHost => stay using commits storing()
  }

  when(WAIT_FOR_INIT_INTERNAL) {
    case Event(_: CMD_HOSTED_INPUT_DISCONNECTED, _) => goto(OFFLINE)

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
          htlcMinimumMsat = nodeParams.htlcMinimum, maxAcceptedHtlcs = nodeParams.maxAcceptedHtlcs,
          channelCapacityMsat = 100000000000L msat, liabilityDeadlineBlockdays = minHostedLiabilityBlockdays,
          minimalOnchainRefundAmountSatoshis = minHostedOnChainRefund, initialClientBalanceMsat = 50000000L msat)

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
        val hostedCommits = restoreEmptyCommits(localLCSS, channelId, isHost = true)
        context.parent ! CMD_HOSTED_REGISTER_SHORT_CHANNEL_ID(channelId, remoteNodeId, hostedCommits)
        stay using data.copy(waitingForShortId = true)
      }

    case Event(commits: HOSTED_DATA_COMMITMENTS, data: HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) if data.waitingForShortId =>
      context.system.eventStream.publish(ShortChannelIdAssigned(self, commits.channelId, commits.channelUpdateOpt.get.shortChannelId))
      context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, commits.channelId, commits.channelId))
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
        val hostedCommits = restoreEmptyCommits(LastCrossSignedState(data.refundScriptPubKey, msg, nodeParams.currentBlockDay, msg.initialClientBalanceMsat,
          msg.channelCapacityMsat - msg.initialClientBalanceMsat, localUpdates = 0L, remoteUpdates = 0L, incomingHtlcs = Nil, outgoingHtlcs = Nil,
          localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes).withLocalSigOfRemote(nodeParams.privateKey), channelId, isHost = false)

        stay using HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE(hostedCommits) sending hostedCommits.lastCrossSignedState.stateUpdate
      }

    case Event(CMD_HOSTED_MESSAGE(_, remoteSU: StateUpdate), data: HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE) =>
      val localCompleteLCSS = data.hostedDataCommits.lastCrossSignedState.copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)
      sanityCheck {
        val isRightRemoteUpdateNumber = data.hostedDataCommits.lastCrossSignedState.remoteUpdates == remoteSU.localUpdates
        val isRightLocalUpdateNumber = data.hostedDataCommits.lastCrossSignedState.localUpdates == remoteSU.remoteUpdates
        val isBlockdayAcceptable = math.abs(remoteSU.blockDay - nodeParams.currentBlockDay) <= 1
        val isRemoteSigOk = localCompleteLCSS.verifyRemoteSig(remoteNodeId)

        if (!isBlockdayAcceptable) throw new ChannelException(data.hostedDataCommits.channelId, "Their blockday is wrong")
        if (!isRightRemoteUpdateNumber) throw new ChannelException(data.hostedDataCommits.channelId, "Their remote update number is wrong")
        if (!isRightLocalUpdateNumber) throw new ChannelException(data.hostedDataCommits.channelId, "Their local update number is wrong")
        if (!isRemoteSigOk) throw new ChannelException(data.hostedDataCommits.channelId, "Their signature is wrong")
      } {
        val hostedCommits1 = data.hostedDataCommits.copy(lastCrossSignedState = localCompleteLCSS)
        goto(NORMAL) using hostedCommits1 storing()
      }

    case Event(CMD_HOSTED_MESSAGE(_, remoteLCSS: LastCrossSignedState), data: HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE) =>
      // Client has expected InitHostedChannel but got LastCrossSignedState so this channel exists already on host side
      // make sure signatures match and if so then become OPEN using remotely supplied state data
      val restoredHostedCommits = restoreEmptyCommits(remoteLCSS.reverse, data.hostedDataCommits.channelId, isHost = false)
      val isLocalSigOk = remoteLCSS.verifyRemoteSig(nodeParams.privateKey.publicKey)
      val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(remoteNodeId)
      if (!isLocalSigOk) {
        localSuspend(restoredHostedCommits, ChannelErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG)
      } else if (!isRemoteSigOk) {
        localSuspend(restoredHostedCommits, ChannelErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
      } else if (remoteLCSS.incomingHtlcs.nonEmpty || remoteLCSS.outgoingHtlcs.nonEmpty) {
        localSuspend(restoredHostedCommits, ChannelErrorCodes.ERR_HOSTED_IN_FLIGHT_HTLC_WHILE_RESTORING)
      } else {
        goto(NORMAL) using restoredHostedCommits storing() sending restoredHostedCommits.lastCrossSignedState.stateUpdate
      }
  }

  when(SYNCING) {
    case Event(_: CMD_HOSTED_INPUT_DISCONNECTED, _: HOSTED_DATA_COMMITMENTS) => goto(OFFLINE)

    case Event(CMD_HOSTED_MESSAGE(_, _: InvokeHostedChannel), data: HOSTED_DATA_COMMITMENTS) => stay sending data.lastCrossSignedState

    case Event(CMD_HOSTED_MESSAGE(_, remoteSU: StateUpdate), data: HOSTED_DATA_COMMITMENTS) if data.isHost =>
      if (remoteSU.localSigOfRemoteLCSS != data.lastCrossSignedState.remoteSigOfLocal) {
        localSuspend(data, ChannelErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
      } else if (data.getError.isDefined) {
        goto(CLOSED) sending data.getError.get
      } else {
        goto(NORMAL)
      }

    case Event(CMD_HOSTED_MESSAGE(_, remoteLCSS: LastCrossSignedState), data: HOSTED_DATA_COMMITMENTS) =>
      val restoredHostedCommits = restoreEmptyCommits(remoteLCSS.reverse, data.channelId, isHost = data.isHost)
      val isLocalSigOk = remoteLCSS.verifyRemoteSig(nodeParams.privateKey.publicKey)
      val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(remoteNodeId)
      val weAreBehind = remoteLCSS.isAhead(data.lastCrossSignedState)
      if (!isLocalSigOk) {
        localSuspend(restoredHostedCommits, ChannelErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG)
      } else if (!isRemoteSigOk) {
        localSuspend(restoredHostedCommits, ChannelErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
      } else if (weAreBehind) {
        if (remoteLCSS.incomingHtlcs.nonEmpty || remoteLCSS.outgoingHtlcs.nonEmpty) {
          localSuspend(restoredHostedCommits, ChannelErrorCodes.ERR_HOSTED_IN_FLIGHT_HTLC_WHILE_RESTORING)
        } else {
          if (data.isHost) context.parent ! CMD_HOSTED_REGISTER_SHORT_CHANNEL_ID(data.channelId, remoteNodeId, restoredHostedCommits)
          goto(NORMAL) using restoredHostedCommits storing() sending restoredHostedCommits.lastCrossSignedState.stateUpdate
        }
      } else {
        forwarder ! data.channelUpdateOpt.get
        goto(NORMAL) sending data.lastCrossSignedState.stateUpdate
      }
  }

  when(NORMAL) {
    case Event(_: CMD_HOSTED_INPUT_DISCONNECTED, _: HOSTED_DATA_COMMITMENTS) => goto(OFFLINE)

    case Event(commits: HOSTED_DATA_COMMITMENTS, data: HOSTED_DATA_COMMITMENTS) if data.isHost =>
      context.system.eventStream.publish(ShortChannelIdAssigned(self, commits.channelId, commits.channelUpdateOpt.get.shortChannelId))
      stay using commits sending commits.channelUpdateOpt.get

    case Event(c: CMD_ADD_HTLC, data: HOSTED_DATA_COMMITMENTS) =>
      Try(data.sendAdd(c, origin(c), nodeParams.currentBlockHeight)) match {
        case Success(Right((commitments1, add))) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, commitments1) sending add
        case Success(Left(error)) => handleCommandError(AddHtlcFailed(data.channelId, c.paymentHash, error, origin(c), data.channelUpdateOpt, Some(c)), c)
        case Failure(cause) => handleCommandError(AddHtlcFailed(data.channelId, c.paymentHash, cause, origin(c), data.channelUpdateOpt, Some(c)), c)
      }

    case Event(CMD_HOSTED_MESSAGE(_, add: UpdateAddHtlc), data: HOSTED_DATA_COMMITMENTS) =>
      Try(data.receiveAdd(add)) match {
        case Success(commitments1) => stay using commitments1
        case Failure(cause) => localSuspend(data, cause)
      }

    case Event(c: CMD_FULFILL_HTLC, data: HOSTED_DATA_COMMITMENTS) =>
      Try(data.sendFulfill(c)) match {
        case Success((commitments1, fulfill)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, commitments1) sending fulfill
        case Failure(cause) =>
          // we can clean up the command right away in case of failure
          relayer ! CommandBuffer.CommandAck(data.channelId, c.id)
          handleCommandError(cause, c)
      }

    case Event(CMD_HOSTED_MESSAGE(_, fulfill: UpdateFulfillHtlc), data: HOSTED_DATA_COMMITMENTS) =>
      Try(data.receiveFulfill(fulfill)) match {
        case Success(Right((commitments1, origin, htlc))) =>
          // we forward preimages as soon as possible to the upstream channel because it allows us to pull funds
          relayer ! ForwardFulfill(fulfill, origin, htlc)
          stay using commitments1
        case Success(Left(_)) => stay
        case Failure(cause) => localSuspend(data, cause)
      }

    case Event(c: CMD_FAIL_HTLC, data: HOSTED_DATA_COMMITMENTS) =>
      Try(data.sendFail(c, nodeParams.privateKey)) match {
        case Success((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, commitments1) sending fail
        case Failure(cause) =>
          // we can clean up the command right away in case of failure
          relayer ! CommandBuffer.CommandAck(data.channelId, c.id)
          handleCommandError(cause, c)
      }

    case Event(c: CMD_FAIL_MALFORMED_HTLC, data: HOSTED_DATA_COMMITMENTS) =>
      Try(data.sendFailMalformed(c)) match {
        case Success((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, commitments1) sending fail
        case Failure(cause) =>
          // we can clean up the command right away in case of failure
          relayer ! CommandBuffer.CommandAck(data.channelId, c.id)
          handleCommandError(cause, c)
      }

    case Event(CMD_HOSTED_MESSAGE(_, fail: UpdateFailHtlc), data: HOSTED_DATA_COMMITMENTS) =>
      Try(data.receiveFail(fail)) match {
        case Success(Right((commitments1, _, _))) => stay using commitments1
        case Success(Left(_)) => stay
        case Failure(cause) => localSuspend(data, cause)
      }

    case Event(CMD_HOSTED_MESSAGE(_, fail: UpdateFailMalformedHtlc), data: HOSTED_DATA_COMMITMENTS) =>
      Try(data.receiveFailMalformed(fail)) match {
        case Success(Right((commitments1, _, _))) => stay using commitments1
        case Success(Left(_)) => stay
        case Failure(cause) => localSuspend(data, cause)
      }
  }

  onTransition {
    case state -> nextState =>
      nextStateData match {
        case commits: HOSTED_DATA_COMMITMENTS if state != NORMAL =>
          context.system.eventStream.publish(LocalChannelUpdate(self, commits.channelId, commits.channelUpdateOpt.get.shortChannelId, remoteNodeId, None, commits.channelUpdateOpt.get, commits))
        case commits: HOSTED_DATA_COMMITMENTS if nextState != NORMAL =>
          context.system.eventStream.publish(LocalChannelDown(self, commits.channelId, commits.channelUpdateOpt.get.shortChannelId, remoteNodeId))
        case _ =>
      }
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

  def origin(c: CMD_ADD_HTLC): Origin = c.upstream match {
    case Left(id) => Local(id, Some(sender)) // we were the origin of the payment
    case Right(u) => Relayed(u.channelId, u.id, u.amountMsat, c.amount) // this is a relayed payment
  }

  def restoreEmptyCommits(localLCSS: LastCrossSignedState, channelId: ByteVector32, isHost: Boolean): HOSTED_DATA_COMMITMENTS =
    HOSTED_DATA_COMMITMENTS(ChannelVersion.STANDARD, lastCrossSignedState = localLCSS, allLocalUpdates = localLCSS.localUpdates, allRemoteUpdates = localLCSS.remoteUpdates,
      localUpdates = Nil, remoteUpdates = Nil, localSpec = CommitmentSpec(htlcs = Set.empty, feeratePerKw = 0L, localLCSS.localBalanceMsat, localLCSS.remoteBalanceMsat),
      originChannels = Map.empty, channelId, isHost, channelUpdateOpt = None, localError = None, remoteError = None)

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

  def localSuspend(hostedCommits: HOSTED_DATA_COMMITMENTS, err: Throwable): HostedFsmState = {
    val localError = Error(channelId = hostedCommits.channelId, data = ByteVector(err.getMessage.getBytes))
    val hostedCommits1 = hostedCommits.copy(localError = Some(localError))
    goto(CLOSED) using hostedCommits1 storing() sending localError
  }

  def handleCommandSuccess(sender: ActorRef, hostedCommits: HOSTED_DATA_COMMITMENTS): HostedFsmState = {
    stay using hostedCommits replying "ok"
  }

  def handleCommandError(cause: Throwable, cmd: Command): HostedFsmState = {
    log.warning(s"${cause.getMessage} while processing cmd=${cmd.getClass.getSimpleName} in state=$stateName")
    stay replying Status.Failure(cause)
  }
}