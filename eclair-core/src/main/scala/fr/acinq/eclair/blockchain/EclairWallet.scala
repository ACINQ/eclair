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

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Psbt, Satoshi, Transaction}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import scodec.bits.ByteVector

import scala.concurrent.Future

/**
 * Created by PM on 06/07/2017.
 */
trait EclairWallet {

  def getBalance: Future[OnChainBalance]

  /**
   * @param label used if implemented with bitcoin core, can be ignored by implementation
   */
  def getReceiveAddress(label: String = ""): Future[String]

  def getReceivePubkey(receiveAddress: Option[String] = None): Future[PublicKey]
  
  def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: FeeratePerKw): Future[MakeFundingTxResponse]

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
  def commit(tx: Transaction): Future[Boolean]

  /**
   * Cancels this transaction: this probably translates to "release locks on utxos".
   */
  def rollback(tx: Transaction): Future[Boolean]

  /**
   * Tests whether the inputs of the provided transaction have been spent by another transaction.
   *
   * Implementations may always return false if they don't want to implement it
   */
  def doubleSpent(tx: Transaction): Future[Boolean]

}

final case class OnChainBalance(confirmed: Satoshi, unconfirmed: Satoshi)

final case class MakeFundingTxResponse(psbt: Psbt, fundingTxOutputIndex: Int, fee: Satoshi)
