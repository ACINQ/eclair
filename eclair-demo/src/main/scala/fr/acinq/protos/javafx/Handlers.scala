package fr.acinq.protos.javafx


import javafx.application.Platform
import javafx.scene.control.TextField

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.io.Client
import fr.acinq.eclair.router.CreatePayment
import fr.acinq.eclair._
import grizzled.slf4j.Logging

/**
  * Created by PM on 16/08/2016.
  */
class Handlers(setup: Setup) extends Logging {

  import setup._

  def open(hostPort: String) = {
    val regex = "([a-zA-Z0-9\\.\\-_]+):([0-9]+)".r
    hostPort match {
      case regex(host, port) =>
        logger.info(s"connecting to $host:$port")
        val amount = 1000000L
        system.actorOf(Client.props(host, port.toInt, amount, register))
      case _ => {}
    }
  }

  def send(nodeId: String, rhash: String, amountMsat: String) = {
    logger.info(s"sending $amountMsat to $rhash @ $nodeId")
    router ! CreatePayment(amountMsat.toInt, BinaryData(rhash), BinaryData(nodeId))
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

}
