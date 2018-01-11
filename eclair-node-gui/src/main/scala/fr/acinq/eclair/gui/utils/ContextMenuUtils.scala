package fr.acinq.eclair.gui.utils

import javafx.event.{ActionEvent, EventHandler}
import javafx.scene.control.{ContextMenu, MenuItem}
import javafx.scene.input.{Clipboard, ClipboardContent}

import scala.collection.immutable.List

/**
  * Created by DPA on 28/09/2016.
  */

/**
  * Action to copy a value
  *
  * @param label label of the copy action in the context menu, defaults to copy value
  * @param value the value to copy
  */
case class CopyAction(label: String = "Copy Value", value: String)

object ContextMenuUtils {
  val clip = Clipboard.getSystemClipboard

  /**
    * Builds a Context Menu containing a list of copy actions.
    *
    * @param actions list of copy action (label + value)
    * @return javafx context menu
    */
  def buildCopyContext(actions: List[CopyAction]): ContextMenu = {
    val context = new ContextMenu()
    for (action <- actions) {
      context.getItems.addAll(buildCopyMenuItem(action))
    }
    context
  }

  def buildCopyMenuItem(action: CopyAction): MenuItem = {
    val copyItem = new MenuItem(action.label)
    copyItem.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent): Unit = copyToClipboard(action.value)
    })
    copyItem
  }

  def copyToClipboard(value: String) = {
    val clipContent = new ClipboardContent
    clipContent.putString(value)
    clip.setContent(clipContent)
  }
}
