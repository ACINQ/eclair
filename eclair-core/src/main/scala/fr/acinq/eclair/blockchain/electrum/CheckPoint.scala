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

package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin.BinaryData
import org.json4s.JsonAST.{JArray, JInt, JString}
import org.json4s.jackson.JsonMethods

/**
  *
  * @param hash block hash
  * @param target difficulty target for the next block
  */
case class CheckPoint(hash: BinaryData, target: BigInt) {
  require(hash.length == 32)
}

object CheckPoint {
  /**
    * Load checkpoints.
    * There is one checkpoint every 2016 blocks (which is the difficulty adjustment period). They are used to check that
    * we're on the right chain and to validate proof-of-work by checking the difficulty target
    * @return an ordered list of checkpoints, with one checkpoint every 2016 blocks
    */
  def load: Vector[CheckPoint] = {
    val stream = classOf[CheckPoint].getResourceAsStream("/electrum/checkpoints.json")
    val JArray(values) = JsonMethods.parse(stream)
    val checkpoints = values.collect {
      case JArray(JString(a) :: JInt(b) :: Nil) => CheckPoint(BinaryData(a).reverse, b)
    }
    checkpoints.toVector
  }
}
