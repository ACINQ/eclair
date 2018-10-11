/*
 * Copyright 2018 ACINQ SAS
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

import java.nio.ByteOrder

import fr.acinq.bitcoin.Protocol
import fr.acinq.eclair.Features._
import org.scalatest.FunSuite

/**
  * Created by PM on 27/01/2017.
  */

class FeaturesSpec extends FunSuite {

  test("'initial_routing_sync' feature") {
    assert(hasFeature("08", Features.INITIAL_ROUTING_SYNC_BIT_OPTIONAL))
  }

  test("'data_loss_protect' feature") {
    assert(hasFeature("01", Features.OPTION_DATA_LOSS_PROTECT_MANDATORY))
    assert(hasFeature("02", Features.OPTION_DATA_LOSS_PROTECT_OPTIONAL))
  }

  test("'initial_routing_sync' and 'data_loss_protect' feature") {
    val features = "0a"
    assert(areSupported(features) && hasFeature(features, OPTION_DATA_LOSS_PROTECT_OPTIONAL) && hasFeature(features, INITIAL_ROUTING_SYNC_BIT_OPTIONAL))
  }

  test("features compatibility") {
    assert(areSupported(Protocol.writeUInt64(1l << INITIAL_ROUTING_SYNC_BIT_OPTIONAL, ByteOrder.BIG_ENDIAN)))
    assert(areSupported(Protocol.writeUInt64(1L << OPTION_DATA_LOSS_PROTECT_MANDATORY, ByteOrder.BIG_ENDIAN)))
    assert(areSupported(Protocol.writeUInt64(1l << OPTION_DATA_LOSS_PROTECT_OPTIONAL, ByteOrder.BIG_ENDIAN)))
    assert(areSupported("14") == false)
    assert(areSupported("0141") == false)
  }

}
