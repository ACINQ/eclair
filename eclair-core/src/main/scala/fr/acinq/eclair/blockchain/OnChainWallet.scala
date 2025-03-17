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

package fr.acinq.eclair.blockchain

import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{OutPoint, Satoshi, ScriptElt, Transaction, TxId}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.AddressType
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import scodec.bits.ByteVector

import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by PM on 06/07/2017.
 */

/** This trait lets users fund lightning channels. */
trait OnChainChannelFunder {

  import OnChainWallet._

  /**
   * Fund the provided transaction by adding inputs (and a change output if necessary).
   * Callers must verify that the resulting transaction isn't sending funds to unexpected addresses (malicious bitcoin node).
   */
  def fundTransaction(tx: Transaction, feeRate: FeeratePerKw, replaceable: Boolean = true, changePosition: Option[Int] = None, externalInputsWeight: Map[OutPoint, Long] = Map.empty, minInputConfirmations_opt: Option[Int] = None, feeBudget_opt: Option[Satoshi])(implicit ec: ExecutionContext): Future[FundTransactionResponse]

  /**
   * Sign a PSBT. Result may be partially signed: only inputs known to our bitcoin wallet will be signed. *
   *
   * @param psbt       PSBT to sign
   * @param ourInputs  our wallet inputs. If Eclair is managing Bitcoin Core wallet keys, only these inputs will be signed.
   * @param ourOutputs our wallet outputs. If Eclair is managing Bitcoin Core wallet keys, it will check that it can actually spend them (i.e re-compute private keys for them)
   */
  def signPsbt(psbt: Psbt, ourInputs: Seq[Int], ourOutputs: Seq[Int])(implicit ec: ExecutionContext): Future[ProcessPsbtResponse]

  /**
   * Publish a transaction on the bitcoin network.
   * This method must be idempotent: if the tx was already published, it must return a success.
   */
  def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[TxId]

  /** Create a fully signed channel funding transaction with the provided pubkeyScript. */
  def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRate: FeeratePerKw, feeBudget_opt: Option[Satoshi])(implicit ec: ExecutionContext): Future[MakeFundingTxResponse]

  /**
   * Committing *must* include publishing the transaction on the network.
   *
   * We need to be very careful here, we don't want to consider a commit 'failed' if we are not absolutely sure that the
   * funding tx won't end up on the blockchain: if that happens and we have cancelled the channel, then we would lose our
   * funds!
   *
   * @return true if success
   *         false IF AND ONLY IF *HAS NOT BEEN PUBLISHED* otherwise funds are at risk!!!
   */
  def commit(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean]

  /** Return the transaction if it exists, either in the blockchain or in the mempool. */
  def getTransaction(txId: TxId)(implicit ec: ExecutionContext): Future[Transaction]

  /** Get the number of confirmations of a given transaction. */
  def getTxConfirmations(txId: TxId)(implicit ec: ExecutionContext): Future[Option[Int]]

  /**
   * Return true if this output can potentially be spent.
   *
   * Note that if this function returns false, that doesn't mean the output cannot be spent. The output could be unknown
   * (not in the blockchain nor in the mempool) but could reappear later and be spendable at that point.
   */
  def isTransactionOutputSpendable(txid: TxId, outputIndex: Int, includeMempool: Boolean)(implicit ec: ExecutionContext): Future[Boolean]

  /** Rollback a transaction that we failed to commit: this probably translates to "release locks on utxos". */
  def rollback(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean]

  /**
   * Mark a transaction as abandoned, which will allow for its wallet inputs to be re-spent.
   *
   * If the transaction has been permanently double-spent by a direct conflict, there is no need to call this function,
   * it will automatically be detected and the wallet inputs will be re-spent.
   *
   * This should only be used when the transaction has become invalid because one of its ancestors has been permanently
   * double-spent. Since the wallet doesn't keep track of unconfirmed ancestors status, it cannot know that the
   * transaction has become permanently invalid and will never be publishable again.
   *
   * This function must never be called on a transaction that isn't permanently invalidated, otherwise it would create
   * a risk of accidentally double-spending ourselves:
   *  - the transaction is abandoned
   *  - its inputs are re-spent in another transaction
   *  - but the initial transaction confirms because it had already reached the mempool of other nodes, unexpectedly
   *    double-spending the second transaction
   */
  def abandon(txId: TxId)(implicit ec: ExecutionContext): Future[Boolean]

  /**
   * Tests whether the inputs of the provided transaction have been spent by another transaction.
   * Implementations may always return false if they don't want to implement it.
   */
  def doubleSpent(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean]

}

/** This trait lets users generate on-chain addresses and public keys. */
trait OnChainAddressGenerator {

  /** Generate the public key script for a new wallet address. */
  def getReceivePublicKeyScript(addressType_opt: Option[AddressType] = None)(implicit ec: ExecutionContext): Future[Seq[ScriptElt]]

  /** Generate a p2wpkh wallet address and return the corresponding public key. */
  def getP2wpkhPubkey()(implicit ec: ExecutionContext): Future[PublicKey]

}

/** A caching layer for [[OnChainAddressGenerator]] that provides synchronous access to wallet addresses and keys. */
trait OnChainPubkeyCache {

  /**
   * @param renew applies after requesting the current pubkey, and is asynchronous.
   */
  def getP2wpkhPubkey(renew: Boolean): PublicKey

  /**
   * @param renew applies after requesting the current script, and is asynchronous.
   */
  def getReceivePublicKeyScript(renew: Boolean): Seq[ScriptElt]

}

/** This trait lets users check the wallet's on-chain balance. */
trait OnChainBalanceChecker {

  import OnChainWallet.OnChainBalance

  /** Get our on-chain balance */
  def onChainBalance()(implicit ec: ExecutionContext): Future[OnChainBalance]

}

/**
 * This trait defines the minimal set of feature an on-chain wallet needs to implement to support lightning.
 */
trait OnChainWallet extends OnChainChannelFunder with OnChainAddressGenerator with OnChainBalanceChecker

object OnChainWallet {

  final case class OnChainBalance(confirmed: Satoshi, unconfirmed: Satoshi)

  final case class MakeFundingTxResponse(fundingTx: Transaction, fundingTxOutputIndex: Int, fee: Satoshi)

  final case class FundTransactionResponse(tx: Transaction, fee: Satoshi, changePosition: Option[Int]) {
    val amountIn: Satoshi = fee + tx.txOut.map(_.amount).sum
  }

  final case class ProcessPsbtResponse(psbt: Psbt, complete: Boolean) {

    import fr.acinq.bitcoin.psbt.UpdateFailure
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    /** Transaction with all available witnesses. */
    val partiallySignedTx: Transaction = {
      var tx = psbt.global.tx
      for (i <- 0 until psbt.inputs.size()) {
        Option(psbt.inputs.get(i).getScriptWitness).foreach { witness =>
          tx = tx.updateWitness(i, witness)
        }
      }
      tx
    }

    /** Extract a fully signed transaction if the psbt is finalized. */
    val finalTx_opt: Either[UpdateFailure, Transaction] = {
      val extracted: Either[UpdateFailure, fr.acinq.bitcoin.Transaction] = psbt.extract()
      extracted match {
        case Left(f) => Left(f)
        case Right(tx) => Right(tx)
      }
    }
  }
}
