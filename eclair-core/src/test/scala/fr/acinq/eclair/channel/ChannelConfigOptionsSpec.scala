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

import fr.acinq.eclair.channel.ChannelConfigOptions._
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.{ByteVector, HexStringSyntax}

class ChannelConfigOptionsSpec extends AnyFunSuiteLike {

  test("channel key path based on funding public key") {
    assert(!ChannelConfigOptions(Set.empty[ChannelConfigOption]).hasOption(FundingPubKeyBasedChannelKeyPath))
    assert(ChannelConfigOptions.standard.hasOption(FundingPubKeyBasedChannelKeyPath))
    assert(ChannelConfigOptions(FundingPubKeyBasedChannelKeyPath).hasOption(FundingPubKeyBasedChannelKeyPath))
  }

  test("channel configuration options to bytes") {
    assert(ChannelConfigOptions(Set.empty[ChannelConfigOption]).bytes === ByteVector.empty)
    assert(ChannelConfigOptions(ByteVector.empty) === ChannelConfigOptions(Set.empty[ChannelConfigOption]))
    assert(ChannelConfigOptions(hex"f0") === ChannelConfigOptions(Set.empty[ChannelConfigOption]))
    assert(ChannelConfigOptions(hex"0000") === ChannelConfigOptions(Set.empty[ChannelConfigOption]))

    assert(ChannelConfigOptions.standard.bytes === hex"01")
    assert(ChannelConfigOptions(FundingPubKeyBasedChannelKeyPath).bytes === hex"01")
    assert(ChannelConfigOptions(hex"01") === ChannelConfigOptions(FundingPubKeyBasedChannelKeyPath))
    assert(ChannelConfigOptions(hex"ff") === ChannelConfigOptions(FundingPubKeyBasedChannelKeyPath))
    assert(ChannelConfigOptions(hex"0001") === ChannelConfigOptions(FundingPubKeyBasedChannelKeyPath))
  }

}
