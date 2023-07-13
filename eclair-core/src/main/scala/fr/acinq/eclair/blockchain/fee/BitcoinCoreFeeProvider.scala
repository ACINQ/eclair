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

package fr.acinq.eclair.blockchain.fee

import fr.acinq.bitcoin.scalacompat._
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinJsonRPCClient
import org.json4s.JsonAST._
import org.json4s.{DefaultFormats, Formats}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by PM on 09/07/2017.
 */
case class BitcoinCoreFeeProvider(rpcClient: BitcoinJsonRPCClient, defaultFeerates: FeeratesPerKB)(implicit ec: ExecutionContext) extends FeeProvider {

  implicit val formats: Formats = DefaultFormats.withBigDecimal

  /**
   * We need this to keep commitment tx fees in sync with the state of the network
   *
   * @param nBlocks number of blocks until tx is confirmed
   * @return the current fee estimate in Satoshi/KB
   */
  def estimateSmartFee(nBlocks: Int): Future[FeeratePerKB] =
    rpcClient.invoke("estimatesmartfee", nBlocks).map(BitcoinCoreFeeProvider.parseFeeEstimate)

  def mempoolMinFee(): Future[FeeratePerKB] =
    rpcClient.invoke("getmempoolinfo").map(json => json \ "mempoolminfee" match {
      case JDecimal(feerate) => FeeratePerKB(Btc(feerate).toSatoshi)
      case JInt(feerate) => FeeratePerKB(Btc(feerate.toLong).toSatoshi)
      case other => throw new RuntimeException(s"mempoolminfee failed: $other")
    })

  override def getFeerates: Future[FeeratesPerKB] = for {
    mempoolMinFee <- mempoolMinFee()
    block_1 <- estimateSmartFee(1)
    blocks_2 <- estimateSmartFee(2)
    blocks_12 <- estimateSmartFee(12)
    blocks_1008 <- estimateSmartFee(1008)
  } yield FeeratesPerKB(
    minimum = if (mempoolMinFee.feerate > 0.sat) mempoolMinFee else defaultFeerates.minimum,
    fastest = if (block_1.feerate > 0.sat) block_1 else defaultFeerates.fastest,
    fast = if (blocks_2.feerate > 0.sat) blocks_2 else defaultFeerates.fast,
    medium = if (blocks_12.feerate > 0.sat) blocks_12 else defaultFeerates.medium,
    slow = if (blocks_1008.feerate > 0.sat) blocks_1008 else defaultFeerates.slow)
}

object BitcoinCoreFeeProvider {
  def parseFeeEstimate(json: JValue): FeeratePerKB = {
    json \ "errors" match {
      case JNothing =>
        (json \ "feerate": @unchecked) match {
          case JDecimal(feerate) =>
            // estimatesmartfee returns a fee rate in Btc/KB
            FeeratePerKB(Btc(feerate).toSatoshi)
          case JInt(feerate) if feerate.toLong < 0 =>
            // negative value means failure: should (hopefully) never happen
            FeeratePerKB(feerate.toLong.sat)
          case JInt(feerate) =>
            FeeratePerKB(Btc(feerate.toLong).toSatoshi)
        }
      case JArray(errors) =>
        val error = errors.collect { case JString(error) => error }.mkString(", ")
        throw new RuntimeException(s"estimatesmartfee failed: $error")
      case _ =>
        throw new RuntimeException("estimatesmartfee failed")
    }
  }
}
