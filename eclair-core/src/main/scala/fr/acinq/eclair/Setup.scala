package fr.acinq.eclair

import java.io.File
import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, Props, SupervisorStrategy}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.NodeParams.{BITCOINJ, ELECTRUM}
import fr.acinq.eclair.blockchain.bitcoinj.{BitcoinjKit, BitcoinjWallet, BitcoinjWatcher}
import fr.acinq.eclair.blockchain.electrum.{ElectrumClient, ElectrumEclairWallet, ElectrumWallet, ElectrumWatcher}
import fr.acinq.eclair.blockchain.fee.{ConstantFeeProvider, _}
import fr.acinq.eclair.blockchain.{EclairWallet, _}
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.io.Switchboard
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router._
import grizzled.slf4j.Logging

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}


/**
  * Created by PM on 25/01/2016.
  */
class Setup(datadir: File, wallet_opt: Option[EclairWallet] = None, overrideDefaults: Config = ConfigFactory.empty(), actorSystem: ActorSystem = ActorSystem()) extends Logging {

  logger.info(s"hello!")
  logger.info(s"version=${getClass.getPackage.getImplementationVersion} commit=${getClass.getPackage.getSpecificationVersion}")

  val config = NodeParams.loadConfiguration(datadir, overrideDefaults)
  val nodeParams = NodeParams.makeNodeParams(datadir, config)
  val chain = config.getString("chain")

  logger.info(s"nodeid=${nodeParams.privateKey.publicKey.toBin} alias=${nodeParams.alias}")
  logger.info(s"using chain=$chain chainHash=${nodeParams.chainHash}")

  implicit val system = actorSystem
  implicit val timeout = Timeout(30 seconds)
  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global

  def bootstrap: Future[Kit] = Future {

    val bitcoin = nodeParams.watcherType match {
      case BITCOINJ =>
        logger.warn("EXPERIMENTAL BITCOINJ MODE ENABLED!!!")
        val staticPeers = config.getConfigList("bitcoinj.static-peers").map(c => new InetSocketAddress(c.getString("host"), c.getInt("port"))).toList
        logger.info(s"using staticPeers=$staticPeers")
        val bitcoinjKit = new BitcoinjKit(chain, datadir, staticPeers)
        bitcoinjKit.startAsync()
        Await.ready(bitcoinjKit.initialized, 10 seconds)
        Bitcoinj(bitcoinjKit)
      case ELECTRUM =>
        logger.warn("EXPERIMENTAL ELECTRUM MODE ENABLED!!!")
        val addressesFile = chain match {
          case "test" => "/electrum/servers_testnet.json"
          case "regtest" => "/electrum/servers_regtest.json"
        }
        val stream = classOf[Setup].getResourceAsStream(addressesFile)
        val addresses = ElectrumClient.readServerAddresses(stream)
        val electrumClient = system.actorOf(SimpleSupervisor.props(Props(new ElectrumClient(addresses)), "electrum-client", SupervisorStrategy.Resume))
        Electrum(electrumClient)
      case _ => ???
    }

    val defaultFeerates = FeeratesPerByte(block_1 = config.getLong("default-feerates.delay-blocks.1"), blocks_2 = config.getLong("default-feerates.delay-blocks.2"), blocks_6 = config.getLong("default-feerates.delay-blocks.6"), blocks_12 = config.getLong("default-feerates.delay-blocks.12"), blocks_36 = config.getLong("default-feerates.delay-blocks.36"), blocks_72 = config.getLong("default-feerates.delay-blocks.72"))
    Globals.feeratesPerByte.set(defaultFeerates)
    Globals.feeratesPerKw.set(FeeratesPerKw(defaultFeerates))
    logger.info(s"initial feeratesPerByte=${Globals.feeratesPerByte.get()}")
    val feeProvider = (chain, bitcoin) match {
      case ("regtest", _) => new ConstantFeeProvider(defaultFeerates)
      case _ => new FallbackFeeProvider(new BitgoFeeProvider() :: new EarnDotComFeeProvider() :: new ConstantFeeProvider(defaultFeerates) :: Nil) // order matters!
    }
    system.scheduler.schedule(0 seconds, 10 minutes)(feeProvider.getFeerates.map {
      case feerates: FeeratesPerByte =>
        Globals.feeratesPerByte.set(feerates)
        Globals.feeratesPerKw.set(FeeratesPerKw(defaultFeerates))
        system.eventStream.publish(CurrentFeerates(Globals.feeratesPerKw.get))
        logger.info(s"current feeratesPerByte=${Globals.feeratesPerByte.get()}")
    })

    val watcher = bitcoin match {
      case Bitcoinj(bitcoinj) =>
        system.actorOf(SimpleSupervisor.props(BitcoinjWatcher.props(bitcoinj), "watcher", SupervisorStrategy.Resume))
      case Electrum(electrumClient) =>
        system.actorOf(SimpleSupervisor.props(Props(new ElectrumWatcher(electrumClient)), "watcher", SupervisorStrategy.Resume))
      case _ => ???
    }

    val wallet = bitcoin match {
      case _ if wallet_opt.isDefined => wallet_opt.get
      case Bitcoinj(bitcoinj) => new BitcoinjWallet(bitcoinj.initialized.map(_ => bitcoinj.wallet()))
      case Electrum(electrumClient) =>
        val electrumSeedPath = new File(datadir, "electrum_seed.dat")
        val electrumWallet = system.actorOf(ElectrumWallet.props(electrumSeedPath, electrumClient, ElectrumWallet.WalletParameters(Block.RegtestGenesisBlock.hash, allowSpendUnconfirmed = true)), "electrum-wallet")
        new ElectrumEclairWallet(electrumWallet)
      case _ => ???
    }

    wallet.getFinalAddress.map {
      case address => logger.info(s"initial wallet address=$address")
    }

    val paymentHandler = system.actorOf(SimpleSupervisor.props(config.getString("payment-handler") match {
      case "local" => LocalPaymentHandler.props(nodeParams)
      case "noop" => Props[NoopPaymentHandler]
    }, "payment-handler", SupervisorStrategy.Resume))
    val register = system.actorOf(SimpleSupervisor.props(Props(new Register), "register", SupervisorStrategy.Resume))
    val relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams, register, paymentHandler), "relayer", SupervisorStrategy.Resume))
    val router = system.actorOf(SimpleSupervisor.props(Router.props(nodeParams, watcher), "router", SupervisorStrategy.Resume))
    val switchboard = system.actorOf(SimpleSupervisor.props(Switchboard.props(nodeParams, watcher, router, relayer, wallet), "switchboard", SupervisorStrategy.Resume))
    val paymentInitiator = system.actorOf(SimpleSupervisor.props(PaymentInitiator.props(nodeParams.privateKey.publicKey, router, register), "payment-initiator", SupervisorStrategy.Restart))

    val kit = Kit(
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

    kit
  }

}

sealed trait Bitcoin

case class Bitcoinj(bitcoinjKit: BitcoinjKit) extends Bitcoin

case class Electrum(electrumClient: ActorRef) extends Bitcoin

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


