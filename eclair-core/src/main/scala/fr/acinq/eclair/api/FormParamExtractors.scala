package fr.acinq.eclair.api

import akka.http.scaladsl.unmarshalling.Unmarshaller
import fr.acinq.bitcoin.{ByteVector32}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.payment.PaymentRequest
import scodec.bits.ByteVector
import scala.util.{Failure, Success, Try}

object FormParamExtractors {

  implicit val publicKeyUnmarshaller: Unmarshaller[String, PublicKey] = Unmarshaller.strict { rawPubKey =>
    Try {
      PublicKey(ByteVector.fromValidHex(rawPubKey))
    } match {
      case Success(key) => key
      case Failure(exception) => throw exception
    }
  }

  implicit val binaryDataUnmarshaller: Unmarshaller[String, ByteVector] = Unmarshaller.strict { str =>
    ByteVector.fromValidHex(str)
  }

  implicit val sha256HashUnmarshaller: Unmarshaller[String, ByteVector32] = Unmarshaller.strict { bin =>
    ByteVector32.fromValidHex(bin)
  }

  implicit val bolt11Unmarshaller: Unmarshaller[String, PaymentRequest] = Unmarshaller.strict { rawRequest =>
    Try {
      PaymentRequest.read(rawRequest)
    } match {
      case Success(req) => req
      case Failure(exception) => throw exception
    }
  }

}
