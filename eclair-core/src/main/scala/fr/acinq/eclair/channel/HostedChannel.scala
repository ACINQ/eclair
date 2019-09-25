package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM, Props, Status}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.wire._
import fr.acinq.eclair._
import com.softwaremill.quicklens._
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.payment.{CommandBuffer, ForwardAdd, ForwardFail, ForwardFailMalformed, ForwardFulfill, Local, Origin, Relayed}
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
      context.system.eventStream.publish(ChannelRestored(self, context.parent, remoteNodeId, isFunder = true, commits.channelId, commits))
      forwarder ! cmd.transport
      if (commits.isHost) {
        goto(SYNCING)
      } else if (commits.getError.isDefined) {
        goto(CLOSED) sending commits.getError.get
      } else {
        goto(SYNCING) sending InvokeHostedChannel(nodeParams.chainHash, commits.lastCrossSignedState.refundScriptPubKey)
      }

    case Event(commits1: HOSTED_DATA_COMMITMENTS, commits: HOSTED_DATA_COMMITMENTS) if commits.isHost => stay using commits1 storing()
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

    case Event(commits1: HOSTED_DATA_COMMITMENTS, commits: HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) if commits.waitingForShortId =>
      context.system.eventStream.publish(ShortChannelIdAssigned(self, commits1.channelId, commits1.channelUpdateOpt.get.shortChannelId))
      context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, commits1.channelId, commits1.channelId))
      goto(NORMAL) using commits1 storing() sending commits1.lastCrossSignedState.stateUpdate

    // CLIENT FLOW

    case Event(cmd: CMD_HOSTED_INVOKE_CHANNEL, HostedNothing) =>
      forwarder ! InvokeHostedChannel(nodeParams.chainHash, cmd.refundScriptPubKey)
      stay using HOSTED_DATA_CLIENT_WAIT_HOST_INIT(cmd.refundScriptPubKey)

    case Event(CMD_HOSTED_MESSAGE(channelId, msg: InitHostedChannel), data: HOSTED_DATA_CLIENT_WAIT_HOST_INIT) =>
      sanityCheck(channelId) {
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
      val localCompleteLCSS = data.commits.lastCrossSignedState.copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)
      sanityCheck(data.commits.channelId) {
        val isRightRemoteUpdateNumber = data.commits.lastCrossSignedState.remoteUpdates == remoteSU.localUpdates
        val isRightLocalUpdateNumber = data.commits.lastCrossSignedState.localUpdates == remoteSU.remoteUpdates
        val isBlockdayAcceptable = math.abs(remoteSU.blockDay - nodeParams.currentBlockDay) <= 1
        val isRemoteSigOk = localCompleteLCSS.verifyRemoteSig(remoteNodeId)

        if (!isBlockdayAcceptable) throw new ChannelException(data.commits.channelId, "Their blockday is wrong")
        if (!isRightRemoteUpdateNumber) throw new ChannelException(data.commits.channelId, "Their remote update number is wrong")
        if (!isRightLocalUpdateNumber) throw new ChannelException(data.commits.channelId, "Their local update number is wrong")
        if (!isRemoteSigOk) throw new ChannelException(data.commits.channelId, "Their signature is wrong")
      } {
        val commits1 = data.commits.copy(lastCrossSignedState = localCompleteLCSS)
        goto(NORMAL) using commits1 storing()
      }

    case Event(CMD_HOSTED_MESSAGE(_, remoteLCSS: LastCrossSignedState), data: HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE) =>
      // Client has expected InitHostedChannel but got LastCrossSignedState so this channel exists already on host side
      // make sure signatures match and if so then become OPEN using remotely supplied state data
      val restoredHostedCommits = restoreEmptyCommits(remoteLCSS.reverse, data.commits.channelId, isHost = false)
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

    case Event(CMD_HOSTED_MESSAGE(_, remoteSU: StateUpdate), commits: HOSTED_DATA_COMMITMENTS) if commits.isHost =>
      // If client sends StateUpdate after sending InvokeHostedChannel this means we both have the same last state
      if (remoteSU.localSigOfRemoteLCSS != commits.lastCrossSignedState.remoteSigOfLocal) {
        localSuspend(commits, ChannelErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
      } else if (commits.getError.isDefined) {
        goto(CLOSED) sending commits.getError.get
      } else {
        goto(NORMAL) using commits.withResetRemoteUpdates resending commits.localUpdates
      }

    case Event(CMD_HOSTED_MESSAGE(_, remoteLCSS: LastCrossSignedState), commits: HOSTED_DATA_COMMITMENTS) =>
      val isLocalSigOk = remoteLCSS.verifyRemoteSig(nodeParams.privateKey.publicKey)
      val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(remoteNodeId)

      val weAreAhead = commits.lastCrossSignedState.isAhead(remoteLCSS)
      val theyHaveCurrent = commits.lastCrossSignedState.isEven(remoteLCSS)
      val theyHaveNext = commits.nextLocalLCSS(nodeParams.currentBlockDay).isEven(remoteLCSS)

      if (commits.getError.isDefined) {
        goto(CLOSED) sending commits.getError.get
      } else if (!isLocalSigOk) {
        localSuspend(commits, ChannelErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG)
      } else if (!isRemoteSigOk) {
        localSuspend(commits, ChannelErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
      } else if (theyHaveCurrent) {
        forwarder ! commits.lastCrossSignedState.stateUpdate
        if (commits.isHost) forwarder ! commits.channelUpdateOpt.get
        goto(NORMAL) using commits.withResetRemoteUpdates resending commits.localUpdates
      } else if (theyHaveNext) {
        if (commits.isHost) forwarder ! commits.channelUpdateOpt.get
        val data1 = commits.copy(lastCrossSignedState = remoteLCSS.reverse, localSpec = commits.nextLocalSpec)
        doStateUpdate(commits, data1.withResetRemoteUpdates.withResetLocalUpdates)
      } else if (weAreAhead) {
        forwarder ! commits.lastCrossSignedState
        if (commits.isHost) forwarder ! commits.channelUpdateOpt.get
        goto(NORMAL) using commits.withResetRemoteUpdates resending commits.localUpdates
      } else {
        // We are far behind, restore state from their data
        val data1 = restoreEmptyCommits(remoteLCSS.reverse, commits.channelId, commits.isHost)
        if (remoteLCSS.incomingHtlcs.nonEmpty || remoteLCSS.outgoingHtlcs.nonEmpty) {
          localSuspend(data1, ChannelErrorCodes.ERR_HOSTED_IN_FLIGHT_HTLC_WHILE_RESTORING)
        } else {
          if (data1.isHost) context.parent ! CMD_HOSTED_REGISTER_SHORT_CHANNEL_ID(data1.channelId, remoteNodeId, data1)
          goto(NORMAL) using data1 storing() sending data1.lastCrossSignedState.stateUpdate
        }
      }
  }

  when(NORMAL) {
    case Event(c: CurrentBlockCount, commits: HOSTED_DATA_COMMITMENTS) => handleNewBlock(c, commits)

    case Event(_: CMD_HOSTED_INPUT_DISCONNECTED, _: HOSTED_DATA_COMMITMENTS) => goto(OFFLINE)

    case Event(commits1: HOSTED_DATA_COMMITMENTS, commits: HOSTED_DATA_COMMITMENTS) if commits.isHost =>
      context.system.eventStream.publish(ShortChannelIdAssigned(self, commits1.channelId, commits1.channelUpdateOpt.get.shortChannelId))
      forwarder ! commits1.channelUpdateOpt.get
      stay using commits1 sending commits1.channelUpdateOpt.get

    case Event(c: CMD_ADD_HTLC, commits: HOSTED_DATA_COMMITMENTS) =>
      Try(commits.sendAdd(c, origin(c), nodeParams.currentBlockHeight)) match {
        case Success(Right((commitments1, add))) =>
          if (c.commit) self ! CMD_SIGN
          handleCommandSuccess(sender, commitments1) sending add
        case Success(Left(error)) => handleCommandError(AddHtlcFailed(commits.channelId, c.paymentHash, error, origin(c), commits.channelUpdateOpt, Some(c)), c)
        case Failure(cause) => handleCommandError(AddHtlcFailed(commits.channelId, c.paymentHash, cause, origin(c), commits.channelUpdateOpt, Some(c)), c)
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

    case Event(CMD_SIGN, commits: HOSTED_DATA_COMMITMENTS) if commits.localUpdates.nonEmpty || commits.remoteUpdates.nonEmpty =>
      // Store not yet cross-signed updates because once local sig is sent we don't know if they have a new complete state
      val nextLocalSU = commits.nextLocalLCSS(nodeParams.currentBlockDay).withLocalSigOfRemote(nodeParams.privateKey).stateUpdate
      commits.localUpdates.collect {
        case u: UpdateFailHtlc => relayer ! CommandBuffer.CommandAck(u.channelId, u.id)
        case u: UpdateFulfillHtlc => relayer ! CommandBuffer.CommandAck(u.channelId, u.id)
        case u: UpdateFailMalformedHtlc => relayer ! CommandBuffer.CommandAck(u.channelId, u.id)
      }
      stay storing() sending nextLocalSU

    case Event(CMD_HOSTED_MESSAGE(_, remoteSU: StateUpdate), commits: HOSTED_DATA_COMMITMENTS)
      // Remote peer may send a few identical updates, so only react if signature is different
      if commits.lastCrossSignedState.remoteSigOfLocal != remoteSU.localSigOfRemoteLCSS =>

      if (stateUpdateAttempts > 16) {
        localSuspend(commits, ChannelErrorCodes.ERR_HOSTED_TOO_MANY_STATE_UPDATES)
      } else {
        stateUpdateAttempts += 1
        val localLCSS1 = commits.nextLocalLCSS(remoteSU.blockDay).copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)
        val data1 = commits.copy(lastCrossSignedState = localLCSS1.withLocalSigOfRemote(nodeParams.privateKey), localSpec = commits.nextLocalSpec)
        val isBlockdayAcceptable = math.abs(remoteSU.blockDay - nodeParams.currentBlockDay) <= 1
        val isRemoteSigOk = localLCSS1.verifyRemoteSig(remoteNodeId)
        if (remoteSU.remoteUpdates < localLCSS1.localUpdates) {
          stay sending data1.lastCrossSignedState.stateUpdate
        } else if (!isBlockdayAcceptable) {
          localSuspend(data1, ChannelErrorCodes.ERR_HOSTED_WRONG_BLOCKDAY)
        } else if (!isRemoteSigOk) {
          localSuspend(data1, ChannelErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
        } else {
          doStateUpdate(commits, data1.withResetRemoteUpdates.withResetLocalUpdates)
        }
      }
  }

  when(CLOSED) {
    case Event(c: CurrentBlockCount, commits: HOSTED_DATA_COMMITMENTS) => handleNewBlock(c, commits)

    case Event(CMD_HOSTED_MESSAGE(_, remoteSO: StateOverride), commits: HOSTED_DATA_COMMITMENTS) if !commits.isHost =>
      val recreatedCompleteLocalLCSS = commits.lastCrossSignedState.copy(incomingHtlcs = Nil, outgoingHtlcs = Nil,
          localBalanceMsat = commits.lastCrossSignedState.initHostedChannel.channelCapacityMsat - remoteSO.localBalanceMsat,
          remoteBalanceMsat = remoteSO.localBalanceMsat, localUpdates = remoteSO.remoteUpdates, remoteUpdates = remoteSO.localUpdates,
          blockDay = remoteSO.blockDay, remoteSigOfLocal = remoteSO.localSigOfRemoteLCSS).withLocalSigOfRemote(nodeParams.privateKey)

      sanityCheck(commits.channelId) {
        val isRemoteSigOk = recreatedCompleteLocalLCSS.verifyRemoteSig(remoteNodeId)
        val isBlockdayAcceptable = math.abs(remoteSO.blockDay - nodeParams.currentBlockDay) <= 1
        val isRightLocalUpdateNumber = remoteSO.localUpdates > commits.lastCrossSignedState.remoteUpdates
        val isRightRemoteUpdateNumber = remoteSO.remoteUpdates > commits.lastCrossSignedState.localUpdates
        if (!isRemoteSigOk) throw new ChannelException(commits.channelId, "Provided remote override signature is wrong")
        if (recreatedCompleteLocalLCSS.localBalanceMsat < 0.msat) throw new ChannelException(commits.channelId, "Provided updated local balance is larger than capacity")
        if (!isRightLocalUpdateNumber) throw new ChannelException(commits.channelId, "Provided local update number from remote override is wrong")
        if (!isRightRemoteUpdateNumber) throw new ChannelException(commits.channelId, "Provided remote update number from remote override is wrong")
        if (!isBlockdayAcceptable) throw new ChannelException(commits.channelId, "Remote override blockday is not acceptable")
      } {
        goto(NORMAL) using restoreEmptyCommits(recreatedCompleteLocalLCSS, commits.channelId, isHost = false) storing() sending recreatedCompleteLocalLCSS.stateUpdate
      }

    case Event(CMD_HOSTED_OVERRIDE(_, newLocalBalance), commits: HOSTED_DATA_COMMITMENTS) if commits.isHost =>
      val recreatedLocallySignedLocalLCSS = commits.lastCrossSignedState.copy(incomingHtlcs = Nil, outgoingHtlcs = Nil,
        localBalanceMsat = newLocalBalance, remoteBalanceMsat = commits.lastCrossSignedState.initHostedChannel.channelCapacityMsat - newLocalBalance,
        localUpdates = commits.lastCrossSignedState.localUpdates + 1, remoteUpdates = commits.lastCrossSignedState.remoteUpdates + 1,
        blockDay = nodeParams.currentBlockDay, remoteSigOfLocal = ByteVector64.Zeroes).withLocalSigOfRemote(nodeParams.privateKey)

      val localSO = StateOverride(blockDay = nodeParams.currentBlockDay, localBalanceMsat = recreatedLocallySignedLocalLCSS.localBalanceMsat,
        localUpdates = recreatedLocallySignedLocalLCSS.localUpdates, remoteUpdates = recreatedLocallySignedLocalLCSS.remoteUpdates,
        localSigOfRemoteLCSS = recreatedLocallySignedLocalLCSS.localSigOfRemote)

      stay sending localSO using restoreEmptyCommits(recreatedLocallySignedLocalLCSS, commits.channelId, isHost = true)

    case Event(CMD_HOSTED_MESSAGE(_, remoteSU: StateUpdate), commits: HOSTED_DATA_COMMITMENTS) if commits.isHost =>
      val commits1 = commits.modify(_.lastCrossSignedState.remoteSigOfLocal).setTo(remoteSU.localSigOfRemoteLCSS)
      sanityCheck(commits1.channelId) {
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
        case commits: HOSTED_DATA_COMMITMENTS =>
          log.debug(s"updating database record for hosted channelId={}", commits.channelId)
          nodeParams.db.hostedChannels.addOrUpdateChannel(commits)
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

    def resending(localUpdates: List[UpdateMessage]): HostedFsmState = {
      for (localUpdate <- localUpdates) forwarder ! localUpdate
      if (localUpdates.nonEmpty) self ! CMD_SIGN
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

  def sanityCheck(channelId: ByteVector32)(check: => Unit)(whenPassed: => HostedFsmState): HostedFsmState = {
    Try(check) match {
      case Failure(reason) =>
        log.debug(s"sanity check failed, reason=${reason.getMessage}")
        goto(CLOSED) sending Error(channelId, ByteVector.view(reason.getMessage.getBytes))
      case _ =>
        whenPassed
    }
  }

  def localSuspend(commits: HOSTED_DATA_COMMITMENTS, err: Throwable): HostedFsmState =
    localSuspend(commits, ByteVector.view(err.getMessage.getBytes))

  def localSuspend(commits: HOSTED_DATA_COMMITMENTS, errCode: ByteVector): HostedFsmState = {
    val localError = Error(channelId = commits.channelId, data = errCode)
    val hostedCommits1 = commits.copy(localError = Some(localError))
    goto(CLOSED) using hostedCommits1 storing() sending localError
  }

  def handleCommandSuccess(sender: ActorRef, commits: HOSTED_DATA_COMMITMENTS): HostedFsmState =
    stay using commits replying "ok"

  def handleCommandError(cause: Throwable, cmd: Command): HostedFsmState = {
    log.warning(s"${cause.getMessage} while processing cmd=${cmd.getClass.getSimpleName} in state=$stateName")
    stay replying Status.Failure(cause)
  }

  def doStateUpdate(commits: HOSTED_DATA_COMMITMENTS, commits1: HOSTED_DATA_COMMITMENTS): HostedFsmState = {
    commits.remoteUpdates.collect {
      case add: UpdateAddHtlc =>
        relayer ! ForwardAdd(add)
      case fail: UpdateFailHtlc =>
        val add = commits.localSpec.findHtlcById(fail.id, OUT).get.add
        relayer ! ForwardFail(fail, commits.originChannels(fail.id), add)
      case fail: UpdateFailMalformedHtlc =>
        val add = commits.localSpec.findHtlcById(fail.id, OUT).get.add
        relayer ! ForwardFailMalformed(fail, commits.originChannels(fail.id), add)
    }

    stateUpdateAttempts = 0
    goto(NORMAL) using commits1 storing() sending commits1.lastCrossSignedState.stateUpdate
  }

  def handleNewBlock(c: CurrentBlockCount, commits: HOSTED_DATA_COMMITMENTS): HostedFsmState = {
    val timedoutHtlcs = commits.timedOutOutgoingHtlcs(c.blockCount)
    if (timedoutHtlcs.nonEmpty) {
      for {
        add <- timedoutHtlcs
        origin <- commits.originChannels.get(add.id)
      } relayer ! Status.Failure(AddHtlcFailed(commits.channelId, add.paymentHash, HtlcTimedout(commits.channelId, Set(add)), origin, None, None))
      if (commits.getError.isDefined) {
        stay
      } else {
        localSuspend(commits, ChannelErrorCodes.ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC)
      }
    } else {
      stay
    }
  }
}