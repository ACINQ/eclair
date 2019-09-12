package fr.acinq.eclair.channel

import com.softwaremill.quicklens._
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId}
import fr.acinq.eclair.payment.Origin
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IN, OUT}
import fr.acinq.eclair.wire.{ChannelUpdate, Error, InFlightHtlc, LastCrossSignedState, LightningMessage, UpdateAddHtlc, UpdateMessage}
import scodec.bits.ByteVector

sealed trait HostedCommand
case object CMD_KILL_IDLE_HOSTED_CHANNELS extends HostedCommand
case class CMD_HOSTED_MESSAGE(channelId: ByteVector32, remoteNodeId: PublicKey, message: LightningMessage) extends HostedCommand
case class CMD_REGISTER_HOSTED_SHORT_CHANNEL_ID(hc: HOSTED_DATA_COMMITMENTS) extends HostedCommand

sealed trait HostedData
case object HostedNothing extends HostedData
case class HOSTED_DATA_WAIT_REMOTE_REPLY(refundScriptPubKey: ByteVector) extends HostedData {
  require(Helpers.Closing.isValidFinalScriptPubkey(refundScriptPubKey), "invalid refundScriptPubKey when opening a hosted channel")
}

case class HOSTED_DATA_WAIT_REMOTE_STATE_UPDATE(hostedDataCommits: HOSTED_DATA_COMMITMENTS) extends HostedData

case class HOSTED_DATA_COMMITMENTS(channelVersion: ChannelVersion,
                                   shortChannelId: ShortChannelId,
                                   lastCrossSignedState: LastCrossSignedState,
                                   allLocalUpdates: Long,
                                   allRemoteUpdates: Long,
                                   localChanges: LocalChanges,
                                   remoteUpdates: List[UpdateMessage],
                                   localSpec: CommitmentSpec,
                                   originChannels: Map[Long, Origin],
                                   channelId: ByteVector32,
                                   isHost: Boolean,
                                   updateOpt: Option[ChannelUpdate],
                                   localError: Option[Error],
                                   remoteError: Option[Error]) extends ChannelCommitments with HostedData { me =>

  lazy val nextLocalReduced: CommitmentSpec = CommitmentSpec.reduce(localSpec, localChanges.proposed ++ localChanges.signed, remoteUpdates)

  lazy val availableBalanceForSend: MilliSatoshi = nextLocalReduced.toLocal

  lazy val availableBalanceForReceive: MilliSatoshi = nextLocalReduced.toRemote

  def isInErrorState: Boolean = localError.isDefined || remoteError.isDefined

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
}