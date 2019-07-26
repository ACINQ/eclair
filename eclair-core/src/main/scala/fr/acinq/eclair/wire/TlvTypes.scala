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

package fr.acinq.eclair.wire

import fr.acinq.eclair.UInt64
import scodec.bits.ByteVector

/**
  * Created by t-bast on 20/06/2019.
  */

// @formatter:off
trait Tlv
sealed trait OnionTlv extends Tlv
// @formatter:on

/**
  * Generic tlv type we fallback to if we don't understand the incoming tlv.
  *
  * @param tag   tlv tag.
  * @param value tlv value (length is implicit, and encoded as a varint).
  */
case class GenericTlv(tag: UInt64, value: ByteVector) extends Tlv

/**
  * A tlv stream is a collection of tlv records.
  * A tlv stream is constrained to a specific tlv namespace that dictates how to parse the tlv records.
  * That namespace is provided by a trait extending the top-level tlv trait.
  *
  * @param records known tlv records.
  * @param unknown unknown tlv records.
  * @tparam T the stream namespace is a trait extending the top-level tlv trait.
  */
case class TlvStream[T <: Tlv](records: Traversable[T], unknown: Traversable[GenericTlv] = Nil)

object TlvStream {

  def apply[T <: Tlv](records: T*): TlvStream[T] = TlvStream(records, Nil)

}