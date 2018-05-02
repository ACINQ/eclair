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

import akka.actor.{ActorRef, ActorSystem, Props, SupervisorStrategy}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.{BinaryData, Block}
import fr.acinq.eclair.NodeParams.ELECTRUM
import fr.acinq.eclair.blockchain.electrum._
import fr.acinq.eclair.blockchain.fee.{ConstantFeeProvider, _}
import fr.acinq.eclair.blockchain.{EclairWallet, _}
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.io.{Authenticator, Switchboard}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router._
import grizzled.slf4j.Logging

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}


/**
  * Setup eclair from a datadir.
  *
  * Created by PM on 25/01/2016.
  *
  * @param datadir  directory where eclair-core will write/read its data
  * @param overrideDefaults
  * @param actorSystem
  * @param seed_opt optional seed, if set eclair will use it instead of generating one and won't create a seed.dat file.
  */
class Setup(datadir: File, wallet_opt: Option[EclairWallet] = None, overrideDefaults: Config = ConfigFactory.empty(), actorSystem: ActorSystem = ActorSystem(), seed_opt: Option[BinaryData] = None) extends Logging {

  logger.info(s"hello!")
  logger.info(s"version=${getClass.getPackage.getImplementationVersion} commit=${getClass.getPackage.getSpecificationVersion}")
  logger.info(s"datadir=${datadir.getCanonicalPath}")

  val config = NodeParams.loadConfiguration(datadir, overrideDefaults)
  val seed = seed_opt.getOrElse(NodeParams.getSeed(datadir))
  val chain = config.getString("chain")
  val keyManager = new LocalKeyManager(seed, NodeParams.makeChainHash(chain))
  val nodeParams = NodeParams.makeNodeParams(datadir, config, keyManager)

  logger.info(s"nodeid=${nodeParams.nodeId} alias=${nodeParams.alias}")
  logger.info(s"using chain=$chain chainHash=${nodeParams.chainHash}")

  implicit val system = actorSystem
  implicit val timeout = Timeout(30 seconds)
  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global

  def bootstrap: Future[Kit] =
    for {
      _ <- Future.successful(true)
      feeratesRetrieved = Promise[Boolean]()

      bitcoin = nodeParams.watcherType match {
        case ELECTRUM =>
          logger.warn("EXPERIMENTAL ELECTRUM MODE ENABLED!!!")
          val addressesFile = nodeParams.chainHash match {
            case Block.RegtestGenesisBlock.hash => "/electrum/servers_regtest.json"
            case Block.TestnetGenesisBlock.hash => "/electrum/servers_testnet.json"
            case Block.LivenetGenesisBlock.hash => "/electrum/servers_mainnet.json"
          }
          val stream = classOf[Setup].getResourceAsStream(addressesFile)
          val addresses = ElectrumClientPool.readServerAddresses(stream)
          val electrumClient = system.actorOf(SimpleSupervisor.props(Props(new ElectrumClientPool(addresses)), "electrum-client", SupervisorStrategy.Resume))
          Electrum(electrumClient)
        case _ => ???
      }

      defaultFeerates = FeeratesPerKB(
        block_1 = config.getLong("default-feerates.delay-blocks.1"),
        blocks_2 = config.getLong("default-feerates.delay-blocks.2"),
        blocks_6 = config.getLong("default-feerates.delay-blocks.6"),
        blocks_12 = config.getLong("default-feerates.delay-blocks.12"),
        blocks_36 = config.getLong("default-feerates.delay-blocks.36"),
        blocks_72 = config.getLong("default-feerates.delay-blocks.72")
      )
      minFeeratePerByte = config.getLong("min-feerate")
      feeProvider = (nodeParams.chainHash, bitcoin) match {
        case (Block.RegtestGenesisBlock.hash, _) => new ConstantFeeProvider(defaultFeerates)
        case _ => new FallbackFeeProvider(new BitgoFeeProvider(nodeParams.chainHash) :: new EarnDotComFeeProvider() :: new ConstantFeeProvider(defaultFeerates) :: Nil, minFeeratePerByte) // order matters!
      }
      _ = system.scheduler.schedule(0 seconds, 10 minutes)(feeProvider.getFeerates.map {
        case feerates: FeeratesPerKB =>
          Globals.feeratesPerKB.set(feerates)
          Globals.feeratesPerKw.set(FeeratesPerKw(feerates))
          system.eventStream.publish(CurrentFeerates(Globals.feeratesPerKw.get))
          logger.info(s"current feeratesPerKB=${Globals.feeratesPerKB.get()} feeratesPerKw=${Globals.feeratesPerKw.get()}")
          feeratesRetrieved.trySuccess(true)
      })
      _ <- feeratesRetrieved.future

      watcher = bitcoin match {
        case Electrum(electrumClient) =>
          system.actorOf(SimpleSupervisor.props(Props(new ElectrumWatcher(electrumClient)), "watcher", SupervisorStrategy.Resume))
        case _ => ???
      }

      wallet = bitcoin match {
        case _ if wallet_opt.isDefined => wallet_opt.get
        case Electrum(electrumClient) =>
          val electrumWallet = system.actorOf(ElectrumWallet.props(seed, electrumClient, ElectrumWallet.WalletParameters(nodeParams.chainHash)), "electrum-wallet")
          new ElectrumEclairWallet(electrumWallet, nodeParams.chainHash)
        case _ => ???
      }
      _ = wallet.getFinalAddress.map {
        case address => logger.info(s"initial wallet address=$address")
      }

      paymentHandler = system.actorOf(SimpleSupervisor.props(config.getString("payment-handler") match {
        case "local" => LocalPaymentHandler.props(nodeParams)
        case "noop" => Props[NoopPaymentHandler]
      }, "payment-handler", SupervisorStrategy.Resume))
      register = system.actorOf(SimpleSupervisor.props(Props(new Register), "register", SupervisorStrategy.Resume))
      relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams, register, paymentHandler), "relayer", SupervisorStrategy.Resume))
      router = system.actorOf(SimpleSupervisor.props(Router.props(nodeParams, watcher), "router", SupervisorStrategy.Resume))
      authenticator = system.actorOf(SimpleSupervisor.props(Authenticator.props(nodeParams), "authenticator", SupervisorStrategy.Resume))
      switchboard = system.actorOf(SimpleSupervisor.props(Switchboard.props(nodeParams, authenticator, watcher, router, relayer, wallet), "switchboard", SupervisorStrategy.Resume))
      paymentInitiator = system.actorOf(SimpleSupervisor.props(PaymentInitiator.props(nodeParams.privateKey.publicKey, router, register), "payment-initiator", SupervisorStrategy.Restart))

      kit = Kit(
        nodeParams = nodeParams,
        system = system,
        watcher = watcher,
        paymentHandler = paymentHandler,
        register = register,
        relayer = relayer,
        router = router,
        switchboard = switchboard,
        paymentInitiator = paymentInitiator,
        wallet = wallet)
    } yield kit

}

// @formatter:off
sealed trait Bitcoin
case class Electrum(electrumClient: ActorRef) extends Bitcoin
// @formatter:on

case class Kit(nodeParams: NodeParams,
               system: ActorSystem,
               watcher: ActorRef,
               paymentHandler: ActorRef,
               register: ActorRef,
               relayer: ActorRef,
               router: ActorRef,
               switchboard: ActorRef,
               paymentInitiator: ActorRef,
               wallet: EclairWallet)


case object BitcoinWalletDisabledException extends RuntimeException("bitcoind must have wallet support enabled")

case object EmptyAPIPasswordException extends RuntimeException("must set a password for the json-rpc api")

case object IncompatibleDBException extends RuntimeException("database is not compatible with this version of eclair")

case object IncompatibleNetworkDBException extends RuntimeException("network database is not compatible with this version of eclair")
