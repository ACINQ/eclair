package fr.acinq.eclair.gui

import java.io.{File, FileWriter}

import akka.pattern.{AskTimeoutException, ask}
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

  implicit val timeout = Timeout(60 seconds)

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
    } yield (kit, conn)) onComplete {
      case Success((k, _)) =>
        logger.info(s"connection to $nodeUri successful")
        channel match {
          case Some(openChannel) =>
            k.switchboard ? openChannel onComplete {
              case Success(s) =>
                logger.info(s"successfully opened channel $s")
                notification("Channel created", s.toString, NOTIFICATION_SUCCESS)
              case Failure(_: AskTimeoutException) =>
                logger.info("opening channel is taking a long time, notifications will not be shown")
              case Failure(t) =>
                logger.info("could not open channel ", t)
                notification("Channel creation failed", t.getMessage, NOTIFICATION_ERROR)
            }
          case None =>
        }
      case Failure(t) =>
        logger.error(s"could not create connection to $nodeUri ", t)
        notification("Connection failed", t.getMessage, NOTIFICATION_ERROR)
    }
  }

  def send(overrideAmountMsat_opt: Option[Long], req: PaymentRequest) = {
    val amountMsat = overrideAmountMsat_opt
      .orElse(req.amount.map(_.amount))
      .getOrElse(throw new RuntimeException("you need to manually specify an amount for this payment request"))
    logger.info(s"sending $amountMsat to ${req.paymentHash} @ ${req.nodeId}")
    val sendPayment = req.minFinalCltvExpiry match {
      case None => SendPayment(amountMsat, req.paymentHash, req.nodeId, req.routingInfo)
      case Some(minFinalCltvExpiry) => SendPayment(amountMsat, req.paymentHash, req.nodeId, req.routingInfo, finalCltvExpiry = minFinalCltvExpiry)
    }
    // completed payment will be handled from GUIUpdater by listening to PaymentSucceeded/PaymentFailed events
    (for {
      kit <- fKit
      res <- (kit.paymentInitiator ? sendPayment).mapTo[PaymentResult]
    } yield res).recover {
      case _: AskTimeoutException =>
        logger.info("sending payment is taking a long time, notifications will not be shown")
      case t =>
        val message = t.getMessage
        notification("Payment Failed", message, NOTIFICATION_ERROR)
    }
  }

  def receive(amountMsat_opt: Option[MilliSatoshi], description: String): Future[String] = for {
    kit <- fKit
    res <- (kit.paymentHandler ? ReceivePayment(amountMsat_opt, description)).mapTo[PaymentRequest].map(PaymentRequest.write)
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
    notifsController.foreach(_.addNotification(if (showAppName) s"Eclair - $title" else title, message, notificationType))
  }
}
