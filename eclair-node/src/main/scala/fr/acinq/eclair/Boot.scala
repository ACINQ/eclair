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

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.BindFailedException
import fr.acinq.eclair.NotificationsLogger.NotifyNodeOperator
import fr.acinq.eclair.api.Service
import grizzled.slf4j.Logging
import kamon.Kamon

import java.io.File
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
 * Created by PM on 25/01/2016.
 */
object Boot extends App with Logging {
  try {
    val datadir = new File(System.getProperty("eclair.datadir", System.getProperty("user.home") + "/.eclair"))
    val config = NodeParams.loadConfiguration(datadir)

    val plugins = Plugin.loadPlugins(args.toIndexedSeq.map(new File(_)))
    plugins.foreach(plugin => logger.info(s"loaded plugin ${plugin.getClass.getSimpleName}"))
    implicit val system: ActorSystem = ActorSystem("eclair-node", config)
    implicit val ec: ExecutionContext = system.dispatcher

    val setup = new Setup(datadir, plugins.map(_.params))

    if (config.getBoolean("eclair.enable-kamon")) {
      Kamon.init(config)
    }

    plugins.foreach(_.onSetup(setup))
    setup.bootstrap onComplete {
      case Success(kit) =>
        plugins.foreach(_.onKit(kit))
        val routeProviderPlugins = plugins.collect { case plugin: RouteProvider => plugin }
        startApiServiceIfEnabled(kit, routeProviderPlugins)
        kit.system.eventStream.publish(NotifyNodeOperator(NotificationsLogger.Info, s"eclair successfully started (version=${Kit.getVersion} commit=${Kit.getCommit})"))
      case Failure(t) => onError(t)
    }
  } catch {
    case t: Throwable => onError(t)
  }

  /**
   * Starts the http APIs service if enabled in the configuration
   */
  def startApiServiceIfEnabled(kit: Kit, providers: Seq[RouteProvider] = Nil)(implicit system: ActorSystem, ec: ExecutionContext) = {
    val config = system.settings.config.getConfig("eclair")
    if (config.getBoolean("api.enabled")) {
      logger.info(s"json API enabled on port=${config.getInt("api.port")}")
      val apiPassword = config.getString("api.password") match {
        case "" => throw EmptyAPIPasswordException
        case valid => valid
      }
      val service: Service = new Service {
        override val actorSystem: ActorSystem = system
        override val password: String = apiPassword
        override val eclairApi: Eclair = new EclairImpl(kit)
      }
      Http().newServerAt(config.getString("api.binding-ip"), config.getInt("api.port")).bindFlow(service.finalRoutes(providers)).recover {
        case _: BindFailedException => onError(TCPBindException(config.getInt("api.port")))
      }
    } else {
      logger.info("json API disabled")
    }
  }

  def onError(t: Throwable): Unit = {
    val errorMsg = if (t.getMessage != null) t.getMessage else t.getClass.getSimpleName
    System.err.println(s"fatal error: $errorMsg")
    logger.error(s"fatal error: $errorMsg", t)
    NotificationsLogger.logFatalError("could not start eclair", t)
    sys.exit(1)
  }
}
