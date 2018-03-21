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

import javafx.fxml.FXML
import javafx.scene.control.{Button, Label}
import javafx.scene.image.ImageView
import javafx.scene.input.MouseEvent
import javafx.scene.layout.GridPane

/**
  * Created by DPA on 17/02/2017.
  */
class NotificationPaneController {

  @FXML var rootPane: GridPane = _
  @FXML var titleLabel: Label = _
  @FXML var messageLabel: Label = _
  @FXML var icon: ImageView = _
  @FXML var closeButton: Button = _
  @FXML var copyButton: Button = _

  @FXML def handleMouseEnter(event: MouseEvent) = {
    rootPane.setOpacity(1)
  }

  @FXML def handleMouseExit(event: MouseEvent) = {
    rootPane.setOpacity(0.95)
  }
}
