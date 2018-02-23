package fr.acinq.eclair.gui.utils

import javafx.collections.FXCollections

import fr.acinq.eclair.{BtcUnit, MBtcUnit, MSatUnit, SatUnit}

object Constants {
  val FX_UNITS_ARRAY_NO_MSAT = FXCollections.observableArrayList(SatUnit.label, MBtcUnit.label, BtcUnit.label)
  val FX_UNITS_ARRAY = FXCollections.observableArrayList(MSatUnit.label, SatUnit.label, MBtcUnit.label, BtcUnit.label)
}
