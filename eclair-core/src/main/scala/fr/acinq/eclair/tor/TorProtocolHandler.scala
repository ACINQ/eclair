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

package fr.acinq.eclair.tor

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash}
import akka.io.Tcp.Connected
import akka.util.ByteString
import fr.acinq.eclair.tor.TorProtocolHandler.Authentication
import fr.acinq.eclair.wire.protocol.{NodeAddress, Tor3}
import scodec.bits.Bases.Alphabets
import scodec.bits.ByteVector

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
import java.util
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.Promise
import scala.util.Try

case class TorException(private val msg: String) extends RuntimeException(s"Tor error: $msg")

/**
  * Created by rorp
  *
  * Specification: https://gitweb.torproject.org/torspec.git/tree/control-spec.txt
  *
  * @param authentication      Tor controller auth mechanism (password or safecookie)
  * @param privateKeyPath      path to a file that contains a Tor private key
  * @param virtualPort         port for the public hidden service (typically 9735)
  * @param targets             address of our protected server (format [host:]port), 127.0.0.1:[[virtualPort]] if empty
  * @param onionAdded          a Promise to track creation of the endpoint
  */
class TorProtocolHandler(authentication: Authentication,
                         privateKeyPath: Path,
                         virtualPort: Int,
                         targets: Seq[String],
                         onionAdded: Option[Promise[NodeAddress]]
                        ) extends Actor with Stash with ActorLogging {

  import TorProtocolHandler._

  private var receiver: ActorRef = _

  private var address: Option[NodeAddress] = None

  override def receive: Receive = {
    case Connected(_, _) =>
      receiver = sender()
      sendCommand("PROTOCOLINFO 1")
      context become protocolInfo
  }

  def protocolInfo: Receive = {
    case data: ByteString =>
      val res = parseResponse(readResponse(data))
      val methods: String = res.getOrElse("METHODS", throw TorException("auth methods not found"))
      val torVersion = unquote(res.getOrElse("Tor", throw TorException("version not found")))
      log.info(s"Tor version $torVersion")
      if (!isCompatible(torVersion)) {
        throw TorException(s"unsupported Tor version: $torVersion")
      }
      if (!Authentication.isCompatible(authentication, methods)) {
        throw TorException(s"cannot use authentication '$authentication', supported methods are '$methods'")
      }
      authentication match {
        case Password(password) =>
          sendCommand(s"""AUTHENTICATE "$password"""")
          context become authenticate
        case SafeCookie(nonce) =>
          val cookieFile = Paths.get(unquote(res.getOrElse("COOKIEFILE", throw TorException("cookie file not found"))))
          sendCommand(s"AUTHCHALLENGE SAFECOOKIE ${nonce.toHex}")
          context become cookieChallenge(cookieFile, nonce)
      }
  }

  def cookieChallenge(cookieFile: Path, clientNonce: ByteVector): Receive = {
    case data: ByteString =>
      val res = parseResponse(readResponse(data))
      val clientHash = computeClientHash(
        ByteVector.fromValidHex(res.getOrElse("SERVERHASH", throw TorException("server hash not found")).toLowerCase, alphabet = Alphabets.HexLowercase),
        ByteVector.fromValidHex(res.getOrElse("SERVERNONCE", throw TorException("server nonce not found")).toLowerCase, alphabet = Alphabets.HexLowercase),
        clientNonce,
        cookieFile
      )
      sendCommand(s"AUTHENTICATE ${clientHash.toHex}")
      context become authenticate
  }

  def authenticate: Receive = {
    case data: ByteString =>
      readResponse(data)
      sendCommand(s"ADD_ONION $computeKey $computePort")
      context become addOnion
  }

  def addOnion: Receive = {
    case data: ByteString =>
      val res = readResponse(data)
      if (ok(res)) {
        val serviceId = processOnionResponse(parseResponse(res))
        address = Some(Tor3(serviceId, virtualPort))
        onionAdded.foreach(_.success(address.get))
        log.debug("Onion address: {}", address.get)
      }
  }


  override def aroundReceive(receive: Receive, msg: Any): Unit = try {
    super.aroundReceive(receive, msg)
  } catch {
    case t: Throwable => onionAdded.map(_.tryFailure(t))
  }

  override def unhandled(message: Any): Unit = message match {
    case GetOnionAddress =>
      sender() ! address
  }

  private def processOnionResponse(res: Map[String, String]): String = {
    val serviceId = res.getOrElse("ServiceID", throw TorException("service ID not found"))
    val privateKey = res.get("PrivateKey")
    privateKey.foreach { pk =>
      writeString(privateKeyPath, pk)
      setPermissions(privateKeyPath, "rw-------")
    }
    serviceId
  }

  private def computeKey: String = {
    if (privateKeyPath.toFile.exists()) {
      readString(privateKeyPath)
    } else {
      "NEW:ED25519-V3"
    }
  }

  private def computePort: String = {
    if (targets.isEmpty) {
      s"Port=$virtualPort,$virtualPort"
    } else {
      targets.map(p => s"Port=$virtualPort,$p").mkString(" ")
    }
  }

  private def computeClientHash(serverHash: ByteVector, serverNonce: ByteVector, clientNonce: ByteVector, cookieFile: Path): ByteVector = {
    if (serverHash.length != 32)
      throw TorException("invalid server hash length")
    if (serverNonce.length != 32)
      throw TorException("invalid server nonce length")

    val cookie = ByteVector.view(Files.readAllBytes(cookieFile))

    val message = cookie ++ clientNonce ++ serverNonce

    val computedServerHash = hmacSHA256(ServerKey, message)
    if (computedServerHash != serverHash) {
      throw TorException("unexpected server hash")
    }

    hmacSHA256(ClientKey, message)
  }

  private def sendCommand(cmd: String): Unit = {
    receiver ! ByteString(s"$cmd\r\n")
  }
}

object TorProtocolHandler {
  def props(authentication: Authentication,
            privateKeyPath: Path,
            virtualPort: Int,
            targets: Seq[String] = Seq(),
            onionAdded: Option[Promise[NodeAddress]] = None
           ): Props =
    Props(new TorProtocolHandler(authentication, privateKeyPath, virtualPort, targets, onionAdded))

  // those are defined in the spec
  private val ServerKey = ByteVector.view("Tor safe cookie authentication server-to-controller hash".getBytes())
  private val ClientKey = ByteVector.view("Tor safe cookie authentication controller-to-server hash".getBytes())


  private[tor] def isCompatible(torVersion: String): Boolean =
    torVersion
      .split("\\.")
      .map(_.split('-').head) // remove non-numeric symbols at the end of the last number (rc, beta, alpha, etc.)
      .map(d => Try(d.toInt).getOrElse(0))
      .zipAll(List(0, 3, 3, 6), 0, 0) // min version for v3 is 0.3.3.6
      .foldLeft(Option.empty[Boolean]) { // compare subversion by subversion starting from the left
        case (Some(res), _) => Some(res) // we stop the comparison as soon as there is a difference
        case (None, (v, vref)) => if (v > vref) Some(true) else if (v < vref) Some(false) else None
      }
      .getOrElse(true) // if version == 0.3.3.6 then result will be None

  // @formatter:off
  sealed trait Authentication
  case class Password(password: String) extends Authentication { override def toString = "password" }
  case class SafeCookie(nonce: ByteVector = fr.acinq.eclair.randomBytes32()) extends Authentication { override def toString = "safecookie" }
  // @formatter:on

  object Authentication {
    def isCompatible(authentication: Authentication, methods: String): Boolean =
      authentication match {
        case _: Password => methods.contains("HASHEDPASSWORD")
        case _: SafeCookie => methods.contains("SAFECOOKIE")
      }
  }

  case object GetOnionAddress

  def readString(path: Path): String = Files.readAllLines(path).get(0)

  def writeString(path: Path, string: String): Unit = Files.write(path, util.Arrays.asList(string))

  def setPermissions(path: Path, permissionString: String): Unit =
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissionString))
    } catch {
      case _: UnsupportedOperationException => () // we are on windows
    }

  def unquote(s: String): String = s
    .stripSuffix("\"")
    .stripPrefix("\"")
    .replace("""\\""", """\""")
    .replace("""\"""", "\"")

  private val r1 = """(\d+)\-(.*)""".r
  private val r2 = """(\d+) (.*)""".r

  def readResponse(bstr: ByteString): Seq[(Int, String)] = {
    val lines = bstr.utf8String.split('\n')
      .map(_.stripSuffix("\r"))
      .filterNot(_.isEmpty)
      .map {
        case r1(c, msg) => (c.toInt, msg)
        case r2(c, msg) => (c.toInt, msg)
        case x@_ => throw TorException(s"unknown response line format: `$x`")
      }
      .toIndexedSeq
    if (!ok(lines)) {
      throw TorException(s"server returned error: ${status(lines)} ${reason(lines)}")
    }
    lines
  }

  def ok(res: Seq[(Int, String)]): Boolean = status(res) == 250

  def status(res: Seq[(Int, String)]): Int = res.lastOption.map(_._1).getOrElse(-1)

  def reason(res: Seq[(Int, String)]): String = res.lastOption.map(_._2).getOrElse("Unknown error")

  private val r = """([^=]+)=(.+)""".r

  def parseResponse(lines: Seq[(Int, String)]): Map[String, String] = {
    lines.flatMap {
      case (_, message) =>
        message.split(" ")
          .collect {
            case r(k, v) => (k, v)
          }
    }.toMap
  }

  def hmacSHA256(key: ByteVector, message: ByteVector): ByteVector = {
    val mac = Mac.getInstance("HmacSHA256")
    val secretKey = new SecretKeySpec(key.toArray, "HmacSHA256")
    mac.init(secretKey)
    ByteVector.view(mac.doFinal(message.toArray))
  }
}