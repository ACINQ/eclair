package fr.acinq.eclair.gui.controllers

import javafx.application.Platform
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control.{ComboBox, Label, TextArea, TextField}
import javafx.stage.Stage

import fr.acinq.bitcoin.MilliSatoshi
import fr.acinq.eclair.Setup
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.utils.GUIValidators
import fr.acinq.eclair.payment.PaymentRequest
import grizzled.slf4j.Logging

import scala.util.{Failure, Success}

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
      || ("milliSatoshi".equals(unit.getValue) && GUIValidators.validate(amount.getText, amountError, "Amount must be numeric (no decimal msat)", GUIValidators.amountRegex))) {
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
          val smartAmount = unit.getValue match {
            case "milliBTC" => MilliSatoshi(parsedInt.toLong * 100000000L + amountDec.toLong * 100000L)
            case "Satoshi" => MilliSatoshi(parsedInt.toLong * 1000L + amountDec.toLong)
            case "milliSatoshi" => MilliSatoshi(amount.getText.toLong)
          }
          if (GUIValidators.validate(amountError, "Amount must be greater than 0", smartAmount.amount > 0)
            && GUIValidators.validate(amountError, f"Amount must be less than ${PaymentRequest.maxAmountMsat}%,d msat (~${PaymentRequest.maxAmountMsat / 1e11}%.3f BTC)", smartAmount.amount < PaymentRequest.maxAmountMsat)) {
            import scala.concurrent.ExecutionContext.Implicits.global
            handlers.receive(smartAmount) onComplete {
              case Success(s) => Platform.runLater(new Runnable {
                def run = {
                  paymentRequest.setText(s)
                  paymentRequest.requestFocus
                  paymentRequest.selectAll
                }})
              case Failure(t) => Platform.runLater(new Runnable {
                def run = GUIValidators.validate(amountError, "The payment request could not be generated", false)
              })
            }
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
