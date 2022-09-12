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

import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features._
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

/**
 * Created by PM on 27/01/2017.
 */

class FeaturesSpec extends AnyFunSuite {

  test("'initial_routing_sync' feature") {
    assert(Features(hex"08").hasFeature(InitialRoutingSync, Some(FeatureSupport.Optional)))
    assert(!Features(hex"08").hasFeature(InitialRoutingSync, Some(FeatureSupport.Mandatory)))
  }

  test("'data_loss_protect' feature") {
    assert(Features(hex"01").hasFeature(DataLossProtect, Some(FeatureSupport.Mandatory)))
    assert(Features(hex"02").hasFeature(DataLossProtect, Some(FeatureSupport.Optional)))
  }

  test("'initial_routing_sync', 'data_loss_protect' and 'variable_length_onion' features") {
    val features = Features(InitialRoutingSync -> Optional, DataLossProtect -> Optional, VariableLengthOnion -> Mandatory)
    assert(features.toByteVector == hex"010a")
    assert(features.hasFeature(DataLossProtect))
    assert(features.hasFeature(InitialRoutingSync, None))
    assert(features.hasFeature(VariableLengthOnion))
  }

  test("'variable_length_onion' feature") {
    assert(Features(hex"0100").hasFeature(VariableLengthOnion))
    assert(Features(hex"0100").hasFeature(VariableLengthOnion, Some(FeatureSupport.Mandatory)))
    assert(Features(hex"0200").hasFeature(VariableLengthOnion, None))
    assert(Features(hex"0200").hasFeature(VariableLengthOnion, Some(FeatureSupport.Optional)))
  }

  test("'option_static_remotekey' feature") {
    assert(Features(hex"1000").hasFeature(StaticRemoteKey))
    assert(Features(hex"1000").hasFeature(StaticRemoteKey, Some(Mandatory)))
    assert(Features(hex"2000").hasFeature(StaticRemoteKey))
    assert(Features(hex"2000").hasFeature(StaticRemoteKey, Some(Optional)))
  }

  test("features dependencies") {
    val testCases = Map(
      bin"                                " -> true,
      bin"                        00000000" -> true,
      bin"                        01011000" -> true,
      // gossip_queries_ex depend on gossip_queries
      bin"        000000000000100000000000" -> false,
      bin"        000000000000010000000000" -> false,
      bin"        000000000000100010000000" -> true,
      bin"        000000000000100001000000" -> true,
      // payment_secret depends on var_onion_optin
      bin"        000000001000000000000000" -> false,
      bin"        000000000100000000000000" -> false,
      bin"        000000000100001000000000" -> true,
      // basic_mpp depends on payment_secret
      bin"        000000100000000000000000" -> false,
      bin"        000000010000000000000000" -> false,
      bin"        000000101000000100000000" -> true,
      bin"        000000011000000100000000" -> true,
      bin"        000000011000001000000000" -> true,
      bin"        000000100100000100000000" -> true,
      // option_anchor_outputs depends on option_static_remotekey
      bin"        001000000000000000000000" -> false,
      bin"        000100000000000000000000" -> false,
      bin"        001000000010000000000000" -> true,
      bin"        001000000001000000000000" -> true,
      bin"        000100000010000000000000" -> true,
      bin"        000100000001000000000000" -> true,
      // option_anchors_zero_fee_htlc_tx depends on option_static_remotekey
      bin"        100000000000000000000000" -> false,
      bin"        010000000000000000000000" -> false,
      bin"        100000000010000000000000" -> true,
      bin"        100000000001000000000000" -> true,
      bin"        010000000010000000000000" -> true,
      bin"        010000000001000000000000" -> true,
    )

    for ((testCase, valid) <- testCases) {
      if (valid) {
        assert(validateFeatureGraph(Features(testCase)).isEmpty)
        assert(validateFeatureGraph(Features(testCase.bytes)).isEmpty)
      } else {
        assert(validateFeatureGraph(Features(testCase)).nonEmpty)
        assert(validateFeatureGraph(Features(testCase.bytes)).nonEmpty)
      }
    }
  }

  test("features compatibility") {
    case class TestCase(ours: Features[Feature], theirs: Features[Feature], oursSupportTheirs: Boolean, theirsSupportOurs: Boolean, compatible: Boolean)
    val testCases = Seq(
      // Empty features
      TestCase(
        Features.empty,
        Features.empty,
        oursSupportTheirs = true,
        theirsSupportOurs = true,
        compatible = true
      ),
      TestCase(
        Features.empty,
        Features(InitialRoutingSync -> Optional, VariableLengthOnion -> Optional),
        oursSupportTheirs = true,
        theirsSupportOurs = true,
        compatible = true
      ),
      TestCase(
        Features.empty,
        Features(activated = Map.empty, Set(UnknownFeature(101), UnknownFeature(103))),
        oursSupportTheirs = true,
        theirsSupportOurs = true,
        compatible = true
      ),
      // Same feature set
      TestCase(
        Features(InitialRoutingSync -> Optional, VariableLengthOnion -> Mandatory),
        Features(InitialRoutingSync -> Optional, VariableLengthOnion -> Mandatory),
        oursSupportTheirs = true,
        theirsSupportOurs = true,
        compatible = true
      ),
      // Many optional features
      TestCase(
        Features(InitialRoutingSync -> Optional, VariableLengthOnion -> Optional, ChannelRangeQueries -> Optional, PaymentSecret -> Optional),
        Features(VariableLengthOnion -> Optional, ChannelRangeQueries -> Optional, ChannelRangeQueriesExtended -> Optional),
        oursSupportTheirs = true,
        theirsSupportOurs = true,
        compatible = true
      ),
      // We support their mandatory features
      TestCase(
        Features(VariableLengthOnion -> Optional),
        Features(InitialRoutingSync -> Optional, VariableLengthOnion -> Mandatory),
        oursSupportTheirs = true,
        theirsSupportOurs = true,
        compatible = true
      ),
      // They support our mandatory features
      TestCase(
        Features(VariableLengthOnion -> Mandatory),
        Features(InitialRoutingSync -> Optional, VariableLengthOnion -> Optional),
        oursSupportTheirs = true,
        theirsSupportOurs = true,
        compatible = true
      ),
      // They have unknown optional features
      TestCase(
        Features(VariableLengthOnion -> Optional),
        Features(Map(VariableLengthOnion -> Optional), unknown = Set(UnknownFeature(141))),
        oursSupportTheirs = true,
        theirsSupportOurs = true,
        compatible = true
      ),
      // They have unknown mandatory features
      TestCase(
        Features(VariableLengthOnion -> Optional),
        Features(Map(VariableLengthOnion -> Optional), unknown = Set(UnknownFeature(142))),
        oursSupportTheirs = false,
        theirsSupportOurs = true,
        compatible = false
      ),
      // We don't support one of their mandatory features
      TestCase(
        Features(ChannelRangeQueries -> Optional),
        Features(ChannelRangeQueries -> Mandatory, VariableLengthOnion -> Mandatory),
        oursSupportTheirs = false,
        theirsSupportOurs = true,
        compatible = false
      ),
      // They don't support one of our mandatory features
      TestCase(
        Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory),
        Features(VariableLengthOnion -> Optional),
        oursSupportTheirs = true,
        theirsSupportOurs = false,
        compatible = false
      ),
      // nonreg testing of future features (needs to be updated with every new supported mandatory bit)
      TestCase(Features.empty, Features(Map.empty, unknown = Set(UnknownFeature(24))), oursSupportTheirs = false, theirsSupportOurs = true, compatible = false),
      TestCase(Features.empty, Features(Map.empty, unknown = Set(UnknownFeature(25))), oursSupportTheirs = true, theirsSupportOurs = true, compatible = true),
      TestCase(Features.empty, Features(Map.empty, unknown = Set(UnknownFeature(28))), oursSupportTheirs = false, theirsSupportOurs = true, compatible = false),
      TestCase(Features.empty, Features(Map.empty, unknown = Set(UnknownFeature(29))), oursSupportTheirs = true, theirsSupportOurs = true, compatible = true),
    )

    for (testCase <- testCases) {
      assert(areCompatible(testCase.ours, testCase.theirs) == testCase.compatible, testCase)
      assert(testCase.ours.areSupported(testCase.theirs) == testCase.oursSupportTheirs, testCase)
      assert(testCase.theirs.areSupported(testCase.ours) == testCase.theirsSupportOurs, testCase)
    }
  }

  test("filter features based on their usage") {
    val features = Features(
      Map(DataLossProtect -> Optional, InitialRoutingSync -> Optional, VariableLengthOnion -> Mandatory, PaymentMetadata -> Optional),
      Set(UnknownFeature(753), UnknownFeature(852), UnknownFeature(65303))
    )
    assert(features.initFeatures() == Features(
      Map(DataLossProtect -> Optional, InitialRoutingSync -> Optional, VariableLengthOnion -> Mandatory),
      Set(UnknownFeature(753), UnknownFeature(852), UnknownFeature(65303))
    ))
    assert(features.nodeAnnouncementFeatures() == Features(
      Map(DataLossProtect -> Optional, VariableLengthOnion -> Mandatory),
      Set(UnknownFeature(753), UnknownFeature(852), UnknownFeature(65303))
    ))
    assert(features.invoiceFeatures() == Features(
      Map(VariableLengthOnion -> Mandatory, PaymentMetadata -> Optional),
      Set(UnknownFeature(753), UnknownFeature(852), UnknownFeature(65303))
    ))
  }

  test("features to bytes") {
    val testCases = Map(
      hex"" -> Features.empty,
      hex"0100" -> Features(VariableLengthOnion -> Mandatory),
      hex"028a8a" -> Features(DataLossProtect -> Optional, InitialRoutingSync -> Optional, ChannelRangeQueries -> Optional, VariableLengthOnion -> Optional, ChannelRangeQueriesExtended -> Optional, PaymentSecret -> Optional, BasicMultiPartPayment -> Optional),
      hex"09004200" -> Features(Map(VariableLengthOnion -> Optional, PaymentSecret -> Mandatory, RouteBlinding -> Mandatory, ShutdownAnySegwit -> Optional)),
      hex"80010080000000000000000000000000000000000000" -> Features(Map.empty[Feature, FeatureSupport], Set(UnknownFeature(151), UnknownFeature(160), UnknownFeature(175)))
    )

    for ((bin, features) <- testCases) {
      assert(features.toByteVector == bin)
      assert(Features(bin) == features)
      val notMinimallyEncoded = Features(hex"00" ++ bin)
      assert(notMinimallyEncoded == features)
      assert(notMinimallyEncoded.toByteVector == bin) // features are minimally-encoded when converting to bytes
    }
  }

  test("parse features from configuration") {
    {
      val conf = ConfigFactory.parseString(
        """
          option_data_loss_protect = optional
          initial_routing_sync = optional
          gossip_queries = optional
          gossip_queries_ex = optional
          var_onion_optin = optional
          payment_secret = optional
          basic_mpp = optional
        """)

      val features = fromConfiguration(conf)
      assert(features.toByteVector == hex"028a8a")
      assert(Features(hex"028a8a") == features)
      assert(validateFeatureGraph(features).isEmpty)
      assert(features.hasFeature(DataLossProtect, Some(Optional)))
      assert(features.hasFeature(InitialRoutingSync, Some(Optional)))
      assert(features.hasFeature(ChannelRangeQueries, Some(Optional)))
      assert(features.hasFeature(ChannelRangeQueriesExtended, Some(Optional)))
      assert(features.hasFeature(VariableLengthOnion, Some(Optional)))
      assert(features.hasFeature(PaymentSecret, Some(Optional)))
      assert(features.hasFeature(BasicMultiPartPayment, Some(Optional)))
    }

    {
      val conf = ConfigFactory.parseString(
        """
          initial_routing_sync = optional
          option_data_loss_protect = optional
          gossip_queries = optional
          gossip_queries_ex = mandatory
          var_onion_optin = optional
        """)

      val features = fromConfiguration(conf)
      assert(features.toByteVector == hex"068a")
      assert(Features(hex"068a") == features)
      assert(validateFeatureGraph(features).isEmpty)
      assert(features.hasFeature(DataLossProtect, Some(Optional)))
      assert(features.hasFeature(InitialRoutingSync, Some(Optional)))
      assert(!features.hasFeature(InitialRoutingSync, Some(Mandatory)))
      assert(features.hasFeature(ChannelRangeQueries, Some(Optional)))
      assert(features.hasFeature(ChannelRangeQueriesExtended, Some(Mandatory)))
      assert(features.hasFeature(VariableLengthOnion, Some(Optional)))
      assert(!features.hasFeature(PaymentSecret))
    }

    {
      val confWithUnknownFeatures = ConfigFactory.parseString(
        """
          option_non_existent = mandatory
          gossip_queries = optional
          payment_secret = mandatory
        """)

      assertThrows[RuntimeException](fromConfiguration(confWithUnknownFeatures))
    }

    {
      val confWithUnknownSupport = ConfigFactory.parseString(
        """
          option_data_loss_protect = what
          gossip_queries = optional
          payment_secret = mandatory
        """)

      assertThrows[RuntimeException](fromConfiguration(confWithUnknownSupport))
    }

    {
      val confWithDisabledFeatures = ConfigFactory.parseString(
        """
          option_data_loss_protect = disabled
          gossip_queries = optional
          payment_secret = mandatory
          option_support_large_channel = disabled
          gossip_queries_ex = mandatory
        """)

      val features = fromConfiguration(confWithDisabledFeatures)
      assert(!features.hasFeature(DataLossProtect))
      assert(!features.hasFeature(Wumbo))
      assert(features.hasFeature(ChannelRangeQueries))
      assert(features.hasFeature(ChannelRangeQueriesExtended))
      assert(features.hasFeature(PaymentSecret))
    }
  }

  test("'knownFeatures' contains all our known features (reflection test)") {
    import scala.reflect.ClassTag
    import scala.reflect.runtime.universe._
    import scala.reflect.runtime.{universe => runtime}
    val mirror = runtime.runtimeMirror(ClassLoader.getSystemClassLoader)

    def extract[T: TypeTag](container: T)(implicit c: ClassTag[T]): Set[Feature] = {
      typeOf[T].decls.filter(_.isPublic).flatMap(symbol => {
        if (symbol.isTerm && symbol.isModule) {
          mirror.reflectModule(symbol.asModule).instance match {
            case f: Feature => Some(f)
            case _ => None
          }
        } else {
          None
        }
      }).toSet
    }

    val declaredFeatures = extract(Features)
    assert(declaredFeatures.nonEmpty)
    assert(declaredFeatures.removedAll(knownFeatures).isEmpty)
  }

}
