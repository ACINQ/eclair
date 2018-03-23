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

package fr.acinq.eclair.gui.utils

import javafx.scene.control.Label

import scala.util.matching.Regex

/**
  * Created by DPA on 27/09/2016.
  */
object GUIValidators {
  val amountDecRegex = """(\d+)|(\d*\.[\d]{1,})""".r

  /**
    * Validate a field against a regex. If field does not match the regex, validatorLabel is shown.
    *
    * @param field            String content of the field to validate
    * @param validatorLabel   JFX label associated to the field.
    * @param validatorMessage Message displayed if the field is invalid. It should describe the cause of
    *                         the validation failure
    * @param regex            Scala regex that the field must match
    * @return true if field is valid, false otherwise
    */
  def validate(field: String, validatorLabel: Label, validatorMessage: String, regex: Regex): Boolean = {
    return field match {
      case regex(_*) => validate(validatorLabel, validatorMessage, true)
      case _ => validate(validatorLabel, validatorMessage, false)
    }
  }

  /**
    * Displays a label with an error message.
    *
    * @param errorLabel     JFX label containing an error message
    * @param validCondition if true the label is hidden, else it is shown
    * @return true if field is valid, false otherwise
    */
  def validate(errorLabel: Label, errorMessage: String, validCondition: Boolean): Boolean = {
    errorLabel.setOpacity(if (validCondition) 0 else 1)
    errorLabel.setText(errorMessage)
    validCondition
  }
}
