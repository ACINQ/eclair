package fr.acinq.eclair.gui.controllers

import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javafx.animation.{FadeTransition, ParallelTransition, SequentialTransition, TranslateTransition}
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
import javafx.scene.layout.{AnchorPane, HBox, StackPane, VBox}
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.stage.FileChooser.ExtensionFilter
import javafx.stage._
import javafx.util.{Callback, Duration}

import fr.acinq.eclair.Setup
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.stages._
import fr.acinq.eclair.gui.utils.{ContextMenuUtils, CopyAction}
import fr.acinq.eclair.payment.{PaymentEvent, PaymentReceived, PaymentRelayed, PaymentSent}
import fr.acinq.eclair.wire.{ChannelAnnouncement, NodeAnnouncement}
import grizzled.slf4j.Logging

/**
  * Created by DPA on 22/09/2016.
  */
class MainController(val handlers: Handlers, val setup: Setup, val hostServices: HostServices) extends Logging {

  @FXML var root: AnchorPane = _
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
  val networkNodesList: ObservableList[NodeAnnouncement] = FXCollections.observableArrayList[NodeAnnouncement]()
  @FXML var networkNodesTab: Tab = _
  @FXML var networkNodesTable: TableView[NodeAnnouncement] = _
  @FXML var networkNodesIdColumn: TableColumn[NodeAnnouncement, String] = _
  @FXML var networkNodesAliasColumn: TableColumn[NodeAnnouncement, String] = _
  @FXML var networkNodesRGBColumn: TableColumn[NodeAnnouncement, String] = _
  @FXML var networkNodesIPColumn: TableColumn[NodeAnnouncement, String] = _

  // all channels
  val networkChannelsList: ObservableList[ChannelAnnouncement] = FXCollections.observableArrayList[ChannelAnnouncement]()
  @FXML var networkChannelsTab: Tab = _
  @FXML var networkChannelsTable: TableView[ChannelAnnouncement] = _
  @FXML var networkChannelsIdColumn: TableColumn[ChannelAnnouncement, Number] = _
  @FXML var networkChannelsNode1Column: TableColumn[ChannelAnnouncement, String] = _
  @FXML var networkChannelsNode2Column: TableColumn[ChannelAnnouncement, String] = _

  // payment sent table
  val paymentSentList = FXCollections.observableArrayList[PaymentSent]()
  @FXML var paymentSentTab: Tab = _
  @FXML var paymentSentTable: TableView[PaymentSent] = _
  @FXML var paymentSentAmountColumn: TableColumn[PaymentSent, Number] = _
  @FXML var paymentSentFeesColumn: TableColumn[PaymentSent, Number] = _
  @FXML var paymentSentHashColumn: TableColumn[PaymentSent, String] = _
  @FXML var paymentSentDateColumn: TableColumn[PaymentSent, String] = _

  // payment received table
  val paymentReceivedList = FXCollections.observableArrayList[PaymentReceived]()
  @FXML var paymentReceivedTab: Tab = _
  @FXML var paymentReceivedTable: TableView[PaymentReceived] = _
  @FXML var paymentReceivedAmountColumn: TableColumn[PaymentReceived, Number] = _
  @FXML var paymentReceivedHashColumn: TableColumn[PaymentReceived, String] = _
  @FXML var paymentReceivedDateColumn: TableColumn[PaymentReceived, String] = _

  // payment relayed table
  val paymentRelayedList = FXCollections.observableArrayList[PaymentRelayed]()
  @FXML var paymentRelayedTab: Tab = _
  @FXML var paymentRelayedTable: TableView[PaymentRelayed] = _
  @FXML var paymentRelayedAmountColumn: TableColumn[PaymentRelayed, Number] = _
  @FXML var paymentRelayedFeesColumn: TableColumn[PaymentRelayed, Number] = _
  @FXML var paymentRelayedHashColumn: TableColumn[PaymentRelayed, String] = _
  @FXML var paymentRelayedDateColumn: TableColumn[PaymentRelayed, String] = _

  @FXML var blocker: StackPane = _
  @FXML var blockerDialog: HBox = _

  val PAYMENT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  val moneyFormatter = NumberFormat.getInstance(Locale.getDefault)

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
      new CopyAction("Copy URI", s"${setup.nodeParams.privateKey.publicKey}@${setup.nodeParams.address.getHostString}:${setup.nodeParams.address.getPort}")))

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
      override def onChanged(c: Change[_ <: NodeAnnouncement]) = updateTabHeader(networkNodesTab, "All Nodes", networkNodesList)
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
        new TableCell[NodeAnnouncement, String]() {
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
      override def onChanged(c: Change[_ <: ChannelAnnouncement]) = updateTabHeader(networkChannelsTab, "All Channels", networkChannelsList)
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

    // init payment sent
    paymentSentTable.setItems(paymentSentList)
    paymentSentList.addListener(new ListChangeListener[PaymentSent] {
      override def onChanged(c: Change[_ <: PaymentSent]) = updateTabHeader(paymentSentTab, "Sent", paymentSentList)
    })
    paymentSentAmountColumn.setCellValueFactory(new Callback[CellDataFeatures[PaymentSent, Number], ObservableValue[Number]]() {
      def call(p: CellDataFeatures[PaymentSent, Number]) = new SimpleLongProperty(p.getValue.amount.amount)
    })
    paymentSentAmountColumn.setCellFactory(new Callback[TableColumn[PaymentSent, Number], TableCell[PaymentSent, Number]]() {
      def call(pn: TableColumn[PaymentSent, Number]) = buildMoneyTableCell
    })
    paymentSentFeesColumn.setCellValueFactory(new Callback[CellDataFeatures[PaymentSent, Number], ObservableValue[Number]]() {
      def call(p: CellDataFeatures[PaymentSent, Number]) = new SimpleLongProperty(p.getValue.feesPaid.amount)
    })
    paymentSentFeesColumn.setCellFactory(new Callback[TableColumn[PaymentSent, Number], TableCell[PaymentSent, Number]]() {
      def call(pn: TableColumn[PaymentSent, Number]) = buildMoneyTableCell
    })
    paymentSentHashColumn.setCellValueFactory(paymentHashCellValueFactory)
    paymentSentDateColumn.setCellValueFactory(paymentDateCellValueFactory)
    paymentSentTable.setRowFactory(paymentRowFactory)

    // init payment received
    paymentReceivedTable.setItems(paymentReceivedList)
    paymentReceivedList.addListener(new ListChangeListener[PaymentReceived] {
      override def onChanged(c: Change[_ <: PaymentReceived]) = updateTabHeader(paymentReceivedTab, "Received", paymentReceivedList)
    })
    paymentReceivedAmountColumn.setCellValueFactory(new Callback[CellDataFeatures[PaymentReceived, Number], ObservableValue[Number]]() {
      def call(p: CellDataFeatures[PaymentReceived, Number]) = new SimpleLongProperty(p.getValue.amount.amount)
    })
    paymentReceivedAmountColumn.setCellFactory(new Callback[TableColumn[PaymentReceived, Number], TableCell[PaymentReceived, Number]]() {
      def call(pn: TableColumn[PaymentReceived, Number]) = buildMoneyTableCell
    })
    paymentReceivedHashColumn.setCellValueFactory(paymentHashCellValueFactory)
    paymentReceivedDateColumn.setCellValueFactory(paymentDateCellValueFactory)
    paymentReceivedTable.setRowFactory(paymentRowFactory)

    // init payment relayed
    paymentRelayedTable.setItems(paymentRelayedList)
    paymentRelayedList.addListener(new ListChangeListener[PaymentRelayed] {
      override def onChanged(c: Change[_ <: PaymentRelayed]) = updateTabHeader(paymentRelayedTab, "Relayed", paymentRelayedList)
    })
    paymentRelayedAmountColumn.setCellValueFactory(new Callback[CellDataFeatures[PaymentRelayed, Number], ObservableValue[Number]]() {
      def call(p: CellDataFeatures[PaymentRelayed, Number]) = new SimpleLongProperty(p.getValue.amount.amount)
    })
    paymentRelayedAmountColumn.setCellFactory(new Callback[TableColumn[PaymentRelayed, Number], TableCell[PaymentRelayed, Number]]() {
      def call(pn: TableColumn[PaymentRelayed, Number]) = buildMoneyTableCell
    })
    paymentRelayedFeesColumn.setCellValueFactory(new Callback[CellDataFeatures[PaymentRelayed, Number], ObservableValue[Number]]() {
      def call(p: CellDataFeatures[PaymentRelayed, Number]) = new SimpleLongProperty(p.getValue.feesEarned.amount)
    })
    paymentRelayedFeesColumn.setCellFactory(new Callback[TableColumn[PaymentRelayed, Number], TableCell[PaymentRelayed, Number]]() {
      def call(pn: TableColumn[PaymentRelayed, Number]) = buildMoneyTableCell
    })
    paymentRelayedHashColumn.setCellValueFactory(paymentHashCellValueFactory)
    paymentRelayedDateColumn.setCellValueFactory(paymentDateCellValueFactory)
    paymentRelayedTable.setRowFactory(paymentRowFactory)
  }

  private def updateTabHeader(tab: Tab, prefix: String, items: ObservableList[_]) = {
    Platform.runLater(new Runnable() {
      override def run = tab.setText(s"$prefix (${items.size})")
    })
  }

  private def paymentHashCellValueFactory[T <: PaymentEvent] = new Callback[CellDataFeatures[T, String], ObservableValue[String]]() {
    def call(p: CellDataFeatures[T, String]) = new SimpleStringProperty(p.getValue.paymentHash.toString)
  }

  private def buildMoneyTableCell[T <: PaymentEvent] = new TableCell[T, Number]() {
    override def updateItem(item: Number, empty: Boolean) = {
      super.updateItem(item, empty)
      if (item != null && !empty) setText(moneyFormatter.format(item))
    }
  }

  private def paymentDateCellValueFactory[T <: PaymentEvent] = new Callback[CellDataFeatures[T, String], ObservableValue[String]]() {
    def call(p: CellDataFeatures[T, String]) = new SimpleStringProperty(LocalDateTime.now.format(PAYMENT_DATE_FORMAT))
  }

  private def paymentRowFactory[T <: PaymentEvent] = new Callback[TableView[T], TableRow[T]]() {
    override def call(table: TableView[T]): TableRow[T] = {
      val row = new TableRow[T]
      val rowContextMenu = new ContextMenu
      val copyHash = new MenuItem("Copy Payment Hash")
      copyHash.setOnAction(new EventHandler[ActionEvent] {
        override def handle(event: ActionEvent) = Option(row.getItem) match {
          case Some(p) => ContextMenuUtils.copyToClipboard(p.paymentHash.toString)
          case None =>
        }
      })
      rowContextMenu.getItems.addAll(copyHash)
      row.setContextMenu(rowContextMenu)
      row
    }
  }

  /**
    * Create a row for a node with context actions (copy node uri and id).
    *
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
    *
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
    val file = fileChooser.showSaveDialog(getWindow.getOrElse(null))
    if (file != null) handlers.exportToDot(file)
  }

  @FXML def handleOpenChannel = {
    val openChannelStage = new OpenChannelStage(handlers, setup)
    openChannelStage.initOwner(getWindow.getOrElse(null))
    positionAtCenter(openChannelStage)
    openChannelStage.show
  }

  @FXML def handleSendPayment = {
    val sendPaymentStage = new SendPaymentStage(handlers, setup)
    sendPaymentStage.initOwner(getWindow.getOrElse(null))
    positionAtCenter(sendPaymentStage)
    sendPaymentStage.show
  }

  @FXML def handleReceivePayment = {
    val receiveStage = new ReceivePaymentStage(handlers, setup)
    receiveStage.initOwner(getWindow.getOrElse(null))
    positionAtCenter(receiveStage)
    receiveStage.show
  }

  def showBlockerModal = {
    val fadeTransition = new FadeTransition(Duration.millis(300))
    fadeTransition.setFromValue(0)
    fadeTransition.setToValue(1)
    val translateTransition = new TranslateTransition(Duration.millis(300))
    translateTransition.setFromY(20)
    translateTransition.setToY(0)
    blocker.setVisible(true)
    val ftCover = new FadeTransition(Duration.millis(200), blocker)
    ftCover.setFromValue(0)
    ftCover.setToValue(1)
    ftCover.play
    val t = new ParallelTransition(blockerDialog, fadeTransition, translateTransition)
    t.setDelay(Duration.millis(200))
    t.play
  }
  def hideBlockerModal = {
    val ftCover = new FadeTransition(Duration.millis(400))
    ftCover.setFromValue(1)
    ftCover.setToValue(0)
    val s = new SequentialTransition(blocker, ftCover)
    s.setOnFinished(new EventHandler[ActionEvent]() {
      override def handle(event: ActionEvent): Unit = blocker.setVisible(false)
    })
    s.play
  }

  private def getWindow: Option[Window] = {
    Option(root).map(_.getScene.getWindow)
  }

  @FXML def handleCloseRequest = getWindow.map(_.fireEvent(new WindowEvent(getWindow.get, WindowEvent.WINDOW_CLOSE_REQUEST)))

  @FXML def handleOpenAbout = {
    val aboutStage = new AboutStage(hostServices)
    aboutStage.initOwner(getWindow.getOrElse(null))
    positionAtCenter(aboutStage)
    aboutStage.show
  }

  @FXML def openNodeIdContext(event: ContextMenuEvent) = contextMenu.show(labelNodeId, event.getScreenX, event.getScreenY)

  def positionAtCenter(childStage: Stage) = {
    childStage.setX(getWindow.map(w => w.getX + w.getWidth / 2 - childStage.getWidth / 2).getOrElse(0))
    childStage.setY(getWindow.map(w => w.getY + w.getHeight / 2 - childStage.getHeight / 2).getOrElse(0))
  }
}
