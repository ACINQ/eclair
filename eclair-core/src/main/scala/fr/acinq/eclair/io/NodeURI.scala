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

package fr.acinq.eclair.io

import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.wire.protocol.NodeAddress
import scodec.bits.ByteVector

import scala.util.{Failure, Success, Try}

case class NodeURI(nodeId: PublicKey, address: NodeAddress) {
  override def toString: String = s"$nodeId@$address"
}

object NodeURI {

  val DEFAULT_PORT = 9735

  /**
   * Extracts the PublicKey and InetAddress from a string URI (format pubkey@host:port). Port is optional, default is 9735.
   *
   * @param uri uri of a node, as a String
   * @throws IllegalArgumentException if the uri is not valid and can not be read
   * @return a NodeURI
   */
  @throws[IllegalArgumentException]
  def parse(uri: String): NodeURI = {
    uri.split("@") match {
      case Array(nodeId, address) => (Try(PublicKey(ByteVector.fromValidHex(nodeId))), Try(HostAndPort.fromString(address)).flatMap(hostAndPort => NodeAddress.fromParts(hostAndPort.getHost, hostAndPort.getPortOrDefault(DEFAULT_PORT)))) match {
        case (Success(pk), Success(nodeAddress)) => NodeURI(pk, nodeAddress)
        case (Failure(t), _) => throw new IllegalArgumentException("Invalid node id", t)
        case (_, Failure(t)) => throw new IllegalArgumentException("Invalid host:port", t)
      }
      case _ => throw new IllegalArgumentException("Invalid uri, should be nodeId@host:port")
    }
  }
}
