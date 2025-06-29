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

package fr.acinq.eclair.wire.internal.channel.version0

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto, OutPoint, Transaction, TxId, TxOut}
import fr.acinq.eclair.channel.LocalFundingStatus.SingleFundedUnconfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.crypto.keymanager.{LocalCommitmentKeys, RemoteCommitmentKeys}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.internal.channel.version0.ChannelTypes0.{HtlcTxAndSigs, PublishableTxs}
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.{channelAnnouncementCodec, channelUpdateCodec, combinedFeaturesCodec}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Alias, BlockHeight, CltvExpiry, CltvExpiryDelta, MilliSatoshiLong, TimestampSecond}
import scodec.Codec
import scodec.bits.{BitVector, ByteVector, HexStringSyntax}
import scodec.codecs._
import shapeless.{::, HNil}

import java.util.UUID

/**
 * Those codecs are here solely for backward compatibility reasons.
 *
 * Created by PM on 02/06/2017.
 */
private[channel] object ChannelCodecs0 {

  private[version0] object Codecs {

    val keyPathCodec: Codec[KeyPath] = ("path" | listOfN(uint16, uint32)).xmap[KeyPath](l => KeyPath(l), keyPath => keyPath.path.toList).as[KeyPath].decodeOnly

    val channelVersionCodec: Codec[ChannelTypes0.ChannelVersion] = discriminatorWithDefault[ChannelTypes0.ChannelVersion](
      discriminator = discriminated[ChannelTypes0.ChannelVersion].by(byte)
        .typecase(0x01, bits(ChannelTypes0.ChannelVersion.LENGTH_BITS).as[ChannelTypes0.ChannelVersion])
      // NB: 0x02 and 0x03 are *reserved* for backward compatibility reasons
      ,
      fallback = provide(ChannelTypes0.ChannelVersion.ZEROES) // README: DO NOT CHANGE THIS !! old channels don't have a channel version
      // field and don't support additional features which is why all bits are set to 0.
    )

    def localParamsCodec(channelVersion: ChannelTypes0.ChannelVersion): Codec[LocalChannelParams] = (
      ("nodeId" | publicKey) ::
        ("channelPath" | keyPathCodec) ::
        ("dustLimit" | satoshi) ::
        ("maxHtlcValueInFlightMsat" | uint64) ::
        ("channelReserve" | conditional(included = true, satoshi)) ::
        ("htlcMinimum" | millisatoshi) ::
        ("toSelfDelay" | cltvExpiryDelta) ::
        ("maxAcceptedHtlcs" | uint16) ::
        ("isInitiator" | bool) ::
        ("upfrontShutdownScript_opt" | varsizebinarydata.map(Option(_)).decodeOnly) ::
        ("walletStaticPaymentBasepoint" | optional(provide(channelVersion.paysDirectlyToWallet), publicKey)) ::
        ("features" | combinedFeaturesCodec)).map {
      case nodeId :: channelPath :: dustLimit :: maxHtlcValueInFlightMsat :: channelReserve :: htlcMinimum :: toSelfDelay :: maxAcceptedHtlcs :: isInitiator :: upfrontShutdownScript_opt :: walletStaticPaymentBasepoint :: features :: HNil =>
        LocalChannelParams(nodeId, channelPath, dustLimit, maxHtlcValueInFlightMsat, channelReserve, htlcMinimum, toSelfDelay, maxAcceptedHtlcs, isInitiator, isInitiator, upfrontShutdownScript_opt, walletStaticPaymentBasepoint, features)
    }.decodeOnly

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
        ("shutdownScript" | provide[Option[ByteVector]](None))).as[ChannelTypes0.RemoteParams].decodeOnly

    val updateAddHtlcCodec: Codec[UpdateAddHtlc] = (
      ("channelId" | bytes32) ::
        ("id" | uint64overflow) ::
        ("amountMsat" | millisatoshi) ::
        ("paymentHash" | bytes32) ::
        ("expiry" | cltvExpiry) ::
        ("onionRoutingPacket" | PaymentOnionCodecs.paymentOnionPacketCodec) ::
        ("tlvStream" | provide(TlvStream.empty[UpdateAddHtlcTlv]))).as[UpdateAddHtlc]

    val htlcCodec: Codec[DirectedHtlc] = discriminated[DirectedHtlc].by(bool)
      .typecase(true, updateAddHtlcCodec.as[IncomingHtlc])
      .typecase(false, updateAddHtlcCodec.as[OutgoingHtlc])

    def setCodec[T](codec: Codec[T]): Codec[Set[T]] = Codec[Set[T]](
      (elems: Set[T]) => listOfN(uint16, codec).encode(elems.toList),
      (wire: BitVector) => listOfN(uint16, codec).decode(wire).map(_.map(_.toSet))
    )

    val commitmentSpecCodec: Codec[CommitmentSpec] = (
      ("htlcs" | setCodec(htlcCodec)) ::
        ("feeratePerKw" | feeratePerKw) ::
        ("toLocal" | millisatoshi) ::
        ("toRemote" | millisatoshi)).as[CommitmentSpec].decodeOnly

    val outPointCodec: Codec[OutPoint] = variableSizeBytes(uint16, bytes.xmap(d => OutPoint.read(d.toArray), d => OutPoint.write(d)))

    val txOutCodec: Codec[TxOut] = variableSizeBytes(uint16, bytes.xmap(d => TxOut.read(d.toArray), d => TxOut.write(d)))

    val txCodec: Codec[Transaction] = variableSizeBytes(uint16, bytes.xmap(d => Transaction.read(d.toArray), d => Transaction.write(d)))

    val closingTxCodec: Codec[ClosingTx] = txCodec.decodeOnly.xmap(
      tx => ChannelTypes0.migrateClosingTx(tx),
      closingTx => closingTx.tx
    )

    val inputInfoCodec: Codec[InputInfo] = (
      ("outPoint" | outPointCodec) ::
        ("txOut" | txOutCodec) ::
        ("redeemScript" | varsizebinarydata)).map {
      case outpoint :: txOut :: _ :: HNil => InputInfo(outpoint, txOut, ByteVector.empty)
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

    // We can safely set htlcId = 0 for htlc txs. This information is only used to find upstream htlcs to fail when a
    // downstream htlc times out, and `Helpers.Closing.timedOutHtlcs` explicitly handles the case where htlcId is missing.
    // We can also safely set confirmBefore = 0: we will simply use a high feerate to make these transactions confirm
    // as quickly as possible. It's very unlikely that nodes will run into this, so it's a good trade-off between code
    // complexity and real world impact.
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

    // this is a backward compatible codec (we used to store the sig as DER encoded), now we store it as 64-bytes
    val sig64OrDERCodec: Codec[ByteVector64] = Codec[ByteVector64](
      (value: ByteVector64) => bytes(64).encode(value),
      (wire: BitVector) => bytes.decode(wire).map(_.map {
        case bin64 if bin64.size == 64 => ByteVector64(bin64)
        case der => Crypto.der2compact(der)
      })
    )

    val htlcTxAndSigsCodec: Codec[HtlcTxAndSigs] = (
      ("txinfo" | txWithInputInfoCodec.downcast[UnsignedHtlcTx]) ::
        ("localSig" | variableSizeBytes(uint16, sig64OrDERCodec)) :: // we store as variable length for historical purposes (we used to store as DER encoded)
        ("remoteSig" | variableSizeBytes(uint16, sig64OrDERCodec))).as[HtlcTxAndSigs].decodeOnly

    val publishableTxsCodec: Codec[PublishableTxs] = (
      ("commitTx" | (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[CommitTx]) ::
        ("htlcTxsAndSigs" | listOfN(uint16, htlcTxAndSigsCodec))).as[PublishableTxs].decodeOnly

    val localCommitCodec: Codec[ChannelTypes0.LocalCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("publishableTxs" | publishableTxsCodec)).as[ChannelTypes0.LocalCommit].decodeOnly

    val remoteCommitCodec: Codec[RemoteCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("txid" | txId) ::
        ("remotePerCommitmentPoint" | publicKey)).as[RemoteCommit].decodeOnly

    val updateFulfillHtlcCodec: Codec[UpdateFulfillHtlc] = (
      ("channelId" | bytes32) ::
        ("id" | uint64overflow) ::
        ("paymentPreimage" | bytes32) ::
        ("tlvStream" | provide(TlvStream.empty[UpdateFulfillHtlcTlv]))).as[UpdateFulfillHtlc]

    val updateFailHtlcCodec: Codec[UpdateFailHtlc] = (
      ("channelId" | bytes32) ::
        ("id" | uint64overflow) ::
        ("reason" | varsizebinarydata) ::
        ("tlvStream" | provide(TlvStream.empty[UpdateFailHtlcTlv]))).as[UpdateFailHtlc]

    val updateFailMalformedHtlcCodec: Codec[UpdateFailMalformedHtlc] = (
      ("channelId" | bytes32) ::
        ("id" | uint64overflow) ::
        ("onionHash" | bytes32) ::
        ("failureCode" | uint16) ::
        ("tlvStream" | provide(TlvStream.empty[UpdateFailMalformedHtlcTlv]))).as[UpdateFailMalformedHtlc]

    val updateFeeCodec: Codec[UpdateFee] = (
      ("channelId" | bytes32) ::
        ("feeratePerKw" | feeratePerKw) ::
        ("tlvStream" | provide(TlvStream.empty[UpdateFeeTlv]))).as[UpdateFee]

    val updateMessageCodec: Codec[UpdateMessage] = discriminated[UpdateMessage].by(uint16)
      .typecase(128, updateAddHtlcCodec)
      .typecase(130, updateFulfillHtlcCodec)
      .typecase(131, updateFailHtlcCodec)
      .typecase(134, updateFeeCodec)
      .typecase(135, updateFailMalformedHtlcCodec)

    val localChangesCodec: Codec[LocalChanges] = (
      ("proposed" | listOfN(uint16, updateMessageCodec)) ::
        ("signed" | listOfN(uint16, updateMessageCodec)) ::
        ("acked" | listOfN(uint16, updateMessageCodec))).as[LocalChanges].decodeOnly

    val remoteChangesCodec: Codec[RemoteChanges] = (
      ("proposed" | listOfN(uint16, updateMessageCodec)) ::
        ("acked" | listOfN(uint16, updateMessageCodec)) ::
        ("signed" | listOfN(uint16, updateMessageCodec))).as[RemoteChanges].decodeOnly

    val commitSigCodec: Codec[CommitSig] = (
      ("channelId" | bytes32) ::
        ("signature" | bytes64) ::
        ("htlcSignatures" | listofsignatures) ::
        ("tlvStream" | provide(TlvStream.empty[CommitSigTlv]))).as[CommitSig]

    val waitingForRevocationCodec: Codec[ChannelTypes0.WaitingForRevocation] = (
      ("nextRemoteCommit" | remoteCommitCodec) ::
        ("sent" | commitSigCodec) ::
        ("sentAfterLocalCommitIndex" | uint64overflow) ::
        ("reSignAsap" | ignore(1))).as[ChannelTypes0.WaitingForRevocation].decodeOnly

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

    // this is for backward compatibility to handle legacy payments that didn't have identifiers
    val UNKNOWN_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

    val coldUpstreamCodec: Codec[Upstream.Cold] = discriminated[Upstream.Cold].by(uint16)
      .typecase(0x03, upstreamLocalCodec) // backward compatible
      .typecase(0x01, provide(Upstream.Local(UNKNOWN_UUID)))
      .typecase(0x02, upstreamChannelCodec)
      .typecase(0x04, upstreamTrampolineCodec)

    val originCodec: Codec[Origin] = coldUpstreamCodec.xmap[Origin](
      upstream => Origin.Cold(upstream),
      {
        case Origin.Hot(_, upstream) => Upstream.Cold(upstream)
        case Origin.Cold(upstream) => upstream
      }
    )

    val originsListCodec: Codec[List[(Long, Origin)]] = listOfN(uint16, int64 ~ originCodec)

    val originsMapCodec: Codec[Map[Long, Origin]] = Codec[Map[Long, Origin]](
      (map: Map[Long, Origin]) => originsListCodec.encode(map.toList),
      (wire: BitVector) => originsListCodec.decode(wire).map(_.map(_.toMap))
    )

    val spentListCodec: Codec[List[(OutPoint, TxId)]] = listOfN(uint16, outPointCodec ~ txId)

    val spentMapCodec: Codec[Map[OutPoint, TxId]] = Codec[Map[OutPoint, TxId]](
      (map: Map[OutPoint, TxId]) => spentListCodec.encode(map.toList),
      (wire: BitVector) => spentListCodec.decode(wire).map(_.map(_.toMap))
    )

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
          ("remoteNextCommitInfo" | either(bool, waitingForRevocationCodec, publicKey)) ::
          ("commitInput" | inputInfoCodec) ::
          ("remotePerCommitmentSecrets" | ShaChain.shaChainCodec) ::
          ("channelId" | bytes32)
      }).as[ChannelTypes0.Commitments].decodeOnly.map[Commitments](_.migrate()).decodeOnly

    val closingSignedCodec: Codec[ClosingSigned] = (
      ("channelId" | bytes32) ::
        ("feeSatoshis" | satoshi) ::
        ("signature" | bytes64) ::
        ("tlvStream" | provide(TlvStream.empty[ClosingSignedTlv]))).as[ClosingSigned]

    val closingTxProposedCodec: Codec[ClosingTxProposed] = (
      ("unsignedTx" | closingTxCodec) ::
        ("localClosingSigned" | closingSignedCodec)).as[ClosingTxProposed].decodeOnly

    val localCommitPublishedCodec: Codec[LocalCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainDelayedOutputTx" | optional(bool, txCodec)) ::
        ("htlcSuccessTxs" | listOfN(uint16, txCodec)) ::
        ("htlcTimeoutTxs" | listOfN(uint16, txCodec)) ::
        ("claimHtlcDelayedTx" | listOfN(uint16, txCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes0.LocalCommitPublished].decodeOnly.map[LocalCommitPublished](_.migrate()).decodeOnly

    val remoteCommitPublishedCodec: Codec[RemoteCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainOutputTx" | optional(bool, txCodec)) ::
        ("claimHtlcSuccessTxs" | listOfN(uint16, txCodec)) ::
        ("claimHtlcTimeoutTxs" | listOfN(uint16, txCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes0.RemoteCommitPublished].decodeOnly.map[RemoteCommitPublished](_.migrate()).decodeOnly

    val revokedCommitPublishedCodec: Codec[RevokedCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainOutputTx" | optional(bool, txCodec)) ::
        ("mainPenaltyTx" | optional(bool, txCodec)) ::
        ("htlcPenaltyTxs" | listOfN(uint16, txCodec)) ::
        ("claimHtlcDelayedPenaltyTxs" | listOfN(uint16, txCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes0.RevokedCommitPublished].decodeOnly.map[RevokedCommitPublished](_.migrate()).decodeOnly

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

    val fundingCreatedCodec: Codec[FundingCreated] = (
      ("temporaryChannelId" | bytes32) ::
        ("fundingTxHash" | txIdAsHash) ::
        ("fundingOutputIndex" | uint16) ::
        ("signature" | bytes64) ::
        ("tlvStream" | provide(TlvStream.empty[FundingCreatedTlv]))).as[FundingCreated]

    val fundingSignedCodec: Codec[FundingSigned] = (
      ("channelId" | bytes32) ::
        ("signature" | bytes64) ::
        ("tlvStream" | provide(TlvStream.empty[FundingSignedTlv]))).as[FundingSigned]

    val channelReadyCodec: Codec[ChannelReady] = (
      ("channelId" | bytes32) ::
        ("nextPerCommitmentPoint" | publicKey) ::
        ("tlvStream" | provide(TlvStream.empty[ChannelReadyTlv]))).as[ChannelReady]

    // this is a decode-only codec compatible with versions 997acee and below, with placeholders for new fields
    val DATA_WAIT_FOR_FUNDING_CONFIRMED_01_Codec: Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
      ("commitments" | commitmentsCodec) ::
        ("fundingTx_opt" | provide[Option[Transaction]](None)) ::
        ("waitingSince" | provide(BlockHeight(TimestampSecond.now().toLong))) ::
        ("deferred" | optional(bool, channelReadyCodec)) ::
        ("lastSent" | either(bool, fundingCreatedCodec, fundingSignedCodec))).map {
      case commitments :: fundingTx :: waitingSince :: deferred :: lastSent :: HNil =>
        val commitments1 = ChannelTypes0.setFundingStatus(commitments, SingleFundedUnconfirmedFundingTx(fundingTx))
        DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments1, waitingSince, deferred, lastSent)
    }.decodeOnly

    val DATA_WAIT_FOR_FUNDING_CONFIRMED_08_Codec: Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
      ("commitments" | commitmentsCodec) ::
        ("fundingTx_opt" | optional(bool, txCodec)) ::
        ("waitingSince" | int64.as[BlockHeight]) ::
        ("deferred" | optional(bool, channelReadyCodec)) ::
        ("lastSent" | either(bool, fundingCreatedCodec, fundingSignedCodec))).map {
      case commitments :: fundingTx :: waitingSince :: deferred :: lastSent :: HNil =>
        val commitments1 = ChannelTypes0.setFundingStatus(commitments, SingleFundedUnconfirmedFundingTx(fundingTx))
        DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments1, waitingSince, deferred, lastSent)
    }.decodeOnly

    val DATA_WAIT_FOR_CHANNEL_READY_02_Codec: Codec[DATA_WAIT_FOR_CHANNEL_READY] = (
      ("commitments" | commitmentsCodec) ::
        ("shortChannelId" | realshortchannelid) ::
        ("lastSent" | channelReadyCodec)).map {
      case commitments :: shortChannelId :: _ :: HNil =>
        DATA_WAIT_FOR_CHANNEL_READY(commitments, aliases = ShortIdAliases(localAlias = Alias(shortChannelId.toLong), remoteAlias_opt = None))
    }.decodeOnly

    val shutdownCodec: Codec[Shutdown] = (
      ("channelId" | bytes32) ::
        ("scriptPubKey" | varsizebinarydata) ::
        ("tlvStream" | provide(TlvStream.empty[ShutdownTlv]))).as[Shutdown]

    // this is a decode-only codec compatible with versions 9afb26e and below
    val DATA_NORMAL_03_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | commitmentsCodec) ::
        ("shortChannelId" | realshortchannelid) ::
        ("buried" | bool) ::
        ("channelAnnouncement" | optional(bool, variableSizeBytes(noUnknownFieldsChannelAnnouncementSizeCodec, channelAnnouncementCodec))) ::
        ("channelUpdate" | variableSizeBytes(noUnknownFieldsChannelUpdateSizeCodec, channelUpdateCodec)) ::
        ("localShutdown" | optional(bool, shutdownCodec)) ::
        ("remoteShutdown" | optional(bool, shutdownCodec)) ::
        ("closeStatus" | provide(Option.empty[CloseStatus]))).map {
      case commitments :: shortChannelId :: _ :: channelAnnouncement :: channelUpdate :: localShutdown :: remoteShutdown :: closingFeerates :: HNil =>
        val aliases = ShortIdAliases(localAlias = Alias(shortChannelId.toLong), remoteAlias_opt = None)
        DATA_NORMAL(commitments, aliases, channelAnnouncement, channelUpdate, localShutdown, remoteShutdown, closingFeerates, SpliceStatus.NoSplice)
    }.decodeOnly

    val DATA_NORMAL_10_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | commitmentsCodec) ::
        ("shortChannelId" | realshortchannelid) ::
        ("buried" | bool) ::
        ("channelAnnouncement" | optional(bool, variableSizeBytes(uint16, channelAnnouncementCodec))) ::
        ("channelUpdate" | variableSizeBytes(uint16, channelUpdateCodec)) ::
        ("localShutdown" | optional(bool, shutdownCodec)) ::
        ("remoteShutdown" | optional(bool, shutdownCodec)) ::
        ("closeStatus" | provide(Option.empty[CloseStatus]))).map {
      case commitments :: shortChannelId :: _ :: channelAnnouncement :: channelUpdate :: localShutdown :: remoteShutdown :: closingFeerates :: HNil =>
        val aliases = ShortIdAliases(localAlias = Alias(shortChannelId.toLong), remoteAlias_opt = None)
        DATA_NORMAL(commitments, aliases, channelAnnouncement, channelUpdate, localShutdown, remoteShutdown, closingFeerates, SpliceStatus.NoSplice)
    }.decodeOnly

    val DATA_SHUTDOWN_04_Codec: Codec[DATA_SHUTDOWN] = (
      ("commitments" | commitmentsCodec) ::
        ("localShutdown" | shutdownCodec) ::
        ("remoteShutdown" | shutdownCodec) ::
        ("closeStatus" | provide[CloseStatus](CloseStatus.Initiator(None)))).as[DATA_SHUTDOWN].decodeOnly

    val DATA_NEGOTIATING_05_Codec: Codec[DATA_NEGOTIATING] = (
      ("commitments" | commitmentsCodec) ::
        ("localShutdown" | shutdownCodec) ::
        ("remoteShutdown" | shutdownCodec) ::
        ("closingTxProposed" | listOfN(uint16, listOfN(uint16, closingTxProposedCodec))) ::
        ("bestUnpublishedClosingTx_opt" | optional(bool, closingTxCodec))).as[DATA_NEGOTIATING].decodeOnly

    // this is a decode-only codec compatible with versions 818199e and below, with placeholders for new fields
    val DATA_CLOSING_06_Codec: Codec[DATA_CLOSING] = (
      ("commitments" | commitmentsCodec) ::
        ("fundingTx_opt" | provide[Option[Transaction]](None)) ::
        ("waitingSince" | provide(BlockHeight(TimestampSecond.now().toLong))) ::
        ("mutualCloseProposed" | listOfN(uint16, closingTxCodec)) ::
        ("mutualClosePublished" | listOfN(uint16, closingTxCodec)) ::
        ("localCommitPublished" | optional(bool, localCommitPublishedCodec)) ::
        ("remoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
        ("nextRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
        ("futureRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
        ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).map {
      case commitments :: fundingTx_opt :: waitingSince :: mutualCloseProposed :: mutualClosePublished :: localCommitPublished :: remoteCommitPublished :: nextRemoteCommitPublished :: futureRemoteCommitPublished :: revokedCommitPublished :: HNil =>
        val commitments1 = ChannelTypes0.setFundingStatus(commitments, SingleFundedUnconfirmedFundingTx(fundingTx_opt))
        DATA_CLOSING(commitments1, waitingSince, commitments1.localChannelParams.upfrontShutdownScript_opt.get, mutualCloseProposed, mutualClosePublished, localCommitPublished, remoteCommitPublished, nextRemoteCommitPublished, futureRemoteCommitPublished, revokedCommitPublished)
    }.decodeOnly

    val DATA_CLOSING_09_Codec: Codec[DATA_CLOSING] = (
      ("commitments" | commitmentsCodec) ::
        ("fundingTx_opt" | optional(bool, txCodec)) ::
        ("waitingSince" | int64.as[BlockHeight]) ::
        ("mutualCloseProposed" | listOfN(uint16, closingTxCodec)) ::
        ("mutualClosePublished" | listOfN(uint16, closingTxCodec)) ::
        ("localCommitPublished" | optional(bool, localCommitPublishedCodec)) ::
        ("remoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
        ("nextRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
        ("futureRemoteCommitPublished" | optional(bool, remoteCommitPublishedCodec)) ::
        ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).map {
      case commitments :: fundingTx_opt :: waitingSince :: mutualCloseProposed :: mutualClosePublished :: localCommitPublished :: remoteCommitPublished :: nextRemoteCommitPublished :: futureRemoteCommitPublished :: revokedCommitPublished :: HNil =>
        val commitments1 = ChannelTypes0.setFundingStatus(commitments, SingleFundedUnconfirmedFundingTx(fundingTx_opt))
        DATA_CLOSING(commitments1, waitingSince, commitments1.localChannelParams.upfrontShutdownScript_opt.get, mutualCloseProposed, mutualClosePublished, localCommitPublished, remoteCommitPublished, nextRemoteCommitPublished, futureRemoteCommitPublished, revokedCommitPublished)
    }.decodeOnly

    val channelReestablishCodec: Codec[ChannelReestablish] = (
      ("channelId" | bytes32) ::
        ("nextLocalCommitmentNumber" | uint64overflow) ::
        ("nextRemoteRevocationNumber" | uint64overflow) ::
        ("yourLastPerCommitmentSecret" | privateKey) ::
        ("myCurrentPerCommitmentPoint" | publicKey) ::
        ("tlvStream" | provide(TlvStream.empty[ChannelReestablishTlv]))).as[ChannelReestablish]

    val DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_07_Codec: Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = (
      ("commitments" | commitmentsCodec) ::
        ("remoteChannelReestablish" | channelReestablishCodec)).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT].decodeOnly
  }

  // Order matters!
  val channelDataCodec: Codec[PersistentChannelData] = discriminated[PersistentChannelData].by(uint16)
    .typecase(0x10, Codecs.DATA_NORMAL_10_Codec)
    .typecase(0x09, Codecs.DATA_CLOSING_09_Codec)
    .typecase(0x08, Codecs.DATA_WAIT_FOR_FUNDING_CONFIRMED_08_Codec)
    .typecase(0x01, Codecs.DATA_WAIT_FOR_FUNDING_CONFIRMED_01_Codec)
    .typecase(0x02, Codecs.DATA_WAIT_FOR_CHANNEL_READY_02_Codec)
    .typecase(0x03, Codecs.DATA_NORMAL_03_Codec)
    .typecase(0x04, Codecs.DATA_SHUTDOWN_04_Codec)
    .typecase(0x05, Codecs.DATA_NEGOTIATING_05_Codec)
    .typecase(0x06, Codecs.DATA_CLOSING_06_Codec)
    .typecase(0x07, Codecs.DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_07_Codec)

}
