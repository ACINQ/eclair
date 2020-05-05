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

/**
 * Created by PM on 13/02/2017.
 */

sealed trait FeatureSupport

// @formatter:off
object FeatureSupport {
  case object Mandatory extends FeatureSupport { override def toString: String = "mandatory" }
  case object Optional extends FeatureSupport { override def toString: String = "optional" }
}
// @formatter:on

sealed trait Feature {
  def rfcName: String

  def mandatory: Int

  def optional: Int = mandatory + 1

  def supportBit(support : FeatureSupport): Int = support match {
    case FeatureSupport.Mandatory => mandatory
    case FeatureSupport.Optional => optional
  }

  override def toString = rfcName
}

case class ActivatedFeature(feature: Feature, support: FeatureSupport)

case class InactiveFeature(bitIndex: Int)

case class Features(activated: Set[ActivatedFeature], inactive: Set[InactiveFeature] = Set.empty) {

  def hasFeature(feature: Feature, support: Option[FeatureSupport] = None): Boolean = support match {
    case Some(s) => activated.contains(ActivatedFeature(feature, s))
    case None => hasFeature(feature, Some(Optional)) || hasFeature(feature, Some(Mandatory))
  }

  def toByteVector: ByteVector = {
    val activatedFeatureBytes = toByteVectorFromInts(activated.map { case ActivatedFeature(f, s) => f.supportBit(s) })
    val inactiveFeatureBytes = toByteVectorFromInts(inactive.map(_.bitIndex))
    val maxSize = activatedFeatureBytes.size.max(inactiveFeatureBytes.size)
    activatedFeatureBytes.padLeft(maxSize) | inactiveFeatureBytes.padLeft(maxSize)
  }

  /** encodes in a byte vector the given bit indexes */
  private def toByteVectorFromInts(ints: Set[Int]): ByteVector = {
    if (ints.isEmpty) return ByteVector.empty
    var buf = BitVector.fill(ints.max + 1)(high = false).bytes.toBitVector
    ints.foreach { i =>
      buf = buf.set(i)
    }
    buf.reverse.bytes
  }
}

object Features {

  def empty = Features(Set.empty[ActivatedFeature])

  def apply(features: Set[ActivatedFeature]): Features = Features(activated = features)

  def apply(bytes: ByteVector): Features = apply(bytes.toBitVector)

  def apply(bits: BitVector): Features = {
    val all = bits.toIndexedSeq.reverse.zipWithIndex.flatMap {
      case (true, idx) if knownFeatures.exists(_.optional == idx) => Some(ActivatedFeature(knownFeatures.find(_.optional == idx).get, Optional))
      case (true, idx) if knownFeatures.exists(_.mandatory == idx) => Some(ActivatedFeature(knownFeatures.find(_.mandatory == idx).get, Mandatory))
      case (true, idx) => Some(InactiveFeature(idx))
      case (false, _) => None
    }

    Features(
      activated = all.collect { case af:ActivatedFeature => af }.toSet,
      inactive = all.collect { case inf: InactiveFeature => inf }.toSet
    )
  }

  /** expects to have a top level config block named "features" */
  def fromConfiguration(config: Config): Features = Features {
    knownFeatures.flatMap { feature =>
      getFeature(config, feature.rfcName) match {
        case Some(support) => Some(ActivatedFeature(feature, support))
        case _ => None
      }
    }
  }

  /** tries to extract the given feature name from the config, if successful returns its feature support */
  def getFeature(config: Config, name: String): Option[FeatureSupport] = {
    if(!config.hasPath(s"features.$name")) None
    else {
      config.getString(s"features.$name") match {
        case support if support == Mandatory.toString => Some(Mandatory)
        case support if support == Optional.toString => Some(Optional)
        case wrongSupport => throw new IllegalArgumentException(s"Wrong support specified ($wrongSupport)")
      }
    }
  }

  case object OptionDataLossProtect extends Feature {
    val rfcName = "option_data_loss_protect"
    val mandatory = 0
  }

  case object InitialRoutingSync extends Feature {
    val rfcName = "initial_routing_sync"
    // reserved but not used as per lightningnetwork/lightning-rfc/pull/178
    val mandatory = 2
  }

  case object ChannelRangeQueries extends Feature {
    val rfcName = "gossip_queries"
    val mandatory = 6
  }

  case object VariableLengthOnion extends Feature {
    val rfcName = "var_onion_optin"
    val mandatory = 8
  }

  case object ChannelRangeQueriesExtended extends Feature {
    val rfcName = "gossip_queries_ex"
    val mandatory = 10
  }

  case object PaymentSecret extends Feature {
    val rfcName = "payment_secret"
    val mandatory = 14
  }

  case object BasicMultiPartPayment extends Feature {
    val rfcName = "basic_mpp"
    val mandatory = 16
  }

  case object Wumbo extends Feature {
    val rfcName = "option_support_large_channel"
    val mandatory = 18
  }

  // TODO: @t-bast: update feature bits once spec-ed (currently reserved here: https://github.com/lightningnetwork/lightning-rfc/issues/605)
  // We're not advertising these bits yet in our announcements, clients have to assume support.
  // This is why we haven't added them yet to `areSupported`.
  case object TrampolinePayment extends Feature {
    val rfcName = "trampoline_payment"
    val mandatory = 50
  }

  // Features may depend on other features, as specified in Bolt 9.
  private val featuresDependency = Map(
    ChannelRangeQueriesExtended -> (ChannelRangeQueries :: Nil),
    // This dependency requirement was added to the spec after the Phoenix release, which means Phoenix users have "invalid"
    // invoices in their payment history. We choose to treat such invoices as valid; this is a harmless spec violation.
    // PaymentSecret -> (VariableLengthOnion :: Nil),
    BasicMultiPartPayment -> (PaymentSecret :: Nil),
    TrampolinePayment -> (PaymentSecret :: Nil)
  )

  case class FeatureException(message: String) extends IllegalArgumentException(message)

  def validateFeatureGraph(features: BitVector): Option[FeatureException] = featuresDependency.collectFirst {
    case (feature, dependencies) if hasFeature(features, feature) && dependencies.exists(d => !hasFeature(features, d)) =>
      FeatureException(s"${features.toBin} sets $feature but is missing a dependency (${dependencies.filter(d => !hasFeature(features, d)).mkString(" and ")})")
  }

  def validateFeatureGraph(features: ByteVector): Option[FeatureException] = validateFeatureGraph(features.bits)

  def validateFeatureGraph(features: Features): Option[FeatureException] = featuresDependency.collectFirst {
    case (feature, dependencies) if features.hasFeature(feature) && dependencies.exists(d => !features.hasFeature(d) ) =>
      FeatureException(s"${features.toByteVector.toBin} sets $feature but is missing a dependency (${dependencies.filter(d => !features.hasFeature(d)).mkString(" and ")})")
  }

  // Note that BitVector indexes from left to right whereas the specification indexes from right to left.
  // This is why we have to reverse the bits to check if a feature is set.

  private def hasFeature(features: BitVector, bit: Int): Boolean = features.sizeGreaterThan(bit) && features.reverse.get(bit)

  def hasFeature(features: BitVector, feature: Feature, support: Option[FeatureSupport] = None): Boolean = support match {
    case Some(FeatureSupport.Mandatory) => hasFeature(features, feature.mandatory)
    case Some(FeatureSupport.Optional) => hasFeature(features, feature.optional)
    case None => hasFeature(features, feature.optional) || hasFeature(features, feature.mandatory)
  }

  def hasFeature(features: ByteVector, feature: Feature): Boolean = hasFeature(features.bits, feature)

  def hasFeature(features: ByteVector, feature: Feature, support: Option[FeatureSupport]): Boolean = hasFeature(features.bits, feature, support)

  def areSupported(features: ByteVector): Boolean = areSupported(features.bits)

  def areSupported(features: BitVector): Boolean = areSupported(Features(features))

  /**
    * A feature set is supported if all even bits are supported.
    * We just ignore unknown odd bits.
    */
  def areSupported(features: Features): Boolean = {
    features.activated.forall {
      case ActivatedFeature(_, Optional) => true
      case ActivatedFeature(feature, Mandatory) => supportedMandatoryFeatures.contains(feature)
    } && (features.inactive.isEmpty || !features.inactive.exists(_.bitIndex % 2 == 0))
  }

  /**
    * A feature set is supported if all even bits are supported.
    * We just ignore unknown odd bits.
    */
  def isSupported(feature: Feature, support: FeatureSupport): Boolean = support match {
    case FeatureSupport.Mandatory => supportedMandatoryFeatures.contains(feature)
    case FeatureSupport.Optional => true
  }

  val supportedMandatoryFeatures: Set[Feature] = Set(
    OptionDataLossProtect,
    ChannelRangeQueries,
    VariableLengthOnion,
    ChannelRangeQueriesExtended,
    PaymentSecret,
    BasicMultiPartPayment,
    Wumbo
  )

  val knownFeatures: Set[Feature] = Set(
    OptionDataLossProtect,
    InitialRoutingSync,
    ChannelRangeQueries,
    VariableLengthOnion,
    ChannelRangeQueriesExtended,
    PaymentSecret,
    BasicMultiPartPayment,
    Wumbo,
    TrampolinePayment
  )
}
