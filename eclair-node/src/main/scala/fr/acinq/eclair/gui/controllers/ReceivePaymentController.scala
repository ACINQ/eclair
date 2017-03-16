package fr.acinq.eclair.gui.controllers

import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control.{ComboBox, Label, TextArea, TextField}
import javafx.stage.Stage

import fr.acinq.bitcoin.MilliSatoshi
import fr.acinq.eclair.Setup
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.utils.GUIValidators
import grizzled.slf4j.Logging

/**
  * Created by DPA on 23/09/2016.
  */
class ReceivePaymentController(val handlers: Handlers, val stage: Stage, val setup: Setup) extends Logging {

  @FXML var amount: TextField = _
  @FXML var amountError: Label = _
  @FXML var unit: ComboBox[String] = _

  // this field is generated and readonly
  @FXML var paymentRequest: TextArea = _

  @FXML def initialize = unit.setValue(unit.getItems.get(0))

  @FXML def handleGenerate(event: ActionEvent) = {
    if ((("milliBTC".equals(unit.getValue) || "Satoshi".equals(unit.getValue))
          && GUIValidators.validate(amount.getText, amountError, "Amount must be numeric", GUIValidators.amountDecRegex))
      || ("milliSatoshi".equals(unit.getValue) && GUIValidators.validate(amount.getText, amountError, "Amount must be numeric (no decimal mSat)", GUIValidators.amountRegex))) {
        try {
          val Array(parsedInt, parsedDec) = if (amount.getText.contains(".")) amount.getText.split("\\.") else Array(amount.getText, "0")
          val amountDec = parsedDec.length match {
            case 0 => "000"
            case 1 => parsedDec.concat("00")
            case 2 => parsedDec.concat("0")
            case 3 => parsedDec
            case _ =>
              // amount has too many decimals, regex validation has failed somehow
              throw new NumberFormatException("incorrect amount")
          }
          logger.debug(s"Parsed amount for payment request = int $parsedInt dec $amountDec")
          val smartAmount = unit.getValue match {
            case "milliBTC" => MilliSatoshi(parsedInt.toLong * 100000000L + amountDec.toLong * 100000L)
            case "Satoshi" => MilliSatoshi(parsedInt.toLong * 1000L + amountDec.toLong)
            case "milliSatoshi" => MilliSatoshi(amount.getText.toLong)
          }
          logger.debug(s"Final amount for payment request = $smartAmount")
          if (GUIValidators.validate(amountError, "Amount must be greater than 0", smartAmount.amount > 0)
            && GUIValidators.validate(amountError, "Must be less than 4 294 967 295 mSat (~0.042 BTC)", smartAmount.amount < 4294967295L)) {
            handlers.getPaymentRequest(smartAmount.amount, paymentRequest)
          }
        } catch {
          case e: NumberFormatException =>
            logger.debug(s"Could not generate payment request for amount = ${amount.getText}")
            paymentRequest.setText("")
            amountError.setText("Amount is incorrect")
            amountError.setOpacity(1)
        }
    }
  }

  @FXML def handleClose(event: ActionEvent) = stage.close
}
