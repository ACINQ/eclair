package fr.acinq.eclair

import java.net.InetSocketAddress
import javafx.application.{Application, Platform}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{Base58Check, BinaryData, MilliSatoshi, OP_CHECKSIG, OP_DUP, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, Satoshi}
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.blockchain.peer.PeerClient
import fr.acinq.eclair.blockchain.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.blockchain.{ExtendedBitcoinClient, PeerWatcher}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.gui.FxApp
import fr.acinq.eclair.io.{Client, Server}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router._
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JString

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Promise}

/**
  * Created by PM on 25/01/2016.
  */
object Boot extends App with Logging {
  args.toList match {
    case "headless" :: rest =>
      val s = new Setup()
      import ExecutionContext.Implicits.global
      s.fatalEventFuture.map(e => {
        logger.error(s"received fatal event $e")
        Platform.exit()
      })
    case _ => Application.launch(classOf[FxApp])
  }
}

class Setup() extends Logging {

  logger.info(s"hello!")
  logger.info(s"nodeid=${Globals.Node.publicKey.toBin} alias=${Globals.Node.alias}")
  val config = ConfigFactory.load()

  implicit lazy val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(30 seconds)

  val bitcoin_client = new ExtendedBitcoinClient(new BitcoinJsonRPCClient(
    user = config.getString("eclair.bitcoind.rpcuser"),
    password = config.getString("eclair.bitcoind.rpcpassword"),
    host = config.getString("eclair.bitcoind.host"),
    port = config.getInt("eclair.bitcoind.rpcport")))

  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global
  val (chain, blockCount) = Await.result(bitcoin_client.client.invoke("getblockchaininfo").map(json => ((json \ "chain").extract[String], (json \ "blocks").extract[Long])), 10 seconds)
  assert(chain == "testnet" || chain == "regtest" || chain == "segnet4", "you should be on testnet or regtest or segnet4")
  val bitcoinVersion = Await.result(bitcoin_client.client.invoke("getinfo").map(json => (json \ "version").extract[String]), 10 seconds)
  // we use it as final payment address, so that funds are moved to the bitcoind wallet upon channel termination
  val JString(finalAddress) = Await.result(bitcoin_client.client.invoke("getnewaddress"), 10 seconds)
  logger.info(s"finaladdress=$finalAddress")
  // TODO: we should use p2wpkh instead of p2pkh as soon as bitcoind supports it
  //val finalScriptPubKey = OP_0 :: OP_PUSHDATA(Base58Check.decode(finalAddress)._2) :: Nil
  val finalScriptPubKey = OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Base58Check.decode(finalAddress)._2) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil
  val socket = new InetSocketAddress(config.getString("eclair.server.host"), config.getInt("eclair.server.port"))

  val fatalEventPromise = Promise[FatalEvent]()
  system.actorOf(Props(new Actor {
    system.eventStream.subscribe(self, classOf[FatalEvent])

    override def receive: Receive = {
      case e: FatalEvent => fatalEventPromise.success(e)

    }
  }))
  val fatalEventFuture = fatalEventPromise.future

  val peer = system.actorOf(Props[PeerClient], "bitcoin-peer")
  val watcher = system.actorOf(PeerWatcher.props(bitcoin_client, blockCount), name = "watcher")
  val paymentHandler = config.getString("eclair.payment-handler") match {
    case "local" => system.actorOf(Props[LocalPaymentHandler], name = "payment-handler")
    case "noop" => system.actorOf(Props[NoopPaymentHandler], name = "payment-handler")
  }
  val relayer = system.actorOf(Relayer.props(Globals.Node.privateKey, paymentHandler), name = "relayer")
  val router = system.actorOf(Router.props(watcher), name = "router")
  val paymentInitiator = system.actorOf(PaymentInitiator.props(Globals.Node.publicKey, router, blockCount), "payment-initiator")
  val register = system.actorOf(Register.props(watcher, router, relayer, finalScriptPubKey), name = "register")
  val server = system.actorOf(Server.props(config.getString("eclair.server.host"), config.getInt("eclair.server.port"), register), "server")

  val _setup = this
  val api = new Service {
    override val register: ActorRef = _setup.register
    override val router: ActorRef = _setup.router
    override val paymentHandler: ActorRef = _setup.paymentHandler
    override val paymentInitiator: ActorRef = _setup.paymentInitiator

    override def connect(host: String, port: Int, pubkey: BinaryData, fundingSatoshis: Satoshi, pushMsat: MilliSatoshi): Unit = system.actorOf(Client.props(host, port, pubkey, fundingSatoshis, pushMsat, register))
  }
  Http().bindAndHandle(api.route, config.getString("eclair.api.host"), config.getInt("eclair.api.port")) onFailure {
    case t: Throwable => system.eventStream.publish(HTTPBindError)
  }
}
