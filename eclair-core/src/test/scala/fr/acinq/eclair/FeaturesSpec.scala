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

import org.scalatest.FunSuite
import scodec.bits._

/**
  * Created by PM on 27/01/2017.
  */

class FeaturesSpec extends FunSuite {

  test("'initial_routing_sync' feature") {
    assert(Features("08").hasInitialRoutingSync)
  }

  test("'data_loss_protect' feature") {
    assert(Features("01").hasOptionDataLossProtectMandatory)
    assert(Features("02").hasOptionDataLossProtectOptional)
  }

  test("'initial_routing_sync' and 'data_loss_protect' feature") {
    val features = Features("0a")
    assert(features.areSupported && features.hasOptionDataLossProtectOptional && features.hasInitialRoutingSync)
  }

  test("features compatibility") {
    assert(Features(bin"1000").areSupported)
    assert(Features(bin"1").areSupported)
    assert(Features(bin"10").areSupported)
    assert(Features(bin"10000000000000000000000000000000000000").areSupported)
    assert(Features(bin"100000000000000000000000000000000000000").areSupported == false)
    assert(Features(bin"10100").areSupported == false)
    assert(Features(bin"101000001").areSupported == false)
  }

}
