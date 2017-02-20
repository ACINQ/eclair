package fr.acinq.eclair.gui.controllers

import javafx.animation._
import javafx.application.Platform
import javafx.event.{ActionEvent, EventHandler}
import javafx.fxml.{FXML, FXMLLoader}
import javafx.scene.image.Image
import javafx.scene.layout.{GridPane, VBox}
import javafx.util.Duration

import fr.acinq.eclair.gui.stages.NotificationsStage
import grizzled.slf4j.Logging

sealed trait NotificationType
case object NOTIFICATION_NONE extends NotificationType
case object NOTIFICATION_SUCCESS extends NotificationType
case object NOTIFICATION_ERROR extends NotificationType
case object NOTIFICATION_INFO extends NotificationType

/**
  * Created by DPA on 17/02/2017.
  */
class NotificationsController extends Logging {
  @FXML var notifsVBox: VBox = _

  val successIcon: Image = new Image(getClass.getResource("/gui/commons/images/success_icon.png").toExternalForm, true)
  val errorIcon: Image = new Image(getClass.getResource("/gui/commons/images/error_icon.png").toExternalForm, true)
  val infoIcon: Image = new Image(getClass.getResource("/gui/commons/images/info_icon.png").toExternalForm, true)

  /**
    * Adds a notification panel to the notifications stage
    *
    * @param title Title of the notification, should not be too long
    * @param body Body of the notification
    * @param notificationType type of the notification (error, warning, success, info...)
    */
  def addNotification (title: String, body: String, notificationType: NotificationType, stage: Option[NotificationsStage]) = {

    val loader = new FXMLLoader(getClass.getResource("/gui/main/notificationPane.fxml"))
    val notifPaneController = new NotificationPaneController
    loader.setController(notifPaneController)

    Platform.runLater(new Runnable() {
      override def run = {
        val root = loader.load[GridPane]
        notifsVBox.getChildren.add(root)

        notifPaneController.titleLabel.setText(title)
        notifPaneController.bodyLabel.setText(body)
        notificationType match {
          case NOTIFICATION_SUCCESS => notifPaneController.icon.setImage(successIcon)
          case NOTIFICATION_ERROR => notifPaneController.icon.setImage(errorIcon)
          case NOTIFICATION_INFO => notifPaneController.icon.setImage(infoIcon)
          case _ =>
        }

        notifPaneController.closeButton.setOnAction(new EventHandler[ActionEvent] {
          override def handle(event: ActionEvent) = {
            val fadeOutTransition = new FadeTransition(Duration.millis(200))
            fadeOutTransition.setFromValue(1)
            fadeOutTransition.setToValue(0)
            val translateRevTransition = new TranslateTransition(Duration.millis(450))
            translateRevTransition.setFromX(0)
            translateRevTransition.setToX(150)
            val scaleTransition = new ScaleTransition(Duration.millis(350))
            scaleTransition.setFromY(1)
            scaleTransition.setToY(0)
            val ptR = new ParallelTransition(notifPaneController.rootPane, fadeOutTransition, translateRevTransition, scaleTransition)
            ptR.setOnFinished(new EventHandler[ActionEvent] {
              override def handle(event: ActionEvent) = notifsVBox.getChildren.remove(root)
            })
            ptR.play
          }
        })
        val fadeTransition = new FadeTransition(Duration.millis(400))
        fadeTransition.setFromValue(0)
        fadeTransition.setToValue(.95)
        val translateTransition = new TranslateTransition(Duration.millis(500))
        translateTransition.setFromX(150)
        translateTransition.setToX(0)
        val pt = new ParallelTransition(notifPaneController.rootPane, fadeTransition, translateTransition)
        pt.play
      }
    })
  }
}
