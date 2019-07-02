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

package fr.acinq.eclair.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.Command

/**
  * This database stores the preimages that we have received from downstream
  * (either directly via UpdateFulfillHtlc or by extracting the value from the
  * blockchain).
  *
  * This means that this database is only used in the context of *relaying* payments.
  *
  * We need to be sure that if downstream is able to pulls funds from us, we can always
  * do the same from upstream, otherwise we lose money. Hence the need for persistence
  * to handle all corner cases.
  *
  */
trait PendingRelayDb {

  def addPendingRelay(channelId: ByteVector32, htlcId: Long, cmd: Command)

  def removePendingRelay(channelId: ByteVector32, htlcId: Long)

  def listPendingRelay(channelId: ByteVector32): Seq[Command]

  def listPendingRelay(): Set[(ByteVector32, Long)]

  def close(): Unit

}
