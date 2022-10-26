/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.plugins.peerswap

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, Crypto}
import fr.acinq.eclair.crypto.keymanager.{LocalChannelKeyManager, LocalNodeKeyManager}
import fr.acinq.eclair.{NodeParams, ShortChannelId, TestDatabases, TestFeeEstimator, randomBytes32}
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits._

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class PeerSwapSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {
  val protocolVersion = 2
  val swapId = hex"dd650741ee45fbad5df209bfb5aea9537e2e6d946cc7ece3b4492bbae0732634"
  val asset = ""
  val network = "regtest"
  val shortId: ShortChannelId = ShortChannelId.fromCoordinates("539268x845x1").success.get
  val amount = 10000
  val pubkey: PublicKey = dummyKey(1).publicKey
  val premium = 1000
  val payreq = "invoice here"
  val txid = "38b854c569ff4b8b25e6eeec31d21ce4a1ee6dbc2afc7efdb44c81d513b4bffc"
  val scriptOut = 0
  val blindingKey = ""
  val message = "a message"
  val privkey: PrivateKey = dummyKey(1)

  def dummyKey(fill: Byte): Crypto.PrivateKey = PrivateKey(ByteVector.fill(32)(fill))

  val defaultConf: Config = ConfigFactory.load("reference.conf").getConfig("eclair")

  def makeNodeParamsWithDefaults(conf: Config): NodeParams = {
    val blockCount = new AtomicLong(0)
    val nodeKeyManager = new LocalNodeKeyManager(randomBytes32(), chainHash = Block.TestnetGenesisBlock.hash)
    val channelKeyManager = new LocalChannelKeyManager(randomBytes32(), chainHash = Block.TestnetGenesisBlock.hash)
    val feeEstimator = new TestFeeEstimator()
    val db = TestDatabases.inMemoryDb()
    NodeParams.makeNodeParams(conf, UUID.fromString("01234567-0123-4567-89ab-0123456789ab"), nodeKeyManager, channelKeyManager, None, db, blockCount, feeEstimator)
  }

  test("load swap key from file") {
    // TODO
  }

  test( "create swap key if none exists") {
    // TODO
  }

  // TODO: test that a plugin exception does not crash the node ? restarts the plugin?

}
