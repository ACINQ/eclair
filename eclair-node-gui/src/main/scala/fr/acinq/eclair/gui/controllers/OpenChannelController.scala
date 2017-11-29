package fr.acinq.eclair.gui.controllers

import java.lang.Boolean
import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control._
import javafx.stage.Stage

import fr.acinq.bitcoin.{MilliSatoshi, Satoshi}
import fr.acinq.eclair.channel.{Channel, ChannelFlags}
import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.utils.GUIValidators
import fr.acinq.eclair.io.Switchboard.NewChannel
import grizzled.slf4j.Logging

/**
  * Created by DPA on 23/09/2016.
  */
class OpenChannelController(val handlers: Handlers, val stage: Stage) extends Logging {

  @FXML var host: TextField = _
  @FXML var hostError: Label = _
  @FXML var simpleConnection: CheckBox = _
  @FXML var fundingSatoshis: TextField = _
  @FXML var fundingSatoshisError: Label = _
  @FXML var pushMsat: TextField = _
  @FXML var pushMsatError: Label = _
  @FXML var publicChannel: CheckBox = _
  @FXML var unit: ComboBox[String] = _
  @FXML var button: Button = _

  @FXML def initialize = {
    unit.setValue(unit.getItems.get(0))

    simpleConnection.selectedProperty.addListener(new ChangeListener[Boolean] {
      override def changed(observable: ObservableValue[_ <: Boolean], oldValue: Boolean, newValue: Boolean) = {
        fundingSatoshis.setDisable(newValue)
        pushMsat.setDisable(newValue)
        unit.setDisable(newValue)
      }
    })
  }

  @FXML def handleOpen(event: ActionEvent) = {
    if (GUIValidators.validate(host.getText, hostError, "Please use a valid url (pubkey@host:port)", GUIValidators.hostRegex)) {
      if (simpleConnection.isSelected) {
        handlers.open(host.getText, None)
        stage.close
      } else {
        if (GUIValidators.validate(fundingSatoshis.getText, fundingSatoshisError, "Funding must be numeric", GUIValidators.amountRegex)
          && GUIValidators.validate(fundingSatoshisError, "Funding must be greater than 0", fundingSatoshis.getText.toLong > 0)) {
          val rawFunding = fundingSatoshis.getText.toLong
          val smartFunding = unit.getValue match {
            case "milliBTC" => Satoshi(rawFunding * 100000L)
            case "Satoshi" => Satoshi(rawFunding)
            case "milliSatoshi" => Satoshi(rawFunding / 1000L)
          }
          if (GUIValidators.validate(fundingSatoshisError, f"Capacity must be less than ${Channel.MAX_FUNDING_SATOSHIS}%d satoshis", smartFunding.toLong < Channel.MAX_FUNDING_SATOSHIS)) {
            if (!pushMsat.getText.isEmpty) {
              // pushMsat is optional, so we validate field only if it isn't empty
              import fr.acinq.bitcoin._
              if (GUIValidators.validate(pushMsat.getText, pushMsatError, "Push msat must be numeric", GUIValidators.amountRegex)
                && GUIValidators.validate(pushMsatError, "Push msat must be less or equal to capacity", pushMsat.getText.toLong <= satoshi2millisatoshi(smartFunding).amount)) {
                val channelFlags = if (publicChannel.isSelected) ChannelFlags.AnnounceChannel else ChannelFlags.Empty
                handlers.open(host.getText, Some(NewChannel(smartFunding, MilliSatoshi(pushMsat.getText.toLong), Some(channelFlags))))
                stage.close
              }
            } else {
              handlers.open(host.getText, Some(NewChannel(smartFunding, MilliSatoshi(0), None)))
              stage.close
            }
          }
        }
      }
    }
  }

  @FXML def handleClose(event: ActionEvent) = stage.close
}
