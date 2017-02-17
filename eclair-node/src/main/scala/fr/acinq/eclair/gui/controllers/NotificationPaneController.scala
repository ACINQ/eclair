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
  @FXML var bodyLabel: Label = _
  @FXML var icon: ImageView = _
  @FXML var closeButton: Button = _

  val MAX_BODY_HEIGHT = 60

  @FXML def initialize = {
    bodyLabel.setMaxHeight(MAX_BODY_HEIGHT)
  }

  @FXML def handleMouseEnter(event: MouseEvent) = {
    rootPane.setOpacity(1)
  }
  @FXML def handleMouseExit(event: MouseEvent) = {
    rootPane.setOpacity(0.95)
  }
  @FXML def handleMouseClick(event: MouseEvent) = {
    if (bodyLabel.getMaxHeight != MAX_BODY_HEIGHT) {
      bodyLabel.setMaxHeight(MAX_BODY_HEIGHT)
    } else {
      bodyLabel.setMaxHeight(Double.MaxValue)
    }
  }
}
