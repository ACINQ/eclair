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

import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, SatoshiLong}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features._
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw, FeerateTolerance}
import fr.acinq.eclair.crypto.keymanager.{LocalChannelKeyManager, LocalNodeKeyManager}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.{ByteVector, HexStringSyntax}

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters._
import scala.util.Try

class StartupSpec extends AnyFunSuite {

  val defaultConf = ConfigFactory.parseResources("reference.conf").getConfig("eclair")

  def makeNodeParamsWithDefaults(conf: Config): NodeParams = {
    val blockCount = new AtomicLong(0)
    val nodeKeyManager = new LocalNodeKeyManager(randomBytes32, chainHash = Block.TestnetGenesisBlock.hash)
    val channelKeyManager = new LocalChannelKeyManager(randomBytes32, chainHash = Block.TestnetGenesisBlock.hash)
    val feeEstimator = new TestConstants.TestFeeEstimator
    val db = TestConstants.inMemoryDb()
    NodeParams.makeNodeParams(conf, UUID.fromString("01234567-0123-4567-89ab-0123456789ab"), nodeKeyManager, channelKeyManager, None, db, blockCount, feeEstimator)
  }

  test("check configuration") {
    assert(Try(makeNodeParamsWithDefaults(ConfigFactory.load().getConfig("eclair"))).isSuccess)
    assert(Try(makeNodeParamsWithDefaults(ConfigFactory.load().getConfig("eclair").withFallback(ConfigFactory.parseMap(Map("max-feerate-mismatch" -> 42).asJava)))).isFailure)
    assert(Try(makeNodeParamsWithDefaults(ConfigFactory.load().getConfig("eclair").withFallback(ConfigFactory.parseMap(Map("on-chain-fees.max-feerate-mismatch" -> 1.56).asJava)))).isFailure)
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
    assert(nodeParamsAttempt.failed.get.getMessage == "requirement failed: configuration key 'features' have moved from bytevector to human readable (ex: 'feature-name' = optional/mandatory)")
  }

  test("NodeParams should fail if features are inconsistent") {
    // Because of https://github.com/ACINQ/eclair/issues/1434, we need to remove the default features when falling back
    // to the default configuration.
    def finalizeConf(testCfg: Config): Config = testCfg.withFallback(defaultConf.withoutPath("features"))

    val legalFeaturesConf = ConfigFactory.parseMap(Map(
      s"features.${OptionDataLossProtect.rfcName}" -> "optional",
      s"features.${ChannelRangeQueries.rfcName}" -> "optional",
      s"features.${ChannelRangeQueriesExtended.rfcName}" -> "optional",
      s"features.${VariableLengthOnion.rfcName}" -> "optional",
      s"features.${PaymentSecret.rfcName}" -> "optional",
      s"features.${BasicMultiPartPayment.rfcName}" -> "optional"
    ).asJava)

    // var_onion_optin cannot be disabled
    val noVariableLengthOnionConf = ConfigFactory.parseMap(Map(
      s"features.${OptionDataLossProtect.rfcName}" -> "optional",
      s"features.${ChannelRangeQueries.rfcName}" -> "optional",
      s"features.${ChannelRangeQueriesExtended.rfcName}" -> "optional"
    ).asJava)

    // initial_routing_sync cannot be enabled
    val initialRoutingSyncConf = ConfigFactory.parseMap(Map(
      s"features.${OptionDataLossProtect.rfcName}" -> "optional",
      s"features.${InitialRoutingSync.rfcName}" -> "optional",
      s"features.${ChannelRangeQueries.rfcName}" -> "optional",
      s"features.${ChannelRangeQueriesExtended.rfcName}" -> "optional",
      s"features.${VariableLengthOnion.rfcName}" -> "optional"
    ).asJava)

    // basic_mpp without payment_secret
    val illegalFeaturesConf = ConfigFactory.parseMap(Map(
      s"features.${BasicMultiPartPayment.rfcName}" -> "optional"
    ).asJava)

    assert(Try(makeNodeParamsWithDefaults(finalizeConf(legalFeaturesConf))).isSuccess)
    assert(Try(makeNodeParamsWithDefaults(finalizeConf(noVariableLengthOnionConf))).isFailure)
    assert(Try(makeNodeParamsWithDefaults(finalizeConf(initialRoutingSyncConf))).isFailure)
    assert(Try(makeNodeParamsWithDefaults(finalizeConf(illegalFeaturesConf))).isFailure)
  }

  test("parse human readable override features") {
    val perNodeConf = ConfigFactory.parseString(
      """
        |  override-features = [ // optional per-node features
        |      {
        |        nodeid = "02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        |          features {
        |            var_onion_optin = optional
        |            payment_secret = mandatory
        |            basic_mpp = mandatory
        |          }
        |      }
        |  ]
      """.stripMargin
    )

    val nodeParams = makeNodeParamsWithDefaults(perNodeConf.withFallback(defaultConf))
    val perNodeFeatures = nodeParams.featuresFor(PublicKey(ByteVector.fromValidHex("02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
    assert(perNodeFeatures === Features(VariableLengthOnion -> Optional, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Mandatory))
  }

  test("override feerate mismatch tolerance") {
    val perNodeConf = ConfigFactory.parseString(
      """
        |  on-chain-fees.override-feerate-tolerance = [
        |    {
        |      nodeid = "02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        |      feerate-tolerance {
        |        ratio-low = 0.1
        |        ratio-high = 15.0
        |        anchor-output-max-commit-feerate = 15
        |      }
        |    },
        |    {
        |      nodeid = "02bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        |      feerate-tolerance {
        |        ratio-low = 0.75
        |        ratio-high = 5.0
        |        anchor-output-max-commit-feerate = 5
        |      }
        |    },
        |  ]
      """.stripMargin
    )

    val nodeParams = makeNodeParamsWithDefaults(perNodeConf.withFallback(defaultConf))
    assert(nodeParams.onChainFeeConf.feerateToleranceFor(PublicKey(hex"02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")) === FeerateTolerance(0.1, 15.0, FeeratePerKw(FeeratePerByte(15 sat))))
    assert(nodeParams.onChainFeeConf.feerateToleranceFor(PublicKey(hex"02bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")) === FeerateTolerance(0.75, 5.0, FeeratePerKw(FeeratePerByte(5 sat))))
    assert(nodeParams.onChainFeeConf.feerateToleranceFor(PublicKey(hex"02cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")) === FeerateTolerance(0.5, 10.0, FeeratePerKw(FeeratePerByte(10 sat))))
  }

  test("NodeParams should fail if htlc-minimum-msat is set to 0") {
    val noHtlcMinimumConf = ConfigFactory.parseString("htlc-minimum-msat = 0")
    assert(Try(makeNodeParamsWithDefaults(noHtlcMinimumConf.withFallback(defaultConf))).isFailure)
  }

}
