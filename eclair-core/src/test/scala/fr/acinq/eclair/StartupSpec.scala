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

import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.crypto.LocalKeyManager
import org.scalatest.FunSuite

import scala.collection.JavaConversions._
import scala.util.Try

class StartupSpec extends FunSuite {

  val defaultConf = ConfigFactory.parseResources("reference.conf").getConfig("eclair")

  def makeNodeParamsWithDefaults(conf: Config): NodeParams = {
    val blockCount = new AtomicLong(0)
    val keyManager = new LocalKeyManager(seed = randomBytes32, chainHash = Block.TestnetGenesisBlock.hash)
    val feeEstimator = new TestConstants.TestFeeEstimator
    val db = TestConstants.inMemoryDb()
    NodeParams.makeNodeParams(conf, keyManager, None, db, blockCount, feeEstimator)
  }

  test("check configuration") {
    assert(Try(makeNodeParamsWithDefaults(ConfigFactory.load().getConfig("eclair"))).isSuccess)
    assert(Try(makeNodeParamsWithDefaults(ConfigFactory.load().getConfig("eclair").withFallback(ConfigFactory.parseMap(Map("max-feerate-mismatch" -> 42))))).isFailure)
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
    val conf = illegalAliasConf.withFallback(defaultConf)

    val nodeParamsAttempt = Try(makeNodeParamsWithDefaults(conf))
    assert(nodeParamsAttempt.isFailure && nodeParamsAttempt.failed.get.getMessage.contains("alias, too long"))
  }

  test("NodeParams should fail with deprecated global-features or local-features") {
    for (deprecated <- Seq("global-features", "local-features")) {
      val illegalGlobalFeaturesConf = ConfigFactory.parseString(deprecated + " = \"0200\"")
      val conf = illegalGlobalFeaturesConf.withFallback(defaultConf)

      val nodeParamsAttempt = Try(makeNodeParamsWithDefaults(conf))
      assert(nodeParamsAttempt.isFailure && nodeParamsAttempt.failed.get.getMessage.contains(deprecated))
    }
  }

  test("NodeParams should fail if features are inconsistent") {
    val legalFeaturesConf = ConfigFactory.parseString("features = \"028a8a\"")
    val illegalButAllowedFeaturesConf = ConfigFactory.parseString("features = \"028000\"") // basic_mpp without var_onion_optin
    val illegalFeaturesConf = ConfigFactory.parseString("features = \"020000\"") // basic_mpp without payment_secret
    assert(Try(makeNodeParamsWithDefaults(legalFeaturesConf.withFallback(defaultConf))).isSuccess)
    assert(Try(makeNodeParamsWithDefaults(illegalButAllowedFeaturesConf.withFallback(defaultConf))).isSuccess)
    assert(Try(makeNodeParamsWithDefaults(illegalFeaturesConf.withFallback(defaultConf))).isFailure)
  }

}
