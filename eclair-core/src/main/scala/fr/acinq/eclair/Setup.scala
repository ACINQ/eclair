package fr.acinq.eclair

import java.io.File
import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, Props, SupervisorStrategy}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction}
import fr.acinq.eclair.blockchain.electrum.{ElectrumClient, ElectrumWallet, ElectrumWatcher}
import fr.acinq.eclair.blockchain.{CurrentFeerate, SpvWatcher}
import fr.acinq.eclair.blockchain.fee.{BitpayInsightFeeProvider, ConstantFeeProvider}
import fr.acinq.eclair.blockchain.spv.BitcoinjKit
import fr.acinq.eclair.blockchain.wallet.{BitcoinjWallet, EclairWallet, MakeFundingTxResponse}
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
  val spv = config.getBoolean("spv")
  val chain = config.getString("chain")

  logger.info(s"nodeid=${nodeParams.privateKey.publicKey.toBin} alias=${nodeParams.alias}")
  logger.info(s"using chain=$chain chainHash=${nodeParams.chainHash}")

  implicit val system = actorSystem
  implicit val timeout = Timeout(30 seconds)
  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global

  logger.warn("EXPERIMENTAL SPV MODE ENABLED!!!")
  val stream = chain match {
    case "test" => classOf[Setup].getResourceAsStream("/electrum/servers_testnet.json")
    case "regtest" => classOf[Setup].getResourceAsStream("/electrum/servers_regtest.json")
  }
  val addresses = ElectrumClient.readServerAddresses(stream)
  stream.close()

  val electrumClient =  system.actorOf(SimpleSupervisor.props(Props(new ElectrumClient(addresses)), "electrum-client", SupervisorStrategy.Resume))
  val watcher = system.actorOf(SimpleSupervisor.props(Props(new ElectrumWatcher(electrumClient)), "watcher", SupervisorStrategy.Resume))
  val electrumSeedPath = new File(datadir, "electrum_seed.dat")
  val electrumWallet = system.actorOf(ElectrumWallet.props(electrumSeedPath, electrumClient), "electrum-wallet")

  def bootstrap: Future[Kit] = Future {

    val defaultFeeratePerKb = config.getLong("default-feerate-per-kb")
    Globals.feeratePerKw.set(feerateKb2Kw(defaultFeeratePerKb))
    logger.info(s"initial feeratePerKw=${Globals.feeratePerKw.get()}")
    val feeProvider = chain match {
      case "regtest" => new ConstantFeeProvider(defaultFeeratePerKb)
      case _ => new BitpayInsightFeeProvider()
    }
    system.scheduler.schedule(0 seconds, 10 minutes)(feeProvider.getFeeratePerKB.map {
      case feeratePerKB =>
        Globals.feeratePerKw.set(feerateKb2Kw(feeratePerKB))
        system.eventStream.publish(CurrentFeerate(Globals.feeratePerKw.get()))
        logger.info(s"current feeratePerKw=${Globals.feeratePerKw.get()}")
    })

    val wallet = new fr.acinq.eclair.blockchain.wallet.ElectrumWallet(electrumWallet)
    wallet.getFinalAddress.map {
      case address => logger.info(s"initial wallet address=$address")
    }

    val paymentHandler = system.actorOf(SimpleSupervisor.props(config.getString("payment-handler") match {
      case "local" => LocalPaymentHandler.props(nodeParams)
      case "noop" => Props[NoopPaymentHandler]
    }, "payment-handler", SupervisorStrategy.Resume))
    val register = system.actorOf(SimpleSupervisor.props(Props(new Register), "register", SupervisorStrategy.Resume))
    val relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams.privateKey, paymentHandler), "relayer", SupervisorStrategy.Resume))
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


