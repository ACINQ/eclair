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

package fr.acinq.eclair.api

import java.util.UUID

import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.util.Timeout
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.payment.PaymentRequest
import scodec.bits.ByteVector
import scala.concurrent.duration._

object FormParamExtractors {

  implicit val publicKeyUnmarshaller: Unmarshaller[String, PublicKey] = Unmarshaller.strict { rawPubKey =>
    PublicKey(ByteVector.fromValidHex(rawPubKey))
  }

  implicit val binaryDataUnmarshaller: Unmarshaller[String, ByteVector] = Unmarshaller.strict { str =>
    ByteVector.fromValidHex(str)
  }

  implicit val sha256HashUnmarshaller: Unmarshaller[String, ByteVector32] = Unmarshaller.strict { bin =>
    ByteVector32.fromValidHex(bin)
  }

  implicit val bolt11Unmarshaller: Unmarshaller[String, PaymentRequest] = Unmarshaller.strict { rawRequest =>
    PaymentRequest.read(rawRequest)
  }

  implicit val shortChannelIdUnmarshaller: Unmarshaller[String, ShortChannelId] = Unmarshaller.strict { str =>
    ShortChannelId(str)
  }

  implicit val javaUUIDUnmarshaller: Unmarshaller[String, UUID] = Unmarshaller.strict { str =>
    UUID.fromString(str)
  }

  implicit val timeoutUnmarshaller: Unmarshaller[String, Timeout] = Unmarshaller.strict { str =>
    Timeout(str.toInt.seconds)
  }

}
