package fr.acinq.eclair.gui

import javafx.event.{ActionEvent, EventHandler}
import javafx.geometry.{Insets, Pos}
import javafx.scene.Scene
import javafx.scene.control.{Button, Label, TextArea, TextField}
import javafx.scene.layout.GridPane
import javafx.stage.{Modality, Stage, StageStyle}

import scala.util.{Success, Try}

/**
  * Created by PM on 16/08/2016.
  */
class DialogReceive(primaryStage: Stage, handlers: Handlers) extends Stage() {
  initModality(Modality.WINDOW_MODAL)
  initStyle(StageStyle.UTILITY)
  initOwner(primaryStage)
  setWidth(900)
  setHeight(300)
  // center on parent
  setX(primaryStage.getX() + primaryStage.getWidth() / 2 - getWidth() / 2)
  setY(primaryStage.getY() + primaryStage.getHeight() / 2 - getHeight() / 2)
  setTitle("Receive Payment")
  setResizable(false)

  val grid = new GridPane()
  grid.setAlignment(Pos.CENTER)
  grid.setHgap(10)
  grid.setVgap(10)
  grid.setPadding(new Insets(20, 5, 20, 5))

  val labelAmountMsat = new Label("amount (msat)")
  grid.add(labelAmountMsat, 0, 0)

  val textFieldAmountMsat = new TextField()
  grid.add(textFieldAmountMsat, 1, 0)

  val btn = new Button("Generate")
  btn.defaultButtonProperty().bind(btn.focusedProperty())
  grid.add(btn, 1, 1)

  val labelPaymentRequest = new Label("Payment Request")
  grid.add(labelPaymentRequest, 0, 2)

  val textAreaPaymentRequest = new TextArea()
  textAreaPaymentRequest.setWrapText(true)
  textAreaPaymentRequest.setPrefRowCount(2)
  textAreaPaymentRequest.setPrefColumnCount(40)
  textAreaPaymentRequest.setEditable(false)
  grid.add(textAreaPaymentRequest, 1, 2)

  btn.setOnAction(new EventHandler[ActionEvent] {
    override def handle(event: ActionEvent): Unit = {
      Try(textFieldAmountMsat.getText.toLong) match {
        case Success(amountMsat) => handlers.getPaymentRequest(textFieldAmountMsat.getText.toLong, textAreaPaymentRequest)
        case _ => {}
      }
    }
  })

  val scene = new Scene(grid)
  setScene(scene)
}
