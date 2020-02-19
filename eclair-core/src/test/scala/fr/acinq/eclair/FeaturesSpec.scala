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

import fr.acinq.eclair.Features._
import org.scalatest.FunSuite
import scodec.bits._

/**
 * Created by PM on 27/01/2017.
 */

class FeaturesSpec extends FunSuite {

  test("'initial_routing_sync' feature") {
    assert(hasFeature(hex"08", InitialRoutingSync, Some(FeatureSupport.Optional)))
    assert(!hasFeature(hex"08", InitialRoutingSync, Some(FeatureSupport.Mandatory)))
  }

  test("'data_loss_protect' feature") {
    assert(hasFeature(hex"01", OptionDataLossProtect, Some(FeatureSupport.Mandatory)))
    assert(hasFeature(hex"02", OptionDataLossProtect, Some(FeatureSupport.Optional)))
  }

  test("'initial_routing_sync', 'data_loss_protect' and 'variable_length_onion' features") {
    val features = hex"010a"
    assert(areSupported(features))
    assert(hasFeature(features, OptionDataLossProtect))
    assert(hasFeature(features, InitialRoutingSync, None))
    assert(hasFeature(features, VariableLengthOnion))
  }

  test("'variable_length_onion' feature") {
    assert(hasFeature(hex"0100", VariableLengthOnion))
    assert(hasFeature(hex"0100", VariableLengthOnion, Some(FeatureSupport.Mandatory)))
    assert(hasFeature(hex"0200", VariableLengthOnion, None))
    assert(hasFeature(hex"0200", VariableLengthOnion, Some(FeatureSupport.Optional)))
  }

  test("features dependencies") {
    val testCases = Map(
      bin"                        " -> true,
      bin"                00000000" -> true,
      bin"                01011000" -> true,
      // gossip_queries_ex depend on gossip_queries
      bin"000000000000100000000000" -> false,
      bin"000000000000010000000000" -> false,
      bin"000000000000100010000000" -> true,
      bin"000000000000100001000000" -> true,
      // payment_secret depends on var_onion_optin, but we allow not setting it to be compatible with Phoenix
      bin"000000001000000000000000" -> true,
      bin"000000000100000000000000" -> true,
      bin"000000000100001000000000" -> true,
      // basic_mpp depends on payment_secret
      bin"000000100000000000000000" -> false,
      bin"000000010000000000000000" -> false,
      bin"000000101000000000000000" -> true, // we allow not setting var_onion_optin
      bin"000000011000000000000000" -> true, // we allow not setting var_onion_optin
      bin"000000011000001000000000" -> true,
      bin"000000100100000100000000" -> true
    )

    for ((testCase, valid) <- testCases) {
      if (valid) {
        assert(validateFeatureGraph(testCase) === None)
        assert(validateFeatureGraph(testCase.bytes) === None)
      } else {
        assert(validateFeatureGraph(testCase).nonEmpty)
        assert(validateFeatureGraph(testCase.bytes).nonEmpty)
      }
    }
  }

  test("features compatibility") {
    assert(areSupported(ByteVector.fromLong(1L << InitialRoutingSync.optional)))
    assert(areSupported(ByteVector.fromLong(1L << OptionDataLossProtect.mandatory)))
    assert(areSupported(ByteVector.fromLong(1L << OptionDataLossProtect.optional)))
    assert(areSupported(ByteVector.fromLong(1L << ChannelRangeQueries.mandatory)))
    assert(areSupported(ByteVector.fromLong(1L << ChannelRangeQueries.optional)))
    assert(areSupported(ByteVector.fromLong(1L << VariableLengthOnion.mandatory)))
    assert(areSupported(ByteVector.fromLong(1L << VariableLengthOnion.optional)))
    assert(areSupported(ByteVector.fromLong(1L << ChannelRangeQueriesExtended.mandatory)))
    assert(areSupported(ByteVector.fromLong(1L << ChannelRangeQueriesExtended.optional)))
    assert(areSupported(ByteVector.fromLong(1L << PaymentSecret.mandatory)))
    assert(areSupported(ByteVector.fromLong(1L << PaymentSecret.optional)))
    assert(areSupported(ByteVector.fromLong(1L << BasicMultiPartPayment.mandatory)))
    assert(areSupported(ByteVector.fromLong(1L << BasicMultiPartPayment.optional)))

    val testCases = Map(
      bin"            00000000000000001011" -> true,
      bin"            00010000100001000000" -> true,
      bin"            00100000100000100000" -> true,
      bin"            00010100000000001000" -> true,
      bin"            00011000001000000000" -> true,
      bin"            00101000000000000000" -> true,
      bin"            00000000010001000000" -> true,
      // unknown optional feature bits
      bin"            10000000000000000000" -> true,
      bin"        001000000000000000000000" -> true,
      // those are useful for nonreg testing of the areSupported method (which needs to be updated with every new supported mandatory bit)
      bin"        000001000000000000000000" -> false,
      bin"        000100000000000000000000" -> false,
      bin"        010000000000000000000000" -> false,
      bin"    0001000000000000000000000000" -> false,
      bin"    0100000000000000000000000000" -> false,
      bin"00010000000000000000000000000000" -> false,
      bin"01000000000000000000000000000000" -> false
    )
    for ((testCase, expected) <- testCases) {
      assert(areSupported(testCase) === expected, testCase)
    }
  }

}
