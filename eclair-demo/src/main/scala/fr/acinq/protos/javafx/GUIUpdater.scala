package fr.acinq.protos.javafx

import javafx.application.Platform
import javafx.event.{ActionEvent, EventHandler}

import akka.actor.{Actor, ActorLogging, ActorRef}
import fr.acinq.eclair.channel._


/**
  * Created by PM on 16/08/2016.
  */
class GUIUpdater(helloWorld: GUIBoot) extends Actor with ActorLogging {

  def receive: Receive = main(Map())

  def main(m: Map[ActorRef, PaneChannel]): Receive = {

    case ChannelCreated(channel, params, theirNodeId) =>
      log.info(s"new channel: $channel")
      val pane = new PaneChannel()
      pane.labelNode.setText(s"${theirNodeId.take(8)}")
      pane.labelFunder.setText(params.anchorAmount.map(_ => "Funder").getOrElse("Fundee"))
      pane.buttonClose.setOnAction(new EventHandler[ActionEvent] {
        override def handle(event: ActionEvent): Unit = channel ! CMD_CLOSE(None)
      })
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          helloWorld.vBoxPane.getChildren.addAll(pane)
        }
      })
      context.become(main(m + (channel -> pane)))

    case ChannelIdAssigned(channel, channelId) =>
      val pane = m(channel)
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          pane.labelChannelId.setText(s"channel #${channelId.toString.take(8)}")
        }
      })

    case ChannelChangedState(channel, previousState, currentState, currentData) =>
      val pane = m(channel)
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          pane.labelState.setText(currentState.toString)
        }
      })

    case ChannelSignatureReceived(channel, commitments) =>
      val pane = m(channel)
      val bal = commitments.ourCommit.spec.amount_us_msat.toDouble / (commitments.ourCommit.spec.amount_us_msat.toDouble + commitments.ourCommit.spec.amount_them_msat.toDouble)
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          pane.progressBarBalance.setProgress(bal)
        }
      })

    case e: ChannelEvent => log.warning(s"channel event: $e")
  }
}
