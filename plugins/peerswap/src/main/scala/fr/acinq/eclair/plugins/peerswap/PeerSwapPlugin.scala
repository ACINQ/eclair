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

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.{ClassicActorSystemOps, ClassicSchedulerOps}
import akka.actor.typed.{ActorRef, SupervisorStrategy}
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import fr.acinq.bitcoin.scalacompat.Satoshi
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.db.sqlite.SqliteUtils
import fr.acinq.eclair.plugins.peerswap.SwapResponses.{Response, Status}
import fr.acinq.eclair.plugins.peerswap.db.SwapsDb
import fr.acinq.eclair.plugins.peerswap.db.sqlite.SqliteSwapsDb
import fr.acinq.eclair.{CustomFeaturePlugin, Feature, InitFeature, Kit, NodeFeature, NodeParams, Plugin, PluginParams, RouteProvider, Setup, ShortChannelId, randomBytes32}
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import java.io.File
import java.nio.file.Files
import scala.concurrent.Future

/**
 * This plugin implements the PeerSwap protocol: https://github.com/ElementsProject/peerswap-spec/blob/main/peer-protocol.md
 */
object PeerSwapPlugin {
  // TODO: derive this set from peerSwapMessageCodec tags
  val peerSwapTags: Set[Int] = Set(42069, 42071, 42073, 42075, 42077, 42079, 42081)
}

class PeerSwapPlugin extends Plugin with RouteProvider with Logging {

  var db: SwapsDb = _
  var swapKeyManager: LocalSwapKeyManager = _
  var pluginKit: PeerSwapKit = _

  case object PeerSwapFeature extends Feature with InitFeature with NodeFeature {
    val rfcName = "peer_swap_plugin_prototype"
    val mandatory = 158
  }

  override def params: PluginParams = new CustomFeaturePlugin {
    // @formatter:off
    override def messageTags: Set[Int] = PeerSwapPlugin.peerSwapTags
    override def feature: Feature = PeerSwapFeature
    override def name: String = "PeerSwap"
    // @formatter:on
  }

  override def onSetup(setup: Setup): Unit = {
    val chain = setup.config.getString("chain")
    val chainDir = new File(setup.datadir, chain)
    db = new SqliteSwapsDb(SqliteUtils.openSqliteFile(chainDir, "peer-swap.sqlite", exclusiveLock = false, journalMode = "wal", syncFlag = "normal"))

    // load or generate seed
    val seedPath: File = new File(setup.datadir, "swap_seed.dat")
    val swapSeed: ByteVector = if (seedPath.exists()) {
      logger.info(s"use seed file: ${seedPath.getCanonicalPath}")
      ByteVector(Files.readAllBytes(seedPath.toPath))
    } else {
      val randomSeed = randomBytes32()
      Files.write(seedPath.toPath, randomSeed.toArray)
      logger.info(s"create new seed file: ${seedPath.getCanonicalPath}")
      randomSeed.bytes
    }
    swapKeyManager = new LocalSwapKeyManager(swapSeed, NodeParams.hashFromChain(chain))
  }

  override def onKit(kit: Kit): Unit = {
    val data = db.restore().toSet
    val swapRegister = kit.system.spawn(Behaviors.supervise(SwapRegister(kit.nodeParams, kit.paymentInitiator, kit.watcher, kit.register, kit.wallet, swapKeyManager, db, data)).onFailure(SupervisorStrategy.restart), "peerswap-plugin-swap-register")
    pluginKit = PeerSwapKit(kit.nodeParams, kit.system, swapRegister)
  }

  override def route(eclairDirectives: EclairDirectives): Route = ApiHandlers.registerRoutes(pluginKit, eclairDirectives)

}

case class PeerSwapKit(nodeParams: NodeParams, system: ActorSystem, swapRegister: ActorRef[SwapRegister.Command]) {
  def swapIn(shortChannelId: ShortChannelId, amount: Satoshi)(implicit timeout: Timeout): Future[Response] =
    swapRegister.ask(ref => SwapRegister.SwapInRequested(ref, amount, shortChannelId))(timeout, system.scheduler.toTyped)

  def swapOut(shortChannelId: ShortChannelId, amount: Satoshi)(implicit timeout: Timeout): Future[Response] =
    swapRegister.ask(ref => SwapRegister.SwapOutRequested(ref, amount, shortChannelId))(timeout, system.scheduler.toTyped)

  def listSwaps()(implicit timeout: Timeout): Future[Iterable[Status]] =
    swapRegister.ask(ref => SwapRegister.ListPendingSwaps(ref))(timeout, system.scheduler.toTyped)

  def cancelSwap(swapId: String)(implicit timeout: Timeout): Future[Response] =
    swapRegister.ask(ref => SwapRegister.CancelSwapRequested(ref, swapId))(timeout, system.scheduler.toTyped)
}
