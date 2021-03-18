package fr.acinq.eclair.wire.internal.channel

import fr.acinq.bitcoin.{ByteVector32, OutPoint, Satoshi, Transaction, TxOut}
import fr.acinq.eclair.channel.{LocalCommitPublished, RemoteCommitPublished, RevokedCommitPublished}
import fr.acinq.eclair.transactions.Transactions._

/**
 * Those codecs are here solely for backward compatibility reasons.
 *
 * Created by PM on 02/06/2017.
 */

private[channel] object MigrationUtils {

  // The format of the XxxCommitPublished types was changed in eclair v0.5.2 to work with anchor outputs channels.
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
    // modified: we don't use the InputInfo in closing business logic, so we don't need to fill everything.
    val input = childTx.txIn.head.outPoint
    InputInfo(input, parentTx.txOut(input.index.toInt), Nil)
  }

  case class LegacyLocalCommitPublished(commitTx: Transaction, claimMainDelayedOutputTx: Option[Transaction], htlcSuccessTxs: List[Transaction], htlcTimeoutTxs: List[Transaction], claimHtlcDelayedTxs: List[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32]) {
    def migrate(): LocalCommitPublished = {
      val htlcTxs = htlcSuccessTxs ++ htlcTimeoutTxs
      val knownTxs: Map[ByteVector32, Transaction] = (commitTx :: claimMainDelayedOutputTx.toList ::: htlcTxs ::: claimHtlcDelayedTxs).map(tx => tx.txid -> tx).toMap
      val irrevocablySpentNew = irrevocablySpent.collect { case (outpoint, txid) if knownTxs.contains(txid) => (outpoint, knownTxs(txid)) }
      val claimMainDelayedOutputTxNew = claimMainDelayedOutputTx.map(tx => ClaimLocalDelayedOutputTx(getPartialInputInfo(commitTx, tx), tx))
      val htlcSuccessTxsNew = htlcSuccessTxs.map(tx => HtlcSuccessTx(getPartialInputInfo(commitTx, tx), tx, ByteVector32.Zeroes, 0))
      val htlcTimeoutTxsNew = htlcTimeoutTxs.map(tx => HtlcTimeoutTx(getPartialInputInfo(commitTx, tx), tx, 0))
      val htlcTxsNew = (htlcSuccessTxsNew ++ htlcTimeoutTxsNew).map(tx => tx.input.outPoint -> Some(tx)).toMap
      val claimHtlcDelayedTxsNew = claimHtlcDelayedTxs.flatMap(tx => htlcTxs.find(_.txid == tx.txIn.head.outPoint.txid).map(htlcTx => ClaimLocalDelayedOutputTx(getPartialInputInfo(htlcTx, tx), tx)))
      LocalCommitPublished(commitTx, claimMainDelayedOutputTxNew, htlcTxsNew, claimHtlcDelayedTxsNew, Nil, irrevocablySpentNew)
    }
  }

  case class LegacyRemoteCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], claimHtlcSuccessTxs: List[Transaction], claimHtlcTimeoutTxs: List[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32]) {
    def migrate(): RemoteCommitPublished = {
      val claimHtlcTxs = claimHtlcSuccessTxs ::: claimHtlcTimeoutTxs
      val knownTxs: Map[ByteVector32, Transaction] = (commitTx :: claimMainOutputTx.toList ::: claimHtlcTxs).map(tx => tx.txid -> tx).toMap
      val irrevocablySpentNew = irrevocablySpent.collect { case (outpoint, txid) if knownTxs.contains(txid) => (outpoint, knownTxs(txid)) }
      val claimMainOutputTxNew = claimMainOutputTx.map(tx => ClaimP2WPKHOutputTx(getPartialInputInfo(commitTx, tx), tx))
      val claimHtlcSuccessTxsNew = claimHtlcSuccessTxs.map(tx => ClaimHtlcSuccessTx(getPartialInputInfo(commitTx, tx), tx, 0))
      val claimHtlcTimeoutTxsNew = claimHtlcTimeoutTxs.map(tx => ClaimHtlcTimeoutTx(getPartialInputInfo(commitTx, tx), tx, 0))
      val claimHtlcTxsNew = (claimHtlcSuccessTxsNew ++ claimHtlcTimeoutTxsNew).map(tx => tx.input.outPoint -> Some(tx)).toMap
      RemoteCommitPublished(commitTx, claimMainOutputTxNew, claimHtlcTxsNew, Nil, irrevocablySpentNew)
    }
  }

  case class LegacyRevokedCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], mainPenaltyTx: Option[Transaction], htlcPenaltyTxs: List[Transaction], claimHtlcDelayedPenaltyTxs: List[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32]) {
    def migrate(): RevokedCommitPublished = {
      val knownTxs: Map[ByteVector32, Transaction] = (commitTx :: claimMainOutputTx.toList ::: mainPenaltyTx.toList ::: htlcPenaltyTxs ::: claimHtlcDelayedPenaltyTxs).map(tx => tx.txid -> tx).toMap
      val irrevocablySpentNew = irrevocablySpent.collect { case (outpoint, txid) if knownTxs.contains(txid) => (outpoint, knownTxs(txid)) }
      val claimMainOutputTxNew = claimMainOutputTx.map(tx => ClaimP2WPKHOutputTx(getPartialInputInfo(commitTx, tx), tx))
      val mainPenaltyTxNew = mainPenaltyTx.map(tx => MainPenaltyTx(getPartialInputInfo(commitTx, tx), tx))
      val htlcPenaltyTxsNew = htlcPenaltyTxs.map(tx => HtlcPenaltyTx(getPartialInputInfo(commitTx, tx), tx))
      val claimHtlcDelayedPenaltyTxsNew = claimHtlcDelayedPenaltyTxs.map(tx => {
        // We don't have all the `InputInfo` data, but it's ok: we only use the tx that is fully signed.
        ClaimHtlcDelayedOutputPenaltyTx(InputInfo(tx.txIn.head.outPoint, TxOut(Satoshi(0), Nil), Nil), tx)
      })
      RevokedCommitPublished(commitTx, claimMainOutputTxNew, mainPenaltyTxNew, htlcPenaltyTxsNew, claimHtlcDelayedPenaltyTxsNew, irrevocablySpentNew)
    }
  }

  /**
   * Starting with eclair v0.5.2, we store a complete ClosingTx object for mutual close scenarios instead of simply
   * storing the raw transaction. It provides more information for auditing but is not used for business logic, so we can
   * safely put dummy values in the migration.
   */
  def migrateClosingTx(tx: Transaction): ClosingTx = ClosingTx(InputInfo(tx.txIn.head.outPoint, TxOut(Satoshi(0), Nil), Nil), tx, None)

}
