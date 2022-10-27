/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.plugins.peerswap.db

import com.google.common.util.concurrent.ThreadFactoryBuilder
import fr.acinq.eclair.db.DualDatabases.runAsync
import fr.acinq.eclair.plugins.peerswap.SwapData
import fr.acinq.eclair.plugins.peerswap.SwapEvents.SwapEvent

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

case class DualSwapsDb(primary: SwapsDb, secondary: SwapsDb) extends SwapsDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-pending-commands").build()))

  override def add(swapData: SwapData): Unit = {
    runAsync(secondary.add(swapData))
    primary.add(swapData)
  }

  override def addResult(swapEvent: SwapEvent): Unit = {
    runAsync(secondary.addResult(swapEvent))
    primary.addResult(swapEvent)
  }

  override def remove(swapId: String): Unit = {
    runAsync(secondary.remove(swapId))
    primary.remove(swapId)
  }

  override def restore(): Seq[SwapData] = {
    runAsync(secondary.restore())
    primary.restore()
  }

  override def list(): Seq[SwapData] = {
    runAsync(secondary.list())
    primary.list()
  }
}