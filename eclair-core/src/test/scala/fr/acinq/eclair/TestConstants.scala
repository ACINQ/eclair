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

package fr.acinq.eclair

import java.io.File
import java.net.InetSocketAddress
import java.sql.DriverManager

import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, Block, Script}
import fr.acinq.eclair.NodeParams.BITCOIND
import fr.acinq.eclair.channel.LocalParams
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.db.{AppDbConfig, DbConfig, EclairDbConfig, NetworkDbConfig}
import fr.acinq.eclair.db.sqlite._
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.wire.Color
import grizzled.slf4j.Logging

import scala.concurrent.duration._

/**
  * Created by PM on 26/04/2016.
  */
object TestConstants extends Logging {
  val fundingSatoshis = 1000000L
  val pushMsat = 200000000L
  val feeratePerKw = 10000L


  private val config = ConfigFactory.load().resolve()

  //currently both bob and alice will share these configs
  val eclairDb = EclairDbConfig.unittestConfig(config)
  val networkDb = NetworkDbConfig.unittestConfig(config)
  lazy val dbConfig = AppDbConfig(eclairDb,networkDb)

  object Alice {
    val seed = BinaryData("01" * 32)
    val keyManager = new LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)
    lazy val aliceDbConfig = dbConfig

    // This is a function, and not a val! When called will return a new NodeParams
    def nodeParams: NodeParams = NodeParams(
      keyManager = keyManager,
      alias = "alice",
      color = Color(1, 2, 3),
      publicAddresses = new InetSocketAddress("localhost", 9731) :: Nil,
      globalFeatures = "",
      localFeatures = "00",
      dustLimitSatoshis = 546,
      maxHtlcValueInFlightMsat = UInt64(150000000),
      maxAcceptedHtlcs = 100,
      expiryDeltaBlocks = 144,
      htlcMinimumMsat = 0,
      minDepthBlocks = 3,
      toRemoteDelayBlocks = 144,
      maxToLocalDelayBlocks = 1000,
      smartfeeNBlocks = 3,
      feeBaseMsat = 546000,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,

      routerBroadcastInterval = 60 seconds,
      pingInterval = 30 seconds,
      maxFeerateMismatch = 1.5,
      updateFeeMinDiffRatio = 0.1,
      autoReconnect = false,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      channelExcludeDuration = 5 seconds,
      watcherType = BITCOIND,
      paymentRequestExpiry = 1 hour,
      maxPendingPaymentRequests = 10000000,
      maxPaymentFee = 0.03,
      minFundingSatoshis = 1000L,
      dbConfig = aliceDbConfig
    )

    def channelParams: LocalParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(Array.fill[Byte](32)(4), compressed = true).publicKey)),
      isFunder = true,
      fundingSatoshis).copy(
      channelReserveSatoshis = 10000 // Bob will need to keep that much satoshis as direct payment
    )
  }

  object Bob {
    val seed = BinaryData("02" * 32)
    val keyManager = new LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)
    lazy val bobDbConfig = dbConfig
    def nodeParams: NodeParams = NodeParams(
      keyManager = keyManager,
      alias = "bob",
      color = Color(4, 5, 6),
      publicAddresses = new InetSocketAddress("localhost", 9732) :: Nil,
      globalFeatures = "",
      localFeatures = "00", // no announcement
      dustLimitSatoshis = 1000,
      maxHtlcValueInFlightMsat = UInt64.MaxValue, // Bob has no limit on the combined max value of in-flight htlcs
      maxAcceptedHtlcs = 30,
      expiryDeltaBlocks = 144,
      htlcMinimumMsat = 1000,
      minDepthBlocks = 3,
      toRemoteDelayBlocks = 144,
      maxToLocalDelayBlocks = 1000,
      smartfeeNBlocks = 3,
      feeBaseMsat = 546000,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,
      routerBroadcastInterval = 60 seconds,
      pingInterval = 30 seconds,
      maxFeerateMismatch = 1.0,
      updateFeeMinDiffRatio = 0.1,
      autoReconnect = false,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      channelExcludeDuration = 5 seconds,
      watcherType = BITCOIND,
      paymentRequestExpiry = 1 hour,
      maxPendingPaymentRequests = 10000000,
      maxPaymentFee = 0.03,
      minFundingSatoshis = 1000L,
      dbConfig = bobDbConfig)

    def channelParams: LocalParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(Array.fill[Byte](32)(5), compressed = true).publicKey)),
      isFunder = false,
      fundingSatoshis).copy(
      channelReserveSatoshis = 20000 // Alice will need to keep that much satoshis as direct payment
    )
  }

  //crib from bitcoin-s to create some random dir
  private def randomDirName: String = {
    0.until(5).map(_ => scala.util.Random.alphanumeric.head).mkString
  }

  def initUnitTestDb: Unit = {
    val chaindir = new File("/home/chris/.eclair/unittest")
    val eclair = new File(chaindir, "eclair.sqlite")
    val res1 = eclair.createNewFile()
    logger.info(s"res1 $res1")
    val network = new File(chaindir, "network.sqlite")
    val res2 = network.createNewFile()
    logger.info(s"res2 $res2")
    ()
  }
}
