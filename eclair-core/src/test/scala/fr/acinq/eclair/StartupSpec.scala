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
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, SatoshiLong}
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

  val defaultConf: Config = ConfigFactory.load("reference.conf").getConfig("eclair")

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

    assert(baseUkraineAlias.length == 27)
    assert(baseUkraineAlias.getBytes.length == 27)

    // we add 2 UTF-8 chars, each is 3-bytes long -> total new length 33 bytes!
    val goUkraineGo = s"${threeBytesUTFChar}BitcoinLightningNodeUkraine$threeBytesUTFChar"

    assert(goUkraineGo.length == 29)
    assert(goUkraineGo.getBytes.length == 33) // too long for the alias, should be truncated

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
        |  features {
        |    var_onion_optin = mandatory
        |    payment_secret = mandatory
        |    option_channel_type = optional
        |  }
        |  override-init-features = [ // optional per-node features
        |      {
        |        nodeid = "031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f"
        |        features {
        |          basic_mpp = mandatory
        |          gossip_queries = optional
        |        }
        |      }
        |  ]
      """.stripMargin
    )

    val nodeParams = makeNodeParamsWithDefaults(perNodeConf.withFallback(defaultConf.withoutPath("features")))
    val perNodeFeatures = nodeParams.initFeaturesFor(PublicKey(ByteVector.fromValidHex("031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f")))
    assert(perNodeFeatures == Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Mandatory, ChannelRangeQueries -> Optional, ChannelType -> Optional))
  }

  test("combine node override features with default features") {
    val perNodeConf = ConfigFactory.parseString(
      """
        |  features {
        |    var_onion_optin = mandatory
        |    payment_secret = mandatory
        |    basic_mpp = mandatory
        |    option_static_remotekey = optional
        |    option_channel_type = optional
        |  }
        |  override-init-features = [ // optional per-node features
        |      {
        |        nodeid = "031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f"
        |        features {
        |          option_static_remotekey = mandatory
        |          option_anchors_zero_fee_htlc_tx = optional
        |        }
        |      },
        |      {
        |        nodeid = "024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766"
        |        features {
        |          basic_mpp = optional
        |          option_static_remotekey = disabled
        |        }
        |      }
        |  ]
      """.stripMargin
    )

    val nodeParams = makeNodeParamsWithDefaults(perNodeConf.withFallback(defaultConf.withoutPath("features")))
    val defaultFeatures = nodeParams.features
    assert(defaultFeatures == Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Mandatory, StaticRemoteKey -> Optional, ChannelType -> Optional))
    val perNodeFeatures1 = nodeParams.initFeaturesFor(PublicKey(ByteVector.fromValidHex("031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f")))
    assert(perNodeFeatures1 == Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Mandatory, StaticRemoteKey -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Optional, ChannelType -> Optional))
    val perNodeFeatures2 = nodeParams.initFeaturesFor(PublicKey(ByteVector.fromValidHex("024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766")))
    assert(perNodeFeatures2 == Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, ChannelType -> Optional))
  }

  test("reject non-init features in node override") {
    val perNodeConf = ConfigFactory.parseString(
      """
        |  override-init-features = [ // optional per-node features
        |      {
        |        nodeid = "031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f"
        |        features {
        |          var_onion_optin = mandatory
        |          payment_secret = mandatory
        |          option_channel_type = optional
        |          option_payment_metadata = disabled
        |        }
        |      },
        |      {
        |        nodeid = "024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766"
        |        features {
        |          var_onion_optin = mandatory
        |          payment_secret = mandatory
        |          option_channel_type = optional
        |          option_payment_metadata = mandatory
        |        }
        |      }
        |  ]
      """.stripMargin
    )

    assertThrows[IllegalArgumentException](makeNodeParamsWithDefaults(perNodeConf.withFallback(defaultConf)))
  }

  test("disallow enabling zero-conf for every peer") {
    val invalidConf = ConfigFactory.parseString(
      """
        |  features {
        |    option_zeroconf = optional
        |  }
      """.stripMargin
    )
    assertThrows[IllegalArgumentException](makeNodeParamsWithDefaults(invalidConf.withFallback(defaultConf)))

    val perNodeConf = ConfigFactory.parseString(
      """
        |  override-init-features = [
        |      {
        |        nodeid = "031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f"
        |        features {
        |          option_zeroconf = optional
        |        }
        |      }
        |  ]
      """.stripMargin
    )

    val nodeParams = makeNodeParamsWithDefaults(perNodeConf.withFallback(defaultConf))
    assert(!nodeParams.features.hasFeature(Features.ZeroConf))
    val perNodeFeatures = nodeParams.initFeaturesFor(PublicKey(ByteVector.fromValidHex("031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f")))
    assert(perNodeFeatures.hasFeature(Features.ZeroConf))
  }

  test("override feerate mismatch tolerance") {
    val perNodeConf = ConfigFactory.parseString(
      """
        |  on-chain-fees.override-feerate-tolerance = [
        |    {
        |      nodeid = "031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f"
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
        |      nodeid = "03462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b"
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
    assert(nodeParams.onChainFeeConf.feerateToleranceFor(PublicKey(hex"031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f")) == FeerateTolerance(0.1, 15.0, FeeratePerKw(FeeratePerByte(15 sat)), DustTolerance(25_000 sat, closeOnUpdateFeeOverflow = true)))
    assert(nodeParams.onChainFeeConf.feerateToleranceFor(PublicKey(hex"03462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b")) == FeerateTolerance(0.75, 5.0, FeeratePerKw(FeeratePerByte(5 sat)), DustTolerance(40_000 sat, closeOnUpdateFeeOverflow = false)))
    assert(nodeParams.onChainFeeConf.feerateToleranceFor(PublicKey(hex"0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f7")) == FeerateTolerance(0.5, 10.0, FeeratePerKw(FeeratePerByte(10 sat)), DustTolerance(50_000 sat, closeOnUpdateFeeOverflow = false)))
  }

  test("NodeParams should fail if htlc-minimum-msat is set to 0") {
    val noHtlcMinimumConf = ConfigFactory.parseString("channel.htlc-minimum-msat = 0")
    assert(Try(makeNodeParamsWithDefaults(noHtlcMinimumConf.withFallback(defaultConf))).isFailure)
  }

  test("NodeParams should fail with deprecated channel.min-funding-satoshis set") {
    val illegalGlobalFeaturesConf = ConfigFactory.parseString("channel.min-funding-satoshis = 200000")
    val illegalConf = illegalGlobalFeaturesConf.withFallback(defaultConf)

    val nodeParamsAttempt1 = Try(makeNodeParamsWithDefaults(illegalConf))
    assert(nodeParamsAttempt1.isFailure && nodeParamsAttempt1.failed.get.getMessage.contains("channel.min-public-funding-satoshis, channel.min-private-funding-satoshis"))

    val newGlobalFeaturesConf = ConfigFactory.parseMap(Map(
      s"channel.min-public-funding-satoshis" -> "20000",
      s"channel.min-private-funding-satoshis" -> "20000",
    ).asJava)
    val legalConf = newGlobalFeaturesConf.withFallback(defaultConf)
    val nodeParamsAttempt2 = Try(makeNodeParamsWithDefaults(legalConf))
    assert(nodeParamsAttempt2.isSuccess)
  }

  test("NodeParams should fail when server.public-ips addresses or server.port are invalid") {
    case class TestCase(publicIps: Seq[String], port: String, error: Option[String] = None, errorIp: Option[String] = None)
    val testCases = Seq[TestCase](
      TestCase(Seq("0.0.0.0", "140.82.121.4", "2620:1ec:c11:0:0:0:0:200", "2620:1ec:c11:0:0:0:0:201", "iq7zhmhck54vcax2vlrdcavq2m32wao7ekh6jyeglmnuuvv3js57r4id.onion", "of7husrflx7sforh3fw6yqlpwstee3wg5imvvmkp4bz6rbjxtg5nljad.onion", "acinq.co"), "9735"),
      TestCase(Seq("140.82.121.4", "2620:1ec:c11:0:0:0:0:200", "acinq.fr", "iq7zhmhck54vcax2vlrdcavq2m32wao7ekh6jyeglmnuuvv3js57r4id.onion"), "0", Some("port 0"), Some("140.82.121.4")),
      TestCase(Seq("hsmithsxurybd7uh.onion", "iq7zhmhck54vcax2vlrdcavq2m32wao7ekh6jyeglmnuuvv3js57r4id.onion"), "9735", Some("Tor v2"), Some("hsmithsxurybd7uh.onion")),
      TestCase(Seq("acinq.co", "acinq.fr"), "9735", Some("DNS host name")),
    )
    testCases.foreach(test => {
      val serverConf = ConfigFactory.parseMap(Map(
        s"server.public-ips" -> test.publicIps.asJava,
        s"server.port" -> test.port,
      ).asJava).withFallback(defaultConf)
      val attempt = Try(makeNodeParamsWithDefaults(serverConf))
      if (test.error.isEmpty) {
        assert(attempt.isSuccess)
      } else {
        assert(attempt.isFailure)
        assert(attempt.failed.get.getMessage.contains(test.error.get))
        assert(test.errorIp.isEmpty || attempt.failed.get.getMessage.contains(test.errorIp.get))
      }
    })
  }

}
