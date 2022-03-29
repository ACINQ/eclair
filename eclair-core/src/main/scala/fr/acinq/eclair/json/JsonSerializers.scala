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
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.scalacompat.{Btc, ByteVector32, ByteVector64, OutPoint, Satoshi, Transaction}
import fr.acinq.eclair.balance.CheckBalance.{CorrectedOnChainBalance, GlobalBalance, OffChainBalance}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.crypto.{ShaChain, Sphinx}
import fr.acinq.eclair.db.FailureType.FailureType
import fr.acinq.eclair.db.{IncomingPaymentStatus, OutgoingPaymentStatus}
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.payment.PaymentFailure.PaymentFailedSummary
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Router.{ChannelHop, ChannelRelayParams, Route}
import fr.acinq.eclair.transactions.DirectedHtlc
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.MessageOnionCodecs.blindedRouteCodec
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Feature, FeatureSupport, MilliSatoshi, ShortChannelId, TimestampMilli, TimestampSecond, UInt64, UnknownFeature}
import org.json4s
import org.json4s.JsonAST._
import org.json4s.jackson.Serialization
import org.json4s.{DefaultFormats, Extraction, Formats, JDecimal, JValue, KeySerializer, Serializer, ShortTypeHints, TypeHints, jackson}
import scodec.bits.ByteVector

import java.net.InetSocketAddress
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Minimal serializer that only does serialization, not deserialization, and does not depend on external formats.
 *
 * NB: this is a stripped-down version of [[org.json4s.CustomSerializer]]
 */
class MinimalSerializer(ser: PartialFunction[Any, JValue]) extends Serializer[Nothing] {

  def deserialize(implicit format: Formats): PartialFunction[(json4s.TypeInfo, JValue), Nothing] = PartialFunction.empty

  def serialize(implicit format: Formats): PartialFunction[Any, JValue] = ser
}

/** Same as above, but for [[org.json4s.CustomKeySerializer]] */
class MinimalKeySerializer(ser: PartialFunction[Any, String]) extends KeySerializer[Nothing] {

  def deserialize(implicit format: Formats): PartialFunction[(json4s.TypeInfo, String), Nothing] = PartialFunction.empty

  def serialize(implicit format: Formats): PartialFunction[Any, String] = ser
}

/**
 * Custom serializer where, instead of providing a `MyClass => JValue` conversion method, we provide a
 * `MyClass => MyClassJson` method, with the assumption that `MyClassJson` is serializable using the base serializers.
 *
 * The rationale is that it's easier to define the structure with types rather than by building json objects.
 *
 * Usage:
 * {{{
 *   /** A type used in eclair */
 *   case class Foo(a: String, b: Int, c: ByteVector32)
 *
 *   /** Special purpose type used only for serialization */
 *   private[json] case class FooJson(a: String, c: ByteVector32)
 *   object FooSerializer extends ConvertClassSerializer[Foo]({ foo: Foo =>
 *     FooJson(foo.a, foo.c)
 * }}}
 *
 */
class ConvertClassSerializer[T: Manifest](f: T => Any) extends Serializer[Nothing] {

  def deserialize(implicit format: Formats): PartialFunction[(json4s.TypeInfo, JValue), Nothing] = PartialFunction.empty

  def serialize(implicit format: Formats): PartialFunction[Any, JValue] = {
    case o: T => Extraction.decompose(f(o))
  }
}

object ByteVectorSerializer extends MinimalSerializer({
  case x: ByteVector => JString(x.toHex)
})

object ByteVector32Serializer extends MinimalSerializer({
  case x: ByteVector32 => JString(x.toHex)
})

object ByteVector32KeySerializer extends MinimalKeySerializer({
  case x: ByteVector32 => x.toHex
})

object ByteVector32KmpSerializer extends MinimalSerializer({
  case x: fr.acinq.bitcoin.ByteVector32 => JString(x.toHex)
})

object ByteVector64Serializer extends MinimalSerializer({
  case x: ByteVector64 => JString(x.toHex)
})

object UInt64Serializer extends MinimalSerializer({
  case x: UInt64 => JInt(x.toBigInt)
})

// @formatter:off
private case class TimestampJson(iso: String, unix: Long)
object TimestampSecondSerializer extends ConvertClassSerializer[TimestampSecond](ts => TimestampJson(
  iso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(ts.toLong)),
  unix = ts.toLong
))
object TimestampMilliSerializer extends ConvertClassSerializer[TimestampMilli](ts => TimestampJson(
  iso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ts.toLong)),
  unix = ts.toLong / 1000 // we convert to standard unix timestamp with second precision
))
// @formatter:on

object BtcSerializer extends MinimalSerializer({
  case x: Btc => JDecimal(x.toDouble)
})

object SatoshiSerializer extends MinimalSerializer({
  case x: Satoshi => JInt(x.toLong)
})

object MilliSatoshiSerializer extends MinimalSerializer({
  case x: MilliSatoshi => JInt(x.toLong)
})

object CltvExpirySerializer extends MinimalSerializer({
  case x: CltvExpiry => JLong(x.toLong)
})

object CltvExpiryDeltaSerializer extends MinimalSerializer({
  case x: CltvExpiryDelta => JInt(x.toInt)
})

object FeeratePerKwSerializer extends MinimalSerializer({
  case x: FeeratePerKw => JLong(x.toLong)
})

object ShortChannelIdSerializer extends MinimalSerializer({
  case x: ShortChannelId => JString(x.toString)
})

object ChannelIdentifierSerializer extends MinimalKeySerializer({
  case Left(x: ByteVector32) => x.toHex
  case Right(x: ShortChannelId) => x.toString
})

object ChannelStateSerializer extends MinimalSerializer({
  case x: ChannelState => JString(x.toString)
})

object ShaChainSerializer extends MinimalSerializer({
  case _: ShaChain => JNull
})

object PublicKeySerializer extends MinimalSerializer({
  case x: PublicKey => JString(x.toString())
})

object PrivateKeySerializer extends MinimalSerializer({
  case _: PrivateKey => JString("XXX")
})

object FeatureKeySerializer extends MinimalKeySerializer({ case f: Feature => f.rfcName })

object FeatureSupportSerializer extends MinimalSerializer({ case s: FeatureSupport => JString(s.toString) })

object UnknownFeatureSerializer extends MinimalSerializer({ case f: UnknownFeature => JInt(f.bitIndex) })

object ChannelConfigSerializer extends MinimalSerializer({
  case x: ChannelConfig => JArray(x.options.toList.map(o => JString(o.name)))
})

object ChannelFeaturesSerializer extends MinimalSerializer({
  case channelFeatures: ChannelFeatures => JArray(channelFeatures.features.map(f => JString(f.rfcName)).toList)
})

object ChannelOpenResponseSerializer extends MinimalSerializer({
  case x: ChannelOpenResponse => JString(x.toString)
})

object CommandResponseSerializer extends MinimalSerializer({
  case RES_SUCCESS(_: CloseCommand, channelId) => JString(s"closed channel $channelId")
  case RES_SUCCESS(_, _) => JString("ok")
  case RES_FAILURE(_: Command, ex: Throwable) => JString(ex.getMessage)
})

object TransactionSerializer extends MinimalSerializer({
  case x: Transaction => JObject(List(
    JField("txid", JString(x.txid.toHex)),
    JField("tx", JString(x.toString()))
  ))
})

object KeyPathSerializer extends MinimalSerializer({
  case x: KeyPath => JObject(JField("path", JArray(x.path.map(x => JLong(x)).toList)))
})

object TransactionWithInputInfoSerializer extends MinimalSerializer({
  case x: HtlcSuccessTx => JObject(List(
    JField("txid", JString(x.tx.txid.toHex)),
    JField("tx", JString(x.tx.toString())),
    JField("paymentHash", JString(x.paymentHash.toString())),
    JField("htlcId", JLong(x.htlcId)),
    JField("confirmBeforeBlock", JLong(x.confirmBefore.toLong))
  ))
  case x: HtlcTimeoutTx => JObject(List(
    JField("txid", JString(x.tx.txid.toHex)),
    JField("tx", JString(x.tx.toString())),
    JField("htlcId", JLong(x.htlcId)),
    JField("confirmBeforeBlock", JLong(x.confirmBefore.toLong))
  ))
  case x: ClaimHtlcSuccessTx => JObject(List(
    JField("txid", JString(x.tx.txid.toHex)),
    JField("tx", JString(x.tx.toString())),
    JField("paymentHash", JString(x.paymentHash.toString())),
    JField("htlcId", JLong(x.htlcId)),
    JField("confirmBeforeBlock", JLong(x.confirmBefore.toLong))
  ))
  case x: ClaimHtlcTx => JObject(List(
    JField("txid", JString(x.tx.txid.toHex)),
    JField("tx", JString(x.tx.toString())),
    JField("htlcId", JLong(x.htlcId)),
    JField("confirmBeforeBlock", JLong(x.confirmBefore.toLong))
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
  case x: ReplaceableTransactionWithInputInfo => JObject(List(
    JField("txid", JString(x.tx.txid.toHex)),
    JField("tx", JString(x.tx.toString())),
    JField("confirmBeforeBlock", JLong(x.confirmBefore.toLong))
  ))
  case x: TransactionWithInputInfo => JObject(List(
    JField("txid", JString(x.tx.txid.toHex)),
    JField("tx", JString(x.tx.toString()))
  ))
})

object InetSocketAddressSerializer extends MinimalSerializer({
  case address: InetSocketAddress => JString(HostAndPort.fromParts(address.getHostString, address.getPort).toString)
})

object OutPointSerializer extends MinimalSerializer({
  case x: OutPoint => JString(s"${x.txid}:${x.index}")
})

object OutPointKeySerializer extends MinimalKeySerializer({
  case x: OutPoint => s"${x.txid}:${x.index}"
})

// @formatter:off
private case class InputInfoJson(outPoint: OutPoint, amountSatoshis: Satoshi)
object InputInfoSerializer extends ConvertClassSerializer[InputInfo](i => InputInfoJson(i.outPoint, i.txOut.amount))
// @formatter:on

object ColorSerializer extends MinimalSerializer({
  case c: Color => JString(c.toString)
})

// @formatter:off
private case class ChannelHopJson(nodeId: PublicKey, nextNodeId: PublicKey, source: ChannelRelayParams)
private case class RouteFullJson(amount: MilliSatoshi, hops: Seq[ChannelHopJson])
object RouteFullSerializer extends ConvertClassSerializer[Route](route => RouteFullJson(route.amount, route.hops.map(h => ChannelHopJson(h.nodeId, h.nextNodeId, h.params))))

private case class RouteNodeIdsJson(amount: MilliSatoshi, nodeIds: Seq[PublicKey])
object RouteNodeIdsSerializer extends ConvertClassSerializer[Route](route => {
  val nodeIds = route.hops match {
    case rest :+ last => rest.map(_.nodeId) :+ last.nodeId :+ last.nextNodeId
    case Nil => Nil
  }
  RouteNodeIdsJson(route.amount, nodeIds)
})

private case class RouteShortChannelIdsJson(amount: MilliSatoshi, shortChannelIds: Seq[ShortChannelId])
object RouteShortChannelIdsSerializer extends ConvertClassSerializer[Route](route => RouteShortChannelIdsJson(route.amount, route.hops.map(_.shortChannelId)))
// @formatter:on

// @formatter:off
private case class PaymentFailureSummaryJson(amount: MilliSatoshi, route: Seq[PublicKey], message: String)
private case class PaymentFailedSummaryJson(paymentHash: ByteVector32, destination: PublicKey, totalAmount: MilliSatoshi, pathFindingExperiment: String, failures: Seq[PaymentFailureSummaryJson])
object PaymentFailedSummarySerializer extends ConvertClassSerializer[PaymentFailedSummary](p => PaymentFailedSummaryJson(
  p.cfg.paymentHash,
  p.cfg.recipientNodeId,
  p.cfg.recipientAmount,
  p.pathFindingExperiment,
  p.paymentFailed.failures.map(f => {
    val route = f.route.map(_.nodeId) ++ f.route.lastOption.map(_.nextNodeId)
    val message = f match {
      case LocalFailure(_, _, t) => t.getMessage
      case RemoteFailure(_, _, Sphinx.DecryptedFailurePacket(origin, failureMessage)) => s"$origin returned: ${failureMessage.message}"
      case _: UnreadableRemoteFailure => "unreadable remote failure"
    }
    PaymentFailureSummaryJson(f.amount, route, message)
  })
))
// @formatter:on

object ThrowableSerializer extends MinimalSerializer({
  case t: Throwable if t.getMessage != null => JString(t.getMessage)
  case t: Throwable => JString(t.getClass.getSimpleName)
})

object FailureMessageSerializer extends MinimalSerializer({
  case m: FailureMessage => JString(m.message)
})

object FailureTypeSerializer extends MinimalSerializer({
  case ft: FailureType => JString(ft.toString)
})

object NodeAddressSerializer extends MinimalSerializer({
  case n: NodeAddress => JString(n.toString)
})

// @formatter:off
private case class DirectedHtlcJson(direction: String, add: UpdateAddHtlc)
object DirectedHtlcSerializer extends ConvertClassSerializer[DirectedHtlc](h => DirectedHtlcJson(direction = h.direction, add = h.add))
// @formatter:on

object InvoiceSerializer extends MinimalSerializer({
  case p: Bolt11Invoice =>
    val expiry = p.tags
      .collectFirst { case expiry: Bolt11Invoice.Expiry => expiry.toLong } // NB: we look at fields directly because the value has a spec-defined default
      .map(ex => JField("expiry", JLong(ex))).toSeq
    val minFinalCltvExpiry = p.tags
      .collectFirst { case cltvExpiry: Bolt11Invoice.MinFinalCltvExpiry => cltvExpiry.toCltvExpiryDelta } // NB: we look at fields directly because the value has a spec-defined default
      .map(mfce => JField("minFinalCltvExpiry", JInt(mfce.toInt))).toSeq
    val amount = p.amount_opt.map(msat => JField("amount", JLong(msat.toLong))).toSeq
    val features = JField("features", Extraction.decompose(p.features)(
      DefaultFormats +
        FeatureKeySerializer +
        FeatureSupportSerializer +
        UnknownFeatureSerializer
    ))
    val paymentMetadata = p.paymentMetadata.map(m => JField("paymentMetadata", JString(m.toHex))).toSeq
    val routingInfo = JField("routingInfo", Extraction.decompose(p.routingInfo)(
      DefaultFormats +
        ByteVector32Serializer +
        ByteVectorSerializer +
        PublicKeySerializer +
        ShortChannelIdSerializer +
        MilliSatoshiSerializer +
        CltvExpiryDeltaSerializer
    ))
    val fieldList = List(
      JField("prefix", JString(p.prefix)),
      JField("timestamp", JLong(p.createdAt.toLong)),
      JField("nodeId", JString(p.nodeId.toString())),
      JField("serialized", JString(p.toString)),
      p.description.fold(string => JField("description", JString(string)), hash => JField("descriptionHash", JString(hash.toHex))),
      JField("paymentHash", JString(p.paymentHash.toString()))) ++
      paymentMetadata ++
      expiry ++
      minFinalCltvExpiry ++
      amount :+
      features :+
      routingInfo

    JObject(fieldList)
})

object JavaUUIDSerializer extends MinimalSerializer({
  case id: UUID => JString(id.toString)
})

object ChannelEventSerializer extends MinimalSerializer({
  case e: ChannelCreated => JObject(
    JField("type", JString("channel-opened")),
    JField("remoteNodeId", JString(e.remoteNodeId.toString())),
    JField("isInitiator", JBool(e.isInitiator)),
    JField("temporaryChannelId", JString(e.temporaryChannelId.toHex)),
    JField("commitTxFeeratePerKw", JLong(e.commitTxFeerate.toLong)),
    JField("fundingTxFeeratePerKw", e.fundingTxFeerate.map(f => JLong(f.toLong)).getOrElse(JNothing))
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

object OriginSerializer extends MinimalSerializer({
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

// @formatter:off
private case class GlobalBalanceJson(total: Btc, onChain: CorrectedOnChainBalance, offChain: OffChainBalance)
object GlobalBalanceSerializer extends ConvertClassSerializer[GlobalBalance](b => GlobalBalanceJson(b.total, b.onChain, b.offChain))

private case class PeerInfoJson(nodeId: PublicKey, state: String, address: Option[String], channels: Int)
object PeerInfoSerializer extends ConvertClassSerializer[Peer.PeerInfo](peerInfo => PeerInfoJson(peerInfo.nodeId, peerInfo.state.toString, peerInfo.address.map(_.toString), peerInfo.channels))

private[json] case class MessageReceivedJson(pathId: Option[ByteVector], encodedReplyPath: Option[String], replyPath: Option[BlindedRoute], unknownTlvs: Map[String, ByteVector])
object OnionMessageReceivedSerializer extends ConvertClassSerializer[OnionMessages.ReceiveMessage](m => MessageReceivedJson(m.pathId, m.finalPayload.replyPath.map(route => blindedRouteCodec.encode(route.blindedRoute).require.bytes.toHex), m.finalPayload.replyPath.map(_.blindedRoute), m.finalPayload.records.unknown.map(tlv => tlv.tag.toString -> tlv.value).toMap))
// @formatter:on

case class CustomTypeHints(custom: Map[Class[_], String]) extends TypeHints {
  val reverse: Map[String, Class[_]] = custom.map(_.swap)

  override def typeHintFieldName: String = "type"

  override val hints: List[Class[_]] = custom.keys.toList

  override def hintFor(clazz: Class[_]): Option[String] = custom.get(clazz)

  override def classFor(hint: String, parent: Class[_]): Option[Class[_]] = reverse.get(hint)
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
    classOf[PaymentFailed] -> "payment-failed",
  ))

  val onionMessageEvent: CustomTypeHints = CustomTypeHints(Map(
    classOf[MessageReceivedJson] -> "onion-message-received"
  ))

  val channelSources: CustomTypeHints = CustomTypeHints(Map(
    classOf[ChannelRelayParams.FromAnnouncement] -> "announcement",
    classOf[ChannelRelayParams.FromHint] -> "hint"
  ))

  val channelStates: ShortTypeHints = ShortTypeHints(
    List(
      classOf[Nothing],
      classOf[DATA_WAIT_FOR_OPEN_CHANNEL],
      classOf[DATA_WAIT_FOR_ACCEPT_CHANNEL],
      classOf[DATA_WAIT_FOR_FUNDING_INTERNAL],
      classOf[DATA_WAIT_FOR_FUNDING_CREATED],
      classOf[DATA_WAIT_FOR_FUNDING_SIGNED],
      classOf[DATA_WAIT_FOR_CHANNEL_READY],
      classOf[DATA_WAIT_FOR_FUNDING_CONFIRMED],
      classOf[DATA_NORMAL],
      classOf[DATA_SHUTDOWN],
      classOf[DATA_NEGOTIATING],
      classOf[DATA_CLOSING],
      classOf[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]
    ), typeHintFieldName = "type")
}

object JsonSerializers {

  implicit val serialization: Serialization.type = jackson.Serialization

  implicit val formats: Formats = org.json4s.DefaultFormats +
    CustomTypeHints.incomingPaymentStatus +
    CustomTypeHints.outgoingPaymentStatus +
    CustomTypeHints.paymentEvent +
    CustomTypeHints.onionMessageEvent +
    CustomTypeHints.channelSources +
    CustomTypeHints.channelStates +
    ByteVectorSerializer +
    ByteVector32Serializer +
    ByteVector64Serializer +
    ChannelEventSerializer +
    UInt64Serializer +
    TimestampSecondSerializer +
    TimestampMilliSerializer +
    BtcSerializer +
    SatoshiSerializer +
    MilliSatoshiSerializer +
    CltvExpirySerializer +
    CltvExpiryDeltaSerializer +
    FeeratePerKwSerializer +
    ShortChannelIdSerializer +
    ChannelIdentifierSerializer +
    ChannelStateSerializer +
    ShaChainSerializer +
    PublicKeySerializer +
    PrivateKeySerializer +
    TransactionSerializer +
    TransactionWithInputInfoSerializer +
    KeyPathSerializer +
    InetSocketAddressSerializer +
    OutPointSerializer +
    OutPointKeySerializer +
    FeatureKeySerializer +
    FeatureSupportSerializer +
    UnknownFeatureSerializer +
    ChannelConfigSerializer +
    ChannelFeaturesSerializer +
    ChannelOpenResponseSerializer +
    CommandResponseSerializer +
    InputInfoSerializer +
    ColorSerializer +
    ThrowableSerializer +
    FailureMessageSerializer +
    FailureTypeSerializer +
    NodeAddressSerializer +
    DirectedHtlcSerializer +
    InvoiceSerializer +
    JavaUUIDSerializer +
    OriginSerializer +
    ByteVector32KeySerializer +
    GlobalBalanceSerializer +
    PeerInfoSerializer +
    PaymentFailedSummarySerializer +
    OnionMessageReceivedSerializer

}
