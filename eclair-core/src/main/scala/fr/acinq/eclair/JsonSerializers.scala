package fr.acinq.eclair

import akka.actor.ActorRef
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.scala.Crypto.PublicKey
import fr.acinq.bitcoin.scala.{ByteVector32, ByteVector64, DeterministicWallet, OutPoint, Satoshi, Transaction, TxOut}
import fr.acinq.eclair.Features.OptionDataLossProtect
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.payment.relay.Origin
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.ChannelTlv.UpfrontShutdownScript
import fr.acinq.eclair.wire.OpenChannelTlv.ChannelVersionTlv
import fr.acinq.eclair.wire.{AcceptChannel, AcceptChannelTlv, ChannelAnnouncement, ChannelUpdate, ClosingSigned, CommitSig, FundingCreated, FundingLocked, FundingSigned, GenericTlv, Init, NodeAddress, OnionRoutingPacket, OpenChannel, OpenChannelTlv, Shutdown, Tlv, TlvStream, UpdateAddHtlc, UpdateFailHtlc, UpdateFailMalformedHtlc, UpdateFee, UpdateFulfillHtlc, UpdateMessage}
import scodec.bits.{BitVector, ByteVector}

object JsonSerializers {

  import upickle.default._

  implicit val txReadWrite: ReadWriter[Transaction] = readwriter[String].bimap[Transaction](_.toString(), Transaction.read(_))
  implicit val outpointReadWrite: ReadWriter[OutPoint] = readwriter[String].bimap[OutPoint](op => s"${op.txid}:${op.index}", s => ???)
  implicit val publicKeyReadWriter: ReadWriter[PublicKey] = readwriter[String].bimap[PublicKey](_.toString(), s => PublicKey(ByteVector.fromValidHex(s)))
  implicit val keyPathReadWriter: ReadWriter[DeterministicWallet.KeyPath] = readwriter[String].bimap[DeterministicWallet.KeyPath](_.toString(), _ => DeterministicWallet.KeyPath(0L :: Nil))
  implicit val bytevectorReadWriter: ReadWriter[ByteVector] = readwriter[String].bimap[ByteVector](_.toHex, s => ByteVector.fromValidHex(s))
  implicit val bytevector32ReadWriter: ReadWriter[ByteVector32] = readwriter[String].bimap[ByteVector32](_.bytes.toHex, s => ByteVector32.fromValidHex(s))
  implicit val bytevector64ReadWriter: ReadWriter[ByteVector64] = readwriter[String].bimap[ByteVector64](_.bytes.toHex, s => ByteVector64.fromValidHex(s))
  implicit val uint64ReadWriter: ReadWriter[UInt64] = readwriter[String].bimap[UInt64](_.toString, s => UInt64(s.toLong))
  implicit val channelVersionReadWriter: ReadWriter[ChannelVersion] = readwriter[String].bimap[ChannelVersion](_.bits.toBin, s => ChannelVersion(BitVector.fromValidBin(s)))
  implicit val featureReadWriter: ReadWriter[Feature] = readwriter[String].bimap[Feature](
    _.rfcName,
    s => Features.knownFeatures.find(_.rfcName == s).getOrElse(OptionDataLossProtect)
  )
  implicit val feartureSupportReadWriter: ReadWriter[FeatureSupport] = readwriter[String].bimap(
    _.toString,
    s => if (s == "mandatory") FeatureSupport.Mandatory else FeatureSupport.Optional
  )
  implicit val activatedFeaturesReadWriter: ReadWriter[ActivatedFeature] = macroRW
  implicit val unknownFeaturesReadWriter: ReadWriter[UnknownFeature] = macroRW
  implicit val featuresReadWriter: ReadWriter[Features] = macroRW
  implicit val localParamsReadWriter: ReadWriter[LocalParams] = macroRW
  implicit val remoteParamsReadWriter: ReadWriter[RemoteParams] = macroRW
  implicit val onionRoutingPacketReadWriter: ReadWriter[OnionRoutingPacket] = macroRW
  implicit val updateAddHtlcReadWriter: ReadWriter[UpdateAddHtlc] = macroRW
  implicit val updateFailHtlcReadWriter: ReadWriter[UpdateFailHtlc] = macroRW
  implicit val updateFailMalformedHtlcReadWriter: ReadWriter[UpdateFailMalformedHtlc] = macroRW
  implicit val updateFeeReadWriter: ReadWriter[UpdateFee] = macroRW
  implicit val updateFulfillHtlcReadWriter: ReadWriter[UpdateFulfillHtlc] = macroRW
  implicit val updateMessageReadWriter: ReadWriter[UpdateMessage] = ReadWriter.merge(macroRW[UpdateAddHtlc], macroRW[UpdateFailHtlc], macroRW[UpdateFailMalformedHtlc], macroRW[UpdateFee], macroRW[UpdateFulfillHtlc])
  implicit val incomingHtlcReadWriter: ReadWriter[IncomingHtlc] = macroRW
  implicit val outgoingHtlcReadWriter: ReadWriter[OutgoingHtlc] = macroRW
  implicit val directedHtlcReadWriter: ReadWriter[DirectedHtlc] = macroRW
  implicit val commitmentSpecReadWriter: ReadWriter[CommitmentSpec] = macroRW
  implicit val localChangesReadWriter: ReadWriter[LocalChanges] = macroRW
  implicit val cltvExpiryReadWriter: ReadWriter[CltvExpiry] = readwriter[Long].bimap(_.toLong, CltvExpiry.apply)
  implicit val cltvExpiryDeltaReadWriter: ReadWriter[CltvExpiryDelta] = readwriter[Int].bimap(_.toInt, CltvExpiryDelta.apply)
  implicit val millisatoshiReadWriter: ReadWriter[MilliSatoshi] = readwriter[Long].bimap(_.toLong, MilliSatoshi.apply)
  implicit val satoshiReadWriter: ReadWriter[Satoshi] = readwriter[Long].bimap(_.toLong, Satoshi.apply)
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
  implicit val customRemoteSigReadWriter: ReadWriter[CustomRemoteSig] = macroRW
  implicit val localOriginReadWriter: ReadWriter[Origin.Local] = macroRW
  implicit val relayedOriginReadWriter: ReadWriter[Origin.Relayed] = macroRW
  implicit val paymentOriginReadWriter: ReadWriter[Origin] = ReadWriter.merge(localOriginReadWriter, relayedOriginReadWriter)
  implicit val remoteChangesReadWriter: ReadWriter[RemoteChanges] = macroRW
  implicit val genericTlvReadWriter: ReadWriter[GenericTlv] = macroRW
  implicit val tlvReadWriter: ReadWriter[Tlv] = ReadWriter.merge(genericTlvReadWriter)
  implicit val tlvStreamOpenTlvReadWriter: ReadWriter[TlvStream[Tlv]] = readwriter[String].bimap[TlvStream[Tlv]](s => "N/A", s2 => TlvStream(List.empty[Tlv]))
  implicit val tlvStreamOpenChannelTlvReadWriter: ReadWriter[TlvStream[OpenChannelTlv]] = readwriter[String].bimap[TlvStream[OpenChannelTlv]](s => "N/A", s2 => TlvStream(List.empty[OpenChannelTlv]))
  implicit val tlvStreamAcceptChannelTlvReadWriter: ReadWriter[TlvStream[AcceptChannelTlv]] = readwriter[String].bimap[TlvStream[AcceptChannelTlv]](s => "N/A", s2 => TlvStream(List.empty[AcceptChannelTlv]))

  case class ShaChain2(knownHashes: Map[Long, ByteVector32], lastIndex: Option[Long] = None) {
    def toShaChain = ShaChain(knownHashes.map { case (k, v) => ShaChain.moves(k) -> v }, lastIndex)
  }
  object ShaChain2 {
    def toLong(input: Vector[Boolean]) : Long = input.foldLeft(0) { case (acc, flag) => if (flag) 2 * acc + 1 else 2 * acc }
    def apply(shaChain: ShaChain): ShaChain2 = new ShaChain2(shaChain.knownHashes.map { case (k,v) => toLong(k) -> v }, shaChain.lastIndex)
  }
  implicit val shaChain2ReadWriter: ReadWriter[ShaChain2] = macroRW
  implicit val shaChainReadWriter: ReadWriter[ShaChain] = readwriter[ShaChain2].bimap[ShaChain](s => ShaChain2(s), s2 => s2.toShaChain)
  implicit val commitmentsReadWriter: ReadWriter[Commitments] = macroRW
  implicit val actorRefReadWriter: ReadWriter[ActorRef] = readwriter[String].bimap[ActorRef](_.toString, _ => ActorRef.noSender)
  implicit val shortChannelIdReadWriter: ReadWriter[ShortChannelId] = readwriter[String].bimap[ShortChannelId](_.toString, s => ShortChannelId(s))

  implicit val upfrontShutdownScriptWriter: ReadWriter[UpfrontShutdownScript] = macroRW
  implicit val channelVersionTlvWriter: ReadWriter[ChannelVersionTlv] = macroRW
  implicit val openChannelTlvWriter: ReadWriter[OpenChannelTlv] = macroRW
  implicit val acceptChannelTlvWriter: ReadWriter[AcceptChannelTlv] = macroRW
  implicit val initReadWriter: ReadWriter[Init] = readwriter[Features].bimap[Init](_.features, s => Init(s))
  implicit val openChannelReadWriter: ReadWriter[OpenChannel] = macroRW
  implicit val acceptChannelReadWriter: ReadWriter[AcceptChannel] = macroRW
  implicit val fundingCreatedReadWriter: ReadWriter[FundingCreated] = macroRW
  implicit val fundingLockedReadWriter: ReadWriter[FundingLocked] = macroRW
  implicit val fundingSignedReadWriter: ReadWriter[FundingSigned] = macroRW
  implicit val channelAnnouncementReadWriter: ReadWriter[ChannelAnnouncement] = macroRW
  implicit val channelUpdateReadWriter: ReadWriter[ChannelUpdate] = macroRW
  implicit val shutdownReadWriter: ReadWriter[Shutdown] = macroRW
  implicit val closingSigndeReadWriter: ReadWriter[ClosingSigned] = macroRW
  implicit val nodeAddressReadWriter: ReadWriter[NodeAddress] = readwriter[String].bimap[NodeAddress](n => HostAndPort.fromParts(n.socketAddress.getHostString, n.socketAddress.getPort).toString, s => null)

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
    case "WAIT_FOR_INIT_INTERNAL" => WAIT_FOR_INIT_INTERNAL
    case "WAIT_FOR_OPEN_CHANNEL" => WAIT_FOR_OPEN_CHANNEL
    case "WAIT_FOR_ACCEPT_CHANNEL" => WAIT_FOR_ACCEPT_CHANNEL
    case "WAIT_FOR_FUNDING_INTERNAL" => WAIT_FOR_FUNDING_INTERNAL
    case "WAIT_FOR_FUNDING_CREATED" => WAIT_FOR_FUNDING_CREATED
    case "WAIT_FOR_FUNDING_SIGNED" => WAIT_FOR_FUNDING_SIGNED
    case "WAIT_FOR_FUNDING_CONFIRMED" => WAIT_FOR_FUNDING_CONFIRMED
    case "WAIT_FOR_FUNDING_LOCKED" => WAIT_FOR_FUNDING_LOCKED
    case "NORMAL" => NORMAL
    case "SHUTDOWN" => SHUTDOWN
    case "NEGOTIATING" => NEGOTIATING
    case "CLOSING" => CLOSING
    case "CLOSED" => CLOSED
    case "OFFLINE" => OFFLINE
    case "SYNCING" => SYNCING
    case "WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT" => WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT
    case "ERR_FUNDING_LOST" => ERR_FUNDING_LOST
    case "ERR_INFORMATION_LEAK" => ERR_INFORMATION_LEAK
  })
  implicit val channelDataReadWriter: ReadWriter[fr.acinq.eclair.channel.Data] = ReadWriter.merge(
    macroRW[DATA_WAIT_FOR_ACCEPT_CHANNEL], macroRW[DATA_WAIT_FOR_FUNDING_CREATED], macroRW[DATA_WAIT_FOR_FUNDING_INTERNAL], macroRW[DATA_WAIT_FOR_FUNDING_SIGNED], macroRW[DATA_WAIT_FOR_FUNDING_LOCKED], macroRW[DATA_WAIT_FOR_FUNDING_CONFIRMED],
    macroRW[DATA_NORMAL],
    macroRW[DATA_SHUTDOWN], macroRW[DATA_NEGOTIATING], macroRW[DATA_CLOSING]
  )
  implicit val cmdResGetinfoReadWriter: ReadWriter[RES_GETINFO] = macroRW

}
