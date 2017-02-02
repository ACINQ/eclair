package fr.acinq.eclair.gui.controllers

import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control.{Label, TextArea, TextField}
import javafx.stage.Stage

import fr.acinq.eclair.Setup
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.utils.GUIValidators
import grizzled.slf4j.Logging

import scala.util.{Success, Try}

/**
  * Created by DPA on 23/09/2016.
  */
class ReceivePaymentController(val handlers: Handlers, val stage: Stage, val setup: Setup) extends BaseController with Logging {

  @FXML var amount: TextField = _
  @FXML var amountError: Label = _

  // this field is generated and readonly
  @FXML var paymentRequest: TextArea = _

  @FXML def initialize(): Unit = {
  }

  @FXML def handleGenerate(event: ActionEvent): Unit = {
    if (GUIValidators.validate(amount.getText, amountError, "Amount must be numeric", GUIValidators.amountRegex)
        && GUIValidators.validate(amountError, "Amount must be greater than 0", amount.getText.toLong > 0)
        && GUIValidators.validate(amountError, "Must be less than 4 294 967 295 mSat (~0.042 BTC)", amount.getText.toLong < 4294967295L)) {
      Try(amount.getText.toLong) match {
        case Success(amountMsat) => handlers.getPaymentRequest(amount.getText.toLong, paymentRequest)
        case _ => {}
      }
    }
  }

  @FXML def handleClose(event: ActionEvent): Unit = {
    stage.close()
  }
}
