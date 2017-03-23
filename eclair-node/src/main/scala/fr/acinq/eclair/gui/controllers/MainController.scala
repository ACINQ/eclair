package fr.acinq.eclair.gui.controllers

import javafx.application.{HostServices, Platform}
import javafx.beans.property._
import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.collections.ListChangeListener.Change
import javafx.collections.{FXCollections, ListChangeListener, ObservableList}
import javafx.event.{ActionEvent, EventHandler}
import javafx.fxml.FXML
import javafx.scene.control.TableColumn.CellDataFeatures
import javafx.scene.control._
import javafx.scene.input.ContextMenuEvent
import javafx.scene.layout.{BorderPane, VBox}
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.stage.FileChooser.ExtensionFilter
import javafx.stage._
import javafx.util.Callback

import fr.acinq.eclair.Setup
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.stages._
import fr.acinq.eclair.gui.utils.{ContextMenuUtils, CopyAction}
import fr.acinq.eclair.wire.{ChannelAnnouncement, NodeAnnouncement}
import grizzled.slf4j.Logging

/**
  * Created by DPA on 22/09/2016.
  */
class MainController(val handlers: Handlers, val setup: Setup, val hostServices: HostServices) extends Logging {

  @FXML var root: BorderPane = _
  var contextMenu: ContextMenu = _

  // menu
  @FXML var menuOpen: MenuItem = _
  @FXML var menuSend: MenuItem = _
  @FXML var menuReceive: MenuItem = _

  // status bar elements
  @FXML var labelNodeId: Label = _
  @FXML var rectRGB: Rectangle = _
  @FXML var labelAlias: Label = _
  @FXML var labelApi: Label = _
  @FXML var labelServer: Label = _
  @FXML var bitcoinVersion: Label = _
  @FXML var bitcoinChain: Label = _

  // channels tab elements
  @FXML var channelInfo: VBox = _
  @FXML var channelBox: VBox = _
  @FXML var channelsTab: Tab = _

  // all nodes tab
  val networkNodesList:ObservableList[NodeAnnouncement] = FXCollections.observableArrayList[NodeAnnouncement]()
  @FXML var networkNodesTab: Tab = _
  @FXML var networkNodesTable: TableView[NodeAnnouncement] = _
  @FXML var networkNodesIdColumn: TableColumn[NodeAnnouncement, String] = _
  @FXML var networkNodesAliasColumn: TableColumn[NodeAnnouncement, String] = _
  @FXML var networkNodesRGBColumn: TableColumn[NodeAnnouncement, String] = _
  @FXML var networkNodesIPColumn: TableColumn[NodeAnnouncement, String] = _

  // all channels
  val networkChannelsList:ObservableList[ChannelAnnouncement] = FXCollections.observableArrayList[ChannelAnnouncement]()
  @FXML var networkChannelsTab: Tab = _
  @FXML var networkChannelsTable: TableView[ChannelAnnouncement] = _
  @FXML var networkChannelsIdColumn: TableColumn[ChannelAnnouncement, Number] = _
  @FXML var networkChannelsNode1Column: TableColumn[ChannelAnnouncement, String] = _
  @FXML var networkChannelsNode2Column: TableColumn[ChannelAnnouncement, String] = _

  /**
    * Initialize the main window.
    *
    * - Set content in status bar labels (node id, host, ...)
    * - init the channels tab with a 'No channels found' message
    * - init the 'nodes in network' and 'channels in network' tables
    */
  @FXML def initialize = {

    // init status bar
    labelNodeId.setText(s"${setup.nodeParams.privateKey.publicKey}")
    labelAlias.setText(s"${setup.nodeParams.alias}")
    rectRGB.setFill(Color.rgb(setup.nodeParams.color._1 & 0xFF, setup.nodeParams.color._2 & 0xFF, setup.nodeParams.color._3 & 0xFF))
    labelApi.setText(s"${setup.config.getInt("api.port")}")
    labelServer.setText(s"${setup.config.getInt("server.port")}")
    bitcoinVersion.setText(s"v${setup.bitcoinVersion}")
    bitcoinChain.setText(s"${setup.chain.toUpperCase()}")
    bitcoinChain.getStyleClass.add(setup.chain)

    // init context
    contextMenu = ContextMenuUtils.buildCopyContext(List(
      new CopyAction("Copy Pubkey", s"${setup.nodeParams.privateKey.publicKey}"),
      new CopyAction("Copy URI", s"${setup.nodeParams.privateKey.publicKey}@${setup.nodeParams.address.getHostString}:${setup.nodeParams.address.getPort}" )))

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
    networkNodesTable.setItems(networkNodesList)
    networkNodesList.addListener(new ListChangeListener[NodeAnnouncement] {
      override def onChanged(c: Change[_ <: NodeAnnouncement]) = {
        Platform.runLater(new Runnable() {
          override def run = networkNodesTab.setText(s"All Nodes (${networkNodesList.size})")
        })
      }
    })
    networkNodesIdColumn.setCellValueFactory(new Callback[CellDataFeatures[NodeAnnouncement, String], ObservableValue[String]]() {
      def call(pn: CellDataFeatures[NodeAnnouncement, String]) = new SimpleStringProperty(pn.getValue.nodeId.toString)
    })
    networkNodesAliasColumn.setCellValueFactory(new Callback[CellDataFeatures[NodeAnnouncement, String], ObservableValue[String]]() {
      def call(pn: CellDataFeatures[NodeAnnouncement, String]) = new SimpleStringProperty(pn.getValue.alias)
    })
    networkNodesRGBColumn.setCellValueFactory(new Callback[CellDataFeatures[NodeAnnouncement, String], ObservableValue[String]]() {
      def call(pn: CellDataFeatures[NodeAnnouncement, String]) = new SimpleStringProperty(
        s"rgb(${new Integer(pn.getValue.rgbColor._1 & 0xFF)}, ${new Integer(pn.getValue.rgbColor._2 & 0xFF)}, ${new Integer(pn.getValue.rgbColor._3 & 0xFF)})")
    })
    networkNodesIPColumn.setCellValueFactory(new Callback[CellDataFeatures[NodeAnnouncement, String], ObservableValue[String]]() {
      def call(pn: CellDataFeatures[NodeAnnouncement, String]) =
        new SimpleStringProperty(s"${pn.getValue.addresses.head.getHostString}:${pn.getValue.addresses.head.getPort}")
    })
    networkNodesRGBColumn.setCellFactory(new Callback[TableColumn[NodeAnnouncement, String], TableCell[NodeAnnouncement, String]]() {
      def call(pn: TableColumn[NodeAnnouncement, String]) = {
        new TableCell[NodeAnnouncement, String] () {
          override def updateItem(item: String, empty: Boolean): Unit = {
            super.updateItem(item, empty)
            setStyle("-fx-background-color:" + item)
          }
        }
      }
    })
    networkNodesTable.setRowFactory(new Callback[TableView[NodeAnnouncement], TableRow[NodeAnnouncement]]() {
      override def call(table: TableView[NodeAnnouncement]): TableRow[NodeAnnouncement] = setupPeerNodeContextMenu
    })

    // init all channels
    networkChannelsTable.setItems(networkChannelsList)
    networkChannelsList.addListener(new ListChangeListener[ChannelAnnouncement] {
      override def onChanged(c: Change[_ <: ChannelAnnouncement]) = {
        Platform.runLater(new Runnable() {
          override def run = networkChannelsTab.setText(s"All Channels (${networkChannelsList.size})")
        })
      }
    })
    networkChannelsIdColumn.setCellValueFactory(new Callback[CellDataFeatures[ChannelAnnouncement, Number], ObservableValue[Number]]() {
      def call(pc: CellDataFeatures[ChannelAnnouncement, Number]) = new SimpleLongProperty(pc.getValue.shortChannelId)
    })
    networkChannelsNode1Column.setCellValueFactory(new Callback[CellDataFeatures[ChannelAnnouncement, String], ObservableValue[String]]() {
      def call(pc: CellDataFeatures[ChannelAnnouncement, String]) = new SimpleStringProperty(pc.getValue.nodeId1.toString)
    })
    networkChannelsNode2Column.setCellValueFactory(new Callback[CellDataFeatures[ChannelAnnouncement, String], ObservableValue[String]]() {
      def call(pc: CellDataFeatures[ChannelAnnouncement, String]) = new SimpleStringProperty(pc.getValue.nodeId2.toString)
    })
    networkChannelsTable.setRowFactory(new Callback[TableView[ChannelAnnouncement], TableRow[ChannelAnnouncement]]() {
      override def call(table: TableView[ChannelAnnouncement]): TableRow[ChannelAnnouncement] = setupPeerChannelContextMenu
    })
  }

  /**
    * Create a row for a node with context actions (copy node uri and id).
    * @return TableRow the created row
    */
  private def setupPeerNodeContextMenu(): TableRow[NodeAnnouncement] = {
    val row = new TableRow[NodeAnnouncement]
    val rowContextMenu = new ContextMenu
    val copyPubkey = new MenuItem("Copy Pubkey")
    copyPubkey.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = Option(row.getItem) match {
        case Some(pn) => ContextMenuUtils.copyToClipboard(pn.nodeId.toString)
        case None =>
      }
    })
    val copyURI = new MenuItem("Copy first known URI")
    copyURI.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = Option(row.getItem) match {
        case Some(pn) => ContextMenuUtils.copyToClipboard(
          if (pn.addresses.nonEmpty) s"${pn.nodeId.toString}@${pn.addresses.head.getHostString}:${pn.addresses.head.getPort}"
          else "no URI Known")
        case None =>
      }
    })
    rowContextMenu.getItems.addAll(copyPubkey, copyURI)
    row.setContextMenu(rowContextMenu)
    row
  }

  /**
    * Create a row for a PeerChannel with Copy context actions.
    * @return TableRow the created row
    */
  private def setupPeerChannelContextMenu(): TableRow[ChannelAnnouncement] = {
    val row = new TableRow[ChannelAnnouncement]
    val rowContextMenu = new ContextMenu
    val copyChannelId = new MenuItem("Copy Channel Id")
    copyChannelId.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = Option(row.getItem) match {
        case Some(pc) => ContextMenuUtils.copyToClipboard(pc.shortChannelId.toString)
        case None =>
      }
    })
    val copyNode1 = new MenuItem("Copy Node 1")
    copyNode1.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = Option(row.getItem) match {
        case Some(pc) => ContextMenuUtils.copyToClipboard(pc.nodeId1.toString)
        case None =>
      }
    })
    val copyNode2 = new MenuItem("Copy Node 2")
    copyNode2.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = Option(row.getItem) match {
        case Some(pc) => ContextMenuUtils.copyToClipboard(pc.nodeId2.toString)
        case None =>
      }
    })
    rowContextMenu.getItems.addAll(copyChannelId, copyNode1, copyNode2)
    row.setContextMenu(rowContextMenu)
    row
  }

  @FXML def handleExportDot = {
    val fileChooser = new FileChooser
    fileChooser.setTitle("Save as")
    fileChooser.getExtensionFilters.addAll(new ExtensionFilter("DOT File (*.dot)", "*.dot"))
    val file = fileChooser.showSaveDialog(root.getScene.getWindow)
    if (file != null) handlers.exportToDot(file)
  }

  @FXML def handleOpenChannel = {
    val openChannelStage = new OpenChannelStage(handlers, setup)
    openChannelStage.initOwner(root.getScene.getWindow)
    positionAtCenter(openChannelStage)
    openChannelStage.show
  }

  @FXML def handleSendPayment = {
    val sendPaymentStage = new SendPaymentStage(handlers, setup)
    sendPaymentStage.initOwner(root.getScene.getWindow)
    positionAtCenter(sendPaymentStage)
    sendPaymentStage.show
  }

  @FXML def handleReceivePayment = {
    val receiveStage = new ReceivePaymentStage(handlers, setup)
    receiveStage.initOwner(root.getScene.getWindow)
    positionAtCenter(receiveStage)
    receiveStage.show
  }

  @FXML def handleCloseRequest = root.getScene.getWindow.fireEvent(new WindowEvent(root.getScene.getWindow, WindowEvent.WINDOW_CLOSE_REQUEST))

  @FXML def handleOpenAbout = {
    val aboutStage = new AboutStage(hostServices)
    aboutStage.initOwner(root.getScene.getWindow)
    positionAtCenter(aboutStage)
    aboutStage.show
  }

  @FXML def openNodeIdContext(event: ContextMenuEvent) = contextMenu.show(labelNodeId, event.getScreenX, event.getScreenY)

  def positionAtCenter(childStage: Stage) = {
    childStage.setX(root.getScene.getWindow.getX + root.getScene.getWindow.getWidth / 2 - childStage.getWidth / 2)
    childStage.setY(root.getScene.getWindow.getY + root.getScene.getWindow.getHeight / 2 - childStage.getHeight / 2)
  }
}
