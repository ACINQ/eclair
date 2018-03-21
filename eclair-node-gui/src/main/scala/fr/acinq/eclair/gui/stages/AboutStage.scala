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

import javafx.application.HostServices
import javafx.event.EventHandler
import javafx.fxml.FXMLLoader
import javafx.scene.image.Image
import javafx.scene.input.KeyCode._
import javafx.scene.input.KeyEvent
import javafx.scene.{Parent, Scene}
import javafx.stage.{Modality, Stage, StageStyle}

import fr.acinq.eclair.gui.controllers.AboutController

/**
  * Created by DPA on 28/09/2016.
  */
class AboutStage(hostServices: HostServices) extends Stage() {
  initModality(Modality.WINDOW_MODAL)
  initStyle(StageStyle.DECORATED)
  getIcons().add(new Image("/gui/commons/images/eclair-square.png", false))
  setTitle("About Eclair")
  setResizable(false)
  setWidth(500)
  setHeight(200)

  // get fxml/controller
  val openFXML = new FXMLLoader(getClass.getResource("/gui/modals/about.fxml"))
  openFXML.setController(new AboutController(hostServices))
  val root = openFXML.load[Parent]

  // create scene
  val scene = new Scene(root)

  val self = this
  scene.addEventFilter(KeyEvent.KEY_PRESSED, new EventHandler[KeyEvent]() {
    def handle(event: KeyEvent) = {
      event.getCode match {
        case ESCAPE =>
          self.close
        case _ =>
      }
    }
  })

  setScene(scene)
}
