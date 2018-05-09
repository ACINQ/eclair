package fr.acinq.eclair

import akka.actor.ActorRef
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.{Point, PublicKey}
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet, OutPoint, Satoshi, Transaction, TxOut}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.payment.Origin
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.{AcceptChannel, ChannelAnnouncement, ChannelUpdate, ClosingSigned, CommitSig, FundingCreated, FundingLocked, FundingSigned, IPv4, IPv6, Init, NodeAddress, OpenChannel, Padding, Shutdown, Tor2, Tor3, UpdateAddHtlc, UpdateFailHtlc, UpdateMessage}

object JsonSerializers {

  import upickle.default._

  implicit val txReadWrite: ReadWriter[Transaction] = readwriter[String].bimap[Transaction](_.toString(), Transaction.read(_))
  implicit val outpointReadWrite: ReadWriter[OutPoint] = readwriter[String].bimap[OutPoint](op => s"${op.hash}:${op.index}", s => ???)
  implicit val publicKeyReadWriter: ReadWriter[PublicKey] = readwriter[String].bimap[PublicKey](_.toString(), s => PublicKey(BinaryData(s)))
  implicit val pointReadWriter: ReadWriter[Point] = readwriter[String].bimap[Point](_.toString(), s => Point(BinaryData(s)))
  implicit val keyPathReadWriter: ReadWriter[DeterministicWallet.KeyPath] = readwriter[String].bimap[DeterministicWallet.KeyPath](_.toString(), _ => DeterministicWallet.KeyPath(0L :: Nil))
  implicit val binarydataReadWriter: ReadWriter[BinaryData] = readwriter[String].bimap[BinaryData](_.toString(), s => BinaryData(s))
  implicit val uint64ReadWriter: ReadWriter[UInt64] = readwriter[String].bimap[UInt64](_.toString, s => UInt64(s.toLong))
  implicit val localParamsReadWriter: ReadWriter[LocalParams] = macroRW
  implicit val remoteParamsReadWriter: ReadWriter[RemoteParams] = macroRW
  implicit val directionReadWriter: ReadWriter[Direction] = readwriter[String].bimap[Direction](f => f match {
    case IN =>
      "INNN"
    case OUT =>
      "OUTTT"
  }, _ match {
    case "IN" => IN
    case "OUT" => OUT
  })
  implicit val updateAddHtlcReadWriter: ReadWriter[UpdateAddHtlc] = macroRW
  implicit val updateFailHtlcReadWriter: ReadWriter[UpdateFailHtlc] = macroRW
  implicit val updateMessageReadWriter: ReadWriter[UpdateMessage] = ReadWriter.merge(macroRW[UpdateAddHtlc], macroRW[UpdateFailHtlc])
  implicit val directeddHtlcReadWriter: ReadWriter[DirectedHtlc] = macroRW
  implicit val commitmentSpecReadWriter: ReadWriter[CommitmentSpec] = macroRW
  implicit val localChangesReadWriter: ReadWriter[LocalChanges] = macroRW
  implicit val satoshiReadWriter: ReadWriter[Satoshi] = macroRW
  implicit val txOutReadWriter: ReadWriter[TxOut] = macroRW
  implicit val inputInfoReadWriter: ReadWriter[InputInfo] = macroRW
  implicit val transactionWithInputInfoReadWriter: ReadWriter[TransactionWithInputInfo] = ReadWriter.merge(
    macroRW[CommitTx], macroRW[HtlcSuccessTx], macroRW[HtlcTimeoutTx], macroRW[ClaimHtlcSuccessTx], macroRW[ClaimHtlcTimeoutTx],
    macroRW[ClaimP2WPKHOutputTx], macroRW[ClaimDelayedOutputTx], macroRW[ClaimDelayedOutputPenaltyTx], macroRW[MainPenaltyTx], macroRW[HtlcPenaltyTx], macroRW[ClosingTx])
  implicit val hlcTxAndSigsReadWriter: ReadWriter[HtlcTxAndSigs] = macroRW
  implicit val commitTxReadWriter: ReadWriter[CommitTx] = macroRW
  implicit val publishableTxsReadWriter: ReadWriter[PublishableTxs] = macroRW
  implicit val localCommitReadWriter: ReadWriter[LocalCommit] = macroRW
  implicit val remoteCommitsReadWriter: ReadWriter[RemoteCommit] = macroRW
  implicit val commitSgReadWriter: ReadWriter[CommitSig] = macroRW
  implicit val waitingForRevocationReadWriter: ReadWriter[WaitingForRevocation] = macroRW
  implicit val paymentOriginReadWriter: ReadWriter[Origin] = readwriter[String].bimap[Origin](_.toString, _ => fr.acinq.eclair.payment.Local(None))
  implicit val remoteChangesReadWriter: ReadWriter[RemoteChanges] = macroRW
  implicit val shaChainReadWriter: ReadWriter[ShaChain] = macroRW
  implicit val commitmentsReadWriter: ReadWriter[Commitments] = macroRW
  implicit val actorRefReadWriter: ReadWriter[ActorRef] = readwriter[String].bimap[ActorRef](_.toString, _ => ActorRef.noSender)
  implicit val shortChannelIdReadWriter: ReadWriter[ShortChannelId] = readwriter[String].bimap[ShortChannelId](_.toString, s => ShortChannelId(s))

  implicit val initReadWriter: ReadWriter[Init] = macroRW
  implicit val openChannelReadWriter: ReadWriter[OpenChannel] = macroRW
  implicit val acceptChannelReadWriter: ReadWriter[AcceptChannel] = macroRW
  implicit val fundingCreatedReadWriter: ReadWriter[FundingCreated] = macroRW
  implicit val fundingLockedReadWriter: ReadWriter[FundingLocked] = macroRW
  implicit val fundingSignedReadWriter: ReadWriter[FundingSigned] = macroRW
  implicit val channelAnnouncementReadWriter: ReadWriter[ChannelAnnouncement] = macroRW
  implicit val channelUpdateReadWriter: ReadWriter[ChannelUpdate] = macroRW
  implicit val shutdownReadWriter: ReadWriter[Shutdown] = macroRW
  implicit val closingSigndeReadWriter: ReadWriter[ClosingSigned] = macroRW
  implicit val nodeAddressReadWriter: ReadWriter[NodeAddress] = readwriter[String].bimap[NodeAddress](_ match {
    case IPv4(a, p) => HostAndPort.fromParts(a.getHostAddress, p).toString
    case IPv6(a, p) => HostAndPort.fromParts(a.getHostAddress, p).toString
    case Tor2(b, p) => s"${b.toString}:$p"
    case Tor3(b, p) => s"${b.toString}:$p"
    case Padding => ""
  }, s => null)

  implicit val inputInitFunderReadWriter: ReadWriter[INPUT_INIT_FUNDER] = macroRW
  implicit val inputInitFundeeReadWriter: ReadWriter[INPUT_INIT_FUNDEE] = macroRW

  implicit val dataWaitForAcceptChannelReadWriter: ReadWriter[DATA_WAIT_FOR_ACCEPT_CHANNEL] = macroRW
  implicit val dataWaitForOpenChannelReadWriter: ReadWriter[DATA_WAIT_FOR_OPEN_CHANNEL] = macroRW
  implicit val dataWaitForFundingCreatedReadWriter: ReadWriter[DATA_WAIT_FOR_FUNDING_CREATED] = macroRW
  implicit val dataWaitForFundingInternalReadWriter: ReadWriter[DATA_WAIT_FOR_FUNDING_INTERNAL] = macroRW
  implicit val dataWaitForFundingSignedReadWriter: ReadWriter[DATA_WAIT_FOR_FUNDING_SIGNED] = macroRW
  implicit val dataWaitForFundingConfirmedReadWriter: ReadWriter[DATA_WAIT_FOR_FUNDING_CONFIRMED] = macroRW
  implicit val dataWaitForFundingLockedReadWriter: ReadWriter[DATA_WAIT_FOR_FUNDING_LOCKED] = macroRW
  implicit val dataNormalReadWriter: ReadWriter[DATA_NORMAL] = macroRW
  implicit val dataShutdownReadWriter: ReadWriter[DATA_SHUTDOWN] = macroRW
  implicit val dataNegociatingReadWriter: ReadWriter[DATA_NEGOTIATING] = macroRW
  implicit val dataClosingReadWriter: ReadWriter[DATA_CLOSING] = macroRW
  implicit val closingTxProposedReadWriter: ReadWriter[ClosingTxProposed] = macroRW
  implicit val localCommitPublishedReadWriter: ReadWriter[LocalCommitPublished] = macroRW
  implicit val remoteCommitPublishedReadWriter: ReadWriter[RemoteCommitPublished] = macroRW
  implicit val revokedCommitPublishedReadWriter: ReadWriter[RevokedCommitPublished] = macroRW
  implicit val channelStateReadWriter: ReadWriter[fr.acinq.eclair.channel.State] = readwriter[String].bimap[fr.acinq.eclair.channel.State](_.toString, _ match {
    case "NORMAL" => NORMAL
    case "CLOSING" => CLOSING
  })
  implicit val channelDataReadWriter: ReadWriter[fr.acinq.eclair.channel.Data] = ReadWriter.merge(
    macroRW[DATA_WAIT_FOR_ACCEPT_CHANNEL], macroRW[DATA_WAIT_FOR_FUNDING_CREATED], macroRW[DATA_WAIT_FOR_FUNDING_INTERNAL], macroRW[DATA_WAIT_FOR_FUNDING_SIGNED], macroRW[DATA_WAIT_FOR_FUNDING_LOCKED], macroRW[DATA_WAIT_FOR_FUNDING_CONFIRMED],
    macroRW[DATA_NORMAL],
    macroRW[DATA_SHUTDOWN], macroRW[DATA_NEGOTIATING], macroRW[DATA_CLOSING]
  )
  implicit val cmdResGetinfoReadWriter: ReadWriter[RES_GETINFO] = macroRW

}
