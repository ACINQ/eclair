/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
