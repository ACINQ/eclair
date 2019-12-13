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

package fr.acinq.eclair.wire

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.wire.CommonCodecs._
import scodec.Codec
import scodec.codecs.{discriminated, list, variableSizeBytesLong}

/**
 * Created by t-bast on 13/12/2019.
 */

/** Tlv types used inside Init messages. */
sealed trait InitTlv extends Tlv

object InitTlv {

  /** The chains the node is interested in. */
  case class Networks(chainHashes: List[ByteVector32]) extends InitTlv

}

object InitTlvCodecs {

  import InitTlv._

  // TODO: wire test-cases:
  //  * Init not containing any tlv stream
  //  * Init containing tlv stream without networks (other odd records)
  //  * Init containing tlv stream without networks (other even records)
  //  * Init containing tlv stream with networks only
  //  * Init containing tlv stream with networks and other odd records)
  //  * Init containing tlv stream with networks and other even records)

  // TODO:
  //  * Add to the Init message after flat features merged
  //  * Send the chainHash from nodeParams when creating Init
  //  * Add logic to Peer.scala to fail connections to others that don't offer my chainHash

  private val networks: Codec[Networks] = variableSizeBytesLong(varintoverflow, list(bytes32)).as[Networks]

  val initTlvCodec = discriminated[InitTlv].by(varint)
    .typecase(UInt64(1), networks)

}