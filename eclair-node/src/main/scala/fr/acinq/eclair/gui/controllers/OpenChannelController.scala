package fr.acinq.eclair.gui.controllers

import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control.{Button, ComboBox, Label, TextField}
import javafx.stage.Stage

import fr.acinq.bitcoin.{MilliSatoshi, Satoshi}
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
  @FXML var fundingSatoshis: TextField = _
  @FXML var fundingSatoshisError: Label = _
  @FXML var pushMsat: TextField = _
  @FXML var pushMsatError: Label = _
  @FXML var unit: ComboBox[String] = _
  @FXML var button: Button = _

  @FXML def initialize(): Unit = {
    unit.setValue(unit.getItems.get(0))
  }

  @FXML def handleOpen(event: ActionEvent): Unit = {
    if (GUIValidators.validate(host.getText, hostError, GUIValidators.hostRegex)
      && GUIValidators.validate(fundingSatoshis.getText, fundingSatoshisError, GUIValidators.amountRegex)) {
      val rawFunding = fundingSatoshis.getText.toLong
      val smartFunding = unit.getValue match {
        case "milliBTC" => Satoshi(rawFunding * 100000L)
        case "Satoshi" => Satoshi(rawFunding)
        case "milliSatoshi" => Satoshi(rawFunding / 1000L)
      }
      if (!pushMsat.getText.isEmpty) {
        // pushMsat is optional, so we validate field only if it isn't empty
        if (GUIValidators.validate(pushMsat.getText, pushMsatError, GUIValidators.amountRegex)) {
          handlers.open(host.getText, smartFunding, MilliSatoshi(pushMsat.getText.toLong))
          stage.close()
        }
      } else {
        handlers.open(host.getText, smartFunding, Satoshi(0))
        stage.close()
      }
    }
  }

  @FXML def handleClose(event: ActionEvent): Unit = {
    stage.close()
  }
}
