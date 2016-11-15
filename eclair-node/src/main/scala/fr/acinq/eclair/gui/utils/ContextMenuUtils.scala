package fr.acinq.eclair.gui.utils

import javafx.event.{ActionEvent, EventHandler}
import javafx.scene.control.{ContextMenu, MenuItem}
import javafx.scene.input.{Clipboard, ClipboardContent}

/**
  * Created by DPA on 28/09/2016.
  */
object ContextMenuUtils {
  val clip = Clipboard.getSystemClipboard

  /**
    * Builds a Context Menu with a single Copy action.
    * @param valueToCopy the value to copy to clipboard
    * @return javafx context menu
    */
  def buildCopyContext(valueToCopy: String): ContextMenu = {
    val context = new ContextMenu()
    val copyItem = new MenuItem("Copy Value")
    copyItem.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent): Unit = {
        val clipContent = new ClipboardContent
        clipContent.putString(valueToCopy)
        clip.setContent(clipContent)
      }
    })
    context.getItems.addAll(copyItem)
    return context
  }
}
