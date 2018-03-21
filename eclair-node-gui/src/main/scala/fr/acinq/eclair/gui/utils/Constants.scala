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

import javafx.collections.FXCollections

import fr.acinq.eclair.{BtcUnit, MBtcUnit, MSatUnit, SatUnit}

object Constants {
  val FX_UNITS_ARRAY_NO_MSAT = FXCollections.observableArrayList(SatUnit.label, MBtcUnit.label, BtcUnit.label)
  val FX_UNITS_ARRAY = FXCollections.observableArrayList(MSatUnit.label, SatUnit.label, MBtcUnit.label, BtcUnit.label)
}
