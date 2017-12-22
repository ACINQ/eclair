package fr.acinq.eclair.gui

import java.io.{File, FileWriter}
import java.text.NumberFormat
import java.util.Locale

import akka.pattern.ask
import akka.util.Timeout
import fr.acinq.bitcoin.MilliSatoshi
import fr.acinq.eclair._
import fr.acinq.eclair.gui.controllers._
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.payment._
import grizzled.slf4j.Logging

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * Created by PM on 16/08/2016.
  */
class Handlers(fKit: Future[Kit])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends Logging {

  implicit val timeout = Timeout(30 seconds)

  private var notifsController: Option[NotificationsController] = None

  def initNotifications(controller: NotificationsController) = {
    notifsController = Option(controller)
  }

  /**
    * Opens a connection to a node. If the channel option exists this will also open a channel with the node, with a
    * `fundingSatoshis` capacity and `pushMsat` amount.
    *
    * @param nodeUri
    * @param channel
    */
  def open(nodeUri: NodeURI, channel: Option[Peer.OpenChannel]) = {
    logger.info(s"opening a connection to nodeUri=$nodeUri")
    (for {
          kit <- fKit
          conn <- kit.switchboard ? Peer.Connect(nodeUri)
          _ <- channel match {
            case Some(o) =>
              logger.info(s"opening a channel with remoteNodeId=${o.remoteNodeId}")
              kit.switchboard ? o
            case None => Future.successful(0) // nothing to do
          }
    } yield conn) onFailure {
          case t: Throwable =>
            t.printStackTrace()
            notification("Connection failed", s"${nodeUri.address.getHostString}:${nodeUri.address.getPort}", NOTIFICATION_ERROR)
    }
  }

  def send(overrideAmountMsat_opt: Option[Long], req: PaymentRequest) = {
    val amountMsat = overrideAmountMsat_opt
      .orElse(req.amount.map(_.amount))
      .getOrElse(throw new RuntimeException("you need to manually specify an amount for this payment request"))

    logger.info(s"sending $amountMsat to ${req.paymentHash} @ ${req.nodeId}")
    val sendPayment = req.minFinalCltvExpiry match {
      case None => SendPayment(amountMsat, req.paymentHash, req.nodeId, req.routingInfo())
      case Some(minFinalCltvExpiry) => SendPayment(amountMsat, req.paymentHash, req.nodeId, req.routingInfo(), minFinalCltvExpiry = minFinalCltvExpiry)
    }
    (for {
      kit <- fKit
      res <- (kit.paymentInitiator ? sendPayment).mapTo[PaymentResult]
    } yield res)
      .onComplete {
        case Success(_: PaymentSucceeded) =>
          val message = s"${NumberFormat.getInstance(Locale.getDefault).format(amountMsat / 1000)} satoshis"
          notification("Payment Sent", message, NOTIFICATION_SUCCESS)
        case Success(PaymentFailed(_, failures)) =>
          val message = s"${
            failures.lastOption match {
              case Some(LocalFailure(t)) => t.getMessage
              case Some(RemoteFailure(_, e)) => e.failureMessage
              case _ => "Unknown error"
            }
          } (${failures.size} attempts)"
          notification("Payment Failed", message, NOTIFICATION_ERROR)
        case Failure(t) =>
          val message = t.getMessage
          notification("Payment Failed", message, NOTIFICATION_ERROR)
      }
  }

  def receive(amountMsat_opt: Option[MilliSatoshi], description: String): Future[String] = for {
    kit <- fKit
    res <- (kit.paymentHandler ? ReceivePayment(amountMsat_opt, description)).mapTo[PaymentRequest].map(PaymentRequest.write(_))
  } yield res


  def exportToDot(file: File) = for {
    kit <- fKit
    dot <- (kit.router ? 'dot).mapTo[String]
    _ = printToFile(file)(writer => writer.write(dot))
  } yield {}

  private def printToFile(f: java.io.File)(op: java.io.FileWriter => Unit) {
    val p = new FileWriter(f)
    try {
      op(p)
    } finally {
      p.close
    }
  }

  /**
    * Displays a system notification if the system supports it.
    *
    * @param title            Title of the notification
    * @param message          main message of the notification, will not wrap
    * @param notificationType type of the message, default to NONE
    * @param showAppName      true if you want the notification title to be preceded by "Eclair - ". True by default
    */
  def notification(title: String, message: String, notificationType: NotificationType = NOTIFICATION_NONE, showAppName: Boolean = true) = {
    notifsController.map(_.addNotification(if (showAppName) s"Eclair - $title" else title, message, notificationType))
  }
}
