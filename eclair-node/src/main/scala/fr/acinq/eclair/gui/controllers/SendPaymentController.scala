package fr.acinq.eclair.gui.controllers

import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.event.{ActionEvent, EventHandler}
import javafx.fxml.FXML
import javafx.scene.control.{Button, Label, TextArea, TextField}
import javafx.scene.input.KeyCode.{ENTER, TAB}
import javafx.scene.input.KeyEvent
import javafx.stage.Stage

import fr.acinq.eclair.Setup
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.utils.GUIValidators
import grizzled.slf4j.Logging


/**
  * Created by DPA on 23/09/2016.
  */
class SendPaymentController(val handlers: Handlers, val stage: Stage, val setup: Setup) extends BaseController with Logging {

  @FXML var paymentRequest: TextArea = _
  @FXML var paymentRequestError: Label = _
  @FXML var nodeIdField: TextField = _
  @FXML var amountField: TextField = _
  @FXML var hashField: TextField = _
  @FXML var sendButton: Button = _

  @FXML def initialize(): Unit = {
    // ENTER or TAB events in the paymentRequest textarea insted fire or focus sendButton
    paymentRequest.addEventFilter(KeyEvent.KEY_PRESSED, new EventHandler[KeyEvent]() {
      def handle(event: KeyEvent) = {
        val parent = paymentRequest.getParent()
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
    paymentRequest.textProperty().addListener(new ChangeListener[String] {
      def changed(observable: ObservableValue[_ <: String], oldValue: String, newValue: String) = {
        if (GUIValidators.validate(paymentRequest.getText, paymentRequestError, "Please use a valid payment request", GUIValidators.paymentRequestRegex)) {
          val Array(nodeId, amount, hash) = paymentRequest.getText.split(":")
          amountField.setText(amount)
          nodeIdField.setText(nodeId)
          hashField.setText(hash)
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
      if (GUIValidators.validate(amount, paymentRequestError, "Amount must be numeric", GUIValidators.amountRegex)
        && GUIValidators.validate(paymentRequestError, "Amount must be greater than 0", amount.toLong > 0)
        && GUIValidators.validate(paymentRequestError, "Amount must be less than 4 294 967 295 mSat (~0.042 BTC)", amount.toLong < 4294967295L)) {
        handlers.send(nodeId, hash, amount)
        stage.close()
      }
    }
  }

  @FXML def handleClose(event: ActionEvent) = {
    stage.close()
  }
}
