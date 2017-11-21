package fr.acinq.eclair.gui.controllers

import javafx.application.HostServices
import javafx.fxml.FXML
import javafx.scene.text.Text

import grizzled.slf4j.Logging

/**
  * Created by DPA on 28/09/2016.
  */
class AboutController(hostServices: HostServices) extends Logging {

  @FXML var version: Text = _

  @FXML def initialize = {
    version.setText(getClass.getPackage.getImplementationVersion)
  }

  @FXML def openApacheLicencePage = hostServices.showDocument("https://www.apache.org/licenses/LICENSE-2.0")

  @FXML def openACINQPage = hostServices.showDocument("https://acinq.co")

  @FXML def openGithubPage = hostServices.showDocument("https://github.com/ACINQ/eclair")

  @FXML def openLNRFCPage = hostServices.showDocument("https://github.com/lightningnetwork/lightning-rfc")
}
