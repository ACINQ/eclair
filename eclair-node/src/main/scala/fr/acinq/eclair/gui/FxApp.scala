package fr.acinq.eclair.gui

import java.awt._
import java.awt.event.{ActionEvent, ActionListener}
import java.net.ConnectException
import javafx.application.{Application, Platform}
import javafx.event.EventHandler
import javafx.fxml.FXMLLoader
import javafx.scene.image.Image
import javafx.scene.text.Text
import javafx.scene.{Group, Parent, Scene}
import javafx.stage.{Modality, Stage, StageStyle, WindowEvent}

import akka.actor.Props
import fr.acinq.eclair.channel.ChannelEvent
import fr.acinq.eclair.gui.controllers.MainController
import fr.acinq.eclair.gui.stages.SplashStage
import fr.acinq.eclair.router.NetworkEvent
import fr.acinq.eclair.{Setup}
import grizzled.slf4j.Logging

/**
  * Created by PM on 16/08/2016.
  */
class FxApp extends Application with Logging {

  override def start(primaryStage: Stage): Unit = {

    val icon = new Image("/gui/commons/images/eclair02.png", true)
    primaryStage.getIcons().add(icon)
    val splashStage = new SplashStage()
    splashStage.initOwner(primaryStage)
    splashStage.show

    new Thread(new Runnable {
      override def run(): Unit = {

        try {
          val setup = new Setup()

          // add icon in system tray
          val awtIcon = Toolkit.getDefaultToolkit.createImage(getClass.getResource("/gui/commons/images/eclair02.png"))
          val trayIcon = new TrayIcon(awtIcon, "Eclair")
          trayIcon.setImageAutoSize(true)
          if (SystemTray.isSupported()) {
            try {
              SystemTray.getSystemTray.add(trayIcon)
            } catch {
              case e: AWTException => logger.debug("Eclair could not be added to System Tray.")
            }
          }

          val handlers = new Handlers(setup, trayIcon)
          val controller = new MainController(handlers, primaryStage, setup, getHostServices)
          val guiUpdater = setup.system.actorOf(Props(classOf[GUIUpdater], primaryStage, controller, setup), "gui-updater")
          setup.system.eventStream.subscribe(guiUpdater, classOf[ChannelEvent])
          setup.system.eventStream.subscribe(guiUpdater, classOf[NetworkEvent])

          import scala.concurrent.ExecutionContext.Implicits.global
          setup.fatalEventFuture onSuccess {
            case e => Platform.runLater(new Runnable {
              override def run(): Unit = {
                val dialog = new Stage()
                dialog.initStyle(StageStyle.UTILITY)
                dialog.setAlwaysOnTop(true)
                dialog.initModality(Modality.APPLICATION_MODAL)
                val scene = new Scene(new Group(new Text(25, 25, s"$e")), 200, 50)
                dialog.setResizable(false)
                dialog.setScene(scene)
                dialog.setTitle("Fatal error")
                dialog.showAndWait()
                Platform.exit()
              }
            })
          }

          Platform.runLater(new Runnable {
            override def run(): Unit = {
              // get fxml/controller
              val mainFXML = new FXMLLoader(getClass.getResource("/gui/main/main.fxml"))
              mainFXML.setController(controller)
              val mainRoot = mainFXML.load[Parent]
              val scene = new Scene(mainRoot)

              primaryStage.setTitle("Eclair")
              primaryStage.setOnCloseRequest(new EventHandler[WindowEvent] {
                override def handle(event: WindowEvent): Unit = {
                  SystemTray.getSystemTray.remove(trayIcon)
                  System.exit(0)
                }
              })
              splashStage.close()
              primaryStage.setScene(scene)
              primaryStage.show()
              addTrayIconActions(trayIcon, primaryStage)
            }
          })

        } catch {
          case con: ConnectException => {
            logger.error(s"Error when connecting to bitcoin-core: ", con)
            Platform.runLater(new Runnable {
              override def run(): Unit = {
                splashStage.controller.showError("Could not connect to Bitcoin-core.")
              }
            })
          }
          case e: Exception => {
            logger.error(s"Something wrong happened: ", e)
            Platform.runLater(new Runnable {
              override def run(): Unit = {
                splashStage.controller.showError("An error has occured.")
              }
            })
          }
        }
      }

    }).start()

  }

  /**
    * Adds a popup menu to the system tray icon, if the system supports it, with the following actions:
    * <ul>
    *   <li>Open the stage (with menu item and double click on icon)
    *   <li>Minimize the stage
    *   <li>Close the stage
    * </ul>
    * @param trayIcon the tray icon
    * @param stage the main app stage
    */
  private def addTrayIconActions (trayIcon: TrayIcon, stage: Stage): Unit = {
    if (SystemTray.isSupported) {
      Platform.setImplicitExit(false)

      // create menu
      val menu = new PopupMenu
      val showItem = new MenuItem("Open Eclair")
      showItem.setFont(Font.decode(null).deriveFont(Font.BOLD))
      val minimizeItem = new MenuItem("Minimize")
      val closeItem = new MenuItem("Close")
      menu.add(showItem)
      menu.add(minimizeItem)
      menu.addSeparator
      menu.add(closeItem)
      trayIcon.setPopupMenu(menu)

      // add actions listeners
      trayIcon.addActionListener(new ActionListener {
        override def actionPerformed(e: ActionEvent) = {
          Platform.runLater(new Runnable {
            // show the stage when the user double-clicks on tray icon
            override def run() = stage.show
          })
        }
      })
      showItem.addActionListener(new ActionListener() {
        override def actionPerformed(e: ActionEvent) = {
          Platform.runLater(new Runnable {
            override def run() = stage.show
          })
        }
      })
      minimizeItem.addActionListener(new ActionListener() {
        override def actionPerformed(e: ActionEvent) = {
          Platform.runLater(new Runnable {
            override def run() = stage.hide
          })
        }
      })
      closeItem.addActionListener(new ActionListener {
        override def actionPerformed(e: ActionEvent) = stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST))
      })
    }
  }
}