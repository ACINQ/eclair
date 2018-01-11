package fr.acinq.eclair.gui

import javafx.application.Preloader
import javafx.application.Preloader.{ErrorNotification, PreloaderNotification}
import javafx.fxml.FXMLLoader
import javafx.scene.image.Image
import javafx.scene.paint.Color
import javafx.scene.{Parent, Scene}
import javafx.stage.{Stage, StageStyle}

import fr.acinq.eclair.gui.controllers.SplashController
import grizzled.slf4j.Logging

sealed trait AppNotificationType

case object SuccessAppNotification extends AppNotificationType

case object InfoAppNotification extends AppNotificationType

case class AppNotification(notificationType: AppNotificationType, message: String) extends PreloaderNotification

/**
  * Created by DPA on 15/03/2017.
  */
class FxPreloader extends Preloader with Logging {

  var controller: Option[SplashController] = None
  var stage: Option[Stage] = None

  override def start(primaryStage: Stage) = {
    setupStage(primaryStage)
    primaryStage.show
    stage = Option(primaryStage)
  }

  private def setupStage(stage: Stage) = {
    val icon = new Image(getClass.getResource("/gui/commons/images/eclair-square.png").toExternalForm, false)
    stage.getIcons.add(icon)

    // set stage props
    stage.initStyle(StageStyle.TRANSPARENT)
    stage.setResizable(false)

    // get fxml/controller
    val splashController = new SplashController(getHostServices)
    val splash = new FXMLLoader(getClass.getResource("/gui/splash/splash.fxml"))
    splash.setController(splashController)
    val root = splash.load[Parent]

    // create scene
    val scene = new Scene(root)
    scene.setFill(Color.TRANSPARENT)

    stage.setScene(scene)
    controller = Option(splashController)
  }

  override def handleApplicationNotification(info: PreloaderNotification) = {
    info match {
      case n: ErrorNotification =>
        logger.debug(s"Preloader error notification => ${n.getDetails}")
        logger.error("", n.getCause)
        controller.map(_.addError(n.getDetails))
        controller.map(_.showErrorBox)
      case n: AppNotification =>
        logger.debug(s"Preloader app notification => ${n.notificationType}, ${n.message}")
        n.notificationType match {
          case SuccessAppNotification => stage.map(_.close)
          case InfoAppNotification => controller.map(_.addLog(n.message))
          case _ =>
        }
      case _ =>
        logger.debug(s"Notification ${info}")
    }
  }
}
