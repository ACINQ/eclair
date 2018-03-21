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

package fr.acinq.eclair.gui.controllers

import javafx.animation._
import javafx.application.HostServices
import javafx.fxml.FXML
import javafx.scene.control.{Button, Label}
import javafx.scene.image.ImageView
import javafx.scene.layout.{Pane, VBox}
import javafx.util.Duration

import grizzled.slf4j.Logging

/**
  * Created by DPA on 22/09/2016.
  */
class SplashController(hostServices: HostServices) extends Logging {

  @FXML var splash: Pane = _
  @FXML var img: ImageView = _
  @FXML var imgBlurred: ImageView = _
  @FXML var closeButton: Button = _
  @FXML var errorBox: VBox = _
  @FXML var logBox: VBox = _

  /**
    * Start an animation when the splash window is initialized
    */
  @FXML def initialize = {
    val timeline = new Timeline(
      new KeyFrame(Duration.ZERO,
        new KeyValue(img.opacityProperty, double2Double(0), Interpolator.EASE_IN),
        new KeyValue(imgBlurred.opacityProperty, double2Double(1.0), Interpolator.EASE_IN)),
      new KeyFrame(Duration.millis(1000.0d),
        new KeyValue(img.opacityProperty, double2Double(1.0), Interpolator.EASE_OUT),
        new KeyValue(imgBlurred.opacityProperty, double2Double(0), Interpolator.EASE_OUT)))
    timeline.play()
  }

  @FXML def closeAndKill = System.exit(0)

  @FXML def openGithubPage = hostServices.showDocument("https://github.com/ACINQ/eclair/blob/master/README.md")

  def addLog(message: String) = {
    val l = new Label
    l.setText(message)
    l.setWrapText(true)
    logBox.getChildren.add(l)
  }

  def addError(message: String) = {
    val l = new Label
    l.setText(message)
    l.setWrapText(true)
    l.getStyleClass.add("text-error")
    logBox.getChildren.add(l)
  }

  /**
    * Shows the error Box with a fade+translate transition.
    */
  def showErrorBox = {
    val fadeTransition = new FadeTransition(Duration.millis(400))
    fadeTransition.setFromValue(0)
    fadeTransition.setToValue(1)
    val translateTransition = new TranslateTransition(Duration.millis(500))
    translateTransition.setFromY(20)
    translateTransition.setToY(0)
    val t = new ParallelTransition(errorBox, fadeTransition, translateTransition)
    t.play
  }
}