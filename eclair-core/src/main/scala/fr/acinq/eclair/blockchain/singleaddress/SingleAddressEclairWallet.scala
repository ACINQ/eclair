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

package fr.acinq.eclair.blockchain.singleaddress

import fr.acinq.bitcoin.scala.Crypto.PublicKey
import fr.acinq.bitcoin.scala.{ByteVector32, Crypto, Satoshi, Transaction, computeP2WpkhAddress}
import fr.acinq.eclair.blockchain.{EclairWallet, MakeFundingTxResponse, OnChainBalance}
import scodec.bits.ByteVector

import scala.concurrent.Future

/**
 * This is a minimal eclair wallet that doesn't manage funds, it can't be used to fund channels. It manages a single public key and will
 * return the BIP84 (p2wpkh) address for this key.
 * @param chainHash chain hash we're on
 * @param receiveKey public key that will be used in all scripts and addresses for this wallet
 */
class SingleAddressEclairWallet(chainHash: ByteVector32, receiveKey: PublicKey) extends EclairWallet {
  val finalAddress = computeP2WpkhAddress(receiveKey, chainHash)

  override def getBalance: Future[OnChainBalance] = Future.successful(OnChainBalance(Satoshi(0), Satoshi(0)))

  override def getReceiveAddress: Future[String] = Future.successful(finalAddress)

  override def getReceivePubkey(receiveAddress: Option[String]): Future[Crypto.PublicKey] = Future.successful(receiveKey)

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: Long): Future[MakeFundingTxResponse] = Future.failed(???)

  override def commit(tx: Transaction): Future[Boolean] = Future.failed(???)

  override def rollback(tx: Transaction): Future[Boolean] = Future.failed(???)

  override def doubleSpent(tx: Transaction): Future[Boolean] = Future.failed(???)
}