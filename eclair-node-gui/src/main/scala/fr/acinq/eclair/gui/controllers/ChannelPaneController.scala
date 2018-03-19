package fr.acinq.eclair.gui.controllers

import javafx.application.Platform
import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.fxml.FXML
import javafx.scene.control._
import javafx.scene.input.{ContextMenuEvent, MouseEvent}
import javafx.scene.layout.VBox

import fr.acinq.eclair.gui.utils.{ContextMenuUtils, CopyAction}
import grizzled.slf4j.Logging

/**
  * Created by DPA on 23/09/2016.
  */
class ChannelPaneController(val theirNodeIdValue: String) extends Logging {

  @FXML var root: VBox = _
  @FXML var channelId: TextField = _
  @FXML var txId: TextField = _
  @FXML var balanceBar: ProgressBar = _
  @FXML var amountUs: TextField = _
  @FXML var nodeId: TextField = _
  @FXML var capacity: TextField = _
  @FXML var funder: TextField = _
  @FXML var state: TextField = _
  @FXML var close: Button = _
  @FXML var forceclose: Button = _

  var contextMenu: ContextMenu = _

  private def buildChannelContextMenu() = {
    Platform.runLater(new Runnable() {
      override def run() = {
        contextMenu = ContextMenuUtils.buildCopyContext(List(
          CopyAction("Copy Channel Id", channelId.getText),
          CopyAction("Copy Peer Pubkey", theirNodeIdValue),
          CopyAction("Copy Tx Id", txId.getText())
        ))
        contextMenu.getStyleClass.add("context-channel")
        channelId.setContextMenu(contextMenu)
        amountUs.setContextMenu(contextMenu)
        nodeId.setContextMenu(contextMenu)
        capacity.setContextMenu(contextMenu)
        funder.setContextMenu(contextMenu)
        state.setContextMenu(contextMenu)
      }
    })
  }

  @FXML def initialize() = {
    channelId.textProperty.addListener(new ChangeListener[String] {
      override def changed(observable: ObservableValue[_ <: String], oldValue: String, newValue: String) = buildChannelContextMenu()
    })
    buildChannelContextMenu()
  }

  @FXML def openChannelContext(event: ContextMenuEvent) {
    contextMenu.show(channelId, event.getScreenX, event.getScreenY)
    event.consume()
  }

  @FXML def closeChannelContext(event: MouseEvent) {
    if (contextMenu != null) contextMenu.hide()
  }

  def updateRemoteNodeAlias(alias: String) {
    Option(nodeId).map((n: TextField) => n.setText(s"$theirNodeIdValue ($alias)"))
  }
}
