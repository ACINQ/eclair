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

package fr.acinq.eclair.plugins.peerswap.wire.protocol

import fr.acinq.eclair.plugins.peerswap.json.PeerSwapJsonSerializers
import org.json4s.jackson.Serialization
import scodec.bits.ByteVector

sealed trait HasSwapId extends Serializable { def swapId: String }

sealed abstract class JSonBlobMessage() extends HasSwapId {
  def json: String = {
    Serialization.write(this)(PeerSwapJsonSerializers.formats)
  }
}

sealed trait HasSwapVersion { def protocolVersion: Long}

sealed trait SwapRequest extends JSonBlobMessage with HasSwapId with HasSwapVersion {
  def asset: String
  def network: String
  def scid: String
  def amount: Long
  def pubkey: String
}

case class SwapInRequest(protocolVersion: Long, swapId: String, asset: String, network: String, scid: String, amount: Long, pubkey: String) extends SwapRequest

case class SwapOutRequest(protocolVersion: Long, swapId: String, asset: String, network: String, scid: String, amount: Long, pubkey: String) extends SwapRequest

sealed trait SwapAgreement extends JSonBlobMessage with HasSwapId with HasSwapVersion {
  def pubkey: String
  def premium: Long
  def payreq: String
}

case class SwapInAgreement(protocolVersion: Long, swapId: String, pubkey: String, premium: Long) extends SwapAgreement {
  override def payreq: String = ""
}

case class SwapOutAgreement(protocolVersion: Long, swapId: String, pubkey: String, payreq: String) extends SwapAgreement {
  override def premium: Long = 0
}

case class OpeningTxBroadcasted(swapId: String, payreq: String, txId: String, scriptOut: Long, blindingKey: String) extends JSonBlobMessage with HasSwapId

case class CancelSwap(swapId: String, message: String) extends JSonBlobMessage with HasSwapId

case class CoopClose(swapId: String, message: String, privkey: String) extends JSonBlobMessage with HasSwapId

case class UnknownPeerSwapMessage(tag: Int, data: ByteVector) extends HasSwapId {
  def swapId: String = "unknown"
}
