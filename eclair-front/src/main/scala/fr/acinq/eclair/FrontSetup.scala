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
import java.net.InetSocketAddress

import akka.Done
import akka.actor.{ActorSystem, Address, Props, RootActorPath, SupervisorStrategy}
import akka.pattern.ask
import akka.util.Timeout
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClient
import com.amazonaws.services.secretsmanager.model.GetSecretValueRequest
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.io.Switchboard.{GetRouterPeerConf, RouterPeerConf}
import fr.acinq.eclair.io.{ClientSpawner, Server}
import fr.acinq.eclair.router.FrontRouter
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

class FrontSetup(datadir: File)(implicit system: ActorSystem) extends Logging {

  implicit val timeout = Timeout(30 seconds)
  implicit val ec: ExecutionContext = system.dispatcher

  logger.info(s"hello!")
  logger.info(s"version=${getClass.getPackage.getImplementationVersion} commit=${getClass.getPackage.getSpecificationVersion}")
  logger.info(s"datadir=${datadir.getCanonicalPath}")
  logger.info(s"initializing secure random generator")
  // this will force the secure random instance to initialize itself right now, making sure it doesn't hang later (see comment in package.scala)
  secureRandom.nextInt()

  datadir.mkdirs()

  val config = system.settings.config.getConfig("eclair")

  val keyPair = {
    val pub = ByteVector.fromValidHex(config.getString("front.pub"))
    val priv = config.getString("front.priv-key-provider") match {
      case "aws-sm" =>
        val sm = AWSSecretsManagerClient.builder().build()
        try {
          // we retrieve the node key from AWS secrets manager and we compare the corresponding pub key with the expected one
          val secretId = config.getString("front.aws-sm.priv-key-name")
          ByteVector.fromValidHex(sm.getSecretValue(new GetSecretValueRequest().withSecretId(secretId)).getSecretString)
        } finally {
          sm.shutdown()
        }
      case "env" => ByteVector.fromValidHex(config.getString("front.priv-key"))
    }
    val keyPair = KeyPair(pub, priv)
    require(PrivateKey(priv).publicKey == PublicKey(pub), "priv/pub keys mismatch")
    keyPair
  }

  logger.info(s"nodeid=${keyPair.pub.toHex}")

  val serverBindingAddress = new InetSocketAddress(
    config.getString("server.binding-ip"),
    config.getInt("server.port"))

  def bootstrap: Future[Unit] = {

    val frontJoinedCluster = Promise[Done]()
    val backendAddressFound = Promise[Address]()
    val tcpBound = Promise[Done]()

    for {
      _ <- Future.successful(0)

      _ = system.actorOf(Props(new ClusterListener(frontJoinedCluster, backendAddressFound)), name = "cluster-listener")
      _ <- frontJoinedCluster.future
      backendAddress <- backendAddressFound.future

      // we give time for the cluster to be ready
      _ <- akka.pattern.after(5.seconds)(Future.successful())

      switchBoardSelection = system.actorSelection(RootActorPath(backendAddress) / "user" / "*" / "switchboard")
      remoteSwitchboard <- switchBoardSelection.resolveOne()
      routerSelection = system.actorSelection(RootActorPath(backendAddress) / "user" / "*" / "router")
      remoteRouter <- routerSelection.resolveOne()

      RouterPeerConf(routerConf, peerConnectionConf) <- (remoteSwitchboard ? GetRouterPeerConf).mapTo[RouterPeerConf]

      frontRouterInitialized = Promise[Done]()
      frontRouter = system.actorOf(SimpleSupervisor.props(FrontRouter.props(routerConf, remoteRouter, Some(frontRouterInitialized)), "front-router", SupervisorStrategy.Resume))
      _ <- frontRouterInitialized.future

      clientSpawner = system.actorOf(Props(new ClientSpawner(keyPair, None, peerConnectionConf, remoteSwitchboard, frontRouter)), name = "client-spawner")

      server = system.actorOf(SimpleSupervisor.props(Server.props(keyPair, peerConnectionConf, remoteSwitchboard, frontRouter, serverBindingAddress, Some(tcpBound)), "server", SupervisorStrategy.Restart))
    } yield ()
  }

}
