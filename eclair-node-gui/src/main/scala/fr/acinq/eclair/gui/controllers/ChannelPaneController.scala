/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.gui.controllers

import akka.actor.ActorRef
import com.google.common.base.Strings
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{CMD_CLOSE, CMD_FORCECLOSE, AbstractCommitments, Commitments}
import fr.acinq.eclair.gui.FxApp
import fr.acinq.eclair.gui.utils.{ContextMenuUtils, CopyAction}
import grizzled.slf4j.Logging
import javafx.application.Platform
import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.event.{ActionEvent, EventHandler}
import javafx.fxml.FXML
import javafx.scene.control.Alert.AlertType
import javafx.scene.control._
import javafx.scene.input.{ContextMenuEvent, MouseEvent}
import javafx.scene.layout.VBox

/**
  * Created by DPA on 23/09/2016.
  */
class ChannelPaneController(val channelRef: ActorRef, val peerNodeId: String) extends Logging {

  @FXML var root: VBox = _
  @FXML var channelId: TextField = _
  @FXML var shortChannelId: TextField = _
  @FXML var txId: TextField = _
  @FXML var balanceBar: ProgressBar = _
  @FXML var amountUs: Label = _
  @FXML var nodeAlias: Label = _
  @FXML var nodeId: TextField = _
  @FXML var state: TextField = _
  @FXML var funder: TextField = _
  @FXML var close: Button = _
  @FXML var forceclose: Button = _

  private var contextMenu: ContextMenu = _
  private var balance: MilliSatoshi = MilliSatoshi(0)
  private var capacity: MilliSatoshi = MilliSatoshi(0)

  private def buildChannelContextMenu(): Unit = {
    Platform.runLater(new Runnable() {
      override def run() = {
        contextMenu = ContextMenuUtils.buildCopyContext(List(
          CopyAction("Copy channel id", channelId.getText),
          CopyAction("Copy peer pubkey", peerNodeId),
          CopyAction("Copy tx id", txId.getText())
        ))
        contextMenu.getStyleClass.add("context-channel")
        channelId.setContextMenu(contextMenu)
        shortChannelId.setContextMenu(contextMenu)
        txId.setContextMenu(contextMenu)
        amountUs.setContextMenu(contextMenu)
        nodeId.setContextMenu(contextMenu)
        funder.setContextMenu(contextMenu)
        state.setContextMenu(contextMenu)
      }
    })
  }

  @FXML def initialize() = {
    channelId.textProperty.addListener(new ChangeListener[String] {
      override def changed(observable: ObservableValue[_ <: String], oldValue: String, newValue: String) = buildChannelContextMenu()
    })
    nodeId.setText(peerNodeId)
    nodeAlias.managedProperty.bind(nodeAlias.visibleProperty)
    close.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = {
        val alert = new Alert(AlertType.CONFIRMATION,
          s"""
            |Are you sure you want to close this channel?
            |
            |$getChannelDetails
            |""".stripMargin, ButtonType.YES, ButtonType.NO)
        alert.showAndWait
        if (alert.getResult eq ButtonType.YES) {
          channelRef ! CMD_CLOSE(ActorRef.noSender, scriptPubKey = None, feerates = None)
        }
      }
    })
    forceclose.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = {
        val alert = new Alert(AlertType.WARNING,
          s"""
            |Careful: force-close is more expensive than a regular close and will incur a delay before funds are spendable.
            |
            |Are you sure you want to forcibly close this channel?
            |
            |$getChannelDetails
            """.stripMargin, ButtonType.YES, ButtonType.NO)
        alert.showAndWait
        if (alert.getResult eq ButtonType.YES) {
          channelRef ! CMD_FORCECLOSE(ActorRef.noSender)
        }
      }
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
    nodeAlias.setText(alias)
    nodeAlias.setVisible(!Strings.isNullOrEmpty(alias))
  }

  def updateBalance(commitments: Commitments) {
    balance = commitments.localCommit.spec.toLocal
    capacity = commitments.capacity.toMilliSatoshi
  }

  def refreshBalance(): Unit = {
    amountUs.setText(s"${CoinUtils.formatAmountInUnit(balance, FxApp.getUnit, false)} / ${CoinUtils.formatAmountInUnit(capacity, FxApp.getUnit, withUnit = true)}")
    balanceBar.setProgress(balance.toLong.toDouble / capacity.toLong)
  }

  def getBalance: MilliSatoshi = balance

  def getCapacity: MilliSatoshi = capacity

  def getChannelDetails: String =
    s"""
      |Channel details:
      |---
      |Id: ${channelId.getText().substring(0, 18)}...
      |Peer: ${peerNodeId.toString().substring(0, 18)}...
      |Balance: ${CoinUtils.formatAmountInUnit(getBalance, FxApp.getUnit, withUnit = true)}
      |State: ${state.getText}
      |"""
}
