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
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.FeatureSupport.Mandatory
import fr.acinq.eclair.Features.{BasicMultiPartPayment, ChannelRangeQueries, ChannelRangeQueriesExtended, InitialRoutingSync, OptionDataLossProtect, PaymentSecret, VariableLengthOnion}
import fr.acinq.eclair.crypto.LocalKeyManager
import org.scalatest.FunSuite
import scodec.bits.ByteVector

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

  test("NodeParams should fail with deprecated global-features, local-features or hex features") {
    for (deprecated <- Seq("global-features", "local-features")) {
      val illegalGlobalFeaturesConf = ConfigFactory.parseString(deprecated + " = \"0200\"")
      val conf = illegalGlobalFeaturesConf.withFallback(defaultConf)

      val nodeParamsAttempt = Try(makeNodeParamsWithDefaults(conf))
      assert(nodeParamsAttempt.isFailure && nodeParamsAttempt.failed.get.getMessage.contains(deprecated))
    }

    val illegalByteVectorFeatures = ConfigFactory.parseString("features = \"0200\"")
    val conf = illegalByteVectorFeatures.withFallback(defaultConf)
    val nodeParamsAttempt = Try(makeNodeParamsWithDefaults(conf))
    assert(nodeParamsAttempt.failed.get.getMessage == "requirement failed: configuration key 'features' cannot be a byte vector (hex string)")
  }

  test("NodeParams should fail if features are inconsistent") {
    val legalFeaturesConf = ConfigFactory.parseMap(Map(
      s"features.${OptionDataLossProtect.rfcName}" -> "optional",
      s"features.${InitialRoutingSync.rfcName}" -> "optional",
      s"features.${ChannelRangeQueries.rfcName}" -> "optional",
      s"features.${ChannelRangeQueriesExtended.rfcName}" -> "optional",
      s"features.${VariableLengthOnion.rfcName}" -> "optional",
      s"features.${PaymentSecret.rfcName}" -> "optional",
      s"features.${BasicMultiPartPayment.rfcName}" -> "optional"
    ))

    // basic_mpp without var_onion_optin
    val illegalButAllowedFeaturesConf = ConfigFactory.parseMap(Map(
      s"features.${PaymentSecret.rfcName}" -> "optional",
      s"features.${BasicMultiPartPayment.rfcName}" -> "optional"
    ))

    // basic_mpp without payment_secret
    val illegalFeaturesConf = ConfigFactory.parseMap(Map(
      s"features.${BasicMultiPartPayment.rfcName}" -> "optional"
    ))

    assert(Try(makeNodeParamsWithDefaults(legalFeaturesConf.withFallback(defaultConf))).isSuccess)
    assert(Try(makeNodeParamsWithDefaults(illegalButAllowedFeaturesConf.withFallback(defaultConf))).isSuccess)
    assert(Try(makeNodeParamsWithDefaults(illegalFeaturesConf.withFallback(defaultConf))).isFailure)
  }

  test("parse human readable override features") {
    val perNodeConf = ConfigFactory.parseString(
      """
        |  override-features = [ // optional per-node features
        |      {
        |        nodeid = "02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        |          features {
        |             basic_mpp = mandatory
        |          }
        |      }
        |  ]
      """.stripMargin
    )

    val nodeParams = makeNodeParamsWithDefaults(perNodeConf.withFallback(defaultConf))
    val perNodeFeatures = nodeParams.overrideFeatures(PublicKey(ByteVector.fromValidHex("02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
    assert(Features.hasFeature(perNodeFeatures, BasicMultiPartPayment, Some(Mandatory)))
  }

  test("NodeParams should fail if htlc-minimum-msat is set to 0") {
    val noHtlcMinimumConf = ConfigFactory.parseString("htlc-minimum-msat = 0")
    assert(Try(makeNodeParamsWithDefaults(noHtlcMinimumConf.withFallback(defaultConf))).isFailure)
  }

}
