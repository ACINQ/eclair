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

import scala.annotation.tailrec

/**
  * Created by t-bast on 20/06/2019.
  */

// @formatter:off
trait Tlv {
  val `type`: UInt64
}
sealed trait OnionTlv extends Tlv
// @formatter:on

/**
  * Generic tlv type we fallback to if we don't understand the incoming type.
  *
  * @param `type` tlv type.
  * @param value  tlv value (length is implicit, and encoded as a varint).
  */
case class GenericTlv(`type`: UInt64, value: ByteVector) extends Tlv

/**
  * A tlv stream is a collection of tlv records.
  * A tlv stream is part of a given namespace that dictates how to parse the tlv records.
  * That namespace is indicated by a trait extending the top-level tlv trait.
  *
  * @param records tlv records.
  */
case class TlvStream(records: Seq[Tlv]) {

  records.foldLeft(Option.empty[Tlv]) {
    case (None, record) =>
      require(!record.isInstanceOf[GenericTlv] || record.`type`.toBigInt % 2 != 0, "tlv streams must not contain unknown even tlv types")
      Some(record)
    case (Some(previousRecord), record) =>
      require(record.`type` != previousRecord.`type`, "tlv streams must not contain duplicate records")
      require(record.`type` > previousRecord.`type`, "tlv records must be ordered by monotonically-increasing types")
      require(!record.isInstanceOf[GenericTlv] || record.`type`.toBigInt % 2 != 0, "tlv streams must not contain unknown even tlv types")
      Some(record)
  }

}