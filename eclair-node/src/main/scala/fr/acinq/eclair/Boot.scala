package fr.acinq.eclair

import java.net.InetSocketAddress
import javafx.application.{Application, Platform}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{Base58Check, OP_CHECKSIG, OP_DUP, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, Script}
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.blockchain.peer.PeerClient
import fr.acinq.eclair.blockchain.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.blockchain.{ExtendedBitcoinClient, PeerWatcher}
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.gui.FxApp
import fr.acinq.eclair.io.{Server, Switchboard}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
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
      s.boostrap
    case _ => Application.launch(classOf[FxApp])
  }
}

class Setup() extends Logging {

  logger.info(s"hello!")
  val nodeParams = NodeParams.loadFromConfiguration()
  logger.info(s"nodeid=${nodeParams.privateKey.publicKey.toBin} alias=${nodeParams.alias}")
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
  val (chain, blockCount, progress) = Await.result(bitcoin_client.client.invoke("getblockchaininfo").map(json => ((json \ "chain").extract[String], (json \ "blocks").extract[Long], (json \ "verificationprogress").extract[Double])), 10 seconds)
  assert(chain == "test" || chain == "regtest" || chain == "segnet4", "you should be on testnet or regtest or segnet4")
  assert(progress > 0.99, "bitcoind should be synchronized")
  Globals.blockCount.set(blockCount)
  val bitcoinVersion = Await.result(bitcoin_client.client.invoke("getinfo").map(json => (json \ "version").extract[String]), 10 seconds)
  // we use it as final payment address, so that funds are moved to the bitcoind wallet upon channel termination
  val JString(finalAddress) = Await.result(bitcoin_client.client.invoke("getnewaddress"), 10 seconds)
  logger.info(s"finaladdress=$finalAddress")
  // TODO: we should use p2wpkh instead of p2pkh as soon as bitcoind supports it
  //val finalScriptPubKey = OP_0 :: OP_PUSHDATA(Base58Check.decode(finalAddress)._2) :: Nil
  val finalScriptPubKey = Script.write(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Base58Check.decode(finalAddress)._2) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil)
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
  val watcher = system.actorOf(PeerWatcher.props(bitcoin_client), name = "watcher")
  val paymentHandler = config.getString("eclair.payment-handler") match {
    case "local" => system.actorOf(Props[LocalPaymentHandler], name = "payment-handler")
    case "noop" => system.actorOf(Props[NoopPaymentHandler], name = "payment-handler")
  }
  val register = system.actorOf(Props(new Register), name = "register")
  val relayer = system.actorOf(Relayer.props(nodeParams.privateKey, paymentHandler), name = "relayer")
  val router = system.actorOf(Router.props(nodeParams, watcher), name = "router")
  val switchboard = system.actorOf(Switchboard.props(nodeParams, watcher, router, relayer, finalScriptPubKey), name = "switchboard")
  val paymentInitiator = system.actorOf(PaymentInitiator.props(nodeParams.privateKey.publicKey, router, register), "payment-initiator")
  val server = system.actorOf(Server.props(nodeParams, switchboard, new InetSocketAddress(config.getString("eclair.server.host"), config.getInt("eclair.server.port"))), "server")

  val _setup = this
  val api = new Service {
    override val switchboard: ActorRef = _setup.switchboard
    override val router: ActorRef = _setup.router
    override val register: ActorRef = _setup.register
    override val paymentHandler: ActorRef = _setup.paymentHandler
    override val paymentInitiator: ActorRef = _setup.paymentInitiator
    override val system: ActorSystem = _setup.system
  }
  Http().bindAndHandle(api.route, config.getString("eclair.api.host"), config.getInt("eclair.api.port")) onFailure {
    case t: Throwable => system.eventStream.publish(HTTPBindError)
  }

  val tasks = new Thread(new Runnable() {
    override def run(): Unit = {
      nodeParams.peersDb.values.foreach(rec => switchboard ! rec)
      nodeParams.channelsDb.values.foreach(rec => switchboard ! rec)
      nodeParams.announcementsDb.values.collect { case ann: ChannelAnnouncement => router ! ann }
      nodeParams.announcementsDb.values.collect { case ann: NodeAnnouncement => router ! ann }
      nodeParams.announcementsDb.values.collect { case ann: ChannelUpdate => router ! ann }
    }
  })

  def boostrap: Unit = tasks.start()
}
