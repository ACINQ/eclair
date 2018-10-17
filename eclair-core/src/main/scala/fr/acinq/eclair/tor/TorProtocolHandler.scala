package fr.acinq.eclair.tor

import java.io._
import java.nio.file.{Files, Paths}
import java.security.SecureRandom

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash, Status}
import akka.io.Tcp.Connected
import akka.util.ByteString
import fr.acinq.eclair.tor.TorProtocolHandler.ProtocolVersion
import fr.acinq.eclair.randomBytes
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.bind.DatatypeConverter

import scala.concurrent.Promise
import scala.util.Random

case class TorException(msg: String) extends RuntimeException(msg)

class TorProtocolHandler(protocolVersion: ProtocolVersion,
                         privateKeyPath: String,
                         virtualPort: Int,
                         targetPorts: Seq[Int],
                         onionAdded: Option[Promise[OnionAddress]],
                         clientNonce: Option[Array[Byte]]
                        ) extends Actor with Stash with ActorLogging {

  import TorProtocolHandler._

  private val ServerKey: Array[Byte] = "Tor safe cookie authentication server-to-controller hash".getBytes()
  private val ClientKey: Array[Byte] = "Tor safe cookie authentication controller-to-server hash".getBytes()

  private var receiver: ActorRef = _

  private var protoInfo: ProtocolInfo = _
  private var clientHash: Array[Byte] = _
  private var keyStr: String = _
  private var portStr: String = _

  private var address: Option[OnionAddress] = None

  private val nonce: Array[Byte] = clientNonce.getOrElse(randomBytes(32))

  override def receive: Receive = {
    case Connected(_, _) =>
      receiver = sender()
      context become protocolInfo
      self ! SendNextCommand
  }

  def protocolInfo: Receive = {
    case SendNextCommand =>
      sendCommand("PROTOCOLINFO 1")
    case data: ByteString => handleExceptions {
      val res = parseResponse(readResponse(data))
      protoInfo = ProtocolInfo(
        methods = res.getOrElse("METHODS", throw TorException("Tor auth methods not found")),
        cookieFile = unquote(res.getOrElse("COOKIEFILE", throw TorException("Tor cookie file not found"))),
        version = unquote(res.getOrElse("Tor", throw TorException("Tor version not found"))))
      log.info(s"Tor version ${protoInfo.version}")
      if (!protocolVersion.supportedBy(protoInfo.version)) {
        throw TorException(s"Tor version ${protoInfo.version} does not support protocol $protocolVersion")
      }
      context become authChallenge
      self ! SendNextCommand
    }
  }

  def authChallenge: Receive = {
    case SendNextCommand =>
      sendCommand(s"AUTHCHALLENGE SAFECOOKIE ${hex(nonce)}")
    case data: ByteString => handleExceptions {
      val res = parseResponse(readResponse(data))
      clientHash = computeClientHash(
        res.getOrElse("SERVERHASH", throw TorException("Tor server hash not found")),
        res.getOrElse("SERVERNONCE", throw TorException("Tor server nonce not found"))
      )
      context become authenticate
      self ! SendNextCommand
    }
  }

  def authenticate: Receive = {
    case SendNextCommand =>
      sendCommand(s"AUTHENTICATE ${hex(clientHash)}")
    case data: ByteString => handleExceptions {
      readResponse(data)
      keyStr = computeKey
      portStr = computePort
      context become addOnion
      self ! SendNextCommand
    }
  }

  def addOnion: Receive = {
    case SendNextCommand =>
      val cmd = s"ADD_ONION $keyStr $portStr"
      sendCommand(cmd)
    case data: ByteString => handleExceptions {
      val res = readResponse(data)
      if (ok(res)) {
        val serviceId = processOnionResponse(parseResponse(res))
        address = Some(protocolVersion match {
          case V2 => OnionAddressV2(serviceId, virtualPort)
          case V3 => OnionAddressV3(serviceId, virtualPort)
        })
        onionAdded.foreach(_.success(address.get))
        log.debug(s"Onion address: ${address.get}")
      }
    }
  }

  override def unhandled(message: Any): Unit = message match {
    case GetOnionAddress =>
      sender() ! address
  }

  private def handleExceptions[T](f: => T): Unit = try {
    f
  } catch {
    case e: Exception =>
      log.error(e, "Tor error: ")
      sender ! Status.Failure(e)
  }

  private def processOnionResponse(res: Map[String, String]): String = {
    val serviceId = res.getOrElse("ServiceID", throw TorException("Tor service ID not found"))
    val privateKey = res.get("PrivateKey")
    privateKey.foreach(writeString(privateKeyPath, _))
    serviceId
  }

  private def computeKey: String = {
    if (Files.exists(Paths.get(privateKeyPath))) {
      readString(privateKeyPath)
    } else {
      protocolVersion match {
        case V2 => "NEW:RSA1024"
        case V3 => "NEW:ED25519-V3"
      }
    }
  }

  private def computePort: String = {
    if (targetPorts.isEmpty) {
      s"Port=$virtualPort,$virtualPort"
    } else {
      targetPorts.map(p => s"Port=$virtualPort,$p").mkString(" ")
    }
  }

  private def computeClientHash(serverHash: String, serverNonce: String): Array[Byte] = {
    val decodedServerHash = unhex(serverHash)
    if (decodedServerHash.length != 32)
      throw TorException("Invalid server hash length")

    val decodedServerNonce = unhex(serverNonce)
    if (decodedServerNonce.length != 32)
      throw TorException("Invalid server nonce length")

    val cookie = readBytes(protoInfo.cookieFile, 32)

    val message = cookie ++ nonce ++ decodedServerNonce

    val computedServerHash = hex(hmacSHA256(ServerKey, message))
    if (computedServerHash != serverHash) {
      throw TorException("Unexpected server hash")
    }

    hmacSHA256(ClientKey, message)
  }

  private def sendCommand(cmd: String): Unit = {
    receiver ! ByteString(s"$cmd\r\n")
  }
}

object TorProtocolHandler {
  def props(version: String,
            privateKeyPath: String,
            virtualPort: Int,
            targetPorts: Seq[Int] = Seq(),
            onionAdded: Option[Promise[OnionAddress]] = None,
            nonce: Option[Array[Byte]] = None
           ): Props =
    Props(new TorProtocolHandler(ProtocolVersion(version), privateKeyPath, virtualPort, targetPorts, onionAdded, nonce))

  val MinV3Version = "0.3.3.6"

  sealed trait ProtocolVersion {
    def supportedBy(torVersion: String): Boolean
  }

  case object V2 extends ProtocolVersion {
    override def supportedBy(torVersion: String): Boolean = true
  }

  case object V3 extends ProtocolVersion {
    override def supportedBy(torVersion: String): Boolean = Version(torVersion) >= Version(MinV3Version)
  }

  object ProtocolVersion {
    def apply(s: String): ProtocolVersion = s match {
      case "v2" | "V2" => V2
      case "v3" | "V3" => V3
      case _ => throw TorException(s"Unknown protocol version `$s`")
    }
  }

  case object SendNextCommand

  case object GetOnionAddress

  case object AuthCompleted

  case object AuthFailed

  case class OnionAdded(onionAddress: OnionAddress)

  case class Error(ex: Throwable)

  case class ProtocolInfo(methods: String, cookieFile: String, version: String)

  def readBytes(filename: String, n: Int): Array[Byte] = {
    val bytes = Array.ofDim[Byte](1024)
    val s = new FileInputStream(filename)
    try {
      if (s.read(bytes) != n)
        throw TorException("Invalid file length")
      bytes.take(n)
    } finally {
      s.close()
    }
  }

  def writeBytes(filename: String, bytes: Array[Byte]): Unit = {
    val s = new FileOutputStream(filename)
    try {
      s.write(bytes)
    } finally {
      s.close()
    }
  }


  def readString(filename: String): String = {
    val r = new BufferedReader(new FileReader(filename))
    try {
      r.readLine()
    } finally {
      r.close()
    }
  }

  def writeString(filename: String, string: String): Unit = {
    val w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(filename)))
    try {
      w.print(string)
    } finally {
      w.close()
    }
  }

  def unquote(s: String): String = s
    .stripSuffix("\"")
    .stripPrefix("\"")
    .replace("""\\""", """\""")
    .replace("""\"""", "\"")

  def hex(buf: Seq[Byte]): String = buf.map("%02X" format _).mkString

  def unhex(hexString: String): Array[Byte] = DatatypeConverter.parseHexBinary(hexString)

  private val r1 = """(\d+)\-(.*)""".r
  private val r2 = """(\d+) (.*)""".r

  def readResponse(bstr: ByteString): Seq[(Int, String)] = {
    val lines = bstr.utf8String.split('\n')
      .map(_.stripSuffix("\r"))
      .filterNot(_.isEmpty)
      .map {
        case r1(c, msg) => (c.toInt, msg)
        case r2(c, msg) => (c.toInt, msg)
        case x@_ => throw TorException(s"Unknown response line format: `$x`")
      }
    if (!ok(lines)) {
      throw TorException(s"Tor server returned error: ${status(lines)} ${reason(lines)}")
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

  def hmacSHA256(key: Array[Byte], message: Array[Byte]): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    val secretKey = new SecretKeySpec(key, "HmacSHA256")
    mac.init(secretKey)
    mac.doFinal(message)
  }
}