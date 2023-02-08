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

import java.net.InetSocketAddress
import java.util.UUID

import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.scala.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scala.{ByteVector32, ByteVector64, OutPoint, Satoshi, Transaction}
import fr.acinq.eclair.ApiTypes.ChannelIdentifier
import fr.acinq.eclair.channel.{ChannelCommandResponse, ChannelVersion, State}
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.db.FailureType.FailureType
import fr.acinq.eclair.db.{IncomingPaymentStatus, OutgoingPaymentStatus}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Router.RouteResponse
import fr.acinq.eclair.transactions.DirectedHtlc
import fr.acinq.eclair.transactions.Transactions.{InputInfo, TransactionWithInputInfo}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, ShortChannelId, UInt64}
import org.json4s.JsonAST._
import org.json4s.{CustomKeySerializer, CustomSerializer, DefaultFormats, Extraction, TypeHints, jackson}
import scodec.bits.ByteVector
import spray.httpx.{Json4sJacksonSupport, Json4sSupport}

/**
 * JSON Serializers.
 * Note: in general, deserialization does not need to be implemented.
 */
class ByteVectorSerializer extends CustomSerializer[ByteVector](_ => ( {
  null
}, {
  case x: ByteVector => JString(x.toHex)
}))

class ByteVector32Serializer extends CustomSerializer[ByteVector32](_ => ( {
  null
}, {
  case x: ByteVector32 => JString(x.toHex)
}))

class ByteVector64Serializer extends CustomSerializer[ByteVector64](_ => ( {
  null
}, {
  case x: ByteVector64 => JString(x.toHex)
}))

class UInt64Serializer extends CustomSerializer[UInt64](_ => ( {
  null
}, {
  case x: UInt64 => JInt(x.toBigInt)
}))

class SatoshiSerializer extends CustomSerializer[Satoshi](_ => ( {
  null
}, {
  case x: Satoshi => JInt(x.toLong)
}))

class MilliSatoshiSerializer extends CustomSerializer[MilliSatoshi](_ => ( {
  null
}, {
  case x: MilliSatoshi => JInt(x.toLong)
}))

class CltvExpirySerializer extends CustomSerializer[CltvExpiry](_ => ( {
  null
}, {
  case x: CltvExpiry => JLong(x.toLong)
}))

class CltvExpiryDeltaSerializer extends CustomSerializer[CltvExpiryDelta](_ => ( {
  null
}, {
  case x: CltvExpiryDelta => JInt(x.toInt)
}))

class ShortChannelIdSerializer extends CustomSerializer[ShortChannelId](_ => ( {
  null
}, {
  case x: ShortChannelId => JString(x.toString)
}))

class ChannelIdentifierSerializer extends CustomKeySerializer[ChannelIdentifier](_ => ( {
  null
}, {
  case Left(x: ByteVector32) => x.toHex
  case Right(x: ShortChannelId) => x.toString
}))

class StateSerializer extends CustomSerializer[State](_ => ( {
  null
}, {
  case x: State => JString(x.toString)
}))

class ShaChainSerializer extends CustomSerializer[ShaChain](_ => ( {
  null
}, {
  case _: ShaChain => JNull
}))

class PublicKeySerializer extends CustomSerializer[PublicKey](_ => ( {
  null
}, {
  case x: PublicKey => JString(x.toString())
}))

class PrivateKeySerializer extends CustomSerializer[PrivateKey](_ => ( {
  null
}, {
  case _: PrivateKey => JString("XXX")
}))

class ChannelVersionSerializer extends CustomSerializer[ChannelVersion](_ => ( {
  null
}, {
  case x: ChannelVersion => JString(x.bits.toBin)
}))

class ChannelCommandResponseSerializer extends CustomSerializer[ChannelCommandResponse](_ => ( {
  null
}, {
  case x: ChannelCommandResponse => JString(x.toString)
}))

class TransactionSerializer extends CustomSerializer[TransactionWithInputInfo](_ => ( {
  null
}, {
  case x: Transaction => JObject(List(
    JField("txid", JString(x.txid.toHex)),
    JField("tx", JString(x.toString()))
  ))
}))

class TransactionWithInputInfoSerializer extends CustomSerializer[TransactionWithInputInfo](_ => ( {
  null
}, {
  case x: TransactionWithInputInfo => JObject(List(
    JField("txid", JString(x.tx.txid.toHex)),
    JField("tx", JString(x.tx.toString()))
  ))
}))

class InetSocketAddressSerializer extends CustomSerializer[InetSocketAddress](_ => ( {
  null
}, {
  case address: InetSocketAddress => JString(HostAndPort.fromParts(address.getHostString, address.getPort).toString)
}))

class OutPointSerializer extends CustomSerializer[OutPoint](_ => ( {
  null
}, {
  case x: OutPoint => JString(s"${x.txid}:${x.index}")
}))

class OutPointKeySerializer extends CustomKeySerializer[OutPoint](_ => ( {
  null
}, {
  case x: OutPoint => s"${x.txid}:${x.index}"
}))

class InputInfoSerializer extends CustomSerializer[InputInfo](_ => ( {
  null
}, {
  case x: InputInfo => JObject(("outPoint", JString(s"${x.outPoint.txid}:${x.outPoint.index}")), ("amountSatoshis", JInt(x.txOut.amount.toLong)))
}))

class ColorSerializer extends CustomSerializer[Color](_ => ( {
  null
}, {
  case c: Color => JString(c.toString)
}))

class RouteResponseSerializer extends CustomSerializer[RouteResponse](_ => ( {
  null
}, {
  case route: RouteResponse =>
    val nodeIds = route.routes.head.hops match {
      case rest :+ last => rest.map(_.nodeId) :+ last.nodeId :+ last.nextNodeId
      case Nil => Nil
    }
    JArray(nodeIds.toList.map(n => JString(n.toString)))
}))

class ThrowableSerializer extends CustomSerializer[Throwable](_ => ( {
  null
}, {
  case t: Throwable if t.getMessage != null => JString(t.getMessage)
  case t: Throwable => JString(t.getClass.getSimpleName)
}))

class FailureMessageSerializer extends CustomSerializer[FailureMessage](_ => ( {
  null
}, {
  case m: FailureMessage => JString(m.message)
}))

class FailureTypeSerializer extends CustomSerializer[FailureType](_ => ( {
  null
}, {
  case ft: FailureType => JString(ft.toString)
}))

class NodeAddressSerializer extends CustomSerializer[NodeAddress](_ => ( {
  null
}, {
  case n: NodeAddress => JString(HostAndPort.fromParts(n.socketAddress.getHostString, n.socketAddress.getPort).toString)
}))

class DirectedHtlcSerializer extends CustomSerializer[DirectedHtlc](_ => ( {
  null
}, {
  case h: DirectedHtlc => new JObject(List(("direction", JString(h.direction)), ("add", Extraction.decompose(h.add)(
    DefaultFormats +
      new ByteVector32Serializer +
      new ByteVectorSerializer +
      new PublicKeySerializer +
      new MilliSatoshiSerializer +
      new CltvExpirySerializer))))
}))

class PaymentRequestSerializer extends CustomSerializer[PaymentRequest](_ => ( {
  null
}, {
  case p: PaymentRequest =>
    val expiry = p.expiry.map(ex => JField("expiry", JLong(ex))).toSeq
    val minFinalCltvExpiry = p.minFinalCltvExpiryDelta.map(mfce => JField("minFinalCltvExpiry", JInt(mfce.toInt))).toSeq
    val amount = p.amount.map(msat => JField("amount", JLong(msat.toLong))).toSeq
    val features = JField("features", JsonSupport.featuresToJson(Features(p.features.bitmask)))
    val fieldList = List(JField("prefix", JString(p.prefix)),
      JField("timestamp", JLong(p.timestamp)),
      JField("nodeId", JString(p.nodeId.toString())),
      JField("serialized", JString(PaymentRequest.write(p))),
      JField("description", JString(p.description match {
        case Left(l) => l
        case Right(r) => r.toString()
      })),
      JField("paymentHash", JString(p.paymentHash.toString()))) ++
      expiry ++
      minFinalCltvExpiry ++
      amount :+
      features
    JObject(fieldList)
}))

class FeaturesSerializer extends CustomSerializer[Features](_ => ( {
  null
}, {
  case features: Features => JsonSupport.featuresToJson(features)
}))

class JavaUUIDSerializer extends CustomSerializer[UUID](_ => ( {
  null
}, {
  case id: UUID => JString(id.toString)
}))

object JsonSupport extends Json4sJacksonSupport {

  override val serialization = org.json4s.jackson.Serialization

  override implicit val json4sJacksonFormats = org.json4s.DefaultFormats +
    new ByteVectorSerializer +
    new ByteVector32Serializer +
    new ByteVector64Serializer +
    new UInt64Serializer +
    new SatoshiSerializer +
    new MilliSatoshiSerializer +
    new CltvExpirySerializer +
    new CltvExpiryDeltaSerializer +
    new ShortChannelIdSerializer +
    new ChannelIdentifierSerializer +
    new StateSerializer +
    new ShaChainSerializer +
    new PublicKeySerializer +
    new PrivateKeySerializer +
    new TransactionSerializer +
    new TransactionWithInputInfoSerializer +
    new InetSocketAddressSerializer +
    new OutPointSerializer +
    new OutPointKeySerializer +
    new ChannelVersionSerializer +
    new ChannelCommandResponseSerializer +
    new InputInfoSerializer +
    new ColorSerializer +
    new RouteResponseSerializer +
    new ThrowableSerializer +
    new FailureMessageSerializer +
    new FailureTypeSerializer +
    new NodeAddressSerializer +
    new DirectedHtlcSerializer +
    new PaymentRequestSerializer +
    new JavaUUIDSerializer +
    new FeaturesSerializer

  case class CustomTypeHints(custom: Map[Class[_], String]) extends TypeHints {
    val reverse: Map[String, Class[_]] = custom.map(_.swap)

    override val hints: List[Class[_]] = custom.keys.toList
    override def hintFor(clazz: Class[_]): String = custom.getOrElse(clazz, {
      throw new IllegalArgumentException(s"No type hint mapping found for $clazz")
    })
    override def classFor(hint: String): Option[Class[_]] = reverse.get(hint)
  }

  def featuresToJson(features: Features) = JObject(
    JField("activated", JArray(features.activated.map { a =>
      JObject(
        JField("name", JString(a.feature.rfcName)),
        JField("support", JString(a.support.toString))
      )}.toList)),
    JField("unknown", JArray(features.unknown.map { i =>
      JObject(
        JField("featureBit", JInt(i.bitIndex))
      )}.toList))
  )
}