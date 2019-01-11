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

import java.io.InputStream

import fr.acinq.bitcoin.{BinaryData, Block, encodeCompact}
import fr.acinq.eclair.blockchain.electrum.db.HeaderDb
import org.json4s.JsonAST.{JArray, JInt, JString}
import org.json4s.jackson.JsonMethods

/**
  *
  * @param hash block hash
  * @param target difficulty target for the next block
  */
case class CheckPoint(hash: BinaryData, nextBits: Long) {
  require(hash.length == 32)
}

object CheckPoint {

  import Blockchain.RETARGETING_PERIOD

  /**
    * Load checkpoints.
    * There is one checkpoint every 2016 blocks (which is the difficulty adjustment period). They are used to check that
    * we're on the right chain and to validate proof-of-work by checking the difficulty target
    * @return an ordered list of checkpoints, with one checkpoint every 2016 blocks
    */
  def load(chainHash: BinaryData): Vector[CheckPoint] = chainHash match {
    case Block.LivenetGenesisBlock.hash => load(classOf[CheckPoint].getResourceAsStream("/electrum/checkpoints_mainnet.json"))
    case Block.TestnetGenesisBlock.hash => load(classOf[CheckPoint].getResourceAsStream("/electrum/checkpoints_testnet.json"))
    case Block.RegtestGenesisBlock.hash => Vector.empty[CheckPoint] // no checkpoints on regtest
  }

  def load(stream: InputStream): Vector[CheckPoint] = {
    val JArray(values) = JsonMethods.parse(stream)
    val checkpoints = values.collect {
      case JArray(JString(a) :: JInt(b) :: Nil) => CheckPoint(BinaryData(a).reverse, encodeCompact(b.bigInteger))
    }
    checkpoints.toVector
  }

  /**
    * load checkpoints from our resources and header database
    *
    * @param chainHash chaim hash
    * @param headerDb  header db
    * @return a series of checkpoints
    */
  def load(chainHash: BinaryData, headerDb: HeaderDb): Vector[CheckPoint] = {
    val checkpoints = CheckPoint.load(chainHash)
    val checkpoints1 = headerDb.getTip match {
      case Some((height, header)) =>
        val newcheckpoints = for {h <- checkpoints.size * RETARGETING_PERIOD - 1 + RETARGETING_PERIOD to height - RETARGETING_PERIOD by RETARGETING_PERIOD} yield {
          // we * should * have these headers in our db
          val cpheader = headerDb.getHeader(h).get
          val nextDiff = headerDb.getHeader(h + 1).get.bits
          CheckPoint(cpheader.hash, nextDiff)
        }
        checkpoints ++ newcheckpoints
      case None => checkpoints
    }
    checkpoints1
  }
}
