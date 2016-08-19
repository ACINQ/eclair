package fr.acinq.eclair.javafx

import javafx.event.{ActionEvent, EventHandler}
import javafx.geometry.{Insets, Pos}
import javafx.scene.{Node, Scene}
import javafx.scene.control.{Button, Label, TextField}
import javafx.scene.input.{KeyCode, KeyEvent}
import javafx.scene.layout.GridPane
import javafx.stage.{Modality, Stage, StageStyle}

/**
  * Created by PM on 16/08/2016.
  */
class DialogOpen(primaryStage: Stage, handlers: Handlers) extends Stage() {
  initModality(Modality.WINDOW_MODAL)
  initStyle(StageStyle.UTILITY)
  initOwner(primaryStage)
  setWidth(300)
  setHeight(100)
  // center on parent
  setX(primaryStage.getX() + primaryStage.getWidth() / 2 - getWidth() / 2)
  setY(primaryStage.getY() + primaryStage.getHeight() / 2 - getHeight() / 2)
  setAlwaysOnTop(true)
  setTitle("Open a new channel")
  setResizable(false)

  val grid = new GridPane()
  grid.setAlignment(Pos.CENTER)
  grid.setHgap(10)
  grid.setVgap(10)
  grid.setPadding(new Insets(25, 25, 25, 25))

  val labelHostPort = new Label("Host:Port")
  grid.add(labelHostPort, 0, 0)

  val textFieldHostPort = new TextField()
  grid.add(textFieldHostPort, 1, 0)

  val btn = new Button("Connect")
  btn.setOnAction(new EventHandler[ActionEvent] {
    override def handle(event: ActionEvent): Unit = {
      handlers.open(textFieldHostPort.getText)
      event.getSource.asInstanceOf[Node].getScene.getWindow.hide()
    }
  })
  // click on enter
  btn.defaultButtonProperty().bind(btn.focusedProperty())
  grid.add(btn, 1, 1)

  val scene = new Scene(grid)
  setScene(scene)
}
