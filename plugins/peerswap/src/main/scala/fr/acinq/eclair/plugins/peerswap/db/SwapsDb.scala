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

import fr.acinq.eclair.payment.Bolt11Invoice
import fr.acinq.eclair.plugins.peerswap.SwapEvents.SwapEvent
import fr.acinq.eclair.plugins.peerswap.SwapRole.Maker
import fr.acinq.eclair.plugins.peerswap.wire.protocol._
import fr.acinq.eclair.plugins.peerswap.{SwapData, SwapRole}
import org.json4s.jackson.JsonMethods.{compact, parse, render}
import org.json4s.jackson.Serialization

import java.sql.{PreparedStatement, ResultSet}

trait SwapsDb {

  def add(swapData: SwapData): Unit

  def addResult(swapEvent: SwapEvent): Unit

  def remove(swapId: String): Unit

  def restore(): Seq[SwapData]

  def list(): Seq[SwapData]

}

object SwapsDb {
  import fr.acinq.eclair.json.JsonSerializers.formats

  def setSwapData(statement: PreparedStatement, swapData: SwapData): Unit = {
    statement.setString(1, swapData.request.swapId)
    statement.setString(2, Serialization.write(swapData.request))
    statement.setString(3, Serialization.write(swapData.agreement))
    statement.setString(4, swapData.invoice.toString)
    statement.setString(5, Serialization.write(swapData.openingTxBroadcasted))
    statement.setInt(6, swapData.swapRole.id)
    statement.setBoolean(7, swapData.isInitiator)
    statement.setString(8, "")
  }

  def getSwapData(rs: ResultSet): SwapData = {
    val isInitiator = rs.getBoolean("is_initiator")
    val isMaker = SwapRole(rs.getInt("swap_role")) == Maker
    val request_json = rs.getString("request")
    val agreement_json = rs.getString("agreement")
    val openingTxBroadcasted_json = rs.getString("opening_tx_broadcasted")
    val (request, agreement) = (isInitiator, isMaker) match {
      case (true, true) => (Serialization.read[SwapInRequest](compact(render(parse(request_json).camelizeKeys))),
        Serialization.read[SwapInAgreement](compact(render(parse(agreement_json).camelizeKeys))))
      case (false, false) => (Serialization.read[SwapInRequest](compact(render(parse(request_json).camelizeKeys))),
        Serialization.read[SwapInAgreement](compact(render(parse(agreement_json).camelizeKeys))))
      case (true, false) => (Serialization.read[SwapOutRequest](compact(render(parse(request_json).camelizeKeys))),
        Serialization.read[SwapOutAgreement](compact(render(parse(agreement_json).camelizeKeys))))
      case (false, true) => (Serialization.read[SwapOutRequest](compact(render(parse(request_json).camelizeKeys))),
        Serialization.read[SwapOutAgreement](compact(render(parse(agreement_json).camelizeKeys))))
    }
    SwapData(
      request,
      agreement,
      Bolt11Invoice.fromString(rs.getString("invoice")).get,
      Serialization.read[OpeningTxBroadcasted](compact(render(parse(openingTxBroadcasted_json).camelizeKeys))),
      SwapRole(rs.getInt("swap_role")),
      rs.getBoolean("is_initiator"))
  }
}