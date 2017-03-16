package fr.acinq.eclair.gui

import java.net.ConnectException
import javafx.application.Preloader.ErrorNotification
import javafx.application.{Application, Platform}
import javafx.event.EventHandler
import javafx.fxml.FXMLLoader
import javafx.scene.image.Image
import javafx.scene.{Parent, Scene}
import javafx.stage.{Popup, Screen, Stage, WindowEvent}

import akka.actor.Props
import akka.stream.StreamTcpException
import fr.acinq.eclair.Setup
import fr.acinq.eclair.channel.ChannelEvent
import fr.acinq.eclair.gui.controllers.{MainController, NotificationsController}
import fr.acinq.eclair.router.NetworkEvent
import grizzled.slf4j.Logging


/**
  * Created by PM on 16/08/2016.
  */
class FxApp extends Application with Logging {

  var mainController: Option[MainController] = None
  var setup: Option[Setup] = None
  var handlers: Option[Handlers] = None
  var isReady = false

  override def init = {
    logger.debug("initializing application...")
    try {
      import scala.concurrent.ExecutionContext.Implicits.global
      val datadir = getParameters.getUnnamed.get(0)
      setup = Option(new Setup(datadir))
      setup.get.fatalEventFuture onSuccess {
        case e => notifyPreloader(new ErrorNotification("Setup", "Error when booting node", null))
      }

      handlers = Option(new Handlers(setup.get))
      val controller = new MainController(handlers.get, setup.get, getHostServices)
      val guiUpdater = setup.get.system.actorOf(Props(classOf[GUIUpdater], controller, setup.get), "gui-updater")
      setup.get.system.eventStream.subscribe(guiUpdater, classOf[ChannelEvent])
      setup.get.system.eventStream.subscribe(guiUpdater, classOf[NetworkEvent])

      mainController = Option(controller)
      isReady = true
      notifyPreloader(new AppNotification(SuccessAppNotification, "Init successful"))
    } catch {
      case _: ConnectException | _: StreamTcpException =>
        notifyPreloader(new ErrorNotification("Setup", "Could not connect to Bitcoin-core.", null))
        notifyPreloader(new AppNotification(InfoAppNotification, "Please check that Bitcoin-core is started and that the RPC user, password and port are correct."))
      case t: Throwable =>
        notifyPreloader(new ErrorNotification("Setup", s"Internal error: ${t.toString}", t))
    }
  }

  override def start(primaryStage: Stage): Unit = {
    val icon = new Image(getClass.getResource("/gui/commons/images/eclair-square.png").toExternalForm, false)
    primaryStage.getIcons.add(icon)

    if (isReady && mainController.isDefined && setup.isDefined) {
      val mainFXML = new FXMLLoader(getClass.getResource("/gui/main/main.fxml"))
      mainFXML.setController(mainController.get)
      val mainRoot = mainFXML.load[Parent]
      val scene = new Scene(mainRoot)

      primaryStage.setTitle("Eclair")
      primaryStage.setMinWidth(570)
      primaryStage.setWidth(650)
      primaryStage.setMinHeight(400)
      primaryStage.setHeight(400)
      primaryStage.setOnCloseRequest(new EventHandler[WindowEvent] {
        override def handle(event: WindowEvent) {
          System.exit(0)
        }
      })

      primaryStage.setScene(scene)
      primaryStage.show
      initNotificationStage(primaryStage, handlers.get)
      setup.get.boostrap
    } else {
      notifyPreloader(new AppNotification(FatalAppNotification, "Fatal error"))
    }
  }

  /**
    * Initialize the notification stage and assign it to the handler class.
    *
    * @param owner stage owning the notification stage
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