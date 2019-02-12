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

import java.net.InetSocketAddress

import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, OutPoint, Transaction}
import fr.acinq.eclair.channel.State
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.router.RouteResponse
import fr.acinq.eclair.transactions.Direction
import fr.acinq.eclair.transactions.Transactions.{InputInfo, TransactionWithInputInfo}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{ShortChannelId, UInt64}
import org.json4s.JsonAST._
import org.json4s.{CustomKeySerializer, CustomSerializer}

/**
  * JSON Serializers.
  * Note: in general, deserialization does not need to be implemented.
  */
class BinaryDataSerializer extends CustomSerializer[BinaryData](format => ({ null }, {
  case x: BinaryData => JString(x.toString())
}))

class UInt64Serializer extends CustomSerializer[UInt64](format => ({ null }, {
  case x: UInt64 => JInt(x.toBigInt)
}))

class MilliSatoshiSerializer extends CustomSerializer[MilliSatoshi](format => ({ null }, {
  case x: MilliSatoshi => JInt(x.amount)
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

class PointSerializer extends CustomSerializer[Point](format => ({ null }, {
  case x: Point => JString(x.toString())
}))

class ScalarSerializer extends CustomSerializer[Scalar](format => ({ null }, {
  case x: Scalar => JString("XXX")
}))

class TransactionSerializer extends CustomSerializer[TransactionWithInputInfo](ser = format => ({ null }, {
  case x: Transaction => JString(x.toString())
}))

class TransactionWithInputInfoSerializer extends CustomSerializer[TransactionWithInputInfo](ser = format => ({ null }, {
  case x: TransactionWithInputInfo => JString(x.tx.toString())
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
  case x: InputInfo => JObject(("outPoint", JString(s"${x.outPoint.txid}:${x.outPoint.index}")), ("amountSatoshis", JInt(x.txOut.amount.amount)))
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

class PaymentRequestSerializer extends CustomSerializer[PaymentRequest](format => ({ null },{
  case p: PaymentRequest => JObject(JField("prefix", JString(p.prefix)) ::
    JField("amount", if (p.amount.isDefined) JLong(p.amount.get.toLong) else JNull) ::
    JField("timestamp", JLong(p.timestamp)) ::
    JField("nodeId", JString(p.nodeId.toString())) ::
    JField("description", JString(p.description match {
      case Left(l) => l.toString()
      case Right(r) => r.toString()
    })) ::
    JField("paymentHash", JString(p.paymentHash.toString())) ::
    JField("expiry", if (p.expiry.isDefined) JLong(p.expiry.get) else JNull) ::
    JField("minFinalCltvExpiry", if (p.minFinalCltvExpiry.isDefined) JLong(p.minFinalCltvExpiry.get) else JNull) ::
    Nil)
}))
