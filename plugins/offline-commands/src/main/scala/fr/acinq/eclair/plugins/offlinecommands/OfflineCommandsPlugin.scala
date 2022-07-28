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

import fr.acinq.eclair.{Kit, Plugin, PluginParams, Setup}
import grizzled.slf4j.Logging

/**
 * Created by t-bast on 28/07/2022.
 */

/**
 * This plugin allows node operators to prepare commands that will be executed once the target peer is online.
 */
class OfflineCommandsPlugin extends Plugin with Logging {

  override def onSetup(setup: Setup): Unit = {
    // TODO
  }

  override def onKit(kit: Kit): Unit = {
    // TODO
  }

  override def params: PluginParams = new PluginParams {
    override def name: String = "OfflineCommands"
  }

}
