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

package fr.acinq.eclair

import com.typesafe.config.Config
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import scodec.bits.{BitVector, ByteVector}

import scala.jdk.CollectionConverters.CollectionHasAsScala

/**
 * Created by PM on 13/02/2017.
 */

sealed trait FeatureSupport

// @formatter:off
object FeatureSupport {
  case object Mandatory extends FeatureSupport { override def toString: String = "mandatory" }
  case object Optional extends FeatureSupport { override def toString: String = "optional" }
}

/** Not a sealed trait, so it can be extended by plugins. */
trait Feature {

  def rfcName: String
  def mandatory: Int
  def optional: Int = mandatory + 1

  def supportBit(support: FeatureSupport): Int = support match {
    case Mandatory => mandatory
    case Optional => optional
  }

  override def toString = rfcName
}

/** Feature scope as defined in Bolt 9. */
/** Feature that should be advertised in init messages. */
trait InitFeature extends Feature
/** Feature that should be advertised in node announcements. */
trait NodeFeature extends Feature
/** Feature that should be advertised in invoices. */
trait InvoiceFeature extends Feature
/** Feature that should be advertised in Bolt 11 invoices. */
trait Bolt11Feature extends InvoiceFeature
/** Feature that should be advertised in Bolt 12 invoices. */
trait Bolt12Feature extends InvoiceFeature

/**
 * Feature negotiated when opening a channel that will apply for all of the channel's lifetime.
 * This doesn't include features that can be safely activated/deactivated without impacting the channel's operation such
 * as option_dataloss_protect or option_shutdown_anysegwit.
 */
trait PermanentChannelFeature extends InitFeature // <- not in the spec
/**
 * Permanent channel feature negotiated in the channel type. Those features take precedence over permanent channel
 * features negotiated in init messages. For example, if the channel type is option_static_remotekey, then even if
 * the option_anchor_outputs feature is supported by both peers, it won't apply to the channel.
 */
trait ChannelTypeFeature extends PermanentChannelFeature
// @formatter:on

case class UnknownFeature(bitIndex: Int)

case class Features[T <: Feature](activated: Map[T, FeatureSupport], unknown: Set[UnknownFeature] = Set.empty) {

  def isEmpty: Boolean = activated.isEmpty && unknown.isEmpty

  def hasFeature(feature: T, support: Option[FeatureSupport] = None): Boolean = support match {
    case Some(s) => activated.get(feature).contains(s)
    case None => activated.contains(feature)
  }

  def hasPluginFeature(feature: UnknownFeature): Boolean = unknown.contains(feature)

  /** NB: this method is not reflexive, see [[Features.areCompatible]] if you want symmetric validation. */
  def areSupported(remoteFeatures: Features[T]): Boolean = {
    // we allow unknown odd features (it's ok to be odd)
    val unknownFeaturesOk = remoteFeatures.unknown.forall(_.bitIndex % 2 == 1)
    // we verify that we activated every mandatory feature they require
    val knownFeaturesOk = remoteFeatures.activated.forall {
      case (_, Optional) => true
      case (feature, Mandatory) => hasFeature(feature)
    }
    unknownFeaturesOk && knownFeaturesOk
  }

  def initFeatures(): Features[InitFeature] = Features(activated.collect { case (f: InitFeature, s) => (f, s) }, unknown)

  def nodeAnnouncementFeatures(): Features[NodeFeature] = Features(activated.collect { case (f: NodeFeature, s) => (f, s) }, unknown)

  def invoiceFeatures(): Features[InvoiceFeature] = Features(activated.collect { case (f: InvoiceFeature, s) => (f, s) }, unknown)

  def bolt11Features(): Features[Bolt11Feature] = Features(activated.collect { case (f: Bolt11Feature, s) => (f, s) }, unknown)

  def bolt12Features(): Features[Bolt12Feature] = Features(activated.collect { case (f: Bolt12Feature, s) => (f, s) }, unknown)

  def unscoped(): Features[Feature] = Features[Feature](activated.collect { case (f, s) => (f: Feature, s) }, unknown)

  def add(feature: T, support: FeatureSupport): Features[T] = copy(activated = activated + (feature -> support))

  def remove(feature: T): Features[T] = copy(activated = activated - feature)

  def toByteVector: ByteVector = {
    val activatedFeatureBytes = toByteVectorFromIndex(activated.map { case (feature, support) => feature.supportBit(support) }.toSet)
    val unknownFeatureBytes = toByteVectorFromIndex(unknown.map(_.bitIndex))
    val maxSize = activatedFeatureBytes.size.max(unknownFeatureBytes.size)
    activatedFeatureBytes.padLeft(maxSize) | unknownFeatureBytes.padLeft(maxSize)
  }

  private def toByteVectorFromIndex(indexes: Set[Int]): ByteVector = {
    if (indexes.isEmpty) return ByteVector.empty
    // When converting from BitVector to ByteVector, scodec pads right instead of left, so we make sure we pad to bytes *before* setting feature bits.
    var buf = BitVector.fill(indexes.max + 1)(high = false).bytes.bits
    indexes.foreach { i => buf = buf.set(i) }
    buf.reverse.bytes
  }

  override def toString: String = {
    val a = activated.map { case (feature, support) => feature.rfcName + ":" + support }.mkString(",")
    val u = unknown.map(_.bitIndex).mkString(",")
    s"$a" + (if (unknown.nonEmpty) s" (unknown=$u)" else "")
  }
}

object Features {

  def empty[T <: Feature]: Features[T] = Features[T](Map.empty[T, FeatureSupport])

  def apply[T <: Feature](features: (T, FeatureSupport)*): Features[T] = Features[T](Map.from(features))

  def apply(bytes: ByteVector): Features[Feature] = apply(bytes.bits)

  def apply(bits: BitVector): Features[Feature] = {
    val all = bits.toIndexedSeq.reverse.zipWithIndex.collect {
      case (true, idx) if knownFeatures.exists(_.optional == idx) => Right((knownFeatures.find(_.optional == idx).get, Optional))
      case (true, idx) if knownFeatures.exists(_.mandatory == idx) => Right((knownFeatures.find(_.mandatory == idx).get, Mandatory))
      case (true, idx) => Left(UnknownFeature(idx))
    }
    Features[Feature](
      activated = all.collect { case Right((feature, support)) => feature -> support }.toMap,
      unknown = all.collect { case Left(inf) => inf }.toSet
    )
  }

  def fromConfiguration[T <: Feature](config: Config, validFeatures: Set[T], baseFeatures: Features[T]): Features[T] = Features[T](
    config.root().entrySet().asScala.foldLeft(baseFeatures.activated) {
      case (current, entry) =>
        val featureName = entry.getKey
        val feature: T = validFeatures.find(_.rfcName == featureName).getOrElse(throw new IllegalArgumentException(s"Invalid feature name ($featureName)"))
        config.getString(featureName) match {
          case support if support == Mandatory.toString => current + (feature -> Mandatory)
          case support if support == Optional.toString => current + (feature -> Optional)
          case support if support == "disabled" => current - feature
          case wrongSupport => throw new IllegalArgumentException(s"Wrong support specified ($wrongSupport)")
        }
    })

  def fromConfiguration(config: Config): Features[Feature] = fromConfiguration[Feature](config, knownFeatures, Features.empty)

  case object DataLossProtect extends Feature with InitFeature with NodeFeature {
    val rfcName = "option_data_loss_protect"
    val mandatory = 0
  }

  case object InitialRoutingSync extends Feature with InitFeature {
    val rfcName = "initial_routing_sync"
    // reserved but not used as per lightningnetwork/lightning-rfc/pull/178
    val mandatory = 2
  }

  case object UpfrontShutdownScript extends Feature with InitFeature with NodeFeature with PermanentChannelFeature {
    val rfcName = "option_upfront_shutdown_script"
    val mandatory = 4
  }

  case object ChannelRangeQueries extends Feature with InitFeature with NodeFeature {
    val rfcName = "gossip_queries"
    val mandatory = 6
  }

  case object VariableLengthOnion extends Feature with InitFeature with NodeFeature with Bolt11Feature {
    val rfcName = "var_onion_optin"
    val mandatory = 8
  }

  case object ChannelRangeQueriesExtended extends Feature with InitFeature with NodeFeature {
    val rfcName = "gossip_queries_ex"
    val mandatory = 10
  }

  case object StaticRemoteKey extends Feature with InitFeature with NodeFeature with ChannelTypeFeature {
    val rfcName = "option_static_remotekey"
    val mandatory = 12
  }

  case object PaymentSecret extends Feature with InitFeature with NodeFeature with Bolt11Feature {
    val rfcName = "payment_secret"
    val mandatory = 14
  }

  case object BasicMultiPartPayment extends Feature with InitFeature with NodeFeature with Bolt11Feature with Bolt12Feature {
    val rfcName = "basic_mpp"
    val mandatory = 16
  }

  case object Wumbo extends Feature with InitFeature with NodeFeature with PermanentChannelFeature {
    val rfcName = "option_support_large_channel"
    val mandatory = 18
  }

  case object AnchorOutputs extends Feature with InitFeature with NodeFeature with ChannelTypeFeature {
    val rfcName = "option_anchor_outputs"
    val mandatory = 20
  }

  case object AnchorOutputsZeroFeeHtlcTx extends Feature with InitFeature with NodeFeature with ChannelTypeFeature {
    val rfcName = "option_anchors_zero_fee_htlc_tx"
    val mandatory = 22
  }

  case object RouteBlinding extends Feature with InitFeature with NodeFeature with Bolt11Feature {
    val rfcName = "option_route_blinding"
    val mandatory = 24
  }

  case object ShutdownAnySegwit extends Feature with InitFeature with NodeFeature {
    val rfcName = "option_shutdown_anysegwit"
    val mandatory = 26
  }

  case object DualFunding extends Feature with InitFeature with NodeFeature with PermanentChannelFeature {
    val rfcName = "option_dual_fund"
    val mandatory = 28
  }

  case object OnionMessages extends Feature with InitFeature with NodeFeature {
    val rfcName = "option_onion_messages"
    val mandatory = 38
  }

  case object ChannelType extends Feature with InitFeature with NodeFeature {
    val rfcName = "option_channel_type"
    val mandatory = 44
  }

  case object ScidAlias extends Feature with InitFeature with NodeFeature with ChannelTypeFeature {
    val rfcName = "option_scid_alias"
    val mandatory = 46
  }

  case object PaymentMetadata extends Feature with Bolt11Feature {
    val rfcName = "option_payment_metadata"
    val mandatory = 48
  }

  case object ZeroConf extends Feature with InitFeature with NodeFeature with ChannelTypeFeature {
    val rfcName = "option_zeroconf"
    val mandatory = 50
  }

  case object KeySend extends Feature with NodeFeature {
    val rfcName = "keysend"
    val mandatory = 54
  }

  // TODO: @t-bast: update feature bits once spec-ed (currently reserved here: https://github.com/lightningnetwork/lightning-rfc/issues/605)
  // We're not advertising these bits yet in our announcements, clients have to assume support.
  // This is why we haven't added them yet to `areSupported`.
  // The version of trampoline enabled by this feature bit does not match the latest spec PR: once the spec is accepted,
  // we will introduce a new version of trampoline that will work in parallel to this legacy one, until we can safely
  // deprecate it.
  case object TrampolinePaymentPrototype extends Feature with InitFeature with NodeFeature with Bolt11Feature {
    val rfcName = "trampoline_payment_prototype"
    val mandatory = 148
  }

  // TODO: @remyers update feature bits once spec-ed (currently reserved here: https://github.com/lightning/bolts/pull/989)
  case object AsyncPaymentPrototype extends Feature with InitFeature with Bolt11Feature {
    val rfcName = "async_payment_prototype"
    val mandatory = 152
  }

  val knownFeatures: Set[Feature] = Set(
    DataLossProtect,
    InitialRoutingSync,
    UpfrontShutdownScript,
    ChannelRangeQueries,
    VariableLengthOnion,
    ChannelRangeQueriesExtended,
    PaymentSecret,
    BasicMultiPartPayment,
    Wumbo,
    StaticRemoteKey,
    AnchorOutputs,
    AnchorOutputsZeroFeeHtlcTx,
    RouteBlinding,
    ShutdownAnySegwit,
    DualFunding,
    OnionMessages,
    ChannelType,
    ScidAlias,
    PaymentMetadata,
    ZeroConf,
    KeySend,
    TrampolinePaymentPrototype,
    AsyncPaymentPrototype
  )

  // Features may depend on other features, as specified in Bolt 9.
  private val featuresDependency = Map(
    ChannelRangeQueriesExtended -> (ChannelRangeQueries :: Nil),
    PaymentSecret -> (VariableLengthOnion :: Nil),
    BasicMultiPartPayment -> (PaymentSecret :: Nil),
    AnchorOutputs -> (StaticRemoteKey :: Nil),
    AnchorOutputsZeroFeeHtlcTx -> (StaticRemoteKey :: Nil),
    RouteBlinding -> (VariableLengthOnion :: Nil),
    TrampolinePaymentPrototype -> (PaymentSecret :: Nil),
    KeySend -> (VariableLengthOnion :: Nil),
    AsyncPaymentPrototype -> (TrampolinePaymentPrototype :: Nil)
  )

  case class FeatureException(message: String) extends IllegalArgumentException(message)

  def validateFeatureGraph[T <: Feature](features: Features[T]): Option[FeatureException] = featuresDependency.collectFirst {
    case (feature, dependencies) if features.unscoped().hasFeature(feature) && dependencies.exists(d => !features.unscoped().hasFeature(d)) =>
      FeatureException(s"$feature is set but is missing a dependency (${dependencies.filter(d => !features.unscoped().hasFeature(d)).mkString(" and ")})")
  }

  /** Returns true if both feature sets are compatible. */
  def areCompatible[T <: Feature](ours: Features[T], theirs: Features[T]): Boolean = ours.areSupported(theirs) && theirs.areSupported(ours)

  /** returns true if both have at least optional support */
  def canUseFeature[T <: Feature](localFeatures: Features[T], remoteFeatures: Features[T], feature: T): Boolean = {
    localFeatures.hasFeature(feature) && remoteFeatures.hasFeature(feature)
  }

}
