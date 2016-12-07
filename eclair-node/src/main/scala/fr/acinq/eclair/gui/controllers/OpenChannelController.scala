package fr.acinq.eclair.gui.controllers

import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control.{Button, ComboBox, Label, TextField}
import javafx.stage.Stage

import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.Setup
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.utils.GUIValidators
import grizzled.slf4j.Logging

/**
  * Created by DPA on 23/09/2016.
  */
class OpenChannelController(val handlers: Handlers, val stage: Stage, val setup: Setup) extends BaseController with Logging {

  @FXML var host: TextField = _
  @FXML var hostError: Label = _
  @FXML var amount: TextField = _
  @FXML var amountError: Label = _
  @FXML var unit: ComboBox[String] = _
  @FXML var button: Button = _

  @FXML def initialize(): Unit = {
    unit.setValue(unit.getItems.get(0))
  }

  @FXML def handleOpen(event: ActionEvent): Unit = {
    if (GUIValidators.validate(host.getText, hostError, GUIValidators.hostRegex)
      & GUIValidators.validate(amount.getText, amountError, GUIValidators.amountRegex)) {
      val raw = amount.getText.toLong
      val smartAmount = unit.getValue match {
        case "milliBTC" => Satoshi(raw * 100000L)
        case "Satoshi" => Satoshi(raw)
        case "milliSatoshi" => Satoshi(raw / 1000L)
      }
      handlers.open(host.getText, smartAmount)
      stage.close()
    }
  }

  @FXML def handleClose(event: ActionEvent): Unit = {
    stage.close()
  }
}
