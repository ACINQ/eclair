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

package fr.acinq.eclair.wire.internal.channel

import fr.acinq.eclair.channel._
import fr.acinq.eclair.wire.internal.channel.version0.ChannelCodecs0
import fr.acinq.eclair.wire.internal.channel.version1.ChannelCodecs1
import grizzled.slf4j.Logging
import scodec.Codec
import scodec.codecs._

/**
 * Codecs used to store the internal channel data.
 *
 * The ability to safely migrate from one version to another one is of the utmost importance, which is why the following
 * rules need to be respected:
 *
 * 1) [[ChannelCodecs]] is the only publicly accessible class. It handles compatibility between different versions
 * of the codecs.
 *
 * 2) Each codec version must be in its separate package, and have the following structure:
 * {{{
 *   private[channel] object ChannelCodecs0 {

       private[version0] object Codecs {

         // internal codecs

       }

       import Codecs._

       val STATE_Codec: Codec[STATE] = ...
 * }}}
 *
 * Notice that the outer class has a visibility restricted to package [[channel]], while the inner class has a
 * visibility restricted to package [[version0]]. This guarantees that we strictly segregate each codec version,
 * while still allowing unitary testing.
 *
 * Created by PM on 02/06/2017.
 */
object ChannelCodecs extends Logging {

  /**
   * Order matters!!
   *
   * We use the fact that the discriminated codec encodes using the first suitable codec it finds in the list to handle
   * database migration.
   *
   * More info here: https://github.com/scodec/scodec/issues/122
   */
  val stateDataCodec: Codec[HasCommitments] = discriminated[HasCommitments].by(byte)
    .typecase(1, discriminated[HasCommitments].by(uint16)
      .typecase(0x20, ChannelCodecs1.DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec)
      .typecase(0x21, ChannelCodecs1.DATA_WAIT_FOR_FUNDING_LOCKED_Codec)
      .typecase(0x22, ChannelCodecs1.DATA_NORMAL_Codec)
      .typecase(0x23, ChannelCodecs1.DATA_SHUTDOWN_Codec)
      .typecase(0x24, ChannelCodecs1.DATA_NEGOTIATING_Codec)
      .typecase(0x25, ChannelCodecs1.DATA_CLOSING_Codec)
      .typecase(0x26, ChannelCodecs1.DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec))
    .typecase(0, discriminated[HasCommitments].by(uint16)
      .typecase(0x10, ChannelCodecs0.DATA_NORMAL_Codec)
      .typecase(0x09, ChannelCodecs0.DATA_CLOSING_Codec)
      .typecase(0x08, ChannelCodecs0.DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec)
      .typecase(0x01, ChannelCodecs0.DATA_WAIT_FOR_FUNDING_CONFIRMED_COMPAT_01_Codec)
      .typecase(0x02, ChannelCodecs0.DATA_WAIT_FOR_FUNDING_LOCKED_Codec)
      .typecase(0x03, ChannelCodecs0.DATA_NORMAL_COMPAT_03_Codec)
      .typecase(0x04, ChannelCodecs0.DATA_SHUTDOWN_Codec)
      .typecase(0x05, ChannelCodecs0.DATA_NEGOTIATING_Codec)
      .typecase(0x06, ChannelCodecs0.DATA_CLOSING_COMPAT_06_Codec)
      .typecase(0x07, ChannelCodecs0.DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec))

}
