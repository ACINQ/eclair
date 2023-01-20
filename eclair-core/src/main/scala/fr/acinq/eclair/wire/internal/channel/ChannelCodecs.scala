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

import fr.acinq.eclair.channel.PersistentChannelData
import fr.acinq.eclair.wire.internal.channel.version0.ChannelCodecs0
import fr.acinq.eclair.wire.internal.channel.version1.ChannelCodecs1
import fr.acinq.eclair.wire.internal.channel.version2.ChannelCodecs2
import fr.acinq.eclair.wire.internal.channel.version3.ChannelCodecs3
import fr.acinq.eclair.wire.internal.channel.version4.ChannelCodecs4
import grizzled.slf4j.Logging
import scodec.Codec
import scodec.codecs.{byte, discriminated}

// @formatter:off
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

       val channelDataCodec: Codec[PersistentChannelData] = ...
 * }}}
 *
 * Notice that the outer class has a visibility restricted to package [[fr.acinq.eclair.wire.internal.channel]], while the inner class has a
 * visibility restricted to package [[version0]]. This guarantees that we strictly segregate each codec version,
 * while still allowing unitary testing.
 *
 * Created by PM on 02/06/2017.
 */
// @formatter:on
object ChannelCodecs extends Logging {

  /**
   * Order matters!!
   *
   * We use the fact that the discriminated codec encodes using the first suitable codec it finds in the list to handle
   * database migration.
   *
   * More info here: https://github.com/scodec/scodec/issues/122
   */
  val channelDataCodec: Codec[PersistentChannelData] = discriminated[PersistentChannelData].by(byte)
    .typecase(4, ChannelCodecs4.channelDataCodec)
    .typecase(3, ChannelCodecs3.channelDataCodec.decodeOnly)
    .typecase(2, ChannelCodecs2.channelDataCodec.decodeOnly)
    .typecase(1, ChannelCodecs1.channelDataCodec.decodeOnly)
    .typecase(0, ChannelCodecs0.channelDataCodec.decodeOnly)

}
