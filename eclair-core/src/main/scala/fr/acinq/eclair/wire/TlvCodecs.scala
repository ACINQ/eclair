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

import fr.acinq.eclair.wire.CommonCodecs._
import scodec.codecs._
import scodec.Codec

/**
  * Created by t-bast on 20/06/2019.
  */

object TlvCodecs {

  val genericTlv: Codec[GenericTlv] = (("type" | varInt) :: variableSizeBytesLong(varInt, bytes)).as[GenericTlv]

  def tlvFallback(codec: Codec[Tlv]): Codec[Tlv] = discriminatorFallback(genericTlv, codec).xmap(_ match {
    case Left(l) => l
    case Right(r) => r
  }, _ match {
    case g: GenericTlv => Left(g)
    case o => Right(o)
  })

}
