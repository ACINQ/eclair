package fr.acinq.eclair.gui.controllers

import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.event.{ActionEvent, EventHandler}
import javafx.fxml.FXML
import javafx.scene.control.{Button, Label, TextArea, TextField}
import javafx.scene.input.KeyCode.{ENTER, TAB}
import javafx.scene.input.KeyEvent
import javafx.stage.Stage

import fr.acinq.eclair.CoinUtils
import fr.acinq.eclair.gui.{FxApp, Handlers}
import fr.acinq.eclair.payment.PaymentRequest
import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}

/**
  * Created by DPA on 23/09/2016.
  */
class SendPaymentController(val handlers: Handlers, val stage: Stage) extends Logging {

  @FXML var paymentRequest: TextArea = _
  @FXML var paymentRequestError: Label = _
  @FXML var nodeIdField: TextField = _
  @FXML var descriptionLabel: Label = _
  @FXML var descriptionField: TextArea = _
  @FXML var amountFieldLabel: Label = _
  @FXML var amountField: TextField = _
  @FXML var amountFieldError: Label = _
  @FXML var paymentHashField: TextField = _
  @FXML var sendButton: Button = _

  @FXML def initialize(): Unit = {

    // set the user preferred unit
    amountFieldLabel.setText(s"Amount (${FxApp.getUnit.shortLabel})")

    // ENTER or TAB events in the paymentRequest textarea instead fire or focus sendButton
    paymentRequest.addEventFilter(KeyEvent.KEY_PRESSED, new EventHandler[KeyEvent] {
      def handle(event: KeyEvent) = {
        event.getCode match {
          case ENTER =>
            sendButton.fire()
            event.consume()
          case TAB =>
            sendButton.requestFocus()
            event.consume()
          case _ =>
        }
      }
    })
    paymentRequest.textProperty.addListener(new ChangeListener[String] {
      def changed(observable: ObservableValue[_ <: String], oldValue: String, newValue: String): Unit = {
        readPaymentRequest() match {
          case Success(pr) => setUIFields(pr)
          case Failure(f) =>
            paymentRequestError.setText("Could not read this payment request")
            descriptionLabel.setText("")
            nodeIdField.setText("")
            paymentHashField.setText("")
        }
      }
    })
  }

  /**
    * Tries to read the payment request string in the payment request input field
    *
    * @return a Try containing the payment request object, if successfully parsed
    */
  private def readPaymentRequest(): Try[PaymentRequest] = {
    clearErrors()
    val prString: String = paymentRequest.getText.trim match {
      case s if s.startsWith("lightning://") => s.replaceAll("lightning://", "")
      case s if s.startsWith("lightning:") => s.replaceAll("lightning:", "")
      case s => s
    }
    Try(PaymentRequest.read(prString))
  }

  private def setUIFields(pr: PaymentRequest) = {
    pr.amount.foreach(amount => amountField.setText(CoinUtils.rawAmountInUnit(amount, FxApp.getUnit).bigDecimal.toPlainString))
    pr.description match {
      case Left(s) => descriptionField.setText(s)
      case Right(hash) =>
        descriptionLabel.setText("Description's Hash")
        descriptionField.setText(hash.toString())
    }
    nodeIdField.setText(pr.nodeId.toString)
    paymentHashField.setText(pr.paymentHash.toString)
  }

  @FXML def handleSend(event: ActionEvent): Unit = {
    (Try(CoinUtils.convertStringAmountToMsat(amountField.getText(), FxApp.getUnit.code)), readPaymentRequest()) match {
      case (Success(amountMsat), Success(pr)) =>
        // we always override the payment request amount with the one from the UI
        Try(handlers.send(Some(amountMsat.amount), pr)) match {
          case Success(_) => stage.close()
          case Failure(f) => paymentRequestError.setText(s"Invalid Payment Request: ${f.getMessage}")
        }
      case (_, Success(_)) => amountFieldError.setText("Invalid amount")
      case (_, Failure(_)) => paymentRequestError.setText("Could not read this payment request")
    }
  }

  @FXML def handleClose(event: ActionEvent): Unit = {
    stage.close()
  }

  private def clearErrors(): Unit = {
    paymentRequestError.setText("")
    amountFieldError.setText("")
  }
}
