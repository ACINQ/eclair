package fr.acinq.eclair.api

import akka.http.scaladsl.unmarshalling.Unmarshaller
import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.payment.PaymentRequest
import scala.util.{Failure, Success, Try}

object FormParamExtractors {

  implicit val publicKeyUnmarshaller: Unmarshaller[String, PublicKey] = Unmarshaller.strict { rawPubKey =>
    Try {
      PublicKey(rawPubKey)
    } match {
      case Success(key) => key
      case Failure(exception) => throw exception
    }
  }

  implicit val binaryDataUnmarshaller: Unmarshaller[String, BinaryData] = Unmarshaller.strict { hex =>
    BinaryData(hex)
  }

  implicit val sha256HashUnmarshaller: Unmarshaller[String, BinaryData] = binaryDataUnmarshaller.map { bin =>
    bin.size match {
      case 32 => bin
      case _ => throw new IllegalArgumentException(s"$bin is not a valid SHA256 hash")
    }
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
