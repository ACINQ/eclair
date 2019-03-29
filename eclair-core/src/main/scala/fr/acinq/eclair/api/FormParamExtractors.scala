package fr.acinq.eclair.api

import akka.http.scaladsl.unmarshalling.Unmarshaller
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.payment.PaymentRequest
import scodec.bits.ByteVector

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

}
