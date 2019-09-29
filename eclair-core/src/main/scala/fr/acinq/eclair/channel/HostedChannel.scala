package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM, Props, Status}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.wire._
import fr.acinq.eclair._
import com.softwaremill.quicklens._
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.{CommitmentSpec, OUT}
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

object HostedChannel {
  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, relayer: ActorRef) = Props(new HostedChannel(nodeParams, remoteNodeId, router, relayer))
}

class HostedChannel(val nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, relayer: ActorRef)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends FSM[State, HostedData] with FSMDiagnosticActorLogging[State, HostedData] {

  context.system.eventStream.subscribe(self, classOf[CurrentBlockCount])

  val forwarder: ActorRef = context.actorOf(Props(new Forwarder(nodeParams)), "forwarder")

  private var stateUpdateAttempts: Int = 0

  startWith(OFFLINE, HostedNothing)

  when(OFFLINE) {
    case Event(c: CurrentBlockCount, commits: HOSTED_DATA_COMMITMENTS) => handleNewBlock(c, commits)

    case Event(data: HOSTED_DATA_COMMITMENTS, HostedNothing) => stay using data

    case Event(cmd: CMD_HOSTED_INPUT_RECONNECTED, HostedNothing) =>
      forwarder ! cmd.transport
      goto(WAIT_FOR_INIT_INTERNAL)

    case Event(cmd: CMD_HOSTED_INPUT_RECONNECTED, commits: HOSTED_DATA_COMMITMENTS) =>
      forwarder ! cmd.transport
      context.system.eventStream.publish(ChannelRestored(self, context.parent, remoteNodeId, isFunder = true, commits.channelId, commits))
      if (commits.isHost) {
        goto(SYNCING)
      } else if (commits.getError.isDefined) {
        goto(CLOSED) sending commits.getError.get
      } else {
        goto(SYNCING) sending InvokeHostedChannel(nodeParams.chainHash, commits.lastCrossSignedState.refundScriptPubKey)
      }
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
        val init =
          InitHostedChannel(maxHtlcValueInFlightMsat = nodeParams.maxHtlcValueInFlightMsat,
            htlcMinimumMsat = nodeParams.htlcMinimum, maxAcceptedHtlcs = nodeParams.maxAcceptedHtlcs,
            channelCapacityMsat = 100000000000L msat, liabilityDeadlineBlockdays = minHostedLiabilityBlockdays,
            minimalOnchainRefundAmountSatoshis = minHostedOnChainRefund, initialClientBalanceMsat = 50000000L msat)
        stay using HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE(init, msg.refundScriptPubKey) sending init
      }

    case Event(CMD_HOSTED_MESSAGE(channelId, remoteSU: StateUpdate), data: HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) =>
      val fullySignedLocalLCSS =
        LastCrossSignedState(data.refundScriptPubKey, initHostedChannel = data.init, blockDay = remoteSU.blockDay,
          localBalanceMsat = data.init.channelCapacityMsat - data.init.initialClientBalanceMsat, remoteBalanceMsat = data.init.initialClientBalanceMsat,
          localUpdates = 0L, remoteUpdates = 0L, incomingHtlcs = Nil, outgoingHtlcs = Nil, remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS,
          localSigOfRemote = ByteVector64.Zeroes).withLocalSigOfRemote(nodeParams.privateKey)
      if (math.abs(remoteSU.blockDay - nodeParams.currentBlockDay) > 1) {
        val message = InvalidBlockDay(channelId, nodeParams.currentBlockDay, remoteSU.blockDay).getMessage
        stay using HostedNothing sending Error(channelId, message)
      } else if (!fullySignedLocalLCSS.verifyRemoteSig(remoteNodeId)) {
        val message = InvalidRemoteStateSignature(channelId, fullySignedLocalLCSS.hostedSigHash, remoteSU.localSigOfRemoteLCSS).getMessage
        stay using HostedNothing sending Error(channelId, message)
      } else {
        val commits = restoreEmptyCommits(fullySignedLocalLCSS, channelId, isHost = true)
        goto(NORMAL) using commits storing() sending commits.lastCrossSignedState.stateUpdate
      }

    // CLIENT FLOW

    case Event(cmd: CMD_HOSTED_INVOKE_CHANNEL, HostedNothing) =>
      val invoke = InvokeHostedChannel(nodeParams.chainHash, cmd.refundScriptPubKey)
      stay using HOSTED_DATA_CLIENT_WAIT_HOST_INIT(cmd.refundScriptPubKey) sending invoke

    case Event(CMD_HOSTED_MESSAGE(channelId, init: InitHostedChannel), data: HOSTED_DATA_CLIENT_WAIT_HOST_INIT) =>
      proceedOrGoCLOSED(channelId) {
        if (init.liabilityDeadlineBlockdays < minHostedLiabilityBlockdays) throw new ChannelException(channelId, "Their liability deadline is too low")
        if (init.initialClientBalanceMsat > init.channelCapacityMsat) throw new ChannelException(channelId, "Their init balance for us is larger than capacity")
        if (init.minimalOnchainRefundAmountSatoshis > minHostedOnChainRefund) throw new ChannelException(channelId, "Their minimal on-chain refund is too high")
        if (init.channelCapacityMsat < minHostedOnChainRefund * 2) throw new ChannelException(channelId, "Their proposed channel capacity is too low")
      } {
        val locallySignedLCSS =
          LastCrossSignedState(data.refundScriptPubKey, initHostedChannel = init, blockDay = nodeParams.currentBlockDay, localBalanceMsat = init.initialClientBalanceMsat,
            remoteBalanceMsat = init.channelCapacityMsat - init.initialClientBalanceMsat, localUpdates = 0L, remoteUpdates = 0L, incomingHtlcs = Nil, outgoingHtlcs = Nil,
            localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes).withLocalSigOfRemote(nodeParams.privateKey)
        val commits = restoreEmptyCommits(locallySignedLCSS, channelId, isHost = false)
        stay using HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE(commits) sending locallySignedLCSS.stateUpdate
      }

    case Event(CMD_HOSTED_MESSAGE(_, remoteSU: StateUpdate), data: HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE) =>
      val fullySignedLCSS = data.commits.lastCrossSignedState.copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)
      proceedOrGoCLOSED(data.commits.channelId) {
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

    case Event(CMD_HOSTED_MESSAGE(_, remoteLCSS: LastCrossSignedState), data: HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE) =>
      // Client has expected InitHostedChannel but got LastCrossSignedState so this channel exists already on host side
      val restoredCommits = restoreEmptyCommits(remoteLCSS.reverse, data.commits.channelId, isHost = false)
      val isLocalSigOk = remoteLCSS.verifyRemoteSig(nodeParams.privateKey.publicKey)
      val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(remoteNodeId)
      if (!isLocalSigOk) {
        localSuspend(restoredCommits, ChannelErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG)
      } else if (!isRemoteSigOk) {
        localSuspend(restoredCommits, ChannelErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
      } else if (remoteLCSS.incomingHtlcs.nonEmpty || remoteLCSS.outgoingHtlcs.nonEmpty) {
        localSuspend(restoredCommits, ChannelErrorCodes.ERR_HOSTED_IN_FLIGHT_HTLC_WHILE_RESTORING)
      } else {
        goto(NORMAL) using restoredCommits storing() sending restoredCommits.lastCrossSignedState
      }
  }

  when(SYNCING) {
    case Event(c: CurrentBlockCount, commits: HOSTED_DATA_COMMITMENTS) => handleNewBlock(c, commits)

    case Event(_: CMD_HOSTED_INPUT_DISCONNECTED, _: HOSTED_DATA_COMMITMENTS) => goto(OFFLINE)

    case Event(CMD_HOSTED_MESSAGE(_, _: InvokeHostedChannel), commits: HOSTED_DATA_COMMITMENTS) if commits.isHost =>
      // We are host and they have initialized a hosted channel, send our LCSS and wait for their reply
      forwarder ! commits.lastCrossSignedState
      if (commits.getError.isDefined) {
        goto(CLOSED) sending commits.getError.get
      } else {
        stay
      }

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
        val commits1 = syncAndResendAdds(commits, commits.futureUpdates, commits.lastCrossSignedState, commits.localSpec)
        goto(NORMAL) using commits1
      } else {
        // They have one of our future states or we are behind
        val commits1Opt = commits.findState(remoteLCSS).headOption

        commits1Opt match {
          case Some(commits1) =>
            val leftovers = commits.futureUpdates.diff(commits1.futureUpdates)
            // They have one of our future states, we also may have local pending UpdateAddHtlc
            val commits2 = syncAndResendAdds(commits1, leftovers, remoteLCSS.reverse, commits1.nextLocalSpec)
            goto(NORMAL) using resolveUpdates(commits1, commits2) storing()

          case None =>
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
    case Event(c: CurrentBlockCount, commits: HOSTED_DATA_COMMITMENTS) => handleNewBlock(c, commits)

    case Event(_: CMD_HOSTED_INPUT_DISCONNECTED, _: HOSTED_DATA_COMMITMENTS) => goto(OFFLINE)

    case Event(c: CMD_ADD_HTLC, commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.sendAdd(c, origin(c), nodeParams.currentBlockHeight)) match {
        case Success(Right((commitments1, add))) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, commitments1) sending add
        case Success(Left(error)) => handleCommandError(AddHtlcFailed(commits.channelId, c.paymentHash, error, origin(c), Some(commits.channelUpdate), Some(c)), c)
        case Failure(cause) => handleCommandError(AddHtlcFailed(commits.channelId, c.paymentHash, cause, origin(c), Some(commits.channelUpdate), Some(c)), c)
      }

    case Event(CMD_HOSTED_MESSAGE(_, add: UpdateAddHtlc), commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.receiveAdd(add)) match {
        case Success(commitments1) => stay using commitments1
        case Failure(cause) => localSuspend(commits, cause)
      }

    case Event(c: CMD_FULFILL_HTLC, commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.sendFulfill(c)) match {
        case Success((commitments1, fulfill)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, commitments1) sending fulfill
        case Failure(cause) =>
          // we can clean up the command right away in case of failure
          relayer ! CommandBuffer.CommandAck(commits.channelId, c.id)
          handleCommandError(cause, c)
      }

    case Event(CMD_HOSTED_MESSAGE(_, fulfill: UpdateFulfillHtlc), commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.receiveFulfill(fulfill)) match {
        case Success(Right((commitments1, origin, htlc))) =>
          // we forward preimages as soon as possible to the upstream channel because it allows us to pull funds
          relayer ! ForwardFulfill(fulfill, origin, htlc)
          stay using commitments1
        case Success(Left(_)) => stay
        case Failure(cause) => localSuspend(commits, cause)
      }

    case Event(c: CMD_FAIL_HTLC, commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.sendFail(c, nodeParams.privateKey)) match {
        case Success((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, commitments1) sending fail
        case Failure(cause) =>
          // we can clean up the command right away in case of failure
          relayer ! CommandBuffer.CommandAck(commits.channelId, c.id)
          handleCommandError(cause, c)
      }

    case Event(c: CMD_FAIL_MALFORMED_HTLC, commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.sendFailMalformed(c)) match {
        case Success((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, commitments1) sending fail
        case Failure(cause) =>
          // we can clean up the command right away in case of failure
          relayer ! CommandBuffer.CommandAck(commits.channelId, c.id)
          handleCommandError(cause, c)
      }

    case Event(CMD_HOSTED_MESSAGE(_, fail: UpdateFailHtlc), commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.receiveFail(fail)) match {
        case Success(Right((commitments1, _, _))) => stay using commitments1
        case Success(Left(_)) => stay
        case Failure(cause) => localSuspend(commits, cause)
      }

    case Event(CMD_HOSTED_MESSAGE(_, fail: UpdateFailMalformedHtlc), commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.receiveFailMalformed(fail)) match {
        case Success(Right((commitments1, _, _))) => stay using commitments1
        case Success(Left(_)) => stay
        case Failure(cause) => localSuspend(commits, cause)
      }

    case Event(CMD_SIGN, commits: HOSTED_DATA_COMMITMENTS) if commits.nextLocalUpdates.nonEmpty || commits.nextRemoteUpdates.nonEmpty =>
      stay storing() sending commits.nextLocalUnsignedLCSS(nodeParams.currentBlockDay).withLocalSigOfRemote(nodeParams.privateKey).stateUpdate

    case Event(CMD_HOSTED_MESSAGE(_, remoteSU: StateUpdate), commits: HOSTED_DATA_COMMITMENTS)
      // Remote peer may send a few identical updates, so only react if signature is different
      if commits.lastCrossSignedState.remoteSigOfLocal != remoteSU.localSigOfRemoteLCSS =>
      if (stateUpdateAttempts > 16) {
        localSuspend(commits, ChannelErrorCodes.ERR_HOSTED_TOO_MANY_STATE_UPDATES)
      } else {
        stateUpdateAttempts += 1
        val localLCSS1 = commits.nextLocalUnsignedLCSS(remoteSU.blockDay).copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)
        val commits1 = commits.copy(lastCrossSignedState = localLCSS1.withLocalSigOfRemote(nodeParams.privateKey), localSpec = commits.nextLocalSpec)
        val isBlockdayAcceptable = math.abs(remoteSU.blockDay - nodeParams.currentBlockDay) <= 1
        val isRemoteSigOk = localLCSS1.verifyRemoteSig(remoteNodeId)
        if (remoteSU.remoteUpdates < localLCSS1.localUpdates) {
          stay sending commits1.lastCrossSignedState.stateUpdate
        } else if (!isBlockdayAcceptable) {
          localSuspend(commits1, ChannelErrorCodes.ERR_HOSTED_WRONG_BLOCKDAY)
        } else if (!isRemoteSigOk) {
          localSuspend(commits1, ChannelErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
        } else {
          stateUpdateAttempts = 0
          val commits2 = resolveUpdates(commits, commits1)
          stay using commits2 storing() sending commits2.lastCrossSignedState.stateUpdate
        }
      }
  }

  when(CLOSED) {
    case Event(c: CurrentBlockCount, commits: HOSTED_DATA_COMMITMENTS) => handleNewBlock(c, commits)

    case Event(CMD_HOSTED_MESSAGE(_, remoteSO: StateOverride), commits: HOSTED_DATA_COMMITMENTS) if !commits.isHost =>
      val completeLocalLCSS =
        commits.lastCrossSignedState.copy(incomingHtlcs = Nil, outgoingHtlcs = Nil,
          localBalanceMsat = commits.lastCrossSignedState.initHostedChannel.channelCapacityMsat - remoteSO.localBalanceMsat,
          remoteBalanceMsat = remoteSO.localBalanceMsat, localUpdates = remoteSO.remoteUpdates, remoteUpdates = remoteSO.localUpdates,
          blockDay = remoteSO.blockDay, remoteSigOfLocal = remoteSO.localSigOfRemoteLCSS).withLocalSigOfRemote(nodeParams.privateKey)
      proceedOrGoCLOSED(commits.channelId) {
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
        val commits1 = restoreEmptyCommits(completeLocalLCSS, commits.channelId, isHost = false)
        goto(NORMAL) using commits1 storing() sending completeLocalLCSS.stateUpdate
      }

    case Event(CMD_HOSTED_OVERRIDE(_, newLocalBalance), commits: HOSTED_DATA_COMMITMENTS) if commits.isHost =>
      val locallySignedLocalLCSS =
        commits.lastCrossSignedState.copy(incomingHtlcs = Nil, outgoingHtlcs = Nil, localBalanceMsat = newLocalBalance,
          remoteBalanceMsat = commits.lastCrossSignedState.initHostedChannel.channelCapacityMsat - newLocalBalance,
          localUpdates = commits.lastCrossSignedState.localUpdates + 1, remoteUpdates = commits.lastCrossSignedState.remoteUpdates + 1,
          blockDay = nodeParams.currentBlockDay, remoteSigOfLocal = ByteVector64.Zeroes).withLocalSigOfRemote(nodeParams.privateKey)
      val localSO =
        StateOverride(blockDay = nodeParams.currentBlockDay, localBalanceMsat = locallySignedLocalLCSS.localBalanceMsat,
          localUpdates = locallySignedLocalLCSS.localUpdates, remoteUpdates = locallySignedLocalLCSS.remoteUpdates,
          localSigOfRemoteLCSS = locallySignedLocalLCSS.localSigOfRemote)
      stay using restoreEmptyCommits(locallySignedLocalLCSS, commits.channelId, isHost = true) sending localSO

    case Event(CMD_HOSTED_MESSAGE(_, remoteSU: StateUpdate), commits: HOSTED_DATA_COMMITMENTS) if commits.isHost =>
      val commits1 = commits.modify(_.lastCrossSignedState.remoteSigOfLocal).setTo(remoteSU.localSigOfRemoteLCSS)
      proceedOrGoCLOSED(commits1.channelId) {
        val isRemoteSigOk = commits1.lastCrossSignedState.verifyRemoteSig(remoteNodeId)
        val isBlockdayAcceptable = math.abs(remoteSU.blockDay - nodeParams.currentBlockDay) <= 1
        val isRightLocalUpdateNumber = remoteSU.localUpdates == commits1.lastCrossSignedState.remoteUpdates
        val isRightRemoteUpdateNumber = remoteSU.remoteUpdates == commits1.lastCrossSignedState.localUpdates
        if (!isRemoteSigOk) throw new ChannelException(commits1.channelId, "Provided remote override signature is wrong")
        if (!isRightLocalUpdateNumber) throw new ChannelException(commits1.channelId, "Provided local update number from remote override is wrong")
        if (!isRightRemoteUpdateNumber) throw new ChannelException(commits1.channelId, "Provided remote update number from remote override is wrong")
        if (!isBlockdayAcceptable) throw new ChannelException(commits1.channelId, "Remote override blockday is not acceptable")
      } {
        goto(NORMAL) using commits1 storing()
      }
  }

  onTransition {
    case state -> nextState =>
      nextStateData match {
        case commits: HOSTED_DATA_COMMITMENTS if state != NORMAL && nextState == NORMAL =>
          context.system.eventStream.publish(LocalChannelUpdate(self, commits.channelId, commits.channelUpdate.shortChannelId, remoteNodeId, None, commits.channelUpdate, commits))
          forwarder ! commits.channelUpdate
        case commits: HOSTED_DATA_COMMITMENTS if nextState != NORMAL =>
          context.system.eventStream.publish(LocalChannelDown(self, commits.channelId, commits.channelUpdate.shortChannelId, remoteNodeId))
        case _ =>
      }
  }

  type HostedFsmState = FSM.State[fr.acinq.eclair.channel.State, HostedData]

  implicit def state2mystate(state: HostedFsmState): MyState = MyState(state)

  case class MyState(state: HostedFsmState) {

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

  }

  def origin(c: CMD_ADD_HTLC): Origin = c.upstream match {
    case Left(id) => Local(id, Some(sender)) // we were the origin of the payment
    case Right(u) => Relayed(u.channelId, u.id, u.amountMsat, c.amount) // this is a relayed payment
  }

  def restoreEmptyCommits(localLCSS: LastCrossSignedState, channelId: ByteVector32, isHost: Boolean): HOSTED_DATA_COMMITMENTS = {
    val localCommitmentSpec = CommitmentSpec(htlcs = Set.empty, feeratePerKw = 0L, localLCSS.localBalanceMsat, localLCSS.remoteBalanceMsat)
    val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, randomHostedChanShortId, minHostedCltvDelta,
      localLCSS.initHostedChannel.htlcMinimumMsat, nodeParams.feeBase, nodeParams.feeProportionalMillionth, localLCSS.initHostedChannel.channelCapacityMsat)
    HOSTED_DATA_COMMITMENTS(ChannelVersion.STANDARD, localLCSS, futureUpdates = Nil, localCommitmentSpec, originChannels = Map.empty,
      channelId, isHost, channelUpdate, localError = None, remoteError = None)
  }

  def proceedOrGoCLOSED(channelId: ByteVector32)(check: => Unit)(whenPassed: => HostedFsmState): HostedFsmState =
    Try(check) match {
      case Failure(reason) =>
        log.error(s"sanity check failed, reason=${reason.getMessage}, suspending with data=$stateData")
        goto(CLOSED) sending Error(channelId, ByteVector.view(reason.getMessage.getBytes))
      case _ =>
        whenPassed
    }

  def localSuspend(commits: HOSTED_DATA_COMMITMENTS, err: Throwable): HostedFsmState =
    localSuspend(commits, ByteVector.view(err.getMessage.getBytes))

  def localSuspend(commits: HOSTED_DATA_COMMITMENTS, errCode: ByteVector): HostedFsmState = {
    val localError = Error(channelId = commits.channelId, data = errCode)
    val commits1 = commits.copy(localError = Some(localError))
    goto(CLOSED) using commits1 storing() sending localError
  }

  def handleCommandSuccess(sender: ActorRef, commits: HOSTED_DATA_COMMITMENTS): HostedFsmState =
    stay using commits replying "ok"

  def syncAndResendAdds(commits: HOSTED_DATA_COMMITMENTS, leftovers: List[Either[UpdateMessage, UpdateMessage]], lcss: LastCrossSignedState, spec: CommitmentSpec): HOSTED_DATA_COMMITMENTS = {
    val outgoingAddLeftovers = leftovers.collect { case Left(localAdd: UpdateAddHtlc) => localAdd }
    val outgoingAddLeftovers1 = for (idx <- outgoingAddLeftovers.indices.toList) yield outgoingAddLeftovers(idx).copy(id = lcss.localUpdates + 1 + idx)
    val commits1 = commits.copy(futureUpdates = outgoingAddLeftovers1.map(Left.apply), lastCrossSignedState = lcss, localSpec = spec)
    for (msg <- lcss +: outgoingAddLeftovers1) forwarder ! msg
    if (outgoingAddLeftovers1.nonEmpty) self ! CMD_SIGN
    commits1
  }

  def handleCommandError(cause: Throwable, cmd: Command): HostedFsmState = {
    log.warning(s"${cause.getMessage} while processing cmd=${cmd.getClass.getSimpleName} in state=$stateName")
    stay replying Status.Failure(cause)
  }

  def resolveUpdates(commits: HOSTED_DATA_COMMITMENTS, nextCommits: HOSTED_DATA_COMMITMENTS): HOSTED_DATA_COMMITMENTS = {
    commits.nextLocalUpdates.collect {
      case u: UpdateFailHtlc => relayer ! CommandBuffer.CommandAck(u.channelId, u.id)
      case u: UpdateFulfillHtlc => relayer ! CommandBuffer.CommandAck(u.channelId, u.id)
      case u: UpdateFailMalformedHtlc => relayer ! CommandBuffer.CommandAck(u.channelId, u.id)
    }

    commits.nextRemoteUpdates.collect {
      case add: UpdateAddHtlc =>
        relayer ! ForwardAdd(add)
      case fail: UpdateFailHtlc =>
        val add = commits.localSpec.findHtlcById(fail.id, OUT).get.add
        relayer ! ForwardFail(fail, commits.originChannels(fail.id), add)
      case fail: UpdateFailMalformedHtlc =>
        val add = commits.localSpec.findHtlcById(fail.id, OUT).get.add
        relayer ! ForwardFailMalformed(fail, commits.originChannels(fail.id), add)
    }

    val completedOutgoingHtlcs = commits.localSpec.htlcs.filter(_.direction == OUT).map(_.add.id) -- nextCommits.localSpec.htlcs.filter(_.direction == OUT).map(_.add.id)
    nextCommits.copy(originChannels = nextCommits.originChannels -- completedOutgoingHtlcs, futureUpdates = Nil)
  }

  def handleNewBlock(c: CurrentBlockCount, commits: HOSTED_DATA_COMMITMENTS): HostedFsmState = {
    val timedoutHtlcs = commits.timedOutOutgoingHtlcs(c.blockCount)

    for {
      add <- timedoutHtlcs
      origin <- commits.originChannels.get(add.id)
      reason = HtlcTimedout(commits.channelId, Set(add))
      error = AddHtlcFailed(commits.channelId, add.paymentHash, reason, origin, None, None)
    } relayer ! error

    if (timedoutHtlcs.isEmpty || commits.getError.isDefined) stay
    else localSuspend(commits, ChannelErrorCodes.ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC)
  }

  initialize()
}