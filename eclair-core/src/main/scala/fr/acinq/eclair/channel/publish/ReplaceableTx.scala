/*
 * Copyright 2025 ACINQ SAS
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

package fr.acinq.eclair.channel.publish

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, OutPoint, Satoshi, Transaction, TxId, TxIn, TxOut}
import fr.acinq.eclair.channel.FullCommitment
import fr.acinq.eclair.crypto.keymanager.{LocalCommitmentKeys, RemoteCommitmentKeys}
import fr.acinq.eclair.transactions.Transactions._
import scodec.bits.ByteVector

/**
 * Created by t-bast on 02/05/2025.
 */

/**
 * A transaction that should be automatically replaced to match a specific [[fr.acinq.eclair.blockchain.fee.ConfirmationTarget]].
 * This is only used for 2nd-stage transactions spending the local or remote commitment transaction.
 */
sealed trait ReplaceableTx {
  // @formatter:off
  def txInfo: ForceCloseTransaction
  def desc: String = txInfo.desc
  def commitTx: Transaction
  def commitment: FullCommitment
  def commitmentFormat: CommitmentFormat = commitment.params.commitmentFormat
  def dustLimit: Satoshi = commitment.localParams.dustLimit
  def commitFee: Satoshi = commitment.commitInput.txOut.amount - commitTx.txOut.map(_.amount).sum
  def concurrentCommitTxs: Set[TxId] = commitment.commitTxIds.txIds - commitTx.txid
  // @formatter:on
}

/** A replaceable transaction that uses additional wallet inputs to pay fees. */
sealed trait ReplaceableTxWithWalletInputs extends ReplaceableTx {
  def redeemInfo(): RedeemInfo

  /** Add wallet inputs obtained from Bitcoin Core. */
  def addWalletInputs(inputs: Seq[TxIn]): ReplaceableTxWithWalletInputs = {
    // We always keep the channel input in first position.
    val updatedTx = txInfo.tx.copy(txIn = txInfo.tx.txIn.take(1) ++ inputs.filter(_.outPoint != txInfo.input.outPoint))
    updateTx(updatedTx)
  }

  /** Add wallet signatures obtained from Bitcoin Core. */
  def addWalletSigs(inputs: Seq[TxIn]): ReplaceableTxWithWalletInputs = {
    val signedTx = inputs.filter(_.outPoint != txInfo.input.outPoint).foldLeft(txInfo.tx) {
      case (tx, input) =>
        val inputIndex = tx.txIn.indexWhere(_.outPoint == input.outPoint)
        if (inputIndex >= 0) {
          tx.updateWitness(inputIndex, input.witness)
        } else {
          tx
        }
    }
    updateTx(signedTx)
  }

  /** Set the change output. */
  def setChangeOutput(amount: Satoshi, changeScript: ByteVector): ReplaceableTxWithWalletInputs

  /** Remove wallet inputs and change output. */
  def reset(): ReplaceableTxWithWalletInputs

  def sign(extraUtxos: Map[OutPoint, TxOut]): ReplaceableTxWithWalletInputs

  protected def updateTx(tx: Transaction): ReplaceableTxWithWalletInputs
}

/** A transaction spending an anchor output to CPFP the corresponding commitment transaction. */
sealed trait ReplaceableAnchor extends ReplaceableTxWithWalletInputs {
  override def reset(): ReplaceableAnchor = {
    val updatedTx = txInfo.tx.copy(txIn = txInfo.tx.txIn.take(1), txOut = Nil)
    updateTx(updatedTx)
  }

  override def setChangeOutput(amount: Satoshi, changeScript: ByteVector): ReplaceableAnchor = {
    val updatedTx = txInfo.tx.copy(txOut = Seq(TxOut(amount, changeScript)))
    updateTx(updatedTx)
  }

  def updateChangeAmount(amount: Satoshi): ReplaceableAnchor = txInfo.tx.txOut.headOption match {
    case Some(txOut) => setChangeOutput(amount, txOut.publicKeyScript)
    case None => this
  }

  override protected def updateTx(tx: Transaction): ReplaceableAnchor = this match {
    case anchorTx: ReplaceableLocalCommitAnchor => anchorTx.copy(txInfo = anchorTx.txInfo.copy(tx = tx))
    case anchorTx: ReplaceableRemoteCommitAnchor => anchorTx.copy(txInfo = anchorTx.txInfo.copy(tx = tx))
  }
}

case class ReplaceableLocalCommitAnchor(txInfo: ClaimAnchorOutputTx, fundingKey: PrivateKey, commitKeys: LocalCommitmentKeys, commitTx: Transaction, commitment: FullCommitment) extends ReplaceableAnchor {
  override def redeemInfo(): RedeemInfo = ClaimAnchorOutputTx.redeemInfo(fundingKey.publicKey, commitKeys, commitment.params.commitmentFormat)

  override def sign(extraUtxos: Map[OutPoint, TxOut]): ReplaceableLocalCommitAnchor = {
    copy(txInfo = txInfo.sign(fundingKey, commitKeys, commitment.params.commitmentFormat, extraUtxos))
  }
}

case class ReplaceableRemoteCommitAnchor(txInfo: ClaimAnchorOutputTx, fundingKey: PrivateKey, commitKeys: RemoteCommitmentKeys, commitTx: Transaction, commitment: FullCommitment) extends ReplaceableAnchor {
  override def redeemInfo(): RedeemInfo = ClaimAnchorOutputTx.redeemInfo(fundingKey.publicKey, commitKeys, commitment.params.commitmentFormat)

  override def sign(extraUtxos: Map[OutPoint, TxOut]): ReplaceableRemoteCommitAnchor = {
    copy(txInfo = txInfo.sign(fundingKey, commitKeys, commitment.params.commitmentFormat, extraUtxos))
  }
}

/**
 * A transaction spending an HTLC output from the local commitment.
 * We always keep the HTLC input and output at index 0 for simplicity.
 */
sealed trait ReplaceableHtlc extends ReplaceableTxWithWalletInputs {
  override def reset(): ReplaceableHtlc = {
    val updatedTx = txInfo.tx.copy(txIn = txInfo.tx.txIn.take(1), txOut = txInfo.tx.txOut.take(1))
    updateTx(updatedTx)
  }

  def setChangeOutput(amount: Satoshi, changeScript: ByteVector): ReplaceableHtlc = {
    // We always keep the HTLC output in the first position.
    val updatedTx = txInfo.tx.copy(txOut = txInfo.tx.txOut.take(1) ++ Seq(TxOut(amount, changeScript)))
    updateTx(updatedTx)
  }

  def updateChangeAmount(amount: Satoshi): ReplaceableHtlc = {
    if (txInfo.tx.txOut.size > 1) {
      setChangeOutput(amount, txInfo.tx.txOut.last.publicKeyScript)
    } else {
      this
    }
  }

  def removeChangeOutput(): ReplaceableHtlc = {
    val updatedTx = txInfo.tx.copy(txOut = txInfo.tx.txOut.take(1))
    updateTx(updatedTx)
  }

  override protected def updateTx(tx: Transaction): ReplaceableHtlc = this match {
    case htlcTx: ReplaceableHtlcSuccess => htlcTx.copy(txInfo = htlcTx.txInfo.copy(tx = tx))
    case htlcTx: ReplaceableHtlcTimeout => htlcTx.copy(txInfo = htlcTx.txInfo.copy(tx = tx))
  }
}

case class ReplaceableHtlcSuccess(txInfo: HtlcSuccessTx, commitKeys: LocalCommitmentKeys, preimage: ByteVector32, remoteSig: ByteVector64, commitTx: Transaction, commitment: FullCommitment) extends ReplaceableHtlc {
  override def redeemInfo(): RedeemInfo = txInfo.redeemInfo(commitKeys.publicKeys, commitment.params.commitmentFormat)

  override def sign(extraUtxos: Map[OutPoint, TxOut]): ReplaceableHtlcSuccess = {
    val localSig = txInfo.sign(commitKeys, commitment.params.commitmentFormat, extraUtxos)
    copy(txInfo = txInfo.addSigs(commitKeys, localSig, remoteSig, preimage, commitment.params.commitmentFormat))
  }
}

case class ReplaceableHtlcTimeout(txInfo: HtlcTimeoutTx, commitKeys: LocalCommitmentKeys, remoteSig: ByteVector64, commitTx: Transaction, commitment: FullCommitment) extends ReplaceableHtlc {
  override def redeemInfo(): RedeemInfo = txInfo.redeemInfo(commitKeys.publicKeys, commitment.params.commitmentFormat)

  override def sign(extraUtxos: Map[OutPoint, TxOut]): ReplaceableHtlcTimeout = {
    val localSig = txInfo.sign(commitKeys, commitment.params.commitmentFormat, extraUtxos)
    copy(txInfo = txInfo.addSigs(commitKeys, localSig, remoteSig, commitment.params.commitmentFormat))
  }
}

/**
 * A transaction spending an HTLC output from a remote commitment.
 * We're not using a pre-signed transaction (whereas we do for [[ReplaceableHtlc]]), so we don't need to add wallet
 * inputs to set fees: we simply lower the amount of our output.
 */
sealed trait ReplaceableClaimHtlc extends ReplaceableTx {
  def sign(): ReplaceableClaimHtlc

  def updateOutputAmount(amount: Satoshi): ReplaceableClaimHtlc = {
    val updatedTx = txInfo.tx.copy(txOut = Seq(txInfo.tx.txOut.head.copy(amount = amount)))
    updateTx(updatedTx)
  }

  private def updateTx(tx: Transaction): ReplaceableClaimHtlc = this match {
    case claimHtlcTx: ReplaceableClaimHtlcSuccess => claimHtlcTx.copy(txInfo = claimHtlcTx.txInfo.copy(tx = tx))
    case claimHtlcTx: ReplaceableClaimHtlcTimeout => claimHtlcTx.copy(txInfo = claimHtlcTx.txInfo.copy(tx = tx))
  }
}

case class ReplaceableClaimHtlcSuccess(txInfo: ClaimHtlcSuccessTx, commitKeys: RemoteCommitmentKeys, preimage: ByteVector32, commitTx: Transaction, commitment: FullCommitment) extends ReplaceableClaimHtlc {
  override def sign(): ReplaceableClaimHtlcSuccess = {
    copy(txInfo = txInfo.sign(commitKeys, preimage, commitment.params.commitmentFormat))
  }
}

case class ReplaceableClaimHtlcTimeout(txInfo: ClaimHtlcTimeoutTx, commitKeys: RemoteCommitmentKeys, commitTx: Transaction, commitment: FullCommitment) extends ReplaceableClaimHtlc {
  override def sign(): ReplaceableClaimHtlcTimeout = {
    copy(txInfo = txInfo.sign(commitKeys, commitment.params.commitmentFormat))
  }
}
