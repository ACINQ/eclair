package fr.acinq.eclair.gui

import java.io.{File, FileWriter}
import java.net.InetSocketAddress
import javafx.application.Platform
import javafx.scene.control.TextArea

import akka.pattern.ask
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.gui.controllers._
import fr.acinq.eclair.gui.utils.GUIValidators
import fr.acinq.eclair.io.Switchboard.{NewChannel, NewConnection}
import fr.acinq.eclair.payment.CreatePayment
import grizzled.slf4j.Logging

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * Created by PM on 16/08/2016.
  */
class Handlers(setup: Setup) extends Logging {

  import setup._

  private var notifsController:Option[NotificationsController] = None

  def initNotifications (controller: NotificationsController) = {
    notifsController = Option(controller)
  }

  def open(hostPort: String, fundingSatoshis: Satoshi, pushMsat: MilliSatoshi) = {
    hostPort match {
      case GUIValidators.hostRegex(remoteNodeId, host, port) =>
        logger.info(s"opening a channel with remoteNodeId=$remoteNodeId")
        (for {
          address <- Future(new InetSocketAddress(host, port.toInt))
          pubkey = PublicKey(remoteNodeId)
          conn <- setup.switchboard ? NewConnection(pubkey, address, Some(NewChannel(fundingSatoshis, pushMsat)))
        } yield conn) onFailure {
          case t =>
            val message = s"$host:$port\nCause: ${t.getMessage}"
            notification("Connection failed", message, NOTIFICATION_ERROR)
        }
      case _ => {}
    }
  }

  def send(nodeId: String, rhash: String, amountMsat: String) = {
    logger.info(s"sending $amountMsat to $rhash @ $nodeId")
    (paymentInitiator ? CreatePayment(amountMsat.toLong, rhash, PublicKey(nodeId))).mapTo[String].onComplete {
      case Success(s) =>
        val message = s"Amount (mSat): $amountMsat\nH: $rhash"
        notification("Payment Successful", message, NOTIFICATION_SUCCESS)
      case Failure(t) =>
        val message = s"Cause: ${t.getMessage}\nAmount (mSat): $amountMsat\nH: $rhash"
        notification("Payment Failed", message, NOTIFICATION_ERROR)
    }
  }

  def getPaymentRequest(amountMsat: Long, textarea: TextArea) = {
    (paymentHandler ? 'genh).mapTo[BinaryData].map { h =>
      Platform.runLater(new Runnable() {
        override def run = {
          textarea.setText(s"${setup.nodeParams.privateKey.publicKey}:$amountMsat:${h.toString()}")
          textarea.requestFocus
          textarea.selectAll
        }
      })
    }
  }

  def exportToDot(file: File) = (router ? 'dot).mapTo[String].map(
    dot => printToFile(file)(writer => writer.write(dot)))

  private def printToFile(f: java.io.File)(op: java.io.FileWriter => Unit) {
    val p = new FileWriter(f)
    try {
      op(p)
    } finally {
      p.close()
    }
  }

  /**
    * Displays a system notification if the system supports it.
    *
    * @param title Title of the notification
    * @param message content of the notification. Accepts line break
    * @param notificationType type of the message, default to NONE
    * @param showAppName true if you want the notification title to be preceded by "Eclair - ". True by default
    */
  def notification (title: String, message: String, notificationType: NotificationType = NOTIFICATION_NONE, showAppName: Boolean = true) = {
    notifsController.map(_.addNotification(if (showAppName) s"Eclair - $title" else title, message, notificationType))
  }
}
