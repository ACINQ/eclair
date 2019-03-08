/*
 * Copyright 2018 ACINQ SAS
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

import fr.acinq.bitcoin.Crypto.Point
import fr.acinq.bitcoin.DeterministicWallet.{ExtendedPrivateKey, KeyPath}
import fr.acinq.bitcoin.{BinaryData, OutPoint, Transaction, TxOut}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.payment.{Local, Origin, Relayed}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.LightningMessageCodecs._
import grizzled.slf4j.Logging
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.{Attempt, Codec, DecodeResult, Decoder, Encoder, Err, GenCodec, SizeBound, Transformer}
import shapeless.{Generic, HList, HNil}

/**
  * Created by PM on 02/06/2017.
  */
object ChannelCodecs extends Logging {

  val keyPathCodec: Codec[KeyPath] = ("path" | listOfN(uint16, uint32)).xmap[KeyPath](l => new KeyPath(l), keyPath => keyPath.path.toList).as[KeyPath]

  val extendedPrivateKeyCodec: Codec[ExtendedPrivateKey] = (
    ("secretkeybytes" | binarydata(32)) ::
      ("chaincode" | binarydata(32)) ::
      ("depth" | uint16) ::
      ("path" | keyPathCodec) ::
      ("parent" | int64)).as[ExtendedPrivateKey]

  val localParamsCodec: Codec[LocalParams] = (
    ("nodeId" | publicKey) ::
      ("channelPath" | keyPathCodec) ::
      ("dustLimitSatoshis" | uint64) ::
      ("maxHtlcValueInFlightMsat" | uint64ex) ::
      ("channelReserveSatoshis" | uint64) ::
      ("htlcMinimumMsat" | uint64) ::
      ("toSelfDelay" | uint16) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("isFunder" | bool) ::
      ("defaultFinalScriptPubKey" | varsizebinarydata) ::
      ("globalFeatures" | varsizebinarydata) ::
      ("localFeatures" | varsizebinarydata)).as[LocalParams]

  val remoteParamsCodec: Codec[RemoteParams] = (
    ("nodeId" | publicKey) ::
      ("dustLimitSatoshis" | uint64) ::
      ("maxHtlcValueInFlightMsat" | uint64ex) ::
      ("channelReserveSatoshis" | uint64) ::
      ("htlcMinimumMsat" | uint64) ::
      ("toSelfDelay" | uint16) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("fundingPubKey" | publicKey) ::
      ("revocationBasepoint" | point) ::
      ("paymentBasepoint" | point) ::
      ("delayedPaymentBasepoint" | point) ::
      ("htlcBasepoint" | point) ::
      ("globalFeatures" | varsizebinarydata) ::
      ("localFeatures" | varsizebinarydata)).as[RemoteParams]

  val directionCodec: Codec[Direction] = Codec[Direction](
    (dir: Direction) => bool.encode(dir == IN),
    (wire: BitVector) => bool.decode(wire).map(_.map(b => if (b) IN else OUT))
  )

  val htlcCodec: Codec[DirectedHtlc] = (
    ("direction" | directionCodec) ::
      ("add" | updateAddHtlcCodec)).as[DirectedHtlc]

  def setCodec[T](codec: Codec[T]): Codec[Set[T]] = Codec[Set[T]](
    (elems: Set[T]) => listOfN(uint16, codec).encode(elems.toList),
    (wire: BitVector) => listOfN(uint16, codec).decode(wire).map(_.map(_.toSet))
  )

  val commitmentSpecCodec: Codec[CommitmentSpec] = (
    ("htlcs" | setCodec(htlcCodec)) ::
      ("feeratePerKw" | uint32) ::
      ("toLocalMsat" | uint64) ::
      ("toRemoteMsat" | uint64)).as[CommitmentSpec]

  def outPointCodec: Codec[OutPoint] = variableSizeBytes(uint16, bytes.xmap(d => OutPoint.read(d.toArray), d => ByteVector(OutPoint.write(d).data)))

  def txOutCodec: Codec[TxOut] = variableSizeBytes(uint16, bytes.xmap(d => TxOut.read(d.toArray), d => ByteVector(TxOut.write(d).data)))

  def txCodec: Codec[Transaction] = variableSizeBytes(uint16, bytes.xmap(d => Transaction.read(d.toArray), d => ByteVector(Transaction.write(d).data)))

  val inputInfoCodec: Codec[InputInfo] = (
    ("outPoint" | outPointCodec) ::
      ("txOut" | txOutCodec) ::
      ("redeemScript" | varsizebinarydata)).as[InputInfo]

  val txWithInputInfoCodec: Codec[TransactionWithInputInfo] = discriminated[TransactionWithInputInfo].by(uint16)
    .typecase(0x01, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[CommitTx])
    .typecase(0x02, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | binarydata(32))).as[HtlcSuccessTx])
    .typecase(0x03, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[HtlcTimeoutTx])
    .typecase(0x04, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimHtlcSuccessTx])
    .typecase(0x05, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimHtlcTimeoutTx])
    .typecase(0x06, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimP2WPKHOutputTx])
    .typecase(0x07, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimDelayedOutputTx])
    .typecase(0x08, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[MainPenaltyTx])
    .typecase(0x09, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[HtlcPenaltyTx])
    .typecase(0x10, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClosingTx])

  val htlcTxAndSigsCodec: Codec[HtlcTxAndSigs] = (
    ("txinfo" | txWithInputInfoCodec) ::
      ("localSig" | varsizebinarydata) ::
      ("remoteSig" | varsizebinarydata)).as[HtlcTxAndSigs]

  val publishableTxsCodec: Codec[PublishableTxs] = (
    ("commitTx" | (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[CommitTx]) ::
      ("htlcTxsAndSigs" | listOfN(uint16, htlcTxAndSigsCodec))).as[PublishableTxs]

  val localCommitCodec: Codec[LocalCommit] = (
    ("index" | uint64) ::
      ("spec" | commitmentSpecCodec) ::
      ("publishableTxs" | publishableTxsCodec)).as[LocalCommit]

  val remoteCommitCodec: Codec[RemoteCommit] = (
    ("index" | uint64) ::
      ("spec" | commitmentSpecCodec) ::
      ("txid" | binarydata(32)) ::
      ("remotePerCommitmentPoint" | point)).as[RemoteCommit]

  val updateMessageCodec: Codec[UpdateMessage] = lightningMessageCodec.narrow(f => Attempt.successful(f.asInstanceOf[UpdateMessage]), g => g)

  val localChangesCodec: Codec[LocalChanges] = (
    ("proposed" | listOfN(uint16, updateMessageCodec)) ::
      ("signed" | listOfN(uint16, updateMessageCodec)) ::
      ("acked" | listOfN(uint16, updateMessageCodec))).as[LocalChanges]

  val remoteChangesCodec: Codec[RemoteChanges] = (
    ("proposed" | listOfN(uint16, updateMessageCodec)) ::
      ("acked" | listOfN(uint16, updateMessageCodec)) ::
      ("signed" | listOfN(uint16, updateMessageCodec))).as[RemoteChanges]

  val waitingForRevocationCodec: Codec[WaitingForRevocation] = (
    ("nextRemoteCommit" | remoteCommitCodec) ::
      ("sent" | commitSigCodec) ::
      ("sentAfterLocalCommitIndex" | uint64) ::
      ("reSignAsap" | bool)).as[WaitingForRevocation]

  val relayedCodec: Codec[Relayed] = (
    ("originChannelId" | binarydata(32)) ::
      ("originHtlcId" | int64) ::
      ("amountMsatIn" | uint64) ::
      ("amountMsatOut" | uint64)).as[Relayed]

  val originCodec: Codec[Origin] = discriminated[Origin].by(uint16)
    .typecase(0x01, provide(Local(None)))
    .typecase(0x02, relayedCodec)

  val originsListCodec: Codec[List[(Long, Origin)]] = listOfN(uint16, int64 ~ originCodec)

  val originsMapCodec: Codec[Map[Long, Origin]] = Codec[Map[Long, Origin]](
    (map: Map[Long, Origin]) => originsListCodec.encode(map.toList),
    (wire: BitVector) => originsListCodec.decode(wire).map(_.map(_.toMap))
  )

  val spentListCodec: Codec[List[(OutPoint, BinaryData)]] = listOfN(uint16, outPointCodec ~ binarydata(32))

  val spentMapCodec: Codec[Map[OutPoint, BinaryData]] = Codec[Map[OutPoint, BinaryData]](
    (map: Map[OutPoint, BinaryData]) => spentListCodec.encode(map.toList),
    (wire: BitVector) => spentListCodec.decode(wire).map(_.map(_.toMap))
  )

  val closingTxProposedCodec: Codec[ClosingTxProposed] = (
    ("unsignedTx" | txCodec) ::
      ("localClosingSigned" | closingSignedCodec)).as[ClosingTxProposed]

  val localCommitPublishedCodec: Codec[LocalCommitPublished] = (
    ("commitTx" | txCodec) ::
      ("claimMainDelayedOutputTx" | optional(bool, txCodec)) ::
      ("htlcSuccessTxs" | listOfN(uint16, txCodec)) ::
      ("htlcTimeoutTxs" | listOfN(uint16, txCodec)) ::
      ("claimHtlcDelayedTx" | listOfN(uint16, txCodec)) ::
      ("pushMeTx" | optional(bool, txCodec)) ::
      ("spent" | spentMapCodec)).as[LocalCommitPublished]

  val remoteCommitPublishedCodec: Codec[RemoteCommitPublished] = (
    ("commitTx" | txCodec) ::
      ("claimMainOutputTx" | optional(bool, txCodec)) ::
      ("claimHtlcSuccessTxs" | listOfN(uint16, txCodec)) ::
      ("claimHtlcTimeoutTxs" | listOfN(uint16, txCodec)) ::
      ("pushMeTx" | optional(bool, txCodec)) ::
      ("spent" | spentMapCodec)).as[RemoteCommitPublished]

  val revokedCommitPublishedCodec: Codec[RevokedCommitPublished] = (
    ("commitTx" | txCodec) ::
      ("claimMainOutputTx" | optional(bool, txCodec)) ::
      ("mainPenaltyTx" | optional(bool, txCodec)) ::
      ("htlcPenaltyTxs" | listOfN(uint16, txCodec)) ::
      ("claimHtlcDelayedPenaltyTxs" | listOfN(uint16, txCodec)) ::
      ("spent" | spentMapCodec)).as[RevokedCommitPublished]

  def commitmentCodec(commitmentVersion: CommitmentVersion): Codec[Commitments] = {
    (("localParams" | localParamsCodec) ::
      ("remoteParams" | remoteParamsCodec) ::
      ("channelFlags" | byte) ::
      ("localCommit" | localCommitCodec) ::
      ("remoteCommit" | remoteCommitCodec) ::
      ("localChanges" | localChangesCodec) ::
      ("remoteChanges" | remoteChangesCodec) ::
      ("localNextHtlcId" | uint64) ::
      ("remoteNextHtlcId" | uint64) ::
      ("originChannels" | originsMapCodec) ::
      ("remoteNextCommitInfo" | either(bool, waitingForRevocationCodec, point)) ::
      ("commitInput" | inputInfoCodec) ::
      ("remotePerCommitmentSecrets" | ShaChain.shaChainCodec) ::
      ("channelId" | binarydata(32)) ::
      ("version" | provide(commitmentVersion))).as[Commitments]
  }

  def DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec(commitmentVersion: CommitmentVersion): Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
    ("commitments" | commitmentCodec(commitmentVersion)) ::
      ("deferred" | optional(bool, fundingLockedCodec)) ::
      ("lastSent" | either(bool, fundingCreatedCodec, fundingSignedCodec))).as[DATA_WAIT_FOR_FUNDING_CONFIRMED]

  def DATA_WAIT_FOR_FUNDING_LOCKED_Codec(commitmentVersion: CommitmentVersion): Codec[DATA_WAIT_FOR_FUNDING_LOCKED] = (
    ("commitments" | commitmentCodec(commitmentVersion)) ::
      ("shortChannelId" | shortchannelid) ::
      ("lastSent" | fundingLockedCodec)).as[DATA_WAIT_FOR_FUNDING_LOCKED]

  def DATA_NORMAL_Codec(commitmentVersion: CommitmentVersion): Codec[DATA_NORMAL] = (
    ("commitments" | commitmentCodec(commitmentVersion)) ::
      ("shortChannelId" | shortchannelid) ::
      ("buried" | bool) ::
      ("channelAnnouncement" | optional(bool, channelAnnouncementCodec)) ::
      ("channelUpdate" | channelUpdateCodec) ::
      ("localShutdown" | optional(bool, shutdownCodec)) ::
      ("remoteShutdown" | optional(bool, shutdownCodec))).as[DATA_NORMAL]

  def DATA_SHUTDOWN_Codec(commitmentVersion: CommitmentVersion): Codec[DATA_SHUTDOWN] = (
    ("commitments" | commitmentCodec(commitmentVersion)) ::
      ("localShutdown" | shutdownCodec) ::
      ("remoteShutdown" | shutdownCodec)).as[DATA_SHUTDOWN]

  def DATA_NEGOTIATING_Codec(commitmentVersion: CommitmentVersion): Codec[DATA_NEGOTIATING] = (
    ("commitments" | commitmentCodec(commitmentVersion)) ::
      ("localShutdown" | shutdownCodec) ::
      ("remoteShutdown" | shutdownCodec) ::
      ("closingTxProposed" | listOfN(uint16, listOfN(uint16, closingTxProposedCodec))) ::
      ("bestUnpublishedClosingTx_opt" | optional(bool, txCodec))).as[DATA_NEGOTIATING]

  def DATA_CLOSING_Codec(commitmentVersion: CommitmentVersion): Codec[DATA_CLOSING] = (
    ("commitments" | commitmentCodec(commitmentVersion)) ::
      ("mutualCloseProposed" | listOfN(uint16, txCodec)) ::
      ("mutualClosePublished" | listOfN(uint16, txCodec)) ::
      ("localCommitPublished" | optional(bool, localCommitPublishedCodec)) ::
      ("remoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("nextRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("futureRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).as[DATA_CLOSING]

  def DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec(commitmentVersion: CommitmentVersion): Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = (
    ("commitments" | commitmentCodec(commitmentVersion)) ::
      ("remoteChannelReestablish" | channelReestablishCodec)).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]

  def stateDataCodec(commitmentVersion: CommitmentVersion): Codec[HasCommitments] = discriminated[HasCommitments].by(uint16)
    .typecase(0x01, DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec(commitmentVersion))
    .typecase(0x02, DATA_WAIT_FOR_FUNDING_LOCKED_Codec(commitmentVersion))
    .typecase(0x03, DATA_NORMAL_Codec(commitmentVersion))
    .typecase(0x04, DATA_SHUTDOWN_Codec(commitmentVersion))
    .typecase(0x05, DATA_NEGOTIATING_Codec(commitmentVersion))
    .typecase(0x06, DATA_CLOSING_Codec(commitmentVersion))
    .typecase(0x07, DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec(commitmentVersion))

  val COMMITMENTv1_VERSION_BYTE = 0x00.toByte
  val COMMITMENT_SIMPLIFIED_VERSION_BYTE = 0x01.toByte

  val genericStateDataCodec = discriminated[HasCommitments].by(uint8)
    .\ (COMMITMENTv1_VERSION_BYTE) { case c if c.commitments.version == VersionCommitmentV1 => c } (stateDataCodec(VersionCommitmentV1))
    .\ (COMMITMENT_SIMPLIFIED_VERSION_BYTE) { case c if c.commitments.version == VersionSimplifiedCommitment => c } (stateDataCodec(VersionSimplifiedCommitment))

}
