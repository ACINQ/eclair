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

import java.util.concurrent.atomic.AtomicLong

import com.typesafe.config.{ConfigFactory, ConfigValue}
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.crypto.LocalKeyManager
import org.scalatest.FunSuite

import scala.collection.JavaConversions._
import scala.util.Try

class StartupSpec extends FunSuite {

  test("check configuration") {
    val blockCount = new AtomicLong(0)
    val keyManager = new LocalKeyManager(seed = randomBytes32, chainHash = Block.TestnetGenesisBlock.hash)
    val conf = ConfigFactory.load().getConfig("eclair")
    assert(Try(NodeParams.makeNodeParams(conf, keyManager, None, TestConstants.inMemoryDb(), blockCount, new TestConstants.TestFeeEstimator)).isSuccess)

    val conf1 = conf.withFallback(ConfigFactory.parseMap(Map("max-feerate-mismatch" -> 42)))
    intercept[RuntimeException] {
      NodeParams.makeNodeParams(conf1, keyManager, None, TestConstants.inMemoryDb(), blockCount, new TestConstants.TestFeeEstimator)
    }
  }

  test("NodeParams should fail if the alias is illegal (over 32 bytes)") {

    val threeBytesUTFChar = '\u20AC' // €
    val baseUkraineAlias = "BitcoinLightningNodeUkraine"

    assert(baseUkraineAlias.length === 27)
    assert(baseUkraineAlias.getBytes.length === 27)

    // we add 2 UTF-8 chars, each is 3-bytes long -> total new length 33 bytes!
    val goUkraineGo = threeBytesUTFChar + "BitcoinLightningNodeUkraine" + threeBytesUTFChar

    assert(goUkraineGo.length === 29)
    assert(goUkraineGo.getBytes.length === 33) // too long for the alias, should be truncated

    val illegalAliasConf = ConfigFactory.parseString(s"node-alias = $goUkraineGo")
    val conf = illegalAliasConf.withFallback(ConfigFactory.parseResources("reference.conf").getConfig("eclair"))
    val keyManager = new LocalKeyManager(seed = randomBytes32, chainHash = Block.TestnetGenesisBlock.hash)

    val blockCount = new AtomicLong(0)

    // try to create a NodeParams instance with a conf that contains an illegal alias
    val nodeParamsAttempt = Try(NodeParams.makeNodeParams(conf, keyManager, None, TestConstants.inMemoryDb(), blockCount, new TestConstants.TestFeeEstimator))
    assert(nodeParamsAttempt.isFailure && nodeParamsAttempt.failed.get.getMessage.contains("alias, too long"))
  }

}
