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
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import akka.Done
import akka.actor.{ActorRef, ActorSystem, Props, SupervisorStrategy}
import akka.util.Timeout
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.NodeParams.ELECTRUM
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.SSL
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool.ElectrumServerAddress
import fr.acinq.eclair.blockchain.electrum._
import fr.acinq.eclair.blockchain.electrum.db.sqlite.SqliteWalletDb
import fr.acinq.eclair.blockchain.fee.{ConstantFeeProvider, _}
import fr.acinq.eclair.blockchain.{EclairWallet, _}
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.db.sqlite.SqliteFeeratesDb
import fr.acinq.eclair.db.{BackupHandler, Databases}
import fr.acinq.eclair.io.{ClientSpawner, Switchboard}
import fr.acinq.eclair.payment.Auditor
import fr.acinq.eclair.payment.receive.PaymentHandler
import fr.acinq.eclair.payment.relay.{CommandBuffer, Relayer}
import fr.acinq.eclair.payment.send.PaymentInitiator
import fr.acinq.eclair.router._
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import scala.concurrent._
import scala.concurrent.duration._

/**
 * Setup eclair from a data directory.
 *
 * Created by PM on 25/01/2016.
 *
 * @param datadir  directory where eclair-core will write/read its data.
 * @param seed_opt optional seed, if set eclair will use it instead of generating one and won't create a seed.dat file.
 * @param db       optional databases to use, if not set eclair will create the necessary databases
 */
class Setup(datadir: File,
            seed_opt: Option[ByteVector] = None,
            db: Option[Databases] = None,
            eclairWallet_opt: Option[EclairWallet] = None)(implicit system: ActorSystem) extends Logging {

  implicit val timeout = Timeout(30 seconds)
  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global
  implicit val sttpBackend = OkHttpFutureBackend()

  logger.info(s"hello!")
  logger.info(s"version=${Kit.getVersion} commit=${Kit.getCommit}")
  logger.info(s"datadir=${datadir.getCanonicalPath}")


  datadir.mkdirs()
  val config = system.settings.config.getConfig("eclair")
  val seed = seed_opt.getOrElse(NodeParams.getSeed(datadir))
  val chain = config.getString("chain")
  val chaindir = new File(datadir, chain)
  val keyManager = new LocalKeyManager(seed, NodeParams.hashFromChain(chain))

  val database = db match {
    case Some(d) => d
    case None => Databases.sqliteJDBC(chaindir)
  }

  /**
   * This counter holds the current blockchain height.
   * It is mainly used to calculate htlc expiries.
   * The value is read by all actors, hence it needs to be thread-safe.
   */
  val blockCount = new AtomicLong(0)

  /**
   * This holds the current feerates, in satoshi-per-kilobytes.
   * The value is read by all actors, hence it needs to be thread-safe.
   */
  val feeratesPerKB = new AtomicReference[FeeratesPerKB](null)

  /**
   * This holds the current feerates, in satoshi-per-kw.
   * The value is read by all actors, hence it needs to be thread-safe.
   */
  val feeratesPerKw = new AtomicReference[FeeratesPerKw](null)

  val feeEstimator = new FeeEstimator {
    // @formatter:off
    override def getFeeratePerKb(target: Int): Long = feeratesPerKB.get().feePerBlock(target)
    override def getFeeratePerKw(target: Int): Long = feeratesPerKw.get().feePerBlock(target)
    // @formatter:on
  }

  val nodeParams = NodeParams.makeNodeParams(config, keyManager, None, database, blockCount, feeEstimator)

  val serverBindingAddress = new InetSocketAddress(
    config.getString("server.binding-ip"),
    config.getInt("server.port"))

  // early checks
  DBCompatChecker.checkDBCompatibility(nodeParams)
  DBCompatChecker.checkNetworkDBCompatibility(nodeParams)
  PortChecker.checkAvailable(serverBindingAddress)

  logger.info(s"nodeid=${nodeParams.nodeId} alias=${nodeParams.alias}")
  logger.info(s"using chain=$chain chainHash=${nodeParams.chainHash}")

  def setFeerates(feerates: FeeratesPerKB, feeratesRetrieved: Promise[Done]): Unit = {
    feeratesPerKB.set(feerates)
    feeratesPerKw.set(FeeratesPerKw(feerates))
    channel.Monitoring.Metrics.LocalFeeratePerKw.withoutTags().update(feeratesPerKw.get.feePerBlock(nodeParams.onChainFeeConf.feeTargets.commitmentBlockTarget))
    system.eventStream.publish(CurrentFeerates(feeratesPerKw.get))
    logger.info(s"current feeratesPerKB=${feeratesPerKB.get} feeratesPerKw=${feeratesPerKw.get}")
    feeratesRetrieved.trySuccess(Done)
  }

  def bootstrap: Future[Kit] =
    for {
      _ <- Future.successful(true)
      feeratesRetrieved = Promise[Done]()
      postRestartCleanUpInitialized = Promise[Done]()

      bitcoin: Bitcoin = nodeParams.watcherType match {
        case ELECTRUM =>
          val addresses = config.hasPath("electrum") match {
            case true =>
              val host = config.getString("electrum.host")
              val port = config.getInt("electrum.port")
              val address = InetSocketAddress.createUnresolved(host, port)
              val ssl = config.getString("electrum.ssl") match {
                case _ if address.getHostName.endsWith(".onion") => SSL.OFF // Tor already adds end-to-end encryption, adding TLS on top doesn't add anything
                case "off" => SSL.OFF
                case "loose" => SSL.LOOSE
                case _ => SSL.STRICT // strict mode is the default when we specify a custom electrum server, we don't want to be MITMed
              }

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
          val electrumClient = system.actorOf(SimpleSupervisor.props(Props(new ElectrumClientPool(blockCount, addresses, nodeParams.socksProxy_opt)), "electrum-client", SupervisorStrategy.Resume))
          Electrum(electrumClient)
        case _ => ???
      }

      defaultFeerates = {
        val confDefaultFeerates = FeeratesPerKB(
          block_1 = config.getLong("on-chain-fees.default-feerates.1"),
          blocks_2 = config.getLong("on-chain-fees.default-feerates.2"),
          blocks_6 = config.getLong("on-chain-fees.default-feerates.6"),
          blocks_12 = config.getLong("on-chain-fees.default-feerates.12"),
          blocks_36 = config.getLong("on-chain-fees.default-feerates.36"),
          blocks_72 = config.getLong("on-chain-fees.default-feerates.72"),
          blocks_144 = config.getLong("on-chain-fees.default-feerates.144")
        )
        feeratesPerKB.set(confDefaultFeerates)
        feeratesPerKw.set(FeeratesPerKw(confDefaultFeerates))
        confDefaultFeerates
      }
      minFeeratePerByte = config.getLong("min-feerate")
      smoothFeerateWindow = config.getInt("smooth-feerate-window")
      readTimeout = FiniteDuration(config.getDuration("feerate-provider-timeout", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
      feeProvider = (nodeParams.chainHash, bitcoin) match {
        case (Block.RegtestGenesisBlock.hash, _) => new FallbackFeeProvider(new ConstantFeeProvider(defaultFeerates) :: Nil, minFeeratePerByte)
        case _ =>
          val sqlite = DriverManager.getConnection(s"jdbc:sqlite:${new File(chaindir, "feerates.sqlite")}")
          val db = new SqliteFeeratesDb(sqlite)
          db.getFeerates().foreach { feerates =>
            logger.info(s"starting with feerates previously stored in db: feeratesPerKB=$feeratesPerKB")
            setFeerates(feerates, feeratesRetrieved)
          }
          new DbFeeProvider(
            db,
            new FallbackFeeProvider(
              new SmoothFeeProvider(new BitgoFeeProvider(nodeParams.chainHash, readTimeout), smoothFeerateWindow) ::
                new SmoothFeeProvider(new EarnDotComFeeProvider(readTimeout), smoothFeerateWindow) :: Nil, // order matters!
              minFeeratePerByte)
          )
      }
      _ = system.scheduler.schedule(0 seconds, 10 minutes)(feeProvider.getFeerates.map(feerates => setFeerates(feerates, feeratesRetrieved)))
      _ <- feeratesRetrieved.future // if we're using a db fee provider and we already had stored a feerate, then the promise will already be completed

      watcher = bitcoin match {
        case Electrum(electrumClient) =>
          system.actorOf(SimpleSupervisor.props(Props(new ElectrumWatcher(blockCount, electrumClient)), "watcher", SupervisorStrategy.Resume))
        case _ => ???
      }

      router = system.actorOf(SimpleSupervisor.props(Router.props(nodeParams, watcher, None), "router", SupervisorStrategy.Resume))

      wallet = eclairWallet_opt match {
        case Some(eclairWallet) => eclairWallet
        case None => bitcoin match {
          case Electrum(electrumClient) =>
            val sqlite = DriverManager.getConnection(s"jdbc:sqlite:${new File(chaindir, "wallet.sqlite")}")
            val walletDb = new SqliteWalletDb(sqlite)
            val electrumWallet = system.actorOf(ElectrumWallet.props(seed, electrumClient, ElectrumWallet.WalletParameters(nodeParams.chainHash, walletDb)), "electrum-wallet")
            implicit val timeout = Timeout(30 seconds)
            new ElectrumEclairWallet(electrumWallet, nodeParams.chainHash)
          case _ => ???
        }
      }
      _ = wallet.getReceiveAddress.map(address => logger.info(s"initial wallet address=$address"))
      // do not change the name of this actor. it is used in the configuration to specify a custom bounded mailbox

      backupHandler = if (config.getBoolean("enable-db-backup")) {
        system.actorOf(SimpleSupervisor.props(
          BackupHandler.props(
            nodeParams.db,
            new File(chaindir, "eclair.sqlite.bak"),
            if (config.hasPath("backup-notify-script")) Some(config.getString("backup-notify-script")) else None
          ), "backuphandler", SupervisorStrategy.Resume))
      } else {
        logger.warn("database backup is disabled")
        system.deadLetters
      }
      audit = system.actorOf(SimpleSupervisor.props(Auditor.props(nodeParams), "auditor", SupervisorStrategy.Resume))
      register = system.actorOf(SimpleSupervisor.props(Props(new Register), "register", SupervisorStrategy.Resume))
      commandBuffer = system.actorOf(SimpleSupervisor.props(Props(new CommandBuffer(nodeParams, register)), "command-buffer", SupervisorStrategy.Resume))
      paymentHandler = system.actorOf(SimpleSupervisor.props(PaymentHandler.props(nodeParams, commandBuffer), "payment-handler", SupervisorStrategy.Resume))
      relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams, router, register, commandBuffer, paymentHandler, Some(postRestartCleanUpInitialized)), "relayer", SupervisorStrategy.Resume))
      // Before initializing the switchboard (which re-connects us to the network) and the user-facing parts of the system,
      // we want to make sure the handler for post-restart broken HTLCs has finished initializing.
      _ <- postRestartCleanUpInitialized.future
      switchboard = system.actorOf(SimpleSupervisor.props(Switchboard.props(nodeParams, watcher, relayer, paymentHandler, wallet), "switchboard", SupervisorStrategy.Resume))
      clientSpawner = system.actorOf(SimpleSupervisor.props(ClientSpawner.props(nodeParams, switchboard, router), "client-spawner", SupervisorStrategy.Restart))
      paymentInitiator = system.actorOf(SimpleSupervisor.props(PaymentInitiator.props(nodeParams, router, register), "payment-initiator", SupervisorStrategy.Restart))

      kit = Kit(
        nodeParams = nodeParams,
        system = system,
        watcher = watcher,
        paymentHandler = paymentHandler,
        register = register,
        commandBuffer = commandBuffer,
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
               commandBuffer: ActorRef,
               relayer: ActorRef,
               router: ActorRef,
               switchboard: ActorRef,
               paymentInitiator: ActorRef,
               wallet: EclairWallet)

object Kit {

  def getVersionLong: String = s"$getVersion-$getCommit"

  def getVersion: String = getClass.getPackage.getImplementationVersion

  def getCommit: String = Option(getClass.getPackage.getSpecificationVersion).map(_.take(7)).getOrElse("null")

}

case object BitcoinWalletDisabledException extends RuntimeException("bitcoind must have wallet support enabled")

case object EmptyAPIPasswordException extends RuntimeException("must set a password for the json-rpc api")

case object IncompatibleDBException extends RuntimeException("database is not compatible with this version of eclair")

case object IncompatibleNetworkDBException extends RuntimeException("network database is not compatible with this version of eclair")
