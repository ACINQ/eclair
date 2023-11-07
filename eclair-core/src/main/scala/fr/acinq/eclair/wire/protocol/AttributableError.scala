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

package fr.acinq.eclair.wire.protocol

import fr.acinq.eclair.wire.protocol.CommonCodecs._
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs._

import scala.concurrent.duration.{DurationLong, FiniteDuration}


case class AttributableError(failurePayload: ByteVector, hopPayloads: Seq[ByteVector], hmacs: Seq[Seq[ByteVector]])

object AttributableError {
  case class HopPayload(isPayloadSource: Boolean, holdTime: FiniteDuration)

  def hopPayloadCodec: Codec[HopPayload] = (
    ("payload_source" | bool8) ::
      ("hold_time_ms" | uint32.xmap[FiniteDuration](_.millis, _.toMillis))).as[HopPayload]

  private def hmacsCodec(n: Int): Codec[Seq[Seq[ByteVector]]] =
    if (n == 0) {
      provide(Nil)
    }
    else {
      (listOfN(provide(n), bytes4).xmap[Seq[ByteVector]](_.toSeq, _.toList) ::
        hmacsCodec(n - 1)).as[(Seq[ByteVector], Seq[Seq[ByteVector]])]
        .xmap(pair => pair._1 +: pair._2, seq => (seq.head, seq.tail))
    }

  def attributableErrorCodec(totalLength: Int, hopPayloadLength: Int, maxNumHop: Int): Codec[AttributableError] = {
    val metadataLength = maxNumHop * hopPayloadLength + (maxNumHop * (maxNumHop + 1)) / 2 * 4
    (("failure_payload" | bytes(totalLength - metadataLength)) ::
      ("hop_payloads" | listOfN(provide(maxNumHop), bytes(hopPayloadLength)).xmap[Seq[ByteVector]](_.toSeq, _.toList)) ::
      ("hmacs" | hmacsCodec(maxNumHop))).as[AttributableError].complete}

  def zero(payloadAndPadLength: Int, hopPayloadLength: Int, maxNumHop: Int): AttributableError =
    AttributableError(
      ByteVector.low(payloadAndPadLength),
      Seq.fill(maxNumHop)(ByteVector.low(hopPayloadLength)),
      maxNumHop.to(1, -1).map(Seq.fill(_)(ByteVector.low(4))))
}
