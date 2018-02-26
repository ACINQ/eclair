package fr.acinq.eclair.gui.controllers

import java.lang.Boolean
import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control._
import javafx.stage.Stage

import fr.acinq.bitcoin.{Satoshi, _}
import fr.acinq.eclair.CoinUtils
import fr.acinq.eclair.channel.{Channel, ChannelFlags}
import fr.acinq.eclair.gui.utils.Constants
import fr.acinq.eclair.gui.{FxApp, Handlers}
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
    unit.setItems(Constants.FX_UNITS_ARRAY_NO_MSAT)
    unit.setValue(FxApp.getUnit.label)

    simpleConnection.selectedProperty.addListener(new ChangeListener[Boolean] {
      override def changed(observable: ObservableValue[_ <: Boolean], oldValue: Boolean, newValue: Boolean) = {
        fundingSatoshis.setDisable(newValue)
        pushMsatField.setDisable(newValue)
        unit.setDisable(newValue)
      }
    })

    host.textProperty.addListener(new ChangeListener[String] {
      def changed(observable: ObservableValue[_ <: String], oldValue: String, newValue: String): Unit = Try(NodeURI.parse(newValue)) match {
        case Failure(t) => hostError.setText(t.getLocalizedMessage)
        case _ => hostError.setText("")
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
    Try(NodeURI.parse(host.getText)) match {
      case Success(nodeUri) if simpleConnection.isSelected =>
        handlers.open(nodeUri, None)
        stage.close
      case Success(nodeUri) => Try(CoinUtils.convertStringAmountToSat(fundingSatoshis.getText, unit.getValue)) match {
        case Success(capacitySat) if capacitySat.amount <= 0 => fundingSatoshisError.setText("Capacity must be greater than 0")
        case Success(capacitySat) if capacitySat.amount >= Channel.MAX_FUNDING_SATOSHIS =>
          fundingSatoshisError.setText(s"Capacity must be less than ${CoinUtils.formatAmountInUnit(Satoshi(Channel.MAX_FUNDING_SATOSHIS), FxApp.getUnit, withUnit = true)}")
        case Success(capacitySat) =>
          pushMsatField.getText match {
            case "" =>
              handlers.open(nodeUri, Some(Peer.OpenChannel(nodeUri.nodeId, capacitySat, MilliSatoshi(0), None)))
              stage close()
            case _ => Try(MilliSatoshi(pushMsatField.getText.toLong)) match {
              case Success(pushMsat) if pushMsat.amount > satoshi2millisatoshi(capacitySat).amount =>
                pushMsatError.setText("Push must be less or equal to capacity")
              case Success(pushMsat) =>
                val channelFlags = if (publicChannel.isSelected) ChannelFlags.AnnounceChannel else ChannelFlags.Empty
                handlers.open(nodeUri, Some(Peer.OpenChannel(nodeUri.nodeId, capacitySat, pushMsat, Some(channelFlags))))
                stage close()
              case Failure(t) =>
                logger.error(s"could not parse push amount with cause=${t.getLocalizedMessage}")
                pushMsatError.setText("Push amount is not valid")
            }
          }
        case Failure(t) =>
          logger.error(s"could not parse capacity with cause=${t.getLocalizedMessage}")
          fundingSatoshisError.setText("Capacity is not valid")
      }
      case Failure(t) => logger.error(s"could not parse node uri with cause=${t.getLocalizedMessage}")
        hostError.setText(t.getLocalizedMessage)
    }
  }

  private def clearErrors() {
    hostError.setText("")
    fundingSatoshisError.setText("")
    pushMsatError.setText("")
  }

  @FXML def handleClose(event: ActionEvent) = stage.close()
}
