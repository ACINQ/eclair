/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.wire

import java.util.UUID

import akka.actor.ActorRef
import fr.acinq.bitcoin.DeterministicWallet.{ExtendedPrivateKey, KeyPath}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, OutPoint, Transaction, TxOut}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.payment.relay.Origin
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.wire.LightningMessageCodecs._
import grizzled.slf4j.Logging
import scodec.bits.BitVector
import scodec.codecs._
import scodec.{Attempt, Codec}

import scala.compat.Platform
import scala.concurrent.duration._

/**
 * Created by PM on 02/06/2017.
 */
object ChannelCodecs extends Logging {

  val keyPathCodec: Codec[KeyPath] = ("path" | listOfN(uint16, uint32)).xmap[KeyPath](l => new KeyPath(l), keyPath => keyPath.path.toList).as[KeyPath]

  val extendedPrivateKeyCodec: Codec[ExtendedPrivateKey] = (
    ("secretkeybytes" | bytes32) ::
      ("chaincode" | bytes32) ::
      ("depth" | uint16) ::
      ("path" | keyPathCodec) ::
      ("parent" | int64)).as[ExtendedPrivateKey]

  val channelVersionCodec: Codec[ChannelVersion] = discriminatorWithDefault[ChannelVersion](
    discriminator = discriminated[ChannelVersion].by(byte)
      .typecase(0x01, bits(ChannelVersion.LENGTH_BITS).as[ChannelVersion])
    // NB: 0x02 and 0x03 are *reserved* for backward compatibility reasons
    ,
    fallback = provide(ChannelVersion.ZEROES) // README: DO NOT CHANGE THIS !! old channels don't have a channel version
    // field and don't support additional features which is why all bits are set to 0.
  )

  val localParamsCodec: Codec[LocalParams] = (
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
      ("globalFeatures" | varsizebinarydata) ::
      ("localFeatures" | varsizebinarydata)).as[LocalParams]

  val remoteParamsCodec: Codec[RemoteParams] = (
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
      ("globalFeatures" | varsizebinarydata) ::
      ("localFeatures" | varsizebinarydata)).as[RemoteParams]

  def which[T](indicator: Boolean, left: Codec[T], right: Codec[T]): Codec[T] = if (indicator) left else right

  def maybePrefixed[T](lengthPrefixed: Boolean, codec: Codec[T]): Codec[T] = which(lengthPrefixed, variableSizeBytes(uint16, codec), codec)

  val directionCodec: Codec[Direction] = Codec[Direction](
    (dir: Direction) => bool.encode(dir == IN),
    (wire: BitVector) => bool.decode(wire).map(_.map(b => if (b) IN else OUT))
  )

  def htlcCodec(lengthPrefixed: Boolean): Codec[DirectedHtlc] = (
    ("direction" | directionCodec) ::
      ("add" | maybePrefixed(lengthPrefixed, updateAddHtlcCodec))).as[DirectedHtlc]

  def setCodec[T](codec: Codec[T]): Codec[Set[T]] = Codec[Set[T]](
    (elems: Set[T]) => listOfN(uint16, codec).encode(elems.toList),
    (wire: BitVector) => listOfN(uint16, codec).decode(wire).map(_.map(_.toSet))
  )

  def commitmentSpecCodec(lengthPrefixed: Boolean): Codec[CommitmentSpec] = (
    ("htlcs" | setCodec(htlcCodec(lengthPrefixed))) ::
      ("feeratePerKw" | uint32) ::
      ("toLocal" | millisatoshi) ::
      ("toRemote" | millisatoshi)).as[CommitmentSpec]

  val outPointCodec: Codec[OutPoint] = variableSizeBytes(uint16, bytes.xmap(d => OutPoint.read(d.toArray), d => OutPoint.write(d)))

  val txOutCodec: Codec[TxOut] = variableSizeBytes(uint16, bytes.xmap(d => TxOut.read(d.toArray), d => TxOut.write(d)))

  val txCodec: Codec[Transaction] = variableSizeBytes(uint16, bytes.xmap(d => Transaction.read(d.toArray), d => Transaction.write(d)))

  val inputInfoCodec: Codec[InputInfo] = (
    ("outPoint" | outPointCodec) ::
      ("txOut" | txOutCodec) ::
      ("redeemScript" | varsizebinarydata)).as[InputInfo]

  val txWithInputInfoCodec: Codec[TransactionWithInputInfo] = discriminated[TransactionWithInputInfo].by(uint16)
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
  val sig64OrDERCodec: Codec[ByteVector64] = Codec[ByteVector64](
    (value: ByteVector64) => bytes(64).encode(value),
    (wire: BitVector) => bytes.decode(wire).map(_.map {
      case bin64 if bin64.size == 64 => ByteVector64(bin64)
      case der => Crypto.der2compact(der)
    })
  )

  val htlcTxAndSigsCodec: Codec[HtlcTxAndSigs] = (
    ("txinfo" | txWithInputInfoCodec) ::
      ("localSig" | variableSizeBytes(uint16, sig64OrDERCodec)) :: // we store as variable length for historical purposes (we used to store as DER encoded)
      ("remoteSig" | variableSizeBytes(uint16, sig64OrDERCodec))).as[HtlcTxAndSigs]

  val publishableTxsCodec: Codec[PublishableTxs] = (
    ("commitTx" | (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[CommitTx]) ::
      ("htlcTxsAndSigs" | listOfN(uint16, htlcTxAndSigsCodec))).as[PublishableTxs]

  def localCommitCodec(lengthPrefixed: Boolean): Codec[LocalCommit] = (
    ("index" | uint64overflow) ::
      ("spec" | commitmentSpecCodec(lengthPrefixed)) ::
      ("publishableTxs" | publishableTxsCodec)).as[LocalCommit]

  def remoteCommitCodec(lengthPrefixed: Boolean): Codec[RemoteCommit] = (
    ("index" | uint64overflow) ::
      ("spec" | commitmentSpecCodec(lengthPrefixed)) ::
      ("txid" | bytes32) ::
      ("remotePerCommitmentPoint" | publicKey)).as[RemoteCommit]

  def updateMessageCodec(lengthPrefixed: Boolean): Codec[UpdateMessage] = maybePrefixed(lengthPrefixed, lightningMessageCodec.narrow(f => Attempt.successful(f.asInstanceOf[UpdateMessage]), g => g))

  def localChangesCodec(lengthPrefixed: Boolean): Codec[LocalChanges] = (
    ("proposed" | listOfN(uint16, updateMessageCodec(lengthPrefixed))) ::
      ("signed" | listOfN(uint16, updateMessageCodec(lengthPrefixed))) ::
      ("acked" | listOfN(uint16, updateMessageCodec(lengthPrefixed)))).as[LocalChanges]

  def remoteChangesCodec(lengthPrefixed: Boolean): Codec[RemoteChanges] = (
    ("proposed" | listOfN(uint16, updateMessageCodec(lengthPrefixed))) ::
      ("acked" | listOfN(uint16, updateMessageCodec(lengthPrefixed))) ::
      ("signed" | listOfN(uint16, updateMessageCodec(lengthPrefixed)))).as[RemoteChanges]

  def waitingForRevocationCodec(lengthPrefixed: Boolean): Codec[WaitingForRevocation] = (
    ("nextRemoteCommit" | remoteCommitCodec(lengthPrefixed)) ::
      ("sent" | maybePrefixed(lengthPrefixed, commitSigCodec)) ::
      ("sentAfterLocalCommitIndex" | uint64overflow) ::
      ("reSignAsap" | bool)).as[WaitingForRevocation]

  val localCodec: Codec[Origin.Local] = (
    ("id" | uuid) ::
      ("sender" | provide(Option.empty[ActorRef]))
    ).as[Origin.Local]

  val relayedCodec: Codec[Origin.Relayed] = (
    ("originChannelId" | bytes32) ::
      ("originHtlcId" | int64) ::
      ("amountIn" | millisatoshi) ::
      ("amountOut" | millisatoshi)).as[Origin.Relayed]

  // this is for backward compatibility to handle legacy payments that didn't have identifiers
  val UNKNOWN_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

  val originCodec: Codec[Origin] = discriminated[Origin].by(uint16)
    .typecase(0x03, localCodec) // backward compatible
    .typecase(0x01, provide(Origin.Local(UNKNOWN_UUID, None)))
    .typecase(0x02, relayedCodec)

  val originsListCodec: Codec[List[(Long, Origin)]] = listOfN(uint16, int64 ~ originCodec)

  val originsMapCodec: Codec[Map[Long, Origin]] = Codec[Map[Long, Origin]](
    (map: Map[Long, Origin]) => originsListCodec.encode(map.toList),
    (wire: BitVector) => originsListCodec.decode(wire).map(_.map(_.toMap))
  )

  val spentListCodec: Codec[List[(OutPoint, ByteVector32)]] = listOfN(uint16, outPointCodec ~ bytes32)

  val spentMapCodec: Codec[Map[OutPoint, ByteVector32]] = Codec[Map[OutPoint, ByteVector32]](
    (map: Map[OutPoint, ByteVector32]) => spentListCodec.encode(map.toList),
    (wire: BitVector) => spentListCodec.decode(wire).map(_.map(_.toMap))
  )

  def commitmentsCodec(lengthPrefixed: Boolean): Codec[Commitments] = (
    ("channelVersion" | channelVersionCodec) ::
      ("localParams" | localParamsCodec) ::
      ("remoteParams" | remoteParamsCodec) ::
      ("channelFlags" | byte) ::
      ("localCommit" | localCommitCodec(lengthPrefixed)) ::
      ("remoteCommit" | remoteCommitCodec(lengthPrefixed)) ::
      ("localChanges" | localChangesCodec(lengthPrefixed)) ::
      ("remoteChanges" | remoteChangesCodec(lengthPrefixed)) ::
      ("localNextHtlcId" | uint64overflow) ::
      ("remoteNextHtlcId" | uint64overflow) ::
      ("originChannels" | originsMapCodec) ::
      ("remoteNextCommitInfo" | either(bool, waitingForRevocationCodec(lengthPrefixed), publicKey)) ::
      ("commitInput" | inputInfoCodec) ::
      ("remotePerCommitmentSecrets" | ShaChain.shaChainCodec) ::
      ("channelId" | bytes32)).as[Commitments]

  def closingTxProposedCodec(lengthPrefixed: Boolean): Codec[ClosingTxProposed] = (
    ("unsignedTx" | txCodec) ::
      ("localClosingSigned" | maybePrefixed(lengthPrefixed, closingSignedCodec))).as[ClosingTxProposed]

  val localCommitPublishedCodec: Codec[LocalCommitPublished] = (
    ("commitTx" | txCodec) ::
      ("claimMainDelayedOutputTx" | optional(bool, txCodec)) ::
      ("htlcSuccessTxs" | listOfN(uint16, txCodec)) ::
      ("htlcTimeoutTxs" | listOfN(uint16, txCodec)) ::
      ("claimHtlcDelayedTx" | listOfN(uint16, txCodec)) ::
      ("spent" | spentMapCodec)).as[LocalCommitPublished]

  val remoteCommitPublishedCodec: Codec[RemoteCommitPublished] = (
    ("commitTx" | txCodec) ::
      ("claimMainOutputTx" | optional(bool, txCodec)) ::
      ("claimHtlcSuccessTxs" | listOfN(uint16, txCodec)) ::
      ("claimHtlcTimeoutTxs" | listOfN(uint16, txCodec)) ::
      ("spent" | spentMapCodec)).as[RemoteCommitPublished]

  val revokedCommitPublishedCodec: Codec[RevokedCommitPublished] = (
    ("commitTx" | txCodec) ::
      ("claimMainOutputTx" | optional(bool, txCodec)) ::
      ("mainPenaltyTx" | optional(bool, txCodec)) ::
      ("htlcPenaltyTxs" | listOfN(uint16, txCodec)) ::
      ("claimHtlcDelayedPenaltyTxs" | listOfN(uint16, txCodec)) ::
      ("spent" | spentMapCodec)).as[RevokedCommitPublished]

  // this is a decode-only codec compatible with versions 997acee and below, with placeholders for new fields
  val DATA_WAIT_FOR_FUNDING_CONFIRMED_COMPAT_01_Codec: Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
    ("commitments" | commitmentsCodec(false)) ::
      ("fundingTx" | provide[Option[Transaction]](None)) ::
      ("waitingSince" | provide(Platform.currentTime.milliseconds.toSeconds)) ::
      ("deferred" | optional(bool, fundingLockedCodec)) ::
      ("lastSent" | either(bool, fundingCreatedCodec, fundingSignedCodec))).as[DATA_WAIT_FOR_FUNDING_CONFIRMED].decodeOnly

  def DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec(lengthPrefixed: Boolean): Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
    ("commitments" | commitmentsCodec(lengthPrefixed)) ::
      ("fundingTx" | optional(bool, txCodec)) ::
      ("waitingSince" | int64) ::
      ("deferred" | optional(bool, maybePrefixed(lengthPrefixed, fundingLockedCodec))) ::
      ("lastSent" | either(bool, maybePrefixed(lengthPrefixed, fundingCreatedCodec), maybePrefixed(lengthPrefixed, fundingSignedCodec)))).as[DATA_WAIT_FOR_FUNDING_CONFIRMED]

  val DATA_WAIT_FOR_FUNDING_CONFIRMED_COMPAT_08_Codec: Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec(false)

  val DATA_WAIT_FOR_FUNDING_CONFIRMED_11_Codec: Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec(false)

  def DATA_WAIT_FOR_FUNDING_LOCKED_Codec(lengthPrefixed: Boolean): Codec[DATA_WAIT_FOR_FUNDING_LOCKED] = (
    ("commitments" | commitmentsCodec(lengthPrefixed)) ::
      ("shortChannelId" | shortchannelid) ::
      ("lastSent" | maybePrefixed(lengthPrefixed, fundingLockedCodec))).as[DATA_WAIT_FOR_FUNDING_LOCKED]

  val DATA_WAIT_FOR_FUNDING_LOCKED_COMPAT_02_Codec: Codec[DATA_WAIT_FOR_FUNDING_LOCKED] = DATA_WAIT_FOR_FUNDING_LOCKED_Codec(false)

  val DATA_WAIT_FOR_FUNDING_LOCKED_12_Codec: Codec[DATA_WAIT_FOR_FUNDING_LOCKED] = DATA_WAIT_FOR_FUNDING_LOCKED_Codec(true)

  // All channel_announcement's written prior to supporting unknown trailing fields had the same fixed size, because
  // those are the announcements that *we* created and we always used an empty features field, which was the only
  // variable-length field.
  val noUnknownFieldsChannelAnnouncementSizeCodec: Codec[Int] = provide(430)

  // We used to ignore unknown trailing fields, and assume that channel_update size was known. This is not true anymore,
  // so we need to tell the codec where to stop, otherwise all the remaining part of the data will be decoded as unknown
  // fields. Fortunately, we can easily tell what size the channel_update will be.
  val noUnknownFieldsChannelUpdateSizeCodec: Codec[Int] = peek( // we need to take a peek at a specific byte to know what size the message will be, and then rollback to read the full message
    ignore(8 * (64 + 32 + 8 + 4)) ~> // we skip the first fields: signature + chain_hash + short_channel_id + timestamp
      byte // this is the messageFlags byte
  )
    .map(messageFlags => if ((messageFlags & 1) != 0) 136 else 128) // depending on the value of option_channel_htlc_max, size will be 128B or 136B
    .decodeOnly // this is for compat, we only need to decode

  // this is a decode-only codec compatible with versions 9afb26e and below
  val DATA_NORMAL_COMPAT_03_Codec: Codec[DATA_NORMAL] = (
    ("commitments" | commitmentsCodec(false)) ::
      ("shortChannelId" | shortchannelid) ::
      ("buried" | bool) ::
      ("channelAnnouncement" | optional(bool, variableSizeBytes(noUnknownFieldsChannelAnnouncementSizeCodec, channelAnnouncementCodec))) ::
      ("channelUpdate" | variableSizeBytes(noUnknownFieldsChannelUpdateSizeCodec, channelUpdateCodec)) ::
      ("localShutdown" | optional(bool, shutdownCodec)) ::
      ("remoteShutdown" | optional(bool, shutdownCodec))).as[DATA_NORMAL].decodeOnly

  def DATA_NORMAL_Codec(lengthPrefixed: Boolean): Codec[DATA_NORMAL] = (
    ("commitments" | commitmentsCodec(lengthPrefixed)) ::
      ("shortChannelId" | shortchannelid) ::
      ("buried" | bool) ::
      ("channelAnnouncement" | optional(bool, variableSizeBytes(uint16, channelAnnouncementCodec))) ::
      ("channelUpdate" | variableSizeBytes(uint16, channelUpdateCodec)) ::
      ("localShutdown" | optional(bool, maybePrefixed(lengthPrefixed, shutdownCodec))) ::
      ("remoteShutdown" | optional(bool, maybePrefixed(lengthPrefixed, shutdownCodec)))).as[DATA_NORMAL]

  val DATA_NORMAL_COMPAT_10_Codec: Codec[DATA_NORMAL] = DATA_NORMAL_Codec(false)

  val DATA_NORMAL_13_Codec: Codec[DATA_NORMAL] = DATA_NORMAL_Codec(true)

  def DATA_SHUTDOWN_Codec(lengthPrefixed: Boolean): Codec[DATA_SHUTDOWN] = (
    ("commitments" | commitmentsCodec(lengthPrefixed)) ::
      ("localShutdown" | maybePrefixed(lengthPrefixed, shutdownCodec)) ::
      ("remoteShutdown" | maybePrefixed(lengthPrefixed, shutdownCodec))).as[DATA_SHUTDOWN]

  val DATA_SHUTDOWN_COMPAT_04_Codec: Codec[DATA_SHUTDOWN] = DATA_SHUTDOWN_Codec(false)

  val DATA_SHUTDOWN_14_Codec: Codec[DATA_SHUTDOWN] = DATA_SHUTDOWN_Codec(true)

  def DATA_NEGOTIATING_Codec(lengthPrefixed: Boolean): Codec[DATA_NEGOTIATING] = (
    ("commitments" | commitmentsCodec(lengthPrefixed)) ::
      ("localShutdown" | maybePrefixed(lengthPrefixed, shutdownCodec)) ::
      ("remoteShutdown" | maybePrefixed(lengthPrefixed, shutdownCodec)) ::
      ("closingTxProposed" | listOfN(uint16, listOfN(uint16, closingTxProposedCodec(lengthPrefixed)))) ::
      ("bestUnpublishedClosingTx_opt" | optional(bool, txCodec))).as[DATA_NEGOTIATING]

  val DATA_NEGOTIATING_COMPAT_05_Codec: Codec[DATA_NEGOTIATING] = DATA_NEGOTIATING_Codec(false)

  val DATA_NEGOTIATING_15_Codec: Codec[DATA_NEGOTIATING] = DATA_NEGOTIATING_Codec(true)

  // this is a decode-only codec compatible with versions 818199e and below, with placeholders for new fields
  val DATA_CLOSING_COMPAT_06_Codec: Codec[DATA_CLOSING] = (
    ("commitments" | commitmentsCodec(false)) ::
      ("fundingTx" | provide[Option[Transaction]](None)) ::
      ("waitingSince" | provide(Platform.currentTime.milliseconds.toSeconds)) ::
      ("mutualCloseProposed" | listOfN(uint16, txCodec)) ::
      ("mutualClosePublished" | listOfN(uint16, txCodec)) ::
      ("localCommitPublished" | optional(bool, localCommitPublishedCodec)) ::
      ("remoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("nextRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("futureRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).as[DATA_CLOSING].decodeOnly

  def DATA_CLOSING_Codec(lengthPrefixed: Boolean): Codec[DATA_CLOSING] = (
    ("commitments" | commitmentsCodec(lengthPrefixed)) ::
      ("fundingTx" | optional(bool, txCodec)) ::
      ("waitingSince" | int64) ::
      ("mutualCloseProposed" | listOfN(uint16, txCodec)) ::
      ("mutualClosePublished" | listOfN(uint16, txCodec)) ::
      ("localCommitPublished" | optional(bool, localCommitPublishedCodec)) ::
      ("remoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("nextRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("futureRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).as[DATA_CLOSING]

  val DATA_CLOSING_COMPAT_09_Codec: Codec[DATA_CLOSING] = DATA_CLOSING_Codec(false)

  val DATA_CLOSING_16_Codec: Codec[DATA_CLOSING] = DATA_CLOSING_Codec(true)

  def DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec(lengthPrefixed: Boolean): Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = (
    ("commitments" | commitmentsCodec(lengthPrefixed)) ::
      ("remoteChannelReestablish" | maybePrefixed(lengthPrefixed, channelReestablishCodec))).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]

  val DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_COMPAT_07_Codec: Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec(false)

  val DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_17_Codec: Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec(true)

  /**
   * Order matters!!
   *
   * We use the fact that the discriminated codec encodes using the first suitable codec it finds in the list to handle
   * database migration.
   *
   * For example, a data encoded with type 01 will be decoded using [[DATA_WAIT_FOR_FUNDING_CONFIRMED_COMPAT_01_Codec]] and
   * encoded to a type 08 using [[DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec]].
   *
   * More info here: https://github.com/scodec/scodec/issues/122
   */
  val stateDataCodec: Codec[HasCommitments] = ("version" | constant(0x00)) ~> discriminated[HasCommitments].by(uint16)
    .typecase(0x17, DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_17_Codec)
    .typecase(0x16, DATA_CLOSING_16_Codec)
    .typecase(0x15, DATA_NEGOTIATING_15_Codec)
    .typecase(0x14, DATA_SHUTDOWN_14_Codec)
    .typecase(0x13, DATA_NORMAL_13_Codec)
    .typecase(0x12, DATA_WAIT_FOR_FUNDING_LOCKED_12_Codec)
    .typecase(0x11, DATA_WAIT_FOR_FUNDING_CONFIRMED_11_Codec)
    .typecase(0x10, DATA_NORMAL_COMPAT_10_Codec)
    .typecase(0x09, DATA_CLOSING_COMPAT_09_Codec)
    .typecase(0x08, DATA_WAIT_FOR_FUNDING_CONFIRMED_COMPAT_08_Codec)
    .typecase(0x01, DATA_WAIT_FOR_FUNDING_CONFIRMED_COMPAT_01_Codec)
    .typecase(0x02, DATA_WAIT_FOR_FUNDING_LOCKED_COMPAT_02_Codec)
    .typecase(0x03, DATA_NORMAL_COMPAT_03_Codec)
    .typecase(0x04, DATA_SHUTDOWN_COMPAT_04_Codec)
    .typecase(0x05, DATA_NEGOTIATING_COMPAT_05_Codec)
    .typecase(0x06, DATA_CLOSING_COMPAT_06_Codec)
    .typecase(0x07, DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_COMPAT_07_Codec)

}
