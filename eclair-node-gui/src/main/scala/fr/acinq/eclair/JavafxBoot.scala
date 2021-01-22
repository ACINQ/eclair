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

import java.io.File

import akka.actor.ActorSystem
import fr.acinq.eclair.gui.{FxApp, FxPreloader}
import grizzled.slf4j.Logging
import javafx.application.Application
import scala.concurrent.ExecutionContext.Implicits.global
/**
  * Created by PM on 25/01/2016.
  */
object JavafxBoot extends App with Logging {
  try {
    val datadir = new File(System.getProperty("eclair.datadir", System.getProperty("user.home") + "/.eclair"))
    val config = NodeParams.loadConfiguration(datadir)
    val headless = System.getProperty("eclair.headless") != null

    if (headless) {
      implicit val system = ActorSystem("eclair-node-gui", config)
      val setup = new Setup(datadir, pluginParams = Seq.empty)
      setup.bootstrap.map { kit =>
        Boot.startApiServiceIfEnabled(kit)
      }
    } else {
      System.setProperty("javafx.preloader", classOf[FxPreloader].getName)
      Application.launch(classOf[FxApp], datadir.getAbsolutePath)
    }
  } catch {
    case t: Throwable =>
      val errorMsg = if (t.getMessage != null) t.getMessage else t.getClass.getSimpleName
      System.err.println(s"fatal error: $errorMsg")
      logger.error(s"fatal error: $errorMsg", t)
      System.exit(1)
  }
}
