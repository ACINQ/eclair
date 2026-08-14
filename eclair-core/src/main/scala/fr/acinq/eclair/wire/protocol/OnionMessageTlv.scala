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

package fr.acinq.eclair.wire.protocol

import scodec.Codec
import scodec.codecs.provide

/**
 * Created by thomash on 10/09/2021.
 */

sealed trait OnionMessageTlv extends Tlv

object OnionMessageTlv {
  // We don't support any TLV for onion messages yet. Since onion messages can be spammy, we don't need to waste any
  // ressources trying to decode unknown TLVs that we'll throw away anyway.
  val onionMessageTlvCodec: Codec[TlvStream[OnionMessageTlv]] = provide(TlvStream.empty[OnionMessageTlv])
}
