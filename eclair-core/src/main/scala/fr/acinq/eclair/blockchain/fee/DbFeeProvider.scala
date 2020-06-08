/*
 * Copyright 2020 ACINQ SAS
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

import fr.acinq.eclair.db.FeeratesDb

import scala.concurrent.{ExecutionContext, Future}

/**
 * This wrapper retrieves the feerates from the a database for a given provider the first time it's used, then fallbacks
 * to the wrapped provider's actual `getFeeRates` future.
 */
class DbFeeProvider(db: FeeratesDb, provider: FeeProvider)(implicit ec: ExecutionContext) extends FeeProvider {

  /** This boolean represents the state of this provider */
  private var isFirstCall = true

  /** This method should use the database once, and then fallback to the wrapped provider. */
  override def getFeerates: Future[FeeratesPerKB] = if (isFirstCall) {
    db.getFeerates(provider.getName) match {
      case Some(feeratesInDb) =>
        isFirstCall = false
        Future.successful(feeratesInDb)
      case _ => provider.getFeerates.flatMap { f =>
        isFirstCall = false
        db.addOrUpdateFeerates(provider.getName, f)
        Future.successful(f)
      }
    }
  } else {
    provider.getFeerates
  }

  override def getName: String = provider.getName

}
