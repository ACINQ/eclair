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

package fr.acinq.eclair.plugins.offlinecommands

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.typed.{ActorRef, SupervisorStrategy}
import akka.http.scaladsl.server.Route
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.db.sqlite.SqliteUtils
import fr.acinq.eclair.{Kit, NodeParams, Plugin, PluginParams, RouteProvider, Setup}
import grizzled.slf4j.Logging

import java.io.File

/**
 * Created by t-bast on 28/07/2022.
 */

/**
 * This plugin allows node operators to prepare commands that will be executed once the target peer is online.
 */
class OfflineCommandsPlugin extends Plugin with RouteProvider with Logging {

  var db: OfflineCommandsDb = _
  var pluginKit: OfflineCommandsKit = _

  override def params: PluginParams = new PluginParams {
    override def name: String = "OfflineCommands"
  }

  override def onSetup(setup: Setup): Unit = {
    // We create our DB in the per-chain data directory.
    val chain = setup.config.getString("chain")
    val chainDir = new File(setup.datadir, chain)
    db = new SqliteOfflineCommandsDb(SqliteUtils.openSqliteFile(chainDir, "offline-commands.sqlite", exclusiveLock = false, journalMode = "wal", syncFlag = "normal"))
  }

  override def onKit(kit: Kit): Unit = {
    val channelsCloser = kit.system.spawn(Behaviors.supervise(OfflineChannelsCloser(kit.nodeParams, db, kit.register)).onFailure(SupervisorStrategy.restart), "offline-commands-plugin-channels-closer")
    pluginKit = OfflineCommandsKit(kit.nodeParams, db, kit.system, channelsCloser)
  }

  override def route(eclairDirectives: EclairDirectives): Route = ApiHandlers.registerRoutes(pluginKit, eclairDirectives)

}

case class OfflineCommandsKit(nodeParams: NodeParams, db: OfflineCommandsDb, system: ActorSystem, closer: ActorRef[OfflineChannelsCloser.Command])
