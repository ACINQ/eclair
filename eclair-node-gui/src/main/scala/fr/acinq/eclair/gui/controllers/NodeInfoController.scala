package fr.acinq.eclair.gui.controllers

import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control._
import javafx.scene.image.ImageView
import javafx.stage.Stage

import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.utils.{ContextMenuUtils, QRCodeUtils}
import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}

class NodeInfoController(val address: String, val handlers: Handlers, val stage: Stage) extends Logging {

  @FXML var uriTextarea: TextArea = _
  @FXML var uriQRCode: ImageView = _

  @FXML def initialize(): Unit = {
    uriTextarea.setText(address)
    Try(QRCodeUtils.createQRCode(address)) match {
      case Success(wImage) => uriQRCode.setImage(wImage)
      case Failure(t) => logger.debug("Failed to generate URI QR Code", t)
    }
    stage.sizeToScene()
  }

  @FXML def handleCopyURI(event: ActionEvent): Boolean = ContextMenuUtils.copyToClipboard(address)

  @FXML def handleClose(event: ActionEvent): Unit = stage.close()
}
