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
import fr.acinq.eclair.blockchain.fee.{DustTolerance, FeeratePerByte, FeeratePerKw, FeerateTolerance}
import fr.acinq.eclair.crypto.keymanager.{LocalChannelKeyManager, LocalNodeKeyManager}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.{ByteVector, HexStringSyntax}

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters._
import scala.util.Try

class StartupSpec extends AnyFunSuite {

  val defaultConf = ConfigFactory.load("reference.conf").getConfig("eclair")

  def makeNodeParamsWithDefaults(conf: Config): NodeParams = {
    val blockCount = new AtomicLong(0)
    val nodeKeyManager = new LocalNodeKeyManager(randomBytes32(), chainHash = Block.TestnetGenesisBlock.hash)
    val channelKeyManager = new LocalChannelKeyManager(randomBytes32(), chainHash = Block.TestnetGenesisBlock.hash)
    val feeEstimator = new TestFeeEstimator()
    val db = TestDatabases.inMemoryDb()
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
    val goUkraineGo = s"${threeBytesUTFChar}BitcoinLightningNodeUkraine$threeBytesUTFChar"

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
      s"features.${DataLossProtect.rfcName}" -> "optional",
      s"features.${ChannelRangeQueries.rfcName}" -> "optional",
      s"features.${ChannelRangeQueriesExtended.rfcName}" -> "optional",
      s"features.${ChannelType.rfcName}" -> "optional",
      s"features.${VariableLengthOnion.rfcName}" -> "mandatory",
      s"features.${PaymentSecret.rfcName}" -> "mandatory",
      s"features.${BasicMultiPartPayment.rfcName}" -> "optional",
    ).asJava)

    // var_onion_optin cannot be disabled
    val noVariableLengthOnionConf = ConfigFactory.parseMap(Map(
      s"features.${DataLossProtect.rfcName}" -> "optional",
      s"features.${ChannelRangeQueries.rfcName}" -> "optional",
      s"features.${ChannelRangeQueriesExtended.rfcName}" -> "optional",
      s"features.${ChannelType.rfcName}" -> "optional",
    ).asJava)

    // var_onion_optin cannot be optional
    val optionalVarOnionOptinConf = ConfigFactory.parseMap(Map(
      s"features.${DataLossProtect.rfcName}" -> "optional",
      s"features.${ChannelType.rfcName}" -> "optional",
      s"features.${VariableLengthOnion.rfcName}" -> "optional",
      s"features.${PaymentSecret.rfcName}" -> "mandatory",
    ).asJava)

    // payment_secret cannot be optional
    val optionalPaymentSecretConf = ConfigFactory.parseMap(Map(
      s"features.${DataLossProtect.rfcName}" -> "optional",
      s"features.${ChannelType.rfcName}" -> "optional",
      s"features.${VariableLengthOnion.rfcName}" -> "mandatory",
      s"features.${PaymentSecret.rfcName}" -> "optional",
    ).asJava)

    // option_channel_type cannot be disabled
    val noChannelTypeConf = ConfigFactory.parseMap(Map(
      s"features.${DataLossProtect.rfcName}" -> "optional",
      s"features.${ChannelRangeQueries.rfcName}" -> "optional",
      s"features.${ChannelRangeQueriesExtended.rfcName}" -> "optional",
      s"features.${VariableLengthOnion.rfcName}" -> "mandatory",
      s"features.${PaymentSecret.rfcName}" -> "mandatory",
      s"features.${BasicMultiPartPayment.rfcName}" -> "optional",
    ).asJava)

    // initial_routing_sync cannot be enabled
    val initialRoutingSyncConf = ConfigFactory.parseMap(Map(
      s"features.${DataLossProtect.rfcName}" -> "optional",
      s"features.${InitialRoutingSync.rfcName}" -> "optional",
      s"features.${ChannelRangeQueries.rfcName}" -> "optional",
      s"features.${ChannelRangeQueriesExtended.rfcName}" -> "optional",
      s"features.${ChannelType.rfcName}" -> "optional",
      s"features.${VariableLengthOnion.rfcName}" -> "mandatory",
      s"features.${PaymentSecret.rfcName}" -> "mandatory",
    ).asJava)

    // extended channel queries without channel queries
    val illegalFeaturesConf = ConfigFactory.parseMap(Map(
      s"features.${DataLossProtect.rfcName}" -> "optional",
      s"features.${ChannelRangeQueriesExtended.rfcName}" -> "optional",
      s"features.${ChannelType.rfcName}" -> "optional",
      s"features.${VariableLengthOnion.rfcName}" -> "mandatory",
      s"features.${PaymentSecret.rfcName}" -> "mandatory",
    ).asJava)

    assert(Try(makeNodeParamsWithDefaults(finalizeConf(legalFeaturesConf))).isSuccess)
    assert(Try(makeNodeParamsWithDefaults(finalizeConf(noVariableLengthOnionConf))).isFailure)
    assert(Try(makeNodeParamsWithDefaults(finalizeConf(optionalVarOnionOptinConf))).isFailure)
    assert(Try(makeNodeParamsWithDefaults(finalizeConf(optionalPaymentSecretConf))).isFailure)
    assert(Try(makeNodeParamsWithDefaults(finalizeConf(noChannelTypeConf))).isFailure)
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
        |            var_onion_optin = mandatory
        |            payment_secret = mandatory
        |            basic_mpp = mandatory
        |            option_channel_type = optional
        |          }
        |      }
        |  ]
      """.stripMargin
    )

    val nodeParams = makeNodeParamsWithDefaults(perNodeConf.withFallback(defaultConf))
    val perNodeFeatures = nodeParams.initFeaturesFor(PublicKey(ByteVector.fromValidHex("02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
    assert(perNodeFeatures === Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Mandatory, ChannelType -> Optional))
  }

  test("filter out non-init features in node override") {
    val perNodeConf = ConfigFactory.parseString(
      """
        |  override-features = [ // optional per-node features
        |      {
        |        nodeid = "02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        |          features {
        |            var_onion_optin = mandatory
        |            payment_secret = mandatory
        |            option_channel_type = optional
        |            option_payment_metadata = disabled
        |          }
        |      },
        |      {
        |        nodeid = "02bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        |          features {
        |            var_onion_optin = mandatory
        |            payment_secret = mandatory
        |            option_channel_type = optional
        |            option_payment_metadata = mandatory
        |          }
        |      }
        |  ]
      """.stripMargin
    )

    val nodeParams = makeNodeParamsWithDefaults(perNodeConf.withFallback(defaultConf))
    val perNodeFeaturesA = nodeParams.initFeaturesFor(PublicKey(ByteVector.fromValidHex("02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
    val perNodeFeaturesB = nodeParams.initFeaturesFor(PublicKey(ByteVector.fromValidHex("02bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")))
    val defaultNodeFeatures = nodeParams.initFeaturesFor(PublicKey(ByteVector.fromValidHex("02cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")))
    // Some features should never be sent in init messages.
    assert(nodeParams.features.hasFeature(PaymentMetadata))
    assert(!perNodeFeaturesA.unscoped().hasFeature(PaymentMetadata))
    assert(!perNodeFeaturesB.unscoped().hasFeature(PaymentMetadata))
    assert(!defaultNodeFeatures.unscoped().hasFeature(PaymentMetadata))
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
        |        dust-tolerance {
        |          max-exposure-satoshis = 25000
        |          close-on-update-fee-overflow = true
        |        }
        |      }
        |    },
        |    {
        |      nodeid = "02bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        |      feerate-tolerance {
        |        ratio-low = 0.75
        |        ratio-high = 5.0
        |        anchor-output-max-commit-feerate = 5
        |        dust-tolerance {
        |          max-exposure-satoshis = 40000
        |          close-on-update-fee-overflow = false
        |        }
        |      }
        |    },
        |  ]
      """.stripMargin
    )

    val nodeParams = makeNodeParamsWithDefaults(perNodeConf.withFallback(defaultConf))
    assert(nodeParams.onChainFeeConf.feerateToleranceFor(PublicKey(hex"02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")) === FeerateTolerance(0.1, 15.0, FeeratePerKw(FeeratePerByte(15 sat)), DustTolerance(25_000 sat, closeOnUpdateFeeOverflow = true)))
    assert(nodeParams.onChainFeeConf.feerateToleranceFor(PublicKey(hex"02bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")) === FeerateTolerance(0.75, 5.0, FeeratePerKw(FeeratePerByte(5 sat)), DustTolerance(40_000 sat, closeOnUpdateFeeOverflow = false)))
    assert(nodeParams.onChainFeeConf.feerateToleranceFor(PublicKey(hex"02cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")) === FeerateTolerance(0.5, 10.0, FeeratePerKw(FeeratePerByte(10 sat)), DustTolerance(50_000 sat, closeOnUpdateFeeOverflow = false)))
  }

  test("NodeParams should fail if htlc-minimum-msat is set to 0") {
    val noHtlcMinimumConf = ConfigFactory.parseString("channel.htlc-minimum-msat = 0")
    assert(Try(makeNodeParamsWithDefaults(noHtlcMinimumConf.withFallback(defaultConf))).isFailure)
  }

}
