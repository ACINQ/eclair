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
import javafx.scene.image.{Image, ImageView}
import javafx.scene.input.ContextMenuEvent
import javafx.scene.layout.{AnchorPane, HBox, StackPane, VBox}
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.text.Text
import javafx.stage.FileChooser.ExtensionFilter
import javafx.stage._
import javafx.util.{Callback, Duration}

import com.google.common.net.HostAndPort
import fr.acinq.eclair.NodeParams.{BITCOIND, BITCOINJ, ELECTRUM}
import fr.acinq.eclair.Setup
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.stages._
import fr.acinq.eclair.gui.utils.{ContextMenuUtils, CopyAction}
import fr.acinq.eclair.payment.{PaymentEvent, PaymentReceived, PaymentRelayed, PaymentSent}
import fr.acinq.eclair.wire.{ChannelAnnouncement, NodeAnnouncement}
import grizzled.slf4j.Logging

case class ChannelInfo(announcement: ChannelAnnouncement, var isNode1Enabled: Option[Boolean], var isNode2Enabled: Option[Boolean])

sealed trait Record {
  val event: PaymentEvent
  val date: LocalDateTime
}

case class PaymentSentRecord(event: PaymentSent, date: LocalDateTime) extends Record

case class PaymentReceivedRecord(event: PaymentReceived, date: LocalDateTime) extends Record

case class PaymentRelayedRecord(event: PaymentRelayed, date: LocalDateTime) extends Record

/**
  * Created by DPA on 22/09/2016.
  */
class MainController(val handlers: Handlers, val hostServices: HostServices) extends Logging {

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
  @FXML var bitcoinWallet: Label = _
  @FXML var bitcoinChain: Label = _

  // channels tab elements
  @FXML var channelInfo: VBox = _
  @FXML var channelBox: VBox = _
  @FXML var channelsTab: Tab = _

  // all nodes tab
  val networkNodesList = FXCollections.observableArrayList[NodeAnnouncement]()
  @FXML var networkNodesTab: Tab = _
  @FXML var networkNodesTable: TableView[NodeAnnouncement] = _
  @FXML var networkNodesIdColumn: TableColumn[NodeAnnouncement, String] = _
  @FXML var networkNodesAliasColumn: TableColumn[NodeAnnouncement, String] = _
  @FXML var networkNodesRGBColumn: TableColumn[NodeAnnouncement, String] = _
  @FXML var networkNodesIPColumn: TableColumn[NodeAnnouncement, String] = _

  // all channels
  val networkChannelsList = FXCollections.observableArrayList[ChannelInfo]()
  @FXML var networkChannelsTab: Tab = _
  @FXML var networkChannelsTable: TableView[ChannelInfo] = _
  @FXML var networkChannelsIdColumn: TableColumn[ChannelInfo, String] = _
  @FXML var networkChannelsNode1Column: TableColumn[ChannelInfo, String] = _
  @FXML var networkChannelsDirectionsColumn: TableColumn[ChannelInfo, ChannelInfo] = _
  @FXML var networkChannelsNode2Column: TableColumn[ChannelInfo, String] = _

  // payment sent table
  val paymentSentList = FXCollections.observableArrayList[PaymentSentRecord]()
  @FXML var paymentSentTab: Tab = _
  @FXML var paymentSentTable: TableView[PaymentSentRecord] = _
  @FXML var paymentSentAmountColumn: TableColumn[PaymentSentRecord, Number] = _
  @FXML var paymentSentFeesColumn: TableColumn[PaymentSentRecord, Number] = _
  @FXML var paymentSentHashColumn: TableColumn[PaymentSentRecord, String] = _
  @FXML var paymentSentDateColumn: TableColumn[PaymentSentRecord, String] = _

  // payment received table
  val paymentReceivedList = FXCollections.observableArrayList[PaymentReceivedRecord]()
  @FXML var paymentReceivedTab: Tab = _
  @FXML var paymentReceivedTable: TableView[PaymentReceivedRecord] = _
  @FXML var paymentReceivedAmountColumn: TableColumn[PaymentReceivedRecord, Number] = _
  @FXML var paymentReceivedHashColumn: TableColumn[PaymentReceivedRecord, String] = _
  @FXML var paymentReceivedDateColumn: TableColumn[PaymentReceivedRecord, String] = _

  // payment relayed table
  val paymentRelayedList = FXCollections.observableArrayList[PaymentRelayedRecord]()
  @FXML var paymentRelayedTab: Tab = _
  @FXML var paymentRelayedTable: TableView[PaymentRelayedRecord] = _
  @FXML var paymentRelayedAmountColumn: TableColumn[PaymentRelayedRecord, Number] = _
  @FXML var paymentRelayedFeesColumn: TableColumn[PaymentRelayedRecord, Number] = _
  @FXML var paymentRelayedHashColumn: TableColumn[PaymentRelayedRecord, String] = _
  @FXML var paymentRelayedDateColumn: TableColumn[PaymentRelayedRecord, String] = _

  @FXML var blocker: StackPane = _
  @FXML var blockerDialog: HBox = _
  @FXML var blockerDialogTitleEngineName: Text = _

  val PAYMENT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  val moneyFormatter: NumberFormat = NumberFormat.getInstance(Locale.getDefault)

  /**
    * Initialize the main window.
    *
    * - Set content in status bar labels (node id, host, ...)
    * - init the channels tab with a 'No channels found' message
    * - init the 'nodes in network' and 'channels in network' tables
    */
  @FXML def initialize(): Unit = {

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
      def call(pn: CellDataFeatures[NodeAnnouncement, String]) = new SimpleStringProperty(pn.getValue.rgbColor.toString)
    })
    networkNodesIPColumn.setCellValueFactory(new Callback[CellDataFeatures[NodeAnnouncement, String], ObservableValue[String]]() {
      def call(pn: CellDataFeatures[NodeAnnouncement, String]) = {
        val address = pn.getValue.addresses.map(a => HostAndPort.fromParts(a.getHostString, a.getPort)).mkString(",")
        new SimpleStringProperty(address)
      }
    })
    networkNodesRGBColumn.setCellFactory(new Callback[TableColumn[NodeAnnouncement, String], TableCell[NodeAnnouncement, String]]() {
      def call(pn: TableColumn[NodeAnnouncement, String]) = {
        new TableCell[NodeAnnouncement, String]() {
          override def updateItem(item: String, empty: Boolean): Unit = {
            super.updateItem(item, empty)
            if (empty || item == null) {
              setText(null)
              setGraphic(null)
              setStyle(null)
            } else {
              setStyle("-fx-background-color:" + item)
            }
          }
        }
      }
    })
    networkNodesTable.setRowFactory(new Callback[TableView[NodeAnnouncement], TableRow[NodeAnnouncement]]() {
      override def call(table: TableView[NodeAnnouncement]) = setupPeerNodeContextMenu()
    })

    // init all channels
    networkChannelsTable.setItems(networkChannelsList)
    networkChannelsList.addListener(new ListChangeListener[ChannelInfo] {
      override def onChanged(c: Change[_ <: ChannelInfo]) = updateTabHeader(networkChannelsTab, "All Channels", networkChannelsList)
    })
    networkChannelsIdColumn.setCellValueFactory(new Callback[CellDataFeatures[ChannelInfo, String], ObservableValue[String]]() {
      def call(pc: CellDataFeatures[ChannelInfo, String]) = new SimpleStringProperty(pc.getValue.announcement.shortChannelId.toHexString)
    })
    networkChannelsNode1Column.setCellValueFactory(new Callback[CellDataFeatures[ChannelInfo, String], ObservableValue[String]]() {
      def call(pc: CellDataFeatures[ChannelInfo, String]) = new SimpleStringProperty(pc.getValue.announcement.nodeId1.toString)
    })
    networkChannelsNode2Column.setCellValueFactory(new Callback[CellDataFeatures[ChannelInfo, String], ObservableValue[String]]() {
      def call(pc: CellDataFeatures[ChannelInfo, String]) = new SimpleStringProperty(pc.getValue.announcement.nodeId2.toString)
    })
    networkChannelsTable.setRowFactory(new Callback[TableView[ChannelInfo], TableRow[ChannelInfo]]() {
      override def call(table: TableView[ChannelInfo]): TableRow[ChannelInfo] = setupPeerChannelContextMenu()
    })
    networkChannelsDirectionsColumn.setCellValueFactory(new Callback[CellDataFeatures[ChannelInfo, ChannelInfo], ObservableValue[ChannelInfo]]() {
      def call(pc: CellDataFeatures[ChannelInfo, ChannelInfo]) = new SimpleObjectProperty[ChannelInfo](pc.getValue)
    })
    networkChannelsDirectionsColumn.setCellFactory(new Callback[TableColumn[ChannelInfo, ChannelInfo], TableCell[ChannelInfo, ChannelInfo]]() {
      def call(pn: TableColumn[ChannelInfo, ChannelInfo]) = {
        new TableCell[ChannelInfo, ChannelInfo]() {
          val directionImage = new ImageView
          directionImage.setFitWidth(20)
          directionImage.setFitHeight(20)

          override def updateItem(item: ChannelInfo, empty: Boolean): Unit = {
            super.updateItem(item, empty)
            if (item == null || empty) {
              setGraphic(null)
              setText(null)
            } else {
              item match {
                case ChannelInfo(_, Some(true), Some(true)) =>
                  directionImage.setImage(new Image("/gui/commons/images/in-out-11.png", false))
                  setTooltip(new Tooltip("Both Node 1 and Node 2 are enabled"))
                  setGraphic(directionImage)
                case ChannelInfo(_, Some(true), Some(false)) =>
                  directionImage.setImage(new Image("/gui/commons/images/in-out-10.png", false))
                  setTooltip(new Tooltip("Node 1 is enabled, but not Node 2"))
                  setGraphic(directionImage)
                case ChannelInfo(_, Some(false), Some(true)) =>
                  directionImage.setImage(new Image("/gui/commons/images/in-out-01.png", false))
                  setTooltip(new Tooltip("Node 2 is enabled, but not Node 1"))
                  setGraphic(directionImage)
                case ChannelInfo(_, Some(false), Some(false)) =>
                  directionImage.setImage(new Image("/gui/commons/images/in-out-00.png", false))
                  setTooltip(new Tooltip("Neither Node 1 nor Node 2 is enabled"))
                  setGraphic(directionImage)
                case _ =>
                  setText("?")
                  setGraphic(null)
              }
            }
          }
        }
      }
    })

    // init payment sent
    paymentSentTable.setItems(paymentSentList)
    paymentSentList.addListener(new ListChangeListener[PaymentSentRecord] {
      override def onChanged(c: Change[_ <: PaymentSentRecord]) = updateTabHeader(paymentSentTab, "Sent", paymentSentList)
    })
    paymentSentAmountColumn.setCellValueFactory(new Callback[CellDataFeatures[PaymentSentRecord, Number], ObservableValue[Number]]() {
      def call(record: CellDataFeatures[PaymentSentRecord, Number]) = new SimpleLongProperty(record.getValue.event.amount.amount)
    })
    paymentSentAmountColumn.setCellFactory(new Callback[TableColumn[PaymentSentRecord, Number], TableCell[PaymentSentRecord, Number]]() {
      def call(record: TableColumn[PaymentSentRecord, Number]) = buildMoneyTableCell
    })
    paymentSentFeesColumn.setCellValueFactory(new Callback[CellDataFeatures[PaymentSentRecord, Number], ObservableValue[Number]]() {
      def call(record: CellDataFeatures[PaymentSentRecord, Number]) = new SimpleLongProperty(record.getValue.event.feesPaid.amount)
    })
    paymentSentFeesColumn.setCellFactory(new Callback[TableColumn[PaymentSentRecord, Number], TableCell[PaymentSentRecord, Number]]() {
      def call(record: TableColumn[PaymentSentRecord, Number]) = buildMoneyTableCell
    })
    paymentSentHashColumn.setCellValueFactory(paymentHashCellValueFactory)
    paymentSentDateColumn.setCellValueFactory(paymentDateCellValueFactory)
    paymentSentTable.setRowFactory(paymentRowFactory)

    // init payment received
    paymentReceivedTable.setItems(paymentReceivedList)
    paymentReceivedList.addListener(new ListChangeListener[PaymentReceivedRecord] {
      override def onChanged(c: Change[_ <: PaymentReceivedRecord]) = updateTabHeader(paymentReceivedTab, "Received", paymentReceivedList)
    })
    paymentReceivedAmountColumn.setCellValueFactory(new Callback[CellDataFeatures[PaymentReceivedRecord, Number], ObservableValue[Number]]() {
      def call(p: CellDataFeatures[PaymentReceivedRecord, Number]) = new SimpleLongProperty(p.getValue.event.amount.amount)
    })
    paymentReceivedAmountColumn.setCellFactory(new Callback[TableColumn[PaymentReceivedRecord, Number], TableCell[PaymentReceivedRecord, Number]]() {
      def call(pn: TableColumn[PaymentReceivedRecord, Number]) = buildMoneyTableCell
    })
    paymentReceivedHashColumn.setCellValueFactory(paymentHashCellValueFactory)
    paymentReceivedDateColumn.setCellValueFactory(paymentDateCellValueFactory)
    paymentReceivedTable.setRowFactory(paymentRowFactory)

    // init payment relayed
    paymentRelayedTable.setItems(paymentRelayedList)
    paymentRelayedList.addListener(new ListChangeListener[PaymentRelayedRecord] {
      override def onChanged(c: Change[_ <: PaymentRelayedRecord]) = updateTabHeader(paymentRelayedTab, "Relayed", paymentRelayedList)
    })
    paymentRelayedAmountColumn.setCellValueFactory(new Callback[CellDataFeatures[PaymentRelayedRecord, Number], ObservableValue[Number]]() {
      def call(p: CellDataFeatures[PaymentRelayedRecord, Number]) = new SimpleLongProperty(p.getValue.event.amountIn.amount)
    })
    paymentRelayedAmountColumn.setCellFactory(new Callback[TableColumn[PaymentRelayedRecord, Number], TableCell[PaymentRelayedRecord, Number]]() {
      def call(pn: TableColumn[PaymentRelayedRecord, Number]) = buildMoneyTableCell
    })
    paymentRelayedFeesColumn.setCellValueFactory(new Callback[CellDataFeatures[PaymentRelayedRecord, Number], ObservableValue[Number]]() {
      def call(p: CellDataFeatures[PaymentRelayedRecord, Number]) = new SimpleLongProperty(p.getValue.event.amountIn.amount - p.getValue.event.amountOut.amount)
    })
    paymentRelayedFeesColumn.setCellFactory(new Callback[TableColumn[PaymentRelayedRecord, Number], TableCell[PaymentRelayedRecord, Number]]() {
      def call(pn: TableColumn[PaymentRelayedRecord, Number]) = buildMoneyTableCell
    })
    paymentRelayedHashColumn.setCellValueFactory(paymentHashCellValueFactory)
    paymentRelayedDateColumn.setCellValueFactory(paymentDateCellValueFactory)
    paymentRelayedTable.setRowFactory(paymentRowFactory)
  }

  def initInfoFields(setup: Setup) = {
    // init status bar
    labelNodeId.setText(s"${setup.nodeParams.privateKey.publicKey}")
    labelAlias.setText(s"${setup.nodeParams.alias}")
    rectRGB.setFill(Color.web(setup.nodeParams.color.toString))
    labelApi.setText(s"${setup.config.getInt("api.port")}")
    labelServer.setText(s"${setup.config.getInt("server.port")}")

    val wallet = setup.nodeParams.watcherType match {
      case BITCOIND => "Bitcoin-core"
      case ELECTRUM => "Electrum"
      case BITCOINJ => "BitcoinJ"
    }
    bitcoinWallet.setText(wallet)
    bitcoinChain.setText(s"${setup.chain.toUpperCase()}")
    bitcoinChain.getStyleClass.add(setup.chain)

    val nodeURI_opt = setup.nodeParams.publicAddresses.headOption.map(address => {
      s"${setup.nodeParams.privateKey.publicKey}@${HostAndPort.fromParts(address.getHostString, address.getPort)}"
    })

    contextMenu = ContextMenuUtils.buildCopyContext(List(CopyAction("Copy Pubkey", setup.nodeParams.privateKey.publicKey.toString())))
    nodeURI_opt.map(nodeURI => {
      val nodeInfoAction = new MenuItem("Node Info")
      nodeInfoAction.setOnAction(new EventHandler[ActionEvent] {
        override def handle(event: ActionEvent): Unit = {
          val nodeInfoStage = new NodeInfoStage(nodeURI, handlers)
          nodeInfoStage.initOwner(getWindow.orNull)
          positionAtCenter(nodeInfoStage)
          nodeInfoStage.show()
        }
      })
      contextMenu.getItems.add(nodeInfoAction)
      contextMenu.getItems.add(ContextMenuUtils.buildCopyMenuItem(CopyAction("Copy URI", nodeURI)))
    })
  }

  private def updateTabHeader(tab: Tab, prefix: String, items: ObservableList[_]) = Platform.runLater(new Runnable() {
      override def run(): Unit = tab.setText(s"$prefix (${items.size})")
    })

  private def paymentHashCellValueFactory[T <: Record] = new Callback[CellDataFeatures[T, String], ObservableValue[String]]() {
    def call(p: CellDataFeatures[T, String]) = new SimpleStringProperty(p.getValue.event.paymentHash.toString)
  }

  private def buildMoneyTableCell[T <: Record] = new TableCell[T, Number]() {
    override def updateItem(item: Number, empty: Boolean): Unit = {
      super.updateItem(item, empty)
      if (empty || item == null) {
        setText(null)
        setGraphic(null)
      } else {
        setText(moneyFormatter.format(item))
      }
    }
  }

  private def paymentDateCellValueFactory[T <: Record] = new Callback[CellDataFeatures[T, String], ObservableValue[String]]() {
    def call(p: CellDataFeatures[T, String]) = new SimpleStringProperty(p.getValue.date.format(PAYMENT_DATE_FORMAT))
  }

  private def paymentRowFactory[T <: Record] = new Callback[TableView[T], TableRow[T]]() {
    override def call(table: TableView[T]): TableRow[T] = {
      val row = new TableRow[T]
      val rowContextMenu = new ContextMenu
      val copyHash = new MenuItem("Copy Payment Hash")
      copyHash.setOnAction(new EventHandler[ActionEvent] {
        override def handle(event: ActionEvent): Unit = Option(row.getItem) match {
          case Some(p) => ContextMenuUtils.copyToClipboard(p.event.paymentHash.toString)
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
      override def handle(event: ActionEvent): Unit = Option(row.getItem) match {
        case Some(pn) => ContextMenuUtils.copyToClipboard(pn.nodeId.toString)
        case None =>
      }
    })
    val copyURI = new MenuItem("Copy first known URI")
    copyURI.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent): Unit = Option(row.getItem) match {
        case Some(pn) => ContextMenuUtils.copyToClipboard(
          if (pn.addresses.nonEmpty) s"${pn.nodeId.toString}@${HostAndPort.fromParts(pn.addresses.head.getHostString, pn.addresses.head.getPort)}"
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
  private def setupPeerChannelContextMenu(): TableRow[ChannelInfo] = {
    val row = new TableRow[ChannelInfo]
    val rowContextMenu = new ContextMenu
    val copyChannelId = new MenuItem("Copy Channel Id")
    copyChannelId.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent): Unit = Option(row.getItem) match {
        case Some(pc) => ContextMenuUtils.copyToClipboard(pc.announcement.shortChannelId.toHexString)
        case None =>
      }
    })
    val copyNode1 = new MenuItem("Copy Node 1")
    copyNode1.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent): Unit = Option(row.getItem) match {
        case Some(pc) => ContextMenuUtils.copyToClipboard(pc.announcement.nodeId1.toString)
        case None =>
      }
    })
    val copyNode2 = new MenuItem("Copy Node 2")
    copyNode2.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent): Unit = Option(row.getItem) match {
        case Some(pc) => ContextMenuUtils.copyToClipboard(pc.announcement.nodeId2.toString)
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
    val file = fileChooser.showSaveDialog(getWindow.orNull)
    if (file != null) handlers.exportToDot(file)
  }

  @FXML def handleOpenChannel = {
    val openChannelStage = new OpenChannelStage(handlers)
    openChannelStage.initOwner(getWindow.orNull)
    positionAtCenter(openChannelStage)
    openChannelStage.show()
  }

  @FXML def handleSendPayment = {
    val sendPaymentStage = new SendPaymentStage(handlers)
    sendPaymentStage.initOwner(getWindow.orNull)
    positionAtCenter(sendPaymentStage)
    sendPaymentStage.show()
  }

  @FXML def handleReceivePayment = {
    val receiveStage = new ReceivePaymentStage(handlers)
    receiveStage.initOwner(getWindow.orNull)
    positionAtCenter(receiveStage)
    receiveStage.show()
  }

  def showBlockerModal(backendName: String) = {
    blockerDialogTitleEngineName.setText(backendName)
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
    ftCover.play()
    val t = new ParallelTransition(blockerDialog, fadeTransition, translateTransition)
    t.setDelay(Duration.millis(200))
    t.play()
  }

  def hideBlockerModal = {
    val ftCover = new FadeTransition(Duration.millis(400))
    ftCover.setFromValue(1)
    ftCover.setToValue(0)
    val s = new SequentialTransition(blocker, ftCover)
    s.setOnFinished(new EventHandler[ActionEvent]() {
      override def handle(event: ActionEvent): Unit = blocker.setVisible(false)
    })
    s.play()
  }

  private def getWindow: Option[Window] = {
    Option(root).map(_.getScene.getWindow)
  }

  @FXML def handleCloseRequest = getWindow.map(_.fireEvent(new WindowEvent(getWindow.get, WindowEvent.WINDOW_CLOSE_REQUEST)))

  @FXML def handleOpenAbout(): Unit = {
    val aboutStage = new AboutStage(hostServices)
    aboutStage.initOwner(getWindow.getOrElse(null))
    positionAtCenter(aboutStage)
    aboutStage.show()
  }

  @FXML def openNodeIdContext(event: ContextMenuEvent) = contextMenu.show(labelNodeId, event.getScreenX, event.getScreenY)

  def positionAtCenter(childStage: Stage): Unit = {
    childStage.setX(getWindow.map(w => w.getX + w.getWidth / 2 - childStage.getWidth / 2).getOrElse(0))
    childStage.setY(getWindow.map(w => w.getY + w.getHeight / 2 - childStage.getHeight / 2).getOrElse(0))
  }
}
