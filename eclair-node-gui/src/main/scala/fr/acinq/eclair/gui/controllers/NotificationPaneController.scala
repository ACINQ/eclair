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
