package fr.acinq.eclair.gui.controllers

import java.lang.Boolean
import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control._
import javafx.stage.Stage

import com.google.common.base.Strings
import fr.acinq.bitcoin.{Satoshi, _}
import fr.acinq.eclair.{CoinUtils, Globals}
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
  @FXML var fundingSat: TextField = _
  @FXML var fundingSatError: Label = _
  @FXML var fundingUnit: ComboBox[String] = _
  @FXML var pushMsatField: TextField = _
  @FXML var pushMsatError: Label = _
  @FXML var feerateField: TextField = _
  @FXML var feerateError: Label = _
  @FXML var publicChannel: CheckBox = _

  @FXML def initialize() = {
    fundingUnit.setItems(Constants.FX_UNITS_ARRAY_NO_MSAT)
    fundingUnit.setValue(FxApp.getUnit.label)
    feerateField.setText(Globals.feeratesPerByte.get().blocks_6.toString)

    simpleConnection.selectedProperty.addListener(new ChangeListener[Boolean] {
      override def changed(observable: ObservableValue[_ <: Boolean], oldValue: Boolean, newValue: Boolean) = {
        fundingSat.setDisable(newValue)
        pushMsatField.setDisable(newValue)
        fundingUnit.setDisable(newValue)
      }
    })

    host.textProperty.addListener(new ChangeListener[String] {
      def changed(observable: ObservableValue[_ <: String], oldValue: String, newValue: String): Unit = Try(NodeURI.parse(newValue)) match {
        case Failure(t) => hostError.setText(t.getLocalizedMessage)
        case _ => hostError.setText("")
      }
    })

    fundingSat.textProperty.addListener(new ChangeListener[String] {
      def changed(observable: ObservableValue[_ <: String], oldValue: String, newValue: String): Unit = {
        Try(CoinUtils.convertStringAmountToSat(newValue, fundingUnit.getValue)) match {
          case Success(capacitySat) if capacitySat.amount <= 0 =>
            fundingSatError.setText("Capacity must be greater than 0")
          case Success(capacitySat) if capacitySat.amount < 50000 =>
            fundingSatError.setText("Capacity is low and the channel may not be able to open")
          case Success(capacitySat) if capacitySat.amount >= Channel.MAX_FUNDING_SATOSHIS =>
            fundingSatError.setText(s"Capacity must be less than ${CoinUtils.formatAmountInUnit(Satoshi(Channel.MAX_FUNDING_SATOSHIS), FxApp.getUnit, withUnit = true)}")
          case Success(_) => fundingSatError.setText("")
          case _ => fundingSatError.setText("Capacity is not valid")
        }
      }
    })
  }

  @FXML def handleOpen(event: ActionEvent) = {
    clearErrors()
    Try(NodeURI.parse(host.getText)) match {
      case Success(nodeUri) if simpleConnection.isSelected =>
        handlers.open(nodeUri, None)
        stage.close()
      case Success(nodeUri) =>
        (Try(CoinUtils.convertStringAmountToSat(fundingSat.getText, fundingUnit.getValue)),
        Try(if (Strings.isNullOrEmpty(pushMsatField.getText())) 0L else pushMsatField.getText().toLong),
        Try(if (Strings.isNullOrEmpty(feerateField.getText())) None else Some(feerateField.getText().toLong))) match {
        case (Success(capacitySat), _, _) if capacitySat.amount <= 0 =>
          fundingSatError.setText("Capacity must be greater than 0")
        case (Success(capacitySat), _, _) if capacitySat.amount >= Channel.MAX_FUNDING_SATOSHIS =>
          fundingSatError.setText(s"Capacity must be less than ${CoinUtils.formatAmountInUnit(Satoshi(Channel.MAX_FUNDING_SATOSHIS), FxApp.getUnit, withUnit = true)}")
        case (Success(capacitySat), Success(pushMsat), _) if pushMsat > satoshi2millisatoshi(capacitySat).amount =>
          pushMsatError.setText("Push must be less or equal to capacity")
        case (Success(_), Success(pushMsat), _) if pushMsat < 0 =>
          pushMsatError.setText("Push must be positive")
        case (Success(_), Success(_), Success(Some(feerate))) if feerate <= 0 =>
          feerateError.setText("Fee rate must be greater than 0")
        case (Success(capacitySat), Success(pushMsat), Success(feeratePerByte_opt)) =>
          val channelFlags = if (publicChannel.isSelected) ChannelFlags.AnnounceChannel else ChannelFlags.Empty
          handlers.open(nodeUri, Some(Peer.OpenChannel(nodeUri.nodeId, capacitySat, MilliSatoshi(pushMsat), feeratePerByte_opt.map(fr.acinq.eclair.feerateByte2Kw), Some(channelFlags))))
          stage.close()
        case (Failure(t), _, _) =>
          logger.error(s"could not parse capacity with cause=${t.getLocalizedMessage}")
          fundingSatError.setText("Capacity is not valid")
        case (_, Failure(t), _) =>
          logger.error(s"could not parse push amount with cause=${t.getLocalizedMessage}")
          pushMsatError.setText("Push amount is not valid")
        case (_, _, Failure(t)) =>
          logger.error(s"could not parse fee rate with cause=${t.getLocalizedMessage}")
          feerateError.setText("Fee rate is not valid")
      }
      case Failure(t) => logger.error(s"could not parse node uri with cause=${t.getLocalizedMessage}")
        hostError.setText(t.getLocalizedMessage)
    }
  }

  private def clearErrors() {
    hostError.setText("")
    fundingSatError.setText("")
    pushMsatError.setText("")
    feerateError.setText("")
  }

  @FXML def handleClose(event: ActionEvent) = stage.close()
}
