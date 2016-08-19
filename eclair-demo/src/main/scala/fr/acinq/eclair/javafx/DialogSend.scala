package fr.acinq.eclair.javafx

import javafx.event.{ActionEvent, EventHandler}
import javafx.geometry.{Insets, Pos}
import javafx.scene.{Node, Scene}
import javafx.scene.control.{Button, Label, TextField}
import javafx.scene.layout.GridPane
import javafx.stage.{Modality, Stage, StageStyle}

/**
  * Created by PM on 16/08/2016.
  */
class DialogSend(primaryStage: Stage, handlers: Handlers) extends Stage() {
  initModality(Modality.WINDOW_MODAL)
  initStyle(StageStyle.UTILITY)
  initOwner(primaryStage)
  setWidth(300)
  setHeight(300)
  // center on parent
  setX(primaryStage.getX() + primaryStage.getWidth() / 2 - getWidth() / 2)
  setY(primaryStage.getY() + primaryStage.getHeight() / 2 - getHeight() / 2)
  setAlwaysOnTop(true)
  setTitle("Send")
  setResizable(false)

  val grid = new GridPane()
  grid.setAlignment(Pos.CENTER)
  grid.setHgap(10)
  grid.setVgap(10)
  grid.setPadding(new Insets(25, 25, 25, 25))

  val labelNodeId = new Label("NodeId")
  grid.add(labelNodeId, 0, 0)

  val textFieldNodeId = new TextField()
  grid.add(textFieldNodeId, 1, 0)

  val labelH = new Label("H")
  grid.add(labelH, 0, 1)

  val textFieldH = new TextField()
  grid.add(textFieldH, 1, 1)

  val labelAmountMsat = new Label("amount (msat)")
  grid.add(labelAmountMsat, 0, 2)

  val textFieldAmountMsat = new TextField()
  grid.add(textFieldAmountMsat, 1, 2)

  val btn = new Button("Send")
  btn.setOnAction(new EventHandler[ActionEvent] {
    override def handle(event: ActionEvent): Unit = {
      handlers.send(textFieldNodeId.getText, textFieldH.getText, textFieldAmountMsat.getText)
      event.getSource.asInstanceOf[Node].getScene.getWindow.hide()
    }
  })
  // click on enter
  btn.defaultButtonProperty().bind(btn.focusedProperty())
  grid.add(btn, 1, 3)

  val scene = new Scene(grid)
  setScene(scene)
}
