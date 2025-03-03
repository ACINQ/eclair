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

package fr.acinq.eclair

import akka.actor.ActorSystem
import fr.acinq.bitcoin.scalacompat.{Block, BlockId, MilliBtcDouble, Transaction, TxId, computeBIP84Address}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.AddressType
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinJsonRPCAuthMethod.UserPassword
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinCoreClient}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by PM on 26/04/2016.
 */
class TestBitcoinCoreClient()(implicit system: ActorSystem) extends BitcoinCoreClient(new BasicBitcoinJsonRPCClient(Block.RegtestGenesisBlock.hash, UserPassword("", ""), "", 0)(sb = null)) {

  import scala.concurrent.ExecutionContext.Implicits.global

  system.scheduler.scheduleWithFixedDelay(100 milliseconds, 100 milliseconds)(() => system.eventStream.publish(NewBlock(BlockId(randomBytes32()))))

  override def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[TxId] = {
    system.eventStream.publish(NewTransaction(tx))
    Future.successful(tx.txid)
  }

  override def getTxConfirmations(txId: TxId)(implicit ec: ExecutionContext): Future[Option[Int]] = Future.successful(Some(10))

  override def getTransaction(txId: TxId)(implicit ec: ExecutionContext): Future[Transaction] = Future.failed(new RuntimeException("not implemented"))

  override def getTransactionShortId(txId: TxId)(implicit ec: ExecutionContext): Future[(BlockHeight, Int)] = Future.successful((BlockHeight(400000), 42))

  override def listUnspent()(implicit ec: ExecutionContext): Future[Seq[BitcoinCoreClient.Utxo]] = Future.successful(Seq(BitcoinCoreClient.Utxo(randomTxId(), outputIndex = 0, 10_000 millibtc, ancestorCount_opt = None, confirmations = 10, safe = true, label_opt = None)))

  override def getReceiveAddress(addressType: Option[AddressType] = None)(implicit ec: ExecutionContext): Future[String] = addressType match {
    case Some(AddressType.P2tr) => Future.successful(randomKey().xOnlyPublicKey().pub.p2trAddress(fr.acinq.bitcoin.Block.RegtestGenesisBlock.hash))
    case _ => Future.successful(computeBIP84Address(randomKey().publicKey, Block.RegtestGenesisBlock.hash))
  }
}