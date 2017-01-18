package fr.acinq.eclair.gui


import javafx.application.Platform
import javafx.scene.control.{TextArea, TextField}

import fr.acinq.bitcoin.{BinaryData, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.io.Client
import fr.acinq.eclair.payment.CreatePayment
import grizzled.slf4j.Logging

/**
  * Created by PM on 16/08/2016.
  */
class Handlers(setup: Setup) extends Logging {

  import setup._

  def open(hostPort: String, pubkey: String, amount: Satoshi) = {
    val regex = "([a-zA-Z0-9\\.\\-_]+):([0-9]+)".r
    hostPort match {
      case regex(host, port) =>
        logger.info(s"connecting to $host:$port")
        system.actorOf(Client.props(host, port.toInt, pubkey, amount, register))
      case _ => {}
    }
  }

  def send(nodeId: String, rhash: String, amountMsat: String) = {
    logger.info(s"sending $amountMsat to $rhash @ $nodeId")
    paymentInitiator ! CreatePayment(amountMsat.toInt, BinaryData(rhash), BinaryData(nodeId))
  }

  def getH(textField: TextField): Unit = {
    import akka.pattern.ask
    (paymentHandler ? 'genh).mapTo[BinaryData].map { h =>
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          textField.setText(h.toString())
        }
      })
    }
  }

  def getPaymentRequest(amountMsat: Long, textField: TextArea): Unit = {
    import akka.pattern.ask
    (paymentHandler ? 'genh).mapTo[BinaryData].map { h =>
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          textField.setText(s"${Globals.Node.id}:$amountMsat:${h.toString()}")
        }
      })
    }
  }

}
