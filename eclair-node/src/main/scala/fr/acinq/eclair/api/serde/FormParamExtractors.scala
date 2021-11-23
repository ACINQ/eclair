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

package fr.acinq.eclair.api.serde

import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.util.Timeout
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi}
import fr.acinq.eclair.api.directives.RouteFormat
import fr.acinq.eclair.api.serde.JsonSupport._
import fr.acinq.eclair.blockchain.fee.FeeratePerByte
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.io.NodeURI
import fr.acinq.eclair.payment.Bolt11Invoice
import fr.acinq.eclair.wire.protocol.MessageOnionCodecs.blindedRouteCodec
import fr.acinq.eclair.wire.protocol.OfferTypes.Offer
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId, TimestampSecond}
import scodec.bits.ByteVector

import java.util.UUID
import scala.concurrent.duration._
import scala.util.Try

object FormParamExtractors {

  implicit val publicKeyUnmarshaller: Unmarshaller[String, PublicKey] = Unmarshaller.strict { rawPubKey => PublicKey(ByteVector.fromValidHex(rawPubKey)) }

  implicit val bytesUnmarshaller: Unmarshaller[String, ByteVector] = Unmarshaller.strict { str => ByteVector.fromValidHex(str) }

  implicit val bytes32Unmarshaller: Unmarshaller[String, ByteVector32] = Unmarshaller.strict { bin => ByteVector32.fromValidHex(bin) }

  implicit val bytes32ListUnmarshaller: Unmarshaller[String, List[ByteVector32]] = listUnmarshaller(bin => ByteVector32.fromValidHex(bin))

  implicit val bolt11Unmarshaller: Unmarshaller[String, Bolt11Invoice] = Unmarshaller.strict { rawRequest => Bolt11Invoice.fromString(rawRequest).get }

  implicit val shortChannelIdUnmarshaller: Unmarshaller[String, ShortChannelId] = Unmarshaller.strict { str => ShortChannelId.fromCoordinates(str).get }

  implicit val shortChannelIdsUnmarshaller: Unmarshaller[String, List[ShortChannelId]] = listUnmarshaller(str => ShortChannelId.fromCoordinates(str).get)

  implicit val javaUUIDUnmarshaller: Unmarshaller[String, UUID] = Unmarshaller.strict { str => UUID.fromString(str) }

  implicit val timeoutSecondsUnmarshaller: Unmarshaller[String, Timeout] = Unmarshaller.strict { str => Timeout(str.toInt.seconds) }

  implicit val nodeURIUnmarshaller: Unmarshaller[String, NodeURI] = Unmarshaller.strict { str => NodeURI.parse(str) }

  implicit val pubkeyListUnmarshaller: Unmarshaller[String, List[PublicKey]] = listUnmarshaller(pk => PublicKey(ByteVector.fromValidHex(pk)))

  implicit val satoshiUnmarshaller: Unmarshaller[String, Satoshi] = Unmarshaller.strict { str => Satoshi(str.toLong) }

  implicit val millisatoshiUnmarshaller: Unmarshaller[String, MilliSatoshi] = Unmarshaller.strict { str => MilliSatoshi(str.toLong) }

  implicit val feeratePerByteUnmarshaller: Unmarshaller[String, FeeratePerByte] = Unmarshaller.strict { str => FeeratePerByte(Satoshi(str.toLong)) }

  implicit val base64DataUnmarshaller: Unmarshaller[String, ByteVector] = Unmarshaller.strict { str => ByteVector.fromValidBase64(str) }

  implicit val routeFormatUnmarshaller: Unmarshaller[String, RouteFormat] = Unmarshaller.strict { str => RouteFormat.fromString(str) }

  implicit val timestampSecondUnmarshaller: Unmarshaller[String, TimestampSecond] = Unmarshaller.strict { str => TimestampSecond(str.toLong) }

  val blindedRouteUnmarshaller: Unmarshaller[String, Sphinx.RouteBlinding.BlindedRoute] = Unmarshaller.strict { str =>
    blindedRouteCodec.decode(ByteVector.fromValidHex(str).bits).require.value
  }

  val offerUnmarshaller: Unmarshaller[String, Offer] = Unmarshaller.strict { Offer.decode(_).get }

  private def listUnmarshaller[T](unmarshal: String => T): Unmarshaller[String, List[T]] = Unmarshaller.strict { str =>
    Try(serialization.read[List[String]](str).map(unmarshal))
      .recoverWith(_ => Try(str.split(",").toList.map(unmarshal)))
      .getOrElse(throw new IllegalArgumentException("list must be either json-encoded or comma separated"))
  }

}
