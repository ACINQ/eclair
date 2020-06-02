/*
 * Copyright 2020 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.wire.legacy

import java.util.UUID

import akka.actor.ActorRef
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, OutPoint, Transaction, TxOut}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.payment.relay.Origin
import fr.acinq.eclair.transactions.Transactions.{ClaimDelayedOutputTx, ClaimHtlcSuccessTx, ClaimHtlcTimeoutTx, ClaimP2WPKHOutputTx, ClosingTx, CommitTx, HtlcPenaltyTx, HtlcSuccessTx, HtlcTimeoutTx, InputInfo, MainPenaltyTx, TransactionWithInputInfo}
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.ChannelCodecs._
import fr.acinq.eclair.wire.CommonCodecs.{bytes32, publicKey, _}
import fr.acinq.eclair.wire.LightningMessageCodecs._
import fr.acinq.eclair.wire.UpdateMessage
import scodec.{Attempt, Codec}
import scodec.bits.BitVector
import scodec.codecs.{bool, discriminated, either, ignore, int64, listOfN, optional, peek, provide, uint16, variableSizeBytes, _}

import scala.concurrent.duration._

object LegacyChannelCodecs {

  private[wire] val localParamsCodec: Codec[LocalParams] = (
    ("nodeId" | publicKey) ::
      ("channelPath" | keyPathCodec) ::
      ("dustLimit" | satoshi) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserve" | satoshi) ::
      ("htlcMinimum" | millisatoshi) ::
      ("toSelfDelay" | cltvExpiryDelta) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("isFunder" | bool) ::
      ("defaultFinalScriptPubKey" | varsizebinarydata) ::
      ("features" | combinedFeaturesCodec)).as[LocalParams]

  private val  remoteParamsCodec: Codec[RemoteParams] = (
    ("nodeId" | publicKey) ::
      ("dustLimit" | satoshi) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserve" | satoshi) ::
      ("htlcMinimum" | millisatoshi) ::
      ("toSelfDelay" | cltvExpiryDelta) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("fundingPubKey" | publicKey) ::
      ("revocationBasepoint" | publicKey) ::
      ("paymentBasepoint" | publicKey) ::
      ("delayedPaymentBasepoint" | publicKey) ::
      ("htlcBasepoint" | publicKey) ::
      ("features" | combinedFeaturesCodec)).as[RemoteParams]

  val  htlcCodec: Codec[DirectedHtlc] = discriminated[DirectedHtlc].by(bool)
    .typecase(true, updateAddHtlcCodec.as[IncomingHtlc])
    .typecase(false, updateAddHtlcCodec.as[OutgoingHtlc])

  private def setCodec[T](codec: Codec[T]): Codec[Set[T]] = Codec[Set[T]](
    (elems: Set[T]) => listOfN(uint16, codec).encode(elems.toList),
    (wire: BitVector) => listOfN(uint16, codec).decode(wire).map(_.map(_.toSet))
  )

  private val  commitmentSpecCodec: Codec[CommitmentSpec] = (
    ("htlcs" | setCodec(htlcCodec)) ::
      ("feeratePerKw" | uint32) ::
      ("toLocal" | millisatoshi) ::
      ("toRemote" | millisatoshi)).as[CommitmentSpec]

  private val  outPointCodec: Codec[OutPoint] = variableSizeBytes(uint16, bytes.xmap(d => OutPoint.read(d.toArray), d => OutPoint.write(d)))

  private val  txOutCodec: Codec[TxOut] = variableSizeBytes(uint16, bytes.xmap(d => TxOut.read(d.toArray), d => TxOut.write(d)))

  private val  txCodec: Codec[Transaction] = variableSizeBytes(uint16, bytes.xmap(d => Transaction.read(d.toArray), d => Transaction.write(d)))

  private val  inputInfoCodec: Codec[InputInfo] = (
    ("outPoint" | outPointCodec) ::
      ("txOut" | txOutCodec) ::
      ("redeemScript" | varsizebinarydata)).as[InputInfo]

  private val  txWithInputInfoCodec: Codec[TransactionWithInputInfo] = discriminated[TransactionWithInputInfo].by(uint16)
    .typecase(0x01, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[CommitTx])
    .typecase(0x02, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32)).as[HtlcSuccessTx])
    .typecase(0x03, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[HtlcTimeoutTx])
    .typecase(0x04, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimHtlcSuccessTx])
    .typecase(0x05, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimHtlcTimeoutTx])
    .typecase(0x06, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimP2WPKHOutputTx])
    .typecase(0x07, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimDelayedOutputTx])
    .typecase(0x08, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[MainPenaltyTx])
    .typecase(0x09, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[HtlcPenaltyTx])
    .typecase(0x10, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClosingTx])

  // this is a backward compatible codec (we used to store the sig as DER encoded), now we store it as 64-bytes
  private val  sig64OrDERCodec: Codec[ByteVector64] = Codec[ByteVector64](
    (value: ByteVector64) => bytes(64).encode(value),
    (wire: BitVector) => bytes.decode(wire).map(_.map {
      case bin64 if bin64.size == 64 => ByteVector64(bin64)
      case der => Crypto.der2compact(der)
    })
  )

  private val  htlcTxAndSigsCodec: Codec[HtlcTxAndSigs] = (
    ("txinfo" | txWithInputInfoCodec) ::
      ("localSig" | variableSizeBytes(uint16, sig64OrDERCodec)) :: // we store as variable length for historical purposes (we used to store as DER encoded)
      ("remoteSig" | variableSizeBytes(uint16, sig64OrDERCodec))).as[HtlcTxAndSigs]

  private val  publishableTxsCodec: Codec[PublishableTxs] = (
    ("commitTx" | (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[CommitTx]) ::
      ("htlcTxsAndSigs" | listOfN(uint16, htlcTxAndSigsCodec))).as[PublishableTxs]

  private val  localCommitCodec: Codec[LocalCommit] = (
    ("index" | uint64overflow) ::
      ("spec" | commitmentSpecCodec) ::
      ("publishableTxs" | publishableTxsCodec)).as[LocalCommit]

  private val  remoteCommitCodec: Codec[RemoteCommit] = (
    ("index" | uint64overflow) ::
      ("spec" | commitmentSpecCodec) ::
      ("txid" | bytes32) ::
      ("remotePerCommitmentPoint" | publicKey)).as[RemoteCommit]

  private val  updateMessageCodec: Codec[UpdateMessage] = lightningMessageCodec.narrow(f => Attempt.successful(f.asInstanceOf[UpdateMessage]), g => g)

  private val  localChangesCodec: Codec[LocalChanges] = (
    ("proposed" | listOfN(uint16, updateMessageCodec)) ::
      ("signed" | listOfN(uint16, updateMessageCodec)) ::
      ("acked" | listOfN(uint16, updateMessageCodec))).as[LocalChanges]

  private val  remoteChangesCodec: Codec[RemoteChanges] = (
    ("proposed" | listOfN(uint16, updateMessageCodec)) ::
      ("acked" | listOfN(uint16, updateMessageCodec)) ::
      ("signed" | listOfN(uint16, updateMessageCodec))).as[RemoteChanges]

  private val  waitingForRevocationCodec: Codec[WaitingForRevocation] = (
    ("nextRemoteCommit" | remoteCommitCodec) ::
      ("sent" | commitSigCodec) ::
      ("sentAfterLocalCommitIndex" | uint64overflow) ::
      ("reSignAsap" | bool)).as[WaitingForRevocation]

  private val  localCodec: Codec[Origin.Local] = (
    ("id" | uuid) ::
      ("sender" | provide(Option.empty[ActorRef]))
    ).as[Origin.Local]

  private val  relayedCodec: Codec[Origin.Relayed] = (
    ("originChannelId" | bytes32) ::
      ("originHtlcId" | int64) ::
      ("amountIn" | millisatoshi) ::
      ("amountOut" | millisatoshi)).as[Origin.Relayed]

  // this is for backward compatibility to handle legacy payments that didn't have identifiers
  val  UNKNOWN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

  private val  trampolineRelayedCodec: Codec[Origin.TrampolineRelayed] = (
    listOfN(uint16, bytes32 ~ int64) ::
      ("sender" | provide(Option.empty[ActorRef]))
    ).as[Origin.TrampolineRelayed]

  val  originCodec: Codec[Origin] = discriminated[Origin].by(uint16)
    .typecase(0x03, localCodec) // backward compatible
    .typecase(0x01, provide(Origin.Local(UNKNOWN_UUID, None)))
    .typecase(0x02, relayedCodec)
    .typecase(0x04, trampolineRelayedCodec)

  private val  originsListCodec: Codec[List[(Long, Origin)]] = listOfN(uint16, int64 ~ originCodec)

  private val  originsMapCodec: Codec[Map[Long, Origin]] = Codec[Map[Long, Origin]](
    (map: Map[Long, Origin]) => originsListCodec.encode(map.toList),
    (wire: BitVector) => originsListCodec.decode(wire).map(_.map(_.toMap))
  )

  private val  spentListCodec: Codec[List[(OutPoint, ByteVector32)]] = listOfN(uint16, outPointCodec ~ bytes32)

  private val  spentMapCodec: Codec[Map[OutPoint, ByteVector32]] = Codec[Map[OutPoint, ByteVector32]](
    (map: Map[OutPoint, ByteVector32]) => spentListCodec.encode(map.toList),
    (wire: BitVector) => spentListCodec.decode(wire).map(_.map(_.toMap))
  )

  private val  commitmentsCodec: Codec[Commitments] = (
    ("channelVersion" | channelVersionCodec) ::
      ("localParams" | localParamsCodec) ::
      ("remoteParams" | remoteParamsCodec) ::
      ("channelFlags" | byte) ::
      ("localCommit" | localCommitCodec) ::
      ("remoteCommit" | remoteCommitCodec) ::
      ("localChanges" | localChangesCodec) ::
      ("remoteChanges" | remoteChangesCodec) ::
      ("localNextHtlcId" | uint64overflow) ::
      ("remoteNextHtlcId" | uint64overflow) ::
      ("originChannels" | originsMapCodec) ::
      ("remoteNextCommitInfo" | either(bool, waitingForRevocationCodec, publicKey)) ::
      ("commitInput" | inputInfoCodec) ::
      ("remotePerCommitmentSecrets" | ShaChain.shaChainCodec) ::
      ("channelId" | bytes32)).as[Commitments]

  private val  closingTxProposedCodec: Codec[ClosingTxProposed] = (
    ("unsignedTx" | txCodec) ::
      ("localClosingSigned" | closingSignedCodec)).as[ClosingTxProposed]

  private val  localCommitPublishedCodec: Codec[LocalCommitPublished] = (
    ("commitTx" | txCodec) ::
      ("claimMainDelayedOutputTx" | optional(bool, txCodec)) ::
      ("htlcSuccessTxs" | listOfN(uint16, txCodec)) ::
      ("htlcTimeoutTxs" | listOfN(uint16, txCodec)) ::
      ("claimHtlcDelayedTx" | listOfN(uint16, txCodec)) ::
      ("spent" | spentMapCodec)).as[LocalCommitPublished]

  private val  remoteCommitPublishedCodec: Codec[RemoteCommitPublished] = (
    ("commitTx" | txCodec) ::
      ("claimMainOutputTx" | optional(bool, txCodec)) ::
      ("claimHtlcSuccessTxs" | listOfN(uint16, txCodec)) ::
      ("claimHtlcTimeoutTxs" | listOfN(uint16, txCodec)) ::
      ("spent" | spentMapCodec)).as[RemoteCommitPublished]

  private val  revokedCommitPublishedCodec: Codec[RevokedCommitPublished] = (
    ("commitTx" | txCodec) ::
      ("claimMainOutputTx" | optional(bool, txCodec)) ::
      ("mainPenaltyTx" | optional(bool, txCodec)) ::
      ("htlcPenaltyTxs" | listOfN(uint16, txCodec)) ::
      ("claimHtlcDelayedPenaltyTxs" | listOfN(uint16, txCodec)) ::
      ("spent" | spentMapCodec)).as[RevokedCommitPublished]

  // this is a decode-only codec compatible with versions 997acee and below, with placeholders for new fields
  private[wire] val  DATA_WAIT_FOR_FUNDING_CONFIRMED_COMPAT_01_Codec: Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
    ("commitments" | commitmentsCodec) ::
      ("fundingTx" | provide[Option[Transaction]](None)) ::
      ("waitingSince" | provide(System.currentTimeMillis.milliseconds.toSeconds)) ::
      ("deferred" | optional(bool, fundingLockedCodec)) ::
      ("lastSent" | either(bool, fundingCreatedCodec, fundingSignedCodec))).as[DATA_WAIT_FOR_FUNDING_CONFIRMED].decodeOnly

  private[wire] val  DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec: Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
    ("commitments" | commitmentsCodec) ::
      ("fundingTx" | optional(bool, txCodec)) ::
      ("waitingSince" | int64) ::
      ("deferred" | optional(bool, fundingLockedCodec)) ::
      ("lastSent" | either(bool, fundingCreatedCodec, fundingSignedCodec))).as[DATA_WAIT_FOR_FUNDING_CONFIRMED]

  private[wire] val  DATA_WAIT_FOR_FUNDING_LOCKED_Codec: Codec[DATA_WAIT_FOR_FUNDING_LOCKED] = (
    ("commitments" | commitmentsCodec) ::
      ("shortChannelId" | shortchannelid) ::
      ("lastSent" | fundingLockedCodec)).as[DATA_WAIT_FOR_FUNDING_LOCKED]

  // All channel_announcement's written prior to supporting unknown trailing fields had the same fixed size, because
  // those are the announcements that *we* created and we always used an empty features field, which was the only
  // variable-length field.
  private val  noUnknownFieldsChannelAnnouncementSizeCodec: Codec[Int] = provide(430)

  // We used to ignore unknown trailing fields, and assume that channel_update size was known. This is not true anymore,
  // so we need to tell the codec where to stop, otherwise all the remaining part of the data will be decoded as unknown
  // fields. Fortunately, we can easily tell what size the channel_update will be.
  private[wire] val  noUnknownFieldsChannelUpdateSizeCodec: Codec[Int] = peek( // we need to take a peek at a specific byte to know what size the message will be, and then rollback to read the full message
    ignore(8 * (64 + 32 + 8 + 4)) ~> // we skip the first fields: signature + chain_hash + short_channel_id + timestamp
      byte // this is the messageFlags byte
  )
    .map(messageFlags => if ((messageFlags & 1) != 0) 136 else 128) // depending on the value of option_channel_htlc_max, size will be 128B or 136B
    .decodeOnly // this is for compat, we only need to decode

  // this is a decode-only codec compatible with versions 9afb26e and below
  private[wire] val  DATA_NORMAL_COMPAT_03_Codec: Codec[DATA_NORMAL] = (
    ("commitments" | commitmentsCodec) ::
      ("shortChannelId" | shortchannelid) ::
      ("buried" | bool) ::
      ("channelAnnouncement" | optional(bool, variableSizeBytes(noUnknownFieldsChannelAnnouncementSizeCodec, channelAnnouncementCodec))) ::
      ("channelUpdate" | variableSizeBytes(noUnknownFieldsChannelUpdateSizeCodec, channelUpdateCodec)) ::
      ("localShutdown" | optional(bool, shutdownCodec)) ::
      ("remoteShutdown" | optional(bool, shutdownCodec))).as[DATA_NORMAL].decodeOnly

  private[wire] val  DATA_NORMAL_Codec: Codec[DATA_NORMAL] = (
    ("commitments" | commitmentsCodec) ::
      ("shortChannelId" | shortchannelid) ::
      ("buried" | bool) ::
      ("channelAnnouncement" | optional(bool, variableSizeBytes(uint16, channelAnnouncementCodec))) ::
      ("channelUpdate" | variableSizeBytes(uint16, channelUpdateCodec)) ::
      ("localShutdown" | optional(bool, shutdownCodec)) ::
      ("remoteShutdown" | optional(bool, shutdownCodec))).as[DATA_NORMAL]

  private[wire] val  DATA_SHUTDOWN_Codec: Codec[DATA_SHUTDOWN] = (
    ("commitments" | commitmentsCodec) ::
      ("localShutdown" | shutdownCodec) ::
      ("remoteShutdown" | shutdownCodec)).as[DATA_SHUTDOWN]

  private[wire] val  DATA_NEGOTIATING_Codec: Codec[DATA_NEGOTIATING] = (
    ("commitments" | commitmentsCodec) ::
      ("localShutdown" | shutdownCodec) ::
      ("remoteShutdown" | shutdownCodec) ::
      ("closingTxProposed" | listOfN(uint16, listOfN(uint16, closingTxProposedCodec))) ::
      ("bestUnpublishedClosingTx_opt" | optional(bool, txCodec))).as[DATA_NEGOTIATING]

  // this is a decode-only codec compatible with versions 818199e and below, with placeholders for new fields
  private[wire] val  DATA_CLOSING_COMPAT_06_Codec: Codec[DATA_CLOSING] = (
    ("commitments" | commitmentsCodec) ::
      ("fundingTx" | provide[Option[Transaction]](None)) ::
      ("waitingSince" | provide(System.currentTimeMillis.milliseconds.toSeconds)) ::
      ("mutualCloseProposed" | listOfN(uint16, txCodec)) ::
      ("mutualClosePublished" | listOfN(uint16, txCodec)) ::
      ("localCommitPublished" | optional(bool, localCommitPublishedCodec)) ::
      ("remoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("nextRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("futureRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).as[DATA_CLOSING].decodeOnly

  private[wire] val  DATA_CLOSING_Codec: Codec[DATA_CLOSING] = (
    ("commitments" | commitmentsCodec) ::
      ("fundingTx" | optional(bool, txCodec)) ::
      ("waitingSince" | int64) ::
      ("mutualCloseProposed" | listOfN(uint16, txCodec)) ::
      ("mutualClosePublished" | listOfN(uint16, txCodec)) ::
      ("localCommitPublished" | optional(bool, localCommitPublishedCodec)) ::
      ("remoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("nextRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("futureRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).as[DATA_CLOSING]

  private[wire] val  DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec: Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = (
    ("commitments" | commitmentsCodec) ::
      ("remoteChannelReestablish" | channelReestablishCodec)).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]
}
