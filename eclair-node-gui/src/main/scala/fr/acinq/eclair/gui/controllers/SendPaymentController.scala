package fr.acinq.eclair.gui.controllers

import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.event.{ActionEvent, EventHandler}
import javafx.fxml.FXML
import javafx.scene.control.{Button, Label, TextArea, TextField}
import javafx.scene.input.KeyCode.{ENTER, TAB}
import javafx.scene.input.KeyEvent
import javafx.stage.Stage

import fr.acinq.bitcoin.MilliSatoshi
import fr.acinq.eclair.gui.Handlers
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
  @FXML var amountField: TextField = _
  @FXML var amountFieldError: Label = _
  @FXML var paymentHashField: TextField = _
  @FXML var sendButton: Button = _

  @FXML def initialize(): Unit = {
    // ENTER or TAB events in the paymentRequest textarea instead fire or focus sendButton
    paymentRequest.addEventFilter(KeyEvent.KEY_PRESSED, new EventHandler[KeyEvent] {
      def handle(event: KeyEvent) = {
        event.getCode match {
          case ENTER =>
            sendButton.fire
            event.consume
          case TAB =>
            sendButton.requestFocus()
            event.consume
          case _ =>
        }
      }
    })
    paymentRequest.textProperty.addListener(new ChangeListener[String] {
      def changed(observable: ObservableValue[_ <: String], oldValue: String, newValue: String) = {
        clearErrors()
        Try(PaymentRequest.read(paymentRequest.getText)) match {
          case Success(pr) =>
            pr.amount.foreach(amount => amountField.setText(amount.amount.toString))
            pr.description match {
              case Left(s) => descriptionField.setText(s)
              case Right(hash) =>
                descriptionLabel.setText("Description's Hash")
                descriptionField.setText(hash.toString())
            }
            nodeIdField.setText(pr.nodeId.toString)
            paymentHashField.setText(pr.paymentHash.toString)
          case Failure(f) =>
            paymentRequestError.setText("Could not read this payment request")
        }
      }
    })
  }

  @FXML def handleSend(event: ActionEvent) = {
    (Try(MilliSatoshi(amountField.getText().toLong)), Try(PaymentRequest.read(paymentRequest.getText))) match {
      case (Success(amountMsat), Success(pr)) =>
        Try(handlers.send(pr.nodeId, pr.paymentHash, amountMsat.amount, pr.minFinalCltvExpiry)) match {
          case Success(s) => stage.close
          case Failure(f) => paymentRequestError.setText(s"Invalid Payment Request: ${f.getMessage}")
        }
      case (_, Success(_)) => amountFieldError.setText("Invalid amount")
      case (_, Failure(f)) => paymentRequestError.setText("Could not read this payment request")
    }
  }

  @FXML def handleClose(event: ActionEvent) = {
    stage.close
  }

  private def clearErrors(): Unit = {
    paymentRequestError.setText("")
    amountFieldError.setText("")
  }
}
