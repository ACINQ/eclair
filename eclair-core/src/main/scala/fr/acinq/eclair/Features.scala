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

sealed trait Feature {

  def rfcName: String
  def mandatory: Int
  def optional: Int = mandatory + 1

  def supportBit(support: FeatureSupport): Int = support match {
    case FeatureSupport.Mandatory => mandatory
    case FeatureSupport.Optional => optional
  }

  override def toString = rfcName

}
// @formatter:on

case class ActivatedFeature(feature: Feature, support: FeatureSupport)

case class UnknownFeature(bitIndex: Int)

case class Features(activated: Set[ActivatedFeature], unknown: Set[UnknownFeature] = Set.empty) {

  def hasFeature(feature: Feature, support: Option[FeatureSupport] = None): Boolean = support match {
    case Some(s) => activated.contains(ActivatedFeature(feature, s))
    case None => hasFeature(feature, Some(Optional)) || hasFeature(feature, Some(Mandatory))
  }

  def toByteVector: ByteVector = {
    val activatedFeatureBytes = toByteVectorFromIndex(activated.map { case ActivatedFeature(f, s) => f.supportBit(s) })
    val unknownFeatureBytes = toByteVectorFromIndex(unknown.map(_.bitIndex))
    val maxSize = activatedFeatureBytes.size.max(unknownFeatureBytes.size)
    activatedFeatureBytes.padLeft(maxSize) | unknownFeatureBytes.padLeft(maxSize)
  }

  private def toByteVectorFromIndex(indexes: Set[Int]): ByteVector = {
    if (indexes.isEmpty) return ByteVector.empty
    // When converting from BitVector to ByteVector, scodec pads right instead of left, so we make sure we pad to bytes *before* setting feature bits.
    var buf = BitVector.fill(indexes.max + 1)(high = false).bytes.bits
    indexes.foreach { i =>
      buf = buf.set(i)
    }
    buf.reverse.bytes
  }

}

object Features {

  def empty = Features(Set.empty[ActivatedFeature])

  def apply(features: Set[ActivatedFeature]): Features = Features(activated = features)

  def apply(bytes: ByteVector): Features = apply(bytes.bits)

  def apply(bits: BitVector): Features = {
    val all = bits.toIndexedSeq.reverse.zipWithIndex.collect {
      case (true, idx) if knownFeatures.exists(_.optional == idx) => Right(ActivatedFeature(knownFeatures.find(_.optional == idx).get, Optional))
      case (true, idx) if knownFeatures.exists(_.mandatory == idx) => Right(ActivatedFeature(knownFeatures.find(_.mandatory == idx).get, Mandatory))
      case (true, idx) => Left(UnknownFeature(idx))
    }
    Features(
      activated = all.collect { case Right(af) => af }.toSet,
      unknown = all.collect { case Left(inf) => inf }.toSet
    )
  }

  /** expects to have a top level config block named "features" */
  def fromConfiguration(config: Config): Features = Features(
    knownFeatures.flatMap {
      feature =>
        getFeature(config, feature.rfcName) match {
          case Some(support) => Some(ActivatedFeature(feature, support))
          case _ => None
        }
    })

  /** tries to extract the given feature name from the config, if successful returns its feature support */
  private def getFeature(config: Config, name: String): Option[FeatureSupport] = {
    if (!config.hasPath(s"features.$name")) None
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

  private val knownFeatures: Set[Feature] = Set(
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

  private val supportedMandatoryFeatures: Set[Feature] = Set(
    OptionDataLossProtect,
    ChannelRangeQueries,
    VariableLengthOnion,
    ChannelRangeQueriesExtended,
    PaymentSecret,
    BasicMultiPartPayment,
    Wumbo
  )

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

  def validateFeatureGraph(features: Features): Option[FeatureException] = featuresDependency.collectFirst {
    case (feature, dependencies) if features.hasFeature(feature) && dependencies.exists(d => !features.hasFeature(d)) =>
      FeatureException(s"$feature is set but is missing a dependency (${dependencies.filter(d => !features.hasFeature(d)).mkString(" and ")})")
  }

  /**
   * A feature set is supported if all even bits are supported.
   * We just ignore unknown odd bits.
   */
  def areSupported(features: Features): Boolean = {
    !features.unknown.exists(_.bitIndex % 2 == 0) && features.activated.forall {
      case ActivatedFeature(_, Optional) => true
      case ActivatedFeature(feature, Mandatory) => supportedMandatoryFeatures.contains(feature)
    }
  }

}
