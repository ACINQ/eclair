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

import akka.actor.{Actor, ActorLogging, ActorSystem, Props, ReceiveTimeout, SupervisorStrategy}
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Satoshi, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.{UtxoStatus, ValidateRequest, ValidateResult}
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.db.Databases
import fr.acinq.eclair.io.{Authenticator, NodeURI, Peer}
import fr.acinq.eclair.router._
import fr.acinq.eclair.transactions.Scripts
import grizzled.slf4j.Logging

import scala.concurrent.{Future, Promise}

/**
  * Setup a lite node for synchronising the local network DB in the datadir.
  *
  * @param datadir          directory where eclair-core will write/read its data.
  * @param overrideDefaults use this parameter to programmatically override the node configuration.
  * @param syncNodeURI      URI of the node from which the routing table is retrieved.
  */
class SyncLiteSetup(datadir: File,
                    overrideDefaults: Config = ConfigFactory.empty(),
                    syncNodeURI: NodeURI,
                    db: Option[Databases] = None)(implicit system: ActorSystem) extends Logging {

  logger.info(s"hello!")
  logger.info(s"version=${getClass.getPackage.getImplementationVersion} commit=${getClass.getPackage.getSpecificationVersion}")
  logger.info(s"datadir=${datadir.getCanonicalPath}")

  val config = NodeParams.loadConfiguration(datadir, overrideDefaults)
  val chain = config.getString("chain")
  val keyManager = new LocalKeyManager(PrivateKey(randomBytes(32), compressed = true).toBin, NodeParams.makeChainHash(chain))
  val database = db match {
    case Some(d) => d
    case None => Databases.sqliteJDBC(new File(datadir, chain))
  }
  val nodeParams = NodeParams.makeNodeParams(config, keyManager, None, database)

  logger.info(s"nodeid=${nodeParams.nodeId} alias=${nodeParams.alias}")
  logger.info(s"using chain=$chain chainHash=${nodeParams.chainHash}")

  /**
    * Perform a network sync
    * @return true if sync succeeded
    *         false otherwise
    */
  def sync: Future[Boolean] = {
    val watcher = system.actorOf(Props[YesWatcher], "yes-watcher")
    val relayer = system.deadLetters
    val router = system.actorOf(SimpleSupervisor.props(Router.props(nodeParams, watcher), "router", SupervisorStrategy.Resume))
    val authenticator = system.actorOf(SimpleSupervisor.props(Authenticator.props(nodeParams), "authenticator", SupervisorStrategy.Resume))

    // actor watching synchronisation progress
    val pSyncDone = Promise[Boolean]()
    system.actorOf(Props(new SyncListener(pSyncDone)))
    // connect to the provided node with a sync global feature flag
    val peer = system.actorOf(Peer.props(nodeParams, syncNodeURI.nodeId, authenticator, watcher, router, relayer, null), "peer")
    authenticator ! peer
    peer ! Peer.Init(previousKnownAddress = None, storedChannels = Set.empty)
    peer ! Peer.Connect(syncNodeURI)
    pSyncDone.future
  }
}

/**
  * This is a fake watcher that blindly validates channel announcement. We don't authenticate them for now.
  */
class YesWatcher extends Actor with ActorLogging {

  override def receive: Receive = {
    case ValidateRequest(c) =>
      log.info(s"blindly validating channel={}", c)
      val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(PublicKey(c.bitcoinKey1), PublicKey(c.bitcoinKey2))))
      val TxCoordinates(_, _, outputIndex) = ShortChannelId.coordinates(c.shortChannelId)
      val fakeFundingTx = Transaction(
        version = 2,
        txIn = Seq.empty[TxIn],
        txOut = List.fill(outputIndex + 1)(TxOut(Satoshi(0), pubkeyScript)), // quick and dirty way to be sure that the outputIndex'th output is of the expected format
        lockTime = 0)
      sender ! ValidateResult(c, Right(fakeFundingTx, UtxoStatus.Unspent))
  }
}

/**
  * This actor monitors the routing table sync progress and terminates the actor system when sync is complete, or if
  * no progress has been made for a long time.
  */
class SyncListener(pSyncDone: Promise[Boolean]) extends Actor with ActorLogging {

  import scala.concurrent.duration._

  context.setReceiveTimeout(60 second)
  context.system.eventStream.subscribe(self, classOf[SyncProgress])

  override def receive: Receive = {
    case SyncProgress(progress) =>
      log.debug("sync progress={}", Math.floor(progress * 100))
      if (progress >= 1) {
        pSyncDone.success(true)
      }
    case ReceiveTimeout ⇒
      log.info("no sync progress for a while, system will terminate...")
      pSyncDone.success(false)
  }
}