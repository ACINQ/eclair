package fr.acinq.eclair.channel

import akka.actor.ActorRef
import fr.acinq.eclair._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.payment.Origin
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IN, OUT}
import fr.acinq.eclair.wire._
import scodec.bits.ByteVector

sealed trait HostedCommand
sealed trait HasHostedChanIdCommand extends HostedCommand {
  def channelId: ByteVector32
}
case object CMD_HOSTED_REMOVE_IDLE_CHANNELS extends HostedCommand
case class CMD_HOSTED_INPUT_DISCONNECTED(channelId: ByteVector32) extends HasHostedChanIdCommand
case class CMD_HOSTED_INPUT_RECONNECTED(channelId: ByteVector32, remoteNodeId: PublicKey, transport: ActorRef) extends HasHostedChanIdCommand
case class CMD_HOSTED_INVOKE_CHANNEL(channelId: ByteVector32, remoteNodeId: PublicKey, refundScriptPubKey: ByteVector) extends HasHostedChanIdCommand
case class CMD_HOSTED_MESSAGE(channelId: ByteVector32, message: LightningMessage) extends HasHostedChanIdCommand
case class CMD_HOSTED_OVERRIDE(channelId: ByteVector32, newLocalBalance: MilliSatoshi) extends HasHostedChanIdCommand

sealed trait HostedData
case object HostedNothing extends HostedData
case class HOSTED_DATA_CLIENT_WAIT_HOST_INIT(refundScriptPubKey: ByteVector) extends HostedData {
  require(Helpers.Closing.isValidFinalScriptPubkey(refundScriptPubKey), "invalid refundScriptPubKey when opening a hosted channel")
}

case class HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE(commits: HOSTED_DATA_COMMITMENTS) extends HostedData

case class HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE(init: InitHostedChannel, refundScriptPubKey: ByteVector) extends HostedData {
  require(Helpers.Closing.isValidFinalScriptPubkey(refundScriptPubKey), "invalid refundScriptPubKey when opening a hosted channel")
}

case class HOSTED_DATA_COMMITMENTS(remoteNodeId: PublicKey,
                                   channelVersion: ChannelVersion,
                                   lastCrossSignedState: LastCrossSignedState,
                                   futureUpdates: List[Either[UpdateMessage, UpdateMessage]], // Left is local/outgoing, Right is remote/incoming
                                   localSpec: CommitmentSpec,
                                   originChannels: Map[Long, Origin],
                                   channelId: ByteVector32,
                                   isHost: Boolean,
                                   channelUpdate: ChannelUpdate,
                                   localError: Option[Error],
                                   remoteError: Option[Error],
                                   overriddenBalanceProposal: Option[MilliSatoshi] // Closed channel override can be initiated by Host, a new proposed balance should be retained if this happens
                                  ) extends ChannelCommitments with HostedData { me =>

  lazy val (nextLocalUpdates, nextRemoteUpdates, nextTotalLocal, nextTotalRemote) =
    futureUpdates.foldLeft((List.empty[UpdateMessage], List.empty[UpdateMessage], lastCrossSignedState.localUpdates, lastCrossSignedState.remoteUpdates)) {
      case ((localMessages, remoteMessages, totalLocalNumber, totalRemoteNumber), Left(msg)) => (localMessages :+ msg, remoteMessages, totalLocalNumber + 1, totalRemoteNumber)
      case ((localMessages, remoteMessages, totalLocalNumber, totalRemoteNumber), Right(msg)) => (localMessages, remoteMessages :+ msg, totalLocalNumber, totalRemoteNumber + 1)
    }

  lazy val nextLocalSpec: CommitmentSpec = CommitmentSpec.reduce(localSpec, nextLocalUpdates, nextRemoteUpdates)

  lazy val availableBalanceForSend: MilliSatoshi = nextLocalSpec.toLocal

  lazy val availableBalanceForReceive: MilliSatoshi = nextLocalSpec.toRemote

  lazy val currentAndNextInFlightHtlcs: Set[DirectedHtlc] = localSpec.htlcs ++ nextLocalSpec.htlcs

  override val announceChannel: Boolean = false

  def getError: Option[Error] = localError.orElse(remoteError)

  def addProposal(update: Either[UpdateMessage, UpdateMessage]): HOSTED_DATA_COMMITMENTS = copy(futureUpdates = futureUpdates :+ update)

  def timedOutOutgoingHtlcs(blockheight: Long): Set[UpdateAddHtlc] = currentAndNextInFlightHtlcs.collect { case htlc if htlc.direction == OUT && blockheight >= htlc.add.cltvExpiry.toLong => htlc.add }

  def allOutgoingHtlcsResolved(blockheight: Long): Boolean = currentAndNextInFlightHtlcs.collect { case htlc if htlc.direction == OUT => htlc.add } == timedOutOutgoingHtlcs(blockheight)

  def nextLocalUnsignedLCSS(blockDay: Long): LastCrossSignedState = {
    val (incomingHtlcs, outgoingHtlcs) = nextLocalSpec.htlcs.toList.partition(_.direction == IN)
    LastCrossSignedState(lastCrossSignedState.refundScriptPubKey, lastCrossSignedState.initHostedChannel, blockDay, nextLocalSpec.toLocal, nextLocalSpec.toRemote,
      nextTotalLocal, nextTotalRemote, incomingHtlcs.map(_.add), outgoingHtlcs.map(_.add), localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes)
  }

  // Rebuild all messaging and state history starting from local LCSS,
  // then try to find a future state with same update numbers as remote LCSS
  def findState(remoteLCSS: LastCrossSignedState): Seq[HOSTED_DATA_COMMITMENTS] = for {
    previousIndex <- futureUpdates.indices drop 1
    previousHC = me.copy(futureUpdates = futureUpdates take previousIndex)
    if previousHC.nextLocalUnsignedLCSS(remoteLCSS.blockDay).isEven(remoteLCSS)
  } yield previousHC

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

    val add = UpdateAddHtlc(channelId, nextTotalLocal + 1, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    val commits1 = addProposal(Left(add)).copy(originChannels = originChannels + (add.id -> origin))
    val outgoingHtlcs = commits1.nextLocalSpec.htlcs.filter(_.direction == OUT)

    if (commits1.nextLocalSpec.toLocal < 0.msat) {
      return Left(InsufficientFunds(channelId, amount = cmd.amount, missing = -commits1.nextLocalSpec.toLocal.truncateToSatoshi, reserve = 0 sat, fees = 0 sat))
    }

    val htlcValueInFlight = outgoingHtlcs.toList.map(_.add.amountMsat).sum
    if (lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat < htlcValueInFlight) {
      return Left(HtlcValueTooHighInFlight(channelId, maximum = lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat, actual = htlcValueInFlight))
    }

    if (outgoingHtlcs.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) {
      return Left(TooManyAcceptedHtlcs(channelId, maximum = lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs))
    }

    Right(commits1, add)
  }

  def receiveAdd(add: UpdateAddHtlc): HOSTED_DATA_COMMITMENTS = {
    if (add.id != nextTotalRemote + 1) {
      throw UnexpectedHtlcId(channelId, expected = nextTotalRemote + 1, actual = add.id)
    }

    if (add.amountMsat < lastCrossSignedState.initHostedChannel.htlcMinimumMsat) {
      throw HtlcValueTooSmall(channelId, minimum = lastCrossSignedState.initHostedChannel.htlcMinimumMsat, actual = add.amountMsat)
    }

    val commits1 = addProposal(Right(add))
    val incomingHtlcs = commits1.nextLocalSpec.htlcs.filter(_.direction == IN)

    if (commits1.nextLocalSpec.toRemote < 0.msat) {
      throw InsufficientFunds(channelId, amount = add.amountMsat, missing = -commits1.nextLocalSpec.toRemote.truncateToSatoshi, reserve = 0 sat, fees = 0 sat)
    }

    val htlcValueInFlight = incomingHtlcs.toList.map(_.add.amountMsat).sum
    if (lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat < htlcValueInFlight) {
      throw HtlcValueTooHighInFlight(channelId, maximum = lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat, actual = htlcValueInFlight)
    }

    if (incomingHtlcs.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) {
      throw TooManyAcceptedHtlcs(channelId, maximum = lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs)
    }

    commits1
  }

  def sendFulfill(cmd: CMD_FULFILL_HTLC): (HOSTED_DATA_COMMITMENTS, UpdateFulfillHtlc) =
    localSpec.findHtlcById(cmd.id, IN) match {
      case Some(htlc) if Commitments.alreadyProposed(nextLocalUpdates, htlc.add.id) =>
        // we have already sent a fail/fulfill for this htlc
        throw UnknownHtlcId(channelId, cmd.id)
      case Some(htlc) if htlc.add.paymentHash == Crypto.sha256(cmd.r) =>
        val fulfill = UpdateFulfillHtlc(channelId, cmd.id, cmd.r)
        (addProposal(Left(fulfill)), fulfill)
      case Some(_) => throw InvalidHtlcPreimage(channelId, cmd.id)
      case None => throw UnknownHtlcId(channelId, cmd.id)
    }

  def receiveFulfill(fulfill: UpdateFulfillHtlc): Either[HOSTED_DATA_COMMITMENTS, (HOSTED_DATA_COMMITMENTS, Origin, UpdateAddHtlc)] =
    localSpec.findHtlcById(fulfill.id, OUT) match {
      case Some(htlc) if htlc.add.paymentHash == fulfill.paymentHash => Right((addProposal(Right(fulfill)), originChannels(fulfill.id), htlc.add))
      case Some(_) => throw InvalidHtlcPreimage(channelId, fulfill.id)
      case None => throw UnknownHtlcId(channelId, fulfill.id)
    }

  def sendFail(cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): (HOSTED_DATA_COMMITMENTS, UpdateFailHtlc) =
    localSpec.findHtlcById(cmd.id, IN) match {
      case Some(htlc) if Commitments.alreadyProposed(nextLocalUpdates, htlc.add.id) =>
        // we have already sent a fail/fulfill for this htlc
        throw UnknownHtlcId(channelId, cmd.id)
      case Some(htlc) =>
        val fail = failHtlc(nodeSecret, cmd, htlc.add)
        (addProposal(Left(fail)), fail)
      case None => throw UnknownHtlcId(channelId, cmd.id)
    }

  def sendFailMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): (HOSTED_DATA_COMMITMENTS, UpdateFailMalformedHtlc) = {
    // BADONION bit must be set in failure_code
    if ((cmd.failureCode & FailureMessageCodecs.BADONION) == 0) {
      throw InvalidFailureCode(channelId)
    }
    localSpec.findHtlcById(cmd.id, IN) match {
      case Some(htlc) if Commitments.alreadyProposed(nextLocalUpdates, htlc.add.id) =>
        // we have already sent a fail/fulfill for this htlc
        throw UnknownHtlcId(channelId, cmd.id)
      case Some(_) =>
        val fail = UpdateFailMalformedHtlc(channelId, cmd.id, cmd.onionHash, cmd.failureCode)
        (addProposal(Left(fail)), fail)
      case None => throw UnknownHtlcId(channelId, cmd.id)
    }
  }

  def receiveFail(fail: UpdateFailHtlc): Either[HOSTED_DATA_COMMITMENTS, (HOSTED_DATA_COMMITMENTS, Origin, UpdateAddHtlc)] =
    localSpec.findHtlcById(fail.id, OUT) match {
      case Some(htlc) => Right((addProposal(Right(fail)), originChannels(fail.id), htlc.add))
      case None => throw UnknownHtlcId(channelId, fail.id)
    }

  def receiveFailMalformed(fail: UpdateFailMalformedHtlc): Either[HOSTED_DATA_COMMITMENTS, (HOSTED_DATA_COMMITMENTS, Origin, UpdateAddHtlc)] = {
    // A receiving node MUST fail the channel if the BADONION bit in failure_code is not set for update_fail_malformed_htlc.
    if ((fail.failureCode & FailureMessageCodecs.BADONION) == 0) {
      throw InvalidFailureCode(channelId)
    }

    localSpec.findHtlcById(fail.id, OUT) match {
      case Some(htlc) => Right((addProposal(Right(fail)), originChannels(fail.id), htlc.add))
      case None => throw UnknownHtlcId(channelId, fail.id)
    }
  }
}