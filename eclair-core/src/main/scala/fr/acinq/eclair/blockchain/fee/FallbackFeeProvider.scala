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

  def getFeerates(fallbacks: Seq[FeeProvider]): Future[FeeratesPerByte] =
    fallbacks match {
      case last +: Nil => last.getFeerates
      case head +: remaining => head.getFeerates.recoverWith { case _ => getFeerates(remaining) }
    }

  override def getFeerates: Future[FeeratesPerByte] = getFeerates(providers).map(FallbackFeeProvider.enforceMinimumFeerate(_, minFeeratePerByte))

}

object FallbackFeeProvider {

  def enforceMinimumFeerate(feeratesPerByte: FeeratesPerByte, minFeeratePerByte: Long) : FeeratesPerByte = feeratesPerByte.copy(
    block_1 = Math.max(feeratesPerByte.block_1, minFeeratePerByte),
    blocks_2 = Math.max(feeratesPerByte.blocks_2, minFeeratePerByte),
    blocks_6 = Math.max(feeratesPerByte.blocks_6, minFeeratePerByte),
    blocks_12 = Math.max(feeratesPerByte.blocks_12, minFeeratePerByte),
    blocks_36 = Math.max(feeratesPerByte.blocks_36, minFeeratePerByte),
    blocks_72 = Math.max(feeratesPerByte.blocks_72, minFeeratePerByte)
  )

}
