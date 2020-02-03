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

import scodec.bits.{BitVector, ByteVector}

/**
 * Created by PM on 13/02/2017.
 */

sealed trait FeatureSupport

// @formatter:off
object FeatureSupport {
  case object Mandatory extends FeatureSupport
  case object Optional extends FeatureSupport
}
// @formatter:on

sealed trait Feature {
  def rfcName: String

  def mandatory: Int

  def optional: Int = mandatory + 1

  override def toString = rfcName
}

object Features {

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

  /**
   * Check that the features that we understand are correctly specified, and that there are no mandatory features that
   * we don't understand (even bits).
   */
  def areSupported(features: BitVector): Boolean = {
    val supportedMandatoryFeatures = Set(
      OptionDataLossProtect,
      ChannelRangeQueries,
      VariableLengthOnion,
      ChannelRangeQueriesExtended,
      PaymentSecret,
      BasicMultiPartPayment
    ).map(_.mandatory.toLong)
    val reversed = features.reverse
    for (i <- 0L until reversed.length by 2) {
      if (reversed.get(i) && !supportedMandatoryFeatures.contains(i)) return false
    }

    true
  }

  /**
   * A feature set is supported if all even bits are supported.
   * We just ignore unknown odd bits.
   */
  def areSupported(features: ByteVector): Boolean = areSupported(features.bits)

}
