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

  /**
    * Max funding is 2^24 satoshi.
    * PushMsat must not be greater than 1000 * Max funding
    *
    * https://github.com/lightningnetwork/lightning-rfc/blob/master/02-peer-protocol.md#requirements
    */
  val maxFunding = 16777216L
  val maxPushMsat = 1000L * maxFunding

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
    if (GUIValidators.validate(host.getText, hostError, "Please use a valid url (pubkey@host:port)", GUIValidators.hostRegex)
      & GUIValidators.validate(fundingSatoshis.getText, fundingSatoshisError, "Funding must be numeric", GUIValidators.amountRegex)
      && GUIValidators.validate(fundingSatoshisError, "Funding must be greater than 0", fundingSatoshis.getText.toLong > 0)) {
      val rawFunding = fundingSatoshis.getText.toLong
      val smartFunding = unit.getValue match {
        case "milliBTC" => Satoshi(rawFunding * 100000L)
        case "Satoshi" => Satoshi(rawFunding)
        case "milliSatoshi" => Satoshi(rawFunding / 1000L)
      }
      if (GUIValidators.validate(fundingSatoshisError, "Funding must be 16 777 216 satoshis (~0.167 BTC) or less", smartFunding.toLong <= maxFunding)) {
        if (!pushMsat.getText.isEmpty) {
          // pushMsat is optional, so we validate field only if it isn't empty
          if (GUIValidators.validate(pushMsat.getText, pushMsatError, "Push mSat must be numeric", GUIValidators.amountRegex)
          && GUIValidators.validate(pushMsatError, "Push mSat must be 16 777 216 000 mSat (~0.167 BTC) or less", pushMsat.getText.toLong <= maxPushMsat)) {
            handlers.open(host.getText, smartFunding, MilliSatoshi(pushMsat.getText.toLong))
            stage.close()
          }
        } else {
          handlers.open(host.getText, smartFunding, MilliSatoshi(0))
          stage.close()
        }
      }
    }
  }

  @FXML def handleClose(event: ActionEvent): Unit = {
    stage.close()
  }
}
