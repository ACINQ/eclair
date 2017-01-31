package fr.acinq.eclair.gui.controllers

import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control.{Label, TextArea, TextField}
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

  @FXML def initialize(): Unit = {

    paymentRequest.textProperty().addListener(new ChangeListener[String] {
      def changed(observable: ObservableValue[_ <: String], oldValue: String, newValue: String): Unit = {
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

  @FXML def handleSend(event: ActionEvent): Unit = {
    if (GUIValidators.validate(paymentRequest.getText, paymentRequestError, "Please use a valid payment request", GUIValidators.paymentRequestRegex)) {
      val Array(nodeId, amount, hash) = paymentRequest.getText.split(":")
      handlers.send(nodeId, hash, amount)
      stage.close()
    }
  }

  @FXML def handleClose(event: ActionEvent): Unit = {
    stage.close()
  }
}
