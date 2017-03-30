package fr.acinq.eclair.gui

import java.time.LocalDateTime
import java.util.function.Predicate
import javafx.application.Platform
import javafx.event.{ActionEvent, EventHandler}
import javafx.fxml.FXMLLoader
import javafx.scene.layout.VBox

import collection.JavaConversions._
import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin._
import fr.acinq.eclair.Setup
import fr.acinq.eclair.channel._
import fr.acinq.eclair.gui.controllers.{ChannelPaneController, MainController, Payment}
import fr.acinq.eclair.io.Reconnect
import fr.acinq.eclair.payment.{PaymentReceived, PaymentRelayed, PaymentSent}
import fr.acinq.eclair.router.{ChannelDiscovered, ChannelLost, NodeDiscovered, NodeLost}
import fr.acinq.eclair.wire.{ChannelAnnouncement, NodeAnnouncement}
import org.jgrapht.graph.{DefaultEdge, SimpleGraph}


/**
  * Created by PM on 16/08/2016.
  */
class GUIUpdater(mainController: MainController, setup: Setup) extends Actor with ActorLogging {

  class NamedEdge(val id: BinaryData) extends DefaultEdge {
    override def toString: String = s"${id.toString.take(8)}..."
  }

  val graph = new SimpleGraph[BinaryData, NamedEdge](classOf[NamedEdge])
  graph.addVertex(setup.nodeParams.privateKey.publicKey)

  def receive: Receive = main(Map())

  def createChannelPanel(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, isFunder: Boolean, temporaryChannelId: BinaryData): (ChannelPaneController, VBox) = {
    log.info(s"new channel: $channel")
    val loader = new FXMLLoader(getClass.getResource("/gui/main/channelPane.fxml"))
    val channelPaneController = new ChannelPaneController(s"$remoteNodeId")
    loader.setController(channelPaneController)
    val root = loader.load[VBox]
    channelPaneController.nodeId.setText(s"$remoteNodeId")
    channelPaneController.channelId.setText(s"$temporaryChannelId")
    channelPaneController.funder.setText(if (isFunder) "Yes" else "No")
    channelPaneController.close.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = channel ! CMD_CLOSE(None)
    })
    channelPaneController.reconnect.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = peer ! Reconnect
    })
    (channelPaneController, root)
  }

  def updateBalance(channelPaneController: ChannelPaneController, commitments: Commitments) = {
    val spec = commitments.localCommit.spec
    channelPaneController.capacity.setText(s"${millisatoshi2millibtc(MilliSatoshi(spec.totalFunds)).amount}")
    channelPaneController.amountUs.setText(s"${millisatoshi2millibtc(MilliSatoshi(spec.toLocalMsat)).amount}")
    channelPaneController.balanceBar.setProgress(spec.toLocalMsat.toDouble / spec.totalFunds)
  }

  def main(m: Map[ActorRef, ChannelPaneController]): Receive = {

    case ChannelCreated(channel, peer, remoteNodeId, isFunder, temporaryChannelId) =>
      context.watch(channel)
      val (channelPaneController, root) = createChannelPanel(channel, peer, remoteNodeId, isFunder, temporaryChannelId)
      Platform.runLater(new Runnable() {
        override def run = mainController.channelBox.getChildren.addAll(root)
      })
      context.become(main(m + (channel -> channelPaneController)))

    case ChannelRestored(channel, peer, remoteNodeId, isFunder, channelId, currentData) =>
      context.watch(channel)
      val (channelPaneController, root) = createChannelPanel(channel, peer, remoteNodeId, isFunder, channelId)
      currentData match {
        case d: HasCommitments => updateBalance(channelPaneController, d.commitments)
        case _ => {}
      }
      Platform.runLater(new Runnable() {
        override def run = {
          mainController.channelBox.getChildren.addAll(root)
        }
      })
      context.become(main(m + (channel -> channelPaneController)))

    case ChannelIdAssigned(channel, temporaryChannelId, channelId) if m.contains(channel) =>
      val channelPaneController = m(channel)
      Platform.runLater(new Runnable() {
        override def run = {
          channelPaneController.channelId.setText(s"$channelId")
        }
      })

    case ChannelStateChanged(channel, _, _, _, currentState, currentData) if m.contains(channel) =>
      val channelPaneController = m(channel)
      Platform.runLater(new Runnable() {
        override def run = {
          // enable reconnect if channel OFFLINE and this node is the funder of the channel
          channelPaneController.reconnect.setDisable(!(OFFLINE == currentState && "Yes".equals(channelPaneController.funder.getText)))
          channelPaneController.close.setText( if (OFFLINE == currentState) "Force close" else "Close")
          channelPaneController.state.setText(currentState.toString)
        }
      })

    case ChannelSignatureReceived(channel, commitments) if m.contains(channel) =>
      val channelPaneController = m(channel)
      Platform.runLater(new Runnable() {
        override def run = updateBalance(channelPaneController, commitments)
      })

    case Terminated(actor) if m.contains(actor) =>
      val channelPaneController = m(actor)
      log.debug(s"channel=${channelPaneController.channelId.getText} to be removed from gui")
      Platform.runLater(new Runnable() {
        override def run = {
          mainController.channelBox.getChildren.remove(channelPaneController.root)
        }
      })

    case NodeDiscovered(nodeAnnouncement) =>
      log.debug(s"peer node discovered with node id=${nodeAnnouncement.nodeId}")
      if(!mainController.networkNodesList.exists(na => na.nodeId == nodeAnnouncement.nodeId)) {
        mainController.networkNodesList.add(nodeAnnouncement)
      }

    case NodeLost(nodeId) =>
      log.debug(s"peer node lost with node id=${nodeId}")
      mainController.networkNodesList.removeIf(new Predicate[NodeAnnouncement] {
        override def test(na: NodeAnnouncement) = na.nodeId.equals(nodeId)
      })

    case ChannelDiscovered(channelAnnouncement, _) =>
      log.debug(s"peer channel discovered with channel id=${channelAnnouncement.shortChannelId}")
      if(!mainController.networkChannelsList.exists(ca => ca.shortChannelId == channelAnnouncement.shortChannelId)) {
        mainController.networkChannelsList.add(channelAnnouncement)
      }

    case ChannelLost(shortChannelId) =>
      log.debug(s"peer channel lost with channel id=${shortChannelId}")
      mainController.networkChannelsList.removeIf(new Predicate[ChannelAnnouncement] {
        override def test(ca: ChannelAnnouncement) = ca.shortChannelId == shortChannelId
      })

    case PaymentSent(amount, feesPaid, h) =>
      log.debug(s"payment sent with h=$h, amount=$amount, fees=$feesPaid")
      mainController.paymentSentList.prepend(new Payment(amount, feesPaid, h, LocalDateTime.now))

    case PaymentReceived(amount, h) =>
      log.debug(s"payment received with h=$h, amount=$amount")
       mainController.paymentReceivedList.prepend(new Payment(amount, MilliSatoshi(0), h, LocalDateTime.now))

    case PaymentRelayed(amount, feesEarned, h) =>
      log.debug(s"payment relayed with h=$h, amount=$amount, feesEarned=$feesEarned")
      mainController.paymentRelayedList.prepend(new Payment(amount, feesEarned, h, LocalDateTime.now))
  }
}
