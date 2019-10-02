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
    assert(hasFeature(hex"08", INITIAL_ROUTING_SYNC_BIT_OPTIONAL))
  }

  test("'data_loss_protect' feature") {
    assert(hasFeature(hex"01", OPTION_DATA_LOSS_PROTECT_MANDATORY))
    assert(hasFeature(hex"02", OPTION_DATA_LOSS_PROTECT_OPTIONAL))
  }

  test("'initial_routing_sync', 'data_loss_protect' and 'variable_length_onion' features") {
    val features = hex"010a"
    assert(areSupported(features) && hasFeature(features, OPTION_DATA_LOSS_PROTECT_OPTIONAL) && hasFeature(features, INITIAL_ROUTING_SYNC_BIT_OPTIONAL) && hasFeature(features, VARIABLE_LENGTH_ONION_MANDATORY))
  }

  test("'variable_length_onion' feature") {
    assert(hasFeature(hex"0100", VARIABLE_LENGTH_ONION_MANDATORY))
    assert(hasVariableLengthOnion(hex"0100"))
    assert(hasFeature(hex"0200", VARIABLE_LENGTH_ONION_OPTIONAL))
    assert(hasVariableLengthOnion(hex"0200"))
  }

  test("'option_static_remotekey feature") {
    val optionalSupport = hex"2000"
    val mandatorySupport = hex"1000"
    val noSupport = hex"0000"

    assert(hasFeature(mandatorySupport, STATIC_REMOTEKEY_MANDATORY))
    assert(hasFeature(optionalSupport, STATIC_REMOTEKEY_OPTIONAL))

    assert(canUseStaticRemoteKey(localFeatures = optionalSupport, remoteFeatures = mandatorySupport))
    assert(canUseStaticRemoteKey(localFeatures = mandatorySupport, remoteFeatures = optionalSupport))
    assert(canUseStaticRemoteKey(localFeatures = optionalSupport, remoteFeatures = optionalSupport))
    assert(!canUseStaticRemoteKey(localFeatures = optionalSupport, remoteFeatures = noSupport))
    assert(canUseStaticRemoteKey(localFeatures = mandatorySupport, remoteFeatures = mandatorySupport))
    assert(!canUseStaticRemoteKey(localFeatures = noSupport, remoteFeatures = mandatorySupport))
  }

  test("features compatibility") {
    assert(areSupported(ByteVector.fromLong(1L << INITIAL_ROUTING_SYNC_BIT_OPTIONAL)))
    assert(areSupported(ByteVector.fromLong(1L << OPTION_DATA_LOSS_PROTECT_MANDATORY)))
    assert(areSupported(ByteVector.fromLong(1L << OPTION_DATA_LOSS_PROTECT_OPTIONAL)))
    assert(areSupported(ByteVector.fromLong(1L << VARIABLE_LENGTH_ONION_OPTIONAL)))
    assert(areSupported(ByteVector.fromLong(1L << VARIABLE_LENGTH_ONION_MANDATORY)))
    assert(areSupported(hex"0b"))
    assert(!areSupported(hex"14"))
    assert(!areSupported(hex"0141"))
  }

}
