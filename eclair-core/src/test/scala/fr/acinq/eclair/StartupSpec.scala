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

import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Block
import fr.acinq.eclair
import fr.acinq.eclair.blockchain.{ImportMultiItem, WatchAddressItem}
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.channel.DATA_WAIT_FOR_FUNDING_CONFIRMED
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.db.{ChannelsDb, Databases}
import fr.acinq.eclair.wire.{ChannelCodecsSpec, FundingSigned}
import grizzled.slf4j.Logger
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.FunSuite

import scala.collection.JavaConversions._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try

class StartupSpec extends FunSuite with IdiomaticMockito {

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

  test("reconciliation routine should import PENDING addresses") {
    implicit val logger = Logger.apply("test-logger")
    implicit val ec = ExecutionContext.global

    val channelDb = mock[ChannelsDb]
    channelDb.listLocalChannels() returns Seq.empty
    val databases = mock[Databases]
    databases.channels returns channelDb

    val bitcoinClient = mock[ExtendedBitcoinClient]
    val mockBlockChannel2 = Block.read("000000209d2308ba402235101251dbe62b22f5376012bd8167e4ab7084e5707f3b440f1756b1998cac8786fc3613f23285fa534505d7a2def663a01269df0ebe9257cbb2f4a5df5dffff7f200000000001020000000001010000000000000000000000000000000000000000000000000000000000000000ffffffff0502b7260101ffffffff0200000000000000001600148f15f444bbacc9a8541ec424fddcabfff5561eb50000000000000000266a24aa21a9ede2f61c3f71d1defd3fa999dfa36953755c690689799962b48bebd836974e8cf90120000000000000000000000000000000000000000000000000000000000000000000000000")

    val channel1 = ChannelCodecsSpec.normal
    val channel2 = ChannelCodecsSpec.normal.copy(
      commitments = channel1.commitments.copy(
        commitInput = Funding.makeFundingInputInfo(randomBytes32, 0, 100 sat, randomKey.publicKey, randomKey.publicKey)
      )
    )
    val channel3 = DATA_WAIT_FOR_FUNDING_CONFIRMED(channel1.commitments.copy(
      commitInput = Funding.makeFundingInputInfo(randomBytes32, 0, 100 sat, randomKey.publicKey, randomKey.publicKey)
    ), None, waitingSince = 123L, None, Right(FundingSigned(randomBytes32, randomBytes64)))

    val address1 = eclair.scriptPubKeyToAddress(channel1.commitments.commitInput.txOut.publicKeyScript)
    val address2 = eclair.scriptPubKeyToAddress(channel2.commitments.commitInput.txOut.publicKeyScript)
    val address3 = eclair.scriptPubKeyToAddress(channel3.commitments.commitInput.txOut.publicKeyScript)

    val channel2Height = ShortChannelId.coordinates(channel2.shortChannelId).blockHeight

    channelDb.listLocalChannels() returns Seq(channel1, channel2, channel3)

    // channel1 is already IMPORTED, channel2 is PENDING, no data for channel3
    bitcoinClient.listReceivedByAddress(1, true, true, None) returns Future.successful(List(
      WatchAddressItem(address1, "IMPORTED"),
      WatchAddressItem(address2, "PENDING")
    ))

    // mock block => timestamp computation
    bitcoinClient.getBlock(channel2Height) returns Future.successful(mockBlockChannel2)

    // importMulti will be called with 2 element to import
    bitcoinClient.importMulti(Seq(
      ImportMultiItem(address2, "PENDING", Some(mockBlockChannel2.header.time)),
      ImportMultiItem(address3, "PENDING", Some(123L))
    ), true) returns Future.successful(true)

    // after having imported and scanned we set the address label to IMPORTED
    bitcoinClient.setLabel(address2, "IMPORTED") returns Future.successful(Unit)
    bitcoinClient.setLabel(address3, "IMPORTED") returns Future.successful(Unit)

    Await.ready(Setup.reconcileWatchAddresses(bitcoinClient, databases), 20 seconds)
  }
}
