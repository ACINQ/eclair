package fr.acinq.eclair.gui.controllers

import java.lang.Boolean
import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control._
import javafx.stage.Stage

import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.channel.{Channel, ChannelFlags}
import fr.acinq.eclair.gui.{FxApp, Handlers}
import fr.acinq.eclair.gui.utils.{CoinUtils, GUIValidators}
import fr.acinq.eclair.io.{NodeURI, Peer}
import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}

/**
  * Created by DPA on 23/09/2016.
  */
class OpenChannelController(val handlers: Handlers, val stage: Stage) extends Logging {

  @FXML var host: TextField = _
  @FXML var hostError: Label = _
  @FXML var simpleConnection: CheckBox = _
  @FXML var fundingSatoshis: TextField = _
  @FXML var fundingSatoshisError: Label = _
  @FXML var pushMsatField: TextField = _
  @FXML var pushMsatError: Label = _
  @FXML var publicChannel: CheckBox = _
  @FXML var unit: ComboBox[String] = _
  @FXML var button: Button = _

  @FXML def initialize = {
    unit.setItems(CoinUtils.FX_UNITS_ARRAY_NO_MSAT)
    unit.setValue(FxApp.getUnit.label)

    simpleConnection.selectedProperty.addListener(new ChangeListener[Boolean] {
      override def changed(observable: ObservableValue[_ <: Boolean], oldValue: Boolean, newValue: Boolean) = {
        fundingSatoshis.setDisable(newValue)
        pushMsatField.setDisable(newValue)
        unit.setDisable(newValue)
      }
    })

    host.textProperty.addListener(new ChangeListener[String] {
      def changed(observable: ObservableValue[_ <: String], oldValue: String, newValue: String): Unit = {
        GUIValidators.validate(newValue, hostError, "Please use a valid uri (pubkey@host:port)", GUIValidators.hostRegex)
      }
    })

    fundingSatoshis.textProperty.addListener(new ChangeListener[String] {
      def changed(observable: ObservableValue[_ <: String], oldValue: String, newValue: String): Unit = {
        Try(CoinUtils.convertStringAmountToSat(newValue, unit.getValue)) match {
          case Success(capacitySat) if capacitySat.amount <= 0 =>
            fundingSatoshisError.setText("Capacity must be greater than 0")
          case Success(capacitySat) if capacitySat.amount < 50000 =>
            fundingSatoshisError.setText("Capacity is low and the channel may not be able to open")
          case Success(capacitySat) if capacitySat.amount >= Channel.MAX_FUNDING_SATOSHIS =>
            fundingSatoshisError.setText(s"Capacity must be less than ${CoinUtils.formatAmountInUnit(Satoshi(Channel.MAX_FUNDING_SATOSHIS), FxApp.getUnit, withUnit = true)}")
          case Success(_) => fundingSatoshisError.setText("")
          case _ => fundingSatoshisError.setText("Capacity is not valid")
        }
      }
    })
  }

  @FXML def handleOpen(event: ActionEvent) = {
    clearErrors()
    if (GUIValidators.validate(host.getText, hostError, "Please use a valid uri (pubkey@host:port)", GUIValidators.hostRegex)) {
      val nodeUri = NodeURI.parse(host.getText)
      if (simpleConnection.isSelected) {
        handlers.open(nodeUri, None)
        stage.close
      } else {
        import fr.acinq.bitcoin._
        fundingSatoshis.getText match {
          case GUIValidators.amountDecRegex(_*) =>
            Try(CoinUtils.convertStringAmountToSat(fundingSatoshis.getText, unit.getValue)) match {
              case Success(capacitySat) if capacitySat.amount <= 0 =>
                fundingSatoshisError.setText("Capacity must be greater than 0")
              case Success(capacitySat) if capacitySat.amount >= Channel.MAX_FUNDING_SATOSHIS =>
                fundingSatoshisError.setText(s"Capacity must be less than ${CoinUtils.formatAmountInUnit(Satoshi(Channel.MAX_FUNDING_SATOSHIS), FxApp.getUnit, withUnit = true)}")
              case Success(capacitySat) =>
                pushMsatField.getText match {
                  case "" =>
                    handlers.open(nodeUri, Some(Peer.OpenChannel(nodeUri.nodeId, capacitySat, MilliSatoshi(0), None)))
                    stage close()
                  case GUIValidators.amountRegex(_*) =>
                    Try(MilliSatoshi(pushMsatField.getText.toLong)) match {
                      case Success(pushMsat) if pushMsat.amount > satoshi2millisatoshi(capacitySat).amount =>
                        pushMsatError.setText("Push must be less or equal to capacity")
                      case Success(pushMsat) =>
                        val channelFlags = if (publicChannel.isSelected) ChannelFlags.AnnounceChannel else ChannelFlags.Empty
                        handlers.open(nodeUri, Some(Peer.OpenChannel(nodeUri.nodeId, capacitySat, pushMsat, Some(channelFlags))))
                        stage close()
                      case Failure(t) =>
                        logger.error("Could not parse push amount", t)
                        pushMsatError.setText("Push amount is not valid")
                    }
                  case _ => pushMsatError.setText("Push amount is not valid")
                }
              case Failure(t) =>
                logger.error("Could not parse capacity amount", t)
                fundingSatoshisError.setText("Capacity is not valid")
            }
          case _ => fundingSatoshisError.setText("Capacity is not valid")
        }
      }
    }
  }

  private def clearErrors() = {
    hostError.setText("")
    fundingSatoshisError.setText("")
    pushMsatError.setText("")
  }

  @FXML def handleClose(event: ActionEvent) = stage.close()
}
