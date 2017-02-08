package fr.acinq.eclair.gui.controllers

import javafx.fxml.FXML
import javafx.scene.control.{Button, ContextMenu, ProgressBar, TextField}
import javafx.scene.input.{ContextMenuEvent, MouseEvent}

import fr.acinq.eclair.channel.LocalParams
import fr.acinq.eclair.gui.utils.{ContextMenuUtils, CopyAction}
import grizzled.slf4j.Logging

/**
  * Created by DPA on 23/09/2016.
  */
class ChannelPaneController(theirNodeIdValue: String, channelParams: LocalParams) extends Logging {

  var channelIdValue = ""
  @FXML var channelId: TextField = _
  @FXML var balanceBar: ProgressBar = _
  @FXML var amountUs: TextField = _
  @FXML var nodeId: TextField = _
  @FXML var capacity: TextField = _
  @FXML var funder: TextField = _
  @FXML var state: TextField = _
  @FXML var close: Button = _

  var contextMenu: ContextMenu = _

  @FXML def openChannelContext(event: ContextMenuEvent): Unit = {
    if (contextMenu != null) contextMenu.hide()
    contextMenu = ContextMenuUtils.buildCopyContext(List(
      new CopyAction("Copy Channel Id", channelIdValue),
      new CopyAction("Copy Node Pubkey", theirNodeIdValue)
    ))
    contextMenu.show(channelId, event.getScreenX, event.getScreenY)
  }

  @FXML def closeChannelContext(event: MouseEvent): Unit = {
    if (contextMenu != null) contextMenu.hide()
  }
}
