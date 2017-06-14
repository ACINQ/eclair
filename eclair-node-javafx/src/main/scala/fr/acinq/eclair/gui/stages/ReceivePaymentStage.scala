package fr.acinq.eclair.gui.stages

import javafx.fxml.FXMLLoader
import javafx.scene.image.Image
import javafx.scene.{Parent, Scene}
import javafx.stage.{Modality, Stage, StageStyle}

import fr.acinq.eclair.Setup
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.controllers.ReceivePaymentController

/**
  * Created by PM on 16/08/2016.
  */
class ReceivePaymentStage(handlers: Handlers, setup: Setup) extends Stage() {
  initModality(Modality.WINDOW_MODAL)
  initStyle(StageStyle.DECORATED)
  getIcons().add(new Image("/gui/commons/images/eclair-square.png", false))
  setTitle("Receive a Payment")
  setMinWidth(550)
  setWidth(550)
  setMinHeight(170)
  setHeight(170)

  // get fxml/controller
  val receivePayment = new FXMLLoader(getClass.getResource("/gui/modals/receivePayment.fxml"))
  receivePayment.setController(new ReceivePaymentController(handlers, this, setup))
  val root = receivePayment.load[Parent]

  // create scene
  val scene = new Scene(root)
  setScene(scene)
}
