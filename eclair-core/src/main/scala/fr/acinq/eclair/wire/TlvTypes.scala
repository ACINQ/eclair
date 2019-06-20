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

import scodec.bits.ByteVector

/**
  * Created by t-bast on 20/06/2019.
  */

// @formatter:off
trait Tlv
sealed trait OnionTlv extends Tlv
// @formatter:on

/**
  * Generic tlv type we fallback to if we don't understand the incoming type.
  *
  * @param `type` tlv type.
  * @param value  tlv value (length is implicit, and encoded as a varint).
  */
case class GenericTlv(`type`: Long, value: ByteVector) extends Tlv
