package fr.acinq.eclair.gui.utils

import javafx.scene.control.Label

import scala.util.matching.Regex

/**
  * Created by DPA on 27/09/2016.
  */
object GUIValidators {
  val hostRegex = """([a-fA-F0-9]{66})@([a-zA-Z0-9:\[\]%\/\.\-_]+)""".r
  val amountRegex = """\d+""".r
  val amountDecRegex = """(\d+)|(\d*\.[\d]{1,})""".r
  val hexRegex = """[0-9a-fA-F]+""".r
  val intRegex = """([\-]?\d+)""".r

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

  /**
    * Validate host field against a hostRegex.
    *
    * @param field            String content of the field to validate
    * @param validatorLabel   JFX label associated to the field.
    * @param validatorMessage Message displayed if the field is invalid. It should describe the cause of
    *                         the validation failure
    * @param regex            Scala regex that the field must match
    * @return true if field is valid, false otherwise
    */
  def validateHost(field: String, validatorLabel: Label, validatorMessage: String, regex: Regex): Boolean = {
    return field match {
      case regex(_*) => {
        // Make sure port is a positive Int below 65536.
        val split_field = field.split(":")
        split_field.length match {
          // No port
          case 1 => {
            validate(validatorLabel, validatorMessage, true)
          }
          case _ => {
            // Port is at the end.
            split_field.last match {
              case intRegex(portString) => {
                val portNumber = portString.toInt
                if (portNumber < 0 || portNumber > 65535) {
                  // Port out of range.
                  validate(validatorLabel, validatorMessage, false)
                } else {
                  // Valid port.
                  validate(validatorLabel, validatorMessage, true)
                }
              }
              case _ => {
                // Not an Int but possibly part of an IPv6 address.
                validate(validatorLabel, validatorMessage, true)
              }
            }  
          }
        }
      }
      case _ => validate(validatorLabel, validatorMessage, false)
    }
  }
}
