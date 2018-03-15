package fr.acinq.eclair.gui

import java.time.LocalDateTime
import java.util.function.Predicate
import javafx.application.Platform
import javafx.event.{ActionEvent, EventHandler}
import javafx.fxml.FXMLLoader
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.{Alert, ButtonType}
import javafx.scene.layout.VBox

import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin._
import fr.acinq.eclair.CoinUtils
import fr.acinq.eclair.blockchain.bitcoind.zmq.ZMQActor.{ZMQConnected, ZMQDisconnected}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{ElectrumConnected, ElectrumDisconnected}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.gui.controllers._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.{NORMAL => _, _}
import fr.acinq.eclair.wire.NodeAnnouncement

import scala.collection.JavaConversions._


/**
  * Created by PM on 16/08/2016.
  */
class GUIUpdater(mainController: MainController) extends Actor with ActorLogging {

  val STATE_MUTUAL_CLOSE = Set(WAIT_FOR_INIT_INTERNAL, WAIT_FOR_OPEN_CHANNEL, WAIT_FOR_ACCEPT_CHANNEL, WAIT_FOR_FUNDING_INTERNAL, WAIT_FOR_FUNDING_CREATED, WAIT_FOR_FUNDING_SIGNED, NORMAL)
  val STATE_FORCE_CLOSE = Set(WAIT_FOR_FUNDING_CONFIRMED, WAIT_FOR_FUNDING_LOCKED, NORMAL, SHUTDOWN, NEGOTIATING, OFFLINE, SYNCING)

  /**
    * Needed to stop JavaFX complaining about updates from non GUI thread
    */
  private def runInGuiThread(f: () => Unit): Unit = {
    Platform.runLater(new Runnable() {
      @Override def run() = f()
    })
  }

  def receive: Receive = main(Map())

  def createChannelPanel(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, isFunder: Boolean, temporaryChannelId: BinaryData): (ChannelPaneController, VBox) = {
    log.info(s"new channel: $channel")
    val loader = new FXMLLoader(getClass.getResource("/gui/main/channelPane.fxml"))
    val channelPaneController = new ChannelPaneController(s"$remoteNodeId")
    loader.setController(channelPaneController)
    val root = loader.load[VBox]
    channelPaneController.nodeId.setText(remoteNodeId.toString())
    channelPaneController.channelId.setText(temporaryChannelId.toString())
    channelPaneController.funder.setText(if (isFunder) "Yes" else "No")
    channelPaneController.close.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = channel ! CMD_CLOSE(scriptPubKey = None)
    })
    channelPaneController.forceclose.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = {
        val alert = new Alert(AlertType.WARNING, "Careful: force-close is more expensive than a regular close and will incur a delay before funds are spendable.\n\nAre you sure you want to proceed?", ButtonType.YES, ButtonType.NO)
        alert.showAndWait
        if (alert.getResult eq ButtonType.YES) {
          channel ! CMD_FORCECLOSE
        }
      }
    })

    // set the node alias if the node has already been announced
    mainController.networkNodesList
      .find(na => na.nodeId.toString.equals(remoteNodeId.toString))
      .map(na => channelPaneController.updateRemoteNodeAlias(na.alias))

    (channelPaneController, root)
  }

  def updateBalance(channelPaneController: ChannelPaneController, commitments: Commitments) = {
    val spec = commitments.localCommit.spec
    channelPaneController.capacity.setText(CoinUtils.formatAmountInUnit(MilliSatoshi(spec.totalFunds), FxApp.getUnit, withUnit = true))
    channelPaneController.amountUs.setText(CoinUtils.formatAmountInUnit(MilliSatoshi(spec.toLocalMsat), FxApp.getUnit, withUnit = true))
    channelPaneController.balanceBar.setProgress(spec.toLocalMsat.toDouble / spec.totalFunds)
  }

  def main(m: Map[ActorRef, ChannelPaneController]): Receive = {

    case ChannelCreated(channel, peer, remoteNodeId, isFunder, temporaryChannelId) =>
      context.watch(channel)
      val (channelPaneController, root) = createChannelPanel(channel, peer, remoteNodeId, isFunder, temporaryChannelId)
      runInGuiThread(() => mainController.channelBox.getChildren.addAll(root))
      context.become(main(m + (channel -> channelPaneController)))

    case ChannelRestored(channel, peer, remoteNodeId, isFunder, channelId, currentData) =>
      context.watch(channel)
      val (channelPaneController, root) = createChannelPanel(channel, peer, remoteNodeId, isFunder, channelId)
      currentData match {
        case d: HasCommitments =>
          updateBalance(channelPaneController, d.commitments)
          channelPaneController.txId.setText(d.commitments.commitInput.outPoint.txid.toString())
        case _ => {}
      }
      runInGuiThread(() => mainController.channelBox.getChildren.addAll(root))
      context.become(main(m + (channel -> channelPaneController)))

    case ChannelIdAssigned(channel, _, _, channelId) if m.contains(channel) =>
      val channelPaneController = m(channel)
      runInGuiThread(() => channelPaneController.channelId.setText(s"$channelId"))

    case ChannelStateChanged(channel, _, _, _, currentState, _) if m.contains(channel) =>
      val channelPaneController = m(channel)
      runInGuiThread { () =>
        channelPaneController.close.setVisible(STATE_MUTUAL_CLOSE.contains(currentState))
        channelPaneController.forceclose.setVisible(STATE_FORCE_CLOSE.contains(currentState))
        channelPaneController.state.setText(currentState.toString)
      }

    case ChannelSignatureReceived(channel, commitments) if m.contains(channel) =>
      val channelPaneController = m(channel)
      runInGuiThread(() => updateBalance(channelPaneController, commitments))

    case Terminated(actor) if m.contains(actor) =>
      val channelPaneController = m(actor)
      log.debug(s"channel=${channelPaneController.channelId.getText} to be removed from gui")
      runInGuiThread(() => mainController.channelBox.getChildren.remove(channelPaneController.root))

    case NodeDiscovered(nodeAnnouncement) =>
      log.debug(s"peer node discovered with node id=${nodeAnnouncement.nodeId}")
      runInGuiThread { () =>
        if (!mainController.networkNodesList.exists(na => na.nodeId == nodeAnnouncement.nodeId)) {
          mainController.networkNodesList.add(nodeAnnouncement)
          m.foreach(f => if (nodeAnnouncement.nodeId.toString.equals(f._2.theirNodeIdValue)) {
            f._2.updateRemoteNodeAlias(nodeAnnouncement.alias)
          })
        }
      }

    case NodeLost(nodeId) =>
      log.debug(s"peer node lost with node id=${nodeId}")
      runInGuiThread { () =>
        mainController.networkNodesList.removeIf(new Predicate[NodeAnnouncement] {
          override def test(na: NodeAnnouncement) = na.nodeId.equals(nodeId)
        })
      }

    case NodeUpdated(nodeAnnouncement) =>
      log.debug(s"peer node with id=${nodeAnnouncement.nodeId} has been updated")
      runInGuiThread { () =>
        val idx = mainController.networkNodesList.indexWhere(na => na.nodeId == nodeAnnouncement.nodeId)
        if (idx >= 0) {
          mainController.networkNodesList.update(idx, nodeAnnouncement)
          m.foreach(f => if (nodeAnnouncement.nodeId.toString.equals(f._2.theirNodeIdValue)) {
            f._2.updateRemoteNodeAlias(nodeAnnouncement.alias)
          })
        }
      }

    case ChannelDiscovered(channelAnnouncement, capacity) =>
      log.debug(s"peer channel discovered with channel id=${channelAnnouncement.shortChannelId}")
      runInGuiThread { () =>
        if (!mainController.networkChannelsList.exists(c => c.announcement.shortChannelId == channelAnnouncement.shortChannelId)) {
          mainController.networkChannelsList.add(new ChannelInfo(channelAnnouncement, -1, -1, capacity, None, None))
        }
      }

    case ChannelLost(shortChannelId) =>
      log.debug(s"peer channel lost with channel id=${shortChannelId}")
      runInGuiThread { () =>
        mainController.networkChannelsList.removeIf(new Predicate[ChannelInfo] {
          override def test(c: ChannelInfo) = c.announcement.shortChannelId == shortChannelId
        })
      }

    case ChannelUpdateReceived(channelUpdate) =>
      log.debug(s"peer channel with id=${channelUpdate.shortChannelId} has been updated")
      runInGuiThread { () =>
        val idx = mainController.networkChannelsList.indexWhere(c => c.announcement.shortChannelId == channelUpdate.shortChannelId)
        if (idx >= 0) {
          val c = mainController.networkChannelsList.get(idx)
          if (Announcements.isNode1(channelUpdate.flags)) {
            c.isNode1Enabled = Some(Announcements.isEnabled(channelUpdate.flags))
          } else {
            c.isNode2Enabled = Some(Announcements.isEnabled(channelUpdate.flags))
          }
          c.feeBaseMsat = channelUpdate.feeBaseMsat
          c.feeProportionalMillionths = channelUpdate.feeProportionalMillionths
          mainController.networkChannelsList.update(idx, c)
        }
      }

    case p: PaymentSucceeded =>
      val message = CoinUtils.formatAmountInUnit(MilliSatoshi(p.amountMsat), FxApp.getUnit, withUnit = true)
      mainController.handlers.notification("Payment Sent", message, NOTIFICATION_SUCCESS)

    case p: PaymentFailed =>
      val distilledFailures = PaymentLifecycle.transformForUser(p.failures)
      val message = s"${distilledFailures.size} attempts:\n${
        distilledFailures.map {
          case LocalFailure(t) => s"- (local) ${t.getMessage}"
          case RemoteFailure(_, e) => s"- (remote) ${e.failureMessage.message}"
          case _ => "- Unknown error"
        }.mkString("\n")
      }"
      mainController.handlers.notification("Payment Failed", message, NOTIFICATION_ERROR)

    case p: PaymentSent =>
      log.debug(s"payment sent with h=${p.paymentHash}, amount=${p.amount}, fees=${p.feesPaid}")
      runInGuiThread(() => mainController.paymentSentList.prepend(new PaymentSentRecord(p, LocalDateTime.now())))

    case p: PaymentReceived =>
      log.debug(s"payment received with h=${p.paymentHash}, amount=${p.amount}")
      runInGuiThread(() => mainController.paymentReceivedList.prepend(new PaymentReceivedRecord(p, LocalDateTime.now())))

    case p: PaymentRelayed =>
      log.debug(s"payment relayed with h=${p.paymentHash}, amount=${p.amountIn}, feesEarned=${p.amountOut}")
      runInGuiThread(() => mainController.paymentRelayedList.prepend(new PaymentRelayedRecord(p, LocalDateTime.now())))

    case ZMQConnected =>
      log.debug("ZMQ connection UP")
      runInGuiThread(() => mainController.hideBlockerModal)

    case ZMQDisconnected =>
      log.debug("ZMQ connection DOWN")
      runInGuiThread(() => mainController.showBlockerModal("Bitcoin Core"))

    case ElectrumConnected =>
      log.debug("Electrum connection UP")
      runInGuiThread(() => mainController.hideBlockerModal)

    case ElectrumDisconnected =>
      log.debug("Electrum connection DOWN")
      runInGuiThread(() => mainController.showBlockerModal("Electrum"))
  }
}
