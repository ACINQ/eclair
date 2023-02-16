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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, Satoshi, Transaction}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import scodec.bits.ByteVector

import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by PM on 06/07/2017.
 */

/** This trait lets users fund lightning channels. */
trait OnChainChannelFunder {

  import OnChainWallet._

  /** Fund the provided transaction by adding inputs (and a change output if necessary). */
  def fundTransaction(tx: Transaction, feeRate: FeeratePerKw, replaceable: Boolean, externalInputsWeight: Map[OutPoint, Long] = Map.empty)(implicit ec: ExecutionContext): Future[FundTransactionResponse]

  /** Sign the wallet inputs of the provided transaction. */
  def signTransaction(tx: Transaction, allowIncomplete: Boolean)(implicit ec: ExecutionContext): Future[SignTransactionResponse]

  /**
   * Publish a transaction on the bitcoin network.
   * This method must be idempotent: if the tx was already published, it must return a success.
   */
  def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[ByteVector32]

  /** Create a fully signed channel funding transaction with the provided pubkeyScript. */
  def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRate: FeeratePerKw)(implicit ec: ExecutionContext): Future[MakeFundingTxResponse]

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
  def getTransaction(txId: ByteVector32)(implicit ec: ExecutionContext): Future[Transaction]

  /** Get the number of confirmations of a given transaction. */
  def getTxConfirmations(txid: ByteVector32)(implicit ec: ExecutionContext): Future[Option[Int]]

  /** Rollback a transaction that we failed to commit: this probably translates to "release locks on utxos". */
  def rollback(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean]

  /**
   * Tests whether the inputs of the provided transaction have been spent by another transaction.
   * Implementations may always return false if they don't want to implement it.
   */
  def doubleSpent(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean]

}

/** This trait lets users generate on-chain addresses and public keys. */
trait OnChainAddressGenerator {

  /**
   * @param label used if implemented with bitcoin core, can be ignored by implementation
   */
  def getReceiveAddress(label: String = "")(implicit ec: ExecutionContext): Future[String]

  /** Generate a p2wpkh wallet address and return the corresponding public key. */
  def getP2wpkhPubkey()(implicit ec: ExecutionContext): Future[PublicKey]

}

trait OnchainPubkeyCache {

  /**
   * @param renew applies after requesting the current pubkey, and is asynchronous
   */
  def getP2wpkhPubkey(renew: Boolean = true): PublicKey
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

  final case class SignTransactionResponse(tx: Transaction, complete: Boolean)

}
