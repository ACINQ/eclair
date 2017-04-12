package fr.acinq.eclair.gui.controllers

import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.event.{ActionEvent, EventHandler}
import javafx.fxml.FXML
import javafx.scene.control.{Button, Label, TextArea, TextField}
import javafx.scene.input.KeyCode.{ENTER, TAB}
import javafx.scene.input.KeyEvent
import javafx.stage.Stage

import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.Setup
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.utils.GUIValidators
import fr.acinq.eclair.payment.PaymentRequest
import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}


/**
  * Created by DPA on 23/09/2016.
  */
class SendPaymentController(val handlers: Handlers, val stage: Stage, val setup: Setup) extends Logging {

  @FXML var paymentRequest: TextArea = _
  @FXML var paymentRequestError: Label = _
  @FXML var nodeIdField: TextField = _
  @FXML var amountField: TextField = _
  @FXML var hashField: TextField = _
  @FXML var sendButton: Button = _

  @FXML def initialize(): Unit = {
    // ENTER or TAB events in the paymentRequest textarea insted fire or focus sendButton
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
        if (GUIValidators.validate(paymentRequest.getText, paymentRequestError, "Please use a valid payment request", GUIValidators.paymentRequestRegex)) {
          Try(PaymentRequest.read(paymentRequest.getText)) match {
            case Success(pr) =>
              amountField.setText(pr.amount.amount.toString)
              nodeIdField.setText(pr.nodeId.toString)
              hashField.setText(pr.paymentHash.toString)
            case Failure(f) =>
              GUIValidators.validate(paymentRequestError, "Please use a valid payment request", false)
              amountField.setText("0")
              nodeIdField.setText("N/A")
              hashField.setText("N/A")
          }
        } else {
          amountField.setText("0")
          nodeIdField.setText("N/A")
          hashField.setText("N/A")
        }
      }
    })
  }

  @FXML def handleSend(event: ActionEvent) = {
    if (GUIValidators.validate(paymentRequest.getText, paymentRequestError, "Please use a valid payment request", GUIValidators.paymentRequestRegex)) {
      val Array(nodeId, amount, hash) = paymentRequest.getText.split(":")
      Try(amount.toLong) match {
        case Success(amountLong) =>
          if (GUIValidators.validate(paymentRequestError, "Amount must be greater than 0", amountLong > 0)
            && GUIValidators.validate(paymentRequestError, "Amount must be less than 4 294 967 295 msat (~0.042 BTC)", amountLong < PaymentRequest.maxAmountMsat)) {
            Try (handlers.send(PublicKey(nodeId), BinaryData(hash), amountLong)) match {
              case Success(s) => stage.close
              case Failure(f) => GUIValidators.validate(paymentRequestError, s"Invalid Payment Request: ${f.getMessage}", false)
            }
          }
        case Failure(f) => GUIValidators.validate(paymentRequestError, s"Amount must be numeric", false)
      }
    }
  }

  @FXML def handleClose(event: ActionEvent) = {
    stage.close
  }
}
