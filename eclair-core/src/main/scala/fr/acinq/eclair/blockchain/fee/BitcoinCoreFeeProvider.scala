/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.blockchain.fee

import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinJsonRPCClient
import org.json4s.DefaultFormats
import org.json4s.JsonAST._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 09/07/2017.
  */
class BitcoinCoreFeeProvider(rpcClient: BitcoinJsonRPCClient, defaultFeerates: FeeratesPerKB)(implicit ec: ExecutionContext) extends FeeProvider {

  implicit val formats = DefaultFormats.withBigDecimal

  /**
    * We need this to keep commitment tx fees in sync with the state of the network
    *
    * @param nBlocks number of blocks until tx is confirmed
    * @return the current fee estimate in Satoshi/KB
    */
  def estimateSmartFee(nBlocks: Int): Future[Long] =
    rpcClient.invoke("estimatesmartfee", nBlocks).map(BitcoinCoreFeeProvider.parseFeeEstimate)

  override def getFeerates: Future[FeeratesPerKB] = for {
    block_1 <- estimateSmartFee(1)
    blocks_2 <- estimateSmartFee(2)
    blocks_6 <- estimateSmartFee(6)
    blocks_12 <- estimateSmartFee(12)
    blocks_36 <- estimateSmartFee(36)
    blocks_72 <- estimateSmartFee(72)
  } yield FeeratesPerKB(
    block_1 = if (block_1 > 0) block_1 else defaultFeerates.block_1,
    blocks_2 = if (blocks_2 > 0) blocks_2 else defaultFeerates.blocks_2,
    blocks_6 = if (blocks_6 > 0) blocks_6 else defaultFeerates.blocks_6,
    blocks_12 = if (blocks_12 > 0) blocks_12 else defaultFeerates.blocks_12,
    blocks_36 = if (blocks_36 > 0) blocks_36 else defaultFeerates.blocks_36,
    blocks_72 = if (blocks_72 > 0) blocks_72 else defaultFeerates.blocks_72)
}

object BitcoinCoreFeeProvider {
  def parseFeeEstimate(json: JValue): Long = {
    json \ "errors" match {
      case JNothing =>
        json \ "feerate" match {
          case JDecimal(feerate) =>
            // estimatesmartfee returns a fee rate in Btc/KB
            btc2satoshi(Btc(feerate)).amount
          case JInt(feerate) if feerate.toLong < 0 =>
            // negative value means failure
            feerate.toLong
          case JInt(feerate) =>
            // should (hopefully) never happen
            btc2satoshi(Btc(feerate.toLong)).amount
        }
      case JArray(errors) =>
        val error = errors collect { case JString(error) => error } mkString (", ")
        throw new RuntimeException(s"estimatesmartfee failed: $error")
      case _ =>
        throw new RuntimeException("estimatesmartfee failed")
    }
  }
}
