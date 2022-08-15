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

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.channel._
import fr.acinq.eclair.wire.protocol.{UpdateFailHtlc, UpdateFailMalformedHtlc, UpdateFulfillHtlc, UpdateMessage}

/**
 * This database stores CMD_FULFILL_HTLC and CMD_FAIL_HTLC that we have received from downstream
 * (either directly via UpdateFulfillHtlc or by extracting the value from the
 * blockchain).
 *
 * This means that this database is only used in the context of *relaying* payments.
 *
 * We need to be sure that if downstream is able to pull funds from us, we can always
 * do the same from upstream, otherwise we lose money. Hence the need for persistence
 * to handle all corner cases.
 *
 */
trait PendingCommandsDb {

  def addSettlementCommand(channelId: ByteVector32, cmd: HtlcSettlementCommand): Unit

  def removeSettlementCommand(channelId: ByteVector32, htlcId: Long): Unit

  def listSettlementCommands(channelId: ByteVector32): Seq[HtlcSettlementCommand]

  def listSettlementCommands(): Seq[(ByteVector32, HtlcSettlementCommand)]

}

object PendingCommandsDb {
  /**
   * We store [[CMD_FULFILL_HTLC]]/[[CMD_FAIL_HTLC]]/[[CMD_FAIL_MALFORMED_HTLC]]
   * in a database because we don't want to lose preimages, or to forget to fail
   * incoming htlcs, which would lead to unwanted channel closings.
   */
  def safeSend(register: ActorRef, db: PendingCommandsDb, channelId: ByteVector32, cmd: HtlcSettlementCommand): Unit = {
    // htlc settlement commands don't have replyTo
    register ! Register.Forward(null, channelId, cmd)
    // we store the command in a db (note that this happens *after* forwarding the command to the channel, so we don't add latency)
    db.addSettlementCommand(channelId, cmd)
  }

  def ackSettlementCommand(db: PendingCommandsDb, channelId: ByteVector32, cmd: HtlcSettlementCommand): Unit = {
    db.removeSettlementCommand(channelId, cmd.id)
  }

  def ackSettlementCommands(db: PendingCommandsDb, updates: List[UpdateMessage])(implicit log: LoggingAdapter): Unit = updates.collect {
    case u: UpdateFulfillHtlc =>
      log.debug(s"fulfill acked for htlcId=${u.id}")
      db.removeSettlementCommand(u.channelId, u.id)
    case u: UpdateFailHtlc =>
      log.debug(s"fail acked for htlcId=${u.id}")
      db.removeSettlementCommand(u.channelId, u.id)
    case u: UpdateFailMalformedHtlc =>
      log.debug(s"fail-malformed acked for htlcId=${u.id}")
      db.removeSettlementCommand(u.channelId, u.id)
  }

  def getSettlementCommands(db: PendingCommandsDb, channelId: ByteVector32)(implicit log: LoggingAdapter): Seq[HtlcSettlementCommand] = {
    db.listSettlementCommands(channelId)
  }
}