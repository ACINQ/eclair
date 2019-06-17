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
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.DeterministicWallet.{ExtendedPrivateKey, KeyPath}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, OutPoint, Transaction, TxOut}
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.payment.{Local, Origin, Relayed}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.LightningMessageCodecs._
import grizzled.slf4j.Logging
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.{Attempt, Codec}
import shapeless.{HList, HNil}
import scala.compat.Platform
import scala.concurrent.duration._


/**
  * Created by PM on 02/06/2017.
  */
object ChannelCodecs extends Logging {

  val keyPathCodec: Codec[KeyPath] = ("path" | listOfN(uint16, uint32)).xmap[KeyPath](l => new KeyPath(l), keyPath => keyPath.path.toList).as[KeyPath]
  val keyPathFundeeCodec: Codec[KeyPathFundee] = (
    ("fundingKeyPath" | keyPathCodec) ::
      ("pointsKeyPath" | keyPathCodec)
    ).as[KeyPathFundee]

  val channelKeyPathCodec = either(bool, keyPathCodec, keyPathFundeeCodec)

  val extendedPrivateKeyCodec: Codec[ExtendedPrivateKey] = (
    ("secretkeybytes" | bytes32) ::
      ("chaincode" | bytes32) ::
      ("depth" | uint16) ::
      ("path" | keyPathCodec) ::
      ("parent" | int64)).as[ExtendedPrivateKey]

  import shapeless._
  val localParamsCodecV0: Codec[LocalParams] = (
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
      ("localFeatures" | varsizebinarydata)).xmap({
    case nodeId ::
      keyPath ::
      dustLimitSatoshis ::
      maxHtlcValueInFlightMsat ::
      channelReserveSatoshis ::
      htlcMinimumMsat ::
      toSelfDelay ::
      maxAcceptedHtlcs ::
      isFunder ::
      defaultFinalScriptPubKey ::
      globalFeatures :: localFeatures :: HNil =>

      LocalParams(
        nodeId = nodeId,
        // old versions of "LocalParams" use a single keypath
        channelKeyPath = if(isFunder) Left(keyPath) else Right(KeyPathFundee(keyPath, keyPath)),
        dustLimitSatoshis = dustLimitSatoshis,
        maxHtlcValueInFlightMsat = maxHtlcValueInFlightMsat,
        channelReserveSatoshis = channelReserveSatoshis,
        htlcMinimumMsat = htlcMinimumMsat,
        toSelfDelay = toSelfDelay,
        maxAcceptedHtlcs = maxAcceptedHtlcs,
        defaultFinalScriptPubKey = defaultFinalScriptPubKey,
        globalFeatures = globalFeatures,
        localFeatures = localFeatures)
  },{ localParams =>

    localParams.nodeId ::
      localParams.channelKeyPath.left.get ::
      localParams.dustLimitSatoshis ::
      localParams.maxHtlcValueInFlightMsat ::
      localParams.channelReserveSatoshis ::
      localParams.htlcMinimumMsat ::
      localParams.toSelfDelay ::
      localParams.maxAcceptedHtlcs ::
      localParams.isFunder ::
      localParams.defaultFinalScriptPubKey ::
      localParams.globalFeatures :: localParams.localFeatures :: HNil
  })

  val localParamsCodecV1: Codec[LocalParams] = (
    ("nodeId" | publicKey) ::
      ("channelPath" | channelKeyPathCodec) ::
      ("dustLimitSatoshis" | uint64) ::
      ("maxHtlcValueInFlightMsat" | uint64ex) ::
      ("channelReserveSatoshis" | uint64) ::
      ("htlcMinimumMsat" | uint64) ::
      ("toSelfDelay" | uint16) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("defaultFinalScriptPubKey" | varsizebinarydata) ::
      ("globalFeatures" | varsizebinarydata) ::
      ("localFeatures" | varsizebinarydata)).as[LocalParams]

  def localParamsCodec(version: CommitmentVersion): Codec[LocalParams] = version match {
    case CommitmentV1 => localParamsCodecV1
    case CommitmentV0 => localParamsCodecV0
  }

  val remoteParamsCodec: Codec[RemoteParams] = (
    ("nodeId" | publicKey) ::
      ("dustLimitSatoshis" | uint64) ::
      ("maxHtlcValueInFlightMsat" | uint64ex) ::
      ("channelReserveSatoshis" | uint64) ::
      ("htlcMinimumMsat" | uint64) ::
      ("toSelfDelay" | uint16) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("fundingPubKey" | publicKey) ::
      ("revocationBasepoint" | publicKey) ::
      ("paymentBasepoint" | publicKey) ::
      ("delayedPaymentBasepoint" | publicKey) ::
      ("htlcBasepoint" | publicKey) ::
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

  def outPointCodec: Codec[OutPoint] = variableSizeBytes(uint16, bytes.xmap(d => OutPoint.read(d.toArray), d => OutPoint.write(d)))

  def txOutCodec: Codec[TxOut] = variableSizeBytes(uint16, bytes.xmap(d => TxOut.read(d.toArray), d => TxOut.write(d)))

  def txCodec: Codec[Transaction] = variableSizeBytes(uint16, bytes.xmap(d => Transaction.read(d.toArray), d => Transaction.write(d)))

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

  val localCommitCodec: Codec[LocalCommit] = (
    ("index" | uint64) ::
      ("spec" | commitmentSpecCodec) ::
      ("publishableTxs" | publishableTxsCodec)).as[LocalCommit]

  val remoteCommitCodec: Codec[RemoteCommit] = (
    ("index" | uint64) ::
      ("spec" | commitmentSpecCodec) ::
      ("txid" | bytes32) ::
      ("remotePerCommitmentPoint" | publicKey)).as[RemoteCommit]

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

  val localCodec: Codec[Local] = (
    ("id" | uuid) ::
      ("sender" | provide(Option.empty[ActorRef]))
    ).as[Local]

  val relayedCodec: Codec[Relayed] = (
    ("originChannelId" | bytes32) ::
      ("originHtlcId" | int64) ::
      ("amountMsatIn" | uint64) ::
      ("amountMsatOut" | uint64)).as[Relayed]

  // this is for backward compatibility to handle legacy payments that didn't have identifiers
  val UNKNOWN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

  val originCodec: Codec[Origin] = discriminated[Origin].by(uint16)
    .typecase(0x03, localCodec) // backward compatible
    .typecase(0x01, provide(Local(UNKNOWN_UUID, None)))
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

  def commitmentsCodec(version: CommitmentVersion): Codec[Commitments] = (
    ("localParams" | localParamsCodec(version)) ::
      ("remoteParams" | remoteParamsCodec) ::
      ("channelFlags" | byte) ::
      ("localCommit" | localCommitCodec) ::
      ("remoteCommit" | remoteCommitCodec) ::
      ("localChanges" | localChangesCodec) ::
      ("remoteChanges" | remoteChangesCodec) ::
      ("localNextHtlcId" | uint64) ::
      ("remoteNextHtlcId" | uint64) ::
      ("originChannels" | originsMapCodec) ::
      ("remoteNextCommitInfo" | either(bool, waitingForRevocationCodec, publicKey)) ::
      ("commitInput" | inputInfoCodec) ::
      ("remotePerCommitmentSecrets" | ShaChain.shaChainCodec) ::
      ("channelId" | bytes32)).as[Commitments]

  val closingTxProposedCodec: Codec[ClosingTxProposed] = (
    ("unsignedTx" | txCodec) ::
      ("localClosingSigned" | closingSignedCodec)).as[ClosingTxProposed]

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
  def DATA_WAIT_FOR_FUNDING_CONFIRMED_COMPAT_01_Codec(version: CommitmentVersion): Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
    ("commitments" | commitmentsCodec(version)) ::
      ("fundingTx" | provide[Option[Transaction]](None)) ::
      ("waitingSince" | provide(Platform.currentTime.milliseconds.toSeconds)) ::
      ("deferred" | optional(bool, fundingLockedCodec)) ::
      ("lastSent" | either(bool, fundingCreatedCodec, fundingSignedCodec))).as[DATA_WAIT_FOR_FUNDING_CONFIRMED].decodeOnly

  def DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec(version: CommitmentVersion): Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
    ("commitments" | commitmentsCodec(version)) ::
      ("fundingTx" | optional(bool, txCodec)) ::
      ("waitingSince" | int64) ::
      ("deferred" | optional(bool, fundingLockedCodec)) ::
      ("lastSent" | either(bool, fundingCreatedCodec, fundingSignedCodec))).as[DATA_WAIT_FOR_FUNDING_CONFIRMED]

  def DATA_WAIT_FOR_FUNDING_LOCKED_Codec(version: CommitmentVersion): Codec[DATA_WAIT_FOR_FUNDING_LOCKED] = (
    ("commitments" | commitmentsCodec(version)) ::
      ("shortChannelId" | shortchannelid) ::
      ("lastSent" | fundingLockedCodec)).as[DATA_WAIT_FOR_FUNDING_LOCKED]

  def DATA_NORMAL_Codec(version: CommitmentVersion): Codec[DATA_NORMAL] = (
    ("commitments" | commitmentsCodec(version)) ::
      ("shortChannelId" | shortchannelid) ::
      ("buried" | bool) ::
      ("channelAnnouncement" | optional(bool, channelAnnouncementCodec)) ::
      ("channelUpdate" | channelUpdateCodec) ::
      ("localShutdown" | optional(bool, shutdownCodec)) ::
      ("remoteShutdown" | optional(bool, shutdownCodec))).as[DATA_NORMAL]

  def DATA_SHUTDOWN_Codec(version: CommitmentVersion): Codec[DATA_SHUTDOWN] = (
    ("commitments" | commitmentsCodec(version)) ::
      ("localShutdown" | shutdownCodec) ::
      ("remoteShutdown" | shutdownCodec)).as[DATA_SHUTDOWN]

  def DATA_NEGOTIATING_Codec(version: CommitmentVersion): Codec[DATA_NEGOTIATING] = (
    ("commitments" | commitmentsCodec(version)) ::
      ("localShutdown" | shutdownCodec) ::
      ("remoteShutdown" | shutdownCodec) ::
      ("closingTxProposed" | listOfN(uint16, listOfN(uint16, closingTxProposedCodec))) ::
      ("bestUnpublishedClosingTx_opt" | optional(bool, txCodec))).as[DATA_NEGOTIATING]

  def DATA_CLOSING_Codec(version: CommitmentVersion): Codec[DATA_CLOSING] = (
    ("commitments" | commitmentsCodec(version)) ::
      ("mutualCloseProposed" | listOfN(uint16, txCodec)) ::
      ("mutualClosePublished" | listOfN(uint16, txCodec)) ::
      ("localCommitPublished" | optional(bool, localCommitPublishedCodec)) ::
      ("remoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("nextRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("futureRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
      ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).as[DATA_CLOSING]

  def DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec(version: CommitmentVersion): Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = (
    ("commitments" | commitmentsCodec(version)) ::
      ("remoteChannelReestablish" | channelReestablishCodec)).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]


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
  val COMMITMENTv0_VERSION_BYTE = 0x00.toByte
  val COMMITMENTv1_VERSION_BYTE = 0x01.toByte

  // Order matters!
  val stateDataCodec = discriminated[HasCommitments].by(uint8)
    .\(COMMITMENTv1_VERSION_BYTE) { case c => c }(stateDataCodecVersioned(CommitmentV1))
    .\(COMMITMENTv0_VERSION_BYTE) { case c => c }(stateDataCodecVersioned(CommitmentV0))

  private def stateDataCodecVersioned(commitmentVersion: CommitmentVersion): Codec[HasCommitments] = discriminated[HasCommitments].by(uint16)
    .typecase(0x08, DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec(commitmentVersion))
    .typecase(0x01, DATA_WAIT_FOR_FUNDING_CONFIRMED_COMPAT_01_Codec(commitmentVersion))
    .typecase(0x02, DATA_WAIT_FOR_FUNDING_LOCKED_Codec(commitmentVersion))
    .typecase(0x03, DATA_NORMAL_Codec(commitmentVersion))
    .typecase(0x04, DATA_SHUTDOWN_Codec(commitmentVersion))
    .typecase(0x05, DATA_NEGOTIATING_Codec(commitmentVersion))
    .typecase(0x06, DATA_CLOSING_Codec(commitmentVersion))
    .typecase(0x07, DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec(commitmentVersion))


  sealed trait CommitmentVersion
  case object CommitmentV0 extends CommitmentVersion
  case object CommitmentV1 extends CommitmentVersion

}
