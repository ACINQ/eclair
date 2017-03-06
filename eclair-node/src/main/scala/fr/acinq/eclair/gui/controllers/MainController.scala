package fr.acinq.eclair.gui.controllers

import java.net.InetSocketAddress
import javafx.application.{HostServices, Platform}
import javafx.beans.property._
import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.collections.{FXCollections, ObservableList}
import javafx.event.{ActionEvent, EventHandler}
import javafx.fxml.{FXML, FXMLLoader}
import javafx.scene.{Parent, Scene}
import javafx.scene.control.TableColumn.CellDataFeatures
import javafx.scene.control._
import javafx.scene.input.{ContextMenuEvent, MouseEvent}
import javafx.scene.layout.{BorderPane, VBox}
import javafx.scene.paint.Color
import javafx.stage.FileChooser.ExtensionFilter
import javafx.stage._
import javafx.util.Callback

import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.stages._
import fr.acinq.eclair.gui.utils.{ContextMenuUtils, CopyAction}
import fr.acinq.eclair.wire.{ChannelAnnouncement, NodeAnnouncement}
import fr.acinq.eclair.{Globals, Setup}
import grizzled.slf4j.Logging
//
case class PeerNode(id: StringProperty, alias: StringProperty, rgbColor: StringProperty, addresses: List[InetSocketAddress]) {
  def this(na: NodeAnnouncement) = {
    this(new SimpleStringProperty(na.nodeId.toString), new SimpleStringProperty(na.alias),
      new SimpleStringProperty("rgb(" + new Integer(na.rgbColor._1 & 0xFF) + "," + new Integer(na.rgbColor._2 & 0xFF) + "," + new Integer(na.rgbColor._3 & 0xFF) + ")"),
      na.addresses)
  }
}

case class PeerChannel(id: LongProperty, nodeId1: StringProperty, nodeId2: StringProperty) {
  def this(ca: ChannelAnnouncement) = this(new SimpleLongProperty(ca.shortChannelId), new SimpleStringProperty(ca.nodeId1.toString), new SimpleStringProperty(ca.nodeId2.toString))
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

  // all nodes tab
  val allNodesList:ObservableList[PeerNode] = FXCollections.observableArrayList[PeerNode]()
  @FXML var allNodesTab: Tab = _
  @FXML var allNodesTable: TableView[PeerNode] = _
  @FXML var allNodesIdColumn: TableColumn[PeerNode, String] = _
  @FXML var allNodesAliasColumn: TableColumn[PeerNode, String] = _
  @FXML var allNodesRGBColumn: TableColumn[PeerNode, String] = _

  // all channels
  val allChannelsList:ObservableList[PeerChannel] = FXCollections.observableArrayList[PeerChannel]()
  @FXML var allChannelsTab: Tab = _
  @FXML var allChannelsTable: TableView[PeerChannel] = _
  @FXML var allChannelsIdColumn: TableColumn[PeerChannel, Number] = _
  @FXML var allChannelsNode1Column: TableColumn[PeerChannel, String] = _
  @FXML var allChannelsNode2Column: TableColumn[PeerChannel, String] = _

  /**
    * Initialize the main window.
    *
    * - Set content in status bar labels (node id, host, ...)
    * - init the channels tab with a 'No channels found' message
    * - init the 'nodes in network' and 'channels in network' tables
    */
  @FXML def initialize = {

    initNotifs

    // init status bar
    labelNodeId.setText(s"${setup.nodeParams.privateKey.publicKey}")
    labelApi.setText(s"${setup.config.getInt("eclair.api.port")}")
    labelServer.setText(s"${setup.config.getInt("eclair.server.port")}")
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
    allNodesTable.setItems(allNodesList)
    allNodesIdColumn.setCellValueFactory(new Callback[CellDataFeatures[PeerNode, String], ObservableValue[String]]() {
      def call(pn: CellDataFeatures[PeerNode, String]) = pn.getValue.id
    })
    allNodesAliasColumn.setCellValueFactory(new Callback[CellDataFeatures[PeerNode, String], ObservableValue[String]]() {
      def call(pn: CellDataFeatures[PeerNode, String]) = pn.getValue.alias
    })
    allNodesRGBColumn.setCellValueFactory(new Callback[CellDataFeatures[PeerNode, String], ObservableValue[String]]() {
      def call(pn: CellDataFeatures[PeerNode, String]) = pn.getValue.rgbColor
    })
    allNodesRGBColumn.setCellFactory(new Callback[TableColumn[PeerNode, String], TableCell[PeerNode, String]]() {
      def call(pn: TableColumn[PeerNode, String]) = {
        new TableCell[PeerNode, String] () {
          override def updateItem(item: String, empty: Boolean): Unit = {
            super.updateItem(item, empty)
            setStyle("-fx-background-color:" + item)
          }
        }
      }
    })
    allNodesTable.setRowFactory(new Callback[TableView[PeerNode], TableRow[PeerNode]]() {
      override def call(table: TableView[PeerNode]): TableRow[PeerNode] = setupPeerNodeContextMenu
    })

    // init all channels
    allChannelsTable.setItems(allChannelsList)
    allChannelsIdColumn.setCellValueFactory(new Callback[CellDataFeatures[PeerChannel, Number], ObservableValue[Number]]() {
      def call(pc: CellDataFeatures[PeerChannel, Number]) = pc.getValue.id
    })
    allChannelsNode1Column.setCellValueFactory(new Callback[CellDataFeatures[PeerChannel, String], ObservableValue[String]]() {
      def call(pc: CellDataFeatures[PeerChannel, String]) = pc.getValue.nodeId1
    })
    allChannelsNode2Column.setCellValueFactory(new Callback[CellDataFeatures[PeerChannel, String], ObservableValue[String]]() {
      def call(pc: CellDataFeatures[PeerChannel, String]) = pc.getValue.nodeId2
    })
    allChannelsTable.setRowFactory(new Callback[TableView[PeerChannel], TableRow[PeerChannel]]() {
      override def call(table: TableView[PeerChannel]): TableRow[PeerChannel] = setupPeerChannelContextMenu
    })
  }

  /**
    * Create a row for a PeerNode with Copy context actions.
    * @return TableRow the created row
    */
  private def setupPeerNodeContextMenu(): TableRow[PeerNode] = {
    val row = new TableRow[PeerNode]
    val rowContextMenu = new ContextMenu
    val copyPubkey = new MenuItem("Copy Pubkey")
    copyPubkey.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = Option(row.getItem) match {
        case Some(pn) => ContextMenuUtils.copyToClipboard(pn.id.getValue)
        case None =>
      }
    })
    val copyURI = new MenuItem("Copy first known URI")
    copyURI.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = Option(row.getItem) match {
        case Some(pn) => ContextMenuUtils.copyToClipboard(
          if (pn.addresses.nonEmpty) s"${pn.id.getValue}@${pn.addresses.head.getHostString}:${pn.addresses.head.getPort}"
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
  private def setupPeerChannelContextMenu(): TableRow[PeerChannel] = {
    val row = new TableRow[PeerChannel]
    val rowContextMenu = new ContextMenu
    val copyChannelId = new MenuItem("Copy Channel Id")
    copyChannelId.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = Option(row.getItem) match {
        case Some(pc) => ContextMenuUtils.copyToClipboard(pc.id.getValue.toString)
        case None =>
      }
    })
    val copyNode1 = new MenuItem("Copy Node 1")
    copyNode1.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = Option(row.getItem) match {
        case Some(pc) => ContextMenuUtils.copyToClipboard(pc.nodeId1.getValue)
        case None =>
      }
    })
    val copyNode2 = new MenuItem("Copy Node 2")
    copyNode2.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = Option(row.getItem) match {
        case Some(pc) => ContextMenuUtils.copyToClipboard(pc.nodeId2.getValue)
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
    val file = fileChooser.showSaveDialog(stage)
    if (file != null) handlers.exportToDot(file)
  }

  @FXML def handleOpenChannel = {
    val openChannelStage = new OpenChannelStage(handlers, setup)
    openChannelStage.initOwner(stage)
    positionAtCenter(openChannelStage)
    openChannelStage.show
  }

  @FXML def handleSendPayment = {
    val sendPaymentStage = new SendPaymentStage(handlers, setup)
    sendPaymentStage.initOwner(stage)
    positionAtCenter(sendPaymentStage)
    sendPaymentStage.show
  }

  @FXML def handleReceivePayment = {
    val receiveStage = new ReceivePaymentStage(handlers, setup)
    receiveStage.initOwner(stage)
    positionAtCenter(receiveStage)
    receiveStage.show
  }

  @FXML def handleCloseRequest = stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST))

  @FXML def handleOpenAbout = {
    val aboutStage = new AboutStage(hostServices)
    aboutStage.initOwner(stage)
    aboutStage.show
  }

  @FXML def openNodeIdContext(event: ContextMenuEvent) = contextMenu.show(labelNodeId, event.getScreenX, event.getScreenY)
  @FXML def closeNodeIdContext(event: MouseEvent) = contextMenu.hide

  def positionAtCenter(childStage: Stage) = {
    childStage.setX(stage.getX + stage.getWidth / 2 - childStage.getWidth / 2)
    childStage.setY(stage.getY + stage.getHeight / 2 - childStage.getHeight / 2)
  }

  /**
    * Initialize the notification stage and assign it to the handler class.
    *
    * @return
    */
  private def initNotifs: NotificationsController = {
    // get fxml/controller
    val notifFXML = new FXMLLoader(getClass.getResource("/gui/main/notifications.fxml"))
    val notifsController = new NotificationsController
    notifFXML.setController(notifsController)
    val root = notifFXML.load[Parent]

    Platform.runLater(new Runnable() {
      override def run = {
        // create scene
        val popup = new Popup
        popup.setAutoFix(false)
        val margin = 10
        val width = 300
        popup.setWidth(margin + width)
        popup.getContent.add(root)
        // positioning the popup @ TOP RIGHT of screen
        val screenBounds = Screen.getPrimary.getVisualBounds
        popup.show(stage, screenBounds.getMaxX - (margin + width), screenBounds.getMinY + margin)
        handlers.initNotifications(notifsController)
      }
    })
    notifsController
  }
}
