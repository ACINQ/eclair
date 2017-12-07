package fr.acinq.eclair.gui.controllers

import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control._
import javafx.scene.image.{ImageView, WritableImage}
import javafx.scene.layout.GridPane
import javafx.scene.paint.Color
import javafx.stage.Stage

import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.{BarcodeFormat, EncodeHintType}
import fr.acinq.bitcoin.MilliSatoshi
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.utils.{CoinUtils, ContextMenuUtils, GUIValidators}
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
    unit.setItems(FXCollections.observableArrayList(CoinUtils.MILLI_SATOSHI_LABEL, CoinUtils.SATOSHI_LABEL, CoinUtils.MILLI_BTC_LABEL))
    unit.setValue(unit.getItems.get(0))
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
            handleError(f"Amount must be less than ${PaymentRequest.MAX_AMOUNT.amount}%,d msat (~${PaymentRequest.MAX_AMOUNT.amount / 1e11}%.3f BTC)")
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
        Try(createQRCode(pr)) match {
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

  private def createQRCode(data: String, width: Int = 250, height: Int = 250, margin: Int = -1): WritableImage = {
    import scala.collection.JavaConversions._
    val hintMap = collection.mutable.Map[EncodeHintType, Object]()
    hintMap.put(EncodeHintType.CHARACTER_SET, "UTF-8")
    hintMap.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L)
    hintMap.put(EncodeHintType.MARGIN, margin.toString)
    val qrWriter = new QRCodeWriter
    val byteMatrix = qrWriter.encode(data, BarcodeFormat.QR_CODE, width, height, hintMap)
    val writableImage = new WritableImage(width, height)
    val pixelWriter = writableImage.getPixelWriter
    for (i <- 0 to byteMatrix.getWidth - 1) {
      for (j <- 0 to byteMatrix.getWidth - 1) {
        if (byteMatrix.get(i, j)) {
          pixelWriter.setColor(i, j, Color.BLACK)
        } else {
          pixelWriter.setColor(i, j, Color.WHITE)
        }
      }
    }
    writableImage
  }

  @FXML def handleClose(event: ActionEvent) = stage.close
}
