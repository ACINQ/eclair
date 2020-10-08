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

package fr.acinq.eclair.api

import java.util.UUID

import akka.util.Timeout
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.io.NodeURI
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId}
import scodec.bits.ByteVector
import spray.httpx.unmarshalling.Deserializer
import JsonSupport.json4sJacksonFormats
import JsonSupport.serialization

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object FormParamExtractors {

  implicit val publicKeyUnmarshaller: Deserializer[Option[String], Option[PublicKey]] = strictDeserializer { str =>
    PublicKey(ByteVector.fromValidHex(str))
  }

  implicit val binaryDataUnmarshaller: Deserializer[Option[String], Option[ByteVector]] = strictDeserializer { str =>
    ByteVector.fromValidHex(str)
  }

  implicit val sha256HashUnmarshaller: Deserializer[Option[String], Option[ByteVector32]] = strictDeserializer { bin =>
    ByteVector32.fromValidHex(bin)
  }

  implicit val sha256HashesUnmarshaller: Deserializer[Option[String], Option[List[ByteVector32]]] = strictDeserializer { bin =>
    bin.split(",").map(ByteVector32.fromValidHex).toList
  }

  implicit val bolt11Unmarshaller: Deserializer[Option[String], Option[PaymentRequest]] = strictDeserializer { rawRequest =>
    PaymentRequest.read(rawRequest)
  }

  implicit val shortChannelIdUnmarshaller: Deserializer[Option[String], Option[ShortChannelId]] = strictDeserializer { str =>
    ShortChannelId(str)
  }

  implicit val shortChannelIdsUnmarshaller: Deserializer[Option[String], Option[List[ShortChannelId]]] = strictDeserializer { str =>
    str.split(",").map(ShortChannelId(_)).toList
  }

  implicit val javaUUIDUnmarshaller: Deserializer[Option[String], Option[UUID]] = strictDeserializer { str =>
    UUID.fromString(str)
  }

  implicit val timeoutSecondsUnmarshaller: Deserializer[Option[String], Option[Timeout]] = strictDeserializer { str =>
    Timeout(str.toInt.seconds)
  }

  implicit val nodeURIUnmarshaller: Deserializer[Option[String], Option[NodeURI]] = strictDeserializer { str =>
    NodeURI.parse(str)
  }

  implicit val pubkeyListUnmarshaller: Deserializer[Option[String], Option[List[PublicKey]]] = strictDeserializer { str =>
    Try(serialization.read[List[String]](str).map { el =>
      PublicKey(ByteVector.fromValidHex(el), checkValid = false)
    }).recoverWith[List[PublicKey]] {
      case error => Try(str.split(",").toList.map(pk => PublicKey(ByteVector.fromValidHex(pk))))
    } match {
      case Success(list: List[PublicKey]) => list
      case Failure(_) => throw new IllegalArgumentException(s"PublicKey list must be either json-encoded or comma separated list")
    }
  }

  implicit val satoshiUnmarshaller: Deserializer[Option[String], Option[Satoshi]] = strictDeserializer { str =>
    Satoshi(str.toLong)
  }

  implicit val millisatoshiUnmarshaller: Deserializer[Option[String], Option[MilliSatoshi]] = strictDeserializer { str =>
    MilliSatoshi(str.toLong)
  }

  implicit val millisatoshiUnmarshallerOpt: Deserializer[Option[String], Option[MilliSatoshi]] = strictDeserializer { str =>
    MilliSatoshi(str.toLong)
  }

  def strictDeserializer[T](f: String => T): Deserializer[Option[String], Option[T]] = Deserializer.fromFunction2Converter {
    case Some(str) => Some(f(str))
    case None => None
  }
}
