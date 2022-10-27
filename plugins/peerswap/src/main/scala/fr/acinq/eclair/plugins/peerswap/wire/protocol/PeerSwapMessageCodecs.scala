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

import fr.acinq.eclair.KamonExt
import fr.acinq.eclair.plugins.peerswap.json.PeerSwapJsonSerializers.formats
import fr.acinq.eclair.wire.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import scodec.bits.BitVector
import scodec.codecs._
import scodec.{Attempt, Codec}

object PeerSwapMessageCodecs {

  val swapInRequestCodec: Codec[SwapInRequest] = limitedSizeBytes(65533, utf8)
    .xmap(a => Serialization.read[SwapInRequest](compact(render(parse(a).camelizeKeys))),
      b => compact(render(parse(Serialization.write(b)).snakizeKeys)))

  val swapOutRequestCodec: Codec[SwapOutRequest] = limitedSizeBytes(65533, utf8)
    .xmap(a => Serialization.read[SwapOutRequest](compact(render(parse(a).camelizeKeys))),
      b => compact(render(parse(Serialization.write(b)).snakizeKeys)))

  val swapInAgreementCodec: Codec[SwapInAgreement] = limitedSizeBytes(65533, utf8)
    .xmap(a => Serialization.read[SwapInAgreement](compact(render(parse(a).camelizeKeys))),
      b => compact(render(parse(Serialization.write(b)).snakizeKeys)))

  val swapOutAgreementCodec: Codec[SwapOutAgreement] = limitedSizeBytes(65533, utf8)
    .xmap(a => Serialization.read[SwapOutAgreement](compact(render(parse(a).camelizeKeys))),
      b => compact(render(parse(Serialization.write(b)).snakizeKeys)))

  val openingTxBroadcastedCodec: Codec[OpeningTxBroadcasted] = limitedSizeBytes(65533, utf8)
    .xmap(a => Serialization.read[OpeningTxBroadcasted](compact(render(parse(a).camelizeKeys))),
      b => compact(render(parse(Serialization.write(b)).snakizeKeys)))

  val canceledCodec: Codec[CancelSwap] = limitedSizeBytes(65533, utf8)
    .xmap(a => Serialization.read[CancelSwap](compact(render(parse(a).camelizeKeys))),
      b => compact(render(parse(Serialization.write(b)).snakizeKeys)))

  val coopCloseCodec: Codec[CoopClose] = limitedSizeBytes(65533, utf8)
    .xmap(a => Serialization.read[CoopClose](compact(render(parse(a).camelizeKeys))),
      b => compact(render(parse(Serialization.write(b)).snakizeKeys)))

  val unknownPeerSwapMessageCodec: Codec[UnknownPeerSwapMessage] = (
    ("tag" | uint16) ::
      ("message" | varsizebinarydata)
    ).as[UnknownPeerSwapMessage]

  val peerSwapMessageCodec: DiscriminatorCodec[HasSwapId, Int] = discriminated[HasSwapId].by(uint16)
    .typecase(42069, swapInRequestCodec)
    .typecase(42071, swapOutRequestCodec)
    .typecase(42073, swapInAgreementCodec)
    .typecase(42075, swapOutAgreementCodec)
    .typecase(42077, openingTxBroadcastedCodec)
    .typecase(42079, canceledCodec)
    .typecase(42081, coopCloseCodec)

  val peerSwapMessageCodecWithFallback: Codec[HasSwapId] = discriminatorWithDefault(peerSwapMessageCodec, unknownPeerSwapMessageCodec.upcast)

  val meteredPeerSwapMessageCodec: Codec[HasSwapId] = Codec[HasSwapId](
    (msg: HasSwapId) => KamonExt.time(Metrics.EncodeDuration.withTag(Tags.MessageType, msg.getClass.getSimpleName))(peerSwapMessageCodecWithFallback.encode(msg)),
    (bits: BitVector) => {
      // this is a bit more involved, because we don't know beforehand what the type of the message will be
      val begin = System.nanoTime()
      val res = peerSwapMessageCodecWithFallback.decode(bits)
      val end = System.nanoTime()
      val messageType = res match {
        case Attempt.Successful(decoded) => decoded.value.getClass.getSimpleName
        case Attempt.Failure(_) => "unknown"
      }
      Metrics.DecodeDuration.withTag(Tags.MessageType, messageType).record(end - begin)
      res
    }
  )

}
