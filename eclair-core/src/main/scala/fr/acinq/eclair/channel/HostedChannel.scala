package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM, Props, Status}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.wire._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.channel.Channel.LocalError
import fr.acinq.eclair.channel.HostedChannel.LocalOrRemoteUpdate
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.{CommitmentSpec, OUT}
import scodec.bits.ByteVector

import scala.util.{Failure, Success, Try}

object HostedChannel {
  type LocalOrRemoteUpdate = Either[UpdateMessage, UpdateMessage]

  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, relayer: ActorRef) = Props(new HostedChannel(nodeParams, remoteNodeId, router, relayer))
}

class HostedChannel(val nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, relayer: ActorRef) extends FSM[State, HostedData] with FSMDiagnosticActorLogging[State, HostedData] {

  context.system.eventStream.subscribe(self, classOf[CurrentBlockCount])
  context.system.eventStream.subscribe(self, CMD_HOSTED_REMOVE_IDLE_CHANNELS.getClass)

  val forwarder: ActorRef = context.actorOf(Props(new Forwarder(nodeParams)), "forwarder")

  startWith(OFFLINE, HostedNothing)

  when(OFFLINE) {
    case Event(data: HOSTED_DATA_COMMITMENTS, HostedNothing) => stay using data

    case Event(CMD_HOSTED_REMOVE_IDLE_CHANNELS, HostedNothing) => stop(FSM.Normal)

    case Event(CMD_HOSTED_REMOVE_IDLE_CHANNELS, commits: HOSTED_DATA_COMMITMENTS) =>
      if (commits.timedOutOutgoingHtlcs(Long.MaxValue).isEmpty) {
        stop(FSM.Normal)
      } else {
        stay
      }

    case Event(cmd: CMD_HOSTED_INPUT_RECONNECTED, HostedNothing) =>
      forwarder ! cmd.transport
      goto(WAIT_FOR_INIT_INTERNAL)

    case Event(cmd: CMD_HOSTED_INPUT_RECONNECTED, commits: HOSTED_DATA_COMMITMENTS) =>
      forwarder ! cmd.transport
      if (commits.isHost) {
        goto(SYNCING)
      } else if (commits.getError.isDefined) {
        goto(CLOSED) sending commits.getError.get
      } else {
        goto(SYNCING) sending InvokeHostedChannel(nodeParams.chainHash, commits.lastCrossSignedState.refundScriptPubKey)
      }

    case Event(c: CurrentBlockCount, commits: HOSTED_DATA_COMMITMENTS) =>
      val (commits1, failedAdds) = failTimedoutOutgoing(c.blockCount, commits)
      if (failedAdds.nonEmpty && commits1.getError.isEmpty) {
        val error = Error(commits1.channelId, ChannelErrorCodes.ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC)
        stay using commits1.copy(localError = Some(error)) storing()
      } else if (failedAdds.nonEmpty) {
        stay using commits1 storing()
      } else {
        stay
      }
  }

  when(WAIT_FOR_INIT_INTERNAL) {
    case Event(CMD_HOSTED_MESSAGE(_, _: Error), _) => goto(CLOSED) using HostedNothing

    case Event(_: CMD_HOSTED_INPUT_DISCONNECTED, _) => goto(OFFLINE) using HostedNothing

    case Event(CMD_HOSTED_MESSAGE(channelId, invoke: InvokeHostedChannel), HostedNothing) =>
      if (nodeParams.chainHash != invoke.chainHash) {
        val message = InvalidChainHash(channelId, local = nodeParams.chainHash, remote = invoke.chainHash).getMessage
        stay sending Error(channelId, message)
      } else if (!Helpers.Closing.isValidFinalScriptPubkey(invoke.refundScriptPubKey)) {
        val message = InvalidFinalScript(channelId).getMessage
        stay sending Error(channelId, message)
      } else {
        val init = InitHostedChannel(maxHtlcValueInFlightMsat = nodeParams.hostedParams.maxHtlcValueInFlightMsat, htlcMinimumMsat = nodeParams.hostedParams.htlcMinimum,
          maxAcceptedHtlcs = nodeParams.hostedParams.maxAcceptedHtlcs, channelCapacityMsat = nodeParams.hostedParams.defaultCapacity, liabilityDeadlineBlockdays = nodeParams.hostedParams.liabilityDeadlineBlockdays,
          minimalOnchainRefundAmountSatoshis = nodeParams.hostedParams.onChainRefundThreshold, initialClientBalanceMsat = nodeParams.hostedParams.defaultClientBalance)
        stay using HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE(init, invoke.refundScriptPubKey) sending init
      }

    case Event(CMD_HOSTED_MESSAGE(channelId, remoteSU: StateUpdate), data: HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) =>
      val fullySignedLocalLCSS = LastCrossSignedState(data.refundScriptPubKey, initHostedChannel = data.init, blockDay = remoteSU.blockDay,
        localBalanceMsat = data.init.channelCapacityMsat - data.init.initialClientBalanceMsat, remoteBalanceMsat = data.init.initialClientBalanceMsat, localUpdates = 0L, remoteUpdates = 0L,
        incomingHtlcs = Nil, outgoingHtlcs = Nil, remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS, localSigOfRemote = ByteVector64.Zeroes).withLocalSigOfRemote(nodeParams.privateKey)
      if (math.abs(remoteSU.blockDay - nodeParams.currentBlockDay) > 1) {
        val message = InvalidBlockDay(channelId, nodeParams.currentBlockDay, remoteSU.blockDay).getMessage
        stay using HostedNothing sending Error(channelId, message)
      } else if (!fullySignedLocalLCSS.verifyRemoteSig(remoteNodeId)) {
        val message = InvalidRemoteStateSignature(channelId, fullySignedLocalLCSS.hostedSigHash, remoteSU.localSigOfRemoteLCSS).getMessage
        stay using HostedNothing sending Error(channelId, message)
      } else {
        val commits = restoreEmptyCommits(fullySignedLocalLCSS, channelId, isHost = true)
        goto(NORMAL) using commits storing() sending commits.lastCrossSignedState.stateUpdate(isTerminal = true)
      }

    // CLIENT FLOW

    case Event(cmd: CMD_HOSTED_INVOKE_CHANNEL, HostedNothing) =>
      val invoke = InvokeHostedChannel(nodeParams.chainHash, cmd.refundScriptPubKey)
      stay using HOSTED_DATA_CLIENT_WAIT_HOST_INIT(cmd.refundScriptPubKey) sending invoke

    case Event(CMD_HOSTED_MESSAGE(channelId, init: InitHostedChannel), data: HOSTED_DATA_CLIENT_WAIT_HOST_INIT) =>
      proceedOrClose(channelId) {
        if (init.liabilityDeadlineBlockdays < nodeParams.hostedParams.liabilityDeadlineBlockdays) throw new ChannelException(channelId, "Their liability deadline is too low")
        if (init.minimalOnchainRefundAmountSatoshis > nodeParams.hostedParams.onChainRefundThreshold) throw new ChannelException(channelId, "Their minimal on-chain refund is too high")
        if (init.channelCapacityMsat < nodeParams.hostedParams.defaultCapacity) throw new ChannelException(channelId, "Their proposed channel capacity is too low")
        if (init.initialClientBalanceMsat > init.channelCapacityMsat) throw new ChannelException(channelId, "Their init balance for us is larger than capacity")
      } {
        val locallySignedLCSS = LastCrossSignedState(data.refundScriptPubKey, initHostedChannel = init, blockDay = nodeParams.currentBlockDay,
          localBalanceMsat = init.initialClientBalanceMsat, remoteBalanceMsat = init.channelCapacityMsat - init.initialClientBalanceMsat, localUpdates = 0L, remoteUpdates = 0L,
          incomingHtlcs = Nil, outgoingHtlcs = Nil, localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes).withLocalSigOfRemote(nodeParams.privateKey)
        val data1 = HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE(restoreEmptyCommits(locallySignedLCSS, channelId, isHost = false))
        stay using data1 sending locallySignedLCSS.stateUpdate(isTerminal = true)
      }

    case Event(CMD_HOSTED_MESSAGE(_, remoteSU: StateUpdate), data: HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE) =>
      val fullySignedLCSS = data.commits.lastCrossSignedState.copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)
      proceedOrClose(data.commits.channelId) {
        val isRightRemoteUpdateNumber = data.commits.lastCrossSignedState.remoteUpdates == remoteSU.localUpdates
        val isRightLocalUpdateNumber = data.commits.lastCrossSignedState.localUpdates == remoteSU.remoteUpdates
        val isBlockdayAcceptable = math.abs(remoteSU.blockDay - nodeParams.currentBlockDay) <= 1
        val isRemoteSigOk = fullySignedLCSS.verifyRemoteSig(remoteNodeId)
        if (!isBlockdayAcceptable) throw new ChannelException(data.commits.channelId, "Their blockday is wrong")
        if (!isRightRemoteUpdateNumber) throw new ChannelException(data.commits.channelId, "Their remote update number is wrong")
        if (!isRightLocalUpdateNumber) throw new ChannelException(data.commits.channelId, "Their local update number is wrong")
        if (!isRemoteSigOk) throw new ChannelException(data.commits.channelId, "Their signature is wrong")
      } {
        val commits1 = data.commits.copy(lastCrossSignedState = fullySignedLCSS)
        goto(NORMAL) using commits1 storing()
      }

    // MISSING CHANNEL

    case Event(CMD_HOSTED_MESSAGE(channelId, remoteLCSS: LastCrossSignedState), _: HOSTED_DATA_CLIENT_WAIT_HOST_INIT) =>
      // We have sent InvokeHostedChannel and were expecting Host's InitHostedChannel
      restoreMissingChannel(channelId, remoteLCSS, isHost = false)

    case Event(CMD_HOSTED_MESSAGE(channelId, remoteLCSS: LastCrossSignedState), _: HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) =>
      // Client have sent us InvokeHostedChannel, we have replied with InitHostedChannel and were expecting Client's StateUpdate
      restoreMissingChannel(channelId, remoteLCSS, isHost = true)
  }

  when(SYNCING) {
    case Event(CMD_HOSTED_MESSAGE(_, _: InvokeHostedChannel), commits: HOSTED_DATA_COMMITMENTS) =>
      if (commits.isHost && commits.getError.isDefined) {
        goto(CLOSED) sending commits.lastCrossSignedState sending commits.getError.get
      } else if (commits.isHost) {
        stay sending commits.lastCrossSignedState
      } else {
        stay
      }

    // Looks like remote peer has lost this channel, they should re-sync from our LCSS
    case Event(CMD_HOSTED_MESSAGE(_, _: InitHostedChannel), commits: HOSTED_DATA_COMMITMENTS) if !commits.isHost =>
      goto(NORMAL) using syncAndResend(commits, commits.futureUpdates, commits.lastCrossSignedState, commits.localSpec)

    case Event(CMD_HOSTED_MESSAGE(_, remoteLCSS: LastCrossSignedState), commits: HOSTED_DATA_COMMITMENTS) =>
      val isLocalSigOk = remoteLCSS.verifyRemoteSig(nodeParams.privateKey.publicKey)
      val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(remoteNodeId)
      val weAreAhead = commits.lastCrossSignedState.isAhead(remoteLCSS)
      val weAreEven = commits.lastCrossSignedState.isEven(remoteLCSS)
      if (commits.getError.isDefined) {
        goto(CLOSED) sending commits.getError.get
      } else if (!isLocalSigOk) {
        localSuspend(commits, ChannelErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG)
      } else if (!isRemoteSigOk) {
        localSuspend(commits, ChannelErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
      } else if (weAreAhead || weAreEven) {
        // Resend our local pending UpdateAddHtlc but retain our current cross-signed state
        val commits1 = syncAndResend(commits, commits.futureUpdates, commits.lastCrossSignedState, commits.localSpec)
        goto(NORMAL) using commits1
      } else {
        commits.findState(remoteLCSS).headOption.map { commits1 =>
          val leftovers = commits.futureUpdates.diff(commits1.futureUpdates)
          // They have one of our future states, we also may have local pending UpdateAddHtlc
          val commits2 = syncAndResend(commits1, leftovers, remoteLCSS.reverse, commits1.nextLocalSpec)
          goto(NORMAL) using commits2 storing()
        } getOrElse {
          // We are behind, restore state from their data
          if (remoteLCSS.incomingHtlcs.nonEmpty || remoteLCSS.outgoingHtlcs.nonEmpty) {
            localSuspend(commits, ChannelErrorCodes.ERR_HOSTED_IN_FLIGHT_HTLC_WHILE_RESTORING)
          } else {
            val commits1 = restoreEmptyCommits(remoteLCSS.reverse, commits.channelId, commits.isHost)
            goto(NORMAL) using commits1 storing() sending commits1.lastCrossSignedState
          }
        }
      }
  }

  when(NORMAL) {
    case Event(c: CMD_FULFILL_HTLC, commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.sendFulfill(c)) match {
        case Success((commits1, fulfill)) if c.commit => stay using commits1 storing() replying "ok" acking(commits.channelId, c.id) sending fulfill sending makeStateUpdate(commits1)
        case Success((commits1, fulfill)) => stay using commits1 storing() replying "ok" acking(commits.channelId, c.id) sending fulfill
        case Failure(cause) => handleCommandError(cause, commits, c) acking(commits.channelId, c.id)
      }

    case Event(c: CMD_ADD_HTLC, commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.sendAdd(c, Channel.origin(c, sender), nodeParams.currentBlockHeight)) match {
        case Failure(cause) => handleCommandError(AddHtlcFailed(commits.channelId, c.paymentHash, cause, Channel.origin(c, sender), Some(commits.channelUpdate), Some(c)), commits, c)
        case Success(Left(error)) => handleCommandError(AddHtlcFailed(commits.channelId, c.paymentHash, error, Channel.origin(c, sender), Some(commits.channelUpdate), Some(c)), commits, c)
        case Success(Right((commits1, add))) if c.commit => stay using commits1 storing() replying "ok" sending add sending makeStateUpdate(commits1)
        case Success(Right((commits1, add))) => stay using commits1 storing() replying "ok" sending add
      }

    case Event(c: CMD_FAIL_HTLC, commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.sendFail(c, nodeParams.privateKey)) match {
        case Success((commits1, fail)) if c.commit => stay using commits1 storing() replying "ok" acking(commits.channelId, c.id) sending fail sending makeStateUpdate(commits1)
        case Success((commits1, fail)) => stay using commits1 storing() replying "ok" acking(commits.channelId, c.id) sending fail
        case Failure(cause) => handleCommandError(cause, commits, c) acking(commits.channelId, c.id)
      }

    case Event(c: CMD_FAIL_MALFORMED_HTLC, commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.sendFailMalformed(c)) match {
        case Success((commits1, fail)) if c.commit => stay using commits1 storing() replying "ok" acking(commits.channelId, c.id) sending fail sending makeStateUpdate(commits1)
        case Success((commits1, fail)) => stay using commits1 storing() replying "ok" acking(commits.channelId, c.id) sending fail
        case Failure(cause) => handleCommandError(cause, commits, c) acking(commits.channelId, c.id)
      }

    case Event(CMD_HOSTED_MESSAGE(_, add: UpdateAddHtlc), commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.receiveAdd(add)) match {
        case Failure(cause) => localSuspend(commits, ByteVector.view(cause.getMessage.getBytes))
        case Success(commits1) => stay using commits1
      }

    case Event(CMD_HOSTED_MESSAGE(_, fail: UpdateFailHtlc), commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.receiveFail(fail)) match {
        case Failure(cause) => localSuspend(commits, ByteVector.view(cause.getMessage.getBytes))
        case Success(Right((commits1, _, _))) => stay using commits1
        case _ => stay
      }

    case Event(CMD_HOSTED_MESSAGE(_, fail: UpdateFailMalformedHtlc), commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.receiveFailMalformed(fail)) match {
        case Failure(cause) => localSuspend(commits, ByteVector.view(cause.getMessage.getBytes))
        case Success(Right((commits1, _, _))) => stay using commits1
        case _ => stay
      }

    case Event(CMD_SIGN, commits: HOSTED_DATA_COMMITMENTS) => stay storing() sending makeStateUpdate(commits)

    case Event(CMD_HOSTED_MESSAGE(_, remoteSU: StateUpdate), commits: HOSTED_DATA_COMMITMENTS) =>
      if (commits.lastCrossSignedState.remoteSigOfLocal == remoteSU.localSigOfRemoteLCSS) {
        // Do nothing if we get a duplicate for new cross-signed state
        stay
      } else {
        val lcss1 = commits.nextLocalUnsignedLCSS(remoteSU.blockDay).copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS).withLocalSigOfRemote(nodeParams.privateKey)
        val commits1 = commits.copy(lastCrossSignedState = lcss1, localSpec = commits.nextLocalSpec, futureUpdates = Nil)
        val isBlockdayAcceptable = math.abs(remoteSU.blockDay - nodeParams.currentBlockDay) <= 1
        val isRemoteSigOk = lcss1.verifyRemoteSig(remoteNodeId)

        if (remoteSU.remoteUpdates < lcss1.localUpdates) {
          stay sending commits1.lastCrossSignedState.stateUpdate(isTerminal = false)
        } else if (!remoteSU.isTerminal) {
          stay sending commits1.lastCrossSignedState.stateUpdate(isTerminal = true)
        } else if (!isBlockdayAcceptable) {
          localSuspend(commits1, ChannelErrorCodes.ERR_HOSTED_WRONG_BLOCKDAY)
        } else if (!isRemoteSigOk) {
          localSuspend(commits1, ChannelErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
        } else {
          commits.nextRemoteUpdates.collect {
            case add: UpdateAddHtlc =>
              relayer ! ForwardAdd(add)
            case fail: UpdateFailHtlc =>
              val add = commits.localSpec.findHtlcById(fail.id, OUT).get.add
              relayer ! ForwardFail(fail, commits.originChannels(fail.id), add)
            case malformed: UpdateFailMalformedHtlc =>
              val add = commits.localSpec.findHtlcById(malformed.id, OUT).get.add
              relayer ! ForwardFailMalformed(malformed, commits.originChannels(malformed.id), add)
          }
          val completedOutgoingHtlcs = commits.localSpec.htlcs.filter(_.direction == OUT).map(_.add.id) -- commits1.localSpec.htlcs.filter(_.direction == OUT).map(_.add.id)
          stay using commits1.copy(originChannels = commits1.originChannels -- completedOutgoingHtlcs) storing() sending commits1.lastCrossSignedState.stateUpdate(isTerminal = true)
        }
      }
  }

  when(CLOSED) {
    case Event(CMD_HOSTED_MESSAGE(_, remoteSO: StateOverride), commits: HOSTED_DATA_COMMITMENTS) if !commits.isHost =>
      val newLocalBalance = commits.lastCrossSignedState.initHostedChannel.channelCapacityMsat - remoteSO.localBalanceMsat
      val completeLocalLCSS = commits.lastCrossSignedState.copy(incomingHtlcs = Nil, outgoingHtlcs = Nil, localBalanceMsat = newLocalBalance,
        remoteBalanceMsat = remoteSO.localBalanceMsat, localUpdates = remoteSO.remoteUpdates, remoteUpdates = remoteSO.localUpdates,
        blockDay = remoteSO.blockDay, remoteSigOfLocal = remoteSO.localSigOfRemoteLCSS).withLocalSigOfRemote(nodeParams.privateKey)
      proceedOrClose(commits.channelId) {
        val isRemoteSigOk = completeLocalLCSS.verifyRemoteSig(remoteNodeId)
        val isBlockdayAcceptable = math.abs(remoteSO.blockDay - nodeParams.currentBlockDay) <= 1
        val isRightLocalUpdateNumber = remoteSO.localUpdates > commits.lastCrossSignedState.remoteUpdates
        val isRightRemoteUpdateNumber = remoteSO.remoteUpdates > commits.lastCrossSignedState.localUpdates
        if (!isRemoteSigOk) throw new ChannelException(commits.channelId, "Provided remote override signature is wrong")
        if (completeLocalLCSS.localBalanceMsat < 0.msat) throw new ChannelException(commits.channelId, "Provided updated local balance is larger than capacity")
        if (!isRightLocalUpdateNumber) throw new ChannelException(commits.channelId, "Provided local update number from remote override is wrong")
        if (!isRightRemoteUpdateNumber) throw new ChannelException(commits.channelId, "Provided remote update number from remote override is wrong")
        if (!isBlockdayAcceptable) throw new ChannelException(commits.channelId, "Remote override blockday is not acceptable")
      } {
        failTimedoutOutgoing(blockHeight = Long.MaxValue, commits)
        Channel.ackPendingFailsAndFulfills(commits.nextLocalUpdates, relayer)
        val commits1 = restoreEmptyCommits(completeLocalLCSS, commits.channelId, isHost = false)
        goto(NORMAL) using commits1 storing() sending completeLocalLCSS.stateUpdate(isTerminal = true)
      }

    case Event(CMD_HOSTED_MESSAGE(_, remoteSU: StateUpdate), commits: HOSTED_DATA_COMMITMENTS) if commits.isHost && commits.overrideProposal.isDefined =>
      val completeOverridingLCSS = makeOverridingLocallySignedLCSS(commits, commits.overrideProposal.get.localBalanceMsat).copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)
      proceedOrClose(commits.channelId) {
        val isRemoteSigOk = completeOverridingLCSS.verifyRemoteSig(remoteNodeId)
        val isBlockdayAcceptable = math.abs(remoteSU.blockDay - nodeParams.currentBlockDay) <= 1
        val isRightLocalUpdateNumber = remoteSU.localUpdates == completeOverridingLCSS.remoteUpdates
        val isRightRemoteUpdateNumber = remoteSU.remoteUpdates == completeOverridingLCSS.localUpdates
        if (!isRemoteSigOk) throw new ChannelException(commits.channelId, "Provided remote override signature is wrong")
        if (!isRightLocalUpdateNumber) throw new ChannelException(commits.channelId, "Provided local update number from remote override is wrong")
        if (!isRightRemoteUpdateNumber) throw new ChannelException(commits.channelId, "Provided remote update number from remote override is wrong")
        if (!isBlockdayAcceptable) throw new ChannelException(commits.channelId, "Remote override blockday is not acceptable")
      } {
        failTimedoutOutgoing(blockHeight = Long.MaxValue, commits)
        Channel.ackPendingFailsAndFulfills(commits.nextLocalUpdates, relayer)
        val commits1 = restoreEmptyCommits(completeOverridingLCSS, commits.channelId, isHost = true)
        goto(NORMAL) using commits1 storing()
      }
  }

  whenUnhandled {
    case Event(_: CMD_HOSTED_INPUT_DISCONNECTED, _) => goto(OFFLINE)

    case Event(CMD_HOSTED_MESSAGE(_, error: Error), commits: HOSTED_DATA_COMMITMENTS) =>
      if (commits.remoteError.isEmpty) {
        goto(CLOSED) using commits.copy(remoteError = Some(error)) storing()
      } else {
        goto(CLOSED)
      }

    case Event(c: CurrentBlockCount, commits: HOSTED_DATA_COMMITMENTS) =>
      val (commits1, failedAdds) = failTimedoutOutgoing(c.blockCount, commits)
      if (failedAdds.nonEmpty && commits1.getError.isEmpty) {
        localSuspend(commits1, ChannelErrorCodes.ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC)
      } else if (failedAdds.nonEmpty) {
        stay using commits1 storing()
      } else {
        stay
      }

    case Event(CMD_HOSTED_MESSAGE(_, fulfill: UpdateFulfillHtlc), commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.receiveFulfill(fulfill)) match {
        case Failure(cause) if commits.localError.isEmpty =>
          localSuspend(commits, ByteVector.view(cause.getMessage.getBytes)) replying s"Failed reason=${cause.getMessage}"
        case Success(Right((commits1, origin, htlc))) =>
          relayer ! ForwardFulfill(fulfill, origin, htlc)
          commits.getError match {
            case Some(error) =>
              val commits2 = commits1.copy(resolvedOutgoingHtlcLeftoverIds = commits1.resolvedOutgoingHtlcLeftoverIds + htlc.id)
              stay using commits2 storing() replying s"Relayed hash=${fulfill.paymentHash}, error=${ChannelErrorCodes.toHostedAscii(error)}"
            case None =>
              stay using commits1 storing() replying s"Relayed hash=${fulfill.paymentHash}"
          }
        case otherwise =>
          stay replying s"Failed reason=$otherwise"
      }

    case Event(c: CMD_ADD_HTLC, commits: HOSTED_DATA_COMMITMENTS) =>
      log.info(s"rejecting htlc request in state=$stateName in a hosted channel")
      // This may happen if CMD_ADD_HTLC message had been issued while this channel was NORMAL but became OFFLINE/CLOSED by the time it arrived
      val disabledChannelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, randomHostedChanShortId,
        nodeParams.hostedParams.cltvDelta, commits.lastCrossSignedState.initHostedChannel.htlcMinimumMsat, nodeParams.hostedParams.feeBase,
        nodeParams.hostedParams.feeProportionalMillionth, commits.lastCrossSignedState.initHostedChannel.channelCapacityMsat, enable = false)
      handleCommandError(AddHtlcFailed(commits.channelId, c.paymentHash, ChannelUnavailable(commits.channelId), Channel.origin(c, sender), Some(disabledChannelUpdate), Some(c)), commits, c)

    case Event(c: CMD_HOSTED_OVERRIDE, commits: HOSTED_DATA_COMMITMENTS) if commits.isHost && commits.getError.isDefined =>
      val overridingLocallySignedLCSS = makeOverridingLocallySignedLCSS(commits, c.newLocalBalance)
      val localSO = StateOverride(blockDay = nodeParams.currentBlockDay, localBalanceMsat = overridingLocallySignedLCSS.localBalanceMsat,
        localUpdates = overridingLocallySignedLCSS.localUpdates, remoteUpdates = overridingLocallySignedLCSS.remoteUpdates,
        localSigOfRemoteLCSS = overridingLocallySignedLCSS.localSigOfRemote)
      log.info(s"saving override proposal for channelId=${commits.channelId}, state=$stateName")
      stay using commits.copy(overrideProposal = Some(localSO)) storing() sending localSO replying "ok"

    case Event(CMD_GETINFO, commits: HOSTED_DATA_COMMITMENTS) =>
      sender ! RES_HOSTED_GETINFO(remoteNodeId, commits.channelId, stateName, commits)
      stay

    case Event(any, _) =>
      log.debug(s"Hosted channel failed to handle $any in state=$stateName, data=$stateData, remoteNodeId=$remoteNodeId")
      stay
  }

  onTransition {
    case state -> nextState =>
      (state, nextState, nextStateData) match {
        case (_, NORMAL, commits: HOSTED_DATA_COMMITMENTS) if state != NORMAL =>
          context.system.eventStream.publish(ChannelRestored(self, context.parent, remoteNodeId, commits.isHost, commits.channelId, commits))
          context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, ByteVector32.Zeroes, commits.channelId))
          context.system.eventStream.publish(ShortChannelIdAssigned(self, commits.channelId, commits.channelUpdate.shortChannelId))
          context.system.eventStream.publish(LocalChannelUpdate(self, commits.channelId, commits.channelUpdate.shortChannelId, remoteNodeId, None, commits.channelUpdate, commits))
          context.system.eventStream.publish(HostedChannelStateChanged(self, context.parent, remoteNodeId, state, nextState, commits))
          forwarder ! commits.channelUpdate
        case (NORMAL, _, commits: HOSTED_DATA_COMMITMENTS) if nextState != NORMAL =>
          context.system.eventStream.publish(LocalChannelDown(self, commits.channelId, commits.channelUpdate.shortChannelId, remoteNodeId))
          if (nextState == CLOSED && commits.isHost) commits.overrideProposal.foreach(localSO => forwarder ! localSO)
        case _ =>
      }
  }

  type HostedFsmState = FSM.State[fr.acinq.eclair.channel.State, HostedData]

  implicit class MyState(state: HostedFsmState) {

    def storing(): HostedFsmState =
      state.stateData match {
        case commits: HOSTED_DATA_COMMITMENTS =>
          log.debug(s"updating database record for hosted channelId=${commits.channelId}")
          nodeParams.db.hostedChannels.addOrUpdateChannel(commits)
          state
        case _ =>
          log.error(s"can't store hosted data=${state.stateData} in state=${state.stateName}")
          state
      }

    def sending(msg: LightningMessage): HostedFsmState = {
      forwarder ! msg
      state
    }

    def acking(channelId: ByteVector32, htlcId: Long): HostedFsmState = {
      relayer ! CommandBuffer.CommandAck(channelId, htlcId)
      state
    }
  }

  def restoreMissingChannel(channelId: ByteVector32, remoteLCSS: LastCrossSignedState, isHost: Boolean): HostedFsmState = {
    val commits = restoreEmptyCommits(remoteLCSS.reverse, channelId, isHost)
    val isLocalSigOk = remoteLCSS.verifyRemoteSig(nodeParams.privateKey.publicKey)
    val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(remoteNodeId)
    if (!isLocalSigOk) {
      localSuspend(commits, ChannelErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG)
    } else if (!isRemoteSigOk) {
      localSuspend(commits, ChannelErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
    } else if (remoteLCSS.incomingHtlcs.nonEmpty || remoteLCSS.outgoingHtlcs.nonEmpty) {
      localSuspend(commits, ChannelErrorCodes.ERR_HOSTED_IN_FLIGHT_HTLC_WHILE_RESTORING)
    } else {
      goto(NORMAL) using commits storing() sending commits.lastCrossSignedState
    }
  }

  def restoreEmptyCommits(localLCSS: LastCrossSignedState, channelId: ByteVector32, isHost: Boolean): HOSTED_DATA_COMMITMENTS = {
    val localCommitmentSpec = CommitmentSpec(htlcs = Set.empty, feeratePerKw = 0L, localLCSS.localBalanceMsat, localLCSS.remoteBalanceMsat)
    val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, randomHostedChanShortId, nodeParams.hostedParams.cltvDelta,
      localLCSS.initHostedChannel.htlcMinimumMsat, nodeParams.hostedParams.feeBase, nodeParams.hostedParams.feeProportionalMillionth, localLCSS.initHostedChannel.channelCapacityMsat)
    HOSTED_DATA_COMMITMENTS(remoteNodeId, ChannelVersion.STANDARD, localLCSS, futureUpdates = Nil, localCommitmentSpec, originChannels = Map.empty,
      channelId, isHost, channelUpdate, localError = None, remoteError = None, resolvedOutgoingHtlcLeftoverIds = Set.empty, overrideProposal = None)
  }

  def makeStateUpdate(commits: HOSTED_DATA_COMMITMENTS): StateUpdate = {
    val localUnsignedLCSS1 = commits.nextLocalUnsignedLCSS(nodeParams.currentBlockDay)
    localUnsignedLCSS1.withLocalSigOfRemote(nodeParams.privateKey).stateUpdate(isTerminal = false)
  }

  def makeOverridingLocallySignedLCSS(commits: HOSTED_DATA_COMMITMENTS, newLocalBalance: MilliSatoshi): LastCrossSignedState =
    commits.lastCrossSignedState.copy(incomingHtlcs = Nil, outgoingHtlcs = Nil, localBalanceMsat = newLocalBalance, remoteBalanceMsat = commits.lastCrossSignedState.initHostedChannel.channelCapacityMsat - newLocalBalance,
      localUpdates = commits.lastCrossSignedState.localUpdates + commits.nextLocalUpdates.size + 1, remoteUpdates = commits.lastCrossSignedState.remoteUpdates + commits.nextRemoteUpdates.size + 1,
      blockDay = nodeParams.currentBlockDay, remoteSigOfLocal = ByteVector64.Zeroes).withLocalSigOfRemote(nodeParams.privateKey)

  def proceedOrClose(channelId: ByteVector32)(check: => Unit)(whenPassed: => HostedFsmState): HostedFsmState =
    Try(check) match {
      case Failure(reason) =>
        log.error(s"sanity check failed, reason=${reason.getMessage}, suspending with data=$stateData")
        goto(CLOSED) sending Error(channelId, ByteVector.view(reason.getMessage.getBytes))
      case _ =>
        whenPassed
    }

  def localSuspend(commits: HOSTED_DATA_COMMITMENTS, errCode: ByteVector): HostedFsmState = {
    context.system.eventStream.publish(ChannelErrorOccurred(self, commits.channelId, remoteNodeId, LocalError(new Throwable(errCode.toHex)), isFatal = true))
    val commits1 = commits.copy(localError = Some(Error(commits.channelId, errCode)))
    goto(CLOSED) using commits1 storing() sending commits1.localError.get
  }

  def handleCommandError(cause: Throwable, commits: HOSTED_DATA_COMMITMENTS, cmd: Command): HostedFsmState = {
    context.system.eventStream.publish(ChannelErrorOccurred(self, commits.channelId, remoteNodeId, LocalError(cause), isFatal = false))
    log.warning(s"${cause.getMessage} while processing cmd=${cmd.getClass.getSimpleName} in state=$stateName")
    stay replying Status.Failure(cause)
  }

  def syncAndResend(commits: HOSTED_DATA_COMMITMENTS, leftovers: List[LocalOrRemoteUpdate], lcss: LastCrossSignedState, spec: CommitmentSpec): HOSTED_DATA_COMMITMENTS = {
    val commits1 = commits.copy(futureUpdates = leftovers.filter(_.isLeft), lastCrossSignedState = lcss, localSpec = spec)

    forwarder ! commits1.lastCrossSignedState
    for (update <- commits1.nextLocalUpdates) forwarder ! update
    if (commits1.nextLocalUpdates.nonEmpty) self ! CMD_SIGN
    commits1
  }

  def failTimedoutOutgoing(blockHeight: Long, commits: HOSTED_DATA_COMMITMENTS): (HOSTED_DATA_COMMITMENTS, Set[UpdateAddHtlc]) = {
    val timedoutOutgoingHtlcs = commits.timedOutOutgoingHtlcs(blockHeight) // Find timedout outgoing HTLCs which have not been failed or fulfilled by remote peer already
    Channel.failPending(timedoutOutgoingHtlcs, (origin, add) => AddHtlcFailed(commits.channelId, add.paymentHash, HtlcTimedout(commits.channelId, Set(add)), origin, None, None), relayer, commits, log)
    (commits.copy(resolvedOutgoingHtlcLeftoverIds = timedoutOutgoingHtlcs.map(_.id) ++ commits.resolvedOutgoingHtlcLeftoverIds), timedoutOutgoingHtlcs)
  }

  initialize()
}