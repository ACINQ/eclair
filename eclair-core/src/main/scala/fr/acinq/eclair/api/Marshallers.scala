package fr.acinq.eclair.api

import java.net.InetAddress

import akka.http.scaladsl.server.{PathMatcher, PathMatcher1, _}
import akka.http.scaladsl.unmarshalling.Unmarshaller
import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.wire.NodeAddress
import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}

object Marshallers extends Directives with Logging {

  val hexRegex = "[0-9a-fA-F]{2}".r

  implicit val publicKeyUnmarshaller: Unmarshaller[String, PublicKey] = Unmarshaller.strict { rawPubKey =>
    Try {
      PublicKey(rawPubKey)
    } match {
      case Success(key) => key
      case Failure(exception) => throw exception
    }
  }

  implicit val inetAddressUnmarshaller: Unmarshaller[String, NodeAddress] = Unmarshaller.strict { rawAddress =>
    val Array(host: String, port: String) = rawAddress.split(":")
    NodeAddress.fromParts(host, port.toInt) match {
      case Success(address) => address
      case Failure(thr) => throw thr
    }
  }

  implicit val binaryDataUnmarshaller: Unmarshaller[String, BinaryData] = Unmarshaller.strict { hex =>
    BinaryData(hex)
  }

  implicit val sha256HashUnmarshaller: Unmarshaller[String, BinaryData] = binaryDataUnmarshaller.map { bin =>
    bin.size match {
      case 33 => bin
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
