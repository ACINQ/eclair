package fr.acinq.eclair.gui


import java.io.{ByteArrayInputStream, ByteArrayOutputStream, File, FileWriter}
import javafx.application.Platform
import javafx.scene.control.{TextArea, TextField}
import javafx.stage.Stage

import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.io.Client
import fr.acinq.eclair.payment.CreatePayment
import grizzled.slf4j.Logging

/**
  * Created by PM on 16/08/2016.
  */
class Handlers(setup: Setup) extends Logging {

  import setup._

  def open(hostPort: String, fundingSatoshis: Satoshi, pushMsat: MilliSatoshi) = {
    val regex = "([a-fA-F0-9]+)@([a-zA-Z0-9\\.\\-_]+):([0-9]+)".r
    hostPort match {
      case regex(pubkey, host, port) =>
        logger.info(s"connecting to $host:$port")
        system.actorOf(Client.props(host, port.toInt, pubkey, fundingSatoshis, pushMsat, register))
      case _ => {}
    }
  }

  def send(nodeId: String, rhash: String, amountMsat: String) = {
    logger.info(s"sending $amountMsat to $rhash @ $nodeId")
    paymentInitiator ! CreatePayment(amountMsat.toLong, BinaryData(rhash), BinaryData(nodeId))
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

  def exportToDot(file: File): Unit = {
    import akka.pattern.ask
    (router ? 'dot).mapTo[String].map(dot => printToFile(file)(writer => writer.write(dot)))
  }

  def printToFile(f: java.io.File)(op: java.io.FileWriter => Unit) {
    val p = new FileWriter(f)
    try {
      op(p)
    } finally {
      p.close()
    }
  }
}
