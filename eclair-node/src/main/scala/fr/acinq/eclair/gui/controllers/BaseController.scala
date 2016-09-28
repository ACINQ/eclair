package fr.acinq.eclair.gui.controllers

import javafx.stage.Stage

import fr.acinq.eclair.Setup
import fr.acinq.eclair.gui.Handlers

/**
  * Created by DPA on 23/09/2016.
  *
  * Controller interacting with Eclair services should implement this.
  */
trait BaseController {
  val handlers: Handlers;
  val stage: Stage;
  val setup: Setup
}
