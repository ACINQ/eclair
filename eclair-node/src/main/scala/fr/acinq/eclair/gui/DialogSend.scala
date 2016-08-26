package fr.acinq.eclair.gui

import javafx.event.{ActionEvent, EventHandler}
import javafx.geometry.{Insets, Pos}
import javafx.scene.{Node, Scene}
import javafx.scene.control.{Button, Label, TextArea, TextField}
import javafx.scene.layout.GridPane
import javafx.stage.{Modality, Stage, StageStyle}

/**
  * Created by PM on 16/08/2016.
  */
class DialogSend(primaryStage: Stage, handlers: Handlers) extends Stage() {
  initModality(Modality.WINDOW_MODAL)
  initStyle(StageStyle.UTILITY)
  initOwner(primaryStage)
  setWidth(800)
  setHeight(300)
  // center on parent
  setX(primaryStage.getX() + primaryStage.getWidth() / 2 - getWidth() / 2)
  setY(primaryStage.getY() + primaryStage.getHeight() / 2 - getHeight() / 2)
  setTitle("Pay")
  setResizable(false)

  val grid = new GridPane()
  grid.setAlignment(Pos.CENTER)
  grid.setHgap(10)
  grid.setVgap(10)
  grid.setPadding(new Insets(25, 25, 25, 25))


  //
  val labelPaymentRequest = new Label("PaymentRequest")
  grid.add(labelPaymentRequest, 0, 0)

  val textAreaPaymentRequest = new TextArea()
  textAreaPaymentRequest.setPrefColumnCount(40)
  textAreaPaymentRequest.setPrefRowCount(2)
  textAreaPaymentRequest.setWrapText(true)
  grid.add(textAreaPaymentRequest, 1, 0)

  val btn = new Button("Send")
  btn.setOnAction(new EventHandler[ActionEvent] {
    override def handle(event: ActionEvent): Unit = {
      val Array(nodeId, amount, hash) = textAreaPaymentRequest.getText.split(":")
      handlers.send(nodeId, hash, amount)
      event.getSource.asInstanceOf[Node].getScene.getWindow.hide()
    }
  })
  // click on enter
  btn.defaultButtonProperty().bind(btn.focusedProperty())
  grid.add(btn, 1, 1)

  val scene = new Scene(grid)
  setScene(scene)
}
