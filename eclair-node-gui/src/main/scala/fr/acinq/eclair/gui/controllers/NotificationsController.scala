package fr.acinq.eclair.gui.controllers

import javafx.animation._
import javafx.application.Platform
import javafx.event.{ActionEvent, EventHandler}
import javafx.fxml.{FXML, FXMLLoader}
import javafx.scene.Parent
import javafx.scene.image.Image
import javafx.scene.layout.{GridPane, VBox}
import javafx.util.Duration

import fr.acinq.eclair.gui.utils.ContextMenuUtils
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
  val errorIcon: Image = new Image(getClass.getResource("/gui/commons/images/warning_icon.png").toExternalForm, true)
  val infoIcon: Image = new Image(getClass.getResource("/gui/commons/images/info_icon.png").toExternalForm, true)

  /**
    * Adds a notification panel to the notifications stage
    *
    * @param title            Title of the notification, should not be too long
    * @param message          Main message of the notification
    * @param notificationType type of the notification (error, warning, success, info...)
    */
  def addNotification(title: String, message: String, notificationType: NotificationType) = {

    val loader = new FXMLLoader(getClass.getResource("/gui/main/notificationPane.fxml"))
    val notifPaneController = new NotificationPaneController
    loader.setController(notifPaneController)

    Platform.runLater(new Runnable() {
      override def run = {
        val root = loader.load[GridPane]
        notifsVBox.getChildren.add(root)

        // set notification content
        notifPaneController.titleLabel.setText(title)
        notifPaneController.messageLabel.setText(message.capitalize)
        val autoDismissed = notificationType match {
          case NOTIFICATION_SUCCESS => {
            notifPaneController.rootPane.setStyle("-fx-border-color: #28d087")
            notifPaneController.icon.setImage(successIcon)
            true
          }
          case NOTIFICATION_ERROR => {
            notifPaneController.rootPane.setStyle("-fx-border-color: #d43c4e")
            notifPaneController.icon.setImage(errorIcon)
            false
          }
          case NOTIFICATION_INFO => {
            notifPaneController.rootPane.setStyle("-fx-border-color: #409be6")
            notifPaneController.icon.setImage(infoIcon)
            true
          }
          case _ => true
        }

        // in/out animations
        val showAnimation = getShowAnimation(notifPaneController.rootPane)

        val dismissAnimation = getDismissAnimation(notifPaneController.rootPane)
        dismissAnimation.setOnFinished(new EventHandler[ActionEvent] {
          override def handle(event: ActionEvent) = notifsVBox.getChildren.remove(root)
        })
        notifPaneController.copyButton.setOnAction(new EventHandler[ActionEvent] {
          override def handle(event: ActionEvent) = {
            dismissAnimation.stop() // automatic dismiss is cancelled
            ContextMenuUtils.copyToClipboard(message)
            notifPaneController.copyButton.setOnAction(null)
            notifPaneController.copyButton.setText("Copied!")
          }
        })
        notifPaneController.closeButton.setOnAction(new EventHandler[ActionEvent] {
          override def handle(event: ActionEvent) = {
            dismissAnimation.stop()
            dismissAnimation.setDelay(Duration.ZERO)
            dismissAnimation.play()
          }
        })
        showAnimation.play()
        if (autoDismissed) {
          dismissAnimation.setDelay(Duration.seconds(12))
          dismissAnimation.play()
        }
      }
    })
  }

  private def getDismissAnimation(element: Parent): Transition = {
    val fadeOutTransition = new FadeTransition(Duration.millis(200))
    fadeOutTransition.setFromValue(1)
    fadeOutTransition.setToValue(0)
    val translateRevTransition = new TranslateTransition(Duration.millis(450))
    translateRevTransition.setFromX(0)
    translateRevTransition.setToX(150)
    val scaleTransition = new ScaleTransition(Duration.millis(350))
    scaleTransition.setFromY(1)
    scaleTransition.setToY(0)
    new ParallelTransition(element, fadeOutTransition, translateRevTransition, scaleTransition)
  }

  private def getShowAnimation(element: Parent): Transition = {
    val fadeTransition = new FadeTransition(Duration.millis(400))
    fadeTransition.setFromValue(0)
    fadeTransition.setToValue(.95)
    val translateTransition = new TranslateTransition(Duration.millis(500))
    translateTransition.setFromX(150)
    translateTransition.setToX(0)
    new ParallelTransition(element, fadeTransition, translateTransition)
  }
}
