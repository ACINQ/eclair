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
import fr.acinq.eclair.wire.internal.channel.version5.ChannelCodecs5
import grizzled.slf4j.Logging
import scodec.codecs.{byte, discriminated, fail}
import scodec.{Codec, Err}

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
 * visibility restricted to package [[version5]]. This guarantees that we strictly segregate each codec version,
 * while still allowing unitary testing.
 *
 * Created by PM on 02/06/2017.
 */
// @formatter:on
object ChannelCodecs extends Logging {

  /**
   * Codecs v0 to v4 have been removed after the eclair v0.13 release.
   * Users on older version will need to first run the v0.13 release before updating to a newer version.
   */
  private val pre013FailingCodec: Codec[PersistentChannelData] = fail(Err("You are updating from a version of eclair older than v0.13: please update to the v0.13 release first to migrate your channel data, and afterwards you'll be able to update to the latest version."))

  /**
   * Order matters!!
   *
   * We use the fact that the discriminated codec encodes using the first suitable codec it finds in the list to handle
   * database migration.
   *
   * More info here: https://github.com/scodec/scodec/issues/122
   */
  val channelDataCodec: Codec[PersistentChannelData] = discriminated[PersistentChannelData].by(byte)
    .typecase(5, ChannelCodecs5.channelDataCodec)
    .typecase(4, pre013FailingCodec)
    .typecase(3, pre013FailingCodec)
    .typecase(2, pre013FailingCodec)
    .typecase(1, pre013FailingCodec)
    .typecase(0, pre013FailingCodec)

}
