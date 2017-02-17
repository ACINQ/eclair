package fr.acinq.eclair.gui.stages

import javafx.stage.{Modality, Screen, Stage, StageStyle}

/**
  * Created by DPA on 17/02/2017.
  */
class NotificationsStage extends Stage {
  // set stage props
  initModality(Modality.NONE)
  initStyle(StageStyle.TRANSPARENT)
  setAlwaysOnTop(true)

  val WIDTH = 310
  setMinWidth(WIDTH)
  setWidth(WIDTH)
  setMaxWidth(WIDTH)

  val screenBounds = Screen.getPrimary.getVisualBounds
  setX(screenBounds.getMaxX - WIDTH)
  setY(screenBounds.getMinY + 10)
  setHeight(screenBounds.getMaxY - 10)
}
