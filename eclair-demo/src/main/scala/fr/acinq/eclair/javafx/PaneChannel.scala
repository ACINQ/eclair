package fr.acinq.eclair.javafx

import javafx.geometry.Pos
import javafx.scene.control.{Button, Label, ProgressBar, TextField}
import javafx.scene.layout.{ColumnConstraints, GridPane, HBox, VBox}

/**
  * Created by PM on 17/08/2016.
  */
class PaneChannel extends VBox {
  setAlignment(Pos.CENTER_LEFT)
  val labelChannelId = new Label(s"Channel id: #<id>")
  getChildren.add(labelChannelId)

  val hBox = new HBox()
  val labelAmountUs = new Label("")
  labelAmountUs.setPrefWidth(100)
  val progressBarBalance = new ProgressBar(0)
  progressBarBalance.setPrefSize(1000, 10)
  hBox.getChildren.addAll(labelAmountUs, progressBarBalance)
  getChildren.add(hBox)

  val grid = new GridPane()
  grid.setHgap(10)
  grid.setVgap(10)
  grid.getColumnConstraints().add(new ColumnConstraints(100))
  grid.getColumnConstraints().add(new ColumnConstraints(500))
  grid.getColumnConstraints().add(new ColumnConstraints(100))
  grid.getColumnConstraints().add(new ColumnConstraints(300))

  val labelNodeId = new Label(s"Node id:")
  grid.add(labelNodeId, 0, 0)
  val textNodeId = new Label("?")
  grid.add(textNodeId, 1, 0)

  val labelCapacity = new Label(s"Capacity (satoshi):")
  grid.add(labelCapacity, 0, 1)
  val textCapacity = new Label("?")
  grid.add(textCapacity, 1, 1)

  val labelFunder = new Label(s"Funder:")
  grid.add(labelFunder, 2, 0)
  val textFunder = new Label("?")
  grid.add(textFunder, 3, 0)

  val labelState = new Label(s"State:")
  grid.add(labelState, 2, 1)
  val textState = new Label("?")
  grid.add(textState, 3, 1)

  val buttonClose = new Button("Close")
  grid.add(buttonClose, 4, 0)

  getChildren.add(grid)
}
