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
    assert(hasFeature(hex"08", Features.INITIAL_ROUTING_SYNC_BIT_OPTIONAL))
  }

  test("'data_loss_protect' feature") {
    assert(hasFeature(hex"01", Features.OPTION_DATA_LOSS_PROTECT_MANDATORY))
    assert(hasFeature(hex"02", Features.OPTION_DATA_LOSS_PROTECT_OPTIONAL))
  }

  test("'initial_routing_sync', 'data_loss_protect' and 'variable_length_onion' and 'option_scid_assign' features") {
    val features = hex"0110a"
    assert(areSupported(features) && hasFeature(features, OPTION_DATA_LOSS_PROTECT_OPTIONAL) && hasFeature(features, INITIAL_ROUTING_SYNC_BIT_OPTIONAL)
      && hasFeature(features, VARIABLE_LENGTH_ONION_MANDATORY) && hasFeature(features, OPTION_SCID_ASSIGN_MANDATORY))
  }

  test("'variable_length_onion' feature") {
    assert(hasFeature(hex"0100", Features.VARIABLE_LENGTH_ONION_MANDATORY))
    assert(hasVariableLengthOnion(hex"0100"))
    assert(hasFeature(hex"0200", Features.VARIABLE_LENGTH_ONION_OPTIONAL))
    assert(hasVariableLengthOnion(hex"0200"))
  }

  test("'option_scid_assign' feature") {
    assert(hasFeature(hex"01000", OPTION_SCID_ASSIGN_MANDATORY))
    assert(hasFeature(hex"02000", OPTION_SCID_ASSIGN_OPTIONAL))
    assert(hasScidAssign(hex"02000"))
  }

  test("features compatibility") {
    assert(areSupported(ByteVector.fromLong(1L << INITIAL_ROUTING_SYNC_BIT_OPTIONAL)))
    assert(areSupported(ByteVector.fromLong(1L << OPTION_DATA_LOSS_PROTECT_MANDATORY)))
    assert(areSupported(ByteVector.fromLong(1L << OPTION_DATA_LOSS_PROTECT_OPTIONAL)))
    assert(areSupported(ByteVector.fromLong(1L << VARIABLE_LENGTH_ONION_OPTIONAL)))
    assert(areSupported(ByteVector.fromLong(1L << VARIABLE_LENGTH_ONION_MANDATORY)))
    assert(areSupported(ByteVector.fromLong(1L << OPTION_SCID_ASSIGN_MANDATORY)))
    assert(areSupported(ByteVector.fromLong(1L << OPTION_SCID_ASSIGN_OPTIONAL)))
    assert(areSupported(hex"0b"))
    assert(!areSupported(hex"14"))
    assert(!areSupported(hex"0141"))
  }

  test("channel features") {
    assert(!isBitSet(0, 0.toByte) && !isBitSet(3, 0.toByte)) // private ordinary channel
    assert(isBitSet(0, 1.toByte) && !isBitSet(3, 1.toByte)) // public ordinary channel
    assert(!isBitSet(0, 8.toByte) && isBitSet(3, 8.toByte)) // private zeroconfSpendablePushChannel channel
    assert(isBitSet(0, 9.toByte) && isBitSet(3, 9.toByte)) // public zeroconfSpendablePushChannel channel
    assert(!isBitSet(0, 16.toByte) && !isBitSet(3, 16.toByte) && isBitSet(4, 16.toByte)) // private ordinary channel which becomes public
    assert(!isBitSet(0, 24.toByte) && isBitSet(3, 24.toByte) && isBitSet(4, 24.toByte)) // private zeroconfSpendablePushChannel channel which becomes public
  }
}
