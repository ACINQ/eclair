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

package fr.acinq.eclair.json

import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Btc, ByteVector32, ByteVector64, OutPoint, Satoshi, Transaction}
import fr.acinq.eclair.ApiTypes.ChannelIdentifier
import fr.acinq.eclair.balance.CheckBalance.GlobalBalance
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.db.FailureType.FailureType
import fr.acinq.eclair.db.{IncomingPaymentStatus, OutgoingPaymentStatus}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Router.RouteResponse
import fr.acinq.eclair.transactions.DirectedHtlc
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, ShortChannelId, UInt64}
import org.json4s
import org.json4s.JsonAST._
import org.json4s.jackson.Serialization
import org.json4s.reflect.TypeInfo
import org.json4s.{DefaultFormats, Extraction, Formats, JDecimal, JValue, KeySerializer, MappingException, Serializer, ShortTypeHints, TypeHints, jackson}
import scodec.bits.ByteVector

import java.net.InetSocketAddress
import java.util.UUID

/**
 * Custom serializer that only does serialization, not deserialization.
 *
 * NB: this is a stripped-down version of [[org.json4s.CustomSerializer]]
 */
class CustomSerializerOnly[A: Manifest](ser: Formats => PartialFunction[Any, JValue]) extends Serializer[A] {

  val Class: Class[_] = implicitly[Manifest[A]].runtimeClass

  def deserialize(implicit format: Formats): PartialFunction[(json4s.TypeInfo, JValue), A] = {
    case (TypeInfo(Class, _), json) => throw new MappingException("Can't convert " + json + " to " + Class)
  }

  def serialize(implicit format: Formats): PartialFunction[Any, JValue] = ser(format)
}

/** Same as above, but for [[org.json4s.CustomKeySerializer]] */
class CustomKeySerializerOnly[A: Manifest](ser: Formats => PartialFunction[Any, String]) extends KeySerializer[A] {

  val Class = implicitly[Manifest[A]].runtimeClass

  def deserialize(implicit format: Formats): PartialFunction[(json4s.TypeInfo, String), A] = {
    case (TypeInfo(Class, _), json) => throw new MappingException("Can't convert " + json + " to " + Class)
  }

  def serialize(implicit format: Formats): PartialFunction[Any, String] = ser(format)
}

class ByteVectorSerializer extends CustomSerializerOnly[ByteVector](_ => {
  case x: ByteVector => JString(x.toHex)
})

class ByteVector32Serializer extends CustomSerializerOnly[ByteVector32](_ => {
  case x: ByteVector32 => JString(x.toHex)
})

class ByteVector32KeySerializer extends CustomKeySerializerOnly[ByteVector32](_ => {
  case x: ByteVector32 => x.toHex
})

class ByteVector64Serializer extends CustomSerializerOnly[ByteVector64](_ => {
  case x: ByteVector64 => JString(x.toHex)
})

class UInt64Serializer extends CustomSerializerOnly[UInt64](_ => {
  case x: UInt64 => JInt(x.toBigInt)
})

class BtcSerializer extends CustomSerializerOnly[Btc](_ => {
  case x: Btc => JDecimal(x.toDouble)
})

class SatoshiSerializer extends CustomSerializerOnly[Satoshi](_ => {
  case x: Satoshi => JInt(x.toLong)
})

class MilliSatoshiSerializer extends CustomSerializerOnly[MilliSatoshi](_ => {
  case x: MilliSatoshi => JInt(x.toLong)
})

class CltvExpirySerializer extends CustomSerializerOnly[CltvExpiry](_ => {
  case x: CltvExpiry => JLong(x.toLong)
})

class CltvExpiryDeltaSerializer extends CustomSerializerOnly[CltvExpiryDelta](_ => {
  case x: CltvExpiryDelta => JInt(x.toInt)
})

class FeeratePerKwSerializer extends CustomSerializerOnly[FeeratePerKw](_ => {
  case x: FeeratePerKw => JLong(x.toLong)
})

class ShortChannelIdSerializer extends CustomSerializerOnly[ShortChannelId](_ => {
  case x: ShortChannelId => JString(x.toString)
})

class ChannelIdentifierSerializer extends CustomKeySerializerOnly[ChannelIdentifier](_ => {
  case Left(x: ByteVector32) => x.toHex
  case Right(x: ShortChannelId) => x.toString
})

class ChannelStateSerializer extends CustomSerializerOnly[ChannelState](_ => {
  case x: ChannelState => JString(x.toString)
})

class ShaChainSerializer extends CustomSerializerOnly[ShaChain](_ => {
  case _: ShaChain => JNull
})

class PublicKeySerializer extends CustomSerializerOnly[PublicKey](_ => {
  case x: PublicKey => JString(x.toString())
})

class PrivateKeySerializer extends CustomSerializerOnly[PrivateKey](_ => {
  case _: PrivateKey => JString("XXX")
})

class ChannelConfigSerializer extends CustomSerializerOnly[ChannelConfig](_ => {
  case x: ChannelConfig => JArray(x.options.toList.map(o => JString(o.name)))
})

class ChannelFeaturesSerializer extends CustomSerializerOnly[ChannelFeatures](_ => {
  case channelFeatures: ChannelFeatures => JArray(channelFeatures.activated.map(f => JString(f.rfcName)).toList)
})

class ChannelOpenResponseSerializer extends CustomSerializerOnly[ChannelOpenResponse](_ => {
  case x: ChannelOpenResponse => JString(x.toString)
})

class CommandResponseSerializer extends CustomSerializerOnly[CommandResponse[Command]](_ => {
  case RES_SUCCESS(_: CloseCommand, channelId) => JString(s"closed channel $channelId")
  case RES_FAILURE(_: Command, ex: Throwable) => JString(ex.getMessage)
})

class TransactionSerializer extends CustomSerializerOnly[TransactionWithInputInfo](_ => {
  case x: Transaction => JObject(List(
    JField("txid", JString(x.txid.toHex)),
    JField("tx", JString(x.toString()))
  ))
})

class TransactionWithInputInfoSerializer extends CustomSerializerOnly[TransactionWithInputInfo](_ => {
  case x: HtlcSuccessTx => JObject(List(
    JField("txid", JString(x.tx.txid.toHex)),
    JField("tx", JString(x.tx.toString())),
    JField("paymentHash", JString(x.paymentHash.toString())),
    JField("htlcId", JLong(x.htlcId))
  ))
  case x: HtlcTimeoutTx => JObject(List(
    JField("txid", JString(x.tx.txid.toHex)),
    JField("tx", JString(x.tx.toString())),
    JField("htlcId", JLong(x.htlcId))
  ))
  case x: ClaimHtlcTx => JObject(List(
    JField("txid", JString(x.tx.txid.toHex)),
    JField("tx", JString(x.tx.toString())),
    JField("htlcId", JLong(x.htlcId))
  ))
  case x: ClosingTx =>
    val txFields = List(
      JField("txid", JString(x.tx.txid.toHex)),
      JField("tx", JString(x.tx.toString()))
    )
    x.toLocalOutput match {
      case Some(toLocal) =>
        val toLocalField = JField("toLocalOutput", JObject(List(
          JField("index", JLong(toLocal.index)),
          JField("amount", JLong(toLocal.amount.toLong)),
          JField("publicKeyScript", JString(toLocal.publicKeyScript.toHex))
        )))
        JObject(txFields :+ toLocalField)
      case None => JObject(txFields)
    }
  case x: TransactionWithInputInfo => JObject(List(
    JField("txid", JString(x.tx.txid.toHex)),
    JField("tx", JString(x.tx.toString()))
  ))
})

class InetSocketAddressSerializer extends CustomSerializerOnly[InetSocketAddress](_ => {
  case address: InetSocketAddress => JString(HostAndPort.fromParts(address.getHostString, address.getPort).toString)
})

class OutPointSerializer extends CustomSerializerOnly[OutPoint](_ => {
  case x: OutPoint => JString(s"${x.txid}:${x.index}")
})

class OutPointKeySerializer extends CustomKeySerializerOnly[OutPoint](_ => {
  case x: OutPoint => s"${x.txid}:${x.index}"
})

class InputInfoSerializer extends CustomSerializerOnly[InputInfo](_ => {
  case x: InputInfo => JObject(("outPoint", JString(s"${x.outPoint.txid}:${x.outPoint.index}")), ("amountSatoshis", JInt(x.txOut.amount.toLong)))
})

class ColorSerializer extends CustomSerializerOnly[Color](_ => {
  case c: Color => JString(c.toString)
})

class RouteResponseSerializer extends CustomSerializerOnly[RouteResponse](_ => {
  case route: RouteResponse =>
    val nodeIds = route.routes.head.hops match {
      case rest :+ last => rest.map(_.nodeId) :+ last.nodeId :+ last.nextNodeId
      case Nil => Nil
    }
    JArray(nodeIds.toList.map(n => JString(n.toString)))
})

class ThrowableSerializer extends CustomSerializerOnly[Throwable](_ => {
  case t: Throwable if t.getMessage != null => JString(t.getMessage)
  case t: Throwable => JString(t.getClass.getSimpleName)
})

class FailureMessageSerializer extends CustomSerializerOnly[FailureMessage](_ => {
  case m: FailureMessage => JString(m.message)
})

class FailureTypeSerializer extends CustomSerializerOnly[FailureType](_ => {
  case ft: FailureType => JString(ft.toString)
})

class NodeAddressSerializer extends CustomSerializerOnly[NodeAddress](_ => {
  case n: NodeAddress => JString(HostAndPort.fromParts(n.socketAddress.getHostString, n.socketAddress.getPort).toString)
})

class DirectedHtlcSerializer extends CustomSerializerOnly[DirectedHtlc](_ => {
  case h: DirectedHtlc => new JObject(List(("direction", JString(h.direction)), ("add", Extraction.decompose(h.add)(
    DefaultFormats +
      new ByteVector32Serializer +
      new ByteVectorSerializer +
      new PublicKeySerializer +
      new MilliSatoshiSerializer +
      new CltvExpirySerializer))))
})

class PaymentRequestSerializer extends CustomSerializerOnly[PaymentRequest](_ => {
  case p: PaymentRequest =>
    val expiry = p.expiry.map(ex => JField("expiry", JLong(ex))).toSeq
    val minFinalCltvExpiry = p.minFinalCltvExpiryDelta.map(mfce => JField("minFinalCltvExpiry", JInt(mfce.toInt))).toSeq
    val amount = p.amount.map(msat => JField("amount", JLong(msat.toLong))).toSeq
    val features = JField("features", JsonSerializers.featuresToJson(Features(p.features.bitmask)))
    val routingInfo = JField("routingInfo", Extraction.decompose(p.routingInfo)(
      DefaultFormats +
        new ByteVector32Serializer +
        new ByteVectorSerializer +
        new PublicKeySerializer +
        new ShortChannelIdSerializer +
        new MilliSatoshiSerializer +
        new CltvExpiryDeltaSerializer
      )
    )
    val fieldList = List(JField("prefix", JString(p.prefix)),
      JField("timestamp", JLong(p.timestamp)),
      JField("nodeId", JString(p.nodeId.toString())),
      JField("serialized", JString(PaymentRequest.write(p))),
      p.description.fold(string => JField("description", JString(string)), hash => JField("descriptionHash", JString(hash.toHex))),
      JField("paymentHash", JString(p.paymentHash.toString()))) ++
      expiry ++
      minFinalCltvExpiry ++
      amount :+
      features :+
      routingInfo

    JObject(fieldList)
})

class FeaturesSerializer extends CustomSerializerOnly[Features](_ => {
  case features: Features => JsonSerializers.featuresToJson(features)
})

class JavaUUIDSerializer extends CustomSerializerOnly[UUID](_ => {
  case id: UUID => JString(id.toString)
})

class ChannelEventSerializer extends CustomSerializerOnly[ChannelEvent](_ => {
  case e: ChannelCreated => JObject(
    JField("type", JString("channel-opened")),
    JField("remoteNodeId", JString(e.remoteNodeId.toString())),
    JField("isFunder", JBool(e.isFunder)),
    JField("temporaryChannelId", JString(e.temporaryChannelId.toHex)),
    JField("initialFeeratePerKw", JLong(e.initialFeeratePerKw.toLong)),
    JField("fundingTxFeeratePerKw", e.fundingTxFeeratePerKw.map(f => JLong(f.toLong)).getOrElse(JNothing))
  )
  case e: ChannelStateChanged => JObject(
    JField("type", JString("channel-state-changed")),
    JField("channelId", JString(e.channelId.toHex)),
    JField("remoteNodeId", JString(e.remoteNodeId.toString())),
    JField("previousState", JString(e.previousState.toString)),
    JField("currentState", JString(e.currentState.toString))
  )
  case e: ChannelClosed => JObject(
    JField("type", JString("channel-closed")),
    JField("channelId", JString(e.channelId.toHex)),
    JField("closingType", JString(e.closingType.getClass.getSimpleName))
  )
})

class OriginSerializer extends CustomSerializerOnly[Origin](_ => {
  case o: Origin.Local => JObject(JField("paymentId", JString(o.id.toString)))
  case o: Origin.ChannelRelayed => JObject(
    JField("channelId", JString(o.originChannelId.toHex)),
    JField("htlcId", JLong(o.originHtlcId)),
  )
  case o: Origin.TrampolineRelayed => JArray(o.htlcs.map {
    case (channelId, htlcId) => JObject(
      JField("channelId", JString(channelId.toHex)),
      JField("htlcId", JLong(htlcId)),
    )
  })
})

class GlobalBalanceSerializer extends CustomSerializerOnly[GlobalBalance](_ => {
  case o: GlobalBalance =>
    val formats = DefaultFormats + new ByteVector32KeySerializer + new BtcSerializer + new SatoshiSerializer
    JObject(JField("total", JDecimal(o.total.toDouble))) merge Extraction.decompose(o)(formats)
})

case class CustomTypeHints(custom: Map[Class[_], String]) extends TypeHints {
  val reverse: Map[String, Class[_]] = custom.map(_.swap)

  override val hints: List[Class[_]] = custom.keys.toList

  override def hintFor(clazz: Class[_]): String = custom.getOrElse(clazz, {
    throw new IllegalArgumentException(s"No type hint mapping found for $clazz")
  })

  override def classFor(hint: String): Option[Class[_]] = reverse.get(hint)
}

object CustomTypeHints {
  val incomingPaymentStatus: CustomTypeHints = CustomTypeHints(Map(
    IncomingPaymentStatus.Pending.getClass -> "pending",
    IncomingPaymentStatus.Expired.getClass -> "expired",
    classOf[IncomingPaymentStatus.Received] -> "received"
  ))

  val outgoingPaymentStatus: CustomTypeHints = CustomTypeHints(Map(
    OutgoingPaymentStatus.Pending.getClass -> "pending",
    classOf[OutgoingPaymentStatus.Failed] -> "failed",
    classOf[OutgoingPaymentStatus.Succeeded] -> "sent"
  ))

  val paymentEvent: CustomTypeHints = CustomTypeHints(Map(
    classOf[PaymentSent] -> "payment-sent",
    classOf[ChannelPaymentRelayed] -> "payment-relayed",
    classOf[TrampolinePaymentRelayed] -> "trampoline-payment-relayed",
    classOf[PaymentReceived] -> "payment-received",
    classOf[PaymentSettlingOnChain] -> "payment-settling-onchain",
    classOf[PaymentFailed] -> "payment-failed"
  ))

  val channelStates: ShortTypeHints = ShortTypeHints(
    List(
      classOf[Nothing],
      classOf[DATA_WAIT_FOR_OPEN_CHANNEL],
      classOf[DATA_WAIT_FOR_ACCEPT_CHANNEL],
      classOf[DATA_WAIT_FOR_FUNDING_INTERNAL],
      classOf[DATA_WAIT_FOR_FUNDING_CREATED],
      classOf[DATA_WAIT_FOR_FUNDING_SIGNED],
      classOf[DATA_WAIT_FOR_FUNDING_LOCKED],
      classOf[DATA_WAIT_FOR_FUNDING_CONFIRMED],
      classOf[DATA_NORMAL],
      classOf[DATA_SHUTDOWN],
      classOf[DATA_NEGOTIATING],
      classOf[DATA_CLOSING],
      classOf[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]
    ))
}

object JsonSerializers {

  implicit val serialization: Serialization.type = jackson.Serialization

  implicit val formats: Formats = (org.json4s.DefaultFormats +
    new ByteVectorSerializer +
    new ByteVector32Serializer +
    new ByteVector64Serializer +
    new ChannelEventSerializer +
    new UInt64Serializer +
    new BtcSerializer +
    new SatoshiSerializer +
    new MilliSatoshiSerializer +
    new CltvExpirySerializer +
    new CltvExpiryDeltaSerializer +
    new FeeratePerKwSerializer +
    new ShortChannelIdSerializer +
    new ChannelIdentifierSerializer +
    new ChannelStateSerializer +
    new ShaChainSerializer +
    new PublicKeySerializer +
    new PrivateKeySerializer +
    new TransactionSerializer +
    new TransactionWithInputInfoSerializer +
    new InetSocketAddressSerializer +
    new OutPointSerializer +
    new OutPointKeySerializer +
    new ChannelConfigSerializer +
    new ChannelFeaturesSerializer +
    new ChannelOpenResponseSerializer +
    new CommandResponseSerializer +
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
    new FeaturesSerializer +
    new OriginSerializer +
    new GlobalBalanceSerializer +
    CustomTypeHints.incomingPaymentStatus +
    CustomTypeHints.outgoingPaymentStatus +
    CustomTypeHints.paymentEvent +
    CustomTypeHints.channelStates).withTypeHintFieldName("type")

  def featuresToJson(features: Features): JObject = JObject(
    JField("activated", JObject(features.activated.map { case (feature, support) =>
      feature.rfcName -> JString(support.toString)
    }.toList)),
    JField("unknown", JArray(features.unknown.map(u => JInt(u.bitIndex)).toList))
  )

}
