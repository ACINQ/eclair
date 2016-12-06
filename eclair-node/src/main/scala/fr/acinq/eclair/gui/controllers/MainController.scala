package fr.acinq.eclair.gui.controllers

import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.embed.swing.SwingNode
import javafx.fxml.FXML
import javafx.scene.control.{ContextMenu, Label, MenuItem, Tab}
import javafx.scene.input.ContextMenuEvent
import javafx.scene.layout.{BorderPane, TilePane, VBox}
import javafx.stage.Stage

import com.mxgraph.swing.mxGraphComponent
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.stages.{AboutStage, OpenChannelStage, ReceivePaymentStage, SendPaymentStage}
import fr.acinq.eclair.gui.utils.ContextMenuUtils
import fr.acinq.eclair.{Globals, Setup}
import grizzled.slf4j.Logging

/**
  * Created by DPA on 22/09/2016.
  */
class MainController(val handlers: Handlers, val stage: Stage, val setup: Setup) extends BaseController with Logging {

  @FXML var root: BorderPane = _
  @FXML var menuOpen: MenuItem = _
  @FXML var menuSend: MenuItem = _
  @FXML var menuReceive: MenuItem = _
  @FXML var labelNodeId: Label = _
  @FXML var labelApi: Label = _
  @FXML var labelServer: Label = _
  @FXML var bitcoinVersion: Label = _
  @FXML var bitcoinChain: Label = _

  @FXML var channelInfo: VBox = _
  @FXML var channelBox: VBox = _
  @FXML var tilePane: TilePane = _
  @FXML var graphTab: Tab = _

  val swingNode: SwingNode = new SwingNode()

  var contextMenu: ContextMenu = _

  @FXML def initialize() = {
    labelNodeId.setText(s"${Globals.Node.id}")
    labelApi.setText(s"${setup.config.getInt("eclair.api.port")}")
    labelServer.setText(s"${setup.config.getInt("eclair.server.port")}")

    bitcoinVersion.setText(s"v${setup.bitcoinVersion}")
    bitcoinChain.setText(s"${setup.chain.toUpperCase()}")
    bitcoinChain.getStyleClass.add(setup.chain)

    graphTab.setContent(swingNode)
    contextMenu = ContextMenuUtils.buildCopyContext(Globals.Node.id)

    if (channelBox.getChildren.size() > 0) {
      channelInfo.setScaleY(0)
      channelInfo.setOpacity(0)
    }

    channelBox.heightProperty().addListener(new ChangeListener[Number] {
      override def changed(observable: ObservableValue[_ <: Number], oldValue: Number, newValue: Number): Unit = {
        if (channelBox.getChildren.size() > 0) {
          channelInfo.setScaleY(0)
          channelInfo.setOpacity(0)
        }
      }
    })
  }

  @FXML def handleOpenChannel() = {
    val openChannelStage = new OpenChannelStage(handlers, setup);
    openChannelStage.initOwner(stage)
    positionAtCenter(openChannelStage)
    openChannelStage.show()
  }

  @FXML def handleSendPayment() = {
    val sendPaymentStage = new SendPaymentStage(handlers, setup)
    sendPaymentStage.initOwner(stage)
    positionAtCenter(sendPaymentStage)
    sendPaymentStage.show()
  }

  @FXML def handleReceivePayment() = {
    val receiveStage = new ReceivePaymentStage(handlers, setup)
    receiveStage.initOwner(stage)
    positionAtCenter(receiveStage)
    receiveStage.show()
  }

  @FXML def handleCloseRequest(): Unit = {
    stage.close()
  }

  @FXML def handleOpenAbout: Unit = {
    val aboutStage = new AboutStage()
    aboutStage.initOwner(stage)
    aboutStage.show()
  }

  def handleRefreshGraph: Unit = {
    Option(swingNode.getContent) match {
      case Some(component: mxGraphComponent) =>
        component.doLayout()
        component.repaint()
        component.refresh()
      case _ => {}
    }
  }

  @FXML def handleNodeIdContext(event: ContextMenuEvent): Unit = {
      contextMenu.show(labelNodeId, event.getScreenX, event.getScreenY)
  }

  def positionAtCenter(childStage: Stage): Unit = {
    childStage.setX(stage.getX() + stage.getWidth() / 2 - childStage.getWidth() / 2)
    childStage.setY(stage.getY() + stage.getHeight() / 2 - childStage.getHeight() / 2)
  }
}
