/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{ActorRef, Props, SupervisorStrategy}
import akka.pattern.ask
import akka.util.Timeout
import com.googlecode.lanterna.gui2.dialogs.TextInputDialogBuilder
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.{TerminalPosition, TerminalSize}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair.channel.State
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.payment.PaymentLifecycle.SendPayment
import fr.acinq.eclair.payment.PaymentRequest
import grizzled.slf4j.Logging

import scala.collection.JavaConversions._
import scala.concurrent.duration._

/**
  * Created by PM on 05/06/2017.
  */
class Textui(kit: Kit) extends Logging {

  import com.googlecode.lanterna.TextColor
  import com.googlecode.lanterna.gui2._
  import com.googlecode.lanterna.screen.TerminalScreen
  import com.googlecode.lanterna.terminal.DefaultTerminalFactory

  // Setup terminal and screen layers
  val terminal = new DefaultTerminalFactory().createTerminal
  val screen = new TerminalScreen(terminal)
  screen.startScreen()

  // Create panel to hold components
  val mainPanel = new Panel()
  mainPanel.setLayoutManager(new BorderLayout())

  val channelsPanel = new Panel()
  channelsPanel.setLayoutManager(new LinearLayout(Direction.VERTICAL))
  channelsPanel.setLayoutData(BorderLayout.Location.TOP)
  mainPanel.addComponent(channelsPanel)
  channelsPanel.addComponent(new Label("channels"))

  val channels = collection.mutable.Map[ActorRef, Panel]()

  def addChannel(channel: ActorRef, channelId: BinaryData, remoteNodeId: PublicKey, state: State, balance: Satoshi, capacity: Satoshi): Unit = {
    val channelPanel = new Panel()
    channelPanel.setLayoutManager(new LinearLayout(Direction.HORIZONTAL))
    val channelDataPanel = new Panel()
    channelDataPanel.setLayoutManager(new GridLayout(2))
    channelDataPanel.addComponent(new Label(s"$channelId"))
    channelDataPanel.addComponent(new Label(s"${state.toString}"))
    channelDataPanel.addComponent(new Label(s"$remoteNodeId"))
    channelDataPanel.addComponent(new EmptySpace(new TerminalSize(0, 0))) // Empty space underneath labels
    channelDataPanel.addComponent(new Separator(Direction.HORIZONTAL)) // Empty space underneath labels
    channelPanel.addComponent(channelDataPanel)
    val pb = new ProgressBar(0, 100)
    pb.setLabelFormat(s"$balance")
    pb.setValue((balance.amount * 100 / capacity.amount).toInt)
    pb.setPreferredWidth(100)
    channelPanel.addComponent(pb)
    channelsPanel.addComponent(channelPanel)
    channels.put(channel, channelPanel)
  }

  def updateState(channel: ActorRef, state: State): Unit = {
    val panel = channels(channel)
    val channelDataPanel = panel.getChildren.iterator().next().asInstanceOf[Panel]
    channelDataPanel.getChildren.toList(1).asInstanceOf[Label].setText(s"$state")
  }

  /*val shortcutsPanel = new Panel()
  shortcutsPanel.setLayoutManager(new LinearLayout(Direction.HORIZONTAL))
  shortcutsPanel.addComponent(new Label("(N)ew channel"))
  shortcutsPanel.addComponent(new Separator(Direction.VERTICAL))
  shortcutsPanel.setLayoutData(BorderLayout.Location.BOTTOM)
  mainPanel.addComponent(shortcutsPanel)*/

  //addChannel(randomBytes(32), randomKey.publicKey, NORMAL, Satoshi(Random.nextInt(1000)), Satoshi(1000))
  //addChannel(randomBytes(32), randomKey.publicKey, NORMAL, Satoshi(Random.nextInt(1000)), Satoshi(1000))
  //addChannel(randomBytes(32), randomKey.publicKey, NORMAL, Satoshi(Random.nextInt(1000)), Satoshi(1000))

  //val theme = new SimpleTheme(TextColor.ANSI.DEFAULT, TextColor.ANSI.BLACK)

  // Create window to hold the panel
  val window = new BasicWindow
  window.setComponent(mainPanel)
  //window.setTheme(theme)
  window.setHints(/*Window.Hint.FULL_SCREEN :: */ Window.Hint.NO_DECORATIONS :: Nil)

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = Timeout(30 seconds)

  val textuiUpdater = kit.system.actorOf(SimpleSupervisor.props(Props(classOf[TextuiUpdater], this), "textui-updater", SupervisorStrategy.Resume))
  // Create gui and start gui
  val runnable = new Runnable {
    override def run(): Unit = {
      val gui = new MultiWindowTextGUI(screen, new DefaultWindowManager, new EmptySpace(TextColor.ANSI.BLUE))
      window.addWindowListener(new WindowListener {
        override def onMoved(window: Window, terminalPosition: TerminalPosition, terminalPosition1: TerminalPosition): Unit = {}

        override def onResized(window: Window, terminalSize: TerminalSize, terminalSize1: TerminalSize): Unit = {}

        override def onUnhandledInput(t: Window, keyStroke: KeyStroke, atomicBoolean: AtomicBoolean): Unit = {}

        override def onInput(t: Window, keyStroke: KeyStroke, atomicBoolean: AtomicBoolean): Unit = {
          if (keyStroke.getCharacter == 'n') {
            val input = new TextInputDialogBuilder()
              .setTitle("Open a new channel")
              .setDescription("Node URI:")
              //.setValidationPattern(Pattern.compile("[0-9]"), "You didn't enter a single number!")
              .build()
              .showDialog(gui)
            try {
              for {
                _ <- kit.switchboard ? Peer.Connect(NodeURI.parse(input))
              } yield {}
            } catch {
              case t: Throwable => logger.error("", t)
            }
          } else if (keyStroke.getCharacter == 's') {
            val input = new TextInputDialogBuilder()
              .setTitle("Send a payment")
              .setDescription("Payment request:")
              //.setValidationPattern(Pattern.compile("[0-9]"), "You didn't enter a single number!")
              .build()
              .showDialog(gui)
            try {
              val paymentRequest = PaymentRequest.read(input)
              kit.paymentInitiator ! SendPayment(paymentRequest.amount.getOrElse(MilliSatoshi(1000000)).amount, paymentRequest.paymentHash, paymentRequest.nodeId)
            } catch {
              case t: Throwable => logger.error("", t)
            }
          }
        }
      })
      gui.addWindowAndWait(window)
      kit.system.shutdown()
    }
  }
  new Thread(runnable).start()

}
