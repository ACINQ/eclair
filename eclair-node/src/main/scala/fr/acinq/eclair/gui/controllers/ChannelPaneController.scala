package fr.acinq.eclair.gui.controllers

import javafx.fxml.FXML
import javafx.scene.control.{Button, ContextMenu, Label, ProgressBar}
import javafx.scene.input.ContextMenuEvent

import fr.acinq.eclair.gui.utils.ContextMenuUtils
import grizzled.slf4j.Logging

/**
  * Created by DPA on 23/09/2016.
  */
class ChannelPaneController(theirNodeIdValue: String) extends Logging {

  var channelIdValue = ""
  @FXML var channelId: Label = _
  @FXML var balanceBar: ProgressBar = _
  @FXML var amountUs: Label = _
  @FXML var nodeId: Label = _
  @FXML var capacity: Label = _
  @FXML var funder: Label = _
  @FXML var state: Label = _
  @FXML var close: Button = _

  var contextMenu: ContextMenu = _

  @FXML def handleChannelIdContext(event: ContextMenuEvent): Unit = {
    if (contextMenu != null) contextMenu.hide()
    contextMenu = ContextMenuUtils.buildCopyContext(channelIdValue)
    contextMenu.show(channelId, event.getScreenX, event.getScreenY)
  }

  @FXML def handleTheirNodeIdContext(event: ContextMenuEvent): Unit = {
    if (contextMenu != null) contextMenu.hide()
    contextMenu = ContextMenuUtils.buildCopyContext(theirNodeIdValue)
    contextMenu.show(nodeId, event.getScreenX, event.getScreenY)
  }
}
