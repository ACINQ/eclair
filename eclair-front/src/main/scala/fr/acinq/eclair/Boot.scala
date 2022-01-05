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
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigSyntax}
import grizzled.slf4j.Logging
import kamon.Kamon

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object Boot extends App with Logging {
  try {
    val datadir = new File(sys.props.getOrElse("eclair.datadir", sys.props("user.home") + "/.eclair"))
    val config = ConfigFactory.parseString(
      sys.env.getOrElse("AKKA_CONF", "").replace(";", "\n"),
      ConfigParseOptions.defaults().setSyntax(ConfigSyntax.PROPERTIES))
      .withFallback(ConfigFactory.parseProperties(System.getProperties))
      .withFallback(ConfigFactory.parseFile(new File(datadir, "eclair.conf")))
      .withFallback(ConfigFactory.load())

    // the actor system name needs to be the same for all members of the cluster
    implicit val system: ActorSystem = ActorSystem("eclair-node", config)
    implicit val ec: ExecutionContext = system.dispatcher

    val setup = new FrontSetup(datadir)

    if (config.getBoolean("eclair.enable-kamon")) {
      Kamon.init(config)
    }

    setup.bootstrap onComplete {
      case Success(_) => ()
      case Failure(t) => onError(t)
    }
  } catch {
    case t: Throwable => onError(t)
  }

  def onError(t: Throwable): Unit = {
    val errorMsg = if (t.getMessage != null) t.getMessage else t.getClass.getSimpleName
    System.err.println(s"fatal error: $errorMsg")
    logger.error(s"fatal error: $errorMsg", t)
    sys.exit(1)
  }
}
