package fr.acinq.eclair.gui.controllers

import javafx.application.Platform
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control._
import javafx.scene.image.{ImageView, WritableImage}
import javafx.scene.layout.GridPane
import javafx.stage.Stage

import fr.acinq.bitcoin.MilliSatoshi
import fr.acinq.eclair.CoinUtils
import fr.acinq.eclair.gui.{FxApp, Handlers}
import fr.acinq.eclair.gui.utils._
import fr.acinq.eclair.payment.PaymentRequest
import grizzled.slf4j.Logging

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

/**
  * Created by DPA on 23/09/2016.
  */
class ReceivePaymentController(val handlers: Handlers, val stage: Stage) extends Logging {

  @FXML var amount: TextField = _
  @FXML var amountError: Label = _
  @FXML var unit: ComboBox[String] = _
  @FXML var description: TextArea = _
  @FXML var prependPrefixCheckbox: CheckBox = _

  @FXML var resultBox: GridPane = _
  // the content of this field is generated and readonly
  @FXML var paymentRequestTextArea: TextArea = _
  @FXML var paymentRequestQRCode: ImageView = _

  @FXML def initialize = {
    unit.setItems(Constants.FX_UNITS_ARRAY)
    unit.setValue(FxApp.getUnit.label)
    resultBox.managedProperty().bind(resultBox.visibleProperty())
    stage.sizeToScene()
  }

  @FXML def handleCopyInvoice(event: ActionEvent) = ContextMenuUtils.copyToClipboard(paymentRequestTextArea.getText)

  /**
    * Generates a payment request from the amount/unit set in form. Displays an error if the generation fails.
    * Amount field content must obviously be numeric. It is also validated against minimal/maximal HTLC values.
    *
    * @param event
    */
  @FXML def handleGenerate(event: ActionEvent) = {
    clearError()
    amount.getText match {
      case "" => createPaymentRequest(None)
      case GUIValidators.amountDecRegex(_*) =>
        Try(CoinUtils.convertStringAmountToMsat(amount.getText, unit.getValue)) match {
          case Success(amountMsat) if amountMsat.amount < 0 =>
            handleError("Amount must be greater than 0")
          case Success(amountMsat) if amountMsat.amount >= PaymentRequest.MAX_AMOUNT.amount =>
            handleError(s"Amount must be less than ${CoinUtils.formatAmountInUnit(PaymentRequest.MAX_AMOUNT, FxApp.getUnit, withUnit = true)}")
          case Failure(_) =>
            handleError("Amount is incorrect")
          case Success(amountMsat) => createPaymentRequest(Some(amountMsat))
        }
      case _ => handleError("Amount must be a number")
    }
  }

  /**
    * Display error message
    *
    * @param message
    */
  private def handleError(message: String): Unit = {
    paymentRequestTextArea.setText("")
    amountError.setText(message)
    amountError.setOpacity(1)
  }

  private def clearError(): Unit = {
    paymentRequestTextArea.setText("")
    amountError.setText("")
    amountError.setOpacity(0)
  }

  /**
    * Ask eclair-core to create a Payment Request. If successful a QR code is generated and displayed, otherwise
    * an error message is shown.
    *
    * @param amount_opt optional amount of the payment request, in millisatoshi
    */
  private def createPaymentRequest(amount_opt: Option[MilliSatoshi]) = {
    logger.debug(s"generate payment request for amount_opt=${amount_opt.getOrElse("N/A")} description=${description.getText()}")
    handlers.receive(amount_opt, description.getText) onComplete {
      case Success(s) =>
        val pr = if (prependPrefixCheckbox.isSelected) s"lightning:$s" else s
        Try(QRCodeUtils.createQRCode(pr.toUpperCase, margin = -1)) match {
          case Success(wImage) => displayPaymentRequestQR(pr, Some(wImage))
          case Failure(t) => displayPaymentRequestQR(pr, None)
        }
      case Failure(t) =>
        logger.error("Could not generate payment request", t)
        Platform.runLater(new Runnable {
          def run = GUIValidators.validate(amountError, "The payment request could not be generated", false)
        })
    }
  }

  /**
    * Displays a QR Code from a QR code image.
    *
    * @param pr    payment request described by the QR code
    * @param image QR code source image
    */
  private def displayPaymentRequestQR(pr: String, image: Option[WritableImage]) = Platform.runLater(new Runnable {
    def run = {
      paymentRequestTextArea.setText(pr)
      if ("".equals(pr)) {
        resultBox.setVisible(false)
        resultBox.setMaxHeight(0)
      } else {
        resultBox.setVisible(true)
        resultBox.setMaxHeight(Double.MaxValue)
      }
      image.map(paymentRequestQRCode.setImage(_))
      stage.sizeToScene()
    }
  })

  @FXML def handleClose(event: ActionEvent) = stage.close
}
