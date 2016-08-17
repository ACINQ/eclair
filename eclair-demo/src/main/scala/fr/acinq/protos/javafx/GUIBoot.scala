package fr.acinq.protos.javafx

import javafx.application.Application
import javafx.event.{ActionEvent, EventHandler}
import javafx.geometry.{Insets, Orientation, Pos}
import javafx.scene.Scene
import javafx.scene.control._
import javafx.scene.layout.{BorderPane, HBox, VBox}
import javafx.stage.Stage

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.Globals
import fr.acinq.eclair.Globals._
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.blockchain.{ExtendedBitcoinClient, PollingWatcher}
import fr.acinq.eclair.channel.{ChannelEvent, LocalPaymentHandler, NoopPaymentHandler, Register}
import fr.acinq.eclair.io.{Client, Server}
import fr.acinq.eclair.router.IRCRouter
import grizzled.slf4j.Logging

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._


/**
  * Created by PM on 16/08/2016.
  */
class GUIBoot extends Application {

  val root = new BorderPane()

  val menuBar = new MenuBar()
  val menuFile = new Menu("File")
  val itemConnect = new MenuItem("Open channel")
  val itemSend = new MenuItem("Send")
  val itemReceive = new MenuItem("Receive")

  menuFile.getItems.addAll(itemConnect, new SeparatorMenuItem(), itemSend, itemReceive)
  menuBar.getMenus().addAll(menuFile)
  root.setTop(menuBar)

  val vBoxPane = new VBox()
  vBoxPane.setSpacing(4)
  vBoxPane.setPadding(new Insets(8, 4, 4, 8))
  root.setCenter(vBoxPane)

  val hBoxPane = new HBox()
  hBoxPane.setSpacing(4)
  hBoxPane.setPadding(new Insets(0, 4, 0, 4))
  hBoxPane.setAlignment(Pos.CENTER_RIGHT)
  val labelApi = new Label(s"Listening on HTTP ${GUIBoot.config.getInt("eclair.api.port")}")
  val separator1 = new Separator(Orientation.VERTICAL)
  val labelServer = new Label(s"Listening on TCP ${GUIBoot.config.getInt("eclair.server.port")}")
  val separator2 = new Separator(Orientation.VERTICAL)
  val labelBitcoin = new Label(s"Connected to bitcoin-core ${GUIBoot.bitcoinVersion} (${GUIBoot.chain})")
  hBoxPane.getChildren.addAll(labelApi, separator1, labelServer, separator2, labelBitcoin)
  root.setBottom(hBoxPane)

  val scene = new Scene(root, 1200, 250)

  override def start(primaryStage: Stage): Unit = {
    primaryStage.setTitle(s"Eclair ${Globals.Node.id}")
    primaryStage.setScene(scene)
    itemConnect.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent): Unit = new DialogOpen(primaryStage).showAndWait()
    })
    itemSend.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent): Unit = new DialogSend(primaryStage).showAndWait()
    })
    itemReceive.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent): Unit = new DialogReceive(primaryStage).showAndWait()
    })
    primaryStage.show()
    val guiUpdater = GUIBoot.system.actorOf(Props(classOf[GUIUpdater], this), "gui-updater")
    GUIBoot.system.eventStream.subscribe(guiUpdater, classOf[ChannelEvent])
  }

}

object GUIBoot extends App with Logging {

    logger.info(s"hello!")
    logger.info(s"nodeid=${Globals.Node.publicKey}")
    val config = ConfigFactory.load()

    implicit lazy val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    implicit val timeout = Timeout(30 seconds)
    implicit val formats = org.json4s.DefaultFormats
    implicit val ec = ExecutionContext.Implicits.global

    val chain = Await.result(bitcoin_client.invoke("getblockchaininfo").map(json => (json \ "chain").extract[String]), 10 seconds)
    assert(chain == "testnet" || chain == "regtest" || chain == "segnet4", "you should be on testnet or regtest or segnet4")
    val bitcoinVersion = Await.result(bitcoin_client.invoke("getinfo").map(json => (json \ "version").extract[String]), 10 seconds)

    val blockchain = system.actorOf(Props(new PollingWatcher(new ExtendedBitcoinClient(bitcoin_client))), name = "blockchain")
    val paymentHandler = config.getString("eclair.payment-handler") match {
      case "local" => system.actorOf(Props[LocalPaymentHandler], name = "payment-handler")
      case "noop" => system.actorOf(Props[NoopPaymentHandler], name = "payment-handler")
    }
    lazy val register = system.actorOf(Register.props(blockchain, paymentHandler), name = "register")
    val router = system.actorOf(IRCRouter.props(new ExtendedBitcoinClient(bitcoin_client)), name = "router")

    val server = system.actorOf(Server.props(config.getString("eclair.server.host"), config.getInt("eclair.server.port"), register), "server")
    val _setup = this
    val api = new Service {
      override val register: ActorRef = _setup.register
      override val router: ActorRef = _setup.router
      override def paymentHandler: ActorRef = _setup.paymentHandler

      override def connect(host: String, port: Int, amount: Long): Unit = system.actorOf(Client.props(host, port, amount, register))
    }
    Http().bindAndHandle(api.route, config.getString("eclair.api.host"), config.getInt("eclair.api.port"))

  val handlers = new Handlers()

  Application.launch(classOf[GUIBoot])
}