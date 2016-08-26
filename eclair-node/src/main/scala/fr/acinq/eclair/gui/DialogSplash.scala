package fr.acinq.eclair.gui

import javafx.scene.image.{Image, ImageView}
import javafx.scene.layout.StackPane
import javafx.scene.Scene
import javafx.stage.{Modality, Stage, StageStyle}

/**
  * Created by PM on 16/08/2016.
  */
class DialogSplash(primaryStage: Stage) extends Stage() {
  initModality(Modality.WINDOW_MODAL)
  initStyle(StageStyle.UNDECORATED)
  initOwner(primaryStage)

  setWidth(500)
  setHeight(500)
  setResizable(false)

  val image = new Image("/eclair01.png", true)
  val icon = new Image("/eclair02.png", true)
  primaryStage.getIcons().add(icon)
  val view = new ImageView(image)

  val pane = new StackPane()
  pane.getChildren.add(view)

  val scene = new Scene(pane)
  setScene(scene)
}
