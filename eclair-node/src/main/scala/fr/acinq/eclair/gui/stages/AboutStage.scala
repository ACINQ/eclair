package fr.acinq.eclair.gui.stages

import javafx.application.HostServices
import javafx.fxml.FXMLLoader
import javafx.scene.image.Image
import javafx.scene.{Parent, Scene}
import javafx.stage.{Modality, Stage, StageStyle}

import fr.acinq.eclair.gui.controllers.AboutController

/**
  * Created by DPA on 28/09/2016.
  */
class AboutStage(hostServices: HostServices) extends Stage() {
  initModality(Modality.WINDOW_MODAL)
  initStyle(StageStyle.DECORATED)
  getIcons().add(new Image("/gui/commons/images/eclair02.png", false))
  setTitle("About Eclair")
  setResizable(false)

  // get fxml/controller
  val openFXML = new FXMLLoader(getClass.getResource("/gui/modals/about.fxml"))
  openFXML.setController(new AboutController(hostServices))
  val root = openFXML.load[Parent]

  // create scene
  val scene = new Scene(root)
  setScene(scene)
}
