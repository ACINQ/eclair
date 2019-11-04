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

import akka.actor.{ActorRef, ActorSystem, Props, SupervisorStrategy}
import akka.http.scaladsl.Http
import akka.stream.{ActorMaterializer, BindFailedException}
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.recovery.{RecoverySwitchBoard, RecoveryTool}
import grizzled.slf4j.Logging
import kamon.Kamon

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
  * Created by PM on 25/01/2016.
  */
object Boot extends App with Logging {

  val datadir = new File(System.getProperty("eclair.datadir", System.getProperty("user.home") + "/.eclair"))

  try {
    val plugins = Plugin.loadPlugins(args.map(new File(_)))
    plugins.foreach(plugin => logger.info(s"loaded plugin ${plugin.getClass.getSimpleName}"))
    implicit val system: ActorSystem = ActorSystem("eclair-node")
    implicit val ec: ExecutionContext = system.dispatcher

    val setup = if(ConfigFactory.load().hasPath("eclair.recovery-tool")){
      new Setup(datadir) {
        logger.info(s"recovery mode enabled")
        override def getSwitchboard(authenticator: ActorRef, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet): ActorRef = {
          system.actorOf(SimpleSupervisor.props(Props(new RecoverySwitchBoard(nodeParams, authenticator, watcher, router, relayer, wallet)), "recovery-switchboard", SupervisorStrategy.Resume))
        }
      }
    } else {
      new Setup(datadir)
    }

    if (setup.config.getBoolean("enable-kamon")) {
      Kamon.init(setup.appConfig)
    }

    plugins.foreach(_.onSetup(setup))
    setup.bootstrap onComplete {
      case Success(kit) =>
        startApiServiceIfEnabled(setup.config, kit)
        plugins.foreach(_.onKit(kit))
        if(setup.config.hasPath("recovery-tool")){
          RecoveryTool.interactiveRecovery(kit)
        }

      case Failure(t) => onError(t)
    }
  } catch {
    case t: Throwable => onError(t)
  }

  /**
    * Starts the http APIs service if enabled in the configuration
    *
    * @param config
    * @param kit
    * @param system
    * @param ec
    */
  def startApiServiceIfEnabled(config: Config, kit: Kit)(implicit system: ActorSystem, ec: ExecutionContext) = {
    if(config.getBoolean("api.enabled")){
      logger.info(s"json API enabled on port=${config.getInt("api.port")}")
      implicit val materializer = ActorMaterializer()
      val apiPassword = config.getString("api.password") match {
        case "" => throw EmptyAPIPasswordException
        case valid => valid
      }
      val apiRoute = new Service {
        override val actorSystem = system
        override val mat = materializer
        override val password = apiPassword
        override val eclairApi: Eclair = new EclairImpl(kit)
      }.route
      Http().bindAndHandle(apiRoute, config.getString("api.binding-ip"), config.getInt("api.port")).onFailure {
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
    System.exit(1)
  }
}
