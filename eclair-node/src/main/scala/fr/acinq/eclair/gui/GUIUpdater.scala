package fr.acinq.eclair.gui

import javafx.application.Platform
import javafx.event.{ActionEvent, EventHandler}
import javafx.fxml.FXMLLoader
import javafx.geometry.Orientation
import javafx.scene.control.Separator
import javafx.scene.layout.{GridPane, VBox}
import javafx.stage.Stage

import akka.actor.{Actor, ActorLogging, ActorRef}
import com.mxgraph.layout.mxCircleLayout
import com.mxgraph.swing.mxGraphComponent
import fr.acinq.bitcoin._
import fr.acinq.eclair.{Globals, Setup}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.gui.controllers.{ChannelPaneController, MainController}
import fr.acinq.eclair.router.{ChannelDesc, ChannelDiscovered}
import org.jgrapht.ext.JGraphXAdapter
import org.jgrapht.graph.{DefaultEdge, SimpleGraph}


/**
  * Created by PM on 16/08/2016.
  */
class GUIUpdater(primaryStage: Stage, mainController:MainController, setup: Setup) extends Actor with ActorLogging {

  class NamedEdge(val id: BinaryData) extends DefaultEdge {
    override def toString: String = s"${id.toString.take(8)}..."
  }
  val graph = new SimpleGraph[BinaryData, NamedEdge](classOf[NamedEdge])
  graph.addVertex(Globals.Node.publicKey)

  def receive: Receive = main(Map())

  def main(m: Map[ActorRef, ChannelPaneController]): Receive = {

    case ChannelCreated(channel, params, theirNodeId) =>
      log.info(s"new channel: $channel")

      val loader = new FXMLLoader(getClass.getResource("/gui/main/channelPane.fxml"))
      val channelPaneController = new ChannelPaneController(theirNodeId)
      loader.setController(channelPaneController)
      val root = loader.load[VBox]

      channelPaneController.nodeId.setText(s"$theirNodeId")
      channelPaneController.funder.setText(params.anchorAmount.map(_ => "Yes").getOrElse("No"))
      channelPaneController.close.setOnAction(new EventHandler[ActionEvent] {
        override def handle(event: ActionEvent): Unit = channel ! CMD_CLOSE(None)
      })

      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          mainController.channelBox.getChildren.addAll(root)
        }
      })
      context.become(main(m + (channel -> channelPaneController)))

    case ChannelIdAssigned(channel, channelId, capacity) =>
      val channelPane = m(channel)
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          channelPane.channelIdValue = s"$channelId"
          channelPane.channelId.setText(s"$channelId")
          channelPane.capacity.setText(s"${satoshi2millibtc(capacity).amount}")
          channelPane.funder.getText match {
            case "Yes" => channelPane.amountUs.setText(s"${satoshi2millibtc(capacity).amount}")
            case "No" => channelPane.amountUs.setText("0")
          }
        }
      })

    case ChannelChangedState(channel, _, previousState, currentState, currentData) =>
      val channelPane = m(channel)
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          channelPane.state.setText(currentState.toString)
        }
      })

    case ChannelSignatureReceived(channel, commitments) =>
      val channelPane = m(channel)
      val bal = commitments.ourCommit.spec.amount_us_msat.toDouble / (commitments.ourCommit.spec.amount_us_msat.toDouble + commitments.ourCommit.spec.amount_them_msat.toDouble)
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          channelPane.amountUs.setText(s"${satoshi2millibtc(Satoshi(commitments.ourCommit.spec.amount_us_msat / 1000L)).amount}")
          channelPane.balanceBar.setProgress(bal)
        }
      })

    case ChannelDiscovered(ChannelDesc(id, a, b)) =>
      graph.addVertex(BinaryData(a))
      graph.addVertex(BinaryData(b))
      graph.addEdge(a, b, new NamedEdge(id))
      val jgxAdapter = new JGraphXAdapter(graph)
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          val component = new mxGraphComponent(jgxAdapter)
          component.setDragEnabled(false)
          val lay = new mxCircleLayout(jgxAdapter)
          lay.execute(jgxAdapter.getDefaultParent())
          mainController.swingNode.setContent(component)
        }
      })

  }
}
