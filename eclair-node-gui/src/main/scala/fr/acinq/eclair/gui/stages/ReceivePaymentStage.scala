/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.gui.stages

import javafx.fxml.FXMLLoader
import javafx.scene.image.Image
import javafx.scene.{Parent, Scene}
import javafx.stage.{Modality, Stage, StageStyle}

import fr.acinq.eclair.gui.Handlers
import fr.acinq.eclair.gui.controllers.ReceivePaymentController

/**
  * Created by PM on 16/08/2016.
  */
class ReceivePaymentStage(handlers: Handlers) extends Stage() {
  initModality(Modality.WINDOW_MODAL)
  initStyle(StageStyle.DECORATED)
  getIcons().add(new Image("/gui/commons/images/eclair-square.png", false))
  setTitle("Receive a Payment")
  setMinWidth(590)
  setWidth(590)
  setMinHeight(200)
  setHeight(200)
  setResizable(false)

  // get fxml/controller
  val receivePayment = new FXMLLoader(getClass.getResource("/gui/modals/receivePayment.fxml"))
  receivePayment.setController(new ReceivePaymentController(handlers, this))
  val root = receivePayment.load[Parent]

  // create scene
  val scene = new Scene(root)
  setScene(scene)
}
