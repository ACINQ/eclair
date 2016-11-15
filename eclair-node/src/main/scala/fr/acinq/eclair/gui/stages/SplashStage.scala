package fr.acinq.eclair.gui.stages

import javafx.fxml.FXMLLoader
import javafx.scene.image.Image
import javafx.scene.paint.Color
import javafx.scene.{Parent, Scene}
import javafx.stage.{Modality, Stage, StageStyle}

import fr.acinq.eclair.gui.controllers.SplashController

/**
  * Created by PM on 16/08/2016.
  *
  * Modal/transparent stage displaying a splash image
  */
class SplashStage() extends Stage() {

  // set stage props
  initModality(Modality.WINDOW_MODAL)
  initStyle(StageStyle.TRANSPARENT)
  setResizable(false)

  val icon = new Image("/gui/commons/images/eclair02.png", false)
  this.getIcons().add(icon)

  // get fxml/controller
  val splash = new FXMLLoader(getClass.getResource("/gui/splash/splash.fxml"))
  val root = splash.load[Parent]
  val controller = splash.getController[SplashController]

  // create scene
  val scene = new Scene(root)
  scene.setFill(Color.TRANSPARENT)

  setScene(scene)
}
