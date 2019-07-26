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

package fr.acinq.eclair

import java.net.{InetAddress, InetSocketAddress, ServerSocket}

import scala.util.{Failure, Success, Try}

object PortChecker {

  /**
    * Tests if a port is open
    * See https://stackoverflow.com/questions/434718/sockets-discover-port-availability-using-java#435579
    *
    * @return
    */
  def checkAvailable(host: String, port: Int): Unit = checkAvailable(InetAddress.getByName(host), port)

  def checkAvailable(socketAddress: InetSocketAddress): Unit = checkAvailable(socketAddress.getAddress, socketAddress.getPort)

  def checkAvailable(address: InetAddress, port: Int): Unit = {
    Try(new ServerSocket(port, 50, address)) match {
      case Success(socket) =>
        Try(socket.close())
      case Failure(_) =>
        throw TCPBindException(port)
    }
  }

}

case class TCPBindException(port: Int) extends RuntimeException(s"could not bind to port $port")