/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.wire.internal.channel.version1

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, Transaction, TxId, TxOut}
import fr.acinq.eclair.channel.LocalFundingStatus.SingleFundedUnconfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.crypto.keymanager.{LocalCommitmentKeys, RemoteCommitmentKeys}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.internal.channel.version0.ChannelTypes0
import fr.acinq.eclair.wire.internal.channel.version0.ChannelTypes0.{HtlcTxAndSigs, PublishableTxs}
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Alias, BlockHeight, CltvExpiry, CltvExpiryDelta, MilliSatoshiLong}
import scodec.bits.{ByteVector, HexStringSyntax}
import scodec.codecs._
import scodec.{Attempt, Codec}
import shapeless.{::, HNil}

private[channel] object ChannelCodecs1 {

  private[version1] object Codecs {

    val keyPathCodec: Codec[KeyPath] = ("path" | listOfN(uint16, uint32)).xmap[KeyPath](l => KeyPath(l), keyPath => keyPath.path.toList).as[KeyPath]

    val channelVersionCodec: Codec[ChannelTypes0.ChannelVersion] = bits(ChannelTypes0.ChannelVersion.LENGTH_BITS).as[ChannelTypes0.ChannelVersion]

    def localParamsCodec(channelVersion: ChannelTypes0.ChannelVersion): Codec[ChannelTypes0.LocalParams] = (
      ("nodeId" | publicKey) ::
        ("channelPath" | keyPathCodec) ::
        ("dustLimit" | satoshi) ::
        ("maxHtlcValueInFlightMsat" | uint64) ::
        ("channelReserve" | conditional(included = true, satoshi)) ::
        ("htlcMinimum" | millisatoshi) ::
        ("toSelfDelay" | cltvExpiryDelta) ::
        ("maxAcceptedHtlcs" | uint16) ::
        ("isChannelOpener" | bool) :: ("paysCommitTxFees" | bool) :: ignore(6) ::
        ("upfrontShutdownScript_opt" | lengthDelimited(bytes).map(Option(_)).decodeOnly) ::
        ("walletStaticPaymentBasepoint" | optional(provide(channelVersion.paysDirectlyToWallet), publicKey)) ::
        ("features" | combinedFeaturesCodec)).as[ChannelTypes0.LocalParams].decodeOnly

    val remoteParamsCodec: Codec[ChannelTypes0.RemoteParams] = (
      ("nodeId" | publicKey) ::
        ("dustLimit" | satoshi) ::
        ("maxHtlcValueInFlightMsat" | uint64) ::
        ("channelReserve" | conditional(included = true, satoshi)) ::
        ("htlcMinimum" | millisatoshi) ::
        ("toSelfDelay" | cltvExpiryDelta) ::
        ("maxAcceptedHtlcs" | uint16) ::
        ("fundingPubKey" | publicKey) ::
        ("revocationBasepoint" | publicKey) ::
        ("paymentBasepoint" | publicKey) ::
        ("delayedPaymentBasepoint" | publicKey) ::
        ("htlcBasepoint" | publicKey) ::
        ("features" | combinedFeaturesCodec) ::
        ("shutdownScript" | provide[Option[ByteVector]](None))).as[ChannelTypes0.RemoteParams]

    def setCodec[T](codec: Codec[T]): Codec[Set[T]] = listOfN(uint16, codec).xmap(_.toSet, _.toList)

    val htlcCodec: Codec[DirectedHtlc] = discriminated[DirectedHtlc].by(bool8)
      .typecase(true, lengthDelimited(updateAddHtlcCodec).as[IncomingHtlc])
      .typecase(false, lengthDelimited(updateAddHtlcCodec).as[OutgoingHtlc])

    val commitmentSpecCodec: Codec[CommitmentSpec] = (
      ("htlcs" | setCodec(htlcCodec)) ::
        ("feeratePerKw" | feeratePerKw) ::
        ("toLocal" | millisatoshi) ::
        ("toRemote" | millisatoshi)).as[CommitmentSpec]

    val outPointCodec: Codec[OutPoint] = lengthDelimited(bytes.xmap(d => OutPoint.read(d.toArray), d => OutPoint.write(d)))

    val txOutCodec: Codec[TxOut] = lengthDelimited(bytes.xmap(d => TxOut.read(d.toArray), d => TxOut.write(d)))

    val txCodec: Codec[Transaction] = lengthDelimited(bytes.xmap(d => Transaction.read(d.toArray), d => Transaction.write(d)))

    val closingTxCodec: Codec[ClosingTx] = txCodec.decodeOnly.xmap(
      tx => ChannelTypes0.migrateClosingTx(tx),
      closingTx => closingTx.tx
    )

    val inputInfoCodec: Codec[InputInfo] = (
      ("outPoint" | outPointCodec) ::
        ("txOut" | txOutCodec) ::
        ("redeemScript" | lengthDelimited(bytes))).map {
      case outpoint :: txOut :: _ :: HNil => InputInfo(outpoint, txOut)
    }.decodeOnly

    private val missingHtlcExpiry: Codec[CltvExpiry] = provide(CltvExpiry(0))
    private val missingPaymentHash: Codec[ByteVector32] = provide(ByteVector32.Zeroes)
    private val missingToSelfDelay: Codec[CltvExpiryDelta] = provide(CltvExpiryDelta(0))
    // Those fields have been added to our transactions after we stopped storing them in our channel data, so they're safe to ignore.
    private val unusedCommitmentFormat: Codec[CommitmentFormat] = provide(DefaultCommitmentFormat)
    private val dummyPrivateKey = PrivateKey(hex"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1")
    private val dummyPublicKey = dummyPrivateKey.publicKey
    private val unusedRemoteCommitKeys: Codec[RemoteCommitmentKeys] = provide(RemoteCommitmentKeys(Right(dummyPrivateKey), dummyPublicKey, dummyPublicKey, dummyPrivateKey, dummyPublicKey, dummyPublicKey))
    private val unusedLocalCommitKeys: Codec[LocalCommitmentKeys] = provide(LocalCommitmentKeys(dummyPrivateKey, dummyPublicKey, dummyPublicKey, dummyPrivateKey, dummyPublicKey, dummyPublicKey))
    private val unusedRevocationKey: Codec[PrivateKey] = provide(dummyPrivateKey)
    private val unusedRevokedRedeemInfo: Codec[RedeemInfo] = provide(RedeemInfo.P2wsh(Nil))

    // NB: we can safely set htlcId = 0 for htlc txs. This information is only used to find upstream htlcs to fail when a
    // downstream htlc times out, and `Helpers.Closing.timedOutHtlcs` explicitly handles the case where htlcId is missing.
    val txWithInputInfoCodec: Codec[TransactionWithInputInfo] = discriminated[TransactionWithInputInfo].by(uint16)
      .typecase(0x01, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[CommitTx])
      .typecase(0x02, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | provide(0L)) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[UnsignedHtlcSuccessTx])
      .typecase(0x03, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcId" | provide(0L)) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[UnsignedHtlcTimeoutTx])
      .typecase(0x04, (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcId" | provide(0L)) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[ClaimHtlcSuccessTx])
      .typecase(0x05, (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcId" | provide(0L)) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[ClaimHtlcTimeoutTx])
      .typecase(0x06, (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: unusedCommitmentFormat).as[ClaimP2WPKHOutputTx])
      .typecase(0x07, (unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("toSelfDelay" | missingToSelfDelay) :: unusedCommitmentFormat).as[ClaimLocalDelayedOutputTx])
      .typecase(0x08, (unusedRemoteCommitKeys :: unusedRevocationKey :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("toSelfDelay" | missingToSelfDelay) :: unusedCommitmentFormat).as[MainPenaltyTx])
      .typecase(0x09, (unusedRemoteCommitKeys :: unusedRevocationKey :: unusedRevokedRedeemInfo :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[HtlcPenaltyTx])
      .typecase(0x10, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("outputIndex" | provide(Option.empty[Long]))).as[ClosingTx])

    val htlcTxAndSigsCodec: Codec[HtlcTxAndSigs] = (
      ("txinfo" | txWithInputInfoCodec.downcast[UnsignedHtlcTx]) ::
        ("localSig" | lengthDelimited(bytes64)) :: // we store as variable length for historical purposes (we used to store as DER encoded)
        ("remoteSig" | lengthDelimited(bytes64))).as[HtlcTxAndSigs]

    val publishableTxsCodec: Codec[PublishableTxs] = (
      ("commitTx" | (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[CommitTx]) ::
        ("htlcTxsAndSigs" | listOfN(uint16, htlcTxAndSigsCodec))).as[PublishableTxs]

    val localCommitCodec: Codec[ChannelTypes0.LocalCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("publishableTxs" | publishableTxsCodec)).as[ChannelTypes0.LocalCommit].decodeOnly

    val remoteCommitCodec: Codec[RemoteCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("txid" | txId) ::
        ("remotePerCommitmentPoint" | publicKey)).as[RemoteCommit]

    val updateMessageCodec: Codec[UpdateMessage] = lengthDelimited(lightningMessageCodec.narrow[UpdateMessage](f => Attempt.successful(f.asInstanceOf[UpdateMessage]), g => g))

    val localChangesCodec: Codec[LocalChanges] = (
      ("proposed" | listOfN(uint16, updateMessageCodec)) ::
        ("signed" | listOfN(uint16, updateMessageCodec)) ::
        ("acked" | listOfN(uint16, updateMessageCodec))).as[LocalChanges]

    val remoteChangesCodec: Codec[RemoteChanges] = (
      ("proposed" | listOfN(uint16, updateMessageCodec)) ::
        ("acked" | listOfN(uint16, updateMessageCodec)) ::
        ("signed" | listOfN(uint16, updateMessageCodec))).as[RemoteChanges]

    val waitingForRevocationCodec: Codec[ChannelTypes0.WaitingForRevocation] = (
      ("nextRemoteCommit" | remoteCommitCodec) ::
        ("sent" | lengthDelimited(commitSigCodec)) ::
        ("sentAfterLocalCommitIndex" | uint64overflow) ::
        ("reSignAsap" | ignore(8))).as[ChannelTypes0.WaitingForRevocation]

    val upstreamLocalCodec: Codec[Upstream.Local] = ("id" | uuid).as[Upstream.Local]

    val upstreamChannelCodec: Codec[Upstream.Cold.Channel] = (
      ("originChannelId" | bytes32) ::
        ("originHtlcId" | int64) ::
        ("amountIn" | millisatoshi) ::
        ("amountOut" | ignore(64))).as[Upstream.Cold.Channel]

    val upstreamChannelWithoutAmountCodec: Codec[Upstream.Cold.Channel] = (
      ("originChannelId" | bytes32) ::
        ("originHtlcId" | int64) ::
        ("amountIn" | provide(0 msat))).as[Upstream.Cold.Channel]

    val upstreamTrampolineCodec: Codec[Upstream.Cold.Trampoline] = listOfN(uint16, upstreamChannelWithoutAmountCodec).as[Upstream.Cold.Trampoline]

    val coldUpstreamCodec: Codec[Upstream.Cold] = discriminated[Upstream.Cold].by(uint16)
      .typecase(0x02, upstreamChannelCodec)
      .typecase(0x03, upstreamLocalCodec)
      .typecase(0x04, upstreamTrampolineCodec)

    val originCodec: Codec[Origin] = coldUpstreamCodec.xmap[Origin](
      upstream => Origin.Cold(upstream),
      {
        case Origin.Hot(_, upstream) => Upstream.Cold(upstream)
        case Origin.Cold(upstream) => upstream
      }
    )

    def mapCodec[K, V](keyCodec: Codec[K], valueCodec: Codec[V]): Codec[Map[K, V]] = listOfN(uint16, keyCodec ~ valueCodec).xmap(_.toMap, _.toList)

    val originsMapCodec: Codec[Map[Long, Origin]] = mapCodec(int64, originCodec)

    val spentMapCodec: Codec[Map[OutPoint, TxId]] = mapCodec(outPointCodec, txId)

    val commitmentsCodec: Codec[Commitments] = (
      ("channelVersion" | channelVersionCodec) >>:~ { channelVersion =>
        ("localParams" | localParamsCodec(channelVersion)) ::
          ("remoteParams" | remoteParamsCodec) ::
          ("channelFlags" | channelflags) ::
          ("localCommit" | localCommitCodec) ::
          ("remoteCommit" | remoteCommitCodec) ::
          ("localChanges" | localChangesCodec) ::
          ("remoteChanges" | remoteChangesCodec) ::
          ("localNextHtlcId" | uint64overflow) ::
          ("remoteNextHtlcId" | uint64overflow) ::
          ("originChannels" | originsMapCodec) ::
          ("remoteNextCommitInfo" | either(bool8, waitingForRevocationCodec, publicKey)) ::
          ("commitInput" | inputInfoCodec) ::
          ("remotePerCommitmentSecrets" | byteAligned(ShaChain.shaChainCodec)) ::
          ("channelId" | bytes32)
      }).as[ChannelTypes0.Commitments].decodeOnly.map[Commitments](_.migrate()).decodeOnly

    val closingTxProposedCodec: Codec[ClosingTxProposed] = (
      ("unsignedTx" | closingTxCodec) ::
        ("localClosingSigned" | lengthDelimited(closingSignedCodec))).as[ClosingTxProposed]

    val localCommitPublishedCodec: Codec[LocalCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainDelayedOutputTx" | optional(bool8, txCodec)) ::
        ("htlcSuccessTxs" | listOfN(uint16, txCodec)) ::
        ("htlcTimeoutTxs" | listOfN(uint16, txCodec)) ::
        ("claimHtlcDelayedTx" | listOfN(uint16, txCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes0.LocalCommitPublished].decodeOnly.map[LocalCommitPublished](_.migrate()).decodeOnly

    val remoteCommitPublishedCodec: Codec[RemoteCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainOutputTx" | optional(bool8, txCodec)) ::
        ("claimHtlcSuccessTxs" | listOfN(uint16, txCodec)) ::
        ("claimHtlcTimeoutTxs" | listOfN(uint16, txCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes0.RemoteCommitPublished].decodeOnly.map[RemoteCommitPublished](_.migrate()).decodeOnly

    val revokedCommitPublishedCodec: Codec[RevokedCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainOutputTx" | optional(bool8, txCodec)) ::
        ("mainPenaltyTx" | optional(bool8, txCodec)) ::
        ("htlcPenaltyTxs" | listOfN(uint16, txCodec)) ::
        ("claimHtlcDelayedPenaltyTxs" | listOfN(uint16, txCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes0.RevokedCommitPublished].decodeOnly.map[RevokedCommitPublished](_.migrate()).decodeOnly

    val DATA_WAIT_FOR_FUNDING_CONFIRMED_20_Codec: Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
      ("commitments" | commitmentsCodec) ::
        ("fundingTx_opt" | optional(bool8, txCodec)) ::
        ("waitingSince" | int64.as[BlockHeight]) ::
        ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec))) ::
        ("lastSent" | either(bool8, lengthDelimited(fundingCreatedCodec), lengthDelimited(fundingSignedCodec)))).map {
      case commitments :: fundingTx :: waitingSince :: deferred :: lastSent :: HNil =>
        val commitments1 = ChannelTypes0.setFundingStatus(commitments, SingleFundedUnconfirmedFundingTx(fundingTx))
        DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments1, waitingSince, deferred, lastSent)
    }.decodeOnly

    val DATA_WAIT_FOR_CHANNEL_READY_21_Codec: Codec[DATA_WAIT_FOR_CHANNEL_READY] = (
      ("commitments" | commitmentsCodec) ::
        ("shortChannelId" | realshortchannelid) ::
        ("lastSent" | lengthDelimited(channelReadyCodec))).map {
      case commitments :: shortChannelId :: _ :: HNil =>
        DATA_WAIT_FOR_CHANNEL_READY(commitments, aliases = ShortIdAliases(localAlias = Alias(shortChannelId.toLong), remoteAlias_opt = None))
    }.decodeOnly

    val DATA_NORMAL_22_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | commitmentsCodec) ::
        ("shortChannelId" | realshortchannelid) ::
        ("buried" | bool8) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closeStatus" | provide(Option.empty[CloseStatus]))).map {
      case commitments :: shortChannelId :: _ :: channelAnnouncement :: channelUpdate :: localShutdown :: remoteShutdown :: closeStatus :: HNil =>
        val aliases = ShortIdAliases(localAlias = Alias(shortChannelId.toLong), remoteAlias_opt = None)
        DATA_NORMAL(commitments, aliases, channelAnnouncement, channelUpdate, SpliceStatus.NoSplice, localShutdown, remoteShutdown, closeStatus)
    }.decodeOnly

    val DATA_SHUTDOWN_23_Codec: Codec[DATA_SHUTDOWN] = (
      ("commitments" | commitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closeStatus" | provide[CloseStatus](CloseStatus.Initiator(None)))).as[DATA_SHUTDOWN]

    val DATA_NEGOTIATING_24_Codec: Codec[DATA_NEGOTIATING] = (
      ("commitments" | commitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingTxProposed" | listOfN(uint16, listOfN(uint16, lengthDelimited(closingTxProposedCodec)))) ::
        ("bestUnpublishedClosingTx_opt" | optional(bool8, closingTxCodec))).as[DATA_NEGOTIATING]

    val DATA_CLOSING_25_Codec: Codec[DATA_CLOSING] = (
      ("commitments" | commitmentsCodec) ::
        ("fundingTx_opt" | optional(bool8, txCodec)) ::
        ("waitingSince" | int64.as[BlockHeight]) ::
        ("mutualCloseProposed" | listOfN(uint16, closingTxCodec)) ::
        ("mutualClosePublished" | listOfN(uint16, closingTxCodec)) ::
        ("localCommitPublished" | optional(bool8, localCommitPublishedCodec)) ::
        ("remoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("nextRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("futureRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).map {
      case commitments :: fundingTx_opt :: waitingSince :: mutualCloseProposed :: mutualClosePublished :: localCommitPublished :: remoteCommitPublished :: nextRemoteCommitPublished :: futureRemoteCommitPublished :: revokedCommitPublished :: HNil =>
        val commitments1 = ChannelTypes0.setFundingStatus(commitments, SingleFundedUnconfirmedFundingTx(fundingTx_opt))
        DATA_CLOSING(commitments1, waitingSince, commitments1.localChannelParams.upfrontShutdownScript_opt.get, mutualCloseProposed, mutualClosePublished, localCommitPublished, remoteCommitPublished, nextRemoteCommitPublished, futureRemoteCommitPublished, revokedCommitPublished)
    }.decodeOnly

    val DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_26_Codec: Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = (
      ("commitments" | commitmentsCodec) ::
        ("remoteChannelReestablish" | channelReestablishCodec)).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]
  }

  // Order matters!
  val channelDataCodec: Codec[PersistentChannelData] = discriminated[PersistentChannelData].by(uint16)
    .typecase(0x20, Codecs.DATA_WAIT_FOR_FUNDING_CONFIRMED_20_Codec)
    .typecase(0x21, Codecs.DATA_WAIT_FOR_CHANNEL_READY_21_Codec)
    .typecase(0x22, Codecs.DATA_NORMAL_22_Codec)
    .typecase(0x23, Codecs.DATA_SHUTDOWN_23_Codec)
    .typecase(0x24, Codecs.DATA_NEGOTIATING_24_Codec)
    .typecase(0x25, Codecs.DATA_CLOSING_25_Codec)
    .typecase(0x26, Codecs.DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_26_Codec)
}
