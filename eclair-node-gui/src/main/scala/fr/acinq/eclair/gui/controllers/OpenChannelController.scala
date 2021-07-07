/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.gui.controllers

import com.google.common.base.Strings
import fr.acinq.bitcoin.SatoshiLong
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.channel.ChannelFlags
import fr.acinq.eclair.gui.utils.Constants
import fr.acinq.eclair.gui.{FxApp, Handlers}
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.{CoinUtils, MilliSatoshi, _}
import grizzled.slf4j.Logging
import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.control._
import javafx.stage.Stage

import java.lang.Boolean
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
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

    handlers.getFundingFeeRatePerKb().onComplete {
      case Success(feeSatKb) =>
        feerateField.setText(FeeratePerByte(feeSatKb.feerate / 1000).toString)
        feerateError.setText("")
      case Failure(t) =>
        logger.error(s"error when estimating funding fee from GUI: ${t.getLocalizedMessage}")
        feerateField.setText("")
        feerateError.setText("Could not estimate fees.")
    } (ExecutionContext.Implicits.global)

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
          case Success(capacitySat) if capacitySat <= 0.sat =>
            fundingSatError.setText("Capacity must be greater than 0")
          case Success(capacitySat) if capacitySat < 50000.sat =>
            fundingSatError.setText("Capacity is low and the channel may not be able to open")
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
        case (Success(capacitySat), _, _) if capacitySat <= 0.sat =>
          fundingSatError.setText("Capacity must be greater than 0")
        case (Success(capacitySat), Success(pushMsat), _) if pushMsat > capacitySat.toMilliSatoshi.toLong =>
          pushMsatError.setText("Push must be less or equal to capacity")
        case (Success(_), Success(pushMsat), _) if pushMsat < 0 =>
          pushMsatError.setText("Push must be positive")
        case (Success(_), Success(_), Success(Some(feerate))) if feerate <= 0 =>
          feerateError.setText("Fee rate must be greater than 0")
        case (Success(capacitySat), Success(pushMsat), Success(feeratePerByte_opt)) =>
          val channelFlags = if (publicChannel.isSelected) ChannelFlags.AnnounceChannel else ChannelFlags.Empty
          handlers.open(nodeUri, Some(Peer.OpenChannel(nodeUri.nodeId, capacitySat, MilliSatoshi(pushMsat), None, feeratePerByte_opt.map(f => FeeratePerKw(FeeratePerByte(f.sat))), None, Some(channelFlags), Some(30 seconds))))
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
