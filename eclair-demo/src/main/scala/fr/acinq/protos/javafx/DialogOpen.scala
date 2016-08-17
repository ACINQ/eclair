package fr.acinq.protos.javafx

import javafx.event.{ActionEvent, EventHandler}
import javafx.geometry.{Insets, Pos}
import javafx.scene.Scene
import javafx.scene.control.{Button, Label, TextField}
import javafx.scene.layout.GridPane
import javafx.stage.{Modality, Stage, StageStyle}

/**
  * Created by PM on 16/08/2016.
  */
class DialogOpen() extends Stage() {
  initModality(Modality.WINDOW_MODAL)
  initStyle(StageStyle.UTILITY)
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
    override def handle(event: ActionEvent): Unit =
      GUIBoot.handlers.open(textFieldHostPort.getText)
  })
  grid.add(btn, 1, 1)

  val scene = new Scene(grid, 300, 100)
  setScene(scene)
}
