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

package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin.Block
import org.scalatest.funsuite.AnyFunSuite

class CheckPointSpec extends AnyFunSuite {
  test("load checkpoint") {
    val checkpoints = CheckPoint.load(Block.LivenetGenesisBlock.hash)
    assert(!checkpoints.isEmpty)
  }
}
