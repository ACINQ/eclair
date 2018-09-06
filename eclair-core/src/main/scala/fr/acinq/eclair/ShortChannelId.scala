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

/**
  * A short channel id uniquely identifies a channel by the coordinates of its funding tx output in the blockchain.
  *
  * See BOLT 7: https://github.com/lightningnetwork/lightning-rfc/blob/master/07-routing-gossip.md#requirements
  *
  */
case class ShortChannelId(blockHeight: Int, txIndex: Int, outputIndex: Int) extends Ordered[ShortChannelId] {
  require(blockHeight >= 0 && txIndex >= 0 && outputIndex >= 0)

  def toLong: Long = ShortChannelId.toShortId(blockHeight, txIndex, outputIndex)

  override def toString: String = s"$blockHeight:$txIndex:$outputIndex"

  // we use an unsigned long comparison here
  override def compare(that: ShortChannelId): Int = (this.toLong + Long.MinValue).compareTo(that.toLong + Long.MinValue)
}

object ShortChannelId {

  def apply(id: Long): ShortChannelId = new ShortChannelId(((id >> 40) & 0xFFFFFF).toInt, ((id >> 16) & 0xFFFFFF).toInt, (id & 0xFFFF).toInt)

  def apply(s: String): ShortChannelId = {
    val Array(a, b, c) = s.split(":")
    new ShortChannelId(a.toInt, b.toInt, c.toInt)
  }

  def toShortId(blockHeight: Int, txIndex: Int, outputIndex: Int): Long = ((blockHeight & 0xFFFFFFL) << 40) | ((txIndex & 0xFFFFFFL) << 16) | (outputIndex & 0xFFFFL)
}
