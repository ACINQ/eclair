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

import akka.event.Logging.MDC
import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey

object Logs {

  def mdc(remoteNodeId_opt: Option[PublicKey] = None, channelId_opt: Option[BinaryData] = None): MDC =
    Seq(
      remoteNodeId_opt.map(n => "nodeId" -> s" n:$n"), // nb: we preformat MDC values so that there is no white spaces in logs
      channelId_opt.map(c => "channelId" -> s" c:$c")
    ).flatten.toMap

}

// we use a dedicated class so that the logging can be independently adjusted
case class Diagnostics()
