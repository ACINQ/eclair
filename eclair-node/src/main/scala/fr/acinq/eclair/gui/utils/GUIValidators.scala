package fr.acinq.eclair.gui.utils

import javafx.scene.control.Label

import scala.util.matching.Regex

/**
  * Created by DPA on 27/09/2016.
  */
object GUIValidators {

  val hostRegex = """[a-zA-Z0-9.]+:[0-9]{1,5}""".r
  val amountRegex = """[0-9]+""".r
  val paymentRequestRegex = """[a-zA-Z0-9]+:[a-zA-Z0-9]+:[a-zA-Z0-9]+""".r
  val hexRegex = """[0-9a-fA-F]+""".r

  /**
    * Validate a field against a regex. If field does not match regex, validatorLabel is shown.
    *
    * @param field          String content of the field to validate
    * @param validatorLabel JFX label associated to the field
    * @param regex          Scala regex that the field must match
    * @return true if field is valid, false otherwise
    */
  def validate(field: String, validatorLabel: Label, regex: Regex): Boolean = {

    return field match {
      case regex() => {
        validatorLabel.setOpacity(0)
        return true
      }
      case _ => {
        validatorLabel.setOpacity(1)
        return false
      }
    }
  }
}
