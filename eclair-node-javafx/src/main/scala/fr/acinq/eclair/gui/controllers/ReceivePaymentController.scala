package fr.acinq.eclair.gui.controllers

import javafx.application.Platform
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control.{ComboBox, Label, TextArea, TextField}
import javafx.scene.image.{ImageView, WritableImage}
import javafx.scene.layout.GridPane
import javafx.scene.paint.Color
import javafx.stage.Stage

import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.{BarcodeFormat, EncodeHintType}
import fr.acinq.bitcoin.MilliSatoshi
import fr.acinq.eclair.Setup
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.utils.GUIValidators
import fr.acinq.eclair.payment.PaymentRequest
import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}

/**
  * Created by DPA on 23/09/2016.
  */
class ReceivePaymentController(val handlers: Handlers, val stage: Stage, val setup: Setup) extends Logging {

  @FXML var amount: TextField = _
  @FXML var amountError: Label = _
  @FXML var unit: ComboBox[String] = _

  @FXML var resultBox: GridPane = _
  // the content of this field is generated and readonly
  @FXML var paymentRequestTextArea: TextArea = _
  @FXML var paymentRequestQRCode: ImageView = _

  @FXML def initialize = {
    unit.setValue(unit.getItems.get(0))
    resultBox.managedProperty().bind(resultBox.visibleProperty())
    stage.sizeToScene()
  }

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
            case Success(s) =>
              Try(createQRCode(s)) match {
                case Success(wImage) => displayPaymentRequest(s, Some(wImage))
                case Failure(t) => displayPaymentRequest(s, None)
              }
            case Failure(t) => Platform.runLater(new Runnable {
              def run = GUIValidators.validate(amountError, "The payment request could not be generated", false)
            })
          }
        }
      } catch {
        case e: NumberFormatException =>
          logger.debug(s"Could not generate payment request for amount = ${amount.getText}")
          paymentRequestTextArea.setText("")
          amountError.setText("Amount is incorrect")
          amountError.setOpacity(1)
      }
    }
  }

  private def displayPaymentRequest(pr: String, image: Option[WritableImage]) = Platform.runLater(new Runnable {
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

  private def createQRCode(data: String, width: Int = 300, height: Int = 300, margin: Int = 0): WritableImage = {
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
