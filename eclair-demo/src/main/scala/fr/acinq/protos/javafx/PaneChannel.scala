package fr.acinq.protos.javafx

import javafx.geometry.Pos
import javafx.scene.control.{Button, Label, ProgressBar}
import javafx.scene.layout.GridPane

/**
  * Created by PM on 17/08/2016.
  */
class PaneChannel extends GridPane {
  setAlignment(Pos.CENTER_LEFT)
  setHgap(10)
  setVgap(10)
  val labelChannelId = new Label(s"channel #<id>")
  add(labelChannelId, 0, 0)
  val progressBarBalance = new ProgressBar(0)
  progressBarBalance.setPrefSize(600, 10)
  add(progressBarBalance, 1, 0)
  val labelNode = new Label(s"#<node>")
  add(labelNode, 2, 0)
  val labelFunder = new Label(s"<funder>")
  labelFunder.setPrefWidth(40)
  add(labelFunder, 3, 0)
  val labelState = new Label(s"<state>")
  add(labelState, 4, 0)
  val buttonClose = new Button("Close")
  add(buttonClose, 5, 0)
}
