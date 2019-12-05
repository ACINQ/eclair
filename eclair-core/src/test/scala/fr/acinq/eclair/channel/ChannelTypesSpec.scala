/*
 * Copyright 2019 ACINQ SAS
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

import org.scalatest.FunSuite
import scodec.bits._

class ChannelTypesSpec extends FunSuite {
  test("standard channel features include deterministic channel key path") {
    assert(ChannelVersion.STANDARD.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
    assert(!ChannelVersion.ZEROES.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
  }

  test("channel version") {
    assert(ChannelVersion.STANDARD.bits === bin"00000000 00000000 00000000 00000001") // USE_PUBKEY_KEYPATH_BIT is enabled by default
    assert((ChannelVersion.STANDARD | ChannelVersion.ZERO_RESERVE).bits === bin"00000000 00000000 00000000 00001001")

    assert(ChannelVersion.STANDARD.isSet(ChannelVersion.ZERO_RESERVE_BIT) === false)
    assert((ChannelVersion.STANDARD | ChannelVersion.ZERO_RESERVE).isSet(ChannelVersion.ZERO_RESERVE_BIT) === true)
  }
}
