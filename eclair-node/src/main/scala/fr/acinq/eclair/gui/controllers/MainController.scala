package fr.acinq.eclair.gui.controllers

import javafx.application.HostServices
import javafx.beans.property.{SimpleStringProperty, StringProperty}
import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.collections.{FXCollections, ObservableList}
import javafx.embed.swing.SwingNode
import javafx.fxml.FXML
import javafx.scene.control.TableColumn.CellDataFeatures
import javafx.scene.control._
import javafx.scene.input.{ContextMenuEvent, MouseEvent}
import javafx.scene.layout.{BorderPane, VBox}
import javafx.stage.Stage
import javafx.util.Callback

import com.mxgraph.swing.mxGraphComponent
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.stages.{AboutStage, OpenChannelStage, ReceivePaymentStage, SendPaymentStage}
import fr.acinq.eclair.gui.utils.{ContextMenuUtils, CopyAction}
import fr.acinq.eclair.wire.{ChannelAnnouncement, NodeAnnouncement}
import fr.acinq.eclair.{Globals, Setup}
import grizzled.slf4j.Logging


case class PeerNode(id: StringProperty, alias: StringProperty) {
  def this(na: NodeAnnouncement) = this(new SimpleStringProperty(na.nodeId.toString), new SimpleStringProperty(na.alias))
}
case class PeerChannel(id: StringProperty) {
  def this(ca: ChannelAnnouncement) = this(new SimpleStringProperty(ca.channelId.toString))
}

/**
  * Created by DPA on 22/09/2016.
  */
class MainController(val handlers: Handlers, val stage: Stage, val setup: Setup, val hostServices: HostServices) extends BaseController with Logging {

  @FXML var root: BorderPane = _
  var contextMenu: ContextMenu = _

  // menu
  @FXML var menuOpen: MenuItem = _
  @FXML var menuSend: MenuItem = _
  @FXML var menuReceive: MenuItem = _

  // status bar elements
  @FXML var labelNodeId: Label = _
  @FXML var labelApi: Label = _
  @FXML var labelServer: Label = _
  @FXML var bitcoinVersion: Label = _
  @FXML var bitcoinChain: Label = _

  // channels tab elements
  @FXML var channelInfo: VBox = _
  @FXML var channelBox: VBox = _
  @FXML var channelsTab: Tab = _

  // graph tab
  @FXML var graphTab: Tab = _
  val swingNode: SwingNode = new SwingNode()

  // all nodes tab
  val allNodesList:ObservableList[PeerNode] = FXCollections.observableArrayList[PeerNode]()
  @FXML var allNodesTab: Tab = _
  @FXML var allNodesTable: TableView[PeerNode] = _
  @FXML var allNodesIdColumn: TableColumn[PeerNode, String] = _
  @FXML var allNodesAliasColumn: TableColumn[PeerNode, String] = _

  // all channels
  val allChannelsList:ObservableList[PeerChannel] = FXCollections.observableArrayList[PeerChannel]()
  @FXML var allChannelsTab: Tab = _
  @FXML var allChannelsTable: TableView[PeerChannel] = _
  @FXML var allChannelsIdColumn: TableColumn[PeerChannel, String] = _

  @FXML def initialize() = {

    // init status bar
    labelNodeId.setText(s"${Globals.Node.id}")
    labelApi.setText(s"${setup.config.getInt("eclair.api.port")}")
    labelServer.setText(s"${setup.config.getInt("eclair.server.port")}")
    bitcoinVersion.setText(s"v${setup.bitcoinVersion}")
    bitcoinChain.setText(s"${setup.chain.toUpperCase()}")
    bitcoinChain.getStyleClass.add(setup.chain)

    // init context
    contextMenu = ContextMenuUtils.buildCopyContext(List(
      new CopyAction("Copy Pubkey", Globals.Node.id),
      new CopyAction("Copy URI", s"${Globals.Node.id}@${Globals.Node.address.getHostString}:${Globals.Node.address.getPort}" )))

    // init graph
    //graphTab.setContent(swingNode)

    // init channels tab
    if (channelBox.getChildren.size() > 0) {
      channelInfo.setScaleY(0)
      channelInfo.setOpacity(0)
    }
    channelBox.heightProperty().addListener(new ChangeListener[Number] {
      override def changed(observable: ObservableValue[_ <: Number], oldValue: Number, newValue: Number): Unit = {
        if (channelBox.getChildren.size() > 0) {
          channelInfo.setScaleY(0)
          channelInfo.setOpacity(0)
        } else {
          channelInfo.setScaleY(1)
          channelInfo.setOpacity(1)
        }
        channelsTab.setText(s"Local Channels (${channelBox.getChildren.size})")
      }
    })

    // init all nodes
    allNodesTable.setItems(allNodesList)
    allNodesIdColumn.setCellValueFactory(new Callback[CellDataFeatures[PeerNode, String], ObservableValue[String]]() {
      def call(pn: CellDataFeatures[PeerNode, String]) = pn.getValue().id
    })
    allNodesAliasColumn.setCellValueFactory(new Callback[CellDataFeatures[PeerNode, String], ObservableValue[String]]() {
      def call(pn: CellDataFeatures[PeerNode, String]) = pn.getValue().alias
    })

    // init all channels
    allChannelsTable.setItems(allChannelsList)
    allChannelsIdColumn.setCellValueFactory(new Callback[CellDataFeatures[PeerChannel, String], ObservableValue[String]]() {
      def call(pc: CellDataFeatures[PeerChannel, String]) = pc.getValue().id
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
    val aboutStage = new AboutStage(hostServices)
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

  @FXML def openNodeIdContext(event: ContextMenuEvent): Unit = {
    contextMenu.show(labelNodeId, event.getScreenX, event.getScreenY)
  }

  @FXML def closeNodeIdContext(event: MouseEvent): Unit = {
    contextMenu.hide()
  }

  def positionAtCenter(childStage: Stage): Unit = {
    childStage.setX(stage.getX() + stage.getWidth() / 2 - childStage.getWidth() / 2)
    childStage.setY(stage.getY() + stage.getHeight() / 2 - childStage.getHeight() / 2)
  }
}
