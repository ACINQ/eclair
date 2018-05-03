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

import scala.concurrent.{ExecutionContext, Future}

/**
  * This provider will try all child providers in sequence, until one of them works
  *
  * @param providers a sequence of providers; they will be tried one after the others until one of them succeeds
  * @param minFeeratePerByte a configurable minimum value for feerates
  */
class FallbackFeeProvider(providers: Seq[FeeProvider], minFeeratePerByte: Long)(implicit ec: ExecutionContext) extends FeeProvider {

  require(providers.size >= 1, "need at least one fee provider")
  require(minFeeratePerByte > 0, "minimum fee rate must be strictly greater than 0")

  def getFeerates(fallbacks: Seq[FeeProvider]): Future[FeeratesPerKB] =
    fallbacks match {
      case last +: Nil => last.getFeerates
      case head +: remaining => head.getFeerates.recoverWith { case _ => getFeerates(remaining) }
    }

  override def getFeerates: Future[FeeratesPerKB] = getFeerates(providers).map(FallbackFeeProvider.enforceMinimumFeerate(_, minFeeratePerByte))

}

object FallbackFeeProvider {

  def enforceMinimumFeerate(feeratesPerKB: FeeratesPerKB, minFeeratePerByte: Long) : FeeratesPerKB = feeratesPerKB.copy(
    block_1 = Math.max(feeratesPerKB.block_1, minFeeratePerByte * 1000),
    blocks_2 = Math.max(feeratesPerKB.blocks_2, minFeeratePerByte * 1000),
    blocks_6 = Math.max(feeratesPerKB.blocks_6, minFeeratePerByte * 1000),
    blocks_12 = Math.max(feeratesPerKB.blocks_12, minFeeratePerByte * 1000),
    blocks_36 = Math.max(feeratesPerKB.blocks_36, minFeeratePerByte * 1000),
    blocks_72 = Math.max(feeratesPerKB.blocks_72, minFeeratePerByte * 1000)
  )

}
