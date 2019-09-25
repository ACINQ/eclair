package fr.acinq.eclair.channel

import akka.actor.ActorRef
import fr.acinq.eclair._
import com.softwaremill.quicklens._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.payment.Origin
import fr.acinq.eclair.transactions.{CommitmentSpec, IN, OUT}
import fr.acinq.eclair.wire._
import scodec.bits.ByteVector

sealed trait HostedCommand
sealed trait HasHostedChanIdCommand extends HostedCommand {
  def channelId: ByteVector32
}
case object CMD_HOSTED_KILL_IDLE_CHANNELS extends HostedCommand
case class CMD_HOSTED_INPUT_DISCONNECTED(channelId: ByteVector32) extends HasHostedChanIdCommand
case class CMD_HOSTED_INPUT_RECONNECTED(channelId: ByteVector32, remoteNodeId: PublicKey, transport: ActorRef) extends HasHostedChanIdCommand
case class CMD_HOSTED_INVOKE_CHANNEL(channelId: ByteVector32, remoteNodeId: PublicKey, refundScriptPubKey: ByteVector) extends HasHostedChanIdCommand
case class CMD_HOSTED_REGISTER_SHORT_CHANNEL_ID(channelId: ByteVector32, remoteNodeId: PublicKey, hostedCommits: HOSTED_DATA_COMMITMENTS) extends HasHostedChanIdCommand
case class CMD_HOSTED_MESSAGE(channelId: ByteVector32, message: LightningMessage) extends HasHostedChanIdCommand
case class CMD_HOSTED_OVERRIDE(channelId: ByteVector32, newLocalBalance: MilliSatoshi) extends HasHostedChanIdCommand

sealed trait HostedData
case object HostedNothing extends HostedData
case class HOSTED_DATA_CLIENT_WAIT_HOST_INIT(refundScriptPubKey: ByteVector) extends HostedData {
  require(Helpers.Closing.isValidFinalScriptPubkey(refundScriptPubKey), "invalid refundScriptPubKey when opening a hosted channel")
}

case class HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE(commits: HOSTED_DATA_COMMITMENTS) extends HostedData

case class HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE(init: InitHostedChannel,
                                                     refundScriptPubKey: ByteVector,
                                                     waitingForShortId: Boolean = false) extends HostedData {
  require(Helpers.Closing.isValidFinalScriptPubkey(refundScriptPubKey), "invalid refundScriptPubKey when opening a hosted channel")
}

case class HOSTED_DATA_COMMITMENTS(channelVersion: ChannelVersion,
                                   lastCrossSignedState: LastCrossSignedState,
                                   allLocalUpdates: Long,
                                   allRemoteUpdates: Long,
                                   localUpdates: List[UpdateMessage],
                                   remoteUpdates: List[UpdateMessage],
                                   localSpec: CommitmentSpec,
                                   originChannels: Map[Long, Origin],
                                   channelId: ByteVector32,
                                   isHost: Boolean,
                                   channelUpdateOpt: Option[ChannelUpdate],
                                   localError: Option[Error],
                                   remoteError: Option[Error]) extends ChannelCommitments with HostedData { me =>

  lazy val nextLocalSpec: CommitmentSpec = CommitmentSpec.reduce(localSpec, localUpdates, remoteUpdates)

  lazy val availableBalanceForSend: MilliSatoshi = nextLocalSpec.toLocal

  lazy val availableBalanceForReceive: MilliSatoshi = nextLocalSpec.toRemote

  lazy val withResetRemoteUpdates: HOSTED_DATA_COMMITMENTS = copy(remoteUpdates = Nil, allRemoteUpdates = lastCrossSignedState.remoteUpdates)

  lazy val withResetLocalUpdates: HOSTED_DATA_COMMITMENTS = copy(originChannels = originChannels -- localUpdates.collect { case add: UpdateAddHtlc => add.id }, localUpdates = Nil, allLocalUpdates = lastCrossSignedState.localUpdates)

  override val announceChannel: Boolean = false

  def getError: Option[Error] = localError.orElse(remoteError)

  def addRemoteProposal(update: UpdateMessage): HOSTED_DATA_COMMITMENTS = me.modify(_.remoteUpdates).using(_ :+ update).modify(_.allRemoteUpdates).using(_ + 1)

  def addLocalProposal(update: UpdateMessage): HOSTED_DATA_COMMITMENTS = me.modify(_.localUpdates).using(_ :+ update).modify(_.allLocalUpdates).using(_ + 1)

  def timedOutOutgoingHtlcs(blockheight: Long): Set[UpdateAddHtlc] =
    localSpec.htlcs.collect { case htlc if htlc.direction == OUT && blockheight >= htlc.add.cltvExpiry.toLong => htlc.add } ++
      nextLocalSpec.htlcs.collect { case htlc if htlc.direction == OUT && blockheight >= htlc.add.cltvExpiry.toLong => htlc.add }

  def nextLocalLCSS(blockDay: Long): LastCrossSignedState = {
    val (inHtlcs, outHtlcs) = nextLocalSpec.htlcs.toList.partition(_.direction == IN)
    LastCrossSignedState(lastCrossSignedState.refundScriptPubKey, lastCrossSignedState.initHostedChannel,
      blockDay, nextLocalSpec.toLocal, nextLocalSpec.toRemote, allLocalUpdates, allRemoteUpdates, inHtlcs.map(_.add), outHtlcs.map(_.add),
      localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes)
  }

  def sendAdd(cmd: CMD_ADD_HTLC, origin: Origin, blockHeight: Long): Either[ChannelException, (HOSTED_DATA_COMMITMENTS, UpdateAddHtlc)] = {
    val minExpiry = Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(blockHeight)
    if (cmd.cltvExpiry < minExpiry) {
      return Left(ExpiryTooSmall(channelId, minimum = minExpiry, actual = cmd.cltvExpiry, blockCount = blockHeight))
    }

    val maxExpiry = Channel.MAX_CLTV_EXPIRY_DELTA.toCltvExpiry(blockHeight)
    if (cmd.cltvExpiry >= maxExpiry) {
      return Left(ExpiryTooBig(channelId, maximum = maxExpiry, actual = cmd.cltvExpiry, blockCount = blockHeight))
    }

    if (cmd.amount < lastCrossSignedState.initHostedChannel.htlcMinimumMsat) {
      return Left(HtlcValueTooSmall(channelId, minimum = lastCrossSignedState.initHostedChannel.htlcMinimumMsat, actual = cmd.amount))
    }

    val add = UpdateAddHtlc(channelId, allLocalUpdates + 1, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    val commitments1 = addLocalProposal(add).copy(originChannels = originChannels + (add.id -> origin))
    val outgoingHtlcs = commitments1.nextLocalSpec.htlcs.filter(_.direction == OUT)

    if (commitments1.nextLocalSpec.toLocal < 0.msat) {
      return Left(InsufficientFunds(channelId, amount = cmd.amount, missing = -commitments1.nextLocalSpec.toLocal.truncateToSatoshi, reserve = 0 sat, fees = 0 sat))
    }

    val htlcValueInFlight = outgoingHtlcs.map(_.add.amountMsat).sum
    if (lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat < htlcValueInFlight) {
      return Left(HtlcValueTooHighInFlight(channelId, maximum = lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat, actual = htlcValueInFlight))
    }

    if (outgoingHtlcs.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) {
      return Left(TooManyAcceptedHtlcs(channelId, maximum = lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs))
    }

    Right(commitments1, add)
  }

  def receiveAdd(add: UpdateAddHtlc): HOSTED_DATA_COMMITMENTS = {
    if (add.id != allRemoteUpdates + 1) {
      throw UnexpectedHtlcId(channelId, expected = allRemoteUpdates + 1, actual = add.id)
    }

    if (add.amountMsat < lastCrossSignedState.initHostedChannel.htlcMinimumMsat) {
      throw HtlcValueTooSmall(channelId, minimum = lastCrossSignedState.initHostedChannel.htlcMinimumMsat, actual = add.amountMsat)
    }

    val commitments1 = addRemoteProposal(add)
    val incomingHtlcs = commitments1.nextLocalSpec.htlcs.filter(_.direction == IN)

    if (commitments1.nextLocalSpec.toRemote < 0.msat) {
      throw InsufficientFunds(channelId, amount = add.amountMsat, missing = -commitments1.nextLocalSpec.toRemote.truncateToSatoshi, reserve = 0 sat, fees = 0 sat)
    }

    val htlcValueInFlight = incomingHtlcs.map(_.add.amountMsat).sum
    if (lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat < htlcValueInFlight) {
      throw HtlcValueTooHighInFlight(channelId, maximum = lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat, actual = htlcValueInFlight)
    }

    if (incomingHtlcs.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) {
      throw TooManyAcceptedHtlcs(channelId, maximum = lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs)
    }

    commitments1
  }

  def sendFulfill(cmd: CMD_FULFILL_HTLC): (HOSTED_DATA_COMMITMENTS, UpdateFulfillHtlc) =
    localSpec.findHtlcById(cmd.id, IN) match {
      case Some(htlc) if Commitments.alreadyProposed(localUpdates, htlc.add.id) =>
        // we have already sent a fail/fulfill for this htlc
        throw UnknownHtlcId(channelId, cmd.id)
      case Some(htlc) if htlc.add.paymentHash == Crypto.sha256(cmd.r) =>
        val fulfill = UpdateFulfillHtlc(channelId, cmd.id, cmd.r)
        (addLocalProposal(fulfill), fulfill)
      case Some(_) => throw InvalidHtlcPreimage(channelId, cmd.id)
      case None => throw UnknownHtlcId(channelId, cmd.id)
    }

  def receiveFulfill(fulfill: UpdateFulfillHtlc): Either[HOSTED_DATA_COMMITMENTS, (HOSTED_DATA_COMMITMENTS, Origin, UpdateAddHtlc)] =
    localSpec.findHtlcById(fulfill.id, OUT) match {
      case Some(htlc) if htlc.add.paymentHash == fulfill.paymentHash => Right((addRemoteProposal(fulfill), originChannels(fulfill.id), htlc.add))
      case Some(_) => throw InvalidHtlcPreimage(channelId, fulfill.id)
      case None => throw UnknownHtlcId(channelId, fulfill.id)
    }

  def sendFail(cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): (HOSTED_DATA_COMMITMENTS, UpdateFailHtlc) =
    localSpec.findHtlcById(cmd.id, IN) match {
      case Some(htlc) if Commitments.alreadyProposed(localUpdates, htlc.add.id) =>
        // we have already sent a fail/fulfill for this htlc
        throw UnknownHtlcId(channelId, cmd.id)
      case Some(htlc) =>
        val fail = failHtlc(nodeSecret, cmd, htlc.add)
        (addLocalProposal(fail), fail)
      case None => throw UnknownHtlcId(channelId, cmd.id)
    }

  def sendFailMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): (HOSTED_DATA_COMMITMENTS, UpdateFailMalformedHtlc) = {
    // BADONION bit must be set in failure_code
    if ((cmd.failureCode & FailureMessageCodecs.BADONION) == 0) {
      throw InvalidFailureCode(channelId)
    }
    localSpec.findHtlcById(cmd.id, IN) match {
      case Some(htlc) if Commitments.alreadyProposed(localUpdates, htlc.add.id) =>
        // we have already sent a fail/fulfill for this htlc
        throw UnknownHtlcId(channelId, cmd.id)
      case Some(_) =>
        val fail = UpdateFailMalformedHtlc(channelId, cmd.id, cmd.onionHash, cmd.failureCode)
        (addLocalProposal(fail), fail)
      case None => throw UnknownHtlcId(channelId, cmd.id)
    }
  }

  def receiveFail(fail: UpdateFailHtlc): Either[HOSTED_DATA_COMMITMENTS, (HOSTED_DATA_COMMITMENTS, Origin, UpdateAddHtlc)] =
    localSpec.findHtlcById(fail.id, OUT) match {
      case Some(htlc) => Right((addRemoteProposal(fail), originChannels(fail.id), htlc.add))
      case None => throw UnknownHtlcId(channelId, fail.id)
    }

  def receiveFailMalformed(fail: UpdateFailMalformedHtlc): Either[HOSTED_DATA_COMMITMENTS, (HOSTED_DATA_COMMITMENTS, Origin, UpdateAddHtlc)] = {
    // A receiving node MUST fail the channel if the BADONION bit in failure_code is not set for update_fail_malformed_htlc.
    if ((fail.failureCode & FailureMessageCodecs.BADONION) == 0) {
      throw InvalidFailureCode(channelId)
    }

    localSpec.findHtlcById(fail.id, OUT) match {
      case Some(htlc) => Right((addRemoteProposal(fail), originChannels(fail.id), htlc.add))
      case None => throw UnknownHtlcId(channelId, fail.id)
    }
  }
}