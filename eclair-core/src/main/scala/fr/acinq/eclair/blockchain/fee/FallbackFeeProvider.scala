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
  */
class FallbackFeeProvider(providers: Seq[FeeProvider])(implicit ec: ExecutionContext) extends FeeProvider {

  require(providers.size >= 1, "need at least one fee provider")

  def getFeerates(fallbacks: Seq[FeeProvider]): Future[FeeratesPerByte] =
    fallbacks match {
      case last +: Nil => last.getFeerates
      case head +: remaining => head.getFeerates.recoverWith { case _ => getFeerates(remaining) }
    }

  override def getFeerates: Future[FeeratesPerByte] = getFeerates(providers)

}
