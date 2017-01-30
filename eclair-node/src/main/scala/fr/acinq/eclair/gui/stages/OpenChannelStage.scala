package fr.acinq.eclair.gui.stages

import javafx.fxml.FXMLLoader
import javafx.scene.image.Image
import javafx.scene.{Parent, Scene}
import javafx.stage.{Modality, Stage, StageStyle}

import fr.acinq.eclair.Setup
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.controllers.OpenChannelController

/**
  * Created by PM on 16/08/2016.
  */
class OpenChannelStage(handlers: Handlers, setup: Setup) extends Stage() {
  initModality(Modality.WINDOW_MODAL)
  initStyle(StageStyle.DECORATED)
  getIcons().add(new Image("/gui/commons/images/eclair02.png", false))
  setTitle("Open a new channel")
  setWidth(550)
  setHeight(350)
  setResizable(false)

  // get fxml/controller
  val openFXML = new FXMLLoader(getClass.getResource("/gui/modals/openChannel.fxml"))
  openFXML.setController(new OpenChannelController(handlers, this, setup))
  val root = openFXML.load[Parent]

  // create scene
  val scene = new Scene(root)
  setScene(scene)
}
