package fr.acinq.eclair.gui

import javafx.collections.FXCollections
import javafx.event.{ActionEvent, EventHandler}
import javafx.geometry.{Insets, Pos}
import javafx.scene.control.{Button, ComboBox, Label, TextField}
import javafx.scene.layout.GridPane
import javafx.scene.{Node, Scene}
import javafx.stage.{Modality, Stage, StageStyle}

import fr.acinq.bitcoin.Satoshi

/**
  * Created by PM on 16/08/2016.
  */
class DialogOpen(primaryStage: Stage, handlers: Handlers) extends Stage() {
  initModality(Modality.WINDOW_MODAL)
  initStyle(StageStyle.UTILITY)
  initOwner(primaryStage)
  setWidth(500)
  setHeight(300)
  // center on parent
  setX(primaryStage.getX() + primaryStage.getWidth() / 2 - getWidth() / 2)
  setY(primaryStage.getY() + primaryStage.getHeight() / 2 - getHeight() / 2)
  setTitle("Create a new channel")
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

  val labelAmount = new Label("Amount (msat)")
  grid.add(labelAmount, 0, 1)

  val textFieldAmount = new TextField("10")
  grid.add(textFieldAmount, 1, 1)

  val units = FXCollections.observableArrayList(
    "milliBTC",
    "Satoshi",
    "milliSatoshi"
  )
  val unitChooser = new ComboBox(units)
  unitChooser.setValue(units.get(0))
  grid.add(unitChooser, 2, 1)

  val btn = new Button("Create channel")
  btn.setOnAction(new EventHandler[ActionEvent] {
    override def handle(event: ActionEvent): Unit = {
      val raw = textFieldAmount.getText.toLong
      val amount = unitChooser.getValue match {
        case "milliBTC" => Satoshi(raw * 100000L)
        case "Satoshi" => Satoshi(raw)
        case "milliSatoshi" => Satoshi(raw / 1000L)
      }
      handlers.open(textFieldHostPort.getText, amount)
      event.getSource.asInstanceOf[Node].getScene.getWindow.hide()
    }
  })
  // click on enter
  btn.defaultButtonProperty().bind(btn.focusedProperty())
  grid.add(btn, 1, 2)

  val scene = new Scene(grid)
  setScene(scene)
}
