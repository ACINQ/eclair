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

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props, ReceiveTimeout, SupervisorStrategy}
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Block, ByteVector32}
import fr.acinq.eclair.NodeParams.ELECTRUM
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{SSL, computeScriptHash}
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool.ElectrumServerAddress
import fr.acinq.eclair.blockchain.electrum.{ElectrumClient, ElectrumClientPool}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.db.Databases
import grizzled.slf4j.Logging

import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * Spawn an electrum watcher to detect if some txes were spent.
  *
  * @param datadir          directory where eclair-core will write/read its data.
  * @param overrideDefaults use this parameter to programmatically override the node configuration.
  */
class CheckElectrumSetup(datadir: File,
                         overrideDefaults: Config = ConfigFactory.empty(),
                         db: Option[Databases] = None)(implicit system: ActorSystem) extends Logging {

  logger.info(s"hello!")
  logger.info(s"version=${getClass.getPackage.getImplementationVersion} commit=${getClass.getPackage.getSpecificationVersion}")
  logger.info(s"datadir=${datadir.getCanonicalPath}")

  implicit val ec = ExecutionContext.Implicits.global

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
    * Check if some of those tx have been spent
    *
    * @return true if none of the txes have been spent
    *         false if at least one tx has been spent
    */
  def check: Future[WatchListener.WatchResult] = {
    val channels = nodeParams.db.channels.listLocalChannels()
    if (channels.isEmpty) {
      Future.successful(WatchListener.Ok)
    } else {

      val electrumClient = nodeParams.watcherType match {
        case ELECTRUM =>
          val addresses = config.hasPath("electrum") match {
            case true =>
              val host = config.getString("electrum.host")
              val port = config.getInt("electrum.port")
              val ssl = config.getString("electrum.ssl") match {
                case "off" => SSL.OFF
                case "loose" => SSL.LOOSE
                case _ => SSL.STRICT // strict mode is the default when we specify a custom electrum server, we don't want to be MITMed
              }
              val address = InetSocketAddress.createUnresolved(host, port)
              logger.info(s"override electrum default with server=$address ssl=$ssl")
              Set(ElectrumServerAddress(address, ssl))
            case false =>
              val (addressesFile, sslEnabled) = nodeParams.chainHash match {
                case Block.RegtestGenesisBlock.hash => ("/electrum/servers_regtest.json", false) // in regtest we connect in plaintext
                case Block.TestnetGenesisBlock.hash => ("/electrum/servers_testnet.json", true)
                case Block.LivenetGenesisBlock.hash => ("/electrum/servers_mainnet.json", true)
              }
              val stream = classOf[Setup].getResourceAsStream(addressesFile)
              ElectrumClientPool.readServerAddresses(stream, sslEnabled)
          }
          val electrumClient = system.actorOf(SimpleSupervisor.props(Props(new ElectrumClientPool(addresses)), "electrum-client", SupervisorStrategy.Resume))
          electrumClient
        case _ => ???
      }

      // actor watching synchronisation progress
      val pWatchResult = Promise[WatchListener.WatchResult]()
      val watchListener = system.actorOf(Props(new BasicWatcher(electrumClient, channels, pWatchResult)))
      pWatchResult.future
    }
  }
}

/**
  * This actor monitors the routing table sync progress and terminates the actor system when sync is complete, or if
  * no progress has been made for a long time.
  */

class BasicWatcher(client: ActorRef, channels: Seq[HasCommitments], pWatchResult: Promise[WatchListener.WatchResult])(implicit ec: ExecutionContext) extends Actor with ActorLogging {

  import scala.concurrent.duration._

  client ! ElectrumClient.AddStatusListener(self)

  // we know those transactions are ok
  val okTxes = channels.flatMap {
    case d: DATA_CLOSING => WatchListener.okTransactions(d.commitments) ++ d.mutualCloseProposed.map(_.txid)
    case d: HasCommitments => WatchListener.okTransactions(d.commitments)
  }.toSet

  log.info(s"starting with ${okTxes.size} known transactions")
  okTxes.foreach(txid => log.info(s"oktxid=$txid"))

  context.setReceiveTimeout(60 second)

  override def receive: Receive = {
    case _: ElectrumClient.ElectrumReady =>
      log.info(s"electrum client is ready")
      channels.map { data =>
        val txid = data.commitments.commitInput.outPoint.txid
        val outputIndex = data.commitments.commitInput.outPoint.index.toInt
        val publicKeyScript = data.commitments.commitInput.txOut.publicKeyScript
        val scriptHash = computeScriptHash(publicKeyScript)
        log.info(s"added watch-spent on output=$txid:$outputIndex scriptHash=$scriptHash")
        client ! ElectrumClient.ScriptHashSubscription(scriptHash, self)
      }
      context become (waiting(0))
  }

  def waiting(ok: Int): Receive = {
    case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status) =>
      log.info(s"new status=$status for scriptHash=$scriptHash")
      client ! ElectrumClient.GetScriptHashHistory(scriptHash)
    case ElectrumClient.GetScriptHashHistoryResponse(_, history) =>
      // TODO: simplified, we should check that the spending tx actually spends the correct utxo
      val ok1 = history.map(_.tx_hash) match {
        case Nil =>
          // funding tx was unspent
          ok + 1
        case txids if txids.forall(okTxes.contains(_)) =>
          // we know this tx, nothing abnormal
          log.info(s"txids=$txids are known")
          ok + 1
        case txids =>
          // uh oh... we don't know this tx, let's raise the alarm
          log.warning(s"at least one of txids=$txids is unknown!!")
          pWatchResult.trySuccess(WatchListener.NotOk)
          context stop self
          0
      }
      if (ok1 == channels.size) {
        log.info(s"everything looks ok!")
        pWatchResult.trySuccess(WatchListener.Ok)
        context stop self

      } else {
        context become waiting(ok1)
      }
    case ReceiveTimeout ⇒
      log.info("no answer from watcher for a while, system will terminate...")
      pWatchResult.trySuccess(WatchListener.Unknown)
      context stop self
  }

  override def postStop(): Unit = {
    super.postStop()
    log.info(s"stopping electrum client")
    client ! PoisonPill
  }
}

object WatchListener {

  // @formatter:off
  sealed trait WatchResult
  case object Ok extends WatchResult
  case object NotOk extends WatchResult
  case object Unknown extends WatchResult
  // @formatter:on

  /**
    * Returns txids of known "honest" transactions (funding, lcoal-close, remote-close) in a commitment
    *
    * @param commitments
    * @return
    */
  def okTransactions(commitments: Commitments): Seq[ByteVector32] = {
    Seq(commitments.commitInput.outPoint.txid, commitments.localCommit.publishableTxs.commitTx.tx.txid, commitments.remoteCommit.txid) ++ commitments.remoteNextCommitInfo.left.toSeq.map(_.nextRemoteCommit.txid)
  }

}