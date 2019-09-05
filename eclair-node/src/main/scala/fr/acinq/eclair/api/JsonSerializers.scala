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
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, OutPoint, Satoshi, Transaction}
import fr.acinq.eclair.channel.{ChannelVersion, State}
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.db.OutgoingPaymentStatus
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.router.RouteResponse
import fr.acinq.eclair.transactions.Direction
import fr.acinq.eclair.transactions.Transactions.{InputInfo, TransactionWithInputInfo}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, MilliSatoshi, ShortChannelId, UInt64}
import org.json4s.JsonAST._
import org.json4s.{CustomKeySerializer, CustomSerializer, TypeHints, jackson}
import scodec.bits.ByteVector

/**
  * JSON Serializers.
  * Note: in general, deserialization does not need to be implemented.
  */
class ByteVectorSerializer extends CustomSerializer[ByteVector](format => ({ null }, {
  case x: ByteVector => JString(x.toHex)
}))

class ByteVector32Serializer extends CustomSerializer[ByteVector32](format => ({ null }, {
  case x: ByteVector32 => JString(x.toHex)
}))

class ByteVector64Serializer extends CustomSerializer[ByteVector64](format => ({ null }, {
  case x: ByteVector64 => JString(x.toHex)
}))

class UInt64Serializer extends CustomSerializer[UInt64](format => ({ null }, {
  case x: UInt64 => JInt(x.toBigInt)
}))

class SatoshiSerializer extends CustomSerializer[Satoshi](format => ({ null }, {
  case x: Satoshi => JInt(x.toLong)
}))

class MilliSatoshiSerializer extends CustomSerializer[MilliSatoshi](format => ({ null }, {
  case x: MilliSatoshi => JInt(x.toLong)
}))

class CltvExpirySerializer extends CustomSerializer[CltvExpiry](format => ({ null }, {
  case x: CltvExpiry => JLong(x.toLong)
}))

class CltvExpiryDeltaSerializer extends CustomSerializer[CltvExpiryDelta](format => ({ null }, {
  case x: CltvExpiryDelta => JInt(x.toInt)
}))

class ShortChannelIdSerializer extends CustomSerializer[ShortChannelId](format => ({ null }, {
  case x: ShortChannelId => JString(x.toString())
}))

class StateSerializer extends CustomSerializer[State](format => ({ null }, {
  case x: State => JString(x.toString())
}))

class ShaChainSerializer extends CustomSerializer[ShaChain](format => ({ null }, {
  case x: ShaChain => JNull
}))

class PublicKeySerializer extends CustomSerializer[PublicKey](format => ({ null }, {
  case x: PublicKey => JString(x.toString())
}))

class PrivateKeySerializer extends CustomSerializer[PrivateKey](format => ({ null }, {
  case x: PrivateKey => JString("XXX")
}))

class ChannelVersionSerializer extends CustomSerializer[ChannelVersion](format => ({ null }, {
  case x: ChannelVersion => JString(x.bits.toBin)
}))

class TransactionSerializer extends CustomSerializer[TransactionWithInputInfo](ser = format => ({ null }, {
  case x: Transaction => JObject(List(
    JField("txid", JString(x.txid.toHex)),
    JField("tx", JString(x.toString()))
  ))
}))

class TransactionWithInputInfoSerializer extends CustomSerializer[TransactionWithInputInfo](ser = format => ({ null }, {
  case x: TransactionWithInputInfo => JObject(List(
    JField("txid", JString(x.tx.txid.toHex)),
    JField("tx", JString(x.tx.toString()))
  ))
}))

class InetSocketAddressSerializer extends CustomSerializer[InetSocketAddress](format => ({ null }, {
  case address: InetSocketAddress => JString(HostAndPort.fromParts(address.getHostString, address.getPort).toString)
}))

class OutPointSerializer extends CustomSerializer[OutPoint](format => ({ null }, {
  case x: OutPoint => JString(s"${x.txid}:${x.index}")
}))

class OutPointKeySerializer extends CustomKeySerializer[OutPoint](format => ({ null }, {
  case x: OutPoint => s"${x.txid}:${x.index}"
}))

class InputInfoSerializer extends CustomSerializer[InputInfo](format => ({ null }, {
  case x: InputInfo => JObject(("outPoint", JString(s"${x.outPoint.txid}:${x.outPoint.index}")), ("amountSatoshis", JInt(x.txOut.amount.toLong)))
}))

class ColorSerializer extends CustomSerializer[Color](format => ({ null }, {
  case c: Color => JString(c.toString)
}))

class RouteResponseSerializer extends CustomSerializer[RouteResponse](format => ({ null }, {
  case route: RouteResponse =>
    val nodeIds = route.hops match {
      case rest :+ last => rest.map(_.nodeId) :+ last.nodeId :+ last.nextNodeId
      case Nil => Nil
    }
    JArray(nodeIds.toList.map(n => JString(n.toString)))
}))

class ThrowableSerializer extends CustomSerializer[Throwable](format => ({ null }, {
  case t: Throwable if t.getMessage != null => JString(t.getMessage)
  case t: Throwable => JString(t.getClass.getSimpleName)
}))

class FailureMessageSerializer extends CustomSerializer[FailureMessage](format => ({ null }, {
  case m: FailureMessage => JString(m.message)
}))

class NodeAddressSerializer extends CustomSerializer[NodeAddress](format => ({ null},{
  case n: NodeAddress => JString(HostAndPort.fromParts(n.socketAddress.getHostString, n.socketAddress.getPort).toString)
}))

class DirectionSerializer extends CustomSerializer[Direction](format => ({ null },{
  case d: Direction => JString(d.toString)
}))

class PaymentRequestSerializer extends CustomSerializer[PaymentRequest](format => ( {
  null
}, {
  case p: PaymentRequest => {
    val expiry = p.expiry.map(ex => JField("expiry", JLong(ex))).toSeq
    val minFinalCltvExpiry = p.minFinalCltvExpiryDelta.map(mfce => JField("minFinalCltvExpiry", JInt(mfce.toInt))).toSeq
    val amount = p.amount.map(msat => JField("amount", JLong(msat.toLong))).toSeq

    val fieldList = List(JField("prefix", JString(p.prefix)),
      JField("timestamp", JLong(p.timestamp)),
      JField("nodeId", JString(p.nodeId.toString())),
      JField("serialized", JString(PaymentRequest.write(p))),
      JField("description", JString(p.description match {
        case Left(l) => l.toString()
        case Right(r) => r.toString()
      })),
      JField("paymentHash", JString(p.paymentHash.toString()))) ++
      expiry ++
      minFinalCltvExpiry ++
      amount

    JObject(fieldList)
  }
}))

class JavaUUIDSerializer extends CustomSerializer[UUID](format => ({ null }, {
  case id: UUID => JString(id.toString)
}))

class OutgoingPaymentStatusSerializer extends CustomSerializer[OutgoingPaymentStatus.Value](format => ({ null }, {
  case el: OutgoingPaymentStatus.Value => JString(el.toString)
}))

object JsonSupport extends Json4sSupport {

  implicit val serialization = jackson.Serialization

  implicit val formats = org.json4s.DefaultFormats +
    new ByteVectorSerializer +
    new ByteVector32Serializer +
    new ByteVector64Serializer +
    new UInt64Serializer +
    new SatoshiSerializer +
    new MilliSatoshiSerializer +
    new CltvExpirySerializer +
    new CltvExpiryDeltaSerializer +
    new ShortChannelIdSerializer +
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
    new InputInfoSerializer +
    new ColorSerializer +
    new RouteResponseSerializer +
    new ThrowableSerializer +
    new FailureMessageSerializer +
    new NodeAddressSerializer +
    new DirectionSerializer +
    new PaymentRequestSerializer +
    new JavaUUIDSerializer +
    new OutgoingPaymentStatusSerializer

  case class CustomTypeHints(custom: Map[Class[_], String]) extends TypeHints {
    val reverse: Map[String, Class[_]] = custom.map(_.swap)

    override val hints: List[Class[_]] = custom.keys.toList
    override def hintFor(clazz: Class[_]): String = custom.getOrElse(clazz, {
      throw new IllegalArgumentException(s"No type hint mapping found for $clazz")
    })
    override def classFor(hint: String): Option[Class[_]] = reverse.get(hint)
  }


}