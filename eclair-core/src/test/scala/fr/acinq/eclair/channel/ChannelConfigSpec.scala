/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.channel

import fr.acinq.eclair.channel.ChannelConfig._
import org.scalatest.funsuite.AnyFunSuiteLike

class ChannelConfigSpec extends AnyFunSuiteLike {

  test("channel key path based on funding public key") {
    assert(!ChannelConfig(Set.empty[ChannelConfigOption]).hasOption(FundingPubKeyBasedChannelKeyPath))
    assert(ChannelConfig.standard.hasOption(FundingPubKeyBasedChannelKeyPath))
    assert(ChannelConfig(FundingPubKeyBasedChannelKeyPath).hasOption(FundingPubKeyBasedChannelKeyPath))
  }

}
