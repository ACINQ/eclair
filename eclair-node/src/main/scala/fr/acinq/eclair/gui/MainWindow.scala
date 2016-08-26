package fr.acinq.eclair.gui

import javafx.application.{Application, Platform}
import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.embed.swing.SwingNode
import javafx.event.{ActionEvent, Event, EventHandler}
import javafx.geometry.{Insets, Orientation, Pos}
import javafx.scene.Scene
import javafx.scene.control.TabPane.TabClosingPolicy
import javafx.scene.control._
import javafx.scene.layout.{BorderPane, HBox, VBox}
import javafx.stage.{Stage, WindowEvent}

import akka.actor.Props
import com.mxgraph.swing.mxGraphComponent
import fr.acinq.eclair.{Globals, Setup}
import fr.acinq.eclair.channel.ChannelEvent
import fr.acinq.eclair.router.NetworkEvent

import scala.concurrent.Await
import scala.concurrent.duration.Duration


/**
  * Created by PM on 16/08/2016.
  */
class MainWindow extends Application {

  val root = new BorderPane()

  val menuBar = new MenuBar()
  val menuChannels = new Menu("Channels")
  val itemConnect = new MenuItem("Open channel")
  val itemSend = new MenuItem("Pay")
  val itemReceive = new MenuItem("Receive payment")

  menuChannels.getItems.addAll(itemConnect, new SeparatorMenuItem(), itemSend, itemReceive)
  menuBar.getMenus().addAll(menuChannels)
  root.setTop(menuBar)

  val tabChannels = new Tab("Channels")
  val vBoxPane = new VBox()
  vBoxPane.setSpacing(4)
  vBoxPane.setPadding(new Insets(8, 4, 4, 8))
  tabChannels.setContent(vBoxPane)

  val tabGraph = new Tab("Graph")
  val swingNode = new SwingNode()
  tabGraph.setContent(swingNode)

  val paneTab = new TabPane()
  paneTab.getTabs.addAll(tabChannels, tabGraph)
  paneTab.setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE)
  root.setCenter(paneTab)

  val scene = new Scene(root, 1200, 250)

  override def start(primaryStage: Stage): Unit = {
    val dialogSplash = new DialogSplash(primaryStage)
    dialogSplash.show

    val _this = this

    new Thread(new Runnable {
      override def run(): Unit = {
        val setup = new Setup
        val guiUpdater = setup.system.actorOf(Props(classOf[GUIUpdater], primaryStage, _this, setup), "gui-updater")
        setup.system.eventStream.subscribe(guiUpdater, classOf[ChannelEvent])
        setup.system.eventStream.subscribe(guiUpdater, classOf[NetworkEvent])
        val handlers = new Handlers(setup)
        Platform.runLater(new Runnable {
          override def run(): Unit = {
            primaryStage.setTitle("Eclair")
            val hBoxPane = new HBox()
            hBoxPane.setSpacing(4)
            hBoxPane.setPadding(new Insets(0, 4, 0, 4))
            hBoxPane.setAlignment(Pos.CENTER_RIGHT)
            val labelNodeId = new Label(s"Node Id: ${Globals.Node.id}")
            val separator1 = new Separator(Orientation.VERTICAL)
            val labelApi = new Label(s"Listening on HTTP ${setup.config.getInt("eclair.api.port")}")
            val separator2 = new Separator(Orientation.VERTICAL)
            val labelServer = new Label(s"Listening on TCP ${setup.config.getInt("eclair.server.port")}")
            val separator3 = new Separator(Orientation.VERTICAL)
            val labelBitcoin = new Label(s"Connected to bitcoin-core ${setup.bitcoinVersion} (${setup.chain})")
            hBoxPane.getChildren.addAll(labelNodeId, separator1, labelApi, separator2, labelServer, separator3, labelBitcoin)
            root.setBottom(hBoxPane)
            itemConnect.setOnAction(new EventHandler[ActionEvent] {
              override def handle(event: ActionEvent): Unit = new DialogOpen(primaryStage, handlers).show()
            })
            itemSend.setOnAction(new EventHandler[ActionEvent] {
              override def handle(event: ActionEvent): Unit = new DialogSend(primaryStage, handlers).show()
            })
            itemReceive.setOnAction(new EventHandler[ActionEvent] {
              override def handle(event: ActionEvent): Unit = new DialogReceive(primaryStage, handlers).show()
            })

            def refreshGraph: Unit = {
              Option(swingNode.getContent) match {
                case Some(component: mxGraphComponent) =>
                  component.doLayout()
                  component.repaint()
                  component.refresh()
                case _ => {}
              }
            }

            tabGraph.setOnSelectionChanged(new EventHandler[Event] {
              override def handle(event: Event): Unit = {
                if (event.getTarget == tabGraph) {
                  refreshGraph
                }
              }
            })
            primaryStage.widthProperty().addListener(new ChangeListener[Number] {
              override def changed(observable: ObservableValue[_ <: Number], oldValue: Number, newValue: Number): Unit = refreshGraph
            })
            primaryStage.heightProperty().addListener(new ChangeListener[Number] {
              override def changed(observable: ObservableValue[_ <: Number], oldValue: Number, newValue: Number): Unit = refreshGraph
            })
            primaryStage.setOnCloseRequest(new EventHandler[WindowEvent] {
              override def handle(event: WindowEvent): Unit = {
                System.exit(0)
              }
            })

            primaryStage.setScene(scene)
            primaryStage.show()
            dialogSplash.hide()
          }
        })
      }
    }).start()
  }

}