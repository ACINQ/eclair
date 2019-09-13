package fr.acinq.eclair.channel

import akka.actor.ActorRef
import fr.acinq.eclair._
import fr.acinq.eclair.wire.ChannelCodecs.originCodec
import com.softwaremill.quicklens._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId}
import fr.acinq.eclair.payment.Origin
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IN, OUT}
import fr.acinq.eclair.wire._
import scodec.bits.ByteVector

sealed trait HostedCommand
sealed trait HasHostedChanIdCommand extends HostedCommand {
  def channelId: ByteVector32
}
case object CMD_KILL_IDLE_HOSTED_CHANNELS extends HostedCommand
case class CMD_HOSTED_INPUT_DISCONNECTED(channelId: ByteVector32) extends HasHostedChanIdCommand
case class CMD_HOSTED_INPUT_RECONNECTED(channelId: ByteVector32, remoteNodeId: PublicKey, transport: ActorRef) extends HasHostedChanIdCommand
case class CMD_INVOKE_HOSTED_CHANNEL(channelId: ByteVector32, remoteNodeId: PublicKey) extends HasHostedChanIdCommand
case class CMD_HOSTED_MESSAGE(channelId: ByteVector32, message: LightningMessage) extends HasHostedChanIdCommand
case class CMD_REGISTER_HOSTED_SHORT_CHANNEL_ID(channelId: ByteVector32, remoteNodeId: PublicKey, localLCSS: LastCrossSignedState) extends HasHostedChanIdCommand

sealed trait HostedData
case object HostedNothing extends HostedData
case object HostedWaitingForShortIdResult extends HostedData
case class HOSTED_DATA_CLIENT_WAIT_HOST_REPLY(refundScriptPubKey: ByteVector) extends HostedData {
  require(Helpers.Closing.isValidFinalScriptPubkey(refundScriptPubKey), "invalid refundScriptPubKey when opening a hosted channel")
}

case class HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE(hostedDataCommits: HOSTED_DATA_COMMITMENTS) extends HostedData

case class HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE(init: InitHostedChannel,
                                                     refundScriptPubKey: ByteVector) extends HostedData


case class HOSTED_DATA_COMMITMENTS(channelVersion: ChannelVersion,
                                   lastCrossSignedState: LastCrossSignedState,
                                   allLocalUpdates: Long,
                                   allRemoteUpdates: Long,
                                   localChanges: LocalChanges,
                                   remoteUpdates: List[UpdateMessage],
                                   localSpec: CommitmentSpec,
                                   originChannels: Map[Long, Origin],
                                   channelId: ByteVector32,
                                   isHost: Boolean,
                                   channelUpdate: ChannelUpdate,
                                   localError: Option[Error],
                                   remoteError: Option[Error]) extends ChannelCommitments with HostedData { me =>

  lazy val nextLocalReduced: CommitmentSpec = CommitmentSpec.reduce(localSpec, localChanges.proposed ++ localChanges.signed, remoteUpdates)

  lazy val availableBalanceForSend: MilliSatoshi = nextLocalReduced.toLocal

  lazy val availableBalanceForReceive: MilliSatoshi = nextLocalReduced.toRemote

  override val announceChannel: Boolean = false

  def getError: Option[Error] = localError.orElse(remoteError)

  def addRemoteProposal(update: UpdateMessage): HOSTED_DATA_COMMITMENTS =
    me.modify(_.remoteUpdates).using(_ :+ update).modify(_.allRemoteUpdates).using(_ + 1)

  def addLocalProposal(update: UpdateMessage): HOSTED_DATA_COMMITMENTS =
    me.modify(_.localChanges.proposed).using(_ :+ update).modify(_.allLocalUpdates).using(_ + 1)

  def resetUpdates: HOSTED_DATA_COMMITMENTS =
    copy(allLocalUpdates = lastCrossSignedState.localUpdates + localChanges.signed.size,
      allRemoteUpdates = lastCrossSignedState.remoteUpdates,
      remoteUpdates = Nil)

  def timedOutOutgoingHtlcs(blockheight: Long): Set[UpdateAddHtlc] =
    localSpec.htlcs.collect { case htlc if htlc.direction == OUT && blockheight >= htlc.add.cltvExpiry.toLong => htlc.add } ++
      localChanges.signed.collect { case add: UpdateAddHtlc => add }

  def nextLocalLCSS(blockDay: Long): LastCrossSignedState = {
    val (incomingHtlcs, outgoingHtlcs) = nextLocalReduced.htlcs.toList.partition(_.direction == IN)
    val incoming = for (DirectedHtlc(_, add) <- incomingHtlcs) yield InFlightHtlc(add.id, add.amountMsat, add.paymentHash, add.cltvExpiry)
    val outgoing = for (DirectedHtlc(_, add) <- outgoingHtlcs) yield InFlightHtlc(add.id, add.amountMsat, add.paymentHash, add.cltvExpiry)

    LastCrossSignedState(lastCrossSignedState.refundScriptPubKey,
      lastCrossSignedState.initHostedChannel,
      blockDay,
      nextLocalReduced.toLocal,
      nextLocalReduced.toRemote,
      allLocalUpdates,
      allRemoteUpdates,
      incoming,
      outgoing,
      ByteVector64.Zeroes)
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
    val outgoingHtlcs = commitments1.nextLocalReduced.htlcs.filter(_.direction == OUT)

    if (commitments1.nextLocalReduced.toLocal < 0.msat) {
      return Left(InsufficientFunds(channelId, amount = cmd.amount, missing = -commitments1.nextLocalReduced.toLocal.truncateToSatoshi, reserve = 0 sat, fees = 0 sat))
    }

    val htlcValueInFlight = outgoingHtlcs.map(_.add.amountMsat).sum
    if (lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat < htlcValueInFlight) {
      // TODO: this should be a specific UPDATE error
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
    val incomingHtlcs = commitments1.nextLocalReduced.htlcs.filter(_.direction == IN)

    if (commitments1.nextLocalReduced.toRemote < 0.msat) {
      throw InsufficientFunds(channelId, amount = add.amountMsat, missing = -commitments1.nextLocalReduced.toRemote.truncateToSatoshi, reserve = 0 sat, fees = 0 sat)
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
      case Some(htlc) if localChanges.alreadyProposed(htlc.add.id) =>
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
      case Some(htlc) if localChanges.alreadyProposed(htlc.add.id) =>
        // we have already sent a fail/fulfill for this htlc
        throw UnknownHtlcId(channelId, cmd.id)
      case Some(htlc) =>
        val fail = failHtlc(nodeSecret, cmd, htlc.add)
        val commitments1 = addLocalProposal(fail)
        (commitments1, fail)
      case None => throw UnknownHtlcId(channelId, cmd.id)
    }

  def sendFailMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): (HOSTED_DATA_COMMITMENTS, UpdateFailMalformedHtlc) = {
    // BADONION bit must be set in failure_code
    if ((cmd.failureCode & FailureMessageCodecs.BADONION) == 0) {
      throw InvalidFailureCode(channelId)
    }
    localSpec.findHtlcById(cmd.id, IN) match {
      case Some(htlc) if localChanges.alreadyProposed(htlc.add.id) =>
        // we have already sent a fail/fulfill for this htlc
        throw UnknownHtlcId(channelId, cmd.id)
      case Some(_) =>
        val fail = UpdateFailMalformedHtlc(channelId, cmd.id, cmd.onionHash, cmd.failureCode)
        val commitments1 = addLocalProposal(fail)
        (commitments1, fail)
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