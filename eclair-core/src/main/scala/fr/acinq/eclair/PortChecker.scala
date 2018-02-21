package fr.acinq.eclair

import java.net.{InetAddress, ServerSocket}

import scala.util.{Failure, Success, Try}

object PortChecker {

  /**
    * Tests if a port is open
    * See https://stackoverflow.com/questions/434718/sockets-discover-port-availability-using-java#435579
    *
    * @return
    */
  def checkAvailable(host: String, port: Int): Unit = {
    Try(new ServerSocket(port, 50, InetAddress.getByName(host))) match {
      case Success(socket) =>
        Try(socket.close())
      case Failure(_) =>
        throw TCPBindException(port)
    }
  }

}

case class TCPBindException(port: Int) extends RuntimeException(s"could not bind to port $port")