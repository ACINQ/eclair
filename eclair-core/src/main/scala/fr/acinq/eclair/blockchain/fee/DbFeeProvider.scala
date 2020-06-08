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

import fr.acinq.eclair.db.FeeratesDb

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * This provider first tries to retrieve the feerates from the a database acting as a cache.
 *
 * If no provider already exists for this provider's name, or if the feerates age exceeds the provided max age,
 * then the provider's actual `getFeeRates` future is called ; subsequent result is saved to the database.
 */
class DbFeeProvider(db: FeeratesDb, provider: FeeProvider, maxAgeMillis: Long = 1.day.toMillis)(implicit ec: ExecutionContext) extends FeeProvider {

  override def getFeerates: Future[FeeratesPerKB] = db.getFeerates(provider.getName) match {
    case Some((feeratesInDb, timestamp)) if System.currentTimeMillis() - timestamp <= maxAgeMillis =>
      Future.successful(feeratesInDb)
    case _ => provider.getFeerates.flatMap { f =>
      db.addOrUpdateFeerates(provider.getName, f)
      Future.successful(f)
    }
  }

  override def getName: String = provider.getName

}
