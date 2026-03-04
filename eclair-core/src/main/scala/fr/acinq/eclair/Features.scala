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

  override def toString: String = rfcName
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
 * Features that can be included in the [[fr.acinq.eclair.wire.protocol.ChannelTlv.ChannelTypeTlv]].
 */
trait ChannelTypeFeature extends InitFeature
// @formatter:on

// @formatter:off
sealed trait FeatureCompatibilityResult {
  def areCompatible: Boolean = this == FeatureCompatibilityResult.Compatible
  def errorHints: Set[String] = this match {
    case FeatureCompatibilityResult.Compatible => Set.empty
    case r: FeatureCompatibilityResult.NotCompatible => r.hints
  }
}
object FeatureCompatibilityResult {
  case object Compatible extends FeatureCompatibilityResult
  case class NotCompatible(hints: Set[String]) extends FeatureCompatibilityResult
}
// @formatter:on

/** NB: features are encoded with the most significant bits first. */
case class EncodedFeatures(bin: ByteVector) {
  val isEmpty: Boolean = bin.isEmpty

  def hasFeature(feature: Feature, support: Option[FeatureSupport] = None): Boolean = {
    support match {
      case Some(support) => hasFeatureBit(feature.supportBit(support))
      case None => hasFeature(feature, Some(FeatureSupport.Optional)) || hasFeature(feature, Some(FeatureSupport.Mandatory))
    }
  }

  def hasFeatureBit(bitIndex: Long): Boolean = {
    if (bitIndex < bin.size * 8) {
      val offset = bitIndex % 8
      (bin.get(bin.size - 1 - (bitIndex / 8)) & (0x01 << offset.toInt)) != 0
    } else {
      false
    }
  }
}

object EncodedFeatures {
  def fromFeatureBits(featureBits: Set[Int]): EncodedFeatures = {
    if (featureBits.isEmpty) {
      EncodedFeatures(ByteVector.empty)
    } else {
      // Note that we pad to bytes before setting feature bits (we use a byte encoding on the wire).
      val byteSize = if ((featureBits.max + 1) % 8 == 0) {
        featureBits.max + 1
      } else {
        featureBits.max + 1 + 8 - ((featureBits.max + 1) % 8)
      }
      var buf = BitVector.fill(byteSize)(high = false)
      // We encode feature bits with the most significant bits first.
      featureBits.foreach { i => buf = buf.set(byteSize - 1 - i) }
      EncodedFeatures(buf.bytes)
    }
  }
}

/**
 * @param activated   known features are parsed from the encoded features: note that this will not contain plugin features
 *                    sent by a remote node: use [[hasFeature]] to test whether a feature is supported or not instead of
 *                    parsing the map directly.
 * @param encoded_opt only provided when reading encoded features, contains all feature bits.
 */
case class Features[T <: Feature](activated: Map[T, FeatureSupport], encoded_opt: Option[EncodedFeatures] = None) {

  def isEmpty: Boolean = activated.isEmpty && encoded_opt.forall(_.isEmpty)

  def hasFeature(feature: T, support: Option[FeatureSupport] = None): Boolean = support match {
    case Some(s) => activated.get(feature).contains(s) || encoded_opt.exists(_.hasFeature(feature, support))
    case None => activated.contains(feature) || encoded_opt.exists(_.hasFeature(feature))
  }

  /** NB: this method is not reflexive, see [[Features.areCompatible]] if you want symmetric validation. */
  def testSupported(remoteFeatures: Features[T]): FeatureCompatibilityResult = {
    // We verify that we activated every mandatory feature they require.
    val incompatibleFeature_opt = remoteFeatures.activated.find {
      case (_, Optional) => false
      case (feature, Mandatory) => !hasFeature(feature)
    }.map(_._1.rfcName)
    // We also verify encoded features, which may contain plugin features and unknown features.
    val incompatibleEncodedFeature_opt = remoteFeatures.encoded_opt match {
      case Some(encoded) =>
        val activatedMandatoryFeatureBits = activated.keySet.map(_.mandatory.toLong)
        // We only need to check even feature bits (it's ok to be odd), so we step by 2.
        (0L until (encoded.bin.size * 8) by 2).find(i => {
          if (encoded.hasFeatureBit(i)) {
            // They have set a mandatory feature bit: we must support it as well. Note that if this is a plugin feature,
            // it may be in the encoded features but not the activated ones if the current object is for remote features.
            !activatedMandatoryFeatureBits.contains(i) && !encoded_opt.exists(_.hasFeatureBit(i)) && !encoded_opt.exists(_.hasFeatureBit(i + 1))
          } else {
            false
          }
        }).map(i => s"unknown_$i")
      case None => None
    }
    if (incompatibleFeature_opt.isEmpty && incompatibleEncodedFeature_opt.isEmpty) {
      FeatureCompatibilityResult.Compatible
    } else {
      FeatureCompatibilityResult.NotCompatible(incompatibleFeature_opt.toSet ++ incompatibleEncodedFeature_opt.toSet)
    }
  }

  def areSupported(remoteFeatures: Features[T]): Boolean = testSupported(remoteFeatures).areCompatible

  def initFeatures(): Features[InitFeature] = Features(activated.collect { case (f: InitFeature, s) => (f, s) }, encoded_opt)

  def nodeAnnouncementFeatures(): Features[NodeFeature] = Features(activated.collect { case (f: NodeFeature, s) => (f, s) }, encoded_opt)

  def invoiceFeatures(): Features[InvoiceFeature] = Features(activated.collect { case (f: InvoiceFeature, s) => (f, s) }, encoded_opt)

  def bolt11Features(): Features[Bolt11Feature] = Features(activated.collect { case (f: Bolt11Feature, s) => (f, s) }, encoded_opt)

  def bolt12Features(): Features[Bolt12Feature] = Features(activated.collect { case (f: Bolt12Feature, s) => (f, s) }, encoded_opt)

  def unscoped(): Features[Feature] = Features[Feature](activated.collect { case (f, s) => (f: Feature, s) }, encoded_opt)

  def add(feature: T, support: FeatureSupport): Features[T] = copy(activated = activated + (feature -> support))

  def remove(feature: T): Features[T] = copy(activated = activated - feature)

  def toByteVector: ByteVector = {
    val activatedFeatureBytes = EncodedFeatures.fromFeatureBits(activated.map { case (feature, support) => feature.supportBit(support) }.toSet).bin
    encoded_opt.map(_.bin) match {
      case Some(encoded) =>
        // We combine both sources of feature bits, and we minimally-encode by removing leading zeroes.
        val maxSize = activatedFeatureBytes.size.max(encoded.size)
        (activatedFeatureBytes.padLeft(maxSize) | encoded.padLeft(maxSize)).dropWhile(_ == 0)
      case None => activatedFeatureBytes
    }
  }

  override def toString: String = {
    activated
      .map { case (feature, support) => feature.rfcName + ":" + support }
      .mkString(",")
  }

  override def equals(obj: Any): Boolean = obj match {
    case features: Features[_] => this.toByteVector.equals(features.toByteVector)
    case _ => false
  }

  override def hashCode(): Int = toByteVector.hashCode
}

object Features {

  def empty[T <: Feature]: Features[T] = Features[T](Map.empty[T, FeatureSupport])

  def apply[T <: Feature](features: (T, FeatureSupport)*): Features[T] = Features[T](Map.from(features))

  def apply(bits: BitVector): Features[Feature] = {
    if (bits.isEmpty) {
      Features.empty
    } else {
      // When converting from BitVector to ByteVector, scodec pads right instead of left, so we make sure we pad to bytes *before* setting feature bits.
      val padded = if (bits.size % 8 == 0) {
        bits
      } else {
        bits.padLeft(bits.size + (8 - (bits.size % 8)))
      }
      Features(padded.bytes)
    }
  }

  def apply(bytes: ByteVector): Features[Feature] = {
    if (bytes.isEmpty) {
      Features.empty
    } else {
      // We extract all official features we support.
      val encoded = EncodedFeatures(bytes)
      val activated = knownFeatures.flatMap {
        case f if encoded.hasFeatureBit(f.optional) => Some(f -> FeatureSupport.Optional)
        case f if encoded.hasFeatureBit(f.mandatory) => Some(f -> FeatureSupport.Mandatory)
        case _ => None
      }
      Features[Feature](
        activated = activated.toMap,
        // Note that we keep all feature bits to allow checking whether plugin features are activated.
        encoded_opt = Some(encoded),
      )
    }
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

  case object Wumbo extends Feature with InitFeature with NodeFeature {
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

  case object RouteBlinding extends Feature with InitFeature with NodeFeature {
    val rfcName = "option_route_blinding"
    val mandatory = 24
  }

  case object ShutdownAnySegwit extends Feature with InitFeature with NodeFeature {
    val rfcName = "option_shutdown_anysegwit"
    val mandatory = 26
  }

  // Note that this is a permanent channel feature because it permanently affects the channel reserve, which is set at 1%.
  case object DualFunding extends Feature with InitFeature with NodeFeature with PermanentChannelFeature {
    val rfcName = "option_dual_fund"
    val mandatory = 28
  }

  case object Quiescence extends Feature with InitFeature with NodeFeature {
    val rfcName = "option_quiesce"
    val mandatory = 34
  }

  case object AttributionData extends Feature with InitFeature with NodeFeature with Bolt11Feature {
    val rfcName = "option_attribution_data"
    val mandatory = 36
  }

  case object OnionMessages extends Feature with InitFeature with NodeFeature {
    val rfcName = "option_onion_messages"
    val mandatory = 38
  }

  case object ProvideStorage extends Feature with InitFeature with NodeFeature {
    val rfcName = "option_provide_storage"
    val mandatory = 42
  }

  case object ChannelType extends Feature with InitFeature with NodeFeature {
    val rfcName = "option_channel_type"
    val mandatory = 44
  }

  case object ScidAlias extends Feature with InitFeature with NodeFeature with ChannelTypeFeature with PermanentChannelFeature {
    val rfcName = "option_scid_alias"
    val mandatory = 46
  }

  case object PaymentMetadata extends Feature with Bolt11Feature {
    val rfcName = "option_payment_metadata"
    val mandatory = 48
  }

  case object ZeroConf extends Feature with InitFeature with NodeFeature with ChannelTypeFeature with PermanentChannelFeature {
    val rfcName = "option_zeroconf"
    val mandatory = 50
  }

  case object KeySend extends Feature with NodeFeature {
    val rfcName = "keysend"
    val mandatory = 54
  }

  case object SimpleClose extends Feature with InitFeature with NodeFeature {
    val rfcName = "option_simple_close"
    val mandatory = 60
  }

  case object Splicing extends Feature with InitFeature with NodeFeature {
    val rfcName = "option_splice"
    val mandatory = 62
  }

  case object PhoenixZeroReserve extends Feature with InitFeature with ChannelTypeFeature with PermanentChannelFeature {
    val rfcName = "phoenix_zero_reserve"
    val mandatory = 128
  }

  /** This feature bit indicates that the node is a mobile wallet that can be woken up via push notifications. */
  case object WakeUpNotificationClient extends Feature with InitFeature {
    val rfcName = "wake_up_notification_client"
    val mandatory = 132
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

  case object SimpleTaprootChannelsPhoenix extends Feature with InitFeature with NodeFeature with ChannelTypeFeature {
    val rfcName = "option_simple_taproot_phoenix"
    val mandatory = 564
  }

  case object SimpleTaprootChannelsStaging extends Feature with InitFeature with NodeFeature with ChannelTypeFeature {
    val rfcName = "option_simple_taproot_staging"
    val mandatory = 180
  }

  /**
   * Activate this feature to provide on-the-fly funding to remote nodes, as specified in bLIP 36: https://github.com/lightning/blips/blob/master/blip-0036.md.
   * TODO: add NodeFeature once bLIP is merged.
   */
  case object OnTheFlyFunding extends Feature with InitFeature {
    val rfcName = "on_the_fly_funding"
    val mandatory = 560
  }

  // TODO:
  //  - add NodeFeature once stable
  //  - add link to bLIP
  case object FundingFeeCredit extends Feature with InitFeature {
    val rfcName = "funding_fee_credit"
    val mandatory = 562
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
    Quiescence,
    AttributionData,
    OnionMessages,
    ProvideStorage,
    ChannelType,
    ScidAlias,
    PaymentMetadata,
    ZeroConf,
    KeySend,
    SimpleClose,
    Splicing,
    SimpleTaprootChannelsPhoenix,
    SimpleTaprootChannelsStaging,
    WakeUpNotificationClient,
    TrampolinePaymentPrototype,
    AsyncPaymentPrototype,
    OnTheFlyFunding,
    FundingFeeCredit,
    PhoenixZeroReserve
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
    SimpleClose -> (ShutdownAnySegwit :: Nil),
    SimpleTaprootChannelsPhoenix -> (ChannelType :: SimpleClose :: Nil),
    AsyncPaymentPrototype -> (TrampolinePaymentPrototype :: Nil),
    FundingFeeCredit -> (OnTheFlyFunding :: Nil)
  )

  case class FeatureException(message: String) extends IllegalArgumentException(message)

  def validateFeatureGraph[T <: Feature](features: Features[T]): Option[FeatureException] = featuresDependency.collectFirst {
    case (feature, dependencies) if features.unscoped().hasFeature(feature) && dependencies.exists(d => !features.unscoped().hasFeature(d)) =>
      FeatureException(s"$feature is set but is missing a dependency (${dependencies.filter(d => !features.unscoped().hasFeature(d)).mkString(" and ")})")
  }

  def testCompatible[T <: Feature](ours: Features[T], theirs: Features[T]): FeatureCompatibilityResult = (ours.testSupported(theirs), theirs.testSupported(ours)) match {
    case (FeatureCompatibilityResult.Compatible, FeatureCompatibilityResult.Compatible) => FeatureCompatibilityResult.Compatible
    case (r1, r2) => FeatureCompatibilityResult.NotCompatible(r1.errorHints ++ r2.errorHints)
  }

  /** Returns true if both feature sets are compatible. */
  def areCompatible[T <: Feature](ours: Features[T], theirs: Features[T]): Boolean = testCompatible(ours, theirs).areCompatible

  /** returns true if both have at least optional support */
  def canUseFeature[T <: Feature](localFeatures: Features[T], remoteFeatures: Features[T], feature: T): Boolean = {
    localFeatures.hasFeature(feature) && remoteFeatures.hasFeature(feature)
  }

}
