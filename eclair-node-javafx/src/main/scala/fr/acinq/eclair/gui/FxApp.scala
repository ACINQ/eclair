package fr.acinq.eclair.gui

import java.io.File
import javafx.application.Preloader.ErrorNotification
import javafx.application.{Application, Platform}
import javafx.event.EventHandler
import javafx.fxml.FXMLLoader
import javafx.scene.image.Image
import javafx.scene.{Parent, Scene}
import javafx.stage.{Popup, Screen, Stage, WindowEvent}

import akka.actor.{ActorSystem, Props, SupervisorStrategy}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.zmq.ZMQEvents
import fr.acinq.eclair.channel.ChannelEvent
import fr.acinq.eclair.gui.controllers.{MainController, NotificationsController}
import fr.acinq.eclair.payment.PaymentEvent
import fr.acinq.eclair.router.NetworkEvent
import grizzled.slf4j.Logging

import scala.concurrent.Promise


/**
  * Created by PM on 16/08/2016.
  */
class FxApp extends Application with Logging {

  override def init = {
    logger.debug("initializing application...")
  }

  override def start(primaryStage: Stage): Unit = {
    val icon = new Image(getClass.getResource("/gui/commons/images/eclair-square.png").toExternalForm, false)
    primaryStage.getIcons.add(icon)

    new Thread(new Runnable {
      override def run(): Unit = {
        try {
          val datadir = new File(getParameters.getUnnamed.get(0))
          implicit val system = ActorSystem("system")
          val setup = new Setup(datadir)
          val pKit = Promise[Kit]()
          val handlers = new Handlers(pKit.future)
          val controller = new MainController(handlers, setup, getHostServices)
          val guiUpdater = setup.system.actorOf(SimpleSupervisor.props(Props(classOf[GUIUpdater], controller), "gui-updater", SupervisorStrategy.Resume))
          setup.system.eventStream.subscribe(guiUpdater, classOf[ChannelEvent])
          setup.system.eventStream.subscribe(guiUpdater, classOf[NetworkEvent])
          setup.system.eventStream.subscribe(guiUpdater, classOf[PaymentEvent])
          setup.system.eventStream.subscribe(guiUpdater, classOf[ZMQEvents])

          Platform.runLater(new Runnable {
            override def run(): Unit = {
              val mainFXML = new FXMLLoader(getClass.getResource("/gui/main/main.fxml"))
              mainFXML.setController(controller)
              val mainRoot = mainFXML.load[Parent]
              val scene = new Scene(mainRoot)

              primaryStage.setTitle("Eclair")
              primaryStage.setMinWidth(600)
              primaryStage.setWidth(960)
              primaryStage.setMinHeight(400)
              primaryStage.setHeight(640)
              primaryStage.setOnCloseRequest(new EventHandler[WindowEvent] {
                override def handle(event: WindowEvent) {
                  System.exit(0)
                }
              })
              notifyPreloader(new AppNotification(SuccessAppNotification, "Init successful"))
              primaryStage.setScene(scene)
              primaryStage.show
              initNotificationStage(primaryStage, handlers)
              pKit.completeWith(setup.bootstrap)
            }
          })

        } catch {
          case TCPBindException(port) =>
            notifyPreloader(new ErrorNotification("Setup", s"Could not bind to port $port", null))
          case BitcoinRPCConnectionException =>
            notifyPreloader(new ErrorNotification("Setup", "Could not connect to Bitcoin Core using JSON-RPC.", null))
            notifyPreloader(new AppNotification(InfoAppNotification, "Make sure that Bitcoin Core is up and running and RPC parameters are correct."))
          case BitcoinZMQConnectionTimeoutException =>
            notifyPreloader(new ErrorNotification("Setup", "Could not connect to Bitcoin Core using ZMQ.", null))
            notifyPreloader(new AppNotification(InfoAppNotification, "Make sure that Bitcoin Core is up and running and ZMQ parameters are correct."))
          case t: Throwable =>
            notifyPreloader(new ErrorNotification("Setup", s"Internal error: ${t.toString}", t))
        }
      }
    }).start

  }

  /**
    * Initialize the notification stage and assign it to the handler class.
    *
    * @param owner         stage owning the notification stage
    * @param notifhandlers Handles the notifications
    */
  private def initNotificationStage(owner: Stage, notifhandlers: Handlers) = {
    // get fxml/controller
    val notifFXML = new FXMLLoader(getClass.getResource("/gui/main/notifications.fxml"))
    val notifsController = new NotificationsController
    notifFXML.setController(notifsController)
    val root = notifFXML.load[Parent]

    Platform.runLater(new Runnable() {
      override def run = {
        // create scene
        val popup = new Popup
        popup.setHideOnEscape(false)
        popup.setAutoFix(false)
        val margin = 10
        val width = 300
        popup.setWidth(margin + width)
        popup.getContent.add(root)
        // positioning the popup @ TOP RIGHT of screen
        val screenBounds = Screen.getPrimary.getVisualBounds
        popup.show(owner, screenBounds.getMaxX - (margin + width), screenBounds.getMinY + margin)
        notifhandlers.initNotifications(notifsController)
      }
    })
  }
}