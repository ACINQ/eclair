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

package fr.acinq.eclair

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import fr.acinq.eclair.blockchain.fee.{FeeratesPerKB, FeeratesPerKw}

/**
  * Created by PM on 25/01/2016.
  */
object Globals {

  /**
    * This counter holds the current blockchain height.
    * It is mainly used to calculate htlc expiries.
    * The value is read by all actors, hence it needs to be thread-safe.
    */
  val blockCount = new AtomicLong(0)

  /**
    * This holds the current feerates, in satoshi-per-kilobytes.
    * The value is read by all actors, hence it needs to be thread-safe.
    */
  val feeratesPerKB = new AtomicReference[FeeratesPerKB](null)

  /**
    * This holds the current feerates, in satoshi-per-kw.
    * The value is read by all actors, hence it needs to be thread-safe.
    */
  val feeratesPerKw = new AtomicReference[FeeratesPerKw](null)
}


