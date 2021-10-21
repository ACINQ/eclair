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

package fr.acinq.eclair.wire.internal.channel.version0

import com.softwaremill.quicklens._
import fr.acinq.bitcoin
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, OP_CHECKMULTISIG, OP_PUSHDATA, OutPoint, Satoshi, Script, ScriptWitness, Transaction, TxOut}
import fr.acinq.eclair.KotlinUtils.OrderedSatoshi
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.{Feature, FeatureSupport, Features, channel}
import scodec.bits.BitVector

import java.util
import scala.jdk.CollectionConverters.SeqHasAsJava

private[channel] object ChannelTypes0 {

  // The format of the XxxCommitPublished types was changed in version2 to work with anchor outputs channels.
  // Before that, all closing txs were generated once (when we detected the force-close) and never updated afterwards
  // (with the exception of 3rd-stage penalty transactions for revoked commitments when one of their htlc txs wins the
  // race against our htlc-penalty tx, but if that happens a `WatchSpent` will be triggered and we will claim it correctly).
  // When migrating from these previous types, we can safely set dummy values in the following fields:
  //  - we only use the `tx` field of `TransactionWithInputInfo` -> no need to completely fill the `InputInfo`
  //  - `irrevocablySpent` now contains the whole transaction (previously only the txid): we can easily set these when
  //    one of *our* transactions confirmed, but not when a *remote* transaction confirms. This can only happen for HTLC
  //    outputs and in these cases we simply remove the entry in `irrevocablySpent`: the channel will set a `WatchSpent`
  //    which will immediately be triggered and that will let us store the information in `irrevocablySpent`.
  //  - the `htlcId` in htlc txs is used to detect timed out htlcs and relay them upstream, but it can be safely set to
  //    0 because the `timedOutHtlcs` in `Helpers.scala` explicitly handle the case where this information is unavailable.

  private def getPartialInputInfo(parentTx: Transaction, childTx: Transaction): InputInfo = {
    // When using the default commitment format, spending txs have a single input. These txs are fully signed and never
    // modified: we don't use the InputInfo in closing business logic, so we don't need to fill everything (this part
    // assumes that we only have standard channels, no anchor output channels - which was the case before version2).
    val input = childTx.txIn.get(0).outPoint
    InputInfo(input, parentTx.txOut.get(input.index.toInt), Nil)
  }

  case class LocalCommitPublished(commitTx: Transaction, claimMainDelayedOutputTx: Option[Transaction], htlcSuccessTxs: List[Transaction], htlcTimeoutTxs: List[Transaction], claimHtlcDelayedTxs: List[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32]) {
    def migrate(): channel.LocalCommitPublished = {
      val htlcTxs = htlcSuccessTxs ++ htlcTimeoutTxs
      val knownTxs: Map[ByteVector32, Transaction] = (commitTx :: claimMainDelayedOutputTx.toList ::: htlcTxs ::: claimHtlcDelayedTxs).map(tx => tx.txid -> tx).toMap
      // NB: irrevocablySpent may contain transactions that belong to our peer: we will drop them in this migration but
      // the channel will put a watch at start-up which will make us fetch the spending transaction.
      val irrevocablySpentNew = irrevocablySpent.collect { case (outpoint, txid) if knownTxs.contains(txid) => (outpoint, knownTxs(txid)) }
      val claimMainDelayedOutputTxNew = claimMainDelayedOutputTx.map(tx => ClaimLocalDelayedOutputTx(getPartialInputInfo(commitTx, tx), tx))
      val htlcSuccessTxsNew = htlcSuccessTxs.map(tx => HtlcSuccessTx(getPartialInputInfo(commitTx, tx), tx, ByteVector32.Zeroes, 0))
      val htlcTimeoutTxsNew = htlcTimeoutTxs.map(tx => HtlcTimeoutTx(getPartialInputInfo(commitTx, tx), tx, 0))
      val htlcTxsNew = (htlcSuccessTxsNew ++ htlcTimeoutTxsNew).map(tx => tx.input.outPoint -> Some(tx)).toMap
      val claimHtlcDelayedTxsNew = claimHtlcDelayedTxs.map(tx => {
        val htlcTx = htlcTxs.find(_.txid == tx.txIn.get(0).outPoint.txid)
        require(htlcTx.nonEmpty, s"3rd-stage htlc tx doesn't spend one of our htlc txs: claim-htlc-tx=$tx, htlc-txs=${htlcTxs.mkString(",")}")
        HtlcDelayedTx(getPartialInputInfo(htlcTx.get, tx), tx)
      })
      channel.LocalCommitPublished(commitTx, claimMainDelayedOutputTxNew, htlcTxsNew, claimHtlcDelayedTxsNew, Nil, irrevocablySpentNew)
    }
  }

  case class RemoteCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], claimHtlcSuccessTxs: List[Transaction], claimHtlcTimeoutTxs: List[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32]) {
    def migrate(): channel.RemoteCommitPublished = {
      val claimHtlcTxs = claimHtlcSuccessTxs ::: claimHtlcTimeoutTxs
      val knownTxs: Map[ByteVector32, Transaction] = (commitTx :: claimMainOutputTx.toList ::: claimHtlcTxs).map(tx => tx.txid -> tx).toMap
      // NB: irrevocablySpent may contain transactions that belong to our peer: we will drop them in this migration but
      // the channel will put a watch at start-up which will make us fetch the spending transaction.
      val irrevocablySpentNew = irrevocablySpent.collect { case (outpoint, txid) if knownTxs.contains(txid) => (outpoint, knownTxs(txid)) }
      val claimMainOutputTxNew = claimMainOutputTx.map(tx => ClaimP2WPKHOutputTx(getPartialInputInfo(commitTx, tx), tx))
      val claimHtlcSuccessTxsNew = claimHtlcSuccessTxs.map(tx => ClaimHtlcSuccessTx(getPartialInputInfo(commitTx, tx), tx, 0))
      val claimHtlcTimeoutTxsNew = claimHtlcTimeoutTxs.map(tx => ClaimHtlcTimeoutTx(getPartialInputInfo(commitTx, tx), tx, 0))
      val claimHtlcTxsNew = (claimHtlcSuccessTxsNew ++ claimHtlcTimeoutTxsNew).map(tx => tx.input.outPoint -> Some(tx)).toMap
      channel.RemoteCommitPublished(commitTx, claimMainOutputTxNew, claimHtlcTxsNew, Nil, irrevocablySpentNew)
    }
  }

  case class RevokedCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], mainPenaltyTx: Option[Transaction], htlcPenaltyTxs: List[Transaction], claimHtlcDelayedPenaltyTxs: List[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32]) {
    def migrate(): channel.RevokedCommitPublished = {
      val knownTxs: Map[ByteVector32, Transaction] = (commitTx :: claimMainOutputTx.toList ::: mainPenaltyTx.toList ::: htlcPenaltyTxs ::: claimHtlcDelayedPenaltyTxs).map(tx => tx.txid -> tx).toMap
      // NB: irrevocablySpent may contain transactions that belong to our peer: we will drop them in this migration but
      // the channel will put a watch at start-up which will make us fetch the spending transaction.
      val irrevocablySpentNew = irrevocablySpent.collect { case (outpoint, txid) if knownTxs.contains(txid) => (outpoint, knownTxs(txid)) }
      val claimMainOutputTxNew = claimMainOutputTx.map(tx => ClaimP2WPKHOutputTx(getPartialInputInfo(commitTx, tx), tx))
      val mainPenaltyTxNew = mainPenaltyTx.map(tx => MainPenaltyTx(getPartialInputInfo(commitTx, tx), tx))
      val htlcPenaltyTxsNew = htlcPenaltyTxs.map(tx => HtlcPenaltyTx(getPartialInputInfo(commitTx, tx), tx))
      val claimHtlcDelayedPenaltyTxsNew = claimHtlcDelayedPenaltyTxs.map(tx => {
        // We don't have all the `InputInfo` data, but it's ok: we only use the tx that is fully signed.
        ClaimHtlcDelayedOutputPenaltyTx(InputInfo(tx.txIn.get(0).outPoint, new TxOut(new Satoshi(0), Array.emptyByteArray), Nil), tx)
      })
      channel.RevokedCommitPublished(commitTx, claimMainOutputTxNew, mainPenaltyTxNew, htlcPenaltyTxsNew, claimHtlcDelayedPenaltyTxsNew, irrevocablySpentNew)
    }
  }

  /**
   * Starting with version2, we store a complete ClosingTx object for mutual close scenarios instead of simply storing
   * the raw transaction. It provides more information for auditing but is not used for business logic, so we can safely
   * put dummy values in the migration.
   */
  def migrateClosingTx(tx: Transaction): ClosingTx = ClosingTx(InputInfo(tx.txIn.get(0).outPoint, new TxOut(new Satoshi(0), Array.emptyByteArray), Nil), tx, None)

  case class HtlcTxAndSigs(txinfo: HtlcTx, localSig: ByteVector64, remoteSig: ByteVector64)

  case class PublishableTxs(commitTx: CommitTx, htlcTxsAndSigs: List[HtlcTxAndSigs])

  // Before version3, we stored fully signed local transactions (commit tx and htlc txs). It meant that someone gaining
  // access to the database could publish revoked commit txs, so we changed that to only store unsigned txs and remote
  // signatures.
  case class LocalCommit(index: Long, spec: CommitmentSpec, publishableTxs: PublishableTxs) {
    def migrate(remoteFundingPubKey: PublicKey): channel.LocalCommit = {
      val remoteSig = extractRemoteSig(publishableTxs.commitTx, remoteFundingPubKey)

      def emptyWitnesses(n: Int): util.List[ScriptWitness] = List.fill(n)(new ScriptWitness()).asJava
      def removeWitnesses(tx: Transaction) : Transaction = tx.updateWitnesses(emptyWitnesses(tx.txIn.size()))

      val unsignedCommitTx = publishableTxs.commitTx.copy(tx = removeWitnesses(publishableTxs.commitTx.tx))
      val commitTxAndRemoteSig = CommitTxAndRemoteSig(unsignedCommitTx, remoteSig)
      val htlcTxsAndRemoteSigs = publishableTxs.htlcTxsAndSigs map {
        case HtlcTxAndSigs(htlcTx: HtlcSuccessTx, _, remoteSig) =>
          val unsignedHtlcTx = htlcTx.copy(tx = removeWitnesses(htlcTx.tx))
          HtlcTxAndRemoteSig(unsignedHtlcTx, remoteSig)
        case HtlcTxAndSigs(htlcTx: HtlcTimeoutTx, _, remoteSig) =>
          val unsignedHtlcTx = htlcTx.copy(tx = removeWitnesses(htlcTx.tx))
          HtlcTxAndRemoteSig(unsignedHtlcTx, remoteSig)
      }
      channel.LocalCommit(index, spec, commitTxAndRemoteSig, htlcTxsAndRemoteSigs)
    }

    private def extractRemoteSig(commitTx: CommitTx, remoteFundingPubKey: PublicKey): ByteVector64 = {
      require(commitTx.tx.txIn.size == 1, s"commit tx must have exactly one input, found ${commitTx.tx.txIn.size}")
      val witness = commitTx.tx.txIn.get(0).witness
      require(witness.stack.size() == 4)
      val sig1 = witness.stack.get(1).toByteArray
      val sig2 = witness.stack.get(2).toByteArray
      val redeemScript = Script.parse(witness.stack.get(3))
      require(redeemScript.size() == 5 && redeemScript.get(1).isPush(33) && redeemScript.get(2).isPush(33) && redeemScript.get(4) == OP_CHECKMULTISIG.INSTANCE)
      val pub1 = redeemScript.get(1).asInstanceOf[OP_PUSHDATA].data
      val pub2 = redeemScript.get(2).asInstanceOf[OP_PUSHDATA].data
      require(pub1 == remoteFundingPubKey.value || pub2 == remoteFundingPubKey.value, "unrecognized funding pubkey")
      if (pub1 == remoteFundingPubKey.value) {
        Crypto.der2compact(sig1)
      } else {
        Crypto.der2compact(sig2)
      }
    }
  }

  // Before version3, we had a ChannelVersion field describing what channel features were activated. It was mixing
  // official features (static_remotekey, anchor_outputs) and internal features (channel key derivation scheme).
  // We separated this into two separate fields in version3:
  //  - a channel type field containing the channel Bolt 9 features
  //  - an internal channel configuration field
  case class ChannelVersion(bits: BitVector) {
    // @formatter:off
    def isSet(bit: Int): Boolean = bits.reverse.get(bit)
    def |(other: ChannelVersion): ChannelVersion = ChannelVersion(bits | other.bits)

    def hasPubkeyKeyPath: Boolean = isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT)
    def hasStaticRemotekey: Boolean = isSet(ChannelVersion.USE_STATIC_REMOTEKEY_BIT)
    def hasAnchorOutputs: Boolean = isSet(ChannelVersion.USE_ANCHOR_OUTPUTS_BIT)
    def paysDirectlyToWallet: Boolean = hasStaticRemotekey && !hasAnchorOutputs
    // @formatter:on
  }

  object ChannelVersion {

    import scodec.bits._

    val LENGTH_BITS: Int = 4 * 8

    private val USE_PUBKEY_KEYPATH_BIT = 0 // bit numbers start at 0
    private val USE_STATIC_REMOTEKEY_BIT = 1
    private val USE_ANCHOR_OUTPUTS_BIT = 2

    def fromBit(bit: Int): ChannelVersion = ChannelVersion(BitVector.low(LENGTH_BITS).set(bit).reverse)

    val ZEROES = ChannelVersion(bin"00000000000000000000000000000000")
    val STANDARD = ZEROES | fromBit(USE_PUBKEY_KEYPATH_BIT)
    val STATIC_REMOTEKEY = STANDARD | fromBit(USE_STATIC_REMOTEKEY_BIT) // PUBKEY_KEYPATH + STATIC_REMOTEKEY
    val ANCHOR_OUTPUTS = STATIC_REMOTEKEY | fromBit(USE_ANCHOR_OUTPUTS_BIT) // PUBKEY_KEYPATH + STATIC_REMOTEKEY + ANCHOR_OUTPUTS
  }

  case class Commitments(channelVersion: ChannelVersion,
                         localParams: LocalParams, remoteParams: RemoteParams,
                         channelFlags: Byte,
                         localCommit: LocalCommit, remoteCommit: RemoteCommit,
                         localChanges: LocalChanges, remoteChanges: RemoteChanges,
                         localNextHtlcId: Long, remoteNextHtlcId: Long,
                         originChannels: Map[Long, Origin],
                         remoteNextCommitInfo: Either[WaitingForRevocation, PublicKey],
                         commitInput: InputInfo,
                         remotePerCommitmentSecrets: ShaChain, channelId: ByteVector32) {
    def migrate(): channel.Commitments = {
      val channelConfig = if (channelVersion.hasPubkeyKeyPath) {
        ChannelConfig(ChannelConfig.FundingPubKeyBasedChannelKeyPath)
      } else {
        ChannelConfig()
      }
      val isWumboChannel = commitInput.txOut.amount > new Satoshi(16777215)
      val baseChannelFeatures: Set[Feature] = if (isWumboChannel) Set(Features.Wumbo) else Set.empty
      val commitmentFeatures: Set[Feature] = if (channelVersion.hasAnchorOutputs) {
        Set(Features.StaticRemoteKey, Features.AnchorOutputs)
      } else if (channelVersion.hasStaticRemotekey) {
        Set(Features.StaticRemoteKey)
      } else {
        Set.empty
      }
      val channelFeatures = ChannelFeatures(baseChannelFeatures ++ commitmentFeatures)
      channel.Commitments(
        channelId,
        channelConfig, channelFeatures,
        localParams, remoteParams,
        channelFlags,
        localCommit.migrate(remoteParams.fundingPubKey), remoteCommit,
        localChanges, remoteChanges,
        localNextHtlcId, remoteNextHtlcId,
        originChannels,
        remoteNextCommitInfo,
        commitInput,
        remotePerCommitmentSecrets)
    }
  }

}
