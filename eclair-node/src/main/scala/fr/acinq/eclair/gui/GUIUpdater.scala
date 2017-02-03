package fr.acinq.eclair.gui

import java.util.function.Predicate
import javafx.application.Platform
import javafx.event.{ActionEvent, EventHandler}
import javafx.fxml.FXMLLoader
import javafx.scene.layout.VBox
import javafx.stage.Stage

import akka.actor.{Actor, ActorLogging, ActorRef}
import fr.acinq.bitcoin._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.gui.controllers.{ChannelPaneController, MainController, PeerChannel, PeerNode}
import fr.acinq.eclair.router.{ChannelDiscovered, ChannelLost, NodeDiscovered, NodeLost}
import fr.acinq.eclair.{Globals, Setup}
import org.jgrapht.graph.{DefaultEdge, SimpleGraph}


/**
  * Created by PM on 16/08/2016.
  */
class GUIUpdater(primaryStage: Stage, mainController: MainController, setup: Setup) extends Actor with ActorLogging {

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
      val channelPaneController = new ChannelPaneController(theirNodeId.toBin.toString())
      loader.setController(channelPaneController)
      val root = loader.load[VBox]

      channelPaneController.nodeId.setText(s"$theirNodeId")
      channelPaneController.funder.setText(if (params.isFunder) "Yes" else "No")
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

    case ChannelChangedState(channel, _, _, previousState, currentState, currentData) =>
      val channelPane = m(channel)
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          channelPane.state.setText(currentState.toString)
        }
      })

    case ChannelSignatureReceived(channel, commitments) =>
      val channelPane = m(channel)
      val bal = commitments.localCommit.spec.toLocalMsat.toDouble / (commitments.localCommit.spec.toLocalMsat.toDouble + commitments.localCommit.spec.toRemoteMsat.toDouble)
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          channelPane.amountUs.setText(s"${satoshi2millibtc(Satoshi(commitments.localCommit.spec.toLocalMsat / 1000L)).amount}")
          channelPane.balanceBar.setProgress(bal)
        }
      })

    case NodeDiscovered(nodeAnnouncement) =>
      log.info(s"Peer Node Discovered ${nodeAnnouncement.nodeId}")
      mainController.allNodesList.add(new PeerNode(nodeAnnouncement))
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          mainController.allNodesTab.setText(s"Nodes (${mainController.allNodesList.size})")
        }
      })

    case NodeLost(nodeId) =>
      log.info(s"Peer Node Lost ${nodeId}")
      mainController.allNodesList.removeIf(new Predicate[PeerNode] {
        override def test(pn: PeerNode) = pn.id.toString == nodeId.toString
      })
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          mainController.allNodesTab.setText(s"Nodes (${mainController.allNodesList.size})")
        }
      })

    case ChannelDiscovered(channelAnnouncement) =>
      log.info(s"Peer Channel Discovered ${channelAnnouncement.channelId}")
      mainController.allChannelsList.add(new PeerChannel(channelAnnouncement))
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          mainController.allChannelsTab.setText(s"Channels (${mainController.allChannelsList.size})")
        }
      })

    case ChannelLost(channelId) =>
      log.info(s"Peer Channel Lost ${channelId}")
      mainController.allChannelsList.removeIf(new Predicate[PeerChannel] {
        override def test(pc: PeerChannel) = pc.id.toString == channelId.toString
      })
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          mainController.allChannelsTab.setText(s"Channels (${mainController.allChannelsList.size})")
        }
      })

    // TODO
    /*case ChannelDiscovered(ChannelDesc(id, a, b)) =>
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
      })*/

  }
}
