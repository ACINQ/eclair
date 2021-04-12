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

import fr.acinq.bitcoin.{ByteVector32, OutPoint, Satoshi, Transaction, TxOut}
import fr.acinq.eclair.channel
import fr.acinq.eclair.transactions.Transactions._

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
    val input = childTx.txIn.head.outPoint
    InputInfo(input, parentTx.txOut(input.index.toInt), Nil)
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
        val htlcTx = htlcTxs.find(_.txid == tx.txIn.head.outPoint.txid)
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
        ClaimHtlcDelayedOutputPenaltyTx(InputInfo(tx.txIn.head.outPoint, TxOut(Satoshi(0), Nil), Nil), tx)
      })
      channel.RevokedCommitPublished(commitTx, claimMainOutputTxNew, mainPenaltyTxNew, htlcPenaltyTxsNew, claimHtlcDelayedPenaltyTxsNew, irrevocablySpentNew)
    }
  }

  /**
   * Starting with version2, we store a complete ClosingTx object for mutual close scenarios instead of simply storing
   * the raw transaction. It provides more information for auditing but is not used for business logic, so we can safely
   * put dummy values in the migration.
   */
  def migrateClosingTx(tx: Transaction): ClosingTx = ClosingTx(InputInfo(tx.txIn.head.outPoint, TxOut(Satoshi(0), Nil), Nil), tx, None)

}
